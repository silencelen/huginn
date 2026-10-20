'use strict';
// lib/apps.js — an answer that comes back for an address the row no longer has
// is thrown away, not written over the row.
//
// The sweep reads the rows once and then waits on the network, up to the probe
// deadline per row. A PATCH that re-points a row (the owner fixing a dead
// address) or a DELETE lands inside that wait and clears what the row had; the
// answer about the OLD address then used to land on top: the corrected,
// healthy row read as down with the dead address's fix lines and a "needs
// retrofit" in the journal, and a deleted app's favicon was written back to
// disk and served for a row that no longer existed.
//
// Deterministic, not timed: the fake fetch HOLDS the old address's requests
// until the test has made its edit, then lets them through.
//
// Pure lib, injected fetch, scratch dirs. No daemon, no sockets.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const appsLib = require('../lib/apps');

const OLD = 'http://127.0.0.1:9001/';
const NEW = 'http://127.0.0.1:9002/';
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

/** A door the test opens: `reached` resolves when a request arrives, `open()` lets them all through. */
function gate() {
  let open;
  let arrived;
  const opened = new Promise((r) => { open = r; });
  const reached = new Promise((r) => { arrived = r; });
  return { opened, open, reached, arrived };
}

function refused() {
  const e = new TypeError('fetch failed');
  e.cause = { code: 'ECONNREFUSED' };
  return e;
}

function seed(dir, row) {
  appsLib.writeEnvelope(dir, {
    schema: appsLib.SCHEMA, seeded: true,
    consoles: [{ kind: 'dashboard', notes: '', addedAt: 1_789_000_000, version: 1, ...row }],
  });
}

function tmpDir(tag) { return fs.mkdtempSync(path.join(os.tmpdir(), `apps-stale-${tag}-`)); }

/** Old address: held, then refused. New address: answers at once. */
function heldOldAddress() {
  const held = gate();
  const f = async (url) => {
    if (new URL(url).port === '9001') {
      held.arrived();
      await held.opened;
      throw refused();
    }
    return new Response('', { status: 200 });
  };
  return { held, f };
}

test('a row re-pointed during a sweep does not wear the old address\'s answer', async () => {
  const dir = tmpDir('patch');
  const lines = [];
  try {
    seed(dir, { id: 'grafana', name: 'Grafana', url: OLD });
    const { held, f } = heldOldAddress();
    const store = appsLib.createStore({ dir, icons: false, hostAddr: '', fetch: f, log: (l) => lines.push(l) });
    store.stop();

    const sweeping = store.sweep();
    await held.reached;
    const p = store.patch('grafana', { version: 1, url: NEW });
    assert.equal(p.ok, true, JSON.stringify(p));
    held.open();
    await sweeping;

    const row = store.row('grafana');
    assert.equal(row.url, NEW);
    assert.equal(row.up, null, 'no observation of the new address yet — not the old one\'s "down"');
    assert.equal(row.reachable.ok, null);
    assert.deepEqual(row.reachable.fix, []);
    assert.deepEqual(lines.filter((l) => /grafana/.test(l)), [], 'and nothing in the journal about the old address');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a fresh answer about the new address is not overwritten by the stale one', async () => {
  const dir = tmpDir('probe');
  try {
    seed(dir, { id: 'grafana', name: 'Grafana', url: OLD });
    const { held, f } = heldOldAddress();
    const store = appsLib.createStore({ dir, icons: false, hostAddr: '', fetch: f });
    store.stop();

    const sweeping = store.sweep();
    await held.reached;
    assert.equal(store.patch('grafana', { version: 1, url: NEW }).ok, true);
    const now = await store.probeNow('grafana');
    assert.equal(now.up, true, 'Check now on the corrected row');
    held.open();
    await sweeping;

    const row = store.row('grafana');
    assert.equal(row.up, true, 'still up after the stale sweep lands');
    assert.equal(row.reachable.ok, true);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Check now on a row re-pointed mid-probe answers with the row as it is now', async () => {
  const dir = tmpDir('probenow');
  try {
    seed(dir, { id: 'grafana', name: 'Grafana', url: OLD });
    const { held, f } = heldOldAddress();
    const store = appsLib.createStore({ dir, icons: false, hostAddr: '', fetch: f });
    store.stop();

    const asking = store.probeNow('grafana');
    await held.reached;
    assert.equal(store.patch('grafana', { version: 1, url: NEW }).ok, true);
    held.open();
    const answer = await asking;
    assert.equal(answer.url, NEW, 'the row it has now, not the one it was asked about');
    assert.equal(answer.up, null);
    assert.equal(store.row('grafana').up, null, 'and the old answer was not kept either');

    // Removed mid-probe: there is no row to answer with.
    const { held: held2, f: f2 } = heldOldAddress();
    const dir2 = tmpDir('probenow-gone');
    try {
      seed(dir2, { id: 'wiki', name: 'Wiki', url: OLD });
      const s2 = appsLib.createStore({ dir: dir2, icons: false, hostAddr: '', fetch: f2 });
      s2.stop();
      const asking2 = s2.probeNow('wiki');
      await held2.reached;
      assert.equal(s2.remove('wiki').ok, true);
      held2.open();
      assert.equal(await asking2, null);
    } finally {
      fs.rmSync(dir2, { recursive: true, force: true });
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

/** The page answers at once and names /brand.png; the icon itself is held until the test says so. */
function heldIcon() {
  const held = gate();
  const f = async (url) => {
    const u = new URL(url);
    if (u.pathname === '/brand.png') {
      held.arrived();
      await held.opened;
      return new Response(PNG_1PX, { status: 200, headers: { 'content-type': 'image/png' } });
    }
    if (u.pathname === '/favicon.ico') return new Response('', { status: 404 });
    return new Response('<html><head><link rel="icon" type="image/png" href="/brand.png"></head></html>',
      { status: 200, headers: { 'content-type': 'text/html' } });
  };
  return { held, f };
}

test('an app deleted while its favicon is in flight gets no icon files back', async () => {
  const dir = tmpDir('delete-icon');
  try {
    seed(dir, { id: 'wiki', name: 'Wiki', url: OLD });
    const { held, f } = heldIcon();
    const store = appsLib.createStore({ dir, hostAddr: '', fetch: f });
    store.stop();

    const sweeping = store.sweep();
    await held.reached;
    assert.equal(store.remove('wiki').ok, true);
    held.open();
    await sweeping;

    const icons = appsLib.iconsDir(dir);
    const left = fs.existsSync(icons) ? fs.readdirSync(icons) : [];
    assert.deepEqual(left, [], 'nothing written for a row that is gone');
    assert.equal(store.icon('wiki').ok, false, 'and nothing for GET /v1/apps/wiki/icon to serve');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('an app re-pointed while its favicon is in flight does not wear the old page\'s icon', async () => {
  const dir = tmpDir('patch-icon');
  try {
    seed(dir, { id: 'wiki', name: 'Wiki', url: OLD });
    const { held, f } = heldIcon();
    const store = appsLib.createStore({ dir, hostAddr: '', fetch: f });
    store.stop();

    const sweeping = store.sweep();
    await held.reached;
    assert.equal(store.patch('wiki', { version: 1, url: NEW }).ok, true);
    held.open();
    await sweeping;

    assert.equal(store.icon('wiki').ok, false, 'the old page\'s brand was not cached for the new address');
    assert.equal(store.row('wiki').icon, false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
