// The sessions list pane: live tmux sessions with state dots, Claude's own
// session titles, background-work lines, and pane previews. Same dot language
// as the chats list: pulsing accent = working, amber = needs you, dim = idle.

import { useEffect, useRef, useState } from 'react'
import type { Session } from '../../shared/api/types'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { isValidSessionName } from '../../shared/api/routes'
import { ConfirmDialog, InputDialog } from '../components/common/Dialog'
import { useContextMenu } from '../components/common/ContextMenu'
import { onNewSessionRequest, useKeyboardNav } from '../hooks/useShortcuts'

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

function SessionRow(props: {
  s: Session
  active: boolean
  onRename: (s: Session) => void
  onInterrupt: (s: Session) => void
  onKill: (s: Session) => void
}): React.JSX.Element {
  const { s, active } = props
  const navigate = useApp((st) => st.navigate)
  const open = (): void => navigate({ view: 'sessions', sessionName: s.name })
  const ctx = useContextMenu()

  const kbNav = useKeyboardNav()
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (active && kbNav) ref.current?.scrollIntoView({ block: 'nearest' })
  }, [active, kbNav])

  return (
    <div
      ref={ref}
      className={`row ${active ? 'row-active' : ''} ${active && kbNav ? 'row-selected' : ''}`}
      onClick={open}
      onContextMenu={ctx.onContextMenu}
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
      {ctx.menu([
        { label: 'Open', onClick: open },
        { label: 'Rename', onClick: () => props.onRename(s) },
        { label: 'Interrupt (Esc)', onClick: () => props.onInterrupt(s) },
        { label: 'Kill', danger: true, onClick: () => props.onKill(s) },
      ])}
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
  const [renaming, setRenaming] = useState<Session | null>(null)
  const [renameErr, setRenameErr] = useState<string | null>(null)
  const [killing, setKilling] = useState<Session | null>(null)
  const [error, setError] = useState<string | null>(null)

  // The palette's "New session" verb opens this pane's own dialog rather than
  // carrying a second copy of the name rules.
  useEffect(() => onNewSessionRequest(() => setCreating(true)), [])

  const fail = (e: unknown): void => setError(e instanceof Error ? e.message : String(e))

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

  const rename = (raw: string): void => {
    if (renaming === null) return
    const from = renaming.name
    const to = raw.trim().toLowerCase()
    if (!isValidSessionName(to)) {
      setRenameErr(`That name will not work. ${NAME_HELP}`)
      return
    }
    void call('sessions.rename', from, to)
      .then(async () => {
        setRenaming(null)
        setRenameErr(null)
        await refreshSessions()
        // The open session just changed identity; follow it.
        if (activeName === from) navigate({ view: 'sessions', sessionName: to })
      })
      .catch((e: unknown) => setRenameErr(e instanceof Error ? e.message : String(e)))
  }

  const interrupt = (s: Session): void => {
    void call('sessions.keys', s.name, { keys: ['Escape'] }).catch(fail)
  }

  const kill = (): void => {
    if (killing === null) return
    const name = killing.name
    setKilling(null)
    void call('sessions.kill', name)
      .then(async () => {
        await refreshSessions()
        if (activeName === name) navigate({ view: 'sessions', sessionName: null })
      })
      .catch(fail)
  }

  return (
    <div className="list">
      <div className="list-header">
        <span>Sessions</span>
        <button type="button" className="list-new" onClick={() => setCreating(true)}>
          + New
        </button>
      </div>
      {error !== null ? (
        <div className="list-note" title="Dismiss" onClick={() => setError(null)}>
          {error}
        </div>
      ) : null}
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
        sessions.map((s) => (
          <SessionRow
            key={s.name}
            s={s}
            active={s.name === activeName}
            onRename={setRenaming}
            onInterrupt={interrupt}
            onKill={setKilling}
          />
        ))
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
      {renaming !== null ? (
        <InputDialog
          title="Rename session"
          label={renameErr ?? NAME_HELP}
          initial={renaming.name}
          confirmLabel="Rename"
          onSubmit={rename}
          onCancel={() => {
            setRenaming(null)
            setRenameErr(null)
          }}
        />
      ) : null}
      {killing !== null ? (
        <ConfirmDialog
          title="Kill session"
          body={`Kill "${killing.name}"? Everything running in the pane stops.`}
          confirmLabel="Kill"
          danger
          onConfirm={kill}
          onCancel={() => setKilling(null)}
        />
      ) : null}
    </div>
  )
}
