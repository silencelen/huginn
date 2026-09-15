'use strict';
// The test-only env knobs, and the guard that stops one of them exfiltrating a
// credential.
//
// Three of appd's overrides point at endpoints that are handed a live secret:
// HUGINN_APPD_USAGE_URL and HUGINN_APPD_OAUTH_ACCOUNT_URL carry the OAuth access
// token in an `Authorization` header, and HUGINN_APPD_OAUTH_TOKEN_URL carries the
// REFRESH token in its body. They exist so a route suite can stand up a local
// stub; a drop-in left over from debugging would otherwise post the owner's login
// to an arbitrary host on a 60 s timer, with nothing in the log to say so.
//
// SAFETY:
//   * PORT 0 — an ephemeral port, so this file takes NO block from the port table
//     in routes-lifecycle.test.js and cannot collide with a sibling.
//   * NO NETWORK. The daemon is started with an EMPTY scratch ~/.claude and an
//     empty accounts dir, so there is no credential to fetch a plan or a refresh
//     with: every network path is short-circuited before it is reached. Nothing
//     here can touch api.anthropic.com, and none of the hostnames below resolve.
//   * A PRIVATE tmux socket, so the daemon's session poll cannot see (or start)
//     anything on the owner's own server.
//
// Only the STARTUP LOG is asserted — that is where the verdict is, and it is the
// operator-facing half of the fix.

const { test } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');

/** Boot a daemon with these extra env vars, collect its log, stop it. */
async function bootLog(extra) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), `env-knobs-${process.pid}-`));
  // The daemon refuses a token under 32 characters, so this is a real one.
  fs.writeFileSync(path.join(tmp, 'token'), `${crypto.randomBytes(24).toString('hex')}\n`);
  const child = spawn(process.execPath, [DAEMON], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: '0',
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-envknobs-${process.pid}`,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      ...extra,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (b) => { out += b.toString(); });
  child.stderr.on('data', (b) => { out += b.toString(); });
  try {
    for (let i = 0; i < 200 && !/listening on/.test(out); i++) {
      await new Promise((r) => setTimeout(r, 50));
    }
  } finally {
    child.kill('SIGTERM');
    await new Promise((r) => { child.on('exit', r); setTimeout(r, 2000); });
    fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
  }
  assert.match(out, /listening on/, `the daemon never started:\n${out}`);
  return out;
}

test('a non-loopback http override of a credential-carrying URL is REFUSED at startup', async () => {
  const out = await bootLog({ HUGINN_APPD_USAGE_URL: 'http://collector.invalid-test.example/usage' });
  assert.match(out, /HUGINN_APPD_USAGE_URL: refused/,
    'an http: override to an arbitrary host was accepted — the access token goes with it');
  assert.ok(!/HUGINN_APPD_USAGE_URL: TEST OVERRIDE/.test(out), 'and it must not read as active');
});

test('the refresh-token endpoint is refused the same way', async () => {
  const out = await bootLog({ HUGINN_APPD_OAUTH_TOKEN_URL: 'http://collector.invalid-test.example/token' });
  assert.match(out, /HUGINN_APPD_OAUTH_TOKEN_URL: refused/);
});

test('a value that is not a URL at all is refused rather than handed to fetch', async () => {
  const out = await bootLog({ HUGINN_APPD_OAUTH_ACCOUNT_URL: 'not a url' });
  assert.match(out, /HUGINN_APPD_OAUTH_ACCOUNT_URL: refused \(not a URL\)/);
});

test('a loopback stub — what the route suites actually use — is accepted and SAID', async () => {
  const out = await bootLog({
    HUGINN_APPD_USAGE_URL: 'http://127.0.0.1:9/usage',
    HUGINN_APPD_OAUTH_ACCOUNT_URL: 'http://localhost:9/account',
  });
  assert.match(out, /HUGINN_APPD_USAGE_URL: TEST OVERRIDE active -> http:\/\/127\.0\.0\.1:9/);
  assert.match(out, /HUGINN_APPD_OAUTH_ACCOUNT_URL: TEST OVERRIDE active -> http:\/\/localhost:9/);
});

test('https anywhere is accepted, and still announced', async () => {
  const out = await bootLog({ HUGINN_APPD_OAUTH_TOKEN_URL: 'https://stub.invalid-test.example/token' });
  assert.match(out, /HUGINN_APPD_OAUTH_TOKEN_URL: TEST OVERRIDE active/);
});

test('no override, no line — the default is silent', async () => {
  const out = await bootLog({});
  assert.ok(!/TEST OVERRIDE/.test(out), 'a clean boot must not claim an override');
  assert.ok(!/: refused/.test(out), 'nor refuse one');
});
