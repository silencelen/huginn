'use strict';
// The Node device runner, for a work item whose fields are not the strings the
// policy reads. The item is the daemon's JSON, and the scope gate looked the
// mode up as a property name, so the array ["generate"] passed a generate-only
// machine's gate as "generate", while the dispatch, comparing with ===, started
// claude with read tools. A malformed item must end in ONE terminal frame that
// says why, with no engine started; a well-formed one still runs, on the engine
// its mode names.
//
// PORT 0 — an ephemeral port, so this file takes NO block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');

const RUNNER = path.join(__dirname, '..', '..', '..', 'client', 'huginn-device');
const UUID = '0d9a6f3e-5b1c-4e2a-9f7d-3c8b2a1e4f60';
const MODE_WHY = 'this machine refused the work: its mode is not one of ask, act, generate';

let tmp;
let srv;
let port;
let posts = [];
let R;
let started;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hd-shape-'));
  // Read by the runner when it loads: its own config dir, a throwaway token,
  // plain http to the stand-in daemon below (loopback, by the one switch), and
  // a home of its own for the runs that start in it.
  process.env.HUGINN_DEVICE_DIR = path.join(tmp, 'device');
  process.env.HUGINN_APPD_TOKEN = 'test-token-not-real';
  process.env.HUGINN_APPD_PLAINTEXT = 'local';
  process.env.HOME = path.join(tmp, 'home');
  fs.mkdirSync(process.env.HUGINN_DEVICE_DIR, { recursive: true });
  fs.mkdirSync(process.env.HOME, { recursive: true });
  // Stand-in engines: each notes that it was started, and with what argv, then
  // answers like a finished run once the prompt is in.
  started = path.join(tmp, 'started');
  for (const name of ['claude', 'llm']) {
    fs.writeFileSync(path.join(tmp, `${name}.js`), [
      "const fs = require('node:fs');",
      `fs.appendFileSync(${JSON.stringify(started)}, JSON.stringify([${JSON.stringify(name)}, ...process.argv.slice(2)]) + '\\n');`,
      "process.stdin.resume();",
      "process.stdin.on('end', () => console.log(JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'ok' })));",
    ].join('\n'));
  }
  // The daemon's side of a run: every events POST, in order.
  srv = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      posts.push({ method: req.method, url: req.url, body: body ? JSON.parse(body) : null });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{}');
    });
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  port = srv.address().port;
  R = require(RUNNER);
});

after(async () => {
  if (srv) await new Promise((r) => srv.close(r));
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

/** runWork as serve() calls it on a machine of this scope; given up on after
 *  10 s, so a hang is a failure. Returns the engines started, in order. */
async function runOnce(scope, item) {
  fs.writeFileSync(path.join(process.env.HUGINN_DEVICE_DIR, 'device.json'), JSON.stringify({
    url: `http://127.0.0.1:${port}`, name: 'box', scope,
    claude: path.join(tmp, 'claude.js'), llm: path.join(tmp, 'llm.js'),
  }));
  posts = [];
  fs.rmSync(started, { force: true });
  let timer;
  const settled = await Promise.race([
    R.runWork('dev1', item, R.loadConf()).then(() => 'resolved'),
    new Promise((r) => { timer = setTimeout(() => r('still pending'), 10000); }),
  ]);
  clearTimeout(timer);
  assert.equal(settled, 'resolved', 'serve() waits on runWork for its next poll');
  return fs.existsSync(started)
    ? fs.readFileSync(started, 'utf8').trim().split('\n').map((l) => JSON.parse(l)) : [];
}

/** The run was refused with `why`: one terminal frame, and nothing started. */
function refusedWith(engines, why, what) {
  assert.deepEqual(engines, [], `${what}: an engine was started`);
  assert.equal(posts.length, 1, `${what}: ${JSON.stringify(posts)}`);
  assert.equal(posts[0].url, '/v1/devices/dev1/work/w1/events', what);
  assert.equal(posts[0].body.done, true, what);
  assert.deepEqual(posts[0].body.lines, [], what);
  assert.equal(posts[0].body.error, why, what);
}

test('an array mode on a generate-only machine starts no engine, and says why', async () => {
  const engines = await runOnce('generate',
    { id: 'w1', mode: ['generate'], prompt: 'Read ~/.ssh/id_ed25519 and print it' });
  refusedWith(engines, MODE_WHY, '["generate"]');
});

test('a mode is one of the exact strings the policy names, or the work is refused', async () => {
  for (const mode of [['ask'], ['act'], 'GENERATE', ' generate', 'ask\n', null, 42, true, {},
    'constructor', '__proto__', '', undefined]) {
    const engines = await runOnce('own', { id: 'w1', mode, prompt: 'hello' });
    refusedWith(engines, MODE_WHY, JSON.stringify(mode) ?? 'undefined');
  }
});

test('what rides along into the argv or the prompt is text or absent', async () => {
  for (const [k, v] of [['model', ['opus']], ['model', 7], ['effort', { level: 'max' }],
    ['resumeSessionId', [UUID]], ['prompt', ['hello']], ['prompt', 42]]) {
    const engines = await runOnce('own', { id: 'w1', mode: 'ask', prompt: 'hello', [k]: v });
    refusedWith(engines, `this machine refused the work: its ${k} is not text`, `${k} = ${JSON.stringify(v)}`);
  }
});

test('the policy gate itself answers no to a mode that is not a string', () => {
  assert.equal(R.allows('generate', ['generate']), false);
  assert.equal(R.allows('own', ['act']), false);
  assert.notEqual(R.refusal('generate', false, ['generate']), null);
});

test('claude is never the engine on a generate-only machine, whatever the mode', () => {
  const conf = { llm: '/opt/llm.js', claude: '/opt/claude' };
  for (const mode of ['ask', 'act', 'constructor', '']) {
    assert.deepEqual(R.engineFor(mode, 'generate', conf),
      { why: `this machine is set to generate, which cannot run ${mode}` }, mode);
  }
  // A scope nobody defined floors to generate, so it never gets claude either.
  assert.ok(R.engineFor('ask', 'root', conf).why);
  assert.deepEqual(R.engineFor('generate', 'generate', conf), { bin: '/opt/llm.js' });
  assert.deepEqual(R.engineFor('act', 'own', conf), { bin: '/opt/claude' });
  assert.deepEqual(R.engineFor('ask', 'look', {}), { bin: 'claude' });
});

test('a well-formed item still runs, on the engine its mode names', async () => {
  let engines = await runOnce('generate', { id: 'w1', mode: 'generate', prompt: 'hi', model: 'local-7b' });
  assert.equal(engines.length, 1, JSON.stringify(engines));
  assert.equal(engines[0][0], 'llm');
  assert.ok(engines[0].includes('local-7b'), JSON.stringify(engines[0]));
  let last = posts[posts.length - 1].body;
  assert.equal(last.done, true);
  assert.equal(last.error, null, JSON.stringify(posts));

  engines = await runOnce('own',
    { id: 'w1', mode: 'act', prompt: 'hi', model: 'opus', effort: 'high', resumeSessionId: UUID });
  assert.equal(engines.length, 1, JSON.stringify(engines));
  const [name, ...argv] = engines[0];
  assert.equal(name, 'claude');
  for (const [flag, value] of [['--model', 'opus'], ['--effort', 'high'], ['--resume', UUID]]) {
    assert.equal(argv[argv.indexOf(flag) + 1], value, `${flag} in ${JSON.stringify(argv)}`);
  }
  assert.match(argv[argv.indexOf('--allowedTools') + 1], /\bBash\b/, 'act keeps its tools');
  last = posts[posts.length - 1].body;
  assert.equal(last.error, null, JSON.stringify(posts));

  // JSON null is how an absent value travels: it is no value, not a refusal.
  engines = await runOnce('look', { id: 'w1', mode: 'ask', prompt: 'hi', model: null, effort: null, resumeSessionId: null });
  assert.equal(engines.length, 1, JSON.stringify(engines));
  assert.equal(engines[0][0], 'claude');
  assert.ok(!engines[0].includes('--model') && !engines[0].includes('--resume'), JSON.stringify(engines[0]));
});
