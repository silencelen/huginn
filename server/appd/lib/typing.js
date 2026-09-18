'use strict';
// Everything measurable about typing a message into a live Claude Code pane.
//
// Pure by design — zero I/O, zero tmux, zero clock reads that are not passed in
// — so every rule below can be asserted in `node --test` instead of being
// discovered on a live session. The I/O half (load-buffer / paste-buffer, the
// per-session queue, the routes) lives in huginn-appd.js and calls into here.
//
// The numbers are measured, not guessed: spike `chunked-typing.md`, 2026-09-15,
// against Claude Code v2.1.258 on a 200x50 pane.

const { randomBytes } = require('node:crypto');
const { stripAnsi, detectPrompt } = require('./pane');

/**
 * The `/keys` text cap, raised from 8,000 to match the chat body cap.
 *
 * 8,000 was the old chunked-send ceiling wearing a policy hat: a pane "took
 * 8,000 characters at a time" because that is what fitted in a tmux command
 * line with margin. Delivery is bracketed paste now (stdin, no length limit),
 * so the cap is a product decision about how long a single message may be, and
 * the honest answer is the same as everywhere else in the daemon.
 */
const SESSION_TEXT_MAX = 100_000;

/**
 * The measured tmux command-line ceiling for `send-keys -l -- <text>`.
 *
 * tmux reconstructs a ~16,363-char command line and refuses anything longer
 * with `command too long` (rc=1, nothing typed — it fails safe). The TARGET
 * spelling comes out of the same budget 1:1, so `-t '=a-long-session-name:'`
 * narrows the payload by exactly its own length. Bisected against a throwaway
 * pane: 16,339 usable for a 4-char target, 16,307 for a 36-char one, no
 * escaping inflation for newlines or quotes. 16,340 is the measured usable
 * width before the target is subtracted.
 *
 * Only the FALLBACK path is bound by this. The paste path travels on stdin.
 */
const SENDKEYS_BUDGET = 16_340;

/** Fallback chunk width. 8,000 is 2.9x faster than 2,000 and still ~2x under the ceiling. */
const CHUNK_SIZE = 8_000;

/** How often a queued send re-checks its two gates. */
const TYPING_POLL_MS = 400;

/** How long an AUTOMATED send waits for a turn boundary before it is dropped, loudly. */
const QUEUE_MAX_WAIT_MS = 10 * 60 * 1000;

/**
 * How long a send waits for a freshly launched `claude` to draw its composer.
 *
 * Measured on this host against Claude Code 2.1.258, sampling the pane every
 * 30 ms from the instant `tmux new-session` returns:
 *
 *   t+0.03 s   new-session returns; the pane is COMPLETELY EMPTY
 *   t+0.8 s    (in a repo with startup warnings) plain console text, no box
 *   t+2.1 s    the whole TUI is painted in one write — banner + composer box
 *   t+3.2 s    the placeholder clears and the status line names the session
 *
 * 20 s is that 2-3 s with a very wide margin for a loaded host, an MCP-heavy
 * cwd or a cold node. It is a CEILING, not a delay: the gate opens the moment
 * the composer appears, which is the normal case a couple of seconds in. When
 * it does expire the send goes out anyway — a person's message is delivered or
 * it is an error, never quietly binned, and a `claude` that never drew a
 * composer has fallen through to the shell, which is the same pane appd would
 * have typed into before any of this existed.
 */
const STARTUP_GRACE_MS = 20 * 1000;

/**
 * Does this text fit in ONE `send-keys -l` command line for this target?
 *
 * Computed, never hardcoded: a long session name eats the budget character for
 * character, so the same message fits for `=t:` and does not for
 * `=huginn-verify-1234567:`.
 */
function sendKeysFits(text, target) {
  const room = SENDKEYS_BUDGET - String(target || '').length;
  return String(text || '').length <= room;
}

/**
 * Split text for the fallback path. Never used on the paste path — chunking is
 * what bracketed paste exists to delete.
 *
 * Width is `min(CHUNK_SIZE, budget - target)`, so a pathological session name
 * shrinks the chunks rather than producing chunks tmux will refuse. A target
 * that leaves no room at all yields [] and the caller must 503 rather than
 * send a truncated message.
 */
function chunks(text, target) {
  const s = String(text || '');
  if (!s) return [];
  const width = Math.min(CHUNK_SIZE, SENDKEYS_BUDGET - String(target || '').length);
  if (width <= 0) return [];
  const out = [];
  for (let i = 0; i < s.length; i += width) out.push(s.slice(i, i + width));
  return out;
}

/**
 * The one record Claude Code writes per completed turn.
 *
 * Census over a real session: 15 of these for 15 turns, against 17 assistant
 * records (a tool-use turn emits several). This is the liveness signal — NOT
 * the pane. The pane's busy marker is a randomised spinner verb (`✽
 * Undulating…`, `* Orbiting…`, `✻ Baked for 1s · done`), and matching a
 * randomised string is how a gate silently stops gating.
 */
function isBoundaryRecord(rec) {
  if (!rec) return false;
  if (rec.type === 'system' && rec.subtype === 'turn_duration') return true;
  // A turn that ended on an API ERROR is also over, and a usage-limit stall is
  // the case that matters: the CLI writes the 429 as an ordinary assistant
  // record and then stops — no `turn_duration` ever follows it (verified on the
  // real capture in test/fixtures/transcripts/limit-429.jsonl, whose last record
  // IS the stall). Without this the auto-resume phrase would queue behind a
  // boundary that can never arrive and be dropped ten minutes later as a
  // timeout: the one session appd most needs to speak to would be the one
  // session it cannot.
  if (rec.type !== 'assistant') return false;
  if (rec.isApiErrorMessage === true) return true;
  // ⚠ AND A TURN CAN END WITH NO turn_duration AT ALL. Census over the 25 most
  // recent real transcripts in ~/.claude/projects/-root-netplan: turn_duration
  // is written 1:1 with system/stop_hook_summary — i.e. ONLY when a Stop hook
  // ran — and 14 of those 25 files contain none at all, 11 of them ending on an
  // assistant record carrying `stop_reason: "end_turn"`. On that kind of session
  // the queue was waiting for a marker nobody would ever write, so every send
  // into one sat for ten minutes and was dropped. `end_turn` is the model
  // saying it has finished and is not calling a tool; mid-turn records say
  // `tool_use` instead, which is what still holds the gate.
  const stop = rec.message && rec.message.stop_reason;
  return stop === 'end_turn';
}

/**
 * Is this record part of the CONVERSATION, as opposed to bookkeeping?
 *
 * ⚠ THE RULE THE 3.0.x QUEUE DID NOT HAVE, AND THE WHOLE BUG. Claude Code
 * appends its own bookkeeping AFTER a turn ends — `last-prompt`, `ai-title`,
 * `mode`, `permission-mode`, `atis-latch`, `cost-state`, `file-history-snapshot`,
 * `queue-operation`, `bridge-session`, `frame-link`, `summary`, `custom-title`,
 * `agent-name`, `pr-link`, `attachment` — and the gate asked only whether the
 * LAST record was the turn marker. On a session that kept working the answer was
 * permanently no.
 *
 * Only three types speak: `user`, `assistant`, and `system` when its subtype is
 * `turn_duration`. Every other system subtype (`stop_hook_summary`, which lands
 * immediately before every turn marker, plus `compact_boundary`,
 * `local_command`, `informational`, `model_refusal_fallback`) is skipped rather
 * than read as a turn in progress. Everything else is invisible.
 */
const CONVERSATIONAL_TYPES = new Set(['user', 'assistant']);
function isConversationalRecord(rec) {
  if (!rec || typeof rec.type !== 'string') return false;
  if (rec.type === 'system') return rec.subtype === 'turn_duration';
  return CONVERSATIONAL_TYPES.has(rec.type);
}

/** The record kind a caller can log or assert on: `user`, `system/turn_duration`, … */
function kindOf(rec) {
  if (!rec || typeof rec.type !== 'string') return null;
  return rec.subtype ? `${rec.type}/${rec.subtype}` : rec.type;
}

/**
 * Is the session between turns, judged from the tail of its jsonl transcript?
 *
 * `idle` iff the last CONVERSATIONAL record is a boundary — the turn marker, an
 * api-error stall, or an assistant record that ended its turn. Bookkeeping is
 * stepped over rather than answered, because Claude Code writes plenty of it
 * after a turn ends and a gate that reads the last LINE never opens on a session
 * that keeps working (measured live 2026-09-15: 43 messages queued behind a tail
 * of `[turn_duration, last-prompt, ai-title, mode, permission-mode, atis-latch]`,
 * every one of them dropped ten minutes later).
 *
 * A tail read from the middle of a file starts mid-line; that first fragment is
 * dropped by the same rule that drops any unparseable line. An empty or
 * all-unparseable tail is `idle: false` — never assume idle from an absence.
 */
function boundaryFromTail(jsonlTail) {
  const lines = String(jsonlTail || '').split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line) continue;
    let rec;
    try { rec = JSON.parse(line); } catch { continue; }
    if (!rec || typeof rec !== 'object') continue;
    // 3.0.2's inline test, lifted into a named rule with the census behind it.
    if (!isConversationalRecord(rec)) continue;
    return { idle: isBoundaryRecord(rec), lastKind: kindOf(rec) };
  }
  return { idle: false, lastKind: null };
}

/**
 * The SECOND boundary source: the title hook's per-session state file.
 *
 * The Stop hook writes `{state:"idle", ts}` the instant a turn ends, and
 * UserPromptSubmit writes `running` when one starts — so an `idle` stamped after
 * a send was queued is a turn boundary that has demonstrably happened, whatever
 * the transcript tail looks like. `ts` is epoch SECONDS (the hook's `now|floor`),
 * `queuedAtMs` is milliseconds.
 *
 * `attention` is the opposite verdict and outranks everything: a numbered prompt
 * is on screen, and prose typed into one is lost or misread. The entry is HELD,
 * never dropped — the caller reports `blockedBy: 'attention'` so a client can
 * say which question is in the way.
 */
function stateVerdict(st, queuedAtMs) {
  if (!st || typeof st.state !== 'string') return null;
  if (st.state === 'attention') return 'hold';
  if (st.state !== 'idle') return null;
  const sec = Number(st.stateSince);
  const at = Number(queuedAtMs);
  if (!Number.isFinite(sec) || !sec || !Number.isFinite(at) || !at) return null;
  return sec * 1000 > at ? 'release' : null;
}

/**
 * Did a HUMAN speak in this slice of transcript?
 *
 * Used for the automated-send drop rule: an automated message queued behind a
 * turn boundary must not land after the owner has taken the conversation
 * somewhere else. `user` records also carry tool_result blocks (Claude Code
 * handing a tool's output back to the model) and meta records; neither is a
 * person typing, so both are skipped — the same distinction transcript.js
 * makes at `case 'user'`.
 */
function hasHumanUserRecord(jsonlSlice) {
  for (const raw of String(jsonlSlice || '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    let rec;
    try { rec = JSON.parse(line); } catch { continue; }
    if (!rec || rec.type !== 'user' || rec.isMeta === true) continue;
    const c = rec.message && rec.message.content;
    if (typeof c === 'string') { if (c.trim()) return true; continue; }
    if (!Array.isArray(c)) continue;
    // A record made only of tool_result blocks is plumbing, not a person.
    if (c.some((b) => b && typeof b === 'object' && b.type !== 'tool_result')) return true;
  }
  return false;
}

// The composer's input line: a bare caret, or a caret with typed text after it.
const CARET_EMPTY_RE = /^\s*❯\s*$/;
const CARET_TYPED_RE = /^\s*❯\s+\S/;
// The highlighted row of ANY selector dialog: the cursor glyph, then a number.
// Deliberately narrower than "a numbered line": a pane echoing a numbered list
// (a shell, a page of notes, Claude's own prose) must not read as a dialog, or
// every send into it would block forever on a modal that is not there.
const SELECTOR_ROW_RE = /^\s*[❯>]\s*\d{1,2}[.)]\s+\S/;
// ANY numbered option row, cursored or not — the second row that turns a lone
// numbered line into a RUN. See the ≥2 rule in `dialogWhy`.
const OPTION_ROW_RE = /^\s*(?:[❯>]\s*)?\d{1,2}[.)]\s+\S/;
// The selector's own help line, drawn under the options while it is live and
// nowhere else. It is the only thing left to go on when the pane is too short
// to have captured the cursored row at all (#13's 24-row case).
const DIALOG_FOOTER_RE = /enter to (?:select|confirm|set|choose)|esc to cancel|(?:↑\/↓|tab\/arrow(?:s| keys)?|arrow keys) to navigate/i;
// The trust dialog, which is the one modal whose DESTRUCTIVE option is
// pre-selected ("No, exit"): a blind Enter here kills the session.
const TRUST_RE = /Yes,\s+I\s+trust\s+this\s+folder|trust\s+the\s+files\s+in\s+this\s+folder|Is\s+this\s+a\s+project\s+you\s+created\s+or\s+one\s+you\s+trust/i;
// How far up from the bottom a dialog's own furniture may reach.
const DIALOG_LOOKBACK = 20;

/**
 * Is a selector dialog up? Three nets, in order of how much they know.
 *
 * ⚠ THE TWO HOLES THIS REPLACED, one at each end of the pane:
 *
 *   #1  ONE numbered line is a person typing, not a dialog. The owner's own
 *       "1. rebuild the index" sitting unsent in the composer read as a modal
 *       and held every send into that session forever, while the client said
 *       "a dialog is open on the screen" about an idle pane. `pane.js
 *       detectPrompt` has always refused to call a single cursored numbered
 *       row a dialog; this one did not, so the daemon's two detectors
 *       contradicted each other on the same capture.
 *   #13 A dialog TALLER than the 20-row lookback was not seen at all — the
 *       cursored row of `ask-tall-desc-64.txt` is line 19 of 44 — and a
 *       person's message was pasted and submitted into a live question, lost
 *       with no transcript trace and the highlighted option answered for them.
 *       `lib/pane.js` dropped its own fixed lookback for exactly this capture
 *       in 2.59.1 (commit 384abc3); this module was left behind.
 *
 * So: ask the structural detector first (it reads the WHOLE pane and knows that
 * ordinary chrome drawn below a numbered run means the run is history), then
 * the footer marker for the clipped case, then the old cursored-row rule with
 * the missing ≥2-rows requirement as belt and braces for a dialog shaped in a
 * way `detectPrompt` is too strict to admit.
 */
function dialogWhy(arr, plain, last) {
  const region = plain.slice(Math.max(0, last - DIALOG_LOOKBACK), last + 1);
  // Trust first: it is a modal too, but it is the one with a destructive
  // default, and a caller that logs `why` should be able to say so. Scanned
  // over the whole pane for the same reason as everything else here.
  if (plain.slice(0, last + 1).some((l) => TRUST_RE.test(l))) return 'trust';
  // 1 — the sibling detector, whole pane, structural.
  if (detectPrompt(arr)) return 'modal';
  // 2 — a live selector's footer near the bottom, with a numbered row above it.
  const footer = region.findIndex((l) => DIALOG_FOOTER_RE.test(l));
  if (footer >= 0 && plain.slice(0, last + 1).some((l) => OPTION_ROW_RE.test(l))) return 'modal';
  // 3 — a cursored row backed by a second option row, in the bottom region.
  if (region.some((l) => SELECTOR_ROW_RE.test(l))
    && region.filter((l) => OPTION_ROW_RE.test(l)).length >= 2) return 'modal';
  return null;
}

/**
 * Is this pane willing to accept a typed message right now?
 *
 * `ready` is the strict reading: the last non-blank line is the composer's
 * caret AND no dialog is up. `why` says which of the three reasons it is not:
 *
 *   'trust'  the fresh-cwd trust dialog (pre-selects "No, exit")
 *   'modal'  any other selector dialog — /model, a permission ask, a plan approval
 *   'busy'   no caret at all: a plain shell, a pane mid-redraw, an empty pane
 *
 * ⚠ 'busy' is NOT a liveness verdict and the send queue must not treat it as
 * one. The pane is for MODAL detection; the jsonl is the liveness signal
 * (spike: the busy marker is a randomised spinner verb). A session mid-turn
 * draws its caret exactly like an idle one, which is why this returns ready for
 * a spinning pane on purpose.
 *
 * An empty pane is 'busy', not ready — but again, only a modal blocks.
 */
function paneReadyForInput(lines) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  if (last < 0) return { ready: false, why: 'busy' };

  const dialog = dialogWhy(arr, plain, last);
  if (dialog) return { ready: false, why: dialog };

  const tail = plain[last];
  if (CARET_EMPTY_RE.test(tail) || CARET_TYPED_RE.test(tail)) return { ready: true, why: null };
  return { ready: false, why: 'busy' };
}

/** Does this pane state block a send outright? Only a dialog does. */
function paneBlocks(why) {
  return why === 'modal' || why === 'trust';
}

/**
 * Has Claude Code drawn its composer yet — i.e. is there an application in this
 * pane that will READ what we paste?
 *
 * ⚠ THE CARET IS NOT THE LAST LINE, and that is why this is a separate function
 * rather than `paneReadyForInput(...).ready`. On 2.1.258 the box is followed by
 * one to three status lines, every real session on this host included:
 *
 *   ────────────────────────────────────────
 *   ❯                                            <- the composer
 *   ────────────────────────────────────────
 *     [jtyper] Fable 5.1 · ctx 43% · main ~5     <- statusline
 *     ⏵⏵ auto mode on (shift+tab to cycle)       <- mode hint
 *
 * `paneReadyForInput` reads the LAST non-blank line, so on this build its
 * `ready` is false for every live Claude pane there is. That costs nothing
 * where it is used — the daemon consumes only `.why`, and 'busy' blocks nothing
 * — but it means the existing verdict cannot answer "is Claude up yet", which
 * is the question the startup race turns on. So this scans the bottom REGION,
 * the same window the dialog rules already use.
 *
 * Deliberately generous about what counts: a dialog's own cursored row is a
 * caret too, and a pane showing the trust dialog has demonstrably got a running
 * Claude in it. Being sure the app is THERE is this function's whole job;
 * whether it may be typed into is `paneBlocks`', and that check comes first.
 */
function composerDrawn(lines) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  if (last < 0) return false;   // an empty pane has drawn nothing at all
  const from = Math.max(0, last - DIALOG_LOOKBACK);
  return plain.slice(from, last + 1).some((l) => CARET_EMPTY_RE.test(l) || CARET_TYPED_RE.test(l));
}

/**
 * ─── PASTE SETTLED ─────────────────────────────────────────────────────────
 *
 * ⚠ THE P1 THIS SECTION EXISTS FOR (reported 2026-09-16, after 3.0.7 shipped
 * the `starting` gate): "im still seeing some errors with the first chat sent
 * during a new session being stuck in the screen views text box but not sent".
 *
 * Swept against the REAL `claude` 2.1.258 in the daemon's own WORKDIR, launched
 * exactly the way `POST /v1/sessions` launches it, pasting exactly the way
 * `sendTextToPane` pastes (load-buffer | paste-buffer -p -d, 50 ms, Enter), 30 ms
 * pane samples, outcome read from the transcript rather than the pane. 53 runs:
 *
 *   paste AFTER the composer, +0 ms to +3000 ms      31/31 submitted, clean
 *   paste ~2.0 s to ~0.85 s BEFORE the composer       6/6  STUCK IN THE BOX
 *   paste ~0.6 s to ~0.3 s BEFORE the composer        4/4  lost without trace
 *
 * So the bug is not a window AFTER the composer — there is none. It is the
 * middle band, and it is the owner's screenshot exactly: the bytes sit in the
 * pty until the TUI starts reading, the TUI renders them into the composer the
 * instant it paints, and the `\r` that rode with them is dropped. Text in the
 * box, no turn, no transcript record, and nothing on fire anywhere.
 *
 * The gate alone cannot close that band, because a send can reach a pane with no
 * composer three ways it does not control: the 20 s startup grace expiring on a
 * loaded host (measured in production 2026-09-17 23:54:49Z — `typing: mcserver:
 * no composer 22s after launch`, then the owner's first message 82 ms later), a
 * session created by `cc`/`huginn <name>` rather than by the route (never marked
 * at all), and a rename inside the grace. So the delivery path itself checks:
 * after pasting, WAIT until the pane visibly holds what was pasted, and only
 * then press Enter. Re-swept with that check in place, the stuck band is gone
 * (0/9 stuck; the four sends with bytes left to catch all submitted).
 */

/** How long a paste may take to become visible before Enter goes anyway. */
const PASTE_SETTLE_MS = 3_000;
/** How often the settle check re-reads the pane. One capture-pane each. */
const PASTE_SETTLE_POLL_MS = 50;
/** How long after Enter the composer has to empty before that is worth logging. */
const SUBMIT_CONFIRM_MS = 1_000;
const SUBMIT_CONFIRM_POLL_MS = 100;

/**
 * The composer's own content, as opposed to anything else with a caret on it.
 *
 * ⚠ AND IT IS THE **LAST** CARET, NOT THE FIRST. Claude Code echoes a SUBMITTED
 * message above the box with the same `❯` glyph, so a rule that reads the bottom
 * REGION — which is what `composerDrawn` and the dialog rules correctly do —
 * sees every successful send as text still sitting in the composer. That cost a
 * whole sweep: nine runs came back "submitted AND reappeared in the box" when
 * the box was empty in every one of them. Scanning UP from the bottom finds the
 * composer first, because the echo is above it and the status lines below carry
 * no caret.
 *
 * Wrapped input continues on the rows beneath the caret with no caret of their
 * own, so they are joined in, stopping at the box's closing rule.
 *
 * Returns null when there is no composer at all — a plain shell, a pane still
 * booting — which is a different answer from "the composer is empty" and the
 * callers below rely on the difference.
 */
const RULE_RE = /^[─━—–_=-]{3,}$/;
function composerText(lines) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  if (last < 0) return null;
  const from = Math.max(0, last - DIALOG_LOOKBACK);
  let caret = -1;
  for (let i = last; i >= from; i--) { if (/^\s*❯/.test(plain[i])) { caret = i; break; } }
  if (caret < 0) return null;
  const out = [plain[caret].replace(/^\s*❯\s?/, '')];
  for (let i = caret + 1; i <= last; i++) {
    if (RULE_RE.test(plain[i].trim())) break;
    out.push(plain[i]);
  }
  return out.join('\n');
}

/**
 * Comparison spelling for pane text: ANSI stripped and ALL whitespace removed.
 *
 * Whitespace goes because tmux wraps a long line at the pane width with no
 * separator at all, so a 200-character message arrives as three rows split
 * mid-word. Removing whitespace from both sides of the comparison makes the
 * wrap invisible instead of making the match impossible.
 */
function squashPane(s) { return stripAnsi(String(s || '')).replace(/\s+/g, ''); }

/**
 * What a COLLAPSED paste looks like in the composer — and it is most of them.
 *
 * Anything with a newline in it renders as `[Pasted text #1 +40 lines]` and the
 * text itself is never on screen at all (measured: a 40-line paste shows the
 * marker 60 ms after paste-buffer and nothing else, forever). A rule that looked
 * only for the text would time out on every multi-line message there is, which
 * is most of what a person sends from a phone. Matched against the squashed
 * spelling, hence no spaces; `#N` is the paste index and is optional because it
 * is not in every build.
 */
const PASTED_MARKER_RE = /\[Pastedtext(?:#\d+)?\+\d+lines?\]/i;

/** The needle: enough of the message to be unmistakable, short enough to fit a row. */
function pasteProbe(text) { return squashPane(text).slice(0, 32); }

/**
 * Where a settle check looks: the composer if there is one, else the bottom of
 * the pane.
 *
 * The fallback is not a shortcut. A pane that is still booting has no composer
 * yet — that is the whole case — and a plain shell never will; both are panes
 * appd legitimately types into, and both echo at the bottom when they echo at
 * all. The composer is preferred when present because it is the precise answer.
 */
function settleRegion(lines) {
  const c = composerText(lines);
  if (c !== null) return squashPane(c);
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  return squashPane(arr.slice(Math.max(0, arr.length - (DIALOG_LOOKBACK + 4))).join('\n'));
}

/**
 * Has the paste visibly LANDED — i.e. is there now an application holding it?
 */
function pasteLanded(lines, text) {
  const probe = pasteProbe(text);
  if (!probe) return true;                       // nothing was sent; nothing to wait for
  const region = settleRegion(lines);
  return region.includes(probe) || PASTED_MARKER_RE.test(region);
}

/**
 * Was the probe ALREADY on screen before the paste went out?
 *
 * Then the check above cannot speak: a pane that already showed the text (a
 * resend of the same message, a collapsed-paste marker left over from an earlier
 * one) is indistinguishable from one that has just received it. The caller
 * treats that as landed immediately, which is exactly what the daemon did before
 * this check existed — no worse, and never a wait that cannot end.
 */
function pasteIndistinguishable(beforeLines, text) {
  if (beforeLines == null) return false;
  const probe = pasteProbe(text);
  if (!probe) return true;
  const region = settleRegion(beforeLines);
  return region.includes(probe) || PASTED_MARKER_RE.test(region);
}

/**
 * After Enter: has the composer let go of the message?
 *
 * Composer-ONLY, never the region — see `composerText`. A pane with no composer
 * (a shell) returns null rather than false: there is nothing here that can
 * answer, and reporting a stall for every shell send would make the journal line
 * below worthless.
 */
function composerCleared(lines, text) {
  const c = composerText(lines);
  if (c === null) return null;
  const probe = pasteProbe(text);
  if (!probe) return true;
  const squashed = squashPane(c);
  return !(squashed.includes(probe) || PASTED_MARKER_RE.test(squashed));
}

/**
 * The journal lines for the two ways delivery can go wrong quietly.
 *
 * Both carry the bottom of the pane, because the question a reader has is "what
 * was in the pane instead" and the answer is never in a counter. One grep-able
 * shape, like `dropLogLine` — the rule since the 43 messages that vanished
 * without a trace is that anything short of a delivered message says so on disk.
 */
function paneTail(lines, rows = 6) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  return arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''))
    .filter((l) => l.trim()).slice(-rows).join(' | ');
}
function pasteLostLogLine(name, waitedMs, lines) {
  return `typing: ${name}: pasted text never appeared in the pane after ${waitedMs}ms; `
    + `pressing Enter anyway | pane: ${paneTail(lines)}`;
}
function submitStalledLogLine(name, waitedMs, lines) {
  return `typing: ${name}: composer still holds the message ${waitedMs}ms after Enter; `
    + `it may be sitting there unsent | pane: ${paneTail(lines)}`;
}

/**
 * ─── THE LOST BAND, RECOVERED ──────────────────────────────────────────────
 *
 * ⚠ WHAT THE SETTLE CHECK ALONE STILL LOSES. The 2026-09-17 sweep against the
 * real 2.1.258 found TWO pre-composer bands, and 3.1.1 only closed one of them:
 *
 *   pasted ~2.0 s to ~0.85 s before the paint   the bytes SURVIVE in the pty and
 *                                               are rendered at paint time minus
 *                                               their `\r` — the settle wait sees
 *                                               them, presses Enter after the
 *                                               paint, and the message goes.
 *   pasted ~0.95 s to ~0.3 s before the paint   the bytes are READ AND DISCARDED
 *                                               by whatever is draining the pty
 *                                               before the TUI attaches. No
 *                                               composer text, no turn, no
 *                                               transcript record, nothing.
 *
 * For the second band the settle wait can only ever time out. 3.1.1 logged that
 * ("pasted text never appeared…") and pressed Enter anyway — into a composer
 * that by then is up and EMPTY, so the Enter submits nothing and the person's
 * message is gone with a journal line as its only trace. Detected, not fixed.
 *
 * The recovery is a re-paste, and it is safe in exactly one state: the composer
 * is now DRAWN and holds nothing. Then there is no message to double (ours never
 * arrived, and nothing was submitted because our Enter has not been pressed yet)
 * and nothing of anybody else's to trample. Any other state is left alone:
 *
 *   'blind'   there is no composer at all — a plain shell, a pane that echoes
 *             nothing (`stty -echo`), a pane tmux stopped answering for. Nothing
 *             here can say whether the bytes arrived, so the Enter goes as it
 *             always did and the pane keeps the behaviour it had before any of
 *             this existed.
 *   'resend'  a composer, empty. Re-paste ONCE, wait for it the same bounded way,
 *             then press Enter.
 *   'leave'   a composer with something in it that is not ours. A person may be
 *             mid-sentence, and a bare Enter would submit THEIR half-written
 *             message. Nothing is typed and nothing is pressed; the journal line
 *             carries the pane's own bottom rows so a reader can see what was
 *             there instead.
 *
 * ⚠ AND "EMPTY" INCLUDES THE PLACEHOLDER. For its first ~500 ms the box holds a
 * dim hint — `❯ Try "refactor status-page"` — measured in the sweep captures
 * (r2-abs-12: present at composer+0 ms, gone by composer+500 ms). Reading that as
 * "somebody is typing" would refuse to recover exactly the sends that arrived
 * earliest, which are the ones this exists for.
 */
const COMPOSER_PLACEHOLDER_RE = /^Try".*"$/i;

/**
 * Is the composer drawn, and is it holding anything?
 *
 *   null   no composer here at all — a different answer from "it is empty", and
 *          every caller below turns on the difference.
 *   true   drawn and holding nothing a person would miss (blank, or the hint).
 *   false  drawn and holding something.
 */
function composerEmpty(lines) {
  const c = composerText(lines);
  if (c === null) return null;
  const squashed = squashPane(c);
  if (!squashed) return true;
  return COMPOSER_PLACEHOLDER_RE.test(squashed);
}

/** What a paste that never appeared may do about it: 'blind' | 'resend' | 'leave'. */
function recoveryDecision(lines) {
  const empty = composerEmpty(lines);
  if (empty === null) return 'blind';
  return empty ? 'resend' : 'leave';
}

function pasteResentLogLine(name, waitedMs, lines) {
  return `typing: ${name}: pasted text never appeared in ${waitedMs}ms and the composer is now `
    + `up and empty; re-pasting it once | pane: ${paneTail(lines)}`;
}
function pasteLeftAloneLogLine(name, waitedMs, lines) {
  return `typing: ${name}: pasted text never appeared in ${waitedMs}ms and the composer holds `
    + `something else; leaving it alone rather than submitting somebody's draft `
    + `| pane: ${paneTail(lines)}`;
}

/**
 * Is this session still coming UP, so that anything pasted into it is lost?
 *
 * ⚠ THE P1 THIS EXISTS FOR (reported 2026-09-15): "a user creates a session and
 * can send a message before claude is brought up in the background, the text
 * seems to still generate paste into the claude code session box, but it forces
 * the user to switch to the session tab and enter live view to hit enter".
 *
 * `POST /v1/sessions` answers 201 the instant `tmux new-session -d` returns —
 * about 30 ms — and `claude` needs a couple of SECONDS after that before
 * anything in the pane is reading stdin. A send in between is not merely early.
 * Measured against 2.1.258 (bracketed paste + Enter, exactly as the daemon
 * sends it):
 *
 *   pasted at t+1.1 s  the pane is empty; the message and its Enter VANISH —
 *                      no composer text, no turn, no transcript, no trace
 *   pasted at t+1.8 s  the message submits AND a copy of it reappears in the
 *                      composer seconds later, typed but unsent
 *   pasted at t+2.1 s  normal
 *
 * Either way the sender sees a composer that emptied and a session that did
 * nothing, which is the same screen a DROPPED message draws — the very thing
 * the send queue was built to stop.
 *
 * `launching` is the discriminator and it is deliberately narrow: appd sets it
 * only for the sessions it started `claude` in itself (the create route and the
 * reboot restore). A tmux session somebody else made is never held here, which
 * matters because a plain shell has no composer and never will — holding those
 * would break every non-Claude pane the app can open.
 */
function startingUp({ launching = false, composer = false, ageMs = null,
  graceMs = STARTUP_GRACE_MS } = {}) {
  if (!launching || composer) return false;
  // ⚠ `ageMs == null`, NOT `Number.isFinite(Number(ageMs))`. `Number(null)` is 0
  // and 0 is a perfectly good age, so the coercing spelling reads "I have no
  // idea when this launched" as "it launched this instant" and holds a send on
  // a session nothing knows anything about. The same trap cost the overview
  // route a first fetch (`Number(null)` is also an empty transcript's cursor).
  if (ageMs == null) return false;
  const age = Number(ageMs);
  if (!Number.isFinite(age)) return false;
  return age < graceMs;
}

/**
 * ─── THE SESSIONS NOBODY MARKED ────────────────────────────────────────────
 *
 * ⚠ THE GATE COVERED THE ROUTE AND NOTHING ELSE. `startingUp` asks `launching`,
 * and only two places ever set it: `POST /v1/sessions` and the reboot restore.
 * But `server/bin/cc` — which is what `cc`, `huginn <name>` and every phone/laptop
 * client's "open a session" really run — starts tmux ITSELF:
 *
 *     exec tmux new-session -A -s "$SESSION" -c "$WORKDIR" 'claude; exec "$SHELL" -l'
 *
 * so the daemon learns about that session only when somebody asks it something,
 * `launchingAt` never has an entry, and the two-second window is wide open for
 * exactly the send a person makes the moment their session appears. Same shape,
 * same loss, no gate at all.
 *
 * The mark is not the only evidence available. tmux knows when the session was
 * BORN (`#{session_created}`, which the daemon already reads and caches for the
 * stale-state rule), and a pane inside the grace with no composer in it is the
 * very state the mark was standing in for. So: hold it, with no mark.
 *
 * ⚠ AND THE ONE THING THAT MUST NOT BE HELD IS A SHELL. A shell has no composer
 * and never will, so "no composer yet" is a permanent state there and a rule that
 * read it as a startup would put a 20-second wait under every send into every
 * non-Claude pane the app can open — for the first 20 seconds of that pane's life,
 * which is when somebody is most likely to be typing into it. A shell says what
 * it is: its last line is a PROMPT. That is the discriminator, and it is the
 * pane-content check the dialog rules already read the same region for.
 *
 * Deliberately NOT part of the shell test: whether a Claude banner is on screen.
 * `cc` runs `claude; exec "$SHELL" -l`, so a `claude` that exits — a bad flag, a
 * crash, a version check — leaves a pane holding BOTH the banner and a live shell
 * prompt, and that pane is a shell now. Requiring "no banner" to call it one would
 * hold every one of those sends for the rest of the grace.
 *
 * ⚠ AND "NO COMPOSER AND NOT A SHELL" IS NOT ENOUGH ON ITS OWN. A pane running
 * anything that neither echoes nor prompts — `cat > file`, a `stty -echo` reader,
 * a picker stub, any of the dozens of panes the app can be pointed at — is EMPTY,
 * and an empty pane is byte for byte what a booting `claude` looks like at t+0.5 s.
 * Inferring from absence alone held fourteen such panes in this daemon's own test
 * suite, every one of them for the full grace. So the rule wants POSITIVE evidence,
 * and tmux keeps exactly the right fact: `#{pane_start_command}`, the command the
 * pane was created with, which for every session `cc` makes is literally
 * `claude; exec "$SHELL" -l`. A pane that was told to run `claude` and has not
 * painted a composer yet is starting up. A pane that was told to run `cat` is not,
 * however empty it looks.
 *
 * Erring is cheap in one direction and not the other, and the rule leans that
 * way on purpose: a pane wrongly called a shell — or wrongly judged not to be
 * running claude — is simply delivered into the way it always was, and the
 * settle-and-recover path above is the backstop. A pane wrongly called a startup
 * is a person waiting with no idea why.
 */
// tmux hands the start command back re-quoted (`"claude; exec \"$SHELL\" -l"`),
// so this reads it as text rather than parsing it: the question is only whether
// `claude` is the program this pane was told to run. Bounded by word edges so a
// path like `/opt/claude-tools/serve` does not answer yes.
const CLAUDE_START_RE = /(?:^|[^A-Za-z0-9_.-])claude(?:[^A-Za-z0-9_-]|$)/;
function startsClaude(startCommand) {
  return CLAUDE_START_RE.test(String(startCommand || ''));
}
// A shell prompt ends in one of the classic terminators: `$` (sh/bash/zsh user),
// `#` (root), `%` (zsh/csh), `>` (a continuation, or a REPL). Read off the LAST
// non-blank line — a prompt is the bottom of the pane by definition, and looking
// anywhere else would match the `$` at the end of any line of prose.
const SHELL_PROMPT_RE = /[$#%>]$/;
function shellPrompt(lines) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  for (let i = plain.length - 1; i >= 0; i--) {
    if (!plain[i].trim()) continue;
    return SHELL_PROMPT_RE.test(plain[i]);
  }
  return false;   // an empty pane has drawn nothing, and nothing is not a prompt
}

/**
 * Is a session NOBODY marked still coming up?
 *
 * `ageMs` is the age of the tmux session itself (`#{session_created}`), not of a
 * mark — that is the whole point. Same `ageMs == null` rule as `startingUp` and
 * the same reason: `Number(null)` is 0, and "I have no idea when this was born"
 * must never read as "it was born this instant".
 */
function startingUnmarked({ composer = false, shell = false, claudeStart = false,
  ageMs = null, graceMs = STARTUP_GRACE_MS } = {}) {
  if (!claudeStart) return false;   // nothing was told to run claude here
  if (composer || shell) return false;
  if (ageMs == null) return false;
  const age = Number(ageMs);
  if (!Number.isFinite(age)) return false;
  return age >= 0 && age < graceMs;
}

/**
 * A per-send tmux buffer name.
 *
 * Unique per send, not per session and never a constant: two sessions pasting
 * at once through one buffer name is a message delivered into the wrong pane,
 * and `paste-buffer -d` on a shared name is a race with a second load.
 */
// A random 24-bit name collided (birthday odds ~0.7 % per 500 sends — and it
// did, twice, in the release gate). A buffer is deleted right after its paste,
// so a per-process counter from a random start is unique for 16.7 M sends and
// keeps the hg-<6hex> shape.
let bufferSeq = randomBytes(3).readUIntBE(0, 3);
function bufferName() {
  bufferSeq = (bufferSeq + 1) & 0xffffff;
  return `hg-${bufferSeq.toString(16).padStart(6, '0')}`;
}

/**
 * How long a PERSON's message may be held by the hook's `attention` verdict when
 * the pane shows no dialog at all.
 *
 * The state file is written by an EVENT, and no event fires when a question is
 * answered at the keyboard — so `attention` can outlive the question that set
 * it. The pane is the corroborating witness (see `humanAttentionHold`); this is
 * the backstop for the case where the pane cannot answer either. Matched to the
 * automated lane's ceiling so there is one number to remember, with the crucial
 * difference that a person's message is RELEASED here, never dropped.
 */
const ATTENTION_HOLD_MAX_MS = QUEUE_MAX_WAIT_MS;

/**
 * Should a PERSON's message be held because a question is waiting?
 *
 * ⚠ 3.0.3 made a human send skip the TURN gate, and `state` was then passed to
 * `releaseDecision` on the automated lane only — so the hook's `attention`, the
 * one authoritative "a numbered prompt is on screen", never reached a person's
 * message at all. The pane gate was the only thing in the way, and a pane is a
 * picture: #13's tall dialog, a frame captured mid-redraw, a selector nobody
 * has a rule for yet.
 *
 * Held, but not on the state file's word alone. `composerEmpty === true` means
 * the pane has drawn a composer holding nothing — which no live selector does
 * (every committed dialog capture reads `false` or `null`) — and that is proof
 * enough that the question is gone whatever the state file still says. Plus a
 * ceiling, because a hold a person cannot see the end of is the same bug as a
 * message that vanishes.
 */
function humanAttentionHold({ state, composerEmpty = null, waitedMs = 0,
  maxMs = ATTENTION_HOLD_MAX_MS } = {}) {
  if (state !== 'hold') return false;
  if (composerEmpty === true) return false;
  const waited = Number(waitedMs);
  return !(Number.isFinite(waited) && waited >= maxMs);
}

/**
 * Every gate, folded into one verdict.
 *
 * `idle` comes from the transcript (the liveness authority), `paneWhy` from
 * `paneReadyForInput`, and `state` from `stateVerdict` — the title hook's state
 * file, the second boundary source. Since 3.0.3 only the daemon's own automated
 * lines and pane scripts come through here with `idle` as read; a person's text
 * passes `idle: true` because only a dialog may hold it.
 *
 * Order is the order of harm:
 *   modal      a dialog SWALLOWS a message with no trace anywhere, and nothing
 *              overrides that.
 *   starting   there is no application in the pane yet, so the paste and its
 *              Enter go nowhere at all. Outranks everything below, and unlike
 *              the turn gate it holds a PERSON's message too — 3.0.3's rule is
 *              that a human send never waits for CLAUDE to finish, not that it
 *              may be thrown at a pane where Claude has not started.
 *   attention  a question is waiting; prose typed into a numbered prompt is
 *              lost or misread. Held, never dropped, for as long as it takes.
 *   then either boundary — the transcript's or the hook's — lets it go.
 */
function releaseDecision({ idle, paneWhy, state = null, starting = false }) {
  if (paneBlocks(paneWhy)) return { release: false, blockedBy: 'modal' };
  if (starting) return { release: false, blockedBy: 'starting' };
  if (state === 'hold') return { release: false, blockedBy: 'attention' };
  if (idle || state === 'release') return { release: true, blockedBy: null };
  return { release: false, blockedBy: 'turn' };
}

/**
 * Should this queued entry be thrown away instead of delivered?
 *
 * Three ways a send stops being the right thing to say (W1 §3 + W2 timeout):
 *
 *   'human'   an AUTOMATED message queued behind a boundary, and meanwhile the
 *             owner typed. Their turn is the conversation now; appd talking
 *             over it is the failure this rule exists to prevent.
 *   'family'  a `kind:'model'` job whose session already changed family — the
 *             native consent swap beat us to it, and appd never fights a
 *             native switch.
 *   'timeout' an AUTOMATED send waited QUEUE_MAX_WAIT_MS for a boundary that
 *             never came. A line about a usage limit is worse than useless ten
 *             minutes late, so appd's own messages still give up.
 *
 * ⚠ A HUMAN-ORIGINATED SEND HAS NO DROP REASON AT ALL — not 'human', not
 * 'family', and as of 3.0.2 not 'timeout' either. The person pressed send; a
 * message they typed is delivered or it is an error, never quietly binned. The
 * timeout half of that promise was missing and cost 43 messages on one session.
 * Since 3.0.3 a person's text does not wait on a turn at all, so the only thing
 * that can hold one is a dialog — and a dialog is answered, not waited out.
 */
function dropReason(entry, ctx = {}) {
  if (!entry) return null;
  if (entry.automated && ctx.humanSpoke) return 'human';
  if (entry.automated && entry.kind === 'model' && entry.family && ctx.family
      && ctx.family !== entry.family) return 'family';
  if (!entry.automated) return null;
  const now = Number(ctx.now) || 0;
  // 3.0.2 made the timeout automated-only; the early return above says it once.
  if (now && entry.at && now - entry.at >= QUEUE_MAX_WAIT_MS) return 'timeout';
  return null;
}

/**
 * The one journal line a drop must write.
 *
 * ⚠ WHY 43 MESSAGES VANISHED WITHOUT A TRACE. The drop wrote its reason into
 * `lastError` on an in-memory struct that only `GET /typing` reads, and nobody
 * was polling a session they had stopped expecting an answer from. journalctl
 * had nothing. Whatever else changes, a message leaving the queue undelivered
 * says so on disk.
 */
function dropLogLine(name, entry = {}, reason = 'unknown') {
  return `typing: dropped ${entry.origin || 'unknown'}/${entry.kind || 'text'} for ${name}: ${reason}`;
}

/** What `typingState.lastError` says about a drop. Never silent — that is the point. */
function dropMessage(reason, entry = {}) {
  const what = entry.origin ? `${entry.origin} message` : 'message';
  switch (reason) {
    case 'human':
      return `dropped an automated ${what}: you typed first`;
    case 'family':
      return `dropped an automated ${what}: the session already changed model`;
    case 'timeout':
      return 'waited 10 minutes for a turn boundary and gave up';
    default:
      return `dropped a queued ${what}`;
  }
}

/**
 * The `GET /v1/sessions/:name/typing` body, from in-memory state only.
 *
 * `waitedMs` is how long the HEAD of the queue has been waiting — the client's
 * half of "late beats lost": a message held ninety seconds behind a turn can say
 * so, instead of looking to its sender like nothing happened. Zero when nothing
 * is queued, and never negative however the clock moves.
 */
function typingSnapshot(q, nowMs = Date.now()) {
  const head = q && Array.isArray(q.entries) && q.entries.length ? q.entries[0] : null;
  return {
    queued: q && Array.isArray(q.entries) ? q.entries.length : 0,
    delivering: !!(q && q.delivering),
    lastError: (q && q.lastError) || null,
    blockedBy: q && q.entries && q.entries.length ? (q.blockedBy || null) : null,
    waitedMs: head && head.at ? Math.max(0, nowMs - head.at) : 0,
    serverTime: Math.floor(nowMs / 1000),
  };
}

module.exports = {
  SESSION_TEXT_MAX, SENDKEYS_BUDGET, CHUNK_SIZE, TYPING_POLL_MS,
  QUEUE_MAX_WAIT_MS, STARTUP_GRACE_MS,
  PASTE_SETTLE_MS, PASTE_SETTLE_POLL_MS, SUBMIT_CONFIRM_MS, SUBMIT_CONFIRM_POLL_MS,
  composerText, pasteProbe, pasteLanded, pasteIndistinguishable, composerCleared,
  composerEmpty, recoveryDecision,
  paneTail, pasteLostLogLine, submitStalledLogLine, pasteResentLogLine, pasteLeftAloneLogLine,
  sendKeysFits, chunks,
  isBoundaryRecord, isConversationalRecord, boundaryFromTail, stateVerdict,
  humanAttentionHold, ATTENTION_HOLD_MAX_MS,
  hasHumanUserRecord, kindOf,
  paneReadyForInput, paneBlocks, composerDrawn, shellPrompt, startsClaude,
  startingUp, startingUnmarked, bufferName,
  releaseDecision, dropReason, dropMessage, dropLogLine, typingSnapshot,
};
