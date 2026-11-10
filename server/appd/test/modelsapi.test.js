'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const { createModelsApi } = require('../lib/modelsapi');
const { discoverModels, selectModels } = require('../lib/models');

function reply(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function harness(responses, { token = 'tok' } = {}) {
  let t = 1_000_000;
  const calls = [];
  const logs = [];
  const api = createModelsApi({
    url: 'https://example.test/v1/models',
    readToken: () => token,
    fetchImpl: async (url, opts) => {
      calls.push({ url, opts });
      const r = responses.shift();
      if (r instanceof Error) throw r;
      return r;
    },
    ttlMs: 1000, retryMs: 100, timeoutMs: 50,
    log: (s) => logs.push(s),
    now: () => t,
  });
  return { api, calls, logs, advance: (ms) => { t += ms; } };
}

test('the first list waits for the server and sends the login as an OAuth bearer', async () => {
  const h = harness([reply(200, { data: [{ id: 'claude-fable-5-2' }, { id: 'claude-opus-5-5' }, { id: 'not-claude' }, {}] })]);
  assert.deepEqual(await h.api.list(), ['claude-fable-5-2', 'claude-opus-5-5']);
  const hd = h.calls[0].opts.headers;
  assert.equal(hd.Authorization, 'Bearer tok');
  assert.equal(hd['anthropic-beta'], 'oauth-2025-04-20');
  assert.equal(hd['anthropic-version'], '2023-06-01');
});

test('a fresh list is served from the cache; a stale one refreshes', async () => {
  const h = harness([reply(200, { data: [{ id: 'claude-opus-5-5' }] }), reply(200, { data: [{ id: 'claude-fable-5-2' }] })]);
  await h.api.list();
  await h.api.list();
  assert.equal(h.calls.length, 1, 'no second call inside the ttl');
  h.advance(1001);
  await h.api.list();                       // starts the refresh, serves the old list
  await new Promise((r) => setImmediate(r));
  assert.equal(h.calls.length, 2);
  assert.deepEqual(await h.api.list(), ['claude-fable-5-2']);
});

test('a failed refresh keeps the last good list and retries sooner', async () => {
  const h = harness([reply(200, { data: [{ id: 'claude-opus-5-5' }] }), reply(401, {}), reply(200, { data: [{ id: 'claude-fable-5-2' }] })]);
  await h.api.list();
  h.advance(1001);
  await h.api.list(); await new Promise((r) => setImmediate(r));
  assert.deepEqual(await h.api.list(), ['claude-opus-5-5'], 'a 401 does not empty the menu');
  assert.match(h.logs.join('\n'), /HTTP 401/);
  h.advance(101);
  await h.api.list(); await new Promise((r) => setImmediate(r));
  assert.deepEqual(await h.api.list(), ['claude-fable-5-2']);
});

test('no login is an empty list, not an error, and makes no call', async () => {
  const h = harness([], { token: null });
  assert.deepEqual(await h.api.list(), []);
  assert.equal(h.calls.length, 0);
});

test('a thrown fetch (network, timeout) never escapes', async () => {
  const h = harness([new Error('ECONNREFUSED')]);
  assert.deepEqual(await h.api.list(), []);
  assert.match(h.logs.join('\n'), /unreachable: ECONNREFUSED/);
});

test('the server list is merged with the binary scan through one selection', async () => {
  // No binary at this path: the server's ids alone decide, and they are selected
  // exactly as scanned ids would be (newest two per family, versions visible).
  const merged = await discoverModels('/nonexistent/claude.exe', undefined,
    ['claude-fable-5-2', 'claude-fable-5-1', 'claude-fable-5', 'claude-opus-5-5', 'claude-mythos-5-2']);
  assert.deepEqual(merged, selectModels(['claude-fable-5-2', 'claude-fable-5-1', 'claude-fable-5', 'claude-opus-5-5']));
  assert.deepEqual(merged.map((m) => m.display), ['Fable 5.2', 'Fable 5.1', 'Opus 5.5']);
});
