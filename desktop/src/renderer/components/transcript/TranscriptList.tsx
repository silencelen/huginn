// Renders a merged TranscriptPage as grouped rows, ported from the Android
// app's TranscriptView.kt — the reference for how each event kind reads.
// Grouping and row identity come from shared/core/transcriptGroups (group() +
// keys()); this file only maps rows to elements. Subagent runs fold into one
// openable card and its steps render through the same event renderers, so a
// new event kind means changing this file only.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { TranscriptEvent, TranscriptPage } from '../../../shared/api/types'
import { group, keys, type SubagentsRow } from '../../../shared/core/transcriptGroups'
import { highlight, langForTool, resultLang } from '../../../shared/core/syntax'
import { MarkdownView } from '../markdown/MarkdownView'
import './transcript.css'

/**
 * Inline fail-soft syntax colouring for tool input/result panes — a lighter
 * sibling of CodeCard (which stays the renderer for fenced code inside
 * markdown). A span that falls outside the string is dropped, never trusted;
 * the code text itself always renders verbatim.
 */
function HlText({ code, lang }: { code: string; lang: string | null }): React.JSX.Element {
  const parts: React.ReactNode[] = []
  let pos = 0
  for (const s of highlight(code, lang)) {
    if (s.start < pos || s.end <= s.start || s.end > code.length) continue
    if (s.start > pos) parts.push(code.slice(pos, s.start))
    parts.push(
      <span key={s.start} className={`tok-${s.tok}`}>
        {code.slice(s.start, s.end)}
      </span>,
    )
    pos = s.end
  }
  if (pos < code.length) parts.push(code.slice(pos))
  return <>{parts}</>
}

const isBlank = (s: string | null): boolean => s === null || s.trim() === ''

/**
 * ⚠ failure, ↳ delegated work, ⚙ everything else — mirrors the Android icons
 * (ErrorOutline / AccountTree / Build). The spec'd ⛭ and ⧉ have ZERO font
 * coverage on the Linux fleet (fc-list charset probe) and render as tofu
 * boxes, so the covered lookalikes are used instead.
 */
const glyphFor = (ev: TranscriptEvent): string => {
  if (ev.ok === false) return '⚠'
  if (ev.name === 'Task' || ev.name === 'Agent' || ev.name === 'Workflow') return '↳'
  return '⚙'
}

/**
 * The chosen option(s), dug out of the result's JSON-ish text; the raw head of
 * the result when nothing matches. Ported from TranscriptView.kt's
 * answeredSummary — results look like {"questions":[…"answer":"Blue"…]} and
 * the answers are the point.
 */
const answeredSummary = (result: string): string => {
  const answers = [...result.matchAll(/"answer"\s*:\s*"((?:[^"\\]|\\.)*)"/g)].map(
    (m) => m[1] ?? '',
  )
  return answers.length > 0 ? answers.join('  ·  ') : result.slice(0, 120)
}

function UserBubble({ ev }: { ev: TranscriptEvent }): React.JSX.Element {
  return (
    <div className="msg-user">
      <div className="bubble-user">
        {(ev.text ?? '').trim()}
        {/* Sent while Claude was mid-turn: waiting its turn, which is worth
            saying so the message does not look ignored. */}
        {ev.queued ? <span className="queued-tag">queued</span> : null}
      </div>
    </div>
  )
}

/**
 * Thinking is collapsed by default: it is the most useful thing to open when a
 * session is mid-turn, and also the longest, so it must not push the answer
 * off-screen. Expansion state keys off the row key via component identity.
 */
function ThinkingBlock({ text }: { text: string }): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const trimmed = text.trim()
  const firstLine = trimmed.split('\n').find((l) => l.trim() !== '')?.slice(0, 110) ?? ''
  return (
    <div className="think-card" onClick={() => setOpen((o) => !o)}>
      <div className="think-head">
        <span className="think-label">thinking</span>
        {open ? null : <span className="think-preview">{firstLine}</span>}
        <span className="chev">{open ? '▴' : '▾'}</span>
      </div>
      {open ? <div className="think-body">{trimmed}</div> : null}
    </div>
  )
}

/**
 * A tool call and its outcome as ONE card, result collapsed by default —
 * two cards per tool makes a tool-heavy turn 90% plumbing. The input pane
 * scrolls horizontally (a command must not wrap into ambiguity) and the
 * result gets diff colouring for free when it looks like a diff.
 */
function ToolCard({ ev }: { ev: TranscriptEvent }): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const result = ev.result ?? ''
  const hasResult = !isBlank(ev.result)
  const failed = ev.ok === false
  return (
    <div className={failed ? 'tool-card tool-failed' : 'tool-card'}>
      <div
        className={hasResult ? 'tool-head tool-head-clickable' : 'tool-head'}
        onClick={hasResult ? () => setOpen((o) => !o) : undefined}
      >
        <span className="tool-glyph">{glyphFor(ev)}</span>
        <span className="tool-name">{ev.name ?? ''}</span>
        {!isBlank(ev.detail) ? <span className="tool-detail">{ev.detail}</span> : null}
        {hasResult ? <span className="chev">{open ? '▴' : '▾'}</span> : null}
      </div>
      {!isBlank(ev.input) ? (
        <pre className="tool-pre">
          <code>
            <HlText code={ev.input ?? ''} lang={langForTool(ev.name)} />
          </code>
        </pre>
      ) : null}
      {open && hasResult ? (
        <div className="tool-result">
          <pre className="tool-result-pre">
            <code>
              <HlText code={result} lang={resultLang(result)} />
            </code>
          </pre>
        </div>
      ) : null}
    </div>
  )
}

/**
 * An AskUserQuestion as a question rather than as JSON. HISTORICAL by design:
 * these options are a record of what was asked — the live, clickable prompt
 * card on the Screen tab is what answers. Once answered, the chosen reply
 * (parsed out of the result) matters more than what was offered.
 */
function AskCard({ ev }: { ev: TranscriptEvent }): React.JSX.Element | null {
  const ask = ev.ask
  if (ask === null) return null
  const answered = !isBlank(ev.result)
  return (
    <div className="ask-card">
      <div className="ask-head">
        <span className="ask-glyph">?</span>
        <span>{ask.questions[0]?.header ?? 'Question'}</span>
      </div>
      {ask.questions.map((q, i) => (
        <div key={i}>
          <div className="ask-question">{q.question}</div>
          {!answered && q.options.length > 0 ? (
            <div className="ask-options">
              {q.options.map((opt) => (
                <div key={opt} className="ask-option">
                  <span className="ask-mark">{q.multiSelect ? '☐' : '○'}</span>
                  <span>{opt}</span>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      ))}
      {answered ? (
        <div className="ask-chosen">→ {answeredSummary(ev.result ?? '')}</div>
      ) : (
        <div className="ask-waiting">Waiting for an answer — the Screen tab has the live buttons</div>
      )}
    </div>
  )
}

/**
 * A result whose call sits above the loaded window. Shown plainly rather than
 * dropped, so a cold open at the tail is not silently missing output.
 */
function ToolResultOrphan({ ev }: { ev: TranscriptEvent }): React.JSX.Element {
  return (
    <pre className={ev.ok === false ? 'orphan-block orphan-failed' : 'orphan-block'}>
      {ev.result ?? ''}
    </pre>
  )
}

/**
 * A slash command / its output as a compact centred note: something that
 * happened to the session, not something anyone said.
 */
function CommandNote({ text, isResult }: { text: string; isResult: boolean }): React.JSX.Element {
  return (
    <div className="cmd-note">
      <span className={isResult ? 'cmd-chip cmd-chip-result' : 'cmd-chip'}>{text}</span>
    </div>
  )
}

function TranscriptEventItem({ ev }: { ev: TranscriptEvent }): React.JSX.Element | null {
  switch (ev.kind) {
    case 'user':
      return <UserBubble ev={ev} />
    case 'assistant':
      return <MarkdownView text={ev.text ?? ''} />
    case 'thinking':
      return <ThinkingBlock text={ev.text ?? ''} />
    case 'tool':
      return ev.ask !== null ? <AskCard ev={ev} /> : <ToolCard ev={ev} />
    case 'tool_result':
      return <ToolResultOrphan ev={ev} />
    case 'command':
      return <CommandNote text={ev.text ?? ''} isResult={false} />
    case 'command_result':
      return <CommandNote text={ev.text ?? ''} isResult={true} />
    case 'system':
      return <div className="sys-note">{ev.text ?? ''}</div>
    default:
      return null
  }
}

/**
 * Delegated work as one unit. Closed, it answers "what was farmed out and how
 * much happened"; open, it is the full play-by-play rendered by the same event
 * renderers as the main thread. Closed by default because during a fan-out the
 * sidechain outweighs the main thread.
 */
function SubagentsCard({ row }: { row: SubagentsRow }): React.JSX.Element {
  const [open, setOpen] = useState(false)
  return (
    <div className="subagents-card">
      <div className="subagents-head" onClick={() => setOpen((o) => !o)}>
        <span className="subagents-title">↳ Subagent</span>
        <span className="subagents-steps">
          {row.steps} step{row.steps === 1 ? '' : 's'}
        </span>
        <span className="chev">{open ? '▴' : '▾'}</span>
      </div>
      {/* The parent's own words for the task: the best one-line summary there is. */}
      {row.task !== null ? (
        <div className={open ? 'subagents-task' : 'subagents-task subagents-task-clamped'}>
          {row.task}
        </div>
      ) : null}
      {open ? (
        <div className="subagents-body">
          {row.events.map((ev) => (
            <TranscriptEventItem key={ev.seq} ev={ev} />
          ))}
        </div>
      ) : null}
    </div>
  )
}

export function TranscriptList({ page }: { page: TranscriptPage }): React.JSX.Element {
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickRef = useRef(true)
  const rows = useMemo(() => group(page.events), [page.events])
  const rowKeys = useMemo(() => keys(rows), [rows])

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

  return (
    <div className="transcript-scroll" ref={scrollRef} onScroll={onScroll}>
      {page.truncated ? (
        <div className="transcript-truncated">older events not loaded</div>
      ) : null}
      {rows.map((row, i) => (
        <div className="transcript-row" key={rowKeys[i] ?? `#${i}`}>
          {row.kind === 'single' ? (
            <TranscriptEventItem ev={row.event} />
          ) : (
            <SubagentsCard row={row} />
          )}
        </div>
      ))}
    </div>
  )
}
