// Transcript-window maintenance for a polled tail, ported from the Android
// app's HuginnViewModel.kt (`mergeTranscript` plus the page apply in
// startTranscriptPolling); the merge cases in ReattachPlanTest.kt pin it.
//
// The daemon numbers every `?offset=` tail read from 0, so concatenated pages
// arrive with REPEATED seqs — and seq is the identity row state and list keys
// are keyed on. Two rows could claim seq 3, so opening one tool card opened
// the wrong one, and a row's expansion followed whichever event later
// inherited its number. Renumbering incoming pages onto the kept window makes
// the identity mean what callers assume; nothing client-side reads seq as the
// server's own numbering.

import type { TranscriptEvent, TranscriptPage } from '../api/types'

/**
 * Window cap: a session left open on a busy day would otherwise grow the
 * event list without limit and copy it whole on every poll.
 */
export const MAX_EVENTS = 600

const takeLast = <T>(xs: T[], n: number): T[] => (n <= 0 ? [] : xs.slice(-n))

/**
 * Appends an incremental page to the window already on screen, renumbering it
 * so `seq` stays unique — and monotonically climbing, even across trims — in
 * the result. The first page (empty `kept`) is taken as the server numbered it.
 */
export const mergeTranscript = (
  kept: TranscriptEvent[],
  incoming: TranscriptEvent[],
  cap: number,
): TranscriptEvent[] => {
  if (kept.length === 0) return takeLast(incoming, cap)
  let next = (kept[kept.length - 1]?.seq ?? -1) + 1
  const renumbered = incoming.map((ev) => ({ ...ev, seq: next++ }))
  return takeLast([...kept, ...renumbered], cap)
}

/**
 * Applies one polled tail page onto the page already on screen.
 *
 * A tail read only reports session-level fields whose records happen to fall
 * inside it, so EVERY nullable field is carried forward (`?? current`) or it
 * reverts to null seconds after the screen opens — a dropped `effort` here is
 * exactly why the mobile effort control kept falling back to a placeholder.
 * The live-state fields the wire type cannot omit (running, pending, tasks,
 * bgAgents) come from the fresh page, and `truncated` keeps the FIRST page's
 * verdict: a tail read says nothing about the head that was dropped.
 */
export const mergeTranscriptPage = (
  current: TranscriptPage | null,
  page: TranscriptPage,
  cap: number = MAX_EVENTS,
): TranscriptPage => {
  if (current === null) return page
  return {
    ...page,
    events: mergeTranscript(current.events, page.events, cap),
    title: page.title ?? current.title,
    model: page.model ?? current.model,
    modelDisplay: page.modelDisplay ?? current.modelDisplay,
    effort: page.effort ?? current.effort,
    gitBranch: page.gitBranch ?? current.gitBranch,
    permissionMode: page.permissionMode ?? current.permissionMode,
    cwd: page.cwd ?? current.cwd,
    state: page.state ?? current.state,
    claudeSessionId: page.claudeSessionId ?? current.claudeSessionId,
    mode: page.mode ?? current.mode,
    // activity is computed fresh by the server on EVERY response (liveActivity),
    // so null means "nothing in flight" — carrying it forward would freeze a
    // finished tool row on screen forever.
    activity: page.activity,
    lastActivityTs: page.lastActivityTs ?? current.lastActivityTs,
    truncated: current.truncated,
  }
}
