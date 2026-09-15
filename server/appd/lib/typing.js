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
const { stripAnsi } = require('./pane');

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

/** How long a queued send waits for a turn boundary before it is dropped, loudly. */
const QUEUE_MAX_WAIT_MS = 10 * 60 * 1000;

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
  return rec.type === 'assistant' && rec.isApiErrorMessage === true;
}

/** The record kind a caller can log or assert on: `user`, `system/turn_duration`, … */
function kindOf(rec) {
  if (!rec || typeof rec.type !== 'string') return null;
  return rec.subtype ? `${rec.type}/${rec.subtype}` : rec.type;
}

/**
 * Is the session between turns, judged from the tail of its jsonl transcript?
 *
 * `idle` iff the LAST record that is neither an `attachment` nor unparseable is
 * a `turn_duration`. Attachments are skipped because Claude Code appends them
 * after the fact and one landing late would otherwise re-close a gate that had
 * legitimately opened.
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
    // HOTFIX 3.0.2: only CONVERSATIONAL records decide the boundary. Claude Code
    // appends bookkeeping records after a turn (last-prompt, ai-title, mode,
    // permission-mode, atis-latch, cost-state, file-history-snapshot, ...), which
    // made a busy session look un-idle forever and dropped queued human sends.
    const conv = rec.type === 'user' || rec.type === 'assistant'
      || (rec.type === 'system' && rec.subtype === 'turn_duration');
    if (!conv) continue;
    return { idle: isBoundaryRecord(rec), lastKind: kindOf(rec) };
  }
  return { idle: false, lastKind: null };
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
// The trust dialog, which is the one modal whose DESTRUCTIVE option is
// pre-selected ("No, exit"): a blind Enter here kills the session.
const TRUST_RE = /Yes,\s+I\s+trust\s+this\s+folder|trust\s+the\s+files\s+in\s+this\s+folder|Is\s+this\s+a\s+project\s+you\s+created\s+or\s+one\s+you\s+trust/i;
// How far up from the bottom a dialog's own furniture may reach.
const DIALOG_LOOKBACK = 20;

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

  const from = Math.max(0, last - DIALOG_LOOKBACK);
  const region = plain.slice(from, last + 1);
  // Trust first: it is a modal too, but it is the one with a destructive
  // default, and a caller that logs `why` should be able to say so.
  if (region.some((l) => TRUST_RE.test(l))) return { ready: false, why: 'trust' };
  if (region.some((l) => SELECTOR_ROW_RE.test(l))) return { ready: false, why: 'modal' };

  const tail = plain[last];
  if (CARET_EMPTY_RE.test(tail) || CARET_TYPED_RE.test(tail)) return { ready: true, why: null };
  return { ready: false, why: 'busy' };
}

/** Does this pane state block a send outright? Only a dialog does. */
function paneBlocks(why) {
  return why === 'modal' || why === 'trust';
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
 * Both gates, folded into one verdict.
 *
 * `idle` comes from the transcript (the liveness authority), `paneWhy` from
 * `paneReadyForInput`. Order matters for the REPORTED reason only: a dialog is
 * the more actionable of the two, so it is named first.
 */
function releaseDecision({ idle, paneWhy }) {
  if (paneBlocks(paneWhy)) return { release: false, blockedBy: 'modal' };
  if (!idle) return { release: false, blockedBy: 'turn' };
  return { release: true, blockedBy: null };
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
 *   'timeout' waited QUEUE_MAX_WAIT_MS for a boundary that never came.
 *
 * A HUMAN-originated send is never dropped for 'human' or 'family': the person
 * pressed send, and a message they typed is delivered or it is an error, never
 * quietly binned.
 */
function dropReason(entry, ctx = {}) {
  if (!entry) return null;
  if (entry.automated && ctx.humanSpoke) return 'human';
  if (entry.automated && entry.kind === 'model' && entry.family && ctx.family
      && ctx.family !== entry.family) return 'family';
  const now = Number(ctx.now) || 0;
  // HOTFIX 3.0.2: a person's message is never dropped on timeout (late beats
  // lost); only automated sends time out.
  if (entry.automated && now && entry.at && now - entry.at >= QUEUE_MAX_WAIT_MS) return 'timeout';
  return null;
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

/** The `GET /v1/sessions/:name/typing` body, from in-memory state only. */
function typingSnapshot(q, nowMs = Date.now()) {
  return {
    queued: q && Array.isArray(q.entries) ? q.entries.length : 0,
    delivering: !!(q && q.delivering),
    lastError: (q && q.lastError) || null,
    blockedBy: q && q.entries && q.entries.length ? (q.blockedBy || null) : null,
    serverTime: Math.floor(nowMs / 1000),
  };
}

module.exports = {
  SESSION_TEXT_MAX, SENDKEYS_BUDGET, CHUNK_SIZE, TYPING_POLL_MS, QUEUE_MAX_WAIT_MS,
  sendKeysFits, chunks,
  isBoundaryRecord, boundaryFromTail, hasHumanUserRecord, kindOf,
  paneReadyForInput, paneBlocks, bufferName,
  releaseDecision, dropReason, dropMessage, typingSnapshot,
};
