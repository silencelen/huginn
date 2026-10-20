'use strict';
// The auto-switcher, end to end through a real daemon, when the saved login
// that LOOKS freshest is one the switch gate will refuse.
//
// A login whose refresh token died weeks ago prices as 0% everywhere, because
// every window it was last read in has since rolled over. The arbiter used to
// pick it on every pass, have the gate refuse it, and pick it again — so the
// healthy account sitting at 30% was never switched to while the active one ran
// into its cap. arbiter-unswitchable.test.js proves the rule; this file proves
// the daemon hands the rule what it needs: it has to switch to the healthy one.
//
// SAFETY, the same as routes-refresh.test.js:
//   * the token, usage and identity endpoints are a local stub on port 0, and
//     HUGINN_APPD_CLAUDE_DIR is a scratch ~/.claude — nothing real is read,
//     rotated or locked;
//   * no push key and no Telegram relay: this file makes the daemon switch
//     accounts, and a switch announces itself on both channels;
//   * the daemon's own port is a free EPHEMERAL one found at start, so this
//     file takes no block from the port table in the other route suites.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
require('./retry-fetch');

const UUID_LIVE = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_DEAD = '3b2f6c11-0a44-4c98-9d0e-7f1a2b3c4d5e';
const UUID_OK = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const HOUR = 3600_000;
const TMUX_SOCK = `huginn-test-${process.pid}`;

const snapshot = (pct, resetsAt) => ({
  at: Math.floor(Date.now() / 1000),
  limits: [
    { kind: 'session', percent: pct, severity: 'normal', resetsAt, label: 'Current session' },
    { kind: 'weekly_all', percent: pct, severity: 'normal', resetsAt, label: 'Current week, all models' },
    { kind: 'weekly_scoped', percent: pct, severity: 'normal', resetsAt, label: 'Current week (Fable)' },
  ],
});

let tmp, claudeDir, credPath, accountsDir, token, daemon, stub, base;
const posts = [];
/** The active login's usage, as the local usage endpoint reports it. */
let usage = 5;

function creds(refresh, over = {}) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      // Expired, as a saved login's access token normally is: the arbiter then
      // prices it from its last reading, and the gate refreshes it on the way in.
      expiresAt: Date.now() - HOUR,
      refreshTokenExpiresAt: Date.now() + 20 * 24 * HOUR,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
      ...over,
    },
  };
}

function seed(slug, email, credentials, lastPlan) {
  const now = Math.floor(Date.now() / 1000);
  fs.writeFileSync(path.join(accountsDir, `${slug}.json`), JSON.stringify({
    slug, email, orgName: null, accountUuid: slug, taggedId: null, firstSeen: now, savedAt: now,
    lastPlan, refresh: null, oauthAccount: null, credentials,
  }), { mode: 0o600 });
}

async function api(pathname, init = {}) {
  const res = await fetch(base + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const liveRefreshToken = () => JSON.parse(fs.readFileSync(credPath, 'utf8')).claudeAiOauth.refreshToken;

/** Poll /v1/headroom, nudging a re-evaluation now and then (PATCH runs one at once). */
async function until(fn, what, ms = 30_000) {
  const deadline = Date.now() + ms;
  let nudge = 0;
  for (;;) {
    const hr = (await api('/v1/headroom')).body;
    if (fn(hr)) return hr;
    if (Date.now() > deadline) {
      throw new Error(`${what} never happened. Last /v1/headroom: `
        + `${JSON.stringify({ accounts: hr && hr.accounts, arbiter: hr && hr.arbiter }).slice(0, 900)}`);
    }
    if (Date.now() > nudge) {
      nudge = Date.now() + 1_500;
      await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    }
    await wait(150);
  }
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-arbiter-'));
  claudeDir = path.join(tmp, '.claude');
  fs.mkdirSync(claudeDir, { recursive: true });
  credPath = path.join(claudeDir, '.credentials.json');
  accountsDir = path.join(tmp, 'data', 'accounts');
  fs.mkdirSync(accountsDir, { recursive: true, mode: 0o700 });
  fs.mkdirSync(path.join(tmp, 'state'), { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(`${claudeDir}.json`, JSON.stringify({ unrelated: 'state' }));

  // Seeded BEFORE the daemon starts: it prices each saved login once and then
  // leaves it for half an hour (routes-refresh.test.js has the whole story).
  const live = creds('rt-LIVE', { expiresAt: Date.now() + 8 * HOUR });
  fs.writeFileSync(credPath, JSON.stringify(live), { mode: 0o600 });
  seed(UUID_LIVE, 'live@example.com', live, null);
  // Refresh token expired days ago, last read weeks ago: prices as 0%.
  seed(UUID_DEAD, 'dead@example.com', creds('rt-DEAD', { refreshTokenExpiresAt: Date.now() - 3 * 24 * HOUR }),
    snapshot(100, new Date(Date.now() - 13 * 24 * HOUR).toISOString()));
  // Healthy and idle at 30%, its windows still running.
  seed(UUID_OK, 'ok@example.com', creds('rt-OK'), snapshot(30, new Date(Date.now() + 24 * HOUR).toISOString()));

  const binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\necho \'{"loggedIn":true,"email":"the-live-cli@example.invalid","subscriptionType":"max"}\'\n', { mode: 0o755 });

  stub = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = null;
      try { body = JSON.parse(raw); } catch { /* not JSON */ }
      posts.push({ url: req.url, body });
      if (req.url.startsWith('/usage')) {
        const resets = new Date(Date.now() + HOUR).toISOString();
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({
          limits: [
            { kind: 'session', percent: usage, severity: 'normal', resets_at: resets },
            { kind: 'weekly_all', percent: usage, severity: 'normal', resets_at: resets },
            { kind: 'weekly_scoped', percent: usage, severity: 'normal', resets_at: resets, scope: { model: { display_name: 'Fable' } } },
          ],
        }));
      }
      if (req.url.startsWith('/oauth/account')) {
        res.writeHead(401, { 'content-type': 'application/json' });
        return res.end('{"error":"unauthorized"}');
      }
      const sent = (body && body.refresh_token) || 'unknown';
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        access_token: `at-${sent}-R`, refresh_token: `${sent}-R`, expires_in: 28800,
        scope: 'user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload',
      }));
    });
  });
  await new Promise((r) => stub.listen(0, '127.0.0.1', r));
  const stubUrl = `http://127.0.0.1:${stub.address().port}`;

  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${binDir}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_OAUTH_TOKEN_URL: `${stubUrl}/v1/oauth/token`,
      HUGINN_APPD_USAGE_URL: `${stubUrl}/usage`,
      HUGINN_APPD_OAUTH_ACCOUNT_URL: `${stubUrl}/oauth/account`,
      HUGINN_APPD_PLAN_TTL_MS: '500',
      HUGINN_APPD_OAUTH_LOCK_RETRY_MS: '10',
      HUGINN_APPD_OAUTH_LOCK_PROBE_MS: '60',
      HUGINN_APPD_OAUTH_LOCK_ATTEMPTS: '2',
      HUGINN_FCM_KEY: path.join(tmp, 'no-push-key.json'),
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/rounds');
  if (own.status !== 200) throw new Error(`the daemon on ${base} did not answer our token (${own.status})`);
});

after(async () => {
  if (stub) await new Promise((r) => stub.close(r));
  if (daemon && daemon.exitCode === null && !daemon.signalCode) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      daemon.once('exit', () => { clearTimeout(hard); resolve(); });
      try { daemon.kill('SIGTERM'); } catch { resolve(); }
    });
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('the arbiter switches to the healthy account, not the dead one that prices as the freshest', async () => {
  const priced = (hr, slug) => {
    const w = (hr && hr.accounts && hr.accounts[slug] && hr.accounts[slug].windows) || {};
    const pcts = Object.values(w).map((x) => x && x.percent).filter((p) => typeof p === 'number');
    return pcts.length ? Math.max(...pcts) : null;
  };
  const hr = await until((h) => priced(h, UUID_DEAD) !== null && priced(h, UUID_OK) !== null,
    'the first pass pricing the saved logins');
  assert.equal(priced(hr, UUID_DEAD), 0, 'precondition: the dead login reads as the freshest on the host');
  assert.equal(priced(hr, UUID_OK), 30, 'precondition: the healthy one reads 30%');

  usage = 100;
  const on = await api('/v1/headroom/settings', {
    method: 'PATCH',
    body: JSON.stringify({ accountSwitch: { enabled: true, threshold: 95, margin: 20 }, cooldownMs: 0 }),
  });
  assert.equal(on.status, 200, JSON.stringify(on.body));

  const done = await until((h) => (h.arbiter && h.arbiter.switches) >= 1, 'a switch', 45_000).catch((e) => e);
  assert.ok(!(done instanceof Error), done instanceof Error ? done.message : '');
  assert.equal(liveRefreshToken(), 'rt-OK-R',
    'the live credentials are the healthy login, refreshed on the way in');
  assert.equal(posts.filter((p) => p.body && p.body.refresh_token === 'rt-DEAD').length, 0,
    'and the dead login was never sent to the token endpoint');
});
