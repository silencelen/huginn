// Ported 1:1 from the Android app's ReattachPlanTest.kt: picking a running
// chat back up without showing its answer twice. Proven against the live
// daemon first: with a 793-character partial answer on screen, a `since=0`
// subscription replayed that same text from "1. The loneliest number", so
// seed+replay doubled it; `since=seq` resumed at character 794. These pin the
// rule that produced that behaviour.

import { describe, expect, it } from 'vitest'
import { parseChatDetail, type ChatDetail } from '../../src/shared/api/types'
import { reattachPlan } from '../../src/shared/core/reattach'

const meta = (
  over: { running?: boolean; seq?: number | null; partial?: string | null } = {},
): ChatDetail =>
  parseChatDetail({
    id: 'c1',
    running: over.running ?? true,
    seq: over.seq === undefined ? 11 : over.seq,
    partialText:
      over.partial === undefined ? '1. The loneliest number.\n2. Company.' : over.partial,
  })

describe('reattachPlan', () => {
  it('resumes after the text it already shows', () => {
    const plan = reattachPlan(meta())
    expect(plan).toEqual({ seed: '1. The loneliest number.\n2. Company.', since: 11 })
  })

  it('a daemon that reports no position gets the replay instead of a seed', () => {
    // Seeding AND replaying from 0 is the doubling; with no position to resume
    // from, the replay has to be the single account of the text.
    const plan = reattachPlan(meta({ seq: null }))
    expect(plan).toEqual({ seed: '', since: 0 })
  })

  it('nothing to follow when the chat is not running', () => {
    expect(reattachPlan(meta({ running: false }))).toBeNull()
    expect(reattachPlan(null)).toBeNull()
  })

  it('a run with no text yet seeds empty rather than null', () => {
    // The bubble is what tells the user their message was received; it has to
    // exist before the first token, so the seed is '' and never null.
    const plan = reattachPlan(meta({ partial: null }))
    expect(plan).toEqual({ seed: '', since: 11 })
  })
})
