// One open chat: digest messages (the rendered truth) + the live streaming
// layer (partial answer text, transient tool activity) + the composer.
// Send-while-running queues server-side; Stop cancels; detach never cancels.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Message } from '../../shared/api/types'
import { displayText } from '../../shared/core/attachmentMarker'
import { useChatStream } from '../hooks/useChatStream'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { MarkdownView } from '../components/markdown/MarkdownView'
import { CodeCard } from '../components/transcript/CodeCard'
import { Composer } from '../components/composer/Composer'
import { InputDialog } from '../components/common/Dialog'

function DigestMessage({ m }: { m: Message }): React.JSX.Element | null {
  switch (m.type) {
    case 'user':
      return (
        <div className="msg msg-user">
          <div className="bubble bubble-user">{displayText(m.text ?? '')}</div>
        </div>
      )
    case 'assistant':
      return (
        <div className="msg msg-assistant">
          <MarkdownView text={m.text ?? ''} />
        </div>
      )
    case 'tool':
      return (
        <div className="msg msg-tool">
          <span className="tool-chip">
            ⚙ {m.name ?? 'tool'}
            {m.input !== null && m.input !== '' ? <span className="tool-input"> {m.input}</span> : null}
          </span>
        </div>
      )
    case 'result': {
      const secs = m.durationMs !== null ? `${Math.round(m.durationMs / 1000)}s` : null
      return (
        <div className="msg msg-meta">
          {m.ok === false ? 'Run failed' : 'Done'}
          {secs !== null ? ` · ${secs}` : ''}
        </div>
      )
    }
    case 'error':
      return <div className="msg msg-error">{m.text ?? 'error'}</div>
    default:
      return null
  }
}

export function ChatView({ chatId }: { chatId: string }): React.JSX.Element {
  const live = useChatStream(chatId)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickRef = useRef(true)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [draftSeed, setDraftSeed] = useState<string | null>(null)
  const [models, setModels] = useState<{ id: string; display: string }[]>([])
  const [renaming, setRenaming] = useState(false)
  const refreshChats = useApp((s) => s.refreshChats)

  useEffect(() => {
    void call('host.models')
      .then(setModels)
      .catch(() => {})
  }, [])

  // Follow the newest content unless the reader scrolled up — a latch, broken
  // only by the user's own scroll, re-armed when they return to the bottom.
  useEffect(() => {
    const el = scrollRef.current
    if (el && stickRef.current) el.scrollTop = el.scrollHeight
  })

  const onScroll = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  }, [])

  useEffect(() => {
    setSuggestions([])
    if (live.running) return
    let alive = true
    void call('chats.suggestions', chatId)
      .then((s) => {
        if (alive) setSuggestions(s.suggestions)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [chatId, live.running])

  const transientTool = useMemo(() => {
    for (let i = live.events.length - 1; i >= 0; i -= 1) {
      const ev = live.events[i]!
      if (ev.type === 'tool' || ev.type === 'result' || ev.type === 'done') return null
      if (ev.type === 'tool_start') return ev.name
    }
    return null
  }, [live.events])

  const detail = live.detail
  return (
    <div className="chat-view">
      {renaming ? (
        <InputDialog
          title="Rename chat"
          initial={detail?.title ?? ''}
          onCancel={() => setRenaming(false)}
          onSubmit={(to) => {
            setRenaming(false)
            void call('chats.patch', chatId, { title: to }).then(() => {
              void live.refresh()
              void refreshChats()
            })
          }}
        />
      ) : null}
      <header className="view-header">
        <div className="view-title-row">
          <div className="view-title" title={detail?.title ?? 'Chat'}>
            {detail?.title ?? 'Chat'}
          </div>
          <div className="view-actions">
            <button type="button" title="Rename this chat" onClick={() => setRenaming(true)}>
              Rename
            </button>
          </div>
        </div>
        {/* One group, one verb: everything here decides what the NEXT turn runs
            with. Applying mid-run is fine — the daemon fixes flags at spawn. */}
        <div className="options-bar">
          <span className="options-label">Next turn</span>
          <div className="seg" role="group" aria-label="Mode">
            {(['ask', 'act'] as const).map((m) => (
              <button
                key={m}
                type="button"
                className={`seg-btn ${(detail?.mode ?? 'ask') === m ? 'seg-on' : ''} ${m === 'act' ? 'seg-act' : ''}`}
                title={
                  m === 'ask'
                    ? 'Ask — reasoning, memory, and reads. No shell, no edits.'
                    : 'Act — can run commands and change files.'
                }
                onClick={() => {
                  if ((detail?.mode ?? 'ask') === m) return
                  void call('chats.patch', chatId, { mode: m }).then(() => {
                    void live.refresh()
                    void refreshChats()
                  })
                }}
              >
                {m === 'ask' ? 'Ask' : 'Act'}
              </button>
            ))}
          </div>
          <select
            className="picker"
            aria-label="Model"
            value={detail?.model ?? ''}
            onChange={(e) => {
              void call('chats.patch', chatId, { model: e.target.value }).then(() => live.refresh())
            }}
          >
            <option value="">Default model</option>
            {models.map((m) => (
              <option key={m.id} value={m.id}>
                {m.display}
              </option>
            ))}
          </select>
          <select
            className="picker"
            aria-label="Effort"
            value={detail?.effort ?? ''}
            onChange={(e) => {
              void call('chats.patch', chatId, { effort: e.target.value }).then(() => live.refresh())
            }}
          >
            <option value="">Default effort</option>
            {['low', 'medium', 'high', 'xhigh', 'max'].map((e2) => (
              <option key={e2} value={e2}>
                {e2}
              </option>
            ))}
          </select>
          {live.running ? <span className="options-note">applies to your next message</span> : null}
        </div>
      </header>

      {live.error !== null ? <div className="banner banner-error">{live.error}</div> : null}
      {live.queuedNotice !== null ? (
        <div className="banner banner-queued">
          Queued — will be delivered when the current run finishes
        </div>
      ) : null}

      <div className="msg-scroll" ref={scrollRef} onScroll={onScroll}>
        {detail?.messages.map((m, i) => <DigestMessage key={i} m={m} />)}
        {live.partialText !== '' ? (
          <div className="msg msg-assistant msg-partial">
            <MarkdownView text={live.partialText} />
          </div>
        ) : null}
        {live.running ? (
          <div className="msg msg-meta working">
            <span className="pulse-dot" /> {transientTool !== null ? `${transientTool}…` : 'Working…'}
          </div>
        ) : null}
      </div>

      {suggestions.length > 0 && !live.running ? (
        <div className="suggestion-row">
          {suggestions.map((s) => (
            <button key={s} type="button" className="suggestion-chip" onClick={() => setDraftSeed(s)}>
              {s}
            </button>
          ))}
        </div>
      ) : null}

      <Composer
        draftKey={`chat:${chatId}`}
        running={live.running}
        seedText={draftSeed}
        onSeedConsumed={() => setDraftSeed(null)}
        onSend={(text) => void live.send(text)}
        onStop={() => void live.cancel()}
      />
    </div>
  )
}

export { CodeCard }
