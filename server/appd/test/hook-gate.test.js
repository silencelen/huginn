'use strict';
// hooks/huginn-headroom-gate, end to end: the real bash script, real payloads on
// stdin, a scratch HUGINN_HEADROOM_DIR.
//
// SAFETY: HUGINN_HEADROOM_DIR is a tmpdir in every case, so no test can arm or
// clear a sentinel in /var/lib/huginn-appd — arming the real STOP would pause
// every agent on the host. Nothing here reads or writes any settings file, and
// no socket is bound (see the note in install-hooks.test.js).
//
// HUGINN_GATE_TIMEOUT is 60 in these tests rather than the installed 1800, which
// puts the self-release deadline at 60-30 = 30 s. That arithmetic is the whole
// safety margin against the CLI's SIGKILL, so it is asserted rather than assumed.
// 60 is also the FLOOR: anything under it lands the deadline in the past (or
// within the margin) and the gate lets every spawn through with waited=0 — a
// pause button silently switched off by a number that passed validation.

const { test } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const GATE = path.join(__dirname, '..', 'hooks', 'huginn-headroom-gate');
const SESSION = 'sess-fable-1';

// Verbatim shapes from the spike (gate-payloads.jsonl), trimmed of the fields the
// gate does not read — plus, on the PreToolUse one, two decoy "session_id"s (see
// below).
const SUBAGENT_PAYLOAD = {
  session_id: SESSION,
  transcript_path: `/root/.claude/projects/-root-netplan/${SESSION}.jsonl`,
  cwd: '/root/netplan',
  agent_id: 'a799b9ac6c215d25e',
  agent_type: 'workflow-subagent',
  hook_event_name: 'SubagentStart',
};
const PRETOOL_PAYLOAD = {
  session_id: SESSION,
  transcript_path: `/root/.claude/projects/-root-netplan/${SESSION}.jsonl`,
  cwd: '/root/netplan',
  hook_event_name: 'PreToolUse',
  tool_name: 'Agent',
  tool_input: {
    description: 'check a thing',
    // Two decoys. The first is what a prompt can really do: quote the key at us
    // inside a string, where JSON escaping (\\"session_id\\") already makes it
    // unmatchable. The second is synthetic — a nested object whose own key is
    // spelled session_id, unescaped like every real key — and it is the one the
    // rule exists for: the gate takes the FIRST occurrence, so the envelope's
    // field wins over anything the payload carries deeper down. A sed that took
    // the last match would gate the wrong session, and would say so in the log
    // while doing it.
    prompt: 'Read the file that says "session_id":"sess-decoy-8" and report it.',
    subagent_type: 'general-purpose',
    context: { session_id: 'sess-decoy-9' },
  },
  tool_use_id: 'toolu_01SunZe4nM1uasX1otr3WZgC',
};

function scratch() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `hookgate-${process.pid}-`));
  fs.mkdirSync(path.join(dir, 'headroom'), { recursive: true });
  return path.join(dir, 'headroom');
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startGate(dir, payload, env = {}) {
  const child = spawn(GATE, [], {
    env: {
      ...process.env,
      HUGINN_HEADROOM_DIR: dir,
      HUGINN_GATE_TIMEOUT: '60',
      ...env,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', (b) => { stderr += b; });
  child.stdin.end(JSON.stringify(payload));
  const t0 = Date.now();
  const done = new Promise((resolve) => {
    child.on('exit', (code) => resolve({ code, ms: Date.now() - t0, stderr }));
  });
  return { child, done, t0 };
}

async function waitFor(fn, timeoutMs = 5000) {
  const until = Date.now() + timeoutMs;
  for (;;) {
    const v = fn();
    if (v) return v;
    if (Date.now() > until) return null;
    await sleep(50); // eslint-disable-line no-await-in-loop
  }
}

function logLines(dir) {
  try {
    return fs.readFileSync(path.join(dir, 'gate.log'), 'utf8').trim().split('\n').filter(Boolean);
  } catch {
    return [];
  }
}
function events(dir) {
  return logLines(dir).map((l) => (l.match(/ event=(\S+)/) || [])[1]);
}
function heldNames(dir) {
  try {
    return fs.readdirSync(path.join(dir, 'held')).filter((n) => !n.endsWith('.tmp'));
  } catch {
    return [];
  }
}

test('no sentinel: through in well under a second, holding nothing', async () => {
  const dir = scratch();
  // Warm the spawn path first. This is the only case that asserts wall time on a
  // single run, and the FIRST fork+exec of a shell out of a node process on a
  // loaded host pays for page-cache misses that have nothing to do with the
  // gate — which is how a real timing assertion turns into a flaky one.
  await startGate(dir, SUBAGENT_PAYLOAD).done;

  // ⚠ AND `ms < 1000` ON ITS OWN IS NOT A FACT ABOUT THE GATE. It is a fact
  // about the host: this box runs parallel gradle builds at load 11-23 on eight
  // cores, and the gate is ~30 fork+execs of coreutils, so the wall time of a
  // run that waits for NOTHING still moves with whatever else is running. That
  // is what failed a release gate once, on a gate that was behaving perfectly.
  //
  // So the budget is measured, here, now, under whatever load this run has:
  // three more no-sentinel runs, median (one unlucky sample cannot move it), and
  // the assertion is 5x that or a second, whichever is larger. It still proves
  // the thing that matters — a gate that WAITED would sit for a POLL (2 s) or to
  // its deadline (30 s), which is orders out of this budget at any load — and it
  // is not weaker than the old bound on an idle host, where it IS the old bound.
  const base = [];
  for (let i = 0; i < 3; i++) base.push((await startGate(dir, SUBAGENT_PAYLOAD).done).ms);
  const median = base.slice().sort((a, b) => a - b)[1];
  const budget = Math.max(1000, 5 * median);

  fs.rmSync(path.join(dir, 'gate.log'));
  const { done } = startGate(dir, SUBAGENT_PAYLOAD);
  const { code, ms, stderr } = await done;
  assert.equal(code, 0, `stderr: ${stderr}`);
  assert.ok(ms < budget,
    `gate took ${ms}ms with nothing armed — budget ${budget}ms (baseline runs ${base.join('/')}ms)`);
  assert.deepEqual(heldNames(dir), [], 'a spawn that never waited is not a held spawn');
  assert.deepEqual(events(dir), ['start', 'release']);
  // ⚠ `waited=` IS WHOLE SECONDS OF WALL TIME (`date +%s` minus START), not a
  // count of polls — so a run that merely crosses a second boundary reports
  // `waited=1` having waited on nothing at all, and pinning the literal 0 was a
  // second timing assertion wearing a string's clothes. It cost a release gate
  // once. The id and type are still pinned exactly; the wait is bounded by this
  // run's OWN measured wall time, which is what "it did not wait" means.
  const line = logLines(dir)[1];
  assert.match(line, /event=release id=a799b9ac6c215d25e type=workflow-subagent waited=\d+/);
  const waited = Number((line.match(/waited=(\d+)/) || [])[1]);
  assert.ok(waited <= Math.ceil(ms / 1000),
    `the gate says it waited ${waited}s while the whole run took ${ms}ms — it waited on something`);
});

test('STOP holds every spawn, and the release follows the rm within a poll', async () => {
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '{"reason":"session 71%","since":1789459000}\n');
  const { done } = startGate(dir, SUBAGENT_PAYLOAD);

  const held = await waitFor(() => (heldNames(dir).length ? heldNames(dir) : null));
  assert.deepEqual(held, ['a799b9ac6c215d25e'], 'the held row is what /v1/headroom reads');
  const row = JSON.parse(fs.readFileSync(path.join(dir, 'held', held[0]), 'utf8'));
  assert.equal(row.agent_type, 'workflow-subagent');
  assert.equal(row.session_id, SESSION);
  assert.ok(row.since > 1_700_000_000, 'since is epoch seconds');

  await sleep(500);
  assert.equal((await Promise.race([done, sleep(0).then(() => 'alive')])), 'alive', 'still holding');

  const rmAt = Date.now();
  fs.unlinkSync(path.join(dir, 'STOP'));
  const { code } = await done;
  const lag = Date.now() - rmAt;
  assert.equal(code, 0);
  assert.ok(lag <= 2500, `released ${lag}ms after the sentinel cleared`);
  assert.deepEqual(heldNames(dir), [], 'the held row goes with the hold');
  assert.deepEqual(events(dir), ['start', 'release']);
  assert.match(logLines(dir)[1], / waited=[1-9]\d*$/, 'the wait is recorded, not zero');
});

test('STOP-FABLE binds a session the daemon listed as Fable', async () => {
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP-FABLE'), '{"reason":"weekly_fable 89%","since":1789459000}\n');
  fs.writeFileSync(path.join(dir, 'fable-sessions'), `other-session\n${SESSION}\n`);
  const { done } = startGate(dir, SUBAGENT_PAYLOAD);

  assert.ok(await waitFor(() => heldNames(dir).length), 'a Fable session waits out STOP-FABLE');
  fs.unlinkSync(path.join(dir, 'STOP-FABLE'));
  const { code } = await done;
  assert.equal(code, 0);
});

test('STOP-FABLE does not bind a session that is not on the list', async () => {
  // The Opus session keeps working while the Fable ones wait — the entire point
  // of having two sentinels. The payload carries no model, so this list is the
  // only thing standing between "graceful" and "the whole host stops".
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP-FABLE'), '{"reason":"weekly_fable 89%","since":1789459000}\n');
  fs.writeFileSync(path.join(dir, 'fable-sessions'), 'some-other-session\n');
  const { done } = startGate(dir, SUBAGENT_PAYLOAD);
  const { code, ms } = await done;
  assert.equal(code, 0);
  assert.ok(ms < 1000, `held for ${ms}ms on a sentinel that does not bind it`);
  assert.deepEqual(heldNames(dir), []);

  // And a missing list is not a match either: no list, no Fable sessions.
  const dir2 = scratch();
  fs.writeFileSync(path.join(dir2, 'STOP-FABLE'), '');
  const second = await startGate(dir2, SUBAGENT_PAYLOAD).done;
  assert.equal(second.code, 0);
  assert.ok(second.ms < 1000);
});

test('the gate self-releases at timeout-30, before the CLI would SIGKILL it', async () => {
  // 60 - 30 = 30 s. A hook killed by the CLI leaves the spawn ALLOWED, silently,
  // and its held row orphaned because SIGKILL runs no trap — so the gate has to
  // be the one that ends the hold, every time.
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '');
  const { done } = startGate(dir, SUBAGENT_PAYLOAD);
  const { code, ms } = await done;
  assert.equal(code, 0, 'a gate that gives up still ALLOWS the spawn');
  assert.ok(ms >= 29_000 && ms <= 34_000, `self-released after ${ms}ms, expected ~30s`);
  assert.ok(fs.existsSync(path.join(dir, 'STOP')), 'and it did not clear the sentinel itself');
  assert.deepEqual(heldNames(dir), [], 'the held row is removed on the way out');
  assert.deepEqual(events(dir), ['start', 'timeout-release']);
  assert.match(logLines(dir)[1], / waited=(29|3[0-4])\b/);
});

test('a HUGINN_GATE_TIMEOUT under the floor does not silently disable the gate', async () => {
  // DEADLINE is START + GATE_TIMEOUT - 30, so anything under 30 lands in the
  // past: the first iteration logged `timeout-release waited=0` and let the
  // spawn straight through. `0` passed the digits-only validator too. It fails
  // open, which is the right direction, and unreadably, which is not.
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '{"reason":"session 71%"}\n');
  const { done } = startGate(dir, SUBAGENT_PAYLOAD, { HUGINN_GATE_TIMEOUT: '5', HUGINN_GATE_POLL: '1' });
  await waitFor(() => heldNames(dir).length === 1);
  await sleep(1500);
  assert.deepEqual(heldNames(dir), ['a799b9ac6c215d25e'], 'the gate released instantly on a sub-floor timeout');
  fs.rmSync(path.join(dir, 'STOP'));
  const { code, ms } = await done;
  assert.equal(code, 0);
  assert.ok(ms >= 1000, `released after ${ms}ms — it never actually held`);
  assert.deepEqual(events(dir), ['start', 'release']);
});

test('a sentinel nobody has heartbeaten is ABANDONED, not obeyed', async () => {
  // `arm` is idempotent and deliberately does not move `since`, so an armed
  // sentinel had no expiry and nothing cleared one on shutdown: a daemon that
  // died with STOP up blocked every Agent/Workflow spawn for GATE_TIMEOUT-30
  // seconds each, and the only symptom was sessions that looked hung. The tick
  // now touches what it asserts.
  const dir = scratch();
  const stop = path.join(dir, 'STOP');
  fs.writeFileSync(stop, '{"reason":"session 71%","since":1789459000}\n');
  const old = new Date(Date.now() - 3600_000);
  fs.utimesSync(stop, old, old);
  const { done } = startGate(dir, SUBAGENT_PAYLOAD, { HUGINN_GATE_STALE_S: '60' });
  const { code, ms } = await done;
  assert.equal(code, 0);
  assert.ok(ms < 2000, `a dead daemon's sentinel held the spawn for ${ms}ms`);
  assert.ok(fs.existsSync(stop), 'the gate does not clear somebody else\'s sentinel');
  assert.deepEqual(heldNames(dir), []);
  // And it SAYS which kind of release it was: a stale sentinel is an incident,
  // a cleared one is routine.
  assert.deepEqual(events(dir), ['start', 'stale-release']);
});

test('a heartbeaten sentinel still holds, however old its `since` is', async () => {
  const dir = scratch();
  const stop = path.join(dir, 'STOP');
  // Armed last night, touched a moment ago — exactly what a long hold looks like.
  fs.writeFileSync(stop, '{"reason":"weekly_fable 96%","since":1789459000}\n');
  const { done } = startGate(dir, SUBAGENT_PAYLOAD, { HUGINN_GATE_STALE_S: '60', HUGINN_GATE_POLL: '1' });
  await waitFor(() => heldNames(dir).length === 1);
  await sleep(1200);
  assert.deepEqual(heldNames(dir), ['a799b9ac6c215d25e'], 'a live hold was dropped');
  fs.rmSync(stop);
  const { code } = await done;
  assert.equal(code, 0);
  assert.deepEqual(events(dir), ['start', 'release']);
});

test('gate.log rotates at 1 MB, one generation kept', async () => {
  const dir = scratch();
  const filler = `${'x'.repeat(199)}\n`.repeat(5300); // ~1.06 MB
  fs.writeFileSync(path.join(dir, 'gate.log'), filler);
  assert.ok(fs.statSync(path.join(dir, 'gate.log')).size > 1024 * 1024);

  const { code } = await startGate(dir, SUBAGENT_PAYLOAD).done;
  assert.equal(code, 0);
  assert.equal(fs.readFileSync(path.join(dir, 'gate.log.1'), 'utf8').length, filler.length);
  const after = fs.statSync(path.join(dir, 'gate.log')).size;
  assert.ok(after < 1000, `rotated log restarted small, got ${after} bytes`);
  assert.deepEqual(events(dir), ['start', 'release']);
});

test('both payload shapes parse: SubagentStart by agent_id, PreToolUse by tool_use_id', async () => {
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '');
  const a = startGate(dir, SUBAGENT_PAYLOAD);
  const b = startGate(dir, PRETOOL_PAYLOAD);

  const names = await waitFor(() => (heldNames(dir).length === 2 ? heldNames(dir).sort() : null));
  assert.deepEqual(names, ['a799b9ac6c215d25e', 'toolu_01SunZe4nM1uasX1otr3WZgC']);

  const pre = JSON.parse(fs.readFileSync(path.join(dir, 'held', 'toolu_01SunZe4nM1uasX1otr3WZgC'), 'utf8'));
  // PreToolUse has no agent_type; the tool name is what the operator needs to see.
  assert.equal(pre.agent_type, 'Agent');
  // Neither decoy inside tool_input became the session.
  assert.equal(pre.session_id, SESSION);

  fs.unlinkSync(path.join(dir, 'STOP'));
  assert.equal((await a.done).code, 0);
  assert.equal((await b.done).code, 0);
  assert.deepEqual(heldNames(dir), []);
});

test('an id that tries to escape held/ is stripped, not obeyed', async () => {
  // The id is payload-controlled and becomes a filename.
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '');
  const evil = { ...SUBAGENT_PAYLOAD, agent_id: '../../../tmp/huginn-gate-escape' };
  const { done } = startGate(dir, evil);
  const names = await waitFor(() => (heldNames(dir).length ? heldNames(dir) : null));
  assert.deepEqual(names, ['tmphuginn-gate-escape']);
  assert.equal(fs.existsSync('/tmp/huginn-gate-escape'), false);
  fs.unlinkSync(path.join(dir, 'STOP'));
  assert.equal((await done).code, 0);
});

test('the command install-hooks writes carries the sentinel dir to the gate', async () => {
  // The other half of #28, end to end: the CLI runs a hook's `command` through a
  // shell with the daemon's environment nowhere in sight, so the ONLY way a
  // relocated sentinel directory reaches the gate is the command string itself.
  // Driven here exactly as the CLI would drive it — `sh -c <command>` with no
  // HUGINN_HEADROOM_DIR in the environment — against a sentinel in the bound dir.
  const { commandFor } = require('../install-hooks');
  const dir = scratch();
  fs.writeFileSync(path.join(dir, 'STOP'), '{"reason":"session 71%","since":1789459000}\n');

  const env = { ...process.env, HUGINN_GATE_TIMEOUT: '60' };
  delete env.HUGINN_HEADROOM_DIR;
  const child = spawn('sh', ['-c', commandFor(GATE, dir)], { env, stdio: ['pipe', 'pipe', 'pipe'] });
  child.stdin.end(JSON.stringify(SUBAGENT_PAYLOAD));
  const done = new Promise((resolve) => child.on('exit', (code) => resolve(code)));

  assert.ok(await waitFor(() => heldNames(dir).length),
    'the bare command reads its compiled-in default and releases every spawn at waited=0');
  fs.unlinkSync(path.join(dir, 'STOP'));
  assert.equal(await done, 0);
  assert.deepEqual(events(dir), ['start', 'release']);

  // And the failure this exists to stop, with the mismatch made safe: a command
  // bound to some OTHER directory ignores the armed sentinel entirely, releases
  // at waited=0 and writes its log where nobody is looking. That is exactly what
  // a bare command did under HUGINN_APPD_DATA — it is just that the directory it
  // read was the compiled-in /var/lib/huginn-appd/headroom, which no test may
  // touch (see the SAFETY note at the top).
  fs.writeFileSync(path.join(dir, 'STOP'), '{"reason":"session 71%","since":1789459000}\n');
  const elsewhere = scratch();
  const stray = spawn('sh', ['-c', commandFor(GATE, elsewhere)], { env, stdio: ['pipe', 'pipe', 'pipe'] });
  stray.stdin.end(JSON.stringify(SUBAGENT_PAYLOAD));
  assert.equal(await new Promise((r) => stray.on('exit', r)), 0);
  assert.deepEqual(heldNames(elsewhere), []);
  assert.deepEqual(events(elsewhere), ['start', 'release']);
  assert.match(logLines(elsewhere).at(-1) || '', / waited=0$/, 'it never waited for the armed sentinel');
});

test('the deployed copy has to be executable to be a hook at all', () => {
  assert.ok(fs.statSync(GATE).mode & 0o111, 'hooks/huginn-headroom-gate lost its exec bit');
});
