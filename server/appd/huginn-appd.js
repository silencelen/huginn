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
const {
  screenHash, previewLines, detectPrompt, promptFingerprint, multiToggleDigits,
  parseSpinner, parseStatusExtras,
  extractLoginUrl, parseStatusLine, loginPaneState,
} = require('./lib/pane');
const { readTranscript, liveActivity } = require('./lib/transcript');
const { summarizeUsage } = require('./lib/usage');
const { normalizePlan } = require('./lib/plan');
const { AccountStore, fingerprint, sameAccount, normUuid } = require('./lib/accounts');
const { formatModel, discoverModels, parseModelId } = require('./lib/models');
const { pushPending, takePending, clearPending, queuedEvents } = require('./lib/chatqueue');
const { digest } = require('./lib/watch');
const { decideAlerts, routeAlerts, telegramText, pruneSent, carryRunStarts } = require('./lib/alerts');
const clientsLib = require('./lib/clients');
const { taskDirFor, parsePs, scanTasks, extractBgIds } = require('./lib/tasks');
const { agentsDirFor, listAgents } = require('./lib/agents');
const { suggestionContext, buildPrompt, parseSuggestions } = require('./lib/suggest');
const {
  decideSwitch, worstLimit, agedLimits, explain: explainSwitch,
  THRESHOLD: AUTOSWITCH_THRESHOLD,
} = require('./lib/autoswitch');
const pushLib = require('./lib/pushtokens');
const { trySender } = require('./lib/fcm');

const VERSION = '2.54.0';
const PORT = Number(process.env.HUGINN_APPD_PORT || 8787);
const DATA_DIR = process.env.HUGINN_APPD_DATA || '/var/lib/huginn-appd';
const UPLOADS_DIR = path.join(DATA_DIR, 'uploads');
// Generous, because a router or NVR backup is tens of megabytes and the body is
// streamed straight to disk rather than held in memory.
const UPLOAD_MAX_BYTES = 128 * 1024 * 1024;
// What uploads are accepted — the rules live in lib/uploads (family matching +
// filename fallback), extracted after the exact-match table refused real .txt
// and .csv files: Android providers report mimes like text/comma-separated-
// values, or nothing at all, and exact-match punished the user for their file
// manager's vocabulary.
const { uploadExtFor, isReadable } = require('./lib/uploads');
// The desktop update channels — feed ymls + installers served from disk,
// stocked by the desktop release scripts via local moves.
//
// TWO of them, and they must never converge while the Electron client is in
// service: it polls /v1/desktop and installs what it finds, so a Compose build
// landing there would replace a running application with a different one. See
// the header of lib/desktop.js.
const desktopLib = require('./lib/desktop');
const DESKTOP_DIR = path.join(DATA_DIR, 'desktop');
const DESKTOP_KT_DIR = path.join(DATA_DIR, 'desktop-kt');

/**
 * Drops uploads old enough that no conversation is coming back for them.
 * Run on each upload rather than a timer: a dir that only grows when the
 * feature is used only needs sweeping then.
 */
function pruneUploads(maxAgeMs = 7 * 24 * 60 * 60 * 1000) {
  let names = [];
  try { names = fs.readdirSync(UPLOADS_DIR); } catch { return; }
  const cutoff = Date.now() - maxAgeMs;
  for (const n of names) {
    const f = path.join(UPLOADS_DIR, n);
    try { if (fs.statSync(f).mtimeMs < cutoff) fs.unlinkSync(f); } catch { /* raced; fine */ }
  }
}
const TOKEN_FILE = process.env.HUGINN_APPD_TOKEN_FILE || '/etc/huginn-appd/token';
const STATE_DIR = '/run/huginn-claude-state';
const PERSONA_FILE = '/usr/local/share/huginn-cli/persona.md';
const WORKDIR = process.env.HUGINN_APPD_WORKDIR || '/root/netplan';
const MUNINN = 'root@192.168.2.118';
const MAX_CONCURRENT_RUNS = 3;
const RUN_HARD_CAP_MS = 2 * 60 * 60 * 1000; // 2 h — safety net, not a feature

// Tool sets mirror the huginn CLI exactly: `-p` (ask) vs `-y` (act).
const TOOLS = {
  // --allowedTools AUTO-APPROVES; it does not restrict. In -p mode the read-only
  // tools (Read/Glob/Grep) are allowed by default with no grant at all — VERIFIED
  // 2026-07-28 by having an ask chat read /etc/hostname with no Read rule present.
  // So the ask/act line is drawn at MUTATION, not at reading: ask can see this
  // host (including attached photos, which is what makes attachments work in ask
  // mode), act can additionally run and change things. A scoped
  // Read(//uploads/**) rule was tried here and removed as a no-op — do not
  // reintroduce it as if it were a fence.
  //
  // WebFetch/WebSearch are granted to ask explicitly: reads over the network fit
  // the same line, and without them a "what's the weather Saturday" falls back
  // to Bash-curl and the coin-flip below.
  ask: 'mcp__mempalace WebFetch WebSearch',
  act: 'Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace',
};

// The deny half, which allowedTools cannot express. Measured 2026-07-28, one
// minute apart in ONE ask chat: two near-identical `curl | python3` commands,
// the first auto-approved by Claude Code's content-dependent safe-Bash
// classification, the second refused ("contains multiple operations"). From the
// phone that reads as a feature that works and then doesn't. Deny beats every
// heuristic, so listing Bash here makes ask mode DETERMINISTIC: the model never
// sees Bash at all and reaches for WebFetch instead of gambling.
const DISALLOWED = {
  ask: 'Bash Edit Write NotebookEdit',
  act: '',
};

// ---------------------------------------------------------------- utilities

function log(...args) { console.log(new Date().toISOString(), ...args); }

/**
 * User text as a title or snippet should read. The attachment marker is
 * plumbing for Claude — a chat list entry titled
 * "[Attached image at /var/lib/huginn-appd/uploads/img-17852…" is where the
 * daemon stored a file, not what the conversation is about.
 */
function humanizeUserText(t) {
  const s = String(t || '')
    .replace(/\[Attached image at [^\]]+\]/g, '\u{1F4F7} photo')
    .replace(/\[Attached file at [^\]]+\]/g, '\u{1F4CE} file')
    .trim();
  return s || '\u{1F4F7} photo';
}

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
  return readBodyRaw(req, limit).then((b) => b.toString('utf8'));
}

/** The same, kept as bytes — an image round-tripped through utf8 is destroyed. */
function readBodyRaw(req, limit = 256 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    let over = false;
    req.on('data', (c) => {
      if (over) return;
      size += c.length;
      if (size > limit) {
        // DRAIN the rest rather than destroying the socket. Killing it here sent
        // a bare TCP reset, so the caller saw a dropped connection instead of the
        // 413 the route had ready — and the chat route's own "text too long"
        // message became unreachable for anything over this cap, which is the
        // case most likely to hit it. Nothing is buffered past the limit, so
        // draining costs bandwidth already in flight and no memory.
        over = true;
        chunks.length = 0;
        const e = new Error('body too large');
        e.tooLarge = true;
        req.resume();
        req.on('end', () => reject(e));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => { if (!over) resolve(Buffer.concat(chunks)); });
    req.on('error', reject);
  });
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: 10_000, maxBuffer: 4 * 1024 * 1024, ...opts },
      (err, stdout, stderr) => resolve({ err, stdout: stdout ?? '', stderr: stderr ?? '' }));
  });
}

// Only the aliases and levels the CLI documents; anything else is dropped rather
// than passed through to a spawn.
const MODEL_ALIASES = new Set(['fable', 'opus', 'sonnet', 'haiku']);
const EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);
/**
 * A family alias, or a full versioned id. Both reach an argv, so the shape is
 * checked rather than the string trusted.
 */
function validModel(v) {
  if (typeof v !== 'string') return null;
  const s = v.trim().toLowerCase();
  if (!/^[a-z0-9-]{2,60}$/.test(s)) return null;
  if (MODEL_ALIASES.has(s)) return s;
  return parseModelId(s) ? s : null;
}
function validEffort(v) {
  return typeof v === 'string' && EFFORT_LEVELS.has(v.toLowerCase()) ? v.toLowerCase() : null;
}

// Session names: the cc contract — letters/digits/underscore, canonically lowercase.
/**
 * The session names this daemon will route to.
 *
 * Wider than it was, because tmux is wider than it was assumed to be: sessions made
 * at the keyboard routinely carry a dash (`dev-phonefarm`) or a capital, and those
 * were LISTED by the app and then 404'd when tapped — visible and unopenable.
 *
 * Still deliberately narrow. A name is used as a filename under /run (the Claude
 * state file), so anything that could climb out of that directory is excluded: no
 * slashes, and the first character must be alphanumeric or an underscore, which
 * makes `.` and `..` unnameable. Every character allowed here is also legal
 * unencoded in a URL path segment, so no caller has to remember to escape it.
 */
const NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}$/;

function canonName(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.toLowerCase();
  return NAME_RE.test(s) ? s : null;
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

// One ps snapshot serves every caller inside its window; the sessions list and
// several transcript polls land inside the same second, and each fresh ps is a
// process spawn.
let psCache = { at: 0, procs: new Map() };
async function psSnapshot() {
  if (Date.now() - psCache.at < 1500) return psCache.procs;
  const r = await run('ps', ['-eo', 'pid,ppid,etimes,args', '--no-headers']);
  if (!r.err) psCache = { at: Date.now(), procs: parsePs(r.stdout) };
  return psCache.procs;
}

/**
 * Which task ids this session's transcript has called background. Cached on the
 * transcript's size so the tail is re-read only when it grew — the sessions list
 * polls every few seconds and must not re-read every transcript each time.
 */
const bgIdCache = new Map();   // sessionId -> {size, ids}
function knownBackgroundIds(st) {
  if (!st || !st.transcript || !st.sessionId) return new Set();
  let size = 0;
  try { size = fs.statSync(st.transcript).size; } catch { return new Set(); }
  const hit = bgIdCache.get(st.sessionId);
  if (hit && hit.size === size) return hit.ids;
  const t = readTranscript(st.transcript, { limit: 600 });
  const ids = extractBgIds(t.events);
  bgIdCache.set(st.sessionId, { size, ids });
  return ids;
}

/** Background shells + agents for one session, or the empty shape. */
async function backgroundWork(name, panePid) {
  const st = readSessionState(name);
  const dir = st ? taskDirFor(st.transcript, st.sessionId, process.getuid()) : null;
  if (!dir || !panePid) return { shells: [], agents: 0 };
  return scanTasks(dir, await psSnapshot(), panePid, Math.floor(Date.now() / 1000),
    fs, knownBackgroundIds(st));
}

async function listSessions({ preview = false } = {}) {
  // window_activity, NOT session_activity: the latter does not move when a pane
  // produces output, so it read ~8 hours stale on sessions that had been busy
  // continuously and the list ordered by it was effectively frozen.
  const fmt = '#{session_name}\t#{session_created}\t#{session_attached}\t#{window_activity}\t' +
    '#{session_windows}\t#{window_width}\t#{window_height}\t#{window-size}\t#{session_activity}\t#{pane_pid}';
  const { err, stdout, stderr } = await run('tmux', ['list-sessions', '-F', fmt]);
  if (err) {
    // Two very different things used to look identical here. tmux exiting
    // because there is genuinely no server is an OBSERVATION: there are no
    // sessions. Any other failure (a fork that hit EAGAIN, the server
    // restarting, the 10s timeout) is a FAILURE TO OBSERVE — and returning []
    // for it told the alert watcher that every session had vanished, so every
    // waiting question was announced as answered, its notification cancelled,
    // and then re-announced once tmux came back. A transient hiccup became a
    // burst of wrong notifications in both directions.
    if (/no server running|no such file or directory/i.test(stderr || '')) return [];
    log(`tmux list-sessions failed: ${(stderr || err.message || '').trim().slice(0, 120)}`);
    return null;
  }
  const rows = [];
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created, attached, activity, windows, w, h, wsize, sessActivity, panePid] = line.split('\t');
    // Still listed, never silently hidden: a monitoring app that drops a session
    // from the list is worse than one that cannot open it, because the reader
    // concludes it is gone. Logged once per listing so an unopenable row has an
    // explanation on the host instead of being a mystery on the phone.
    if (!NAME_RE.test(name)) log(`sessions: "${name}" cannot be addressed by the app (name shape)`);
    const st = readSessionState(name) || {};
    rows.push({
      name,
      createdAt: Number(created),
      activityAt: Number(activity),
      // Kept for reference; it tracks client interaction, not output.
      sessionActivityAt: Number(sessActivity),
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
      panePid: Number(panePid) || null,
      bgShells: 0,
      bgAgents: 0,
      bgTask: null,
    });
  }

  if (preview) {
    // Background work rides along so the LIST can say a session is not stalled:
    // that complaint came from exactly this surface. One ps snapshot serves all
    // rows via the cache; the per-row cost is one readdir plus a few /proc reads.
    await Promise.all(rows.map(async (r) => {
      const bg = await backgroundWork(r.name, r.panePid);
      r.bgShells = bg.shells.length;
      r.bgAgents = bg.agents;
      r.bgTask = bg.shells[0] ? bg.shells[0].command : null;
    }));
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
    // "Blocked" means a resize is NEEDED and refused — not merely that a client is
    // attached. The distinction is what fixes the returning banner: after "fit
    // anyway" forced the resize, every later poll still had an attached client, so
    // the old test kept reporting blocked about a resize nothing was asking for,
    // and the banner the user had just dealt with came straight back.
    const wantW = Math.max(20, Math.min(300, Math.floor(cols)));
    const wantH = Math.max(10, Math.min(200, Math.floor(rows)));
    const [paneW, paneH] = dim.stdout.split('\t').map(Number);
    if (paneW === wantW && paneH === wantH) {
      // Already fits. Renew a lease we hold so the sweeper does not hand the
      // window back mid-view; if we hold none, the size is somebody else's doing
      // and setting `manual` on their window would be a real change, not a renewal.
      if (leases.has(name)) await acquireSize(name, cols, rows);
    } else if (attached > 0 && !force) {
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
    // The fingerprint travels WITH the prompt so the app never computes it itself.
    // One implementation of "which question is this" means an answer offered on a
    // lock screen and the check that validates it can never disagree about the
    // formatting; two implementations would eventually differ over a space.
    prompt: (() => {
      const pr = detectPrompt(lines);
      return pr ? { ...pr, fingerprint: promptFingerprint(pr) } : null;
    })(),
    // The moment-to-moment status ("Gallivanting… · 3m 15s") exists only here:
    // the transcript is silent until whole blocks complete, which left the
    // conversation looking dead right after a message was sent.
    spinner: parseSpinner(lines),
    // The TUI's own progress rows, split by lifetime: durable rows (workflow
    // phases, boards) render as-is; the transient per-tool row ("Running 2 shell
    // commands…") flaps in and out at tool speed, so the app updates it in place
    // instead of letting the strip grow and shrink on repeat.
    ...(() => {
      const px = parseStatusExtras(lines);
      return { statusLines: px.durable, transientLine: px.transient };
    })(),
    // The pane is the only CURRENT source for these; the transcript lags a turn.
    ...(() => {
      const st = parseStatusLine(lines);
      return { liveModel: st.model, liveMode: st.mode, liveBranch: st.branch };
    })(),
  };
}

// Named keys the app may send. C-<letter> covered by regex; everything else
// must be in this set. Anything not matching is rejected, not passed through.
// IC is Insert. It was the one key the desktop client's mapper could produce
// that this set did not name, and a rejection is not confined to the key that
// caused it: the client coalesces a burst of keystrokes into ONE request, so a
// single Insert took every character batched alongside it down with a 400.
const NAMED_KEYS = new Set([
  'Enter', 'Escape', 'Tab', 'BTab', 'Space', 'BSpace', 'DC', 'IC',
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
  // `running` is derived from activeRuns, and read paths attach it to the meta they
  // just loaded; `pending` is the real queue on disk but a COUNT in list views.
  // Persisting either stores something that is false the moment the daemon
  // restarts — and a stored numeric `pending` reads as "no queue" to every
  // consumer, which is a queue quietly thrown away. This is the single funnel all
  // writes pass through, so it is where those two can be dropped for good.
  const { running, ...rest } = meta;
  if (typeof rest.pending === 'number') delete rest.pending;
  fs.mkdirSync(chatDir(meta.id), { recursive: true });
  fs.writeFileSync(metaPath(meta.id), JSON.stringify(rest, null, 2));
}
/**
 * Mutates the meta ON DISK: reload, change, save.
 *
 * Writing back a meta object captured earlier discards every field written since
 * it was read. That is how a queued message vanished: the run's init event
 * persisted the snapshot it started with, which had no queue in it. Anything
 * changing meta during a run must go through here.
 */
function updateMeta(id, mutate) {
  const m = loadMeta(id);
  if (!m) return null;
  mutate(m);
  saveMeta(m);
  return m;
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
/**
 * Just enough about each chat to decide whether to wake a watching phone: no
 * transcript reads, no titles beyond what meta already holds. The display list
 * reads a transcript head per chat for Claude's own title, which is fine once a
 * screen opens and far too expensive on a loop.
 */
function chatStates() {
  let ids = [];
  try { ids = fs.readdirSync(CHATS_DIR); } catch { return []; }
  const out = [];
  for (const id of ids) {
    const m = loadMeta(id);
    if (!m) continue;
    out.push({
      id: m.id,
      title: m.title ?? null,
      running: activeRuns.has(id),
      pending: Array.isArray(m.pending) ? m.pending.length : 0,
      finishedRuns: m.finishedRuns || 0,
      // The last thing Claude said. Already on disk from the run's own result
      // event, so this costs nothing here, and it is what turns "a chat
      // finished" into a notification worth reading without opening anything.
      snippet: m.lastSnippet || null,
      // Carried so a chat seen for the first time can be told apart from a chat
      // that merely existed before anyone happened to be looking.
      createdAt: Number(m.createdAt) || 0,
    });
  }
  return out;
}

function listChats() {
  let ids = [];
  try { ids = fs.readdirSync(CHATS_DIR); } catch { /* empty */ }
  const metas = [];
  for (const id of ids) {
    const m = loadMeta(id);
    if (m) {
      m.running = activeRuns.has(id);
      // A count, not the texts: the list needs "2 waiting", not the messages.
      m.pending = Array.isArray(m.pending) ? m.pending.length : 0;
      // Claude Code generates a real title for its own sessions; it reads far
      // better than the truncated first message this daemon falls back to.
      if (m.claudeSessionId) {
        const f = findTranscriptFile(m.claudeSessionId);
        if (f) {
          try {
            const t = readTranscript(f, { limit: 1 });
            if (t.title) m.title = t.title;
          } catch { /* fall back to the stored title */ }
        }
      }
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
  meta.lastSnippet = humanizeUserText(userText).slice(0, 120);
  updateMeta(chatId, (m) => { m.updatedAt = now; m.lastSnippet = humanizeUserText(userText).slice(0, 120); });

  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages'];
  if (meta.model) args.push('--model', meta.model);
  if (meta.effort) args.push('--effort', meta.effort);
  if (meta.claudeSessionId) args.push('--resume', meta.claudeSessionId);
  let persona = '';
  try { persona = fs.readFileSync(PERSONA_FILE, 'utf8'); } catch { /* interactive-only host */ }
  if (persona) args.push('--append-system-prompt', persona);
  args.push('--allowedTools', TOOLS[meta.mode] || TOOLS.ask);
  const denied = DISALLOWED[meta.mode] ?? DISALLOWED.ask;
  if (denied) args.push('--disallowedTools', denied);

  const run_ = new Run(chatId);
  activeRuns.set(chatId, run_);
  // Durable "a run is in flight" marker. activeRuns is in memory, so after a
  // restart there is nothing left to say a run had been going — see
  // reconcileInterruptedRuns.
  updateMeta(chatId, (m) => { m.runStartedAt = now; });

  const proc = spawn('claude', args, {
    cwd: WORKDIR,
    env: { ...process.env, TERM: 'dumb' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  run_.proc = proc;

  // An unhandled 'error' on a child process is FATAL to the daemon — Node
  // rethrows it, systemd restarts, and the next message crashes it again: a
  // user-driven crash loop. spawn fails for ordinary reasons (a wedged
  // `claude update` leaving no binary on PATH, EMFILE under fd pressure), and
  // this daemon holds every phone's SSE stream and the alert watcher, so its
  // death is never local. 'close' still fires after a handled 'error', so the
  // normal finish path below does the bookkeeping; this only has to record
  // WHY, and absorb stdin's EPIPE when the child never existed to read it.
  proc.on('error', (err) => {
    const ts = Math.floor(Date.now() / 1000);
    const text = `could not start claude: ${err.code || err.message}`;
    appendMsg(chatId, { type: 'error', text, ts });
    run_.emit('error', { text });
    updateMeta(chatId, (m) => { m.updatedAt = ts; m.lastSnippet = text.slice(0, 120); });
    log(`chat ${chatId} spawn failed: ${err.code || err.message}`);
  });
  proc.stdin.on('error', () => { /* EPIPE when the child never started */ });
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
      updateMeta(chatId, (m) => { m.updatedAt = ts; m.lastSnippet = errText.slice(0, 120); });
    }
    // Recorded before anything else observes the finish. A completed run has to
    // leave a durable mark: the alert watcher runs on a timer and cannot be relied
    // on to catch the instant `running` goes false — a five-second run slipped
    // straight through a ten-second tick and was never reported.
    updateMeta(chatId, (m) => {
      m.finishedRuns = (m.finishedRuns || 0) + 1;
      m.finishedAt = ts;
      delete m.runStartedAt;                  // this run is accounted for
    });
    run_.emit('done', { exitCode: code });
    run_.finish();
    log(`chat ${chatId} run finished (exit ${code})`);

    const fresh = loadMeta(chatId);
    if (fresh) {
      if (run_.cancelled) {
        // Cancel means stop. Respawning from the queue would make the stop
        // button start the very thing it was pressed to end.
        const dropped = clearPending(fresh);
        if (dropped) { saveMeta(fresh); log(`chat ${chatId} dropped ${dropped} queued message(s) on cancel`); }
      } else {
        const next = takePending(fresh);
        if (next) {
          saveMeta(fresh);
          log(`chat ${chatId} delivering queued message(s)`);
          startQueuedRun(fresh, next);
        }
      }
    }
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
        meta.claudeSessionId = ev.session_id;           // for this run
        updateMeta(chatId, (m) => { if (!m.claudeSessionId) m.claudeSessionId = ev.session_id; });
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
      const finalText = typeof ev.result === 'string' ? ev.result : run_.assistantText;
      updateMeta(chatId, (m) => {
        m.updatedAt = ts;
        if (finalText) m.lastSnippet = finalText.slice(0, 120);
        m.turns = (m.turns || 0) + 1;
      });
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

/** The sign-in flow's current state, read from its pane. */
async function readLoginState() {
  if (!(await sessionExists('login'))) {
    return { session: 'login', running: false, awaitingCode: false, done: false, url: loginUrl };
  }
  const cap = await run('tmux', ['capture-pane', '-p', '-e', '-t', '=login:']);
  const lines = cap.err ? [] : cap.stdout.replace(/\n$/, '').split('\n');
  const st = loginPaneState(lines);
  if (!loginUrl) loginUrl = extractLoginUrl(lines);
  return {
    session: 'login',
    running: true,
    awaitingCode: st.awaitingCode,
    done: st.done,
    url: loginUrl,
    message: st.failed ? st.message : (st.awaitingCode ? 'Waiting for the code' : st.message),
  };
}

/**
 * Starts a run for any chat left holding queued messages with nothing running.
 * Only reachable after a restart or a crash, since in normal operation the queue
 * is drained by the closing run.
 */
/**
 * Starts a run for text already drained from the queue, and PUTS IT BACK if the
 * start is refused.
 *
 * Both drain sites used to `takePending` (which empties the queue), save that
 * emptiness, then call startRun and ignore its return value. startRun refuses
 * synchronously for ordinary reasons — too many concurrent runs, a run already
 * active — and when it did, the messages were already erased from disk: silently
 * destroyed, after the sender had been told they were queued.
 */
function startQueuedRun(meta, text) {
  const started = startRun(meta, text);
  if (!started || !started.error) return started;
  // Restored at the FRONT, so it stays ahead of anything queued since, and as a
  // single entry because takePending already joined the batch into one prompt.
  updateMeta(meta.id, (m) => {
    m.pending = [{ text, ts: Math.floor(Date.now() / 1000) }].concat(
      Array.isArray(m.pending) ? m.pending : []);
  });
  log(`chat ${meta.id}: run refused (${started.error}); ${text.length} chars returned to the queue`);
  return started;
}

/**
 * Records runs that a restart killed, which otherwise reported themselves as
 * successful answers.
 *
 * SIGTERM exits immediately and systemd kills the `claude` child along with the
 * cgroup, so the 'close' handler never runs: no error record, no finishedRuns
 * bump, and meta.lastSnippet still holds the humanized USER text written at
 * startRun. The alert watcher then sees running -> not-running on the next tick
 * and announces a finished chat QUOTING THE OWNER'S OWN QUESTION as the answer.
 * Deploys here are frequent, so this fired in ordinary use.
 *
 * The prompt is deliberately NOT re-run. Re-spawning would be a guess about what
 * the owner wants and could pay for a long answer twice (a restart one second
 * before completion is indistinguishable from one at the start). Saying plainly
 * that it was interrupted leaves the decision where it belongs.
 */
function reconcileInterruptedRuns() {
  let ids = [];
  // The directory rather than listChats(): this runs before the port is open, and
  // listChats reads a transcript file per chat to prettify titles — work nobody is
  // waiting for here.
  try { ids = fs.readdirSync(CHATS_DIR); } catch { return; }
  for (const id of ids) {
    const meta = loadMeta(id);
    if (!meta || !meta.runStartedAt || activeRuns.has(id)) continue;
    const ts = Math.floor(Date.now() / 1000);
    const text = 'interrupted: huginn-appd restarted while this was running';
    appendMsg(id, { type: 'error', text, ts });
    updateMeta(id, (m) => {
      m.finishedRuns = (m.finishedRuns || 0) + 1;
      m.finishedAt = ts;
      m.updatedAt = ts;
      // The snippet is what the notification says, so it has to stop claiming the
      // question was the answer.
      m.lastSnippet = text.slice(0, 120);
      delete m.runStartedAt;
    });
    log(`chat ${id}: run interrupted by restart (started ${meta.runStartedAt}) — recorded`);
  }
}

function deliverOrphanedQueues() {
  for (const meta of listChats()) {
    if (!meta.pending || activeRuns.has(meta.id)) continue;
    const full = loadMeta(meta.id);
    if (!full) continue;
    const next = takePending(full);
    if (!next) continue;
    saveMeta(full);
    log(`chat ${meta.id}: delivering ${meta.pending} message(s) queued before restart`);
    startQueuedRun(full, next);
  }
}

const CREDENTIALS_PATH = path.join(os.homedir(), '.claude', '.credentials.json');
const CLAUDE_CONFIG_PATH = path.join(os.homedir(), '.claude.json');
const accounts = new AccountStore(path.join(DATA_DIR, 'accounts'), CREDENTIALS_PATH, CLAUDE_CONFIG_PATH);

// Fingerprint of the login that was active when a sign-in flow started. When the
// live credentials no longer match it, the flow finished — which is how the
// leftover `login` session gets cleaned up without the user having to notice it.
let loginStartedFrom = null;
let loginUrl = null;
// Which account the user SAID they were adding, so the outcome can be checked
// against their intent instead of merely reported.
let loginIntent = null;

/**
 * Who a stored credential set actually belongs to, asked of the credentials
 * themselves.
 *
 * Labels used to come from `claude auth status`, which describes whatever is
 * ACTIVE — so a profile could be filed under the wrong person whenever those two
 * reads disagreed. That is how two profiles ended up labelled as different
 * accounts while both tokens authenticated as the same one, which made the
 * headroom shown per account misleading. Resolving from the token cannot be
 * wrong.
 *
 * The `uuid` this returns is the account's PERMANENT id, and it is the reason
 * this call matters beyond labelling: refresh tokens rotate every few hours, so
 * anything keyed on them treats one login as a new account several times a day.
 * Cached by fingerprint — one round trip per token, not per read.
 */
const idByPrint = new Map();
async function resolveIdentity(creds) {
  const print = fingerprint(creds);
  const token = creds && creds.claudeAiOauth && creds.claudeAiOauth.accessToken;
  if (!print || !token) return null;
  if (idByPrint.has(print)) return idByPrint.get(print);
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 10_000);
  try {
    const resp = await fetch('https://api.anthropic.com/api/oauth/account', {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'anthropic-beta': 'oauth-2025-04-20',
      },
      signal: ac.signal,
    });
    if (!resp.ok) return null;                 // expired token: keep what is stored
    const body = await resp.json();
    const acct = (body && (body.account || body)) || {};
    const id = {
      email: acct.email_address || acct.email || null,
      uuid: normUuid(acct.uuid || acct.account_uuid),
      taggedId: typeof acct.tagged_id === 'string' ? acct.tagged_id : null,
      orgName: (acct.memberships && acct.memberships[0]
        && acct.memberships[0].organization && acct.memberships[0].organization.name) || null,
    };
    if (id.email || id.uuid) idByPrint.set(print, id);
    return id;
  } catch { return null; } finally { clearTimeout(timer); }
}

async function resolveEmail(creds) {
  const id = await resolveIdentity(creds);
  return (id && id.email) || null;
}

/**
 * Saves credentials with the strongest identity available for them.
 *
 * Every save that goes through here survives a token rotation: the account uuid
 * comes from the token itself, so the profile is updated in place instead of a
 * fresh one appearing beside it. When the account cannot be reached the store
 * falls back to the refresh-token fingerprint, which may leave a surplus profile
 * behind — recoverable — rather than risk filing one login under another's name.
 *
 * `label` is only a fallback: the token's own answer is preferred wherever it
 * is available, because it cannot be stale.
 */
async function saveIdentified(label, creds, extra = {}) {
  if (!creds) return null;
  const id = await resolveIdentity(creds);
  return accounts.save((id && id.email) || label || null, creds, {
    ...extra,
    orgName: extra.orgName ?? (id && id.orgName) ?? null,
    ...(id && id.uuid ? { accountUuid: id.uuid, taggedId: id.taggedId } : {}),
  });
}

/**
 * Plan utilization for a SAVED account, so you can see which login has headroom
 * before switching to it. Best effort: a stored access token expires, and this
 * daemon deliberately does not implement the refresh flow — an expired one
 * simply reports unknown, and refreshes itself once that account is active and
 * Claude Code runs under it.
 */
async function planForCredentials(creds) {
  const o = (creds && creds.claudeAiOauth) || {};
  const token = o.accessToken;
  if (!token) return null;
  // An access token past its expiry cannot answer, and asking anyway is not free:
  // this endpoint rate-limits per account, and a saved login that has been idle
  // for days always has a dead token. Spending the allowance on those was
  // starving the one read that matters — the ACTIVE account's.
  if (typeof o.expiresAt === 'number' && o.expiresAt <= Date.now()) return null;
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
/**
 * Who the host is signed in as.
 *
 * `claude auth status` reads an identity block in ~/.claude.json that the CLI
 * refreshes when it next RUNS — so immediately after switching accounts it reports
 * nothing at all, which would show up as "not signed in" on a host that is
 * perfectly signed in. The credentials are the real answer, so fall back to asking
 * them who they belong to.
 */
async function accountStatus() {
  const { err, stdout } = await run('claude', ['auth', 'status'], { timeout: 20_000 });
  let parsed = null;
  if (!err) { try { parsed = JSON.parse(stdout); } catch { /* handled below */ } }

  if (parsed && parsed.loggedIn && parsed.email) {
    return {
      loggedIn: true,
      email: parsed.email,
      orgName: parsed.orgName ?? null,
      subscriptionType: parsed.subscriptionType ?? null,
      authMethod: parsed.authMethod ?? null,
      apiProvider: parsed.apiProvider ?? null,
      identitySource: 'cli',
    };
  }

  const live = accounts.readActive();
  if (live) {
    const email = await resolveEmail(live);
    if (email) {
      const o = live.claudeAiOauth || {};
      return {
        loggedIn: true,
        email,
        orgName: null,
        subscriptionType: o.subscriptionType ?? null,
        authMethod: 'claude.ai',
        apiProvider: 'firstParty',
        // The CLI will name it itself once it next runs; until then this is the
        // token's own answer, which cannot be stale.
        identitySource: 'token',
      };
    }
  }
  return { loggedIn: false, error: parsed ? 'not signed in' : 'could not read auth status' };
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
    listSessions().then((v) => v ?? []),
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

// ------------------------------------------------------------ host-side alerts
//
// The phone can only notice things while the app is alive. For an alert to reach
// somebody whose phone has been in a pocket for two hours, the HOST has to notice
// and reach out — so this watches the same state the app does and delivers over
// Telegram, which is already how this homelab reaches its owner.
const ALERT_STATE = path.join(DATA_DIR, 'alerts.json');
const ALERT_POLL_MS = 10_000;
const TELEGRAM_SCRIPT = '/root/netplan/scripts/active/send-telegram.sh';

// ------------------------------------------------------- who is still listening
//
// In memory, because it is written on every keepalive from every watching phone
// and this is the only process that writes it. Flushed to disk on a timer so a
// daemon restart does not make every phone look newly-arrived, and so the record
// of an overnight vigil survives to be read in the morning.
const CLIENT_STATE = path.join(DATA_DIR, 'clients.json');
const CLIENT_FLUSH_MS = 60_000;
let clientState = (() => {
  try { return JSON.parse(fs.readFileSync(CLIENT_STATE, 'utf8')); }
  catch { return clientsLib.emptyState(); }
})();
let clientDirty = false;

function flushClients() {
  if (!clientDirty) return;
  clientDirty = false;
  try {
    clientsLib.pruneClients(clientState, Date.now());
    fs.writeFileSync(`${CLIENT_STATE}.tmp`, JSON.stringify(clientState), { mode: 0o600 });
    fs.renameSync(`${CLIENT_STATE}.tmp`, CLIENT_STATE);
  } catch (e) { log('clients: could not persist', e.message); }
}
setInterval(flushClients, CLIENT_FLUSH_MS).unref();

/**
 * Stamps the calling app as still listening. Headers rather than query parameters
 * so the id never lands in a URL, and so an ordinary long poll carries it without
 * changing its shape.
 *
 * `X-Huginn-Notify` is the app reporting whether Android will in fact display what
 * it posts — a connected app with notifications denied is not a delivery route, and
 * counting it as one would hold back the Telegram fallback in favour of nothing.
 */
function noteClient(req, kind) {
  const id = String(req.headers['x-huginn-client'] || '').trim().slice(0, 64);
  if (!id) return;
  const notifyHeader = req.headers['x-huginn-notify'];
  clientsLib.noteSeen(clientState, id, {
    kind,
    ua: req.headers['user-agent'],
    notify: notifyHeader == null ? undefined : notifyHeader === '1',
  }, Date.now());
  clientDirty = true;
}

function loadAlertState() {
  try { return JSON.parse(fs.readFileSync(ALERT_STATE, 'utf8')); }
  catch { return { enabled: false, sent: {}, prev: null, delivered: 0, lastAt: null }; }
}
function saveAlertState(st) {
  try {
    fs.writeFileSync(`${ALERT_STATE}.tmp`, JSON.stringify(st), { mode: 0o600 });
    fs.renameSync(`${ALERT_STATE}.tmp`, ALERT_STATE);
  } catch (e) { log('alerts: could not persist state', e.message); }
}

/**
 * Sends through the homelab's existing Telegram path rather than a new one: it is
 * outbound-only, it logs every send, and the owner already has it. House rule
 * from that setup — statements only, never a question, because nothing consumes
 * replies.
 */
async function deliverTelegram(text) {
  if (!fs.existsSync(TELEGRAM_SCRIPT)) return false;
  const r = await run('bash', [TELEGRAM_SCRIPT, '--message', text, '--source', 'huginn-app'],
    { timeout: 30_000 });
  if (r.err) { log('alerts: telegram send failed', (r.stderr || '').trim().slice(0, 120)); return false; }
  return true;
}

// ------------------------------------------------------------------ FCM push
//
// The one transport that reaches a phone asleep with the app closed, in seconds
// rather than at the next alarm. Optional on purpose: absent a key this daemon still
// alerts by Telegram and the app still checks in on its own, so a host without push
// set up is degraded rather than broken.
const FCM_KEY = process.env.HUGINN_FCM_KEY || '/etc/huginn-appd/fcm-service-account.json';
const PUSH_STATE = path.join(DATA_DIR, 'push.json');
const fcm = trySender(FCM_KEY, log);

function loadPushState() {
  try { return JSON.parse(fs.readFileSync(PUSH_STATE, 'utf8')); }
  catch { return pushLib.emptyState(); }
}
function savePushState(st) {
  try {
    fs.writeFileSync(`${PUSH_STATE}.tmp`, JSON.stringify(st), { mode: 0o600 });
    fs.renameSync(`${PUSH_STATE}.tmp`, PUSH_STATE);
  } catch (e) { log('push: could not persist tokens', e.message); }
}

/**
 * Pushes one alert to every registered device.
 *
 * A token FCM reports as dead is forgotten; any other failure is counted and left
 * alone. That distinction is deliberate — treating an outage as a dead token would
 * unregister a working phone and leave no route back except reinstalling the app.
 *
 * @returns {Promise<{sent: number, dead: number, failed: number}>}
 */
async function deliverPush(alert) {
  if (!fcm) return { sent: 0, dead: 0, failed: 0 };
  const st = loadPushState();
  const devices = pushLib.list(st);
  if (!devices.length) return { sent: 0, dead: 0, failed: 0 };

  let sent = 0; let dead = 0; let failed = 0;
  // Outcomes are COLLECTED, not written as they happen. This function awaits one
  // network round trip per device, and POST /v1/push/register writes the same file:
  // a phone registering a rotated token inside that window was erased when the
  // snapshot loaded before the sends got saved over it, leaving the host pushing to
  // a token the phone had already replaced. Two concurrent deliverPush calls
  // (alert tick, autoswitch tick, /v1/push/test) clobbered each other the same way.
  const outcomes = [];
  for (const d of devices) {
    let r;
    try {
      r = await fcm.send(d.token, alert);
    } catch (e) {
      r = { ok: false, dead: false, status: 0, error: e.message };
    }
    if (r.ok) {
      sent++;
      outcomes.push({ kind: 'ok', installId: d.installId, token: d.token, at: Date.now() });
      log(`push: delivered ${alert.kind || 'alert'} to ${d.installId}${d.model ? ` (${d.model})` : ''}`);
    } else if (r.dead) {
      dead++;
      log(`push: dropping dead token for ${d.installId} (${r.error})`);
      outcomes.push({ kind: 'dead', installId: d.installId, token: d.token });
    } else {
      failed++;
      log(`push: send failed for ${d.installId} (${r.status} ${r.error})`);
      outcomes.push({ kind: 'fail', installId: d.installId, token: d.token });
    }
  }
  if (outcomes.length) {
    const fresh = loadPushState();
    for (const o of outcomes) {
      if (o.kind === 'ok') pushLib.noteSuccess(fresh, o.installId, o.at);
      // Only while the install still holds the token that failed: a drop keyed on
      // the install alone would delete a registration made while we were sending.
      else if (o.kind === 'dead') pushLib.drop(fresh, o.installId, o.token);
      else pushLib.noteFailure(fresh, o.installId);
    }
    savePushState(fresh);
  }
  return { sent, dead, failed };
}

const suggestCache = new Map();   // sessionId -> {size, suggestions, promise}

/**
 * Suggestions for one transcript, cached on its size, single-flight per id.
 * Shared by the sessions route and the chats route so the two surfaces cannot
 * drift: a chat and a session are the same transcript wearing different UIs.
 */
async function suggestionsFor(id, transcriptPath) {
  let size = 0;
  try { size = fs.statSync(transcriptPath).size; } catch {
    return { suggestions: [], reason: 'no transcript' };
  }
  const hit = suggestCache.get(id);
  if (hit && hit.size === size) {
    if (hit.promise) await hit.promise;
    const now2 = suggestCache.get(id);
    return { suggestions: (now2 && now2.suggestions) || [], forSize: size };
  }
  const t = readTranscript(transcriptPath, { limit: 60 });
  const context = suggestionContext(t.events);
  if (!context) return { suggestions: [], reason: 'nothing to react to' };

  const entry = { size, suggestions: [], promise: null };
  entry.promise = (async () => {
    const r = await run('claude', [
      '-p',
      // Caged: no CLAUDE.md (global or project), no tools, one turn, cheap
      // model, scratch cwd. This call must never inherit huginn's persona.
      '--setting-sources', '',
      '--model', 'haiku',
      '--max-turns', '1',
      '--tools', '',
      '--', buildPrompt(context),
    ], { timeout: 45_000, cwd: DATA_DIR });
    entry.suggestions = r.err ? [] : parseSuggestions(r.stdout);
    entry.promise = null;
    if (r.err) log(`suggest: ${id} failed: ${(r.stderr || r.err.message || '').slice(0, 120)}`);
    else log(`suggest: ${id} -> ${entry.suggestions.length} for size ${size}`);
  })();
  suggestCache.set(id, entry);
  if (suggestCache.size > 50) suggestCache.delete(suggestCache.keys().next().value);
  await entry.promise;
  return { suggestions: entry.suggestions, forSize: size };
}


/**
 * Makes a saved login the active one, everywhere it has to happen at once:
 * credentials + identity block (accounts.activate), the label correction once
 * `auth status` is authoritative for the new login, and the plan cache, whose
 * old figures belong to the account just left. One path for the button in
 * Settings and for the auto-switcher, so they cannot drift.
 */
async function performSwitch(slug) {
  const before = await accountStatus();
  // Fold the outgoing account's CURRENT tokens into its own profile first. Its
  // refresh token has almost certainly rotated since it was last written, and
  // the snapshot activate() takes on the way out can only recognise a profile by
  // that token — so without this the account being left is filed a second time,
  // under a name nothing else knows.
  await saveIdentified(before.email, accounts.readActive());

  const r = accounts.activate(slug, before.email);
  if (!r.ok) return { ok: false, error: r.error };
  const after = await accountStatus();
  const nowLive = accounts.readActive();
  if (nowLive && after.loggedIn && after.email) {
    await saveIdentified(after.email, nowLive, after.orgName ? { orgName: after.orgName } : {});
  }
  planCache.at = 0; planCache.data = null;
  log(`account switched: ${before.email || 'unknown'} -> ${after.email || 'unknown'}`);
  return { ok: true, before, after };
}

// ------------------------------------------------------- automatic switching
//
// The owner keeps three Max accounts so a hard limit is never a hard stop; the
// rotation was manual, made at exactly the moment a limit-hit made everything
// stall. The daemon can read every saved account's headroom, so it makes the
// same move itself: when the active account's binding limit crosses the
// threshold, switch to the freshest saved login. Decision rules (and their
// anti-flap guards) live in lib/autoswitch, tested.
const AUTOSWITCH_STATE = path.join(DATA_DIR, 'autoswitch.json');
const AUTOSWITCH_POLL_MS = 5 * 60 * 1000;

function loadAutoswitch() {
  try { return JSON.parse(fs.readFileSync(AUTOSWITCH_STATE, 'utf8')); }
  catch { return { enabled: false, lastSwitchAt: 0, switches: 0, last: null }; }
}
function saveAutoswitch(st) {
  try {
    fs.writeFileSync(`${AUTOSWITCH_STATE}.tmp`, JSON.stringify(st), { mode: 0o600 });
    fs.renameSync(`${AUTOSWITCH_STATE}.tmp`, AUTOSWITCH_STATE);
  } catch (e) { log('autoswitch: could not persist', e.message); }
}

let autoswitchBusy = false;
// The last tick's reasoning, so "it never fires" can be told apart from "it has
// not needed to yet" without reading the log.
let autoswitchWhy = 'not run yet';

async function autoswitchTick() {
  if (autoswitchBusy) return;
  const st = loadAutoswitch();
  if (!st.enabled) { autoswitchWhy = 'disabled'; return; }
  autoswitchBusy = true;
  try {
    // Keep the live login's profile in step with its rotating tokens, or it stops
    // matching any stored record and this tick cannot tell which account is
    // active at all.
    await saveIdentified(null, accounts.readActive());

    const saved = accounts.list();
    if (saved.length < 2) { autoswitchWhy = 'only one account is saved'; return; }
    const activeRec = saved.find((a) => a.isActive);
    if (!activeRec) { autoswitchWhy = 'no saved profile matches the live credentials'; return; }

    // Cheap first look: only the active account's plan. Candidates are only
    // priced once the active one is actually hot.
    const activePlan = await planForCredentials(accounts.readActive());
    if (activePlan) accounts.recordPlan(activeRec.slug, activePlan);
    const activeLimits = (activePlan && activePlan.limits) || [];
    const active = { slug: activeRec.slug, email: activeRec.email, limits: activeLimits };

    const threshold = typeof st.threshold === 'number' ? st.threshold : undefined;
    const w = worstLimit(activeLimits);
    if (!w || w.percent < (threshold ?? 95)) {
      autoswitchWhy = explainSwitch({ active, candidates: [], now: Date.now(), lastSwitchAt: st.lastSwitchAt || 0, threshold });
      return;
    }

    const candidates = [];
    for (const a of saved) {
      if (a.isActive) continue;
      const rec = accounts.readProfile(a.slug);
      if (!rec) continue;
      // Live figures if the stored token still authenticates, which it usually
      // does not — an access token outlives its account's turn by hours at most.
      // Otherwise the last reading taken while that account was active, aged
      // forward; see agedLimits for why that is sound and which way it errs.
      const plan = await planForCredentials(rec.credentials);
      if (plan) accounts.recordPlan(a.slug, plan);
      candidates.push({
        slug: a.slug,
        email: a.email,
        limits: plan ? plan.limits : agedLimits(rec.lastPlan, Date.now()),
      });
    }

    const d = decideSwitch({
      active,
      candidates,
      now: Date.now(),
      lastSwitchAt: st.lastSwitchAt || 0,
      threshold,
    });
    if (!d) {
      autoswitchWhy = explainSwitch({ active, candidates, now: Date.now(), lastSwitchAt: st.lastSwitchAt || 0, threshold });
      // Say so OUT LOUD when the account is at the threshold and we still cannot
      // act, rather than only answering /v1/autoswitch when somebody thinks to
      // ask. This is a feature whose whole job is to act unattended, so the one
      // way it fails — armed, enabled, and with nothing it is allowed to switch
      // to — otherwise announces itself as hitting a limit that was supposed to
      // have been avoided. Candidates are priced with their STORED tokens and an
      // expired one reports nothing, so a pool that has sat unused long enough
      // simply empties, quietly.
      //
      // Only at the threshold, and once a day: below it there is nothing to warn
      // about, and above it the reading changes every few minutes.
      const worst = worstLimit(active.limits);
      if (worst && worst.percent >= threshold) {
        const lastWarn = st.lastIdleWarnAt || 0;
        if (Date.now() - lastWarn > 24 * 60 * 60 * 1000) {
          st.lastIdleWarnAt = Date.now();
          saveAutoswitch(st);
          const text = `${active.email || active.slug} is at ${worst.percent}% (${worst.label}) and ` +
            `auto-switch could not move: ${autoswitchWhy}. Sign in to another account, or ` +
            `open one of the saved ones once so its headroom can be read.`;
          const push = await deliverPush({ kind: 'account_switch', title: 'Auto-switch is stuck', text, subject: active.slug });
          if (!push.sent) await deliverTelegram(`\u{26A0} Auto-switch is stuck\n${text}`);
          log(`autoswitch: STUCK at ${worst.percent}% — ${autoswitchWhy}`);
        }
      }
      return;
    }
    autoswitchWhy = null;

    const r = await performSwitch(d.to);
    if (!r.ok) { log(`autoswitch: activate failed: ${r.error}`); return; }

    st.lastSwitchAt = Date.now();
    st.switches = (st.switches || 0) + 1;
    st.last = { at: Math.floor(Date.now() / 1000), ...d };
    saveAutoswitch(st);

    // A silent identity change would be spooky: the phone and Telegram both
    // hear about it, whatever the alert toggle says. Statement, not question.
    const text = `${d.fromEmail || d.from} hit ${d.fromPercent}% (${d.fromLabel}) — ` +
      `now on ${d.toEmail || d.to} at ${d.toPercent}%. Running sessions keep the ` +
      `old account until they restart.`;
    const push = await deliverPush({ kind: 'account_switch', title: 'Switched Claude account', text, subject: d.to });
    if (!push.sent) await deliverTelegram(`\u{1F501} Switched Claude account\n${text}`);
    log(`autoswitch: ${d.fromEmail} (${d.fromPercent}%) -> ${d.toEmail} (${d.toPercent}%)`);
  } catch (e) {
    autoswitchWhy = `last tick failed: ${e.message}`;
    log('autoswitch: tick failed', e.message);
  } finally {
    autoswitchBusy = false;
  }
}
setInterval(() => { autoswitchTick().catch(() => { }); }, AUTOSWITCH_POLL_MS).unref();


let alertTimer = null;
// Set while a tick is in flight. The poll and the file watcher can now both
// trigger a tick, and two overlapping runs would read-modify-write the same
// alert state — the classic way a "sent" marker gets lost and an alert fires
// twice.
let alertBusy = false;
async function alertTick() {
  const st = loadAlertState();
  if (!st.enabled) return;
  if (alertBusy) return;
  alertBusy = true;
  try {
    await alertTickInner(st);
  } finally {
    alertBusy = false;
  }
}

async function alertTickInner(st) {
  const now = Date.now();
  const sessions = await listSessions();
  // Unknown, not empty. Diffing against a snapshot we failed to take is how a
  // tmux blip turns into mass spurious resolutions; the next tick is 10s away.
  if (sessions === null) { log('alerts: skipping tick, session list unavailable'); return; }
  const d = digest(sessions, chatStates());
  // When each running session's run began — the watcher's OWN ledger, carried
  // from the previous observation, not the state file's timestamp. The file is
  // rewritten by the hook on every tool call, so its ts means "seconds since a
  // tool last ran", and a duration gate fed that read near-zero for runs of any
  // length: multiple 15-minute runs produced zero finish alerts. Rides alongside
  // the digest rather than inside it, because the digest is a change signal that
  // parked phones hash, and a timestamp would make it churn.
  const sessionsSince = carryRunStarts(
    st.prev && st.prev.sessionsSince, d.sessions, Math.floor(now / 1000));
  // Whether a terminal is attached right now, so a finish can stay quiet for a
  // session somebody is sitting at. Deliberately NOT sticky across the run: the
  // finish gate reads the attachment from the last observation while running —
  // "were they watching when it wrapped up" — and making it sticky would mean a
  // ten-second attach early in a two-hour run silences the finish entirely, a
  // missed notification. The non-sticky failure is the benign one: detach in
  // the final seconds and the buzz is merely redundant.
  const sessionsAttached = {};
  for (const s of sessions) if (s.attachedClients > 0) sessionsAttached[s.name] = true;
  const observation = { sessions: d.sessions, sessionsSince, sessionsAttached, chats: d.chats };

  const { alerts, sentUpdates } = decideAlerts(st.prev, observation, st.sent, now, st.prevAt || 0);

  // "A session needs you" is not much use on a lock screen — it says something is
  // wrong without saying what, so the only possible response is to go and look.
  // The question itself is right there in the pane, and the app already turns it
  // into buttons once you are inside; carrying it into the alert is what lets it be
  // answered without opening anything.
  //
  // Enriched HERE rather than inside the digest, deliberately. The digest runs every
  // three seconds for every watching phone, and capturing panes at that rate to
  // collect text that changes only on a transition would be pure waste. This runs
  // once, when something actually happened.
  for (const a of alerts) {
    if (a.kind !== 'session_attention') continue;
    const screen = await captureScreen(a.subject);
    const prompt = screen ? detectPrompt(screen.lines) : null;
    if (!prompt) continue;                 // waiting on something unparsed; keep the plain text
    a.question = prompt.question || '';
    // A notification button is one tap; a multi-select answer is a SET. Buttons
    // are only offered when one tap can honestly answer.
    a.options = prompt.multiSelect ? [] : prompt.options.map((o) => ({ number: o.number, label: o.label }));
    a.fingerprint = promptFingerprint(prompt);
    if (a.question) a.text = a.question;
    log(`alerts: enriched ${a.subject} q=${JSON.stringify((a.question||'').slice(0,40))} opts=${(a.options||[]).length}`);
  }

  // Push first, because it is the route that actually reaches a sleeping phone, and
  // because its outcome is what decides whether Telegram is needed. FCM accepting a
  // message is far better evidence than "this phone checked in recently" — so when
  // push is configured the fallback turns on real delivery rather than a guess.
  let pushedAny = false;
  const pushedKeys = new Set();
  for (const a of alerts) {
    const r = await deliverPush(a);
    if (r.sent > 0) { pushedAny = true; pushedKeys.add(a.key); }   // deliverPush logs and counts each device
  }

  // With no push configured and no tokens registered, fall back to the older signal:
  // whether a phone has been checking in on its own.
  const appReached = pushedAny || clientsLib.appOnline(clientState, now);
  // Resolutions are plumbing for the phone — an instruction to take a stale
  // notification down — never news for a person. Telegram must not carry one:
  // "andrev answered" arriving as a message is noise about something the owner
  // themselves just did.
  const news = alerts.filter((a) => a.kind !== 'session_resolved');
  const { deliver, held } = routeAlerts(news, { mode: st.mode || 'fallback', appOnline: appReached });

  for (const a of held) {
    // Logged rather than dropped quietly: months from now, "why did Telegram stay
    // silent" needs an answer, and "the app had it" is a different answer from
    // "nothing happened".
    log(`alerts: held ${a.kind} for ${a.subject} (${pushedAny ? 'pushed to the app' : 'app checked in recently'})`);
    // "Held" means two different things and only one of them should un-suppress.
    // Held because a PUSH delivered it: the owner has been told, so the repeat
    // guard must stand — otherwise a session flapping in and out of attention
    // pushes every time with no rate limit at all. Held because the app merely
    // looked reachable: nothing was actually delivered, so the marker goes and
    // the next tick may try again.
    if (!pushedKeys.has(a.key)) delete sentUpdates[a.key];
  }
  const undelivered = [];
  for (const a of deliver) {
    const ok = await deliverTelegram(telegramText(a));
    if (ok) {
      st.delivered = (st.delivered || 0) + 1;
      st.lastAt = Math.floor(now / 1000);
      log(`alerts: sent ${a.kind} for ${a.subject}`);
    } else {
      // Undo the suppression so a failed send is retried on the next tick
      // rather than swallowed for half an hour.
      delete sentUpdates[a.key];
      if (!pushedKeys.has(a.key)) undelivered.push(a);
    }
  }
  st.sent = pruneSent({ ...(st.sent || {}), ...sentUpdates }, now);

  // Clearing the repeat guard above is NOT enough to make a failed alert retry,
  // and the comment there used to imply it was. decideAlerts only fires on a
  // TRANSITION (attention edge, running->idle, finishedRuns increase) — so once
  // this observation is saved as `prev`, the transition is consumed and no later
  // tick can re-decide it. A blocking question that failed both channels during a
  // brief WAN blip was therefore never delivered at all, on any channel, ever.
  //
  // So for alerts that reached nobody — push sent to zero devices AND Telegram
  // refused — this rolls that subject back to its previous state, leaving the
  // edge intact for the next tick to re-decide. Only the both-failed case: an
  // alert HELD because the app has it is genuinely delivered (the phone's own
  // watch baseline consumes it independently), and re-deciding those would
  // re-buzz every tick until the phone reappeared.
  if (undelivered.length) {
    const prevObs = st.prev || {};
    for (const a of undelivered) {
      if (a.kind === 'chat_finished') {
        const before = (prevObs.chats || {})[a.subject];
        if (before) observation.chats[a.subject] = before;
        else delete observation.chats[a.subject];
      } else {
        const before = (prevObs.sessions || {})[a.subject];
        if (before === undefined) delete observation.sessions[a.subject];
        else observation.sessions[a.subject] = before;
        // The run-start ledger has to roll back with it, or a re-decided
        // session_finished would measure a zero-length run and stay silent.
        const since = (prevObs.sessionsSince || {})[a.subject];
        if (since === undefined) delete observation.sessionsSince[a.subject];
        else observation.sessionsSince[a.subject] = since;
      }
      log(`alerts: ${a.kind} for ${a.subject} reached nobody — edge kept for retry`);
    }
  }
  st.prev = observation;
  // Stamped alongside it, because "was this chat created since we last looked?"
  // cannot be answered by the observation itself — it records what existed, not
  // when the looking happened.
  st.prevAt = now;

  // Merged onto a RELOADED state, never written as a whole snapshot.
  //
  // `st` was loaded when this tick began, and a tick spans network calls — so a
  // POST /v1/alerts landing meanwhile (turning alerts off, changing the mode)
  // was silently reverted the moment the tick finished: the setting appeared to
  // take, then undid itself. Only the fields this tick OWNS are written back;
  // `enabled` and `mode` belong to the caller and are left exactly as found.
  const fresh = loadAlertState();
  if (fresh.enabled !== st.enabled) {
    // The feature was toggled underneath us. Enabling deliberately clears `prev`
    // so switching on does not announce everything already true, and writing our
    // observation over that would defeat it. Keep only the suppression record
    // and let the next tick take a clean baseline.
    fresh.sent = st.sent;
    log('alerts: settings changed during the tick; baseline left to the next one');
  } else {
    fresh.sent = st.sent;
    fresh.prev = st.prev;
    fresh.prevAt = st.prevAt;
    fresh.delivered = st.delivered;
    fresh.lastAt = st.lastAt;
  }
  saveAlertState(fresh);
}

/**
 * Watches the hook's state directory so a session changing state is noticed
 * IMMEDIATELY rather than on the next poll.
 *
 * Measured before this existed: a real question took ~1.9s to reach the phone,
 * of which the push itself was under 100ms — the rest was waiting for the
 * ten-second poll to come round. The hook already writes
 * /run/huginn-claude-state/<session> the moment a session's state changes, so
 * the information is sitting there; polling for it was the only reason it went
 * unnoticed.
 *
 * The interval poll STAYS as a floor: inotify can miss events under some
 * filesystem conditions, and chat-finished alerts have no state file to watch
 * at all. This makes the common case instant without becoming the only path.
 */
let stateWatcher = null;
let stateWatchRetry = null;
let watchDebounce = null;
function startStateWatch() {
  if (stateWatcher) return;
  try {
    // STATE_DIR lives under /run, which is a tmpfs: it does NOT survive a
    // reboot. If this daemon started before anything recreated it, fs.watch
    // threw ENOENT, the failure was logged exactly once, and instant detection
    // was off for the entire uptime — degraded to the 10s poll with nothing
    // saying so. Creating it costs nothing and is idempotent.
    fs.mkdirSync(STATE_DIR, { recursive: true });
    stateWatcher = fs.watch(STATE_DIR, () => {
      // Debounced: a single state change is several filesystem events (write,
      // rename, attribute), and each should not spawn its own tick.
      if (watchDebounce) return;
      watchDebounce = setTimeout(() => {
        watchDebounce = null;
        alertTick().catch((e) => log('alerts: watch tick failed', e.message));
      }, 120);
      watchDebounce.unref();
    });
    // A watch can also DIE later — the directory being removed and recreated
    // leaves a handle watching an inode nobody writes to any more, which is
    // silent in exactly the same way.
    stateWatcher.on('error', (e) => {
      log(`alerts: state watch died (${e.message}); will retry`);
      try { stateWatcher.close(); } catch { }
      stateWatcher = null;
      retryStateWatch();
    });
    log(`alerts: watching ${STATE_DIR} for instant detection`);
  } catch (e) {
    // No watch is survivable — the poll still covers everything, just slower —
    // but it should not be PERMANENT. Retried, so a reboot race heals itself
    // instead of costing instant detection until the next deploy.
    log(`alerts: could not watch ${STATE_DIR} (${e.message}); retrying, polling meanwhile`);
    retryStateWatch();
  }
}

/** Re-attempts the state watch, at a cadence that cannot become a busy loop. */
function retryStateWatch() {
  if (stateWatchRetry) return;
  stateWatchRetry = setTimeout(() => {
    stateWatchRetry = null;
    startStateWatch();
  }, 30_000);
  stateWatchRetry.unref();
}

function startAlertWatcher() {
  if (alertTimer) return;
  alertTimer = setInterval(() => { alertTick().catch((e) => log('alerts: tick failed', e.message)); },
    ALERT_POLL_MS);
  alertTimer.unref();
  startStateWatch();
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

    // --- host-side alerts, which reach a phone with the app closed
    if (req.method === 'GET' && p === '/v1/alerts') {
      const st = loadAlertState();
      return sendJson(res, 200, {
        enabled: !!st.enabled,
        mode: st.mode || 'fallback',
        delivered: st.delivered || 0,
        lastAt: st.lastAt ?? null,
        channel: fs.existsSync(TELEGRAM_SCRIPT) ? 'telegram' : 'none',
        appOnline: clientsLib.appOnline(clientState, Date.now()),
        pushConfigured: !!fcm,
        pushDevices: pushLib.count(loadPushState()),
        pushed: pushLib.totals(loadPushState()).pushed,
      });
    }

    // --- FCM: the app hands over the token Google will deliver to
    if (req.method === 'POST' && p === '/v1/push/register') {
      const body = JSON.parse(await readBody(req) || '{}');
      const installId = String(body.installId || '').trim().slice(0, 64);
      const token = String(body.token || '').trim();
      if (!installId || !token) return sendErr(res, 400, 'installId and token are required');
      const st = loadPushState();
      const r = pushLib.register(st, installId, token, Date.now(), { model: body.model });
      // Persisted only on a real change, because the app re-registers on every start
      // and rewriting the file each time buys nothing.
      if (r.changed) {
        savePushState(st);
        log(`push: ${r.rotated ? 'rotated' : 'registered'} token for ${installId}`);
      }
      return sendJson(res, 200, {
        ok: true,
        configured: !!fcm,
        devices: pushLib.count(st),
        rotated: r.rotated,
      });
    }

    if (req.method === 'GET' && p === '/v1/push') {
      const st = loadPushState();
      return sendJson(res, 200, {
        // Whether the HOST can send at all, which is a different question from
        // whether any phone has registered — and they fail for different reasons.
        configured: !!fcm,
        projectId: fcm ? fcm.projectId : null,
        sender: fcm ? fcm.email : null,
        devices: pushLib.list(st).map(({ token, ...rest }) => ({
          ...rest,
          // Never the token itself: it is a delivery credential for this device.
          tokenTail: token.slice(-8),
        })),
        ...pushLib.totals(st),
      });
    }

    if (req.method === 'POST' && p === '/v1/push/test') {
      if (!fcm) return sendErr(res, 503, 'FCM is not configured on this host');
      const r = await deliverPush({
        title: 'Huginn push test',
        text: 'This is what an alert delivered straight to the app looks like.',
        kind: 'test',
        subject: 'test',
      });
      return sendJson(res, r.sent > 0 ? 200 : 502, {
        ok: r.sent > 0, ...r,
        error: r.sent > 0 ? undefined
          : (r.dead ? 'the registered token was rejected as dead' : 'no device accepted the push'),
      });
    }

    // --- automatic account switching
    if (req.method === 'GET' && p === '/v1/autoswitch') {
      const st = loadAutoswitch();
      return sendJson(res, 200, {
        enabled: !!st.enabled,
        switches: st.switches || 0,
        last: st.last || null,
        accounts: accounts.list().length,
        threshold: typeof st.threshold === 'number' ? st.threshold : AUTOSWITCH_THRESHOLD,
        // Why the most recent look did nothing. Null while a switch is in hand.
        idleBecause: autoswitchWhy,
      });
    }
    if (req.method === 'POST' && p === '/v1/autoswitch') {
      const body = JSON.parse(await readBody(req) || '{}');
      const st = loadAutoswitch();
      if (typeof body.enabled === 'boolean') st.enabled = body.enabled;
      // The default fires at 95%, on the reasoning that a limit resetting in
      // twenty minutes is not worth spending a fresh account on. That is a taste
      // question, not a fact, so it is tunable without a deploy.
      if (typeof body.threshold === 'number' && body.threshold >= 50 && body.threshold <= 100) {
        st.threshold = Math.round(body.threshold);
      }
      saveAutoswitch(st);
      log(`autoswitch: ${st.enabled ? 'enabled' : 'disabled'} at ${st.threshold ?? AUTOSWITCH_THRESHOLD}%`);
      // An immediate look, so enabling it against an already-dry account acts
      // now rather than in five minutes.
      if (st.enabled) autoswitchTick().catch(() => { });
      return sendJson(res, 200, {
        enabled: !!st.enabled,
        threshold: typeof st.threshold === 'number' ? st.threshold : AUTOSWITCH_THRESHOLD,
      });
    }

    // --- has the phone actually been checking in? The whole point of recording
    //     this on the host is that the phone cannot answer while it is asleep.
    if (req.method === 'GET' && p === '/v1/clients') {
      const now = Date.now();
      return sendJson(res, 200, {
        clients: clientsLib.listClients(clientState, now),
        appOnline: clientsLib.appOnline(clientState, now),
        // Per-kind, because a stream that has said nothing for three minutes is dead
        // while an alarm that has said nothing for three minutes is merely between
        // beats. Each client carries the window it is actually judged against.
        freshStreamSeconds: Math.floor(clientsLib.FRESH_STREAM_MS / 1000),
        freshBeatSeconds: Math.floor(clientsLib.FRESH_BEAT_MS / 1000),
        serverTime: Math.floor(now / 1000),
      });
    }

    if (req.method === 'POST' && p === '/v1/alerts') {
      const body = JSON.parse(await readBody(req) || '{}');
      const st = loadAlertState();
      if (typeof body.enabled === 'boolean') {
        st.enabled = body.enabled;
        // Forget the previous observation when switching on, so turning it on
        // does not announce whatever was already true.
        if (body.enabled) st.prev = null;
      }
      if (body.mode === 'fallback' || body.mode === 'always') st.mode = body.mode;
      saveAlertState(st);
      if (st.enabled) startAlertWatcher();
      log(`alerts: ${st.enabled ? 'enabled' : 'disabled'} mode=${st.mode || 'fallback'}`);
      return sendJson(res, 200, { enabled: !!st.enabled, mode: st.mode || 'fallback' });
    }

    if (req.method === 'POST' && p === '/v1/alerts/test') {
      const ok = await deliverTelegram('🔔 Huginn test alert\nThis is what a session needing you will look like.');
      return sendJson(res, ok ? 200 : 500, ok ? { ok: true } : { error: 'could not deliver' });
    }

    // --- the change signal a watching phone parks on
    if (req.method === 'GET' && p === '/v1/watch') {
      const known = u.searchParams.get('hash');

      // Streaming mode exists for one reason: a long poll that dies silently is
      // indistinguishable from a long poll that is simply waiting. The phone had
      // no way to tell, so a socket black-holed by a network change or a NAT
      // timeout looked exactly like a quiet night, and the app went on believing
      // it was watching. A keepalive every 25 seconds makes silence mean failure,
      // which the app can act on — and each one re-stamps this client as alive, so
      // the host can also see the vigil from its side.
      if (u.searchParams.get('stream') === '1') {
        noteClient(req, 'stream');
        const streamInstall = String(req.headers['x-huginn-client'] || '').trim().slice(0, 64);
        res.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
          'X-Accel-Buffering': 'no',
        });
        let last = known || null;
        const started = Date.now();
        // Bounded so a client that vanished without closing cannot hold a tmux
        // polling loop forever; the app treats `bye` as "reconnect", not "stop".
        const MAX_MS = 30 * 60 * 1000;
        const KEEPALIVE_MS = 25_000;
        let nextKeepalive = Date.now() + KEEPALIVE_MS;

        while (!req.destroyed && !res.writableEnded) {
          const sess = await listSessions();
          const d = digest(sess ?? [], chatStates());
          // A failed observation must not be published as a change: the phone
          // would see every session disappear and act on it.
          if (sess !== null && d.hash !== last) {
            last = d.hash;
            res.write(`event: state\ndata: ${JSON.stringify({
              ...d, changed: true, serverTime: Math.floor(Date.now() / 1000),
              // Same field the long poll returns. Without it the app decodes the
              // absent value as 0 and overwrites its real tally, which silently
              // disables push-deficit detection: the phone can no longer tell a
              // quiet night from a broken delivery path, so it never tightens
              // its fallback cadence no matter how many pushes go missing.
              pushesSent: streamInstall ? pushLib.sentTo(loadPushState(), streamInstall) : null,
            })}\n\n`);
            nextKeepalive = Date.now() + KEEPALIVE_MS;
          } else if (Date.now() >= nextKeepalive) {
            // A comment frame: valid SSE, ignored by any parser, and enough to
            // prove the path is still open in both directions.
            res.write(`: ka ${Math.floor(Date.now() / 1000)}\n\n`);
            nextKeepalive = Date.now() + KEEPALIVE_MS;
            noteClient(req, 'stream');
          }
          if (Date.now() - started > MAX_MS) {
            res.write('event: bye\ndata: {"reason":"rotate"}\n\n');
            break;
          }
          await sleep(3000);
        }
        if (!res.writableEnded) res.end();
        return;
      }

      const waitMs = Math.max(0, Math.min(300_000, Number(u.searchParams.get('wait')) || 0));
      // A zero-wait watch is the Doze-proof alarm checking in; a long one is the
      // older poll. Worth telling apart, because "the alarm still fires while the
      // phone sleeps" is the claim this whole change stands on.
      noteClient(req, waitMs > 0 ? 'poll' : 'heartbeat');
      const deadline = Date.now() + waitMs;
      // Cheap inputs on purpose: no previews, no transcripts. This runs in a loop
      // for as long as a phone is watching.
      let sess = await listSessions();
      let d = digest(sess ?? [], chatStates());
      while (known && (sess === null || d.hash === known) && Date.now() < deadline && !req.destroyed) {
        await sleep(3000);
        sess = await listSessions();
        d = digest(sess ?? [], chatStates());
      }
      if (req.destroyed) return;
      const installId = String(req.headers['x-huginn-client'] || '').trim().slice(0, 64);
      return sendJson(res, 200, {
        ...d,
        changed: !known || d.hash !== known,
        serverTime: Math.floor(Date.now() / 1000),
        // What this host thinks it has delivered to the caller. The phone compares
        // it against what it actually received, which is the only way it can tell a
        // quiet night from a broken delivery path — and that distinction is worth a
        // hundred and twenty device wake-ups a day.
        pushesSent: installId ? pushLib.sentTo(loadPushState(), installId) : 0,
      });
    }

    // --- models the installed CLI actually offers
    if (req.method === 'GET' && p === '/v1/models') {
      const bin = process.env.HUGINN_CLAUDE_BIN ||
        '/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe';
      return sendJson(res, 200, { models: await discoverModels(bin) });
    }

    // --- account
    if (req.method === 'GET' && p === '/v1/account') return sendJson(res, 200, await accountStatus());

    // --- saved accounts
    if (req.method === 'GET' && p === '/v1/accounts') {
      const withPlan = u.searchParams.get('plan') === '1';
      // Capture whatever is signed in right now, under the account uuid its own
      // token reports. Claude Code rewrites this file with a fresh token pair
      // every few hours; keyed on the tokens alone that produced a new profile on
      // every rotation, thirteen of them for three logins.
      const acct = await accountStatus();
      const live = accounts.readActive();
      if (live) await saveIdentified(acct.loggedIn ? acct.email : null, live,
        acct.orgName ? { orgName: acct.orgName } : {});
      // A finished sign-in leaves a `login` session sitting at a prompt; retire it
      // once the credentials have actually changed, so the sessions list is not
      // littered with the mechanics of adding an account.
      if (loginStartedFrom && fingerprint(live) && fingerprint(live) !== loginStartedFrom) {
        if (await sessionExists('login')) {
          await run('tmux', ['kill-session', '-t', '=login']);
          log('sign-in completed; retired the login session');
        }
        loginStartedFrom = null;
      }
      // Identify each profile from its OWN token rather than from whatever is
      // active — and write back the uuid it reports, which is what keeps the
      // profile in one piece the next time that token rotates. Sequential, since
      // each of these can rewrite the store.
      const answered = new Set();
      for (const a of accounts.list()) {
        const rec = accounts.readProfile(a.slug);
        if (!rec) continue;
        const id = await resolveIdentity(rec.credentials);
        if (!id || !(id.email || id.uuid)) continue;
        answered.add(id.uuid || a.slug);
        if (rec.email !== id.email || normUuid(rec.accountUuid) !== id.uuid) {
          accounts.save(id.email ?? rec.email, rec.credentials, {
            orgName: rec.orgName ?? id.orgName ?? null,
            ...(id.uuid ? { accountUuid: id.uuid, taggedId: id.taggedId } : {}),
          });
        }
      }
      // Whatever a rotation or an offline stretch left behind, folded back into
      // one profile per login. Idempotent, and a no-op on a settled store.
      try { accounts.consolidate(); } catch (e) { log('accounts: consolidate failed', e.message); }

      const saved = accounts.list();
      // "Verified" stays a claim about THIS moment: the account's own token was
      // asked just now and answered. A stored uuid is identity enough to file the
      // profile under, but it is not a live proof that the login still works —
      // and in practice only the active account holds a token fresh enough to
      // answer at all.
      for (const a of saved) a.verified = answered.has(a.accountUuid || a.slug);
      // Only meaningful now for a profile the endpoint could never identify: with
      // a uuid in hand, one login is one record and cannot appear twice. Two rows
      // sharing an email means at least one of them was saved while this host
      // could not reach the API, and switching between them may do nothing.
      const byEmail = new Map();
      for (const a of saved) {
        if (!a.email) continue;
        byEmail.set(a.email, (byEmail.get(a.email) || 0) + 1);
      }
      for (const a of saved) a.duplicateOf = (byEmail.get(a.email) || 0) > 1;

      if (withPlan) {
        await Promise.all(saved.map(async (a) => {
          const rec = accounts.readProfile(a.slug);
          const pl = rec && await planForCredentials(rec.credentials);
          if (pl) accounts.recordPlan(a.slug, pl);
          // Only the ACTIVE account holds a token fresh enough to answer; for the
          // others fall back to the last reading taken while they were, aged
          // forward. Said plainly via planAgeSec rather than passed off as live.
          const limits = pl ? pl.limits : agedLimits(rec && rec.lastPlan, Date.now());
          const pick = (kind) => limits.find((l) => l.kind === kind)?.percent ?? null;
          // The weekly all-models figure is the one that decides whether an
          // account still has room.
          a.weeklyPercent = pick('weekly_all');
          a.sessionPercent = pick('session');
          a.planLive = !!pl;
          a.planAgeSec = pl ? 0
            : (rec && rec.lastPlan && rec.lastPlan.at ? Math.floor(Date.now() / 1000) - rec.lastPlan.at : null);
        }));
      }
      return sendJson(res, 200, { accounts: saved });
    }

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})$/)) && req.method === 'DELETE') {
      if (!accounts.remove(m[1])) return sendErr(res, 404, 'no such saved account');
      return sendJson(res, 200, { ok: true });
    }

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})\/activate$/)) && req.method === 'POST') {
      const r = await performSwitch(m[1]);
      if (!r.ok) return sendErr(res, 404, r.error);
      return sendJson(res, 200, { ok: true, ...r.after });
    }

    if (req.method === 'POST' && p === '/v1/account/login') {
      const loginBody = JSON.parse(await readBody(req) || '{}');
      // An email is optional but strongly worth having: the authorize page uses
      // whatever claude.ai session the browser already has, which is how signing
      // in "as a second account" can silently re-authorize the first one.
      loginIntent = typeof loginBody.email === 'string' && /^[^\s@]+@[^\s@]+$/.test(loginBody.email.trim())
        ? loginBody.email.trim().toLowerCase()
        : null;
      // Signing in is an interactive OAuth flow: it prints a URL and waits for a
      // code. There is no headless path, so put it in a real tmux session and
      // hand the app the session name — the Screen view can show the URL and
      // take the pasted code, which is exactly what that view is for.
      const name = 'login';
      // Signing in REPLACES the credentials file, so capture what is there now or
      // adding an account silently costs you the one you were using.
      const before = await accountStatus();
      const cur = accounts.readActive();
      if (cur) await saveIdentified(before.loggedIn ? before.email : null, cur,
        before.orgName ? { orgName: before.orgName } : {});
      loginStartedFrom = fingerprint(cur);
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
      if (url) loginUrl = url;
      let out = url || loginUrl;
      // login_hint is the standard way to aim an authorize page at one account.
      // The endpoint accepts it; whether it overrides an existing browser session
      // is not something this host can prove, so the app also tells the user how
      // to be certain (a signed-out or private window).
      if (out && loginIntent) {
        out += (out.includes('?') ? '&' : '?') + 'login_hint=' + encodeURIComponent(loginIntent);
      }
      return sendJson(res, existed ? 200 : 201, {
        ok: true, session: name, existed, url: out, intendedEmail: loginIntent,
      });
    }

    // Where the sign-in has got to, so the app can host the whole flow.
    if (req.method === 'GET' && p === '/v1/account/login/state') {
      return sendJson(res, 200, await readLoginState());
    }

    // The pasted code, handed to the waiting prompt.
    if (req.method === 'POST' && p === '/v1/account/login/code') {
      const body = JSON.parse(await readBody(req) || '{}');
      const code = typeof body.code === 'string' ? body.code.trim() : '';
      // Codes are opaque; accept a generous shape but nothing that could be a
      // second command, since this is typed into a live terminal.
      if (!/^[A-Za-z0-9#._~:/?=&%+-]{8,600}$/.test(code)) {
        return sendErr(res, 400, 'that does not look like a sign-in code');
      }
      if (!(await sessionExists('login'))) return sendErr(res, 409, 'no sign-in is in progress');

      const before = fingerprint(accounts.readActive());
      const send = await run('tmux', ['send-keys', '-t', '=login:', '-l', '--', code]);
      if (send.err) return sendErr(res, 500, `tmux: ${send.stderr.trim()}`);
      await run('tmux', ['send-keys', '-t', '=login:', 'Enter']);

      // Wait for the credentials to actually change, which is the only
      // trustworthy signal that the sign-in took: the pane says plenty of
      // encouraging things before it is finished.
      for (let i = 0; i < 40; i++) {
        await sleep(500);
        if (fingerprint(accounts.readActive()) !== before) {
          const acct = await accountStatus();
          const live = accounts.readActive();
          if (live) await saveIdentified(acct.loggedIn ? acct.email : null, live,
            acct.orgName ? { orgName: acct.orgName } : {});
          if (await sessionExists('login')) await run('tmux', ['kill-session', '-t', '=login']);

          // Ask the new token who it is, rather than trusting the label: this is
          // the check that catches "signed in the same account again", which is
          // the failure this whole flow exists to avoid.
          const captured = (live && await resolveEmail(live)) || acct.email || null;
          // "Every profile that is not the one we just saved."
          //
          // Compared by CREDENTIALS, not by slug. This read
          // `a.slug !== fingerprint(live)` while profiles are keyed by
          // accountUuid, so the filter never matched anything: the new account
          // stayed in `others`, was compared against itself, trivially had the
          // same email, and every successful sign-in of a genuinely NEW account
          // came back flagged `duplicate` — the one warning this flow exists to
          // raise, cried on every use.
          const others = accounts.list().filter((a) => {
            const rec = accounts.readProfile(a.slug);
            return !(rec && sameAccount(rec.credentials, live));
          });
          const dupSlugs = [];
          for (const o of others) {
            const rec = accounts.readProfile(o.slug);
            const em = rec && await resolveEmail(rec.credentials);
            if (em && captured && em.toLowerCase() === captured.toLowerCase()) dupSlugs.push(o.slug);
          }
          const intended = loginIntent;
          loginStartedFrom = null;
          loginUrl = null;
          loginIntent = null;
          log(`sign-in completed as ${captured || 'unknown'}${dupSlugs.length ? ' (DUPLICATE)' : ''}`);
          return sendJson(res, 200, {
            session: 'login', running: false, awaitingCode: false, done: true,
            email: captured,
            intendedEmail: intended,
            duplicate: dupSlugs.length > 0,
            mismatch: !!(intended && captured && intended !== captured.toLowerCase()),
            message: 'Signed in',
          });
        }
      }
      // Still nothing: hand back what the pane says rather than a bare timeout.
      return sendJson(res, 200, { ...(await readLoginState()), done: false });
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
      {
        const sess = await listSessions({ preview });
        if (sess === null) return sendErr(res, 503, 'tmux is not answering right now');
        return sendJson(res, 200, { sessions: sess });
      }
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
      // What tmux called it, not what we asked for — same reason as the rename
      // route below: a '.' is rewritten to '_' with a zero exit, and a client
      // told the wrong name gets a 404 on everything it does next.
      const q = await run('tmux', ['display-message', '-p', '-t', `=${name}`, '#S']);
      return sendJson(res, 201, { ok: true, name: (q.stdout || '').trim() || name });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})$/)) && req.method === 'DELETE') {
      const name = m[1];
      const { err, stderr } = await run('tmux', ['kill-session', '-t', `=${name}`]);
      if (err) return sendErr(res, 404, `tmux: ${stderr.trim() || 'no such session'}`);
      return sendJson(res, 200, { ok: true });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/screen$/)) && req.method === 'GET') {
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
      // Adaptive tick. Every keystroke echoed by the pane ends this request and
      // the next one starts at the fast rate, so while somebody is TYPING the
      // effective echo latency is the fast tick; a session nobody is touching
      // decays to the slow one after a few seconds. 700ms flat was the largest
      // single cause of live typing feeling laggy.
      let tick = 0;
      while (known && scr.hash === known && Date.now() < deadline && !req.destroyed) {
        await sleep(tick++ < 24 ? 130 : 450);
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
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/size$/)) && req.method === 'DELETE') {
      await releaseSize(m[1]);
      return sendJson(res, 200, { ok: true });
    }

    // Structured conversation for a tmux session, straight from its Claude Code
    // transcript: thinking, tool calls, subagent output, workflow runs. This is
    // the primary way the app shows a session; the pane is for interaction.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/transcript$/)) && req.method === 'GET') {
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
      // `until` reads BACKWARDS: the window ends here instead of at the live end
      // of the file. Pass the previous response's `windowStart` to walk into
      // history a page at a time — without it a long session shows only its tail
      // and there is no way to ask for the rest.
      const untilParam = u.searchParams.get('until');
      const untilNum = untilParam == null ? null : Number(untilParam);
      if (untilNum !== null && !Number.isFinite(untilNum)) return sendErr(res, 400, 'until must be a number');
      const t = readTranscript(st.transcript, {
        offset: offsetNum,
        until: untilNum,
        limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
      });
      return sendJson(res, 200, {
        ...t,
        modelDisplay: formatModel(t.model),
        state: st.state,
        claudeSessionId: st.sessionId,
        // What the tail says is in flight — an unresolved tool, active subagents —
        // so the conversation can show work happening rather than going silent
        // between completed blocks.
        activity: liveActivity(t.events, Math.floor(Date.now() / 1000)),
        // Background shells and agents, so a session blocked on a long build does
        // not read as stalled from the conversation.
        ...await (async () => {
          const pid = await run('tmux', ['display-message', '-p', '-t', `=${name}:`, '#{pane_pid}']);
          const bg = await backgroundWork(name, pid.err ? null : Number(pid.stdout.trim()) || null);
          return { tasks: bg.shells, bgAgents: bg.agents };
        })(),
      });
    }

    // --- suggested next messages, generated when a turn has just ended
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/suggestions$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || st.state === 'running' || !st.transcript) {
        return sendJson(res, 200, { suggestions: [], reason: 'running' });
      }
      return sendJson(res, 200, await suggestionsFor(st.sessionId, st.transcript));
    }

    // --- the individual agents behind "0/4 agents done"
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/agents$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      const dir = st ? agentsDirFor(st.transcript, st.sessionId) : null;
      const agents = dir ? listAgents(dir, Math.floor(Date.now() / 1000)) : [];
      return sendJson(res, 200, {
        agents,
        active: agents.filter((a) => a.active).length,
        serverTime: Math.floor(Date.now() / 1000),
      });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/rename$/)) && req.method === 'POST') {
      const from = m[1];
      const body = JSON.parse(await readBody(req) || '{}');
      const to = canonName(body.name);
      if (!to) return sendErr(res, 400, 'invalid session name (letters, digits, underscore)');
      if (to !== from && await sessionExists(to)) return sendErr(res, 409, `session '${to}' already exists`);
      const r = await run('tmux', ['rename-session', '-t', `=${from}`, to]);
      if (r.err) return sendErr(res, 404, `tmux: ${r.stderr.trim() || 'no such session'}`);
      // Ask tmux what it ACTUALLY called the session rather than assuming it
      // took the name we asked for. tmux silently rewrites '.' to '_' and still
      // exits 0, so a rename to "my.session" left a live session named
      // "my_session" while this route moved the state file to "my.session" and
      // handed the client a name that 404s on every subsequent request. The
      // orphaned state file is the worse half: it is the session -> transcript
      // mapping, so the Conversation view — the app's primary surface — had
      // nothing to read until the title hook happened to rewrite it, which for
      // an idle session is never.
      //
      // Reading the name back rather than rejecting '.' keeps this correct for
      // whatever character tmux decides to rewrite next.
      const q = await run('tmux', ['display-message', '-p', '-t', `=${to}`, '#S']);
      const actual = (q.stdout || '').trim() || to;
      // The state file is keyed by name; move it so state/transcript survive.
      try { fs.renameSync(path.join(STATE_DIR, from), path.join(STATE_DIR, actual)); } catch { }
      if (leases.has(from)) { leases.set(actual, leases.get(from)); leases.delete(from); }
      return sendJson(res, 200, { ok: true, name: actual });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/keys$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const body = JSON.parse(await readBody(req) || '{}');
      if (typeof body.text === 'string' && body.text.length > 0) {
        if (body.text.length > 8000) return sendErr(res, 400, 'text too long');
        const r = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', body.text]);
        if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim()}`);
        // A beat before any Enter that follows. Text and Enter in one burst
        // occasionally read to the TUI as a single paste, which INSERTS the
        // newline instead of submitting — the message sat in Claude's composer
        // until someone pressed Enter by hand. Rare because it needs the reads
        // to coincide; the pause makes Enter a distinct keypress every time.
        if (Array.isArray(body.keys) && body.keys.includes('Enter')) await sleep(150);
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

    // --- answering a question from a notification, without opening the app
    //
    // Check-and-act, on the host, in one request. The phone cannot do this safely:
    // between reading the pane and sending the digit it would have to trust that
    // nothing changed, and the whole point of this endpoint is that something might
    // have. Answered in tmux meanwhile, moved on to a different question, back to an
    // idle composer — in every one of those cases a bare digit lands somewhere it was
    // never meant to, and in a Claude Code pane that can accept a prompt the owner
    // never saw. So the fingerprint of the question being answered comes with the
    // answer, and a mismatch is refused rather than delivered hopefully.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/answer$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const body = JSON.parse(await readBody(req) || '{}');
      const option = Number(body.option);
      const isMulti = Array.isArray(body.options);
      if (!isMulti && (!Number.isInteger(option) || option < 1 || option > 20)) {
        return sendErr(res, 400, 'option must be a small positive integer');
      }
      if (isMulti && (body.options.length > 20 ||
        !body.options.every((n) => Number.isInteger(Number(n)) && n >= 1 && n <= 20))) {
        return sendErr(res, 400, 'options must be small positive integers');
      }

      const screen = await captureScreen(name);
      const prompt = screen ? detectPrompt(screen.lines) : null;
      if (!prompt) {
        return sendJson(res, 409, {
          ok: false, reason: 'gone',
          error: 'that question is no longer on screen',
        });
      }
      const live = promptFingerprint(prompt);
      // REQUIRED, not merely honoured when offered. This used to read
      // `if (body.fingerprint && body.fingerprint !== live)`, which made the
      // whole check-and-act guard opt-in: a caller that omitted the field — or
      // sent an empty string, which is equally falsy — got its digit typed into
      // whatever question happened to be on the pane. Both were reachable from
      // the shipping clients (HuginnClient omits the key for a null,
      // AnswerReceiver turns a blank notification extra into null, and
      // lib/fcm.js puts `String(fingerprint ?? '')` on the wire), so the
      // guarantee this route's comment above describes did not exist.
      //
      // The empty-string case is called out separately in the tests because it
      // is a JavaScript truthiness trap: `if (!body.fingerprint)` reads as a
      // presence check and silently also accepts ''. The Electron client got
      // this right in notify/activation.ts and rejects both; the host did not.
      if (typeof body.fingerprint !== 'string' || body.fingerprint === '') {
        return sendErr(res, 400, 'fingerprint required');
      }
      if (body.fingerprint !== live) {
        return sendJson(res, 409, {
          ok: false, reason: 'changed',
          error: 'the session is asking something else now',
          prompt, fingerprint: live,
        });
      }
      // A SET of options: the multi-select dialog. Digits toggle, Right opens
      // the review tab, Enter submits — the whole sequence verified live before
      // this was written. The digits are a DIFF against the current checkbox
      // state, because the owner may have half-answered in tmux already and
      // blindly pressing every desired digit would un-check those.
      if (Array.isArray(body.options)) {
        if (!prompt.multiSelect) {
          return sendJson(res, 409, {
            ok: false, reason: 'changed',
            error: 'this question takes a single answer', prompt, fingerprint: live,
          });
        }
        const desired = body.options.map(Number);
        const valid = new Set(prompt.options
          .filter((o) => typeof o.checked === 'boolean').map((o) => o.number));
        if (!desired.every((n) => Number.isInteger(n) && valid.has(n))) {
          return sendJson(res, 409, {
            ok: false, reason: 'changed',
            error: 'an option is not offered any more', prompt, fingerprint: live,
          });
        }
        const digits = multiToggleDigits(prompt.options, desired);
        for (const d of digits) {
          const t = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', d]);
          if (t.err) return sendErr(res, 500, `tmux: ${t.stderr.trim()}`);
          await sleep(120);                        // let the TUI apply each toggle
        }
        const right = await run('tmux', ['send-keys', '-t', `=${name}:`, 'Right']);
        if (right.err) return sendErr(res, 500, `tmux: ${right.stderr.trim()}`);
        await sleep(250);                          // the review tab needs a beat
        const enter2 = await run('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
        if (enter2.err) return sendErr(res, 500, `tmux: ${enter2.stderr.trim()}`);
        const labels = prompt.options
          .filter((o) => desired.includes(o.number)).map((o) => o.label);
        log(`answer: ${name} <- multi [${desired.join(',')}] (${labels.join(', ').slice(0, 80)})`);
        return sendJson(res, 200, { ok: true, options: desired, labels });
      }

      const chosen = prompt.options.find((o) => o.number === option);
      if (!chosen) {
        return sendJson(res, 409, {
          ok: false, reason: 'changed',
          error: `option ${option} is not offered any more`,
          prompt, fingerprint: live,
        });
      }

      // The digit and Enter separately, literal digit first, so a multi-digit option
      // cannot be split across a submit.
      const typed = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', String(option)]);
      if (typed.err) return sendErr(res, 500, `tmux: ${typed.stderr.trim()}`);
      const enter = await run('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
      if (enter.err) return sendErr(res, 500, `tmux: ${enter.stderr.trim()}`);
      log(`answer: ${name} <- ${option} (${chosen.label.slice(0, 60)})`);
      return sendJson(res, 200, { ok: true, option, label: chosen.label });
    }

    // --- chats
    // --- desktop update channel: electron-updater's feed (latest*.yml) and the
    // installers themselves. Auth like every route — the updater sends the
    // Bearer header on the feed AND artifact GETs. This is the daemon's first
    // streaming-OUT path (uploads is the streaming-in precedent): an installer
    // is ~90 MB and must never transit the heap.
    //
    // One helper, both channels. The directory is the ONLY difference between
    // them here; keeping that a parameter rather than a second copy of the route
    // is what stops a future fix landing in one channel and not the other.
    const serveDesktopArtifact = (dir, name) => {
      const found = desktopLib.resolveArtifact(dir, name);
      if (!found.ok) return sendErr(res, found.status, found.error);
      res.writeHead(200, {
        'Content-Type': found.contentType,
        'Content-Length': found.size,
      });
      const stream = fs.createReadStream(found.file);
      // Let the fd go when the client does. `pipe` only UNPIPES its source when
      // the destination closes — it never destroys it — so an aborted download
      // left the read handle open for the life of the process. These are ~90MB
      // installers fetched by the self-updater over a mesh link, so a closed
      // laptop or a dropped tunnel is ordinary; measured one leaked fd per abort,
      // never reclaimed, each pinning the artifact's inode so a pruned release
      // still occupied disk that `du` could not see.
      res.on('close', () => stream.destroy());
      stream.pipe(res);
      stream.on('error', () => { try { res.destroy(); } catch { } });
    };
    if (req.method === 'GET' && p === '/v1/desktop/manifest') {
      const man = desktopLib.readManifest(DESKTOP_DIR);
      if (!man) return sendErr(res, 404, 'no desktop releases yet');
      return sendJson(res, 200, man);
    }
    if (req.method === 'GET' && (m = p.match(/^\/v1\/desktop\/([^/]+)$/))) {
      return serveDesktopArtifact(DESKTOP_DIR, decodeURIComponent(m[1]));
    }
    // --- the Compose Multiplatform client's channel. Same contract, same auth,
    // its OWN directory: /v1/desktop-kt, stocked by mobile/scripts/release-desktop.sh.
    // The updater that reads it pins these paths at compile time (UpdateFeed.kt),
    // because the builds are unsigned and whoever controls the feed controls what
    // executes.
    if (req.method === 'GET' && p === '/v1/desktop-kt/manifest') {
      const man = desktopLib.readManifest(DESKTOP_KT_DIR);
      if (!man) return sendErr(res, 404, 'no desktop-kt releases yet');
      return sendJson(res, 200, man);
    }
    if (req.method === 'GET' && (m = p.match(/^\/v1\/desktop-kt\/([^/]+)$/))) {
      return serveDesktopArtifact(DESKTOP_KT_DIR, decodeURIComponent(m[1]));
    }

    // --- attachments: a photo from the phone, landed where a chat can Read it
    //
    // Raw bytes rather than multipart, because the daemon has no multipart parser
    // and one image needs none: the body IS the file, Content-Type names its kind,
    // and the server chooses the filename — so nothing the phone sends can steer
    // where this writes.
    if (req.method === 'POST' && p === '/v1/uploads') {
      const mime = String(req.headers['content-type'] || '');
      const name = String(u.searchParams.get('name') || '');
      // Never refused for its type: see lib/uploads. A router backup is not
      // Readable but IS inspectable, and blocking it blocked the owner.
      const ext = uploadExtFor(mime, name);

      fs.mkdirSync(UPLOADS_DIR, { recursive: true });
      pruneUploads();
      const file = path.join(UPLOADS_DIR, `up-${Date.now()}-${crypto.randomBytes(3).toString('hex')}.${ext}`);

      // STREAMED to disk rather than buffered. Backups are tens of megabytes and
      // the old path concatenated the whole body in memory first — a 100MB
      // upload meant 100MB of heap in a daemon that otherwise sits at ~130MB
      // RSS. The cap is enforced as bytes arrive, so an over-sized upload is cut
      // off early instead of being fully received and then rejected.
      let bytes = 0;
      let failed = null;
      try {
        await new Promise((resolve, reject) => {
          const out = fs.createWriteStream(file, { mode: 0o600 });
          const stop = (err) => { failed = err; try { req.destroy(); } catch { } out.destroy(); reject(err); };
          req.on('data', (chunk) => {
            bytes += chunk.length;
            if (bytes > UPLOAD_MAX_BYTES) return stop(new Error('too large'));
            if (!out.write(chunk)) req.pause();
          });
          out.on('drain', () => req.resume());
          req.on('error', stop);
          out.on('error', stop);
          req.on('end', () => out.end());
          out.on('close', () => (failed ? undefined : resolve()));
        });
      } catch {
        try { fs.unlinkSync(file); } catch { }
        const mb = Math.floor(UPLOAD_MAX_BYTES / 1024 / 1024);
        log(`uploads: ${name || 'unnamed'} aborted after ${bytes} bytes`);
        return sendErr(res, 413, `that file is too large (max ${mb}MB)`);
      }
      if (!bytes) {
        try { fs.unlinkSync(file); } catch { }
        return sendErr(res, 400, 'empty body');
      }
      const readable = isReadable(ext);
      log(`uploads: ${path.basename(file)} (${bytes} bytes, ${readable ? 'readable' : 'binary'})`);
      // `readable` travels so the app can phrase the message correctly: telling
      // Claude to Read a binary is how the original refusal justified itself, and
      // saying "inspect it with a shell" instead removes the reason to refuse.
      return sendJson(res, 200, { ok: true, path: file, bytes, ext, readable });
    }

    if (req.method === 'GET' && p === '/v1/chats') return sendJson(res, 200, { chats: listChats() });

    if (req.method === 'POST' && p === '/v1/chats') {
      const body = JSON.parse(await readBody(req) || '{}');
      const mode = body.mode === 'act' ? 'act' : 'ask';
      const now = Math.floor(Date.now() / 1000);
      const meta = {
        id: crypto.randomUUID(),
        title: (typeof body.title === 'string' && body.title.trim().slice(0, 80)) || null,
        mode,
        model: validModel(body.model),
        effort: validEffort(body.effort),
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
          pending: (meta.pending || []).length,
          messages: loadMsgs(id),
          // partial text of an in-flight turn so a cold open shows progress
          partialText: run_ ? run_.assistantText : null,
          // WHERE that partial text ends in the event stream. The client seeds its
          // streaming bubble from partialText and then subscribes; without a
          // position it had to subscribe from 0, which replays the very deltas the
          // seed already contains and rendered the answer TWICE. Read in the same
          // synchronous handler as assistantText, so the pair cannot disagree.
          seq: run_ ? run_.seq : null,
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
        // Busy: hold it and deliver when this run ends, rather than refusing.
        // A headless run cannot be fed mid-flight, and a dead end here would be
        // the one place the app behaves worse than typing into the session.
        // A run being cancelled is NOT a run that will deliver a queue. It stays in
        // activeRuns until 'close' (up to the 5s SIGKILL fallback), and the close
        // handler drops the queue on purpose so that stopping does not immediately
        // start the next thing. A message sent in that window was therefore
        // accepted with a 202 saying "queued" and then destroyed without a word —
        // which is worse than being told to wait.
        const cancelling = activeRuns.get(id);
        if (cancelling && cancelling.cancelled) {
          return sendErr(res, 409, 'this chat is stopping — send again in a moment');
        }
        if (activeRuns.has(id)) {
          // Through updateMeta, NOT saveMeta(meta): `meta` was loaded before the
          // readBody await above, and writing that whole snapshot back clobbers
          // anything the run wrote meanwhile. Measured consequences of the
          // snapshot version: two quick follow-ups both load pending=[], the
          // second saves over the first and a message the caller was told was
          // queued (202) silently vanishes; and a send straddling the run's close
          // resurrects an already-drained message, answering it twice.
          // pushPending mutates only on success, so a rejected queue leaves the
          // reloaded meta untouched.
          let q;
          if (!updateMeta(id, (fresh) => { q = pushPending(fresh, text, Math.floor(Date.now() / 1000)); })) {
            return sendErr(res, 404, 'no such chat');
          }
          if (!q.ok) return sendErr(res, q.code, q.error);
          return sendJson(res, 202, { ok: true, queued: true, position: q.position });
        }
        const started = startRun(meta, text);
        if (started.error) return sendErr(res, started.code, started.error);
        // Auto-title from the first message, through updateMeta.
        //
        // This was the LAST stale-snapshot writer in this route, and it was found
        // by its damage rather than by reading: `meta` is loaded before the
        // readBody await AND before startRun, which writes to disk through
        // updateMeta — so saving this whole object put the pre-run meta back,
        // silently erasing the in-flight marker startRun had just recorded. The
        // interrupted-run test failed on a missing marker and this is why.
        if (!meta.title) {
          const title = humanizeUserText(text).slice(0, 60);
          meta.title = title;
          updateMeta(id, (m) => { if (!m.title) m.title = title; });
        }
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
        // Same backwards read as the session route: a long chat has history above
        // its tail and this is how a reader asks for it.
        const untilParam = u.searchParams.get('until');
        const untilNum = untilParam == null ? null : Number(untilParam);
        if (untilNum !== null && !Number.isFinite(untilNum)) return sendErr(res, 400, 'until must be a number');
        const t = readTranscript(file, {
          offset: offsetNum,
          until: untilNum,
          limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
        });
        // A headless run records its prompt as an enqueue with no matching
        // removal, so the reader's "queued" marker sticks to messages that were
        // in fact delivered. For a chat the daemon is authoritative: anything in
        // the transcript was delivered (it only writes a prompt when it starts a
        // run), and anything genuinely waiting is in meta.pending.
        const delivered = t.events.map((e) => (e.queued ? { ...e, queued: false } : e));
        const events = delivered.concat(queuedEvents(meta, delivered.length));
        return sendJson(res, 200, {
          ...t,
          events,
          modelDisplay: formatModel(t.model),
          running: meta.running,
          mode: meta.mode,
          pending: (meta.pending || []).length,
        });
      }

      if (req.method === 'GET' && sub === '/suggestions') {
        // Mid-run suggestions would guess at a reply still being written.
        if (meta.running || activeRuns.has(id)) return sendJson(res, 200, { suggestions: [], reason: 'running' });
        if (!meta.claudeSessionId) return sendJson(res, 200, { suggestions: [], reason: 'no transcript' });
        const file = findTranscriptFile(meta.claudeSessionId);
        if (!file) return sendJson(res, 200, { suggestions: [], reason: 'no transcript' });
        return sendJson(res, 200, await suggestionsFor(meta.claudeSessionId, file));
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
        // Where the client is resuming from, and how much that costs it. `since=0`
        // means a full replay of the buffer, which is both the expensive case and the
        // one that doubled the answer on screen before chat meta carried a position —
        // so it is worth being able to see which one a client is asking for.
        const behind = run_.seq - since;
        log(`chat ${id}: stream attach since=${since} (replaying ${behind > 0 ? behind : 0} of ${run_.seq})`);
        run_.subscribe(res, since);
        return;
      }
      if (req.method === 'POST' && sub === '/cancel') {
        const run_ = activeRuns.get(id);
        // Reloaded too: no await precedes this one, but the run's own writes are
        // concurrent with it, and the whole-snapshot write-back has the same
        // clobbering shape.
        updateMeta(id, (fresh) => { clearPending(fresh); });
        if (!run_) return sendErr(res, 409, 'no active run');
        run_.cancelled = true;
        try { run_.proc.kill('SIGTERM'); } catch { }
        setTimeout(() => { try { run_.proc.kill('SIGKILL'); } catch { } }, 5000).unref();
        return sendJson(res, 200, { ok: true });
      }
      if (req.method === 'PATCH' && sub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        // Applied to a RELOADED meta for the same reason as the queue branch: the
        // snapshot above predates the readBody await, and saving it back reverted
        // whatever the run recorded meanwhile. The worst case was specific and
        // silent — a mode toggle landing across the run's init event wrote
        // claudeSessionId back to null, so the next turn spawned without
        // --resume and the chat lost its entire conversation history.
        let changed = false;
        const updated = updateMeta(id, (fresh) => {
          if (typeof body.title === 'string' && body.title.trim()) {
            fresh.title = body.title.trim().slice(0, 80); changed = true;
          }
          // Model and effort apply to the NEXT turn; an in-flight run keeps what
          // it started with, since the flags are fixed at spawn.
          if ('model' in body) { fresh.model = validModel(body.model); changed = true; }
          if ('effort' in body) { fresh.effort = validEffort(body.effort); changed = true; }
          if ('mode' in body) { fresh.mode = body.mode === 'act' ? 'act' : 'ask'; changed = true; }
        });
        if (!updated) return sendErr(res, 404, 'no such chat');
        updated.running = activeRuns.has(id);
        return sendJson(res, 200, updated);
      }
    }

    return sendErr(res, 404, 'not found');
  } catch (e) {
    log('ERROR', req.method, p, e.message);
    // A body over the cap is the client's mistake, not ours, and it now reaches
    // them as a status instead of a reset socket.
    if (!res.headersSent && e.tooLarge) return sendErr(res, 413, 'request body too large');
    if (!res.headersSent) return sendErr(res, 500, e.message);
    try { res.end(); } catch { }
  }
});

// SSE heartbeat so half-open phone connections die fast instead of lingering.
// Also what makes the phone's 60s stream read timeout safe: silence longer than
// a few of these means the path is gone, not that Claude is thinking. Keep the
// interval well under that timeout.
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
  // A restart kills any run that was in flight, and delivery of a chat's queue is
  // triggered by that run closing — so without this, messages queued before a
  // restart would sit on disk unanswered forever.
  // Before the queue drain, so an interrupted run is recorded as interrupted
  // rather than being overwritten by the next run's bookkeeping.
  reconcileInterruptedRuns();
  deliverOrphanedQueues();
  if (loadAlertState().enabled) { startAlertWatcher(); log('alerts: watcher resumed'); }
  // Re-key any profile still stored under the old email-derived name, and clear
  // the duplicates that scheme produced.
  try {
    const { migrated, duplicates } = accounts.migrate();
    if (migrated || duplicates) {
      log(`accounts: migrated ${migrated}, removed ${duplicates} duplicate(s) left by email-keyed storage`);
    }
  } catch (e) { log('accounts: migration failed', e.message); }
  // Then fold the profiles that ROTATION produced — one login was filed afresh
  // every few hours — onto the account uuid, which does not rotate. Surplus
  // records are archived, not deleted.
  try {
    const c = accounts.consolidate();
    if (c.merged || c.archived) {
      log(`accounts: consolidated ${c.merged} login(s), archived ${c.archived} rotated profile(s)` +
        `${c.failed ? `, ${c.failed} group(s) failed` : ''}`);
    }
  } catch (e) { log('accounts: consolidation failed', e.message); }
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
