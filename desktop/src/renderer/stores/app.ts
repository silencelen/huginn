// App-level state: navigation, settings, the two lists, and watch-stream
// connection. Detail-view state (an open chat's live run, a session's screen)
// lives in per-view hooks, not here — it has a lifecycle, this store does not.

import { create } from 'zustand'
import type { Chat, Session } from '../../shared/api/types'
import type { SettingsView } from '../../main/settings'
import { call, on } from '../lib/ipc'

export type Dest =
  | { view: 'chats'; chatId: string | null }
  | { view: 'sessions'; sessionName: string | null }
  | { view: 'status' }
  | { view: 'settings' }

interface AppState {
  dest: Dest
  settings: SettingsView | null
  chats: Chat[]
  sessions: Session[] | null
  sessionsUnavailable: boolean
  watchConnected: boolean
  navigate: (dest: Dest) => void
  refreshSettings: () => Promise<void>
  updateSettings: (patch: Parameters<typeof call<'settings.update'>>[1]) => Promise<void>
  refreshChats: () => Promise<void>
  refreshSessions: () => Promise<void>
}

export const useApp = create<AppState>((set, get) => ({
  dest: { view: 'chats', chatId: null },
  settings: null,
  chats: [],
  sessions: null,
  sessionsUnavailable: false,
  watchConnected: false,

  navigate: (dest) => set({ dest }),

  refreshSettings: async () => {
    set({ settings: await call('settings.get') })
  },

  updateSettings: async (patch) => {
    set({ settings: await call('settings.update', patch) })
  },

  refreshChats: async () => {
    try {
      set({ chats: await call('chats.list') })
    } catch {
      // Transient; the next refresh or watch tick will retry.
    }
  },

  refreshSessions: async () => {
    try {
      const sessions = await call('sessions.list')
      set({ sessions, sessionsUnavailable: sessions === null })
    } catch {
      set({ sessionsUnavailable: true })
    }
  },
}))

let wired = false

/** One-time wiring of push channels + the 5s list poll. Call from App mount. */
export function wireAppStore(): () => void {
  if (wired) return () => {}
  wired = true
  const { refreshChats, refreshSessions, refreshSettings } = useApp.getState()

  void refreshSettings()
  void refreshChats()
  void refreshSessions()
  // The watch stream usually connects before the first window mounts; pull the
  // state we missed rather than waiting for the next push.
  void call('watch.latest').then(({ connected }) => useApp.setState({ watchConnected: connected }))

  const offWatch = on('push.watch', ({ watch, connected }) => {
    useApp.setState({ watchConnected: connected })
    if (watch !== null) {
      // The digest changed: something started, finished, or queued. The lists
      // re-derive from full fetches (they carry more than the digest does).
      void refreshChats()
      void refreshSessions()
    }
  })
  const offLists = on('push.listsChanged', () => {
    void refreshChats()
    void refreshSessions()
  })
  const offNav = on('push.navigate', (target) => {
    useApp
      .getState()
      .navigate(
        target.view === 'chats'
          ? { view: 'chats', chatId: target.id }
          : { view: 'sessions', sessionName: target.id },
      )
  })

  // Hidden window, no polling: the watch stream still wakes us for anything
  // that matters, and a tray-resident app must not poll all night.
  const poll = setInterval(() => {
    if (document.hidden) return
    void refreshChats()
    void refreshSessions()
  }, 5_000)

  // Coming back from hidden should feel instant rather than up to 5s stale.
  const onVisible = (): void => {
    if (document.hidden) return
    void refreshChats()
    void refreshSessions()
  }
  document.addEventListener('visibilitychange', onVisible)

  return () => {
    offWatch()
    offLists()
    offNav()
    document.removeEventListener('visibilitychange', onVisible)
    clearInterval(poll)
    wired = false
  }
}
