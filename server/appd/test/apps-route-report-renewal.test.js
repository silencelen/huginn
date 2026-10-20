'use strict';
// lib/apps.js — a route a client reported stays REQUIRED for as long as that
// client keeps talking to huginn, not for thirty days after it launched.
//
// Both clients report their routes once per launch and again only when the set
// changes. An always-on desktop therefore reported once, kept polling for
// weeks, and on day 31 its route quietly stopped being required: an app that
// did not answer there was added with a 201, its row went green, and the
// address lost its "saved on <device>" — the refusal decision 54 exists to make,
// gone for the one device still using the route. Every request a client makes
// carries its X-Huginn-Client; the per-request hook now hands that to the store,
// which renews that client's report.
//
// Pure lib, a fake clock, an injected fetch. No daemon, no network.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const appsLib = require('../lib/apps');

const DAY_MS = 24 * 3600 * 1000;
const ROUTE = '192.168.1.20';

/** A fetch that answers only on loopback, so a required route that is not served refuses an add. */
async function loopbackOnly(url) {
  if (new URL(url).hostname === '127.0.0.1') return { status: 200, body: null };
  const e = new TypeError('fetch failed');
  e.cause = { code: 'ECONNREFUSED' };
  throw e;
}

function harness() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-report-renewal-'));
  const clock = { ms: 1_800_000_000_000 };
  const make = () => {
    const s = appsLib.createStore({ dir, icons: false, hostAddr: '', nowMs: () => clock.ms, fetch: loopbackOnly });
    s.stop();
    return s;
  };
  return { dir, clock, make, done: () => fs.rmSync(dir, { recursive: true, force: true }) };
}

test('a client that keeps polling keeps its route required past the month, across a restart', async () => {
  const h = harness();
  try {
    let store = h.make();
    store.list();
    store.noteReportedRoutes([ROUTE], 'desk-1', 'DESK');
    // What the always-on desktop does from then on: poll, never report again.
    for (let day = 1; day <= 45; day++) {
      h.clock.ms += DAY_MS;
      store.noteClientAddress(ROUTE, '192.168.1.131', 'desk-1');
      if (day === 15) store = h.make();   // an appd upgrade mid-way: the renewal was written down
    }
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1', ROUTE], 'still required on day 45');
    assert.deepEqual(store.pinnersOf(ROUTE), [{ client: 'desk-1', name: 'DESK' }]);

    // And it still does its job: an app that does not answer there is refused.
    const r = await store.add({ name: 'Loopback only', url: 'http://127.0.0.1:9999/' });
    assert.equal(r.ok, false, JSON.stringify(r));
    assert.equal(r.status, 422);
    assert.ok(r.reachable.addresses.some((a) => a.addr === ROUTE && a.required && a.ok === false));

    // The renewed stamp reached the disk, so a restart now keeps it too.
    assert.deepEqual(h.make().requiredAddresses(), ['127.0.0.1', ROUTE]);
  } finally {
    h.done();
  }
});

test('another client polling does not renew a silent client\'s report', () => {
  const h = harness();
  try {
    const store = h.make();
    store.list();
    store.noteReportedRoutes([ROUTE], 'desk-1');
    for (let day = 1; day <= 31; day++) {
      h.clock.ms += DAY_MS;
      store.noteClientAddress(ROUTE, '192.168.1.140', 'phone-2');
    }
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1'], 'desk-1 has said nothing for a month');
  } finally {
    h.done();
  }
});

test('a request with no client id renews nothing, not even the shared header-less report', () => {
  const h = harness();
  try {
    const store = h.make();
    store.list();
    store.noteReportedRoutes([ROUTE], undefined);
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1', ROUTE]);
    for (let day = 1; day <= 31; day++) {
      h.clock.ms += DAY_MS;
      store.noteClientAddress(ROUTE, '192.168.1.140');
      store.noteClientAddress(ROUTE, '192.168.1.141', '');
    }
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1'],
      'every header-less caller shares one id, so one of them talking proves nothing about another');
  } finally {
    h.done();
  }
});

test('the per-request hook hands the store the caller\'s X-Huginn-Client', () => {
  // The renewal above is only as good as this one argument: without it every
  // request is header-less to the store and nothing is ever renewed. Checked on
  // the source because the hook runs on every authorised request and a daemon
  // test cannot fast-forward a month.
  const src = fs.readFileSync(path.join(__dirname, '..', 'huginn-appd.js'), 'utf8');
  const calls = src.match(/\.noteClientAddress\([^)]*\)/g) || [];
  assert.equal(calls.length, 1, `one hook call: ${JSON.stringify(calls)}`);
  assert.match(calls[0], /req\.headers\['x-huginn-client'\]/, calls[0]);
});

test('a client that stops talking still expires a month after its last word', () => {
  const h = harness();
  try {
    const store = h.make();
    store.list();
    store.noteReportedRoutes([ROUTE], 'desk-1');
    h.clock.ms += 20 * DAY_MS;
    store.noteClientAddress(ROUTE, '192.168.1.131', 'desk-1');   // its last request
    h.clock.ms += (appsLib.REPORTED_ADDR_TTL_SEC - 60) * 1000;
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1', ROUTE], 'a month from the last word, not from the report');
    h.clock.ms += 120 * 1000;
    assert.deepEqual(store.requiredAddresses(), ['127.0.0.1']);
  } finally {
    h.done();
  }
});
