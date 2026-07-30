// Ported 1:1 from the Android app's TranscriptGroupsTest.kt (the folding rule
// for subagent runs — a wrong boundary silently swallows events into the
// wrong card, which nobody reports because nobody can see it) and
// PlannedAgentsTest.kt (owner report, 2026-07-29: the work sheet counted only
// agents that had started, so it disagreed with the pane's own "N/M agents
// done" — a planned agent has no file until it runs; the denominator comes
// from the TUI row).

import { describe, expect, it } from 'vitest'
import { parseTranscriptEvent } from '../../src/shared/api/types'
import {
  group, keys, plannedAgents, type SubagentsRow,
} from '../../src/shared/core/transcriptGroups'

const ev = (seq: number, kind: string, side = false, text: string | null = null) =>
  parseTranscriptEvent({ seq, kind, sidechain: side, text })

describe('TranscriptGroups.group', () => {
  it('a plain conversation stays row for row', () => {
    const rows = group([ev(1, 'user'), ev(2, 'assistant'), ev(3, 'tool')])
    expect(rows).toHaveLength(3)
    expect(rows.every((r) => r.kind === 'single')).toBe(true)
  })

  it('consecutive sidechain events fold into one group', () => {
    const rows = group([
      ev(1, 'assistant'),
      ev(2, 'user', true, 'Search the tree for FIXME'),
      ev(3, 'thinking', true),
      ev(4, 'tool', true),
      ev(5, 'assistant', true),
      ev(6, 'assistant'),
    ])
    expect(rows).toHaveLength(3)
    const grp = rows[1] as SubagentsRow
    expect(grp.events).toHaveLength(4)
    expect(grp.task).toBe('Search the tree for FIXME')
    expect(grp.steps).toBe(3) // the prompt is the task, not a step
  })

  it('two separated fan-outs are two groups, not one', () => {
    const rows = group([ev(1, 'tool', true), ev(2, 'assistant'), ev(3, 'tool', true)])
    expect(rows).toHaveLength(3)
    expect(rows[0]?.kind).toBe('subagents')
    expect(rows[1]?.kind).toBe('single')
    expect(rows[2]?.kind).toBe('subagents')
  })

  it('a transcript ending mid-fan-out still shows the group', () => {
    // The tail case matters most: it is what a live session looks like while
    // subagents are running right now.
    const rows = group([ev(1, 'assistant'), ev(2, 'tool', true), ev(3, 'tool', true)])
    expect(rows).toHaveLength(2)
    expect((rows[1] as SubagentsRow).events).toHaveLength(2)
  })

  it('a group with no user event has no task line', () => {
    const rows = group([ev(1, 'tool', true)])
    expect((rows[0] as SubagentsRow).task).toBeNull()
  })

  it('the group key is stable while the group grows', () => {
    // Expansion state is remembered by key; a key that changed as events
    // streamed in would collapse the card the reader just opened.
    const first = group([ev(7, 'tool', true)])
    const grown = group([ev(7, 'tool', true), ev(8, 'assistant', true)])
    expect((first[0] as SubagentsRow).key).toBe((grown[0] as SubagentsRow).key)
  })

  it('an empty transcript groups to nothing', () => {
    expect(group([])).toHaveLength(0)
  })

  it('a blank task is treated as absent', () => {
    const rows = group([ev(1, 'user', true, '   ')])
    expect((rows[0] as SubagentsRow).task).toBeNull()
  })
})

describe('TranscriptGroups.keys', () => {
  it('row keys are distinct, and a Single never collides with a Subagents run', () => {
    // The list renderer throws on a duplicate key, so this is a crash guard as
    // much as an identity check.
    const rows = group([ev(1, 'user'), ev(2, 'tool', true), ev(3, 'assistant')])
    const ks = keys(rows)
    expect(ks).toHaveLength(rows.length)
    expect(new Set(ks).size).toBe(ks.length)
  })

  it('a repeated seq costs one row its anchoring, not the screen', () => {
    // Uniqueness holds by construction upstream; if that ever breaks, the list
    // must still render.
    const rows = group([ev(4, 'assistant'), ev(4, 'assistant')])
    const ks = keys(rows)
    expect(ks).toHaveLength(2)
    expect(new Set(ks).size).toBe(2)
    expect(ks[0]).toBe('s4')
  })
})

describe('plannedAgents', () => {
  it('reads the total off a real workflow row', () => {
    // Shape taken from a live pane capture.
    const lines = [
      '◯ andvari-polish-wave3  Wave 3   0/4 agents done · 7m 39s · ↓ 562.4k tokens',
    ]
    expect(plannedAgents(lines)).toBe(4)
  })

  it('takes the widest total when several rows are present', () => {
    const lines = ['◯ inner   1/3 agents done', '◯ outer   2/9 agents done']
    expect(plannedAgents(lines)).toBe(9)
  })

  it('tolerates the spacing and singular the TUI may use', () => {
    expect(plannedAgents(['Running  0 / 1  agent done'])).toBe(1)
    expect(plannedAgents(['phase two 11/12 agents done · 2m'])).toBe(12)
  })

  it('says nothing when no row reports agents', () => {
    // Must be null, not 0: the caller falls back to the file count, and a 0
    // would render "3 of 0 agents done".
    expect(plannedAgents([])).toBeNull()
    expect(plannedAgents(['Running 2 shell commands', 'Searching for 1 pattern'])).toBeNull()
  })
})
