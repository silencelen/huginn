'use strict';
// When a sentinel was ARMED, as /v1/headroom reports it (`sentinels.*.since`),
// for the one kind of sentinel that carries no time of its own: the operator's
// `touch $HEADROOM_DIR/STOP`.
//
// The tick heartbeats every armed sentinel — the gate ages a hold by its mtime,
// so a dead daemon's hold lapses — and that includes a hand-armed one. Reading
// the mtime as `since` therefore reported "armed seconds ago" for a hold that
// had been in place all night. Pure filesystem, no daemon, no ports.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const sent = require('../lib/sentinels');

function scratch() {
  return fs.mkdtempSync(path.join(os.tmpdir(), `sentinel-armed-${process.pid}-`));
}

/**
 * Does this filesystem keep a birth time that a utimes() leaves alone? ext4,
 * xfs and btrfs do; one that does not falls back to the mtime by design, and
 * there is nothing for these tests to tell apart.
 */
function birthTimeKept(dir) {
  const probe = path.join(dir, 'probe');
  fs.writeFileSync(probe, '');
  const born = fs.statSync(probe).birthtimeMs;
  const later = new Date(Date.now() + 3600_000);
  fs.utimesSync(probe, later, later);
  const after = fs.statSync(probe).birthtimeMs;
  fs.unlinkSync(probe);
  return born > 0 && after === born;
}

test('a hand-armed STOP keeps the time it was armed through a night of heartbeats', (t) => {
  const dir = scratch();
  if (!birthTimeKept(dir)) return t.skip('this filesystem records no birth time');
  const file = path.join(dir, 'STOP');
  fs.writeFileSync(file, '');                       // the operator's `touch STOP`
  const armed = sent.state(dir).STOP.since;
  assert.ok(Math.abs(armed - Date.now()) < 5000, 'armed just now');

  // Eight hours of ticks, each touching the file so the gate keeps holding.
  for (let h = 1; h <= 8; h++) assert.equal(sent.touch(dir, 'STOP', armed + h * 3600_000), true);
  assert.ok(fs.statSync(file).mtimeMs >= armed + 8 * 3600_000 - 1000, 'the heartbeat did move the mtime');

  const st = sent.state(dir).STOP;
  assert.equal(st.since, armed, 'since is when the hold began, not the last tick');
  assert.equal(st.by, null, 'and it is still nobody\'s to reap');
});

test('re-touching a hold that is already armed does not restart it', (t) => {
  const dir = scratch();
  if (!birthTimeKept(dir)) return t.skip('this filesystem records no birth time');
  const file = path.join(dir, 'STOP-FABLE');
  fs.writeFileSync(file, '');
  const armed = sent.state(dir)['STOP-FABLE'].since;
  // A second `touch` on the same file: the hold has been continuous throughout.
  const later = new Date(armed + 2 * 3600_000);
  fs.utimesSync(file, later, later);
  assert.equal(sent.state(dir)['STOP-FABLE'].since, armed);
});

test('an mtime the operator set into the past is still believed', () => {
  // `touch -d '8 hours ago' STOP` says when the hold is meant to have begun.
  // The earlier of birth and mtime, so that claim is not overruled by "now".
  const dir = scratch();
  const file = path.join(dir, 'STOP');
  fs.writeFileSync(file, '');
  const past = new Date(Date.now() - 8 * 3600_000);
  fs.utimesSync(file, past, past);
  assert.equal(sent.state(dir).STOP.since, Math.floor(fs.statSync(file).mtimeMs));
});

test('a sentinel the daemon armed still reports the since in its body', () => {
  const dir = scratch();
  sent.arm(dir, 'STOP', 'session 71%', 1_789_459_000_000);
  sent.touch(dir, 'STOP', Date.now() + 3600_000);
  assert.equal(sent.state(dir).STOP.since, 1_789_459_000_000);
});
