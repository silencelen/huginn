'use strict';
// lib/statusline.js — the status-line record huginn-statusline keeps under
// STATE_DIR/.status/<session>, read into the wire's context/model/effort/fast/
// limits/cost — and lib/pane.js fastModeUnavailable, the pane's half of `fast`.
//
// The record is Claude Code 2.1.306's own status-line JSON, captured from a
// throwaway session (fixtures/statusline-2.1.306.json). Filesystem-only: a
// scratch state directory, never /run.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const sl = require('../lib/statusline');
const { fastModeUnavailable } = require('../lib/pane');

const FIXTURE = require('./fixtures/statusline-2.1.306.json');
const PANES = path.join(__dirname, 'fixtures', 'prompts');
const pane = (name) => fs.readFileSync(path.join(PANES, name), 'utf8').split('\n');

function scratch() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `sl-${process.pid}-`));
  fs.mkdirSync(path.join(dir, sl.DIRNAME));
  return dir;
}
function write(dir, name, json, { ageMs = 0 } = {}) {
  const file = path.join(dir, sl.DIRNAME, name);
  fs.writeFileSync(file, `${JSON.stringify(json)}\n`);
  if (ageMs) {
    const t = (Date.now() - ageMs) / 1000;
    fs.utimesSync(file, t, t);
  }
  return file;
}

test('the 2.1.306 record parses into every wire object', () => {
  const r = sl.parseStatusRecord(FIXTURE);
  assert.deepEqual(r.context, { used: 35334, window: 1000000, percent: 4, exceeds200k: false });
  assert.equal(r.model, 'Haiku 5.5');
  assert.equal(r.effort, 'medium');
  assert.deepEqual(r.fast, { on: false, available: true, reason: null });
  assert.deepEqual(r.limits, {
    fiveHour: { usedPercent: 5, resetsAt: 1792830600 },
    sevenDay: { usedPercent: 1, resetsAt: 1793001600 },
    spend: null,
  });
  assert.deepEqual(r.cost, {
    usd: FIXTURE.cost.total_cost_usd, durationMs: 37177, apiMs: 10015, linesAdded: 0, linesRemoved: 0,
  });
});

test('`used` is the input plus both cache figures, which is what the percentage is of', () => {
  const cu = FIXTURE.context_window.current_usage;
  const r = sl.parseStatusRecord(FIXTURE);
  assert.equal(r.context.used, cu.input_tokens + cu.cache_creation_input_tokens + cu.cache_read_input_tokens);
  assert.equal(Math.round(r.context.used / r.context.window * 100), r.context.percent);
});

test('before the first response: no context, everything else still reported', () => {
  const j = JSON.parse(JSON.stringify(FIXTURE));
  j.context_window.current_usage = null;
  j.context_window.used_percentage = null;
  j.context_window.remaining_percentage = null;
  j.rate_limits = undefined;
  const r = sl.parseStatusRecord(j);
  assert.equal(r.context, null);
  assert.equal(r.model, 'Haiku 5.5');
  assert.equal(r.effort, 'medium');
  assert.deepEqual(r.fast, { on: false, available: true, reason: null });
  assert.equal(r.limits, null);
  assert.ok(r.cost);
});

test('a record with nothing recognisable in it is every field null, never a throw', () => {
  assert.deepEqual(sl.parseStatusRecord({}), sl.empty());
  assert.deepEqual(sl.parseStatusRecord(null), sl.empty());
  assert.deepEqual(sl.parseStatusRecord([1, 2]), sl.empty());
  assert.deepEqual(sl.parseStatusRecord({ model: 'not an object', effort: 'max', fast_mode: 'yes' }), sl.empty());
});

test('fast mode: on from the JSON, available/reason from the pane', () => {
  const on = { ...FIXTURE, fast_mode: true };
  assert.deepEqual(sl.parseStatusRecord(on).fast, { on: true, available: true, reason: null });
  const r = sl.parseStatusRecord(on, { paneLines: pane('fast-mode-unavailable-80.txt') });
  assert.equal(r.fast.on, true);
  assert.equal(r.fast.available, false);
  assert.match(r.fast.reason, /^Fast mode requires usage credits/);
  // A pane with nothing of the kind on it leaves fast mode available.
  assert.deepEqual(sl.parseStatusRecord(on, { paneLines: pane('statusline-manual-80.txt') }).fast,
    { on: true, available: true, reason: null });
});

test('fastModeUnavailable: each of Claude Code\'s four shapes, nothing else', () => {
  for (const line of [
    'Fast mode disabled · the context window is too large for fast mode',
    'Fast mode unavailable for this model',
    'Fast mode requires usage credits · run /usage-credits',
    'auto mode unavailable while fast mode is on',
    ' ⎿  Fast mode unavailable right now',
    '\u001b[33m⚠ Fast mode disabled · reason\u001b[39m',
  ]) {
    const got = fastModeUnavailable(['older rows', line, '❯ ']);
    assert.ok(got, `should match: ${JSON.stringify(line)}`);
    assert.match(got, /^(Fast mode|auto mode unavailable)/);
  }
  for (const line of [
    'fast mode is on',
    'Fast mode on · responses stream faster',
    'I set fast mode unavailable in the test',
    'the auto mode unavailable while fast mode is on message',
  ]) {
    assert.equal(fastModeUnavailable(['x', line, '❯ ']), null, `should not match: ${JSON.stringify(line)}`);
  }
  // Only the bottom of the pane: a sentence that scrolled up is no longer the state.
  const far = [...Array(20).fill('prose'), 'Fast mode unavailable here', ...Array(13).fill('more prose')];
  assert.equal(fastModeUnavailable(far), null);
  assert.equal(fastModeUnavailable([]), null);
});

test('effort is one of the five levels or null', () => {
  assert.equal(sl.parseStatusRecord({ effort: { level: 'XHIGH' } }).effort, 'xhigh');
  assert.equal(sl.parseStatusRecord({ effort: { level: 'turbo' } }).effort, null);
  assert.equal(sl.parseStatusRecord({ effort: {} }).effort, null);
});

test('statusFor reads the record for a session, and only a trustworthy one', () => {
  const dir = scratch();
  write(dir, 'alpha', FIXTURE);
  const r = sl.statusFor(dir, 'alpha', { sessionId: FIXTURE.session_id });
  assert.equal(r.model, 'Haiku 5.5');
  assert.equal(r.context.percent, 4);
  // No state-file session id to compare against: taken as this session's.
  assert.equal(sl.statusFor(dir, 'alpha').model, 'Haiku 5.5');
  // Another Claude session under the same tmux name: not this one's.
  assert.deepEqual(sl.statusFor(dir, 'alpha', { sessionId: 'some-other-session' }), sl.empty());
  // No record at all.
  assert.deepEqual(sl.statusFor(dir, 'beta'), sl.empty());
  // Not JSON.
  fs.writeFileSync(path.join(dir, sl.DIRNAME, 'gamma'), '{"model": {');
  assert.deepEqual(sl.statusFor(dir, 'gamma'), sl.empty());
  fs.rmSync(dir, { recursive: true, force: true });
});

test('a record older than six hours is a corpse', () => {
  const dir = scratch();
  write(dir, 'old', FIXTURE, { ageMs: sl.MAX_AGE_MS + 60_000 });
  assert.deepEqual(sl.statusFor(dir, 'old'), sl.empty());
  write(dir, 'fresh', FIXTURE, { ageMs: sl.MAX_AGE_MS - 60_000 });
  assert.equal(sl.statusFor(dir, 'fresh').model, 'Haiku 5.5');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('clearStatus removes the record and tolerates its absence', () => {
  const dir = scratch();
  write(dir, 'gone', FIXTURE);
  sl.clearStatus(dir, 'gone');
  assert.equal(fs.existsSync(path.join(dir, sl.DIRNAME, 'gone')), false);
  sl.clearStatus(dir, 'gone');
  fs.rmSync(dir, { recursive: true, force: true });
});
