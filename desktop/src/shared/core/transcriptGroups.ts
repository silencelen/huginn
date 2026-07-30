// Folds a transcript's subagent activity into visible, openable rows, ported
// from the Android app's TranscriptGroups.kt (TranscriptGroupsTest.kt is the
// spec), plus `plannedAgents` from SessionScreen.kt. Subagent events used to
// render inline with a small indent — truthful and useless in practice:
// during a fan-out the sidechain is most of the transcript, so the main
// thread drowned in its own helpers. A run of consecutive sidechain events is
// one unit of delegated work, so it becomes one row. Pure and separate from
// the rendering so the folding rule is testable: a wrong boundary silently
// swallows events into the wrong card, which is the kind of bug nobody
// reports because nobody can see it.

import type { TranscriptEvent } from '../api/types'

/** An ordinary main-thread event. */
export interface SingleRow {
  kind: 'single'
  event: TranscriptEvent
}

/** A consecutive run of subagent events, shown as one openable unit. */
export interface SubagentsRow {
  kind: 'subagents'
  events: TranscriptEvent[]
  /**
   * The task the subagent was given: its first user event, which is the
   * prompt the parent wrote for it — the parent's own description of the
   * work, and so the best possible one-line summary.
   */
  task: string | null
  /** Steps worth counting: what it said and did, not its own prompts. */
  steps: number
  /** A stable identity for expansion state: where the run starts. */
  key: number
}

export type Row = SingleRow | SubagentsRow

/** Builds a Subagents row from a non-empty run of sidechain events. */
const subagentsRow = (events: TranscriptEvent[]): SubagentsRow => {
  const prompt = events.find((ev) => ev.kind === 'user')?.text?.trim()
  return {
    kind: 'subagents',
    events,
    task: prompt ? prompt : null,
    steps: events.filter((ev) => ev.kind !== 'user').length,
    key: events[0]?.seq ?? 0,
  }
}

export const group = (events: TranscriptEvent[]): Row[] => {
  const out: Row[] = []
  let run: TranscriptEvent[] | null = null
  for (const ev of events) {
    if (ev.sidechain) {
      if (run === null) run = []
      run.push(ev)
    } else {
      if (run !== null) out.push(subagentsRow(run))
      run = null
      out.push({ kind: 'single', event: ev })
    }
  }
  if (run !== null) out.push(subagentsRow(run))
  return out
}

/**
 * A row's identity for list keys and saved state.
 *
 * Both conversation lists were POSITIONAL, so once the retained window hit
 * its cap every poll dropped events off the front and shifted every index —
 * the content slid under a reader who was scrolled up looking at something.
 * Keyed on the row's first event instead, the viewport stays put. The kind
 * prefix keeps a Single and a Subagents run starting at the same seq from
 * colliding.
 */
const keyOf = (row: Row): string =>
  row.kind === 'single' ? `s${row.event.seq}` : `a${row.key}`

/**
 * Keys for a whole row list, guaranteed distinct.
 *
 * The list renderer CRASHES on a duplicate key, which would take out the
 * entire conversation view — far worse than the drifting scroll position the
 * keys were added to fix. Uniqueness is supposed to hold by construction (the
 * merge renumbers, and one page's seqs are unique), so a collision means an
 * upstream assumption broke; the `#n` suffix costs that row its anchoring
 * instead of costing the reader the screen.
 */
export const keys = (rows: Row[]): string[] => {
  const seen = new Set<string>()
  return rows.map((row) => {
    const base = keyOf(row)
    if (!seen.has(base)) {
      seen.add(base)
      return base
    }
    let n = 2
    while (seen.has(`${base}#${n}`)) n++
    const suffixed = `${base}#${n}`
    seen.add(suffixed)
    return suffixed
  })
}

/**
 * How many agents the fan-out PLANNED, read off the TUI's own progress row
 * ("0/4 agents done"), or null when no row says so.
 *
 * It has to come from here rather than from the agent files: a planned agent
 * has no file until it starts, so counting files undercounts the total for
 * exactly as long as the fan-out is interesting to look at. Takes the largest
 * total on screen, since several workflow rows can be present and the widest
 * is the job. Null, never 0, when nothing matches: the caller falls back to
 * the file count, and a 0 would render "3 of 0 agents done".
 */
export const plannedAgents = (statusLines: string[]): number | null => {
  let widest: number | null = null
  for (const line of statusLines) {
    const m = /(\d+)\s*\/\s*(\d+)\s+agents?\s+done/.exec(line)
    if (m === null) continue
    const total = Number(m[2])
    if (Number.isFinite(total) && (widest === null || total > widest)) widest = total
  }
  return widest
}
