'use strict';
// lib/sentinels — the headroom directory the daemon and the bash gate share.
// Pure filesystem, no daemon, no ports (see the note in install-hooks.test.js).

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const sent = require('../lib/sentinels');

function scratch() {
  return fs.mkdtempSync(path.join(os.tmpdir(), `sentinels-${process.pid}-`));
}
function writeHeld(dir, id, body) {
  fs.mkdirSync(path.join(dir, 'held'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'held', id), body);
}

test('arm writes one JSON line, and re-arming keeps the original since', () => {
  const dir = path.join(scratch(), 'headroom'); // also proves it creates the dir
  const first = sent.arm(dir, 'STOP-FABLE', 'weekly_fable 89%', 1_789_459_000_000);
  assert.equal(first.created, true);
  // MILLISECONDS, like every other epoch on /v1/headroom. This was the one
  // seconds field in the payload, sitting beside `resets[].at` and
  // `arbiter.last*At` with no field-name tell.
  assert.equal(first.since, 1_789_459_000_000);

  const body = fs.readFileSync(path.join(dir, 'STOP-FABLE'), 'utf8');
  assert.equal(body, `${JSON.stringify({ reason: 'weekly_fable 89%', since: 1_789_459_000_000 })}\n`);

  // Half an hour later the tick re-asserts the same plan. `since` is what the
  // operator reads as "held since", and what hysteresis measures against — a
  // value that resets every tick says the stop is always four seconds old.
  const again = sent.arm(dir, 'STOP-FABLE', 'weekly_fable 91%', 1_789_460_800_000);
  assert.equal(again.created, false);
  assert.equal(again.since, 1_789_459_000_000);
  assert.equal(again.reason, 'weekly_fable 89%');
});

test('a sentinel file written by an older daemon still reads as a time', () => {
  // `since` used to be seconds. A file left by the previous release must not
  // read as 1970 on the way through /v1/headroom.
  const dir = scratch();
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'STOP'), `${JSON.stringify({ reason: 'old', since: 1_789_459_000 })}\n`);
  assert.deepEqual(sent.state(dir).STOP, { since: 1_789_459_000_000, reason: 'old' });
});

test('touch heartbeats an armed sentinel WITHOUT moving its since', () => {
  // The gate ages a sentinel against its mtime, so the tick has to say "still
  // me" on every pass — otherwise a daemon that dies with STOP armed wedges
  // every Agent/Workflow spawn on the host for half an hour each, with no
  // symptom but sessions that look hung. But `since` is what the operator reads
  // as "held since", so the heartbeat must be the mtime and nothing else.
  const dir = scratch();
  sent.arm(dir, 'STOP', 'session 71%', 1_789_459_000_000);
  const file = path.join(dir, 'STOP');
  const stale = new Date(Date.now() - 3600_000);
  fs.utimesSync(file, stale, stale);

  assert.equal(sent.touch(dir, 'STOP'), true);
  assert.ok(Date.now() - fs.statSync(file).mtimeMs < 5000, 'the mtime is the heartbeat');
  assert.equal(sent.state(dir).STOP.since, 1_789_459_000_000, 'and `since` must NOT move');
  assert.equal(sent.state(dir).STOP.reason, 'session 71%');

  // Nothing armed: a no-op that says so, rather than creating one.
  assert.equal(sent.touch(dir, 'STOP-FABLE'), false);
  assert.equal(fs.existsSync(path.join(dir, 'STOP-FABLE')), false);
  assert.throws(() => sent.touch(dir, 'NOPE'), /unknown sentinel/);
});

test('state reads both sentinels, and absence is null rather than a shape', () => {
  const dir = scratch();
  assert.deepEqual(sent.state(dir), { STOP: null, 'STOP-FABLE': null });

  sent.arm(dir, 'STOP', 'session 71%', 1_789_459_000_000);
  const st = sent.state(dir);
  assert.deepEqual(st.STOP, { since: 1_789_459_000_000, reason: 'session 71%' });
  assert.equal(st['STOP-FABLE'], null);
});

test('a sentinel touched by hand is still armed, with mtime as its since', () => {
  // The operator's escape hatch when the daemon is the thing that is wrong:
  // `touch STOP`. The gate only tests for existence, so the reader must agree —
  // treating an empty file as "not armed" would report a fleet-wide pause as no
  // pause at all.
  const dir = scratch();
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'STOP'), '');
  const st = sent.state(dir).STOP;
  assert.ok(st, 'an empty sentinel is armed');
  assert.equal(st.reason, '');
  assert.ok(Math.abs(st.since - Date.now()) < 5000);
});

test('clear removes it and says whether there was anything to remove', () => {
  const dir = scratch();
  sent.arm(dir, 'STOP', 'x', Date.now());
  assert.equal(sent.clear(dir, 'STOP'), true);
  assert.equal(fs.existsSync(path.join(dir, 'STOP')), false);
  assert.equal(sent.clear(dir, 'STOP'), false);
});

test('only the two known names are writable', () => {
  // The name reaches this module from the arbiter's plan; a name that can be
  // anything is a path that can be anywhere.
  const dir = scratch();
  assert.throws(() => sent.arm(dir, '../../etc/passwd', 'no', Date.now()), /unknown sentinel/);
  assert.throws(() => sent.clear(dir, 'gate.log'), /unknown sentinel/);
});

test('writeFableSessions is one id per line, deduped, junk dropped', () => {
  const dir = scratch();
  const kept = sent.writeFableSessions(dir, [
    'aaaa-1111', 'bbbb-2222', 'aaaa-1111', '', null, 42, 'has space', 'two\nlines',
  ]);
  assert.deepEqual(kept, ['aaaa-1111', 'bbbb-2222']);
  assert.equal(fs.readFileSync(path.join(dir, 'fable-sessions'), 'utf8'), 'aaaa-1111\nbbbb-2222\n');
  // The gate matches whole lines; an id carrying a newline would have forged an
  // extra entry and held a session nobody listed.
  assert.deepEqual(fs.readdirSync(dir), ['fable-sessions'], 'tmp+rename left nothing behind');

  sent.writeFableSessions(dir, []);
  assert.equal(fs.readFileSync(path.join(dir, 'fable-sessions'), 'utf8'), '');
});

test('listHeld maps the gate\'s rows and drops what it cannot trust', () => {
  const dir = scratch();
  const now = 1_789_460_000_000;
  const nowS = Math.floor(now / 1000);
  writeHeld(dir, 'a799b9ac6c215d25e', JSON.stringify({ since: nowS - 30, agent_type: 'workflow-subagent', session_id: 'sess-1' }));
  writeHeld(dir, 'toolu_01Sun', JSON.stringify({ since: nowS - 5, agent_type: 'Agent', session_id: 'sess-2' }));
  writeHeld(dir, 'garbage', '{"since": ');
  writeHeld(dir, 'inflight.tmp', JSON.stringify({ since: nowS, agent_type: 'x', session_id: 'y' }));

  const held = sent.listHeld(dir, now);
  assert.deepEqual(held.map((h) => h.agentId), ['a799b9ac6c215d25e', 'toolu_01Sun'], 'oldest first');
  // The gate is bash — `date +%s` — so its rows arrive in SECONDS and are
  // normalised here, because everything the route emits is milliseconds.
  assert.deepEqual(held[0], {
    agentId: 'a799b9ac6c215d25e', agentType: 'workflow-subagent', sessionId: 'sess-1', since: now - 30_000,
  });
  assert.equal(held[1].since, now - 5_000);

  // And a row already written in ms passes through unchanged.
  writeHeld(dir, 'inms', JSON.stringify({ since: now - 10_000, agent_type: 'Agent', session_id: 'sess-3' }));
  assert.equal(sent.listHeld(dir, now).find((h) => h.agentId === 'inms').since, now - 10_000);
});

test('listHeld forgets a row the gate never got to delete', () => {
  // A SIGKILL on the CLI's own hook timeout runs no trap, so the held file
  // outlives the hold. Rendering it forever tells the operator a spawn is
  // waiting that has in fact been running for hours.
  const dir = scratch();
  const now = 1_789_460_000_000;
  const nowS = Math.floor(now / 1000);
  writeHeld(dir, 'stale', JSON.stringify({ since: nowS - 3 * 3600, agent_type: 'x', session_id: 's' }));
  writeHeld(dir, 'fresh', JSON.stringify({ since: nowS - 60, agent_type: 'x', session_id: 's' }));
  assert.deepEqual(sent.listHeld(dir, now).map((h) => h.agentId), ['fresh']);
});

test('listHeld on a directory the gate has never run in is empty, not a throw', () => {
  assert.deepEqual(sent.listHeld(scratch(), Date.now()), []);
  assert.deepEqual(sent.listHeld('/nonexistent/headroom', Date.now()), []);
});
