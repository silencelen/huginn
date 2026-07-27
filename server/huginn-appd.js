#!/usr/bin/env node
// huginn-appd — HTTP/SSE backend for the Huginn Android app.
//
// Serves the phone a chat + session surface over the tailnet:
//   * headless chats: spawns `claude -p --output-format stream-json` in ~/netplan
//     (same persona + tool sets as `huginn -p` / `huginn -y`), streams deltas over
//     SSE, persists a digested transcript per chat under /var/lib/huginn-appd.
//   * tmux sessions: list (with the state the huginn-claude-title hook records in
//     /run/huginn-claude-state), create (same shape as `cc`), kill, capture-pane
//     screen reads, send-keys input.
//
// Security model: binds the TAILSCALE address only (devstore precedent: tailnet is
// the trust boundary) AND requires `Authorization: Bearer <token>` on every route,
// token in /etc/huginn-appd/token (created by deploy.sh, 0600). Everything this
// daemon can do equals root-on-huginn — the token is not decorative.
//
// Zero npm dependencies; Node >= 20.

'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFile, spawn } = require('node:child_process');

const VERSION = '1.0.0';
const PORT = Number(process.env.HUGINN_APPD_PORT || 8787);
const DATA_DIR = process.env.HUGINN_APPD_DATA || '/var/lib/huginn-appd';
const TOKEN_FILE = process.env.HUGINN_APPD_TOKEN_FILE || '/etc/huginn-appd/token';
const STATE_DIR = '/run/huginn-claude-state';
const PERSONA_FILE = '/usr/local/share/huginn-cli/persona.md';
const WORKDIR = process.env.HUGINN_APPD_WORKDIR || '/root/netplan';
const MUNINN = 'root@192.168.2.118';
const MAX_CONCURRENT_RUNS = 3;
const RUN_HARD_CAP_MS = 2 * 60 * 60 * 1000; // 2 h — safety net, not a feature

// Tool sets mirror the huginn CLI exactly: `-p` (ask) vs `-y` (act).
const TOOLS = {
  ask: 'mcp__mempalace',
  act: 'Bash Read Edit Write Glob Grep WebFetch mcp__mempalace',
};

// ---------------------------------------------------------------- utilities

function log(...args) { console.log(new Date().toISOString(), ...args); }

const TOKEN = (() => {
  try { return fs.readFileSync(TOKEN_FILE, 'utf8').trim(); }
  catch { console.error(`FATAL: cannot read token file ${TOKEN_FILE} — run deploy.sh first`); process.exit(1); }
})();
if (TOKEN.length < 32) { console.error('FATAL: token too short (<32 chars)'); process.exit(1); }

function authorized(req) {
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/.exec(h);
  if (!m) return false;
  const got = Buffer.from(m[1]);
  const want = Buffer.from(TOKEN);
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}
function sendErr(res, code, msg) { sendJson(res, code, { error: msg }); }

function readBody(req, limit = 256 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: 10_000, maxBuffer: 4 * 1024 * 1024, ...opts },
      (err, stdout, stderr) => resolve({ err, stdout: stdout ?? '', stderr: stderr ?? '' }));
  });
}

// Session names: the cc contract — letters/digits/underscore, canonically lowercase.
function canonName(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.toLowerCase();
  return /^[a-z0-9_]{1,50}$/.test(s) ? s : null;
}

// ------------------------------------------------------------ tmux sessions

async function listSessions() {
  const fmt = '#{session_name}\t#{session_created}\t#{session_attached}\t#{session_activity}\t#{session_windows}';
  const { err, stdout } = await run('tmux', ['list-sessions', '-F', fmt]);
  if (err) return []; // no server running -> no sessions
  const out = [];
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created, attached, activity, windows] = line.split('\t');
    let state = null, stateSince = null;
    try {
      const p = path.join(STATE_DIR, name);
      state = fs.readFileSync(p, 'utf8').trim() || null;
      stateSince = Math.floor(fs.statSync(p).mtimeMs / 1000);
    } catch { /* no state recorded — hook not fired yet or headless */ }
    out.push({
      name,
      createdAt: Number(created),
      activityAt: Number(activity),
      attachedClients: Number(attached),
      windows: Number(windows),
      state, stateSince,
    });
  }
  out.sort((a, b) => b.activityAt - a.activityAt);
  return out;
}

async function sessionExists(name) {
  const { err } = await run('tmux', ['has-session', '-t', `=${name}`]);
  return !err;
}

async function captureScreen(name) {
  const fmt = '#{pane_width}\t#{pane_height}\t#{cursor_x}\t#{cursor_y}\t#{session_attached}\t#{alternate_on}';
  const [dim, cap] = await Promise.all([
    run('tmux', ['display-message', '-p', '-t', `=${name}:`, fmt]),
    run('tmux', ['capture-pane', '-p', '-e', '-t', `=${name}:`]),
  ]);
  if (dim.err || cap.err) return null;
  const [w, h, cx, cy, attached, altOn] = dim.stdout.trim().split('\t');
  // capture-pane emits exactly pane_height lines (some empty); keep them so the
  // client can render a stable screen without jumping.
  const lines = cap.stdout.replace(/\n$/, '').split('\n');
  return {
    width: Number(w), height: Number(h),
    cursorX: Number(cx), cursorY: Number(cy),
    attachedClients: Number(attached),
    altScreen: altOn === '1',
    lines,
  };
}

// Named keys the app may send. C-<letter> covered by regex; everything else
// must be in this set. Anything not matching is rejected, not passed through.
const NAMED_KEYS = new Set([
  'Enter', 'Escape', 'Tab', 'BTab', 'Space', 'BSpace', 'DC',
  'Up', 'Down', 'Left', 'Right', 'Home', 'End', 'PPage', 'NPage',
]);
function validKey(k) {
  return NAMED_KEYS.has(k) || /^C-[a-z]$/.test(k) || /^M-[a-z]$/.test(k) || /^F([1-9]|1[0-2])$/.test(k);
}

// ------------------------------------------------------------ chats storage

const CHATS_DIR = path.join(DATA_DIR, 'chats');
fs.mkdirSync(CHATS_DIR, { recursive: true });

function chatDir(id) { return path.join(CHATS_DIR, id); }
function metaPath(id) { return path.join(chatDir(id), 'meta.json'); }
function msgsPath(id) { return path.join(chatDir(id), 'messages.jsonl'); }

function loadMeta(id) {
  try { return JSON.parse(fs.readFileSync(metaPath(id), 'utf8')); } catch { return null; }
}
function saveMeta(meta) {
  fs.mkdirSync(chatDir(meta.id), { recursive: true });
  fs.writeFileSync(metaPath(meta.id), JSON.stringify(meta, null, 2));
}
function appendMsg(id, rec) {
  fs.appendFileSync(msgsPath(id), JSON.stringify(rec) + '\n');
}
function loadMsgs(id) {
  try {
    return fs.readFileSync(msgsPath(id), 'utf8').split('\n').filter(Boolean).map((l) => {
      try { return JSON.parse(l); } catch { return null; }
    }).filter(Boolean);
  } catch { return []; }
}
function listChats() {
  let ids = [];
  try { ids = fs.readdirSync(CHATS_DIR); } catch { /* empty */ }
  const metas = [];
  for (const id of ids) {
    const m = loadMeta(id);
    if (m) {
      m.running = activeRuns.has(id);
      metas.push(m);
    }
  }
  metas.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  return metas;
}

// -------------------------------------------------------------- chat runs

// One active `claude -p` per chat. Each run keeps a bounded replay buffer of
// SSE events so a phone that locks mid-answer can reattach and catch up.
const activeRuns = new Map(); // chatId -> run

class Run {
  constructor(chatId) {
    this.chatId = chatId;
    this.seq = 0;
    this.buffer = [];           // [{seq, event, data}]
    this.subscribers = new Set(); // http responses in SSE mode
    this.proc = null;
    this.assistantText = '';    // text accumulated for the current turn
    this.done = false;
  }
  emit(event, data) {
    const item = { seq: ++this.seq, event, data };
    this.buffer.push(item);
    if (this.buffer.length > 4000) this.buffer.splice(0, this.buffer.length - 4000);
    const payload = `id: ${item.seq}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const res of this.subscribers) {
      try { res.write(payload); } catch { this.subscribers.delete(res); }
    }
  }
  subscribe(res, since) {
    for (const item of this.buffer) {
      if (item.seq > since) {
        res.write(`id: ${item.seq}\nevent: ${item.event}\ndata: ${JSON.stringify(item.data)}\n\n`);
      }
    }
    if (this.done) { try { res.end(); } catch { } return; }
    this.subscribers.add(res);
    res.on('close', () => this.subscribers.delete(res));
  }
  finish() {
    this.done = true;
    for (const res of this.subscribers) { try { res.end(); } catch { } }
    this.subscribers.clear();
    activeRuns.delete(this.chatId);
  }
}

function toolInputDigest(input) {
  try {
    if (input && typeof input === 'object') {
      // Prefer the human-meaningful field when the tool has an obvious one.
      const pick = input.command || input.file_path || input.pattern || input.url || input.query || input.prompt;
      if (typeof pick === 'string') return pick.length > 200 ? pick.slice(0, 200) + '…' : pick;
    }
    const s = JSON.stringify(input);
    return s.length > 200 ? s.slice(0, 200) + '…' : s;
  } catch { return ''; }
}

function startRun(meta, userText) {
  const chatId = meta.id;
  const runsActive = activeRuns.size;
  if (activeRuns.has(chatId)) return { error: 'chat already has an active run', code: 409 };
  if (runsActive >= MAX_CONCURRENT_RUNS) return { error: `too many concurrent runs (${runsActive})`, code: 429 };

  const now = Math.floor(Date.now() / 1000);
  appendMsg(chatId, { type: 'user', text: userText, ts: now });
  meta.updatedAt = now;
  meta.lastSnippet = userText.slice(0, 120);
  saveMeta(meta);

  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages'];
  if (meta.claudeSessionId) args.push('--resume', meta.claudeSessionId);
  let persona = '';
  try { persona = fs.readFileSync(PERSONA_FILE, 'utf8'); } catch { /* interactive-only host */ }
  if (persona) args.push('--append-system-prompt', persona);
  args.push('--allowedTools', TOOLS[meta.mode] || TOOLS.ask);

  const run_ = new Run(chatId);
  activeRuns.set(chatId, run_);

  const proc = spawn('claude', args, {
    cwd: WORKDIR,
    env: { ...process.env, TERM: 'dumb' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  run_.proc = proc;
  proc.stdin.end(userText);

  const killer = setTimeout(() => { try { proc.kill('SIGKILL'); } catch { } }, RUN_HARD_CAP_MS);

  run_.emit('started', { chatId, ts: now });

  let outBuf = '';
  proc.stdout.on('data', (chunk) => {
    outBuf += chunk.toString('utf8');
    let idx;
    while ((idx = outBuf.indexOf('\n')) >= 0) {
      const line = outBuf.slice(0, idx).trim();
      outBuf = outBuf.slice(idx + 1);
      if (!line) continue;
      let ev;
      try { ev = JSON.parse(line); } catch { continue; }
      handleClaudeEvent(meta, run_, ev);
    }
  });
  let errBuf = '';
  proc.stderr.on('data', (c) => { errBuf = (errBuf + c.toString('utf8')).slice(-4000); });

  proc.on('close', (code) => {
    clearTimeout(killer);
    const ts = Math.floor(Date.now() / 1000);
    if (!run_.sawResult) {
      // Crashed / killed / cancelled with no result event — record what we know.
      const errText = run_.cancelled ? 'cancelled' : `claude exited ${code}${errBuf ? `: ${errBuf.slice(-500)}` : ''}`;
      if (run_.assistantText) appendMsg(chatId, { type: 'assistant', text: run_.assistantText, ts, partial: true });
      appendMsg(chatId, { type: 'error', text: errText, ts });
      run_.emit('error', { text: errText });
      const m = loadMeta(chatId);
      if (m) { m.updatedAt = ts; m.lastSnippet = errText.slice(0, 120); saveMeta(m); }
    }
    run_.emit('done', { exitCode: code });
    run_.finish();
    log(`chat ${chatId} run finished (exit ${code})`);
  });

  log(`chat ${chatId} run started (mode=${meta.mode}, resume=${meta.claudeSessionId || 'new'})`);
  return { run: run_ };
}

function handleClaudeEvent(meta, run_, ev) {
  const ts = Math.floor(Date.now() / 1000);
  const chatId = meta.id;
  switch (ev.type) {
    case 'system': {
      if (ev.subtype === 'init' && ev.session_id && !meta.claudeSessionId) {
        meta.claudeSessionId = ev.session_id;
        saveMeta(meta);
      }
      break;
    }
    case 'stream_event': {
      const e = ev.event || {};
      if (e.type === 'content_block_delta' && e.delta && e.delta.type === 'text_delta') {
        run_.assistantText += e.delta.text;
        run_.emit('delta', { text: e.delta.text });
      } else if (e.type === 'content_block_start' && e.content_block && e.content_block.type === 'tool_use') {
        run_.emit('tool_start', { name: e.content_block.name });
      }
      break;
    }
    case 'assistant': {
      const content = (ev.message && ev.message.content) || [];
      for (const block of content) {
        if (block.type === 'text' && block.text) {
          appendMsg(chatId, { type: 'assistant', text: block.text, ts });
          run_.emit('assistant', { text: block.text });
          run_.assistantText = '';
        } else if (block.type === 'tool_use') {
          const rec = { type: 'tool', name: block.name, input: toolInputDigest(block.input), ts };
          appendMsg(chatId, rec);
          run_.emit('tool', rec);
        }
      }
      break;
    }
    case 'result': {
      run_.sawResult = true;
      const rec = {
        type: 'result',
        ok: !ev.is_error,
        durationMs: ev.duration_ms ?? null,
        costUsd: ev.total_cost_usd ?? null,
        turns: ev.num_turns ?? null,
        ts,
      };
      appendMsg(chatId, rec);
      run_.emit('result', rec);
      const m = loadMeta(chatId) || meta;
      m.updatedAt = ts;
      const finalText = typeof ev.result === 'string' ? ev.result : run_.assistantText;
      if (finalText) m.lastSnippet = finalText.slice(0, 120);
      m.turns = (m.turns || 0) + 1;
      saveMeta(m);
      break;
    }
    default: break;
  }
}

// ---------------------------------------------------------------- status

let cachedClaudeVersion = null;
async function claudeVersion() {
  if (cachedClaudeVersion) return cachedClaudeVersion;
  const { stdout } = await run('claude', ['--version']);
  cachedClaudeVersion = stdout.trim().split('\n')[0] || 'unknown';
  setTimeout(() => { cachedClaudeVersion = null; }, 60 * 60 * 1000).unref();
  return cachedClaudeVersion;
}

let mpCache = { at: 0, value: 'unknown' };
async function mempalaceState() {
  if (Date.now() - mpCache.at < 60_000) return mpCache.value;
  const { err, stdout } = await run('ssh',
    ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=3', MUNINN,
      'if [ -e /home/silence/.mempalace/REBUILD_IN_PROGRESS ]; then echo rebuilding; elif systemctl is-active --quiet mempalace-daemon; then echo ok; else echo daemon-down; fi'],
    { timeout: 6_000 });
  mpCache = { at: Date.now(), value: err ? 'unreachable' : stdout.trim() || 'unknown' };
  return mpCache.value;
}

async function statusPayload() {
  const [ver, mp, df, sessions] = await Promise.all([
    claudeVersion(), mempalaceState(),
    run('df', ['-h', '/']),
    listSessions(),
  ]);
  let disk = null;
  const lines = df.stdout.trim().split('\n');
  if (lines.length >= 2) {
    const f = lines[1].split(/\s+/);
    disk = { size: f[1], used: f[2], free: f[3], usedPercent: f[4] };
  }
  return {
    host: os.hostname(),
    appdVersion: VERSION,
    uptimeSec: Math.floor(os.uptime()),
    load: os.loadavg().map((x) => Math.round(x * 100) / 100),
    cores: os.cpus().length,
    claude: ver,
    mempalace: mp,
    disk,
    sessions: sessions.length,
    chatsRunning: activeRuns.size,
  };
}

// ---------------------------------------------------------------- routing

const server = http.createServer(async (req, res) => {
  const t0 = Date.now();
  const u = new URL(req.url, 'http://x');
  const p = u.pathname.replace(/\/+$/, '') || '/';
  res.on('finish', () => log(`${req.method} ${p} ${res.statusCode} ${Date.now() - t0}ms`));

  if (!authorized(req)) return sendErr(res, 401, 'unauthorized');

  try {
    // --- ping / status
    if (req.method === 'GET' && p === '/v1/ping') return sendJson(res, 200, { ok: true, version: VERSION, host: os.hostname() });
    if (req.method === 'GET' && p === '/v1/status') return sendJson(res, 200, await statusPayload());

    // --- sessions
    if (req.method === 'GET' && p === '/v1/sessions') return sendJson(res, 200, { sessions: await listSessions() });

    if (req.method === 'POST' && p === '/v1/sessions') {
      const body = JSON.parse(await readBody(req) || '{}');
      const name = canonName(body.name);
      if (!name) return sendErr(res, 400, 'invalid session name (letters, digits, underscore)');
      if (await sessionExists(name)) return sendErr(res, 409, `session '${name}' already exists`);
      // Same shape as cc: open in ~/netplan, claude first, fall through to a shell.
      const { err, stderr } = await run('tmux',
        ['new-session', '-d', '-s', name, '-c', WORKDIR, 'claude; exec "$SHELL" -l']);
      if (err) return sendErr(res, 500, `tmux: ${stderr.trim() || err.message}`);
      return sendJson(res, 201, { ok: true, name });
    }

    let m;
    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})$/)) && req.method === 'DELETE') {
      const name = m[1];
      const { err, stderr } = await run('tmux', ['kill-session', '-t', `=${name}`]);
      if (err) return sendErr(res, 404, `tmux: ${stderr.trim() || 'no such session'}`);
      return sendJson(res, 200, { ok: true });
    }

    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/screen$/)) && req.method === 'GET') {
      const scr = await captureScreen(m[1]);
      if (!scr) return sendErr(res, 404, 'no such session');
      return sendJson(res, 200, scr);
    }

    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/keys$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const body = JSON.parse(await readBody(req) || '{}');
      if (typeof body.text === 'string' && body.text.length > 0) {
        if (body.text.length > 8000) return sendErr(res, 400, 'text too long');
        const r = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', body.text]);
        if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim()}`);
      }
      if (Array.isArray(body.keys)) {
        if (body.keys.length > 32) return sendErr(res, 400, 'too many keys');
        for (const k of body.keys) {
          if (!validKey(k)) return sendErr(res, 400, `key not allowed: ${k}`);
        }
        for (const k of body.keys) {
          const r = await run('tmux', ['send-keys', '-t', `=${name}:`, k]);
          if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim()}`);
        }
      }
      return sendJson(res, 200, { ok: true });
    }

    // --- chats
    if (req.method === 'GET' && p === '/v1/chats') return sendJson(res, 200, { chats: listChats() });

    if (req.method === 'POST' && p === '/v1/chats') {
      const body = JSON.parse(await readBody(req) || '{}');
      const mode = body.mode === 'act' ? 'act' : 'ask';
      const now = Math.floor(Date.now() / 1000);
      const meta = {
        id: crypto.randomUUID(),
        title: (typeof body.title === 'string' && body.title.trim().slice(0, 80)) || null,
        mode,
        createdAt: now,
        updatedAt: now,
        claudeSessionId: null,
        lastSnippet: null,
        turns: 0,
      };
      saveMeta(meta);
      return sendJson(res, 201, meta);
    }

    const chatIdRe = /^\/v1\/chats\/([0-9a-f-]{36})(\/.*)?$/;
    if ((m = p.match(chatIdRe))) {
      const id = m[1]; const sub = m[2] || '';
      const meta = loadMeta(id);
      if (!meta) return sendErr(res, 404, 'no such chat');
      meta.running = activeRuns.has(id);

      if (req.method === 'GET' && sub === '') {
        const run_ = activeRuns.get(id);
        return sendJson(res, 200, {
          ...meta,
          messages: loadMsgs(id),
          // partial text of an in-flight turn so a cold open shows progress
          partialText: run_ ? run_.assistantText : null,
        });
      }
      if (req.method === 'DELETE' && sub === '') {
        if (meta.running) return sendErr(res, 409, 'chat has an active run — cancel first');
        fs.rmSync(chatDir(id), { recursive: true, force: true });
        return sendJson(res, 200, { ok: true });
      }
      if (req.method === 'POST' && sub === '/messages') {
        const body = JSON.parse(await readBody(req) || '{}');
        const text = typeof body.text === 'string' ? body.text.trim() : '';
        if (!text) return sendErr(res, 400, 'text required');
        if (text.length > 100_000) return sendErr(res, 400, 'text too long');
        const started = startRun(meta, text);
        if (started.error) return sendErr(res, started.code, started.error);
        // Auto-title from the first message.
        if (!meta.title) { meta.title = text.slice(0, 60); saveMeta(meta); }
        if (u.searchParams.get('stream') === '1') {
          res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no',
          });
          started.run.subscribe(res, 0);
          return;
        }
        return sendJson(res, 202, { ok: true, running: true });
      }
      if (req.method === 'GET' && sub === '/stream') {
        const run_ = activeRuns.get(id);
        const since = Number(u.searchParams.get('since') || 0);
        res.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
          'X-Accel-Buffering': 'no',
        });
        if (!run_) { res.write('event: done\ndata: {"idle":true}\n\n'); res.end(); return; }
        run_.subscribe(res, since);
        return;
      }
      if (req.method === 'POST' && sub === '/cancel') {
        const run_ = activeRuns.get(id);
        if (!run_) return sendErr(res, 409, 'no active run');
        run_.cancelled = true;
        try { run_.proc.kill('SIGTERM'); } catch { }
        setTimeout(() => { try { run_.proc.kill('SIGKILL'); } catch { } }, 5000).unref();
        return sendJson(res, 200, { ok: true });
      }
      if (req.method === 'PATCH' && sub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        if (typeof body.title === 'string' && body.title.trim()) {
          meta.title = body.title.trim().slice(0, 80);
          saveMeta(meta);
        }
        return sendJson(res, 200, meta);
      }
    }

    return sendErr(res, 404, 'not found');
  } catch (e) {
    log('ERROR', req.method, p, e.message);
    if (!res.headersSent) return sendErr(res, 500, e.message);
    try { res.end(); } catch { }
  }
});

// SSE heartbeat so half-open phone connections die fast instead of lingering.
setInterval(() => {
  for (const run_ of activeRuns.values()) {
    for (const res of run_.subscribers) {
      try { res.write(': ping\n\n'); } catch { }
    }
  }
}, 15_000).unref();

// Bind the tailscale address only. Resolved at startup; systemd orders us after
// tailscaled and restarts us if the address is not yet available.
function resolveBind() {
  if (process.env.HUGINN_APPD_BIND) return Promise.resolve(process.env.HUGINN_APPD_BIND);
  return new Promise((resolve, reject) => {
    execFile('tailscale', ['ip', '-4'], { timeout: 5000 }, (err, stdout) => {
      if (err || !stdout.trim()) return reject(new Error('tailscale ip -4 failed — is tailscaled up?'));
      resolve(stdout.trim().split('\n')[0]);
    });
  });
}

resolveBind().then((bind) => {
  server.listen(PORT, bind, () => log(`huginn-appd ${VERSION} listening on ${bind}:${PORT}`));
}).catch((e) => { console.error('FATAL:', e.message); process.exit(1); });
