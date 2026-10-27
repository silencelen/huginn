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
