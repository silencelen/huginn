// Tooltip copy, as pure functions.
//
// Kept apart from the tooltip component for two reasons. One, the wording is
// then unit-testable, and wording is the whole point of a tooltip — a hover
// that says "running" over a dot that is already blue has told you nothing.
// Two, every caller has to go through here, so nothing can invent data: each
// formatter takes only fields the row it describes already renders, and returns
// null when there is nothing true to say.

/** Plain duration, in the unit a person would say out loud. */
export function duration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  if (s < 60) return 'less than a minute'
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} minute${m === 1 ? '' : 's'}`
  const h = Math.floor(m / 60)
  if (h < 48) return `${h} hour${h === 1 ? '' : 's'}`
  return `${Math.floor(h / 24)} days`
}

const plural = (n: number, word: string): string => `${n} ${word}${n === 1 ? '' : 's'}`

/** The dot colours in the two lists mean these things. */
const STATE_LABEL: Record<string, string> = {
  running: 'Working',
  attention: 'Needs input',
  idle: 'Idle',
}

export interface SessionDotFacts {
  state: string | null
  /** When the session entered `state`; null when the host never recorded one. */
  stateSince: number | null
  activityAt: number
}

/**
 * A session row's state dot. `stateSince` is the honest answer to "how long" —
 * the fallback to last activity is for hosts that never reported a transition,
 * and says so by naming a different clock rather than pretending.
 */
export function sessionDotTip(s: SessionDotFacts, nowSec: number): string {
  const label = s.state === null ? 'No state reported' : (STATE_LABEL[s.state] ?? s.state)
  if (s.state !== null && s.stateSince !== null && s.stateSince > 0) {
    return `${label} for ${duration(nowSec - s.stateSince)}`
  }
  if (s.activityAt > 0) return `${label} · last activity ${duration(nowSec - s.activityAt)} ago`
  return label
}

/** A chat row's dot, which the list renders only while a run is live. */
export function chatDotTip(c: { running: boolean; updatedAt: number }, nowSec: number): string | null {
  if (!c.running) return null
  if (c.updatedAt > 0) return `Working · last update ${duration(nowSec - c.updatedAt)} ago`
  return 'Working'
}

/** The `+N queued` badge: what those messages are waiting for. */
export function queuedTip(pending: number): string | null {
  if (pending <= 0) return null
  return `${plural(pending, 'message')} queued behind the current reply`
}

/** The rail's connection dot — stated in terms of what it costs you. */
export function connectionTip(connected: boolean): string {
  return connected
    ? 'Connected to huginn · notifications arrive as they happen'
    : 'Not connected · notifications will not arrive until this reconnects'
}

/** The work strip's `⚙ task +N` count. */
export function bgShellsTip(bgShells: number, bgTask: string | null): string {
  const head = bgShells > 0 ? `${plural(bgShells, 'background shell')} running` : 'Background shell running'
  return bgTask === null || bgTask === '' ? head : `${head} · longest: ${bgTask}`
}

/** The work strip's `N agents` count. */
export function bgAgentsTip(bgAgents: number): string | null {
  if (bgAgents <= 0) return null
  return `${plural(bgAgents, 'subagent')} running for this session`
}

/**
 * The agent sheet's `X of Y agents done` line. Y can exceed the number of agent
 * files, because it counts agents the pane has PLANNED but not yet started —
 * which is exactly the part of that line a reader squints at.
 */
export function agentsDoneTip(done: number, total: number): string {
  const left = Math.max(0, total - done)
  if (left === 0) return `All ${plural(total, 'agent')} finished`
  if (done === 0) return `${plural(left, 'agent')} still working`
  return `${done} finished · ${left} still working`
}

/** A single agent row's dot inside the sheet. */
export function agentDotTip(
  a: { active: boolean; startedAt: number },
  serverTimeSec: number,
): string {
  if (!a.active) return 'Finished'
  if (a.startedAt > 0) return `Working for ${duration(serverTimeSec - a.startedAt)}`
  return 'Working'
}
