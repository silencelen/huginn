'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApiLane, costOf, monthOf } = require('../lib/apilane');

function tmpdir() { return fs.mkdtempSync(path.join(os.tmpdir(), 'apilane-')); }
function keyFile(dir, k = 'sk-test') { const f = path.join(dir, 'key'); fs.writeFileSync(f, `${k}\n`); return f; }
function okFetch(calls, { text = 'a\nb\nc', usage = { input_tokens: 1000, output_tokens: 100 }, status = 200 } = {}) {
  return async (url, init) => {
    calls.push({ url, init });
    return { ok: status >= 200 && status < 300, status, json: async () => ({ model: 'claude-haiku-5-5', usage, content: [{ type: 'text', text }] }) };
  };
}

test('off without a key file: null, and nothing is sent', async () => {
  const calls = [];
  const lane = createApiLane({ dataDir: tmpdir(), env: {}, fetchImpl: okFetch(calls) });
  assert.equal(await lane.complete('hi'), null);
  assert.equal(calls.length, 0);
  assert.equal(lane.status().enabled, false);
});

test('with a key: posts to /v1/messages with the key header and returns the text', async () => {
  const dir = tmpdir();
  const calls = [];
  const lane = createApiLane({ dataDir: dir, env: { HUGINN_API_KEY_FILE: keyFile(dir) }, fetchImpl: okFetch(calls) });
  assert.equal(await lane.complete('hi'), 'a\nb\nc');
  assert.match(calls[0].url, /\/v1\/messages$/);
  assert.equal(calls[0].init.headers['x-api-key'], 'sk-test');
  const sent = JSON.parse(calls[0].init.body);
  assert.equal(sent.model, 'claude-haiku-5-5');
  assert.deepEqual(sent.messages, [{ role: 'user', content: 'hi' }]);
  assert.deepEqual(sent.thinking, { type: 'disabled' }, 'Haiku 5.5 thinks by default and eats the budget');
});

test('spend is charged from the reported usage and the cap stops further calls', async () => {
  const dir = tmpdir();
  const calls = [];
  // 1M in + 1M out on haiku 5.5 = $0.60 per call; a $1 cap admits two calls, refuses the third.
  const lane = createApiLane({
    dataDir: dir,
    env: { HUGINN_API_KEY_FILE: keyFile(dir), HUGINN_API_MONTHLY_USD: '1' },
    fetchImpl: okFetch(calls, { usage: { input_tokens: 1e6, output_tokens: 1e6 } }),
  });
  assert.ok(await lane.complete('1'));
  assert.ok(await lane.complete('2'));
  assert.equal(await lane.complete('3'), null, 'over the cap: fall back');
  assert.equal(calls.length, 2, 'and the third was never sent');
  assert.ok(Math.abs(lane.status().spentUsd - 1.2) < 1e-9);
});

test('the ledger starts over in a new month', async () => {
  const dir = tmpdir();
  let t = Date.UTC(2026, 9, 31, 23, 0);
  const calls = [];
  const lane = createApiLane({
    dataDir: dir, now: () => t,
    env: { HUGINN_API_KEY_FILE: keyFile(dir), HUGINN_API_MONTHLY_USD: '0.5' },
    fetchImpl: okFetch(calls, { usage: { input_tokens: 1e6, output_tokens: 1e6 } }),
  });
  assert.ok(await lane.complete('oct'));
  assert.equal(await lane.complete('oct again'), null);
  t = Date.UTC(2026, 10, 1, 0, 1);
  assert.ok(await lane.complete('nov'), 'a new month has a new budget');
  assert.equal(lane.status().month, '2026-11');
});

test('a non-2xx or a network error is null, never a throw', async () => {
  const dir = tmpdir();
  const env = { HUGINN_API_KEY_FILE: keyFile(dir) };
  const bad = createApiLane({ dataDir: dir, env, fetchImpl: okFetch([], { status: 400 }) });
  assert.equal(await bad.complete('x'), null);
  const boom = createApiLane({ dataDir: dir, env, fetchImpl: async () => { throw new Error('ECONNRESET'); } });
  assert.equal(await boom.complete('x'), null);
});

test('cost: list price, cache tokens count as input, unknown models priced as opus', () => {
  assert.ok(Math.abs(costOf('claude-haiku-5-5', { input_tokens: 1e6, output_tokens: 0 }) - 0.10) < 1e-12);
  assert.ok(Math.abs(costOf('claude-haiku-5-5', { input_tokens: 0, output_tokens: 1e6 }) - 0.50) < 1e-12);
  assert.ok(Math.abs(costOf('claude-haiku-5-5', { cache_read_input_tokens: 1e6 }) - 0.10) < 1e-12);
  assert.ok(Math.abs(costOf('mystery', { input_tokens: 1e6 }) - 4) < 1e-12);
  assert.equal(monthOf(Date.UTC(2026, 0, 5)), '2026-01');
});

test('HUGINN_API_KEY_VAR reads the key out of an env-file in place', async () => {
  const dir = tmpdir();
  const f = path.join(dir, 'secrets.env');
  fs.writeFileSync(f, '# comment\nOTHER=nope\nexport CLAUDE_API_KEY="sk-from-envfile"\n');
  const calls = [];
  const lane = createApiLane({ dataDir: dir, env: { HUGINN_API_KEY_FILE: f, HUGINN_API_KEY_VAR: 'CLAUDE_API_KEY' }, fetchImpl: okFetch(calls) });
  assert.ok(await lane.complete('hi'));
  assert.equal(calls[0].init.headers['x-api-key'], 'sk-from-envfile');
  const missing = createApiLane({ dataDir: dir, env: { HUGINN_API_KEY_FILE: f, HUGINN_API_KEY_VAR: 'ABSENT' }, fetchImpl: okFetch([]) });
  assert.equal(await missing.complete('hi'), null, 'a var that is not there means off');
});
