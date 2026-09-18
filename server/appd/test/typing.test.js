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

// ------------------------------------------- the bookkeeping tail (3.0.2)

// The record types Claude Code writes AFTER a turn has ended, as counted over
// the last 40 real transcripts in ~/.claude/projects/-root-netplan: bookkeeping,
// every one of them, and every one of them arrived after the turn marker.
const BOOKKEEPING = [
  'last-prompt', 'ai-title', 'mode', 'permission-mode', 'atis-latch', 'cost-state',
  'file-history-snapshot', 'file-history-delta', 'queue-operation', 'bridge-session',
  'frame-link', 'attachment', 'summary', 'custom-title', 'agent-name', 'pr-link',
].map((type) => JSON.stringify({ type }));

const TURN_REC = JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 900 });

test('boundaryFromTail reads the last CONVERSATIONAL record, not the last LINE', () => {
  // ⚠ THE 3.0.x DATA LOSS. The gate opened only when the transcript's very last
  // record was the turn marker — but Claude Code appends its bookkeeping after
  // it, so on a session that kept working the tail was
  // [turn_duration, last-prompt, ai-title, mode, permission-mode, atis-latch]
  // and the gate never opened: 43 messages on one session waited ten minutes
  // and were dropped with no word to anyone. Measured live, 2026-09-15.
  const live = [TURN_REC, ...['last-prompt', 'ai-title', 'mode', 'permission-mode', 'atis-latch']
    .map((type) => JSON.stringify({ type }))].join('\n') + '\n';
  assert.deepEqual(t.boundaryFromTail(live), { idle: true, lastKind: 'system/turn_duration' });
});

test('every bookkeeping record type is invisible to the gate, in any order', () => {
  for (const rec of BOOKKEEPING) {
    assert.equal(t.boundaryFromTail(`${TURN_REC}\n${rec}\n`).idle, true, `${rec} re-closed the gate`);
    assert.equal(t.isConversationalRecord(JSON.parse(rec)), false, rec);
  }
  // All of them at once, which is what a real tail looks like.
  assert.equal(t.boundaryFromTail([TURN_REC, ...BOOKKEEPING].join('\n')).idle, true);
});

test('a session mid-turn is still NOT idle, whatever bookkeeping lands after it', () => {
  // The other half of the same rule: ignoring bookkeeping must not turn a
  // running turn into an open gate. A mid-turn assistant record is a tool call
  // (`stop_reason: "tool_use"`), and that is what the gate must hold on.
  const working = JSON.stringify({ type: 'assistant', message: { stop_reason: 'tool_use', content: [] } });
  assert.deepEqual(t.boundaryFromTail(`${TURN_REC}\n${working}\n`), { idle: false, lastKind: 'assistant' });
  const withTail = [TURN_REC, working, ...BOOKKEEPING].join('\n');
  assert.equal(t.boundaryFromTail(withTail).idle, false, 'bookkeeping cannot open a gate by itself');
  assert.equal(t.boundaryFromTail(withTail).lastKind, 'assistant');
});

test('a system record that is not the turn marker is skipped, not read as busy', () => {
  // stop_hook_summary lands immediately BEFORE every turn_duration (30/30 in the
  // largest real transcript), and compact_boundary / local_command / informational
  // can land after one. None of them is a turn.
  for (const subtype of ['stop_hook_summary', 'compact_boundary', 'local_command', 'informational']) {
    const rec = JSON.stringify({ type: 'system', subtype });
    assert.equal(t.boundaryFromTail(`${TURN_REC}\n${rec}\n`).idle, true, subtype);
    assert.equal(t.isConversationalRecord(JSON.parse(rec)), false, subtype);
  }
});

test('a turn that ended with no turn_duration at all is still a boundary', () => {
  // ⚠ MEASURED, NOT ASSUMED. turn_duration is written 1:1 with
  // system/stop_hook_summary — i.e. only when a Stop hook ran. Census of the 25
  // most recent real transcripts: 14 contain ZERO turn_duration records, and 11
  // of them END on an assistant record carrying `stop_reason: "end_turn"`. On
  // those sessions the queue was waiting for a marker that would never be
  // written, so every send into one was dropped at the ten-minute mark.
  const ended = JSON.stringify({ type: 'assistant', message: { stop_reason: 'end_turn', content: [] } });
  assert.equal(t.isBoundaryRecord(JSON.parse(ended)), true);
  assert.equal(t.boundaryFromTail(`${ended}\n`).idle, true);
  assert.equal(t.boundaryFromTail([ended, ...BOOKKEEPING].join('\n')).idle, true);
  // But a LATER user record re-closes it: that is a turn starting, not ending.
  const user = JSON.stringify({ type: 'user', message: { content: 'and now this' } });
  assert.equal(t.boundaryFromTail(`${ended}\n${user}\n`).idle, false);
});

test('isConversationalRecord is the whole filter, stated once', () => {
  assert.equal(t.isConversationalRecord({ type: 'user', message: { content: 'hi' } }), true);
  assert.equal(t.isConversationalRecord({ type: 'assistant', message: {} }), true);
  assert.equal(t.isConversationalRecord({ type: 'system', subtype: 'turn_duration' }), true);
  assert.equal(t.isConversationalRecord({ type: 'system', subtype: 'stop_hook_summary' }), false);
  assert.equal(t.isConversationalRecord({ type: 'last-prompt' }), false);
  assert.equal(t.isConversationalRecord(null), false);
  assert.equal(t.isConversationalRecord({}), false);
});

// ------------------------------------------ the hook's state file as a gate

test('stateVerdict releases on an idle state stamped AFTER the send was queued', () => {
  // The Stop hook writes {state:"idle", ts} the moment a turn ends. It is the
  // SECOND boundary source, and the only one on a session whose transcript
  // never gets a turn marker.
  const at = 1_757_900_000_000;                       // queued at this ms
  const sec = Math.floor(at / 1000);
  assert.equal(t.stateVerdict({ state: 'idle', stateSince: sec + 2 }, at), 'release');
  assert.equal(t.stateVerdict({ state: 'idle', stateSince: sec - 2 }, at), null,
    'an idle stamped BEFORE the send is the turn this send is waiting out');
  assert.equal(t.stateVerdict({ state: 'running', stateSince: sec + 2 }, at), null);
  assert.equal(t.stateVerdict(null, at), null);
  assert.equal(t.stateVerdict({ state: 'idle' }, at), null, 'no timestamp is no evidence');
});

test('stateVerdict HOLDS while a question is waiting, however long it takes', () => {
  // `attention` means a numbered prompt is on screen. Prose typed into one is
  // lost or misread — the same reason /soft-end refuses — so this outranks the
  // human deadline rather than racing it.
  const at = 1_757_900_000_000;
  assert.equal(t.stateVerdict({ state: 'attention', stateSince: Math.floor(at / 1000) + 5 }, at), 'hold');
  assert.equal(t.stateVerdict({ state: 'attention', stateSince: Math.floor(at / 1000) - 5 }, at), 'hold');
});

test('releaseDecision takes the state file as a boundary of its own', () => {
  assert.deepEqual(t.releaseDecision({ idle: false, paneWhy: null, state: 'release' }),
    { release: true, blockedBy: null }, 'the hook saw the turn end even though the transcript did not');
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: null, state: 'hold' }),
    { release: false, blockedBy: 'attention' }, 'a waiting question beats an open turn gate');
  assert.deepEqual(t.releaseDecision({ idle: false, paneWhy: 'modal', state: 'release' }),
    { release: false, blockedBy: 'modal' },
    'and a dialog beats everything: text typed into one is swallowed with no trace');
  // The shape a human text send arrives in since 3.0.3 — only the dialog holds it.
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'busy', state: null }),
    { release: true, blockedBy: null });
});

// --------------------------------------------------- the drop journal line

test('every drop writes ONE journal line naming the origin, kind, session and reason', () => {
  // ⚠ WHY 43 MESSAGES VANISHED WITHOUT A TRACE. The drop wrote `lastError` into
  // an in-memory struct nobody was polling; journalctl had nothing at all.
  assert.equal(
    t.dropLogLine('pctrooubleshoot', { origin: 'headroom', kind: 'resume' }, 'timeout'),
    'typing: dropped headroom/resume for pctrooubleshoot: timeout',
  );
  assert.equal(
    t.dropLogLine('jtyper', {}, 'human'),
    'typing: dropped unknown/text for jtyper: human',
  );
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

// -------------------------------------------------------- is claude up yet

// A real 2.1.258 pane, bottom five lines, captured off this host. The point is
// where the caret ISN'T: the box has status lines under it, so the composer is
// three lines from the bottom and never the last one.
const LIVE_COMPOSER = [
  '                                    tmux detected · scroll with PgUp/PgDn',
  '──────────────────────────────────────────────────────────────────────────',
  '❯ ',
  '──────────────────────────────────────────────────────────────────────────',
  '  [jtyper] Fable 5.1 · ctx 43% · main ~5',
  '  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents',
];

test('composerDrawn finds the caret under the status lines, where ready cannot', () => {
  // ⚠ THE MEASUREMENT THIS RULE EXISTS FOR. `paneReadyForInput` reads the LAST
  // non-blank line, and on this build that is the auto-mode hint — so `ready` is
  // false for every live Claude pane on the host. It costs nothing where it is
  // used (the daemon consumes only `.why`, and 'busy' blocks nothing), but it
  // means the old verdict cannot answer "has Claude come up", which is the whole
  // startup question. Asserted together so the two stay honest about each other.
  assert.equal(t.composerDrawn(LIVE_COMPOSER), true);
  assert.equal(t.paneReadyForInput(LIVE_COMPOSER).ready, false,
    'not a regression: this is why composerDrawn is a separate rule');
});

test('composerDrawn is false for every pane a booting claude draws', () => {
  // The three real frames, in order, sampled every 30 ms off a live startup.
  assert.equal(t.composerDrawn([]), false, 't+0.03s: new-session returns, pane EMPTY');
  assert.equal(t.composerDrawn(['', '   ', '']), false, 'still nothing but whitespace');
  assert.equal(t.composerDrawn([
    'Claude Code v2.1.258',
    'Permission allow rule (settings): a wildcard before the rest of the command',
    'matches more than it looks like it does.',
  ]), false, 't+0.8s: console text, no box — the frame that looks up and is not');
});

test('composerDrawn counts a DIALOG as claude being up — it is a caret too', () => {
  // Being sure the app is THERE is this rule's only job. Whether it may be typed
  // into is paneBlocks', and releaseDecision asks that one first.
  assert.equal(t.composerDrawn(TRUST_DIALOG), true);
  assert.equal(t.composerDrawn(MODEL_MODAL), true);
});

test('startingUp holds only a session appd itself launched claude in', () => {
  // A plain shell has no composer and never will. Holding those would break
  // every non-Claude pane the app can open, so the mark is the discriminator.
  assert.equal(t.startingUp({ launching: false, composer: false, ageMs: 10 }), false);
  assert.equal(t.startingUp({ launching: true, composer: false, ageMs: 10 }), true);
  assert.equal(t.startingUp({}), false, 'no mark, no age: nothing to hold');
});

test('startingUp ends the moment the composer appears, and at the grace either way', () => {
  assert.equal(t.startingUp({ launching: true, composer: true, ageMs: 10 }), false,
    'claude is up: this rule must never speak about the pane again');
  assert.equal(t.startingUp({ launching: true, composer: false, ageMs: t.STARTUP_GRACE_MS - 1 }), true);
  assert.equal(t.startingUp({ launching: true, composer: false, ageMs: t.STARTUP_GRACE_MS }), false,
    'a wait with no end is the same bug wearing a different hat');
  assert.equal(t.startingUp({ launching: true, composer: false, ageMs: null }), false);
});

test('releaseDecision holds a send while claude is still coming up', () => {
  // ⚠ AND IT HOLDS A PERSON'S TOO. 3.0.3's rule is that a human send never waits
  // for Claude to finish a TURN; it was never that it may be thrown at a pane
  // where Claude has not started. The pump passes `idle: true` for human text
  // and `starting` rides alongside it.
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'busy', starting: true }),
    { release: false, blockedBy: 'starting' });
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'busy', starting: false }),
    { release: true, blockedBy: null });
});

test('a dialog OUTRANKS startup: the trust pane is never released by the new gate', () => {
  // The trust dialog pre-selects "No, exit" and has a caret of its own, so the
  // one ordering that must not slip is this one: a pane that is both starting
  // and showing a dialog reports the dialog and stays shut.
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'trust', starting: true }),
    { release: false, blockedBy: 'modal' });
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'modal', starting: true }),
    { release: false, blockedBy: 'modal' });
});

test('startup outranks the attention hold and the turn gate below it', () => {
  // Order of harm: a message at a dialog is swallowed, a message at a pane with
  // nothing in it is lost outright, and everything below those is merely early.
  assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: 'busy', starting: true, state: 'hold' }),
    { release: false, blockedBy: 'starting' });
  assert.deepEqual(t.releaseDecision({ idle: false, paneWhy: 'busy', starting: true }),
    { release: false, blockedBy: 'starting' });
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
  const q = {
    entries: [{ id: 'a', at: 1_757_900_000_123 - 4_000 }, { id: 'b' }],
    delivering: true, lastError: null, blockedBy: 'turn',
  };
  const s = t.typingSnapshot(q, 1_757_900_000_123);
  assert.deepEqual(s, {
    queued: 2, delivering: true, lastError: null, blockedBy: 'turn',
    waitedMs: 4_000, serverTime: 1_757_900_000,
  });
  const idle = t.typingSnapshot({ entries: [], delivering: false, lastError: 'x', blockedBy: 'turn' }, 0);
  assert.equal(idle.blockedBy, null, 'nothing queued cannot be blocked by anything');
  assert.equal(idle.lastError, 'x', 'but the last failure is still readable');
});

test('typingSnapshot of a session that has never sent is all zeroes, not a crash', () => {
  const s = t.typingSnapshot(undefined, 1_757_900_000_000);
  assert.deepEqual(s, {
    queued: 0, delivering: false, lastError: null, blockedBy: null, waitedMs: 0, serverTime: 1_757_900_000,
  });
});

test('typingSnapshot says how long the head of the queue has waited', () => {
  // The client's half of "late beats lost": a message held for 90 seconds can
  // SAY so instead of looking like nothing happened.
  const now = 1_757_900_000_000;
  const q = { entries: [{ id: 'a', at: now - 90_000 }], delivering: false, lastError: null, blockedBy: 'turn' };
  assert.equal(t.typingSnapshot(q, now).waitedMs, 90_000);
  assert.equal(t.typingSnapshot({ entries: [{ id: 'a', at: now + 5_000 }] }, now).waitedMs, 0,
    'a clock that went backwards reads as zero, never as a negative wait');
});

test('the poll interval and the give-up window are the contract\'s', () => {
  assert.equal(t.TYPING_POLL_MS, 400);
  assert.equal(t.QUEUE_MAX_WAIT_MS, 10 * 60 * 1000);
});

// ------------------------------------------------ paste settled (3.1.x P1)
//
// Fixtures are REAL pane captures taken from the sweep that found the bug
// (2026-09-17, Claude Code 2.1.258, /root/netplan): the same 5 rows the daemon
// reads, one from a send that is sitting unsent in the composer and one from a
// send that went. A hand-written pane would have agreed with whatever the rules
// happened to say.

/** A pane whose composer is HOLDING the message: the owner's screenshot. */
const STUCK_PANE = [
  '',
  '                                                             ◉ xhigh · /effort',
  '────────────────────────────────────────────────────────────────────────────────',
  '❯ RACEPROBE-abs-0 reply with only the word RACEOK',
  '────────────────────────────────────────────────────────────────────────────────',
  '  [r2-abs-0] Fable 5.1 · main ~5 · ⚠ 3 sessions in this tree',
  '  ⏵⏵ auto mode on (shift+tab to cycle)',
];

/** The SAME message, delivered: echoed above the box, composer empty. */
const SENT_PANE = [
  '❯ RACEPROBE-settle-0 reply with only the word RACEOK',
  '',
  '✢ Actioning…',
  '                                                             ◉ xhigh · /effort',
  '────────────────────────────────────────────────────────────────────────────────',
  '❯ ',
  '────────────────────────────────────────────────────────────────────────────────',
  '  [r2-settle-0] Fable 5.1 · main ~5 · ⚠ 3 sessions in this tree',
  '  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents',
];

const STUCK_TEXT = 'RACEPROBE-abs-0 reply with only the word RACEOK';
const SENT_TEXT = 'RACEPROBE-settle-0 reply with only the word RACEOK';

test('the composer is the LAST caret, not the first — an echoed message is not a draft', () => {
  // ⚠ THE TRAP THIS RULE EXISTS FOR. Claude Code echoes a SUBMITTED message
  // above the box with the same ❯ glyph, so reading the bottom REGION (which is
  // right for `composerDrawn` and for the dialog rules) calls every successful
  // send "still sitting in the box". It did: nine runs of the verification sweep
  // came back "submitted AND reappeared" with an empty composer in every one.
  assert.equal(t.composerText(SENT_PANE), '');
  assert.equal(t.composerText(STUCK_PANE), STUCK_TEXT);
});

test('a pane with no caret at all has no composer — which is not an empty one', () => {
  // null, not ''. A shell and a booting claude both have nothing to say about a
  // composer, and `composerCleared` must not read that as "the message went".
  assert.equal(t.composerText(['root@huginn:~/netplan# ']), null);
  assert.equal(t.composerText([]), null);
});

test('the settle check sees the message land, and the confirm sees it leave', () => {
  assert.equal(t.pasteLanded(STUCK_PANE, STUCK_TEXT), true);
  assert.equal(t.composerCleared(STUCK_PANE, STUCK_TEXT), false, 'this is the bug: still in the box');
  assert.equal(t.composerCleared(SENT_PANE, SENT_TEXT), true, 'and this one went');
});

test('a COLLAPSED paste counts as landed — the text itself is never on screen', () => {
  // Measured: a 40-line paste renders as `[Pasted text #1 +40 lines]` 60 ms
  // after paste-buffer and the text never appears at all. A settle rule that
  // only looked for the text would time out on every multi-line message there
  // is, which is most of what a person sends from a phone.
  const collapsed = ['──────────', '❯ [Pasted text #1 +40 lines]', '──────────', '  paste again to expand'];
  const long = `first line of the block\n${'x'.repeat(2000)}`;
  assert.equal(t.pasteLanded(collapsed, long), true);
  assert.equal(t.composerCleared(collapsed, long), false);
});

test('tmux WRAPPING a long message does not hide it from the settle check', () => {
  // capture-pane breaks a long line at the pane width with no separator, so the
  // probe is compared against a whitespace-free spelling of both sides.
  const text = 'please summarise the last three commits and say which one touched the daemon';
  const wrapped = ['──────────', '❯ please summarise the last three commits and say which', 'one touched the daemon', '──────────'];
  assert.equal(t.pasteLanded(wrapped, text), true);
});

test('a pane that ALREADY showed the text cannot be waited on, and says so', () => {
  // A resend of the same message is indistinguishable from one that has just
  // landed. The caller treats that as landed immediately — exactly what the
  // daemon did before this check existed, and never a wait that cannot end.
  assert.equal(t.pasteIndistinguishable(STUCK_PANE, STUCK_TEXT), true);
  assert.equal(t.pasteIndistinguishable(SENT_PANE, SENT_TEXT), false);
  assert.equal(t.pasteIndistinguishable(null, STUCK_TEXT), false, 'no capture is not a match');
});

test('a booting pane has not landed anything, however much it has drawn', () => {
  const booting = ['Claude Code v2.1.258', 'Permission allow rule (settings): a wildcard', 'matches more than it looks like it does.'];
  assert.equal(t.pasteLanded(booting, STUCK_TEXT), false);
  assert.equal(t.composerCleared(booting, STUCK_TEXT), null, 'nothing here can answer');
});

test('both quiet failures write a journal line carrying the pane itself', () => {
  // The rule since the 43 messages that vanished: anything short of a delivered
  // message says so on disk, in one grep-able shape, with what was there instead.
  const lost = t.pasteLostLogLine('mcserver', 3000, STUCK_PANE);
  assert.match(lost, /mcserver/);
  assert.match(lost, /never appeared/);
  assert.match(lost, /pane: /);
  const stalled = t.submitStalledLogLine('mcserver', 1000, STUCK_PANE);
  assert.match(stalled, /composer still holds the message/);
  assert.match(stalled, /RACEPROBE-abs-0/, 'the tail is the evidence; it must be IN the line');
});

test('the settle bounds are the measured ones, not a round number someone liked', () => {
  // 3 s covers the whole stuck band with margin: the widest gap measured between
  // a paste and the paint that rendered it was 1,987 ms.
  assert.equal(t.PASTE_SETTLE_MS, 3_000);
  assert.equal(t.PASTE_SETTLE_POLL_MS, 50);
  assert.equal(t.SUBMIT_CONFIRM_MS, 1_000);
});
