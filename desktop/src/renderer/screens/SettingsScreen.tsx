// Connection + app settings. The token is write-only from here: the renderer
// can set it but never read it back (hasToken is all it learns).

import { useEffect, useState } from 'react'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'

export function SettingsScreen(): React.JSX.Element {
  const settings = useApp((s) => s.settings)
  const updateSettings = useApp((s) => s.updateSettings)
  const watchConnected = useApp((s) => s.watchConnected)
  const [baseUrl, setBaseUrl] = useState('')
  const [token, setToken] = useState('')
  const [pingResult, setPingResult] = useState<string | null>(null)
  const [version, setVersion] = useState('')

  useEffect(() => {
    if (settings) setBaseUrl(settings.baseUrl)
  }, [settings])
  useEffect(() => {
    void call('app.version').then(setVersion)
  }, [])

  const save = (): void => {
    const patch: { baseUrl?: string; token?: string } = { baseUrl }
    if (token !== '') patch.token = token
    void updateSettings(patch).then(() => {
      setToken('')
      setPingResult(null)
    })
  }

  const testConnection = (): void => {
    setPingResult('…')
    void call('host.ping')
      .then((p) => setPingResult(p.ok ? `OK — appd ${p.version ?? '?'}` : 'Daemon answered oddly'))
      .catch((e: unknown) => setPingResult(e instanceof Error ? e.message : String(e)))
  }

  if (!settings) return <div className="pane-placeholder">Loading…</div>
  return (
    <div className="settings">
      <h2>Connection</h2>
      <label className="field">
        <span>Server</span>
        <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} spellCheck={false} />
      </label>
      <label className="field">
        <span>Token {settings.hasToken ? '(saved)' : '(required)'}</span>
        <input
          type="password"
          value={token}
          placeholder={settings.hasToken ? '••••••••  (leave blank to keep)' : 'paste the appd token'}
          onChange={(e) => setToken(e.target.value)}
        />
      </label>
      {settings.tokenPlaintextFallback ? (
        <div className="banner banner-warn">
          No OS keyring available — the token is stored unencrypted in config.json.
        </div>
      ) : null}
      <div className="settings-actions">
        <button type="button" onClick={save}>
          Save
        </button>
        <button type="button" onClick={testConnection}>
          Test connection
        </button>
        {pingResult !== null ? <span className="ping-result">{pingResult}</span> : null}
      </div>

      <h2>Behaviour</h2>
      <label className="check">
        <input
          type="checkbox"
          checked={settings.notifyEnabled}
          onChange={(e) => void updateSettings({ notifyEnabled: e.target.checked })}
        />
        <span>Show notifications</span>
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={settings.launchAtLogin}
          onChange={(e) => void updateSettings({ launchAtLogin: e.target.checked })}
        />
        <span>Launch at login</span>
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={settings.closeToTray}
          onChange={(e) => void updateSettings({ closeToTray: e.target.checked })}
        />
        <span>Keep running in tray when closed</span>
      </label>

      <h2>About</h2>
      <div className="about-line">
        Huginn Desktop v{version} · watch stream:{' '}
        <span className={watchConnected ? 'ok' : 'bad'}>
          {watchConnected ? 'connected' : 'disconnected'}
        </span>
      </div>
    </div>
  )
}
