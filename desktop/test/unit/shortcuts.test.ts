// The keyboard model's pure halves: which chord means what (matchShortcut),
// where the selection lands (nextIndex), which pane a rail switch keeps its
// detail item on (toDest), and what the palette shows for a query.
//
// The cheat sheet is checked against the mapper here too: every row carries a
// probe event, so a chord that gets renamed in one place and not the other
// fails this file instead of lying to the user.

import { describe, expect, it } from 'vitest'
import {
  SHORTCUTS,
  matchShortcut,
  nextIndex,
  toDest,
  type KeyContext,
  type KeyLike,
} from '../../src/renderer/hooks/useShortcuts'
import {
  filterPalette,
  rankItem,
  toPaletteItems,
} from '../../src/renderer/components/common/CommandPalette'
import { parseChat, parseSession } from '../../src/shared/api/types'

const ev = (key: string, mods: Partial<Omit<KeyLike, 'key'>> = {}): KeyLike => ({
  key,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  shiftKey: false,
  ...mods,
})

const IDLE: KeyContext = { typing: false, capturing: false, overlay: false }
const TYPING: KeyContext = { ...IDLE, typing: true }
const CAPTURING: KeyContext = { ...IDLE, capturing: true }
const OVERLAY: KeyContext = { ...IDLE, overlay: true }

describe('matchShortcut: navigation', () => {
  it('maps the view chords, Ctrl and Cmd alike', () => {
    expect(matchShortcut(ev('1', { ctrlKey: true }), IDLE)).toEqual({ kind: 'view', view: 'chats' })
    expect(matchShortcut(ev('2', { metaKey: true }), IDLE)).toEqual({
      kind: 'view',
      view: 'sessions',
    })
    expect(matchShortcut(ev('3', { ctrlKey: true }), IDLE)).toEqual({ kind: 'view', view: 'status' })
    expect(matchShortcut(ev(',', { ctrlKey: true }), IDLE)).toEqual({
      kind: 'view',
      view: 'settings',
    })
  })

  it('needs the modifier: bare digits are just typing', () => {
    expect(matchShortcut(ev('1'), IDLE)).toBeNull()
    expect(matchShortcut(ev('n'), IDLE)).toBeNull()
  })

  it('opens the palette on Ctrl+K and the sheet on F1 or Ctrl+/', () => {
    expect(matchShortcut(ev('k', { ctrlKey: true }), IDLE)).toEqual({ kind: 'palette' })
    expect(matchShortcut(ev('K', { metaKey: true }), IDLE)).toEqual({ kind: 'palette' })
    expect(matchShortcut(ev('F1'), IDLE)).toEqual({ kind: 'cheatsheet' })
    expect(matchShortcut(ev('/', { ctrlKey: true }), IDLE)).toEqual({ kind: 'cheatsheet' })
    // Layouts where Ctrl+/ arrives as '?' still carry the code.
    expect(matchShortcut({ ...ev('?', { ctrlKey: true }), code: 'Slash' }, IDLE)).toEqual({
      kind: 'cheatsheet',
    })
  })

  it('separates new Ask from new Act by shift', () => {
    expect(matchShortcut(ev('n', { ctrlKey: true }), IDLE)).toEqual({
      kind: 'newChat',
      mode: 'ask',
    })
    // Shift already shaped e.key into 'N'.
    expect(matchShortcut(ev('N', { ctrlKey: true, shiftKey: true }), IDLE)).toEqual({
      kind: 'newChat',
      mode: 'act',
    })
  })

  it('moves the list selection on Alt+Arrow, with Ctrl+Alt+Arrow as an alias', () => {
    expect(matchShortcut(ev('ArrowDown', { altKey: true }), IDLE)).toEqual({
      kind: 'move',
      delta: 1,
    })
    expect(matchShortcut(ev('ArrowUp', { altKey: true }), IDLE)).toEqual({ kind: 'move', delta: -1 })
    expect(matchShortcut(ev('ArrowUp', { altKey: true, ctrlKey: true }), IDLE)).toEqual({
      kind: 'move',
      delta: -1,
    })
  })

  it('leaves bare arrows alone — they belong to whatever is focused', () => {
    expect(matchShortcut(ev('ArrowDown'), IDLE)).toBeNull()
    expect(matchShortcut(ev('ArrowUp'), IDLE)).toBeNull()
  })
})

describe('matchShortcut: who owns the keyboard', () => {
  it('takes nothing from a text field except the palette, Escape and Alt+Arrow', () => {
    expect(matchShortcut(ev('k', { ctrlKey: true }), TYPING)).toEqual({ kind: 'palette' })
    expect(matchShortcut(ev('Escape'), TYPING)).toEqual({ kind: 'back' })
    expect(matchShortcut(ev('ArrowDown', { altKey: true }), TYPING)).toEqual({
      kind: 'move',
      delta: 1,
    })
    expect(matchShortcut(ev('1', { ctrlKey: true }), TYPING)).toBeNull()
    expect(matchShortcut(ev('n', { ctrlKey: true }), TYPING)).toBeNull()
    expect(matchShortcut(ev('F1'), TYPING)).toBeNull()
  })

  it('takes nothing at all from the live pane — those keys are tmux input', () => {
    for (const e of [
      ev('k', { ctrlKey: true }),
      ev('Escape'),
      ev('F1'),
      ev('ArrowDown', { altKey: true }),
      ev('1', { ctrlKey: true }),
    ]) {
      expect(matchShortcut(e, CAPTURING)).toBeNull()
    }
  })

  it('stands down while a dialog, the palette or a menu is up', () => {
    expect(matchShortcut(ev('Escape'), OVERLAY)).toBeNull()
    expect(matchShortcut(ev('k', { ctrlKey: true }), OVERLAY)).toBeNull()
    expect(matchShortcut(ev('ArrowDown', { altKey: true }), OVERLAY)).toBeNull()
  })

  it('swallows reload and close everywhere, so nothing drops a live stream', () => {
    for (const ctx of [IDLE, TYPING, CAPTURING, OVERLAY]) {
      expect(matchShortcut(ev('r', { ctrlKey: true }), ctx)).toEqual({ kind: 'swallow' })
      expect(matchShortcut(ev('R', { ctrlKey: true, shiftKey: true }), ctx)).toEqual({
        kind: 'swallow',
      })
      expect(matchShortcut(ev('w', { ctrlKey: true }), ctx)).toEqual({ kind: 'swallow' })
      expect(matchShortcut(ev('r', { metaKey: true }), ctx)).toEqual({ kind: 'swallow' })
    }
    // Alt+Ctrl+R is not a menu role; leave it alone.
    expect(matchShortcut(ev('r', { ctrlKey: true, altKey: true }), IDLE)).toBeNull()
  })
})

describe('the cheat sheet is the mapper', () => {
  it('every documented chord still produces the action it claims', () => {
    for (const row of SHORTCUTS) {
      expect(matchShortcut(row.probe, IDLE)?.kind, row.keys).toBe(row.action)
    }
  })

  it('lists every action the mapper can produce', () => {
    const kinds = new Set(SHORTCUTS.map((s) => s.action))
    for (const kind of ['palette', 'cheatsheet', 'view', 'newChat', 'move', 'back', 'swallow']) {
      expect(kinds.has(kind as (typeof SHORTCUTS)[number]['action']), kind).toBe(true)
    }
  })
})

describe('nextIndex', () => {
  it('starts at an end when nothing is selected', () => {
    expect(nextIndex(3, -1, 1)).toBe(0)
    expect(nextIndex(3, -1, -1)).toBe(2)
  })

  it('steps, and stops at the ends rather than wrapping', () => {
    expect(nextIndex(3, 0, 1)).toBe(1)
    expect(nextIndex(3, 2, -1)).toBe(1)
    expect(nextIndex(3, 2, 1)).toBeNull()
    expect(nextIndex(3, 0, -1)).toBeNull()
  })

  it('has nowhere to go in an empty list', () => {
    expect(nextIndex(0, -1, 1)).toBeNull()
    expect(nextIndex(0, 0, -1)).toBeNull()
  })
})

describe('toDest', () => {
  it('keeps the open item when you come back to its pane', () => {
    expect(toDest('chats', { view: 'chats', chatId: 'c1' })).toEqual({
      view: 'chats',
      chatId: 'c1',
    })
    expect(toDest('sessions', { view: 'sessions', sessionName: 's1' })).toEqual({
      view: 'sessions',
      sessionName: 's1',
    })
  })

  it('does not carry another paneid across', () => {
    expect(toDest('chats', { view: 'sessions', sessionName: 's1' })).toEqual({
      view: 'chats',
      chatId: null,
    })
    expect(toDest('sessions', { view: 'status' })).toEqual({
      view: 'sessions',
      sessionName: null,
    })
    expect(toDest('status', { view: 'chats', chatId: 'c1' })).toEqual({ view: 'status' })
  })
})

// ------------------------------------------------------------- the palette

const chat = (over: Partial<Record<string, unknown>>) =>
  parseChat({ id: 'c1', title: 'A chat', mode: 'ask', ...over })

const session = (over: Partial<Record<string, unknown>>) =>
  parseSession({ name: 's1', title: null, state: 'idle', ...over })

describe('rankItem', () => {
  it('everything matches an empty query', () => {
    expect(rankItem('', 'anything', 'at all')).toBe(0)
    expect(rankItem('   ', 'anything', '')).toBe(0)
  })

  it('ranks title prefix over word start over anywhere over the second line', () => {
    expect(rankItem('net', 'netplan audit', 'x')).toBe(0)
    expect(rankItem('audit', 'netplan audit', 'x')).toBe(1)
    expect(rankItem('udit', 'netplan audit', 'x')).toBe(2)
    expect(rankItem('x', 'netplan audit', 'x')).toBe(3)
  })

  it('is case-insensitive both ways', () => {
    expect(rankItem('NET', 'netplan', '')).toBe(0)
    expect(rankItem('net', 'NETPLAN', '')).toBe(0)
  })

  it('falls back to spelled-out letters, then gives up', () => {
    expect(rankItem('ntpn', 'netplan audit', '')).toBe(4)
    expect(rankItem('zzz', 'netplan audit', '')).toBeNull()
  })

  it('treats a query with regex metacharacters as text', () => {
    expect(rankItem('a+b', 'x a+b', '')).toBe(1)
    expect(rankItem('(', 'nothing here', '')).toBeNull()
  })
})

describe('filterPalette', () => {
  const items = toPaletteItems(
    [
      chat({ id: 'c1', title: 'Netplan audit', running: true }),
      chat({ id: 'c2', title: 'Grocery list', mode: 'act' }),
    ],
    [
      session({ name: 'jtyper', title: 'Trainer work', state: 'attention' }),
      session({ name: 'netbox', title: null, state: 'running' }),
    ],
  )

  it('shows verbs first, then chats, then sessions, on an empty query', () => {
    const all = filterPalette('', items)
    expect(all.slice(0, 5).map((i) => i.label)).toEqual([
      'New Ask chat',
      'New Act chat',
      'New session',
      'Settings',
      'Status',
    ])
    expect(all.slice(5).map((i) => i.kind)).toEqual(['chat', 'chat', 'session', 'session'])
  })

  it('finds a session by its tmux name even when a title hides it', () => {
    const hit = filterPalette('jtyper', items)
    expect(hit[0]?.label).toBe('Trainer work')
    expect(hit[0]?.target).toEqual({ kind: 'session', name: 'jtyper' })
  })

  it('keeps a matching verb above the items it shares words with', () => {
    const hit = filterPalette('new', items)
    expect(hit[0]?.kind).toBe('verb')
  })

  it('carries the state each row should show', () => {
    const marks = Object.fromEntries(filterPalette('', items).map((i) => [i.label, i.mark]))
    expect(marks['Netplan audit']).toBe('working')
    expect(marks['Grocery list']).toBe('act')
    expect(marks['Trainer work']).toBe('needs you')
    expect(marks['netbox']).toBe('working')
  })

  it('returns nothing rather than everything when nothing matches', () => {
    expect(filterPalette('qqqqq', items)).toEqual([])
  })
})
