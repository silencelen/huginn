// A detected Claude Code choice prompt as buttons — the biggest remote-client
// win over a raw terminal (answering "1/2/3" without typing digits into a TUI
// that is redrawing). Ported from the Android PromptCard: multi-select rows
// keep LOCAL checkbox state seeded from what the dialog already shows (the
// question may be half-answered in tmux; starting blank would discard those),
// and nothing reaches the pane until Answer. Every answer carries the host's
// fingerprint so the daemon can refuse (409, reason gone|changed) to type into
// a pane that has moved on — that refusal is an ordinary outcome, reported
// inline and never retried. The parent keys this component per question, so
// state resets when the question changes.

import { useState } from 'react'
import type { AnswerResult, PanePrompt } from '../../../shared/api/types'

export function PromptCard({
  prompt,
  onAnswer,
}: {
  prompt: PanePrompt
  onAnswer: (body: {
    option?: number
    options?: number[]
    fingerprint?: string
  }) => Promise<AnswerResult>
}): React.JSX.Element {
  const [chosen, setChosen] = useState<ReadonlySet<number>>(
    () => new Set(prompt.options.filter((o) => o.checked === true).map((o) => o.number)),
  )
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const send = (body: { option?: number; options?: number[] }): void => {
    if (busy) return
    setBusy(true)
    setNote(null)
    const withFp =
      prompt.fingerprint !== null ? { ...body, fingerprint: prompt.fingerprint } : body
    void onAnswer(withFp)
      .then((r) => {
        if (!r.ok) {
          setNote(
            r.reason !== null
              ? 'The question moved on — the pane is no longer asking this.'
              : (r.error ?? 'Answer failed'),
          )
        }
      })
      .catch((e: unknown) => setNote(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false))
  }

  const toggle = (n: number): void => {
    setChosen((prev) => {
      const next = new Set(prev)
      if (next.has(n)) next.delete(n)
      else next.add(n)
      return next
    })
  }

  return (
    <div className="term-prompt">
      <div className="term-prompt-q">
        {prompt.question !== '' ? prompt.question : 'Claude is asking'}
      </div>
      <div className="term-prompt-opts">
        {prompt.options.map((o) =>
          prompt.multiSelect && o.checked !== null ? (
            <label
              key={o.number}
              className={`prompt-check${chosen.has(o.number) ? ' prompt-check-on' : ''}`}
            >
              <input
                type="checkbox"
                checked={chosen.has(o.number)}
                disabled={busy}
                onChange={() => toggle(o.number)}
              />
              <span>{o.label}</span>
            </label>
          ) : (
            <button
              key={o.number}
              type="button"
              className={`prompt-option${o.selected && !prompt.multiSelect ? ' prompt-selected' : ''}`}
              disabled={busy}
              onClick={() => send({ option: o.number })}
            >
              {o.number}. {o.label}
            </button>
          ),
        )}
        {prompt.multiSelect ? (
          <button
            type="button"
            className="prompt-answer"
            disabled={busy}
            onClick={() => send({ options: [...chosen].sort((a, b) => a - b) })}
          >
            {chosen.size === 0 ? 'Answer with none selected' : `Answer with ${chosen.size} selected`}
          </button>
        ) : null}
      </div>
      {note !== null ? <div className="prompt-note">{note}</div> : null}
    </div>
  )
}
