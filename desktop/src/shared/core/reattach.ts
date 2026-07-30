// How to pick a running chat back up, ported from the Android app's
// HuginnViewModel.kt (`reattachPlan`). Proven against the live daemon first:
// with a 793-character partial answer on screen, a `since=0` subscription
// replayed that same text from the top, so seed+replay doubled it, while
// `since=seq` resumed at character 794.

import type { ChatDetail } from '../api/types'

/** What to show and where to resume when reattaching to a running chat. */
export interface Reattach {
  seed: string
  since: number
}

/**
 * How to pick a running chat back up, or null when there is nothing to follow.
 *
 * The seed (`partialText`) and the replay are two accounts of the SAME text,
 * so the subscription has to start where the seed ends. Subscribing from 0
 * replays the deltas the seed already contains and renders the answer twice —
 * for as long as the block keeps streaming, since live deltas then append to
 * a doubled base.
 *
 * A daemon older than 2.48.0 reports no position (`seq` null). Then the
 * replay alone is the only non-doubling choice, and it is also the more
 * complete one: the seed is merely an accumulation the server kept, while the
 * replay is the same event stream that drives live rendering.
 *
 * The seed is `''` and never null even before the first token: the bubble is
 * what tells the user their message was received.
 */
export const reattachPlan = (meta: ChatDetail | null): Reattach | null => {
  if (meta === null || !meta.running) return null
  if (meta.seq === null) return { seed: '', since: 0 }
  return { seed: meta.partialText ?? '', since: meta.seq }
}
