'use strict';
// Project sessions brought back after a reboot.
//
// A reboot kills the tmux server, and the daemon brings every registered
// session back at startup with `claude --resume <id>`. A project session was
// not launched that way: the lead and each member were started with
// `--name <slug>/<role>`, their effort, and the persona file that carries the
// lead's manifest contract and tag and every member's peer-safety paragraph.
// Brought back without the file, the persona survived only as long as Claude
// Code's recorded prompt did — gone at the first compaction after the restore,
// and at once for a conversation with no recording — while the dashboard went on
// showing the cluster alive.
//
// SAFETY: no real `claude`: the stand-in on the daemon's PATH holds the pane open
// and executes nothing. The stores the daemon reads are seeded in a scratch dir,
// and its tmux server is a private `-L` socket that does not exist until the
// daemon starts it, which is the state a reboot leaves. HOME and the Claude
// config directory are redirected into the scratch dir.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 12390 + pid%10 -> 12390-12399.
const PORT = 12390 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, dataDir, claudeDir, token, daemon;

const PROJECT_ID = crypto.randomUUID();
const TAG = crypto.randomBytes(5).toString('hex');
const LEAD = crypto.randomUUID();
const API = crypto.randomUUID();
const WEB = crypto.randomUUID();      // its transcript is gone: a fresh start
const NOTES = crypto.randomUUID();    // adopted: launched by hand, never with a persona
const PLAIN = crypto.randomUUID();    // not a project session at all

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };

/**
 * What tmux was told to run in the session the restore made. tmux reports it
 * quoted for a shell (`"claude …; exec \"\$SHELL\" -l"`); this is the command
 * as it was given.
 */
function startCommand(name) {
  const raw = sh('tmux', ['display-message', '-p', '-t', `=${name}:`, '#{pane_start_command}']).trim();
  const m = /^"(.*)"$/s.exec(raw);
  return m ? m[1].replace(/\\(["$\\])/g, '$1') : raw;
}

const persona = (role) => path.join(dataDir, 'projects', 'render', `${PROJECT_ID}.${role}.md`);

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** The stores as a running daemon left them before the box went down. */
function seed() {
  const now = Math.floor(Date.now() / 1000);
  const member = (role, sessionId, extra) => ({
    role, name: `rst-${role}`, claudeName: `rst/${role}`, sessionId, cwd: tmp,
    model: null, effort: null, mode: null, firstPrompt: `the ${role} part`, spawnedAt: now - 500, endedAt: null,
    ...extra,
  });
  const project = {
    id: PROJECT_ID, name: 'Restore', slug: 'rst', kind: 'software', status: 'active',
    brief: 'bring the cluster back', cwd: tmp,
    lead: { role: 'lead', name: 'rst-lead', claudeName: 'rst/lead', sessionId: LEAD, spawnedAt: now - 600, endedAt: null },
    members: [
      member('api', API, { model: 'sonnet', effort: 'low' }),
      member('web', WEB, { model: 'haiku', effort: 'high' }),
      // Adopted members are recorded with no first prompt: appd never launched them.
      member('notes', NOTES, { name: 'scratch-notes', claudeName: 'notes-by-hand', firstPrompt: null }),
    ],
    manifest: {
      tag: TAG, rev: 1, receivedAt: now - 550, type: 'software', scope: 'the cluster', summary: null,
      sessions: [], untaggedSeen: false, spawnedRev: 1,
    },
    endedReason: null, endedAt: null, createdAt: now - 600, updatedAt: now - 500, rev: 4,
  };
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${PROJECT_ID}.json`), JSON.stringify(project, null, 2));

  const sessions = {};
  for (const [name, id] of [['rst-lead', LEAD], ['rst-api', API], ['rst-web', WEB], ['scratch-notes', NOTES], ['plain-one', PLAIN]]) {
    sessions[name] = { name, createdAt: now - 600, claudeSessionId: id, cwd: tmp, updatedAt: now - 60 };
  }
  fs.writeFileSync(path.join(dataDir, 'active-sessions.json'), JSON.stringify({ version: 1, sessions }, null, 2));

  // Claude Code's own transcripts, where findTranscriptFile looks; WEB has none.
  const dir = path.join(claudeDir, 'projects', tmp.replace(/\//g, '-'));
  fs.mkdirSync(dir, { recursive: true });
  for (const id of [LEAD, API, NOTES, PLAIN]) {
    fs.writeFileSync(path.join(dir, `${id}.jsonl`),
      `${JSON.stringify({ type: 'user', message: { content: 'hello' }, sessionId: id, cwd: tmp })}\n`);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-prestore-'));
  dataDir = path.join(tmp, 'data');
  claudeDir = path.join(tmp, 'claude');
  for (const d of [dataDir, claudeDir, path.join(tmp, 'state'), path.join(tmp, 'home')]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  seed();

  // ⚠ A RESTORE RUNS `claude …` IN REAL PANES. The stand-in holds each one open
  // and executes nothing; the tmux server the daemon starts hands it this PATH.
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  // The restore runs before the daemon listens, so a ping that answers means it is done.
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

function reap(child, ms = 10_000) {
  if (child.exitCode !== null || child.signalCode) return Promise.resolve();
  return new Promise((resolve) => {
    const hard = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
    const giveUp = setTimeout(resolve, ms);
    const done = () => { clearTimeout(hard); clearTimeout(giveUp); resolve(); };
    child.once('exit', done);
    try { child.kill('SIGTERM'); } catch { done(); }
  });
}

after(async () => {
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('the lead comes back with its name and its persona, resuming its conversation', () => {
  const cmd = startCommand('rst-lead');
  assert.match(cmd, new RegExp(`--resume ${LEAD}`), cmd);
  assert.match(cmd, /--name rst\/lead/, cmd);
  assert.ok(cmd.includes(`--append-system-prompt-file ${persona('lead')}`), cmd);
  const text = readOr(persona('lead'));
  assert.match(text, /LEAD session of the Huginn project "Restore"/, 'the persona file is the lead\'s');
  assert.ok(text.includes(`THIS PROJECT'S TAG: ${TAG}`), 'with the tag its proposals are recognised by');
});

test('a spawned member comes back with its persona and effort, and keeps the model it was on', () => {
  // `--model` is left off a RESUME on purpose: the conversation carries the
  // model it was on, which may be one the ladder moved it to, and the
  // manifest's would quietly undo that.
  const cmd = startCommand('rst-api');
  assert.match(cmd, new RegExp(`--resume ${API}`), cmd);
  assert.match(cmd, /--name rst\/api/, cmd);
  assert.match(cmd, /--effort low/, cmd);
  assert.doesNotMatch(cmd, /--model/, cmd);
  assert.ok(cmd.includes(`--append-system-prompt-file ${persona('api')}`), cmd);
  assert.match(readOr(persona('api')), /You are the api session of the Huginn project "Restore"/);
});

test('a member with nothing left to resume starts fresh exactly as it was first launched', () => {
  const cmd = startCommand('rst-web');
  assert.doesNotMatch(cmd, /--resume/, cmd);
  assert.match(cmd, /--name rst\/web/, cmd);
  assert.match(cmd, /--model haiku/, cmd);
  assert.match(cmd, /--effort high/, cmd);
  assert.ok(cmd.includes(`--append-system-prompt-file ${persona('web')}`), cmd);
});

test('an adopted member and a plain session come back as they were launched: a bare resume', () => {
  // appd never gave an adopted member a persona or a name, so a restore must not
  // start doing so; and a session outside every project is untouched.
  for (const [name, id] of [['scratch-notes', NOTES], ['plain-one', PLAIN]]) {
    const cmd = startCommand(name);
    assert.match(cmd, new RegExp(`^claude --resume ${id}; exec "?\\$SHELL"? -l$`), cmd);
  }
  assert.equal(fs.existsSync(persona('notes')), false, 'and no persona was written for it');
});
