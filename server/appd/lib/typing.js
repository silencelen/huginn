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
const { stripAnsi, detectPrompt, detectTrustDialog, detectUnnumberedSelector } = require('./pane');

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
 * 60 s is that 2-3 s with a very wide margin for a loaded host, an MCP-heavy
 * cwd or a cold node. It is a CEILING, not a delay: the gate opens the moment
 * the composer appears, which is the normal case a couple of seconds in. When
 * it does expire the send goes out anyway — a person's message is delivered or
 * it is an error, never quietly binned, and a `claude` that never drew a
 * composer has fallen through to the shell, which is the same pane appd would
 * have typed into before any of this existed (and is refused as one).
 *
 * ⚠ IT WAS 20 s, AND 20 s LOST MESSAGES. `typing: mcserver: no composer 22s
 * after launch` (2026-09-17) and `typing: rounds: no composer 21s after launch`
 * (2026-09-27): twelve sessions and a gradle build on the box, the hold ended,
 * the first message went into a pane that painted a few seconds later, and it
 * sat in the box unsent. A minute is still a ceiling nobody normally reaches.
 */
const STARTUP_GRACE_MS = 60 * 1000;

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
  // recent real transcripts in ~/.claude/projects/-root-projects-app: turn_duration
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
  // The compaction summary is a `user` record nobody said: after a compaction
  // while idle it was the last "conversational" record, so the session read as
  // mid-turn and every automated send waited out its ten minutes, then dropped.
  if (rec.isCompactSummary === true) return false;
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
    return { idle: isBoundaryRecord(rec), lastKind: kindOf(rec), unknown: false };
  }
  // ⚠ `unknown` IS NOT "NOT IDLE" (#5). A window with no conversational record
  // in it at all means the reader could not SEE the boundary, not that there
  // isn't one — measured on 66 of 716 of this host's own transcripts (9.2%), all
  // demonstrably idle, and in none of them because the last MESSAGE was huge: a
  // large non-conversational record (a 94 KB `attachment`) had been appended
  // after the turn ended, so the post-boundary bookkeeping inside the 64 KB
  // window parsed fine while the fragment of the big record did not. Reported as
  // plain `idle:false` it blocked every automated send on 'turn' until the
  // ten-minute drop. The caller widens the window on this flag.
  return { idle: false, lastKind: null, unknown: true };
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
  const stamp = stateStampMs(st);
  const at = Number(queuedAtMs);
  if (stamp == null || !Number.isFinite(at) || !at) return null;
  return stamp > at ? 'release' : null;
}

/**
 * The state file's stamp, in milliseconds, whatever unit it was written in.
 *
 * ⚠ #7: the hook stamped `ts: now|floor` — SECONDS — and this gate compared
 * `sec * 1000` against a millisecond `at`, so an `idle` written in the same
 * wall-clock second as the enqueue could never release that entry. The miss
 * window was `999 - (at mod 1000)` ms and one-directional (it could hold
 * wrongly, never release wrongly), and when the transcript gate was blind at
 * the same moment the automated send was dropped as 'timeout' ten minutes on.
 *
 * The hook writes milliseconds now. The unit is read off the MAGNITUDE rather
 * than from a version field, because a state file written by an older hook
 * survives a deploy and sits in /run until that session's next event: a stamp
 * past 1e11 cannot be seconds (that is the year 5138), and one below it cannot
 * be milliseconds (1973). A seconds stamp keeps the old conservative reading.
 */
function stateStampMs(st) {
  for (const v of [st && st.stateSinceMs, st && st.stateSince]) {
    const n = Number(v);
    if (!Number.isFinite(n) || !n) continue;
    return n > 1e11 ? n : n * 1000;
  }
  return null;
}

/**
 * What Claude Code writes as a `user` record about ITSELF: a command's output,
 * its caveat, a background task's notification. The OUTPUT of `!cmd` and of a
 * slash command, never the command — the `<bash-input>` and `<command-name>`
 * records before them are what somebody typed, and are read as such.
 */
const CLAUDE_CODE_OWN_RE = /^\s*<(?:local-command-stdout|local-command-stderr|local-command-caveat|bash-stdout|bash-stderr|task-notification)\b/;

/**
 * A prompt, in the spelling two copies of it can be compared in.
 *
 * Whitespace-free (`squashPane`), because Claude Code trims what it records and a
 * paste can arrive with its line ends changed. And a slash command is put back
 * the way it was TYPED: Claude Code records `/model` as
 * `<command-name>/model</command-name>…<command-args></command-args>`.
 *
 * ⚠ AND A LONG PASTE IS RECORDED WRAPPED. One Claude Code collapsed in the box
 * (`[Pasted text #1 …]`) is stored as `<pasted_content id="d842">\n…\n
 * </pasted_content id="d842">` — every long brief or relay appd sends. The tags
 * are dropped (PASTED_CONTENT_TAG_RE, a plain linear match: the body is not
 * needed, only the words around it); the whitespace they leave goes with the rest.
 */
const PASTED_CONTENT_TAG_RE = /<\/?pasted_content id="[0-9a-f]+">/g;
function promptSpelling(text) {
  let s = String(text == null ? '' : text);
  const name = /<command-name>([\s\S]*?)<\/command-name>/.exec(s);
  if (name) {
    const args = /<command-args>([\s\S]*?)<\/command-args>/.exec(s);
    s = `${name[1]} ${args ? args[1] : ''}`;
  }
  return squashPane(s.replace(PASTED_CONTENT_TAG_RE, ''));
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
 *
 * ⚠ AND TWO MORE KINDS OF `user` RECORD THAT NO PERSON WROTE. Read as the owner,
 * each one dropped an automated send with "you typed first" that nobody had:
 *   - Claude Code's own: a background task's notification draining into an idle
 *     session (`origin.kind: "task-notification"`, `promptSource: "system"` —
 *     every one of the 364 on the author's host), and the output of a command
 *     (CLAUDE_CODE_OWN_RE) — the ladder's own `/model` leaves one of those.
 *     Keyed on the harness's fields first, as transcript.js does: `origin.kind`
 *     other than `human` is never a person; no origin at all is an older
 *     transcript or a slash command, and the content decides.
 *   - appd's own: Claude Code records a message appd pasted EXACTLY as it
 *     records one the owner typed (`origin.kind: "human"`, `promptSource:
 *     "typed"`), so no field can tell them apart. `ours` is the caller's
 *     memory of what it typed (a `promptSpelling` → true); the second of two
 *     queued automated messages was dropped as soon as the first one's record
 *     was written, every time.
 */
function hasHumanUserRecord(jsonlSlice, { ours = null } = {}) {
  for (const raw of String(jsonlSlice || '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    let rec;
    try { rec = JSON.parse(line); } catch { continue; }
    if (!rec || rec.type !== 'user' || rec.isMeta === true) continue;
    // Claude Code's own summary after a compaction, not a person.
    if (rec.isCompactSummary === true) continue;
    const kind = rec.origin && rec.origin.kind;
    if ((typeof kind === 'string' && kind !== 'human') || rec.promptSource === 'system') continue;
    const c = rec.message && rec.message.content;
    let text;
    if (typeof c === 'string') {
      if (!c.trim()) continue;
      text = c;
    } else if (Array.isArray(c)) {
      const blocks = c.filter((b) => b && typeof b === 'object');
      // A record made only of tool_result blocks is plumbing, not a person.
      if (!blocks.some((b) => b.type !== 'tool_result')) continue;
      // Anything but words (an image) was attached by somebody; appd sends text.
      if (blocks.some((b) => b.type !== 'text' && b.type !== 'tool_result')) return true;
      text = blocks.filter((b) => b.type === 'text').map((b) => b.text || '').join('\n');
    } else continue;
    if (CLAUDE_CODE_OWN_RE.test(text)) continue;
    if (typeof ours === 'function' && ours(promptSpelling(text))) continue;
    return true;
  }
  return false;
}

/**
 * The most one drop check may read of a held entry's transcript.
 *
 * Generous on purpose: a person's message with a pasted image is one record of
 * several MB of base64, and it has to fit whole to be read as a person. A record
 * longer than this is a tool's output, never something somebody typed.
 */
const HUMAN_SCAN_MAX_BYTES = 8 * 1024 * 1024;

/**
 * Has a person spoken in the part of the transcript not looked at yet?
 *
 * ⚠ ONLY WHAT IS NEW, AND NEVER MORE THAN A BOUNDED READ OF IT. The drop check
 * runs on every 400 ms poll while an automated send is held, and it used to read
 * and decode EVERYTHING from the moment the entry was queued — so a send held
 * behind a long agent turn re-read the whole growing turn, tool output and all,
 * twice and a half times a second for up to ten minutes. Now `cursor.at` moves
 * past every complete line once it has been read, each check reads only what
 * was appended since (at most `max` bytes), and a line still being written is
 * left for the next check.
 *
 * @param cursor { at } — byte offset of the first unread byte; advanced here
 * @param read   (from, max) => { buf, start } | null, raw bytes of the file
 * @param opts   { ours } — handed to hasHumanUserRecord
 */
function scanForHuman(cursor, read, max = HUMAN_SCAN_MAX_BYTES, opts = {}) {
  const text = takeCompleteLines(cursor, read, max);
  return text != null && hasHumanUserRecord(text, opts);
}

/**
 * The complete lines appended since `cursor.at`, at most `max` bytes of them, as
 * text — and the cursor moved past them. Null when there is nothing new to read.
 */
function takeCompleteLines(cursor, read, max) {
  const slice = read(cursor.at, max);
  if (!slice || !slice.buf || !slice.buf.length) return null;
  const nl = slice.buf.lastIndexOf(0x0a);
  if (nl < 0) {
    // No complete line: one still being written — or one longer than a whole
    // read, which nobody typed. Step over that; wait for the other.
    if (slice.buf.length >= max) cursor.at = slice.start + slice.buf.length;
    return null;
  }
  const done = slice.buf.subarray(0, nl + 1);
  cursor.at = slice.start + done.length;
  // A read that began inside a line it stepped over starts with that line's
  // tail; it does not parse, and is skipped like any fragment.
  return done.toString('utf8');
}

/**
 * How long the transcript may show nothing of a delivery before its tail is
 * trusted again (DELIVERY_SHOW_MAX_MS), unless the title hook says a turn is
 * running.
 *
 * ⚠ BOUNDED, BECAUSE NOT EVERY PANE WRITES A TRANSCRIPT. Claude Code writes the
 * prompt's record within a flush (100 ms) plus its synchronous hooks, and its
 * title hook marks the session `running` sooner still — while that mark stands
 * the wait is not cut short at all. A pane that will never write anything (a
 * shell left with a stale state file, a stand-in) would otherwise hold every
 * automated send to it until the ten-minute drop.
 */
const DELIVERY_SHOW_MAX_MS = 5_000;

/**
 * Has the conversation moved past the point a delivery was made at?
 *
 * ⚠ THE TAIL CANNOT SEE A TURN THAT HAS NOT BEEN WRITTEN YET. Claude Code
 * flushes its transcript in batches (every 100 ms in 2.1.302, after any
 * synchronous UserPromptSubmit hook), so for a moment after appd submits a
 * message the last record is still the boundary that released it — and the next
 * automated send, read against that tail, was released into the turn the first
 * one had just started. One boundary, one automated message; the state file's
 * stamp already said so (`q.deliveredAt` in the pump), the transcript did not.
 *
 * So after a delivery the tail's `idle` counts only once a conversational record
 * that is NOT a boundary has been written after it: the delivered prompt, or —
 * for a message Claude Code absorbed into a running turn, which gets no record
 * of its own — that turn going on. A turn marker flushed late for the turn that
 * ended before the delivery is a boundary, and does not count.
 *
 * Incremental and bounded like scanForHuman: `cursor.at` starts at the size the
 * transcript had when the delivery was released.
 */
function scanForTurnStart(cursor, read, max = HUMAN_SCAN_MAX_BYTES) {
  const text = takeCompleteLines(cursor, read, max);
  if (text == null) return false;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    let rec;
    try { rec = JSON.parse(line); } catch { continue; }
    if (rec && typeof rec === 'object' && isConversationalRecord(rec) && !isBoundaryRecord(rec)) return true;
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
// One of the trust dialog's own ROWS, numbered or not, caret or not.
const TRUST_ROW_RE = /^\s*(?:[❯>]\s*)?(?:\d{1,2}[.)]\s*)?(?:Yes, I trust this folder|Yes, proceed|No, exit)\s*$/;
// How far up from the bottom a dialog's own furniture may reach.
const DIALOG_LOOKBACK = 20;

/**
 * Is the folder-trust dialog the thing on screen — as a DIALOG, not as words?
 *
 * ⚠ THE SENTENCE IS NOT THE DIALOG. This used to be "the trust sentence appears
 * anywhere on the pane", and a reply that merely QUOTED it — Claude explaining
 * the dialog, a pasted capture, a doc being edited — read as a trust prompt on a
 * pane that had a live composer under it. That holds every person's message to
 * the session with no ceiling and drops appd's own after ten minutes. So it is
 * recognised the way `detectTrustDialog` recognises it: by its rows and its place.
 *
 *   - the unnumbered 2.1.296 dialog: `detectTrustDialog`'s structural match
 *   - the numbered one: a live selector (`detectPrompt`: one caret, a contiguous
 *     run, nothing of the composer under it) whose rows are the trust rows
 *   - a layout neither reads: the dialog's own footer is the last thing on the
 *     pane, with the sentence AND one of its rows in the block above it. Prose
 *     never ends a pane while a composer is drawn below it, which is what keeps
 *     the belt from closing on the conversation again.
 */
function trustDialogUp(arr, plain, last, prompt) {
  const p = prompt === undefined ? detectPrompt(arr) : prompt;
  if (p && p.unnumbered) return true;
  if (p && Array.isArray(p.options)) {
    const labels = p.options.map((o) => String(o.label || ''));
    if (labels.some((l) => /^No, exit$/i.test(l))
        && (TRUST_RE.test(p.question || '') || labels.some((l) => TRUST_RE.test(l)))) return true;
  }
  if (detectTrustDialog(arr)) return true;
  if (last < 0 || !DIALOG_FOOTER_RE.test(plain[last])) return false;
  const block = plain.slice(Math.max(0, last - DIALOG_LOOKBACK), last);
  return block.some((l) => TRUST_RE.test(l)) && block.some((l) => TRUST_ROW_RE.test(l));
}

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
  // ⚠ NOTHING ABOVE OR INSIDE A LIVE COMPOSER BOX IS A DIALOG (2026-10-02).
  // Claude Code draws a selector INSTEAD of the composer, never above one, so a
  // box drawn at the bottom means every numbered run and every footer phrase
  // over it is conversation: the person's own echoed `❯ 1. fix the backup job`
  // / `  2. then rerun the audit`, Claude's prose saying "press esc to cancel",
  // or a numbered draft still sitting in the box. Nets 2 and 3 read all of them
  // as a live selector, and since a modal hold has no ceiling every later send
  // — the Screen tab's typing included — waited until enough output scrolled
  // the history out of the lookback, which after a short reply is never. Net 1
  // (the structural detector) and the trust check still read the whole pane.
  const box = composerBox(plain, last);
  const regionFrom = box && box.bottom >= 0 ? box.bottom + 1 : Math.max(0, last - DIALOG_LOOKBACK);
  const region = plain.slice(regionFrom, last + 1);
  const prompt = detectPrompt(arr);
  // Trust first: it is a modal too, but it is the one with a destructive
  // default, and a caller that logs `why` should be able to say so. Recognised
  // by its rows, never by its sentence alone (see trustDialogUp).
  if (trustDialogUp(arr, plain, last, prompt)) return 'trust';
  // 1 — the sibling detector, whole pane, structural.
  if (prompt) return 'modal';
  // 2 — a live selector's footer near the bottom, with a numbered row ABOVE it.
  // (2026-10-02: "above", not "anywhere on the pane" — a footer phrase with a
  // numbered line somewhere below it is not drawn the way a selector draws.)
  const footer = region.findIndex((l) => DIALOG_FOOTER_RE.test(l));
  if (footer >= 0 && plain.slice(0, regionFrom + footer).some((l) => OPTION_ROW_RE.test(l))) return 'modal';
  // 3 — a cursored row backed by a second option row, in the bottom region.
  if (region.some((l) => SELECTOR_ROW_RE.test(l))
    && region.filter((l) => OPTION_ROW_RE.test(l)).length >= 2) return 'modal';
  // 4 — a selector drawn with no numbers at all (Claude Code's `hideIndexes`:
  // EnterPlanMode, the decline-first permission ask). Every net above needs a
  // numbered row, so a message sent while one was up was pasted into it and
  // lost. Read by its frame, never its words (pane.js detectUnnumberedSelector).
  // Not under a shell prompt: that is a dead claude's last frame, and the send
  // is the shell refusal's to answer, not a hold with no end.
  if (!shellPromptRow(plain[last]) && detectUnnumberedSelector(arr)) return 'modal';
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
 *     [jotter] Fable 5.1 · ctx 43% · main ~5     <- statusline
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
  // ⚠ A SHELL PROMPT UNDER THE FRAME OUTRANKS THE CARET IN IT. When `claude`
  // dies without cleaning up — killed, crashed, out of memory — its last frame
  // stays on screen and the login shell `cc` falls through to prints its prompt
  // BELOW it. The caret row in that dead frame is still inside the lookback, so
  // this said "Claude is up", `submitRefusal` stood aside, and a chat message
  // was pasted and submitted to a root shell: the #15 class, by a second door.
  // Claude Code draws nothing under its own box at column 0, so a prompt there
  // is the shell's, and everything above it is history.
  if (shellPromptRow(plain[last])) return false;
  if (shellModeBox(plain, last)) return true;      // the box with `!` for its caret
  const from = Math.max(0, last - DIALOG_LOOKBACK);
  return plain.slice(from, last + 1).some((l) => CARET_EMPTY_RE.test(l) || CARET_TYPED_RE.test(l));
}

/**
 * Is this (last, right-trimmed) row a SHELL's prompt rather than Claude Code's
 * own furniture?
 *
 * Stricter than `shellPrompt` on purpose, because it has to be safe to read under
 * a LIVE composer: Claude Code's status line can end in `%` (`ctx 43%` with no
 * branch after it) and its other rows end in anything, but every row it draws
 * below the box is indented — `  [name] Fable 5.1 · ctx 43%`, `  ⏵⏵ auto mode
 * on…`, `  ? for shortcuts` — while a shell prints its prompt from column 0.
 * The rules, the caret row and a dialog's own rows are never a prompt.
 */
function shellPromptRow(row) {
  const s = String(row || '');
  if (!s || /^\s/.test(s)) return false;
  if (!SHELL_PROMPT_RE.test(s)) return false;
  if (CARET_ROW_RE.test(s) || RULE_RE.test(s)) return false;
  return true;
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
 * ─── THE GHOST IS NOT A DRAFT ──────────────────────────────────────────────
 *
 * ⚠ P-14 (2026-09-19). Claude Code draws an inline SUGGESTION in an empty
 * composer — the last thing you typed here, offered back in dim text, taken
 * with →. The raw row is
 *
 *   '\x1b[39m❯\xa0\x1b[2mrun sleep 10 in the background then say doneB\x1b[0m'
 *
 * with the cursor still at column 2: the box is EMPTY and Claude Code is
 * proposing something. Strip the escapes and it is indistinguishable from
 * somebody's half-typed sentence, which is exactly what `composerHoldsDraft`
 * said about it (verified against a live pane: `composerText` = the suggestion,
 * `composerEmpty` = false, `composerHoldsDraft` = true). The 3.5.1 belt-and-
 * braces — text AND a live-view keypress inside 60 s — saves it only for
 * somebody who has NOT just used the Screen tab, which is the one person the
 * guard is for. A phantom `blockedBy:"draft"` holds their next message for a
 * minute over a suggestion nobody typed.
 *
 * The tell is the dim attribute itself (SGR 2), so the pane has to be captured
 * WITH its escapes (`tmux capture-pane -e`) and the dim runs dropped before the
 * text is read. `\x1b[0m` and `\x1b[22m` end a run; a multi-parameter SGR is
 * read parameter by parameter, because `\x1b[2;37m` is one sequence saying two
 * things.
 *
 * ⚠ THE CARET AND THE WHITESPACE ARE NEVER DROPPED, whatever they are drawn in.
 * `composerText` finds the box by its `❯`, and a build that dimmed the caret
 * would make this function delete the composer — turning the draft guard off
 * silently, which is worse than the bug it fixes. Whitespace is kept for the
 * same structural reason; `squashPane` removes it from the comparison anyway.
 *
 * A capture taken WITHOUT `-e` carries no escapes at all, so this is a no-op on
 * one — every existing caller and fixture reads exactly as it did.
 */
const ESC = '\u001B';
function stripGhost(line) {
  const s = String(line || '');
  if (!s.includes(ESC)) return s;
  let out = '';
  let dim = false;
  let i = 0;
  while (i < s.length) {
    if (s[i] === ESC && s[i + 1] === '[') {
      let j = i + 2;
      while (j < s.length && !(s[j] >= '@' && s[j] <= '~')) j++;
      if (j < s.length && s[j] === 'm') {
        for (const part of s.slice(i + 2, j).split(';')) {
          const n = Number(part === '' ? '0' : part);
          if (n === 2) dim = true;
          else if (n === 0 || n === 22) dim = false;
        }
      }
      i = j < s.length ? j + 1 : s.length;
      continue;
    }
    const ch = s[i];
    if (!dim || ch === '❯' || /\s/.test(ch)) out += ch;
    i++;
  }
  return out;
}

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
//
// ⚠ A RULE IS A ROW THAT STARTS AT COLUMN 0 (2026-10-02). This used to be
// tested on the TRIMMED row, and the trim is what removed the only thing that
// tells the box's own rule from a line of the message: Claude Code draws its
// rules from column 0 and every continuation row of the composer indented by
// two. A short message with a markdown rule or a setext underline in it —
// `## Plan` / `---` / `step one…`, `Status` / `======` / `all green` — was cut
// at the `---`, neither probe was found in what was left, the recovery called
// the box "somebody's draft" and chose 'leave', and the message sat in the box
// unsent while the POST answered delivered:true.
const RULE_RE = /^[─━—–_=-]{3,}$/;
const CARET_ROW_RE = /^\s*❯/;
// The composer's input row in SHELL MODE. A `!` typed into an empty box (or a
// paste that starts with one) switches Claude Code 2.1.302 into it: the box's
// `❯` is drawn as `!` (`mode==="bash" ? "!\xA0" : [pointer, "\xA0"]`), the
// footer reads `! for shell mode`, and Enter runs the box as a shell command.
const SHELL_CARET_ROW_RE = /^!(?:\s|$)/;

/**
 * Where the live composer BOX is: its top rule, its caret row, its closing rule.
 *
 * ⚠ FOUND BY ITS STRUCTURE, NOT BY THE LAST CARET GLYPH (2026-10-02). The box
 * is a column-0 rule with the caret row directly under it — every real capture
 * in test/fixtures/prompts draws it that way, and neither an echoed message
 * (a blank row above it) nor a dialog's cursored row (a title or blank above
 * it) does. The bottom-most caret was the old rule, and a message with a line
 * of its own that starts with `❯` — a terminal snippet pasted in, drawn as the
 * continuation row `  ❯ hello world` — moved "the composer" down onto that row.
 * The probes were never found in what it read, the recovery chose 'leave', and
 * the person's retry was then swallowed as a duplicate of a message that had
 * never been sent.
 *
 * Null when no such box is drawn in the bottom region; callers fall back to
 * what they did before, so a pane shaped some other way reads as it always did.
 *
 * ⚠ AND THE BOX IN SHELL MODE IS THE SAME BOX (`shellMode`). Its caret row
 * starts with `!` instead of `❯`, and a rule for the box that only knew the `❯`
 * saw no composer at all once the last echoed `❯` had scrolled out of the
 * lookback: no draft to hold for, no shell refusal, a paste that "landed" in
 * the bottom rows — and an Enter that ran the message as a root shell command.
 * The `!` row counts only with the box's closing rule under it as well.
 */
function composerBox(plain, last) {
  const from = Math.max(1, last - DIALOG_LOOKBACK);
  for (let i = last; i >= from; i--) {
    const shellMode = SHELL_CARET_ROW_RE.test(plain[i]);
    if (!(shellMode || CARET_ROW_RE.test(plain[i])) || !RULE_RE.test(plain[i - 1])) continue;
    let bottom = -1;
    for (let j = i + 1; j <= last; j++) { if (RULE_RE.test(plain[j])) { bottom = j; break; } }
    if (shellMode && bottom < 0) continue;
    return { top: i - 1, caret: i, bottom, shellMode };
  }
  return null;
}

/** Is the composer box drawn, and in shell mode? (`plain` rows, `last` non-blank.) */
function shellModeBox(plain, last) {
  const box = last >= 0 ? composerBox(plain, last) : null;
  return !!(box && box.shellMode);
}

/**
 * Is the session's composer in SHELL MODE, where Enter runs what is in the box
 * as a shell command? (See composerBox.) Nothing may be submitted into it: the
 * message would run as a command in the session's directory.
 */
function composerShellMode(lines) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  return shellModeBox(plain, last);
}

function composerText(lines, { dropGhost = false } = {}) {
  const arr = Array.isArray(lines) ? lines : String(lines || '').split('\n');
  const plain = arr.map((l) => stripAnsi(dropGhost ? stripGhost(l) : String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  if (last < 0) return null;
  const from = Math.max(0, last - DIALOG_LOOKBACK);
  const box = composerBox(plain, last);
  let caret = box ? box.caret : -1;
  if (caret < 0) {
    for (let i = last; i >= from; i--) { if (CARET_ROW_RE.test(plain[i])) { caret = i; break; } }
  }
  if (caret < 0) return null;
  // The `!` of shell mode is the box's mode, like the `❯`, not text in it.
  const out = [plain[caret].replace(box && box.shellMode ? /^!\s?/ : /^\s*❯\s?/, '')];
  for (let i = caret + 1; i <= last; i++) {
    if (RULE_RE.test(plain[i])) break;   // column 0 only — see RULE_RE
    out.push(plain[i]);
  }
  return out.join('\n');
}

/**
 * The characters Claude Code 2.1.302 calls invisible — `k()` in the classifier
 * its prompt submit runs (read from the binary), less TAB and LF. Some it always
 * removes, some only out of context (a joiner inside an emoji or an Indic word
 * stays); either way a pane may not show them.
 */
const INVISIBLE_RE = /[\u0000-\u0008\u000B-\u001F\u007F-\u009F\u00AD\u034F\u061C\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u2028-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\u{1107F}\u{13430}-\u{1343F}\u{16FE4}\u{1BCA0}-\u{1BCA3}\u{1D173}-\u{1D17A}\u{E0000}-\u{E0FFF}]/gu;

/**
 * Comparison spelling for pane text: ANSI stripped and ALL whitespace removed.
 *
 * Whitespace goes because tmux wraps a long line at the pane width with no
 * separator at all, so a 200-character message arrives as three rows split
 * mid-word. Removing whitespace from both sides of the comparison makes the
 * wrap invisible instead of making the match impossible.
 *
 * ⚠ AND THE INVISIBLE CHARACTERS GO TOO (INVISIBLE_RE). Claude Code 2.1.30x will
 * not submit a prompt holding them: it removes them, puts the rest back in the
 * box, and sends nothing — and its renderer draws some of them not at all. With
 * them left in, a message carrying one either never "landed" (the recovery left
 * it in the box, and every retry pasted another copy) or "left the box" the
 * moment Claude Code cleaned it (a refused message reported submitted, and the
 * person's retry swallowed as its duplicate). Judged without them on both sides,
 * the box is read as a person would read it.
 */
function squashPane(s) {
  return stripAnsi(String(s || '')).replace(/\s+/g, '').replace(INVISIBLE_RE, '');
}

/**
 * What a COLLAPSED paste looks like in the composer — and it is most of them.
 *
 * A long multi-line paste renders as `[Pasted text #1 +29 lines]` and the text
 * itself is never on screen at all (measured: a 40-line paste shows the marker
 * 60 ms after paste-buffer and nothing else, forever). A rule that looked only
 * for the text would time out on every multi-line message there is, which is
 * most of what a person sends from a phone. Matched against the squashed
 * spelling, hence no spaces; `#N` is the paste index and is optional because it
 * is not in every build.
 *
 * ⚠ AND THE SUFFIX IS OPTIONAL TOO (2026-10-01). Measured on 2.1.280: a ONE-LINE
 * paste of ~850 characters or more — a brief typed as a single paragraph on the
 * phone, which is how the owner writes them — collapses to `[Pasted text #1]`
 * with no line count at all, while a short three-line paste is not collapsed at
 * all. A pattern that demanded `+N lines` called every long paragraph "never
 * appeared", the recovery found the box non-empty and chose 'leave', and the
 * message sat there unsent (`devxapp`, 2026-09-28: `❯ [Pasted text #1]` in
 * the journal line itself). `\w+` rather than `lines?` so a future `+N chars`
 * reads the same way.
 */
const PASTED_MARKER_RE = /\[Pastedtext(?:#\d+)?(?:\+\d+\w+)?\]/i;

/** The needle: enough of the message to be unmistakable, short enough to fit a row. */
function pasteProbe(text) { return squashPane(text).slice(0, 32); }

/**
 * BOTH ends of the message, because the composer may be showing either.
 *
 * ⚠ THE BOX SCROLLS (2026-10-01). On the 23-25 row panes the live sessions
 * actually have, Claude Code's input box caps at about seven rows and then
 * scrolls to keep the cursor — the END of the paste — in view, so the caret row
 * shows the middle of a 700-character brief and its first 32 characters are
 * nowhere on screen. The head probe alone read that as "never appeared"; the
 * recovery saw a non-empty box and chose 'leave'; no Enter was pressed. Three
 * of the five journal lines in five days (`homead`, `tailscale`,
 * `bursarxledger`) show the tail of the person's own message in the box.
 *
 * The tail is as unmistakable as the head for the same reason, and a message
 * short enough for the two to overlap yields one probe, not two.
 */
function pasteProbes(text) {
  const squashed = squashPane(text);
  const head = squashed.slice(0, 32);
  if (!head) return [];
  const tail = squashed.slice(-32);
  return tail === head ? [head] : [head, tail];
}

/** Is any spelling of this message — head, tail, or the collapsed marker — in the region? */
function probeSeen(region, text) {
  const probes = pasteProbes(text);
  return probes.some((p) => region.includes(p)) || PASTED_MARKER_RE.test(region);
}

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
  if (!pasteProbe(text)) return true;            // nothing was sent; nothing to wait for
  return probeSeen(settleRegion(lines), text);
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
  if (!pasteProbe(text)) return true;
  return probeSeen(settleRegion(beforeLines), text);
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
  if (!pasteProbe(text)) return true;
  return !probeSeen(squashPane(c), text);
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
const COMPOSER_PLACEHOLDER_RE = /^(Try".*"|Press up to edit queued messages.*)$/i;

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

/**
 * ─── WHOSE TEXT IS IN THE BOX ──────────────────────────────────────────────
 *
 * ⚠ THE P1 THIS EXISTS FOR (2026-09-19 04:04:01Z, the owner's own session).
 * A message queued behind a turn boundary was released into a composer that
 * already held the owner's half-typed draft — `❯  was think ask againaskada` —
 * and the daemon did what it has always done: pasted after it, watched the
 * probe appear (it WAS on screen, amid their words), and pressed Enter. The
 * transcript recorded one `user` record holding both:
 *
 *   " was think ask againaskadaask questions again, side note: i was thinking…"
 *
 * Their draft was submitted, as part of a sentence they never wrote. `composerEmpty`
 * was the only rule that could have spoken and nothing on the DELIVERY path asked
 * it: the whole composer-is-theirs question lived in the lost-paste RECOVERY, which
 * only ever runs when a paste failed to appear. A paste that lands perfectly on
 * top of somebody's draft never went near it.
 *
 * So the question is asked BEFORE the paste, by the gate, for every send there is:
 *
 *   null   no composer here at all (a shell, a pane still booting). This rule
 *          cannot speak, and `draftHold` below turns on the difference — a
 *          session appd types into blind keeps the behaviour it has always had.
 *   false  empty, the placeholder hint, or the very text we are about to paste
 *          (a resend, or a collapsed-paste marker left by our own first try —
 *          the same reading `pasteIndistinguishable` makes).
 *   true   somebody else's. A draft, or live-view keystrokes still in flight.
 *
 * ⚠ THE PROBE, NOT THE WHOLE MESSAGE, on the "ours" half. A pane 100 columns wide
 * shows the first rows of a long paste and a `[Pasted text +40 lines]` marker for
 * the rest, so comparing the full text would read every long resend as a stranger's
 * draft and hold it for ten minutes.
 */
/**
 * Does THIS text explain everything in the box?
 *
 * ⚠ FROM THE BOX TO THE MESSAGE, NEVER THE OTHER WAY ROUND (2026-10-02). The
 * old test asked whether the message's probe appeared somewhere in the box, and
 * a message of 32 characters or fewer IS its own probe — so `ok` was "already
 * in" a draft reading `look at the token cost`, `yes` in `yesterday`, `no` in
 * `not`. The draft guard called the person's sentence ours, the hold collapsed
 * from the 60 s keys window to the 5 s quiet, and the reply was pasted onto the
 * end of their draft and submitted with it (`look at the token cost xok`), with
 * no intoDraft notice because nothing thought it had been a draft.
 *
 * So the box is ours only when the message accounts for ALL of it: the whole
 * message (a resend), or a contiguous piece at least a probe long (a long paste
 * scrolled in the box, SCROLLED_PANE). A short draft that merely occurs inside
 * a longer message is still a person's.
 */
function boxExplainedBy(squashed, text) {
  const msg = squashPane(text);
  if (!msg || !squashed) return false;
  if (squashed === msg) return true;
  return squashed.length >= 32 && msg.includes(squashed);
}

/**
 * Could this text have been drawn as a collapsed `[Pasted text …]` marker?
 *
 * Measured on 2.1.280 (PASTED_MARKER_RE): a one-line paste of ~850 characters
 * collapses, a short three-line paste does not. Generous on both counts, because
 * this is only ever asked about appd's OWN last paste.
 */
function wouldCollapse(text) {
  const s = String(text || '');
  return s.length >= 800 || s.split('\n').length > 3;
}
const PASTED_MARKER_ALL_RE = new RegExp(PASTED_MARKER_RE.source, 'gi');

/**
 * The paste-safe spelling of a message: no bracketed-paste markers inside it.
 *
 * ⚠ THE TEXT COULD END THE PASTE ITSELF (2026-10-02). tmux wraps the buffer in
 * ESC[200~ … ESC[201~ and passes markers already INSIDE it through untouched
 * (verified byte for byte on tmux 3.6b), so a message carrying ESC[201~ closed
 * the paste early and everything after it reached the TUI as typed keys — a CR
 * there is an Enter. `first part ESC[201~ CR second part` submitted `first part`
 * on its own and left `second part` in the box, and the POST said delivered.
 * Any automated or scratchpad-derived text can carry an escape like that.
 *
 * Removed until none is left, because taking one out can splice another one
 * together. Nothing else is touched: inside a bracketed paste every other byte
 * is literal, which is the reason the paste is bracketed at all.
 */
const PASTE_MARKER_RE = /\u001b\[20[01]~/g;
function pasteSafe(text) {
  let s = String(text == null ? '' : text);
  for (;;) {
    const next = s.replace(PASTE_MARKER_RE, '');
    if (next === s) return s;
    s = next;
  }
}

/**
 * The spelling of a MESSAGE that Claude Code 2.1.30x will submit as it is: what
 * pasteSafe leaves, minus what its prompt submit would take out at the Enter.
 *
 * ⚠ A PROMPT WITH INVISIBLE CHARACTERS IS NOT SUBMITTED. On Enter, Claude Code
 * removes them, puts the cleaned text back in the box, says `Removed 1 invisible
 * character · review and press Enter to send`, and sends nothing — and the
 * person who pressed Send in the app is not looking at that box. A soft hyphen or
 * a zero-width joiner copied off a web page was enough to make a message
 * impossible to send from the app at all.
 *
 * So the characters it would remove are removed here first, and the prompt it
 * receives is the one it would have sent after that second Enter. Only where the
 * removal is certain, so this never takes out a character Claude Code keeps:
 *   - line breaks it rewrites (VT, FF, NEL, LS, PS, a lone CR) become the LF it
 *     would make of them; CRLF becomes LF;
 *   - ALWAYS_REMOVED_RE, which its classifier removes in every context (the
 *     tag characters too, except the three flag sequences it keeps);
 *   - the rest of INVISIBLE_RE — joiners, ZWSP, LRM/RLM, variation selectors,
 *     CGJ — only on a line that is otherwise plain ASCII. Each of those is kept
 *     only beside a particular script, an emoji or a combining mark, none of
 *     which an ASCII line has; anywhere else Claude Code decides, and whatever it
 *     refuses is reported honestly (squashPane) and goes on the person's retry.
 *
 * Messages only. The Screen tab's own typing is a person at the box, who sees
 * the notice and answers it; it keeps pasteSafe.
 */
const LINE_BREAK_RE = /\r\n|[\r\u000B\u000C\u0085\u2028\u2029]/g;
const ALWAYS_REMOVED_RE = /[\u0000-\u0008\u000E-\u001F\u007F-\u0084\u0086-\u009F\u00AD\u115F\u1160\u202A-\u202E\u2060-\u206F\u3164\uFE03-\uFE0D\uFEFF\uFFA0\uFFF0-\uFFFB\u{16FE4}\u{1D173}-\u{1D17A}]/gu;
// The subdivision flags (England, Scotland, Wales) are the one use of tag
// characters it keeps; every other one goes.
const TAG_RE = /\u{1F3F4}\u{E0067}\u{E0062}(?:\u{E0065}\u{E006E}\u{E0067}|\u{E0073}\u{E0063}\u{E0074}|\u{E0077}\u{E006C}\u{E0073})\u{E007F}|[\u{E0000}-\u{E0FFF}]/gu;
function sendable(text) {
  const s = pasteSafe(text)
    .replace(LINE_BREAK_RE, '\n')
    .replace(ALWAYS_REMOVED_RE, '')
    .replace(TAG_RE, (m) => (m.codePointAt(0) === 0x1F3F4 ? m : ''));
  return s.split('\n').map((line) => {
    const bare = line.replace(INVISIBLE_RE, '');
    return /^[\x00-\x7F]*$/.test(bare) ? bare : line;
  }).join('\n');
}

/**
 * Is the box holding exactly this message already — so the send is an Enter?
 *
 * The 'leave' recovery strands a message in the box on purpose, and the person's
 * natural move is to press Send again. Pasting a second copy behind the first
 * and pressing Enter submits the message twice, run together (2026-10-02). The
 * caller passes the GHOST-FREE composer (`gate.composerHolds`): a dim
 * suggestion of the same words is an empty box, and an Enter there sends nothing.
 */
function boxHoldsExactly(composerHolds, text) {
  if (composerHolds == null) return false;
  const squashed = squashPane(composerHolds);
  return !!squashed && squashed === squashPane(text);
}

/**
 * Is what the box holds the message appd typed into it — and nothing else?
 *
 * Broader than `boxHoldsExactly` in the two ways a box legitimately shows a
 * message that is in it: a long paste collapsed to its `[Pasted text …]` marker,
 * and a long one scrolled so only a piece of it is on screen (`boxExplainedBy`).
 * Never true for a box with somebody else's words in it as well.
 */
function boxIsOurs(composerHolds, text) {
  if (composerHolds == null) return false;
  const squashed = squashPane(composerHolds);
  if (!squashed) return false;
  if (boxExplainedBy(squashed, text)) return true;
  return PASTED_MARKER_RE.test(squashed) && !squashed.replace(PASTED_MARKER_ALL_RE, '')
    && wouldCollapse(text);
}

/**
 * ─── LOOK BEFORE THE ENTER ─────────────────────────────────────────────────
 *
 * ⚠ THE ENTER THAT SUBMITS A MESSAGE IS ALSO THE KEY THAT ANSWERS A DIALOG.
 * A send is released off one capture, pasted, given a settle wait and a beat —
 * and the Enter went out with no fresh look at the pane. A permission prompt or
 * an AskUserQuestion that a background subagent or a hook raised in that gap
 * takes the Enter on its highlighted row, and on a tool prompt that row is Yes:
 * the owner approves a tool call they never saw, by sending a chat message. The
 * same is true of the Enter that resubmits a message already in the box, and of
 * the Enter the lost-paste recoveries press.
 *
 * So the pane is captured once more immediately before every such Enter, and
 * the Enter goes only to the composer:
 *
 *   null                       press — the composer holds exactly our message,
 *                              or nothing on screen is a dialog (a pane with no
 *                              composer keeps the blind behaviour it always had)
 *   {blockedBy:'modal'|'trust'} a dialog is up: the message stays in the box,
 *                              unsent, and the send is HELD until it is answered
 *   {blockedBy:'shell'}        claude died under the paste and a shell prompt is
 *                              on the last row: nothing is run, nothing is pressed
 *   {blockedBy:'shell',        the box is in shell mode (a `!` typed before the
 *    shellMode:true}           paste, or a paste that starts with one), where the
 *                              Enter runs the box as a command: nothing is pressed
 *   {blockedBy:'modal'}        a composer was drawn before the paste and none is
 *                              now — something with no caret of its own replaced
 *                              it, and an Enter there answers whatever it is
 *
 * The composer holding our text is checked FIRST because a message is allowed
 * to look like a dialog: `1. fix the backup` / `2. rerun` in a box with no status
 * line under it reads as a selector run to `detectPrompt`, and must still go.
 */
function enterRefusal(lines, text, { hadComposer = false } = {}) {
  if (lines == null) return null;    // nothing could be read: the blind rule, as before
  const arr = Array.isArray(lines) ? lines : String(lines).split('\n');
  const plain = arr.map((l) => stripAnsi(String(l)).replace(/\s+$/, ''));
  let last = -1;
  for (let i = plain.length - 1; i >= 0; i--) { if (plain[i].trim()) { last = i; break; } }
  if (last >= 0 && shellPromptRow(plain[last])) {
    return { blockedBy: 'shell', why: 'claude is no longer running in the pane (a shell prompt is on its last row)' };
  }
  // Before "the box holds our message", which shell mode does too: there the
  // Enter is a command, whoever's text it is.
  if (shellModeBox(plain, last)) {
    return { blockedBy: 'shell', shellMode: true, why: 'the prompt is in shell mode (!), where Enter runs it as a command' };
  }
  const c = composerText(arr);
  if (c !== null && boxIsOurs(c, text) && !dialogUnderBox(plain, last)) return null;
  const why = paneReadyForInput(arr).why;
  if (paneBlocks(why)) {
    return { blockedBy: why === 'trust' ? 'trust' : 'modal', why: 'a dialog opened before the Enter' };
  }
  if (hadComposer && c === null) {
    return { blockedBy: 'modal', why: 'the composer was replaced by something with no caret' };
  }
  return null;
}

/**
 * Is a dialog drawn BELOW the composer box `composerText` found?
 *
 * A frame that left an old box on screen with a selector painted under it reads,
 * to `composerText`, as a box holding our message — the structural box is found
 * first. The rows under its closing rule are the status lines and nothing else
 * while the box is live; a cursored option row, a selector footer or a whole
 * dialog there means the box is history and the selector has the keyboard.
 */
function dialogUnderBox(plain, last) {
  const box = composerBox(plain, last);
  if (!box || box.bottom < 0) return false;
  const below = plain.slice(box.bottom + 1, last + 1);
  if (!below.length) return false;
  return below.some((l) => SELECTOR_ROW_RE.test(l) || DIALOG_FOOTER_RE.test(l)
    || (TRUST_ROW_RE.test(l) && /❯/.test(l))) || !!detectPrompt(below)
    || detectUnnumberedSelector(below);
}

function enterHeldLogLine(name, held, lines) {
  return `typing: ${name}: not pressing Enter — ${held.why}; the message is in the box, unsent `
    + `(${held.blockedBy}) | pane: ${paneTail(lines)}`;
}

function composerHoldsDraft(composerHolds, text, ours = null) {
  if (composerHolds === null || composerHolds === undefined) return null;
  const squashed = squashPane(composerHolds);
  if (!squashed) return false;
  if (COMPOSER_PLACEHOLDER_RE.test(squashed)) return false;
  if (boxExplainedBy(squashed, text)) return false;
  // ⚠ A `[Pasted text …]` MARKER IS OURS ONLY IF OUR LAST PASTE MADE IT
  // (2026-10-02). The marker says nothing about WHOSE paste collapsed: the
  // person's own long Screen-tab paste draws the same one. Read as ours
  // whatever it stood for, `[Pasted text #1 +40 lines] summarise this x` was
  // released after the 5 s quiet and the next message was submitted welded to
  // it. So: a box holding nothing but marker(s), and only when appd's own
  // remembered paste was long enough to be drawn that way.
  if (PASTED_MARKER_RE.test(squashed) && !squashed.replace(PASTED_MARKER_ALL_RE, '')
      && ours && wouldCollapse(ours)) return false;
  // ⚠ AND OUR OWN LEFTOVERS ARE NOT A DRAFT. A pane can strand a piece of the
  // LAST thing appd pasted into it: the recovery's 'leave' branch deliberately
  // presses no Enter, and a frame whose final line carries no newline sits in
  // the box afterwards (`❯ [End brief. Size this project…]`, seen in the
  // projects suite). Reading that as a person mid-sentence would hold every
  // later message to that session for the full ten minutes, one after another —
  // a deadlock built out of a guard. What is left of our own paste is ours to
  // join; the stalled-submit line above is what owns that problem.
  //
  // A SUBSTRING, because what survives is a fragment of what went in — and
  // never the other way round: a draft that has GROWN past our last paste (the
  // Screen tab typing character by character, which is exactly how the owner's
  // draft was built) is not contained in it and stays a draft.
  const mine = squashPane(ours || '');
  if (mine && mine.includes(squashed)) return false;
  return true;
}

/** How long a queued message may be held by somebody's unsent draft. */
const DRAFT_HOLD_MAX_MS = QUEUE_MAX_WAIT_MS;

/**
 * How long after a live-view keypress the composer still belongs to the person.
 *
 * ⚠ BECAUSE THE PANE IS A PICTURE AND KEYSTROKES ARE IN FLIGHT. The Screen tab
 * sends one `/keys` per keypress; between the POST landing and the TUI painting
 * the character there is a window in which `composerHoldsDraft` truthfully
 * answers "empty" about a box somebody is actively typing into. On the night of
 * the incident the burst ran 03:58:25→03:59:11Z at up to nine posts a second.
 * Five seconds is two orders over the paint and short enough that a person who
 * stopped typing is not kept waiting for a message they can see is queued.
 */
const LIVE_KEYS_WINDOW_MS = 60_000;

/**
 * Should this send be held because the composer is somebody's, not ours?
 *
 * Two ways the box is theirs, and the second is the one a capture cannot see:
 * text is in it, or a keystroke reached this session in the last few seconds.
 * Held, never dropped, and BOUNDED — a hold a person cannot see the end of is
 * the same bug as a message that vanishes, so after `maxMs` the message goes.
 * The ceiling is the one the attention hold uses, for the same reason: one
 * number to remember.
 *
 * `draft === null` — no composer at all — never holds. A plain shell has no box
 * to own, and refusing to type into one would break every non-Claude pane the
 * app can open.
 */
function draftHold({ draft = null, keysAgoMs = null, waitedMs = 0,
  maxMs = DRAFT_HOLD_MAX_MS, keysWindowMs = LIVE_KEYS_WINDOW_MS } = {}) {
  if (draft === null) return false;
  const waited = Number(waitedMs);
  if (Number.isFinite(waited) && waited >= maxMs) return false;
  // ⚠ 3.5.1: A CAPTURE ALONE IS NOT ENOUGH. On 3.5.0 the guard held every send
  // on the owner's session for minutes at a time because Claude Code draws its
  // OWN queued messages and its "Press up to edit queued messages" hint where
  // the composer is, and a screen read cannot tell those from a person's
  // draft. What a capture cannot see, the keystroke stream can: a draft is a
  // thing somebody TYPED here, and the daemon saw every key of it. So a hold
  // needs both — text in the box AND a live-view keypress inside the window.
  // A pane with no composer is never held by this rule (draft === null); a box
  // that LOOKS empty or full decides nothing on its own — only the keys do.
  // ⚠ `== null` FIRST, because `Number(null)` is 0 and 0 ms ago is the most
  // recent keypress there is. Read as a number, "nobody has ever typed here"
  // became "somebody is typing right now" and held every send on every session
  // for the width of the window.
  if (keysAgoMs == null) return false;
  const ago = Number(keysAgoMs);
  if (!Number.isFinite(ago) || ago < 0) return false;
  // Text in the box: theirs for the whole window after the last key. A box that
  // reads empty: theirs only while keys are still in flight (a keypress accepted
  // by the route but not yet painted), so clearing a draft frees the send in
  // seconds rather than a minute.
  return ago < (draft === true ? keysWindowMs : LIVE_KEYS_QUIET_MS);
}
const LIVE_KEYS_QUIET_MS = 5_000;

/**
 * Should a pane SCRIPT (a queue job) wait because the box is not a bare caret?
 *
 * ⚠ A JOB TYPES TOO. The draft guard left every job out on the grounds that "a
 * pane script types no text" — and the one job there is, the ladder's picker
 * walk, starts by typing `/model` and pressing Enter. Into a box holding the
 * owner's half-written sentence that is `please check the backup and then/model`
 * submitted as a prompt: the picker never opens, and their draft is sent for
 * them, which is the 2026-09-19 harm the guard exists to prevent.
 *
 * Stricter than `draftHold`, on purpose: no live-keys condition and no "our own
 * leftovers" exemption. A message may be joined to what is in the box (decision
 * 59 says when); `/model` only means anything typed into an EMPTY box, so
 * anything a person could see in it holds the job — a draft typed in an attached
 * terminal, a message appd stranded there, Claude Code's own queued messages.
 * `composerHolds` is the ghost-free read, so the dim suggestion is not text, and
 * the placeholder hint is not either.
 *
 * Bounded like every other hold. An automated job is dropped at the same ten
 * minutes before this lets go; one a person asked for (Undo) is released, and
 * applyLadder looks again before typing and refuses rather than type into text.
 * `composerHolds === null` — no composer at all — never holds, as above.
 */
function jobHold({ composerHolds = null, waitedMs = 0, maxMs = DRAFT_HOLD_MAX_MS } = {}) {
  if (composerHolds === null || composerHolds === undefined) return false;
  const waited = Number(waitedMs);
  if (Number.isFinite(waited) && waited >= maxMs) return false;
  const squashed = squashPane(composerHolds);
  return !!squashed && !COMPOSER_PLACEHOLDER_RE.test(squashed);
}

function draftHeldLogLine(name, holds) {
  return `typing: ${name}: holding a send — there is unsent text in the live view `
    + `| composer: ${String(holds || '').replace(/\s+/g, ' ').trim().slice(0, 120)}`;
}
function draftClearedLogLine(name, waitedMs) {
  return `typing: ${name}: the live view's composer is free again after ${waitedMs}ms; sending`;
}
function draftOverdueLogLine(name, waitedMs, holds) {
  return `typing: ${name}: unsent text has been in the live view for ${Math.round(waitedMs / 1000)}s `
    + `and a message cannot wait forever; sending into it anyway `
    + `| composer: ${String(holds || '').replace(/\s+/g, ' ').trim().slice(0, 120)}`;
}

/**
 * What a paste that never appeared may do about it.
 *
 *   'blind'   no composer at all — press Enter as this always did.
 *   'resend'  a composer, empty. Re-paste ONCE.
 *   'landed'  our text IS there after all, in a box that held nothing of anybody
 *             else's before the paste — it simply arrived after the bound ran
 *             out. Press Enter; re-pasting would put the message in twice.
 *   'leave'   a composer holding something that is not ours. Nothing is typed
 *             and nothing is pressed.
 *   'stalled' a composer, empty, that was ALREADY DRAWN before the paste: the
 *             TUI is behind its input, not deaf to it. Press Enter behind the
 *             queued bytes; never re-paste (2026-10-02, see below).
 *
 * ⚠ 'landed' IS THE ANSWER THE TIMEOUT COULD NOT GIVE. `pasteLanded` is read
 * once more on a FRESH capture here, so "absent" and "present, late" stop being
 * the same verdict — and `before` is what separates "present, late, alone" from
 * "present, late, on top of a draft", which must never be submitted. When the
 * pane could not be read before the paste the doubt goes to the message: it is
 * on screen and a reader can see it, which is better than stranding it.
 */
function recoveryDecision(lines, opts = {}) {
  const { text = null, before = null } = opts;
  const empty = composerEmpty(lines);
  if (empty === null) return 'blind';
  // ⚠ AN EMPTY BOX IS PROOF OF LOSS ONLY IF THERE WAS NO BOX TO PASTE INTO
  // (2026-10-02). The lost band is bytes pasted BEFORE the TUI attached, and
  // `before` shows that: no composer yet. When `before` already showed a drawn
  // composer, a running TUI was reading the pty, and a running TUI does not
  // discard input — it is merely late (an event loop stalled on a loaded host).
  // The empty box at 3 s is a stale frame, the first paste is still queued, and
  // a re-paste lands behind it: measured with a TUI 3.6 s behind its stdin,
  // `summarise the overnight backup reportsummarise the overnight backup report`
  // was submitted as one prompt. 'stalled' presses Enter behind the queued
  // bytes and never pastes again.
  if (empty) return composerText(before) !== null ? 'stalled' : 'resend';
  if (text != null && pasteLanded(lines, text)
      && composerHoldsDraft(composerText(before), text) !== true) return 'landed';
  return 'leave';
}

/**
 * The same message, POSTed again while the first copy is STILL WAITING.
 *
 * ⚠ WHAT ACTUALLY DOUBLED THE OWNER'S MESSAGE (2026-09-19). The daemon did not
 * re-paste anything and Claude Code did not duplicate anything: the app posted
 * the same text three times — 04:00:16.419Z, 04:00:28.379Z, 04:00:32.818Z — into
 * a queue that was held, and the queue faithfully delivered three copies. There
 * is no retry in the client; a person pressing Send again at a composer that
 * emptied and then said nothing for forty-seven seconds is the whole mechanism.
 *
 * ⚠ AND ONLY AGAINST A COPY THAT HAS NOT GONE YET. "the same words twice in
 * thirty seconds" is a perfectly ordinary thing to mean — `yes`, `continue`, a
 * retried one-word answer — so a delivered message is never an excuse to swallow
 * the next one. A copy still sitting in the queue is different in kind: nothing
 * the sender could see has happened yet, and pressing Send again asks for the
 * message to arrive, not to arrive twice. The window caps it so that a send held
 * behind a ten-minute dialog can still be deliberately repeated.
 */
const DUPLICATE_WINDOW_MS = 30 * 1000;
const DUPLICATE_MIN_CHARS = 20;   // a re-press is a specific message; `yes` twice is two answers
function duplicatePending(entries, text, nowMs, windowMs = DUPLICATE_WINDOW_MS) {
  if (!Array.isArray(entries) || typeof text !== 'string' || !text) return null;
  const now = Number(nowMs);
  if (!Number.isFinite(now)) return null;
  for (const e of entries) {
    if (!e || e.automated || typeof e.run === 'function' || e.submit === false) continue;
    if (e.text !== text) continue;
    const at = Number(e.at);
    if (!Number.isFinite(at) || at <= 0 || now - at > windowMs) continue;
    return e;
  }
  return null;
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
  // ⚠ NOT `|| shell`. The MARK outranks the shell heuristic on purpose: while
  // `claude` boots, plain console text scrolls past and a line of it can end in
  // a prompt character, which is all `shellPrompt` looks for — the unmarked
  // rule has nothing better, this one does. Tried 2026-10-01 and caught by
  // `routes-session-start` ("the MARK holds it even though the pane looks like
  // a shell") and the projects suite's `shellish` role; a dead launch is held
  // for the whole grace and then refused as a shell, which is the right order.
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
 * May this queued text be SUBMITTED into the pane as it is now?
 *
 * ⚠ THE P1 THIS EXISTS FOR (#15). `claude` exits — a broken install, a node
 * upgrade, ENOSPC, or the owner's own /exit in a `cc`-style session whose start
 * command is `claude; exec "$SHELL" -l` — and what is left in the pane is a
 * ROOT LOGIN SHELL. The chat composer sends text and Enter together, so bash
 * ran the owner's message as a command: measured, `please rewrite > notes.txt
 * tomorrow` truncated notes.txt to zero bytes, because bash performs the
 * redirections before it fails to find the command. The route answered 200,
 * `/typing` reported no error, and the message was gone as well as executed.
 *
 * The discriminator is the PANE, not the state file — a session whose Claude
 * exited after running still has one — and it is narrow on purpose:
 *
 *   shell mode       the composer's caret is a `!` (composerShellMode): Claude
 *                    is up, and its Enter runs the box as a shell command — the
 *                    same harm by a second door. Refuse the SUBMIT.
 *   composer drawn   Claude is up. Nothing to refuse.
 *   shell prompt     the pane's last line ends in $ # % >. Refuse the SUBMIT.
 *   neither          a pane mid-redraw, an empty pane, a TUI that is not
 *                    Claude: say nothing. Refusing here would break every
 *                    non-Claude pane the app can open.
 *
 * Only the submit is refused. Text without an Enter still goes (the Screen
 * tab types into whatever is there and sends its Enter as a raw key), and raw
 * keys have never come through the queue at all.
 */
function submitRefusal({ submit = true, composer = false, shell = false, shellMode = false } = {}) {
  if (!submit) return null;
  if (shellMode) {
    return "this session's prompt is in shell mode (it starts with !), where Enter runs it as a "
      + 'shell command, so the message was not typed. Leave shell mode in the Screen tab '
      + '(Backspace on the empty prompt), then send it again.';
  }
  if (composer || !shell) return null;
  return 'claude is not running in this session — it dropped to a shell, so the message '
    + 'was not typed. Start claude in the pane, or use the Screen tab to drive the shell.';
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
 *   trust      the SAME hold, named. It is the one dialog whose pre-selected
 *              answer is destructive ("No, exit"), and `/screen` reports no
 *              prompt and no options for it either, so "a dialog is open on the
 *              screen" sends a reader looking for buttons that are not there.
 *              ⚠ M2 (round-2 review): `dialogWhy` has told the two apart since
 *              3.5.0 and its comment said "a caller that logs `why` should be
 *              able to say so" — and then this line flattened both to 'modal',
 *              so the word the 3.5.1 changelog advertises could never reach a
 *              client. It is the pane's own verdict, passed through.
 *   starting   there is no application in the pane yet, so the paste and its
 *              Enter go nowhere at all. Outranks everything below, and unlike
 *              the turn gate it holds a PERSON's message too — 3.0.3's rule is
 *              that a human send never waits for CLAUDE to finish, not that it
 *              may be thrown at a pane where Claude has not started.
 *   attention  a question is waiting; prose typed into a numbered prompt is
 *              lost or misread. Held, never dropped, for as long as it takes.
 *   draft      there is unsent text in the live view. Pasting in front of it
 *              merges two people's sentences into one and submits the result
 *              (2026-09-19; see `composerHoldsDraft`). LAST of the holds on
 *              purpose: a dialog pane has no ordinary composer, so `modal` and
 *              `attention` keep the words they always reported for it, and only
 *              a plain box with somebody's words in it reaches this line.
 *   then either boundary — the transcript's or the hook's — lets it go.
 */
function releaseDecision({ idle, paneWhy, state = null, starting = false, draft = false }) {
  if (paneBlocks(paneWhy)) return { release: false, blockedBy: paneWhy === 'trust' ? 'trust' : 'modal' };
  if (starting) return { release: false, blockedBy: 'starting' };
  if (state === 'hold') return { release: false, blockedBy: 'attention' };
  if (draft) return { release: false, blockedBy: 'draft' };
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
 * What a client is owed when a message went into somebody's draft anyway.
 *
 * ⚠ DECISION 59 (2026-09-19). The ceiling stays where `draftHold` puts it — 60 s
 * after the last live-view keystroke — because a hold a person cannot see the end
 * of is the same bug as a message that vanishes. But the daemon KNOWS when it
 * pasted in front of somebody's sentence (`draftOverdueLogLine` is written from
 * exactly that verdict) and until now the only place it said so was the journal.
 * A person who was typing deserves to be told that their next message went in on
 * top of it, in the app, where they are.
 *
 *   at        epoch SECONDS, like every other timestamp outside /v1/headroom
 *   waitedMs  how long the message had been held when it went
 *   composer  the first 120 characters of the draft it went into, so the notice
 *             can show what it landed on rather than asserting it abstractly
 *
 * Stored in ms and rendered here, so the caller keeps one clock.
 */
function intoDraftView(rec, max = 120) {
  if (!rec || !rec.at) return null;
  return {
    at: Math.floor(rec.at / 1000),
    waitedMs: Math.max(0, Math.round(Number(rec.waitedMs) || 0)),
    composer: String(rec.composer || '').replace(/\s+/g, ' ').trim().slice(0, max),
  };
}

/**
 * The `GET /v1/sessions/:name/typing` body, from in-memory state only.
 *
 * `waitedMs` is how long the HEAD of the queue has been waiting — the client's
 * half of "late beats lost": a message held ninety seconds behind a turn can say
 * so, instead of looking to its sender like nothing happened. Zero when nothing
 * is queued, and never negative however the clock moves.
 *
 * `intoDraft` is the LAST delivery to this session that landed in somebody's
 * draft, or null. It deliberately outlives the queue struct — that is reaped the
 * moment the queue empties, which is the same moment the notice becomes worth
 * reading — so the caller passes it in separately.
 */
function typingSnapshot(q, nowMs = Date.now(), intoDraft = null) {
  const head = q && Array.isArray(q.entries) && q.entries.length ? q.entries[0] : null;
  return {
    queued: q && Array.isArray(q.entries) ? q.entries.length : 0,
    delivering: !!(q && q.delivering),
    lastError: (q && q.lastError) || null,
    blockedBy: q && q.entries && q.entries.length ? (q.blockedBy || null) : null,
    waitedMs: head && head.at ? Math.max(0, nowMs - head.at) : 0,
    intoDraft: intoDraftView(intoDraft),
    serverTime: Math.floor(nowMs / 1000),
    // 3.6.0: the same instant in SECONDS under a name that says so. `serverTime`
    // above is already seconds HERE and milliseconds on /v1/headroom, which is
    // the trap M3 is about; this field means the same thing on every route.
    serverTimeSec: Math.floor(nowMs / 1000),
  };
}

module.exports = {
  SESSION_TEXT_MAX, SENDKEYS_BUDGET, CHUNK_SIZE, TYPING_POLL_MS,
  QUEUE_MAX_WAIT_MS, STARTUP_GRACE_MS,
  PASTE_SETTLE_MS, PASTE_SETTLE_POLL_MS, SUBMIT_CONFIRM_MS, SUBMIT_CONFIRM_POLL_MS,
  composerText, pasteProbe, pasteProbes, pasteLanded, pasteIndistinguishable, composerCleared,
  composerEmpty, recoveryDecision, stripGhost, pasteSafe, sendable, boxHoldsExactly, boxIsOurs,
  enterRefusal, enterHeldLogLine,
  composerHoldsDraft, draftHold, jobHold, DRAFT_HOLD_MAX_MS, LIVE_KEYS_WINDOW_MS,
  duplicatePending, DUPLICATE_WINDOW_MS, DUPLICATE_MIN_CHARS, LIVE_KEYS_QUIET_MS,
  paneTail, pasteLostLogLine, submitStalledLogLine, pasteResentLogLine, pasteLeftAloneLogLine,
  draftHeldLogLine, draftClearedLogLine, draftOverdueLogLine,
  sendKeysFits, chunks,
  isBoundaryRecord, isConversationalRecord, boundaryFromTail, stateVerdict,
  humanAttentionHold, ATTENTION_HOLD_MAX_MS, submitRefusal, stateStampMs,
  hasHumanUserRecord, kindOf, scanForHuman, scanForTurnStart, DELIVERY_SHOW_MAX_MS, promptSpelling,
  HUMAN_SCAN_MAX_BYTES,
  paneReadyForInput, paneBlocks, composerDrawn, composerShellMode, shellPrompt, shellPromptRow, startsClaude,
  startingUp, startingUnmarked, bufferName,
  releaseDecision, dropReason, dropMessage, dropLogLine, typingSnapshot, intoDraftView,
};
