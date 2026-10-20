'use strict';
// A Round set for a wall-clock time that a spring-forward deletes runs AFTER the
// gap, in every zone: the first instant past the jump, on the day it was set for.
//
// West of UTC it used to run BEFORE the gap instead. Los Angeles' 02:30 on the
// transition day resolved to 01:30 PST — an hour early, and ahead of the 02:00
// backup or sync that a 02:30 Round is typically there to check, so the report
// described work that had not run yet. London's 01:30 had always resolved forward,
// to 02:30 BST: which side won was an accident of the offset's sign.
//
// Pure: lib/rounds owns the arithmetic, so no daemon and no clock are needed.

const { test } = require('node:test');
const assert = require('node:assert');
const R = require('../lib/rounds');

const LA = 'America/Los_Angeles';

/** Local [day, hour, minute] of an instant in `tz`. */
function local(tz, ms) {
  const p = R.partsIn(tz, ms);
  return [p.d, p.h, p.mi];
}

test('Los Angeles 02:30 on the spring-forward day resolves to 03:30 PDT', () => {
  // 2026-03-08: 02:00 PST jumps to 03:00 PDT (10:00Z), so 02:30 never happens.
  const t = R.epochForWallClock(LA, 2026, 3, 8, 2, 30);
  assert.equal(new Date(t).toISOString(), '2026-03-08T10:30:00.000Z',
    'after the jump, not 09:30Z (01:30 PST, an hour before the time asked for)');
  assert.deepEqual(local(LA, t), [8, 3, 30]);
});

test('a daily 02:30 Round fires after the 02:00 jump, then keeps its time', () => {
  const daily = { kind: 'daily', at: '02:30', tz: LA };
  const jump = Date.UTC(2026, 2, 8, 10, 0, 0);
  const fire = R.nextFireAt(daily, Date.UTC(2026, 2, 8, 0, 0, 0));
  assert.ok(fire >= jump, `fired ${new Date(fire).toISOString()}, before the 02:00 PST -> 03:00 PDT jump`);
  assert.deepEqual(local(LA, fire), [8, 3, 30]);
  const next = R.nextFireAt(daily, fire);
  assert.deepEqual(local(LA, next), [9, 2, 30], 'the day after is back at 02:30');
});

test('every deleted minute in the US and Canadian zones lands after the jump, same day', () => {
  // All of these jump from 02:00 to 03:00 local on 2026-03-08.
  for (const tz of ['America/Los_Angeles', 'America/Denver', 'America/Chicago', 'America/New_York',
    'America/Anchorage', 'America/Halifax', 'America/St_Johns']) {
    for (const [h, mi] of [[2, 0], [2, 1], [2, 30], [2, 59]]) {
      const t = R.epochForWallClock(tz, 2026, 3, 8, h, mi);
      const [d, lh, lmi] = local(tz, t);
      assert.equal(d, 8, `${tz} ${h}:${mi} moved to day ${d}`);
      assert.deepEqual([lh, lmi], [3, mi], `${tz} ${h}:${mi} resolved to ${lh}:${lmi}, not past the jump`);
    }
  }
});

test('eastern gaps still resolve forward, as they always did', () => {
  // The control: these were right before the fix and must not move.
  assert.equal(new Date(R.epochForWallClock('Europe/London', 2026, 3, 29, 1, 30)).toISOString(),
    '2026-03-29T01:30:00.000Z', 'London 01:30 is 02:30 BST');
  assert.equal(new Date(R.epochForWallClock('Europe/Berlin', 2026, 3, 29, 2, 30)).toISOString(),
    '2026-03-29T01:30:00.000Z', 'Berlin 02:30 is 03:30 CEST');
  // A half-hour jump: Lord Howe goes 02:00 -> 02:30.
  assert.deepEqual(local('Australia/Lord_Howe', R.epochForWallClock('Australia/Lord_Howe', 2026, 10, 4, 2, 15)),
    [4, 2, 45]);
});

test('a time that exists is untouched, the repeated fall-back hour included', () => {
  assert.equal(new Date(R.epochForWallClock(LA, 2026, 3, 8, 9, 0)).toISOString(), '2026-03-08T16:00:00.000Z');
  // 01:30 happens twice on 2026-11-01; it resolves to the first, as before.
  assert.equal(new Date(R.epochForWallClock(LA, 2026, 11, 1, 1, 30)).toISOString(), '2026-11-01T08:30:00.000Z');
  // And the midnight gaps keep their date (the earlier fix stays in force).
  assert.deepEqual(local('America/Havana', R.epochForWallClock('America/Havana', 2026, 3, 8, 0, 0)), [8, 1, 0]);
  assert.deepEqual(local('Atlantic/Azores', R.epochForWallClock('Atlantic/Azores', 2026, 3, 29, 0, 0)), [29, 1, 0]);
});
