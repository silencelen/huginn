'use strict';
// The typing rules, asserted away from tmux: the caps, the command-line budget,
// the turn-boundary reader, the modal reader, and the queue's decisions.
//
// Everything here is pure, so a failure means a RULE is wrong rather than a
// pane being slow. The route-level half — real panes, real bytes, the bracketed
// paste argv — lives in routes-typing.test.js.

const { test } = require('node:test');
const assert = require('node:assert');
const t = require('../lib/typing');

// ------------------------------------------------------------------ the caps

test('the session text cap matches the chat body cap, not the old pane ceiling', () => {
  assert.equal(t.SESSION_TEXT_MAX, 100_000);
});

test('the send-keys budget is the MEASURED tmux command-line ceiling', () => {
  // Bisected against a throwaway pane: 16,339 usable for a 4-char target.
  assert.equal(t.SENDKEYS_BUDGET, 16_340);
});

// ------------------------------------------------------------- sendKeysFits

test('sendKeysFits narrows 1:1 with the target length', () => {
  const short = '=t:';                       // 3 chars
  const long = '=a-much-longer-session-name:';  // 28
  const atShort = 'x'.repeat(t.SENDKEYS_BUDGET - short.length);
  assert.equal(t.sendKeysFits(atShort, short), true, 'exactly the budget fits');
  assert.equal(t.sendKeysFits(atShort + 'x', short), false, 'one over does not');
  assert.equal(t.sendKeysFits(atShort, long), false,
    'the SAME text does not fit a longer target — the name comes out of the same budget');
  assert.equal(t.sendKeysFits('x'.repeat(t.SENDKEYS_BUDGET - long.length), long), true);
});

test('sendKeysFits counts newlines and quotes as one character each, not as escapes', () => {
  // Measured: bodies of x, \n and " all hit the identical limit. If the budget
  // were re-quoted by tmux this would be wrong by a factor of two.
  const target = '=t:';
  const room = t.SENDKEYS_BUDGET - target.length;
  assert.equal(t.sendKeysFits('\n'.repeat(room), target), true);
  assert.equal(t.sendKeysFits('"'.repeat(room), target), true);
  assert.equal(t.sendKeysFits('"'.repeat(room + 1), target), false);
});

// -------------------------------------------------------------------- chunks

test('chunks never exceeds the computed budget for its target', () => {
  const target = '=a-much-longer-session-name:';
  const out = t.chunks('z'.repeat(40_000), target);
  assert.ok(out.length > 1);
  for (const c of out) {
    assert.ok(t.sendKeysFits(c, target), `a chunk of ${c.length} does not fit ${target}`);
    assert.ok(c.length <= t.CHUNK_SIZE);
  }
  assert.equal(out.join(''), 'z'.repeat(40_000), 'and the pieces still spell the message');
});

test('chunks uses the full 8,000 width when the target leaves room', () => {
  const out = t.chunks('q'.repeat(20_100), '=t:');
  assert.deepEqual(out.map((c) => c.length), [8000, 8000, 4100]);
});

test('chunks of nothing is nothing, and an impossible target yields nothing to send', () => {
  assert.deepEqual(t.chunks('', '=t:'), []);
  // A target longer than the whole budget leaves negative room: [] is the
  // signal to 503, never a truncated message delivered as if it were whole.
  assert.deepEqual(t.chunks('hello', '='.repeat(t.SENDKEYS_BUDGET + 10)), []);
});

// ----------------------------------------------------------- turn boundaries

test('isBoundaryRecord accepts system/turn_duration and a turn that died on an api error', () => {
  assert.equal(t.isBoundaryRecord({ type: 'system', subtype: 'turn_duration', durationMs: 5 }), true);
  assert.equal(t.isBoundaryRecord({ type: 'system', subtype: 'stop_hook_summary' }), false);
  assert.equal(t.isBoundaryRecord({ type: 'assistant' }), false);
  // ⚠ A USAGE-LIMIT STALL IS A BOUNDARY. The CLI writes the 429 as an ordinary
  // assistant record and then stops; no turn_duration ever follows, so a queued
  // auto-resume phrase would wait for a boundary that cannot arrive.
  assert.equal(t.isBoundaryRecord({ type: 'assistant', isApiErrorMessage: true, apiErrorStatus: 429 }), true);
  assert.equal(t.isBoundaryRecord(null), false);
});

test('boundaryFromTail is idle only when the LAST record is the boundary', () => {
  const user = JSON.stringify({ type: 'user', message: { content: 'hi' } });
  const turn = JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 900 });
  assert.deepEqual(t.boundaryFromTail(`${user}\n${turn}\n`), { idle: true, lastKind: 'system/turn_duration' });
  assert.deepEqual(t.boundaryFromTail(`${turn}\n${user}\n`), { idle: false, lastKind: 'user' });
});

test('boundaryFromTail ignores attachment records landing after the boundary', () => {
  // Claude Code appends these after the fact; one arriving late must not
  // re-close a gate that legitimately opened.
  const turn = JSON.stringify({ type: 'system', subtype: 'turn_duration' });
  const att = JSON.stringify({ type: 'attachment', content: 'x' });
  assert.equal(t.boundaryFromTail(`${turn}\n${att}\n${att}\n`).idle, true);
});

test('boundaryFromTail tolerates a truncated first line — a tail starts mid-record', () => {
  const turn = JSON.stringify({ type: 'system', subtype: 'turn_duration' });
  const tail = `pe":"assistant","message":{"content":"…"}}\n${turn}\n`;
  assert.equal(t.boundaryFromTail(tail).idle, true);
});

test('boundaryFromTail is NOT idle for an empty or unreadable tail', () => {
  assert.deepEqual(t.boundaryFromTail(''), { idle: false, lastKind: null });
  assert.deepEqual(t.boundaryFromTail('\n\n  \n'), { idle: false, lastKind: null });
  assert.deepEqual(t.boundaryFromTail('not json at all\n{oops'), { idle: false, lastKind: null });
  assert.equal(t.boundaryFromTail(null).idle, false, 'absence is never evidence of idleness');
});

// ------------------------------------------------------- did a human speak?

test('hasHumanUserRecord sees a person typing', () => {
  const rec = JSON.stringify({ type: 'user', message: { content: 'do the other thing instead' } });
  assert.equal(t.hasHumanUserRecord(rec), true);
  const blocks = JSON.stringify({ type: 'user', message: { content: [{ type: 'text', text: 'hi' }] } });
  assert.equal(t.hasHumanUserRecord(blocks), true);
});

test('hasHumanUserRecord does NOT count tool results or meta records as a person', () => {
  const toolResult = JSON.stringify({
    type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 'a', content: 'ok' }] },
  });
  const meta = JSON.stringify({ type: 'user', isMeta: true, message: { content: 'caveat: …' } });
  const assistant = JSON.stringify({ type: 'assistant', message: { content: 'sure' } });
  assert.equal(t.hasHumanUserRecord(`${toolResult}\n${meta}\n${assistant}\n`), false,
    'a tool handing output back is plumbing; dropping appd\'s message for it would be wrong');
});

// --------------------------------------------------------- pane readiness

const MODEL_MODAL = [
  '   Switch model?',
  '   This conversation is cached for the current model. Switching to Opus 5 means',
  '   the full history gets re-read…',
  '',
  ' ❯ 1. Yes, switch to Opus 5',
  '   2. No, go back',
  '',
  '   Enter to confirm · Esc to cancel',
];

const TRUST_DIALOG = [
  ' Accessing workspace:',
  ' /tmp/scratch/fresh-cwd',
  '',
  ' Quick safety check: Is this a project you created or one you trust?',
  '',
  ' ❯ 1. Yes, I trust this folder',
  '   2. No, exit',
  '',
  ' Enter to confirm · Esc to cancel',
];

test('paneReadyForInput calls the /model switch dialog a modal', () => {
  assert.deepEqual(t.paneReadyForInput(MODEL_MODAL), { ready: false, why: 'modal' });
});

test('paneReadyForInput calls the trust dialog by its own name', () => {
  // Its destructive option is PRE-SELECTED ("No, exit"), so a blind Enter here
  // kills the session — worth distinguishing from any other modal in a log.
  assert.deepEqual(t.paneReadyForInput(TRUST_DIALOG), { ready: false, why: 'trust' });
});

test('paneReadyForInput does NOT match the spinner — the busy verb is randomised', () => {
  // Three different verbs, each with the composer caret still drawn under it.
  // Matching these was the brittle design: this build randomises them, so the
  // pane is for MODAL detection and the jsonl is the liveness signal.
  for (const verb of ['✽ Undulating…', '* Orbiting…', '✻ Baked for 1s · done']) {
    const pane = ['', `  ${verb} (esc to interrupt)`, '─────────────', '❯ '];
    assert.deepEqual(t.paneReadyForInput(pane), { ready: true, why: null }, verb);
  }
});

test('paneReadyForInput accepts a bare caret and a caret with typed text', () => {
  assert.deepEqual(t.paneReadyForInput(['────', '❯ ']), { ready: true, why: null });
  assert.deepEqual(t.paneReadyForInput(['────', '❯ half a sentence']), { ready: true, why: null });
});

test('paneReadyForInput strips ANSI before reading the caret', () => {
  assert.deepEqual(t.paneReadyForInput(['[38;5;153m❯[39m ']), { ready: true, why: null });
});

test('paneReadyForInput says busy for a pane with no composer at all', () => {
  assert.deepEqual(t.paneReadyForInput(['$ ls', 'a  b  c']), { ready: false, why: 'busy' });
  assert.deepEqual(t.paneReadyForInput([]), { ready: false, why: 'busy' });
  assert.deepEqual(t.paneReadyForInput(['', '   ', '']), { ready: false, why: 'busy' });
});

test('a numbered LIST in the pane is not a dialog — only a cursored row is', () => {
  // A pane echoing ordinary text must never read as a modal, or every send into
  // it blocks forever on a dialog that is not there.
  const pane = ['Three things to do:', '1. commit', '2. push', '3. sleep', '────', '❯ '];
  assert.deepEqual(t.paneReadyForInput(pane), { ready: true, why: null });
});

test('only a dialog BLOCKS a send: busy is not a liveness verdict', () => {
  assert.equal(t.paneBlocks('modal'), true);
  assert.equal(t.paneBlocks('trust'), true);
  assert.equal(t.paneBlocks('busy'), false, 'the transcript decides liveness, never the pane');
  assert.equal(t.paneBlocks(null), false);
});

// ------------------------------------------------------------- buffer names

test('bufferName is unique per send and shaped hg-<6hex>', () => {
  const seen = new Set();
  for (let i = 0; i < 500; i++) {
    const b = t.bufferName();
    assert.match(b, /^hg-[0-9a-f]{6}$/);
    seen.add(b);
  }
  // Two sessions sharing one buffer name is a message pasted into the wrong
  // pane; 500 draws with no collision is the floor this rule needs.
  assert.equal(seen.size, 500);
});

// -------------------------------------------------------- queue decisions

test('releaseDecision opens only when BOTH gates are open', () => {
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: null }), { release: true, blockedBy: null });
  assert.deepEqual(t.releaseDecision({ idle: false, paneWhy: null }), { release: false, blockedBy: 'turn' });
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'modal' }), { release: false, blockedBy: 'modal' });
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'trust' }), { release: false, blockedBy: 'modal' });
});

test('releaseDecision names the DIALOG when both gates are shut', () => {
  // The more actionable of the two: a turn ends by itself, a dialog does not.
  assert.deepEqual(t.releaseDecision({ idle: false, paneWhy: 'modal' }), { release: false, blockedBy: 'modal' });
});

test('releaseDecision treats a busy pane as open — the transcript is the gate', () => {
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'busy' }), { release: true, blockedBy: null });
});

test('an automated send is dropped when the owner typed after it was queued', () => {
  const entry = { automated: true, origin: 'headroom', kind: 'headsUp', at: Date.now() };
  assert.equal(t.dropReason(entry, { humanSpoke: true, now: Date.now() }), 'human');
  assert.equal(t.dropReason(entry, { humanSpoke: false, now: Date.now() }), null);
});

test('a HUMAN send is never dropped for a human speaking, or for a family change', () => {
  const mine = { automated: false, origin: 'client', at: Date.now() };
  assert.equal(t.dropReason(mine, { humanSpoke: true, family: 'opus', now: Date.now() }), null,
    'a message somebody pressed send on is delivered or it is an error, never binned');
});

test('a queued model job is dropped once the session has changed family', () => {
  const entry = { automated: true, kind: 'model', family: 'fable', origin: 'headroom', at: Date.now() };
  assert.equal(t.dropReason(entry, { humanSpoke: false, family: 'opus', now: Date.now() }), 'family');
  assert.equal(t.dropReason(entry, { humanSpoke: false, family: 'fable', now: Date.now() }), null);
  assert.equal(t.dropReason(entry, { humanSpoke: false, family: null, now: Date.now() }), null,
    'no probe installed means the rule cannot fire, not that it fires blindly');
});

test('an AUTOMATED send that waited ten minutes is dropped, and says so', () => {
  const now = Date.now();
  const entry = { automated: true, origin: 'headroom', at: now - t.QUEUE_MAX_WAIT_MS - 1 };
  assert.equal(t.dropReason(entry, { now }), 'timeout');
  assert.equal(t.dropReason({ automated: true, at: now - 1000 }, { now }), null);
  assert.match(t.dropMessage('timeout'), /waited 10 minutes for a turn boundary and gave up/);
});

// HOTFIX 3.0.2: a person's message is never binned. 3.0.0 dropped it after ten
// minutes, and because the boundary check also misread the CLI's bookkeeping
// records as "still busy", 43 of the owner's messages vanished from one session.
test('a HUMAN send is never dropped on timeout — late beats lost', () => {
  const now = Date.now();
  const stale = { automated: false, at: now - 3 * t.QUEUE_MAX_WAIT_MS };
  assert.equal(t.dropReason(stale, { now }), null);
});

test('bookkeeping records after the turn marker do not hide the boundary', () => {
  const tail = [
    '{"type":"system","subtype":"turn_duration","durationMs":1200}',
    '{"type":"last-prompt"}', '{"type":"ai-title"}', '{"type":"mode"}',
    '{"type":"permission-mode"}', '{"type":"atis-latch"}', '{"type":"cost-state"}',
    '{"type":"file-history-snapshot"}', '{"type":"queue-operation","operation":"enqueue"}',
  ].join('\n');
  assert.equal(t.boundaryFromTail(tail).idle, true, 'the turn ended; the records after it are not a turn');
  const busy = '{"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}\n{"type":"mode"}';
  assert.equal(t.boundaryFromTail(busy).idle, false, 'an assistant record is a turn in progress');
});

test('every drop has words for it — a queue that loses a message silently is the bug', () => {
  const entry = { origin: 'headroom' };
  assert.match(t.dropMessage('human', entry), /you typed first/);
  assert.match(t.dropMessage('family', entry), /already changed model/);
  for (const r of ['human', 'family', 'timeout', 'whatever']) {
    assert.ok(t.dropMessage(r, entry).length > 10, r);
  }
});

test('typingSnapshot reports the queue, and reports no block when nothing waits', () => {
  const q = { entries: [{ id: 'a' }, { id: 'b' }], delivering: true, lastError: null, blockedBy: 'turn' };
  const s = t.typingSnapshot(q, 1_757_900_000_123);
  assert.deepEqual(s, {
    queued: 2, delivering: true, lastError: null, blockedBy: 'turn', serverTime: 1_757_900_000,
  });
  const idle = t.typingSnapshot({ entries: [], delivering: false, lastError: 'x', blockedBy: 'turn' }, 0);
  assert.equal(idle.blockedBy, null, 'nothing queued cannot be blocked by anything');
  assert.equal(idle.lastError, 'x', 'but the last failure is still readable');
});

test('typingSnapshot of a session that has never sent is all zeroes, not a crash', () => {
  const s = t.typingSnapshot(undefined, 1_757_900_000_000);
  assert.deepEqual(s, { queued: 0, delivering: false, lastError: null, blockedBy: null, serverTime: 1_757_900_000 });
});

test('the poll interval and the give-up window are the contract\'s', () => {
  assert.equal(t.TYPING_POLL_MS, 400);
  assert.equal(t.QUEUE_MAX_WAIT_MS, 10 * 60 * 1000);
});
