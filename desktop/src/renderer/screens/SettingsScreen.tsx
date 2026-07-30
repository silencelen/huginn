// Connection + app settings. The token is write-only from here: the renderer
// can set it but never read it back (hasToken is all it learns).

import { useEffect, useState } from 'react'
import { useApp } from '../stores/app'
import { call, on } from '../lib/ipc'
import type { UpdateState } from '../../main/updater'

function AccountsSection(): React.JSX.Element {
  const [current, setCurrent] = useState<{ email: string | null; subscriptionType: string | null } | null>(null)
  const [saved, setSaved] = useState<
    { slug: string; email: string | null; isActive: boolean; weeklyPercent: number | null; verified: boolean; duplicateOf: boolean }[]
  >([])
  const [autoswitch, setAutoswitch] = useState<string | null>(null)
  const [loginEmail, setLoginEmail] = useState('')
  const [loginUrl, setLoginUrl] = useState<string | null>(null)
  const [loginCode, setLoginCode] = useState('')
  const [loginNote, setLoginNote] = useState<string | null>(null)

  const reload = (): void => {
    void call('account.current').then((a) => setCurrent(a)).catch(() => {})
    void call('account.saved', true).then(setSaved).catch(() => {})
    void call('account.autoswitch')
      .then((a) => {
        if (!a.enabled) setAutoswitch(null)
        else {
          const last = a.last
          setAutoswitch(
            last === null
              ? `on · ${a.accounts} accounts`
              : `on · last: ${last.fromEmail ?? '?'} (${last.fromPercent}%) → ${last.toEmail ?? '?'} (${last.toPercent}%)`,
          )
        }
      })
      .catch(() => {})
  }
  useEffect(reload, [])

  const startLogin = (): void => {
    setLoginNote('Starting sign-in on the host…')
    void call('account.login.start', loginEmail.trim() === '' ? null : loginEmail.trim())
      .then((s) => {
        if (s.url !== null) {
          setLoginUrl(s.url)
          window.open(s.url)
          setLoginNote('Complete the sign-in in the browser, then paste the code here.')
        } else {
          setLoginNote('The host did not produce a sign-in URL — check the login tmux session.')
        }
      })
      .catch((e: unknown) => setLoginNote(e instanceof Error ? e.message : String(e)))
  }

  const submitCode = (): void => {
    setLoginNote('Checking…')
    void call('account.login.code', loginCode.trim())
      .then((s) => {
        setLoginNote(
          s.duplicate
            ? `Already saved: ${s.email ?? 'that account'} (same login twice).`
            : s.mismatch
              ? `Signed in as ${s.email ?? 'someone else'}, not ${s.intendedEmail ?? 'the intended account'}.`
              : s.done
                ? `Added ${s.email ?? 'account'}.`
                : (s.message ?? 'Still waiting on the host.'),
        )
        if (s.done) {
          setLoginUrl(null)
          setLoginCode('')
          reload()
        }
      })
      .catch((e: unknown) => setLoginNote(e instanceof Error ? e.message : String(e)))
  }

  return (
    <>
      <h2>Account</h2>
      <div className="about-line">
        {current === null
          ? 'Loading…'
          : `${current.email ?? 'not signed in'}${current.subscriptionType !== null ? ` · ${current.subscriptionType}` : ''}`}
        {autoswitch !== null ? ` · autoswitch ${autoswitch}` : ''}
      </div>
      {saved.map((a) => (
        <div key={a.slug} className="account-row">
          <span className={`state-dot ${a.isActive ? 'dot-running' : 'dot-idle'}`} />
          <span className="account-email">
            {a.email ?? a.slug}
            {a.verified ? '' : ' (unconfirmed)'}
            {a.duplicateOf ? ' (duplicate)' : ''}
          </span>
          {a.weeklyPercent !== null ? (
            <span className="dim">{Math.round(a.weeklyPercent)}% of week</span>
          ) : null}
          {a.isActive ? (
            <span className="dim">active</span>
          ) : (
            <button
              type="button"
              onClick={() => void call('account.activate', a.slug).then(reload)}
            >
              Use
            </button>
          )}
          <button
            type="button"
            className="danger"
            onClick={() => {
              if (window.confirm(`Forget saved login ${a.email ?? a.slug}?`))
                void call('account.forget', a.slug).then(reload)
            }}
          >
            Forget
          </button>
        </div>
      ))}
      <div className="login-flow">
        {loginUrl === null ? (
          <>
            <input
              placeholder="email to add (aims the sign-in page)"
              value={loginEmail}
              onChange={(e) => setLoginEmail(e.target.value)}
            />
            <button type="button" onClick={startLogin}>
              Add login
            </button>
          </>
        ) : (
          <>
            <input
              placeholder="paste the code from the browser"
              value={loginCode}
              onChange={(e) => setLoginCode(e.target.value)}
            />
            <button type="button" disabled={loginCode.trim() === ''} onClick={submitCode}>
              Submit code
            </button>
            <button
              type="button"
              onClick={() => {
                setLoginUrl(null)
                setLoginNote(null)
              }}
            >
              Cancel
            </button>
          </>
        )}
      </div>
      {loginNote !== null ? <div className="dim">{loginNote}</div> : null}
    </>
  )
}

export function SettingsScreen(): React.JSX.Element {
  const settings = useApp((s) => s.settings)
  const updateSettings = useApp((s) => s.updateSettings)
  const watchConnected = useApp((s) => s.watchConnected)
  const [baseUrl, setBaseUrl] = useState('')
  const [token, setToken] = useState('')
  const [pingResult, setPingResult] = useState<string | null>(null)
  const [version, setVersion] = useState('')
  const [update, setUpdate] = useState<UpdateState | null>(null)

  useEffect(() => {
    if (settings) setBaseUrl(settings.baseUrl)
  }, [settings])
  useEffect(() => {
    void call('app.version').then(setVersion)
    void call('update.state').then(setUpdate)
    return on('push.update', setUpdate)
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

      <AccountsSection />

      <h2>About</h2>
      <div className="about-line">
        Huginn Desktop v{version} · watch stream:{' '}
        <span className={watchConnected ? 'ok' : 'bad'}>
          {watchConnected ? 'connected' : 'disconnected'}
        </span>
      </div>
      <div className="about-line update-line">
        {update === null || update.status === 'none' ? (
          <>
            Up to date.{' '}
            <button type="button" onClick={() => void call('update.check')}>
              Check now
            </button>
          </>
        ) : update.status === 'ready' ? (
          <>
            v{update.version} downloaded.{' '}
            <button type="button" onClick={() => void call('update.install')}>
              Restart to update
            </button>
          </>
        ) : update.status === 'error' ? (
          <span className="bad">Update check failed: {update.error}</span>
        ) : (
          <span className="dim">
            {update.status === 'downloading' ? `Downloading v${update.version ?? ''}…` : 'Checking…'}
          </span>
        )}
      </div>
    </div>
  )
}
