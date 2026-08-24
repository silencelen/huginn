'use strict';
// The rules behind a Round, tested without a daemon, a clock or a tmux server.
//
// Schedule arithmetic gets the most attention here because its failures are the
// quiet kind: a job that fires an hour early for half the year, or skips one week
// a year, looks exactly like a job that works. The briefing cron ran at 2pm for
// months before anyone noticed.

const { test } = require('node:test');
const assert = require('node:assert');

const R = require('../lib/rounds');

const LA = 'America/Los_Angeles';
const SUNDAY_7PM = { kind: 'weekly', days: [0], at: '19:00', tz: LA };

/** The weekday a zone thinks that instant falls on. */
function localDow(tz, t) {
  const p = R.partsIn(tz, t);
  return new Date(Date.UTC(p.y, p.mo - 1, p.d)).getUTCDay();
}

// ---------------------------------------------------------------- schedules

test('weekly lands on the requested day at the requested wall-clock time', () => {
  // A Sunday, 05:00 in Los Angeles.
  const from = Date.UTC(2026, 7, 23, 12, 0, 0);
  const t = R.nextFireAt(SUNDAY_7PM, from);
  const p = R.partsIn(LA, t);
  assert.equal(localDow(LA, t), 0, 'a Sunday');
  assert.equal(p.h, 19);
  assert.equal(p.mi, 0);
  assert.equal(p.d, 23, 'later the same Sunday, not a week out');
});

test('a fire already past today rolls to the next matching day', () => {
  // The same Sunday, but 21:00 local — 19:00 has been and gone.
  const from = Date.UTC(2026, 7, 24, 4, 0, 0);
  const t = R.nextFireAt(SUNDAY_7PM, from);
  assert.equal(localDow(LA, t), 0);
  assert.equal(R.partsIn(LA, t).d, 30, 'the following Sunday');
});

test('the wall-clock hour survives a DST transition', () => {
  // US spring-forward 2027 is March 14. A 09:00 daily must stay 09:00 across it,
  // which is the whole reason the zone is stored rather than a fixed offset.
  const daily = { kind: 'daily', at: '09:00', tz: LA };
  let t = R.nextFireAt(daily, Date.UTC(2027, 2, 12, 20, 0, 0));
  for (let i = 0; i < 4; i++) {
    const p = R.partsIn(LA, t);
    assert.equal(p.h, 9, `fire ${i} should be 09:00 local, got ${p.h}`);
    assert.equal(p.mi, 0);
    t = R.nextFireAt(daily, t);
  }
});

test('a daily schedule never skips or repeats a calendar day across DST', () => {
  // Stepping by 86_400_000 instead of by calendar date lands on the day AFTER
  // next at a spring-forward, so a weekly Round would silently miss that week.
  const daily = { kind: 'daily', at: '09:00', tz: LA };
  let t = R.nextFireAt(daily, Date.UTC(2027, 2, 10, 0, 0, 0));
  const seen = [];
  for (let i = 0; i < 8; i++) { seen.push(R.partsIn(LA, t).d); t = R.nextFireAt(daily, t); }
  assert.deepEqual(seen, [10, 11, 12, 13, 14, 15, 16, 17]);
});

test('a weekly Round still fires on the DST-transition weekend', () => {
  const t = R.nextFireAt({ kind: 'weekly', days: [0], at: '09:00', tz: LA },
    Date.UTC(2027, 2, 9, 12, 0, 0));
  const p = R.partsIn(LA, t);
  assert.equal(p.d, 14, 'the spring-forward Sunday itself');
  assert.equal(p.h, 9);
});

test('a time that does not exist on a spring-forward day still advances', () => {
  // 02:30 is skipped entirely on the transition day. Whatever it resolves to, it
  // must be a real instant and the schedule must keep moving forward.
  const daily = { kind: 'daily', at: '02:30', tz: LA };
  let t = R.nextFireAt(daily, Date.UTC(2027, 2, 13, 20, 0, 0));
  assert.ok(Number.isFinite(t));
  for (let i = 0; i < 5; i++) {
    const next = R.nextFireAt(daily, t);
    assert.ok(next > t, 'each fire is strictly after the last');
    t = next;
  }
});

test('a schedule is strictly monotonic over a full year', () => {
  // The invariant that matters most: whatever the zone does, arming a Round from
  // its own last fire can never stall or go backwards.
  let t = R.nextFireAt(SUNDAY_7PM, Date.UTC(2026, 0, 1));
  for (let i = 0; i < 60; i++) {
    const next = R.nextFireAt(SUNDAY_7PM, t);
    assert.ok(next > t, `fire ${i} did not advance`);
    assert.equal(localDow(LA, next), 0);
    t = next;
  }
});

test('monthly skips months that have no such date', () => {
  const t = R.nextFireAt({ kind: 'monthly', dates: [31], at: '08:00', tz: LA },
    Date.UTC(2026, 1, 1, 20, 0, 0));
  const p = R.partsIn(LA, t);
  assert.equal(p.mo, 3, 'February has no 31st, so March');
  assert.equal(p.d, 31);
});

test('interval fires a fixed distance from now', () => {
  const from = Date.UTC(2026, 7, 23, 12, 0, 0);
  assert.equal(R.nextFireAt({ kind: 'interval', everyMinutes: 240 }, from), from + 240 * 60_000);
});

test('validateSchedule accepts the four kinds and normalises them', () => {
  assert.equal(R.validateSchedule({ kind: 'daily', at: '07:00', tz: LA }).ok, true);
  const w = R.validateSchedule({ kind: 'weekly', days: [0, 0, 3], at: '19:00', tz: LA });
  assert.deepEqual(w.schedule.days, [0, 3], 'deduped and sorted');
  assert.equal(R.validateSchedule({ kind: 'interval', everyMinutes: 240 }).ok, true);
  assert.equal(R.validateSchedule({ kind: 'monthly', dates: [1], at: '08:00', tz: LA }).ok, true);
});

test('validateSchedule rejects what would fire wrong rather than not at all', () => {
  const bad = [
    { kind: 'nope' },
    { kind: 'daily', at: '25:00', tz: LA },
    { kind: 'daily', at: '7:00', tz: LA },              // not zero-padded
    { kind: 'daily', at: '07:00', tz: 'Mars/Olympus' },
    { kind: 'weekly', at: '07:00', tz: LA, days: [] },
    { kind: 'weekly', at: '07:00', tz: LA, days: [7] },
    { kind: 'monthly', at: '07:00', tz: LA, dates: [0] },
    { kind: 'interval', everyMinutes: 1 },              // below the floor
  ];
  for (const s of bad) {
    const r = R.validateSchedule(s);
    assert.equal(r.ok, false, `should reject ${JSON.stringify(s)}`);
    assert.ok(r.error, 'and say why');
  }
});

test('describeSchedule renders the cadence for the client', () => {
  assert.equal(R.describeSchedule(SUNDAY_7PM), 'Sundays at 7:00 PM');
  assert.equal(R.describeSchedule({ kind: 'daily', at: '07:00', tz: LA }), 'Daily at 7:00 AM');
  assert.equal(R.describeSchedule({ kind: 'weekly', days: [1, 3], at: '00:30', tz: LA }), 'Mon, Wed at 12:30 AM');
  assert.equal(R.describeSchedule({ kind: 'interval', everyMinutes: 240 }), 'Every 4 hours');
  assert.equal(R.describeSchedule({ kind: 'interval', everyMinutes: 30 }), 'Every 30 minutes');
  assert.equal(R.describeSchedule({ kind: 'monthly', dates: [1, 22], at: '08:00', tz: LA }),
    'Monthly on the 1st, 22nd at 8:00 AM');
});

test('noon and midnight are not rendered as 0:00', () => {
  assert.equal(R.clockWords('00:00'), '12:00 AM');
  assert.equal(R.clockWords('12:00'), '12:00 PM');
});

// ------------------------------------------------------------------ report

test('the report is read out of the fenced block', () => {
  const r = R.parseReport(`Looked at the week.

\`\`\`huginn-report
{"status":"attention","headline":"3 action items","items":[{"title":"stuck vzdump","detail":"same guest daily","suggest":"check tasks/active"}]}
\`\`\``);
  assert.equal(r.status, 'attention');
  assert.equal(r.headline, '3 action items');
  assert.equal(r.items.length, 1);
  assert.equal(r.items[0].suggest, 'check tasks/active');
  assert.equal(r.malformed, false);
});

test('the LAST block wins', () => {
  // A run that quotes the contract back, or drafts and then corrects, ends with
  // the answer — the same way the last word of any turn is the answer.
  const r = R.parseReport(
    '```huginn-report\n{"status":"ok","headline":"draft"}\n```\n' +
    'on reflection:\n```huginn-report\n{"status":"action","headline":"final"}\n```');
  assert.equal(r.headline, 'final');
  assert.equal(r.status, 'action');
});

test('an unparseable or headline-less block is not a report', () => {
  assert.equal(R.parseReport('```huginn-report\n{not json}\n```'), null);
  assert.equal(R.parseReport('```huginn-report\n{"status":"ok"}\n```'), null, 'no headline');
  assert.equal(R.parseReport('```huginn-report\n["a","b"]\n```'), null, 'not an object');
  assert.equal(R.parseReport('no block at all'), null);
  assert.equal(R.parseReport(''), null);
});

test('an unknown status is reported as unknown rather than trusted', () => {
  const r = R.parseReport('```huginn-report\n{"status":"catastrophe","headline":"h"}\n```');
  assert.equal(r.status, 'unknown');
});

test('items are cleaned, capped and never fabricated', () => {
  const items = Array.from({ length: 30 }, (_, i) => ({ title: `t${i}` }));
  const r = R.parseReport(`\`\`\`huginn-report\n${JSON.stringify({ status: 'ok', headline: 'h', items })}\n\`\`\``);
  assert.equal(r.items.length, 20, 'capped');
  assert.equal(r.items[0].detail, '', 'missing fields become empty, not undefined');
  const none = R.parseReport('```huginn-report\n{"status":"ok","headline":"h","items":"nope"}\n```');
  assert.deepEqual(none.items, []);
});

test('a run with no block still reports, quoting what it said', () => {
  const r = R.fallbackReport('I checked the alerts and everything looks fine.');
  assert.equal(r.status, 'unknown');
  assert.equal(r.malformed, true);
  assert.match(r.headline, /checked the alerts/);
});

test('the fallback quote does not echo a code fence back at the reader', () => {
  const r = R.fallbackReport('Result:\n```json\n{"a":1}\n```\nand that is that.');
  assert.ok(!r.headline.includes('```'), r.headline);
  assert.match(r.headline, /that is that/);
});

test('a run that produced nothing at all still says so', () => {
  assert.match(R.fallbackReport('').headline, /no output/);
  assert.equal(R.errorReport('claude exited 1').status, 'action');
});

test('the contract rides on the prompt', () => {
  const p = R.promptFor({ prompt: 'Review the week.' });
  assert.match(p, /^Review the week\./);
  assert.match(p, /huginn-report/);
});

// ------------------------------------------------------------------ notify

test('notifyWhen decides, and unknown always reaches a human', () => {
  assert.equal(R.shouldNotify('attention', 'ok'), false);
  assert.equal(R.shouldNotify('attention', 'attention'), true);
  assert.equal(R.shouldNotify('attention', 'action'), true);
  assert.equal(R.shouldNotify('attention', 'unknown'), true, 'a broken contract needs a person');
  assert.equal(R.shouldNotify('always', 'ok'), true);
  assert.equal(R.shouldNotify('never', 'action'), false);
});

// --------------------------------------------------------------------- due

const ROUND = { enabled: true, catchUp: false, schedule: SUNDAY_7PM };
const NOW = Date.UTC(2026, 7, 23, 12, 0, 0);

test('a Round with no arming time is armed, not run', () => {
  const d = R.dueDecision({ ...ROUND, nextRunAt: 0 }, NOW);
  assert.equal(d.run, false);
  assert.ok(d.nextRunAt > NOW);
});

test('a Round fires when its time comes', () => {
  const d = R.dueDecision({ ...ROUND, nextRunAt: NOW - 1000 }, NOW);
  assert.equal(d.run, true);
  assert.ok(d.nextRunAt > NOW, 're-armed forward');
});

test('a slightly late tick still counts as on time', () => {
  const d = R.dueDecision({ ...ROUND, nextRunAt: NOW - R.MISSED_GRACE_MS + 1000 }, NOW);
  assert.equal(d.run, true);
});

test('a fire missed by hours is skipped, and never repeats', () => {
  // The daemon was down over the weekend. Running a Sunday review on Tuesday is
  // the owner's call, and the default is not to.
  const d = R.dueDecision({ ...ROUND, nextRunAt: NOW - 36 * 3600_000 }, NOW);
  assert.equal(d.run, false);
  assert.equal(d.reason, 'missed');
  assert.ok(d.nextRunAt > NOW, 'still re-armed, so the miss cannot repeat every tick');
});

test('catchUp runs the missed fire once', () => {
  const d = R.dueDecision({ ...ROUND, catchUp: true, nextRunAt: NOW - 36 * 3600_000 }, NOW);
  assert.equal(d.run, true);
  assert.ok(d.nextRunAt > NOW);
});

test('a disabled Round neither runs nor re-arms', () => {
  const d = R.dueDecision({ ...ROUND, enabled: false, nextRunAt: NOW - 1000 }, NOW);
  assert.equal(d.run, false);
  assert.equal(d.nextRunAt, NOW - 1000, 'left where it was, so enabling does not lose the slot');
});
