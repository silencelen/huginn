// Port of the Android LocalEchoTest: the judgment calls of optimistic echo.
// A wrong guess here is a ghost character floating in a live pane — worse than
// the latency it hides — so most of these tests are about NOT rendering.

import { describe, expect, it } from 'vitest'
import {
  backspace, emptyEcho, frame, MAX_PENDING, otherKey, typed, visible, type Echo,
} from '../../src/shared/core/localEcho'

const muted: Echo = { text: '', muted: true }

describe('LocalEcho', () => {
  it('typed characters accumulate and are visible', () => {
    let e = emptyEcho
    e = typed(e, 'l')
    e = typed(e, 's')
    expect(e.text).toBe('ls')
    expect(visible(e)).toBe(true)
  })

  it('backspace eats the last pending character', () => {
    let e = typed(emptyEcho, 'ab')
    e = backspace(e)
    expect(e.text).toBe('a')
  })

  it('backspace past the buffer mutes - the effect on screen is unknowable', () => {
    const e = backspace(emptyEcho)
    expect(e.muted).toBe(true)
    expect(visible(e)).toBe(false)
  })

  it('unpredictable keys mute until a frame settles things', () => {
    const e = otherKey(typed(emptyEcho, 'half'))
    expect(e.muted).toBe(true)
    expect(visible(e)).toBe(false)
  })

  it('typing while muted stays muted - no guessing on unknown ground', () => {
    const e = typed(muted, 'x')
    expect(e.muted).toBe(true)
    expect(e.text).toBe('')
  })

  it("a frame consumes exactly the cursor's advance", () => {
    const e = typed(emptyEcho, 'abc')
    // The pane confirmed two characters: cursor moved 2 cells on the same row.
    const after = frame(e, [10, 5], [12, 5])
    expect(after.text).toBe('c')
    expect(visible(after)).toBe(true)
  })

  it('a frame that consumes everything leaves nothing pending', () => {
    const e = typed(emptyEcho, 'ab')
    expect(frame(e, [4, 2], [6, 2]).text).toBe('')
  })

  it('a row change clears - wraps are exactly where ghosts come from', () => {
    const e = typed(emptyEcho, 'abcdef')
    expect(frame(e, [78, 5], [4, 6]).text).toBe('')
  })

  it('a backwards cursor clears - the pane redrew, all bets off', () => {
    const e = typed(emptyEcho, 'abc')
    expect(frame(e, [10, 5], [2, 5]).text).toBe('')
  })

  it('an advance longer than the buffer clears rather than inventing text', () => {
    const e = typed(emptyEcho, 'ab')
    expect(frame(e, [10, 5], [20, 5]).text).toBe('')
  })

  it('a frame lifts a mute - it is the resolution being waited for', () => {
    const e = frame(muted, [3, 1], [4, 1])
    expect(e.muted).toBe(false)
    expect(e.text).toBe('')
  })

  it('the first frame ever clears rather than guessing at history', () => {
    const e = typed(emptyEcho, 'abc')
    expect(frame(e, null, [5, 5]).text).toBe('')
  })

  it('a runaway buffer mutes instead of building a longer ghost', () => {
    let e = emptyEcho
    for (let i = 0; i < MAX_PENDING + 1; i++) e = typed(e, 'x')
    expect(e.muted).toBe(true)
  })
})
