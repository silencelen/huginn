'use strict';
// The send_file tool's host half (lib/outbox.js) and its MCP server: what may be
// sent, that it is a COPY under a minted id, and that only such an id is honoured.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const outbox = require('../lib/outbox');

function tmp(prefix) { return fs.mkdtempSync(path.join(os.tmpdir(), prefix)); }

test('credentials are refused by place and by name, wherever they live', () => {
  const no = (p) => assert.ok(outbox.refusal(p), `should refuse ${p}`);
  const ok = (p) => assert.equal(outbox.refusal(p), null, `should allow ${p}`);
  no('/etc/huginn-appd/token');
  no('/root/.ssh/id_ed25519');
  no('/home/a/project/.ssh/config');
  no('/root/netplan/scripts/.secrets.env');
  no('/srv/app/.env');
  no('/srv/app/.env.production');
  no('/srv/app/server.key');
  no('/root/.claude.json');
  no('/root/.claude/.credentials.json');
  no('/root/.huginn-app/keystore.properties');
  no('/proc/self/environ');
  no('/var/lib/huginn-appd/pushtokens.json');
  ok('/var/lib/huginn-appd/uploads/up-1-abc.jpg');
  ok('/root/netplan/docs/report.pdf');
  ok('/tmp/chart.png');
});

test('a send is a copy under a minted id, with a sidecar, and the original is untouched', () => {
  const src = tmp('ob-src-'); const box = tmp('ob-box-');
  const f = path.join(src, 'Quarterly chart.png');
  fs.writeFileSync(f, 'PNGDATA');
  const m = outbox.deposit(f, { outboxDir: box, dataDir: '/nonexistent-data', caption: '  the chart  ' });
  assert.match(m.id, outbox.ID_RE);
  assert.equal(m.name, 'Quarterly chart.png');
  assert.equal(m.mime, 'image/png');
  assert.equal(m.image, true);
  assert.equal(m.size, 7);
  assert.equal(m.caption, 'the chart');
  assert.equal(fs.readFileSync(path.join(box, m.id), 'utf8'), 'PNGDATA');
  fs.writeFileSync(f, 'CHANGED');
  assert.equal(fs.readFileSync(path.join(box, m.id), 'utf8'), 'PNGDATA', 'a later edit changes nothing sent');
  assert.deepEqual(outbox.metaOf(box, m.id), m);
});

test('a symlink is judged by what it points at', () => {
  const src = tmp('ob-src-'); const box = tmp('ob-box-');
  const ssh = path.join(src, '.ssh'); fs.mkdirSync(ssh);
  fs.writeFileSync(path.join(ssh, 'id_rsa'), 'KEY');
  fs.symlinkSync(path.join(ssh, 'id_rsa'), path.join(src, 'notes.txt'));
  assert.throws(() => outbox.deposit(path.join(src, 'notes.txt'), { outboxDir: box, dataDir: '/x' }), /never sent|credential/);
  assert.equal(fs.readdirSync(box).length, 0);
});

test('directories, missing files, relative paths and anything over 50 MB are refused', () => {
  const src = tmp('ob-src-'); const box = tmp('ob-box-');
  assert.throws(() => outbox.deposit(src, { outboxDir: box, dataDir: '/x' }), /regular file/);
  assert.throws(() => outbox.deposit(path.join(src, 'nope'), { outboxDir: box, dataDir: '/x' }), /no such file/);
  assert.throws(() => outbox.deposit('rel/file.txt', { outboxDir: box, dataDir: '/x' }), /absolute/);
  const big = path.join(src, 'big.bin');
  fs.closeSync(fs.openSync(big, 'w')); fs.truncateSync(big, outbox.MAX_BYTES + 1);
  assert.throws(() => outbox.deposit(big, { outboxDir: box, dataDir: '/x' }), /limit is 50 MB/);
});

test('a non-image is served as a download, never as a renderable type', () => {
  assert.equal(outbox.contentTypeFor('out-1793400000000-abcdef.html'), 'application/octet-stream');
  assert.equal(outbox.contentTypeFor('out-1793400000000-abcdef.svg'), 'application/octet-stream');
  assert.equal(outbox.contentTypeFor('out-1793400000000-abcdef.jpg'), 'image/jpeg');
  assert.equal(outbox.safeDownloadName('../../etc/"x"\r\n.txt', 'id'), '_x___.txt');
});

test('only a result naming a minted id becomes a file on the transcript', () => {
  const good = outbox.fileFromResult(JSON.stringify({ sent: true, id: 'out-1793400000000-abcdef.pdf', name: 'r.pdf', size: 10 }));
  assert.deepEqual(good, { id: 'out-1793400000000-abcdef.pdf', name: 'r.pdf', mime: 'application/octet-stream', size: 10, image: false, caption: null });
  assert.equal(outbox.fileFromResult(JSON.stringify({ sent: true, id: '../../etc/passwd' })), null);
  assert.equal(outbox.fileFromResult(JSON.stringify({ sent: true, id: 'up-1-abc.jpg' })), null);
  assert.equal(outbox.fileFromResult('not sent: refused'), null);
});

function mcp(env) {
  const p = spawn(process.execPath, [path.join(__dirname, '..', 'mcp', 'huginn-mcp.js')], { env: { ...process.env, ...env }, stdio: ['pipe', 'pipe', 'pipe'] });
  let buf = ''; const waiting = new Map();
  p.stdout.on('data', (c) => {
    buf += c;
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const m = JSON.parse(buf.slice(0, nl)); buf = buf.slice(nl + 1);
      const w = waiting.get(m.id); if (w) { waiting.delete(m.id); w(m); }
    }
  });
  let id = 0;
  return {
    call(method, params) {
      const mid = ++id;
      return new Promise((resolve) => { waiting.set(mid, resolve); p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: mid, method, params }) + '\n'); });
    },
    notify(method) { p.stdin.write(JSON.stringify({ jsonrpc: '2.0', method }) + '\n'); },
    close() { p.stdin.end(); },
  };
}

test('the MCP server speaks the protocol: initialize, list, a send, and a refusal', async () => {
  const data = tmp('ob-data-'); const src = tmp('ob-src-');
  const s = mcp({ HUGINN_APPD_DATA: data });
  try {
    const init = await s.call('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '0' } });
    assert.equal(init.result.serverInfo.name, 'huginn');
    assert.ok(init.result.capabilities.tools);
    s.notify('notifications/initialized');
    const list = await s.call('tools/list', {});
    assert.deepEqual(list.result.tools.map((t) => t.name), ['send_file']);
    const f = path.join(src, 'notes.txt'); fs.writeFileSync(f, 'hello');
    const sent = await s.call('tools/call', { name: 'send_file', arguments: { path: f } });
    assert.ok(!sent.result.isError, JSON.stringify(sent.result));
    const file = outbox.fileFromResult(sent.result.content[0].text);
    assert.ok(file, 'the result is what the transcript reads back');
    assert.equal(fs.readFileSync(path.join(data, 'outbox', file.id), 'utf8'), 'hello');
    const refused = await s.call('tools/call', { name: 'send_file', arguments: { path: path.join(data, 'token') } });
    assert.equal(refused.result.isError, true);
    const unknown = await s.call('nope/nope', {});
    assert.equal(unknown.error.code, -32601);
  } finally { s.close(); }
});
