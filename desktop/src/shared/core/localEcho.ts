// Optimistic echo for live typing, ported from the Android app's ui/LocalEcho.kt
// (the reference implementation; its unit tests are the executable spec).
//
// Characters render at the cursor the moment they are typed, before the pane
// confirms them. Without this, every keystroke waits a full round trip plus the
// screen poll tick (~200-400ms) to appear, which reads as lag even though
// delivery is fine. With it, the perceived echo is a frame.
//
// The whole design is about when NOT to guess, because a wrong prediction is a
// ghost character floating in a live pane — worse than the latency it hides:
//
//  * Only plain typed text is echoed. Enter, arrows, Tab, backspace-past-the-
//    buffer: anything whose screen effect this cannot predict MUTES the echo
//    until the next authoritative frame settles the question.
//  * The echo never invents layout. It renders inside the cursor's row and is
//    clipped at the row's end — predicting the composer's wrap is exactly where
//    ghosts come from, so it is not attempted.
//  * Every authoritative frame wins. Pending text is consumed by how far the
//    frame's cursor actually advanced; anything unexplained (row change,
//    backwards cursor, a jump longer than the buffer) clears the echo entirely.
//
// Pure, because these rules are the feature: the rendering is twenty lines, the
// judgment is here, and only one of them can be tested without a screen.

/** More pending than this means the pane has stopped confirming; stop guessing. */
export const MAX_PENDING = 60

/**
 * `text`  — characters typed since the last frame accounted for them.
 * `muted` — true after an unpredictable key: render nothing, wait for a frame.
 */
export type Echo = {
  readonly text: string
  readonly muted: boolean
}

export const emptyEcho: Echo = { text: '', muted: false }

export const visible = (e: Echo): boolean => !e.muted && e.text.length > 0

/** Cursor position as (x, y) — column then row, as tmux reports it. */
export type Cursor = readonly [number, number]

export const typed = (e: Echo, t: string): Echo => {
  if (e.muted) return e
  const next = e.text + t
  // A buffer this deep means frames stopped consuming it; guessing further
  // just builds a longer ghost.
  return next.length > MAX_PENDING ? { text: '', muted: true } : { ...e, text: next }
}

export const backspace = (e: Echo): Echo => {
  if (e.muted) return e
  if (e.text.length > 0) return { ...e, text: e.text.slice(0, -1) }
  // Deleting before our anchor: the effect on screen is unknowable here.
  return { text: '', muted: true }
}

/** Enter, arrows, Tab, control keys: effects this cannot predict. */
export const otherKey = (_e: Echo): Echo => ({ text: '', muted: true })

/**
 * An authoritative frame arrived. The cursor's actual movement says how much
 * of the pending text the pane has absorbed.
 *
 * @param prev the previous frame's cursor, null on the first frame
 * @param cur  this frame's cursor
 */
export const frame = (e: Echo, prev: Cursor | null, cur: Cursor): Echo => {
  // A frame always lifts a mute: it IS the resolution being waited for.
  if (e.muted || e.text.length === 0) return emptyEcho
  if (prev === null) return emptyEcho
  const [px, py] = prev
  const [cx, cy] = cur
  if (cy !== py) return emptyEcho // wrapped or moved: unknown land
  const advanced = cx - px
  if (advanced < 0) return emptyEcho // cursor went backwards: redraw
  if (advanced > e.text.length) return emptyEcho // moved further than we typed
  return { text: e.text.slice(advanced), muted: false }
}
