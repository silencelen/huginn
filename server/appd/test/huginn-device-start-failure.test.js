'use strict';
// The Node device runner, for a run that cannot start: a work root that is not
// a directory, or an engine spawn() refuses outright. Either must end the run
// with the reason in ONE terminal frame and hand the runner back to its poll
// loop, which is waiting on runWork.
//
// PORT 0 — an ephemeral port, so this file takes NO block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');

const RUNNER = path.join(__dirname, '..', '..', '..', 'client', 'huginn-device');

let tmp;
let srv;
let port;
let posts = [];
let R;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hd-start-'));
  // Read by the runner when it loads: its own config dir, a throwaway token, and
  // plain http to the stand-in daemon below (loopback, by the one switch).
  process.env.HUGINN_DEVICE_DIR = path.join(tmp, 'device');
  process.env.HUGINN_APPD_TOKEN = 'test-token-not-real';
  process.env.HUGINN_APPD_PLAINTEXT = 'local';
  fs.mkdirSync(process.env.HUGINN_DEVICE_DIR, { recursive: true });
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

/** runWork as serve() calls it, given up on after `ms` so a hang is a failure. */
async function runOnce(root, work) {
  fs.writeFileSync(path.join(process.env.HUGINN_DEVICE_DIR, 'device.json'), JSON.stringify({
    url: `http://127.0.0.1:${port}`, name: 'box', scope: 'work', root,
  }));
  posts = [];
  let timer;
  const settled = await Promise.race([
    R.runWork('dev1', { id: 'w1', mode: 'ask', prompt: 'hello', ...work }, R.loadConf()).then(() => 'resolved'),
    new Promise((r) => { timer = setTimeout(() => r('still pending'), 5000); }),
  ]);
  clearTimeout(timer);
  return settled;
}

test('a work root that is not there ends the run with that reason, and the runner carries on', async () => {
  const missing = path.join(tmp, 'not-mounted-yet');
  assert.equal(await runOnce(missing, {}), 'resolved', 'serve() waits on runWork for its next poll');
  assert.equal(posts.length, 1, JSON.stringify(posts));
  assert.equal(posts[0].url, '/v1/devices/dev1/work/w1/events');
  assert.equal(posts[0].body.done, true);
  assert.deepEqual(posts[0].body.lines, []);
  assert.equal(posts[0].body.error, `box cannot start work: ${missing} is not a directory`);
});

test('an engine that spawn() refuses at once is reported the same way', async () => {
  // A NUL in an argument is refused by spawn() synchronously, before any
  // process exists: the shape Windows gives a .cmd it will not run.
  assert.equal(await runOnce(tmp, { model: 'bad\u0000model' }), 'resolved');
  assert.equal(posts.length, 1, JSON.stringify(posts));
  assert.equal(posts[0].body.done, true);
  assert.match(posts[0].body.error, /^could not start claude on box: /);
});
