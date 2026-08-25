'use strict';
// The shim's gate: the claude-CLI contract on its outside, honest failure on
// its inside. Run with:  node --test scripts/test-llm-shim.js
//
// PORT ALLOCATION: this file claims 18790-18799 (fake backends). The appd test
// files own 8788-10099 (see the table in any of them); nothing else in the
// repo binds in the 18790s.
//
// The contract verifier here IS the drift gate: it asserts exactly what
// server/appd handleClaudeEvent consumes — init first with a uuid session_id,
// text deltas, at least one assistant frame, exactly one terminal result with
// is_error/duration_ms/total_cost_usd/num_turns — so a shim that "works" but
// speaks the wrong dialect fails HERE, not live on a serving machine.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');

const SHIM = path.resolve(__dirname, '..', 'client', 'huginn-llm-shim');
const shim = require(SHIM);

const PORT = 18790 + (process.pid % 5);
const UUID_RE = /^[0-9a-f-]{36}$/i;

let tmp;
before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'llm-shim-'));
  // For the IN-PROCESS calls (sessions, localDir) as well as the spawned shim:
  // without this the module writes into the real ~/.config/huginn-local.
  process.env.HUGINN_LOCAL_DIR = tmp;
  fs.writeFileSync(path.join(tmp, 'api-key'), 'k-test-key\n', { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'local.json'), JSON.stringify({
    mode: 'managed', port: PORT, llmSlug: 'testbox',
    defaultModel: 'tiny', modelsBySlug: { tiny: { file: 'tiny.gguf', display: 'Tiny' } },
  }));
});
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/** Spawn the shim as the runner would, collect its NDJSON frames. */
function runShim(argv, promptText, env = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SHIM, ...argv], {
      env: { ...process.env, HUGINN_LOCAL_DIR: tmp, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let out = '', err = '';
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.on('close', (code) => {
      const frames = out.trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
      resolve({ code, frames, err, rawLines: out.trim().split('\n').filter(Boolean) });
    });
    child.stdin.end(promptText);
  });
}

/** Exactly what handleClaudeEvent consumes — the drift gate. */
function verifyFrames({ frames, rawLines }) {
  assert.ok(frames.length >= 2, 'at least init + result');
  const init = frames[0];
  assert.equal(init.type, 'system');
  assert.equal(init.subtype, 'init');
  assert.match(String(init.session_id), UUID_RE, 'the daemon stores this and feeds it back as --resume');
  for (const f of frames.filter((x) => x.type === 'stream_event')) {
    assert.equal(f.event.type, 'content_block_delta');
    assert.equal(f.event.delta.type, 'text_delta');
    assert.equal(typeof f.event.delta.text, 'string');
  }
  const results = frames.filter((x) => x.type === 'result');
  assert.equal(results.length, 1, 'exactly one terminal frame');
  const r = results[0];
  assert.equal(typeof r.is_error, 'boolean');
  assert.equal(typeof r.duration_ms, 'number');
  assert.equal(r.total_cost_usd, 0, 'a local run never claims spend');
  assert.equal(r.num_turns, 1);
  assert.ok(!frames.some((x) => x.type === 'tool_use' || (x.event && x.event.type === 'tool_use')),
    'no tool surface, no tool frames — ever');
  for (const line of rawLines) {
    assert.ok(Buffer.byteLength(line) <= 128 * 1024, 'no line ever approaches the runner cap');
  }
  return r;
}

const ARGV = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages'];

// ------------------------------------------------------------- pure pieces

test('parseArgv accepts the runner argv and refuses anything else', () => {
  const ok = shim.parseArgv([...ARGV, '--model', 'local-testbox-tiny', '--resume', crypto.randomUUID()]);
  assert.equal(ok.error, undefined);
  assert.equal(ok.model, 'local-testbox-tiny');
  const bad = shim.parseArgv(['--frobnicate']);
  assert.match(bad.error, /unknown flag/);
});

test('mapModel is strict: own prefix strips, foreign hosts and unknowns refuse', () => {
  const conf = { llmSlug: 'testbox', defaultModel: 'tiny', modelsBySlug: { tiny: {} } };
  assert.equal(shim.mapModel('local-testbox-tiny', conf).key, 'tiny');
  assert.equal(shim.mapModel('tiny', conf).key, 'tiny');
  assert.equal(shim.mapModel(null, conf).key, 'tiny', 'absent means the default');
  assert.match(shim.mapModel('local-otherbox-tiny', conf).error, /different machine/,
    'an id minted for another host is never quietly served here');
  assert.match(shim.mapModel('local-testbox-nope', conf).error, /does not serve/);
  assert.match(shim.mapModel('gpt-4', conf).error, /unknown model/);
});

test('splitText chunks losslessly under the cap; capBytes admits its cut', () => {
  const big = 'x'.repeat(300 * 1024) + 'END';
  const parts = shim.splitText(big);
  assert.ok(parts.length >= 3);
  for (const p of parts) assert.ok(Buffer.byteLength(p) <= shim.ASSISTANT_CHUNK_BYTES);
  assert.equal(parts.join(''), big, 'lossless when concatenated');
  const capped = shim.capBytes(big, 8 * 1024);
  assert.ok(Buffer.byteLength(capped) <= 8 * 1024 + 64);
  assert.match(capped, /truncated/);
});

test('versionString is version+contenthash and changes with the content', () => {
  const v = shim.versionString();
  assert.match(v, /^\d+\.\d+\.\d+\+[0-9a-f]{12}$/);
});

test('sessions cap by dropping the oldest turns and prune by age and count', () => {
  const sid = crypto.randomUUID();
  const turns = [];
  for (let i = 0; i < 50; i++) {
    turns.push({ role: 'user', content: `q${i} ` + 'x'.repeat(20_000) });
    turns.push({ role: 'assistant', content: `a${i}` });
  }
  shim.saveSession(sid, turns);
  const loaded = shim.loadSession(sid);
  const bytes = fs.statSync(path.join(tmp, 'sessions', `${sid}.jsonl`)).size;
  assert.ok(bytes <= shim.SESSION_CAP_BYTES, `capped (${bytes})`);
  assert.equal(loaded[loaded.length - 1].content, 'a49', 'the newest turns survive');

  const old = path.join(tmp, 'sessions', `${crypto.randomUUID()}.jsonl`);
  fs.writeFileSync(old, '{"role":"user","content":"ancient"}\n');
  const past = Date.now() - shim.SESSION_MAX_AGE_MS - 60_000;
  fs.utimesSync(old, past / 1000, past / 1000);
  shim.pruneSessions();
  assert.ok(!fs.existsSync(old), 'aged out');
  assert.ok(fs.existsSync(path.join(tmp, 'sessions', `${sid}.jsonl`)), 'the fresh one stays');
});

// --------------------------------------------------------------- contract

test('--contract-check emits the exact dialect handleClaudeEvent consumes', async () => {
  const run = await runShim(['--contract-check'], '');
  assert.equal(run.code, 0, run.err);
  const r = verifyFrames(run);
  assert.equal(r.is_error, false);
  assert.ok(run.frames.some((f) => f.type === 'assistant'
    && f.message.content[0].type === 'text'), 'an assistant frame with text content');
});

// ------------------------------------------------------------------- e2e

function sseServer(handler) {
  return new Promise((resolve) => {
    const seen = [];
    const srv = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        seen.push({ url: req.url, auth: req.headers.authorization, body: body ? JSON.parse(body) : null });
        handler(req, res, seen[seen.length - 1]);
      });
    });
    srv.listen(PORT, '127.0.0.1', () => resolve({ srv, seen }));
  });
}

const sse = (res, deltas, { stall = false } = {}) => {
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  for (const d of deltas) {
    res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: d } }] })}\n\n`);
  }
  if (!stall) { res.write('data: [DONE]\n\n'); res.end(); }
};

test('a run streams deltas, carries the key, saves its session, and resumes', async () => {
  const { srv, seen } = await sseServer((req, res) => sse(res, ['Hel', 'lo ', 'there']));
  try {
    const one = await runShim([...ARGV, '--model', 'local-testbox-tiny'], 'first question');
    assert.equal(one.code, 0, one.err);
    const r1 = verifyFrames(one);
    assert.equal(r1.is_error, false, r1.result);
    assert.equal(r1.result, 'Hello there');
    assert.equal(seen[0].auth, 'Bearer k-test-key', 'the minted key rides every request');
    assert.equal(seen[0].body.model, 'tiny', 'the composite id was stripped to the swap key');
    assert.equal(seen[0].body.messages.at(-1).content, 'first question');

    const sid = one.frames[0].session_id;
    const two = await runShim([...ARGV, '--resume', sid], 'second question');
    assert.equal(verifyFrames(two).is_error, false);
    const history = seen[1].body.messages.map((m) => m.content);
    assert.ok(history.includes('first question') && history.includes('Hello there'),
      'the resumed request replays the saved exchange');
  } finally { srv.close(); }
});

test('an unknown model is an error RESULT and no request is ever sent', async () => {
  const { srv, seen } = await sseServer((req, res) => sse(res, ['nope']));
  try {
    const run = await runShim([...ARGV, '--model', 'local-otherbox-tiny'], 'hi');
    assert.equal(run.code, 0, 'the frame is the report, the exit stays 0');
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /different machine/);
    assert.equal(seen.length, 0, 'no silent substitution — and no request at all');
  } finally { srv.close(); }
});

test('a refused key is an honest error result', async () => {
  const { srv } = await sseServer((req, res) => { res.writeHead(401); res.end('no'); });
  try {
    const run = await runShim([...ARGV], 'hi');
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /key/);
  } finally { srv.close(); }
});

test('the watchdog ends a stalled generation with an honest error', async () => {
  const { srv } = await sseServer((req, res) => sse(res, ['one delta then silence'], { stall: true }));
  try {
    const run = await runShim([...ARGV], 'hi', { HUGINN_SHIM_WATCHDOG_MS: '1200' });
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /no output for/);
    assert.equal(run.code, 0);
  } finally { srv.close(); }
});
