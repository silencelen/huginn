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

test('a goal is stated FIRST, as a completion test', () => {
  // A scheduled run has nobody to ask "is this enough?", so the only thing that
  // can tell it when to stop is a sentence written in advance.
  const p = R.promptFor({ prompt: 'Review the week.', goal: 'every alert is triaged' });
  assert.match(p, /^GOAL — this run is done when: every alert is triaged/);
  assert.ok(p.indexOf('GOAL') < p.indexOf('Review the week.'), 'before the task, not after it');
});

test('a Round with no goal gets no goal line', () => {
  // Reporting on something is a legitimate Round with no finish line to cross.
  assert.ok(!R.promptFor({ prompt: 'Just look.' }).includes('GOAL'));
  assert.ok(!R.promptFor({ prompt: 'Just look.', goal: '   ' }).includes('GOAL'));
});

test('goalMet is tri-state: yes, no, and did not say', () => {
  const yes = R.parseReport('```huginn-report\n{"status":"ok","headline":"h","goalMet":true}\n```');
  const no = R.parseReport('```huginn-report\n{"status":"ok","headline":"h","goalMet":false}\n```');
  const quiet = R.parseReport('```huginn-report\n{"status":"ok","headline":"h"}\n```');
  assert.equal(yes.goalMet, true);
  assert.equal(no.goalMet, false);
  assert.equal(quiet.goalMet, null, 'a Round with no goal has nothing to answer');
  // Coercing "did not say" to false would report every goal-less Round as failed.
  assert.notEqual(quiet.goalMet, false);
});

test('a non-boolean goalMet is not believed', () => {
  const r = R.parseReport('```huginn-report\n{"status":"ok","headline":"h","goalMet":"yes"}\n```');
  assert.equal(r.goalMet, null);
});

test('an unmet goal lifts a clean status, and never lowers a dirty one', () => {
  // The failure most worth surfacing: a run that says "ok" while admitting it did
  // not do the job. Nothing else about it looks wrong.
  assert.equal(R.effectiveStatus({ status: 'ok', goalMet: false }), 'attention');
  assert.equal(R.effectiveStatus({ status: 'ok', goalMet: true }), 'ok');
  assert.equal(R.effectiveStatus({ status: 'ok', goalMet: null }), 'ok');
  assert.equal(R.effectiveStatus({ status: 'action', goalMet: false }), 'action', 'never downgraded');
  assert.equal(R.effectiveStatus({ status: 'attention', goalMet: true }), 'attention');
  assert.equal(R.effectiveStatus(null), 'unknown');
});

test('a failed run reports its goal as unmet', () => {
  assert.equal(R.errorReport('claude exited 1').goalMet, false);
  assert.equal(R.fallbackReport('some prose').goalMet, null, 'but a formatting miss is not a claim');
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

// ---------------------------------------------------------- a default zone

test('a schedule with no zone takes the one it is given', () => {
  // The shared UI code is multiplatform and has no calendar, so a client cannot
  // always name a zone. An absent one means the host's — which is where the
  // Round fires and whose DST rules it obeys.
  const r = R.validateSchedule({ kind: 'weekly', days: [0], at: '19:00' }, LA);
  assert.equal(r.ok, true, r.error);
  assert.equal(r.schedule.tz, LA);
});

test('a zone the client DID name wins over the default', () => {
  const r = R.validateSchedule({ kind: 'daily', at: '07:00', tz: 'Europe/London' }, LA);
  assert.equal(r.ok, true, r.error);
  assert.equal(r.schedule.tz, 'Europe/London');
});

test('a blank zone is an absent one, not an invalid one', () => {
  // "   " arriving from a text field must fall back rather than fail: the user
  // did not name a zone, and telling them their zone is malformed is a lie.
  for (const tz of ['', '   ', null, undefined]) {
    const r = R.validateSchedule({ kind: 'daily', at: '07:00', tz }, LA);
    assert.equal(r.ok, true, `tz=${JSON.stringify(tz)}: ${r.error}`);
    assert.equal(r.schedule.tz, LA);
  }
});

test('no zone anywhere is still refused', () => {
  // The default makes a zone easy to supply, not optional. A schedule with no
  // zone cannot be fired at a time, and storing one would be storing a bug.
  const r = R.validateSchedule({ kind: 'daily', at: '07:00' }, null);
  assert.equal(r.ok, false);
  assert.match(r.error, /tz/);
});

test('a nonsense zone is refused even as the default', () => {
  const r = R.validateSchedule({ kind: 'daily', at: '07:00' }, 'Mars/Olympus_Mons');
  assert.equal(r.ok, false);
});

test('an interval schedule needs no zone at all', () => {
  // It counts minutes; there is no wall clock to place, so no zone to get wrong.
  const r = R.validateSchedule({ kind: 'interval', everyMinutes: 30 });
  assert.equal(r.ok, true, r.error);
  assert.equal(r.schedule.tz, undefined);
});

// ------------------------------------------------- one verdict, every channel

test('a promoted report reads the same on every channel', () => {
  // The failure this closes: push led with "did not finish" while Telegram
  // indexed the REPORTED status, so an ok-but-unfinished round arrived as a green
  // tick and a clean sentence — on the channel used exactly when the app is not
  // there to show the warning row. Same event, two channels, opposite verdicts.
  const d = R.reportDisplay({ status: 'ok', goalMet: false, headline: 'looked fine to me', items: [] });
  assert.equal(d.status, 'attention', 'the promotion did not reach the display status');
  assert.match(d.text, /^did not finish — /);
});

test('an honest all-clear is not dressed up as a problem', () => {
  const d = R.reportDisplay({ status: 'ok', goalMet: true, headline: 'all clear', items: [] });
  assert.equal(d.status, 'ok');
  assert.equal(d.text, 'all clear');
});

test('a goal nobody set is not a failure', () => {
  // goalMet null means the Round stated no goal, or the run did not say. Neither
  // is "did not finish", and rendering it as one would make every goal-less Round
  // look broken.
  const d = R.reportDisplay({ status: 'ok', goalMet: null, headline: 'nothing to report', items: [] });
  assert.equal(d.status, 'ok');
  assert.equal(d.text, 'nothing to report');
});

test('a promotion only ever raises', () => {
  const d = R.reportDisplay({ status: 'action', goalMet: false, headline: 'disk full', items: [] });
  assert.equal(d.status, 'action', 'action was softened to attention');
});

// ------------------------------------ the gap that begins at local midnight

// ⚠ NOT `localDow` — this file already has one, returning the weekday INDEX, and
// a second function declaration silently overwrites the first. Redeclaring it
// handed every existing test a string where it expected a number, and three
// invariants failed for a reason that had nothing to do with what they assert.
/** The local calendar day an instant falls on, in `tz`. */
function localDayName(tz, ms) {
  return new Date(ms).toLocaleString('en-US',
    { timeZone: tz, year: 'numeric', month: 'numeric', day: 'numeric' });
}
/** The weekday as a short NAME, for messages a person reads. */
function localDowName(tz, ms) {
  return new Date(ms).toLocaleString('en-US', { timeZone: tz, weekday: 'short' });
}

// America/Havana, America/Santiago and Atlantic/Azores start their spring-forward
// at 00:00 local. A wall time that does not exist used to resolve to 23:xx the
// PREVIOUS day — a different date, weekday and day-of-month — so a midnight Round
// ran twice on one day and never on the transition day, a Sunday Round fired on
// Saturday, and a monthly-on-the-8th fired on the 7th. Silent everywhere, because
// the cadence is rendered from the schedule and the schedule was never wrong.
const MIDNIGHT_GAP = [
  ['America/Havana', Date.UTC(2026, 2, 1)],
  ['America/Santiago', Date.UTC(2026, 8, 1)],
  ['Atlantic/Azores', Date.UTC(2026, 2, 20)],
];

for (const [tz, from] of MIDNIGHT_GAP) {
  test(`${tz}: a midnight daily fires once per local day across its gap`, () => {
    let t = from; const days = [];
    for (let i = 0; i < 12; i++) { t = R.nextFireAt({ kind: 'daily', at: '00:00', tz }, t); days.push(localDayName(tz, t)); t += 1000; }
    assert.equal(new Set(days).size, days.length, `a local day fired twice: ${days.join(' | ')}`);
  });

  test(`${tz}: a midnight WEEKLY round stays on its weekday`, () => {
    let t = from;
    for (let i = 0; i < 6; i++) {
      t = R.nextFireAt({ kind: 'weekly', days: [0], at: '00:00', tz }, t);
      assert.equal(localDowName(tz, t), 'Sun', `fired on ${localDowName(tz, t)} while the row says Sundays`);
      t += 1000;
    }
  });

  test(`${tz}: a midnight MONTHLY round stays on its date`, () => {
    let t = from;
    for (let i = 0; i < 4; i++) {
      t = R.nextFireAt({ kind: 'monthly', dates: [8], at: '00:00', tz }, t);
      assert.match(localDayName(tz, t), /\/8\//, `fired on ${localDayName(tz, t)} for a round set to the 8th`);
      t += 1000;
    }
  });
}

test('the zones that were already right did not move', () => {
  // The fix changes which side of a transition an impossible wall time resolves
  // to. Everything else must be untouched — including the eastern-offset gaps
  // (Cairo) and the half- and three-quarter-hour zones.
  for (const tz of ['America/Los_Angeles', 'Europe/London', 'Africa/Cairo',
    'Pacific/Chatham', 'Australia/Lord_Howe', 'Asia/Kathmandu', 'UTC']) {
    let t = Date.UTC(2026, 0, 1); const days = [];
    for (let i = 0; i < 400; i++) { t = R.nextFireAt({ kind: 'daily', at: '09:00', tz }, t); days.push(localDayName(tz, t)); t += 1000; }
    assert.equal(new Set(days).size, days.length, `${tz} repeated a day`);
  }
});

// ------------------------------------------- an interval keeps its zone

test('switching to an interval does not destroy the zone', () => {
  // It does not USE the zone — but dropping it destroyed it, and the editor seeds
  // itself from schedule.tz, so a Round toggled to Interval and back came out in
  // whatever zone the editing device was in. Eight hours out, unrecoverable.
  const r = R.validateSchedule({ kind: 'interval', everyMinutes: 60, tz: 'Asia/Tokyo' }, 'America/Los_Angeles');
  assert.equal(r.ok, true, r.error);
  assert.equal(r.schedule.tz, 'Asia/Tokyo');
  assert.equal(r.schedule.everyMinutes, 60);
});

test('an interval with no zone takes the default, like every other kind', () => {
  const r = R.validateSchedule({ kind: 'interval', everyMinutes: 60 }, LA);
  assert.equal(r.ok, true, r.error);
  assert.equal(r.schedule.tz, LA);
});

test('an interval is not REFUSED over a zone it never uses', () => {
  // Carrying the zone must not make it a validation gate: an interval counts
  // minutes and has no wall clock to place, so a bad zone is irrelevant to it.
  const r = R.validateSchedule({ kind: 'interval', everyMinutes: 60, tz: 'Not/AZone' }, null);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.schedule.tz, undefined);
});

// ---------------------------------------------------------------- read state

test('an acknowledgement is a fact about a RUN, not about a Round', () => {
  // The design in one assertion: there is no round-level flag to get stale,
  // because the thing that carries it is replaced every time a Round fires.
  assert.equal(R.isAcknowledged(null), false);
  assert.equal(R.isAcknowledged({ status: 'action' }), false);
  assert.equal(R.isAcknowledged({ status: 'action', acknowledgedAt: 0 }), false);
  assert.equal(R.isAcknowledged({ status: 'action', acknowledgedAt: 1787649475 }), true);
});

test('a clean run is never offered the control', () => {
  // Nothing to acknowledge on an all-clear, and a control that appears on every
  // row is a control nobody reads.
  assert.equal(R.canAcknowledge({ status: 'ok', goalMet: true }), false);
  assert.equal(R.canAcknowledge({ status: 'action' }), true);
  assert.equal(R.canAcknowledge({ status: 'attention' }), true);
  assert.equal(R.canAcknowledge({ status: 'unknown', malformed: true }), true);
  // An `ok` that did not reach its goal is PROMOTED to attention, and the offer
  // follows the promoted status rather than the claimed one — otherwise the one
  // report most worth answering is the one you cannot answer.
  assert.equal(R.canAcknowledge({ status: 'ok', goalMet: false }), true);
  // Already marked: the control becomes Undo, which is a different question.
  assert.equal(R.canAcknowledge({ status: 'action', acknowledgedAt: 1787649475 }), false);
});
