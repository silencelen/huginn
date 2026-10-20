'use strict';
// What a /keys send does when the pane is not a plain, idle composer: the
// page-only send's Enter, against a throwaway daemon and a private tmux socket.
//
// PORT: taken from the kernel (listen on 0, read it back, close), not from the
// fixed table the older route files keep in step by hand — a port the kernel
// just handed out is free now, and no other file can be holding it.
//
// SAFETY: never touches a real session. Every tmux session lives on a private
// `-L` socket that after() kills, the daemon's data, state, Claude dir and FCM
// key all point into a scratch directory, and every HUGINN_* variable of the
// shell that runs the suite is dropped, so nothing can reach a live relay.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const PFX = `sg-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let tmp, stateDir, token, daemon, base;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}
function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const readOr = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '');
async function api(pathname, init = {}) {
  const res = await fetch(base + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
async function until(what, fn, ms = 15_000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const got = await fn();
    if (got) return got;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await wait(100);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-sg-'));
  stateDir = path.join(tmp, 'state');
  for (const d of ['state', 'data', 'claude', 'scratch']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_FCM_KEY: path.join(tmp, 'no-fcm-key.json'),
      HUGINN_CLAUDE_BIN: path.join(tmp, 'no-claude-binary'),
      // Every pane here is drawn by a stand-in that is up at once: the startup
      // hold has nothing to wait for, and its subject is tested elsewhere.
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not listening yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) {
    throw new Error(`port ${port} is held by another huginn-appd that refuses this run's token`);
  }
});

after(async () => {
  try { sh('tmux', ['kill-server']); } catch { /* none left */ }
  if (daemon && daemon.exitCode === null) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      daemon.once('exit', () => { clearTimeout(hard); resolve(); });
      daemon.kill('SIGTERM');
    });
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** A numbered permission dialog, drawn by printf, with every byte typed at it kept raw in `out`. */
const DIALOG = [
  'Do you want to create probe.txt?', '',
  '❯ 1. Yes', '  2. Yes, and do not ask again', '  3. No', '',
  'Enter to select · Esc to cancel',
].join('\\n');
function dialogSession(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.keys`);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${DIALOG}\\n"; stty raw -echo; cat > ${out}'`]);
  return { name, out };
}
/** The stand-in Claude Code composer (fixtures/fake-claude.js), up at once, lines it submits in `out`. */
function composerSession(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.sent`);
  const env = `HG_FAKE_CLAUDE_OUT=${out} HG_FAKE_CLAUDE_BOOT_MS=0 HG_FAKE_CLAUDE_BANNER_MS=0`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env ${env} ${process.execPath} ${path.join(__dirname, 'fixtures', 'fake-claude.js')}`]);
  return { name, out };
}
async function mkPad(name, content) {
  const r = await api('/v1/scratchpads', { method: 'POST', body: JSON.stringify({ name, content }) });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  return r.body;
}

// ---- a page sent on its own -------------------------------------------------

test('a page sent on its own with keys:["Enter"] waits whole behind a dialog: no Enter reaches it', async () => {
  // The Enter is the page's own submit, so it waits with the page. Pressed at
  // once it was the dialog's answer: Enter on the highlighted row is Yes.
  const pad = await mkPad('Plan', 'step one: rebuild the index');
  const { name, out } = dialogSession('pad-dialog');
  await until('the dialog on /screen', async () => (await api(`/v1/sessions/${name}/screen`)).body?.prompt);
  const r = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ scratchpadId: pad.id, keys: ['Enter'] }),
  });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.delivered, false, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'modal', 'held for the dialog, like any message');
  await wait(800);
  assert.equal(readOr(out), '', 'nothing — no Enter, no page — was typed at the dialog');
});

// ---- a selector with no numbers ---------------------------------------------

/** Claude Code 2.1.302's EnterPlanMode dialog: a `hideIndexes` selector, no footer. */
const ENTER_PLAN = [
  '─'.repeat(80), ' Enter plan mode?', '',
  '  Claude wants to enter plan mode to explore and design an implementation', '  approach.', '',
  '  No code changes will be made until you approve the plan.', '',
  '  ❯ Yes, enter plan mode', '    No, start implementing now',
].join('\\n');

test('a message and a soft-end wait for a selector drawn with no numbers', async () => {
  // A background agent keeps the state file at `running`, so only the pane can
  // say a question is up; every rule here needed a numbered row to see one, and
  // the message was pasted into the selector and lost.
  const name = `${PFX}-plan`;
  const out = path.join(tmp, 'plan.keys');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${ENTER_PLAN}\\n"; stty raw -echo; cat > ${out}'`]);
  fs.writeFileSync(path.join(stateDir, name),
    JSON.stringify({ state: 'running', sessionId: null, transcript: null, cwd: tmp, ts: Math.floor(Date.now() / 1000) }));
  await until('the dialog on screen', () => /enter plan mode/.test(capture(name)));
  const r = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: "use the existing parser, don't write a new one", keys: ['Enter'] }),
  });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.delivered, false, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'modal');
  const soft = await api(`/v1/sessions/${name}/soft-end`, { method: 'POST', body: JSON.stringify({ auto: false }) });
  assert.equal(soft.status, 409, JSON.stringify(soft.body));
  assert.match(soft.body.error, /a question is on screen/);
  await wait(800);
  assert.equal(readOr(out), '', 'nothing was pasted into the selector');
});

// ---- a prompt in shell mode -------------------------------------------------

/**
 * Claude Code's box in shell mode (`!` typed into it): the same column-0 rules,
 * `!` where the `❯` was, `! for shell mode` under it. A long reply above, so no
 * echoed `❯` is anywhere near the box.
 */
const SHELL_MODE = [
  '● Here is what changed in the deploy:',
  ...Array.from({ length: 16 }, (_, i) => `  - item ${i + 1}: adjusted the config for service ${i + 1}`),
  '', '─'.repeat(80), '! ', '─'.repeat(80), '  [bp] Fable 5.1 · main ~5', '  ! for shell mode',
].join('\\n');

test('a message is never submitted into a prompt in shell mode, where Enter runs it', async () => {
  // Submitted there, `please rewrite > notes.txt tomorrow` runs as a shell
  // command and the redirect empties notes.txt before the command fails — the
  // shell refusal's harm, with a Claude composer on screen.
  const name = `${PFX}-bang`;
  const out = path.join(tmp, 'bang.keys');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${SHELL_MODE}\\n"; stty raw -echo; cat > ${out}'`]);
  await until('the box on screen', () => /for shell mode/.test(capture(name)));
  const r = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'please rewrite > notes.txt tomorrow', keys: ['Enter'] }),
  });
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.match(r.body.error, /shell mode/);
  await wait(800);
  assert.equal(readOr(out), '', 'nothing was typed, so nothing could run');
  // Live-view typing has no Enter, and is still the person's to put there.
  const typing = await api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ text: 'ls' }) });
  assert.equal(typing.status, 200, JSON.stringify(typing.body));
  await until('the live-view text', () => readOr(out).includes('ls'));
  assert.doesNotMatch(readOr(out), /\r/, 'and no Enter went with it');
});

test('with nothing in the way the page goes at once, submitted by its one Enter', async () => {
  // The same misread left the Enter as a live-view keypress into an empty box,
  // and the draft guard then held the page for a draft nobody was typing.
  const pad = await mkPad('Notes', 'read me first');
  const { name, out } = composerSession('pad-composer');
  await until('the composer', () => /❯/.test(capture(name)));
  const r = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ scratchpadId: pad.id, keys: ['Enter'] }),
  });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, null, JSON.stringify(r.body));
  assert.equal(r.body.delivered, true, JSON.stringify(r.body));
  const sent = await until('the page to be submitted', () => readOr(out));
  assert.match(sent, /^\[Scratchpad "Notes" at \S+\.md — read it before acting on this message\.\]\n$/,
    'the page reference was submitted once, on its own line');
});
