'use strict';
// The Node device runner's last batch. When the engine exits, the flush loop
// may still be posting the run's final lines (the answer, the result); the
// terminal frame must not overtake them. The daemon settles a run on its
// terminal frame ("the run ended without a result" when it has seen none) and
// answers any later batch for it 404.
//
// The stand-in daemon below holds the FIRST batch open, as a slow uplink does,
// and the stand-in engine exits only once that batch has arrived: the race,
// made certain instead of likely.
//
// PORT 0 — an ephemeral port per case, so this file takes NO block from the
// port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');

const RUNNER = path.join(__dirname, '..', '..', '..', 'client', 'huginn-device');
const HOLD_MS = 1500;

const LINES = [
  { type: 'system', subtype: 'init', session_id: '00000000-0000-4000-8000-000000000001' },
  { type: 'assistant', message: { content: [{ type: 'text', text: 'the whole answer' }] } },
  { type: 'result', is_error: false, result: 'the whole answer', num_turns: 1, duration_ms: 5 },
].map((o) => JSON.stringify(o));

let tmp;
let R;
let cases = 0;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hd-final-'));
  // Read by the runner when it loads: its own config dir, a throwaway token, and
  // plain http to the stand-in daemon (loopback, by the one switch).
  process.env.HUGINN_DEVICE_DIR = path.join(tmp, 'device');
  process.env.HUGINN_APPD_TOKEN = 'test-token-not-real';
  process.env.HUGINN_APPD_PLAINTEXT = 'local';
  fs.mkdirSync(process.env.HUGINN_DEVICE_DIR, { recursive: true });
  R = require(RUNNER);
});

after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/**
 * One run against a daemon that answers the first batch `firstStatus` after
 * HOLD_MS. Returns what the daemon had accepted when the terminal frame came.
 */
async function oneRun(firstStatus) {
  cases += 1;
  const marker = path.join(tmp, `batch-arrived-${cases}`);
  const engine = path.join(tmp, `engine-${cases}.js`);
  // Prints the run's lines in one write, then exits once the daemon holds them.
  fs.writeFileSync(engine, `'use strict';
const fs = require('fs');
process.stdout.write(${JSON.stringify(LINES.join('\n') + '\n')});
const t = setInterval(() => { if (fs.existsSync(${JSON.stringify(marker)})) { clearInterval(t); process.exit(0); } }, 10);
setTimeout(() => process.exit(3), 20000);
`);

  const accepted = [];
  let atTerminal = null;
  let terminals = 0;
  let batches = 0;
  let firstAnswered;
  const firstDone = new Promise((r) => { firstAnswered = r; });
  const srv = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const b = JSON.parse(body);
      const answer = (status) => {
        if (status === 200) accepted.push(...b.lines);
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(status === 200 ? '{}' : '{"error":"try again"}');
      };
      if (b.done) {
        terminals += 1;
        atTerminal = { lines: accepted.slice(), body: b };
        return answer(200);
      }
      batches += 1;
      if (batches > 1) return answer(200);
      fs.writeFileSync(marker, '');
      setTimeout(() => { answer(firstStatus); firstAnswered(); }, HOLD_MS);
    });
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  try {
    fs.writeFileSync(path.join(process.env.HUGINN_DEVICE_DIR, 'device.json'), JSON.stringify({
      url: `http://127.0.0.1:${srv.address().port}`, name: 'box', scope: 'work', root: tmp, claude: engine,
    }));
    let timer;
    const settled = await Promise.race([
      R.runWork('dev1', { id: `w${cases}`, mode: 'ask', prompt: 'hi' }, R.loadConf()).then(() => 'resolved'),
      new Promise((r) => { timer = setTimeout(() => r('still pending'), 15000); }),
    ]);
    clearTimeout(timer);
    assert.equal(settled, 'resolved');
    await firstDone;
  } finally {
    await new Promise((r) => srv.close(r));
  }
  assert.equal(terminals, 1, 'one terminal frame');
  assert.equal(atTerminal.body.exitCode, 0);
  assert.equal(atTerminal.body.error, null);
  return atTerminal.lines;
}

test('the terminal frame waits for the batch still uploading', async () => {
  assert.deepEqual(await oneRun(200), LINES,
    'the answer and the result reached the daemon before the run was settled');
});

test('a batch that blips while the engine exits is sent again before the terminal frame', async () => {
  assert.deepEqual(await oneRun(503), LINES,
    'the lines the failed POST put back were delivered, not dropped');
});
