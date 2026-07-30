// KeyboardEvent → live-input ops for the focus-capture layer. Pure: takes the
// few fields of the event that matter, returns an op for liveInput's queue (or
// null for keys that must NOT reach the pane, like bare modifiers and OS
// chords). The wire allowlist lives in liveInput; this maps browser names to
// the tmux names that allowlist accepts.

import { opKeys, opText, type Op } from '../../../shared/core/liveInput'

export interface KeyLike {
  readonly key: string
  readonly ctrlKey: boolean
  readonly altKey: boolean
  readonly metaKey: boolean
  readonly shiftKey: boolean
}

/** Browser key names → tmux named keys (liveInput.NAMED_KEYS vocabulary). */
const NAMED: Readonly<Record<string, string>> = {
  Enter: 'Enter',
  Escape: 'Escape',
  Backspace: 'BSpace',
  Delete: 'DC',
  ArrowUp: 'Up',
  ArrowDown: 'Down',
  ArrowLeft: 'Left',
  ArrowRight: 'Right',
  Home: 'Home',
  End: 'End',
  PageUp: 'PPage',
  PageDown: 'NPage',
}

export const eventToOp = (e: KeyLike): Op | null => {
  // OS/window chords (Cmd/Win) never belong to the pane.
  if (e.metaKey) return null
  if (e.ctrlKey) {
    const k = e.key.toLowerCase()
    return k.length === 1 && k >= 'a' && k <= 'z' && !e.altKey ? opKeys(`C-${k}`) : null
  }
  if (e.altKey) {
    const k = e.key.toLowerCase()
    return k.length === 1 && k >= 'a' && k <= 'z' ? opKeys(`M-${k}`) : null
  }
  if (e.key === 'Tab') return opKeys(e.shiftKey ? 'BTab' : 'Tab')
  const named = NAMED[e.key]
  if (named !== undefined) return opKeys(named)
  if (/^F([1-9]|1[0-2])$/.test(e.key)) return opKeys(e.key)
  // Printable characters (including space) travel as literal text. A single
  // code point, which covers surrogate-pair keys (e.key.length can be 2).
  if ([...e.key].length === 1) return opText(e.key)
  return null
}
