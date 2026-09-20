'use strict';
// Claims the SOURCE makes about the daemon's own contract, checked against the
// daemon.
//
// ⚠ THE FINDING THIS FILE EXISTS FOR (r2 L1). `/v1/ping` has always required the
// bearer token — `authorized()` runs above the route with no exemption — and for
// four releases the comment at the auth check, thirty test files and deploy.sh
// all said it did not, each of them using that as the REASON for a leaked-daemon
// guard that actually works for the opposite reason (a foreign daemon refuses
// our token, so the caller's start loop never sees a 200 at all). A wrong
// explanation attached to correct code is the kind of thing a reader trusts and
// then designs against: the desktop's route probe was written to treat "answered
// ping" as proof of a daemon, which is how a fake collected 129 authenticated
// requests on the review bench. The one genuinely unauthenticated route is
// `/v1/challenge`, and it returns BEFORE the auth check for that exact reason.
//
// SAFETY: reads files. No daemon, no port, no tmux, no network.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const APPD = path.join(__dirname, '..');
const REPO = path.join(APPD, '..', '..');

/** Every .js under server/appd (lib + tests + the daemon), plus deploy.sh. */
function sources() {
  const out = [path.join(APPD, 'deploy.sh')];
  for (const dir of [APPD, path.join(APPD, 'lib'), path.join(APPD, 'test'), path.join(APPD, 'hooks')]) {
    let names = [];
    try { names = fs.readdirSync(dir); } catch { continue; }
    for (const n of names) {
      const p = path.join(dir, n);
      // Not this file: it has to spell the retired claims out to look for them.
      if (p === __filename) continue;
      if (fs.statSync(p).isFile() && /\.(js|sh)$/.test(n)) out.push(p);
    }
  }
  return out;
}

test('nothing in the daemon tree says /v1/ping is unauthenticated (L1)', () => {
  // The exact sentences the round-2 walk found, and the shapes they were written
  // in. `/v1/challenge` is allowed to say it — it is the one route for which it
  // is true — so the match has to be about ping.
  const claims = [
    /ping needs no token/i,
    /ping.{0,40}no token needed/i,
    /\/v1\/ping is unauthenticated/i,
    /PING IS UNAUTHENTICATED/,
    /unauthenticated.{0,30}\/v1\/ping/i,
    /answers ping but not our token/i,
  ];
  const guilty = [];
  for (const file of sources()) {
    const text = fs.readFileSync(file, 'utf8');
    for (const re of claims) {
      const m = re.exec(text);
      if (!m) continue;
      const line = text.slice(0, m.index).split('\n').length;
      guilty.push(`${path.relative(REPO, file)}:${line}: ${m[0]}`);
    }
  }
  assert.deepEqual(guilty, [],
    `these still claim /v1/ping needs no token:\n  ${guilty.join('\n  ')}`);
});

test('the README API table says ping is authenticated and challenge is not (L1)', () => {
  const readme = fs.readFileSync(path.join(REPO, 'mobile', 'README.md'), 'utf8');
  const ping = readme.split('\n').find((l) => /\|\s*`\/v1\/ping`\s*\|/.test(l));
  assert.ok(ping, 'the API table still has a /v1/ping row');
  assert.match(ping, /requires the bearer token/i,
    'the /v1/ping row must say the token is required');

  const challenge = readme.split('\n').find((l) => /\|\s*`\/v1\/challenge`\s*\|/.test(l));
  assert.ok(challenge, '/v1/challenge must be documented — it is the probe that needs no token');
  assert.match(challenge, /unauthenticated/i,
    'the /v1/challenge row must say so, because that is what makes it the probe');
});

test('the auth check has exactly one route above it, and it is /v1/challenge (L1)', () => {
  const src = fs.readFileSync(path.join(APPD, 'huginn-appd.js'), 'utf8');
  const at = src.indexOf("if (!authorized(req)) return sendErr(res, 401, 'unauthorized');");
  assert.ok(at > 0, 'the auth check is still one line, still spelled this way');
  // Everything from the request handler's first line down to the auth check.
  const head = src.slice(src.indexOf('res.setHeader(\'X-Huginn-Appd\', VERSION);'), at);
  const routes = [...head.matchAll(/p === '(\/v1\/[a-z-]+)'/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(routes)], ['/v1/challenge'],
    'a route answered before the token is checked is a route anybody on the LAN can call');
});
