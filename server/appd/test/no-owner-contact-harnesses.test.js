'use strict';
// The harnesses OUTSIDE these Node suites that start huginn-appd (the core's Go
// e2e tests and the shell e2e scripts) keep it from reaching the owner too.
//
// ⚠ THEY STRIP EVERY HUGINN_APPD_* VARIABLE AND STOP THERE. That blanks the
// relay and the memory host, whose defaults are empty, but the push key is
// HUGINN_FCM_KEY, and unset it falls back to the live key on a huginn host
// (see no-owner-contact.js). Each must name a key that cannot exist.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '..', '..', '..');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(go|sh)$/.test(e.name)) out.push(p);
  }
  return out;
}

test('every Go or shell harness that starts huginn-appd points the push key at nothing', () => {
  const starters = [];
  const missing = [];
  for (const f of [...walk(path.join(REPO, 'core')), ...walk(path.join(REPO, 'scripts'))]) {
    const src = fs.readFileSync(f, 'utf8');
    if (!/huginn-appd\.js/.test(src)) continue;
    // Go: runs node on a path built from the daemon's file name (a test that
    // only runs `node -e` on one of its libraries names the daemon in comments).
    // Shell: a `node …/huginn-appd.js` line.
    const startsDaemon = f.endsWith('.go')
      ? /exec\.Command\("node"/.test(src) && /"huginn-appd\.js"/.test(src)
      : /^\s*node\s+\S*huginn-appd\.js/m.test(src);
    if (!startsDaemon) continue;
    const rel = path.relative(REPO, f);
    starters.push(rel);
    if (!/HUGINN_FCM_KEY=["']?[^\s"']*nonexistent/.test(src)) missing.push(rel);
  }
  // The scan has to keep finding the harnesses it was written for.
  for (const known of ['core/test/e2e.sh', 'core/internal/appd/e2e_test.go']) {
    assert.ok(starters.includes(known), `${known} is no longer recognised as starting a daemon: ${starters.join(', ')}`);
  }
  assert.deepEqual(missing, [], `these start a daemon that could load the live push key: ${missing.join(', ')}`);
});
