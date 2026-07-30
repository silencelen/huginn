// Live typing straight into the pane, keystroke by keystroke — the pure core,
// ported from the Android app's ui/LiveInput.kt plus the drainer semantics of
// HuginnViewModel.sendLive and the /keys validation in huginn-appd (the wire
// contract). Deliberately NOT ported: the zero-width-sentinel IME diff. That
// exists because Android has no key events for soft keyboards, only text-field
// edits; the desktop gets real key events, so its input layer feeds ops into
// this queue directly.
//
// Why an ordered op queue at all: the path this replaced launched an
// independent request per keystroke, and independent requests are not ordered —
// type "ls" fast enough and the pane could receive "sl". A single drainer
// sending merged ops sequentially makes ordering a property of the design
// instead of a property of network luck. Fewer round trips is the bonus: a
// burst of six characters becomes one request.

/** One thing to deliver to the pane, in order. */
export type Op =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'key'; readonly keys: readonly string[] }

export const opText = (text: string): Op => ({ kind: 'text', text })
export const opKeys = (...keys: string[]): Op => ({ kind: 'key', keys })

/**
 * How long the drainer waits before draining, so a keystroke burst accumulates
 * and merges: keystrokes arrive faster than round trips complete, and merging
 * them is the point. (From HuginnViewModel.sendLive's `delay(15)`.)
 */
export const BURST_MERGE_MS = 15

/** Coalesces queued keystrokes into the fewest ops that preserve order. */
export const merge = (ops: readonly Op[]): Op[] => {
  const out: Op[] = []
  for (const op of ops) {
    const last = out[out.length - 1]
    if (op.kind === 'text' && last?.kind === 'text') {
      out[out.length - 1] = { kind: 'text', text: last.text + op.text }
    } else if (op.kind === 'key' && last?.kind === 'key') {
      out[out.length - 1] = { kind: 'key', keys: [...last.keys, ...op.keys] }
    } else {
      out.push(op)
    }
  }
  return out
}

// ----------------------------------------------------- the /keys wire contract
//
// POST /v1/sessions/<name>/keys accepts {text?, keys?[]}. The daemon REJECTS
// (400s the whole request, allowed keys included) anything outside these
// limits, so the client must never construct a request that crosses them.

/**
 * Named keys the daemon will pass to tmux. Everything else must match
 * C-<a-z>, M-<a-z>, or F1-F12; anything not matching is rejected, not passed
 * through. Mirrors huginn-appd's NAMED_KEYS + validKey().
 */
export const NAMED_KEYS: ReadonlySet<string> = new Set([
  'Enter', 'Escape', 'Tab', 'BTab', 'Space', 'BSpace', 'DC',
  'Up', 'Down', 'Left', 'Right', 'Home', 'End', 'PPage', 'NPage',
])

export const isAllowedKey = (k: string): boolean =>
  NAMED_KEYS.has(k) || /^C-[a-z]$/.test(k) || /^M-[a-z]$/.test(k) || /^F([1-9]|1[0-2])$/.test(k)

/** The daemon 400s a request with more named keys than this. */
export const MAX_KEYS_PER_REQUEST = 32

/** The daemon 400s a request with a longer text than this (UTF-16 units). */
export const MAX_TEXT_PER_REQUEST = 8000

/** One POST /keys body: exactly one of text/keys, matching how the app sends. */
export type KeysBody = { text: string } | { keys: string[] }

/**
 * Queue → wire: merge the batch, then map each op to the fewest request bodies
 * that fit the daemon's limits, preserving order throughout.
 *
 * Keys that fail the allowlist are dropped here rather than sent: one bad key
 * would 400 the entire request and take the good keys down with it.
 */
export const toWire = (ops: readonly Op[]): KeysBody[] => {
  const out: KeysBody[] = []
  for (const op of merge(ops)) {
    if (op.kind === 'text') {
      let t = op.text
      while (t.length > 0) {
        let n = Math.min(t.length, MAX_TEXT_PER_REQUEST)
        // Never split a surrogate pair across requests: the halves would land
        // as two lone surrogates and the glyph would arrive mangled.
        if (n < t.length) {
          const c = t.charCodeAt(n - 1)
          if (c >= 0xd800 && c <= 0xdbff) n--
        }
        out.push({ text: t.slice(0, n) })
        t = t.slice(n)
      }
    } else {
      const keys = op.keys.filter(isAllowedKey)
      for (let i = 0; i < keys.length; i += MAX_KEYS_PER_REQUEST) {
        out.push({ keys: keys.slice(i, i + MAX_KEYS_PER_REQUEST) })
      }
    }
  }
  return out
}
