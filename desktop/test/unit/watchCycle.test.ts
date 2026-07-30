// Ported 1:1 from the Android app's WatchCycleTest.kt: the rule that decides
// whether a chat finished while nobody was looking. Worth its own tests
// because the background check runs on a schedule, so "a chat that started
// and finished between two observations" is the normal case rather than an
// edge case — and the obvious implementation, diffing the set of running
// chats, silently reports nothing at all for it.

import { describe, expect, it } from 'vitest'
import { finishedSince } from '../../src/shared/core/watchCycle'

describe('finishedSince', () => {
  it('a chat seen running and then not has finished', () => {
    const finished = finishedSince({ c1: 0 }, { c1: 0 }, new Set(['c1']), new Set())
    expect(finished).toEqual(new Set(['c1']))
  })

  it('a chat that ran entirely between two looks still counts as finished', () => {
    const finished = finishedSince(
      { c1: 3 },
      { c1: 4 },
      // Never observed running: the set diff alone would report nothing.
      new Set(),
      new Set(),
    )
    expect(finished).toEqual(new Set(['c1']))
  })

  it('several runs inside one gap report the chat once, not once per run', () => {
    const finished = finishedSince({ c1: 1 }, { c1: 5 }, new Set(), new Set())
    expect(finished).toEqual(new Set(['c1']))
  })

  it('a chat that finished and immediately started again is reported', () => {
    const finished = finishedSince(
      { c1: 1 },
      { c1: 2 },
      new Set(['c1']),
      new Set(['c1']), // still running, so no edge to see
    )
    expect(finished).toEqual(new Set(['c1']))
  })

  it('a chat still running with no completed run is not reported', () => {
    const finished = finishedSince({ c1: 2 }, { c1: 2 }, new Set(['c1']), new Set(['c1']))
    expect(finished.size).toBe(0)
  })

  it('nothing happening reports nothing', () => {
    const finished = finishedSince({ c1: 7 }, { c1: 7 }, new Set(), new Set())
    expect(finished.size).toBe(0)
  })

  it('a chat never seen before is not announced on its history', () => {
    // The trap this guards: a chat with no recorded baseline has a counter
    // describing its whole history. Treating that as news would make the first
    // look after an install announce every chat ever run.
    const finished = finishedSince({}, { 'old-chat': 42 }, new Set(), new Set())
    expect(finished.size).toBe(0)
  })

  it('a deleted chat that was running is still reported as finished', () => {
    const finished = finishedSince(
      { c1: 1 },
      {}, // gone from the snapshot entirely
      new Set(['c1']),
      new Set(),
    )
    expect(finished).toEqual(new Set(['c1']))
  })

  it('two chats finishing at once are both reported', () => {
    const finished = finishedSince(
      { c1: 0, c2: 0 },
      { c1: 1, c2: 0 },
      new Set(['c2']),
      new Set(),
    )
    expect(finished).toEqual(new Set(['c1', 'c2']))
  })

  it('a counter that somehow went backwards is not a finish', () => {
    const finished = finishedSince({ c1: 5 }, { c1: 2 }, new Set(), new Set())
    expect(finished.size).toBe(0)
  })
})
