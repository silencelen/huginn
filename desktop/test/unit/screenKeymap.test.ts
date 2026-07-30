// The live-typing key mapping: browser KeyboardEvent fields → liveInput ops.
// Everything it emits must survive liveInput's wire allowlist, so the two are
// checked against each other at the bottom.

import { describe, expect, it } from 'vitest'
import { eventToOp, type KeyLike } from '../../src/renderer/components/terminal/keymap'
import { isAllowedKey } from '../../src/shared/core/liveInput'

const ev = (key: string, mods: Partial<Omit<KeyLike, 'key'>> = {}): KeyLike => ({
  key,
  ctrlKey: false,
  altKey: false,
  metaKey: false,
  shiftKey: false,
  ...mods,
})

describe('eventToOp', () => {
  it('printable characters become text ops, space included', () => {
    expect(eventToOp(ev('a'))).toEqual({ kind: 'text', text: 'a' })
    expect(eventToOp(ev('Ö'))).toEqual({ kind: 'text', text: 'Ö' })
    expect(eventToOp(ev(' '))).toEqual({ kind: 'text', text: ' ' })
    expect(eventToOp(ev('/'))).toEqual({ kind: 'text', text: '/' })
  })

  it('shifted printables stay literal (shift already shaped e.key)', () => {
    expect(eventToOp(ev('A', { shiftKey: true }))).toEqual({ kind: 'text', text: 'A' })
    expect(eventToOp(ev('?', { shiftKey: true }))).toEqual({ kind: 'text', text: '?' })
  })

  it('named keys map to tmux names', () => {
    expect(eventToOp(ev('Enter'))).toEqual({ kind: 'key', keys: ['Enter'] })
    expect(eventToOp(ev('Escape'))).toEqual({ kind: 'key', keys: ['Escape'] })
    expect(eventToOp(ev('Backspace'))).toEqual({ kind: 'key', keys: ['BSpace'] })
    expect(eventToOp(ev('Delete'))).toEqual({ kind: 'key', keys: ['DC'] })
    expect(eventToOp(ev('ArrowUp'))).toEqual({ kind: 'key', keys: ['Up'] })
    expect(eventToOp(ev('PageUp'))).toEqual({ kind: 'key', keys: ['PPage'] })
    expect(eventToOp(ev('PageDown'))).toEqual({ kind: 'key', keys: ['NPage'] })
    expect(eventToOp(ev('Home'))).toEqual({ kind: 'key', keys: ['Home'] })
  })

  it('tab and shift+tab differ', () => {
    expect(eventToOp(ev('Tab'))).toEqual({ kind: 'key', keys: ['Tab'] })
    expect(eventToOp(ev('Tab', { shiftKey: true }))).toEqual({ kind: 'key', keys: ['BTab'] })
  })

  it('control and alt chords map to C-x / M-x', () => {
    expect(eventToOp(ev('c', { ctrlKey: true }))).toEqual({ kind: 'key', keys: ['C-c'] })
    expect(eventToOp(ev('x', { altKey: true }))).toEqual({ kind: 'key', keys: ['M-x'] })
  })

  it('F keys pass through', () => {
    expect(eventToOp(ev('F1'))).toEqual({ kind: 'key', keys: ['F1'] })
    expect(eventToOp(ev('F12'))).toEqual({ kind: 'key', keys: ['F12'] })
  })

  it('OS chords, bare modifiers, and unknown keys are dropped', () => {
    expect(eventToOp(ev('c', { metaKey: true }))).toBeNull()
    expect(eventToOp(ev('Control', { ctrlKey: true }))).toBeNull()
    expect(eventToOp(ev('Shift', { shiftKey: true }))).toBeNull()
    expect(eventToOp(ev('Alt', { altKey: true }))).toBeNull()
    expect(eventToOp(ev('CapsLock'))).toBeNull()
    expect(eventToOp(ev('1', { ctrlKey: true }))).toBeNull()
  })

  it('every named key it can emit passes the wire allowlist', () => {
    const probes: KeyLike[] = [
      ev('Enter'), ev('Escape'), ev('Backspace'), ev('Delete'),
      ev('ArrowUp'), ev('ArrowDown'), ev('ArrowLeft'), ev('ArrowRight'),
      ev('Home'), ev('End'), ev('PageUp'), ev('PageDown'),
      ev('Tab'), ev('Tab', { shiftKey: true }), ev('F5'),
      ev('q', { ctrlKey: true }), ev('b', { altKey: true }),
    ]
    for (const p of probes) {
      const op = eventToOp(p)
      expect(op).not.toBeNull()
      if (op !== null && op.kind === 'key') {
        for (const k of op.keys) expect(isAllowedKey(k), k).toBe(true)
      }
    }
  })
})
