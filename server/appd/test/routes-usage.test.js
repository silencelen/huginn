'use strict';
// GET /v1/usage comes from the daemon's own ledger (lib/usageledger), never from
// a ccusage run — end to end through a real daemon.
//
// SAFETY:
//   * NO REAL ~/.claude. HUGINN_APPD_CLAUDE_DIR is a scratch dir with a synthetic
//     projects tree (alex's), so the sweep never opens the owner's transcripts.
//   * A ccusage STUB first on PATH. It records every invocation to a file; the
//     test asserts the file never appears. The real binary, if installed, is
//     never reached.
//   * A PRIVATE tmux socket, no sessions, no network, no owner contact
//     (retry-fetch → no-owner-contact).
//
// PORT ALLOCATION (the table lives in routes-lifecycle.test.js):
//   routes-usage        11850 + pid%50   -> 11850-11899

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const L = require('../lib/usageledger');

const PORT = 11850 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-usage-${process.pid}`;

const CCUSAGE_STUB = `#!/bin/sh
echo "$@" >> "$HUGINN_TEST_CCUSAGE_LOG"
echo '{"daily":[],"totals":{}}'
`;

let tmp, token, daemon, ccusageLog, transcript;

function at(day, hhmm = '12:00') {
  const [y, m, d] = day.split('-').map(Number);
  const [hh, mm] = hhmm.split(':').map(Number);
  return new Date(y, m - 1, d, hh, mm, 0).toISOString();
}

function assistant(id, ts, out) {
  return {
    type: 'assistant', uuid: `u-${id}`, timestamp: ts, cwd: '/home/alex/work', sessionId: 's-alex', version: '2.1.316',
    message: {
      model: 'claude-opus-5-5', id, type: 'message', role: 'assistant', content: [{ type: 'text', text: 'hi' }],
      usage: {
        input_tokens: 3, output_tokens: out, cache_creation_input_tokens: 100, cache_read_input_tokens: 1000,
        cache_creation: { ephemeral_5m_input_tokens: 100, ephemeral_1h_input_tokens: 0 },
      },
    },
  };
}

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* not json */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-usage-'));
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'ccusage'), CCUSAGE_STUB, { mode: 0o755 });
  ccusageLog = path.join(tmp, 'ccusage-calls.log');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  // alex's scratch ~/.claude: one session transcript and one subagent transcript,
  // both written today, plus an api-request line that must not be counted.
  const claudeDir = path.join(tmp, 'claude');
  const slug = path.join(claudeDir, 'projects', '-home-alex-work');
  fs.mkdirSync(path.join(slug, 's-alex', 'subagents'), { recursive: true });
  const today = L.localDay(new Date());
  transcript = path.join(slug, 's-alex.jsonl');
  fs.writeFileSync(transcript, [
    JSON.stringify({ type: 'user', uuid: 'u0', timestamp: at(today), message: { role: 'user', content: 'hello' } }),
    JSON.stringify(assistant('msg_a1', at(today, '09:00'), 10)),
    JSON.stringify({ ...assistant('msg_a1', at(today, '09:00'), 10), uuid: 'u-a1-block2' }),
    JSON.stringify({ type: 'api-request', sessionId: 's-alex', id: 'r1', timestamp: at(today), request: { messages: [{ role: 'assistant', usage: { output_tokens: 99999 } }], pad: 'x'.repeat(50_000) } }),
    JSON.stringify(assistant('msg_a2', at(today, '09:01'), 20)),
    '',
  ].join('\n'));
  fs.writeFileSync(path.join(slug, 's-alex', 'subagents', 'agent-a1.jsonl'),
    JSON.stringify({ ...assistant('msg_sub1', at(today, '09:02'), 5), isSidechain: true }) + '\n');

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HUGINN_TEST_CCUSAGE_LOG: ccusageLog,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/scratchpads');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('/v1/usage answers at once, from the daemon\'s own ledger, with the shape the apps decode', async () => {
  const t0 = Date.now();
  const r = await api('/v1/usage');
  assert.ok(Date.now() - t0 < 2_000, 'the answer never waits for a sweep');
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.source, 'huginn');
  assert.strictEqual(r.body.costIsEstimate, true);
  assert.strictEqual(r.body.correlation, null, 'no weekly ccusage run has happened');
  for (const k of ['data', 'computedAt', 'stale', 'refreshing', 'error']) assert.ok(k in r.body, k);
  assert.deepStrictEqual(Object.keys(r.body.data).sort(), ['daily', 'today', 'week']);
});

test('the first request triggers a background sweep and the next one carries today\'s spend', async () => {
  let body = null;
  for (let i = 0; i < 100; i++) {
    body = (await api('/v1/usage')).body;
    if (body.data.today && body.data.today.totalTokens > 0 && !body.refreshing) break;
    await wait(100);
  }
  assert.ok(body.data.today, 'today is filled in');
  // msg_a1 (once, though written as two blocks) + msg_a2 + the subagent's msg_sub1;
  // the api-request line's 99999 output tokens are not in here.
  assert.strictEqual(body.data.today.outputTokens, 35);
  assert.strictEqual(body.data.today.inputTokens, 9);
  assert.strictEqual(body.data.today.cacheCreationTokens, 300);
  assert.strictEqual(body.data.today.cacheReadTokens, 3000);
  assert.strictEqual(body.data.today.totalTokens, 3344);
  assert.ok(body.data.today.costUsd > 0);
  assert.strictEqual(body.data.week.days, 1);
  assert.strictEqual(body.data.week.totalTokens, 3344);
  assert.strictEqual(body.data.daily.length, 1);
  assert.strictEqual(body.stale, false);
  assert.ok(body.computedAt > 0);
  assert.strictEqual(body.error, null);
});

test('a record appended to a transcript is picked up without re-reading what was read', async () => {
  const today = L.localDay(new Date());
  fs.appendFileSync(transcript, JSON.stringify(assistant('msg_a3', at(today, '09:03'), 100)) + '\n');
  // The sweep TTL is 60 s, so a request alone would not re-sweep yet: the ledger
  // on disk says what was read, and the next sweep starts from there.
  const ledgerFile = path.join(tmp, 'data', 'usage', 'ledger.json');
  const led = L.load(ledgerFile);
  assert.ok(led.files[transcript], 'the transcript has a stored offset');
  assert.ok(led.files[transcript].offset > 0);
  assert.ok(led.files[transcript].offset < fs.statSync(transcript).size, 'the appended record is past the stored offset');
  const r = await L.sweep(led, [path.join(tmp, 'claude', 'projects')]);
  assert.strictEqual(r.counted, 1);
  assert.ok(r.bytesRead < 1000, `read ${r.bytesRead} bytes: more than the appended record`);
});

test('ccusage is never run for a request', async () => {
  for (let i = 0; i < 5; i++) await api('/v1/usage');
  await wait(300);
  assert.strictEqual(fs.existsSync(ccusageLog), false, `ccusage was invoked: ${fs.existsSync(ccusageLog) && fs.readFileSync(ccusageLog, 'utf8')}`);
});
