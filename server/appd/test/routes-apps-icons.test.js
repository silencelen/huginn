'use strict';
// GET /v1/apps/:id/icon — the favicon the daemon fetched on the app's probe
// (decision 53), cached under DATA_DIR and served back to the clients.
//
// THE PROMISE UNDER TEST: the picture beside a row is that app's own, it was
// fetched once and not on every poll, it is an IMAGE, and it is small. Every
// case here is a way that stops being true while a row still renders — an SPA
// answering /favicon.ico with its index.html, a page that names a 180px
// apple-touch-icon, a redirect leading the daemon off this host, an icon that
// arrives with no content-length and does not stop.
//
// SAFETY: the store is written before the daemon starts, pointing at fixture
// servers this file owns on ephemeral loopback ports, and SELF_ADDR is pinned to
// 127.0.0.1 by the bind — so no probe here can reach the operator's real armap,
// trainer, board view or sim, which are on the tailnet address only.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');
const crypto = require('node:crypto');
const appsLib = require('../lib/apps');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. The full table is in
// routes-scratchpads.test.js; the Apps rebrand owns 11500-11549:
//
//   routes-apps-icons   11500 + pid%25   -> 11500-11524   (this file)
//   routes-apps-reach   11525 + pid%25   -> 11525-11549
//
// Only the DAEMON takes a number from the block. The fixture servers below bind
// port 0 and are told their port by the kernel.
const PORT = 11500 + (process.pid % 25);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

// Private tmux socket shared with the daemon under test, so nothing it does at
// startup surfaces in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

// A one-pixel PNG, and its magic bytes, so "the right bytes came back" is an
// assertion about content rather than about a length.
const PNG = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6360000002000100'
  + '05fe02fea7bb4d0b0000000049454e44ae426082', 'hex');
const ICO = Buffer.from('00000100010010101000010004002802', 'hex');
// A DIFFERENT one-pixel PNG (the pixel is not the same colour), so "the picture
// changed" is a byte fact rather than a timing one.
const PNG2 = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6364f8cf000001'
  + '0101007a1b21c30000000049454e44ae426082', 'hex');

let tmp, dataDir, token, daemon;
let wellKnown, linked, sneaky, huge, prefers;
const portOf = (s) => s.address().port;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    signal: AbortSignal.timeout(init.timeoutMs || 15_000),
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** The raw fetch, because an icon is bytes and headers rather than JSON. */
async function raw(pathname, headers = {}) {
  const res = await fetch(BASE + pathname, {
    signal: AbortSignal.timeout(15_000),
    headers: { authorization: `Bearer ${token}`, ...headers },
  });
  const bytes = res.status === 304 ? Buffer.alloc(0) : Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, bytes };
}

function rowOf(body, id) { return (body.apps || []).find((a) => a.id === id) || null; }

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-apps-icons-'));
  dataDir = path.join(tmp, 'data');
  fs.mkdirSync(dataDir);
  fs.mkdirSync(path.join(tmp, 'state'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // 1. A page whose /favicon.ico is a real icon.
  wellKnown = http.createServer((req, res) => {
    if (req.url === '/favicon.ico') { res.writeHead(200, { 'content-type': 'image/x-icon' }); return res.end(ICO); }
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html><body>well-known</body></html>');
  });

  // 2. A page with no /favicon.ico that names one in a <link rel=icon>, behind
  //    one same-origin redirect — the ordinary shape of a real app.
  linked = http.createServer((req, res) => {
    if (req.url === '/favicon.ico') { res.writeHead(404); return res.end(); }
    if (req.url === '/icon') { res.writeHead(302, { location: '/static/brand.png' }); return res.end(); }
    if (req.url === '/static/brand.png') { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(PNG); }
    res.writeHead(200, { 'content-type': 'text/html' });
    // ⚠ THE apple-touch-icon COMES FIRST IN THE MARKUP, deliberately, and this
    // server does not serve it: `/huge.png` falls through to the html below, so
    // the fetcher has to SKIP it and go on to the next-ranked candidate.
    res.end('<html><head><link rel="apple-touch-icon" href="/huge.png">'
      + '<link rel="icon" href="/icon"></head><body>linked</body></html>');
  });

  // 3. An SPA: every path, /favicon.ico included, is the same index.html.
  sneaky = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html><head><title>spa</title></head><body>app</body></html>');
  });

  // 5. A page that offers BOTH: an `.ico` at the well-known path AND a PNG it
  //    names in its head. The Android client cannot decode the first one.
  prefers = http.createServer((req, res) => {
    if (req.url === '/favicon.ico') { res.writeHead(200, { 'content-type': 'image/x-icon' }); return res.end(ICO); }
    if (req.url === '/brand.png') {
      res.writeHead(200, { 'content-type': 'image/png' });
      return res.end(prefers.swapped ? PNG2 : PNG);
    }
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html><head><link rel="icon" type="image/png" href="/brand.png"></head><body>prefers</body></html>');
  });

  // 4. A page whose icon is over the cap, and lies about it by sending no length.
  huge = http.createServer((req, res) => {
    if (req.url === '/favicon.ico') {
      res.writeHead(200, { 'content-type': 'image/png' });   // chunked: no content-length
      return res.end(Buffer.alloc(appsLib.ICON_MAX_BYTES + 4096, 9));
    }
    res.writeHead(404); res.end();
  });

  for (const s of [wellKnown, linked, sneaky, huge, prefers]) {
    await new Promise((r) => s.listen(0, '127.0.0.1', r));
  }

  // The store, written before the daemon reads it. See the SAFETY note above.
  fs.writeFileSync(path.join(dataDir, appsLib.STORE_NAME), JSON.stringify({
    schema: appsLib.SCHEMA,
    seeded: true,
    consoles: [
      appsLib.buildRecord({ id: 'wellknown', name: 'Has a favicon.ico', kind: 'tool',
        url: `http://127.0.0.1:${portOf(wellKnown)}/` }, 1789460000),
      appsLib.buildRecord({ id: 'linked', name: 'Names its icon', kind: 'tool',
        url: `http://127.0.0.1:${portOf(linked)}/` }, 1789460000),
      appsLib.buildRecord({ id: 'spa', name: 'Answers everything with html', kind: 'tool',
        url: `http://127.0.0.1:${portOf(sneaky)}/` }, 1789460000),
      appsLib.buildRecord({ id: 'huge', name: 'An icon too big to keep', kind: 'tool',
        url: `http://127.0.0.1:${portOf(huge)}/` }, 1789460000),
      appsLib.buildRecord({ id: 'prefers', name: 'Serves both an ico and a png', kind: 'tool',
        url: `http://127.0.0.1:${portOf(prefers)}/` }, 1789460000),
    ],
  }, null, 2), { mode: 0o600 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: tmp,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one.
  const own = await api('/v1/apps');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
  // One probe per row, which is where icons are fetched.
  for (const id of ['wellknown', 'linked', 'spa', 'huge', 'prefers']) {
    await api(`/v1/apps/${id}/probe`, { method: 'POST' });
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  for (const s of [wellKnown, linked, sneaky, huge, prefers]) { if (s) s.close(); }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// -------------------------------------------------------------- the fetch

test('an app whose /favicon.ico is an icon has it cached and served back', async () => {
  const { status, headers, bytes } = await raw('/v1/apps/wellknown/icon');
  assert.equal(200, status);
  assert.equal('image/x-icon', headers.get('content-type'));
  assert.deepEqual(ICO, bytes, 'the bytes that came off the app, not a re-encoding of them');
  // ⚠ These bytes came off another server. The type was checked when they were
  // cached; nosniff is what stops a browser deciding otherwise about the bytes.
  assert.equal('nosniff', headers.get('x-content-type-options'));
  assert.ok(/private/.test(headers.get('cache-control') || ''), 'the token is the only thing in front of this');
});

test('an app with no /favicon.ico gets the one its page names, through one same-origin redirect', async () => {
  // The apple-touch-icon is RANKED first here (3.5.2) and this page does not
  // actually serve it — `/huge.png` answers with html — so the fetcher falls
  // through to the `<link rel=icon>` and follows its redirect. A fetcher that
  // stopped at its first choice would cache nothing for a page that has one.
  const { status, headers, bytes } = await raw('/v1/apps/linked/icon');
  assert.equal(200, status);
  assert.equal('image/png', headers.get('content-type'));
  assert.deepEqual(PNG, bytes);
});

test('an SPA that answers /favicon.ico with its own index.html gets NO icon', async () => {
  // ⚠ THE COMMON CASE. Without the content-type test this row would carry 40 KB
  // of markup, served back under an image type to every client that polls.
  assert.equal(404, (await raw('/v1/apps/spa/icon')).status);
  assert.equal(false, rowOf((await api('/v1/apps')).body, 'spa').icon, 'and the row says it has none');
});

test('an icon bigger than the cap is refused, even when it declares no length', async () => {
  assert.equal(404, (await raw('/v1/apps/huge/icon')).status);
  assert.equal(false, rowOf((await api('/v1/apps')).body, 'huge').icon);
  // Nothing was written: a cache of half icons is worse than an empty one.
  const cached = path.join(dataDir, appsLib.ICONS_DIR_NAME, 'huge');
  assert.equal(false, fs.existsSync(cached), 'not even a truncated copy');
});

test('the list says which rows have an icon, as a boolean', async () => {
  const { body } = await api('/v1/apps');
  assert.equal(true, rowOf(body, 'wellknown').icon);
  assert.equal(true, rowOf(body, 'linked').icon);
  assert.equal(false, rowOf(body, 'spa').icon);
  assert.equal(false, rowOf(body, 'huge').icon,
    'a client draws an initial-letter tile off this, and must never have to ask twice');
});

// ------------------------------------------------- the PREFERENCE (3.5.2)

test('a page that serves BOTH an .ico and a PNG it names gets the PNG', async () => {
  // ⚠ THE FAIL-FIRST FOR THE RE-ORDER. `/favicon.ico` used to be asked first
  // and answered first, so nearly every row on this host cached an `.ico` —
  // which ANDROID CANNOT DECODE. The phone drew an initial-letter tile for an
  // app that had a perfectly good PNG one link away, and nothing anywhere said
  // why. The well-known path is now the last resort, not the first guess.
  const { status, headers, bytes } = await raw('/v1/apps/prefers/icon');
  assert.equal(200, status);
  assert.equal('image/png', headers.get('content-type'),
    'the PNG the page named, not the .ico it also serves');
  assert.deepEqual(PNG, bytes);
});

// ---------------------------------------------------------- iconAt (3.5.2)

test('iconAt says when the BYTES last changed, and a refetch of the same bytes does not move it', async () => {
  // ⚠ WHY THE FIELD EXISTS. `version` is the row's edit history and never moves
  // for a refetch; the cached file's mtime moves on EVERY refetch. A client with
  // neither had no way to tell "new picture" from "same picture, fetched again",
  // so the phone kept drawing the old one (client backlog, 3.5.0).
  const before = rowOf((await api('/v1/apps')).body, 'prefers');
  assert.equal(true, before.icon);
  assert.ok(before.iconAt > 0, 'a row WITH an icon carries the stamp');
  assert.equal(0, rowOf((await api('/v1/apps')).body, 'spa').iconAt, 'and a row without one says 0');

  // Force the hourly throttle open and probe again: the same page, the same
  // bytes. The file is rewritten (that is what a refetch does); the stamp is not.
  const metaFile = appsLib.iconMetaFile(dataDir, 'prefers');
  const meta = JSON.parse(fs.readFileSync(metaFile, 'utf8'));
  fs.writeFileSync(metaFile, JSON.stringify({ ...meta, fetchedAtMs: 1 }));
  await api('/v1/apps/prefers/probe', { method: 'POST' });

  const after = rowOf((await api('/v1/apps')).body, 'prefers');
  assert.equal(before.iconAt, after.iconAt, 'identical bytes must not invalidate every client’s copy');
  assert.ok(JSON.parse(fs.readFileSync(metaFile, 'utf8')).fetchedAtMs > 1, 'and it really did re-fetch');

  // The ETag is built on iconAt for exactly that reason: a conditional GET
  // across a refetch still answers 304.
  const tag = (await raw('/v1/apps/prefers/icon')).headers.get('etag');
  assert.equal(304, (await raw('/v1/apps/prefers/icon', { 'if-none-match': tag })).status);
});

test('iconAt MOVES when the picture does', async () => {
  // The other half: a stamp that never moved would be worse than no stamp.
  const before = rowOf((await api('/v1/apps')).body, 'prefers');
  const tag = (await raw('/v1/apps/prefers/icon')).headers.get('etag');

  prefers.swapped = true;   // the page starts serving different bytes
  const metaFile = appsLib.iconMetaFile(dataDir, 'prefers');
  fs.writeFileSync(metaFile,
    JSON.stringify({ ...JSON.parse(fs.readFileSync(metaFile, 'utf8')), fetchedAtMs: 1 }));
  await wait(1_100);   // the stamp is in SECONDS; it has to land in a later one
  await api('/v1/apps/prefers/probe', { method: 'POST' });

  const after = rowOf((await api('/v1/apps')).body, 'prefers');
  assert.ok(after.iconAt > before.iconAt, `the stamp moved (${before.iconAt} -> ${after.iconAt})`);
  const fresh = await raw('/v1/apps/prefers/icon');
  assert.equal(200, fresh.status);
  assert.deepEqual(PNG2, fresh.bytes, 'and the new bytes are what is served');
  assert.equal(200, (await raw('/v1/apps/prefers/icon', { 'if-none-match': tag })).status,
    'the old validator no longer matches, so the client fetches the new picture');
});

// ---------------------------------------------------------------- the serve

test('a conditional GET gets a 304 and no bytes, like /v1/files/image', async () => {
  const first = await raw('/v1/apps/wellknown/icon');
  const etag = first.headers.get('etag');
  assert.ok(etag, 'there is a validator to send back');

  const second = await raw('/v1/apps/wellknown/icon', { 'if-none-match': etag });
  assert.equal(304, second.status);
  assert.equal(0, second.bytes.length, 'a 304 is the point: the bytes do not travel again');
  assert.equal(etag, second.headers.get('etag'), 'and the validator rides the 304 too');

  // A proxy may weaken the tag on the way out; weak comparison is the correct
  // one for a conditional GET (RFC 9110 §13.1.2).
  assert.equal(304, (await raw('/v1/apps/wellknown/icon', { 'if-none-match': `W/${etag}` })).status);
  assert.equal(200, (await raw('/v1/apps/wellknown/icon', { 'if-none-match': '"nope"' })).status);
});

test('there is no icon route for an app with none, or for an app that does not exist', async () => {
  assert.equal(404, (await api('/v1/apps/spa/icon')).status);
  assert.equal(404, (await api('/v1/apps/nosuch/icon')).status);
  assert.equal(404, (await api('/v1/apps/NOT_AN_ID/icon')).status, 'the id grammar is the route');
});

test('the icon is fetched ONCE, not on every probe', async () => {
  // ⚠ [ICON_REFRESH_MS]. The sweep runs every five minutes forever; an icon
  // re-fetched on each one is three requests per app per five minutes against
  // somebody's app, for a picture that changes about never.
  const before = fs.statSync(path.join(dataDir, appsLib.ICONS_DIR_NAME, 'wellknown')).mtimeMs;
  await api('/v1/apps/wellknown/probe', { method: 'POST' });
  await api('/v1/apps/wellknown/probe', { method: 'POST' });
  const after = fs.statSync(path.join(dataDir, appsLib.ICONS_DIR_NAME, 'wellknown')).mtimeMs;
  assert.equal(before, after, 'two more probes and the cached file was not rewritten');
});

test('re-pointing an app drops the icon it is no longer wearing', async () => {
  // A row that kept the old page's brand until the hour was up would be showing
  // one app's mark on another app's row.
  const current = rowOf((await api('/v1/apps')).body, 'wellknown');
  assert.equal(true, current.icon, 'precondition: it has one');
  const moved = await api('/v1/apps/wellknown', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version, url: `http://127.0.0.1:${portOf(sneaky)}/` }),
  });
  assert.equal(200, moved.status, JSON.stringify(moved.body));
  assert.equal(false, moved.body.icon, 'the old icon went with the old address');
  assert.equal(404, (await raw('/v1/apps/wellknown/icon')).status);

  // And the new page has none to give it, so it stays without one.
  await api('/v1/apps/wellknown/probe', { method: 'POST' });
  assert.equal(false, rowOf((await api('/v1/apps')).body, 'wellknown').icon);
});

test('deleting an app takes its cached icon with it', async () => {
  const cached = path.join(dataDir, appsLib.ICONS_DIR_NAME, 'linked');
  assert.ok(fs.existsSync(cached), 'precondition: there is one on disk');
  assert.equal(200, (await api('/v1/apps/linked', { method: 'DELETE' })).status);
  assert.equal(false, fs.existsSync(cached), 'a deleted row leaves nothing behind in DATA_DIR');
  assert.equal(404, (await raw('/v1/apps/linked/icon')).status);
});
