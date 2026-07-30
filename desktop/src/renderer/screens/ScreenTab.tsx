// The Screen tab: the live pane as a real character grid. useScreenPoll fits
// cols × rows to the box and streams frames; TerminalCanvas paints them;
// KeyRow covers the keys a composer cannot say; PromptCard turns a detected
// choice prompt into buttons; live typing sends keystrokes straight to the
// pane through liveInput's ordered queue, and localEcho renders them at the
// cursor before the pane confirms. Scrollback (when the pane has any —
// Claude Code runs on the alternate screen, which has none) loads above the
// live grid. The shared session composer below this tab handles ordinary text
// entry; this tab only ever speaks keys.

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AnswerResult } from '../../shared/api/types'
import { toWire, type Op } from '../../shared/core/liveInput'
import {
  backspace, emptyEcho, frame, otherKey, typed, visible, type Cursor, type Echo,
} from '../../shared/core/localEcho'
import { call } from '../lib/ipc'
import { useScreenPoll } from '../hooks/useScreenPoll'
import { TerminalCanvas } from '../components/terminal/TerminalCanvas'
import { KeyRow } from '../components/terminal/KeyRow'
import { PromptCard } from '../components/terminal/PromptCard'
import { eventToOp } from '../components/terminal/keymap'
import '../components/terminal/terminal.css'

const TEXT_FLUSH_MS = 150
const HISTORY_LINES = 2000

export function ScreenTab({
  name,
  active = true,
}: {
  name: string
  active?: boolean
}): React.JSX.Element {
  const { screen, fontPx, setFontPx, forceFit, containerRef } = useScreenPoll(name, active)
  const [scrollback, setScrollback] = useState<string[] | null>(null)
  const [loadingHist, setLoadingHist] = useState(false)
  const [liveTyping, setLiveTyping] = useState(false)
  // Optimistic echo state for live typing; the rules live in localEcho (pure).
  const [echo, setEcho] = useState<Echo>(emptyEcho)
  const prevCursorRef = useRef<Cursor | null>(null)

  const boxElRef = useRef<HTMLDivElement | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickRef = useRef(true)
  const liveRef = useRef<HTMLDivElement | null>(null)

  // ------------------------------------------------- ordered key/text sending
  // One queue + one drainer (liveInput's design): independent requests are not
  // ordered, so "ls" typed fast enough could land as "sl". Chip keys go
  // through the same queue so they cannot overtake in-flight typed text.
  const opsRef = useRef<Op[]>([])
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const drainingRef = useRef(false)

  const drain = useCallback(async (): Promise<void> => {
    if (drainingRef.current) return
    drainingRef.current = true
    try {
      while (opsRef.current.length > 0) {
        const batch = opsRef.current
        opsRef.current = []
        for (const body of toWire(batch)) {
          await call('sessions.keys', name, body)
        }
      }
    } catch {
      // Pane gone or transient network failure: drop the burst rather than
      // retry-loop keystrokes into a session that may have moved on.
      opsRef.current = []
    } finally {
      drainingRef.current = false
    }
  }, [name])

  const queueOp = useCallback(
    (op: Op): void => {
      opsRef.current.push(op)
      if (op.kind === 'key') {
        // Named keys flush at once — Enter and Escape must feel instant.
        if (flushTimerRef.current !== null) {
          clearTimeout(flushTimerRef.current)
          flushTimerRef.current = null
        }
        void drain()
      } else if (flushTimerRef.current === null) {
        // Printable bursts accumulate briefly and merge into one {text} body.
        flushTimerRef.current = setTimeout(() => {
          flushTimerRef.current = null
          void drain()
        }, TEXT_FLUSH_MS)
      }
    },
    [drain],
  )

  const onLiveKey = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>): void => {
      const op = eventToOp(e)
      if (op === null) return
      // The pane owns this key now: the browser must not scroll, tab focus, etc.
      e.preventDefault()
      e.stopPropagation()
      // Each keystroke ALSO feeds the echo, which is what makes typing feel
      // immediate: render now, reconcile when the pane confirms. Only plain
      // text is predictable — backspace eats one pending character, and every
      // other named key mutes until a frame settles the question.
      setEcho((prev) =>
        op.kind === 'text'
          ? typed(prev, op.text)
          : op.keys.every((k) => k === 'BSpace')
            ? op.keys.reduce((acc) => backspace(acc), prev)
            : otherKey(prev),
      )
      queueOp(op)
    },
    [queueOp],
  )

  // Every authoritative frame consumes exactly what its cursor advance
  // explains, and always lifts a mute. In the LAYOUT phase so the re-render it
  // schedules lands before the browser paints: a passive effect here would
  // show the just-confirmed character twice for one frame.
  useLayoutEffect(() => {
    if (screen === null) return
    const cur: Cursor = [screen.cursorX, screen.cursorY]
    // Read the previous cursor into a LOCAL before advancing the ref: a state
    // updater runs when React calls it, not when it is written, so
    // `prevCursorRef.current` inside the closure would already be `cur` — every
    // frame would explain an advance of zero and consume nothing. That is not a
    // subtle degradation: the echo simply never clears, which is the ghost the
    // whole module exists to prevent (seen on screen before this line existed).
    const prev = prevCursorRef.current
    prevCursorRef.current = cur
    setEcho((e) => frame(e, prev, cur))
  }, [screen])

  // ----------------------------------------------------------- per-session UI
  useEffect(() => {
    setScrollback(null)
    setLoadingHist(false)
    setLiveTyping(false)
    setEcho(emptyEcho)
    prevCursorRef.current = null
    stickRef.current = true
    opsRef.current = []
  }, [name])

  useEffect(() => {
    if (liveTyping) liveRef.current?.focus()
    // Leaving or entering live typing drops any pending guess: nothing that was
    // typed before the mode changed can be predicted across it.
    setEcho(emptyEcho)
    prevCursorRef.current = null
  }, [liveTyping])

  useEffect(
    () => () => {
      if (flushTimerRef.current !== null) clearTimeout(flushTimerRef.current)
    },
    [],
  )

  // Ctrl+scroll over the pane zooms the font (persisted via settings.update in
  // the hook). Native non-passive listener: React registers wheel passively,
  // so preventDefault (needed to stop page zoom/scroll) has no effect there.
  useEffect(() => {
    const el = boxElRef.current
    if (el === null) return
    const onWheel = (e: WheelEvent): void => {
      if (!e.ctrlKey) return
      e.preventDefault()
      setFontPx(fontPx + (e.deltaY < 0 ? 1 : -1))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [fontPx, setFontPx])

  // Follow the live grid unless the reader scrolled up into history — the
  // same latch ChatView uses, re-armed when they return to the bottom.
  useEffect(() => {
    const el = scrollRef.current
    if (el !== null && stickRef.current) el.scrollTop = el.scrollHeight
  })

  const onScroll = useCallback((): void => {
    const el = scrollRef.current
    if (el === null) return
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  }, [])

  // The hook measures the SCROLLER's content box (excludes the scrollbar);
  // the wheel/refocus handlers live on the outer box.
  const setScrollEl = useCallback(
    (el: HTMLDivElement | null): void => {
      scrollRef.current = el
      containerRef(el)
    },
    [containerRef],
  )

  const loadHistory = useCallback((): void => {
    setLoadingHist(true)
    void call('sessions.screen.once', name, { history: HISTORY_LINES })
      .then((s) => setScrollback(s.scrollback))
      .catch(() => {})
      .finally(() => setLoadingHist(false))
  }, [name])

  const sendChipKey = useCallback((key: string): void => queueOp({ kind: 'key', keys: [key] }), [
    queueOp,
  ])

  const answer = useCallback(
    (body: { option?: number; options?: number[]; fingerprint?: string }): Promise<AnswerResult> =>
      call('sessions.answer', name, body),
    [name],
  )

  const refocusLive = useCallback((): void => {
    if (liveTyping) liveRef.current?.focus()
  }, [liveTyping])

  const statusText = [screen?.spinner, screen?.transientLine]
    .filter((s): s is string => s !== null && s !== undefined && s !== '')
    .join('  ·  ')

  // Scrollback affordance: a full-screen program (Claude Code) runs on the
  // alternate screen, which keeps no scrollback at all — offering a load
  // button there would be a button that silently does nothing.
  const historyHeader =
    screen === null ? null : screen.altScreen ? (
      <div className="term-topnote">
        Full-screen program — no scrollback exists. The Conversation tab has the whole session.
      </div>
    ) : screen.historySize > 0 && scrollback === null ? (
      <button type="button" className="term-load-btn" disabled={loadingHist} onClick={loadHistory}>
        {loadingHist ? 'Loading…' : `Load history (${screen.historySize} lines)`}
      </button>
    ) : null

  return (
    <div className="screen-tab">
      {screen?.resizeBlocked === true ? (
        <div className="banner banner-warn">
          Another client is attached — showing the pane at its own size.{' '}
          <button type="button" onClick={forceFit}>
            Fit anyway
          </button>
        </div>
      ) : null}

      {historyHeader !== null ? <div className="term-header">{historyHeader}</div> : null}

      <div className="term-box" ref={boxElRef} onMouseUp={refocusLive}>
        <div className="term-scroll" ref={setScrollEl} onScroll={onScroll}>
          <div className="term-content">
            {screen === null ? (
              <div className="term-topnote">Connecting to the pane…</div>
            ) : (
              <>
                {scrollback !== null && scrollback.length > 0 ? (
                  <>
                    <TerminalCanvas
                      lines={scrollback}
                      cols={screen.width}
                      fontPx={fontPx}
                      cursor={null}
                    />
                    <hr className="term-hist-divider" />
                  </>
                ) : null}
                <TerminalCanvas
                  lines={screen.lines}
                  cols={screen.width}
                  fontPx={fontPx}
                  cursor={{ x: screen.cursorX, y: screen.cursorY }}
                  echo={liveTyping && visible(echo) ? echo.text : ''}
                />
              </>
            )}
          </div>
        </div>
        {statusText !== '' ? <div className="term-status">{statusText}</div> : null}
      </div>

      {screen?.prompt !== null && screen?.prompt !== undefined ? (
        <PromptCard
          key={screen.prompt.fingerprint ?? screen.prompt.question}
          prompt={screen.prompt}
          onAnswer={answer}
        />
      ) : null}

      <KeyRow onKey={sendChipKey} live={liveTyping} onToggleLive={() => setLiveTyping((v) => !v)} />

      {liveTyping ? (
        <>
          <div
            ref={liveRef}
            className="term-live-capture"
            tabIndex={0}
            onKeyDown={onLiveKey}
            aria-label={`Live typing into ${name}`}
          />
          <div className="term-live-strip" onClick={() => setLiveTyping(false)}>
            <span className="term-live-strip-msg">
              ⌨ Typing straight into {name} — every key goes to the pane
            </span>
            <span className="term-live-strip-done">Done</span>
          </div>
        </>
      ) : null}
    </div>
  )
}
