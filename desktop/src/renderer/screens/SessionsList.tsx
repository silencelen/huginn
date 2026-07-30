// The sessions list pane: live tmux sessions with state dots, Claude's own
// session titles, background-work lines, and pane previews. Detail view lands
// in phase 2 — the list is already useful for seeing what needs you.

import type { Session } from '../../shared/api/types'
import { useApp } from '../stores/app'

const stateClass = (state: string | null): string => {
  switch (state) {
    case 'running':
      return 'dot-running'
    case 'attention':
      return 'dot-attention'
    case 'idle':
      return 'dot-idle'
    default:
      return 'dot-none'
  }
}

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
        <span className="row-time">
          {s.cols}x{s.rows}
        </span>
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
      </div>
      {s.preview.length > 0 ? <div className="row-preview">{s.preview.join('\n')}</div> : null}
    </div>
  )
}

export function SessionsList({ activeName }: { activeName: string | null }): React.JSX.Element {
  const sessions = useApp((s) => s.sessions)
  const unavailable = useApp((s) => s.sessionsUnavailable)

  return (
    <div className="list">
      <div className="list-header">
        <span>Sessions</span>
      </div>
      {unavailable ? (
        <div className="pane-placeholder">tmux unreachable</div>
      ) : sessions === null || sessions.length === 0 ? (
        <div className="pane-placeholder">No sessions</div>
      ) : (
        sessions.map((s) => <SessionRow key={s.name} s={s} active={s.name === activeName} />)
      )}
    </div>
  )
}
