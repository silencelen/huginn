'use strict';
// lib/apps.js — a stored row is cleaned when it is READ and written back as it
// was STORED, unless an edit changed it.
//
// Reading cleans a row so that it renders and is never probed where it must
// not be: a kind this version does not know reads as `other`, an address its
// rule refuses reads as '', a long note reads cut. The store used to write that
// reading back — the address flush on the first authorised request after every
// start (even GET /v1/ping), and every add, patch or remove for every row it
// never touched. A later daemon's sixth kind became `other` for good after a
// rollback, and a hand-added address was blanked on disk, with no copy kept.
//
// Pure lib, scratch dirs, injected fetch. No daemon, no network.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const appsLib = require('../lib/apps');

/** As a later daemon might write it: a sixth kind, a longer note, an address class this version refuses, a new key. */
const LATER = {
  id: 'plex', name: 'Plex', url: 'http://127.0.0.1:32400/', kind: 'media',
  notes: 'n'.repeat(250), unit: '', addresses: ['127.0.0.5', 'nas.home.arpa'],
  addedAt: 1_789_000_000, version: 3, futureField: 7,
};
/** As an owner might hand-add it: a dotted LAN name the URL rule refuses, and no addedAt or version. */
const BY_HAND = { id: 'nas', name: 'NAS', url: 'http://nas.home.arpa:5000/', kind: 'tool' };
const PLAIN = { id: 'grafana', name: 'Grafana', url: 'http://127.0.0.1:3000/', kind: 'dashboard', notes: '', unit: '', addresses: [], addedAt: 1_789_000_002, version: 1 };

function tmpDir(tag) { return fs.mkdtempSync(path.join(os.tmpdir(), `apps-kept-${tag}-`)); }

function seed(dir, consoles, sidecarRows = null) {
  fs.writeFileSync(appsLib.storePath(dir), JSON.stringify({ schema: 1, seeded: true, consoles, clientAddresses: [] }, null, 2));
  if (sidecarRows) {
    fs.writeFileSync(appsLib.sidecarPath(dir), JSON.stringify({ schema: 1, reporters: [], rows: sidecarRows }, null, 2));
  }
}

const onDisk = (dir) => JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8')).consoles;
const sideOnDisk = (dir) => JSON.parse(fs.readFileSync(appsLib.sidecarPath(dir), 'utf8')).rows;
const rowOnDisk = (dir, id) => onDisk(dir).find((c) => c.id === id);

/** Answers on loopback, so an add on this host passes. */
async function loopbackOnly(url) {
  if (new URL(url).hostname === '127.0.0.1') return { status: 200, body: null };
  const e = new TypeError('fetch failed');
  e.cause = { code: 'ECONNREFUSED' };
  throw e;
}

function mkStore(dir) {
  const s = appsLib.createStore({ dir, icons: false, hostAddr: '', fetch: loopbackOnly });
  s.stop();
  return s;
}

test('the first request after a start writes back no row value', () => {
  const dir = tmpDir('flush');
  try {
    seed(dir, [LATER, BY_HAND]);
    const store = mkStore(dir);
    // What this version READS — unchanged by the fix.
    assert.equal(store.get('plex').kind, 'other');
    assert.equal(store.get('plex').notes.length, appsLib.MAX_NOTES);
    assert.deepEqual(store.get('plex').addresses, ['127.0.0.5']);
    assert.equal(store.get('nas').url, '', 'an address the rule refuses is never probed');

    // GET /v1/ping from a phone: the per-request hook, and its immediate flush.
    store.noteClientAddress('192.168.1.20', '192.168.1.131');
    assert.ok(JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8')).clientAddresses.length, 'the flush happened');

    assert.deepEqual(onDisk(dir), [LATER, BY_HAND], 'both rows exactly as stored');
    assert.deepEqual(sideOnDisk(dir).plex.addresses, ['127.0.0.5', 'nas.home.arpa'],
      'and the sidecar it created holds the stored list, not this version\'s reading of it');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('an add, a patch or a remove of ANOTHER row leaves every untouched row as stored', async () => {
  const dir = tmpDir('others');
  try {
    seed(dir, [LATER, BY_HAND, PLAIN]);
    const store = mkStore(dir);

    assert.equal(store.remove('grafana').ok, true);
    assert.deepEqual(onDisk(dir), [LATER, BY_HAND], 'remove');

    const added = await store.add({ name: 'Jotter', url: 'http://127.0.0.1:8091/' });
    assert.equal(added.ok, true, JSON.stringify(added));
    assert.deepEqual(onDisk(dir).slice(0, 2), [LATER, BY_HAND], 'add');

    assert.equal(store.patch(added.app.id, { version: 1, name: 'Jotter notes' }).ok, true);
    assert.deepEqual(onDisk(dir).slice(0, 2), [LATER, BY_HAND], 'patch of another row');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('an edit writes what it changed, and only that', () => {
  const dir = tmpDir('edit');
  try {
    seed(dir, [LATER]);
    const store = mkStore(dir);

    // A rename touches the name and the version, nothing else.
    assert.equal(store.patch('plex', { version: 3, name: 'Plex Media' }).ok, true);
    assert.deepEqual(rowOnDisk(dir, 'plex'), { ...LATER, name: 'Plex Media', version: 4 });

    // A field the edit does set is written in this version's terms.
    assert.equal(store.patch('plex', { version: 4, kind: 'lab', addresses: ['127.0.0.6'] }).ok, true);
    const row = rowOnDisk(dir, 'plex');
    assert.equal(row.kind, 'lab');
    assert.deepEqual(row.addresses, ['127.0.0.6']);
    assert.equal(row.notes, LATER.notes, 'the note it did not touch is still whole');
    assert.equal(row.futureField, 7);
    assert.deepEqual(sideOnDisk(dir).plex.addresses, ['127.0.0.6']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a rollback round trip keeps a later daemon\'s values in both files', () => {
  // The later daemon wrote both files, as every daemon since the sidecar does.
  const dir = tmpDir('roundtrip');
  try {
    seed(dir, [LATER, PLAIN], { plex: { addedAt: LATER.addedAt, addresses: LATER.addresses, laterKey: 1 } });
    const store = mkStore(dir);
    store.noteClientAddress('192.168.1.20', '192.168.1.131');
    assert.equal(store.remove('grafana').ok, true);

    // Rolled forward again: what the later daemon finds.
    assert.deepEqual(onDisk(dir), [LATER]);
    assert.deepEqual(sideOnDisk(dir).plex, { addedAt: LATER.addedAt, addresses: LATER.addresses, laterKey: 1 });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('what the sidecar supplies is still written into consoles.json', () => {
  // The 2026-10-02 erasure: a rollback to 3.8 rewrote consoles.json without the
  // rows' own address lists; the sidecar kept them. That reading differs from the
  // stored row, so it is written — consoles.json gets the list back.
  const dir = tmpDir('merged');
  try {
    const { addresses, ...as38 } = { ...PLAIN, addresses: ['127.0.0.5'] };
    seed(dir, [as38], { grafana: { addedAt: PLAIN.addedAt, addresses } });
    const store = mkStore(dir);
    assert.deepEqual(store.get('grafana').addresses, ['127.0.0.5']);
    store.noteClientAddress('192.168.1.20', '192.168.1.131');
    assert.deepEqual(rowOnDisk(dir, 'grafana'), { ...as38, addresses: ['127.0.0.5'] });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
