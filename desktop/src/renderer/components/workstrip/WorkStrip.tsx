// The work strip: what this session is doing right now, pinned above the
// composer — and, for a few minutes, what it just finished doing. Cheap data
// comes from the sessions list row (already polled); the per-agent fan-out is
// fetched at 3s ONLY while the sheet is open, plus once at the moment work
// ends.
//
// That last fetch is the point of the settled state. Each agent's own
// conclusion (`summary`, written to the workflow journal when it settles) only
// EXISTS once the agent is done — so a strip that unmounted the instant
// `running` went false took the sheet away at exactly the moment it became
// worth opening. The strip now stays, dimmed, saying how many agents finished.

import { useCallback, useEffect, useRef, useState } from 'react'
import type { AgentRun, AgentsInfo } from '../../../shared/api/types'
import { plannedAgents } from '../../../shared/core/transcriptGroups'
import { useApp } from '../../stores/app'
import { call } from '../../lib/ipc'

/**
 * How long the strip advertises finished work. Android keeps agent rows for 45
 * minutes (the daemon's own retention window), but a phone shows the sheet on
 * demand while this strip occupies a row above the composer permanently — so it
 * says its piece for five minutes and then gets out of the way. Nothing is lost
 * either way: the daemon still holds the agents for the full 45, the strip just
 * stops pointing at them. The countdown does not run while the sheet is open.
 */
const SETTLED_MS = 5 * 60_000

const AGENTS_POLL_MS = 3_000

const elapsed = (startedAt: number, now: number): string => {
  const s = Math.max(0, now - startedAt)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m`
}

/** Agent prompts routinely open with a boilerplate header; the ask is after it. */
const taskLabel = (a: AgentRun): string =>
  (a.task ?? a.id).replace(/^CONTEXT:\s*/, '').trim() || a.id

function AgentSheet({
  info,
  planned,
}: {
  info: AgentsInfo | null
  planned: number | null
}): React.JSX.Element {
  if (info === null) return <div className="agent-sheet dim">Agents…</div>
  const list = info.agents
  if (list.length === 0 && planned === null) {
    return <div className="agent-sheet dim">No agents recorded</div>
  }
  const done = list.filter((a) => !a.active).length
  // The TUI's own denominator, which counts agents PLANNED but not yet started.
  // Counting the agent FILES undercounts for exactly as long as a fan-out is
  // interesting to look at — a planned agent has no file until it runs, so
  // "1 of 2 agents" sat beside the pane's own "1/6 agents done" and read as a
  // different fan-out. Same number, same phrasing, taken from the row Claude
  // Code already prints. The file count still wins if it is somehow larger.
  const total = Math.max(planned ?? list.length, list.length)
  return (
    <div className="agent-sheet">
      <div className="agent-row">
        <span className="agent-line">
          {done} of {total} agent{total === 1 ? '' : 's'} done
        </span>
      </div>
      {list.map((a) => (
        <div key={a.id} className="agent-row">
          <span className={`state-dot ${a.active ? 'dot-running' : 'dot-idle'}`} />
          <span className="agent-task">{taskLabel(a)}</span>
          {a.workflow !== null ? <span className="agent-wf">{a.workflow}</span> : null}
          <span className="agent-line">
            {/* A settled agent's own conclusion beats its last tool call as an
                epitaph; the live line stays for agents still working. */}
            {a.active ? (a.lastLine ?? '') : (a.summary ?? a.lastLine ?? '')}
            {a.active ? ` · ${elapsed(a.startedAt, info.serverTime)}` : ''}
          </span>
        </div>
      ))}
    </div>
  )
}

export function WorkStrip({ name }: { name: string }): React.JSX.Element | null {
  const sessions = useApp((s) => s.sessions)
  const [open, setOpen] = useState(false)
  const [agents, setAgents] = useState<AgentsInfo | null>(null)
  const [planned, setPlanned] = useState<number | null>(null)
  const [settled, setSettled] = useState(false)
  const wasLiveRef = useRef(false)

  const row = sessions?.find((s) => s.name === name) ?? null
  const working = row?.state === 'running'
  const hasBg = (row?.bgShells ?? 0) > 0 || (row?.bgAgents ?? 0) > 0
  const live = working || hasBg

  // Every agents read goes through here so a response for the session we just
  // navigated away from cannot land on the new one's strip.
  const nameRef = useRef(name)
  nameRef.current = name
  const loadAgents = useCallback((): void => {
    void call('sessions.agents', name)
      .then((info) => {
        if (nameRef.current === name) setAgents(info)
      })
      .catch(() => {})
  }, [name])

  // Another session: nothing carried over, including whether the last one had
  // just finished something.
  useEffect(() => {
    setOpen(false)
    setAgents(null)
    setPlanned(null)
    setSettled(false)
    wasLiveRef.current = false
  }, [name])

  // The live → settled edge: hold the strip open and read the agents ONCE, so
  // the collapsed line can say how many finished without the sheet being open.
  useEffect(() => {
    if (live) {
      wasLiveRef.current = true
      setSettled(false)
      setPlanned(null)
      return
    }
    if (!wasLiveRef.current) return
    wasLiveRef.current = false
    setSettled(true)
    loadAgents()
  }, [live, loadAgents])

  // Settled work ages out — but never out from under a reader who has the
  // sheet open; closing it restarts the window.
  useEffect(() => {
    if (!settled || open) return
    const t = setTimeout(() => setSettled(false), SETTLED_MS)
    return () => clearTimeout(t)
  }, [settled, open])

  // Agents are files on huginn, and reading two dozen transcript tails every
  // three seconds is a cost worth paying only while somebody is looking.
  useEffect(() => {
    if (!open) return
    loadAgents()
    const t = setInterval(loadAgents, AGENTS_POLL_MS)
    return () => clearInterval(t)
  }, [open, loadAgents])

  // The pane's own progress rows, for plannedAgents. Read off the pane, so only
  // while the sheet is open AND there is still work whose total can grow: once
  // the fan-out settles the TUI stops printing the row anyway, and the last
  // total it printed is the one that counts.
  useEffect(() => {
    if (!open || !live) return
    const tick = (): void => {
      void call('sessions.screen.once', name, {})
        .then((s) => {
          const n = plannedAgents(s.statusLines)
          if (nameRef.current === name && n !== null) setPlanned(n)
        })
        .catch(() => {})
    }
    tick()
    const t = setInterval(tick, AGENTS_POLL_MS)
    return () => clearInterval(t)
  }, [open, live, name])

  if (row === null) return null
  if (!live && !settled) return null

  const finished = agents === null ? 0 : agents.agents.filter((a) => !a.active).length
  const settledHead =
    finished > 0 ? `${finished} agent${finished === 1 ? '' : 's'} finished` : 'Work finished'

  return (
    <div className="workstrip">
      <div className="workstrip-line" onClick={() => setOpen((o) => !o)}>
        {working ? <span className="pulse-dot" /> : <span className="state-dot dot-idle" />}
        {live ? (
          <span className="workstrip-head">{working ? 'Working…' : 'Background work'}</span>
        ) : (
          <span className="workstrip-head dim">{settledHead}</span>
        )}
        {live && row.bgTask !== null ? (
          <span className="workstrip-detail">
            ⚙ {row.bgTask}
            {row.bgShells > 1 ? ` +${row.bgShells - 1}` : ''}
          </span>
        ) : null}
        {live && row.bgAgents > 0 ? (
          <span className="workstrip-detail">{row.bgAgents} agents</span>
        ) : null}
        <span className="workstrip-toggle">{open ? '▾' : '▸'}</span>
      </div>
      {open ? <AgentSheet info={agents} planned={planned} /> : null}
    </div>
  )
}
