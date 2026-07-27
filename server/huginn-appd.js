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
const { screenHash, previewLines, detectPrompt, extractLoginUrl, parseStatusLine } = require('./lib/pane');
const { readTranscript } = require('./lib/transcript');
const { summarizeUsage } = require('./lib/usage');
const { normalizePlan } = require('./lib/plan');
const { AccountStore } = require('./lib/accounts');

const VERSION = '2.6.0';
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

/**
 * Reads the per-session state the huginn-claude-title hook records. JSON since
 * v2 (carrying the session id + transcript path, which is the only way to map a
 * tmux session to its transcript); a bare state word from an older hook is still
 * accepted so a half-updated host degrades instead of breaking.
 */
function readSessionState(name) {
  let raw;
  try { raw = fs.readFileSync(path.join(STATE_DIR, name), 'utf8').trim(); } catch { return null; }
  if (!raw) return null;
  let mtime = null;
  try { mtime = Math.floor(fs.statSync(path.join(STATE_DIR, name)).mtimeMs / 1000); } catch { }
  if (raw[0] === '{') {
    try {
      const o = JSON.parse(raw);
      return {
        state: o.state || null,
        sessionId: o.sessionId || null,
        transcript: o.transcript || null,
        cwd: o.cwd || null,
        stateSince: o.ts || mtime,
      };
    } catch { /* fall through to the bare-word path */ }
  }
  return { state: raw, sessionId: null, transcript: null, cwd: null, stateSince: mtime };
}

async function listSessions({ preview = false } = {}) {
  const fmt = '#{session_name}\t#{session_created}\t#{session_attached}\t#{session_activity}\t' +
    '#{session_windows}\t#{window_width}\t#{window_height}\t#{window-size}';
  const { err, stdout } = await run('tmux', ['list-sessions', '-F', fmt]);
  if (err) return []; // no server running -> no sessions
  const rows = [];
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created, attached, activity, windows, w, h, wsize] = line.split('\t');
    const st = readSessionState(name) || {};
    rows.push({
      name,
      createdAt: Number(created),
      activityAt: Number(activity),
      attachedClients: Number(attached),
      windows: Number(windows),
      cols: Number(w),
      rows: Number(h),
      windowSize: wsize || null,
      sizeLeased: leases.has(name),
      state: st.state ?? null,
      stateSince: st.stateSince ?? null,
      claudeSessionId: st.sessionId ?? null,
      hasTranscript: !!(st.transcript && fs.existsSync(st.transcript)),
      title: null,
      preview: [],
    });
  }

  if (preview) {
    // Title comes from the transcript (Claude Code's own ai-title, which is a far
    // better label than the tmux name); the preview lines come from the pane,
    // because the spinner/progress state a user wants at a glance is drawn by the
    // TUI and never lands in the transcript.
    await Promise.all(rows.map(async (r) => {
      const st = readSessionState(r.name);
      if (st && st.transcript) {
        try {
          const t = readTranscript(st.transcript, { limit: 1 });
          if (t.title) r.title = t.title;
          if (t.permissionMode) r.permissionMode = t.permissionMode;
        } catch { /* transcript unreadable: not fatal for a list */ }
      }
      const cap = await run('tmux', ['capture-pane', '-p', '-t', `=${r.name}:`]);
      if (!cap.err) {
        const paneLines = cap.stdout.replace(/\n$/, '').split('\n');
        r.preview = previewLines(paneLines, 2);
        const st = parseStatusLine(paneLines);
        r.liveModel = st.model;
        r.liveMode = st.mode;
      }
    }));
  }

  rows.sort((a, b) => b.activityAt - a.activityAt);
  return rows;
}

async function sessionExists(name) {
  const { err } = await run('tmux', ['has-session', '-t', `=${name}`]);
  return !err;
}

// ---- pane sizing, as an expiring lease -------------------------------------
//
// tmux sizes a window to its attached clients; with no client attached it keeps
// whatever size it was created at (80x24 from `cc`). That makes the phone view a
// cramped, truncated window of a layout drawn for a laptop. Resizing the window
// to the phone's real geometry fixes it AND makes Claude Code re-wrap its own
// output to fit, which is the actual goal.
//
// The hazard: resizing requires `window-size manual`, and a manual window does
// NOT re-fit when a laptop later attaches — it would leave a 45x40 window inside
// a 200x50 terminal (verified on tmux 3.6b). So a resize is a LEASE, never a
// permanent change: it expires on its own, is renewed by continued viewing, and
// is released on every exit path including a crash (the startup sweep). The
// laptop can therefore never be left with a shrunken window by an app that was
// force-quit or a phone that went out of range.
//
// TARGETING: `window-size` is a per-WINDOW option, and the target `=name:` means
// "session name, CURRENT window". Leasing by that target and later releasing by
// it releases whichever window happens to be active THEN — so if the user opens
// or switches to another window (prefix+c) after a lease is taken, the leased
// window keeps `manual` forever and every release path misses it, including the
// sweeps. Verified on tmux 3.6b. Leases therefore record the concrete
// `#{window_id}` (`@14`) and operate on that.
const leases = new Map(); // session name -> {windowId, cols, rows, expiresAt}
const LEASE_MS = 90_000;
const LEASE_SWEEP_MS = 15_000;

async function currentWindowId(name) {
  const { err, stdout } = await run('tmux', ['display-message', '-p', '-t', `=${name}:`, '#{window_id}']);
  if (err) return null;
  const id = stdout.trim();
  return /^@\d+$/.test(id) ? id : null;
}

async function acquireSize(name, cols, rows) {
  cols = Math.max(20, Math.min(300, Math.floor(cols)));
  rows = Math.max(10, Math.min(200, Math.floor(rows)));
  const windowId = await currentWindowId(name);
  if (!windowId) return false;

  const cur = leases.get(name);
  // A window switch makes the old lease stale: hand that window back before
  // touching the new one, or it stays manual with nothing left to release it.
  if (cur && cur.windowId !== windowId) {
    await releaseWindow(cur.windowId, `${name} (window switched)`);
    leases.delete(name);
  }

  const held = leases.get(name);
  if (!held || held.cols !== cols || held.rows !== rows) {
    const a = await run('tmux', ['set-option', '-t', windowId, 'window-size', 'manual']);
    if (a.err) return false;
    const b = await run('tmux', ['resize-window', '-t', windowId, '-x', String(cols), '-y', String(rows)]);
    if (b.err) { await releaseWindow(windowId, name); leases.delete(name); return false; }
    log(`lease ${name} ${windowId} -> ${cols}x${rows}`);
  }
  leases.set(name, { windowId, cols, rows, expiresAt: Date.now() + LEASE_MS });
  return true;
}

/** Unsetting restores the inherited default and tmux re-fits any client at once. */
async function releaseWindow(windowId, label) {
  await run('tmux', ['set-option', '-u', '-t', windowId, 'window-size']);
  log(`lease released: ${label} ${windowId}`);
}

async function releaseSize(name) {
  const l = leases.get(name);
  leases.delete(name);
  if (l) await releaseWindow(l.windowId, name);
  else {
    // No record (daemon restarted mid-view): fall back to the current window.
    const id = await currentWindowId(name);
    if (id) await releaseWindow(id, name);
  }
}

setInterval(() => {
  const now = Date.now();
  for (const [name, l] of leases) {
    if (l.expiresAt <= now) releaseSize(name).catch(() => { });
  }
}, LEASE_SWEEP_MS).unref();

/**
 * Releases EVERY window left at a manual size, across all sessions. Runs at
 * startup so a daemon killed mid-view cannot strand a laptop with a phone-sized
 * window, and on shutdown for the ordinary case.
 *
 * Enumerates windows, not sessions: `list-sessions -F '#{window-size}'` reports
 * only each session's active window, so a stranded background window would be
 * invisible to it.
 */
async function sweepStrandedSizes(reason) {
  const { err, stdout } = await run('tmux',
    ['list-windows', '-a', '-F', '#{window_id}\t#{session_name}:#{window_index}\t#{window-size}']);
  if (err) return;
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [windowId, label, wsize] = line.split('\t');
    if (wsize === 'manual') {
      log(`${reason}: releasing stranded manual size on ${label}`);
      await run('tmux', ['set-option', '-u', '-t', windowId, 'window-size']);
    }
  }
  leases.clear();
}

/**
 * The cheapest possible "did anything change?": ONE tmux process producing both
 * the cursor position and the pane, chained with `;` so the parked long poll
 * does not spawn three processes a tick. The hash must be computed exactly as
 * captureScreen computes it or every poll would look like a change.
 */
async function peekHash(name) {
  const r = await run('tmux', [
    'display-message', '-p', '-t', `=${name}:`, '#{cursor_x},#{cursor_y}',
    ';', 'capture-pane', '-p', '-e', '-t', `=${name}:`,
  ]);
  if (r.err) return null;
  const out = r.stdout.replace(/\n$/, '').split('\n');
  const [cx = '0', cy = '0'] = (out[0] || '0,0').split(',');
  const lines = out.slice(1);
  return { hash: screenHash(lines.join('\n') + `|${cx},${cy}`) };
}

/**
 * @param name    session
 * @param opts.cols/rows  request this geometry (takes/renews a lease)
 * @param opts.history    include this many scrollback lines above the screen
 * @param opts.force      resize even though another client is attached
 */
async function captureScreen(name, { cols = null, rows = null, history = 0, force = false } = {}) {
  const fmt = '#{pane_width}\t#{pane_height}\t#{cursor_x}\t#{cursor_y}\t#{session_attached}\t' +
    '#{alternate_on}\t#{history_size}\t#{window-size}';
  let dim = await run('tmux', ['display-message', '-p', '-t', `=${name}:`, fmt]);
  if (dim.err) return null;
  let attached = Number(dim.stdout.trim().split('\t')[4]);

  // Refuse to shrink a window somebody is actually looking at unless told to.
  let resizeBlocked = false;
  if (cols && rows) {
    if (attached > 0 && !force) {
      resizeBlocked = true;
    } else if (await acquireSize(name, cols, rows)) {
      // Only re-read geometry when the resize actually changed something; a
      // renewal of an identical lease issues no tmux command and cannot have
      // moved the pane.
      const held = leases.get(name);
      if (!held || held.cols !== Number(dim.stdout.split('\t')[0]) ) {
        dim = await run('tmux', ['display-message', '-p', '-t', `=${name}:`, fmt]);
        if (dim.err) return null;
      }
    }
  }

  const [w, h, cx, cy, att, altOn, hist, wsize] = dim.stdout.trim().split('\t');
  const capArgs = ['capture-pane', '-p', '-e', '-t', `=${name}:`];
  const histWant = Math.max(0, Math.min(2000, Math.floor(history)));
  if (histWant > 0) capArgs.splice(2, 0, '-S', `-${histWant}`);
  const cap = await run('tmux', capArgs);
  if (cap.err) return null;

  const all = cap.stdout.replace(/\n$/, '').split('\n');
  const height = Number(h);
  // With history, everything before the last `height` lines is scrollback.
  const scrollback = histWant > 0 && all.length > height ? all.slice(0, all.length - height) : [];
  const lines = histWant > 0 && all.length > height ? all.slice(-height) : all;

  return {
    width: Number(w), height,
    cursorX: Number(cx), cursorY: Number(cy),
    attachedClients: Number(att),
    altScreen: altOn === '1',
    historySize: Number(hist),
    windowSize: wsize,
    sizeLeased: leases.has(name),
    resizeBlocked,
    lines,
    scrollback,
    hash: screenHash(lines.join('\n') + `|${cx},${cy}`),
    prompt: detectPrompt(lines),
    // The pane is the only CURRENT source for these; the transcript lags a turn.
    ...(() => {
      const st = parseStatusLine(lines);
      return { liveModel: st.model, liveMode: st.mode, liveBranch: st.branch };
    })(),
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

/**
 * Locates a session's transcript by id. Chats run in WORKDIR, so the slug is
 * predictable, but a chat resumed after a cwd change (or an older layout) can
 * sit under a different project dir — so fall back to a scan rather than
 * claiming there is no transcript.
 */
function findTranscriptFile(sessionId) {
  if (!/^[0-9a-f-]{36}$/.test(sessionId)) return null;
  const root = path.join(os.homedir(), '.claude', 'projects');
  const slug = WORKDIR.replace(/\//g, '-');
  const first = path.join(root, slug, `${sessionId}.jsonl`);
  if (fs.existsSync(first)) return first;
  let dirs = [];
  try { dirs = fs.readdirSync(root); } catch { return null; }
  for (const d of dirs) {
    const c = path.join(root, d, `${sessionId}.jsonl`);
    if (fs.existsSync(c)) return c;
  }
  return null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const CREDENTIALS_PATH = path.join(os.homedir(), '.claude', '.credentials.json');
const accounts = new AccountStore(path.join(DATA_DIR, 'accounts'), CREDENTIALS_PATH);

/**
 * Plan utilization for a SAVED account, so you can see which login has headroom
 * before switching to it. Best effort: a stored access token expires, and this
 * daemon deliberately does not implement the refresh flow — an expired one
 * simply reports unknown, and refreshes itself once that account is active and
 * Claude Code runs under it.
 */
async function planForCredentials(creds) {
  const token = creds && creds.claudeAiOauth && creds.claudeAiOauth.accessToken;
  if (!token) return null;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 10_000);
  try {
    const resp = await fetch('https://api.anthropic.com/api/oauth/usage', {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'anthropic-beta': 'oauth-2025-04-20',
      },
      signal: ac.signal,
    });
    if (!resp.ok) return null;
    return normalizePlan(await resp.json());
  } catch { return null; } finally { clearTimeout(timer); }
}

// ------------------------------------------------------- account + usage

/** `claude auth status` already emits JSON; pass it through, minus nothing secret. */
async function accountStatus() {
  const { err, stdout } = await run('claude', ['auth', 'status'], { timeout: 20_000 });
  if (err) return { loggedIn: false, error: 'could not read auth status' };
  try {
    const o = JSON.parse(stdout);
    return {
      loggedIn: !!o.loggedIn,
      email: o.email ?? null,
      orgName: o.orgName ?? null,
      subscriptionType: o.subscriptionType ?? null,
      authMethod: o.authMethod ?? null,
      apiProvider: o.apiProvider ?? null,
    };
  } catch {
    return { loggedIn: false, error: 'unexpected auth status output' };
  }
}

/**
 * Plan utilization — the numbers Claude Code's own `/usage` shows.
 *
 * Read from the same endpoint the CLI uses, with the OAuth access token from
 * this host's credentials file. The token never leaves the daemon: the app is
 * handed percentages and reset times only. Cached briefly because the phone
 * polls the settings screen and this is a network round trip.
 */
const planCache = { at: 0, data: null, error: null, running: false };
const PLAN_TTL_MS = 60_000;

async function fetchPlan() {
  if (planCache.running) return;
  planCache.running = true;
  try {
    let creds;
    try {
      creds = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.claude', '.credentials.json'), 'utf8'));
    } catch {
      planCache.error = 'no credentials on this host';
      return;
    }
    const token = creds && creds.claudeAiOauth && creds.claudeAiOauth.accessToken;
    if (!token) { planCache.error = 'not signed in with an OAuth account'; return; }

    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 15_000);
    let resp;
    try {
      resp = await fetch('https://api.anthropic.com/api/oauth/usage', {
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          'anthropic-beta': 'oauth-2025-04-20',
        },
        signal: ac.signal,
      });
    } finally { clearTimeout(timer); }

    if (resp.status === 401) {
      // The CLI refreshes this token as it runs; a stale one is transient.
      planCache.error = 'access token expired, refreshes on the next Claude run';
      return;
    }
    if (!resp.ok) { planCache.error = `plan usage HTTP ${resp.status}`; return; }
    planCache.data = normalizePlan(await resp.json());
    planCache.at = Date.now();
    planCache.error = null;
  } catch (e) {
    planCache.error = String((e && e.message) || e).slice(0, 200);
  } finally {
    planCache.running = false;
  }
}

// ccusage walks every transcript on disk and takes ~20-30 s even for one day,
// so it is computed in the background and served from cache. The phone gets an
// immediate answer that says how old it is, rather than a 30 s spinner.
const usageCache = { at: 0, data: null, running: false, error: null, failedAt: 0 };
const USAGE_TTL_MS = 10 * 60 * 1000;
// After a failure, wait before trying again: a 30 s job re-triggered by every
// poll would pin a core for nothing.
const USAGE_RETRY_MS = 2 * 60 * 1000;

function ymd(d) {
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

async function computeUsage() {
  if (usageCache.running) return;
  usageCache.running = true;
  try {
    const now = new Date();
    const weekAgo = new Date(now.getTime() - 6 * 86_400_000);
    const { err, stdout, stderr } = await run(
      'ccusage', ['daily', '--json', '-s', ymd(weekAgo), '-u', ymd(now)],
      { timeout: 180_000, maxBuffer: 32 * 1024 * 1024, env: { ...process.env, HOME: process.env.HOME || '/root' } },
    );
    if (err) {
      usageCache.error = (stderr || err.message || 'ccusage failed').trim().slice(0, 200);
      usageCache.failedAt = Date.now();
      return;
    }
    const parsed = JSON.parse(stdout);
    usageCache.data = summarizeUsage(parsed, ymd(now));
    usageCache.at = Date.now();
    usageCache.error = null;
    log(`usage refreshed (${usageCache.data.daily.length} days)`);
  } catch (e) {
    usageCache.error = String(e.message || e).slice(0, 200);
    usageCache.failedAt = Date.now();
  } finally {
    usageCache.running = false;
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
    let m;   // shared by the path-matching routes below
    // --- ping / status
    if (req.method === 'GET' && p === '/v1/ping') return sendJson(res, 200, { ok: true, version: VERSION, host: os.hostname() });
    if (req.method === 'GET' && p === '/v1/status') return sendJson(res, 200, await statusPayload());

    // --- account
    if (req.method === 'GET' && p === '/v1/account') {
      const acct = await accountStatus();
      // Snapshot whatever is signed in right now, so switching away is always
      // reversible even if this account was never explicitly saved.
      if (acct.loggedIn && acct.email) {
        const cur = accounts.readActive();
        if (cur) accounts.save(acct.email, cur, { orgName: acct.orgName ?? null });
      }
      return sendJson(res, 200, acct);
    }

    // --- saved accounts
    if (req.method === 'GET' && p === '/v1/accounts') {
      const withPlan = u.searchParams.get('plan') === '1';
      const saved = accounts.list();
      if (withPlan) {
        await Promise.all(saved.map(async (a) => {
          const rec = accounts.readProfile(a.slug);
          const pl = rec && await planForCredentials(rec.credentials);
          // The weekly all-models figure is the one that decides whether an
          // account still has room.
          const weekly = pl && pl.limits.find((l) => l.kind === 'weekly_all');
          a.weeklyPercent = weekly ? weekly.percent : null;
          a.sessionPercent = pl ? (pl.limits.find((l) => l.kind === 'session')?.percent ?? null) : null;
        }));
      }
      return sendJson(res, 200, { accounts: saved });
    }

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})$/)) && req.method === 'DELETE') {
      if (!accounts.remove(m[1])) return sendErr(res, 404, 'no such saved account');
      return sendJson(res, 200, { ok: true });
    }

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})\/activate$/)) && req.method === 'POST') {
      const before = await accountStatus();
      const r = accounts.activate(m[1], before.email);
      if (!r.ok) return sendErr(res, 404, r.error);
      // Report what the host actually thinks it is now, not what we intended.
      const after = await accountStatus();
      planCache.at = 0; planCache.data = null;   // the old plan figures are not this account's
      log(`account switched: ${before.email || 'unknown'} -> ${after.email || 'unknown'}`);
      return sendJson(res, 200, { ok: true, ...after });
    }

    if (req.method === 'POST' && p === '/v1/account/login') {
      // Signing in is an interactive OAuth flow: it prints a URL and waits for a
      // code. There is no headless path, so put it in a real tmux session and
      // hand the app the session name — the Screen view can show the URL and
      // take the pasted code, which is exactly what that view is for.
      const name = 'login';
      const existed = await sessionExists(name);
      if (!existed) {
        const r = await run('tmux',
          ['new-session', '-d', '-s', name, '-c', WORKDIR, 'claude auth login; echo; echo "[done] press enter"; read _']);
        if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim() || r.err.message}`);
      }
      // Wait briefly for the URL to appear and hand it back: the pane hard-wraps
      // it across lines, which is impossible to copy on a phone, while the OSC 8
      // hyperlink target holds it whole.
      let url = null;
      for (let i = 0; i < 12 && !url; i++) {
        await sleep(700);
        const cap = await run('tmux', ['capture-pane', '-p', '-e', '-t', `=${name}:`]);
        if (!cap.err) url = extractLoginUrl(cap.stdout.replace(/\n$/, '').split('\n'));
      }
      return sendJson(res, existed ? 200 : 201, { ok: true, session: name, existed, url });
    }

    if (req.method === 'POST' && p === '/v1/account/logout') {
      // Signing out breaks every running session AND every cron on this host
      // (briefings, escalation, status-page investigation) until someone signs
      // back in, so it takes an explicit confirmation rather than a stray tap.
      const body = JSON.parse(await readBody(req) || '{}');
      if (body.confirm !== 'logout') return sendErr(res, 400, 'confirmation required');
      const r = await run('claude', ['auth', 'logout'], { timeout: 30_000 });
      if (r.err) return sendErr(res, 500, (r.stderr || r.err.message).slice(0, 200));
      return sendJson(res, 200, { ok: true, ...(await accountStatus()) });
    }

    // --- plan utilization (what Claude Code's /usage shows)
    if (req.method === 'GET' && p === '/v1/plan') {
      if (Date.now() - planCache.at > PLAN_TTL_MS && !planCache.running) await fetchPlan();
      return sendJson(res, 200, {
        ...(planCache.data || { limits: [], extraUsage: null }),
        fetchedAt: planCache.at || null,
        error: planCache.error,
      });
    }

    // --- usage
    if (req.method === 'GET' && p === '/v1/usage') {
      const stale = Date.now() - usageCache.at > USAGE_TTL_MS;
      const backingOff = usageCache.error && Date.now() - usageCache.failedAt < USAGE_RETRY_MS;
      if ((stale || !usageCache.data) && !usageCache.running && !backingOff) computeUsage();
      return sendJson(res, 200, {
        data: usageCache.data,
        computedAt: usageCache.at || null,
        stale,
        refreshing: usageCache.running,
        error: usageCache.error,
        // Tokens come straight from the transcripts and are exact; the dollar
        // figures are ccusage's list-price estimate and run high for a Max
        // subscription, so the app must not present them as a bill.
        costIsEstimate: true,
      });
    }

    // --- sessions
    if (req.method === 'GET' && p === '/v1/sessions') {
      // preview=1 costs one capture-pane + one transcript head per session, so
      // the list stays cheap for the notification poller that only needs state.
      const preview = u.searchParams.get('preview') === '1';
      return sendJson(res, 200, { sessions: await listSessions({ preview }) });
    }

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

    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})$/)) && req.method === 'DELETE') {
      const name = m[1];
      const { err, stderr } = await run('tmux', ['kill-session', '-t', `=${name}`]);
      if (err) return sendErr(res, 404, `tmux: ${stderr.trim() || 'no such session'}`);
      return sendJson(res, 200, { ok: true });
    }

    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/screen$/)) && req.method === 'GET') {
      const name = m[1];
      const q = u.searchParams;
      const opts = {
        cols: Number(q.get('cols')) || null,
        rows: Number(q.get('rows')) || null,
        history: Number(q.get('history')) || 0,
        force: q.get('force') === '1',
      };
      // Long poll: hold the request until the screen actually differs from what
      // the phone already has. An idle session then costs one parked request
      // instead of a capture every second, and a busy one updates as fast as it
      // changes rather than on a fixed tick.
      const known = q.get('hash');
      const waitMs = Math.max(0, Math.min(30_000, Number(q.get('wait')) || 0));
      const deadline = Date.now() + waitMs;
      let scr = await captureScreen(name, opts);
      if (!scr) return sendErr(res, 404, 'no such session');
      // While parked, poll with ONE cheap capture-pane rather than a full
      // captureScreen: the geometry cannot change without the content changing,
      // and re-running the resize/geometry calls on every tick cost three tmux
      // processes per iteration (~9/second per viewed session on an 8-core box).
      while (known && scr.hash === known && Date.now() < deadline && !req.destroyed) {
        await sleep(700);
        const peek = await peekHash(name);
        if (peek === null) return sendErr(res, 404, 'no such session');
        if (peek.hash !== known) {
          scr = await captureScreen(name, opts);
          if (!scr) return sendErr(res, 404, 'no such session');
          break;
        }
      }
      if (req.destroyed) return;
      if (known && scr.hash === known) {
        // Nothing changed within the window. Tell the client so, without
        // re-sending a screen it already has.
        return sendJson(res, 200, {
          unchanged: true, hash: scr.hash, width: scr.width, height: scr.height,
          attachedClients: scr.attachedClients, sizeLeased: scr.sizeLeased,
          resizeBlocked: scr.resizeBlocked,
        });
      }
      return sendJson(res, 200, scr);
    }

    // Explicitly hand the pane size back to tmux (so an attached laptop re-fits
    // immediately rather than waiting for the lease to lapse).
    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/size$/)) && req.method === 'DELETE') {
      await releaseSize(m[1]);
      return sendJson(res, 200, { ok: true });
    }

    // Structured conversation for a tmux session, straight from its Claude Code
    // transcript: thinking, tool calls, subagent output, workflow runs. This is
    // the primary way the app shows a session; the pane is for interaction.
    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/transcript$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || !st.transcript) {
        return sendErr(res, 409, 'no transcript recorded for this session yet — the Claude hook fires on the first prompt');
      }
      if (!fs.existsSync(st.transcript)) return sendErr(res, 409, 'recorded transcript file is gone');
      const offsetParam = u.searchParams.get('offset');
      const offsetNum = offsetParam == null ? null : Number(offsetParam);
      if (offsetNum !== null && !Number.isFinite(offsetNum)) return sendErr(res, 400, 'offset must be a number');
      const t = readTranscript(st.transcript, {
        offset: offsetNum,
        limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
      });
      return sendJson(res, 200, { ...t, state: st.state, claudeSessionId: st.sessionId });
    }

    if ((m = p.match(/^\/v1\/sessions\/([a-z0-9_]{1,50})\/rename$/)) && req.method === 'POST') {
      const from = m[1];
      const body = JSON.parse(await readBody(req) || '{}');
      const to = canonName(body.name);
      if (!to) return sendErr(res, 400, 'invalid session name (letters, digits, underscore)');
      if (to !== from && await sessionExists(to)) return sendErr(res, 409, `session '${to}' already exists`);
      const r = await run('tmux', ['rename-session', '-t', `=${from}`, to]);
      if (r.err) return sendErr(res, 404, `tmux: ${r.stderr.trim() || 'no such session'}`);
      // The state file is keyed by name; move it so state/transcript survive.
      try { fs.renameSync(path.join(STATE_DIR, from), path.join(STATE_DIR, to)); } catch { }
      if (leases.has(from)) { leases.set(to, leases.get(from)); leases.delete(from); }
      return sendJson(res, 200, { ok: true, name: to });
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
      // The same structured reader the sessions use. A headless run writes a
      // normal Claude Code transcript, so a chat gets thinking and subagent
      // output for free instead of only the digest this daemon persisted.
      if (req.method === 'GET' && sub === '/transcript') {
        if (!meta.claudeSessionId) return sendErr(res, 409, 'chat has not run yet');
        const file = findTranscriptFile(meta.claudeSessionId);
        if (!file) return sendErr(res, 409, 'transcript not found for this chat');
        const offsetParam = u.searchParams.get('offset');
        const offsetNum = offsetParam == null ? null : Number(offsetParam);
        if (offsetNum !== null && !Number.isFinite(offsetNum)) return sendErr(res, 400, 'offset must be a number');
        const t = readTranscript(file, {
          offset: offsetNum,
          limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
        });
        return sendJson(res, 200, { ...t, running: meta.running, mode: meta.mode });
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

resolveBind().then(async (bind) => {
  // Recover from a previous crash BEFORE serving: a session left at `window-size
  // manual` by a killed daemon would otherwise keep a laptop's window shrunken
  // with nothing left to release it.
  await sweepStrandedSizes('startup');
  server.listen(PORT, bind, () => log(`huginn-appd ${VERSION} listening on ${bind}:${PORT}`));
}).catch((e) => { console.error('FATAL:', e.message); process.exit(1); });

// Ordinary shutdown: hand every leased pane size back before exiting.
let shuttingDown = false;
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    log(`${sig}: releasing ${leases.size} pane size lease(s)`);
    try { await sweepStrandedSizes('shutdown'); } catch { }
    process.exit(0);
  });
}
