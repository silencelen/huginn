// The three-pane shell: nav rail | list pane | detail pane. Desktop is always
// wide — no fold/rotate gymnastics, just panes.

import { useEffect, useRef } from 'react'
import { call } from './lib/ipc'
import { useApp, wireAppStore, type Dest } from './stores/app'
import { ChatsList } from './screens/ChatsList'
import { SessionsList } from './screens/SessionsList'
import { ChatView } from './screens/ChatView'
import { SessionView } from './screens/SessionView'
import { SettingsScreen } from './screens/SettingsScreen'
import { StatusScreen } from './screens/StatusScreen'

const RAIL: { key: Dest['view']; label: string }[] = [
  { key: 'chats', label: 'Chats' },
  { key: 'sessions', label: 'Sessions' },
  { key: 'status', label: 'Status' },
]

export function App(): React.JSX.Element {
  const dest = useApp((s) => s.dest)
  const navigate = useApp((s) => s.navigate)
  const settings = useApp((s) => s.settings)
  const watchConnected = useApp((s) => s.watchConnected)

  useEffect(() => wireAppStore(), [])

  // A chat deleted (here or from the phone) must not leave the detail pane
  // rendering a dead id — that showed a raw IPC error and a composer that
  // sent into nothing. Two consecutive misses, so a just-created chat that
  // has not reached the list yet is never mistaken for a deleted one.
  const chats = useApp((s) => s.chats)
  const chatMisses = useRef(0)
  useEffect(() => {
    if (dest.view !== 'chats' || dest.chatId === null) {
      chatMisses.current = 0
      return
    }
    if (chats.some((c) => c.id === dest.chatId)) {
      chatMisses.current = 0
      return
    }
    if (chats.length === 0) return
    chatMisses.current += 1
    if (chatMisses.current >= 2) navigate({ view: 'chats', chatId: null })
  }, [chats, dest, navigate])

  // Tell main what the user is looking at, so notifications for the visible
  // target are suppressed and its stale ones withdrawn.
  useEffect(() => {
    const target =
      dest.view === 'chats' && dest.chatId !== null
        ? { view: 'chats' as const, id: dest.chatId }
        : dest.view === 'sessions' && dest.sessionName !== null
          ? { view: 'sessions' as const, id: dest.sessionName }
          : null
    void call('ui.viewed', target)
  }, [dest])

  const toDest = (view: Dest['view']): Dest =>
    view === 'chats'
      ? { view: 'chats', chatId: dest.view === 'chats' ? dest.chatId : null }
      : view === 'sessions'
        ? { view: 'sessions', sessionName: dest.view === 'sessions' ? dest.sessionName : null }
        : view === 'status'
          ? { view: 'status' }
          : { view: 'settings' }

  const needsSetup = settings !== null && !settings.hasToken

  return (
    <div className="shell">
      <nav className="rail">
        {RAIL.map((item) => (
          <div
            key={item.key}
            className={`rail-item ${dest.view === item.key ? 'active' : ''}`}
            onClick={() => navigate(toDest(item.key))}
          >
            {item.label}
          </div>
        ))}
        <div
          className={`rail-item rail-bottom ${dest.view === 'settings' ? 'active' : ''}`}
          onClick={() => navigate({ view: 'settings' })}
          title={watchConnected ? 'Connected' : 'Disconnected'}
        >
          <span className={`conn-dot ${watchConnected ? 'ok' : 'bad'}`} />
          Settings
        </div>
      </nav>

      {dest.view === 'chats' ? (
        <>
          <aside className="list-pane">
            <ChatsList activeChatId={dest.chatId} />
          </aside>
          <main className="detail-pane">
            {needsSetup ? (
              <div className="pane-placeholder">
                <p>Set the server token in Settings to connect.</p>
              </div>
            ) : dest.chatId !== null ? (
              <ChatView key={dest.chatId} chatId={dest.chatId} />
            ) : (
              <div className="pane-placeholder">Select or create a chat</div>
            )}
          </main>
        </>
      ) : dest.view === 'sessions' ? (
        <>
          <aside className="list-pane">
            <SessionsList activeName={dest.sessionName} />
          </aside>
          <main className="detail-pane">
            {dest.sessionName !== null ? (
              <SessionView key={dest.sessionName} name={dest.sessionName} />
            ) : (
              <div className="pane-placeholder">Select a session</div>
            )}
          </main>
        </>
      ) : (
        <main className="detail-pane detail-wide">
          {dest.view === 'status' ? <StatusScreen /> : <SettingsScreen />}
        </main>
      )}
    </div>
  )
}
