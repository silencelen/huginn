'use strict';
// The local model shim's no-output watchdog, against a reasoning model. Ollama
// and llama-server stream a model's thinking before its answer, in fields the
// shim does not show (`delta.reasoning`, `delta.reasoning_content`); that is an
// engine at work, and the watchdog must let it finish. An engine that streams
// nothing at all is still stopped.
//
// The engine is an adopted one (adapter mode) served from this process, so the
// shim's identity check holds it to this account. PORT 0 — an ephemeral port
// per case, so this file takes NO block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SHIM = path.join(__dirname, '..', '..', '..', 'client', 'huginn-llm-shim');
const LINUX = process.platform === 'linux';
const MY_UID = typeof process.getuid === 'function' ? process.getuid() : null;
const WATCHDOG_MS = 1500;
const THINK_STEPS = 20;     // one every 150 ms: twice the watchdog, and then some
const ARGV = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages',
  '--model', 'local-testbox-tiny'];

let tmp;
before(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'shim-think-')); });
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

const sse = (o) => `data: ${JSON.stringify(o)}\n\n`;

/** An OpenAI-style streaming engine that thinks in `field` before it answers. */
async function engine(field) {
  const sockets = new Set();
  const srv = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      if (field === null) return;   // headers, then nothing: a wedged engine
      let n = 0;
      const t = setInterval(() => {
        if (res.destroyed) return clearInterval(t);
        n += 1;
        if (n <= THINK_STEPS) {
          const delta = field === 'reasoning'
            ? { role: 'assistant', content: '', reasoning: `step ${n} ` }   // Ollama's shape
            : { [field]: `step ${n} ` };                                  // llama-server's
          return res.write(sse({ choices: [{ delta }] }));
        }
        clearInterval(t);
        res.write(sse({ choices: [{ delta: { content: 'the answer' } }] }));
        res.end('data: [DONE]\n\n');
      }, 150);
    });
  });
  srv.on('connection', (s) => { sockets.add(s); s.on('close', () => sockets.delete(s)); });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  return {
    port: srv.address().port,
    close: () => new Promise((r) => { for (const s of sockets) s.destroy(); srv.close(() => r()); }),
  };
}

/** An adopted engine's config, pinned to this account, and the shim run against it. */
function runShim(port) {
  const dir = fs.mkdtempSync(path.join(tmp, 'local-'));
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'adapter', adopted: true, llmSlug: 'testbox', defaultModel: 'tiny',
    modelsBySlug: { tiny: { file: 'tiny:1b', display: 'tiny:1b' } },
    adapter: { kind: 'ollama', url: `http://127.0.0.1:${port}`, key: null, engine: { uid: MY_UID, exe: process.execPath } },
  }), { mode: 0o600 });
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SHIM, ...ARGV], {
      env: { ...process.env, HUGINN_LOCAL_DIR: dir, HUGINN_SHIM_WATCHDOG_MS: String(WATCHDOG_MS) },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.on('close', (code) => {
      const frames = out.trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
      const results = frames.filter((f) => f.type === 'result');
      resolve({ code, err, frames, result: results.length === 1 ? results[0] : null });
    });
    child.stdin.end('hi');
  });
}

for (const [field, who] of [['reasoning', 'Ollama'], ['reasoning_content', 'llama-server']]) {
  test(`a model thinking in delta.${field} (${who}) is working, not silent`, { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
    const eng = await engine(field);
    try {
      const run = await runShim(eng.port);
      assert.equal(run.code, 0, run.err);
      assert.ok(run.result, `exactly one result frame: ${JSON.stringify(run.frames)}`);
      assert.equal(run.result.is_error, false, run.result.result);
      assert.equal(run.result.result, 'the answer');
    } finally { await eng.close(); }
  });
}

test('an engine that streams nothing is still stopped by the watchdog', { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
  const eng = await engine(null);
  try {
    const run = await runShim(eng.port);
    assert.equal(run.code, 0, run.err);
    assert.ok(run.result, `exactly one result frame: ${JSON.stringify(run.frames)}`);
    assert.equal(run.result.is_error, true);
    assert.match(run.result.result, /produced no output for/);
  } finally { await eng.close(); }
});
