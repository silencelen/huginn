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

test('nextOccurrence rolls the CALENDAR day, so a DST night does not skip one', () => {
  // The clock in a 429 has no date ("resets 3:10am (America/Los_Angeles)"), so
  // the only thing it can mean is the next time that clock comes round. Adding
  // 24 hours to find tomorrow is wrong twice a year, and both directions land on
  // the one night nobody is awake to notice.
  const at = (iso) => Date.parse(iso);
  const of = (clock, now) => new Date(resume.nextOccurrence(clock, 'America/Los_Angeles', at(now))).toISOString();

  // Spring-forward EVE: local 23:30 on 2027-03-13, the reset clock already past
  // for today. The next 3:10am is tomorrow — but tomorrow is a 23-hour day, so
  // `now + 24h` lands on the 15th and the stall's resetsAt reads a full day late.
  assert.equal(of('3:10am', '2027-03-14T07:30:00Z'), '2027-03-14T10:10:00.000Z');
  assert.equal(of('11:45pm', '2027-03-14T07:30:00Z'), '2027-03-14T07:45:00.000Z',
    'a clock still ahead of `now` on the same local day is today, and is not rolled');

  // FALL-BACK, the mirror: local 00:30 on 2026-11-01, a 25-hour day. `now + 24h`
  // reads the SAME calendar date back, so the answer was 20 minutes in the PAST
  // — breaking the one contract this function has.
  const back = resume.nextOccurrence('12:10am', 'America/Los_Angeles', at('2026-11-01T07:30:00Z'));
  assert.ok(back > at('2026-11-01T07:30:00Z'), `strictly in the future, got ${new Date(back).toISOString()}`);
  assert.equal(new Date(back).toISOString(), '2026-11-02T08:10:00.000Z');

  // An ordinary night still behaves exactly as before.
  assert.equal(of('3:10am', '2026-08-31T06:02:03Z'), '2026-08-31T10:10:00.000Z');
  assert.equal(of('3:10am', '2026-08-31T11:00:00Z'), '2026-09-01T10:10:00.000Z');
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

// ---- whose reset was it? ---------------------------------------------------

test('a reset on ANOTHER account is not proof for a stall on the active one', () => {
  // ⚠ `detectResets` runs over EVERY saved login, and for an inactive one the
  // "fresh" reading is aged-forward history — a fabricated 0% for any window
  // whose reset time has passed. So a second account's stale weekly_fable
  // snapshot rolling over used to satisfy a session stalled on the ACTIVE
  // account's Fable week, which is still full: the phrase is typed, the session
  // re-stalls, and one of only three attempts is gone.
  const now = Date.now();
  const stall = { at: now - 10 * 60_000, window: 'weekly_fable' };
  const theirs = { slug: 'other-account', window: 'weekly_fable', at: now - 60_000 };
  const mine = { slug: 'live-account', window: 'weekly_fable', at: now - 30_000 };

  const onlyTheirs = resume.recentResetWindows([theirs], { now, activeSlug: 'live-account' });
  assert.equal(resume.resetSeenFor(onlyTheirs, stall), false,
    "another login's rollover is not this window resetting");

  const withMine = resume.recentResetWindows([theirs, mine], { now, activeSlug: 'live-account' });
  assert.equal(resume.resetSeenFor(withMine, stall), true, 'the active account\'s own reset does count');

  // A reset that predates the stall is a DIFFERENT stall's proof.
  const earlier = resume.recentResetWindows(
    [{ slug: 'live-account', window: 'weekly_fable', at: stall.at - 60_000 }], { now, activeSlug: 'live-account' },
  );
  assert.equal(resume.resetSeenFor(earlier, stall), false);

  // Aged out of the rolling window.
  const old = resume.recentResetWindows(
    [{ slug: 'live-account', window: 'weekly_fable', at: now - 2 * resume.RESET_MEMORY_MS }],
    { now, activeSlug: 'live-account' },
  );
  assert.equal(resume.resetSeenFor(old, stall), false);

  // A row from before the slug existed is kept: dropping it would stop resumes
  // dead on an upgrade.
  const legacy = resume.recentResetWindows([{ window: 'weekly_fable', at: now - 30_000 }],
    { now, activeSlug: 'live-account' });
  assert.equal(resume.resetSeenFor(legacy, stall), true);

  // A different WINDOW is never this stall's proof either.
  const otherWindow = resume.recentResetWindows(
    [{ slug: 'live-account', window: 'session', at: now - 30_000 }], { now, activeSlug: 'live-account' },
  );
  assert.equal(resume.resetSeenFor(otherWindow, stall), false);
});

test('resetSeen short-circuits BOTH halves of the reset test, which is why whose it is matters', () => {
  // The consequence of the bug above: with `resetSeen` true, neither the clock
  // nor the fresh reading is consulted at all.
  const now = Date.now();
  const stall = { at: now - 60_000, window: 'weekly_fable', resetsAt: now + 6 * 86_400_000 };
  const settings = { autoResume: true, clearBelowPct: 50 };
  assert.equal(resume.eligible({ settings, stall, resetSeen: false, percent: 99, now }).ok, false);
  assert.equal(resume.eligible({ settings, stall, resetSeen: true, percent: 99, now }).ok, true,
    'a reset event overrides a window that still reads 99% — so it had better be the right account');
});

// ---- a stall with no clock -------------------------------------------------

test('a stall with no reset time is waited out, then re-derived, then given up on', () => {
  // ⚠ The measured Fable-weekly apology carries NO clock ("You're out of usage
  // credits. Run /usage-credits …"), so parseLimitError returns
  // `resetsClock: null`. If the usage endpoint also has nothing for that window
  // at that instant, `due` is false for ever and the only escape is a
  // detectResets event that may never come — meanwhile a held Round report is
  // never filed and the ten-second poll runs for the life of the daemon.
  const now = Date.now();
  const stall = { at: now - 60_000, window: 'weekly_fable', resetsAt: null };

  // Young: nothing to do. The endpoint usually catches up within a tick or two.
  const young = resume.unclockedVerdict({ stall, activeWindows: {}, now });
  assert.equal(young.action, 'wait');
  assert.match(young.why, /no reset time/);

  // A stall that HAS a clock is never touched by this at all.
  assert.equal(resume.unclockedVerdict({ stall: { ...stall, resetsAt: now + 1000 }, now }).action, 'clocked');

  const old = { ...stall, at: now - resume.STALL_MAX_AGE_MS - 1 };
  // Old, and the window now has a time: take it.
  const iso = new Date(now + 3_600_000).toISOString();
  const adopt = resume.unclockedVerdict({
    stall: old, activeWindows: { weekly_fable: { percent: 100, resetsAt: iso } }, now,
  });
  assert.equal(adopt.action, 'adopt');
  assert.equal(adopt.resetsAt, Date.parse(iso));

  // Old, and still nothing anywhere: give up, and SAY SO.
  const gone = resume.unclockedVerdict({ stall: old, activeWindows: { weekly_fable: { percent: 100 } }, now });
  assert.equal(gone.action, 'give_up');
  assert.match(gone.why, /no reset time/);
  assert.match(gone.why, /6 h/);
  // A window that is not this stall's is no help either.
  assert.equal(resume.unclockedVerdict({
    stall: old, activeWindows: { session: { resetsAt: iso } }, now,
  }).action, 'give_up');
  // Nor an unparseable one.
  assert.equal(resume.unclockedVerdict({
    stall: old, activeWindows: { weekly_fable: { resetsAt: 'soon' } }, now,
  }).action, 'give_up');
});

test('the ten-second poll arms for a reset that is NEAR, not for anything stalled', () => {
  const now = Date.now();
  const far = { at: now - 60_000, resetsAt: now + 6 * 86_400_000 };
  const near = { at: now - 60_000, resetsAt: now + 5 * 60_000 };

  assert.equal(resume.pollShouldArm({ stalls: [far], now }), false,
    'six days of listSessions and a transcript tail per session, to watch a clock that cannot move');
  assert.equal(resume.pollShouldArm({ stalls: [near], now }), true);
  assert.equal(resume.pollShouldArm({ stalls: [far, near], now }), true, 'any ONE near reset is enough');

  // The other two reasons to be awake.
  assert.equal(resume.pollShouldArm({ stalls: [far], mode: 'red', now }), true);
  assert.equal(resume.pollShouldArm({ stalls: [far], mode: 'exhausted', now }), true);
  assert.equal(resume.pollShouldArm({ stalls: [far], chatStallsPending: true, now }), true);

  // No clock at all: the backstop has to be able to age it out, so it stays awake.
  assert.equal(resume.pollShouldArm({ stalls: [{ at: now, resetsAt: null }], now }), true);

  // Settled stalls are not stalls.
  assert.equal(resume.pollShouldArm({ stalls: [{ ...near, resumedAt: now }], now }), false);
  assert.equal(resume.pollShouldArm({ stalls: [{ ...near, gaveUpAt: now }], now }), false);
  assert.equal(resume.pollShouldArm({}), false);
});
