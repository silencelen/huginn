// The session's Conversation tab: the rendered Claude transcript, polled and
// tail-followed. The SessionView header already shows name/model/permission
// mode, so the meta line here carries only what the transcript adds (model as
// a person reads it, effort, branch).
//
// It also answers. A question is the one moment a reader must act, and making
// them find the Screen tab to click "1" — while reading the very question in
// the transcript — is a tab switch charged for nothing. The Android app put the
// buttons in BOTH tabs deliberately; this is the same PromptCard the Screen tab
// renders, pinned under the transcript, wired to the same check-and-act answer.

import { useCallback, useEffect, useState } from 'react'
import type { AnswerResult, PanePrompt } from '../../shared/api/types'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { useTranscript } from '../hooks/useTranscript'
import { TranscriptList } from '../components/transcript/TranscriptList'
import { PromptCard } from '../components/terminal/PromptCard'
import '../components/terminal/terminal.css'

/**
 * How long to wait after an accepted answer before re-reading the pane. A
 * multi-question dialog asks the next question WITHOUT leaving `attention`, so
 * there is no state edge to ride; the TUI needs a beat to redraw first.
 */
const AFTER_ANSWER_MS = 700

export function ConversationTab({
  name,
  active = true,
}: {
  name: string
  active?: boolean
}): React.JSX.Element {
  const t = useTranscript('session', name, active)
  // The session list the store already polls (and the watch stream already
  // refreshes the moment a pane starts asking) — read-only, no extra traffic,
  // and fresher than the transcript page's own `state`.
  // Selected as two primitives rather than as the row: the store replaces the
  // whole list every poll, so a selector returning the object would re-render
  // this view (and the transcript under it) every five seconds for nothing.
  const state = useApp((s) => s.sessions?.find((x) => x.name === name)?.state ?? null)
  // Re-entering `attention` from `attention` is a real event — a dialog can ask
  // its next question without the session ever leaving the state — and the word
  // alone cannot show it. `stateSince` moves whenever the host restamps the
  // state, so it is the edge the word hides.
  const stateSince = useApp((s) => s.sessions?.find((x) => x.name === name)?.stateSince ?? null)
  const [prompt, setPrompt] = useState<PanePrompt | null>(null)
  const [reload, setReload] = useState(0)

  // The live question, read off the pane ONLY on the attention edge (and once
  // more after an answer). A card in the transcript must not turn a reading
  // view into a second pane poll — the Screen tab is what watches the pane.
  useEffect(() => {
    if (state !== 'attention') {
      setPrompt(null)
      return
    }
    let alive = true
    void call('sessions.screen.once', name, {})
      .then((s) => {
        if (alive) setPrompt(s.prompt)
      })
      .catch(() => {
        if (alive) setPrompt(null)
      })
    return () => {
      alive = false
    }
  }, [name, state, stateSince, reload])

  useEffect(() => {
    setPrompt(null)
  }, [name])

  const answer = useCallback(
    async (body: {
      option?: number
      options?: number[]
      fingerprint?: string
    }): Promise<AnswerResult> => {
      const r = await call('sessions.answer', name, body)
      // A 409 (reason gone|changed) is an ordinary outcome — the click was
      // right when it was offered. PromptCard says so; nothing is retried, and
      // the card stays put because the next edge will replace it.
      if (r.ok) {
        setPrompt(null)
        await new Promise<void>((res) => setTimeout(res, AFTER_ANSWER_MS))
        setReload((n) => n + 1)
      }
      return r
    },
    [name],
  )

  if (t.neverRan) {
    return (
      <div className="pane-placeholder">
        No conversation yet — this session has not prompted Claude.
      </div>
    )
  }
  if (t.page === null) {
    return (
      <div className="pane-placeholder">
        {t.error !== null ? `Transcript unavailable: ${t.error}` : 'Loading conversation…'}
      </div>
    )
  }

  const meta = [t.page.modelDisplay, t.page.effort, t.page.gitBranch].filter(
    (x): x is string => x !== null && x !== '',
  )
  return (
    <div className="conversation-tab">
      {meta.length > 0 ? <div className="transcript-meta">{meta.join(' · ')}</div> : null}
      {t.error !== null ? (
        <div className="banner banner-warn">transcript refresh failing: {t.error}</div>
      ) : null}
      <TranscriptList page={t.page} />
      {prompt !== null ? (
        <PromptCard
          key={prompt.fingerprint ?? prompt.question}
          prompt={prompt}
          onAnswer={answer}
        />
      ) : null}
    </div>
  )
}
