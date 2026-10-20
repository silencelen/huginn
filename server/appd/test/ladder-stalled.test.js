'use strict';
// A session stalled on a usage limit is not a candidate for a ladder move.
//
// Auto-resume waits for the 429 to still be the session's last record at the
// reset. A move typed into a stalled session (the 429 is a turn boundary, so the
// job runs at once) leaves Claude Code's `/model` command record and its
// `Set model to … for this session only` output after the 429 — and the resume
// pass cannot tell those from a person answering: it filed the stall as
// "a person answered it", the next pass cleared it, the desktop announced the
// session as resumed, and nothing ever continued it. The arbiter was never told
// about stalls at all, although the tick's own comment says a stalled session
// is not a ladder candidate.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const h = require('../lib/headroom');
const resume = require('../lib/resume');
const { lift } = require('./ladder-lift');

const NOW = 1_789_500_000_000;
const MIN = 60_000;
const settings = h.defaults();
const fableAt = (percent, severity = 'normal') => ({
  slug: 'a',
  windows: { session: { percent: 5 }, weekly_all: { percent: 40 }, weekly_fable: { percent, severity, label: 'Current week (Fable)' } },
});
const sess = (name, over = {}) => ({
  claudeSessionId: `sid-${name}`, name, state: 'idle', family: 'fable', ladder: null,
  headsUpAt: NOW - 60 * MIN, nativeSwitch: { seenAt: null, to: null }, humanSetModelAt: null, ...over,
});
const stall = (window, over = {}) => ({
  at: NOW - 2 * MIN, window, resetsAt: NOW + 4 * 24 * 60 * MIN, resetsAtSource: 'endpoint',
  resumedAt: null, queuedAt: null, gaveUpAt: null, how: null, attempts: 0, nativeArmed: false, ...over,
});
const moves = (v) => v.actions.filter((a) => /^ladder_/.test(a.type)).map((a) => `${a.type}:${a.name}`);

test('a session stalled on the Fable week is not moved down; it is resumed after the reset', () => {
  const v = h.decide({
    active: fableAt(100, 'exceeded'), settings, now: NOW, state: { lastLadderAt: NOW - 60 * MIN },
    sessions: [sess('b', { stall: stall('weekly_fable') })],
  });
  assert.deepEqual(moves(v), [], v.why);
  assert.match(v.why, /b is stalled on a usage limit/);
});

test('nor is one stalled on the 5-hour window while the Fable week is red', () => {
  const v = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall('session') }), sess('c')],
  });
  assert.deepEqual(moves(v), ['ladder_down:c'], 'the next session gets the move instead');
});

test('once its stall has been resumed, the session is a candidate again', () => {
  const v = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall('session', { resumedAt: NOW - MIN, how: 'appd' }) })],
  });
  assert.deepEqual(moves(v), ['ladder_down:b']);
  // A blank stall (every session record carries one) is no stall.
  const blank = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall(null, { at: null }) })],
  });
  assert.deepEqual(moves(blank), ['ladder_down:b']);
});

test('a laddered session stalled on another limit is not moved back up under its 429 either', () => {
  const ladder = { from: 'fable', to: 'opus', at: NOW - 3 * 24 * 60 * MIN, delivery: 'confirmed' };
  const input = (s) => ({
    active: fableAt(10), settings, now: NOW, state: { lastLadderAt: 0 },
    lastFableResetAt: NOW - 30 * MIN, sessions: [s],
  });
  assert.deepEqual(moves(h.decide(input(sess('x', { family: 'opus', ladder, stall: stall('session') })))), []);
  assert.deepEqual(moves(h.decide(input(sess('x', { family: 'opus', ladder })))), ['ladder_up:x'], 'the control');
});

test('a move queued behind a turn that then ends in the 429 types nothing after it', async () => {
  // At 92%+ of the Fable week a move is usually queued behind a running turn —
  // and that turn may end in the limit itself. The 429 is a turn boundary, so
  // the job runs the moment it lands, before any tick has seen the stall.
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), `ladder-stall-${process.pid}-`)), 'b.jsonl');
  const line = (r) => `${JSON.stringify(r)}\n`;
  fs.writeFileSync(file, line({ type: 'user', message: { content: 'refactor the backup job' }, timestamp: new Date().toISOString() })
    + line({ type: 'assistant', message: { model: 'claude-fable-5-1', content: [{ type: 'text', text: 'working on it' }] }, timestamp: new Date().toISOString() }));

  const queue = [];
  const typed = [];
  const L = lift(['applyHeadroomAction', 'resolvePendingLadder', 'tailRecords', 'transcriptTail'], {
    fs, Buffer, Date, JSON, headroomLib: h, resumeLib: resume,
    transcriptPath: () => file,
    applyLadder: async (name, to) => { typed.push(to); return { ok: true, delivery: 'confirmed', to }; },
    enqueueJob: async (name, run, opts) => { queue.push({ run, opts }); return { result: null }; },
    log: () => { }, deliverPush: async () => ({ sent: true }), deliverTelegram: async () => null,
    saveHeadroomState: () => { }, GLYPH: { info: 'i' },
  }, { helpers: ['ladderUnlessStalled'] });
  const rec = { name: 'b', family: 'fable', ladder: null, nativeSwitch: { seenAt: null, to: null } };
  const state = { sessions: { 'sid-b': rec }, arbiter: { lastLadderAt: 0 } };
  await L.applyHeadroomAction(
    { type: 'ladder_down', claudeSessionId: 'sid-b', name: 'b', from: 'fable', to: 'opus', pct: 99 },
    { state, settings, now: NOW });
  assert.equal(rec.ladder.delivery, 'pending', 'queued behind the running turn');

  // The turn ends in the Fable week's 429.
  fs.appendFileSync(file, line({
    type: 'assistant', isApiErrorMessage: true, apiErrorStatus: 429, timestamp: new Date().toISOString(),
    message: { model: '<synthetic>', content: [{ type: 'text', text: "You're out of usage credits. Run /usage-credits to keep using Fable 5.1 or /model to switch models." }] },
  }));
  const job = queue.shift();
  job.opts.onSettle({ result: await job.run() });

  assert.deepEqual(typed, [], 'no /model after the 429');
  assert.equal(rec.ladder.delivery, 'delivery_unconfirmed', 'the move did not happen');
  const records = fs.readFileSync(file, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  assert.deepEqual(resume.recordsAfterStall(records), [], 'the 429 is still the last word, as auto-resume needs');
});

test('why it matters: what a move leaves after the 429 reads as a person to the resume pass', () => {
  // The records Claude Code 2.1.30x writes for the picker's session-only switch.
  const after = [
    { type: 'user', isMeta: true, message: { content: '<local-command-caveat>Caveat: …</local-command-caveat>' } },
    { type: 'user', message: { content: '<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args></command-args>' } },
    { type: 'user', message: { content: '<local-command-stdout>Set model to `Opus 5` for this session only</local-command-stdout>' } },
  ];
  assert.equal(after.some((r) => resume.isHumanRecord(r)), true,
    'so the move must not be made: the resume would be cancelled as "a person answered it"');
});
