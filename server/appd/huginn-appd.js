#!/usr/bin/env node
// huginn-appd — HTTP/SSE backend for the Huginn Android app.
//
// Serves the phone a chat + session surface over the tailnet:
//   * headless chats: spawns `claude -p --output-format stream-json` in WORKDIR
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
  parseSpinner, parseStatusExtras, spinnerIsCompacting,
  extractLoginUrl, parseStatusLine, loginPaneState,
} = require('./lib/pane');
const { parseAskSidecar, fuseAskPrompt, degradedAskCard, parsePlanSidecar } = require('./lib/ask');
const { readTranscript, liveActivity } = require('./lib/transcript');
const { summarizeUsage } = require('./lib/usage');
const { normalizePlan } = require('./lib/plan');
const { AccountStore, fingerprint, sameAccount, normUuid } = require('./lib/accounts');
const { formatModel, discoverModels, parseModelId } = require('./lib/models');
const { pushPending, takePending, clearPending, drainPending, queuedEvents } = require('./lib/chatqueue');
const { digest } = require('./lib/watch');
const { decideAlerts, routeAlerts, telegramText, pruneSent, carryRunStarts } = require('./lib/alerts');
const clientsLib = require('./lib/clients');
const roundsLib = require('./lib/rounds');
const devicesLib = require('./lib/devices');
const { taskDirFor, parsePs, scanTasks, extractBgIds } = require('./lib/tasks');
const { agentsDirFor, listAgents } = require('./lib/agents');
const { suggestionContext, buildPrompt, parseSuggestions } = require('./lib/suggest');
const {
  decideSwitch, worstLimit, agedLimits, explain: explainSwitch,
  THRESHOLD: AUTOSWITCH_THRESHOLD,
} = require('./lib/autoswitch');
const pushLib = require('./lib/pushtokens');
const { trySender } = require('./lib/fcm');
const { createPending, stepSoftEnd } = require('./lib/softend');

const VERSION = '2.77.0';
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
const { uploadExtFor, isReadable, contentTypeForUpload, isImageUpload } = require('./lib/uploads');
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

// How long a NON-IMAGE upload is kept. Images are exempt entirely (see below):
// chat history renders them as thumbnails read back from here, and a photo
// vanishing after a week would silently turn a message's picture back into a
// "photo attached" placeholder — the owner's doctrine is manual deletion, not a
// timer. Env-tunable for a deployment that wants a cap.
const UPLOAD_KEEP_DAYS = Math.max(1, Number(process.env.HUGINN_APPD_UPLOAD_KEEP_DAYS) || 7);

/**
 * Drops non-image uploads old enough that no conversation is coming back for
 * them. IMAGES ARE NEVER PRUNED — they back the chat-history thumbnails and are
 * small transcoded JPEGs; only manual deletion removes them. Run on each upload
 * rather than a timer: a dir that only grows when the feature is used only needs
 * sweeping then.
 */
function pruneUploads(maxAgeMs = UPLOAD_KEEP_DAYS * 24 * 60 * 60 * 1000) {
  let names = [];
  try { names = fs.readdirSync(UPLOADS_DIR); } catch { return; }
  const cutoff = Date.now() - maxAgeMs;
  for (const n of names) {
    if (isImageUpload(n)) continue;                 // kept until manually deleted
    const f = path.join(UPLOADS_DIR, n);
    try { if (fs.statSync(f).mtimeMs < cutoff) fs.unlinkSync(f); } catch { /* raced; fine */ }
  }
}
const TOKEN_FILE = process.env.HUGINN_APPD_TOKEN_FILE || '/etc/huginn-appd/token';
// A test knob only in production (the default is fixed): the route tests point it
// at a scratch dir so they never write state files into the live daemon's
// watched directory on the same host.
const STATE_DIR = process.env.HUGINN_APPD_STATE_DIR || '/run/huginn-claude-state';
const PERSONA_FILE = '/usr/local/share/huginn-cli/persona.md';
const WORKDIR = process.env.HUGINN_APPD_WORKDIR || process.env.HOME || '/root';
// Optional companion memory node ("Muninn") — feeds the /v1/status mempalace
// field. Empty/default on a generic host (the probe reports 'unconfigured');
// a deployment that has one sets these via a systemd drop-in.
const MEMPALACE_HOST = process.env.HUGINN_APPD_MEMPALACE_HOST || '';
// The marker is interpolated into the ssh remote command, so it is validated to
// a plain path charset here: the env is root-set (systemd drop-in), but a value
// that COULD carry shell syntax should not exist at all (CodeQL flagged the
// flow). A bad value disables the probe rather than reaching a shell.
const MEMPALACE_MARKER = (() => {
  const m = process.env.HUGINN_APPD_MEMPALACE_MARKER || '~/.mempalace/REBUILD_IN_PROGRESS';
  return /^[A-Za-z0-9_.~/-]+$/.test(m) ? m : '';
})();
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
  //
  // Skill is granted to BOTH: a skill is markdown instructions, not a capability —
  // invoking one cannot exceed the tools already granted, and ask's deny list still
  // holds. Without it the host's 23 project skills are invisible to phone and
  // desktop chats, which was the state until 2026-08-14. They load only because
  // HUGINN_APPD_WORKDIR points at the project; skills are cwd-scoped.
  ask: 'Skill mcp__mempalace WebFetch WebSearch',
  act: 'Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace',
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
  // String ops, not a regex: /^Bearer\s+(.+)$/ backtracked polynomially on a
  // hostile many-spaces header, and this check runs PRE-auth on every request —
  // exactly where a cheap DoS must not live (CodeQL js/polynomial-redos).
  if (!h.startsWith('Bearer ')) return false;
  const presented = h.slice(7).trim();
  if (!presented) return false;
  const got = Buffer.from(presented);
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
 *
 * Decision, not coercion: absent, null or empty means "the host default", a
 * known id passes, and an unknown NON-EMPTY id is an error the route must send
 * back as a 400. The old validModel returned null for unknown ids, which meant
 * "host default" — so a typo'd or foreign model id silently changed which model
 * answered. Silently substituting an engine for the one somebody named is the
 * failure class this whole file is built to refuse; do not reintroduce a
 * silent-null validator here.
 */
function modelDecision(v) {
  if (v === undefined || v === null) return { model: null };
  if (typeof v !== 'string') {
    return { error: 'model must be a string id from /v1/models, or omitted for the default' };
  }
  const s = v.trim().toLowerCase();
  if (s === '') return { model: null };
  if (!/^[a-z0-9-]{2,60}$/.test(s)) {
    return { error: `${JSON.stringify(String(v)).slice(0, 40)} is not a model id (2-60 chars of a-z, 0-9, dash)` };
  }
  if (MODEL_ALIASES.has(s) || parseModelId(s)) return { model: s };
  return { error: `unknown model ${JSON.stringify(s)}: use an id from /v1/models, or omit it for the default` };
}
/**
 * The local-model family: composite ids `local-<llmSlug>-<modelSlug>`, where
 * the llmSlug was minted by THIS daemon at the device's generate enrolment.
 * Resolution keys on that daemon-minted slug — the device-declared model list
 * gates only which rows exist, and the shim's own strict mapping is the final
 * fence. Picking a local row IS the host choice.
 */
const isLocalFamily = (id) => typeof id === 'string' && id.startsWith('local-');

function resolveLocalModel(rawId) {
  const modelId = String(rawId || '').trim().toLowerCase();
  for (const [devId, d] of Object.entries(deviceState.devices || {})) {
    if (d.scope !== 'generate' || !d.llmSlug) continue;
    const prefix = `local-${d.llmSlug}-`;
    if (!modelId.startsWith(prefix)) continue;
    const slug = modelId.slice(prefix.length);
    if ((d.models || []).some((x) => x.slug === slug)) {
      return { deviceId: devId, device: d, slug, id: modelId };
    }
    return { error: `${d.name} does not serve "${slug}" — it advertises: ${(d.models || []).map((x) => x.slug).join(', ') || '(nothing)'}` };
  }
  return { error: 'no enrolled machine serves this model — its machine may have been unenrolled' };
}

/** Same matrix as modelDecision: absent or empty clears, unknown non-empty refuses. */
function effortDecision(v) {
  if (v === undefined || v === null) return { effort: null };
  if (typeof v !== 'string') {
    return { error: 'effort must be one of low, medium, high, xhigh, max, or omitted for the default' };
  }
  const s = v.trim().toLowerCase();
  if (s === '') return { effort: null };
  if (EFFORT_LEVELS.has(s)) return { effort: s };
  return { error: `unknown effort ${JSON.stringify(s.slice(0, 20))}: one of low, medium, high, xhigh, max` };
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
      return ofThisIncarnation(name, {
        state: o.state || null,
        sessionId: o.sessionId || null,
        transcript: o.transcript || null,
        cwd: o.cwd || null,
        stateSince: o.ts || mtime,
      });
    } catch { /* fall through to the bare-word path */ }
  }
  return ofThisIncarnation(name,
    { state: raw, sessionId: null, transcript: null, cwd: null, stateSince: mtime });
}

/**
 * When each live tmux session was created, learned from the tmux calls the routes
 * already make rather than from an extra one.
 *
 * The state file is keyed by session NAME, and a name outlives the session that
 * owned it: Claude's SessionEnd hook is what removes the file, and that hook never
 * fires on a kill (see hardEndSession). Measured on the author's host, 24 state
 * files existed for 5 live sessions — the oldest a month dead. Reuse one of those
 * names and every reader keyed on the name alone is handed the CORPSE: the app's
 * conversation tab rendered a session that had ended weeks earlier while the
 * screen tab, which scrapes the live pane and cannot lie, showed the real one.
 *
 * `session_created` separates them exactly. Anything written before the session
 * that currently holds the name was born belongs to a previous incarnation.
 */
const sessionBorn = new Map();   // name -> epoch seconds

function rememberBorn(name, created) {
  const n = Number(created);
  if (Number.isFinite(n) && n > 0) sessionBorn.set(name, n);
}

/**
 * Drops state belonging to a PREVIOUS session of this name; passes everything
 * else through untouched.
 *
 * Deliberately permissive when the birth time is unknown: a name we have not
 * listed or probed yet keeps its state rather than being blanked on a guess.
 * Every route that reads state gates on sessionExists() first, and that call
 * records the birth time, so the unknown case is the cold start and little else.
 * The hook always writes AFTER tmux has created the session, so a live session's
 * own state can never look older than its birth — `<` is strict for the case
 * where both land in the same second.
 */
function ofThisIncarnation(name, st) {
  if (!st) return null;
  const born = sessionBorn.get(name);
  if (!born || !st.stateSince) return st;
  return st.stateSince < born ? null : st;
}

/**
 * Every per-name file the hook may have left behind. Used both when ending a
 * session and when creating one, because those are the two moments a name changes
 * hands — and the create side is what closes the window between a new session
 * starting and its first hook firing.
 */
function clearSessionState(name) {
  for (const f of [
    path.join(STATE_DIR, name),
    path.join(STATE_DIR, 'ask', name),
    path.join(STATE_DIR, 'plan', name),
    path.join(STATE_DIR, 'compacting', name),
  ]) {
    try { fs.unlinkSync(f); } catch { /* already gone */ }
  }
  sessionBorn.delete(name);
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
  const seen = new Set();
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created, attached, activity, windows, w, h, wsize, sessActivity, panePid] = line.split('\t');
    // Before the state read below, which needs it to tell this session's state
    // from that of a dead session that had the same name.
    rememberBorn(name, created);
    seen.add(name);
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
      // A soft end is pending: clients can badge the row as winding down. NOT in
      // the watch digest (see lib/watch) — a winding-down session must not wake
      // parked phones.
      softEnding: softEnds.has(name),
      // Context-window pressure (filled from the pane on a preview list) and
      // whether this session is compacting (cheap marker check, so it works even
      // on the quick non-preview list).
      contextPercent: null,
      compacting: isCompacting(name),
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

  // A successful listing is the complete set of live sessions, so anything else
  // in the map has ended. Pruning matters because a NEW session of a pruned name
  // must be born-stamped afresh rather than inheriting its predecessor's stamp.
  for (const name of [...sessionBorn.keys()]) if (!seen.has(name)) sessionBorn.delete(name);

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
        // Context-window pressure for the list's per-row meter, and whether this
        // session is compacting right now.
        r.contextPercent = st.contextPercent;
        r.compacting = spinnerIsCompacting(parseSpinner(paneLines)) || isCompacting(r.name);
      }
    }));
  }

  rows.sort((a, b) => b.activityAt - a.activityAt);
  return rows;
}

/**
 * The scope the tmux server should live in, so that a session's lifetime is not
 * tied to this daemon's.
 */
const TMUX_SCOPE = 'huginn-tmux';

/**
 * Make sure the tmux server exists OUTSIDE this daemon's cgroup before a session
 * is created in it.
 *
 * `tmux new-session` starts the server if none is running, and the server
 * daemonises from that call — inheriting whatever cgroup and mount namespace the
 * caller had. When the caller is this daemon, two things follow that nobody asked
 * for, and both were measured on the author's host (2026-08-23):
 *
 *   * `systemctl restart huginn-appd` with the default KillMode=control-group
 *     SIGTERMs the whole cgroup, so a routine deploy killed the tmux server and
 *     every Claude Code session on the box — and reported success. A KillMode
 *     drop-in is the floor under this; putting the server somewhere else is the
 *     actual fix.
 *   * the sessions inherit ProtectSystem=strict, so /opt is READ-ONLY inside them
 *     while the host itself is perfectly writable — an EROFS that costs an hour
 *     every time somebody meets it for the first time.
 *
 * A transient scope answers both: the server owns its own cgroup and gets the
 * host's real namespace, and every session the server forks afterwards belongs to
 * the SERVER, not to us.
 *
 * A running server is left exactly where it is. Cgroup membership is per-process,
 * so moving a live server would strand every session's processes behind it — the
 * migration has to happen when there is nothing to migrate.
 *
 * Best effort by design: if systemd-run is unavailable or refuses, the old
 * inherited-server behaviour still works. Degraded is better than no sessions.
 */
async function ensureTmuxServerScope() {
  const probe = await run('tmux', ['ls']);
  // Only "no server running" means there is nothing there. Any other failure is a
  // failure to OBSERVE, and starting a second server on a bad read is worse than
  // doing nothing.
  if (!probe.err || !/no server running/i.test(probe.stderr || '')) return;

  const r = await run('systemd-run',
    ['--scope', '--quiet', '--collect', `--unit=${TMUX_SCOPE}`, 'tmux', 'start-server']);
  if (r.err) {
    log(`tmux: could not start the server in its own scope (${(r.stderr || r.err.message || '').trim().slice(0, 120)}); it will inherit this daemon's`);
    return;
  }
  log(`tmux: server started in ${TMUX_SCOPE}.scope, independent of this daemon`);
}

async function sessionExists(name) {
  // display-message, not has-session: the same single call answers "does it
  // exist" and "when was it created", and every state read downstream needs the
  // second answer to know whether the state belongs to THIS session.
  //
  // TWO tmux traps here, both measured, both silent:
  //
  //   * the target needs the TRAILING COLON. `-t '=name'` resolves as a session
  //     target with no client to expand formats against, and tmux answers with an
  //     EMPTY string and exit 0 — every format field blank, no error anywhere.
  //   * exit status cannot answer existence. Unlike has-session, display-message
  //     exits 0 for a session that does not exist, again returning blanks. Trusted
  //     naively it reports every name as live, which turns the create route's
  //     "already exists" check into a permanent 409.
  //
  // So the returned NAME is the answer: tmux echoing back the session it actually
  // resolved is the only proof the target hit something, and it costs no extra call.
  const { err, stdout } = await run('tmux',
    ['display-message', '-p', '-t', `=${name}:`, '#{session_name}\t#{session_created}']);
  const [found, created] = (err ? '' : (stdout || '')).trim().split('\t');
  if (found !== name) { sessionBorn.delete(name); return false; }
  rememberBorn(name, created);
  return true;
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

// ---- soft end / hard end ---------------------------------------------------
//
// A HARD end kills the tmux session outright. A SOFT end types a wrap-up phrase
// into the pane ("finish, commit, prepare to end") so Claude can land its work,
// and — when auto-end is on — the session is killed once it settles. The auto
// watcher lives in lib/softend.js (pure); this is the I/O around it.

// The 150ms beat between typed text and the Enter that submits it: text+Enter in
// one burst can read to the TUI as a paste that INSERTS the newline instead of
// submitting. Named once so /keys and the soft-end share the same value.
const SUBMIT_BEAT_MS = 150;

const SOFT_END_PHRASE = process.env.HUGINN_APPD_SOFT_END_PHRASE ||
  'Finish outstanding items, commit your work, and prepare to end the session.';
// Auto-end is ON by default (owner decision 2026-08-10): after the phrase lands,
// the session ends on its own the next time it settles. A deployment that wants
// "phrase only, I end it myself" sets HUGINN_APPD_SOFT_END_AUTO=0 in a drop-in.
const SOFT_END_AUTO = process.env.HUGINN_APPD_SOFT_END_AUTO !== '0';

const softEnds = new Map(); // session name -> pending record (lib/softend)

/** Type a line into a pane and submit it, with the anti-paste beat before Enter. */
async function sendLineToPane(name, text) {
  const a = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', text]);
  if (a.err) return a;
  await sleep(SUBMIT_BEAT_MS);
  return run('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
}

/**
 * The one hard-end path. Kills the session AND cleans up what a bare
 * `tmux kill-session` used to leak: the /run state file (Claude's SessionEnd
 * hook never fires on a kill, so nothing else removes it) and the pane-size
 * lease. Used by the DELETE route and by the auto-end.
 */
async function hardEndSession(name) {
  const { err, stderr } = await run('tmux', ['kill-session', '-t', `=${name}`]);
  if (err) return { err, stderr };
  clearSessionState(name);
  await releaseSize(name).catch(() => { });
  softEnds.delete(name);
  return { err: null };
}

/** One pass over the pending soft ends; kills the ones that have settled. */
async function softEndTick() {
  if (!softEnds.size) return;
  const now = Date.now();
  for (const [name, pending] of [...softEnds]) {
    const st = readSessionState(name);
    const { pending: next, action } = stepSoftEnd(pending, st ? st.state : null, now);
    if (action === 'kill') {
      log(`soft-end: ${name} settled, ending`);
      await hardEndSession(name).catch((e) => log(`soft-end kill ${name} failed: ${e.message}`));
    } else if (action === 'cancel') {
      log(`soft-end: ${name} asked a question, auto-end cancelled`);
      softEnds.delete(name);
    } else if (action === 'expire') {
      log(`soft-end: ${name} never started a run, auto-end dropped`);
      softEnds.delete(name);
    } else {
      softEnds.set(name, next);
    }
  }
}

// A floor tick independent of the alert watcher: auto-end must not be coupled to
// whether alerts are enabled. The state-file fs.watch also calls softEndTick for
// sub-second response (wired where alertTick is).
setInterval(() => { softEndTick().catch(() => { }); }, 10_000).unref();

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

// A prompt sidecar the hook wrote (exact AskUserQuestion/ExitPlanMode input),
// under STATE_DIR/{ask,plan}/<name>. Absent, unreadable, or malformed -> null,
// which just drops to the pane-only path.
function readSidecar(kind, name) {
  try { return JSON.parse(fs.readFileSync(path.join(STATE_DIR, kind, name), 'utf8')); }
  catch { return null; }
}

/**
 * Is the session compacting? huginn-claude-title touches
 * STATE_DIR/compacting/<name> on PreCompact and removes it on PostCompact/Stop —
 * a reliable, poll-independent signal (the pane spinner only shows it while a
 * screen is being captured).
 *
 * Backstopped by the marker's mtime: if PostCompact somehow never fires (a
 * crash, a killed compaction), the marker would otherwise pin "Compacting…" on
 * forever. No real compaction runs for minutes, so a marker older than the TTL
 * is treated as stale. The live smoke on 2026-08-11 showed the marker still
 * present ~6s after /compact, which is why the honest signal needs this guard.
 */
const COMPACTING_TTL_MS = 5 * 60 * 1000;
function isCompacting(name) {
  try {
    const st = fs.statSync(path.join(STATE_DIR, 'compacting', name));
    return (Date.now() - st.mtimeMs) < COMPACTING_TTL_MS;
  } catch { return false; }
}

const SIDECAR_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * The one place "what question is on this pane" is decided, so /screen, /answer
 * and the alert enrichment can never disagree about a label or a fingerprint.
 *
 * Fuses the hook's exact question with the pane's live run when both are present
 * (correct, width-stable labels + the pane's caret); falls back to the pane
 * alone; and, when the hook says a question waits but the pane scrape cannot read
 * it (a wrap/preview/tab shape), returns a DEGRADED card so the client shows
 * something rather than nothing. Also surfaces a pending plan approval.
 *
 * Returns { prompt, ask, planPending } — prompt carries its own fingerprint.
 */
function promptFor(name, lines) {
  const panePrompt = detectPrompt(lines);
  const sidecar = parseAskSidecar(readSidecar('ask', name));
  let prompt = null;
  let ask = null;

  if (panePrompt && sidecar) {
    const fused = fuseAskPrompt(panePrompt, sidecar);
    if (fused) {
      if (fused.questionCount > 1) {
        // A MULTI-PART AskUserQuestion (several questions in one call, answered
        // through the TUI's tab strip) CANNOT be answered by a single digit:
        // verified live 2026-08-11 that the digit-then-Enter path over-answers —
        // the digit selects+advances and the Enter confirms the NEXT question's
        // default too, so one button tap silently answers two questions and the
        // dialog skids past the card (the owner hit this: every tap 409'd as the
        // pane moved ahead). Until per-question stepping exists, serve it as a
        // NON-answerable card that routes to the Screen tab rather than offering
        // buttons that misfire. A fingerprint is still attached so a shipped
        // client's tap reaches /answer, which returns 409 'undetected' and steers
        // to the Screen tab (rather than a dead "no fingerprint" message).
        const q0 = sidecar.questions[0];
        const synthetic = {
          question: q0.question,
          options: q0.options.map((o, i) => ({ number: i + 1, label: o.label })),
        };
        ask = { ...degradedAskCard(sidecar, promptFingerprint(synthetic)), multiPart: true };
      } else {
        prompt = { ...fused.prompt, fingerprint: promptFingerprint(fused.prompt) };
      }
    }
  }
  if (!prompt && !ask && panePrompt) {
    prompt = { ...panePrompt, fingerprint: promptFingerprint(panePrompt) };
  }
  if (!prompt && sidecar) {
    // The hook has a question but the pane run is unreadable. Offer it as a
    // degraded card only while the session is actually asking (state=attention)
    // and the sidecar is fresh, so a stale file cannot resurrect buttons.
    const st = readSessionState(name);
    const fresh = sidecar.ts && (Date.now() - sidecar.ts * 1000) < SIDECAR_TTL_MS;
    if (st && st.state === 'attention' && fresh) {
      // Fingerprint over the hook labels, exactly as fusion would compute it, so
      // that if the pane becomes readable between serve and answer the digit
      // still validates against the same fingerprint.
      const synthetic = {
        question: sidecar.questions[0].question,
        options: sidecar.questions[0].options.map((o, i) => ({ number: i + 1, label: o.label })),
      };
      ask = degradedAskCard(sidecar, promptFingerprint(synthetic));
    }
  }

  const planPending = parsePlanSidecar(readSidecar('plan', name));
  return { prompt, ask, planPending };
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
    // promptFor fuses the hook sidecar in (correct, width-stable labels) and adds
    // the degraded card / pending-plan fields.
    ...(() => {
      const pf = promptFor(name, lines);
      return { prompt: pf.prompt, ask: pf.ask, planPending: pf.planPending };
    })(),
    // The moment-to-moment status ("Gallivanting… · 3m 15s") exists only here:
    // the transcript is silent until whole blocks complete, which left the
    // conversation looking dead right after a message was sent.
    spinner: parseSpinner(lines),
    // Compaction shows as its own live status ("Compacting conversation…"); flag
    // it so the conversation view can say so distinctly rather than as generic
    // spinner text. The hook-driven marker (below, via readSessionState) is the
    // reliable poll-independent signal; this is the live-text fallback.
    compacting: spinnerIsCompacting(parseSpinner(lines)) || isCompacting(name),
    // The TUI's own progress rows, split by lifetime: durable rows (workflow
    // phases, boards) render as-is; the transient per-tool row ("Running 2 shell
    // commands…") flaps in and out at tool speed, so the app updates it in place
    // instead of letting the strip grow and shrink on repeat.
    ...(() => {
      const px = parseStatusExtras(lines);
      return { statusLines: px.durable, transientLine: px.transient };
    })(),
    // The pane is the only CURRENT source for these; the transcript lags a turn.
    // contextPercent is the huginn-statusline `ctx N%` (context-window pressure),
    // which the old regex swallowed into `branch` — now parsed out for the meter.
    ...(() => {
      const st = parseStatusLine(lines);
      return {
        liveModel: st.model, liveMode: st.mode, liveBranch: st.branch,
        contextPercent: st.contextPercent,
      };
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
    // A Round's run reports itself, through its Round. Leaving it here would
    // announce every scheduled run TWICE — once as "a chat finished" from the
    // alert watcher and once as the report — and two notifications for one event
    // is how a reader learns to ignore the channel.
    if (m.roundId) continue;
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

/**
 * The name of the machine a chat runs on, or null for this host.
 *
 * Resolved here rather than in each client: a client that looked this up itself
 * would print a bare uuid for a device that has since been unenrolled, and the
 * whole point of the label is that a person can tell at a glance where something
 * is happening.
 */
function hostNameFor(host) {
  if (!host || host === 'local') return null;
  return ((deviceState.devices || {})[host] || {}).name || 'a removed device';
}

/**
 * A remote chat's conversation, built from what the device streamed back.
 *
 * THE BUG THIS FIXES: a chat's reader renders Claude's OWN transcript file, found
 * by session id under this host's ~/.claude/projects. A run that happened on
 * another machine wrote that file THERE, so the lookup found nothing and the
 * conversation rendered empty — no answer, and the user's own message gone too —
 * while the chat list row still showed the text, because that comes from meta.
 * A working feature that looked like it had swallowed your message.
 *
 * The daemon already holds everything it needs: every event the device posted was
 * fed through handleClaudeEvent and appended to messages.jsonl. So a remote chat
 * reads from there instead, in the same shape and honouring the same paging
 * contract — the reader pages by `offset` and appends what comes back, so
 * returning the whole conversation on every poll would duplicate it on screen.
 * Here the offset is a message INDEX rather than a byte position; the client
 * never inspects it, it only hands it back.
 *
 * Deliberately does NOT fall back to a local file for a remote chat. Session ids
 * are uuids and a collision is fantastically unlikely, but "show this machine's
 * transcript for a conversation that happened somewhere else" is the kind of
 * wrong that would be very hard to see and very bad to read.
 */
function transcriptFromMessages(meta, { offset = null, until = null, limit = 400 } = {}) {
  const msgs = loadMsgs(meta.id);
  const total = msgs.length;

  let start; let end;
  if (until != null) {
    end = Math.max(0, Math.min(until, total));
    start = Math.max(0, end - limit);
  } else if (offset != null) {
    start = Math.max(0, Math.min(offset, total));
    end = total;
  } else {
    end = total;
    start = Math.max(0, total - limit);
  }

  let seq = 0;
  const events = [];
  for (const m of msgs.slice(start, end)) {
    const ts = m.ts ?? null;
    if (m.type === 'user') {
      events.push({ seq: ++seq, kind: 'user', ts, sidechain: false, text: m.text || '' });
    } else if (m.type === 'assistant') {
      events.push({ seq: ++seq, kind: 'assistant', ts, sidechain: false, text: m.text || '' });
    } else if (m.type === 'tool') {
      events.push({ seq: ++seq, kind: 'tool', ts, sidechain: false, name: m.name || '', input: m.input || '' });
    } else if (m.type === 'error') {
      // `system`, not `error`: the readers know six kinds and error is not one of
      // them, so an error event would render as nothing at all — which is exactly
      // the silence this whole function exists to remove.
      events.push({ seq: ++seq, kind: 'system', ts, sidechain: false, text: m.text || '' });
    }
    // `result` carries cost and turn counts, which the local reader does not emit
    // as an event either. Left out so both paths render the same conversation.
  }

  return {
    events,
    deliveredQueued: [],
    nextOffset: end,
    windowStart: start,
    truncated: start > 0,
    title: meta.title ?? null,
    permissionMode: null,
    model: meta.model ?? null,
    gitBranch: null,
    cwd: null,
    effort: meta.effort ?? null,
    lastActivityTs: total ? (msgs[total - 1].ts ?? null) : null,
  };
}

function listChats() {
  let ids = [];
  try { ids = fs.readdirSync(CHATS_DIR); } catch { /* empty */ }
  const metas = [];
  for (const id of ids) {
    const m = loadMeta(id);
    // Round runs live under their Round, not in the conversation list (see
    // chatStates). They are still openable by id, which is how a report's
    // "show me the run" link works.
    if (m && !m.roundId) {
      m.running = activeRuns.has(id);
      m.hostName = hostNameFor(m.host);
      // A count, not the texts: the list needs "2 waiting", not the messages.
      m.pending = Array.isArray(m.pending) ? m.pending.length : 0;
      // Claude Code generates a real title for its own sessions; it reads far
      // better than the truncated first message this daemon falls back to.
      // Not for a remote chat: that session's file is on the other machine, so
      // this can only find nothing — or, once, something that is not it.
      if (m.claudeSessionId && (!m.host || m.host === 'local')) {
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
      // ⚠ GUARDED, as the device path at /work/:id/events already was. This is a
      // stdout 'data' handler: a throw here is an unhandled exception and TAKES
      // THE WHOLE DAEMON DOWN — every tmux session's reader, every other chat,
      // every Round. handleClaudeEvent appends to the transcript, so a disk that
      // is full, read-only, or holding a file where a directory should be turned
      // one broken chat into a dead process. Found by trying to test the run-slot
      // leak below it: the injected write failure killed the daemon before the
      // slot could even be observed.
      try { handleClaudeEvent(meta, run_, ev); }
      catch (e) { log(`chat ${chatId} could not record an event: ${e.message}`); }
    }
  });
  let errBuf = '';
  proc.stderr.on('data', (c) => { errBuf = (errBuf + c.toString('utf8')).slice(-4000); });

  proc.on('close', (code) => {
    clearTimeout(killer);
    // Same reasoning: a 'close' handler that throws is unhandled. settleRun now
    // releases the run slot in a finally, so the worst case here is a chat whose
    // ending was not written — not a daemon that stops existing.
    try {
      settleRun(run_, {
        exitCode: code,
        failureText: `claude exited ${code}${errBuf ? `: ${errBuf.slice(-500)}` : ''}`,
      });
    } catch (e) {
      log(`chat ${chatId} could not settle cleanly: ${e.message}`);
      try { run_.finish(); } catch { /* already finished */ }
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
      // ⚠ A SESSION ID IS A UUID. This value arrives inside an event a DEVICE
      // posts, is stored verbatim, and rides back to that machine as the value of
      // `--resume`. `--resume` takes its value optionally, so a string beginning
      // with `--` does not become the id — it becomes the NEXT FLAG. An
      // unvalidated string in flag position is authority travelling inside a
      // request, and a work item is defined as carrying a request and no
      // authority. Anything that is not a uuid is simply not a session.
      if (ev.subtype === 'init' && isSessionId(ev.session_id) && !meta.claudeSessionId) {
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
        // A local-family run never records spend: the shim reports 0, but the
        // gate is on ENGINE IDENTITY, not on the number — a lying frame must
        // not be able to bill a free run to the subscription's ledger.
        costUsd: isLocalFamily(meta.model) ? null : (ev.total_cost_usd ?? null),
        turns: ev.num_turns ?? null,
        ts,
      };
      // What the run said went wrong, KEPT. A usage-limit or credit failure
      // arrives as is_error with the reason in `result` and no assistant text at
      // all; without this the Round could only report "no output", which is false
      // and unactionable when the truth was sitting in the event.
      if (ev.is_error && typeof ev.result === 'string') rec.errorText = ev.result.slice(0, 500);
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
/**
 * Everything that must happen when a run ends — WHEREVER it ran.
 *
 * Extracted from the spawn's own close handler when runs became able to happen on
 * another machine. It is not a tidy-up: three separately-learned lessons live in
 * here and none of them are guessable from outside, so a second copy for the
 * remote path would have drifted from this one within a release.
 *
 * @param failureText what to record when the run produced no result event. The
 *   caller knows why it ended — an exit code and stderr locally, a device's own
 *   report or its silence remotely — and only the caller can say it in a way that
 *   means anything to a reader.
 */
/**
 * What the transcript says about messages that were queued and then dropped.
 *
 * The TEXT is quoted back, not just the count: it is the only remaining copy —
 * the sender's client cleared its composer when the 202 came back — so a bare
 * "1 message was dropped" would be an apology for losing something without
 * saying what.
 */
function droppedNote(dropped, why) {
  const n = dropped.length;
  const head = n === 1
    ? `A message you sent was not delivered, because ${why}.`
    : `${n} messages you sent were not delivered, because ${why}.`;
  const body = dropped
    .map((p) => `  “${roundsLib.oneLine(p.text, 300)}”`)
    .join('\n');
  return `${head}\n\n${body}\n\nNothing was sent to Claude. Send it again if you still want it.`;
}

function settleRun(run_, { exitCode = null, failureText = null } = {}) {
  const chatId = run_.chatId;
  const ts = Math.floor(Date.now() / 1000);

  // ⚠ THE SLOT IS RELEASED WHATEVER HAPPENS. Everything above `run_.finish()`
  // touches the disk, and `appendMsg` on a full or read-only disk throws — which
  // used to abandon the run in `activeRuns` forever. That set IS the local run
  // pool (MAX_CONCURRENT_RUNS = 3), so each leak permanently cost one slot, and
  // after three every local chat and every `local` Round got 429 "too many
  // concurrent runs" with nothing actually running, until somebody restarted the
  // daemon. A disk that is full is a bad day; a daemon that never runs anything
  // again until it is restarted is a worse one.
  try {
    if (!run_.sawResult) {
      // Crashed / killed / cancelled with no result event — record what we know.
      const errText = run_.cancelled ? 'cancelled' : (failureText || 'the run ended without a result');
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
    run_.emit('done', { exitCode });
  } finally {
    run_.finish();
  }
  log(`chat ${chatId} run finished (exit ${exitCode})`);

  const fresh = loadMeta(chatId);
  if (fresh) {
    // A Round's run ends when its chat's run ends; the report is whatever it
    // left in the transcript.
    if (fresh.roundId) {
      try { finishRoundRun(fresh, run_.cancelled ? 'cancelled' : null); }
      catch (e) { log(`round run ${chatId} could not be recorded: ${e.message}`); }
      // ⚠ RE-READ. finishRoundRun just wrote the seal, the verdict and endedAt;
      // `fresh` is the snapshot from BEFORE that. Saving it back erased all of
      // them, and a chat that reopens is a chat where the owner's next question
      // gets filed as the Round's official report — verbatim the failure
      // reconcileInterruptedRuns' comment says was already fixed.
      const sealed = loadMeta(chatId) || fresh;
      // And then it is OVER. Draining a queue into a sealed run would reopen the
      // very thing that just ended, so anything waiting is dropped here instead.
      const waiting = drainPending(sealed);
      if (waiting.length) {
        saveMeta(sealed);
        // ⚠ SAID IN THE CHAT, not only in a log nobody reads. The sender got a
        // 202 {queued:true, position:1}; the message then never appeared in the
        // transcript, nothing said it had been dropped, and the retry hit a 409
        // off the sealed run — so it was simply gone. The chat route already
        // fixed exactly this for the cancel window and wrote down why it was
        // unacceptable: "worse than being told to wait". Round runs then did the
        // same thing.
        appendMsg(chatId, { type: 'system', text: droppedNote(waiting, 'this round finished'), ts });
        log(`round run ${chatId} dropped ${waiting.length} queued message(s): the run is closed`);
      }
      return;
    }
    if (run_.cancelled) {
      // Cancel means stop. Respawning from the queue would make the stop
      // button start the very thing it was pressed to end.
      const dropped = drainPending(fresh);
      if (dropped.length) {
        saveMeta(fresh);
        appendMsg(chatId, { type: 'system', text: droppedNote(dropped, 'the run was cancelled'), ts });
        log(`chat ${chatId} dropped ${dropped.length} queued message(s) on cancel`);
      }
    } else {
      const next = takePending(fresh);
      if (next) {
        saveMeta(fresh);
        log(`chat ${chatId} delivering queued message(s)`);
        startQueuedRun(fresh, next);
      }
    }
  }
}

/**
 * Devices this daemon has heard ASK FOR WORK since it started.
 *
 * ⚠ IN-MEMORY ON PURPOSE, and the emptiness after a restart is the signal.
 * `remoteRuns` is in-memory too, so `deploy.sh` — a routine operation here —
 * wipes it. The daemon then correctly writes "interrupted: huginn-appd restarted
 * while this was running" into the old chat, but the far machine is still
 * running that claude and is SINGLE-JOB: it will not poll again until its
 * orphaned child exits, which for a real run is minutes to hours.
 *
 * Meanwhile the daemon reported that device `online:true, running:false,
 * queued:0`, accepted the next job with a 202, and the job sat undelivered until
 * it was declared "no word for 5 minutes". Reachable is not the same as free,
 * and after a restart the honest answer is that we do not know which.
 */
const polledSince = new Map();

// ------------------------------------------------------------------ devices
//
// Another machine that can run a chat in ITS context. See lib/devices for the
// scope model and why the daemon sends a request rather than a permission.
//
// The transport is the device's choice of moment, not ours: it long-polls for
// work and POSTs results back, so it needs no inbound port, no static address and
// no hole in anyone's firewall. A laptop on hotel wi-fi works exactly as well as
// the desktop in the next room, which is the whole reason this is pull and not a
// push from here.

const DEVICES_FILE = path.join(DATA_DIR, 'devices.json');

let deviceState = (() => {
  try {
    const o = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
    // A file that parses is not a file that is USABLE: an empty object, or one
    // hand-edited into a different shape, would make the first registration throw
    // inside the request handler instead of starting from empty.
    if (o && typeof o === 'object' && o.devices && typeof o.devices === 'object') return o;
  } catch { /* absent or unreadable */ }
  return devicesLib.emptyState();
})();

function saveDevices() {
  try { fs.writeFileSync(DEVICES_FILE, JSON.stringify(deviceState, null, 2)); }
  catch (e) { log(`devices: could not persist (${e.message})`); }
}

/** Work handed out but not yet finished, and the runs behind it. */
const deviceQueues = new Map();   // deviceId -> [workItem]
const deviceWaiters = new Map();  // deviceId -> [{ respond, timer }]
const remoteRuns = new Map();     // workId -> { run_, deviceId, chatId, lastHeard, startedAt }

const MAX_WORK_QUEUE = 5;
const WORK_WAIT_DEFAULT_S = 25;
const WORK_WAIT_MAX_S = 60;

/**
 * How long a started remote run may go without a word before it is declared
 * lost.
 *
 * A device posts events as they arrive, so silence is not "thinking" — a tool
 * call that takes four minutes still produces its start event immediately. The
 * failure this catches is the one with no other signal at all: the machine slept,
 * lost its network, or was shut down mid-run, and the chat would otherwise sit
 * `running` forever with nothing coming.
 */
const REMOTE_SILENCE_MS = 5 * 60 * 1000;

function queueWork(deviceId, item) {
  const q = deviceQueues.get(deviceId) || [];
  q.push(item);
  deviceQueues.set(deviceId, q);
  // A parked poll is woken rather than left to time out: the difference between
  // "starts now" and "starts in up to 25 seconds" is the difference between the
  // feature feeling remote and feeling broken.
  const waiters = deviceWaiters.get(deviceId) || [];
  const w = waiters.shift();
  deviceWaiters.set(deviceId, waiters);
  if (w) {
    clearTimeout(w.timer);
    const handing = q.shift() || null;
    // Put it BACK if the waiter could not take it. Its socket may have closed in
    // the window before its own close handler unparked it.
    if (handing && w.respond(handing) === false) q.unshift(handing);
    else if (!handing) w.respond(null);
    deviceQueues.set(deviceId, q);
  }
}

/**
 * Parks a poll until there is work or the wait ends.
 *
 * @returns a `drop` that UNPARKS it. Not optional: a device that hangs up
 * mid-poll leaves a response nobody can write to, and if queueWork later hands
 * that waiter an item the item is silently swallowed — a job that was created,
 * accepted, and then simply never ran. So the disconnect handler must be able to
 * take the waiter out of the running.
 */
function parkWaiter(deviceId, waitS, respond) {
  // `respond` returns whether it actually delivered. A socket that closed between
  // `answered = true` and the `close` handler running left a waiter that queueWork
  // would hand an item to and drop on the floor — the job created, accepted, and
  // never run, with the chat stuck `running` and the device showing nothing queued.
  const entry = { respond, timer: null };
  const drop = () => {
    clearTimeout(entry.timer);
    deviceWaiters.set(deviceId, (deviceWaiters.get(deviceId) || []).filter((x) => x !== entry));
  };
  entry.timer = setTimeout(() => { drop(); respond(null); },
    Math.max(1, Math.min(WORK_WAIT_MAX_S, waitS)) * 1000);
  const waiters = deviceWaiters.get(deviceId) || [];
  waiters.push(entry);
  deviceWaiters.set(deviceId, waiters);
  return drop;
}

/**
 * Takes a work item out of a device's queue.
 *
 * ⚠ A QUEUE ENTRY MUST NOT OUTLIVE ITS RUN. It did, and the consequences were the
 * worst in this feature: pressing Stop on an `act` job left the item sitting
 * there, so the machine was handed it on its next poll and ran it for real with
 * full grants — while the chat told the owner it had been cancelled. The same
 * entry survived `loseRemoteRun`, so a laptop that woke hours later executed a
 * dead job whose every result was then rejected 404.
 */
function withdrawWork(deviceId, workId) {
  const q = deviceQueues.get(deviceId);
  if (!q || !q.length) return false;
  const keep = q.filter((it) => it.id !== workId);
  if (keep.length === q.length) return false;
  deviceQueues.set(deviceId, keep);
  return true;
}

/** Runs this device currently owns. One at a time, so a machine is never flooded. */
function activeRunFor(deviceId) {
  for (const r of remoteRuns.values()) if (r.deviceId === deviceId) return r;
  return null;
}

/**
 * A run that happens on another machine.
 *
 * Deliberately does NOT consume MAX_CONCURRENT_RUNS: that limit exists because
 * each local run is a `claude -p` on THIS host, and a run on the owner's PC costs
 * this host a map entry. The bound that matters instead is one active run per
 * device, so a queue of Rounds cannot pile four simultaneous jobs onto one laptop.
 */
function startRemoteRun(meta, userText) {
  const chatId = meta.id;
  if (activeRuns.has(chatId)) return { error: 'chat already has an active run', code: 409 };

  const deviceId = meta.host;
  const device = (deviceState.devices || {})[deviceId];
  const now = Date.now();
  // The WORK ITEM's mode: a local-family chat rides as generate — the mode the
  // exclusive scope serves and the argv sheds tools/persona/effort for. The
  // chat-level wire never carries generate; this is where the daemon translates.
  const workMode = isLocalFamily(meta.model) ? 'generate' : meta.mode;
  const verdict = devicesLib.canRun(device, workMode, now);
  if (!verdict.ok) return { error: verdict.reason, code: 409 };
  if (activeRunFor(deviceId)) return { error: `${device.name} is already running something`, code: 409 };
  if ((deviceQueues.get(deviceId) || []).length >= MAX_WORK_QUEUE) {
    return { error: `${device.name} has too much work queued`, code: 429 };
  }

  const ts = Math.floor(now / 1000);
  appendMsg(chatId, { type: 'user', text: userText, ts });
  updateMeta(chatId, (m) => {
    m.updatedAt = ts;
    m.lastSnippet = humanizeUserText(userText).slice(0, 120);
    m.runStartedAt = ts;
  });

  const run_ = new Run(chatId);
  activeRuns.set(chatId, run_);
  const workId = crypto.randomUUID();
  run_.remote = { deviceId, workId };

  // No persona and no tool list. The device appends its own operating posture and
  // builds its own argv — see lib/devices. What travels is the request.
  const item = devicesLib.workItem({
    id: workId,
    chatId,
    prompt: userText,
    mode: workMode,
    model: meta.model,
    effort: meta.effort,
    resumeSessionId: meta.claudeSessionId,
    roundId: meta.roundId,
    now,
  });
  remoteRuns.set(workId, { run_, deviceId, chatId, lastHeard: now, startedAt: now });
  queueWork(deviceId, item);
  run_.emit('started', { chatId, ts, host: deviceId });
  log(`chat ${chatId} queued to device ${device.name} (work ${workId})`);
  return { run: run_ };
}

/** Ends a remote run that will never report again. */
function loseRemoteRun(workId, why) {
  const entry = remoteRuns.get(workId);
  if (!entry) return;
  remoteRuns.delete(workId);
  // Before the name lookup below, because the DELETE-device path has already
  // removed the record by the time it gets here and the queue would be orphaned.
  withdrawWork(entry.deviceId, workId);
  const name = hostNameFor(entry.deviceId) || ((deviceState.devices || {})[entry.deviceId] || {}).name || entry.deviceId;
  log(`chat ${entry.chatId} lost its run on ${name}: ${why}`);
  settleRun(entry.run_, { exitCode: null, failureText: `${name}: ${why}` });
}

/**
 * One pass over the runs devices owe us an answer for.
 *
 * Silence is the only failure a remote run can have that produces no message of
 * its own, so it is the only one that needs a clock.
 */
function devicesTick() {
  const now = Date.now();
  for (const [workId, entry] of [...remoteRuns]) {
    if (now - entry.lastHeard > REMOTE_SILENCE_MS) {
      loseRemoteRun(workId, `no word for ${Math.round((now - entry.lastHeard) / 60_000)} minutes`);
    } else if (now - entry.startedAt > RUN_HARD_CAP_MS) {
      loseRemoteRun(workId, 'passed the hard run cap');
    }
  }
  const before = Object.keys(deviceState.devices || {}).length;
  devicesLib.pruneDevices(deviceState, now);
  if (Object.keys(deviceState.devices || {}).length !== before) saveDevices();
}
setInterval(() => { try { devicesTick(); } catch (e) { log('devices: tick failed', e.message); } }, 30_000).unref();

/** Stop a run: ask, then insist. Shared by the cancel route and a Round timeout. */
function cancelRun(run_) {
  run_.cancelled = true;
  // A REMOTE run has no `proc` to kill. Stopping it means taking the work back
  // before the machine is handed it — otherwise Stop stops nothing, the device
  // picks the item up on its next poll (up to 25s away, or hours for a sleeping
  // laptop) and does the work anyway while the chat says it was cancelled.
  if (run_.remote) {
    const { deviceId, workId } = run_.remote;
    if (withdrawWork(deviceId, workId)) {
      // Never handed over, so nothing out there is running: settle immediately
      // rather than leaving the chat "stopping" until the silence timer fires.
      remoteRuns.delete(workId);
      settleRun(run_, { exitCode: null, failureText: 'cancelled before it was picked up' });
      return;
    }
    // Already with the device. It learns of the cancel in the ack to its next
    // batch and posts its own terminal frame, so the ending still comes from it.
    return;
  }
  try { run_.proc.kill('SIGTERM'); } catch { }
  setTimeout(() => { try { run_.proc.kill('SIGKILL'); } catch { } }, 5000).unref();
}

// ------------------------------------------------------------------- rounds
//
// Scheduled work, built ON the chat machinery above rather than beside it. A
// Round fires by creating a chat and posting one message to it, so the transcript,
// the SSE stream, the cancel button, the model controls and the push notification
// a chat already has all apply to a scheduled run for free. What Rounds add is the
// cadence, the output contract, and a record of what came back.
//
// ONE CHAT PER RUN, not one resumed thread: a wedged week cannot poison the next,
// a timeout is scoped to the run it belongs to, and each report opens clean.
// Week-over-week continuity is MemPalace's job, not this file's.

const ROUNDS_DIR = path.join(DATA_DIR, 'rounds');
fs.mkdirSync(ROUNDS_DIR, { recursive: true });

const UUID_RE = /^[0-9a-f-]{36}$/;
const NOTIFY_WHEN = ['always', 'attention', 'never'];
const MAX_RUN_HISTORY = 10;
const MAX_ROUND_PROMPT = 20_000;
const MAX_ROUND_GOAL = 500;
/**
 * Default per-Round cap. The global RUN_HARD_CAP_MS is two hours, which is a
 * safety net for a person who is watching; a scheduled run that wedges would
 * otherwise hold one of three pool slots until long after its report was any use.
 */
const DEFAULT_ROUND_TIMEOUT_S = 15 * 60;

function roundPath(id) { return path.join(ROUNDS_DIR, `${id}.json`); }

function loadRound(id) {
  if (!UUID_RE.test(String(id || ''))) return null;
  try { return JSON.parse(fs.readFileSync(roundPath(id), 'utf8')); } catch { return null; }
}
function saveRound(r) {
  fs.writeFileSync(roundPath(r.id), JSON.stringify(r, null, 2));
  return r;
}
function listRounds() {
  let files = [];
  try { files = fs.readdirSync(ROUNDS_DIR); } catch { return []; }
  const out = [];
  for (const f of files) {
    if (!f.endsWith('.json')) continue;
    const r = loadRound(f.slice(0, -5));
    if (r) out.push(r);
  }
  // Soonest first: the list answers "what happens next" before "what exists".
  out.sort((a, b) => (a.nextRunAt || Infinity) - (b.nextRunAt || Infinity));
  return out;
}
/** Reload, change, save — same reason as updateMeta: never write back a stale snapshot. */
function updateRound(id, mutate) {
  const r = loadRound(id);
  if (!r) return null;
  mutate(r);
  r.updatedAt = Math.floor(Date.now() / 1000);
  return saveRound(r);
}

/** The record plus what a client would otherwise have to derive for itself. */
function roundView(r) {
  return {
    ...r,
    // Rendered here so the phone, the desktop and a Telegram line cannot disagree
    // about what "Sundays at 7:00 PM" means.
    cadence: roundsLib.describeSchedule(r.schedule),
    running: !!(r.currentChatId && activeRuns.has(r.currentChatId)),
    host: r.host || 'local',
    // Resolved here for the same reason as the cadence: a client that looked this
    // up itself would show a bare uuid for a device that has been unenrolled.
    hostName: (r.host && r.host !== 'local')
      ? (((deviceState.devices || {})[r.host] || {}).name || 'a removed device')
      : null,
  };
}

function clampRoundTimeout(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return DEFAULT_ROUND_TIMEOUT_S;
  return Math.max(60, Math.min(7200, Math.floor(n)));
}

/**
 * Resolves and checks where a Round should run.
 *
 * Checks the device's ENROLLED scope, not its lock state and not whether it is
 * awake: a Round scheduled for next Sunday must not be refused because the laptop
 * is asleep on Tuesday. What is worth refusing now is the permanent kind of
 * wrong — an `act` Round pinned to a device that is only ever allowed to look,
 * which would otherwise fail every single week with nobody watching.
 */
function placeRound(rawHost, mode) {
  if (typeof rawHost !== 'string' || !rawHost || rawHost === 'local') return { host: 'local' };
  const dev = (deviceState.devices || {})[rawHost];
  if (!dev) return { error: 'no such device' };
  // Own-property, like canRun: MODE_NEEDS is a plain object and inherited keys
  // answer with a function, which only refused here by accident of indexOf.
  const needed = (Object.prototype.hasOwnProperty.call(devicesLib.MODE_NEEDS, mode)
    && typeof devicesLib.MODE_NEEDS[mode] === 'string') ? devicesLib.MODE_NEEDS[mode] : null;
  if (needed === null) return { error: `cannot run ${JSON.stringify(String(mode)).slice(0, 20)} anywhere` };
  if (!devicesLib.scopeCovers(dev.scope, needed)) {
    return { error: `${dev.name} is enrolled as "${dev.scope}", which cannot run ${mode}` };
  }
  return { host: rawHost };
}

/**
 * This host's IANA zone, used when a client names none.
 *
 * Read from Intl rather than $TZ or /etc/timezone, because Intl is the same
 * source `lib/rounds.js` resolves wall-clock times through — so the zone a Round
 * is stored with and the zone it is fired by cannot disagree. Falls back to UTC,
 * which is wrong for a person but never invalid, so a schedule still saves.
 */
function hostZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** YYYY-MM-DD as the round's own zone reads it. */
function runDateIn(round, ms) {
  const tz = (round && round.schedule && round.schedule.tz) || hostZone();
  try {
    const p = roundsLib.partsIn(tz, ms);
    return `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
  } catch {
    return new Date(ms).toISOString().slice(0, 10);
  }
}

function buildRound(body) {
  const title = roundsLib.oneLine(body.title, 80);
  if (!title) return { error: 'title required' };
  const prompt = typeof body.prompt === 'string' ? body.prompt.trim() : '';
  if (!prompt) return { error: 'prompt required' };
  if (prompt.length > MAX_ROUND_PROMPT) return { error: 'prompt too long' };
  const goal = typeof body.goal === 'string' ? body.goal.trim().slice(0, MAX_ROUND_GOAL) : '';
  const sched = roundsLib.validateSchedule(body.schedule, hostZone());
  if (!sched.ok) return { error: sched.error };
  const placed = placeRound(body.host, body.mode === 'act' ? 'act' : 'ask');
  if (placed.error) return { error: placed.error };
  // Rounds run unattended and their report contract assumes a model that holds
  // it under injection pressure — the panel's unanimous answer: never local.
  if (isLocalFamily(body.model)) return { error: 'local models serve chats only — a Round needs a Claude model' };
  const mv = modelDecision(body.model);
  if (mv.error) return { error: mv.error };
  const ev = effortDecision(body.effort);
  if (ev.error) return { error: ev.error };

  const now = Math.floor(Date.now() / 1000);
  return {
    round: {
      v: 1,
      id: crypto.randomUUID(),
      title,
      prompt,
      // What "done" means for this Round, as a completion test. Optional, because
      // a Round that just reports on something has no finish line to cross — but
      // when it is set, the run is asked whether it got there and an honest no is
      // reported rather than smoothed over.
      goal,
      enabled: body.enabled !== false,
      // `ask` unless asked otherwise, deliberately: an unattended 3am run holding
      // Bash and Write is a different risk class from one that can only read, and
      // nothing about wanting something on a schedule implies consent to the second.
      mode: body.mode === 'act' ? 'act' : 'ask',
      // Where it runs. A Round on a Device is the thing neither feature could do
      // alone: work that happens on a schedule, in another machine's context.
      host: placed.host,
      model: mv.model,
      effort: ev.effort,
      schedule: sched.schedule,
      notifyWhen: NOTIFY_WHEN.includes(body.notifyWhen) ? body.notifyWhen : 'attention',
      catchUp: body.catchUp === true,
      timeoutSec: clampRoundTimeout(body.timeoutSec),
      createdAt: now,
      updatedAt: now,
      nextRunAt: roundsLib.nextFireAt(sched.schedule, Date.now()),
      currentChatId: null,
      lastRun: null,
      runs: [],
    },
  };
}

function applyRoundPatch(round, body) {
  const r = { ...round };
  if ('title' in body) {
    const t = roundsLib.oneLine(body.title, 80);
    if (!t) return { error: 'title cannot be empty' };
    r.title = t;
  }
  if ('prompt' in body) {
    const p = typeof body.prompt === 'string' ? body.prompt.trim() : '';
    if (!p) return { error: 'prompt cannot be empty' };
    if (p.length > MAX_ROUND_PROMPT) return { error: 'prompt too long' };
    r.prompt = p;
  }
  if ('schedule' in body) {
    const s = roundsLib.validateSchedule(body.schedule, round.schedule?.tz || hostZone());
    if (!s.ok) return { error: s.error };
    r.schedule = s.schedule;
    // Re-armed immediately: keeping the old slot would fire once more on a
    // cadence the owner has just replaced, which reads as the edit not working.
    r.nextRunAt = roundsLib.nextFireAt(s.schedule, Date.now());
  }
  if ('enabled' in body) {
    r.enabled = body.enabled !== false;
    // Re-enabling arms from NOW. A Round switched off for a month would otherwise
    // come back with a slot deep in the past and fire immediately on resume.
    if (r.enabled && (!r.nextRunAt || r.nextRunAt <= Date.now())) {
      r.nextRunAt = roundsLib.nextFireAt(r.schedule, Date.now());
    }
  }
  if ('goal' in body) {
    r.goal = typeof body.goal === 'string' ? body.goal.trim().slice(0, MAX_ROUND_GOAL) : '';
  }
  if ('mode' in body) r.mode = body.mode === 'act' ? 'act' : 'ask';
  if ('host' in body || 'mode' in body) {
    const wanted = 'host' in body ? body.host : r.host;
    // Re-checked together, because widening the mode can invalidate a host that
    // was fine for the old one — an `act` Round on a look-scope device would fail
    // every week, silently, at 3am.
    const placed = placeRound(wanted, r.mode);
    if (placed.error) {
      // ⚠ EXCEPT when the machine is simply GONE and the owner is not moving the
      // Round anywhere. Both clients send `host` on every save, so a device that
      // was unenrolled made EVERY edit fail — including changing only the title —
      // with an error naming something the person did not touch, and their typing
      // discarded. If it was the only device the clients hide the where-it-runs
      // chips entirely, so there was no way to move the Round back to this host:
      // permanently uneditable, while Pause/Resume still worked so the row looked
      // alive. A Round pointing at a machine that no longer exists cannot fire,
      // and since the tick now RECORDS every refusal that is visible every time
      // it tries. Being unable to fix it is the worse failure.
      const unchanged = wanted === r.host;
      const gone = typeof wanted === 'string' && wanted !== 'local' && !((deviceState.devices || {})[wanted]);
      if (!(unchanged && gone)) return { error: placed.error };
    } else {
      r.host = placed.host;
    }
  }
  // `r` is a discarded copy, so an error return here leaves the round untouched.
  if ('model' in body) {
    if (isLocalFamily(body.model)) return { error: 'local models serve chats only — a Round needs a Claude model' };
    const mv = modelDecision(body.model);
    if (mv.error) return { error: mv.error };
    r.model = mv.model;
  }
  if ('effort' in body) {
    const ev = effortDecision(body.effort);
    if (ev.error) return { error: ev.error };
    r.effort = ev.effort;
  }
  if ('notifyWhen' in body && NOTIFY_WHEN.includes(body.notifyWhen)) r.notifyWhen = body.notifyWhen;
  if ('catchUp' in body) r.catchUp = body.catchUp === true;
  if ('timeoutSec' in body) r.timeoutSec = clampRoundTimeout(body.timeoutSec);
  r.updatedAt = Math.floor(Date.now() / 1000);
  return { round: r };
}

/**
 * One run of a Round: a fresh chat, one message, the output contract appended.
 *
 * A refusal is not a failure. The run pool is shared with the owner's own chats,
 * so a Round that cannot start right now waits for the next tick rather than
 * burning its slot — this reports the reason and leaves the arming to the caller.
 */
function fireRound(round, { manual = false } = {}) {
  if (round.currentChatId && activeRuns.has(round.currentChatId)) {
    return { error: 'previous run is still going', code: 409 };
  }
  // Defensive: only reachable by hand-editing the round file — both write
  // paths refuse the local family. The tick records this refusal as an
  // attention run, so the bypass fails closed AND visibly.
  if (isLocalFamily(round.model)) {
    return { error: 'this Round names a local model, which Rounds refuse — edit it to a Claude model', code: 400 };
  }
  const now = Math.floor(Date.now() / 1000);
  const meta = {
    id: crypto.randomUUID(),
    // The date the OPERATOR was living in when it fired, not UTC. An evening
    // round in America/Los_Angeles is 7 hours into the next UTC day, so every
    // single run of it was filed under tomorrow — the Sunday 19:00 round
    // produced a chat titled Monday, which is the one date it never ran on.
    title: `${round.title} · ${runDateIn(round, now * 1000)}`.slice(0, 80),
    mode: round.mode === 'act' ? 'act' : 'ask',
    model: round.model || null,
    effort: round.effort || null,
    createdAt: now,
    updatedAt: now,
    claudeSessionId: null,
    lastSnippet: null,
    turns: 0,
    // What makes this chat a Round's run rather than a conversation: the close
    // handler reads it to decide whether a report is owed, and the chat list
    // reads it to stay out of the way.
    roundId: round.id,
    roundStartedAt: now,
    roundManual: !!manual,
    // The chat carries the placement; startRunAnywhere reads it and nothing about
    // firing a Round needs to know that devices exist.
    host: round.host || 'local',
    // A tag minted for THIS run and put only in the prompt. Nothing the run
    // READS can know it, so a report block arriving inside fetched content
    // cannot be mistaken for the run's own answer. It lives on the chat rather
    // than in memory because the parse happens when the run FINISHES, which may
    // be on the other side of a daemon restart.
    reportTag: crypto.randomBytes(5).toString('hex'),
  };
  saveMeta(meta);

  const started = startRunAnywhere(meta, roundsLib.promptFor(round, meta.reportTag));
  if (started && started.error) {
    // startRun checks the pool before it writes anything, so nothing but the meta
    // exists yet and the chat can be withdrawn completely — better than leaving
    // an empty run in the history of every Round that ever hit a busy minute.
    try { fs.rmSync(chatDir(meta.id), { recursive: true, force: true }); } catch { }
    return started;
  }
  updateRound(round.id, (r) => { r.currentChatId = meta.id; r.lastFiredAt = now; });
  log(`round ${round.id} (${round.title}) fired into chat ${meta.id}${manual ? ' (manual)' : ''}`);
  return { chatId: meta.id };
}

/**
 * A Round's run has ended: read what it said, record it, decide whether that is
 * worth interrupting somebody for.
 *
 * Always records something. A run that failed to format its report has usually
 * still done the work, and going silent would make a broken contract look like a
 * clean week — the worst failure here, because nobody goes looking for a report
 * they were never told was missing.
 */
function finishRoundRun(meta, failure) {
  const round = loadRound(meta.roundId);
  if (!round) return;                    // the Round was deleted mid-run; the chat stands alone

  const msgs = loadMsgs(meta.id);
  // EVERY assistant message, joined — not merely the last one.
  //
  // parseReport is documented as "the LAST block wins" across the whole answer,
  // but this narrowed to a single message first, and each text content block
  // becomes its own message. So an ordinary agentic turn — write the report, run
  // one more tool, say "Confirmed." — threw the report away and filed the word
  // "Confirmed." as the week's finding, with a complete `action` report sitting
  // one message earlier in the same transcript. Joining preserves last-wins.
  const parts = [];
  let lastError = null;
  let resultFailure = null;
  for (const m of msgs) {
    if (m.type === 'assistant' && m.text) parts.push(m.text);
    if (m.type === 'error' && m.text) lastError = m.text;
    // A FLAG, not a guess. lib/rounds' own header says the report contract exists
    // because "success must be a FLAG, not a guess" — and this was guessing from
    // prose while the flag sat unread two fields away.
    if (m.type === 'result' && m.ok === false) {
      resultFailure = m.errorText || 'the run reported an error';
    }
  }
  const text = parts.join('\n\n');
  const parsed = text ? roundsLib.parseReport(text, meta.reportTag || null) : null;

  let report;
  if (parsed) {
    // ⚠ A REPORT BEATS A FAILURE, and on a device that is the NORMAL case rather
    // than a race: a timeout cancel cannot stop the far machine, which finishes
    // cleanly and delivers a valid report — and this used to file "did not finish"
    // over the top of "7 of 7 backups verified, all green".
    report = parsed;
  } else if (failure) {
    report = roundsLib.errorReport(failure);
  } else if (resultFailure) {
    report = roundsLib.errorReport(resultFailure);
  } else if (lastError) {
    // Reached even when there IS text: one streamed token before a crash used to
    // turn "claude exited 1" into a cheerful progress line, so the same crash
    // reported as a failure or as "Checking the disks now" depending on timing.
    report = roundsLib.errorReport(lastError);
  } else {
    report = roundsLib.fallbackReport(
      text,
      roundsLib.untaggedReport(text, meta.reportTag)
        ? 'a report block arrived without this run\'s tag'
        : 'no huginn-report block',
    );
  }

  const at = Math.floor(Date.now() / 1000);
  const status = roundsLib.effectiveStatus(report);
  const run = {
    at,
    chatId: meta.id,
    status,
    // Kept alongside, so "it said ok but had not finished" stays visible rather
    // than being flattened into the status it was promoted to.
    reportedStatus: report.status,
    goalMet: report.goalMet,
    headline: report.headline,
    items: report.items,
    // ⚠ CARRIED, and it was not. parseReport caps `items` at 20 and records how
    // many the run actually reported — and this record dropped that number on
    // the floor, so every reader fell back to the capped length and the fix
    // shipped doing nothing. A round that found 500 things still showed "20
    // items" under a headline saying 500. The parse being right is not the same
    // as a reader seeing it; caught by a live run, not by a test.
    itemsTotal: typeof report.itemsTotal === 'number' ? report.itemsTotal : report.items.length,
    malformed: report.malformed,
    manual: !!meta.roundManual,
    durationSec: meta.roundStartedAt ? at - meta.roundStartedAt : null,
  };
  // ⚠ WHAT FALLS OFF THE END IS DELETED, and this used to be a slow leak with a
  // false promise on top of it. `finishRoundRun` says the conversation "stays
  // readable forever — that is the whole point of keeping it", and after the 11th
  // run the chat id was evicted from runs[] — while round chats are filtered out
  // of /v1/chats by design, so there was no other path to it. It could not be
  // opened, listed or deleted through any route. A daily Round left ~355 orphan
  // transcript directories a year, invisible and impossible to count against.
  //
  // So the promise is narrowed to what is true — readable while it is in the
  // history — and the transcript goes when the row does, rather than becoming
  // something only `du` can find.
  let evicted = [];
  updateRound(round.id, (r) => {
    const prior = Array.isArray(r.runs) ? r.runs : [];
    const kept = [run, ...prior].slice(0, MAX_RUN_HISTORY);
    evicted = prior.slice(MAX_RUN_HISTORY - 1)
      .map((x) => x && x.chatId)
      .filter((id) => id && id !== meta.id && !kept.some((k) => k.chatId === id));
    r.lastRun = run;
    r.runs = kept;
    if (r.currentChatId === meta.id) r.currentChatId = null;
  });
  for (const id of evicted) {
    // Never a live one: a chat still running is not in this Round's history tail.
    if (activeRuns.has(id)) { log(`round ${round.id}: not pruning ${id}, still running`); continue; }
    try { fs.rmSync(chatDir(id), { recursive: true, force: true }); log(`round ${round.id} pruned run transcript ${id}`); }
    catch (e) { log(`round ${round.id}: could not prune ${id}: ${e.message}`); }
  }
  // On the chat too, so opening a past run shows its verdict without re-parsing.
  // `sealed` is the auto-end: a Round's run is one turn against a stated goal and
  // then it is over. The conversation stays readable for as long as the run is in
  // the Round's history (the last MAX_RUN_HISTORY) — that is the whole
  // point of keeping it — but it stops being something anyone can continue, so a
  // scheduled job cannot quietly become an open chat nobody meant to start.
  updateMeta(meta.id, (m) => {
    m.roundStatus = status;
    m.roundHeadline = report.headline;
    m.roundGoalMet = report.goalMet;
    m.sealed = true;
    m.endedAt = at;
  });
  log(`round ${round.id} run ${meta.id} -> ${status}${report.goalMet === false ? ' (goal NOT met)' : ''}: ${report.headline.slice(0, 80)}`);

  if (roundsLib.shouldNotify(round.notifyWhen, status)) {
    deliverRoundReport(round, report, meta.id)
      .catch((e) => log(`round ${round.id}: report delivery failed (${e.message})`));
  }
}

const ROUND_MARK = { ok: '✅', attention: '⚠️', action: '🔴', unknown: '❓' };

/**
 * Push first, Telegram only if the app did not get it.
 *
 * Not a new delivery policy — this is the rule the alert watcher already applies,
 * and the reason it exists is written in lib/clients: "a duplicate of every alert
 * on two channels is worse than one channel: the reader learns to ignore both".
 * A weekly report arriving twice is exactly what would train that habit.
 */
async function deliverRoundReport(round, report, chatId) {
  // ⚠ BUILT ONCE, USED BY BOTH CHANNELS. These were computed separately and
  // disagreed: push led with "did not finish — " while Telegram indexed the
  // REPORTED status, so an `ok` report with goalMet false — the single case the
  // design calls out as most worth surfacing — arrived as a green tick and a
  // clean sentence. On the channel used exactly when the app is NOT there to
  // show the warning row. Same event, two channels, opposite verdicts.
  const { status, text } = roundsLib.reportDisplay(report);
  const pushed = await deliverPush({
    kind: 'round_report',
    key: `round:${round.id}:${chatId}`,
    subject: round.title,
    title: round.title,
    text,
  });
  if (pushed.sent > 0 || clientsLib.appOnline(clientState, Date.now())) {
    log(`round ${round.id}: telegram held (${pushed.sent > 0 ? 'pushed to the app' : 'app checked in recently'})`);
    return;
  }
  const lines = [`${ROUND_MARK[status] || ROUND_MARK.unknown} ${round.title}`, text];
  // A handful of items, each with its next step. Statements only — there is no
  // reply path on that channel, so a question would arrive as noise.
  for (const it of report.items.slice(0, 5)) {
    lines.push(`• ${it.title}${it.suggest ? ` — ${it.suggest}` : ''}`);
  }
  await deliverTelegram(lines.join('\n'));
}

/**
 * One pass over every Round. Cheap by construction: a few small JSON reads, and
 * no tmux, no network and no spawn unless something is actually due.
 */
/**
 * A scheduled fire that was REFUSED is still something that happened.
 *
 * ⚠ THIS IS THE QUIETEST FAILURE IN THE FEATURE and it used to be one log line.
 * Eight ordinary triggers reach here — the device was unenrolled, is asleep, is
 * locked, narrowed its scope, is already running something (two Rounds on one
 * machine at 03:00: the second was dropped EVERY NIGHT), the local pool is full,
 * or the slot was missed with catchUp off. In every one of them `runs` stayed 0,
 * `lastRun` stayed null, and nothing was sent — even with notifyWhen "always",
 * because a failure that never becomes a run can never be notified about.
 *
 * So the operator saw "Daily at 3:00 AM · in 51m" and a clean green week, for a
 * job that had not run since the laptop went to sleep. Firing by hand was the
 * only way to learn why, and that path has always answered with a reason.
 *
 * A skipped run is recorded as `attention` rather than `action`: nothing is
 * wrong with the world, something is wrong with the arrangement.
 */
function recordSkippedRound(roundId, reason) {
  const round = loadRound(roundId);
  if (!round) return;
  const at = Math.floor(Date.now() / 1000);
  const report = {
    status: 'attention',
    headline: `did not run: ${String(reason).slice(0, 120)}`,
    items: [],
    goalMet: null,
    malformed: false,
  };
  const run = {
    at,
    chatId: null,                 // there is no transcript; nothing ran
    status: 'attention',
    reportedStatus: null,
    goalMet: null,
    headline: report.headline,
    items: [],
    itemsTotal: 0,
    malformed: false,
    skipped: true,
    manual: false,
    durationSec: 0,
  };
  updateRound(round.id, (r) => {
    r.lastRun = run;
    r.runs = [run, ...(Array.isArray(r.runs) ? r.runs : [])].slice(0, MAX_RUN_HISTORY);
  });
  log(`round ${round.id}: could not start (${reason}) — recorded as a skipped run`);
  if (roundsLib.shouldNotify(round.notifyWhen, 'attention')) {
    deliverRoundReport(round, report, null)
      .catch((e) => log(`round ${round.id}: skip notice failed (${e.message})`));
  }
}

async function roundsTick() {
  const now = Date.now();
  for (const round of listRounds()) {
    // A wedged run holds a pool slot the owner's own chats need.
    if (round.currentChatId) {
      const active = activeRuns.get(round.currentChatId);
      const meta = active ? loadMeta(round.currentChatId) : null;
      const capMs = clampRoundTimeout(round.timeoutSec) * 1000;
      if (active && meta && meta.roundStartedAt && now - meta.roundStartedAt * 1000 > capMs) {
        log(`round ${round.id}: run ${round.currentChatId} passed ${capMs / 1000}s, cancelling`);
        cancelRun(active);
        continue;
      }
    }

    const d = roundsLib.dueDecision(round, now);
    // Re-armed BEFORE firing, so a crash between the two cannot leave a Round
    // firing on every tick forever.
    if (d.nextRunAt !== round.nextRunAt) updateRound(round.id, (r) => { r.nextRunAt = d.nextRunAt; });
    if (d.reason === 'missed') {
      log(`round ${round.id}: missed its slot by ${Math.round(d.lateBy / 60_000)}m, skipped (catchUp off)`);
    }
    if (!d.run) continue;

    const started = fireRound(loadRound(round.id) || round);
    if (started.error) recordSkippedRound(round.id, started.error);
  }
}
setInterval(() => { roundsTick().catch((e) => log('rounds: tick failed', e.message)); }, 30_000).unref();

/**
 * Starts a run wherever the chat says it belongs.
 *
 * One seam, so every caller — a message from a phone, a queued message, a Round
 * firing — reaches a device without knowing that devices exist.
 */
function startRunAnywhere(meta, text) {
  return (meta.host && meta.host !== 'local') ? startRemoteRun(meta, text) : startRun(meta, text);
}

function startQueuedRun(meta, text) {
  const started = startRunAnywhere(meta, text);
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
/**
 * A Round whose run was interrupted must still record that it happened.
 *
 * Without this the restart left `runs` at 0, `lastRun` null, `currentChatId`
 * dangling at a dead chat, and — worst — the chat UNSEALED, so opening it and
 * typing started a live run whose answer was then filed as the Round's report.
 */
function reconcileInterruptedRound(meta) {
  if (!meta.roundId) return;
  try { finishRoundRun(meta, 'huginn-appd restarted while this was running'); }
  catch (e) { log(`round run ${meta.id} could not be recorded after restart: ${e.message}`); }
}

/** A Claude session id, and nothing that could be read as a flag. */
function isSessionId(v) {
  return typeof v === 'string' && /^[0-9a-fA-F-]{36}$/.test(v);
}

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
    reconcileInterruptedRound(loadMeta(id) || meta);
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
  if (!MEMPALACE_HOST || !MEMPALACE_MARKER) return 'unconfigured';
  if (Date.now() - mpCache.at < 60_000) return mpCache.value;
  const { err, stdout } = await run('ssh',
    ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=3', MEMPALACE_HOST,
      `if [ -e ${MEMPALACE_MARKER} ]; then echo rebuilding; elif systemctl is-active --quiet mempalace-daemon; then echo ok; else echo daemon-down; fi`],
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
    // So clients can show the exact wrap-up wording (and whether ending is
    // automatic) without carrying their own copy that could drift from the host.
    softEndPhrase: SOFT_END_PHRASE,
    softEndAuto: SOFT_END_AUTO,
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
// Optional out-of-band delivery script (anything accepting --message/--source).
// Empty on a generic host — the fallback channel simply reports 'none'.
const TELEGRAM_SCRIPT = process.env.HUGINN_APPD_TELEGRAM_SCRIPT || '';

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
    // promptFor, not a second detectPrompt call: the notification's labels and
    // fingerprint must be the FUSED ones, or a lock-screen answer would carry a
    // fingerprint the /answer route (also fused) rejects as changed.
    const prompt = screen ? promptFor(a.subject, screen.lines).prompt : null;
    if (!prompt) continue;                 // waiting on something unparsed; keep the plain text
    a.question = prompt.question || '';
    // A notification button is one tap; a multi-select answer is a SET. Buttons
    // are only offered when one tap can honestly answer.
    a.options = prompt.multiSelect ? [] : prompt.options.map((o) => ({ number: o.number, label: o.label }));
    a.fingerprint = prompt.fingerprint;
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
        // Same state change drives auto-end, so a settled session is killed
        // within the debounce window rather than on the 10s floor tick.
        softEndTick().catch((e) => log('soft-end: watch tick failed', e.message));
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
      const models = await discoverModels(bin);
      // Local rows are OPT-IN (?local=1): an old client would render them and
      // then 400 on every pick, so a client asks for them when it knows how to
      // treat them. Computed per request from the registry — no cache, because
      // `available` is time-dependent and a cache would lie.
      if (u.searchParams.get('local') === '1') {
        return sendJson(res, 200, { models: models.concat(devicesLib.localModelRows(deviceState, Date.now())) });
      }
      return sendJson(res, 200, { models });
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
      // Whatever the last holder of this name left behind goes now, before the new
      // session can be observed. The born-time guard would reject it anyway, but
      // deleting it here closes the window rather than papering over it.
      clearSessionState(name);
      // Before the session exists, never after: the server's cgroup is decided by
      // whoever starts it, and that is a one-time choice per server lifetime.
      await ensureTmuxServerScope();
      // Same shape as cc: open in WORKDIR, claude first, fall through to a shell.
      const { err, stderr } = await run('tmux',
        ['new-session', '-d', '-s', name, '-c', WORKDIR, 'claude; exec "$SHELL" -l']);
      if (err) return sendErr(res, 500, `tmux: ${stderr.trim() || err.message}`);
      // What tmux called it, not what we asked for — same reason as the rename
      // route below: a '.' is rewritten to '_' with a zero exit, and a client
      // told the wrong name gets a 404 on everything it does next.
      const q = await run('tmux', ['display-message', '-p', '-t', `=${name}:`, '#S']);
      return sendJson(res, 201, { ok: true, name: (q.stdout || '').trim() || name });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})$/)) && req.method === 'DELETE') {
      const name = m[1];
      const { err, stderr } = await hardEndSession(name);
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
      // Move the prompt sidecars + the compacting marker too, or a fused prompt
      // silently degrades to pane-only after a rename until the next question
      // rewrites them.
      for (const kind of ['ask', 'plan', 'compacting']) {
        try { fs.renameSync(path.join(STATE_DIR, kind, from), path.join(STATE_DIR, kind, actual)); } catch { }
      }
      if (leases.has(from)) { leases.set(actual, leases.get(from)); leases.delete(from); }
      if (softEnds.has(from)) { softEnds.set(actual, softEnds.get(from)); softEnds.delete(from); }
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

    // --- soft end: type a wrap-up phrase, and (when auto) end on settle
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/soft-end$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const body = JSON.parse(await readBody(req) || '{}');
      const st = readSessionState(name);
      // A question is already waiting: typing prose into a numbered prompt is
      // lost or misread. Answer it first (same reasoning as /answer's guard).
      if (st && st.state === 'attention') {
        return sendErr(res, 409, 'answer the waiting question first, then end the session');
      }
      // No state file means the hook has never seen a Claude turn here — the pane
      // may be a plain shell, where the phrase would EXECUTE as a command. Refuse
      // unless the caller insists.
      if (!st && !body.force) {
        return sendErr(res, 409, 'no Claude state recorded for this session — it may be a plain shell; pass force to send anyway');
      }
      const phrase = (typeof body.phrase === 'string' && body.phrase.trim())
        ? body.phrase.slice(0, 8000) : SOFT_END_PHRASE;
      const auto = typeof body.auto === 'boolean' ? body.auto : SOFT_END_AUTO;
      const queued = !!(st && st.state === 'running'); // mid-turn text queues in the composer
      const r = await sendLineToPane(name, phrase);
      if (r.err) return sendErr(res, 500, `tmux: ${(r.stderr || '').trim()}`);
      if (auto) softEnds.set(name, createPending(Date.now()));
      else softEnds.delete(name);
      return sendJson(res, 200, { ok: true, phrase, auto, queued });
    }

    // --- manual context compaction (the "context manager" action)
    //
    // Sends the "/compact" slash command into the pane so the owner can reclaim
    // context from a phone/desktop the same way they would at the keyboard. Same
    // two guards as /soft-end: refuse when no Claude turn has been recorded (a
    // plain shell would RUN "/compact" as a command), and never fire while a
    // question is waiting (it would be typed into the numbered prompt). Sending
    // mid-turn is allowed — Claude Code queues the command and compacts when the
    // turn ends — and reported back as `queued` so the client can say so.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/compact$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st) {
        return sendErr(res, 409, 'no Claude state recorded for this session — it may be a plain shell');
      }
      if (st.state === 'attention') {
        return sendErr(res, 409, 'answer the waiting question first, then compact');
      }
      const queued = st.state === 'running';
      const r = await sendLineToPane(name, '/compact');
      if (r.err) return sendErr(res, 500, `tmux: ${(r.stderr || '').trim()}`);
      return sendJson(res, 200, { ok: true, sent: '/compact', queued });
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
      const pf = screen ? promptFor(name, screen.lines) : { prompt: null, ask: null };
      const prompt = pf.prompt;
      if (!prompt) {
        // The hook may still say a question is waiting (a wrap/preview the pane
        // scrape cannot read). Distinguish that from "gone" so the client can
        // deep-link to the Screen tab instead of reporting the question vanished.
        if (pf.ask) {
          return sendJson(res, 409, {
            ok: false, reason: 'undetected',
            error: 'the question is on screen but not answerable from here — use the Screen tab',
          });
        }
        return sendJson(res, 409, {
          ok: false, reason: 'gone',
          error: 'that question is no longer on screen',
        });
      }
      const live = prompt.fingerprint;
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
    const serveArtifact = (dir, name, extraHeaders = null) => {
      const found = desktopLib.resolveArtifact(dir, name);
      if (!found.ok) return sendErr(res, found.status, found.error);
      res.writeHead(200, {
        'Content-Type': (extraHeaders && extraHeaders['Content-Type']) || found.contentType,
        'Content-Length': found.size,
        ...(extraHeaders || {}),
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
      return serveArtifact(DESKTOP_DIR, decodeURIComponent(m[1]));
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
      return serveArtifact(DESKTOP_KT_DIR, decodeURIComponent(m[1]));
    }

    // --- an uploaded file, served back so chat history can show a real
    // thumbnail instead of a "photo attached" placeholder. Auth like every route
    // (the global gate above). resolveArtifact's validName rejects every path
    // separator and dotfile, so the name cannot escape UPLOADS_DIR — the same
    // by-construction defence the desktop channels rely on. Served with a
    // conservative type and nosniff: these are user-supplied bytes and must never
    // render as an active type. A 404 after a manual delete is expected; the
    // client falls back to the placeholder.
    if (req.method === 'GET' && (m = p.match(/^\/v1\/uploads\/([^/]+)$/))) {
      const name = decodeURIComponent(m[1]);
      return serveArtifact(UPLOADS_DIR, name, {
        'Content-Type': contentTypeForUpload(name),
        'X-Content-Type-Options': 'nosniff',
      });
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

    // ---- devices: other machines that can run a chat in their context
    if (req.method === 'GET' && p === '/v1/devices') {
      const now = Date.now();
      const list = Object.entries(deviceState.devices || {})
        .map(([id, d]) => ({
          ...devicesLib.deviceView(id, d, now),
          running: !!activeRunFor(id),
          queued: (deviceQueues.get(id) || []).length,
          // "It has not asked for work since huginn restarted, so whether it is
          // free is not something this daemon can currently say." A device that
          // was mid-run through a restart looks exactly like an idle one until it
          // finishes and polls again.
          awaitingPoll: !polledSince.has(id),
        }))
        .sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
      return sendJson(res, 200, { devices: list });
    }

    if (req.method === 'POST' && p === '/v1/devices') {
      const body = JSON.parse(await readBody(req) || '{}');
      const now = Date.now();
      const built = devicesLib.validateRegistration(body, now);
      if (!built.ok) return sendErr(res, 400, built.error);

      // Re-registering under the same id keeps the id, so a device that restarts
      // does not accumulate ghosts in the list. A device with no id gets one.
      const id = /^[0-9a-f-]{36}$/.test(String(body.id || '')) ? body.id : crypto.randomUUID();
      const existing = (deviceState.devices || {})[id];
      // The llmSlug is minted HERE, once, at a generate enrolment, and echoed in
      // the response — agreement by handshake, never parallel derivation, and it
      // survives renames because every local-model row id embeds it.
      if (built.device.scope === 'generate') {
        built.device.llmSlug = (existing && existing.llmSlug) || devicesLib.mintLlmSlug(
          built.device.name, id,
          Object.entries(deviceState.devices || {})
            .filter(([k]) => k !== id).map(([, d]) => d.llmSlug).filter(Boolean),
        );
      } else if (existing && existing.llmSlug) {
        built.device.llmSlug = existing.llmSlug;
      }
      deviceState.devices[id] = existing
        ? { ...existing, ...built.device, registeredAt: existing.registeredAt }
        : built.device;
      saveDevices();
      log(`device ${built.device.name} registered (${id}, scope=${built.device.scope}${built.device.llmSlug ? `, llm=${built.device.llmSlug}` : ''})`);
      return sendJson(res, 201, devicesLib.deviceView(id, deviceState.devices[id], now));
    }

    if ((m = p.match(/^\/v1\/devices\/([0-9a-f-]{36})(\/.*)?$/))) {
      const devId = m[1]; const dsub = m[2] || '';
      const device = (deviceState.devices || {})[devId];
      if (!device) return sendErr(res, 404, 'no such device');
      const now = Date.now();

      if (req.method === 'GET' && dsub === '') {
        return sendJson(res, 200, {
          ...devicesLib.deviceView(devId, device, now),
          running: !!activeRunFor(devId),
          queued: (deviceQueues.get(devId) || []).length,
          awaitingPoll: !polledSince.has(devId),
        });
      }

      if (req.method === 'DELETE' && dsub === '') {
        // Unenrolling does not reach onto the machine — nothing here can. It
        // stops work being offered; the runner on the far end is stopped there.
        delete deviceState.devices[devId];
        saveDevices();
        deviceQueues.delete(devId);
        const live = activeRunFor(devId);
        if (live) loseRemoteRun(live.run_.remote.workId, 'the device was removed');
        return sendJson(res, 200, { ok: true });
      }

      // The device saying it is still there, and what it is willing to do now.
      if (req.method === 'POST' && dsub === '/beat') {
        const body = JSON.parse(await readBody(req) || '{}');
        devicesLib.noteSeen(deviceState, devId, now, body);
        saveDevices();
        return sendJson(res, 200, {
          ok: true,
          effectiveScope: devicesLib.effectiveScope(deviceState.devices[devId]),
        });
      }

      // The long poll. Answers at once when there is work, otherwise holds until
      // there is or the wait runs out — so a device learns about a job in the
      // moment it is created without polling in a loop.
      if (req.method === 'GET' && dsub === '/work') {
        // Asking for work is the only evidence that a device is FREE. A heartbeat
        // proves it is reachable, which is a different question and the one the
        // daemon used to answer instead.
        polledSince.set(devId, Date.now());
        const lockedParam = u.searchParams.get('locked');
        devicesLib.noteSeen(deviceState, devId, now, {
          locked: lockedParam === '1' ? true : (lockedParam === '0' ? false : undefined),
        });
        // Anything whose run has ended is dropped rather than handed over. The
        // queue is a view of `remoteRuns`; an item that outlives its run is a job
        // the owner already stopped, or one the daemon already gave up on.
        let q = (deviceQueues.get(devId) || []).filter((it) => remoteRuns.has(it.id));
        if (q.length) {
          const item = q.shift();
          deviceQueues.set(devId, q);
          const entry = remoteRuns.get(item.id);
          if (entry) entry.lastHeard = Date.now();
          return sendJson(res, 200, { work: item });
        }
        deviceQueues.set(devId, q);
        const waitS = Number(u.searchParams.get('wait')) || WORK_WAIT_DEFAULT_S;
        let answered = false;
        const drop = parkWaiter(devId, waitS, (item) => {
          // The return value is the contract: false means "I did not take it",
          // and queueWork puts the item back rather than losing it.
          if (answered || res.writableEnded) return false;
          answered = true;
          if (item) {
            const entry = remoteRuns.get(item.id);
            if (entry) entry.lastHeard = Date.now();
          }
          sendJson(res, 200, { work: item || null });
          return true;
        });
        // Unparked, not just flagged: a flag alone would let queueWork hand this
        // dead response a job and drop it on the floor.
        req.on('close', () => { answered = true; drop(); });
        return undefined;
      }

      // Results, in batches. NOT one long chunked POST: a home network drops, and
      // a dropped stream is indistinguishable from a finished run. Short posts
      // with an explicit terminal frame make the ending something the device SAYS
      // rather than something we infer.
      if ((m = dsub.match(/^\/work\/([0-9a-f-]{36})\/events$/)) && req.method === 'POST') {
        const workId = m[1];
        const entry = remoteRuns.get(workId);
        if (!entry) return sendErr(res, 404, 'no such run');
        if (entry.deviceId !== devId) return sendErr(res, 403, 'that run belongs to another device');

        // A megabyte here, not the default 256KB. A device streams stream-json
        // with --include-partial-messages, so one line legitimately carries a
        // whole tool_result; the runner keeps itself well under this, but an
        // OLDER runner does not know to, and rejecting its batch loses the whole
        // answer rather than the oversized part of it.
        const body = JSON.parse(await readBody(req, 1024 * 1024) || '{}');
        entry.lastHeard = Date.now();
        devicesLib.noteSeen(deviceState, devId, entry.lastHeard, body);

        const meta = loadMeta(entry.chatId);
        if (meta) {
          for (const line of Array.isArray(body.lines) ? body.lines : []) {
            let ev;
            try { ev = typeof line === 'string' ? JSON.parse(line) : line; } catch { continue; }
            try { handleClaudeEvent(meta, entry.run_, ev); }
            catch (e) { log(`device ${devId} sent an event we could not handle: ${e.message}`); }
          }
        }

        if (body.done === true) {
          remoteRuns.delete(workId);
          settleRun(entry.run_, {
            exitCode: Number.isFinite(body.exitCode) ? body.exitCode : null,
            failureText: body.error ? String(body.error).slice(0, 500) : null,
          });
          return sendJson(res, 200, { ok: true, done: true });
        }
        // The one thing a device needs told mid-run: stop. It kills its own child
        // and posts a terminal frame, so the ending still comes from the device.
        return sendJson(res, 200, { ok: true, cancel: !!entry.run_.cancelled });
      }

      return sendErr(res, 404, 'no such device route');
    }

    // ---- rounds: work this host does on a schedule
    if (req.method === 'GET' && p === '/v1/rounds') {
      return sendJson(res, 200, { rounds: listRounds().map(roundView) });
    }
    if (req.method === 'POST' && p === '/v1/rounds') {
      const body = JSON.parse(await readBody(req) || '{}');
      const built = buildRound(body);
      if (built.error) return sendErr(res, 400, built.error);
      return sendJson(res, 201, roundView(saveRound(built.round)));
    }
    if ((m = p.match(/^\/v1\/rounds\/([0-9a-f-]{36})(\/.*)?$/))) {
      const roundId = m[1]; const rsub = m[2] || '';
      const round = loadRound(roundId);
      if (!round) return sendErr(res, 404, 'no such round');

      if (req.method === 'GET' && rsub === '') return sendJson(res, 200, roundView(round));

      if (req.method === 'PATCH' && rsub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        // ⚠ RE-READ AFTER THE AWAIT. `round` above was loaded before the body
        // arrived, and a phone sends the whole prompt on save, so the window is
        // every PATCH. A run finishing inside it had its record erased — runs
        // 1 -> 0, lastRun back to null — AFTER its push had gone out; a run
        // STARTING inside it got currentChatId reset to null, which defeats the
        // "previous run is still going" guard and puts two live claude processes
        // on the same act work while the row reads not-running.
        const current = loadRound(roundId);
        if (!current) return sendErr(res, 404, 'no such round');
        const patched = applyRoundPatch(current, body);
        if (patched.error) return sendErr(res, 400, patched.error);
        return sendJson(res, 200, roundView(saveRound(patched.round)));
      }
      if (req.method === 'DELETE' && rsub === '') {
        // Stop the work before removing the schedule. Deleting a Round used to
        // leave its run with no surface at all — absent from /v1/rounds and from
        // /v1/chats, nothing in either client to press, holding a slot in the
        // pool until the 2-hour hard cap. For an act Round, "delete the schedule"
        // has to stop what it is doing right now.
        const live = round.currentChatId && activeRuns.get(round.currentChatId);
        if (live) {
          log(`round ${roundId} deleted while running — cancelling chat ${round.currentChatId}`);
          try { cancelRun(live); } catch (e) { log(`round ${roundId}: cancel failed (${e.message})`); }
        }
        // The Round goes; its past runs are ordinary chats and are left alone, so
        // deleting a schedule never destroys the reports it already produced.
        try { fs.unlinkSync(roundPath(roundId)); } catch { /* already gone */ }
        return sendJson(res, 200, { ok: true });
      }
      if (req.method === 'POST' && rsub === '/run') {
        const started = fireRound(round, { manual: true });
        if (started.error) return sendErr(res, started.code || 500, started.error);
        return sendJson(res, 202, { ok: true, chatId: started.chatId });
      }
      /**
       * "I have read this and dealt with it."
       *
       * ⚠ THE GAP THIS FILLS: a report that says `action` is TRUE the moment it
       * is written and stays true forever, because nothing could ever say
       * otherwise. The row held a red mark about findings the owner had already
       * read, worked through, and in some cases fixed — and the only thing that
       * would clear it was the next run, which for still-open findings simply
       * said `action` again. A signal that cannot be answered stops being a
       * signal.
       *
       * Recorded on the RUN, so firing again clears it with no code to remember.
       * The report itself is untouched: this marks that somebody has seen it, and
       * never edits what it said.
       */
      if (req.method === 'POST' && rsub === '/ack') {
        const body = JSON.parse(await readBody(req) || '{}');
        const ack = body.acknowledged !== false;
        // ⚠ Re-read AFTER the await. `round` was loaded before the body was
        // read, and a run can finish in that window — writing the stale snapshot
        // back would resurrect the previous report over the new one. Same defect
        // that erased a Round's seal and its just-recorded run.
        const fresh = loadRound(roundId);
        if (!fresh) return sendErr(res, 404, 'no such round');
        if (!fresh.lastRun) return sendErr(res, 409, 'this round has no report to mark');
        const at = ack ? Math.floor(Date.now() / 1000) : null;
        const updated = updateRound(roundId, (r) => {
          if (!r.lastRun) return;
          r.lastRun.acknowledgedAt = at;
          // And in the history, matched by the chat the run happened in, so the
          // two copies of one run cannot disagree about whether it was read.
          const twin = (Array.isArray(r.runs) ? r.runs : [])
            .find((x) => x && x.chatId === r.lastRun.chatId && x.at === r.lastRun.at);
          if (twin) twin.acknowledgedAt = at;
        });
        return sendJson(res, 200, updated || fresh);
      }
      return sendErr(res, 404, 'no such round route');
    }

    if (req.method === 'GET' && p === '/v1/chats') return sendJson(res, 200, { chats: listChats() });

    if (req.method === 'POST' && p === '/v1/chats') {
      const body = JSON.parse(await readBody(req) || '{}');
      // A LOCAL-family model first: picking the row IS the host choice, so the
      // daemon resolves the machine itself, forces ask-mode, and refuses at the
      // button when the machine cannot serve — never a silent fall-through.
      if (isLocalFamily(body.model)) {
        const r = resolveLocalModel(body.model);
        if (r.error) return sendErr(res, 400, r.error);
        if (typeof body.host === 'string' && body.host && body.host !== 'local' && body.host !== r.deviceId) {
          return sendErr(res, 400, `this model runs on ${r.device.name} — the model row already chooses the machine`);
        }
        if (body.mode === 'act') return sendErr(res, 400, 'local models run ask-only — switch to Ask');
        const served = devicesLib.canServe(r.device, Date.now());
        if (!served.ok) return sendErr(res, 409, served.reason);
        const evL = effortDecision(body.effort);
        if (evL.error) return sendErr(res, 400, evL.error);
        const nowL = Math.floor(Date.now() / 1000);
        const metaL = {
          id: crypto.randomUUID(),
          title: roundsLib.oneLine(body.title, 80) || null,
          mode: 'ask',
          host: r.deviceId,
          model: r.id,
          effort: evL.effort,
          createdAt: nowL,
          updatedAt: nowL,
          claudeSessionId: null,
          lastSnippet: null,
          turns: 0,
        };
        saveMeta(metaL);
        return sendJson(res, 201, metaL);
      }
      // Decided before anything is built: an unknown model or effort id is a 400
      // at the button, never a silent fall-through to the host default.
      const mv = modelDecision(body.model);
      if (mv.error) return sendErr(res, 400, mv.error);
      const ev = effortDecision(body.effort);
      if (ev.error) return sendErr(res, 400, ev.error);
      const mode = body.mode === 'act' ? 'act' : 'ask';
      const now = Math.floor(Date.now() / 1000);
      // WHERE this chat runs, decided once and for the chat's life. Checked here
      // rather than at first message so "that machine is asleep" is answered by
      // the button that made the chat, not by a message that seems to vanish.
      let host = 'local';
      if (typeof body.host === 'string' && body.host && body.host !== 'local') {
        const dev = (deviceState.devices || {})[body.host];
        if (!dev) return sendErr(res, 404, 'no such device');
        const verdict = devicesLib.canRun(dev, mode, Date.now());
        if (!verdict.ok) return sendErr(res, 409, verdict.reason);
        host = body.host;
      }
      const meta = {
        id: crypto.randomUUID(),
        title: roundsLib.oneLine(body.title, 80) || null,
        mode,
        host,
        model: mv.model,
        effort: ev.effort,
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
          hostName: hostNameFor(meta.host),
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
        // Sealed means finished: a Round's run answered its goal and closed. It is
        // kept so it can be read, not continued — and refusing here is what makes
        // "auto end" true rather than merely a label on a row.
        if (meta.sealed) {
          return sendErr(res, 409, 'this run has finished and is kept for review — start a new chat to continue');
        }
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
        // Anywhere, not here: a chat pinned to a device must reach that device
        // whether the message came from a phone, a queue drain or a Round.
        const started = startRunAnywhere(meta, text);
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
        const remote = !!(meta.host && meta.host !== 'local');
        // Checked BEFORE claudeSessionId: a remote chat should show the message
        // you just sent while the device is still picking the job up, and the
        // session id only arrives with the run's first event.
        if (remote) {
          // ABSENCE FIRST, then validity. `Number(null)` is 0, not NaN, so a
          // finite-check on a missing param reads as "0" — which made both offset
          // and until zero on an ordinary read, and `until` wins, so the window
          // collapsed to nothing and the conversation came back empty. Exactly
          // the symptom this route is here to fix.
          const num = (name) => {
            const raw = u.searchParams.get(name);
            if (raw == null) return null;
            const n = Number(raw);
            return Number.isFinite(n) ? n : null;
          };
          const t = transcriptFromMessages(meta, {
            offset: num('offset'),
            until: num('until'),
            limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
          });
          return sendJson(res, 200, {
            ...t,
            events: t.events.concat(queuedEvents(meta, t.events.length)),
            modelDisplay: formatModel(t.model),
            running: meta.running,
            mode: meta.mode,
            claudeSessionId: meta.claudeSessionId ?? null,
            host: meta.host,
            hostName: hostNameFor(meta.host),
            pending: (meta.pending || []).length,
          });
        }
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
        // A sealed run takes no more messages, and a suggestion is an offer to
        // SEND one — the chip fills a composer that is not there. Found by driving
        // the phone: a finished round showed "This round has finished" above two
        // perfectly tappable suggestions.
        if (meta.sealed) return sendJson(res, 200, { suggestions: [], reason: 'sealed' });
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
        cancelRun(run_);
        return sendJson(res, 200, { ok: true });
      }
      if (req.method === 'PATCH' && sub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        // Validated BEFORE the mutator runs: a 400 PATCH must not half-apply the
        // rest of the body, and the updateMeta callback cannot return an error.
        let mv = null;
        // An UNSTARTED chat may re-decide its model freely, INCLUDING across
        // the local/claude boundary and between machines. The pin below
        // protects HISTORY — a transcript that lives on the machine that ran
        // it — and a chat with no turns, no claude session and nothing in
        // flight has none. Without this, both clients' "New chat, then pick
        // the model" flow was refused with an instruction to start the new
        // chat the user was already looking at.
        const unstarted = !meta.turns && !meta.claudeSessionId && !activeRuns.has(id);
        if (unstarted && 'model' in body) {
          if (isLocalFamily(body.model)) {
            // Crossing ONTO a machine: same decision as at creation — same
            // resolution, same at-the-button refusals, same forced ask.
            if (body.mode === 'act') return sendErr(res, 400, 'local models run ask-only — switch to Ask');
            const r = resolveLocalModel(body.model);
            if (r.error) return sendErr(res, 400, r.error);
            const served = devicesLib.canServe(r.device, Date.now());
            if (!served.ok) return sendErr(res, 409, served.reason);
            mv = { model: r.id, host: r.deviceId, forceAsk: true };
          } else if (isLocalFamily(meta.model)) {
            // Leaving a machine (including clearing back to the default):
            // becomes a plain claude chat on this host.
            mv = modelDecision(body.model);
            if (mv.error) return sendErr(res, 400, mv.error);
            mv = { model: mv.model, host: 'local' };
          } else {
            // claude -> claude: the host is untouched on purpose — an
            // Ask-here chat pointed at a device keeps its device.
            mv = modelDecision(body.model);
            if (mv.error) return sendErr(res, 400, mv.error);
          }
        // A STARTED local-family chat is pinned to its machine: the model row
        // WAS the host choice, the transcript lives there, so the only legal
        // model change is another model on the same machine, and act-mode can
        // never arrive.
        } else if (isLocalFamily(meta.model)) {
          if (body.mode === 'act') {
            return sendErr(res, 409, `this chat runs on ${hostNameFor(meta.host) || 'a local model'}, which is ask-only`);
          }
          if ('model' in body) {
            if (!isLocalFamily(body.model)) {
              return sendErr(res, 409, 'this chat is pinned to its machine — start a new chat to use Claude');
            }
            const r = resolveLocalModel(body.model);
            if (r.error) return sendErr(res, 400, r.error);
            if (r.deviceId !== meta.host) {
              return sendErr(res, 409, 'start a new chat to use a different machine');
            }
            mv = { model: r.id };
          }
        } else if ('model' in body && isLocalFamily(body.model)) {
          return sendErr(res, 409, 'a local model is a machine choice — start a new chat to move there');
        } else if ('model' in body) {
          mv = modelDecision(body.model);
          if (mv.error) return sendErr(res, 400, mv.error);
        }
        let ev = null;
        if ('effort' in body) {
          ev = effortDecision(body.effort);
          if (ev.error) return sendErr(res, 400, ev.error);
        }
        // Applied to a RELOADED meta for the same reason as the queue branch: the
        // snapshot above predates the readBody await, and saving it back reverted
        // whatever the run recorded meanwhile. The worst case was specific and
        // silent — a mode toggle landing across the run's init event wrote
        // claudeSessionId back to null, so the next turn spawned without
        // --resume and the chat lost its entire conversation history.
        let changed = false;
        const updated = updateMeta(id, (fresh) => {
          if (roundsLib.oneLine(body.title, 80)) {
            fresh.title = roundsLib.oneLine(body.title, 80); changed = true;
          }
          // Model and effort apply to the NEXT turn; an in-flight run keeps what
          // it started with, since the flags are fixed at spawn.
          if (mv) {
            fresh.model = mv.model; changed = true;
            // Set only by an unstarted-chat re-decision: crossing onto a
            // machine carries its host and is always ask; crossing back
            // carries 'local'. A plain model change touches neither.
            if (mv.host !== undefined) fresh.host = mv.host;
            if (mv.forceAsk) fresh.mode = 'ask';
          }
          if (ev) { fresh.effort = ev.effort; changed = true; }
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
  // The state-dir watch drives auto soft-end too, which must respond within a
  // second regardless of whether alerts are enabled — so start it here rather
  // than only inside startAlertWatcher. Idempotent (guards on stateWatcher), and
  // its alertTick call is a cheap no-op while alerts are off.
  startStateWatch();
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
