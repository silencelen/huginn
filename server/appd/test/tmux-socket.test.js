'use strict';
// The tmux socket swept from under a living server (2026-10-27): a /tmp sweep
// unlinked /tmp/tmux-0/default while huginn-tmux.service kept running with every
// session. The daemon read `tmux ls`'s "No such file or directory" as "no
// sessions", dropped 13 registry rows, archived two projects for a "gone" lead and
// started a second, empty server inside itself. These pin the decision helpers:
// the unit's MainPID is what tells an unlinked socket from a fresh boot.

const { test } = require('node:test');
const assert = require('node:assert');
const s = require('../lib/tmux-socket');

// Verbatim tmux 3.x wording for the two no-server shapes, plus the failures
// that are NOT a missing server.
const NO_FILE = 'error connecting to /tmp/tmux-0/default (No such file or directory)\n';
const STALE = 'no server running on /tmp/tmux-0/default\n';
const REFUSED = 'error connecting to /tmp/tmux-0/default (Connection refused)\n';
const EAGAIN = 'fork failed: Resource temporarily unavailable\n';

test('noServer recognises the three socket-level failures and nothing else', () => {
  assert.equal(s.noServer(NO_FILE), true);
  assert.equal(s.noServer(STALE), true);
  assert.equal(s.noServer(REFUSED), true);
  assert.equal(s.noServer(EAGAIN), false);
  assert.equal(s.noServer(''), false);
  assert.equal(s.noServer(null), false);
});

test('unitMainPid reads systemctl --value and MainPID= forms; 0 and junk are null', () => {
  assert.equal(s.unitMainPid('1284\n'), 1284);
  assert.equal(s.unitMainPid('MainPID=1284\n'), 1284);
  assert.equal(s.unitMainPid('0\n'), null);
  assert.equal(s.unitMainPid('MainPID=0'), null);
  assert.equal(s.unitMainPid(''), null);
  assert.equal(s.unitMainPid('Unit huginn-tmux.service could not be found.'), null);
  assert.equal(s.unitMainPid(undefined), null);
});

test('socketLost: no socket + a living unit is the swept-socket case; a fresh boot is not', () => {
  assert.equal(s.socketLost(NO_FILE, 1284), true, 'the 2026-10-27 incident');
  assert.equal(s.socketLost(STALE, 1284), true, 'a stale file left behind reads the same');
  assert.equal(s.socketLost(NO_FILE, null), false, 'fresh boot: the unit has no pid yet');
  assert.equal(s.socketLost(NO_FILE, 0), false);
  assert.equal(s.socketLost(EAGAIN, 1284), false, 'a fork failure is a different failure to observe');
});

// ---- 2026-11-06: an empty host is OBSERVED, never inferred ------------------
//
// A loaded host failed one listing the no-server way while the server and all
// 23 sessions were fine, and `systemctl show` failed too. The unit's pid read as
// "none" for every "could not tell", so the daemon answered an empty host and
// one reconcile tick archived five projects. These pin the three-way answer and
// the verdict built on it. The run() results are shaped as execFile hands them.

/** A run() result for a child that exited non-zero, or was killed by the 10 s timeout. */
function failed(stderr, { code = 1, killed = false } = {}) {
  const err = new Error('Command failed');
  err.code = killed ? null : code;
  if (killed) { err.killed = true; err.signal = 'SIGTERM'; }
  return { err, stdout: '', stderr };
}

test('unitAnswer: systemd ANSWERING is a pid or 0; anything else is null, never 0', () => {
  assert.equal(s.unitAnswer({ err: null, stdout: '1284\n', stderr: '' }).pid, 1284);
  assert.equal(s.unitAnswer({ err: null, stdout: 'MainPID=1284\n', stderr: '' }).pid, 1284);
  // A stopped unit, and a unit systemd has never heard of (LoadState=not-found),
  // both answer 0: that IS an answer.
  assert.equal(s.unitAnswer({ err: null, stdout: '0\n', stderr: '' }).pid, 0);
  assert.equal(s.unitAnswer({ err: null, stdout: 'MainPID=0', stderr: '' }).pid, 0);

  const bus = s.unitAnswer(failed('Failed to connect to bus: Connection timed out\n'));
  assert.equal(bus.pid, null, 'a systemd that could not be asked is not a unit with no pid');
  assert.match(bus.why, /Connection timed out/);
  const slow = s.unitAnswer(failed('', { killed: true }));
  assert.equal(slow.pid, null, 'the 10 s timeout on a loaded host (2026-11-06)');
  assert.match(slow.why, /timed out/);
  assert.equal(s.unitAnswer({ err: null, stdout: '', stderr: '' }).pid, null, 'no answer at all');
  assert.equal(s.unitAnswer({ err: null, stdout: 'Unit huginn-tmux.service could not be found.', stderr: '' }).pid, null);
  assert.equal(s.unitAnswer(undefined).pid, null);
});

test('listingVerdict: empty needs tmux\'s no-server words AND a unit with no main process', () => {
  assert.equal(s.listingVerdict(NO_FILE, { pid: 0 }), 'empty', 'a fresh boot');
  assert.equal(s.listingVerdict(STALE, { pid: 0 }), 'empty');
  assert.equal(s.listingVerdict(NO_FILE, { pid: 1284 }), 'lost', 'the 2026-10-27 swept socket');
  assert.equal(s.listingVerdict(REFUSED, { pid: 1284 }), 'lost');

  assert.equal(s.listingVerdict(NO_FILE, { pid: null }), 'unknown', 'THE 2026-11-06 INCIDENT: systemd could not be asked');
  assert.equal(s.listingVerdict(STALE, { pid: null }), 'unknown');
  assert.equal(s.listingVerdict(NO_FILE, null), 'unknown', 'systemd not asked at all');
  assert.equal(s.listingVerdict(EAGAIN, { pid: 0 }), 'unknown', 'a fork that failed says nothing about sessions');
  assert.equal(s.listingVerdict('', { pid: 0 }), 'unknown');
  // The daemon's old test was /no server running|no such file or directory/: any
  // stderr naming ENOENT read as "no server". tmux's own words are narrower.
  assert.equal(s.listingVerdict('couldn\'t create directory /tmp/tmux-0 (No such file or directory)\n', { pid: 0 }),
    'unknown', 'ENOENT about something else is not tmux saying there is no server');
});

test('probeVerdict: only a server saying "can\'t find session" proves a session gone', () => {
  // Verbatim tmux 3.6 for `has-session -t =<name>`.
  assert.equal(s.probeVerdict({ err: null, stdout: '', stderr: '' }).gone, false, 'exit 0: it exists');
  const gone = s.probeVerdict(failed('can\'t find session: build\n'));
  assert.equal(gone.gone, true);
  assert.match(gone.why, /can't find session/);

  // Every way of not reaching a server holds: under the load that makes a
  // listing lie, these are exactly the answers a probe gets.
  for (const stderr of [NO_FILE, STALE, REFUSED, EAGAIN, '']) {
    assert.equal(s.probeVerdict(failed(stderr)).gone, null, JSON.stringify(stderr));
  }
  const slow = s.probeVerdict(failed('', { killed: true }));
  assert.equal(slow.gone, null, 'a probe killed by the timeout');
  assert.match(slow.why, /timed out/);
  assert.equal(s.probeVerdict(failed('can\'t find session: no server running\n')).gone, null,
    'a session name that reads like a connection error is not trusted either way');
});
