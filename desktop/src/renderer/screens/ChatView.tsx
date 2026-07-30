// One open chat: digest messages (the rendered truth) + the live streaming
// layer (partial answer text, transient tool activity) + the composer.
// Send-while-running queues server-side; Stop cancels; detach never cancels.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Message } from '../../shared/api/types'
import { useChatStream } from '../hooks/useChatStream'
import { call } from '../lib/ipc'
import { MarkdownView } from '../components/markdown/MarkdownView'
import { CodeCard } from '../components/transcript/CodeCard'
import { Composer } from '../components/composer/Composer'

function DigestMessage({ m }: { m: Message }): React.JSX.Element | null {
  switch (m.type) {
    case 'user':
      return (
        <div className="msg msg-user">
          <div className="bubble bubble-user">{m.text ?? ''}</div>
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
      <header className="view-header">
        <div className="view-title">{detail?.title ?? 'Chat'}</div>
        <div className="view-sub">
          <span className={`mode-chip mode-${detail?.mode ?? 'ask'}`}>
            {(detail?.mode ?? 'ask').toUpperCase()}
          </span>
          {detail?.model !== null && detail?.model !== undefined ? (
            <span className="view-meta">{detail.model}</span>
          ) : null}
          {detail?.effort !== null && detail?.effort !== undefined ? (
            <span className="view-meta">{detail.effort}</span>
          ) : null}
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
