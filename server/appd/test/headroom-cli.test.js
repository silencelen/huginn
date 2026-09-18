'use strict';
// `huginn-headroom` — the host-side renderer both CLI clients ssh in to run.
//
// It is Python, so it is driven here the way the shell wrappers drive it: as a
// module, with a fixture body, through `python3 -c`. Two things are worth pinning
// down and neither is visible from the daemon side:
//
//   * `ago()` reads MILLISECONDS. Every epoch on /v1/headroom is ms; this file
//     documented and treated them as seconds, so `arbiter` printed "just now"
//     for ever — a time that never ages reads as a daemon that never acts.
//   * `arbiter.lastAction` is `{type, at, name, to}`. The renderer read
//     `kind`/`action` and `session`, which the daemon has never emitted, so every
//     run printed a bare "Arbiter: acted" with no target. The CLI and the
//     client-side fixture shared the same wrong names: both were written from the
//     design, neither re-derived from the daemon.
//
// PORT ALLOCATION — nothing here binds a socket (the renderer is handed a body,
// never asked to fetch one), so this file takes no block from the table in
// routes-lifecycle.test.js.

const { test } = require('node:test');
const assert = require('node:assert');
const { execFileSync, spawnSync } = require('node:child_process');
const path = require('node:path');

const CLI = path.join(__dirname, '..', '..', 'bin', 'huginn-headroom');
const HAVE_PY = spawnSync('python3', ['-c', 'pass']).status === 0;

/** Import the renderer as a module and run `render(body)` over a fixture. */
function render(body) {
  const prog = [
    'import importlib.util, importlib.machinery, json, sys',
    `spec = importlib.util.spec_from_loader("hh", importlib.machinery.SourceFileLoader("hh", ${JSON.stringify(CLI)}))`,
    'mod = importlib.util.module_from_spec(spec)',
    'spec.loader.exec_module(mod)',
    'sys.stdout.write(mod.render(json.loads(sys.stdin.read())))',
  ].join('\n');
  return execFileSync('python3', ['-c', prog], { input: JSON.stringify(body), encoding: 'utf8' });
}

function ago(v) {
  const prog = [
    'import importlib.util, importlib.machinery, json, sys',
    `spec = importlib.util.spec_from_loader("hh", importlib.machinery.SourceFileLoader("hh", ${JSON.stringify(CLI)}))`,
    'mod = importlib.util.module_from_spec(spec)',
    'spec.loader.exec_module(mod)',
    'sys.stdout.write(mod.ago(json.loads(sys.stdin.read())))',
  ].join('\n');
  return execFileSync('python3', ['-c', prog], { input: JSON.stringify(v), encoding: 'utf8' });
}

/**
 * `local_at(iso)` with the wall clock and the zone both pinned.
 *
 * The renderer reads `now` inside the function, so the only way to test a DST
 * boundary is to hand it one: a stand-in `datetime` module whose `now()` answers
 * a fixed instant, installed in sys.modules AFTER the file is loaded (it does
 * `import datetime` per call) so not a line of the real source is altered. TZ
 * pins the host zone the same way a terminal session would.
 */
function localAt(iso, { now, tz }) {
  const prog = [
    'import importlib.util, importlib.machinery, sys, types, os, datetime as _dt',
    `spec = importlib.util.spec_from_loader("hh", importlib.machinery.SourceFileLoader("hh", ${JSON.stringify(CLI)}))`,
    'mod = importlib.util.module_from_spec(spec)',
    'spec.loader.exec_module(mod)',
    'FAKE = os.environ["FAKE_NOW"].replace("Z", "+00:00")',
    'class FakeDT(_dt.datetime):',
    '    @classmethod',
    '    def now(cls, tz=None):',
    '        base = _dt.datetime.fromisoformat(FAKE)',
    '        return base.astimezone(tz) if tz is not None else base.astimezone()',
    'fake = types.ModuleType("datetime")',
    'fake.datetime = FakeDT; fake.timezone = _dt.timezone; fake.timedelta = _dt.timedelta; fake.date = _dt.date',
    'sys.modules["datetime"] = fake',
    'sys.stdout.write(mod.local_at(os.environ["ISO"]))',
  ].join('\n');
  return execFileSync('python3', ['-c', prog], {
    encoding: 'utf8',
    env: { ...process.env, TZ: tz, FAKE_NOW: now, ISO: iso },
  });
}

test('local_at compares dates in the HOST zone, not the reset\'s offset', { skip: !HAVE_PY && 'no python3' }, () => {
  // `dt.astimezone()` yields a FIXED-offset tzinfo for the RESET instant, and
  // rendering `now` in THAT offset puts it on a different calendar date than the
  // host is really on — twice a year, in the hour either side of a transition.
  // The clock time printed is always right; the word in front of it is not.

  // Fall-back: truly 00:30 local on the transition day, reset later the same day
  // (and on the other side of it). Read through the reset's -08:00 the current
  // instant falls on the PREVIOUS date, so a reset eight hours away is announced
  // as "tomorrow".
  assert.equal(localAt('2026-11-01T18:00:00Z',
    { now: '2026-11-01T07:30:00Z', tz: 'America/Los_Angeles' }), 'today 10:00');

  // Spring-forward mirror: truly 23:30 on the day BEFORE, reset after the gap.
  // Read through the reset's -07:00, "now" is dragged forward onto the reset's
  // own date and tomorrow reads as today.
  assert.equal(localAt('2027-03-14T17:00:00Z',
    { now: '2027-03-14T07:30:00Z', tz: 'America/Los_Angeles' }), 'tomorrow 10:00');

  // A zone on the other side of the world, transitioning on its own date.
  assert.equal(localAt('2026-10-25T08:00:00Z',
    { now: '2026-10-24T21:30:00Z', tz: 'Europe/Athens' }), 'today 10:00');

  // Ordinary days, which must not move: 09:00 local on Tuesday the 15th.
  const tue = { now: '2026-09-15T16:00:00Z', tz: 'America/Los_Angeles' };
  assert.equal(localAt('2026-09-15T20:30:00Z', tue), 'today 13:30');
  assert.equal(localAt('2026-09-16T20:30:00Z', tue), 'tomorrow 13:30');
  assert.equal(localAt('2026-09-19T20:30:00Z', tue), 'Sat 13:30');
  assert.equal(localAt('2026-09-30T20:30:00Z', tue), '2026-09-30 13:30');
});

const NOW = Date.now();
const base = {
  mode: 'ok',
  worst: null,
  accounts: {},
  sessions: [],
  sentinels: { STOP: null, 'STOP-FABLE': null },
  held: [],
  resets: [],
  arbiter: { why: null, lastSwitchAt: 0, lastLadderAt: 0, lastResumeAt: 0, switches: 0, lastAction: null },
  settings: {},
  serverTime: NOW,
};

test('ago() reads MILLISECONDS, and ages', { skip: !HAVE_PY && 'no python3' }, () => {
  assert.equal(ago(NOW - 5_000), 'just now');
  assert.equal(ago(NOW - 20 * 60_000), '20m ago');
  assert.equal(ago(NOW - 3 * 3600_000), '3h ago');
  assert.equal(ago(NOW - 2 * 86_400_000), '2d ago');
  // ⚠ The bug: a ms epoch read as seconds is ~fifty thousand years in the
  // future, which this renderer prints as "just now" — for ever.
  assert.notEqual(ago(NOW - 3 * 3600_000), 'just now');
  // Nothing to say is said as nothing, not as 1970.
  assert.equal(ago(0), '');
  assert.equal(ago(null), '');
  // An older daemon's seconds still render rather than printing 1970.
  assert.equal(ago(Math.floor((NOW - 3 * 3600_000) / 1000)), '3h ago');
});

test("the arbiter line uses the daemon's OWN field names", { skip: !HAVE_PY && 'no python3' }, () => {
  const out = render({
    ...base,
    arbiter: {
      ...base.arbiter,
      lastAction: { type: 'ladder_down', at: NOW - 20 * 60_000, name: 'jtyper', from: 'fable', to: 'opus' },
      why: 'jtyper: fable -> opus at 94% of the Fable week',
    },
  });
  const line = out.split('\n').find((l) => l.includes('Arbiter:'));
  assert.ok(line, out);
  assert.match(line, /ladder down/, 'the action, not the word "acted"');
  assert.match(line, /on jtyper/, 'and WHICH session — `name`, not `session`');
  assert.match(line, /-> opus/);
  assert.match(line, /20m ago/, 'and a time that actually ages');
  assert.match(out, /why: jtyper: fable -> opus/);
});

test('a sentinel and a held spawn render their ms `since`', { skip: !HAVE_PY && 'no python3' }, () => {
  const out = render({
    ...base,
    sentinels: { STOP: { since: NOW - 45 * 60_000, reason: 'session 97%' }, 'STOP-FABLE': null },
    held: [{ agentId: 'a1', agentType: 'Explore', sessionId: 's1', since: NOW - 90 * 60_000 }],
  });
  assert.match(out, /armed 45m ago/);
  assert.match(out, /session 97%/);
  assert.match(out, /Holding 1 new agent: Explore/);
  assert.match(out, /oldest held 1h ago/);
});

test('a laddered session shows when it was moved', { skip: !HAVE_PY && 'no python3' }, () => {
  const out = render({
    ...base,
    sessions: [{
      name: 'jtyper', family: 'opus', autoResume: true, stalled: false,
      ladder: { from: 'fable', to: 'opus', at: NOW - 3 * 3600_000, delivery: 'confirmed' },
    }],
  });
  assert.match(out, /jtyper: fable -> opus {2}\(confirmed, 3h ago\)/);
});
