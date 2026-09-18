'use strict';
// Keep-awake: whether to spend one tiny request on starting a 5-hour window
// nobody is using yet.
//
// This is the one lane in the daemon that costs the owner money with nobody
// asking, so the bar for what is asserted here is higher than elsewhere: every
// veto has a case, because a veto that stops working is a feature quietly
// spending quota in a situation it was specifically told not to. The cases that
// are impossible to reproduce on a live account — "the window rolled over at
// 3am", "the daemon restarted between the stamp and the spawn" — are exactly
// the ones this file exists for.

const { test } = require('node:test');
const assert = require('node:assert');
const k = require('../lib/keepawake');
const h = require('../lib/headroom');

const NOW = 1_800_000_000_000;
const iso = (ms) => new Date(ms).toISOString();

/** Settings with keep-awake ON, since OFF is the default and its own test. */
const S = (patch = {}) => ({ ...h.defaults(), keepAwake: true, ...patch });

/** The window shape `windowsOf` produces. `resetsAt: null` IS "not running". */
const win = (percent, resetsAt = null) => ({ percent, resetsAt, severity: 'normal', label: '' });

/**
 * Everything [k.decide] needs, in the state where it SHOULD fire. Each veto
 * test below flips exactly one field of this, so a failure names its own cause.
 */
const firing = (over = {}) => ({
  settings: S(),
  windows: { session: win(0), weekly_all: win(51, iso(NOW + 86_400_000)), weekly_fable: win(53, iso(NOW + 86_400_000)) },
  worst: { window: 'weekly_fable', percent: 53, label: 'Current week (Fable)', resetsAt: null, mode: 'ok' },
  sentinels: { STOP: null, 'STOP-FABLE': null },
  keepAwake: k.blank(),
  actions: [],
  arbiter: { lastSwitchAt: 0 },
  planError: null,
  haveReading: true,
  now: NOW,
  minutes: 14 * 60,
  ...over,
});

// ------------------------------------------------------- the trigger itself

test('a session window with a null resetsAt is NOT running, and that is the trigger', () => {
  const d = k.decide(firing());
  assert.equal(d.fire, true);
  assert.match(d.why, /no window is running/);
});

test('a session window with a real resetsAt IS running, so nothing is spent', () => {
  // The whole feature rests on this one field. Read the other way round it
  // would ping every tick against a window that is already open, which is the
  // most expensive way to get this wrong.
  const d = k.decide(firing({
    windows: { session: win(6, iso(NOW + 4 * 3600_000)), weekly_all: win(51), weekly_fable: win(53) },
  }));
  assert.equal(d.fire, false);
  assert.match(d.why, /already running/);
});

test('an ABSENT session window reads as not running, like a null one', () => {
  // The endpoint has been seen to omit the row entirely as well as to null its
  // reset, and both mean the same thing.
  const d = k.decide(firing({ windows: { session: null, weekly_all: win(51), weekly_fable: win(53) } }));
  assert.equal(d.fire, true);
});

// -------------------------------------------------------------- the vetoes

test('OFF is the default, and off means off', () => {
  assert.equal(h.defaults().keepAwake, false, 'this must never arrive switched on by an upgrade');
  const d = k.decide(firing({ settings: { ...h.defaults() } }));
  assert.equal(d.fire, false);
  assert.match(d.why, /off/);
});

test('an unreadable usage endpoint is not an idle account', () => {
  const d = k.decide(firing({ planError: 'plan usage HTTP 503' }));
  assert.equal(d.fire, false);
  assert.match(d.why, /503/);
});

test('no reading at all — a cold daemon — fires nothing', () => {
  // planCache.error is empty on the very first tick too, before any fetch has
  // landed. Without this the daemon pings itself awake on every boot.
  const d = k.decide(firing({ haveReading: false }));
  assert.equal(d.fire, false);
  assert.match(d.why, /no usage reading/);
});

test('a red WEEKLY window vetoes a ping the 5-hour window would otherwise want', () => {
  // The two run on different clocks: the week can be nearly spent while the
  // session window sits idle. Starting a window nobody can use is the one way
  // this feature makes things worse.
  const d = k.decide(firing({
    worst: { window: 'weekly_fable', percent: 94, label: 'Current week (Fable)', resetsAt: null, mode: 'red' },
  }));
  assert.equal(d.fire, false);
  assert.match(d.why, /Fable\) window is red/);
});

test('an exhausted window vetoes it too', () => {
  const d = k.decide(firing({
    worst: { window: 'weekly_all', percent: 100, label: 'Current week', resetsAt: null, mode: 'exhausted' },
  }));
  assert.equal(d.fire, false);
  assert.match(d.why, /exhausted/);
});

test('STOP armed is a hard veto — the owner\'s own agents are being held', () => {
  const d = k.decide(firing({ sentinels: { STOP: { since: NOW - 1000 }, 'STOP-FABLE': null } }));
  assert.equal(d.fire, false);
  assert.match(d.why, /STOP armed/);
});

test('STOP-FABLE armed is the same veto, and it is named', () => {
  const d = k.decide(firing({ sentinels: { STOP: null, 'STOP-FABLE': { since: NOW - 1000 } } }));
  assert.equal(d.fire, false);
  assert.match(d.why, /STOP-FABLE/);
});

test('a switch_account in this tick\'s verdict holds the ping', () => {
  // A ping lands on whatever login is ACTIVE. Firing one while the arbiter is
  // moving pins a fresh window to the account we are leaving.
  const d = k.decide(firing({ actions: [{ type: 'ladder_down' }, { type: 'switch_account', slug: 'b' }] }));
  assert.equal(d.fire, false);
  assert.match(d.why, /switch is in flight/);
});

test('the switch cooldown holds it too, after the switch itself has landed', () => {
  const d = k.decide(firing({ arbiter: { lastSwitchAt: NOW - 60_000 } }));
  assert.equal(d.fire, false);
  assert.match(d.why, /cooldown/);
  // And releases once the cooldown has run out.
  const after = k.decide(firing({ arbiter: { lastSwitchAt: NOW - (h.defaults().cooldownMs + 1) } }));
  assert.equal(after.fire, true);
});

test('a ping inside the last five hours does not fire again', () => {
  const d = k.decide(firing({ keepAwake: { ...k.blank(), lastAt: NOW - (4 * 3600_000) } }));
  assert.equal(d.fire, false);
  assert.match(d.why, /already kept awake/);
});

test('once the window has passed, the next one is eligible', () => {
  const d = k.decide(firing({ keepAwake: { ...k.blank(), lastAt: NOW - (k.FIVE_HOURS_MS + 1) } }));
  assert.equal(d.fire, true);
});

// ------------------------------------------- once per window, across a restart

test('ONCE PER WINDOW, and the guard survives a restart because it is on disk', () => {
  // The scenario: fire, write, daemon dies, daemon comes back and reads the
  // file. `hstate()` reads from disk exactly once, so what normalize() makes of
  // the stored record is the whole of the daemon's memory here.
  const first = k.decide(firing());
  assert.equal(first.fire, true);

  const stamped = k.noteFired(k.blank(), NOW);
  assert.equal(stamped.lastAt, NOW, 'lastAt is written BEFORE the spawn, never after');

  // Round-trip through JSON, which is literally what headroom.json does.
  const reloaded = k.normalize(JSON.parse(JSON.stringify(stamped)));
  const second = k.decide(firing({ keepAwake: reloaded, now: NOW + 60_000 }));
  assert.equal(second.fire, false, 'a restarted daemon must not ping the same window twice');
  assert.match(second.why, /already kept awake/);
});

test('a headroom.json written by an older daemon has no keepAwake key at all', () => {
  // Absent must read as "never pinged", not as undefined — `now - undefined` is
  // NaN, which compares false against every threshold and would turn the
  // once-per-window guard off completely.
  const r = k.normalize(undefined);
  assert.equal(r.lastAt, 0);
  assert.equal(r.keptAwakeToday, 0);
  assert.equal(r.keptAwakeTotal, 0);
  assert.equal(k.decide(firing({ keepAwake: r })).fire, true);
});

test('a half-written record degrades to zero rather than to NaN', () => {
  const r = k.normalize({ lastAt: 'yesterday', keptAwakeToday: null, keptAwakeTotal: undefined });
  assert.equal(r.lastAt, 0);
  assert.equal(r.keptAwakeToday, 0);
  assert.equal(r.keptAwakeTotal, 0);
});

// ------------------------------------------------------------- quiet hours

test('quiet hours parse as two local clock times, and nothing else does', () => {
  assert.deepEqual(k.parseQuietHours('01:00-07:00'), { from: 60, to: 420 });
  assert.deepEqual(k.parseQuietHours(' 22:30 – 06:15 '), { from: 1350, to: 375 }, 'an en dash is what a phone keyboard makes');
  assert.equal(k.parseQuietHours(null), null);
  assert.equal(k.parseQuietHours(''), null);
  assert.equal(k.parseQuietHours('1am to 7am'), null);
  assert.equal(k.parseQuietHours('25:00-07:00'), null);
  assert.equal(k.parseQuietHours('01:60-07:00'), null);
  assert.equal(k.parseQuietHours('01:00-01:00'), null, 'a zero-width range is either nothing or everything');
});

test('an ordinary daytime range is half open at both ends', () => {
  assert.equal(k.inQuietHours('09:00-17:00', 8 * 60 + 59), false);
  assert.equal(k.inQuietHours('09:00-17:00', 9 * 60), true, 'the from minute is quiet');
  assert.equal(k.inQuietHours('09:00-17:00', 16 * 60 + 59), true);
  assert.equal(k.inQuietHours('09:00-17:00', 17 * 60), false, 'the to minute is not');
});

test('A RANGE THAT CROSSES MIDNIGHT is the ordinary case, not the edge one', () => {
  // `22:00-07:00` is what somebody means by quiet hours, and read as a plain
  // from <= m < to it is EMPTY — the feature would ping all night and the knob
  // would look broken.
  const q = '22:00-07:00';
  assert.equal(k.inQuietHours(q, 21 * 60 + 59), false);
  assert.equal(k.inQuietHours(q, 22 * 60), true);
  assert.equal(k.inQuietHours(q, 23 * 60 + 59), true);
  assert.equal(k.inQuietHours(q, 0), true, 'midnight itself is inside it');
  assert.equal(k.inQuietHours(q, 3 * 60), true);
  assert.equal(k.inQuietHours(q, 6 * 60 + 59), true);
  assert.equal(k.inQuietHours(q, 7 * 60), false);
  assert.equal(k.inQuietHours(q, 12 * 60), false);
});

test('an unparseable spec is NO quiet hours rather than a silent all-day veto', () => {
  assert.equal(k.inQuietHours('nonsense', 3 * 60), false);
  assert.equal(k.decide(firing({ settings: S({ keepAwakeQuietHours: 'nonsense' }) })).fire, true);
});

test('quiet hours veto the ping, and say which range did it', () => {
  const d = k.decide(firing({ settings: S({ keepAwakeQuietHours: '01:00-07:00' }), minutes: 3 * 60 }));
  assert.equal(d.fire, false);
  assert.match(d.why, /quiet hours \(01:00-07:00\)/);
  assert.equal(k.decide(firing({ settings: S({ keepAwakeQuietHours: '01:00-07:00' }), minutes: 9 * 60 })).fire, true);
});

test('minutesOfDay reads the LOCAL clock, whatever zone the host is in', () => {
  // Built from a local Date so the assertion holds in any TZ — which is the
  // point: quiet hours are the owner's hours, not UTC's.
  const at = new Date(2026, 0, 15, 2, 30, 0).getTime();
  assert.equal(k.minutesOfDay(at), 150);
  assert.equal(k.clockOf(at), '02:30');
  assert.equal(k.dayKey(at), '2026-01-15');
});

test('the clock string is zero padded on both halves', () => {
  assert.equal(k.clockOf(new Date(2026, 0, 5, 9, 5).getTime()), '09:05');
  assert.equal(k.clockOf(new Date(2026, 0, 5, 0, 0).getTime()), '00:00');
});

// ------------------------------------------------------------- the spawn argv

test('THE CAGE: the argv carries every flag that keeps this a heartbeat', () => {
  // None of these going missing changes anything anybody could see in the
  // answer — which is exactly why they are asserted. Without
  // `--setting-sources ""` the ping loads huginn's own CLAUDE.md and the hook
  // gate's SubagentStart; without `--tools ""` it is a headless agent with a
  // shell.
  const argv = k.argvFor('claude-haiku-4-5-20251001');
  assert.deepEqual(argv, [
    '-p',
    '--setting-sources', '',
    '--strict-mcp-config',
    '--no-session-persistence',
    '--model', 'claude-haiku-4-5-20251001',
    '--max-turns', '1',
    '--tools', '',
    '--', 'Reply with the single word ok.',
  ]);
});

test('the prompt is LAST and behind a `--`, so it can never be read as a flag', () => {
  const argv = k.argvFor('haiku', '--version');
  assert.equal(argv[argv.length - 2], '--');
  assert.equal(argv[argv.length - 1], '--version');
});

test('the default model is Haiku 4.5 pinned by its DATED id, never the alias', () => {
  // The alias is whatever the installed CLI decides it means; a release that
  // promoted it would silently multiply the cost of a feature whose entire
  // justification is that it is cheap.
  assert.equal(k.DEFAULT_MODEL, 'claude-haiku-4-5-20251001');
  assert.equal(h.defaults().keepAwakeModel, 'claude-haiku-4-5-20251001');
  assert.equal(k.argvFor().includes('claude-haiku-4-5-20251001'), true);
  assert.equal(k.argvFor('').includes('claude-haiku-4-5-20251001'), true, 'an empty setting falls back rather than spawning --model ""');
});

test('`--bare` is never in the argv', () => {
  // Its own help says OAuth is never read under it: it would bill an API key or
  // fail, and either way never touch the subscription window this exists for.
  assert.equal(k.argvFor().includes('--bare'), false);
});

// --------------------------------------------------------- failure handling

test('a spawn that never reached the API is retried ONCE, then held', () => {
  const missing = { err: Object.assign(new Error('spawn claude ENOENT'), { code: 'ENOENT' }) };
  assert.equal(k.classifyOutcome(missing), 'retry');

  const stamped = k.noteFired(k.blank(), NOW);
  const first = k.afterSpawn(stamped, 'retry', NOW);
  assert.equal(first.lastAt, 0, 'lastAt goes back where it was, so the next tick tries again');
  assert.equal(k.decide(firing({ keepAwake: first, now: NOW + 60_000 })).fire, true);

  const stamped2 = k.noteFired(first, NOW + 60_000);
  const second = k.afterSpawn(stamped2, 'retry', NOW + 60_000);
  assert.equal(second.lastAt, NOW + 60_000, 'the SECOND failure is a broken host, not a blip');
  assert.equal(k.decide(firing({ keepAwake: second, now: NOW + 120_000 })).fire, false);
});

test('a limit answer HOLDS — a heartbeat must never become a hammer', () => {
  const limited = { err: new Error('exited 1'), stderr: 'Error: 429 rate_limit_error' };
  assert.equal(k.classifyOutcome(limited), 'hold');
  const after = k.afterSpawn(k.noteFired(k.blank(), NOW), 'hold', NOW);
  assert.equal(after.lastAt, NOW, 'the window stays spent, so nothing retries into a limit');
  assert.equal(k.decide(firing({ keepAwake: after, now: NOW + 60_000 })).fire, false);
});

test('a timeout holds rather than retrying — the turn may well have landed', () => {
  assert.equal(k.classifyOutcome({ err: Object.assign(new Error('timeout'), { killed: true }) }), 'hold');
  assert.equal(k.classifyOutcome({ err: Object.assign(new Error('killed'), { signal: 'SIGTERM' }) }), 'hold');
});

test('an ordinary non-zero exit holds too', () => {
  assert.equal(k.classifyOutcome({ err: new Error('Command failed'), stderr: 'something went wrong' }), 'hold');
});

test('a clean run is ok', () => {
  assert.equal(k.classifyOutcome({ err: null, stdout: 'ok\n' }), 'ok');
});

// --------------------------------------------------------------- the counters

test('only LANDED pings are counted, so the Status line cannot inflate', () => {
  const ok = k.afterSpawn(k.noteFired(k.blank(), NOW), 'ok', NOW);
  assert.equal(ok.keptAwakeToday, 1);
  assert.equal(ok.keptAwakeTotal, 1);

  const failed = k.afterSpawn(k.noteFired(ok, NOW + k.FIVE_HOURS_MS + 1), 'hold', NOW + k.FIVE_HOURS_MS + 1);
  assert.equal(failed.keptAwakeToday, 1, 'a failed attempt is not a ping');
  assert.equal(failed.keptAwakeTotal, 1);
});

test('the daily count rolls over on the LOCAL day, with no cron to do it', () => {
  const mon = new Date(2026, 4, 11, 22, 0).getTime();
  const tue = new Date(2026, 4, 12, 4, 0).getTime();
  const after = k.afterSpawn(k.noteFired(k.blank(), mon), 'ok', mon);
  assert.equal(after.keptAwakeToday, 1);
  assert.equal(k.todayCount(after, mon), 1);
  assert.equal(k.todayCount(after, tue), 0, 'yesterday\'s pings are not today\'s');

  const next = k.afterSpawn(k.noteFired(after, tue), 'ok', tue);
  assert.equal(next.keptAwakeToday, 1, 'the new day starts from one, not from two');
  assert.equal(next.keptAwakeTotal, 2, 'the total keeps counting');
});

test('the published view carries what the Status line needs and nothing private', () => {
  const at = new Date(2026, 4, 11, 14, 32).getTime();
  const rec = k.afterSpawn(k.noteFired(k.blank(), at), 'ok', at);
  const v = k.view(rec, S({ keepAwakeModel: 'claude-haiku-4-5-20251001' }), at);
  assert.equal(v.enabled, true);
  assert.equal(v.model, 'claude-haiku-4-5-20251001');
  assert.equal(v.lastAt, at);
  assert.equal(v.lastAtClock, '14:32', 'the HOST formats the clock; :core has no timezone database');
  assert.equal(v.keptAwakeToday, 1);
});

test('the view reports TODAY\'s count, not the stored one, after midnight', () => {
  const mon = new Date(2026, 4, 11, 23, 50).getTime();
  const tue = new Date(2026, 4, 12, 0, 10).getTime();
  const rec = k.afterSpawn(k.noteFired(k.blank(), mon), 'ok', mon);
  assert.equal(k.view(rec, S(), mon).keptAwakeToday, 1);
  assert.equal(k.view(rec, S(), tue).keptAwakeToday, 0);
});

// ---------------------------------------------------------------- settings

test('the three settings validate field by field, with the rule as the message', () => {
  const base = h.defaults();
  assert.equal(h.validateSettings({ keepAwake: true }, base).settings.keepAwake, true);
  assert.equal(h.validateSettings({ keepAwake: 'yes' }, base).ok, false);
  assert.match(h.validateSettings({ keepAwake: 'yes' }, base).error, /true or false/);

  assert.equal(
    h.validateSettings({ keepAwakeModel: 'claude-haiku-4-5-20251001' }, base).settings.keepAwakeModel,
    'claude-haiku-4-5-20251001',
  );
  assert.equal(h.validateSettings({ keepAwakeModel: 'haiku' }, base).ok, true, 'a family alias is legal, if weaker');
  assert.equal(h.validateSettings({ keepAwakeModel: '' }, base).ok, false);
  assert.equal(h.validateSettings({ keepAwakeModel: 'the cheap one' }, base).ok, false);
  assert.match(h.validateSettings({ keepAwakeModel: 'the cheap one' }, base).error, /model id or family alias/);

  assert.equal(h.validateSettings({ keepAwakeQuietHours: '01:00-07:00' }, base).settings.keepAwakeQuietHours, '01:00-07:00');
  assert.equal(h.validateSettings({ keepAwakeQuietHours: null }, base).settings.keepAwakeQuietHours, null);
  assert.equal(h.validateSettings({ keepAwakeQuietHours: '' }, base).settings.keepAwakeQuietHours, null,
    'clearing the field and never setting it must mean the same thing');
  assert.equal(h.validateSettings({ keepAwakeQuietHours: '1am-7am' }, base).ok, false);
  assert.match(h.validateSettings({ keepAwakeQuietHours: '1am-7am' }, base).error, /HH:MM-HH:MM/);
  assert.equal(h.validateSettings({ keepAwakeQuietHours: '26:00-07:00' }, base).ok, false);
});

test('a settings file written before this feature existed normalises it OFF', () => {
  // The loader passes the stored file as the PATCH over the defaults, so a file
  // with no keepAwake key must come back with keep-awake off rather than
  // inheriting anything. This is the upgrade path, and it is the one that must
  // not spend money.
  const stored = { headsUpPct: 80, ladderPct: 90, autoResume: false };
  const v = h.validateSettings(stored, h.defaults());
  assert.equal(v.ok, true);
  assert.equal(v.settings.keepAwake, false);
  assert.equal(v.settings.keepAwakeModel, 'claude-haiku-4-5-20251001');
  assert.equal(v.settings.keepAwakeQuietHours, null);
});

test('the decision is RECORDED either way, because "not pinging" has many causes', () => {
  // The published `why` is the only place the owner can read why a feature they
  // switched on is doing nothing — and it is what makes the wire testable at
  // all: "did not ping" and "never even looked" are the same picture without it.
  const rec = k.noteDecision(k.blank(), { fire: false, why: 'inside quiet hours (01:00-07:00)' }, NOW);
  assert.equal(rec.why, 'inside quiet hours (01:00-07:00)');
  assert.equal(rec.evaluatedAt, NOW);
  // …and it does not disturb the guard it sits beside.
  assert.equal(rec.lastAt, 0);
  const fired = k.noteDecision(k.noteFired(k.blank(), NOW), { fire: true, why: 'no window is running' }, NOW);
  assert.equal(fired.lastAt, NOW);
  assert.equal(fired.why, 'no window is running');
});
