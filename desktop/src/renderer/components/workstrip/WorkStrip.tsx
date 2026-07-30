// The work strip: what this session is doing right now, pinned above the
// composer. Cheap data comes from the sessions list row (already polled);
// the per-agent fan-out is fetched at 3s ONLY while the sheet is open.

import { useEffect, useState } from 'react'
import type { AgentRun } from '../../../shared/api/types'
import { useApp } from '../../stores/app'
import { call } from '../../lib/ipc'

const elapsed = (startedAt: number, now: number): string => {
  const s = Math.max(0, now - startedAt)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m`
}

function AgentSheet({ name }: { name: string }): React.JSX.Element {
  const [agents, setAgents] = useState<AgentRun[]>([])
  const [serverTime, setServerTime] = useState(0)

  useEffect(() => {
    let alive = true
    const load = (): void => {
      void call('sessions.agents', name)
        .then((info) => {
          if (alive) {
            setAgents(info.agents)
            setServerTime(info.serverTime)
          }
        })
        .catch(() => {})
    }
    load()
    const t = setInterval(load, 3_000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [name])

  if (agents.length === 0) return <div className="agent-sheet dim">No agents recorded</div>
  return (
    <div className="agent-sheet">
      {agents.map((a) => (
        <div key={a.id} className="agent-row">
          <span className={`state-dot ${a.active ? 'dot-running' : 'dot-idle'}`} />
          <span className="agent-task">{a.task ?? a.id}</span>
          {a.workflow !== null ? <span className="agent-wf">{a.workflow}</span> : null}
          <span className="agent-line">
            {a.active ? (a.lastLine ?? '') : (a.summary ?? '')}
            {a.active ? ` · ${elapsed(a.startedAt, serverTime)}` : ''}
          </span>
        </div>
      ))}
    </div>
  )
}

export function WorkStrip({ name }: { name: string }): React.JSX.Element | null {
  const sessions = useApp((s) => s.sessions)
  const [open, setOpen] = useState(false)
  const row = sessions?.find((s) => s.name === name) ?? null
  if (row === null) return null

  const working = row.state === 'running'
  const hasBg = row.bgShells > 0 || row.bgAgents > 0
  if (!working && !hasBg) return null

  return (
    <div className="workstrip">
      <div className="workstrip-line" onClick={() => setOpen((o) => !o)}>
        {working ? <span className="pulse-dot" /> : <span className="state-dot dot-idle" />}
        <span className="workstrip-head">{working ? 'Working…' : 'Background work'}</span>
        {row.bgTask !== null ? (
          <span className="workstrip-detail">
            ⚙ {row.bgTask}
            {row.bgShells > 1 ? ` +${row.bgShells - 1}` : ''}
          </span>
        ) : null}
        {row.bgAgents > 0 ? <span className="workstrip-detail">{row.bgAgents} agents</span> : null}
        <span className="workstrip-toggle">{open ? '▾' : '▸'}</span>
      </div>
      {open ? <AgentSheet name={name} /> : null}
    </div>
  )
}
