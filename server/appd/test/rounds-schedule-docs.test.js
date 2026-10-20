'use strict';
// The API reference's Round schedule, checked against the validator that
// answers the request.
//
// ⚠ A REFERENCE IS A CONTRACT FOR THE PEOPLE WHO DO NOT READ THE SOURCE. The row
// for POST /v1/rounds named an `hourly` kind that the daemon has never accepted,
// and left out `monthly` and `interval`, which it has accepted since Rounds
// shipped. A script written from the reference got a 400 for the documented
// shape and had no way to learn the working ones short of reading the error.
// So the kinds the row spells out must be exactly the kinds `validateSchedule`
// takes, and every number range it gives must be the validator's own edge.
//
// SAFETY: reads files. No daemon, no port, no network.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const rounds = require('../lib/rounds');

const REPO = path.join(__dirname, '..', '..', '..');
const TZ = 'America/Los_Angeles';

/** The reference row for creating a Round. */
function roundsRow() {
  const md = fs.readFileSync(path.join(REPO, 'docs', 'reference', 'api.md'), 'utf8');
  const row = md.split('\n').find((l) => /^\|\s*POST\s*\|\s*`\/v1\/rounds`\s*\|/.test(l));
  assert.ok(row, 'the API reference still has a POST /v1/rounds row');
  return row;
}

/** `name: lo-hi` inside the row, as two numbers, or null when the row gives no range. */
function rangeOf(row, name) {
  const m = new RegExp(`${name}:\\s*\\[?(\\d+)-(\\d+)\\]?`).exec(row);
  return m ? [Number(m[1]), Number(m[2])] : null;
}

const ok = (s) => rounds.validateSchedule(s, TZ).ok;

test('the reference names exactly the schedule kinds the daemon accepts', () => {
  const documented = [...roundsRow().matchAll(/kind:\s*"([a-z]+)"/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(documented)].sort(), [...rounds.KINDS].sort(),
    'every kind the validator takes is spelled out, and nothing it refuses is');
  for (const kind of documented) {
    assert.ok(rounds.KINDS.includes(kind), `"${kind}" is documented, so the daemon must accept it`);
  }
});

test('the ranges the reference gives are the validator\'s own edges', () => {
  const row = roundsRow();

  const every = rangeOf(row, 'everyMinutes');
  assert.ok(every, 'an interval says how often it may fire');
  assert.equal(true, ok({ kind: 'interval', everyMinutes: every[0] }), `${every[0]} is the documented floor`);
  assert.equal(true, ok({ kind: 'interval', everyMinutes: every[1] }), `${every[1]} is the documented ceiling`);
  assert.equal(false, ok({ kind: 'interval', everyMinutes: every[0] - 1 }), 'and one under it is refused');
  assert.equal(false, ok({ kind: 'interval', everyMinutes: every[1] + 1 }), 'and one over it is refused');

  const dates = rangeOf(row, 'dates');
  assert.ok(dates, 'a monthly Round says which dates it may name');
  for (const d of dates) assert.equal(true, ok({ kind: 'monthly', dates: [d], at: '08:00' }), `date ${d}`);
  assert.equal(false, ok({ kind: 'monthly', dates: [dates[0] - 1], at: '08:00' }));
  assert.equal(false, ok({ kind: 'monthly', dates: [dates[1] + 1], at: '08:00' }));

  const days = rangeOf(row, 'days');
  assert.ok(days, 'a weekly Round says which days it may name');
  for (const d of days) assert.equal(true, ok({ kind: 'weekly', days: [d], at: '19:00' }), `day ${d}`);
  assert.equal(false, ok({ kind: 'weekly', days: [days[1] + 1], at: '19:00' }));

  assert.equal(true, ok({ kind: 'daily', at: '07:30' }), 'and the plain daily shape');
});
