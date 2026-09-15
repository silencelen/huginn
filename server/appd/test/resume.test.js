'use strict';
// The auto-resume rules, against the real shape of a stalled session.
//
// The 429 fixture is a scrubbed capture of an actual session that ran out of
// headroom (test/fixtures/transcripts/limit-429.jsonl) — not a hand-written
// record, because three things about the real one are load-bearing and none is
// obvious enough to invent: the stall is an ORDINARY assistant record rather
// than an error type, its `message.model` is `<synthetic>`, and the five records
// before it are a perfectly normal turn.
//
// The CLI's own strings are quoted verbatim from spike native-rl §1. They are
// the entire contract with the native auto-continue: there is no event, no state
// file and no flag for "the wait is running" or "the wait died", so if these
// stop matching, appd silently stops covering the gaps.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const resume = require('../lib/resume');
const { defaults } = require('../lib/headroom');

const FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'limit-429.jsonl');
const RECORDS = fs.readFileSync(FIXTURE, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
/** The instant the fixture's 429 was written. */
const STALL_AT = Date.parse('2026-08-31T06:02:03.442Z');

const user = (text, at) => ({
  type: 'user',
  message: { role: 'user', content: [{ type: 'text', text }] },
  ...(at ? { timestamp: new Date(at).toISOString() } : {}),
});
const system = (text) => ({ type: 'system', message: { content: text } });

// ------------------------------------------------------------------ stallOf

test('stallOf reads the real 429 record and takes resetsAt from the ENDPOINT', () => {
  const windows = { session: { percent: 100, resetsAt: '2026-08-31T13:10:00Z' } };
  const s = resume.stallOf(RECORDS, windows, { now: STALL_AT + 1000 });
  assert.ok(s, 'the fixture ends on a stall');
  assert.equal(s.window, 'session');
  assert.equal(s.resetsAtSource, 'endpoint');
  assert.equal(s.resetsAt, Date.parse('2026-08-31T13:10:00Z'));
  assert.equal(s.at, STALL_AT, 'the stall is timed by the record, not by the clock');
  assert.match(s.text, /hit your session limit/);
});

test('with no endpoint reading it falls back to the clock in the text, and SAYS SO', () => {
  // 3:10am America/Los_Angeles on the day of the capture = 10:10 UTC. The flag
  // is the point: a caller must be able to tell an inference from a fact, because
  // resuming on a guessed reset burns one of only three attempts.
  const s = resume.stallOf(RECORDS, {}, { now: STALL_AT });
  assert.equal(s.resetsAtSource, 'text');
  assert.equal(new Date(s.resetsAt).toISOString(), '2026-08-31T10:10:00.000Z');
});

test('stallOf refuses anything that is not the 429', () => {
  const ok = RECORDS.slice(0, -1);                  // the normal turn, without the stall
  assert.equal(resume.stallOf(ok, {}, { now: STALL_AT }), null);
  const five = { type: 'assistant', isApiErrorMessage: true, apiErrorStatus: 500, message: { content: 'overloaded' } };
  assert.equal(resume.stallOf([five], {}, { now: STALL_AT }), null,
    'a 500 is an outage to ride out, not a window to wait for');
});

test('a stall is still found under the bookkeeping records that land after it', () => {
  const withNoise = [...RECORDS, { type: 'attachment', content: 'skill list' },
    { type: 'queue-operation', operation: 'remove' }];
  const s = resume.stallOf(withNoise, {}, { now: STALL_AT });
  assert.ok(s, 'an attachment landing after the 429 must not hide it');
  assert.equal(s.at, STALL_AT);
});

// -------------------------------------------------------------- nativeArmed

const CLI = { sessionId: 'x', entrypoint: 'cli', kind: 'interactive' };
const SDK = { sessionId: 'x', entrypoint: 'sdk-cli', kind: 'interactive' };

test('nativeArmed: interactive, not restored, reset inside 24 h', () => {
  const now = STALL_AT + 60_000;
  assert.equal(resume.nativeArmed({
    registryEntry: CLI, restoredAt: null, stallAt: STALL_AT, resetsAt: now + 30 * 60_000, now,
  }), true);
});

test('nativeArmed: `kind` is useless — a `-p` run also says "interactive"', () => {
  const now = STALL_AT + 60_000;
  assert.equal(resume.nativeArmed({
    registryEntry: SDK, restoredAt: null, stallAt: STALL_AT, resetsAt: now + 60_000, now,
  }), false, 'entrypoint sdk-cli has no wait at all (native-rl §1, peer-registry §7)');
  assert.equal(resume.nativeArmed({
    registryEntry: null, restoredAt: null, stallAt: STALL_AT, resetsAt: now + 60_000, now,
  }), false, 'no registry entry = the process is gone');
});

test('nativeArmed: a session appd restored SINCE the stall has no wait left', () => {
  const now = STALL_AT + 120_000;
  const args = { registryEntry: CLI, stallAt: STALL_AT, resetsAt: now + 60_000, now };
  assert.equal(resume.nativeArmed({ ...args, restoredAt: STALL_AT + 1000 }), false,
    '"Claude Code relaunched during the wait, so the task will not resume on its own"');
  assert.equal(resume.nativeArmed({ ...args, restoredAt: STALL_AT - 60_000 }), true,
    'restored BEFORE the stall means this very process hit the 429, and its wait is live');
});

test('nativeArmed: a reset more than 24 h out is refused outright', () => {
  const now = STALL_AT;
  const base = { registryEntry: CLI, restoredAt: null, stallAt: STALL_AT, now };
  assert.equal(resume.nativeArmed({ ...base, resetsAt: now + 23 * 3600_000 }), true);
  assert.equal(resume.nativeArmed({ ...base, resetsAt: now + 25 * 3600_000 }), false,
    'a weekly window is normally past the horizon — which is the gap appd fills');
  assert.equal(resume.nativeArmed({ ...base, resetsAt: null }), false);
});

// ----------------------------------------------- nativeResumed / Cancelled

test('nativeResumed sees the injected continuation sentence, verbatim', () => {
  assert.equal(resume.nativeResumed([user(resume.NATIVE_CONTINUATION)], null), true);
  assert.equal(resume.nativeResumed([user('carry on then')], null), false);
});

test('nativeResumed also counts a person typing after the reset', () => {
  const resetsAt = STALL_AT + 3600_000;
  assert.equal(resume.nativeResumed([user('actually, do the other thing', resetsAt + 1000)], resetsAt), true);
  assert.equal(resume.nativeResumed([user('typed before the reset', resetsAt - 1000)], resetsAt), false);
  // A tool result is not a person: a stall almost always follows a tool call, so
  // counting one would cancel the resume on essentially every stalled session.
  const toolResult = { type: 'user', message: { content: [{ type: 'tool_result', content: 'ok' }] }, timestamp: new Date(resetsAt + 1).toISOString() };
  assert.equal(resume.nativeResumed([toolResult], resetsAt), false);
});

test('nativeCancelled names each abort string the CLI actually writes', () => {
  const cases = [
    ['Claude Code exited during the wait, so the task will not resume on its own when the usage limit resets', 'exited'],
    ['Claude Code relaunched during the wait, so the task will not resume on its own', 'relaunched'],
    ['the usage limit now resets more than 24 hours out, so this task will not resume on its own', 'too-far-out'],
    ['Automatic continue cancelled. Your session will wait for you instead', 'cancelled'],
    ['Usage limit reached again after you continued', 'limited-again'],
    ['this session moved to the background, so the task will not resume on its own', 'backgrounded'],
  ];
  for (const [text, why] of cases) {
    assert.equal(resume.nativeCancelled([system(text)]), why, text.slice(0, 40));
  }
  assert.equal(resume.nativeCancelled([system('all done')]), null);
});

// ------------------------------------------------------------- eligibility

const SETTINGS = defaults();
const STALL = { at: STALL_AT, window: 'session', resetsAt: STALL_AT + 3600_000, attempts: 0, resumedAt: null };
const AFTER_RESET = STALL.resetsAt + 1000;

test('eligibility: the global default, and the per-session override both ways', () => {
  const base = { settings: SETTINGS, stall: STALL, resetSeen: true, now: AFTER_RESET };
  assert.equal(resume.eligible(base).ok, true, 'autoResume defaults to on');
  assert.equal(resume.eligible({ ...base, settings: { ...SETTINGS, autoResume: false } }).ok, false);
  // null means FOLLOW THE GLOBAL and must not read as false.
  assert.equal(resume.eligible({ ...base, meta: { autoResume: null } }).ok, true);
  assert.equal(resume.eligible({ ...base, meta: { autoResume: false } }).ok, false);
  assert.match(resume.eligible({ ...base, meta: { autoResume: false } }).why, /off for this session/);
  // And the override goes the other way too: on for this session, off globally.
  assert.equal(resume.eligible({
    ...base, settings: { ...SETTINGS, autoResume: false }, meta: { autoResume: true },
  }).ok, true);
});

test('eligibility: a human record after the 429 cancels it', () => {
  const base = { settings: SETTINGS, stall: STALL, resetSeen: true, now: AFTER_RESET };
  const spoke = [...RECORDS, user('stop, I will do it myself', AFTER_RESET)];
  const v = resume.eligible({ ...base, records: spoke });
  assert.equal(v.ok, false);
  assert.match(v.why, /a person answered it/);
  // …while the 429 still being the last word is the state it acts on.
  assert.equal(resume.eligible({ ...base, records: RECORDS }).ok, true);
});

test('eligibility: three attempts and no more', () => {
  const base = { settings: SETTINGS, resetSeen: true, now: AFTER_RESET };
  assert.equal(resume.eligible({ ...base, stall: { ...STALL, attempts: 2 } }).ok, true);
  const spent = resume.eligible({ ...base, stall: { ...STALL, attempts: 3 } });
  assert.equal(spent.ok, false);
  assert.match(spent.why, /gave up after 3/);
  assert.equal(resume.eligible({ ...base, stall: { ...STALL, resumedAt: AFTER_RESET } }).ok, false);
});

test('eligibility: BOTH halves of a reset, or nothing happens', () => {
  const base = { settings: SETTINGS, stall: STALL, resetSeen: false };
  // The calendar time has not passed.
  assert.match(resume.eligible({ ...base, now: STALL.resetsAt - 1 }).why, /has not reset yet/);
  // It has passed, but the window still reads full — the case that fails
  // silently: a reset by the calendar is not a reset on the wire.
  const stillRed = resume.eligible({ ...base, now: AFTER_RESET, percent: 97 });
  assert.equal(stillRed.ok, false);
  assert.match(stillRed.why, /still reads 97%/);
  assert.equal(resume.eligible({ ...base, now: AFTER_RESET, percent: 4 }).ok, true);
  assert.match(resume.eligible({ ...base, now: AFTER_RESET, percent: null }).why, /no fresh reading/);
});

// ------------------------------------------------------------- resumePlan

test('resumePlan: an armed native wait is given its 90 seconds, then taken over', () => {
  const stall = { ...STALL };
  const early = resume.resumePlan({ kind: 'session', armed: true, stall, now: stall.resetsAt + 1000 });
  assert.equal(early.action, 'wait_native');
  assert.equal(early.graceUntil, stall.resetsAt + resume.NATIVE_GRACE_MS);
  const late = resume.resumePlan({ kind: 'session', armed: true, stall, now: stall.resetsAt + resume.NATIVE_GRACE_MS });
  assert.equal(late.action, 'type_phrase');
  // A session appd restored gets no grace at all: there is no wait to defer to.
  assert.equal(resume.resumePlan({ kind: 'session', armed: false, stall, now: stall.resetsAt + 1 }).action,
    'type_phrase');
  // Nor does one whose wait the CLI has already said is dead.
  const cancelled = resume.resumePlan({ kind: 'session', armed: true, stall, cancelled: 'relaunched', now: stall.resetsAt + 1 });
  assert.equal(cancelled.action, 'type_phrase');
  assert.match(cancelled.why, /relaunched/);
});

test('resumePlan: headless work is re-run or re-queued, never typed at', () => {
  const stall = { ...STALL };
  const now = stall.resetsAt + 1;
  assert.equal(resume.resumePlan({ kind: 'chat', armed: true, stall, now }).action, 'rerun_chat');
  assert.equal(resume.resumePlan({ kind: 'round', armed: true, stall, now }).action, 'rerun_chat');
  assert.equal(resume.resumePlan({ kind: 'device', stall, now }).action, 'requeue_device');
  assert.equal(resume.resumePlan({ kind: 'session', armed: true, stall, resumedNatively: true, now }).action,
    'skip:native');
});

test('recordsAfterStall slices at the stall, not at a timestamp', () => {
  const after = resume.recordsAfterStall([...RECORDS, user('hello', AFTER_RESET)]);
  assert.equal(after.length, 1);
  assert.equal(resume.recordsAfterStall(RECORDS).length, 0, 'the 429 is still the last word');
  assert.equal(resume.recordsAfterStall([]).length, 0);
});
