// The sessions list pane: live tmux sessions with state dots, Claude's own
// session titles, background-work lines, and pane previews. Same dot language
// as the chats list: pulsing accent = working, amber = needs you, dim = idle.

import { useState } from 'react'
import type { Session } from '../../shared/api/types'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { isValidSessionName } from '../../shared/api/routes'
import { InputDialog } from '../components/common/Dialog'

const relTime = (epochSec: number): string => {
  if (epochSec <= 0) return ''
  const s = Math.max(0, Math.floor(Date.now() / 1000 - epochSec))
  if (s < 60) return 'now'
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86400) return `${Math.floor(s / 3600)}h`
  return `${Math.floor(s / 86400)}d`
}

const stateClass = (state: string | null): string => {
  switch (state) {
    case 'running':
      return 'dot-running dot-pulse'
    case 'attention':
      return 'dot-attention'
    case 'idle':
      return 'dot-idle'
    default:
      return 'dot-none'
  }
}

const NAME_HELP = 'Lowercase letters, digits, dot, dash or underscore (up to 50 characters).'

function SessionRow({ s, active }: { s: Session; active: boolean }): React.JSX.Element {
  const navigate = useApp((st) => st.navigate)
  return (
    <div
      className={`row ${active ? 'row-active' : ''}`}
      onClick={() => navigate({ view: 'sessions', sessionName: s.name })}
    >
      <div className="row-line1">
        <span className={`state-dot ${stateClass(s.state)}`} />
        <span className="row-title">{s.title ?? s.name}</span>
        <span className="row-time">{relTime(s.activityAt)}</span>
      </div>
      <div className="row-line2">
        <span className="row-name">{s.name}</span>
        {s.bgShells > 0 || s.bgAgents > 0 ? (
          <span className="bg-line">
            ⚙ {s.bgTask ?? ''}
            {s.bgShells > 1 ? ` +${s.bgShells - 1}` : ''}
            {s.bgAgents > 0 ? ` · ${s.bgAgents} agents` : ''}
          </span>
        ) : null}
        <span className="row-geom">
          {s.cols}×{s.rows}
        </span>
      </div>
      {s.preview.length > 0 ? <div className="row-preview">{s.preview.join('\n')}</div> : null}
    </div>
  )
}

export function SessionsList({ activeName }: { activeName: string | null }): React.JSX.Element {
  const sessions = useApp((s) => s.sessions)
  const unavailable = useApp((s) => s.sessionsUnavailable)
  const refreshSessions = useApp((s) => s.refreshSessions)
  const navigate = useApp((s) => s.navigate)
  const [creating, setCreating] = useState(false)
  const [createErr, setCreateErr] = useState<string | null>(null)

  const create = (raw: string): void => {
    const name = raw.trim().toLowerCase()
    if (!isValidSessionName(name)) {
      setCreateErr(`That name will not work. ${NAME_HELP}`)
      return
    }
    void call('sessions.create', name)
      .then(async () => {
        setCreating(false)
        setCreateErr(null)
        await refreshSessions()
        navigate({ view: 'sessions', sessionName: name })
      })
      .catch((e: unknown) => setCreateErr(e instanceof Error ? e.message : String(e)))
  }

  return (
    <div className="list">
      <div className="list-header">
        <span>Sessions</span>
        <button type="button" className="list-new" onClick={() => setCreating(true)}>
          + New
        </button>
      </div>
      {unavailable ? (
        <div className="list-empty">
          tmux is unreachable on the host, so sessions cannot be listed right now.
        </div>
      ) : sessions === null ? (
        <div className="list-empty">Loading sessions…</div>
      ) : sessions.length === 0 ? (
        <div className="list-empty">
          No tmux sessions are running on the host. New creates one ready for Claude.
        </div>
      ) : (
        sessions.map((s) => <SessionRow key={s.name} s={s} active={s.name === activeName} />)
      )}
      {creating ? (
        <InputDialog
          title="New session"
          label={createErr ?? NAME_HELP}
          confirmLabel="Create"
          onSubmit={create}
          onCancel={() => {
            setCreating(false)
            setCreateErr(null)
          }}
        />
      ) : null}
    </div>
  )
}
