'use strict';
// The refresh routes, end to end through a real daemon.
//
// The unit tests prove the rules; this file proves the WIRING, and the thing it
// is really watching is what the daemon does to the credentials file. Every
// assertion about "byte-identical" below is guarding the same outcome: the owner
// sitting in a terminal, mid-task, suddenly signed out because something on this
// host rotated the token pair the running `claude` was holding.
//
// SAFETY, and it is the whole reason this file is shaped the way it is:
//
//   * the token endpoint is a local http.createServer stub reached through
//     HUGINN_APPD_OAUTH_TOKEN_URL. Nothing here can reach
//     platform.claude.com — a regression that tried would fail, not rotate
//     somebody's real login.
//   * HUGINN_APPD_CLAUDE_DIR points the daemon at a scratch ~/.claude, so the
//     credentials file it reads, writes and LOCKS is a fixture. The real one is
//     never opened.
//   * `claude` and the identity endpoint are stubbed too, so no test here makes
//     an outbound request of any kind.
//   * no token value is ever asserted against a real secret; the fixtures are
//     the string `rt-…`.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: this file
// and routes-lifecycle both sat inside 9700-9949, and the suite passed four times
// before failing 16 tests on an unlucky pair of pids. The width is what makes a
// range, not the base, so both are fixed here:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349
//   routes-resume       10450 + pid%10   -> 10450-10459
//   routes-refresh      10460 + pid%40   -> 10460-10499   (this file)
//
// ⚠ 10450-10499 was ONE block in the wave-1 contract; it is SPLIT — resume keeps
// the bottom ten, refresh the upper forty. Widening either back re-collides.
//
// The stub token endpoint deliberately takes NO block: it binds port 0 and the
// daemon is told its address, so it cannot collide with anything.
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10460 + (process.pid % 40);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;

const UUID_LIVE = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_IDLE = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const UUID_DEAD = '3b2f6c11-0a44-4c98-9d0e-7f1a2b3c4d5e';
const HOUR = 3600_000;

let tmp, claudeDir, credPath, dataDir, accountsDir, token, daemon;
let stub, stubUrl;
/** Every body the daemon POSTed to the token endpoint. Counting is the test. */
let posts = [];
/** What the next token POST should answer with. */
let stubMode = 'ok';
/** The percentages the local usage endpoint answers with. Green by default. */
let usage = { session: 5, weekly_all: 10, weekly_fable: 20 };

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function creds(refresh, over = {}) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      expiresAt: Date.now() - HOUR,
      refreshTokenExpiresAt: Date.now() + 20 * 24 * HOUR,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
      ...over,
    },
  };
}

/** Writes a profile the way AccountStore would, without booting the store. */
function seed(slug, email, credentials, over = {}) {
  const now = Math.floor(Date.now() / 1000);
  fs.writeFileSync(path.join(accountsDir, `${slug}.json`), JSON.stringify({
    slug, email, orgName: null, accountUuid: slug, taggedId: null,
    firstSeen: now, savedAt: now, lastPlan: null, refresh: null,
    oauthAccount: null, credentials, ...over,
  }), { mode: 0o600 });
}

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

const liveBytes = () => fs.readFileSync(credPath);
const profile = (slug) => JSON.parse(fs.readFileSync(path.join(accountsDir, `${slug}.json`), 'utf8'));
const slugs = () => fs.readdirSync(accountsDir).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5)).sort();

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-refresh-'));
  claudeDir = path.join(tmp, '.claude');
  fs.mkdirSync(claudeDir, { recursive: true });
  credPath = path.join(claudeDir, '.credentials.json');
  dataDir = path.join(tmp, 'data');
  accountsDir = path.join(dataDir, 'accounts');
  fs.mkdirSync(accountsDir, { recursive: true, mode: 0o700 });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(`${claudeDir}.json`, JSON.stringify({ unrelated: 'state' }));

  // The live login, and the profile that mirrors it. Its access token is
  // deliberately EXPIRED: that is the normal state of the file between the
  // CLI's own refreshes, and it is exactly the shape that would tempt a
  // careless refresher into rotating it.
  const live = creds('rt-LIVE');
  fs.writeFileSync(credPath, JSON.stringify(live), { mode: 0o600 });
  seed(UUID_LIVE, 'live@example.com', live);
  seed(UUID_IDLE, 'idle@example.com', creds('rt-IDLE'));
  // A login whose refresh token expired days ago: nothing but an interactive
  // sign-in brings it back, and switching to it would hand the CLI a dead pair.
  seed(UUID_DEAD, 'dead@example.com',
    creds('rt-DEAD', { refreshTokenExpiresAt: Date.now() - 3 * 24 * HOUR }));

  // A `claude` that answers `auth status` instantly and does nothing else, so
  // performSwitch never shells out to the real CLI (which would read the real
  // ~/.claude and take a network round trip).
  const binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\necho \'{"loggedIn":true,"email":"stub@example.com"}\'\n', { mode: 0o755 });

  // The stub token endpoint. It ROTATES the pair, which is what the real one
  // does and what every in-place-rewrite assertion below depends on.
  stub = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = null;
      try { body = JSON.parse(raw); } catch { /* recorded as null */ }
      posts.push({ url: req.url, body });
      if (req.url.startsWith('/usage')) {
        // The plan endpoint, local. Without this the daemon would read the REAL
        // api.anthropic.com on every tick — which is both a network call this
        // file promises not to make and the only way to drive the arbiter.
        const resets = new Date(Date.now() + HOUR).toISOString();
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({
          limits: [
            { kind: 'session', percent: usage.session, severity: 'normal', resets_at: resets },
            { kind: 'weekly_all', percent: usage.weekly_all, severity: 'normal', resets_at: resets },
            {
              kind: 'weekly_scoped', percent: usage.weekly_fable, severity: 'normal', resets_at: resets,
              scope: { model: { display_name: 'Fable' } },
            },
          ],
        }));
      }
      if (req.url.startsWith('/oauth/account')) {
        // The identity endpoint: a saved profile's token never authenticates,
        // and 401 is what the daemon is built to shrug off.
        res.writeHead(401, { 'content-type': 'application/json' });
        return res.end('{"error":"unauthorized"}');
      }
      if (stubMode === 'invalid_grant') {
        res.writeHead(400, { 'content-type': 'application/json' });
        return res.end('{"error":"invalid_grant","error_description":"refresh token is dead"}');
      }
      if (stubMode === 'rate_limited') {
        res.writeHead(429, { 'content-type': 'text/plain' });
        return res.end('Too Many Requests');
      }
      const sent = (body && body.refresh_token) || 'unknown';
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        access_token: `at-${sent}-R`,
        refresh_token: `${sent}-R`,
        expires_in: 28800,
        refresh_token_expires_in: 30 * 24 * 3600,
        scope: 'user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload',
      }));
    });
  });
  await new Promise((r) => stub.listen(0, '127.0.0.1', r));
  stubUrl = `http://127.0.0.1:${stub.address().port}`;

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${binDir}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_OAUTH_TOKEN_URL: `${stubUrl}/v1/oauth/token`,
      HUGINN_APPD_USAGE_URL: `${stubUrl}/usage`,
      HUGINN_APPD_PLAN_TTL_MS: '500',
      HUGINN_APPD_OAUTH_ACCOUNT_URL: `${stubUrl}/oauth/account`,
      // The lock ladder's arithmetic is oauthlock.test.js's job; here it only has
      // to not cost the suite fifteen seconds per contended call.
      HUGINN_APPD_OAUTH_LOCK_RETRY_MS: '10',
      HUGINN_APPD_OAUTH_LOCK_PROBE_MS: '60',
      HUGINN_APPD_OAUTH_LOCK_ATTEMPTS: '2',
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  fs.mkdirSync(path.join(tmp, 'state'), { recursive: true });
  for (let i = 0; i < 300; i++) {   // 30s cap: a loaded host has pushed start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping happily (ping needs no token) and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(async () => {
  if (daemon) daemon.kill('SIGTERM');
  if (stub) await new Promise((r) => stub.close(r));
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('an inactive expired profile is refreshed in place, under the same slug', async () => {
  const before = posts.length;
  const liveBefore = liveBytes();

  const { status, body } = await api(`/v1/accounts/${UUID_IDLE}/refresh`, { method: 'POST' });
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.status, 'refreshed');
  assert.equal(body.slug, UUID_IDLE, 'the row this answer belongs to');

  const tokenPosts = posts.slice(before).filter((p) => p.url.includes('/oauth/token'));
  assert.equal(tokenPosts.length, 1, 'exactly one POST to the token endpoint');
  assert.equal(tokenPosts[0].body.grant_type, 'refresh_token');
  assert.equal(tokenPosts[0].body.refresh_token, 'rt-IDLE');

  assert.ok(slugs().includes(UUID_IDLE), 'the slug survives the rotation');
  const rec = profile(UUID_IDLE);
  assert.equal(rec.credentials.claudeAiOauth.refreshToken, 'rt-IDLE-R', 'the rotated pair landed');
  assert.equal(rec.email, 'idle@example.com', 'and the label came with it');
  assert.equal(rec.refresh.lastStatus, 'refreshed');
  assert.ok(rec.refresh.nextAt > Date.now() + 7 * HOUR,
    'scheduled off the new expiry, not for the next tick');

  assert.deepEqual(liveBytes(), liveBefore,
    'THE point of the feature: refreshing an idle profile does not touch the live login');
});

test('the ACTIVE profile is never posted to the token endpoint', async () => {
  const before = posts.filter((p) => p.url.includes('/oauth/token')).length;
  const liveBefore = liveBytes();

  const { status, body } = await api(`/v1/accounts/${UUID_LIVE}/refresh`, { method: 'POST' });
  assert.equal(status, 200);
  assert.equal(body.status, 'active_skipped');
  assert.equal(body.ok, true, 'not an error: the CLI is keeping that one fresh itself');

  assert.equal(posts.filter((p) => p.url.includes('/oauth/token')).length, before,
    'rotating the live pair would leave the running CLI holding a stale refresh token, '
    + 'and its answer to the invalid_grant that follows is to blank its own credentials');
  assert.deepEqual(liveBytes(), liveBefore);
  assert.equal(profile(UUID_LIVE).credentials.claudeAiOauth.refreshToken, 'rt-LIVE');
});

test('the accounts list carries freshness, both expiries and the refresh record', async () => {
  const { status, body } = await api('/v1/accounts');
  assert.equal(status, 200);
  const rows = Object.fromEntries(body.accounts.map((a) => [a.slug, a]));

  for (const row of body.accounts) {
    for (const k of ['freshness', 'expiresAt', 'refreshTokenExpiresAt', 'refresh']) {
      assert.ok(k in row, `every row carries ${k}`);
    }
  }
  assert.equal(rows[UUID_IDLE].freshness, 'fresh', 'just refreshed');
  assert.equal(rows[UUID_IDLE].refresh.lastStatus, 'refreshed');
  assert.ok(rows[UUID_IDLE].refresh.nextAt > Date.now());
  // The one word that means "sign in again" rather than "wait for the tick".
  assert.equal(rows[UUID_DEAD].freshness, 'unrefreshable');
  assert.equal(rows[UUID_DEAD].refresh, null, 'never attempted, so nothing recorded');
  assert.ok(rows[UUID_LIVE].isActive);
});

test('activating an unrefreshable profile is refused with the date and a 409', async () => {
  const before = posts.filter((p) => p.url.includes('/oauth/token')).length;
  const liveBefore = liveBytes();

  const { status, body } = await api(`/v1/accounts/${UUID_DEAD}/activate`, { method: 'POST' });
  assert.equal(status, 409, '409, not 404: the account exists, it just cannot be switched to');
  assert.match(body.error, /^dead@example\.com cannot be switched to: its login expired on \d{4}-\d{2}-\d{2} — sign in again$/);

  assert.equal(posts.filter((p) => p.url.includes('/oauth/token')).length, before,
    'a login past its refresh token expiry is refused without spending a request on it');
  assert.deepEqual(liveBytes(), liveBefore, 'and above all, the live credentials are untouched');
});

test('activating an expired profile refreshes it first, then switches', async () => {
  // Put the idle profile back in the state a saved login is normally found in.
  const rec = profile(UUID_IDLE);
  rec.credentials.claudeAiOauth.expiresAt = Date.now() - HOUR;
  rec.refresh = null;
  fs.writeFileSync(path.join(accountsDir, `${UUID_IDLE}.json`), JSON.stringify(rec));
  const before = posts.filter((p) => p.url.includes('/oauth/token')).length;

  const { status, body } = await api(`/v1/accounts/${UUID_IDLE}/activate`, { method: 'POST' });
  assert.equal(status, 200, body && body.error);

  const tokenPosts = posts.filter((p) => p.url.includes('/oauth/token')).slice(before);
  assert.equal(tokenPosts.length, 1, 'refreshed exactly once on the way in');
  const live = JSON.parse(fs.readFileSync(credPath, 'utf8'));
  assert.equal(live.claudeAiOauth.refreshToken, 'rt-IDLE-R-R',
    'the credentials file gets the REFRESHED pair, not the stale one that was stored');
  assert.ok(live.claudeAiOauth.expiresAt > Date.now(),
    'a switch that installed an expired token would break on the CLI`s first call');
  assert.ok(slugs().includes(UUID_IDLE), 'and the profile is still one profile');
});

test('invalid_grant marks the login dead and keeps every byte of the record', async () => {
  // Switch back so the account under test is inactive again.
  assert.equal((await api(`/v1/accounts/${UUID_LIVE}/activate`, { method: 'POST' })).status, 200);
  const rec = profile(UUID_IDLE);
  rec.credentials.claudeAiOauth.expiresAt = Date.now() - HOUR;
  rec.refresh = null;
  fs.writeFileSync(path.join(accountsDir, `${UUID_IDLE}.json`), JSON.stringify(rec));
  const storedBefore = JSON.stringify(profile(UUID_IDLE).credentials);

  stubMode = 'invalid_grant';
  try {
    const { status, body } = await api(`/v1/accounts/${UUID_IDLE}/refresh`, { method: 'POST' });
    assert.equal(status, 200);
    assert.equal(body.ok, false);
    assert.equal(body.status, 'known_dead_refresh_token');
    assert.equal(body.slug, UUID_IDLE);
    assert.ok(body.refresh && body.refresh.deadAt, 'the describe() block travels with the verdict');
  } finally { stubMode = 'ok'; }

  const after = profile(UUID_IDLE);
  assert.equal(JSON.stringify(after.credentials), storedBefore,
    'the CLI zeroes its tokens here; we do not — a blank row tells the owner nothing '
    + 'about what to sign back in as');
  assert.ok(after.refresh.deadAt > 0);
  // The row itself has to survive, uuid and all — that is what the owner signs
  // back in as. (Its label is whatever the last switch relabelled it to; the
  // stub `claude` names every account the same, so do not assert on that here.)
  assert.equal(after.accountUuid, UUID_IDLE);
  assert.ok(after.email, 'the record still has a label');

  const rows = (await api('/v1/accounts')).body.accounts;
  assert.equal(rows.find((a) => a.slug === UUID_IDLE).freshness, 'unrefreshable');
});

test('a rate-limited refresh backs off instead of retrying, and rotates nothing', async () => {
  const rec = profile(UUID_IDLE);
  rec.credentials.claudeAiOauth.expiresAt = Date.now() - HOUR;
  rec.refresh = null;                       // clear the deadAt the last test set
  fs.writeFileSync(path.join(accountsDir, `${UUID_IDLE}.json`), JSON.stringify(rec));
  const storedBefore = JSON.stringify(profile(UUID_IDLE).credentials);
  const before = posts.filter((p) => p.url.includes('/oauth/token')).length;

  stubMode = 'rate_limited';
  try {
    const { body } = await api(`/v1/accounts/${UUID_IDLE}/refresh`, { method: 'POST' });
    assert.equal(body.ok, false);
    assert.equal(body.status, 'refresh_failed');
    assert.equal(body.slug, UUID_IDLE);
  } finally { stubMode = 'ok'; }

  assert.equal(posts.filter((p) => p.url.includes('/oauth/token')).length, before + 1,
    'one attempt, not a retry storm against an endpoint that rate-limits per account');
  const after = profile(UUID_IDLE);
  assert.equal(JSON.stringify(after.credentials), storedBefore);
  assert.equal(after.refresh.deadAt ?? null, null, 'a 429 is not a dead login');
  assert.ok(after.refresh.nextAt - Date.now() > 7 * HOUR, 'and it waits a token lifetime');
});

test('POST /refresh answers the shape the client declares: ok, slug, status, refresh', async () => {
  // It used to answer `{ok, status}` only, so `AccountRefreshed.slug` and
  // `.refresh` were always their defaults and a settings screen had to re-fetch
  // /v1/accounts to learn when the next attempt was due — for the row it was
  // already looking at.
  const { status, body } = await api(`/v1/accounts/${UUID_LIVE}/refresh`, { method: 'POST' });
  assert.equal(status, 200);
  assert.equal(body.slug, UUID_LIVE, 'the row this answer belongs to');
  // ⚠ THE REAL VOCABULARY, not the kdoc's invented one. These are the words the
  // daemon actually emits; `ok` is about the profile being usable afterwards,
  // not about a POST having happened.
  assert.ok(['refreshed', 'not_needed', 'active_skipped'].includes(body.status), body.status);
  assert.equal(body.ok, true);
  assert.ok(body.refresh && typeof body.refresh === 'object', 'the describe() block travels too');
  assert.equal(body.refresh.lastStatus, body.status);
  assert.ok(Number.isFinite(body.refresh.lastAt));
});

test('refreshing an unknown slug is a 404, not a silent ok', async () => {
  const { status, body } = await api('/v1/accounts/nosuchslug/refresh', { method: 'POST' });
  assert.equal(status, 404);
  assert.match(body.error, /no such saved account/);
});

test('a switch is refused while another process holds the OAuth lock', async () => {
  const liveBefore = liveBytes();
  const lock = path.join(claudeDir, '.oauth_refresh.lock');
  fs.mkdirSync(lock, { recursive: true });
  // Heartbeat it: a live holder means "come back later", a frozen one means
  // "somebody died holding this". Both refuse; only one is worth alerting on.
  const beat = setInterval(() => { const n = new Date(); fs.utimesSync(lock, n, n); }, 10);
  try {
    const { status, body } = await api(`/v1/accounts/${UUID_DEAD}/activate`, { method: 'POST' });
    // UUID_DEAD is refused on freshness before the lock is even reached, so use
    // a profile that would otherwise be switchable.
    assert.equal(status, 409);
    assert.ok(body.error);
    const second = await api(`/v1/accounts/${UUID_LIVE}/activate`, { method: 'POST' });
    assert.equal(second.status, 409, 'the switch itself waits for the lock, then gives up');
    assert.match(second.body.error, /another process is refreshing/);
  } finally {
    clearInterval(beat);
    fs.rmSync(lock, { recursive: true, force: true });
  }
  assert.deepEqual(liveBytes(), liveBefore, 'a refused switch changes nothing');
});

// ------------------------------------------------- the UNATTENDED switch gate

test('the arbiter refuses an unrefreshable profile too, and signs nobody out', async () => {
  // ⚠ THE ARBITER IS THE MORE DANGEROUS CALLER, not the safer one. Installing a
  // credential pair that is behind hands the CLI `invalid_grant`, and the CLI
  // answers by blanking its own credentials file — the owner is signed out of
  // Claude Code, at 3am, with nothing to read afterwards. And the arbiter is
  // MORE likely than the button to choose a dead profile: `agedLimits` zeroes
  // every window whose reset time has passed, so the profile nobody has read for
  // weeks scores as the freshest candidate on the host.
  const gone = new Date(Date.now() - HOUR).toISOString();
  const plan = (pct) => ({
    at: Date.now(),
    limits: [
      { kind: 'session', percent: pct, severity: 'normal', resetsAt: gone, label: 'Current session' },
      { kind: 'weekly_all', percent: pct, severity: 'normal', resetsAt: gone, label: 'Current week, all models' },
      { kind: 'weekly_scoped', percent: pct, severity: 'normal', resetsAt: gone, label: 'Current week (Fable)' },
    ],
  });
  // The dead login looks like the freshest thing on the host; the other saved
  // one is full, so it is not a candidate at all.
  const dead = profile(UUID_DEAD);
  dead.lastPlan = plan(0);
  fs.writeFileSync(path.join(accountsDir, `${UUID_DEAD}.json`), JSON.stringify(dead));
  const idle = profile(UUID_IDLE);
  idle.lastPlan = plan(99);
  fs.writeFileSync(path.join(accountsDir, `${UUID_IDLE}.json`), JSON.stringify(idle));

  const liveBefore = liveBytes();
  const postsBefore = posts.filter((p) => p.url.includes('/oauth/token')).length;

  // The active account is out of room, and the switcher is on.
  usage = { session: 100, weekly_all: 100, weekly_fable: 100 };
  await wait(700);                                    // outlive the plan cache
  const on = await api('/v1/headroom/settings', {
    method: 'PATCH',
    body: JSON.stringify({ accountSwitch: { enabled: true, threshold: 95, margin: 20 } }),
  });
  assert.equal(on.status, 200, JSON.stringify(on.body));

  // Give it several passes to do the wrong thing.
  let why = '';
  const deadline = Date.now() + 20_000;
  for (;;) {
    const hr = (await api('/v1/headroom')).body;
    why = String((hr.arbiter && hr.arbiter.why) || '');
    if (/cannot be switched to/.test(why)) break;
    if (Date.now() > deadline) break;
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    await wait(400);
  }

  assert.deepEqual(liveBytes(), liveBefore,
    `performSwitch ran on a login whose refresh token is gone — this is the signed-out case (why: ${why})`);
  assert.match(why, /dead@example\.com cannot be switched to/,
    `the arbiter never said why it refused the dead profile; why was: ${why}`);
  assert.equal(posts.filter((p) => p.url.includes('/oauth/token')).length, postsBefore,
    'and a login past its refresh-token expiry is refused without spending a request on it');
  const sw = (await api('/v1/autoswitch')).body;
  assert.equal(sw.switches, 0);

  usage = { session: 5, weekly_all: 10, weekly_fable: 20 };
  await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ accountSwitch: { enabled: false } }) });
});
