'use strict';
// A usage hold and the login it was armed for.
//
// Seen live 2026-11-04: STOP armed `{"reason":"weekly_all 99%","by":"appd"}`
// while one login sat at 100% of its week. The sessions then ran on a second
// login — live on /v1/headroom at 54% — and since nothing recorded WHICH login
// armed the hold, the week's band (clear below 50%) was judged against the
// second login's week and held until that login's own reset, days later. Every
// Agent/Workflow launch on the host waited out the gate's 1800 s timeout, and
// the five researchers of a security scan's workflow were interrupted and
// restarted four times. The scan produced nothing.
//
// The daemon's REAL writeSentinels and its headroom.json load and save are
// lifted out of huginn-appd.js (ladder-lift) and run over a scratch data
// directory with the real lib/headroom plan: no daemon, no port, no tmux.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const headroomLib = require('../lib/headroom');
const sentinelsLib = require('../lib/sentinels');
const keepAwakeLib = require('../lib/keepawake');
const { lift } = require('./ladder-lift');

const S = headroomLib.defaults();          // stop 70, stopFable 88, clear 50
const win = (percent) => ({ percent, resetsAt: null, severity: 'normal', label: '' });
const A = 'acct-a';
const B = 'acct-b';

function scratch() {
  return fs.mkdtempSync(path.join(os.tmpdir(), `sentinel-account-${process.pid}-`));
}

/** The daemon's own pieces over one data directory. A second call is a restart. */
function daemon(dataDir) {
  const lines = [];
  const { writeSentinels, hstate, saveHeadroomState } = lift(
    ['writeSentinels', 'hstate', 'saveHeadroomState', 'normalizeHeadroomState'],
    {
      fs, path, sentinelsLib, keepAwakeLib,
      DATA_DIR: dataDir,
      HEADROOM_DIR: path.join(dataDir, 'headroom'),
      HEADROOM_STATE_FILE: path.join(dataDir, 'headroom.json'),
      log: (...a) => lines.push(a.join(' ')),
    },
    { extra: 'let headroomStateCache = null;' },
  );
  return { writeSentinels, hstate, saveHeadroomState, lines, dir: path.join(dataDir, 'headroom') };
}

/** One headroom pass's sentinel half: the plan for `login`'s windows, then the files. */
function pass(d, state, windows, login) {
  const plan = headroomLib.sentinelPlan(windows, S, state.sentinels, login);
  d.writeSentinels(plan, state, S);
  return plan;
}

test('another login with room clears the hold at once, and the journal says whose it was', () => {
  const d = daemon(scratch());
  const state = d.hstate();
  state.accounts = { [A]: { email: 'alex@example.net', live: false }, [B]: { email: 'sam@example.net', live: true } };

  pass(d, state, { session: win(10), weekly_all: win(99) }, A);
  assert.equal(sentinelsLib.state(d.dir).STOP.account, A, 'the file names the login it was armed for');
  assert.equal(state.sentinels.STOP.account, A, 'and so does the record the next plan reads');

  // The host now runs on B at 54%: inside the band A armed, at no threshold of B's.
  pass(d, state, { session: win(10), weekly_all: win(54) }, B);
  assert.equal(sentinelsLib.state(d.dir).STOP, null, 'cleared at once, not at B\'s reset');
  assert.equal(state.sentinels.STOP, null);
  assert.ok(d.lines.includes('headroom: cleared STOP — armed for alex@example.net, the active account has room'),
    d.lines.join('\n'));
});

test('another login out of room itself keeps the hold, re-armed as its own', () => {
  const d = daemon(scratch());
  const state = d.hstate();
  pass(d, state, { weekly_all: win(99) }, A);
  const before = sentinelsLib.state(d.dir).STOP;

  // B's 5-hour window is past the hold level; its week is ordinary.
  const plan = pass(d, state, { session: win(75), weekly_all: win(60) }, B);
  assert.equal(plan.STOP, true);
  const after = sentinelsLib.state(d.dir).STOP;
  assert.equal(after.account, B);
  assert.equal(after.reason, 'session 75%', 'its band is now B\'s 5-hour window, not A\'s week');
  assert.ok(after.since >= before.since);
  assert.deepEqual({ ...state.sentinels.STOP }, { since: after.since, reason: 'session 75%', account: B });
  assert.ok(d.lines.includes('headroom: re-armed STOP for acct-b (session 75%) — it was armed for acct-a'),
    d.lines.join('\n'));

  // B's window resets. Had the hold kept A's "weekly_all 99%", B's 60% week —
  // inside a WEEK's band — would have held it for the rest of B's week.
  pass(d, state, { session: win(5), weekly_all: win(60) }, B);
  assert.equal(sentinelsLib.state(d.dir).STOP, null);
});

test('across a switch the daemon\'s own STOP-FABLE clears and a hand-armed STOP stays', () => {
  const d = daemon(scratch());
  const state = d.hstate();
  fs.mkdirSync(d.dir, { recursive: true });
  fs.writeFileSync(path.join(d.dir, 'STOP'), '');              // the operator's `touch STOP`
  pass(d, state, { weekly_all: win(95), weekly_fable: win(95) }, A);
  assert.equal(sentinelsLib.state(d.dir)['STOP-FABLE'].account, A);
  assert.equal(fs.readFileSync(path.join(d.dir, 'STOP'), 'utf8'), '', 'a hand-armed file is never rewritten');

  // B has room, and sits inside both bands.
  d.lines.length = 0;
  pass(d, state, { session: win(10), weekly_all: win(60), weekly_fable: win(60) }, B);
  assert.equal(sentinelsLib.state(d.dir)['STOP-FABLE'], null, 'the daemon\'s hold was A\'s, and B has room');
  assert.ok(fs.existsSync(path.join(d.dir, 'STOP')), 'the operator\'s pause button is never cleared by a switch');
  assert.equal(sentinelsLib.state(d.dir).STOP.by, null);
  assert.ok(d.lines.includes('headroom: STOP is armed by hand; leaving it alone'), d.lines.join('\n'));
  assert.ok(!d.lines.some((l) => /^headroom: cleared STOP(?!-FABLE)/.test(l)), d.lines.join('\n'));
});

test('a restart keeps the login a hold was armed for, and with it the rule', () => {
  const dataDir = scratch();
  const first = daemon(dataDir);
  const state = first.hstate();
  pass(first, state, { weekly_all: win(99) }, A);
  first.saveHeadroomState(state);

  // A new process: nothing in memory, headroom.json and the sentinel files on disk.
  const second = daemon(dataDir);
  const again = second.hstate();
  assert.equal(again.sentinels.STOP.account, A, 'headroom.json carries the login');
  // The same login inside the band still holds. A record that came back naming
  // no login would have been decided again here, and released.
  assert.equal(pass(second, again, { session: win(10), weekly_all: win(54) }, A).STOP, true);
  assert.ok(sentinelsLib.state(second.dir).STOP);
  // Another login with room: cleared at once.
  pass(second, again, { session: win(10), weekly_all: win(54) }, B);
  assert.equal(sentinelsLib.state(second.dir).STOP, null);
  assert.ok(second.lines.includes('headroom: cleared STOP — armed for acct-a, the active account has room'),
    second.lines.join('\n'));
});
