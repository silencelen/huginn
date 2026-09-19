'use strict';
// `GET /v1/challenge` — the token-proving handshake (decision 58, 2026-09-19).
//
// THE EXPOSURE THIS CLOSES. The desktop's auto-switch adopted any route-book
// address that answered with a non-blank `X-Huginn-Appd` header and then sent it
// the real bearer token: 129 authenticated requests to a fake in about two
// minutes on the review bench (D-1). A header anybody can print is a fingerprint,
// not proof. So the client picks a fresh nonce, asks for it UNAUTHENTICATED, and
// adopts the address only if the answer matches the HMAC it computes itself —
// which an impostor without the token cannot produce.
//
// Publishing HMAC-SHA256(token, nonce) is safe because HMAC is a PRF of its key,
// the key is 256 bits of `openssl rand -hex 32`, and the nonce is the client's
// own so a recorded proof cannot be replayed at somebody else's challenge. What
// this file pins is that the route holds up its end: the right proof, a WRONG
// token's proof never matching, a 400 for a nonce that is not one, no token
// anywhere in the answer or the log, and a rate limit so the route cannot be
// turned into a free keyed-hash amplifier.
//
// SAFETY: a scratch data dir, a scratch ~/.claude, a PRIVATE tmux socket, and a
// throwaway token of this file's own. No session is created at all.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11650 + pid%25 -> 11650-11674.
const PORT = 11650 + (process.pid % 25);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

let tmp, token, daemon, daemonLog;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** The route is UNAUTHENTICATED: no header at all, on purpose. */
async function challenge(query) {
  const res = await fetch(`${BASE}/v1/challenge${query}`);
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body, header: res.headers.get('x-huginn-appd') };
}
const hmac = (key, msg) => crypto.createHmac('sha256', key).update(msg, 'utf8').digest('hex');

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-challenge-'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), `${token}\n`, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-challenge-${process.pid}`,
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try {
      const res = await fetch(`${BASE}/v1/ping`);
      if (res.status === 401 || res.status === 200) break;
    } catch { /* not up */ }
    await wait(100);
  }
});

after(async () => {
  if (daemon && daemon.exitCode === null) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      const giveUp = setTimeout(resolve, 10_000);
      daemon.once('exit', () => { clearTimeout(hard); clearTimeout(giveUp); resolve(); });
      try { daemon.kill('SIGTERM'); } catch { resolve(); }
    });
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a nonce comes back as HMAC-SHA256 of it under the bearer token', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.5.2 there is no such route: this is a 401
  // (everything but /v1/ping is behind the token) and there is nothing a client
  // can ask that an impostor cannot answer.
  const nonce = crypto.randomBytes(16).toString('hex');
  const { status, body, header } = await challenge(`?nonce=${nonce}`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.proof, hmac(token, nonce), 'the proof is the HMAC the client computes itself');
  assert.equal(body.version, header, 'and it names the version, matching the fingerprint header');
  assert.match(body.proof, /^[0-9a-f]{64}$/, 'lower-case hex, SHA-256 wide');
});

test('a proof computed under a DIFFERENT token never matches', async () => {
  // The whole mechanism in one assertion: an address that does not hold this
  // daemon's token cannot answer its challenge, so a client that checks the
  // answer cannot be talked into handing the token over.
  const nonce = crypto.randomBytes(24).toString('hex');
  const { body } = await challenge(`?nonce=${nonce}`);
  const impostor = crypto.randomBytes(32).toString('hex');
  assert.notEqual(body.proof, hmac(impostor, nonce),
    'a fake daemon answering with its own token is rejected');
  assert.equal(body.proof, hmac(token, nonce), 'and the real one is accepted');
});

test('the same nonce is deterministic; a different nonce is a different proof', async () => {
  const a = crypto.randomBytes(16).toString('hex');
  const b = crypto.randomBytes(16).toString('hex');
  const first = (await challenge(`?nonce=${a}`)).body.proof;
  const again = (await challenge(`?nonce=${a}`)).body.proof;
  const other = (await challenge(`?nonce=${b}`)).body.proof;
  assert.equal(first, again, 'the client must be able to verify what it asked for');
  assert.notEqual(first, other, 'and a replayed proof is no use against a fresh nonce');
});

test('a nonce that is not 16-64 hex characters is a 400, with the rule stated', async () => {
  for (const q of ['', '?nonce=', '?nonce=short', '?nonce=0123456789abcde',
    `?nonce=${'a'.repeat(65)}`, '?nonce=zzzzzzzzzzzzzzzz', '?nonce=../../etc/passwd']) {
    const { status, body } = await challenge(q);
    assert.equal(status, 400, `expected 400 for ${JSON.stringify(q)}`);
    assert.match(body.error, /16 to 64 hexadecimal/, 'a sentence a caller can act on');
  }
  // 16 and 64 are both IN, because a boundary written down in prose is a
  // boundary somebody will get wrong.
  assert.equal((await challenge(`?nonce=${'a'.repeat(16)}`)).status, 200);
  assert.equal((await challenge(`?nonce=${'a'.repeat(64)}`)).status, 200);
});

test('the route never says the token, and never writes it down', async () => {
  const nonce = crypto.randomBytes(16).toString('hex');
  const { body } = await challenge(`?nonce=${nonce}`);
  assert.equal(JSON.stringify(body).includes(token), false, 'not in the answer');
  const log = fs.readFileSync(daemonLog, 'utf8');
  assert.equal(log.includes(token), false, 'not in the journal');
  assert.equal(log.includes(nonce), false,
    'and not even the nonce — the request line this daemon writes is the PATH only');
  assert.match(log, /GET \/v1\/challenge 200/, 'the call itself is logged, as every call is');
});

test('a burst is rate-limited, and the limit is per second, not per lifetime', async () => {
  // An unauthenticated route that does a keyed hash per call is a free amplifier
  // otherwise. Twenty a second is far above any real client — one per route
  // probe — and far below anything worth having.
  // Start at the TOP of a second, with this file's earlier tests out of the
  // allowance: the window is a wall-clock second, so a burst begun at 0.98 s
  // gets two allowances and a burst begun after nine other calls gets eleven.
  while (Date.now() % 1000 > 150) await wait(20);
  const burst = [];
  for (let i = 0; i < 40; i++) burst.push(challenge(`?nonce=${'b'.repeat(16)}`));
  const codes = (await Promise.all(burst)).map((r) => r.status);
  assert.ok(codes.includes(429), `a burst of 40 must be refused somewhere (got ${codes.join(',')})`);
  assert.ok(codes.filter((c) => c === 200).length >= 20,
    `but twenty a second still get through (${codes.join(',')})`);

  // The next second is a fresh allowance — a limit that never forgave would lock
  // a client out of its own route probe for good.
  await wait(1_200);
  assert.equal((await challenge(`?nonce=${'c'.repeat(16)}`)).status, 200);
});

test('the route is GET only and still refuses everything else without a token', async () => {
  const res = await fetch(`${BASE}/v1/challenge?nonce=${'a'.repeat(16)}`, { method: 'POST' });
  assert.equal(res.status, 401, 'a POST falls through to the auth check like any other path');
  const sess = await fetch(`${BASE}/v1/sessions`);
  assert.equal(sess.status, 401, 'and proving the daemon is not the same as being let in');
});
