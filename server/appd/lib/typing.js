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

/** How long an AUTOMATED send waits for a turn boundary before it is dropped, loudly. */
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
 *   attention  a question is waiting; prose typed into a numbered prompt is
 *              lost or misread. Held, never dropped, for as long as it takes.
 *   then either boundary — the transcript's or the hook's — lets it go.
 */
function releaseDecision({ idle, paneWhy, state = null }) {
  if (paneBlocks(paneWhy)) return { release: false, blockedBy: 'modal' };
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
  QUEUE_MAX_WAIT_MS,
  sendKeysFits, chunks,
  isBoundaryRecord, isConversationalRecord, boundaryFromTail, stateVerdict,
  hasHumanUserRecord, kindOf,
  paneReadyForInput, paneBlocks, bufferName,
  releaseDecision, dropReason, dropMessage, dropLogLine, typingSnapshot,
};
