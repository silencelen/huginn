import { useEffect, useState } from 'react'
import { call } from './lib/ipc'

export function App(): React.JSX.Element {
  const [version, setVersion] = useState('…')
  useEffect(() => {
    void call('app.version').then(setVersion)
  }, [])

  return (
    <div className="shell">
      <nav className="rail">
        <div className="rail-item active">Chats</div>
        <div className="rail-item">Sessions</div>
        <div className="rail-item">Status</div>
        <div className="rail-item rail-bottom">Settings</div>
      </nav>
      <aside className="list-pane">
        <div className="pane-placeholder">No chats yet</div>
      </aside>
      <main className="detail-pane">
        <div className="pane-placeholder">
          <h1>Huginn</h1>
          <p>Desktop client v{version}</p>
        </div>
      </main>
    </div>
  )
}
