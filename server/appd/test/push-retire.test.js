'use strict';
// What a dead push token costs, and WHEN each half of that cost is paid.
//
// The install id is the join between two registries: push.json holds the token
// Google delivers to, clients.json holds the row the panel calls "still
// listening", and both are written by the same install. A token FCM reports as
// gone is evidence about both — but push.json was persisted the moment the
// verdict came in while clients.json waited on a 60-second flush timer, so a
// restart in that minute (a deploy, a crash, an OOM kill) brought back a row
// naming a phone whose token had already been dropped. That row can never be
// retired again: the only thing that drops it is a dead-token verdict, and there
// is no longer a token to get one for.
//
// HOW THIS IS DRIVEN WITHOUT GOOGLE. The daemon builds its FCM sender from a
// service-account key named by HUGINN_FCM_KEY, so the key here is a real RSA
// keypair generated in before() and thrown away in after(). Both network calls
// — the OAuth token exchange and messages:send — are answered by a preload shim
// that replaces globalThis.fetch in the DAEMON's process only. Nothing here
// touches the network, and the shim passes through anything it does not
// recognise. The trigger is a Round's report push, which is the shortest real
// path to deliverPush: no tmux, no alert watcher, no timers to wait on.
//
// SAFETY: scratch HUGINN_APPD_DATA and state dir, a stub `claude` at the front
// of PATH, no tmux sessions, and never /var/lib/huginn-appd.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: two files
// once sat inside 9700-9949, and the suite passed four times before failing 16
// tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer        8788 + pid%900  ->  8788-9687
//   routes-lifecycle     9700 + pid%100  ->  9700-9799
//   routes-rounds        9800 + pid%60   ->  9800-9859
//   routes-devices       9870 + pid%50   ->  9870-9919
//   session-identity     9930 + pid%40   ->  9930-9969
//   breaker-fixes        9971 + pid%25   ->  9971-9995
//   routes-modelgate    10000 + pid%50   -> 10000-10049
//   routes-localmodels  10050 + pid%50   -> 10050-10099
//   routes-polish       10100 + pid%50   -> 10100-10149
//   routes-scratchpads  10150 + pid%50   -> 10150-10199
//   routes-overview     10200 + pid%50   -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299   (this file)
//   routes-desktop      10300 + pid%50   -> 10300-10349
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10250 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

const INSTALL = `inst-${process.pid}`;
const TOKEN_STR = `tok-${process.pid}`;

let tmp, token, daemon;

const STUB = `#!/usr/bin/env node
const F = String.fromCharCode(96, 96, 96);
const NL = String.fromCharCode(10);
let inp = '';
process.stdin.on('data', (c) => { inp += c; });
process.stdin.on('end', () => {
  const TAG = (inp.match(/THIS RUN'S TAG: ([A-Za-z0-9_-]{1,64})/) || [])[1] || '';
  const FENCE = F + 'huginn-report' + (TAG ? ' ' + TAG : '');
  const body = JSON.stringify({ status: 'ok', headline: 'nothing to report', goalMet: true, items: [] });
  console.log(JSON.stringify({ type: 'system', subtype: 'init', session_id: 'stub-' + process.pid }));
  console.log(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text',
    text: 'Here is what I found.' + NL + NL + FENCE + NL + body + NL + F }] } }));
  console.log(JSON.stringify({ type: 'result', is_error: false, duration_ms: 5, num_turns: 1 }));
});
`;

/**
 * Google, as far as the daemon is concerned.
 *
 * Loaded with `--require` so it is in place before huginn-appd.js builds its
 * sender. The token exchange succeeds and every send is answered UNREGISTERED,
 * which is one of the two codes lib/fcm treats as "forget this token" — the
 * verdict the whole retirement path hangs off.
 */
const FETCH_SHIM = `'use strict';
const real = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = String(input && input.url ? input.url : input);
  const json = (status, obj) => new Response(JSON.stringify(obj), {
    status, headers: { 'content-type': 'application/json' },
  });
  if (url.includes('oauth2.googleapis.com')) return json(200, { access_token: 'stub-access', expires_in: 3600 });
  if (url.includes('fcm.googleapis.com')) {
    return json(404, { error: { status: 'NOT_FOUND', message: 'requested entity was not found',
      details: [{ errorCode: 'UNREGISTERED' }] } });
  }
  return real(input, init);
};
`;

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const dataFile = (name) => path.join(tmp, 'data', name);
function readState(name) {
  try { return JSON.parse(fs.readFileSync(dataFile(name), 'utf8')); } catch { return null; }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-retire-'));
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  const shim = path.join(tmp, 'fetch-shim.js');
  fs.writeFileSync(shim, FETCH_SHIM);

  // A real key, because ServiceAccount signs a real JWT with it before anything
  // else happens. It never leaves this directory and never reaches Google.
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const keyFile = path.join(tmp, 'fcm-service-account.json');
  fs.writeFileSync(keyFile, JSON.stringify({
    type: 'service_account',
    project_id: 'huginn-test',
    client_email: 'test@huginn-test.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    token_uri: 'https://oauth2.googleapis.com/token',
  }), { mode: 0o600 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require ${shim}`.trim(),
      PATH: `${bin}:${process.env.PATH}`,
      HUGINN_FCM_KEY: keyFile,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one. Ask an
  // AUTHENTICATED question before trusting the port.
  const own = await api('/v1/alerts');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('the shim is in place, or nothing below is testing what it says it is', async () => {
  const { body } = await api('/v1/alerts');
  assert.equal(body.pushConfigured, true, 'the daemon built a sender from the generated key');
});

test('a dead token retires BOTH registries, and both are on disk before the flush timer', async () => {
  // ⚠ THE TWO HALVES USED TO LAND UP TO A MINUTE APART. push.json was written
  // as soon as the verdict came in; clients.json waited on a 60-second timer. A
  // restart in that window resurrected a client row whose token was already
  // gone — and nothing can ever drop it again, because the only thing that does
  // is a dead-token verdict and there is no token left to get one for. It reads
  // "still listening" about an uninstalled app until its seven-day prune, and
  // `appOnline` believes it.
  //
  // This whole test runs in well under a minute, so if clients.json is right at
  // the end of it, it was written by the retirement and not by the timer.
  const reg = await api('/v1/push/register', {
    method: 'POST', body: JSON.stringify({ installId: INSTALL, token: TOKEN_STR, model: 'Pixel 9' }),
  });
  assert.equal(reg.status, 200, JSON.stringify(reg.body));
  assert.equal(reg.body.devices, 1);

  // The same install checking in as an app, which is what creates the paired row.
  await api('/v1/watch', { headers: { 'x-huginn-client': INSTALL, 'x-huginn-notify': '1' } });
  const listed = await api('/v1/clients');
  assert.ok(listed.body.clients.some((c) => c.id === INSTALL), 'the panel can see it');

  // Fire a Round. Its report is pushed before Telegram is even considered, which
  // is the shortest honest path to a send — and the shim answers UNREGISTERED.
  const round = (await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: 'retire me',
      prompt: 'Check things.',
      schedule: { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' },
      notifyWhen: 'always',
    }),
  })).body;
  const fired = await api(`/v1/rounds/${round.id}/run`, { method: 'POST' });
  assert.equal(fired.status, 202, JSON.stringify(fired.body));

  const until = Date.now() + 20_000;
  let push = null;
  while (Date.now() < until) {
    push = readState('push.json');
    if (push && !JSON.stringify(push).includes(TOKEN_STR)) break;
    await wait(150);
  }
  assert.ok(push, 'push.json was never written');
  assert.ok(!JSON.stringify(push).includes(TOKEN_STR), 'the dead token is gone from push.json');

  // The half that used to be missing. Read from DISK, not from the route: what
  // a restart would find is the only thing at issue here.
  const clients = readState('clients.json');
  assert.ok(clients, 'clients.json must exist by now — it is written BY the retirement, not by a timer');
  assert.ok(!JSON.stringify(clients).includes(INSTALL),
    'a restart here would resurrect a row that can never be dropped again');
});

test('and the panel agrees with the disk', async () => {
  const { body } = await api('/v1/clients');
  assert.ok(!body.clients.some((c) => c.id === INSTALL), 'no ghost row in memory either');
  assert.equal(body.appOnline, false, 'so nothing holds back the Telegram fallback on its behalf');
});
