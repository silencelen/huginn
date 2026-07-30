// Connection + app settings. The token is write-only from here: the renderer
// can set it but never read it back (hasToken is all it learns).
//
// One save model everywhere: text fields commit on blur (or Enter) with an
// inline "Saved" mark, matching the checkboxes' auto-save. settings.update can
// REJECT a bad server address (the allowlist is a security control) — that
// error surfaces inline under the field instead of being swallowed.

import { useEffect, useRef, useState } from 'react'
import { useApp } from '../stores/app'
import { call, on } from '../lib/ipc'
import { ConfirmDialog } from '../components/common/Dialog'
import type { UpdateState } from '../../main/updater'

interface FieldNote {
  ok: boolean
  text: string
}

/** Field note that self-clears when it is a success mark (errors stay put). */
function useFieldNote(): [FieldNote | null, (n: FieldNote | null) => void] {
  const [note, setNote] = useState<FieldNote | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const set = (n: FieldNote | null): void => {
    if (timer.current !== null) clearTimeout(timer.current)
    setNote(n)
    if (n !== null && n.ok) timer.current = setTimeout(() => setNote(null), 3000)
  }
  useEffect(
    () => () => {
      if (timer.current !== null) clearTimeout(timer.current)
    },
    [],
  )
  return [note, set]
}

const errText = (e: unknown): string => (e instanceof Error ? e.message : String(e))

function NoteLine({ note }: { note: FieldNote | null }): React.JSX.Element | null {
  if (note === null) return null
  return <span className={note.ok ? 'save-note ok' : 'field-error'}>{note.text}</span>
}

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
  const [forgetting, setForgetting] = useState<{ slug: string; email: string | null } | null>(null)

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
      .catch((e: unknown) => setLoginNote(errText(e)))
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
      .catch((e: unknown) => setLoginNote(errText(e)))
  }

  const step = loginUrl === null ? 1 : 2

  return (
    <>
      <h2>Accounts</h2>
      <div className="field-help">
        Saved Claude logins on the host. The active one serves every chat and session.
      </div>
      <div className="about-line">
        {current === null
          ? 'Loading…'
          : `Signed in: ${current.email ?? 'nobody'}${current.subscriptionType !== null ? ` · ${current.subscriptionType}` : ''}`}
        {autoswitch !== null ? ` · autoswitch ${autoswitch}` : ''}
      </div>
      {saved.map((a) => (
        <div key={a.slug} className={`account-row ${a.isActive ? 'account-row-active' : ''}`}>
          <span className={`state-dot ${a.isActive ? 'dot-running' : 'dot-idle'}`} />
          <span className="account-email">
            {a.email ?? a.slug}
            {a.verified ? '' : ' (unconfirmed)'}
            {a.duplicateOf ? ' (duplicate)' : ''}
          </span>
          <span className="account-pct">
            {a.weeklyPercent !== null ? `${Math.round(a.weeklyPercent)}% of week` : ''}
          </span>
          <span className="account-state">{a.isActive ? 'active' : ''}</span>
          {a.isActive ? null : (
            <button type="button" onClick={() => void call('account.activate', a.slug).then(reload)}>
              Use
            </button>
          )}
          <button
            type="button"
            className="danger"
            onClick={() => setForgetting({ slug: a.slug, email: a.email })}
          >
            Forget
          </button>
        </div>
      ))}
      <div className="login-steps">
        <span className={step === 1 ? 'step-current' : 'step'}>1 · Start sign-in</span>
        <span className="step-sep">›</span>
        <span className={step === 2 ? 'step-current' : 'step'}>2 · Approve in the browser</span>
        <span className="step-sep">›</span>
        <span className={step === 2 ? 'step-current' : 'step'}>3 · Paste the code</span>
      </div>
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
      {forgetting !== null ? (
        <ConfirmDialog
          title="Forget saved login"
          body={`Remove ${forgetting.email ?? forgetting.slug} from the host's saved logins? Signing in again re-adds it.`}
          confirmLabel="Forget"
          danger
          onConfirm={() => {
            const slug = forgetting.slug
            setForgetting(null)
            void call('account.forget', slug).then(reload)
          }}
          onCancel={() => setForgetting(null)}
        />
      ) : null}
    </>
  )
}

export function SettingsScreen(): React.JSX.Element {
  const settings = useApp((s) => s.settings)
  const updateSettings = useApp((s) => s.updateSettings)
  const watchConnected = useApp((s) => s.watchConnected)
  const [baseUrl, setBaseUrl] = useState('')
  const [urlDirty, setUrlDirty] = useState(false)
  const [urlNote, setUrlNote] = useFieldNote()
  const [token, setToken] = useState('')
  const [tokenNote, setTokenNote] = useFieldNote()
  const [pingResult, setPingResult] = useState<string | null>(null)
  const [version, setVersion] = useState('')
  const [update, setUpdate] = useState<UpdateState | null>(null)
  const [diagNote, setDiagNote] = useFieldNote()

  // Seed the Server field from the store only while it is not being edited —
  // otherwise toggling a checkbox mid-edit wipes what was typed.
  useEffect(() => {
    if (settings !== null && !urlDirty) setBaseUrl(settings.baseUrl)
  }, [settings, urlDirty])
  useEffect(() => {
    void call('app.version').then(setVersion)
    void call('update.state').then(setUpdate)
    return on('push.update', setUpdate)
  }, [])

  const commitBaseUrl = (): void => {
    if (settings === null) return
    const next = baseUrl.trim()
    if (next === '' || next === settings.baseUrl) {
      setBaseUrl(settings.baseUrl)
      setUrlDirty(false)
      setUrlNote(null)
      return
    }
    void updateSettings({ baseUrl: next })
      .then(() => {
        setUrlDirty(false)
        setUrlNote({ ok: true, text: 'Saved' })
        setPingResult(null)
      })
      .catch((e: unknown) => setUrlNote({ ok: false, text: errText(e) }))
  }

  const commitToken = (): void => {
    if (token.trim() === '') return
    void updateSettings({ token })
      .then(() => {
        setToken('')
        setTokenNote({ ok: true, text: 'Token saved' })
        setPingResult(null)
      })
      .catch((e: unknown) => setTokenNote({ ok: false, text: errText(e) }))
  }

  const blurOnEnter = (e: React.KeyboardEvent<HTMLInputElement>): void => {
    if (e.key === 'Enter') e.currentTarget.blur()
  }

  const testConnection = (): void => {
    setPingResult('…')
    void call('host.ping')
      .then((p) => setPingResult(p.ok ? `OK — appd ${p.version ?? '?'}` : 'Daemon answered oddly'))
      .catch((e: unknown) => setPingResult(errText(e)))
  }

  if (!settings) return <div className="pane-placeholder">Loading…</div>
  return (
    <div className="settings">
      <h2>Connection</h2>
      <label className="field">
        <span>
          Server <NoteLine note={urlNote} />
        </span>
        <input
          value={baseUrl}
          onChange={(e) => {
            setBaseUrl(e.target.value)
            setUrlDirty(true)
          }}
          onBlur={commitBaseUrl}
          onKeyDown={blurOnEnter}
          spellCheck={false}
        />
        <span className="field-help">
          The huginn-appd address. Saves when you leave the field; only known daemon addresses are
          accepted.
        </span>
      </label>
      <label className="field">
        <span>
          Token {settings.hasToken ? '(saved)' : '(required)'} <NoteLine note={tokenNote} />
        </span>
        <input
          type="password"
          value={token}
          placeholder={settings.hasToken ? '••••••••  (leave blank to keep)' : 'paste the appd token'}
          onChange={(e) => setToken(e.target.value)}
          onBlur={commitToken}
          onKeyDown={blurOnEnter}
        />
        <span className="field-help">
          Write-only: paste a new token to replace the saved one. It is never shown back.
        </span>
      </label>
      {settings.tokenPlaintextFallback ? (
        <div className="banner banner-warn">
          No OS keyring available — the token is stored unencrypted in config.json.
        </div>
      ) : null}
      <div className="settings-actions">
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
        <span>
          Show notifications
          <span className="field-help">
            Toast when a chat finishes or a session needs an answer.
          </span>
        </span>
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={settings.launchAtLogin}
          onChange={(e) => void updateSettings({ launchAtLogin: e.target.checked })}
        />
        <span>
          Launch at login
          <span className="field-help">Start Huginn when you sign in to this computer.</span>
        </span>
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={settings.closeToTray}
          onChange={(e) => void updateSettings({ closeToTray: e.target.checked })}
        />
        <span>
          Keep running in tray when closed
          <span className="field-help">
            Closing the window keeps the watch stream and notifications alive.
          </span>
        </span>
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

      <h2>Diagnostics</h2>
      <div className="field-help">
        If something looks wrong, copy the diagnostics and paste them into a chat. They describe
        this install and the recent log; the token is never included.
      </div>
      <div className="settings-actions">
        <button
          type="button"
          onClick={() => {
            void call('diagnostics.text')
              .then((text) => navigator.clipboard.writeText(text))
              .then(() => setDiagNote({ ok: true, text: 'Copied to the clipboard' }))
              .catch((e: unknown) => setDiagNote({ ok: false, text: errText(e) }))
          }}
        >
          Copy diagnostics
        </button>
        <button
          type="button"
          onClick={() => {
            void call('diagnostics.testNotification')
              .then((r) =>
                setDiagNote({
                  ok: r.shown,
                  text: r.shown ? `Test notification ${r.reason}` : `Not sent: ${r.reason}`,
                }),
              )
              .catch((e: unknown) => setDiagNote({ ok: false, text: errText(e) }))
          }}
        >
          Send test notification
        </button>
        <NoteLine note={diagNote} />
      </div>
    </div>
  )
}
