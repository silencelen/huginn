// The three-pane shell: nav rail | list pane | detail pane. Desktop is always
// wide — no fold/rotate gymnastics, just panes.

import { useEffect } from 'react'
import { useApp, wireAppStore, type Dest } from './stores/app'
import { ChatsList } from './screens/ChatsList'
import { SessionsList } from './screens/SessionsList'
import { ChatView } from './screens/ChatView'
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
            <div className="pane-placeholder">
              {dest.sessionName !== null
                ? `Session view for ${dest.sessionName} lands in phase 2`
                : 'Select a session'}
            </div>
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
