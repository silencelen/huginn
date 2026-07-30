// Ctrl+K: one input over everything, every chat and session in one list, plus
// the handful of verbs worth typing instead of clicking. The ranking is a pure
// function so it is tested without a DOM, and so the "why is that first?"
// question has an answer that is readable in one screen.

import { useEffect, useMemo, useRef, useState } from 'react'
import type { Chat, Session } from '../../../shared/api/types'
import { useApp } from '../../stores/app'
import {
  SHORTCUTS,
  createChat,
  requestNewSession,
  type ShortcutDoc,
} from '../../hooks/useShortcuts'

export type VerbId = 'new-ask' | 'new-act' | 'new-session' | 'settings' | 'status'

export type PaletteTarget =
  | { kind: 'chat'; id: string }
  | { kind: 'session'; name: string }
  | { kind: 'verb'; verb: VerbId }

export interface PaletteItem {
  /** Stable react key, unique across kinds. */
  key: string
  kind: 'verb' | 'chat' | 'session'
  label: string
  /** Second line: the thing you would search by but not name it. */
  sub: string
  /** State in the app's own words, never a badge. */
  mark: string
  target: PaletteTarget
}

const VERBS: { verb: VerbId; label: string; sub: string }[] = [
  { verb: 'new-ask', label: 'New Ask chat', sub: 'answers questions' },
  { verb: 'new-act', label: 'New Act chat', sub: 'can make changes on the host' },
  { verb: 'new-session', label: 'New session', sub: 'tmux session on the host' },
  { verb: 'settings', label: 'Settings', sub: 'server, notifications, account' },
  { verb: 'status', label: 'Status', sub: 'host, plan, usage' },
]

const chatMark = (c: Chat): string =>
  c.running ? 'working' : c.pending > 0 ? `${c.pending} queued` : c.mode === 'act' ? 'act' : ''

const sessionMark = (s: Session): string =>
  s.state === 'running'
    ? 'working'
    : s.state === 'attention'
      ? 'needs you'
      : s.state === 'idle'
        ? 'idle'
        : ''

/** Every character of q appears in text, in order. The "fuzzy-ish" fallback. */
const subsequence = (text: string, q: string): boolean => {
  let i = 0
  for (const ch of text) {
    if (ch === q[i]) i += 1
    if (i === q.length) return true
  }
  return q.length === 0
}

/**
 * Lower is better; null means "no match". The tiers are what a person expects:
 * what you typed starts the title, starts a word in it, is somewhere in it,
 * is in the second line, or is merely spelled out across the row.
 */
export const rankItem = (query: string, label: string, sub: string): number | null => {
  const q = query.trim().toLowerCase()
  if (q === '') return 0
  const l = label.toLowerCase()
  const s = sub.toLowerCase()
  if (l.startsWith(q)) return 0
  if (new RegExp(`(^|[^a-z0-9])${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(l)) return 1
  if (l.includes(q)) return 2
  if (s.includes(q)) return 3
  if (subsequence(`${l} ${s}`, q)) return 4
  return null
}

export const toPaletteItems = (chats: Chat[], sessions: Session[] | null): PaletteItem[] => [
  ...VERBS.map((v) => ({
    key: `verb:${v.verb}`,
    kind: 'verb' as const,
    label: v.label,
    sub: v.sub,
    mark: '',
    target: { kind: 'verb' as const, verb: v.verb },
  })),
  ...chats.map((c) => ({
    key: `chat:${c.id}`,
    kind: 'chat' as const,
    label: c.title ?? 'Untitled',
    sub: c.lastSnippet ?? '',
    mark: chatMark(c),
    target: { kind: 'chat' as const, id: c.id },
  })),
  ...(sessions ?? []).map((s) => ({
    key: `session:${s.name}`,
    kind: 'session' as const,
    label: s.title ?? s.name,
    sub: s.name,
    mark: sessionMark(s),
    target: { kind: 'session' as const, name: s.name },
  })),
]

/** Filter + rank. Verbs sort above the rest so a matching verb is reachable. */
export const filterPalette = (query: string, items: PaletteItem[]): PaletteItem[] =>
  items
    .map((item, index) => ({ item, index, score: rankItem(query, item.label, item.sub) }))
    .filter((r): r is { item: PaletteItem; index: number; score: number } => r.score !== null)
    .sort((a, b) => {
      const kindA = a.item.kind === 'verb' ? 0 : 1
      const kindB = b.item.kind === 'verb' ? 0 : 1
      if (kindA !== kindB) return kindA - kindB
      if (a.score !== b.score) return a.score - b.score
      return a.index - b.index
    })
    .map((r) => r.item)

export function CommandPalette({ onClose }: { onClose: () => void }): React.JSX.Element {
  const chats = useApp((s) => s.chats)
  const sessions = useApp((s) => s.sessions)
  const navigate = useApp((s) => s.navigate)
  const [query, setQuery] = useState('')
  const [sel, setSel] = useState(0)
  const listRef = useRef<HTMLDivElement | null>(null)

  const items = useMemo(() => toPaletteItems(chats, sessions), [chats, sessions])
  const results = useMemo(() => filterPalette(query, items), [query, items])

  // A shrinking result list must not strand the selection past its end.
  const selected = results.length === 0 ? -1 : Math.min(sel, results.length - 1)

  useEffect(() => {
    const row = listRef.current?.querySelector('.palette-row.sel')
    row?.scrollIntoView({ block: 'nearest' })
  }, [selected, results.length])

  const open = (item: PaletteItem): void => {
    onClose()
    switch (item.target.kind) {
      case 'chat':
        navigate({ view: 'chats', chatId: item.target.id })
        return
      case 'session':
        navigate({ view: 'sessions', sessionName: item.target.name })
        return
      case 'verb':
        switch (item.target.verb) {
          case 'new-ask':
            void createChat('ask').catch(() => {})
            return
          case 'new-act':
            void createChat('act').catch(() => {})
            return
          case 'new-session':
            // The name dialog and its validation live in SessionsList; ask it
            // rather than growing a second copy here.
            navigate({ view: 'sessions', sessionName: null })
            requestNewSession()
            return
          case 'settings':
            navigate({ view: 'settings' })
            return
          case 'status':
            navigate({ view: 'status' })
        }
    }
  }

  const onKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
      return
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (results.length === 0) return
      const next = (selected + (e.key === 'ArrowDown' ? 1 : -1) + results.length) % results.length
      setSel(next)
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      const item = selected < 0 ? undefined : results[selected]
      if (item !== undefined) open(item)
    }
  }

  return (
    <div className="palette-backdrop" onMouseDown={onClose}>
      <div
        className="palette"
        onMouseDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
      >
        <input
          className="palette-input"
          value={query}
          autoFocus
          spellCheck={false}
          placeholder="Go to a chat or session, or start something"
          onChange={(e) => {
            setQuery(e.target.value)
            setSel(0)
          }}
          onKeyDown={onKeyDown}
        />
        <div className="palette-results" ref={listRef}>
          {results.length === 0 ? (
            <div className="palette-empty">Nothing matches.</div>
          ) : (
            results.map((item, i) => (
              <div
                key={item.key}
                className={`palette-row ${i === selected ? 'sel' : ''}`}
                onMouseMove={() => setSel(i)}
                onClick={() => open(item)}
              >
                <span className="palette-kind">{item.kind === 'verb' ? '' : item.kind}</span>
                <span className="palette-label">{item.label}</span>
                {item.mark !== '' ? <span className="palette-mark">{item.mark}</span> : null}
                {item.sub !== '' ? <span className="palette-sub">{item.sub}</span> : null}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}

export function Cheatsheet({ onClose }: { onClose: () => void }): React.JSX.Element {
  const ref = useRef<HTMLButtonElement | null>(null)
  useEffect(() => ref.current?.focus(), [])
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape' || e.key === 'F1') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="palette-backdrop" onMouseDown={onClose}>
      <div
        className="cheats"
        onMouseDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
      >
        <div className="dlg-title">Keyboard</div>
        <div className="cheats-grid">
          {SHORTCUTS.map((s: ShortcutDoc) => (
            <div key={s.keys} className="cheats-row">
              <span className="cheats-key">{s.keys}</span>
              <span className="cheats-what">{s.what}</span>
            </div>
          ))}
        </div>
        <div className="cheats-note">
          Ctrl is Cmd on macOS. Right-click a row in either list for its actions. While the live
          pane has focus every key goes to tmux instead.
        </div>
        <div className="dlg-actions">
          <button ref={ref} type="button" className="dlg-primary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
