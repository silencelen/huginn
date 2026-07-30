// Port of the Android LiveMergeTest plus desktop-only tests for the /keys wire
// contract. The Android LiveInputTest's `diff` cases are deliberately NOT
// ported: they exercise the zero-width-sentinel IME diff, which exists only
// because Android soft keyboards produce text-field edits instead of key
// events. The desktop gets real key events and skips that layer entirely.
//
// Ordering is the point of the merge tests: the per-keystroke request path the
// queue replaced could deliver "ls" as "sl".

import { describe, expect, it } from 'vitest'
import {
  BURST_MERGE_MS, isAllowedKey, MAX_KEYS_PER_REQUEST, MAX_TEXT_PER_REQUEST,
  merge, NAMED_KEYS, opKeys, opText, toWire, type Op,
} from '../../src/shared/core/liveInput'

const t = opText
const k = opKeys

describe('LiveInput.merge', () => {
  it('a typing burst becomes one request', () => {
    expect(merge([t('h'), t('e'), t('llo')])).toEqual([t('hello')])
  })

  it('keys between text split the merge, preserving order', () => {
    expect(merge([t('l'), t('s'), k('Enter'), t('c'), t('d')])).toEqual([
      t('ls'), k('Enter'), t('cd'),
    ])
  })

  it('consecutive keys merge into one request too', () => {
    expect(merge([k('BSpace'), k('BSpace'), k('Enter')])).toEqual([
      k('BSpace', 'BSpace', 'Enter'),
    ])
  })

  it('an empty queue merges to nothing', () => {
    expect(merge([])).toEqual([])
  })

  it('a single op passes through untouched', () => {
    expect(merge([t('x')])).toEqual([t('x')])
  })
})

describe('LiveInput wire contract (desktop)', () => {
  it('pins the daemon limits and the burst window', () => {
    // These mirror huginn-appd's /keys validation and the viewmodel drainer;
    // change them there first, then here.
    expect(BURST_MERGE_MS).toBe(15)
    expect(MAX_KEYS_PER_REQUEST).toBe(32)
    expect(MAX_TEXT_PER_REQUEST).toBe(8000)
  })

  it('allows every named key the daemon allows', () => {
    for (const key of [
      'Enter', 'Escape', 'Tab', 'BTab', 'Space', 'BSpace', 'DC',
      'Up', 'Down', 'Left', 'Right', 'Home', 'End', 'PPage', 'NPage',
    ]) {
      expect(NAMED_KEYS.has(key)).toBe(true)
      expect(isAllowedKey(key)).toBe(true)
    }
  })

  it('allows the modifier and function-key patterns', () => {
    for (const key of ['C-a', 'C-z', 'M-a', 'M-x', 'F1', 'F9', 'F10', 'F12']) {
      expect(isAllowedKey(key)).toBe(true)
    }
  })

  it('rejects everything outside the allowlist', () => {
    for (const key of ['C-A', 'C-1', 'M-Z', 'F0', 'F13', 'enter', 'rm -rf', '', 'C-', 'C-aa']) {
      expect(isAllowedKey(key)).toBe(false)
    }
  })

  it('maps a merged queue to ordered {text}/{keys} bodies', () => {
    expect(toWire([t('l'), t('s'), k('Enter'), t('c'), t('d')])).toEqual([
      { text: 'ls' }, { keys: ['Enter'] }, { text: 'cd' },
    ])
  })

  it('splits a key run longer than the daemon allows into full chunks', () => {
    const keys = Array.from({ length: MAX_KEYS_PER_REQUEST + 1 }, () => 'Up')
    const bodies = toWire([{ kind: 'key', keys }])
    expect(bodies).toHaveLength(2)
    expect(bodies[0]).toEqual({ keys: Array.from({ length: MAX_KEYS_PER_REQUEST }, () => 'Up') })
    expect(bodies[1]).toEqual({ keys: ['Up'] })
  })

  it('splits text longer than the daemon allows', () => {
    const bodies = toWire([t('a'.repeat(MAX_TEXT_PER_REQUEST + 1))])
    expect(bodies).toEqual([
      { text: 'a'.repeat(MAX_TEXT_PER_REQUEST) },
      { text: 'a' },
    ])
  })

  it('never splits a surrogate pair across requests', () => {
    // An astral glyph straddling the 8000-unit boundary: the chunk backs off
    // one unit so both halves of the pair travel together.
    const text = 'a'.repeat(MAX_TEXT_PER_REQUEST - 1) + '🚀b'
    const bodies = toWire([t(text)])
    expect(bodies).toEqual([
      { text: 'a'.repeat(MAX_TEXT_PER_REQUEST - 1) },
      { text: '🚀b' },
    ])
  })

  it('drops disallowed keys instead of letting them 400 the whole request', () => {
    expect(toWire([k('Enter', 'F13', 'Up')])).toEqual([{ keys: ['Enter', 'Up'] }])
  })

  it('an op of only disallowed keys produces no request at all', () => {
    expect(toWire([k('F13', 'C-A')])).toEqual([])
  })

  it('an empty queue produces no requests', () => {
    expect(toWire([] as Op[])).toEqual([])
  })
})
