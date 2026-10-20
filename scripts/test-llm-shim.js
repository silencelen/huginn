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
//
// THE ENGINE IS A REAL PROCESS RUNNING FROM THE TIER'S OWN runtime/ DIRECTORY.
// The shim sends its key and the owner's prompt only to a listener it has
// identified as the engine this tier started (see "engine identity" in the
// shim): on Linux, the service account running a program under
// <localDir>/runtime. So the stand-in engine runs from a hard link (or a copy)
// of node placed there, and the squatters run as another account, or as
// another program, on the same port. Each squatter counts every byte it is
// sent: the assertion is that it got none.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');

const SHIM = path.resolve(__dirname, '..', 'client', 'huginn-llm-shim');
const shim = require(SHIM);

const PORT = 18790 + (process.pid % 5);
const UUID_RE = /^[0-9a-f-]{36}$/i;
const LINUX = process.platform === 'linux';
const ROOT = typeof process.getuid === 'function' && process.getuid() === 0;
const MY_UID = typeof process.getuid === 'function' ? process.getuid() : null;
const NOBODY = 65534;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const modeOf = (f) => fs.statSync(f).mode & 0o777;

let tmp;
let TREE_NODE;   // node, at a path under <localDir>/runtime: what the managed engine runs from
let SCRIPTS;     // world-readable, so a listener under another account can load them

const MANAGED = () => ({
  mode: 'managed', port: PORT, llmSlug: 'testbox',
  defaultModel: 'tiny', modelsBySlug: { tiny: { file: 'tiny.gguf', display: 'Tiny' } },
});

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'llm-shim-'));
  // For the IN-PROCESS calls (sessions, localDir) as well as the spawned shim:
  // without this the module writes into the real ~/.config/huginn-local.
  process.env.HUGINN_LOCAL_DIR = tmp;
  fs.writeFileSync(path.join(tmp, 'api-key'), 'k-test-key\n', { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'local.json'), JSON.stringify(MANAGED()));

  const treeDir = path.join(tmp, 'runtime', 'llama-swap-test');
  fs.mkdirSync(treeDir, { recursive: true, mode: 0o755 });
  TREE_NODE = path.join(treeDir, 'llama-swap');
  try { fs.linkSync(process.execPath, TREE_NODE); } catch {
    fs.copyFileSync(process.execPath, TREE_NODE);
    fs.chmodSync(TREE_NODE, 0o755);
  }

  SCRIPTS = path.join(tmp, 'scripts');
  fs.mkdirSync(SCRIPTS, { mode: 0o755 });
  fs.chmodSync(SCRIPTS, 0o755);
  // A stand-in engine: an OpenAI-style SSE endpoint that reports each request.
  fs.writeFileSync(path.join(SCRIPTS, 'engine.js'), `'use strict';
const http = require('http');
const [mode, port] = process.argv.slice(2);
const say = (o) => process.stdout.write(JSON.stringify(o) + '\\n');
const deltas = { ok: ['Hel', 'lo ', 'there'], nope: ['nope'], stall: ['one delta then silence'] }[mode] || [];
http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    say({ url: req.url, auth: req.headers.authorization || null, body: body ? JSON.parse(body) : null });
    if (mode === '401') { res.writeHead(401); return res.end('no'); }
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    for (const d of deltas) res.write('data: ' + JSON.stringify({ choices: [{ delta: { content: d } }] }) + '\\n\\n');
    if (mode !== 'stall') { res.write('data: [DONE]\\n\\n'); res.end(); }
  });
}).listen(Number(port), '127.0.0.1', () => say({ ready: true }));
`, { mode: 0o644 });
  // What takes the port while the engine is down: it keeps every byte it is
  // sent, and answers whatever it hears the way a model would.
  fs.writeFileSync(path.join(SCRIPTS, 'squatter.js'), `'use strict';
const net = require('net');
const [port] = process.argv.slice(2);
const say = (o) => process.stdout.write(JSON.stringify(o) + '\\n');
net.createServer((s) => {
  say({ conn: true });
  s.on('error', () => {});
  s.on('data', (d) => {
    say({ bytes: d.length, text: d.toString('latin1') });
    s.end('HTTP/1.1 200 OK\\r\\ncontent-type: text/event-stream\\r\\nconnection: close\\r\\n\\r\\n'
      + 'data: ' + JSON.stringify({ choices: [{ delta: { content: 'pwned' } }] }) + '\\n\\ndata: [DONE]\\n\\n');
  });
}).listen(Number(port), '127.0.0.1', () => say({ ready: true }));
`, { mode: 0o644 });
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

/**
 * One of the listener scripts as its own process: `exe` is the program it runs
 * as (the in-tree node by default), `uid`/`gid` the account. Resolves once it
 * listens; `events` fills with what it reports.
 */
function listener(script, args, { exe = TREE_NODE, uid, gid } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(exe, [path.join(SCRIPTS, script), ...args], {
      cwd: '/', stdio: ['ignore', 'pipe', 'pipe'], ...(uid != null ? { uid, gid } : {}),
    });
    const events = [];
    let err = '';
    const handle = {
      child, events,
      close: () => new Promise((r) => {
        if (child.exitCode !== null || child.signalCode !== null) return r();
        child.once('exit', () => r());
        child.kill('SIGKILL');
      }),
    };
    readline.createInterface({ input: child.stdout }).on('line', (l) => {
      let o;
      try { o = JSON.parse(l); } catch { return; }
      if (o.ready) resolve(handle); else events.push(o);
    });
    child.stderr.on('data', (c) => { err += c; });
    child.on('exit', (code) => reject(new Error(`${script} exited ${code} before it listened: ${err}`)));
  });
}
const engine = (mode, opts) => listener('engine.js', [mode, String(PORT)], opts);
const squatter = (opts) => listener('squatter.js', [String(PORT)], opts);
const requestsOf = (l) => l.events.filter((e) => e.url);
const bytesOf = (l) => l.events.reduce((n, e) => n + (e.bytes || 0), 0);
/** Until `pred` holds (a positive assertion's evidence may still be in a pipe), or 2 s. */
async function until(pred) { for (let i = 0; i < 40 && !pred(); i++) await wait(50); }

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

// The transcripts are the owner's prompts and the model's answers; the key,
// the config and the token beside them are all 0600.
test('session transcripts are owner-only: 0600 files in a 0700 directory, whatever the umask', { skip: process.platform === 'win32' && 'mode bits are POSIX' }, () => {
  const dir = path.join(tmp, 'sessions');
  fs.mkdirSync(dir, { recursive: true });
  fs.chmodSync(dir, 0o755);                         // as an older shim left it
  const legacy = crypto.randomUUID();
  const legacyFile = path.join(dir, `${legacy}.jsonl`);
  fs.writeFileSync(legacyFile, '{"role":"user","content":"old"}\n');
  fs.chmodSync(legacyFile, 0o644);
  const old = process.umask(0);                     // the widest umask there is
  try {
    shim.saveSession(legacy, [{ role: 'user', content: 'the owner said this' }]);
    assert.equal(modeOf(legacyFile), 0o600, 'a save replaces a 0644 transcript with a 0600 one');
    assert.equal(modeOf(dir), 0o700, 'and closes the directory an older shim left open');
    const fresh = crypto.randomUUID();
    shim.saveSession(fresh, [{ role: 'user', content: 'and this' }]);
    assert.equal(modeOf(path.join(dir, `${fresh}.jsonl`)), 0o600, 'a new transcript is 0600 from the start');
    assert.deepEqual(shim.loadSession(fresh).map((m) => m.content), ['and this']);
    assert.deepEqual(fs.readdirSync(dir).filter((f) => f.endsWith('.tmp')), [], 'no temp is left behind');
  } finally {
    process.umask(old);
  }
});

// ---------------------------------------------------- engine identity: pure

test('ipKey: IPv4 and its v4-mapped IPv6 spelling are one address; junk is null', () => {
  const lo = shim.ipKey('127.0.0.1');
  assert.equal(lo, '00000000000000000000ffff7f000001');
  for (const s of ['::ffff:127.0.0.1', '[::ffff:127.0.0.1]', '::ffff:7f00:1', '0:0:0:0:0:ffff:7f00:0001']) {
    assert.equal(shim.ipKey(s), lo, s);
  }
  assert.equal(shim.ipKey('::1'), '0'.repeat(31) + '1');
  assert.equal(shim.ipKey('[::1]'), shim.ipKey('0:0:0:0:0:0:0:1'));
  assert.equal(shim.ipKey('::'), '0'.repeat(32));
  assert.equal(shim.ipKey('fe80::1%eth0'), shim.ipKey('fe80::1'), 'a zone is not part of the address');
  for (const bad of ['', 'localhost', '1.2.3', '256.0.0.1', '1::2::3', '12345::', '1:2:3:4:5:6:7:8:9', '1:2:3:4:5:6:7::8', '::ffff:1.2.3.999']) {
    assert.equal(shim.ipKey(bad), null, bad);
  }
});

test('procAddrKey reads /proc/net/tcp addresses in either byte order, v4 and v6', () => {
  const lo = shim.ipKey('127.0.0.1');
  assert.equal(shim.procAddrKey('0100007F', true), lo, 'little-endian, as x86 and arm print it');
  assert.equal(shim.procAddrKey('7F000001', false), lo, 'big-endian');
  assert.equal(shim.procAddrKey('00000000000000000000000001000000', true), shim.ipKey('::1'));
  assert.equal(shim.procAddrKey('0000000000000000FFFF00000100007F', true), lo, 'a dual-stack listener sees v4-mapped');
  assert.equal(shim.procAddrKey('XYZ', true), null);
  assert.equal(shim.procAddrKey('0100007', true), null);
});

// Lines as a little-endian kernel prints them: our socket 127.0.0.1:40000 ->
// :8748, the engine's accepted side of it, its listener, a TIME_WAIT left by an
// earlier connection on the same ports, and the same connection over v6.
const PROC_TCP = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:222C 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 5550 1 0000000000000000 100 0 0 10 0
   1: 0100007F:9C40 0100007F:222C 01 00000000:00000000 00:00000000 00000000     0        0 7771 1 0000000000000000 20 4 30 10 -1
   2: 0100007F:222C 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  1000        0 7772 1 0000000000000000 20 4 30 10 -1
   3: 0100007F:222C 0100007F:9C40 06 00000000:00000000 03:00000F1A 00000000     0        0 0 3 0000000000000000
`;
const PROC_TCP6 = `  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000001000000:30D4 00000000000000000000000001000000:9C41 01 00000000:00000000 00:00000000 00000000   998        0 8881 1 0000000000000000 20 4 30 10 -1
`;

test('parseProcNet + peerRow find the server side of OUR connection, accepted, and nothing else', () => {
  const rows = [...shim.parseProcNet(PROC_TCP, true), ...shim.parseProcNet(PROC_TCP6, true)];
  assert.equal(rows.length, 5);
  const conn = { localAddress: '127.0.0.1', localPort: 0x9C40, remoteAddress: '127.0.0.1', remotePort: 0x222C };
  const row = shim.peerRow(rows, conn);
  assert.equal(row.uid, 1000);
  assert.equal(row.inode, '7772', 'the accepted socket, not our own end, the listener or a TIME_WAIT');
  assert.equal(shim.peerRow(rows, { ...conn, localPort: 1 }), null, 'another connection is not ours');
  const v6 = shim.peerRow(rows, { localAddress: '::1', localPort: 0x9C41, remoteAddress: '::1', remotePort: 0x30D4 });
  assert.equal(v6.uid, 998);
  // Listening on [::] takes IPv4 too, and /proc lists that under tcp6, v4-mapped.
  const mapped = shim.parseProcNet(`h
   0: 0000000000000000FFFF00000100007F:222C 0000000000000000FFFF00000100007F:9C40 01 00000000:00000000 00:00000000 00000000  1001        0 9991 1 0 20 4 30 10 -1
`, true);
  assert.equal(shim.peerRow(mapped, conn).uid, 1001);
});

// netstat -ano as Windows prints it, the state column in German: it is never read.
const NETSTAT = `
Aktive Verbindungen

  Proto  Lokale Adresse         Remoteadresse          Status           PID
  TCP    0.0.0.0:135            0.0.0.0:0              ABHÖREN          1012
  TCP    127.0.0.1:8748         0.0.0.0:0              ABHÖREN          4242
  TCP    127.0.0.1:8748         127.0.0.1:50123        HERGESTELLT      4242
  TCP    127.0.0.1:50123        127.0.0.1:8748         HERGESTELLT      5150
  TCP    [::1]:8748             [::1]:50124            HERGESTELLT      777
  TCP    [::ffff:127.0.0.1]:11434  [::ffff:127.0.0.1]:50125  HERGESTELLT  888
  UDP    0.0.0.0:5353           *:*                                     2024
`;

test('parseNetstat + netstatPeer name the PID that owns the server side of our connection', () => {
  const rows = shim.parseNetstat(NETSTAT);
  assert.equal(rows.length, 6, 'the TCP rows, v4 and v6; UDP and headers are not connections');
  const conn = { localAddress: '127.0.0.1', localPort: 50123, remoteAddress: '127.0.0.1', remotePort: 8748 };
  assert.equal(shim.netstatPeer(rows, conn).pid, 4242, 'the far side, not our own row (5150)');
  assert.equal(shim.netstatPeer(rows, { localAddress: '::1', localPort: 50124, remoteAddress: '::1', remotePort: 8748 }).pid, 777);
  assert.equal(shim.netstatPeer(rows, { localAddress: '127.0.0.1', localPort: 50125, remoteAddress: '127.0.0.1', remotePort: 11434 }).pid, 888);
  assert.equal(shim.netstatPeer(rows, { ...conn, localPort: 9 }), null);
  assert.deepEqual(shim.parseWinProc('EXE=C:\\ProgramData\\huginn-local\\runtime\\x\\llama-swap.exe\r\nSID=S-1-5-18\r\n'),
    { exe: 'C:\\ProgramData\\huginn-local\\runtime\\x\\llama-swap.exe', sid: 'S-1-5-18' });
  assert.deepEqual(shim.parseWinProc('EXE=\r\nSID=\r\n'), { exe: null, sid: null }, 'unnamed is null, never empty-and-equal');
  assert.match(shim.winProcScript('12; Remove-Item x'), /ProcessId=NaN/, 'the pid reaches PowerShell as a number or not at all');
});

test('withinDir: inside the runtime tree only — no prefix tricks, no climbing out', () => {
  assert.ok(shim.withinDir('/srv/hl/runtime/llama-swap-v251/llama-swap', '/srv/hl/runtime'));
  assert.ok(shim.withinDir('/srv/hl/runtime/llama-swap-v251/llama-swap (deleted)', '/srv/hl/runtime'),
    'a binary replaced by an update while it ran is still the one from this tree');
  assert.ok(!shim.withinDir('/srv/hl/runtime-evil/llama-swap', '/srv/hl/runtime'));
  assert.ok(!shim.withinDir('/srv/hl/runtime/../bin/x', '/srv/hl/runtime'));
  assert.ok(!shim.withinDir('/srv/hl/runtime', '/srv/hl/runtime'), 'the directory is not a program in it');
  assert.ok(!shim.withinDir('/usr/bin/node', '/srv/hl/runtime'));
  assert.ok(!shim.withinDir(null, '/srv/hl/runtime'));
  assert.ok(shim.withinDir('c:\\programdata\\HUGINN-LOCAL\\runtime\\llama-swap-v251\\llama-swap.exe', 'C:\\ProgramData\\huginn-local\\runtime', 'win32'));
  assert.ok(!shim.withinDir('C:\\Users\\mallory\\llama-swap.exe', 'C:\\ProgramData\\huginn-local\\runtime', 'win32'));
  assert.ok(!shim.withinDir('C:\\ProgramData\\huginn-local\\runtime\\..\\x.exe', 'C:\\ProgramData\\huginn-local\\runtime', 'win32'));
});

test('expectedEngine: managed is the service account under runtime/, adapters their recorded account', () => {
  const m = MANAGED();
  assert.deepEqual(shim.expectedEngine(m, { platform: 'linux', uid: 1000, dir: '/srv/hl' }),
    { uid: 1000, exeDir: '/srv/hl/runtime' });
  assert.deepEqual(shim.expectedEngine(m, { platform: 'win32', uid: null, dir: 'C:\\ProgramData\\huginn-local' }),
    { sid: 'S-1-5-18', exeDir: 'C:\\ProgramData\\huginn-local\\runtime' }, 'both WinSW services are LocalSystem');
  assert.match(shim.expectedEngine(m, { platform: 'linux', uid: null, dir: '/srv/hl' }).refuse, /which account/);
  const a = { mode: 'adapter', adapter: { kind: 'ollama', url: 'http://127.0.0.1:11434', engine: { uid: 998, exe: '/usr/local/bin/ollama' } } };
  const la = shim.expectedEngine(a, { platform: 'linux', uid: 1000, dir: '/srv/hl' });
  assert.equal(la.uid, 998, 'the account recorded at adoption, not this one');
  assert.equal(la.exeDir, undefined, 'an adopted engine lives wherever it was installed');
  const wa = shim.expectedEngine({ ...a, adapter: { ...a.adapter, engine: { sid: 'S-1-5-21-1-2-3-1001' } } }, { platform: 'win32', dir: 'C:\\x' });
  assert.equal(wa.sid, 'S-1-5-21-1-2-3-1001');
  const unpinned = shim.expectedEngine({ mode: 'adapter', adapter: { kind: 'ollama', url: 'http://127.0.0.1:11434' } }, { platform: 'linux', uid: 1000, dir: '/x' });
  assert.match(unpinned.refuse, /huginn local update/, 'an adoption from before identities were recorded says how to record one');
  assert.match(shim.expectedEngine(m, { platform: 'darwin', uid: 501, dir: '/x' }).refuse, /no way to tell/);
});

test('judgeEngine fails closed: the wrong account, the wrong program, or nobody to ask', () => {
  const want = { uid: 1000, exeDir: '/srv/hl/runtime' };
  const engine = { uid: 1000, holders: [{ pid: 7, exe: '/srv/hl/runtime/llama-swap-v251/llama-swap' }] };
  assert.equal(shim.judgeEngine(engine, want, 'linux'), null);
  assert.match(shim.judgeEngine({ ...engine, uid: 1001 }, want, 'linux'), /uid 1001, not uid 1000/);
  assert.match(shim.judgeEngine({ uid: 1000, holders: [{ pid: 9, exe: '/usr/bin/python3' }] }, want, 'linux'), /pid 9 runs \/usr\/bin\/python3/);
  assert.match(shim.judgeEngine({ uid: 1000, holders: [...engine.holders, { pid: 9, exe: null }] }, want, 'linux'), /pid 9/,
    'every process holding the connection must be the engine');
  assert.match(shim.judgeEngine({ uid: 1000, holders: [] }, want, 'linux'), /no process/);
  assert.match(shim.judgeEngine({ uid: null, holders: [] }, want, 'linux'), /not accepted/);
  assert.match(shim.judgeEngine({ uid: null, holders: [], noProc: true }, want, 'linux'), /lists no TCP sockets/);
  assert.match(shim.judgeEngine({ self: true }, want, 'linux'), /reached itself/);
  assert.match(shim.judgeEngine(null, want, 'linux'), /could say/);
  assert.match(shim.judgeEngine(engine, {}, 'linux'), /no account/, 'an expectation with no account in it passes nothing');
  assert.match(shim.judgeEngine(engine, { refuse: 'because' }, 'linux'), /because/);
  assert.equal(shim.judgeEngine({ uid: 998, holders: [] }, { uid: 998 }, 'linux'), null, 'an adopted engine is held to its account');
  const wwant = { sid: 'S-1-5-18', exeDir: 'C:\\ProgramData\\huginn-local\\runtime' };
  const wengine = { pid: 4242, sid: 'S-1-5-18', exe: 'C:\\ProgramData\\huginn-local\\runtime\\llama-swap-v251\\llama-swap.exe' };
  assert.equal(shim.judgeEngine(wengine, wwant, 'win32'), null);
  assert.match(shim.judgeEngine({ ...wengine, sid: 'S-1-5-21-9-9-9-1001' }, wwant, 'win32'), /runs as S-1-5-21-9-9-9-1001/,
    'our own llama-swap.exe started by a standard user with its own config is not the engine');
  assert.match(shim.judgeEngine({ ...wengine, exe: 'C:\\Users\\m\\evil.exe' }, wwant, 'win32'), /not under/);
  assert.match(shim.judgeEngine({ ...wengine, sid: null }, wwant, 'win32'), /would not name/);
  assert.match(shim.judgeEngine({ pid: null }, wwant, 'win32'), /netstat/);
  assert.match(shim.judgeEngine(wengine, {}, 'win32'), /no account/);
});

// huginn-local's health check may ask a far side it could not NAME for the
// model list without the key; one it named and found wrong it asks nothing.
test('engineVerdict tells "could not name it" from "named it, and it is wrong"', () => {
  const want = { uid: 1000, exeDir: '/srv/hl/runtime' };
  const v = (seen, w = want, p = 'linux') => shim.engineVerdict(seen, w, p);
  assert.deepEqual(v({ uid: 1000, holders: [{ pid: 7, exe: '/srv/hl/runtime/x/llama-swap' }] }), { why: null, unknown: false });
  assert.equal(v({ uid: 1001, holders: [] }).unknown, false, 'another account is a definite no');
  assert.equal(v({ uid: 1000, holders: [{ pid: 9, exe: '/usr/bin/nc' }] }).unknown, false, 'another program too');
  assert.equal(v({ uid: null, holders: [] }).unknown, true, 'never accepted: nothing was named');
  assert.equal(v({ uid: 1000, holders: [] }).unknown, true);
  assert.equal(v({ uid: 1000, holders: [{ pid: 9, exe: null }] }).unknown, true);
  assert.equal(v(null).unknown, true);
  assert.equal(v({ self: true }).unknown, false);
  assert.equal(v({ uid: 1000 }, { refuse: 'x' }).unknown, false, 'a refusal from the configuration is not about the far side');
  const ww = { sid: 'S-1-5-18', exeDir: 'C:\\h\\runtime' };
  assert.equal(v({ pid: 4, sid: null, exe: null }, ww, 'win32').unknown, true, 'Windows would not name the owner');
  assert.equal(v({ pid: 4, sid: 'S-1-5-21-1-1-1-1001', exe: null }, ww, 'win32').unknown, false);
  assert.equal(v({ pid: 4, sid: 'S-1-5-18', exe: null }, ww, 'win32').unknown, true);
  assert.equal(v({ pid: 4, sid: 'S-1-5-18', exe: 'C:\\x\\evil.exe' }, ww, 'win32').unknown, false);
});

// --------------------------------------------- engine identity: real /proc

test('a /proc that lists no sockets is said at once, not waited out', { skip: !LINUX && 'Linux only' }, async () => {
  const t0 = Date.now();
  const seen = await shim.linuxPeer({ localAddress: '127.0.0.1', localPort: 40000, remoteAddress: '127.0.0.1', remotePort: 8748 },
    { procRoot: fs.mkdtempSync(path.join(tmp, 'noproc-')) });
  assert.deepEqual(seen, { uid: null, holders: [], noProc: true });
  assert.ok(Date.now() - t0 < 500);
  assert.equal(shim.engineVerdict(seen, { uid: 0 }, 'linux').unknown, true);
});

test('on this machine, /proc names the process that accepted a connection, and its program', { skip: !LINUX && 'Linux only' }, async () => {
  const srv = net.createServer(() => {});
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const sock = net.connect(srv.address().port, '127.0.0.1');
  try {
    await new Promise((r, j) => { sock.once('connect', r); sock.once('error', j); });
    const seen = await shim.linuxPeer({
      localAddress: sock.localAddress, localPort: sock.localPort,
      remoteAddress: sock.remoteAddress, remotePort: sock.remotePort,
    });
    assert.equal(seen.uid, MY_UID);
    assert.ok(seen.holders.some((h) => h.pid === process.pid && h.exe === process.execPath),
      `this test process accepted it: ${JSON.stringify(seen)}`);
  } finally {
    sock.destroy();
    srv.close();
  }
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

test('a run streams deltas, carries the key, saves its session, and resumes', { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
  const eng = await engine('ok');
  try {
    const one = await runShim([...ARGV, '--model', 'local-testbox-tiny'], 'first question');
    assert.equal(one.code, 0, one.err);
    const r1 = verifyFrames(one);
    assert.equal(r1.is_error, false, r1.result);
    assert.equal(r1.result, 'Hello there');
    await until(() => requestsOf(eng).length >= 1);
    const seen = requestsOf(eng);
    assert.equal(seen[0].auth, 'Bearer k-test-key', 'the minted key rides every request');
    assert.equal(seen[0].body.model, 'tiny', 'the composite id was stripped to the swap key');
    assert.equal(seen[0].body.messages.at(-1).content, 'first question');

    const sid = one.frames[0].session_id;
    assert.equal(modeOf(path.join(tmp, 'sessions', `${sid}.jsonl`)), 0o600, 'the transcript is owner-only');
    const two = await runShim([...ARGV, '--resume', sid], 'second question');
    assert.equal(verifyFrames(two).is_error, false);
    await until(() => requestsOf(eng).length >= 2);
    const history = requestsOf(eng)[1].body.messages.map((m) => m.content);
    assert.ok(history.includes('first question') && history.includes('Hello there'),
      'the resumed request replays the saved exchange');
  } finally { await eng.close(); }
});

test('an unknown model is an error RESULT and no request is ever sent', { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
  const eng = await engine('nope');
  try {
    const run = await runShim([...ARGV, '--model', 'local-otherbox-tiny'], 'hi');
    assert.equal(run.code, 0, 'the frame is the report, the exit stays 0');
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /different machine/);
    await wait(200);
    assert.equal(requestsOf(eng).length, 0, 'no silent substitution — and no request at all');
  } finally { await eng.close(); }
});

test('a refused key is an honest error result', { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
  const eng = await engine('401');
  try {
    const run = await runShim([...ARGV], 'hi');
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /refused this machine's key \(401\)/);
  } finally { await eng.close(); }
});

test('the watchdog ends a stalled generation with an honest error', { skip: !LINUX && 'the engine check runs on Linux here' }, async () => {
  const eng = await engine('stall');
  try {
    const run = await runShim([...ARGV], 'hi', { HUGINN_SHIM_WATCHDOG_MS: '1200' });
    const r = verifyFrames(run);
    assert.equal(r.is_error, true);
    assert.match(r.result, /no output for/);
    assert.equal(run.code, 0);
  } finally { await eng.close(); }
});

// ------------------------------------------------- squatters on the port
//
// Another account binds 127.0.0.1:8748 while llama-swap restarts, or runs a
// different program there. The old shim sent it the key and the prompt and
// filed its answer ("pwned") as the model's.

const refusedRun = async (sq, why) => {
  const run = await runShim([...ARGV, '--model', 'local-testbox-tiny'], "the owner's private prompt");
  assert.equal(run.code, 0, run.err);
  const r = verifyFrames(run);
  assert.equal(r.is_error, true, `the squatter's reply was taken as the answer: ${r.result}`);
  assert.match(r.result, /is not this machine's local engine/);
  assert.match(r.result, why);
  assert.match(r.result, /Nothing was sent to it/);
  assert.ok(!run.frames.some((f) => f.type === 'assistant' || f.type === 'stream_event'), 'nothing it said became the answer');
  await wait(300);
  assert.ok(sq.events.some((e) => e.conn), 'the shim did reach it (and judged it before writing)');
  assert.equal(bytesOf(sq), 0, `not one byte reached it, so neither the key nor the prompt did: ${JSON.stringify(sq.events)}`);
};

test('a squatter on the engine port under ANOTHER ACCOUNT is refused and sent nothing', {
  skip: (!LINUX && 'Linux only') || (!ROOT && 'needs root to start a listener as another account'),
}, async () => {
  // Our own llama-swap path, run by somebody else: only the account tells them apart.
  fs.chmodSync(tmp, 0o711);
  const sq = await squatter({ uid: NOBODY, gid: NOBODY });
  try {
    await refusedRun(sq, new RegExp(`runs as uid ${NOBODY}, not uid ${MY_UID}`));
  } finally {
    await sq.close();
    fs.chmodSync(tmp, 0o700);
  }
});

test('a squatter on the engine port running ANOTHER PROGRAM is refused and sent nothing', { skip: !LINUX && 'Linux only' }, async () => {
  // This account, but not a program from the tier's runtime directory.
  const sq = await squatter({ exe: process.execPath });
  try {
    await refusedRun(sq, /runs \S*node, which is not under \S*\/runtime/);
  } finally { await sq.close(); }
});

// ------------------------------------------------------- adopted engines

function adapterDir(engineRec) {
  const d = fs.mkdtempSync(path.join(tmp, 'adapter-'));
  fs.writeFileSync(path.join(d, 'local.json'), JSON.stringify({
    mode: 'adapter', adopted: true, llmSlug: 'testbox', defaultModel: 'tiny',
    modelsBySlug: { tiny: { file: 'tiny:1b', display: 'tiny:1b' } },
    adapter: { kind: 'ollama', url: `http://127.0.0.1:${PORT}`, key: 'adapter-key', ...(engineRec ? { engine: engineRec } : {}) },
  }), { mode: 0o600 });
  return d;
}

test('an adopted engine is held to the account recorded when it was adopted', { skip: !LINUX && 'Linux only' }, async () => {
  // An adopted engine lives wherever it was installed: no directory, only the account.
  const eng = await engine('ok', { exe: process.execPath });
  try {
    const ok = await runShim([...ARGV], 'hi', { HUGINN_LOCAL_DIR: adapterDir({ uid: MY_UID, exe: process.execPath }) });
    assert.equal(verifyFrames(ok).result, 'Hello there');
    await until(() => requestsOf(eng).length >= 1);
    assert.equal(requestsOf(eng)[0].auth, 'Bearer adapter-key');
    assert.equal(requestsOf(eng)[0].body.model, 'tiny:1b', 'the backend name, as before');

    const other = await runShim([...ARGV], 'hi', { HUGINN_LOCAL_DIR: adapterDir({ uid: MY_UID + 1 }) });
    const r = verifyFrames(other);
    assert.equal(r.is_error, true);
    assert.match(r.result, new RegExp(`runs as uid ${MY_UID}, not uid ${MY_UID + 1}`));
    assert.match(r.result, /adopt it again: huginn local on --adapter ollama/);

    const unrecorded = await runShim([...ARGV], 'hi', { HUGINN_LOCAL_DIR: adapterDir(null) });
    const u = verifyFrames(unrecorded);
    assert.equal(u.is_error, true);
    assert.match(u.result, /nothing records which account serves the adopted ollama/);
    await wait(200);
    assert.equal(requestsOf(eng).length, 1, 'neither the wrong account nor an unrecorded one was sent a request');
  } finally { await eng.close(); }
});

test('probeEngine names who serves a port and sends nothing', { skip: !LINUX && 'Linux only' }, async () => {
  const sq = await squatter({ exe: process.execPath });
  try {
    const seen = await shim.probeEngine(`http://127.0.0.1:${PORT}`);
    assert.equal(seen.uid, MY_UID);
    assert.ok(seen.holders.some((h) => h.exe === process.execPath), JSON.stringify(seen));
    assert.deepEqual(shim.pinFor(seen), { uid: MY_UID, exe: process.execPath });
    // EVERY metacharacter escaped, the backslash included, not just `/` and `.`:
    // node can live under any path (a `+` or a `(` in a version directory), and
    // one left bare turns the path into a pattern that no longer matches itself.
    const exe = process.execPath.replace(/[\\^$.*+?()[\]{}|/]/g, '\\$&');
    assert.match(shim.describePeer(seen), new RegExp(`uid ${MY_UID}, pid \\d+ \\(${exe}\\)`));
    await wait(200);
    assert.equal(bytesOf(sq), 0);
  } finally { await sq.close(); }
  await assert.rejects(shim.probeEngine(`http://127.0.0.1:${PORT}`), /ECONNREFUSED/, 'nothing listening is an error, not an identity');
});
