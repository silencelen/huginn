// The app's keyboard model: ONE capture-phase keydown listener on window, and
// one pure mapper from a key event to an action. The mapper is pure so the
// cheat sheet, the tests and the running app can never disagree about what a
// chord does — SHORTCUTS below carries a probe event per row and the unit test
// runs every probe back through matchShortcut.
//
// Two hard rules the mapper encodes:
//
//  1. Typing wins. If focus is in a text field the app takes NOTHING except the
//     palette chord, Escape, and Alt+Arrow (list movement is on Alt precisely
//     so it can work from the composer without stealing bare arrows).
//  2. The live pane wins harder. `.term-live-capture` forwards raw keys to
//     tmux — including C-<letter>, Escape and F-keys — so while it has focus
//     the app claims nothing but the reload/close swallow below.
//
// The swallow: main still runs Electron's default menu roles (autoHideMenuBar
// only hides the bar), so Ctrl+R reloads the renderer — orphaning every
// main-side stream subscription — and Ctrl+W closes the window. We cannot edit
// main from here, so we eat those chords defensively. The real fix is a
// purpose-built Menu (or a before-input-event hook) in the main process.

import { useEffect, useRef, useSyncExternalStore } from 'react'
import { call } from '../lib/ipc'
import { useApp, type Dest } from '../stores/app'

/** The fields of a KeyboardEvent the mapper reads — so tests need no DOM. */
export interface KeyLike {
  key: string
  code?: string
  ctrlKey: boolean
  metaKey: boolean
  altKey: boolean
  shiftKey: boolean
}

/** Where the keystroke landed, and what is on screen. */
export interface KeyContext {
  /** Focus is in a text field (input/textarea/select/contenteditable). */
  typing: boolean
  /** Focus is on a surface that forwards raw keys elsewhere (the live pane). */
  capturing: boolean
  /** A dialog, the command palette, the cheat sheet or a context menu is up. */
  overlay: boolean
}

export type Action =
  | { kind: 'swallow' }
  | { kind: 'palette' }
  | { kind: 'cheatsheet' }
  | { kind: 'view'; view: Dest['view'] }
  | { kind: 'newChat'; mode: 'ask' | 'act' }
  | { kind: 'move'; delta: 1 | -1 }
  | { kind: 'back' }

/** Ctrl on Windows/Linux, Cmd on macOS — one modifier, spelled both ways. */
const mod = (e: KeyLike): boolean => e.ctrlKey || e.metaKey
const lower = (e: KeyLike): string => e.key.toLowerCase()

/** Pure: what should this keystroke do, given what is focused and on screen? */
export function matchShortcut(e: KeyLike, ctx: KeyContext): Action | null {
  // Always, even mid-typing and mid-terminal: never let the default menu roles
  // reload or close the window out from under a running chat.
  if (mod(e) && !e.altKey && (lower(e) === 'r' || lower(e) === 'w')) return { kind: 'swallow' }

  // An overlay owns the keyboard while it is up; it handles its own keys.
  if (ctx.overlay) return null
  // The live pane needs C-k, Escape and the F-keys to reach tmux intact.
  if (ctx.capturing) return null

  if (mod(e) && !e.altKey && !e.shiftKey && lower(e) === 'k') return { kind: 'palette' }
  if (e.key === 'Escape' && !mod(e) && !e.altKey) return { kind: 'back' }
  if (e.altKey && !e.metaKey && !e.shiftKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown'))
    return { kind: 'move', delta: e.key === 'ArrowUp' ? -1 : 1 }

  // Everything past here would fight the text field the user is in.
  if (ctx.typing) return null

  if (e.key === 'F1') return { kind: 'cheatsheet' }
  if (mod(e) && !e.altKey && (e.key === '/' || e.code === 'Slash')) return { kind: 'cheatsheet' }

  if (mod(e) && !e.altKey) {
    if (e.key === '1') return { kind: 'view', view: 'chats' }
    if (e.key === '2') return { kind: 'view', view: 'sessions' }
    if (e.key === '3') return { kind: 'view', view: 'status' }
    if (e.key === ',') return { kind: 'view', view: 'settings' }
    if (lower(e) === 'n') return { kind: 'newChat', mode: e.shiftKey ? 'act' : 'ask' }
  }

  return null
}

/** One row of the cheat sheet, carrying the probe that proves it is honest. */
export interface ShortcutDoc {
  keys: string
  what: string
  probe: KeyLike
  action: Action['kind']
}

const probe = (key: string, mods: Partial<Omit<KeyLike, 'key'>> = {}): KeyLike => ({
  key,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  shiftKey: false,
  ...mods,
})

export const SHORTCUTS: readonly ShortcutDoc[] = [
  { keys: 'Ctrl K', what: 'Command palette', probe: probe('k', { ctrlKey: true }), action: 'palette' },
  { keys: 'Ctrl 1', what: 'Chats', probe: probe('1', { ctrlKey: true }), action: 'view' },
  { keys: 'Ctrl 2', what: 'Sessions', probe: probe('2', { ctrlKey: true }), action: 'view' },
  { keys: 'Ctrl 3', what: 'Status', probe: probe('3', { ctrlKey: true }), action: 'view' },
  { keys: 'Ctrl ,', what: 'Settings', probe: probe(',', { ctrlKey: true }), action: 'view' },
  { keys: 'Ctrl N', what: 'New Ask chat', probe: probe('n', { ctrlKey: true }), action: 'newChat' },
  {
    keys: 'Ctrl Shift N',
    what: 'New Act chat',
    probe: probe('N', { ctrlKey: true, shiftKey: true }),
    action: 'newChat',
  },
  {
    keys: 'Alt ↑ / ↓',
    what: 'Previous / next in the list, from anywhere',
    probe: probe('ArrowDown', { altKey: true }),
    action: 'move',
  },
  { keys: 'Esc', what: 'Back to the list, or drop focus', probe: probe('Escape'), action: 'back' },
  { keys: 'F1', what: 'This sheet', probe: probe('F1'), action: 'cheatsheet' },
  {
    keys: 'Ctrl R / Ctrl W',
    what: 'Ignored — a stray reload would drop live streams',
    probe: probe('r', { ctrlKey: true }),
    action: 'swallow',
  },
] as const

/** Index to move to, or null when the move would leave the list. */
export const nextIndex = (length: number, current: number, delta: number): number | null => {
  if (length <= 0) return null
  if (current < 0) return delta > 0 ? 0 : length - 1
  const next = current + delta
  if (next < 0 || next >= length) return null
  return next
}

/** Keep the current detail item when switching panes; drop it otherwise. */
export const toDest = (view: Dest['view'], from: Dest): Dest =>
  view === 'chats'
    ? { view: 'chats', chatId: from.view === 'chats' ? from.chatId : null }
    : view === 'sessions'
      ? { view: 'sessions', sessionName: from.view === 'sessions' ? from.sessionName : null }
      : view === 'status'
        ? { view: 'status' }
        : { view: 'settings' }

// ------------------------------------------------------------ shared actions
//
// Creating a chat happens from three places (Ctrl+N, the palette verb, the list
// header's + New). One implementation, so the three cannot drift.

export const createChat = async (mode: 'ask' | 'act'): Promise<void> => {
  const { refreshChats, navigate } = useApp.getState()
  const chat = await call('chats.create', { mode })
  await refreshChats()
  navigate({ view: 'chats', chatId: chat.id })
}

// "New session" lives in SessionsList (it owns the name dialog and its
// validation). The palette asks for it by name rather than duplicating that
// flow; a window event is enough signal and keeps the store untouched.
const NEW_SESSION_EVENT = 'huginn:new-session'

export const requestNewSession = (): void => {
  window.dispatchEvent(new CustomEvent(NEW_SESSION_EVENT))
}

export const onNewSessionRequest = (cb: () => void): (() => void) => {
  window.addEventListener(NEW_SESSION_EVENT, cb)
  return () => window.removeEventListener(NEW_SESSION_EVENT, cb)
}

// --------------------------------------------------- keyboard-selection mark
//
// Rows show a focus ring only while the keyboard is driving. A pointer click
// already highlights the row it hit; a ring on top of that is noise.

let kbNav = false
const kbSubs = new Set<() => void>()

const setKeyboardNav = (v: boolean): void => {
  if (kbNav === v) return
  kbNav = v
  for (const f of kbSubs) f()
}

const subscribeKbNav = (f: () => void): (() => void) => {
  kbSubs.add(f)
  return () => {
    kbSubs.delete(f)
  }
}

/** True while the last selection change came from the keyboard. */
export const useKeyboardNav = (): boolean =>
  useSyncExternalStore(
    subscribeKbNav,
    () => kbNav,
    () => false,
  )

// ------------------------------------------------------------- the listener

const OVERLAY_SELECTOR = '.dlg-backdrop, .palette-backdrop, .ctx-menu'
const CAPTURE_SELECTOR = '.term-live-capture, canvas, [data-capture-keys]'
const TYPING_SELECTOR = 'input, textarea, select, [contenteditable=""], [contenteditable="true"]'

/** Read the context out of the DOM at event time — no cross-component state. */
export const contextFor = (target: EventTarget | null, overlayOpen: boolean): KeyContext => {
  const el = target instanceof Element ? target : null
  return {
    typing: el?.closest(TYPING_SELECTOR) != null,
    capturing: el?.closest(CAPTURE_SELECTOR) != null,
    overlay: overlayOpen || document.querySelector(OVERLAY_SELECTOR) !== null,
  }
}

export interface ShortcutHooks {
  /** The palette and cheat sheet live in App; the rest the hook does itself. */
  openPalette: () => void
  toggleCheatsheet: () => void
  /** True while App's own overlays are up (they are not in the DOM query). */
  overlayOpen: boolean
}

const move = (delta: number): void => {
  const st = useApp.getState()
  const dest = st.dest
  if (dest.view === 'chats') {
    const at = dest.chatId
    const i = nextIndex(
      st.chats.length,
      at === null ? -1 : st.chats.findIndex((c) => c.id === at),
      delta,
    )
    const chat = i === null ? undefined : st.chats[i]
    if (chat === undefined) return
    setKeyboardNav(true)
    st.navigate({ view: 'chats', chatId: chat.id })
    return
  }
  if (dest.view === 'sessions') {
    const list = st.sessions ?? []
    const at = dest.sessionName
    const i = nextIndex(
      list.length,
      at === null ? -1 : list.findIndex((s) => s.name === at),
      delta,
    )
    const session = i === null ? undefined : list[i]
    if (session === undefined) return
    setKeyboardNav(true)
    st.navigate({ view: 'sessions', sessionName: session.name })
  }
}

const back = (): void => {
  const st = useApp.getState()
  if (st.dest.view === 'chats' && st.dest.chatId !== null) {
    st.navigate({ view: 'chats', chatId: null })
    return
  }
  if (st.dest.view === 'sessions' && st.dest.sessionName !== null) {
    st.navigate({ view: 'sessions', sessionName: null })
    return
  }
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
}

const run = (action: Action, hooks: ShortcutHooks): void => {
  switch (action.kind) {
    case 'swallow':
      return
    case 'palette':
      hooks.openPalette()
      return
    case 'cheatsheet':
      hooks.toggleCheatsheet()
      return
    case 'view': {
      const st = useApp.getState()
      st.navigate(toDest(action.view, st.dest))
      return
    }
    case 'newChat':
      // No banner at this level; the list's own + New surfaces failures.
      void createChat(action.mode).catch(() => {})
      return
    case 'move':
      move(action.delta)
      return
    case 'back':
      back()
  }
}

/** Register the global keyboard model. Call ONCE, from App. */
export function useShortcuts(hooks: ShortcutHooks): void {
  const latest = useRef(hooks)
  latest.current = hooks

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.repeat && e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return
      const action = matchShortcut(e, contextFor(e.target, latest.current.overlayOpen))
      if (action === null) return
      e.preventDefault()
      e.stopPropagation()
      run(action, latest.current)
    }
    const onPointer = (): void => setKeyboardNav(false)

    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('mousedown', onPointer, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('mousedown', onPointer, true)
    }
  }, [])
}
