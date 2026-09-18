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
  extractLoginUrl, parseStatusLine, loginPaneState, parseModelPicker, sameConsent,
} = require('./lib/pane');
const { parseAskSidecar, fuseAskPrompt, degradedAskCard, parsePlanSidecar } = require('./lib/ask');
const { readTranscript, liveActivity } = require('./lib/transcript');
const { summarizeUsage } = require('./lib/usage');
const { normalizePlan } = require('./lib/plan');
const { AccountStore, fingerprint, sameAccount, normUuid } = require('./lib/accounts');
const oauthRefresh = require('./lib/oauth-refresh');
const oauthlock = require('./lib/oauthlock');
const { formatModel, discoverModels, parseModelId } = require('./lib/models');
const sessreg = require('./lib/session-registry');
const { pushPending, takePending, clearPending, drainPending, queuedEvents } = require('./lib/chatqueue');
const { digest } = require('./lib/watch');
const { decideAlerts, routeAlerts, telegramText, pruneSent, carryRunStarts } = require('./lib/alerts');
const clientsLib = require('./lib/clients');
const roundsLib = require('./lib/rounds');
const scratchpadsLib = require('./lib/scratchpads');
const archiveLib = require('./lib/archive');
const devicesLib = require('./lib/devices');
const consolesLib = require('./lib/consoles');   // next to devices on purpose: it is the registry devices is NOT (lib/consoles.js)
const { taskDirFor, parsePs, scanTasks, extractBgIds } = require('./lib/tasks');
const { agentsDirFor, listAgents, listAgentFiles } = require('./lib/agents');
const { sessionGraph, sessionOverview, CACHE_MAX: GRAPH_CACHE_MAX } = require('./lib/sessiongraph');
// Projects: the name grammars, the manifest contract and its parser, the
// membership join across three registries, and the dashboard rollup. Pure, so
// "never join a member by the native row's `tmux` field" and "the LAST tagged
// block wins" are asserted in test/projects.test.js rather than discovered on a
// cluster of twelve live sessions.
const projectsLib = require('./lib/projects');
const { suggestionContext, buildPrompt, parseSuggestions } = require('./lib/suggest');
const { FIELDS: POLISH_FIELDS, buildPolishPrompt, parsePolish } = require('./lib/polish');
// Only `agedLimits` is still called from here: the account-switch DECISION moved
// inside lib/headroom's arbiter, which imports the rest of this module itself so
// its rules and its anti-flap guards stay exactly what they were.
const { agedLimits } = require('./lib/autoswitch');
const pushLib = require('./lib/pushtokens');
const { trySender } = require('./lib/fcm');
const { createPending, stepSoftEnd } = require('./lib/softend');
// Typing rules: the caps, the tmux command-line budget, the turn-boundary and
// modal readers, and the queue's decisions. Pure, so they are asserted in
// test/typing.test.js rather than discovered on a live pane.
const typing = require('./lib/typing');
// The usage model and the arbiter: percentages and settings in, a list of
// actions out. Pure, so every judgment call it makes is asserted in
// test/headroom.test.js rather than discovered on a live account.
const headroomLib = require('./lib/headroom');
// Keep-awake: whether to spend one tiny request on starting a 5-hour window
// nobody is using yet. Pure — the vetoes, the quiet-hours arithmetic and the
// argv are all asserted in test/keepawake.test.js, which matters more here than
// anywhere else in the daemon because this is the one lane that costs money
// unprompted.
const keepAwakeLib = require('./lib/keepawake');
// The sentinel files the hook gate watches, and the held/ directory it writes.
const sentinelsLib = require('./lib/sentinels');
// Auto-resume: reading a 429 stall off a transcript, deciding whether Claude
// Code's OWN wait covers it, and what appd should do when it does not. Pure, so
// every rule is asserted in test/resume.test.js rather than on a live stall the
// host sees a handful of times a year.
const resumeLib = require('./lib/resume');
// The quick-action templates: the wording either client puts in front of a
// quoted selection. Pure — the rules live there so the two clients cannot
// disagree about them; the file and the route live here.
const quickLib = require('./lib/quickactions');

const VERSION = '3.3.0';
const PORT = Number(process.env.HUGINN_APPD_PORT || 8787);
const DATA_DIR = process.env.HUGINN_APPD_DATA || '/var/lib/huginn-appd';
const UPLOADS_DIR = path.join(DATA_DIR, 'uploads');
// Where Claude Code puts its own per-session scratchpad on this host — the
// directory the assistant writes screenshots and rendered charts into. A ROOT
// for GET /v1/files/image and nothing else; it is never written to from here.
// Env-settable so a test can point it at a scratch dir rather than depend on
// the operator's real one, and harmless when it does not exist: lib/files drops
// a root it cannot realpath instead of comparing the literal string.
const CLAUDE_SCRATCH_DIR = process.env.HUGINN_APPD_CLAUDE_SCRATCH || '/tmp/claude-0';

// Generous, because a router or NVR backup is tens of megabytes and the body is
// streamed straight to disk rather than held in memory.
const UPLOAD_MAX_BYTES = 128 * 1024 * 1024;
// What uploads are accepted — the rules live in lib/uploads (family matching +
// filename fallback), extracted after the exact-match table refused real .txt
// and .csv files: Android providers report mimes like text/comma-separated-
// values, or nothing at all, and exact-match punished the user for their file
// manager's vocabulary.
const { uploadExtFor, isReadable, contentTypeForUpload, createUploadPruner } = require('./lib/uploads');
// Serving a HOST file the assistant named by path — the resolver behind
// GET /v1/files/image. Kept out of here because it is all rules and no HTTP,
// and because a containment check that cannot be unit-tested is a containment
// check nobody re-reads. NOT lib/desktop's resolveArtifact: that one defends
// itself with a filename regex and says in its own comment that it needs no
// realpath, which is true only because the SERVER names those files.
const filesLib = require('./lib/files');
// The cap on an image SERVED back (not on one stored — an upload may be 128 MB).
// A client is drawing this inline in a transcript; past this it is a screenshot
// of a mistake, and the 413 says so. Env-settable so a route test can prove the
// 413 with a small file instead of writing twelve megabytes to prove arithmetic.
const IMAGE_SERVE_MAX_BYTES = Number(process.env.HUGINN_APPD_IMAGE_SERVE_MAX)
  || filesLib.IMAGE_SERVE_MAX_BYTES;
// The desktop update channel — manifest + installers served from disk, stocked
// by mobile/scripts/release-desktop.sh via local moves.
//
// ONE of them. There were two, and keeping them apart was load-bearing: the
// Electron client polled /v1/desktop and installed whatever it found, so a
// Compose build landing there would have replaced a running application with a
// different one. The Electron client was deleted on 2026-08-27 by owner
// directive — strictly Compose — and /v1/desktop went with it. Nothing routes
// to DATA_DIR/desktop any more; a directory of that name left on a deployed
// host is stale bytes no route can reach.
const desktopLib = require('./lib/desktop');
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
 * small transcoded JPEGs; only manual deletion removes them.
 *
 * THROTTLED to one sweep a minute (lib/uploads.createUploadPruner). It still
 * hangs off POST /v1/uploads rather than a timer — a dir that only grows when
 * the feature is used only needs sweeping then — but multi-attach turns one
 * attach into ten POSTs inside a second, and ten full readdir+stat sweeps of the
 * same directory, to delete the same nothing, on the event loop that is also
 * streaming those uploads to disk. Retention is measured in days; the sweep has
 * no deadline and only needs to beat a burst. The rules, and the throttle, live
 * in the lib so uploads.test.js can assert them with a readdirSync spy.
 */
const pruneUploads = createUploadPruner({
  dir: UPLOADS_DIR,
  fs, path,
  keepMs: UPLOAD_KEEP_DAYS * 24 * 60 * 60 * 1000,
});
const TOKEN_FILE = process.env.HUGINN_APPD_TOKEN_FILE || '/etc/huginn-appd/token';
// A test knob only in production (the default is fixed): the route tests point it
// at a scratch dir so they never write state files into the live daemon's
// watched directory on the same host.
const STATE_DIR = process.env.HUGINN_APPD_STATE_DIR || '/run/huginn-claude-state';
// A test knob only, empty in production. When set, EVERY tmux invocation this
// daemon makes is pinned to a private `-L <name>` server instead of the default
// socket that the interactive `cc` sessions (and the live daemon) share. The
// route tests set it to a per-pid value so the throwaway sessions they create —
// and any they LEAK when a test child is SIGKILLed before its after() hook can
// fire — live on a socket nobody reads, and never surface in the desktop's
// session list as `life-<pid>-*` / `ov*_<pid>` noise. Empty means the default
// socket, i.e. exactly the production behaviour. `-L` (not TMUX_TMPDIR) because
// an inherited $TMUX from the launching pane overrides TMUX_TMPDIR but not -L.
const TMUX_SOCKET = process.env.HUGINN_APPD_TMUX_SOCKET || '';
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

// The scratchpad frames, same argument as the attachment markers below: a chat
// list entry titled with the first line of an attached page is about the page,
// not about what was asked. The literals are lib/scratchpads.js's own — see the
// ⚠ there — and :core's ScratchpadRules carries the third copy, byte for byte.
//
// The optional ` #xxxxxx` is the tag a page mints for itself when its own
// CONTENT holds a line beginning "[End scratchpad". `\2` backreferences the tag
// the OPEN carried, so a tagged frame is closed only by its own marker and the
// pasted one inside it is just more content. (A backreference to a group that
// did not participate matches the empty string, which is exactly the untagged
// case: `[End scratchpad]` and nothing else.)
//
// Non-greedy on the close, which is the opposite of what rounds.js chose and
// right for the opposite reason: this is display only, so matching the FIRST
// closing line can at worst leave a tail of the page visible, while matching the
// last could swallow words the person actually typed.
// The SESSION frame is deliberately left alone: it names a path and never
// carries content, so nothing in it can close it early and it never gets a tag.
const CHAT_FRAME_RE = /\[Scratchpad "([^"\n]{1,60})"( #[0-9a-f]{6})?\]\n[\s\S]*?\n\[End scratchpad\2\]\n*/g;
const SESSION_FRAME_RE = /\[Scratchpad "([^"\n]{1,60})" at [^\]\n]*\]\n*/g;

/**
 * User text as a title or snippet should read. The attachment marker is
 * plumbing for Claude — a chat list entry titled
 * "[Attached image at /var/lib/huginn-appd/uploads/img-17852…" is where the
 * daemon stored a file, not what the conversation is about.
 */
function humanizeUserText(t) {
  let s = String(t || '')
    .replace(/\[Attached image at [^\]]+\]/g, '\u{1F4F7} photo')
    .replace(/\[Attached file at [^\]]+\]/g, '\u{1F4CE} file');
  // ⚠ BOTH MARKERS PRESENT BEFORE THE SCAN IS ATTEMPTED AT ALL. CHAT_FRAME_RE
  // holds a `[\s\S]*?` between two literals, so on text with many opening
  // markers and no closing one the engine restarts a walk to end-of-string at
  // every one of them — quadratic in the length of a message a person can paste.
  // This runs on the title of every chat in the list. The guard costs two
  // indexOf scans and changes nothing about what matches: neither pattern can
  // fire without the literal it is testing for.
  if (s.includes('[Scratchpad "')) {
    if (s.includes('[End scratchpad')) s = s.replace(CHAT_FRAME_RE, '\u{1F4DD} $1\n');
    s = s.replace(SESSION_FRAME_RE, '\u{1F4DD} $1\n');
  }
  s = s.trim();
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

/**
 * Socket isolation for tests (see TMUX_SOCKET): pin every tmux call to a
 * private `-L` server. `-L` is a server flag, so it goes before the tmux
 * subcommand. Empty TMUX_SOCKET (production) leaves argv untouched.
 *
 * ONE place, used by both run() and runStdin(), so a socket change lands once
 * rather than in whichever of the two a later reader happened to edit.
 */
function tmuxArgs(args) {
  return TMUX_SOCKET ? ['-L', TMUX_SOCKET, ...args] : args;
}

function run(cmd, args, opts = {}) {
  if (cmd === 'tmux') args = tmuxArgs(args);
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: 10_000, maxBuffer: 4 * 1024 * 1024, ...opts },
      (err, stdout, stderr) => resolve({ err, stdout: stdout ?? '', stderr: stderr ?? '' }));
  });
}

/**
 * run(), with something on the child's STDIN.
 *
 * `tmux load-buffer -b <name> -` is the whole reason this exists: a message on
 * stdin has no length limit, while the same text in argv is bounded by tmux's
 * ~16 KB command line (and by the OS above that). run() cannot write stdin at
 * all, so a "just use run()" delivery path is capped at 16 KB forever.
 *
 * The stdin error handler is not optional: if tmux exits before reading the
 * whole payload the write EPIPEs, and an unhandled 'error' on that stream takes
 * the daemon down rather than failing the send.
 */
function runStdin(cmd, args, input, opts = {}) {
  if (cmd === 'tmux') args = tmuxArgs(args);
  return new Promise((resolve) => {
    const child = execFile(cmd, args, { timeout: 10_000, maxBuffer: 4 * 1024 * 1024, ...opts },
      (err, stdout, stderr) => resolve({ err, stdout: stdout ?? '', stderr: stderr ?? '' }));
    if (child.stdin) {
      child.stdin.on('error', () => { /* EPIPE: the exit code below is the verdict */ });
      child.stdin.end(input);
    }
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
    return { error: `${devicesLib.machineDisplayName(deviceState, d)} does not serve "${slug}" — it advertises: ${(d.models || []).map((x) => x.slug).join(', ') || '(nothing)'}` };
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
 *
 * The word it reports is the flat file's, EXCEPT where a prompt sidecar says a
 * question is waiting — see withPendingQuestion, which is the whole reason this
 * is the one door every reader comes through.
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
      return withPendingQuestion(name, ofThisIncarnation(name, {
        state: o.state || null,
        sessionId: o.sessionId || null,
        transcript: o.transcript || null,
        cwd: o.cwd || null,
        stateSince: o.ts || mtime,
      }));
    } catch { /* fall through to the bare-word path */ }
  }
  return withPendingQuestion(name, ofThisIncarnation(name,
    { state: raw, sessionId: null, transcript: null, cwd: null, stateSince: mtime }));
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

// -------------------------------------------------------- per-session notes
//
// The goals and notes a PERSON writes against a session — the overview surface
// is somewhere to rest during a long run, and a rest stop with nowhere to write
// down what you are waiting for is a dashboard.
//
// ⚠ KEYED ON THE CLAUDE SESSION ID, NEVER THE TMUX NAME. tmux names are reused
// and the hook's state files outlive the sessions that wrote them (see
// ofThisIncarnation) — 24 state files for 5 live sessions on this host, the
// oldest a month dead. A notes file keyed on the name would hand the next
// session called `dev` the previous one's goals, and then overwrite them.

const SESSION_META_DIR = path.join(DATA_DIR, 'session-meta');

const MAX_GOALS = 2_000;
const MAX_NOTES = 20_000;

function sessionMetaPath(id) { return path.join(SESSION_META_DIR, `${id}.json`); }

function loadSessionMeta(id) {
  if (!id || !/^[0-9a-zA-Z_-]{1,64}$/.test(id)) return null;
  try { return JSON.parse(fs.readFileSync(sessionMetaPath(id), 'utf8')); } catch { return null; }
}

/** tmp+rename at 0600, like every other store here. */
function saveSessionMeta(meta) {
  fs.mkdirSync(SESSION_META_DIR, { recursive: true });
  const file = sessionMetaPath(meta.sessionId);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(meta, null, 2), { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  return meta;
}

/**
 * Reload, change, save — the funnel updateMeta and updatePad use, for the same
 * reason. Two clients autosaving the same page is the ordinary case here, and a
 * meta captured before an awaited request body is a snapshot: writing it back
 * would drop whatever the other device saved in the gap.
 */
function updateSessionMeta(id, mutate) {
  const m = loadSessionMeta(id) || { sessionId: id, goals: '', notes: '', updatedAt: 0 };
  mutate(m);
  m.sessionId = id;
  m.updatedAt = Math.floor(Date.now() / 1000);
  return saveSessionMeta(m);
}

function sessionMetaView(id) {
  const m = loadSessionMeta(id);
  return {
    goals: (m && m.goals) || '',
    notes: (m && m.notes) || '',
    // null means FOLLOW THE GLOBAL, which is a different answer from false and
    // has to survive the round trip: a per-session `false` set on the phone must
    // not be indistinguishable from "never chosen" the next time the global is
    // turned on.
    autoResume: m && typeof m.autoResume === 'boolean' ? m.autoResume : null,
    updatedAt: (m && m.updatedAt) || 0,
  };
}

// ---- archive store: sessions ended on purpose --------------------------------
//
// One directory per archived session under DATA_DIR/archive/<claude session id>:
// `record.json` (the card, tmp+rename at 0600 like every other store here) and
// `transcript.jsonl` (a COPY of Claude Code's own, taken at archive time).
//
// ⚠ THE COPY IS THE WHOLE POINT, not a nicety. `cleanupPeriodDays` (21 on this
// host) means Claude Code deletes its own transcript while the archive row sits
// there still offering `claude --resume <uuid>` — a revive that succeeds, opens
// in the right directory, and has no memory of anything. See lib/archive.js.
//
// Keyed by the CLAUDE SESSION ID and never the tmux name, for the reason
// session-meta gives above: names are reused here within hours, and an archive
// filed under a reused name would hand the next `dev` a stranger's conversation.

const ARCHIVE_DIR = path.join(DATA_DIR, 'archive');

/**
 * The per-transcript copy cap, overridable for a host with less room than this
 * one — and by the route suite, which needs a cap it can actually exceed without
 * writing a 32 MB fixture. Not a URL and not a credential path: it decides how
 * many bytes of a local file are copied to another local file.
 */
const ARCHIVE_TRANSCRIPT_CAP = Number(process.env.HUGINN_APPD_ARCHIVE_CAP) > 0
  ? Number(process.env.HUGINN_APPD_ARCHIVE_CAP)
  : archiveLib.TRANSCRIPT_CAP_BYTES;

function archiveDirFor(id) { return path.join(ARCHIVE_DIR, id); }
function archiveRecordPath(id) { return path.join(archiveDirFor(id), 'record.json'); }
function archiveTranscriptPath(id) { return path.join(archiveDirFor(id), 'transcript.jsonl'); }

function loadArchive(id) {
  if (!sessreg.UUID_RE.test(String(id || ''))) return null;
  try { return JSON.parse(fs.readFileSync(archiveRecordPath(id), 'utf8')); } catch { return null; }
}

/** tmp+rename at 0600, like every other store here: a reader never sees half a row. */
function saveArchive(rec) {
  const file = archiveRecordPath(rec.id);
  fs.mkdirSync(archiveDirFor(rec.id), { recursive: true });
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(rec, null, 2), { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  refreshArchivedIds();
  return rec;
}

function listArchives() {
  let dirs = [];
  try { dirs = fs.readdirSync(ARCHIVE_DIR); } catch { return []; }
  const out = [];
  for (const d of dirs) {
    const rec = loadArchive(d);
    if (rec && rec.id) out.push(rec);
  }
  return out;
}

/** Reload, change, save — the updateRound/updatePad funnel, for the same reason. */
function updateArchive(id, mutate) {
  const rec = loadArchive(id);
  if (!rec) return null;
  mutate(rec);
  return saveArchive(rec);
}

/** The row AND its transcript copy. The copy is the bulk; leaving it would be a leak. */
function removeArchive(id) {
  if (!sessreg.UUID_RE.test(String(id || ''))) return false;
  try { fs.rmSync(archiveDirFor(id), { recursive: true, force: true }); } catch { return false; }
  refreshArchivedIds();
  return true;
}

/**
 * The claude session ids that GET /v1/sessions must not report.
 *
 * Normally a no-op — an archived session has been through hardEndSession and is
 * not in tmux to be listed. It earns its place in the two windows where it is
 * not: a crash between writing the row and the kill landing, and a name reused
 * fast enough to be listed before the reconcile notices. Kept in memory because
 * listSessions runs on a five-second poll from every open client and must not
 * grow a directory scan.
 *
 * Only FINISHED archives are here. A row whose `endedAt` is still null belongs to
 * a session that is genuinely alive, and hiding a live session is the failure
 * listSessions names in its own comment ("a monitoring app that drops a session
 * from the list is worse than one that cannot open it"). A REVIVED row is out for
 * the same reason: the session it names is back, and it is the archive list that
 * marks it as running, not this one that hides it.
 */
const archivedIds = new Set();

function refreshArchivedIds() {
  archivedIds.clear();
  for (const rec of listArchives()) {
    if (rec.endedAt && !rec.revivedAt) archivedIds.add(rec.id);
  }
}

/**
 * Copies a transcript into the archive, tail-first when it is over the cap.
 *
 * ⚠ THE TAIL IS SNAPPED FORWARD TO A RECORD BOUNDARY. A jsonl file opened at an
 * arbitrary byte begins with half a record, and readTranscript's own boundary
 * guard would then drop a real message off the top of the window — so the copy
 * starts after the first newline in it, and the archive holds whole records only.
 *
 * Best effort: a transcript that cannot be copied costs the revive its memory
 * later, never the archive itself now. Said out loud in the log and on the row.
 */
function copyTranscriptForArchive(id, src) {
  let sfd, dfd;
  try {
    const size = fs.statSync(src).size;
    const w = archiveLib.transcriptWindow(size, ARCHIVE_TRANSCRIPT_CAP);
    fs.mkdirSync(archiveDirFor(id), { recursive: true });
    const dst = archiveTranscriptPath(id);
    const tmp = `${dst}.tmp`;
    sfd = fs.openSync(src, 'r');
    let start = w.start;
    if (w.truncated && start > 0) {
      const probe = Buffer.alloc(Math.min(1 << 20, size - start));
      const n = fs.readSync(sfd, probe, 0, probe.length, start);
      const nl = probe.subarray(0, n).indexOf(0x0a);
      if (nl >= 0) start += nl + 1;
    }
    dfd = fs.openSync(tmp, 'w', 0o600);
    const buf = Buffer.alloc(1 << 20);
    let pos = start;
    let written = 0;
    for (;;) {
      const n = fs.readSync(sfd, buf, 0, buf.length, pos);
      if (n <= 0) break;
      fs.writeSync(dfd, buf, 0, n);
      pos += n;
      written += n;
    }
    fs.closeSync(dfd); dfd = undefined;
    fs.renameSync(tmp, dst);
    return { bytes: written, truncated: w.truncated };
  } catch (e) {
    log(`archive: could not copy the transcript for ${id}: ${e.message}`);
    return { bytes: 0, truncated: false };
  } finally {
    if (sfd !== undefined) { try { fs.closeSync(sfd); } catch { } }
    if (dfd !== undefined) { try { fs.closeSync(dfd); } catch { } }
  }
}

/**
 * Puts the archived transcript back where Claude Code looks for it, so that
 * `claude --resume <id>` has something to resume.
 *
 * ⚠ AN EXISTING FILE IS NEVER OVERWRITTEN — not even an older one. Claude Code's
 * own copy is the file it is about to read and append to, while ours may be a
 * truncated TAIL of a conversation that was over the size cap; writing one over
 * the other would silently delete history to restore history. Absent is the only
 * case this handles, and absent is the only case that needs handling: the reason
 * this function exists is the 21-day sweep, which removes the file outright.
 */
function restoreArchivedTranscript(id, cwd) {
  const copy = archiveTranscriptPath(id);
  if (!fs.existsSync(copy)) return { restored: false, reason: 'no transcript was kept for this archive' };
  const existing = findTranscriptFile(id);
  if (existing) return { restored: false, reason: 'Claude Code still has its own copy' };
  const slug = String(cwd || WORKDIR).replace(/\//g, '-');
  const dir = path.join(os.homedir(), '.claude', 'projects', slug);
  const dst = path.join(dir, `${id}.jsonl`);
  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.copyFileSync(copy, `${dst}.tmp`);
    fs.renameSync(`${dst}.tmp`, dst);
    log(`archive: restored the kept transcript for ${id} to ${dst}`);
    return { restored: true, reason: null, path: dst };
  } catch (e) {
    log(`archive: could not restore the transcript for ${id}: ${e.message}`);
    return { restored: false, reason: e.message };
  }
}

/**
 * Is THIS conversation running right now, and under what name?
 *
 * ⚠ BOTH NAMES ARE CHECKED, and only one of them is obvious. A revive under a
 * taken name lands on `<name>2` and stamps `revivedAs`, so a row matched on
 * `tmuxName` alone reports a session that came back ten seconds ago as still
 * archived — and offers Revive again, which is how a second Claude ends up
 * appending to one transcript. Caught by a screenshot fixture, not by a test,
 * which is why there is now a test.
 *
 * The ID has to match either way: a tmux name is reused within hours here, and a
 * stranger holding the old name is not this archive coming back.
 */
function archiveLiveName(rec, liveIds) {
  if (!liveIds) return null;
  for (const name of [rec.revivedAs, rec.tmuxName]) {
    if (name && liveIds.get(name) === rec.id) return name;
  }
  return null;
}

/**
 * Brings the store back to the cap, oldest archive first.
 *
 * Rows never time-expire — an archive whose promise is "still here when you want
 * it" cannot carry a clock — so this is the only thing that ever removes one, and
 * it says which, because a conversation disappearing silently is the failure mode
 * a cap has.
 */
function enforceArchiveCap() {
  const drop = archiveLib.evictions(listArchives());
  for (const rec of drop) {
    removeArchive(rec.id);
    log(`archive: at the ${archiveLib.MAX_ARCHIVES}-row cap, dropped the oldest `
      + `(${rec.tmuxName || rec.id}${rec.title ? `: ${rec.title}` : ''})`);
  }
  return drop.length;
}

// ---- durable session registry (survives a reboot) -------------------------
//
// The one store here that is deliberately keyed by tmux NAME rather than by Claude
// session id: after a power cut the id is exactly what we have lost and are trying
// to recover, and the name is the only handle that persists across the reboot. It
// lives in DATA_DIR (not /run) for the same reason. See lib/session-registry.js for
// the why; this is just the fs around it. Registered on create, dropped on kill,
// and reconciled against live tmux on a timer so an id learned late (or a session
// started outside the daemon) still lands before the next reboot needs it.

const SESSION_REGISTRY_FILE = path.join(DATA_DIR, 'active-sessions.json');

function loadRegistry() {
  try {
    const o = JSON.parse(fs.readFileSync(SESSION_REGISTRY_FILE, 'utf8'));
    return o && o.sessions && typeof o.sessions === 'object' ? o.sessions : {};
  } catch { return {}; }
}

/** tmp+rename at 0600, like every other store here. Best effort: a registry that
 * fails to save costs a restore after the NEXT reboot, never a live session now. */
function saveRegistry(sessions) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(`${SESSION_REGISTRY_FILE}.tmp`,
      JSON.stringify({ version: 1, sessions }, null, 2), { mode: 0o600 });
    fs.renameSync(`${SESSION_REGISTRY_FILE}.tmp`, SESSION_REGISTRY_FILE);
  } catch (e) { log(`session registry: save failed: ${e.message}`); }
}

function registryAdd(name, fields) {
  if (!name || sessreg.isReserved(name)) return;
  const s = loadRegistry();
  const now = Math.floor(Date.now() / 1000);
  if (!s[name]) s[name] = { name, createdAt: now, claudeSessionId: null, cwd: null };
  Object.assign(s[name], fields || {}, { updatedAt: now });
  saveRegistry(s);
}

function registryRemove(name) {
  const s = loadRegistry();
  if (s[name]) { delete s[name]; saveRegistry(s); }
}

/** Follow a session rename so the entry is not orphaned under the old name (which
 * would then be pruned as "ended") while the live session keeps running unrecorded. */
function registryRename(from, to) {
  if (from === to) return;
  const s = loadRegistry();
  if (!s[from]) return;
  if (!sessreg.isReserved(to)) s[to] = { ...s[from], name: to };
  delete s[from];
  saveRegistry(s);
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
  let rows = [];
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
      // Messages waiting on a turn boundary or a modal. The clients draw a
      // "queued" mark from this, so a send that is legitimately waiting reads
      // as waiting rather than as a send that silently did nothing.
      pendingSends: pendingSendCount(name),
      // Context-window pressure (filled from the pane on a preview list) and
      // whether this session is compacting (cheap marker check, so it works even
      // on the quick non-preview list).
      contextPercent: null,
      compacting: isCompacting(name),
      state: st.state ?? null,
      stateSince: st.stateSince ?? null,
      claudeSessionId: st.sessionId ?? null,
      // Which model this run is on, whether the daemon moved it there, and
      // whether it is waiting for a window. Cheap by construction: headroom.json
      // in memory plus one session-meta read, never a transcript walk.
      headroom: headroomForSession(st.sessionId ?? null),
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

  // An ARCHIVED conversation is not a session any more. Normally this removes
  // nothing — an archive goes through hardEndSession, so tmux has already
  // forgotten it — and it exists for the window where that is not yet true: a
  // crash between the row being written and the kill landing. Matched on the
  // CLAUDE SESSION ID, never the name, so a reused name cannot hide a live
  // stranger; and only finished, un-revived archives are in that set, so this can
  // never hide a session that is genuinely still yours to use (see archivedIds).
  const hidden = archivedIds.size
    ? rows.filter((r) => r.claudeSessionId && archivedIds.has(r.claudeSessionId)).map((r) => r.name)
    : [];
  if (hidden.length) {
    rows = rows.filter((r) => !(r.claudeSessionId && archivedIds.has(r.claudeSessionId)));
    log(`sessions: ${hidden.join(', ')} still in tmux but archived; listed under /v1/archive instead`);
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

  // An isolated test socket wants a plain private server, NOT the shared
  // huginn-tmux.scope: that unit name is global, so wrapping a per-pid test
  // server in it would collide with (or be blocked by) the live daemon's scope.
  if (TMUX_SOCKET) { await run('tmux', ['start-server']); return; }

  const r = await run('systemd-run',
    ['--scope', '--quiet', '--collect', `--unit=${TMUX_SCOPE}`, 'tmux', 'start-server']);
  if (r.err) {
    log(`tmux: could not start the server in its own scope (${(r.stderr || r.err.message || '').trim().slice(0, 120)}); it will inherit this daemon's`);
    return;
  }
  log(`tmux: server started in ${TMUX_SCOPE}.scope, independent of this daemon`);
}

/**
 * Every live tmux session mapped to the Claude conversation running in it.
 *
 * Null on a tmux read failure, never an empty map: the archive routes use this to
 * decide "is this conversation already back" and "which names are taken", and
 * read as "nothing is live" a failed listing would answer both questions wrongly
 * — a second Claude on one transcript, under a name already in use. The same
 * failure-to-observe guard listSessions and the registry reconcile carry.
 */
async function liveSessionIds() {
  const { err, stdout, stderr } = await run('tmux', ['list-sessions', '-F', '#{session_name}\t#{session_created}']);
  if (err) {
    if (/no server running|no such file or directory/i.test(stderr || '')) return new Map();
    return null;
  }
  const out = new Map();
  const reg = loadRegistry();
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created] = line.split('\t');
    if (!name) continue;
    rememberBorn(name, created);
    const st = readSessionState(name);
    // ⚠ THE REGISTRY IS THE FALLBACK, AND IT MATTERS FOR EXACTLY ONE WINDOW. A
    // just-revived session has no state file yet — the title hook writes one on
    // its first event, seconds later — and for those seconds the archive list
    // would show its row as revivable again and offer a SECOND Claude on the
    // same transcript. The revive route records the id on the registry at the
    // moment it creates the session, which is the only thing that knows sooner.
    const entry = reg[name];
    out.set(name, (st && st.sessionId) || (entry && entry.claudeSessionId) || null);
  }
  return out;
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

/**
 * The beat on the BRACKETED-PASTE path — a different number for a different
 * reason, and the two must not be folded into one constant.
 *
 * `paste-buffer -p` wraps the payload in ESC[200~ … ESC[201~, so the Enter that
 * follows arrives after an explicit paste-END marker and the TUI's parser can
 * never read it as paste content. Measured: 5/5 clean with no beat at all. The
 * 50 ms is insurance against a future TUI change, not a fix for an observed
 * race — whereas SUBMIT_BEAT_MS above is load-bearing on the send-keys
 * fallback, where there is no marker and a beatless Enter is inserted into the
 * composer as a literal newline (proven at the byte level: got == payload+"\n").
 */
const PASTE_BEAT_MS = 50;

const SOFT_END_PHRASE = process.env.HUGINN_APPD_SOFT_END_PHRASE ||
  'Finish outstanding items, commit your work, and prepare to end the session.';
// Auto-end is ON by default (owner decision 2026-08-10): after the phrase lands,
// the session ends on its own the next time it settles. A deployment that wants
// "phrase only, I end it myself" sets HUGINN_APPD_SOFT_END_AUTO=0 in a drop-in.
const SOFT_END_AUTO = process.env.HUGINN_APPD_SOFT_END_AUTO !== '0';

const softEnds = new Map(); // session name -> pending record (lib/softend)

/**
 * Put TEXT into a pane and submit it — the one delivery path.
 *
 *   printf %s "$text" | tmux load-buffer -b <buf> -     # stdin: no length limit
 *   tmux paste-buffer -b <buf> -p -d -t '=<name>:'      # -p bracketed, -d self-deleting
 *   sleep PASTE_BEAT_MS
 *   tmux send-keys -t '=<name>:' Enter
 *
 * Why not chunked `send-keys -l`, which this replaces: measured on a 20,100-char
 * message, bracketed paste is 0.011 s against 0.48-1.72 s, two tmux calls
 * against three to eleven, has no size ceiling at all, needs no timing beat, and
 * draws ONE `[Pasted text +400 lines]` placeholder in the pane instead of ten
 * fragments. Byte-exactness is identical.
 *
 * ⚠ `-p` IS MANDATORY. Plain `paste-buffer` replaces every `\n` with `\r` —
 * the pane renders three correct lines and the transcript stores
 * `'line one\rline two\rline three'`. It looks right and arrives wrong, with no
 * error anywhere. `-r` is byte-safe but has no paste-end marker, so it
 * reintroduces exactly the timing dependency `-p` removes. Never call
 * paste-buffer without `-p`.
 *
 * ⚠ `-d` deletes the buffer after pasting, so a message does not linger in the
 * tmux buffer stack where any pane can paste it back. Every failure path issues
 * delete-buffer itself, best effort — a load that succeeded and a paste that
 * did not leaves the text sitting there otherwise.
 *
 * The fallback runs only when load-buffer itself fails (no tmux server, a
 * refused buffer): chunked send-keys with the load-bearing 150 ms beat, and
 * only for text that fits one command line. Anything longer is a 503 rather
 * than a message delivered in visibly mangled pieces.
 */
/** One plain capture of a pane, as lines, or null when tmux cannot answer. */
async function capturePaneLines(name) {
  const cap = await run('tmux', ['capture-pane', '-p', '-t', `=${name}:`]);
  if (cap.err) return null;
  return cap.stdout.replace(/\n$/, '').split('\n');
}

/**
 * Wait until the pane visibly holds what was just pasted into it.
 *
 * ⚠ THE ONE THING THAT MAKES THE ENTER LAND ON THE RIGHT SIDE OF A STARTUP.
 * Measured against the real `claude` 2.1.258 (lib/typing.js, PASTE SETTLED):
 * bytes pasted between ~2.0 s and ~0.85 s before the TUI paints sit in the pty,
 * get rendered into the composer the instant it appears, and lose the `\r` that
 * rode with them — text in the box, unsent, no transcript record. Waiting for
 * the text to be ON SCREEN before pressing Enter puts the keystroke after the
 * paint in every one of those runs, and the whole band submits.
 *
 * Bounded, and the bound is not a failure: a pane that does not echo (a shell
 * with `stty -echo`, a pane tmux stopped answering for) gets its Enter anyway
 * after PASTE_SETTLE_MS, because a person's message is delivered or it is an
 * error, never quietly binned. It says so in the journal with the pane's own
 * bottom rows, which is the only place a reader can see what was there instead.
 */
async function waitForPasteToLand(name, text, before) {
  if (typing.pasteIndistinguishable(before, text)) {
    return { landed: true, waitedMs: 0, lines: before };
  }
  const started = Date.now();
  let lines = before;
  for (;;) {
    const got = await capturePaneLines(name);
    if (got) {
      lines = got;
      if (typing.pasteLanded(got, text)) return { landed: true, waitedMs: Date.now() - started, lines: got };
    }
    if (Date.now() - started >= typing.PASTE_SETTLE_MS) {
      return { landed: false, waitedMs: Date.now() - started, lines };
    }
    await sleep(typing.PASTE_SETTLE_POLL_MS);
  }
}

/**
 * After Enter: did the composer let go of the message?
 *
 * Only asked when the paste was SEEN to land — a paste nobody could see leaving
 * a composer nobody could see is not news, and saying so on every shell send
 * would bury the line that matters. `null` from `composerCleared` means this
 * pane has no composer to speak about, which is the same non-answer.
 */
async function confirmSubmitted(name, text) {
  const started = Date.now();
  let lines = null;
  for (;;) {
    const got = await capturePaneLines(name);
    if (got) {
      lines = got;
      const cleared = typing.composerCleared(got, text);
      if (cleared !== false) return { ok: true, waitedMs: Date.now() - started, lines: got };
    }
    if (Date.now() - started >= typing.SUBMIT_CONFIRM_MS) {
      return { ok: false, waitedMs: Date.now() - started, lines };
    }
    await sleep(typing.SUBMIT_CONFIRM_POLL_MS);
  }
}

/** One bracketed paste of `text` into a pane. The half of delivery that repeats. */
async function pasteOnce(name, text) {
  const target = `=${name}:`;
  const buf = typing.bufferName();
  const lb = await runStdin('tmux', ['load-buffer', '-b', buf, '-'], text);
  if (lb.err) {
    await run('tmux', ['delete-buffer', '-b', buf]);     // best effort: a partial load
    return { ok: false, fallback: true, stderr: lb.stderr };
  }
  const pb = await run('tmux', ['paste-buffer', '-b', buf, '-p', '-d', '-t', target]);
  if (pb.err) {
    await run('tmux', ['delete-buffer', '-b', buf]);     // -d never ran
    return { ok: false, fallback: false, stderr: pb.stderr };
  }
  return { ok: true };
}

/**
 * The paste never appeared. What may be done about it?
 *
 * ⚠ 3.1.1 DETECTED THIS AND DID NOT FIX IT. The lost band — bytes pasted ~0.95 s
 * to ~0.3 s before the TUI paints — is read and discarded by whatever drains the
 * pty before Claude attaches: no composer text, no turn, no transcript record.
 * The settle wait can only time out on it, and the Enter that followed went into
 * a composer that was by then up and EMPTY, so it submitted nothing. One journal
 * line, one message gone.
 *
 * The one safe recovery is a re-paste into an empty composer, and `recoveryDecision`
 * is the rule (lib/typing.js, THE LOST BAND). It is a RE-PASTE and never a second
 * bare Enter: an Enter on its own either submits nothing, or submits whatever
 * somebody else has typed since.
 *
 * ONCE. A second failure is reported, not retried — a loop here is a pane getting
 * the same message three times the moment its first paste was merely slow.
 */
async function recoverLostPaste(name, text, settle) {
  const fresh = (await capturePaneLines(name)) || settle.lines;
  const what = typing.recoveryDecision(fresh);
  if (what === 'blind') {
    log(typing.pasteLostLogLine(name, settle.waitedMs, fresh));
    return { landed: false, enter: true, recovered: false };
  }
  if (what === 'leave') {
    log(typing.pasteLeftAloneLogLine(name, settle.waitedMs, fresh));
    return { landed: false, enter: false, recovered: false };
  }
  log(typing.pasteResentLogLine(name, settle.waitedMs, fresh));
  const again = await pasteOnce(name, text);
  if (!again.ok) {
    // The re-paste could not even be loaded. Nothing was typed, so there is
    // nothing to submit and an Enter would only fire at an empty box.
    log(`typing: ${name}: the re-paste failed too (${(again.stderr || '').trim().slice(0, 120)})`);
    return { landed: false, enter: false, recovered: false };
  }
  const second = await waitForPasteToLand(name, text, fresh);
  if (!second.landed) log(typing.pasteLostLogLine(name, second.waitedMs, second.lines));
  return { landed: second.landed, enter: true, recovered: true };
}

async function sendTextToPane(name, text, { submit = true } = {}) {
  const target = `=${name}:`;
  // Read BEFORE the load, not after: the only use of this capture is to tell a
  // pane that already showed this text from one that has just received it, and
  // after the paste there is no telling.
  const before = submit ? await capturePaneLines(name) : null;
  const first = await pasteOnce(name, text);
  if (!first.ok && !first.fallback) {
    return { ok: false, code: 503, message: 'could not reach the pane buffer', stderr: first.stderr };
  }
  if (first.ok) {
    if (!submit) return { ok: true, how: 'paste' };
    const settle = await waitForPasteToLand(name, text, before);
    let landed = settle.landed;
    let recovered = false;
    let press = true;
    if (!settle.landed) {
      const r = await recoverLostPaste(name, text, settle);
      landed = r.landed;
      recovered = r.recovered;
      press = r.enter;
    }
    if (!press) {
      return { ok: true, how: 'paste', settled: false, settleMs: settle.waitedMs, submitted: false, recovered };
    }
    await sleep(PASTE_BEAT_MS);
    const en = await run('tmux', ['send-keys', '-t', target, 'Enter']);
    if (en.err) return { ok: false, code: 500, message: `tmux: ${(en.stderr || '').trim()}`, stderr: en.stderr };
    let submitted = null;
    if (landed) {
      const conf = await confirmSubmitted(name, text);
      submitted = conf.ok;
      if (!conf.ok) log(typing.submitStalledLogLine(name, conf.waitedMs, conf.lines));
    }
    return { ok: true, how: 'paste', settled: landed, settleMs: settle.waitedMs, submitted, recovered };
  }
  log(`typing: load-buffer failed for ${name} (${(first.stderr || '').trim().slice(0, 120)}); falling back to send-keys`);
  if (!typing.sendKeysFits(text, target)) {
    return { ok: false, code: 503, message: 'could not reach the pane buffer', stderr: first.stderr };
  }
  for (const chunk of typing.chunks(text, target)) {
    const r = await run('tmux', ['send-keys', '-t', target, '-l', '--', chunk]);
    if (r.err) return { ok: false, code: 503, message: 'could not reach the pane buffer', stderr: r.stderr };
    await sleep(SUBMIT_BEAT_MS);
  }
  if (!submit) return { ok: true, how: 'send-keys' };
  const en = await run('tmux', ['send-keys', '-t', target, 'Enter']);
  if (en.err) return { ok: false, code: 500, message: `tmux: ${(en.stderr || '').trim()}`, stderr: en.stderr };
  return { ok: true, how: 'send-keys' };
}

/**
 * Type a line into a pane and submit it, for the callers that send a FIXED
 * phrase (soft end, /compact) rather than a queued message.
 *
 * Same delivery as sendTextToPane — these used chunk-free `send-keys -l`, which
 * is the path with the newline trap — kept in run()'s `{err, stderr}` shape
 * because that is what its two callers translate into an HTTP error. Not
 * queued: both are deliberate interventions whose whole point is to arrive now.
 */
async function sendLineToPane(name, text) {
  const r = await sendTextToPane(name, text);
  if (r.ok) return { err: null, stderr: '' };
  return { err: new Error(r.message), stderr: r.stderr || r.message };
}

// ---- the send queue --------------------------------------------------------
//
// A message delivered mid-turn is not merely late. The TUI enqueues it and then
// SPLICES it into the running turn — the transcript records
// {"operation":"remove","reason":"absorbed_mid_turn"} and the first message's
// instruction is never carried out (measured: a turn told to reply FIRSTDONE
// never emitted it, because a second message landed while a tool was running).
// A modal is worse: it swallows the next message whole and leaves no transcript
// trace at all. So the queue lives HERE, daemon-side, and not in the TUI whose
// own queue has the absorb semantics we are avoiding.
//
// Per session, FIFO, in memory only. A send does not survive a daemon restart
// and is not meant to (W2, out of scope) — what it must never do is arrive at
// the wrong moment.

const sendQueues = new Map();   // session name -> { entries, blockedBy, delivering, lastError, timer, pumping }

/**
 * Sessions appd has just launched `claude` into, and when.
 *
 * ⚠ THE WHOLE POINT IS WHAT IS **NOT** IN HERE. `POST /v1/sessions` answers 201
 * about 30 ms after `tmux new-session -d` returns, and `claude` takes another
 * two seconds to draw anything that reads stdin — so a client that creates a
 * session and sends into it immediately pastes into a pane with no application
 * in it, and both the text and its Enter vanish (measured: t+1.1 s leaves no
 * composer text, no turn and no transcript record at all). `lib/typing.js`
 * `startingUp` holds those sends; this map is how it knows which sessions the
 * rule may apply to.
 *
 * Only the two places appd itself starts `claude` write here. A tmux session
 * the owner made in a terminal is never marked, so a send into a plain shell —
 * which has no composer and never will — behaves exactly as it always has.
 *
 * In memory, and deliberately: a daemon restarted inside the two-second window
 * simply falls back to the old behaviour for that one send, which is the bug
 * this fixes and not a new one. Entries are pruned on write, so the map holds
 * at most the sessions created in the last STARTUP_GRACE_MS.
 */
const launchingAt = new Map();  // session name -> ms epoch when appd ran `claude` in it

/**
 * The grace, with an override.
 *
 * `HUGINN_APPD_STARTUP_GRACE_MS` exists for two readers. A host slow enough that
 * `claude` needs longer than twenty seconds to paint wants it BIGGER — the
 * measured production line `no composer 22s after launch` is exactly that host,
 * and the send that followed it 82 ms later is exactly the loss. And the delivery
 * tests want it ZERO, because their whole subject is what happens when a send
 * reaches a composer-less pane ANYWAY, which is the state a grace of zero puts
 * every pane in permanently. Nonsense (a negative, a word) falls back rather than
 * disabling the gate by typo.
 */
const STARTUP_GRACE_MS = (() => {
  const raw = process.env.HUGINN_APPD_STARTUP_GRACE_MS;
  if (raw == null || raw === '') return typing.STARTUP_GRACE_MS;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : typing.STARTUP_GRACE_MS;
})();

/**
 * The sessions being held by the UNMARKED rule, so its journal line is written
 * once per session rather than once per 400 ms poll.
 */
const unmarkedHeld = new Set();

function markLaunching(name) {
  const now = Date.now();
  for (const [k, t] of launchingAt) {
    if (now - t > STARTUP_GRACE_MS) launchingAt.delete(k);
  }
  launchingAt.set(name, now);
}

/** How long ago appd launched `claude` here, or null if it never did. */
function launchAgeMs(name) {
  const t = launchingAt.get(name);
  return t == null ? null : Date.now() - t;
}

/**
 * The two facts the UNMARKED startup rule needs from tmux, in one call.
 *
 * `#{session_created}` is when the session was born — the launch time the missing
 * mark would have carried — and `#{pane_start_command}` is what the pane was told
 * to run, which is the positive evidence that separates a booting `claude` from
 * the many other things that draw an empty pane. `server/bin/cc` starts every one
 * of its sessions with the literal `claude; exec "$SHELL" -l`, so the fact is
 * there for exactly the sessions this rule is about.
 *
 * ⚠ THE TWO display-message TRAPS, BOTH AGAIN. The target needs the TRAILING
 * COLON or tmux answers every field blank at exit 0, and the exit status cannot
 * answer existence — so the echoed NAME is the proof the target hit something.
 *
 * Only called for a session with no mark and something queued, which is rare and
 * short-lived; the seconds-resolution birth time is two orders under the twenty
 * seconds it is compared against.
 */
async function paneStartFacts(name) {
  const { err, stdout } = await run('tmux', ['display-message', '-p', '-t', `=${name}:`,
    '#{session_name}\t#{session_created}\t#{pane_start_command}']);
  if (err) return null;
  const [found, created, ...rest] = (stdout || '').replace(/\n$/, '').split('\t');
  if (found !== name) return null;
  rememberBorn(name, created);
  const born = Number(created);
  return {
    ageMs: Number.isFinite(born) && born > 0 ? Math.max(0, Date.now() - born * 1000) : null,
    startCommand: rest.join('\t'),
  };
}

/**
 * How the queue learns a session's model family, without importing the
 * headroom machinery that owns that question.
 *
 * W1's ladder drops a queued `kind:'model'` job when the session already
 * changed family (a native consent swap beat us to it, and appd never fights a
 * native switch). Headroom installs the probe at startup; unset, the family
 * rule simply never fires, which is the correct behaviour for a daemon built
 * without it.
 */
let familyProbe = null;
function setFamilyProbe(fn) { familyProbe = typeof fn === 'function' ? fn : null; }

function queueFor(name) {
  let q = sendQueues.get(name);
  if (!q) {
    q = { entries: [], blockedBy: null, delivering: false, lastError: null, timer: null, pumping: false };
    sendQueues.set(name, q);
  }
  return q;
}

/** For the session list's `pendingSends` badge. Declaration, not const: listSessions is above. */
function pendingSendCount(name) {
  const q = sendQueues.get(name);
  return q ? q.entries.length : 0;
}

/** The last 64 KB of a session's jsonl transcript, plus its size, or null. */
function transcriptTail(file, from = null, bytes = 64 * 1024) {
  let fd;
  try {
    const st = fs.statSync(file);
    const start = from == null ? Math.max(0, st.size - bytes) : Math.max(0, Math.min(from, st.size));
    const len = st.size - start;
    if (len <= 0) return { text: '', size: st.size };
    fd = fs.openSync(file, 'r');
    const b = Buffer.alloc(len);
    fs.readSync(fd, b, 0, len, start);
    return { text: b.toString('utf8'), size: st.size };
  } catch { return null; } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { } }
  }
}

function transcriptPath(name) {
  const st = readSessionState(name);
  return st && st.transcript ? st.transcript : null;
}

/** Where the transcript ENDS right now — the watermark an entry's drop rules read from. */
function transcriptSize(file) {
  try { return fs.statSync(file).size; } catch { return null; }
}

/**
 * Both gates, read fresh.
 *
 * Gate 1 (turn boundary) is the transcript, because the pane cannot be trusted
 * for liveness: this build's busy marker is a randomised spinner verb. A
 * session with NO transcript — a plain shell, or a Claude session whose title
 * hook has not fired yet — passes: there are no turns to be mid-way through,
 * and refusing to type into a shell would break every non-Claude pane the app
 * can open.
 *
 * Gate 2 (no modal) is the pane, because the transcript cannot see a dialog at
 * all. Only a DIALOG blocks — `paneReadyForInput`'s 'busy' (no caret) is not a
 * liveness verdict and must not be used as one here.
 *
 * Gate 3 is the title hook's state file, returned RAW because its verdict is
 * per-entry: `{state:"idle", ts}` only releases a send it is NEWER than, and the
 * pump knows each entry's `at`. It is the second boundary source and the only
 * one a session whose transcript never gets a turn marker has at all.
 *
 * Gate 4 is STARTUP, and it is the one gate that is about the pane
 * having an application in it at all. It comes from the same capture as gate 2
 * — a caret anywhere in the bottom region means Claude has painted its box —
 * and applies only to the sessions appd started `claude` in itself.
 */
async function checkGates(name) {
  const file = transcriptPath(name);
  let idle = true;
  let lastKind = null;
  if (file) {
    const tail = transcriptTail(file);
    if (tail) {
      const b = typing.boundaryFromTail(tail.text);
      idle = b.idle;
      lastKind = b.lastKind;
    }
  }
  const cap = await run('tmux', ['capture-pane', '-p', '-t', `=${name}:`]);
  const lines = cap.err ? null : cap.stdout.replace(/\n$/, '').split('\n');
  const paneWhy = lines ? typing.paneReadyForInput(lines).why : null;
  // A capture that FAILED says nothing about startup — the session is probably
  // gone, and holding a send on a pane we cannot read would be a wait with no
  // end. Fall through to the old behaviour and let delivery report the failure.
  const starting = lines ? await startupGate(name, lines) : false;
  return { idle, lastKind, paneWhy, starting, sessionState: readSessionState(name) };
}

/**
 * Is this send waiting on `claude` to come up — and has the wait ended?
 *
 * Both answers come out of one call because the mark has to be RETIRED
 * somewhere, and the moment the composer appears is the only honest place: from
 * then on the pane is a Claude pane like any other and this rule must never
 * speak about it again. The grace expiring retires it too, with a journal line,
 * because a `claude` that never drew a composer has fallen through to the login
 * shell — the send still goes (a person's message is delivered or it is an
 * error) and the reader deserves to know which pane it went into.
 */
async function startupGate(name, lines) {
  const composer = typing.composerDrawn(lines);
  const ageMs = launchAgeMs(name);
  if (ageMs != null) {
    if (typing.startingUp({ launching: true, composer, ageMs, graceMs: STARTUP_GRACE_MS })) return true;
    launchingAt.delete(name);
    unmarkedHeld.delete(name);
    if (!composer) {
      log(`typing: ${name}: no composer ${Math.round(ageMs / 1000)}s after launch; `
        + 'sending into the pane as it is (claude may have exited to the shell)');
    }
    return false;
  }
  // ⚠ NO MARK IS NOT NO EVIDENCE. `cc` / `huginn <name>` start tmux themselves
  // (`server/bin/cc`: `tmux new-session -A -s … 'claude; exec "$SHELL" -l'`), so
  // the sessions a person makes from a phone or a laptop shell have never been in
  // `launchingAt` at all — and they race exactly the same way. tmux's own
  // `#{session_created}` is the launch time the mark would have recorded.
  const facts = await paneStartFacts(name);
  const bornMs = facts ? facts.ageMs : null;
  const starting = typing.startingUnmarked({
    composer,
    shell: typing.shellPrompt(lines),
    claudeStart: typing.startsClaude(facts && facts.startCommand),
    ageMs: bornMs,
    graceMs: STARTUP_GRACE_MS,
  });
  if (starting) { unmarkedHeld.add(name); return true; }
  // One line when a hold ENDS without a composer to show for it, and only for a
  // session this rule actually held — the same promise the marked path makes, and
  // written once rather than once per 400 ms poll.
  if (unmarkedHeld.delete(name) && !composer) {
    log(`typing: ${name}: no composer ${Math.round((bornMs || 0) / 1000)}s after the tmux `
      + 'session was created; sending into the pane as it is (this session was not started by appd)');
  }
  return false;
}

/**
 * Has a human spoken since this entry was queued? Only the appended bytes are read.
 *
 * ⚠ THE PATH IS RE-RESOLVED WHEN THE ENTRY HAS NONE. `entry.transcript` is
 * captured once, at enqueue time — and a session whose title hook has not fired
 * yet has no transcript then, so this returned false for the whole life of that
 * entry and the 'human' drop rule was simply inert for it. That is the one rule
 * that stops appd talking over the owner. A transcript that appears later is
 * read from its START (offset 0): everything in it arrived after the entry was
 * queued, which is exactly the window the rule is about.
 */
function humanSpokeSince(entry, name) {
  let file = entry.transcript;
  let from = entry.transcriptAt;
  if (!file && name) {
    file = transcriptPath(name);
    from = 0;
    if (file) { entry.transcript = file; entry.transcriptAt = 0; }
  }
  if (!file || from == null) return false;
  const tail = transcriptTail(file, from);
  if (!tail || !tail.text) return false;
  return typing.hasHumanUserRecord(tail.text);
}

function armQueueTimer(name) {
  const q = queueFor(name);
  if (q.timer || !q.entries.length) return;
  q.timer = setTimeout(() => {
    q.timer = null;
    pumpQueue(name).catch((e) => log(`typing: pump failed for ${name}: ${e.message}`));
  }, typing.TYPING_POLL_MS);
  if (q.timer.unref) q.timer.unref();
}

/**
 * Release everything at the head of the queue whose gates are open, drop what
 * has gone stale, and re-arm the poll if anything is left waiting.
 *
 * Re-entrancy matters: the poll timer and an incoming POST can both land here,
 * and two pumps delivering the same entry is a message sent twice. `pumping` is
 * the lock; the timer simply re-fires 400 ms later.
 */
async function pumpQueue(name) {
  const q = queueFor(name);
  if (q.pumping) return;
  q.pumping = true;
  try {
    while (q.entries.length) {
      const entry = q.entries[0];
      const gate = await checkGates(name);
      const now = Date.now();
      const reason = typing.dropReason(entry, {
        humanSpoke: entry.automated ? humanSpokeSince(entry, name) : false,
        family: entry.automated && familyProbe ? familyProbe(name) : null,
        now,
      });
      if (reason) {
        q.entries.shift();
        q.lastError = typing.dropMessage(reason, entry);
        // ⚠ TWO LINES, AND THE FIRST ONE IS THE POINT. `lastError` lives in a
        // struct only `GET /typing` reads, and nobody polls a session they have
        // stopped expecting an answer from — which is how 43 dropped messages
        // left no trace anywhere. A message leaving this queue undelivered says
        // so in the journal, in one grep-able shape, every time.
        log(typing.dropLogLine(name, entry, reason));
        log(`typing: ${name}: ${q.lastError}`);
        entry.settle({ delivered: false, dropped: reason });
        continue;
      }
      // HOTFIX 3.0.3: a PERSON's message never waits for a turn boundary. 3.0.0
      // held it in this queue until the turn ended — invisible to the sender,
      // and on a session inside a long agent turn "the message just disappears".
      // 2.x typed it at once and Claude Code's OWN queue showed it in the pane
      // and took it up after the turn; that is what the owner wants back. Only
      // the modal gate still holds a human send (text into a dialog is
      // swallowed with no trace). Automated lines keep both gates.
      // A pane SCRIPT (the ladder's picker walk, even when a person asked for it
      // via Undo) keeps the turn gate: a picker opened mid-turn is a modal.
      // AND a person's message does not skip the STARTUP gate. "A human
      // send never waits for a turn" is about Claude being BUSY; it was never
      // about Claude not being there. `gate.starting` rides on both lanes below.
      const humanText = !entry.automated && typeof entry.run !== 'function';
      // 3.0.4: the AUTOMATED lane gets a second boundary source — the title
      // hook's state file. `{state:"idle", ts}` stamped after the send was
      // queued is a turn that demonstrably ended, and it is the ONLY boundary a
      // session whose transcript never gets a turn marker ever has; `attention`
      // is the opposite verdict and holds, because a numbered prompt is on
      // screen and prose typed into one is lost or misread.
      const d = typing.releaseDecision(humanText
        ? { ...gate, idle: true }
        : { ...gate, state: typing.stateVerdict(gate.sessionState, entry.at) });
      if (!d.release) {
        q.blockedBy = d.blockedBy;
        armQueueTimer(name);
        return;
      }
      q.entries.shift();
      q.blockedBy = null;
      q.delivering = true;
      let r;
      try {
        // A JOB rather than a message: the ladder's `/model` picker script is
        // several keystrokes with reads between them, and it needs the SAME two
        // gates and the same drop rules as a send — a picker opened mid-turn is
        // a modal that swallows the next message whole (spike E1), and a ladder
        // job that finds the family already changed must be binned, not run.
        // One queue, two payload shapes; nothing else differs.
        if (typeof entry.run === 'function') r = await entry.run();
        else r = await sendTextToPane(name, entry.text, { submit: entry.submit });
      } catch (e) {
        r = { ok: false, message: (e && e.message) || String(e) };
      } finally { q.delivering = false; }
      if (!r.ok) {
        q.lastError = r.message;
        log(`typing: ${name}: delivery failed: ${r.message}`);
      } else if (q.entries.length === 0) {
        q.lastError = null;
      }
      entry.settle({ delivered: !!r.ok, result: r });
    }
    q.blockedBy = null;
  } finally {
    q.pumping = false;
    if (q.entries.length) armQueueTimer(name);
    // ⚠ AND A QUEUE WHOSE SESSION IS GONE. `lastError` kept the map entry alive
    // so `GET /typing` could still report the failure — but nothing ever removed
    // it afterwards, so a long-lived daemon accumulated one row per session that
    // ever failed a delivery. A session tmux no longer has cannot be polled about.
    else if (!q.lastError || !(await sessionExists(name))) {
      sendQueues.delete(name);
      unmarkedHeld.delete(name);
    }
  }
}

/**
 * Queue a message for a session, and deliver it the moment both gates are open.
 *
 * Resolves as soon as THIS entry's fate is known for the synchronous case (the
 * gates were already open, so the paste has landed) and as soon as it is
 * accepted otherwise — the caller gets `{delivered:false, position}` and the
 * queue keeps working whether or not anyone is still listening. A dropped
 * socket must never lose a send; that is why the progress surface is a poll
 * (`GET /typing`) and not a stream on this request.
 *
 * opts: { automated, origin, kind, family } — W1's automated sends carry all
 * four so the drop rules can tell an owner's message from appd's own.
 */
function enqueueSend(name, text, opts = {}) {
  const q = queueFor(name);
  const file = transcriptPath(name);
  const at = file ? transcriptSize(file) : null;
  const entry = {
    id: crypto.randomBytes(6).toString('hex'),
    text,
    // Set only by enqueueJob: an async function run INSTEAD of pasting text.
    run: typeof opts.run === 'function' ? opts.run : null,
    at: Date.now(),
    automated: !!opts.automated,
    origin: opts.origin || null,
    kind: opts.kind || null,
    family: opts.family || null,
    // Whether the paste is followed by Enter. The Screen tab types without
    // submitting (its Enter arrives as its own key op), every other caller
    // means "send this".
    submit: opts.submit !== false,
    transcript: file,
    transcriptAt: at,
    settle: () => { },
  };
  const done = new Promise((resolve) => {
    let fired = false;
    entry.settle = (v) => {
      if (fired) return;
      fired = true;
      // ⚠ THE CALLER'S RECONCILIATION POINT. enqueueSend resolves after ONE pump
      // pass, so a caller that waits on it learns only "delivered" or "still
      // queued" — and a send that waits ten minutes for a turn boundary and is
      // then dropped used to be reconciled by nobody. Anything that writes state
      // on the strength of a send (the resume record, the ladder row) hooks the
      // real outcome here. Throwing must not take the pump down with it.
      if (typeof opts.onSettle === 'function') {
        try { opts.onSettle(v); } catch (e) { log(`typing: ${name}: settle hook failed: ${e.message}`); }
      }
      resolve(v);
    };
  });
  q.entries.push(entry);
  const position = q.entries.length;
  // One pass now, so a session that is already idle answers `delivered: true`
  // rather than making the client poll for something that has already happened.
  const pass = pumpQueue(name).catch((e) => {
    log(`typing: pump failed for ${name}: ${e.message}`);
  });
  return pass.then(() => {
    const queued = q.entries.indexOf(entry);
    if (queued === -1) {
      // Settled during that pass: delivered, dropped, or failed.
      return done.then((v) => ({
        id: entry.id, position: 0, delivered: !!v.delivered, dropped: v.dropped || null,
        result: v.result || null, queued: q.entries.length, blockedBy: q.entries.length ? (q.blockedBy || null) : null,
      }));
    }
    // `blockedBy` is what the pump just decided about the HEAD of this queue —
    // the same word `GET /typing` will report — so a caller that seeds its wait
    // line from this answer says the same sentence the first poll will.
    return {
      id: entry.id, position: queued + 1, delivered: false, dropped: null, result: null,
      queued: q.entries.length, blockedBy: q.blockedBy || null,
    };
  }).catch(() => ({
    id: entry.id, position, delivered: false, dropped: null, result: null,
    queued: q.entries.length, blockedBy: q.blockedBy || null,
  }));
}

/**
 * Queue a pane SCRIPT — several keystrokes with reads between them — behind the
 * same gates a message waits on.
 *
 * The ladder is the only caller: `/model` opens a picker, the cursor is walked
 * to a row by label, `s` sets the model for this session only. Every one of
 * those keys is destructive if it lands at the wrong moment (a picker opened
 * mid-turn blocks input until somebody answers it, and the keys after it go
 * into the dialog), so the script runs as ONE queue entry and starts only at a
 * turn boundary with a bare caret.
 *
 * `fn` resolves `{ok, ...}` exactly like sendTextToPane, and a throw is caught
 * by the pump rather than killing it.
 */
function enqueueJob(name, fn, opts = {}) {
  return enqueueSend(name, null, { ...opts, run: fn, submit: false });
}

// ---- archiving a session ----------------------------------------------------
//
// An archive is a session ended ON PURPOSE that leaves a tombstone. Everything
// about ending it is the existing path — the soft-end phrase, its guards, its
// settle timer, and hardEndSession as the one true-death boundary — and the only
// new thing is that the card is written just BEFORE the kill.
//
// Before, deliberately. A crash between the two then leaves a row whose session
// is still live, which the list can see and say ("running as x") — where the
// other order would leave a dead session with no row and nothing to bring back,
// which nothing could ever notice.

/**
 * Sessions whose wind-down should end in an archive rather than a plain kill.
 *
 * In memory beside `softEnds`, and for the same reason: the intent only has to
 * outlive the seconds between the phrase being typed and the session settling. A
 * daemon restarted in that window loses the intent and the session is simply not
 * archived — visibly, because it is still there.
 */
const archiveIntents = new Map();   // tmux name -> { note }

/**
 * Writes the card for a live session. Must run BEFORE the kill: everything it
 * reads (the /run state file, the transcript path, the pane's session id) is
 * removed by hardEndSession.
 *
 * Returns `{err}` for a session with nothing to archive — a pane that has never
 * run Claude has no session id, and a row whose resume command cannot work is
 * worse than being told no.
 */
function captureArchive(name, { note = null } = {}) {
  const st = readSessionState(name);
  const id = st && st.sessionId;
  if (!id || !sessreg.UUID_RE.test(id)) {
    return { err: 'no Claude session recorded here yet — there is nothing to bring back, so this can only be ended' };
  }
  // The transcript the hook recorded, or wherever it moved to. Both are checked
  // because a session resumed after a cwd change sits under a different project
  // slug, and findTranscriptFile is the one that knows how to look.
  const recorded = st.transcript && fs.existsSync(st.transcript) ? st.transcript : null;
  const tpath = recorded || findTranscriptFile(id);

  // ONE read of the tail, which is both the card and the preview — the same
  // trick the sessions list uses, at a bigger limit because the last thing SAID
  // can sit behind a run of tool calls.
  let t = null;
  if (tpath) { try { t = readTranscript(tpath, { limit: 60 }); } catch { /* unreadable: not fatal */ } }

  const entry = loadRegistry()[name] || {};
  const copied = tpath ? copyTranscriptForArchive(id, tpath) : { bytes: 0, truncated: false };
  const rec = archiveLib.buildRecord({
    claudeSessionId: id,
    tmuxName: name,
    title: (t && t.title) || null,
    cwd: st.cwd || (t && t.cwd) || entry.cwd || WORKDIR,
    model: (t && t.model) || null,
    effort: (t && t.effort) || null,
    permissionMode: (t && t.permissionMode) || null,
    gitBranch: (t && t.gitBranch) || null,
    archivedAt: Math.floor(Date.now() / 1000),
    lastMessage: t ? archiveLib.lastMessageOf(t.events) : '',
    transcriptPath: tpath,
    transcriptBytes: copied.bytes,
    transcriptTruncated: copied.truncated,
    note,
  });
  saveArchive(rec);
  if (copied.truncated) {
    log(`archive: ${name} transcript was over the ${ARCHIVE_TRANSCRIPT_CAP} byte cap; kept the last ${copied.bytes}`);
  }
  return { rec };
}

/**
 * Card, then kill, then stamp the death.
 *
 * `endedAt` is written LAST and is what separates a finished archive from the
 * crash window above — and it is also what puts the id on the list
 * GET /v1/sessions filters by, so a row is never hidden from the sessions list
 * before the session it names has actually gone.
 */
async function archiveAndEnd(name, opts = {}) {
  const cap = captureArchive(name, opts);
  if (cap.err) return { err: cap.err };
  const { err, stderr } = await hardEndSession(name);
  if (err) return { err: `tmux: ${(stderr || err.message || '').trim() || 'could not end the session'}`, rec: cap.rec };
  const rec = updateArchive(cap.rec.id, (r) => { r.endedAt = Math.floor(Date.now() / 1000); }) || cap.rec;
  enforceArchiveCap();
  log(`archive: ${name} archived as ${rec.id}`);
  return { rec };
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
  // Off the restore list: a session ended on purpose (a DELETE, or an auto
  // wind-down settling here) must not be resurrected by the next reboot. This is
  // the single true-death boundary — a soft-end that only TYPES the wrap-up phrase
  // leaves the session alive and still registered, which is correct, because it is
  // still running and would still be worth bringing back if the power went now.
  registryRemove(name);
  await releaseSize(name).catch(() => { });
  softEnds.delete(name);
  launchingAt.delete(name);
  // An archive intent belongs to a session that was going to be archived when it
  // settled. Whatever just ended it got there first, so the intent is stale —
  // left behind it would archive the NEXT session to take this name.
  archiveIntents.delete(name);
  return { err: null };
}

// ---- session registry reconcile + reboot restore ---------------------------

/**
 * Keep the durable registry in step with what tmux actually has: fold in live
 * sessions (learning a claudeSessionId the moment the title hook writes one, and
 * picking up sessions started outside the daemon) and drop the ones that ended
 * without a kill. Runs on a timer so the NEXT reboot restores the CURRENT set —
 * a resumed session mints a fresh id, and this is what records it.
 *
 * Skipped whole on any tmux read failure. "no server running" and a timed-out
 * fork look alike enough that treating either as "nothing is live" would prune the
 * registry to empty and leave a reboot with nothing to restore — the same
 * failure-to-observe trap listSessions documents.
 */
async function reconcileSessionRegistry() {
  const { err, stdout } = await run('tmux',
    ['list-sessions', '-F', '#{session_name}\t#{session_created}']);
  if (err) return;
  const now = Math.floor(Date.now() / 1000);
  const live = [];
  const liveNames = new Set();
  for (const line of stdout.trim().split('\n')) {
    if (!line) continue;
    const [name, created] = line.split('\t');
    if (!name) continue;
    liveNames.add(name);
    rememberBorn(name, created);
    const st = readSessionState(name) || {};
    live.push({
      name,
      createdAt: Number(created) || now,
      claudeSessionId: st.sessionId || null,
      cwd: st.cwd || null,
    });
  }
  const merged = sessreg.mergeLive(loadRegistry(), live, now);
  const { next, removed } = sessreg.pruneDead(merged, liveNames);
  saveRegistry(next);
  if (removed.length) log(`session registry: dropped ${removed.length} ended session(s): ${removed.join(', ')}`);
}

/**
 * Bring back the sessions that were alive before a reboot.
 *
 * The tmux server lives in its own systemd scope, so it SURVIVES an ordinary
 * `systemctl restart huginn-appd` — in that case the sessions never died and this
 * must do nothing, or it would double-create every one of them. The distinguisher
 * is the server itself: present means an appd-only restart (skip); absent means the
 * box lost power or rebooted and the registry is the only record left (restore).
 *
 * Each session is recreated exactly as the create route would, but running
 * `claude --resume <id>` so the conversation comes back rather than a blank prompt.
 * Best effort per session: one that cannot be recreated is logged and skipped, not
 * fatal to the rest.
 */
async function restoreSessionsAfterReboot() {
  const probe = await run('tmux', ['ls']);
  const serverGone = probe.err && /no server running|no such file or directory/i.test(probe.stderr || '');
  if (!serverGone) return;  // appd-only restart (or a read we cannot trust) — leave live sessions be

  const plan = sessreg.restorePlan(loadRegistry(), new Set());
  if (!plan.length) return;
  log(`session restore: tmux server is gone (reboot?); ${plan.length} recorded session(s) to bring back`);
  await ensureTmuxServerScope();

  let restored = 0, fresh = 0;
  for (const entry of plan) {
    const cwd = entry.cwd || WORKDIR;
    const hasTranscript = !!(entry.claudeSessionId && findTranscriptFile(entry.claudeSessionId));
    const { canResume, command } = sessreg.resumeCommand(entry, hasTranscript);
    const r = await run('tmux', ['new-session', '-d', '-s', entry.name, '-c', cwd, command]);
    if (r.err) {
      log(`session restore: ${entry.name} could not be recreated: ${(r.stderr || r.err.message || '').trim().slice(0, 120)}`);
      continue;
    }
    restored++;
    // Same startup hold as the create route: a restored session is a `claude`
    // that has not come up yet either, and the phone reconnecting after a reboot
    // is exactly when somebody types into one straight away.
    markLaunching(entry.name);
    // ⚠ MARKED AS RESTORED, and auto-resume depends on it. Claude Code's own
    // wait at a usage limit is IN-PROCESS: the process that was waiting died
    // with the box, and the CLI says so in as many words ("Claude Code
    // relaunched during the wait, so the task will not resume on its own").
    // Without this mark appd would see an interactive session, believe the
    // native wait was armed, and sit out its 90-second grace waiting for a
    // continuation that can never come — every time, on every restored session.
    registryAdd(entry.name, { restoredAt: Math.floor(Date.now() / 1000) });
    if (canResume) log(`session restore: ${entry.name} resumed (${entry.claudeSessionId})`);
    else { fresh++; log(`session restore: ${entry.name} restarted fresh (nothing resumable on disk)`); }
  }
  log(`session restore: ${restored}/${plan.length} back${fresh ? `, ${fresh} without a transcript` : ''}`);
}

/** One pass over the pending soft ends; kills the ones that have settled. */
async function softEndTick() {
  if (!softEnds.size) return;
  const now = Date.now();
  for (const [name, pending] of [...softEnds]) {
    const st = readSessionState(name);
    const { pending: next, action } = stepSoftEnd(pending, st ? st.state : null, now);
    if (action === 'kill') {
      // THE ONE HOOK ARCHIVE ADDS TO THIS PATH. A session wound down by
      // POST /archive gets its card written here, in the instant before the
      // kill, because that is the last moment its state file, its transcript
      // path and its session id still exist.
      const intent = archiveIntents.get(name);
      if (intent) {
        log(`soft-end: ${name} settled, archiving`);
        archiveIntents.delete(name);
        const r = await archiveAndEnd(name, intent).catch((e) => ({ err: e.message }));
        if (r.err) log(`archive: ${name} could not be archived on settle: ${r.err}`);
      } else {
        log(`soft-end: ${name} settled, ending`);
        await hardEndSession(name).catch((e) => log(`soft-end kill ${name} failed: ${e.message}`));
      }
    } else if (action === 'cancel') {
      // A wrap-up that turned into a question is not an archive either: the
      // session is alive and waiting on a person, which is the one state this
      // feature refuses to end from.
      log(`soft-end: ${name} asked a question, auto-end cancelled`);
      archiveIntents.delete(name);
      softEnds.delete(name);
    } else if (action === 'expire') {
      log(`soft-end: ${name} never started a run, auto-end dropped`);
      archiveIntents.delete(name);
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
 * When the MAIN THREAD raised a dialog that has not been cleared — its ts, or
 * null. Declarations, not consts: readSessionState is far above.
 *
 * The prompt sidecars are the only marker under STATE_DIR that belongs to the
 * main thread alone. huginn-claude-title writes one on the PreToolUse that
 * RAISES an AskUserQuestion / ExitPlanMode dialog and clears it on that tool's
 * PostToolUse, on the next UserPromptSubmit, on Stop and on SessionEnd —
 * deliberately NOT on some other tool's PreToolUse, because a background agent's
 * tool calls fire in this same session while the question waits.
 */
function askPendingSince(name, sessionId) {
  for (const kind of ['ask', 'plan']) {
    const s = readSidecar(kind, name);
    const ts = s && Number(s.ts);
    if (!ts || Date.now() - ts * 1000 >= SIDECAR_TTL_MS) continue;
    // A sidecar left by a PREVIOUS Claude run under the same tmux name says
    // nothing about this one. Same reasoning as ofThisIncarnation.
    if (sessionId && s.sessionId && s.sessionId !== sessionId) continue;
    return ts;
  }
  return null;
}

/**
 * A question on screen outranks the flat state file.
 *
 * ⚠ THE FLAT STATE FILE IS LAST-WRITER-WINS ACROSS THE WHOLE SESSION, and while
 * the main thread sits on a dialog the only writers left are its background
 * agents. Measured on 2026-09-15 against a live AskUserQuestion (Claude Code
 * 2.1.258), with the dialog on screen throughout:
 *
 *   PreToolUse(AskUserQuestion)     -> running     (the event that RAISES it)
 *   ...six seconds of dialog, state "running"...
 *   Notification                    -> attention
 *   any subagent's Pre/PostToolUse  -> running
 *
 * and it never comes back, because the Notification fires ONCE. So a session
 * held up by a question read as `running` — "working" on both clients, no red
 * row, no "needs you" — and the send queue's modal gate (typing.stateVerdict)
 * opened straight into the dialog, which is where prose goes to be swallowed.
 * The sidecar is the fact the hook kept for exactly this thread; it decides.
 *
 * Only `running` is promoted. `idle` means Stop or the idle nudge landed — the
 * turn is over and nothing is being asked — which is also the floor under a
 * sidecar whose clear was somehow missed.
 */
function withPendingQuestion(name, st) {
  if (!st || st.state !== 'running') return st;
  const since = askPendingSince(name, st.sessionId);
  return since ? { ...st, state: 'attention', stateSince: since } : st;
}

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
  // meta.json is the hottest writer in the store — rewritten on every message,
  // queue push, run event and settle. A bare writeFileSync leaves a truncated file
  // if the daemon is killed (OOM / power cut / ENOSPC) mid-write, and loadMeta then
  // returns null forever: the chat vanishes from every view, its queue and sealed
  // flag are lost, and messages.jsonl is orphaned, with nothing logged. tmp+rename
  // is atomic within a filesystem, so a reader sees the whole old file or the whole
  // new one, never a half. 0o700 on the dir and 0o600 on the file keep a chat
  // transcript off other users even if the data-dir mode is ever loosened.
  const dir = chatDir(meta.id);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const p = metaPath(meta.id);
  fs.writeFileSync(`${p}.tmp`, JSON.stringify(rest, null, 2), { mode: 0o600 });
  fs.renameSync(`${p}.tmp`, p);
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
  // The MACHINE name a person knows: a local chat's host is the serving
  // credential's row, whose "-llm" name the UI no longer shows anywhere —
  // badges and refusals built from this must not resurrect it.
  const d = (deviceState.devices || {})[host];
  return d ? devicesLib.machineDisplayName(deviceState, d) : 'a removed device';
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
  // A chat that pinned a model gets it. One that did not, launched while the
  // Fable weekly window is out of room, is told EXPLICITLY to use the next model
  // down rather than being left to the CLI's silent swap: `-p` has no dialog
  // channel, so the CLI picks from a hardcoded chain with no say from us and no
  // record on the chat row (native-rl §7). `CLAUDE_CODE_NO_MODEL_FALLBACK` is
  // deliberately NOT set — a silent swap beats a dead run, and the variable is
  // documented for lanes that would rather fail.
  const downgrade = meta.model ? null : fableRedDowngrade();
  if (meta.model) args.push('--model', meta.model);
  else if (downgrade) args.push('--model', downgrade);
  if (meta.effort) args.push('--effort', meta.effort);
  if (meta.claudeSessionId) args.push('--resume', meta.claudeSessionId);
  let persona = '';
  try { persona = fs.readFileSync(PERSONA_FILE, 'utf8'); } catch { /* interactive-only host */ }
  if (persona) args.push('--append-system-prompt', persona);
  args.push('--allowedTools', TOOLS[meta.mode] || TOOLS.ask);
  const denied = DISALLOWED[meta.mode] ?? DISALLOWED.ask;
  if (denied) args.push('--disallowedTools', denied);

  const run_ = new Run(chatId);
  // What we ASKED for, so the result can say whether that is what answered.
  run_.askedModel = meta.model || downgrade || null;
  activeRuns.set(chatId, run_);
  if (downgrade) {
    updateMeta(chatId, (m) => { m.ranOn = `${downgrade} (Fable limit)`; });
    log(`chat ${chatId} launched on ${downgrade}: the Fable week is out of room`);
  }
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
      // WHICH MODEL ANSWERED. In `-p` there is no consent dialog — the CLI
      // swaps to a non-gated model silently (`no_dialog_fallback`, native-rl
      // §7) — so the only evidence that a run did not get the model it asked
      // for is the id on its own records.
      if (ev.message && typeof ev.message.model === 'string') run_.sawModel = ev.message.model;
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
      if (ev.is_error && typeof ev.result === 'string') {
        rec.errorText = ev.result.slice(0, 500);
        // Carried to settleRun, which is where a limit stall is recorded: a `-p`
        // run reports the limit HERE, as a result event, and never as the
        // transcript record an interactive session leaves behind.
        run_.limitText = rec.errorText;
      }
      appendMsg(chatId, rec);
      run_.emit('result', rec);
      const finalText = typeof ev.result === 'string' ? ev.result : run_.assistantText;
      const ranOn = ranOnLabel(run_.askedModel, run_.sawModel);
      updateMeta(chatId, (m) => {
        m.updatedAt = ts;
        if (finalText) m.lastSnippet = finalText.slice(0, 120);
        m.turns = (m.turns || 0) + 1;
        if (ranOn) m.ranOn = ranOn;
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
  // A run that died on a usage limit is not finished, it is WAITING. Read before
  // anything else settles, because the evidence is the failure text and the
  // result record, both of which are about to be overwritten by bookkeeping.
  let stalled = false;
  if (!run_.cancelled) {
    const limitText = run_.limitText || failureText || '';
    try { stalled = noteRunStall(chatId, limitText); }
    catch (e) { log(`chat ${chatId}: could not record a limit stall: ${e.message}`); }
  }
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
      // ⚠ A ROUND RUN COUNTS ONCE. Filing the report now and re-running after the
      // reset would put TWO runs in the Round's history for one scheduled job,
      // with the first one reading "hit the usage limit" — and the report
      // notification would go out twice. So a run waiting for a reset is left
      // open: `currentChatId` still points at it, the chat is not sealed, and
      // `finishRoundRun` files exactly one record when the re-run finishes,
      // against the same stored report tag.
      if (stalled) {
        log(`round run ${chatId} held open for a usage-limit re-run`);
        return;
      }
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

/**
 * Persists the registry, atomically — write a temp file, then rename over.
 *
 * ⚠ THIS WAS A BARE writeFileSync, and it was the only state writer here that
 * was: push.json and clients.json have used tmp+rename since they were written.
 * The difference matters because of what the LOADER above does with a file it
 * cannot parse — it starts from empty and says nothing. A crash, a power cut or
 * a full disk partway through this write leaves truncated JSON, and the next
 * start therefore discards EVERY enrolment silently. Every device then looks
 * un-enrolled and re-registers under a fresh id, so the machines come back as
 * new rows with no history while the old rows are simply gone.
 *
 * rename(2) is atomic within a filesystem, so a reader sees either the whole
 * previous file or the whole new one and never a half of either. 0o600 because
 * the same file names every machine that has offered itself to this daemon.
 */
function saveDevices() {
  try {
    fs.writeFileSync(`${DEVICES_FILE}.tmp`, JSON.stringify(deviceState, null, 2), { mode: 0o600 });
    fs.renameSync(`${DEVICES_FILE}.tmp`, DEVICES_FILE);
  } catch (e) { log(`devices: could not persist (${e.message})`); }
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
  const verdict = devicesLib.canRun(device, workMode, now, deviceState);
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
    resumedAfterLimit: !!meta.resumedAfterLimit,
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
  // A Round is rewritten on every fire (currentChatId, nextRunAt, run history), so
  // a torn bare write is a recurring exposure: loadRound swallows the parse error
  // to null, listRounds skips the file, and the scheduled Round silently ceases to
  // exist — the owner's report just stops arriving, with no alert and no log line.
  // Atomic tmp+rename at 0o600, the same shape saveDevices/savePad use.
  fs.writeFileSync(`${roundPath(r.id)}.tmp`, JSON.stringify(r, null, 2), { mode: 0o600 });
  fs.renameSync(`${roundPath(r.id)}.tmp`, roundPath(r.id));
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
function finishRoundRun(meta, failure, { status: statusOverride = null } = {}) {
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
    if (m.type === 'result') {
      if (m.ok === false) resultFailure = m.errorText || 'the run reported an error';
      // ⚠ AND A LATER SUCCESS CLEARS IT. The transcript can hold more than one
      // run since auto-resume landed: a turn that died on a usage limit is
      // re-run on the same conversation, so the failed result of the first
      // attempt is still sitting in `messages.jsonl` when the second one
      // succeeds. Without this the Round filed "run failed: You've hit your
      // session limit" over the top of a run that had just completed normally —
      // and that verdict is what the report notification says.
      else { resultFailure = null; lastError = null; }
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
    // `action` is the default and the right one for a run that CRASHED. A run
    // abandoned because appd could not identify a reset time did not fail — the
    // arrangement did — so the caller may lower it to `attention`.
    if (statusOverride) report.status = statusOverride;
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
   try {
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
      // Record it as a skipped run, not just a log line. A missed slot that never
      // becomes a run can never be notified — the row stays "last run OK", the week
      // stays green, and a scan that silently did not happen looks like it did.
      // recordSkippedRound is what makes it visible and honours notifyWhen; the
      // re-arm above already prevents it repeating.
      recordSkippedRound(round.id, `missed its slot by ${Math.round(d.lateBy / 60_000)}m (catchUp off)`);
    }
    if (!d.run) continue;

    const started = fireRound(loadRound(round.id) || round);
    if (started.error) recordSkippedRound(round.id, started.error);
   } catch (e) {
    // One corrupt/hand-edited round file must not stall every round after it. The
    // per-round guard logs and moves on; without it a single throwing round wedges
    // the whole 30s tick and nothing else ever fires.
    log(`round ${round && round.id}: tick step failed (${e.message})`);
   }
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

/**
 * A TEST-ONLY endpoint override, accepted only when it cannot leak a credential.
 *
 * All three of these URLs are handed a live secret: two send the access token in
 * an `Authorization` header and the third sends the REFRESH token in its body.
 * They exist so a route suite can stand up a local stub — and nothing else — but
 * an env knob that redirects them is an env knob that exfiltrates the owner's
 * login to whatever host a leftover drop-in names, with no symptom in the log.
 *
 * So: `https:` (the transport the real endpoints use), or a LOOPBACK host on any
 * scheme (which is what a stub is, and cannot leave the machine). Anything else
 * is refused at startup with the reason and the real URL is used instead. An
 * accepted override logs one line too — "no production path sets it" is a claim
 * the log should be able to settle.
 */
function testUrl(name, fallback) {
  const v = process.env[name];
  if (!v) return fallback;
  let u = null;
  try { u = new URL(v); } catch { u = null; }
  if (!u) {
    log(`${name}: refused (not a URL) — using the real endpoint`);
    return fallback;
  }
  const loopback = u.hostname === '127.0.0.1' || u.hostname === 'localhost' || u.hostname === '::1';
  if (u.protocol !== 'https:' && !loopback) {
    log(`${name}: refused (${u.protocol}//${u.host}) — only https: or a loopback host is accepted; using the real endpoint`);
    return fallback;
  }
  log(`${name}: TEST OVERRIDE active -> ${u.protocol}//${u.host}${u.pathname}`);
  return v;
}

// Claude Code's own config directory. Injectable for exactly one reason: the
// route suites need a credentials file and an OAuth lock of their own, and a
// test that locked or rewrote the real ~/.claude would reach straight into the
// owner's live CLI. Unset — which is every production path — it is ~/.claude.
const CLAUDE_DIR = process.env.HUGINN_APPD_CLAUDE_DIR || path.join(os.homedir(), '.claude');
/**
 * The usage endpoint, in ONE place.
 *
 * Overridable for exactly one reason: the route suites stand up a local server
 * that answers with the percentages the test needs. Two readers call it
 * (`fetchPlan` for the active login, `planForCredentials` for a saved one) and
 * before this they each carried their own literal — so a test could stub one
 * and silently reach api.anthropic.com through the other. No production path
 * sets it.
 */
const USAGE_URL = testUrl('HUGINN_APPD_USAGE_URL', 'https://api.anthropic.com/api/oauth/usage');
/**
 * The OAuth identity endpoint, in ONE place, on the same test-only terms.
 * Read at startup rather than per call so an override is refused — and logged —
 * once, at a moment somebody is looking, instead of silently on every refresh.
 */
const OAUTH_ACCOUNT_URL = testUrl('HUGINN_APPD_OAUTH_ACCOUNT_URL', 'https://api.anthropic.com/api/oauth/account');
/** The token endpoint. This one carries the REFRESH token in its body. */
const OAUTH_TOKEN_URL = testUrl('HUGINN_APPD_OAUTH_TOKEN_URL', oauthRefresh.TOKEN_URL);
const CREDENTIALS_PATH = path.join(CLAUDE_DIR, '.credentials.json');
const CLAUDE_CONFIG_PATH = `${CLAUDE_DIR.replace(/\/+$/, '')}.json`;
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
    // Overridable ONLY so a route suite can answer this itself instead of
    // reaching api.anthropic.com with a fixture token. Validated at startup by
    // testUrl; no production path sets it.
    const url = OAUTH_ACCOUNT_URL;
    const resp = await fetch(url, {
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
 * before switching to it. Best effort: a stored access token expires, and the
 * daemon refreshes INACTIVE profiles on a timer; the active one is the CLI's.
 * So a token that has expired between refreshes simply reports unknown here
 * until the next refresh tick picks it up, and the ACTIVE account's token is
 * never touched by this daemon at all — see refreshProfile.
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
    const resp = await fetch(USAGE_URL, {
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

// ------------------------------------------------------------ token refresh
//
// Keeping the SAVED, INACTIVE logins alive. Claude Code refreshes exactly one
// account — whichever is in the credentials file — so every other profile here
// goes dark within hours: its headroom stops being readable, the auto-switcher
// has nothing it is allowed to switch to, and by the time somebody wants to
// switch the token has been dead for days. The rules, the request shape and the
// reasons all live in lib/oauth-refresh.js; this is the wiring.
//
// Two invariants hold everything up, and both are enforced below rather than
// documented and hoped for:
//
//   * the ACTIVE account is never posted to the token endpoint (rotating it
//     signs the owner out of their own CLI), and
//   * nothing logs a token — a slug and one status word, that is the whole line.

const REFRESH_TICK_MS = 60_000;
/** Profiles with a POST in flight, so a row can read `refreshing` honestly. */
const refreshInFlight = new Set();
let refreshBusy = false;

/**
 * The refresh POST itself. The endpoint is overridable ONLY so tests can point a
 * local stub at it; there is no production path that sets this.
 */
async function postRefreshToken(body, opts = {}) {
  const url = OAUTH_TOKEN_URL;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 30_000);
  // The lock's compromise signal and our own timeout both have to be able to
  // kill this request: a refresh continuing under a lock somebody else now holds
  // is the double-write the lock exists to prevent.
  const onAbort = () => { try { ac.abort(); } catch { /* already aborted */ } };
  if (opts.signal) opts.signal.addEventListener('abort', onAbort, { once: true });
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: ac.signal,
    });
    // Read as text first: an error body is not reliably JSON, and classifyError
    // only needs to find a word in it.
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* not JSON; the text is enough */ }
    return { ok: resp.ok, status: resp.status, json, text };
  } catch (e) {
    return { ok: false, status: 0, json: null, text: e.name === 'AbortError' ? 'aborted' : e.message };
  } finally {
    clearTimeout(timer);
    if (opts.signal) opts.signal.removeEventListener('abort', onAbort);
  }
}

/**
 * Refreshes one saved profile. Returns one of lib/oauth-refresh's status words.
 *
 * Inactive-only, re-checked inside the lock. Write-back happens before any
 * enrichment, through AccountStore.save, so the slug survives the rotation and
 * a crash mid-flight cannot lose a login.
 */
async function refreshProfile(slug) {
  refreshInFlight.add(slug);
  try {
    const status = await oauthRefresh.refreshWithStore(accounts, slug, {
      post: postRefreshToken,
      lock: () => oauthlock.acquire(CLAUDE_DIR),
    });
    log(`refresh ${slug}: ${status}`);
    return status;
  } finally {
    refreshInFlight.delete(slug);
  }
}

/**
 * Tells the owner a refresh token is about to expire, once.
 *
 * Past `refreshTokenExpiresAt` nothing this daemon can do brings the login back —
 * only an interactive `claude auth login` does — so the warning has to arrive
 * BEFORE the cliff. Sent once per profile and then recorded, because the
 * condition stays true for the whole two days and a daily reminder of something
 * the owner has already been told is how a channel gets muted.
 */
async function notifyRefreshWarning(rec) {
  const at = rec.credentials.claudeAiOauth.refreshTokenExpiresAt;
  const when = new Date(at).toISOString().slice(0, 16).replace('T', ' ');
  const text = `${rec.email || rec.slug} needs a fresh sign-in by ${when} UTC — its refresh token `
    + `expires then and huginn cannot renew it after that. Run \`claude auth login\` for that account.`;
  log(`refresh ${rec.slug}: warning owner, refresh token expires soon`);
  const push = await deliverPush({
    kind: 'account_refresh', title: 'A saved Claude login is expiring', text, subject: rec.slug,
  });
  if (!push.sent) await deliverTelegram(`\u{26A0} A saved Claude login is expiring\n${text}`);
  return true;
}

/**
 * The timer. Refreshes only what is DUE, which is the difference between this
 * and a sweep: a saved inactive profile is permanently past its access token's
 * expiry between refreshes, so "refresh everything expired, every minute" would
 * mean a POST per profile per minute against an endpoint that rate-limits per
 * account. `refresh.nextAt` is the gate, written by every attempt.
 */
async function refreshTick() {
  if (refreshBusy) return;
  refreshBusy = true;
  try {
    const now = Date.now();
    const activePrint = fingerprint(accounts.readActive());
    for (const a of accounts.list()) {
      const rec = accounts.readProfile(a.slug);
      if (!rec || !rec.credentials) continue;
      // Never the live login. Checked here too so the common tick does not even
      // reach for the lock on the owner's own account.
      if (activePrint && fingerprint(rec.credentials) === activePrint) continue;

      if (oauthRefresh.refreshTokenWarnDue(rec, now) && !(rec.refresh && rec.refresh.warnedAt)) {
        try {
          await notifyRefreshWarning(rec);
          accounts.recordRefresh(a.slug, { warnedAt: now });
        } catch (e) { log(`refresh ${a.slug}: warning failed`, e.message); }
      }

      const nextAt = rec.refresh && typeof rec.refresh.nextAt === 'number' ? rec.refresh.nextAt : null;
      // No schedule yet means this profile has never been through here: decide
      // from the token itself rather than waiting for a tick that writes one.
      const due = nextAt === null
        ? ['expired', 'expiring'].includes(oauthRefresh.freshnessOf(rec, now))
        : nextAt <= now;
      if (!due) continue;
      if (oauthRefresh.freshnessOf(rec, now) === 'unrefreshable') continue;
      await refreshProfile(a.slug);
    }
  } catch (e) {
    log('refresh: tick failed', e.message);
  } finally {
    refreshBusy = false;
  }
}
setInterval(() => { refreshTick().catch(() => { }); }, REFRESH_TICK_MS).unref();

// -------------------------------------------------------------- scratchpads
//
// The user's own pages, held here so a phone and a desktop are editing the SAME
// text rather than two copies of it. Nothing on this host writes to them: they
// are written by a person and read by a run only when a person attaches one.
//
// Shaped like rounds — one JSON file per pad, a read-modify-write funnel, and a
// lib holding the rules — with one thing rounds does not have: a REVISION. Two
// clients autosaving the same page is the normal case here, not the exceptional
// one, so a save carries the rev it was based on and a stale one is refused with
// the current copy rather than silently overwriting whatever the other device
// had just typed.

const SCRATCHPADS_DIR = path.join(DATA_DIR, 'scratchpads');
/**
 * Where a pad is materialised for a tmux session to READ.
 *
 * Separate from the store on purpose, and not 0600: the pad's own file is this
 * daemon's record, while this is a copy handed to a Claude running as whoever
 * owns that session. World-readable is the point — an unreadable path in the
 * message would be a reference the run cannot follow.
 */
const SCRATCHPAD_RENDER_DIR = path.join(SCRATCHPADS_DIR, 'render');
fs.mkdirSync(SCRATCHPAD_RENDER_DIR, { recursive: true });

/**
 * How much body a page write may send — larger than the global 256 KiB default.
 *
 * ⚠ THE CAPS HAD TO AGREE AND DID NOT. A pad is capped at 100,000 CHARACTERS and
 * a body at 256 KiB of BYTES, so for any script whose characters cost three
 * bytes — Japanese, Chinese, Devanagari, most emoji — the character cap was
 * unreachable: a legal 90,000-character page is 270,000 bytes and died in
 * readBody, surfacing as the outer catch-all "request body too large" with no
 * mention of pages at all. This is enough room for the largest legal page in the
 * worst encoding (and for a client that \u-escapes every non-ASCII character),
 * so a page that IS over the limit is refused by contentProblem, in the route's
 * own words, naming the number the person is being held to.
 */
const SCRATCHPAD_BODY_MAX = 1024 * 1024;

function padPath(id) { return path.join(SCRATCHPADS_DIR, `${id}.json`); }

function loadPad(id) {
  if (!UUID_RE.test(String(id || ''))) return null;
  try { return JSON.parse(fs.readFileSync(padPath(id), 'utf8')); } catch { return null; }
}

/** tmp+rename, like every other store here: a reader never sees half a page. */
function savePad(pad) {
  const file = padPath(pad.id);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(pad, null, 2), { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  return pad;
}

function listPads() {
  let files = [];
  try { files = fs.readdirSync(SCRATCHPADS_DIR); } catch { return []; }
  const out = [];
  for (const f of files) {
    if (!f.endsWith('.json')) continue;
    const p = loadPad(f.slice(0, -5));
    if (p) out.push(p);
  }
  return scratchpadsLib.sortPads(out);
}

/**
 * Reload, change, save — the same funnel as updateRound and updateMeta, and the
 * same reason. A PATCH is read after an await on the request body, and the pad
 * loaded before that await is a snapshot: writing it back would discard whatever
 * the OTHER device saved in the gap, which on a page two clients are both
 * autosaving is not a rare race but the ordinary one.
 */
function updatePad(id, mutate) {
  const p = loadPad(id);
  if (!p) return null;
  mutate(p);
  p.updatedAt = Math.floor(Date.now() / 1000);
  // The rev is bumped HERE rather than by each caller, so there is no way to
  // write a page without moving the number every save is checked against.
  p.rev = (Number(p.rev) || 0) + 1;
  return savePad(p);
}

/**
 * The pad a reference falls back to, created on first sight.
 *
 * Lazily rather than at startup because an install that never opens a scratchpad
 * should not grow a file for one — and because "the list is empty" is a state the
 * clients would otherwise have to distinguish from "the feature is off".
 */
function ensureMain() {
  const existing = listPads().find((p) => scratchpadsLib.isMain(p));
  if (existing) return existing;
  const now = Math.floor(Date.now() / 1000);
  return savePad({
    id: crypto.randomUUID(),
    name: scratchpadsLib.MAIN_NAME,
    content: '',
    main: true,
    createdAt: now,
    updatedAt: now,
    rev: 1,
  });
}

/**
 * The pad a message names, or the fallback — and why not, when there is no pad.
 *
 * THREE ANSWERS, and the callers must tell them apart: `{pad}` is the page,
 * `{error}` is a 404 (an id that names nothing), and `{badRequest}` is a 400
 * (a field that is present and is not an id at all).
 *
 * ⚠ ABSENT AND BLANK ARE NOT THE SAME THING. Absent — the field is not in the
 * body, or is null — means no reference, and is decided by the CALLER before it
 * gets here; that is what keeps Main out of conversations nobody attached it to.
 * A field that is PRESENT and blank ("", "   ", 0, false, {}) is a client that
 * meant to send an id and sent nothing, and this used to coerce every one of
 * them to Main: a page silently attached to a message because a picker's state
 * was empty. Said out loud instead, which is also the only way the client that
 * did it ever finds out.
 *
 * The literal "main" stays a legal id — a client with no picker at all still
 * gets the behaviour the owner asked for ("Main when it is named").
 */
function padForReference(rawId) {
  if (typeof rawId !== 'string' || !rawId.trim()) {
    return { badRequest: 'scratchpadId must be a page id or "main" — leave it out entirely for no page' };
  }
  const id = rawId.trim();
  if (id === 'main') return { pad: ensureMain() };
  const pad = loadPad(id);
  if (!pad) return { error: 'no such scratchpad' };
  return { pad };
}

/** Render files this old are gone, and no more than this many are kept. */
const RENDER_KEEP_MS = 7 * 24 * 60 * 60 * 1000;
const RENDER_KEEP_MAX = 50;

/**
 * Drops render files nothing should still be reading.
 *
 * Two rules because they answer different questions: the age rule is about a
 * pane that may still be told to read a path (a message can sit unsent in a
 * composer for a while, but not for a week), and the count rule is what keeps a
 * directory of world-readable copies from growing for the life of the daemon.
 * A pruned path is an honest ENOENT; the thing being avoided is a path that
 * resolves to the WRONG text, which is what a shared filename guaranteed.
 */
function pruneRenderFiles(now = Date.now()) {
  let names;
  try { names = fs.readdirSync(SCRATCHPAD_RENDER_DIR); } catch { return; }
  const kept = [];
  for (const n of names) {
    if (!n.endsWith('.md')) continue;                       // .md.tmp mid-write
    const stamped = /-(\d{10,})\.md$/.exec(n);
    let at;
    if (stamped) at = Number(stamped[1]);
    else {
      // The pre-per-send shape (`<padId>.md`). Nothing new points at one, but
      // it is still a readable copy of somebody's page, so it ages out too.
      try { at = fs.statSync(path.join(SCRATCHPAD_RENDER_DIR, n)).mtimeMs; } catch { continue; }
    }
    if (now - at > RENDER_KEEP_MS) {
      try { fs.unlinkSync(path.join(SCRATCHPAD_RENDER_DIR, n)); } catch { /* raced */ }
      continue;
    }
    kept.push({ n, at });
  }
  if (kept.length <= RENDER_KEEP_MAX) return;
  kept.sort((a, b) => b.at - a.at);
  for (const { n } of kept.slice(RENDER_KEEP_MAX)) {
    try { fs.unlinkSync(path.join(SCRATCHPAD_RENDER_DIR, n)); } catch { /* raced */ }
  }
}

/**
 * Writes the pad where a session's Claude can Read it, and says where that is.
 *
 * ⚠ ONE FILE PER SEND, named with the moment it was written — because the
 * comment that used to sit here claimed snapshot semantics that a shared
 * `<padId>.md` could not possibly provide. That file was rewritten on every
 * attach and shared across every session, so an hour-old message still sitting
 * in a pane's scrollback named a path that now held the page's CURRENT text.
 * The run would follow it and answer confidently about words the sender never
 * attached — the exact failure the old comment said it was avoiding, caused by
 * the mechanism it described.
 *
 * Now the marker names a file that is genuinely the text as it read when SEND
 * was pressed, which is the same contract the chat path gets by carrying the
 * content itself. Old ones are pruned rather than kept forever; see
 * [pruneRenderFiles] for what "old" is and why a missing file beats a wrong one.
 */
function renderPad(pad) {
  const file = path.join(SCRATCHPAD_RENDER_DIR, `${pad.id}-${Date.now()}.md`);
  fs.writeFileSync(`${file}.tmp`, String(pad.content || ''), { mode: 0o644 });
  fs.renameSync(`${file}.tmp`, file);
  pruneRenderFiles();
  return file;
}

/** Every rendered copy of one pad, for the delete that must leave none behind. */
function unlinkRenderFiles(padId) {
  let names;
  try { names = fs.readdirSync(SCRATCHPAD_RENDER_DIR); } catch { return; }
  for (const n of names) {
    if (n !== `${padId}.md` && !n.startsWith(`${padId}-`)) continue;
    try { fs.unlinkSync(path.join(SCRATCHPAD_RENDER_DIR, n)); } catch { /* already gone */ }
  }
}

// ---- projects -------------------------------------------------------------
//
// A cluster of tmux sessions with roles: a LEAD that sizes the work and proposes
// a manifest, members the owner approves into existence, and a dashboard that
// sums what they spent. lib/projects.js holds every rule; this is the fs, the
// tmux and the send queue around them.
//
// ⚠ THE DAEMON IS NOT A BUS. Claude Code 2.1.258 routes peer messages itself —
// process to process over `/tmp/cc-socks/<pid>.sock`, identity kernel-verified
// by peer credentials, auto-triggering a turn in the recipient with no keypress
// — so NONE of this daemon's gates apply to a `SendMessage`. appd's contribution
// is the names (`--name <slug>/<role>`), the personas, the membership table and
// the view. The only things it types into a member are its own framed
// `[Huginn] …` lines and the first prompt, and those DO ride the send queue.

const PROJECTS_DIR = path.join(DATA_DIR, 'projects');
/**
 * Where a persona is materialised for a session's `claude` to READ.
 *
 * 0644 like the scratchpad renders and for the same reason: the project file is
 * this daemon's record, this is a copy handed to a `claude` that has to be able
 * to open it. `--append-system-prompt-file` (verified against CLI 2.1.258: it is
 * accepted, it is absent from `--help`, and passing it turns system-prompt
 * SNAPSHOTTING off so the persona applies fresh on every launch including a
 * `--resume`) keeps the persona out of argv and therefore out of `ps`.
 */
const PROJECT_RENDER_DIR = path.join(PROJECTS_DIR, 'render');
fs.mkdirSync(PROJECT_RENDER_DIR, { recursive: true });

function projectPath(id) { return path.join(PROJECTS_DIR, `${id}.json`); }

function loadProject(id) {
  if (!UUID_RE.test(String(id || ''))) return null;
  try { return JSON.parse(fs.readFileSync(projectPath(id), 'utf8')); } catch { return null; }
}

/** tmp+rename, 0600, like every other store here: a reader never sees half one. */
function saveProject(p) {
  const file = projectPath(p.id);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(p, null, 2), { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  return p;
}

function listProjects() {
  let files = [];
  try { files = fs.readdirSync(PROJECTS_DIR); } catch { return []; }
  const out = [];
  for (const f of files) {
    if (!f.endsWith('.json')) continue;
    const p = loadProject(f.slice(0, -5));
    if (p) out.push(p);
  }
  return projectsLib.sortProjects(out);
}

/**
 * Reload, change, save — the updatePad funnel, and the same hazard sharpened.
 *
 * A spawn writes this file once PER MEMBER, with a `tmux new-session` and a
 * queued send between each write. Mutating a snapshot taken before the loop
 * started would drop every member recorded since — which is exactly the Rounds
 * bug, except here the lost record is a live tmux session nothing owns any more.
 * The rev is bumped here so there is no way to write a project without moving
 * the number every save is checked against.
 */
function updateProject(id, mutate) {
  const p = loadProject(id);
  if (!p) return null;
  mutate(p);
  p.updatedAt = Math.floor(Date.now() / 1000);
  p.rev = (Number(p.rev) || 0) + 1;
  return saveProject(p);
}

function projectRenderPath(id, role) { return path.join(PROJECT_RENDER_DIR, `${id}.${role}.md`); }

/** The persona a session reads at launch. Rewritten on every launch/restore. */
function writePersona(id, role, text) {
  const file = projectRenderPath(id, role);
  fs.writeFileSync(`${file}.tmp`, text, { mode: 0o644 });
  fs.renameSync(`${file}.tmp`, file);
  return file;
}

/** Every persona of one project goes with it — a readable path to a deleted
 *  project's instructions is a session taking orders from a ghost. */
function unlinkPersonas(id) {
  let names;
  try { names = fs.readdirSync(PROJECT_RENDER_DIR); } catch { return; }
  for (const n of names) {
    if (!n.startsWith(`${id}.`)) continue;
    try { fs.unlinkSync(path.join(PROJECT_RENDER_DIR, n)); } catch { /* already gone */ }
  }
}

/**
 * Is this directory ALREADY trusted by Claude Code itself?
 *
 * ⚠ READ ONLY, AND DELIBERATELY SO. The first design pre-WROTE
 * `projects[<cwd>].hasTrustDialogAccepted` into `~/.claude.json` so a fresh lead
 * would never meet the folder-trust dialog (which blocks registration entirely
 * and preselects "No, exit", so a blind Enter kills the launch). That file is
 * 115 KB, holds 25 project entries and the OAuth identity block, and is
 * rewritten by every running `claude` continuously — its mtime moved twice
 * inside a ten-minute recon. A read-modify-tmp-rename would silently discard
 * whatever a CLI wrote in that window. So an untrusted cwd is REFUSED with an
 * instruction the owner can act on (decision 50), and nothing here ever writes
 * to that file.
 *
 * WORKDIR passes without a lookup: it is where `POST /v1/sessions` has always
 * opened `claude`, so a host on which it is untrusted is already living with
 * that, and it is a project's default cwd for the same reason.
 */
function cwdIsTrusted(dir) {
  const want = String(dir || '').replace(/\/+$/, '') || '/';
  if (want === String(WORKDIR).replace(/\/+$/, '')) return true;
  let cfg;
  try { cfg = JSON.parse(fs.readFileSync(CLAUDE_CONFIG_PATH, 'utf8')); } catch { return false; }
  const projects = cfg && cfg.projects;
  if (!projects || typeof projects !== 'object') return false;
  const entry = projects[want] || projects[`${want}/`];
  return !!(entry && entry.hasTrustDialogAccepted === true);
}

/**
 * The shell command a project session runs.
 *
 * Every piece is grammar-checked before it reaches here — the claude name is two
 * `[a-z0-9-]` runs joined by a slash, the persona path is DATA_DIR plus a uuid
 * and a role, and model/effort are enum members — so nothing in this string can
 * be shell syntax. The tail mirrors the create route exactly, so a project
 * session that exits drops to a login shell like any other.
 *
 * `mode` is NOT passed. It is a label on the manifest: this daemon's ask/act
 * vocabulary is about `--allowedTools` for headless chats and has no meaning as
 * a `--permission-mode` for an interactive pane, and inventing a mapping would
 * mean guessing a flag value on the owner's behalf.
 */
function claudeLaunchCommand({ claudeName, persona, model, effort }) {
  const parts = ['claude', '--name', claudeName];
  if (model) parts.push('--model', model);
  if (effort) parts.push('--effort', effort);
  parts.push('--append-system-prompt-file', persona);
  return `${parts.join(' ')}; exec "$SHELL" -l`;
}

/**
 * One project session, created the way `POST /v1/sessions` creates one.
 *
 * ⚠ AND MARKED AS STILL COMING UP, which is the difference between a first
 * prompt that arrives and one that never existed. `claude` needs about two
 * seconds to paint a composer, and a paste before that is discarded whole,
 * Enter included — measured on this host. `markLaunching` is what puts the
 * session behind the `starting` gate so the queued first prompt waits for the
 * composer instead of being typed into an empty pty.
 */
async function launchProjectSession(tmuxName, cwd, command) {
  await ensureTmuxServerScope();
  // Whatever the last holder of this name left behind goes before the new
  // session can be observed — the create route's own first move.
  clearSessionState(tmuxName);
  const r = await run('tmux', ['new-session', '-d', '-s', tmuxName, '-c', cwd, command]);
  if (r.err) {
    return { error: (r.stderr || r.err.message || '').trim().slice(0, 160) || 'tmux refused the session' };
  }
  // What tmux CALLED it, not what we asked for: a '.' is rewritten to '_' with a
  // zero exit. The grammar forbids dots so this should never differ — and it is
  // read back anyway, because "should never" is how the rename route got bitten.
  const q = await run('tmux', ['display-message', '-p', '-t', `=${tmuxName}:`, '#S']);
  const created = (q.stdout || '').trim() || tmuxName;
  registryAdd(created, { cwd });
  markLaunching(created);
  return { name: created };
}

/**
 * Create the members the owner approved, and report per member.
 *
 * ⚠ A FAILURE CONTINUES THE LOOP. A cluster is twelve sessions and the ways one
 * of them fails are ordinary — a tmux name a previous project left behind, a
 * member cwd that has been deleted since the lead proposed it. Returning at the
 * first one would leave the owner with a project that spawned the first two
 * roles, no answer about the rest, and a card still offering Spawn for a plan
 * half of which is already live. So every member is attempted, every outcome is
 * reported, and each spawned member is WRITTEN before the next is attempted —
 * re-reading the file each time, because there is an await between every write.
 */
async function spawnProject(project) {
  const manifest = project.manifest;
  const spawned = [];
  const failed = [];
  for (const s of (manifest.sessions || []).slice(0, projectsLib.MAX_MEMBERS)) {
    const tmuxName = projectsLib.tmuxNameFor(project.slug, s.role);
    const claudeName = projectsLib.claudeNameFor(project.slug, s.role);
    const cwd = s.cwd || project.cwd;
    const member = {
      role: s.role,
      name: tmuxName,
      claudeName,
      sessionId: null,
      cwd,
      model: s.model || null,
      effort: s.effort || null,
      mode: s.mode || null,
      firstPrompt: s.firstPrompt,
      spawnedAt: null,
      endedAt: null,
    };
    let persona;
    try {
      persona = writePersona(project.id, s.role, projectsLib.memberPersona(project, member));
    } catch (e) {
      failed.push({ role: s.role, reason: `persona could not be written: ${e.message}` });
      continue;
    }
    const launched = await launchProjectSession(tmuxName, cwd, claudeLaunchCommand({
      claudeName, persona, model: s.model, effort: s.effort,
    }));
    if (launched.error) {
      failed.push({ role: s.role, reason: launched.error });
      log(`project ${project.slug}: ${s.role} could not be created: ${launched.error}`);
      continue;
    }
    member.name = launched.name;
    member.spawnedAt = Math.floor(Date.now() / 1000);
    updateProject(project.id, (p) => {
      p.members = [...(p.members || []).filter((x) => x.role !== s.role), projectsLib.memberRow(member)];
    });
    spawned.push(member);
    // The first prompt rides the ORDINARY queue, with the startup hold from
    // markLaunching in front of it: `automated` so the drop rules and the drop
    // journal treat it as appd's own send rather than as a person's message.
    enqueueSend(launched.name, projectsLib.firstPromptFrame(project, s.role, s.firstPrompt), {
      automated: true, origin: 'project', kind: 'firstPrompt',
    }).catch((e) => log(`project ${project.slug}: ${s.role} first prompt failed: ${e.message}`));
  }

  const saved = updateProject(project.id, (p) => {
    // Nothing spawned leaves the card standing: the owner's approval has not
    // been carried out and the plan is still the plan.
    if (spawned.length) p.status = 'active';
    if (p.manifest) p.manifest.spawnedRev = p.manifest.rev || 0;
  }) || project;

  if (spawned.length && project.lead) {
    // Typed, not sent as a peer message — appd must never appear in the peer
    // registry as something with authority over these sessions.
    enqueueSend(project.lead.name, projectsLib.spawnedFrame(project, spawned.map((m) => m.claudeName)), {
      automated: true, origin: 'project', kind: 'spawned',
    }).catch(() => { });
  }
  log(`project ${project.slug}: spawned ${spawned.length}, failed ${failed.length}`);
  return {
    ok: failed.length === 0 && spawned.length > 0,
    spawned: spawned.map(projectsLib.memberRow),
    failed,
    project: saved,
  };
}

/** The proposal, as a notification with the two bounded actions and no others. */
function notifyProposal(project) {
  const m = project.manifest || {};
  const roles = (m.sessions || []).map((s) => s.role).join(', ');
  deliverPush({
    kind: 'project_proposed',
    key: `project:${project.id}`,
    subject: project.id,
    title: `${project.name} — proposal ready`,
    text: `${(m.sessions || []).length} session(s) — ${roles}. ${m.summary || ''}`.trim(),
    // ⚠ BOUNDED CHOICES ONLY. Edit is not here and must never be: it opens an
    // editor, and a notification button that cannot complete its own action is
    // the house rule this list exists to keep.
    options: ['Spawn', 'Discard'],
    payload: { projectId: project.id, manifestRev: m.rev || 0 },
    fingerprint: `${project.id}:${m.rev || 0}`,
  }).catch((e) => log(`project ${project.slug}: proposal push failed: ${e.message}`));
}

/**
 * Read the lead's last turn and adopt a manifest if a new one is there.
 *
 * Called from the 60 s reconcile AND from the two project GETs. A side effect in
 * a GET is deliberate and has precedent — `GET /v1/scratchpads` mints Main and
 * says why — because the alternative is a proposal card that appears up to a
 * minute after the lead wrote it, on a surface whose entire job is to show that
 * the lead is waiting for an answer. It costs one transcript TAIL of 40 events
 * and it is idempotent: a block already adopted does not move the rev.
 *
 * Gated on drafting|proposed. A project whose members are already running is not
 * re-proposed by a lead that mentions its own plan again — `active -> proposed`
 * is not a legal move, and spawning a second cluster because the lead recapped
 * would be the worst possible reading of a recap.
 */
function detectManifest(project) {
  if (!project || !project.lead || !project.manifest || !project.manifest.tag) return project;
  if (project.status !== 'drafting' && project.status !== 'proposed') return project;
  const st = readSessionState(project.lead.name);
  const file = (st && st.transcript)
    || (project.lead.sessionId ? findTranscriptFile(project.lead.sessionId) : null);
  if (!file) return project;
  let text = '';
  try {
    const t = readTranscript(file, { limit: 40 });
    text = (t.events || [])
      .filter((e) => e.kind === 'assistant' && !e.sidechain && typeof e.text === 'string')
      .map((e) => e.text).join('\n');
  } catch { return project; }
  if (!text) return project;

  const tag = project.manifest.tag;
  const parsed = projectsLib.parseManifest(text, tag, { cwd: project.cwd });
  const untagged = projectsLib.untaggedManifest(text, tag);
  const before = project.manifest;
  const isNew = parsed && (before.summary !== parsed.summary
    || JSON.stringify(before.sessions || []) !== JSON.stringify(parsed.sessions));

  if (!isNew && !(untagged && !before.untaggedSeen)) return project;

  const now = Math.floor(Date.now() / 1000);
  const saved = updateProject(project.id, (p) => {
    if (isNew) {
      p.manifest = {
        ...p.manifest,
        type: parsed.type,
        scope: parsed.scope,
        summary: parsed.summary,
        sessions: parsed.sessions,
        rev: (Number(p.manifest.rev) || 0) + 1,
        receivedAt: now,
      };
      p.status = 'proposed';
      if (p.kind === 'other' && parsed.type !== 'other') p.kind = parsed.type;
    }
    if (untagged) p.manifest.untaggedSeen = true;
  });
  if (!saved) return project;
  if (isNew) {
    log(`project ${saved.slug}: manifest rev ${saved.manifest.rev} (${saved.manifest.sessions.length} session(s))`);
    notifyProposal(saved);
  }
  if (untagged && !before.untaggedSeen) {
    log(`project ${saved.slug}: the lead wrote an UNTAGGED project block — reported, not acted on`);
  }
  return saved;
}

/**
 * Keep the project store in step with live tmux, on the registry reconcile's own
 * timer.
 *
 * Skipped whole when the listing fails — `listSessions` answers null for a
 * failure to OBSERVE, and reading that as "nothing is live" would drop every
 * member of every project and archive them all in one tick. The same trap
 * `pruneDead` documents, with a worse blast radius.
 */
async function reconcileProjects() {
  const live = await listSessions();
  if (live === null) return;
  const now = Math.floor(Date.now() / 1000);
  for (const row of listProjects()) {
    if (row.status === 'archived') continue;
    let project = loadProject(row.id);
    if (!project) continue;
    project = detectManifest(project);
    const plan = projectsLib.reconcilePlan(project, live);
    if (!plan.drop.length && !plan.rebind.length && !plan.endProject) continue;
    updateProject(project.id, (p) => {
      for (const r of plan.rebind) {
        // ⚠ RE-BOUND BY TMUX NAME. A reboot restore that falls back to a fresh
        // `claude` (nothing resumable on disk) mints a NEW Claude session id, so
        // the stored one stops matching anything in the native registry. The
        // tmux name is the membership key precisely so this is a rewrite rather
        // than a lost member.
        if (r.role === projectsLib.LEAD_ROLE) { if (p.lead) p.lead.sessionId = r.sessionId; continue; }
        const m = (p.members || []).find((x) => x.role === r.role);
        if (m) m.sessionId = r.sessionId;
      }
      if (plan.drop.length) p.members = (p.members || []).filter((m) => !plan.drop.includes(m.role));
      if (plan.endProject) {
        p.status = 'archived';
        p.endedReason = 'the lead session is gone';
        p.endedAt = now;
      }
    });
    if (plan.drop.length) log(`project ${project.slug}: dropped ${plan.drop.join(', ')} (session gone)`);
    if (plan.endProject) log(`project ${project.slug}: archived — the lead session is gone`);
  }
}

/**
 * Per-session overviews already computed, keyed by Claude session id.
 *
 * Bounded at the graph cache's own size for the same reason it exists: this map
 * holds one parsed summary per member and a host with several projects would
 * otherwise keep every session it ever rendered.
 */
const projectOverviewCache = new Map();

function rememberOverview(key, value) {
  projectOverviewCache.delete(key);
  projectOverviewCache.set(key, value);
  while (projectOverviewCache.size > GRAPH_CACHE_MAX) {
    projectOverviewCache.delete(projectOverviewCache.keys().next().value);
  }
}

async function projectDashboard(project) {
  const sessions = await listSessions();
  const joined = projectsLib.joinMembers(project, sessions || [], readNativeRegistry());
  const withFiles = joined.map((r) => {
    const st = r.name ? readSessionState(r.name) : null;
    const file = (st && st.transcript) || (r.sessionId ? findTranscriptFile(r.sessionId) : null);
    return { ...r, transcript: file };
  });
  const rows = projectsLib.rollupMembers(withFiles, {
    cache: { get: (k) => projectOverviewCache.get(k), set: rememberOverview },
    sizeOf: (f) => { try { return fs.statSync(f).size; } catch { return null; } },
    overview: (f, id) => { try { return sessionOverview(f, id); } catch { return null; } },
  });
  const { totals, rate } = projectsLib.aggregateDashboard(rows);
  return {
    project: projectsLib.projectRow(project, joined),
    generatedAt: Math.floor(Date.now() / 1000),
    totals,
    rate,
    members: rows.map(projectsLib.dashboardMemberRow),
  };
}

/** The member (or the lead) a `from`/`to` names: by role, by tmux name, or by
 *  the `<slug>/<role>` peers use. */
function projectMemberNamed(project, who) {
  const want = String(who || '').trim();
  if (!want) return null;
  for (const m of projectsLib.memberList(project)) {
    if (m.role === want || m.name === want || m.claudeName === want) return m;
  }
  return null;
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
/**
 * How long one reading of the usage endpoint is served for.
 *
 * A minute, because the phone polls the settings screen and this is a network
 * round trip that rate-limits per account. Overridable ONLY so a route suite can
 * change its stubbed percentages and see the next read — the same test knob
 * HUGINN_APPD_OAUTH_ACCOUNT_URL is. No production path sets it.
 */
const PLAN_TTL_MS = Number(process.env.HUGINN_APPD_PLAN_TTL_MS) || 60_000;

async function fetchPlan() {
  if (planCache.running) return;
  planCache.running = true;
  try {
    let creds;
    try {
      creds = JSON.parse(fs.readFileSync(CREDENTIALS_PATH, 'utf8'));
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
      resp = await fetch(USAGE_URL, {
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
    const data = normalizePlan(await resp.json());
    /**
     * WHOSE usage this is, cached WITH the numbers.
     *
     * `planCache` is keyed on nothing: it is one slot holding "the plan", and a
     * client that asks for identity separately gets whatever the credentials
     * file says NOW. So after an account switch the new account's bars were
     * captioned with the old account's email until the cache aged out — the
     * numbers and the name came from two different moments. Resolving the
     * identity here, and storing it inside `data`, makes them one fact that
     * `performSwitch` invalidates together (it nulls `data`).
     *
     * Best effort: an identity lookup that fails leaves `account` null and the
     * bars simply say "signed-in account", which is what an older daemon's
     * clients already render.
     */
    const id = await resolveIdentity(creds);
    let slug = null;
    try { const rec = accounts.list().find((a) => a.isActive); slug = rec ? rec.slug : null; } catch { /* no store yet */ }
    const o = (creds && creds.claudeAiOauth) || {};
    data.account = (id && (id.email || id.uuid)) || slug
      ? {
        email: (id && id.email) || null,
        accountUuid: (id && id.uuid) || null,
        slug,
        subscriptionType: o.subscriptionType || null,
      }
      : null;
    planCache.data = data;
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

// ------------------------------------------------- host-owned quick actions
//
// The wording the clients put in front of a quoted selection. It lives on the
// host for the same reason SOFT_END_PHRASE does — one copy, so the desktop and
// the phone cannot end up sending two different prompts for the same button —
// and unlike the soft-end phrase it is EDITABLE from either client, because it
// is the operator's wording rather than a deployment setting.
const QUICK_ACTIONS_FILE = path.join(DATA_DIR, 'quick-actions.json');

/**
 * The stored templates, or the built-in ones.
 *
 * ⚠ THE FILE IS A PATCH OVER THE DEFAULTS, exactly as loadHeadroomSettings
 * treats its own — and for the same reason. A file written by an older build,
 * or hand-edited, may be missing a field or carrying one that no longer
 * validates; serving half a stored record and half nothing would put a template
 * with no `{selection}` in front of the Explain button, which silently drops the
 * selection. Every field goes through the same rule the PATCH route applies, and
 * a file that fails falls back whole.
 */
function loadQuickActions() {
  let raw = null;
  try { raw = JSON.parse(fs.readFileSync(QUICK_ACTIONS_FILE, 'utf8')); }
  catch { return quickLib.defaults(); }        // absent is the normal case, not an error
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return quickLib.defaults();
  const v = quickLib.validate(raw);
  if (!v.ok) {
    log(`quick-actions: ${QUICK_ACTIONS_FILE} does not validate (${v.error}); using defaults`);
    return quickLib.defaults();
  }
  const rec = { ...quickLib.defaults(), ...v.fields };
  // The revision is state, not content: it survives a field that had to be
  // dropped, so a client holding rev 4 is not silently handed rev 0 and told
  // its next edit conflicts.
  if (Number.isInteger(raw.rev) && raw.rev >= 0) rec.rev = raw.rev;
  if (Number.isInteger(raw.updatedAt) && raw.updatedAt >= 0) rec.updatedAt = raw.updatedAt;
  return rec;
}

function saveQuickActions(rec) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(`${QUICK_ACTIONS_FILE}.tmp`, `${JSON.stringify(rec, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(`${QUICK_ACTIONS_FILE}.tmp`, QUICK_ACTIONS_FILE);
  return rec;
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
    // Beside the soft-end phrase and for the same reason: the clients render
    // host-owned copy rather than their own. One small file read per poll, which
    // is nothing next to the df and the version shell-out above.
    quickActions: quickLib.view(loadQuickActions()),
    // The one-line usage summary behind the clients' headroom pill. From
    // headroom.json and the sentinel directory only — no network, so the status
    // poll stays as cheap as it was.
    headroom: headroomStatus(),
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

/**
 * The registry, re-read on every touch.
 *
 * ⚠ ANY failure here empties the store, and the next write makes that permanent.
 * That is survivable — a phone re-registers on its next start — but it also
 * silently resets every install's delivery tally, and the phone compares that
 * tally against its own. A restart at 0 that nobody announces reads to the phone
 * as "the host has sent fewer than I received", i.e. nothing is ever dropped,
 * which disables the deficit check for good. The announcement is the epoch: an
 * install rebuilt into an empty store mints a new one (see pushtokens.mintEpoch),
 * so the phone rebaselines instead of believing the difference.
 *
 * A missing file is an ordinary first run and says nothing. Anything else is a
 * file that may well still exist and still hold counters, so it gets a line —
 * without one, the only evidence is a number quietly starting again from zero.
 */
function loadPushState() {
  let raw;
  try { raw = fs.readFileSync(PUSH_STATE, 'utf8'); }
  catch (e) {
    if (e.code !== 'ENOENT') log('push: state unreadable, counters restart under a new epoch', e.code || e.message);
    return pushLib.emptyState();
  }
  try { return JSON.parse(raw); }
  catch (e) { log('push: state corrupt, counters restart under a new epoch', e.message); return pushLib.emptyState(); }
}
function savePushState(st) {
  try {
    fs.writeFileSync(`${PUSH_STATE}.tmp`, JSON.stringify(st), { mode: 0o600 });
    fs.renameSync(`${PUSH_STATE}.tmp`, PUSH_STATE);
  } catch (e) { log('push: could not persist tokens', e.message); }
}

/**
 * Everything one dead registration costs, in the two places that learn of one:
 * a real send in [deliverPush], and the daily validate-only sweep.
 *
 * THE INSTALL ID IS THE JOIN. The app sends the same per-installation id when it
 * registers a push token (push.json) and when it checks in (clients.json), so a
 * dead token is evidence about BOTH rows and dropping only the first left a ghost
 * phone listed in the clients panel until its seven-day prune. Worse than merely
 * untidy: `clientsLib.appOnline` reads that list to decide whether the app is a
 * delivery route — a stale row cannot make a phone look fresh forever (its lastAt
 * still ages out) but the panel says "still listening" about a phone that has been
 * uninstalled.
 *
 * ⚠ devices.json is NOT part of this join and must not be touched here. A device
 * is another MACHINE that runs claude work; phones never enrol as devices, and an
 * install id is not a device id. The two registries share no key.
 *
 * @returns true when the token was actually dropped (see pushLib.drop's token guard).
 */
function retireDeadInstall(freshPush, installId, token) {
  if (!pushLib.drop(freshPush, installId, token)) return false;
  if (clientsLib.dropClient(clientState, installId)) {
    clientDirty = true;
    // ⚠ WRITTEN NOW, NOT ON THE 60-SECOND TIMER. The caller persists push.json
    // as soon as this returns, so the two halves of one retirement were landing
    // up to a minute apart — and a restart in that minute (a deploy, a crash,
    // an OOM kill) brought back a clients.json still naming a phone whose token
    // had already gone. That row can never be retired again: the only thing that
    // drops it is a dead-token verdict, and there is no longer a token to get
    // one for. It sits in the clients panel saying "still listening" about an
    // uninstalled app until its seven-day prune, and `appOnline` reads it.
    flushClients();
  }
  return true;
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
  // (an alert tick and an autoswitch tick) clobbered each other the same way.
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
      // The client row goes with it — see retireDeadInstall for why the install id
      // is the join, and why devices.json is deliberately not part of it.
      else if (o.kind === 'dead') retireDeadInstall(fresh, o.installId, o.token);
      else pushLib.noteFailure(fresh, o.installId);
    }
    savePushState(fresh);
  }
  return { sent, dead, failed };
}

// ------------------------------------------------- the daily uninstall sweep
//
// The one thing a quiet host cannot learn any other way. A phone that has been
// uninstalled stops checking in, which is what a phone in a drawer also does, so
// the registry keeps a dead token until something happens worth pushing about —
// and on a household daemon that can be months. A validate-only probe asks FCM
// the question directly, without delivering anything to anybody.
//
// Once a DAY, not once an hour: the answer changes when somebody uninstalls an
// app, which is not an hourly event, and each pass costs one FCM round trip per
// registered phone. The first pass waits ten minutes rather than running at
// startup, so a daemon crash-looping never sweeps at all — and so a deploy does
// not spend its first second on a question whose answer keeps for a day.

const PUSH_RECONCILE_MS = 24 * 60 * 60 * 1000;
const PUSH_RECONCILE_FIRST_MS = 10 * 60 * 1000;

/**
 * Validates every stored token and retires the ones FCM says are gone.
 *
 * Same cleanup as a dead verdict from a real send — [retireDeadInstall] — and
 * deliberately the same function, so an uninstall discovered by the sweep and one
 * discovered by an alert cannot leave the daemon in two different states.
 *
 * One quiet line per REMOVAL and nothing at all otherwise. A sweep that logged
 * its own heartbeat would write 365 lines a year saying nothing happened, and the
 * lines that matter would be the ones nobody could find.
 */
async function pushReconcileTick() {
  if (!fcm) return { checked: 0, dead: 0 };
  const st = loadPushState();
  if (!pushLib.count(st)) return { checked: 0, dead: 0 };

  const r = await pushLib.reconcile(st, (token) => fcm.validate(token));
  if (!r.dead.length) return { checked: r.checked, dead: 0 };

  // Applied against a FRESH read: the sweep held the network for one round trip
  // per phone, and POST /v1/push/register writes this same file.
  const fresh = loadPushState();
  let dropped = 0;
  for (const d of r.dead) {
    if (!retireDeadInstall(fresh, d.installId, d.token)) continue;
    dropped += 1;
    log(`push: install ${d.installId} gone (uninstalled?) — token + client row dropped`);
  }
  if (dropped) savePushState(fresh);
  return { checked: r.checked, dead: dropped };
}

if (fcm) {
  setTimeout(() => {
    pushReconcileTick().catch((e) => log('push: reconcile failed', e.message));
    setInterval(() => { pushReconcileTick().catch((e) => log('push: reconcile failed', e.message)); },
      PUSH_RECONCILE_MS).unref();
  }, PUSH_RECONCILE_FIRST_MS).unref();
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

/** sha of the draft -> {at, promise, result}. Small: a draft stops mattering the moment it is saved. */
const polishCache = new Map();
const POLISH_CACHE_MAX = 20;

/**
 * One AI-polished draft of a Round field, single-flight on the exact draft.
 *
 * Keyed on a hash of everything the prompt is built from, so the second tap of
 * Polish on unchanged text — a double tap, a rotation, both clients open — waits on
 * the first call instead of buying a second one. Change a character and it is a
 * different key and a real call, which is the behaviour somebody editing wants.
 *
 * FAILURES ARE NOT CACHED. A cached error would make Polish permanently broken for
 * that exact draft with no way to retry but to type something and undo it, and a
 * failure here is nearly always transient (a timeout, a busy limit). Successes stay
 * because they cost a model call; failures cost nothing to repeat.
 *
 * @returns {{polished, note?}|{error}} — never throws, and never a 5xx: the field
 *   the person is typing in must not be hostage to a model being unavailable.
 */
async function polishFor(field, draft, cap) {
  const key = crypto.createHash('sha256')
    .update(JSON.stringify([field, draft.title, draft.prompt, draft.goal, draft.mode]))
    .digest('hex');
  const hit = polishCache.get(key);
  if (hit) return hit.promise ? await hit.promise : hit.result;

  const entry = { at: Date.now(), promise: null, result: null };
  entry.promise = (async () => {
    const r = await run('claude', [
      '-p',
      // Caged exactly as the suggestion call is: no CLAUDE.md (global or
      // project), no tools, one turn, scratch cwd. This must never inherit
      // huginn's persona — it is rewriting text for an unattended job, and a
      // persona would write as one.
      '--setting-sources', '',
      // sonnet, not suggest's haiku: this answer is pasted into a job that then
      // runs unattended for months, so the quality of the sentence is the whole
      // feature. And 90s rather than the suggestion path's 45: the prompt field
      // carries up to 20k characters of draft.
      '--model', 'sonnet',
      '--max-turns', '1',
      '--tools', '',
      '--', buildPolishPrompt(field, draft, cap),
    ], { timeout: 90_000, cwd: DATA_DIR });
    if (r.err) {
      log(`polish: ${field} failed: ${(r.stderr || r.err.message || '').slice(0, 120)}`);
      return { error: 'polish is unavailable right now' };
    }
    const parsed = parsePolish(field, r.stdout, cap);
    log(`polish: ${field} -> ${parsed.error ? `refused (${parsed.error})` : `${parsed.polished.length} chars`}`);
    return parsed;
  })();

  polishCache.set(key, entry);
  const result = await entry.promise;
  if (result.error) {
    // Dropped whole rather than settled, so callers still holding entry.promise
    // get this answer and the NEXT tap is a real retry rather than a replay.
    polishCache.delete(key);
  } else {
    entry.result = result;
    entry.promise = null;
    // Insertion-ordered, so the oldest key is the first one out.
    while (polishCache.size > POLISH_CACHE_MAX) polishCache.delete(polishCache.keys().next().value);
  }
  return result;
}


/**
 * May this profile be switched TO at all?
 *
 * ⚠ THE UNATTENDED PATH NEEDS THIS AS MUCH AS THE BUTTON DOES — more, in fact.
 * Installing a credential pair that is behind hands the CLI `invalid_grant`, and
 * the CLI answers by blanking its own credentials file (lib/oauth-refresh's
 * header). The owner pressing Activate at least sees a 409; the arbiter doing it
 * at 3am signs the host out with nothing to read afterwards. And the arbiter is
 * the MORE likely of the two to pick a dead profile: `agedLimits` zeroes every
 * window whose reset time has passed, so the profile nobody has read for weeks
 * scores as the freshest candidate on the host.
 *
 * Refuses a login whose refresh token is gone, and refreshes a stale one FIRST —
 * synchronously, because handing out a stale token to save thirty seconds means
 * handing out a session that dies on its first request.
 *
 * @returns {{ok: true}} or {{ok: false, error: string, why: string}}
 */
async function ensureSwitchable(slug) {
  const rec = accounts.readProfile(slug);
  // Not a profile we know, or already the active login: performSwitch owns both
  // of those answers, and neither is a freshness question.
  if (!rec || !rec.credentials) return { ok: true };
  if (sameAccount(rec.credentials, accounts.readActive())) return { ok: true };
  const freshness = oauthRefresh.freshnessOf(rec, Date.now());
  if (freshness === 'unrefreshable') {
    const at = oauthRefresh.deadSince(rec);
    const when = at ? new Date(at).toISOString().slice(0, 10) : 'an unknown date';
    return {
      ok: false,
      why: 'unrefreshable',
      error: `${rec.email || slug} cannot be switched to: its login expired on ${when} — sign in again`,
    };
  }
  if (freshness === 'expired' || freshness === 'expiring') {
    const status = await refreshProfile(slug);
    if (status !== 'refreshed' && status !== 'not_needed') {
      return {
        ok: false,
        why: status,
        error: `${rec.email || slug} could not be refreshed before switching (${status}) — try again, or sign in again`,
      };
    }
  }
  return { ok: true };
}

/**
 * Makes a saved login the active one, everywhere it has to happen at once:
 * credentials + identity block (accounts.activate), the label correction once
 * `auth status` is authoritative for the new login, and the plan cache, whose
 * old figures belong to the account just left. One path for the button in
 * Settings and for the auto-switcher, so they cannot drift.
 */
async function performSwitch(slug) {
  // Held across the WHOLE swap, not just the rename inside activate(). The
  // snapshot below reads the live credentials and the activate() call replaces
  // them; a refresh landing between those two rotates the account being left,
  // and the pair we snapshotted is then a rotation behind — which is a dead
  // login the next time anybody switches back to it. This is the caller that
  // can afford to wait for the lock, so it uses the full ladder and hands
  // activate() `held` rather than letting it take the lock a second time.
  const lock = await oauthlock.acquire(CLAUDE_DIR);
  if (!lock.ok) {
    log(`switch ${slug}: ${lock.status}`);
    return { ok: false, error: 'another process is refreshing this host\'s token', status: lock.status };
  }
  try {
    return await performSwitchLocked(slug);
  } finally {
    try { lock.release(); } catch { /* stolen at 60s anyway */ }
  }
}

async function performSwitchLocked(slug) {
  const before = await accountStatus();
  // Fold the outgoing account's CURRENT tokens into its own profile first. Its
  // refresh token has almost certainly rotated since it was last written, and
  // the snapshot activate() takes on the way out can only recognise a profile by
  // that token — so without this the account being left is filed a second time,
  // under a name nothing else knows.
  await saveIdentified(before.email, accounts.readActive());

  const r = accounts.activate(slug, before.email, { held: true });
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

// ------------------------------------------------- autoswitch.json (history)
//
// The account switcher's own store, kept READ-ONLY. Its rules now live in
// lib/headroom.js and its two settings moved into `headroom-settings.json`
// (migrated once, on the first boot that finds no headroom settings file); this
// file is left on disk as the record of what the old switcher did and is never
// written again. `GET/POST /v1/autoswitch` are aliases onto the new settings for
// one release.
const AUTOSWITCH_STATE = path.join(DATA_DIR, 'autoswitch.json');

function loadAutoswitch() {
  try { return JSON.parse(fs.readFileSync(AUTOSWITCH_STATE, 'utf8')); }
  catch { return { enabled: false, lastSwitchAt: 0, switches: 0, last: null }; }
}

// ----------------------------------------------------------------- headroom
//
// One subsystem, one arbiter, one cooldown. The daemon reads how much room each
// saved account has left, notices a window RESETTING (which nothing did before),
// and has three levers instead of one:
//
//   switch account   helps NEW runs only — a running `claude` holds its token in
//                    memory and cannot be moved to another login.
//   the model ladder helps a LIVE session: a framed heads-up at 85% of the Fable
//                    week so it writes its own handoff note, then a SESSION-ONLY
//                    move to opus at 92%, typed into the `/model` picker at a
//                    turn boundary. The host's default model is never rewritten.
//   the sentinels    hold new agent spawns while the account is out of room.
//
// The rules live in lib/headroom.js, pure and tested. Everything here is the
// I/O: reading the endpoint, reading transcripts, typing into panes, writing
// files, sending notifications.

const HEADROOM_SETTINGS_FILE = path.join(DATA_DIR, 'headroom-settings.json');
const HEADROOM_STATE_FILE = path.join(DATA_DIR, 'headroom.json');
// The hook gate reads this directory with `test -e` and no daemon access, which
// is why it is a directory of FILES and not a field in headroom.json. The env
// override exists so the gate's own tests and the route suites can point both
// halves at a scratch dir.
const HEADROOM_DIR = process.env.HUGINN_HEADROOM_DIR || path.join(DATA_DIR, 'headroom');

// Cadence, re-evaluated every tick: a minute while anything is running, five
// minutes while the host is idle. The endpoint rate-limits per account and an
// idle host asking every minute all night spends that allowance on nothing.
const HEADROOM_ACTIVE_MS = 60_000;
const HEADROOM_IDLE_MS = 5 * 60_000;
/** A saved (inactive) profile is priced at most this often — it is not moving. */
const SAVED_PLAN_MS = 30 * 60_000;

/** How long the picker has to appear after `/model` before we give up quietly. */
const PICKER_WAIT_MS = 3_000;
/** How long the `for this session only` line has to appear after `s`. */
const LADDER_FEEDBACK_MS = 5_000;
/** A picker is five or six rows; ten moves means we are not reading it right. */
const MAX_CURSOR_MOVES = 10;
/** Both spellings observed for the session-only confirmation (native-rl §4). */
const LADDER_FEEDBACK_RE = /Set model to .* for this session only|Model set to .* for this session only/;

// ---- auto-resume timings ---------------------------------------------------
//
// Both are overridable by env for the route suites ONLY: a test that had to wait
// out ninety real seconds per case would take longer than the whole suite, and
// the alternative — a test-only branch in the daemon — is a code path nobody
// runs in production. The defaults are the contract's.
const NATIVE_GRACE_MS = Number(process.env.HUGINN_APPD_NATIVE_GRACE_MS) || resumeLib.NATIVE_GRACE_MS;
const CONSENT_GRACE_MS = Number(process.env.HUGINN_APPD_CONSENT_GRACE_MS) || resumeLib.CONSENT_GRACE_MS;
/**
 * How often stalls are re-read while the account is out of room.
 *
 * The headroom tick runs at a minute at its FASTEST and five minutes at its
 * idlest — and a host whose window has just reset is, by definition, idle: every
 * session is sitting on a 429 doing nothing. Waiting five minutes to notice the
 * reset would be most of the delay this whole subsystem exists to remove, so a
 * stalled host polls on its own short clock and stops again as soon as nothing
 * is stalled.
 */
const RESUME_POLL_MS = 10_000;

/**
 * Is the process behind a registry row still the one that wrote it?
 *
 * `/proc/<pid>/stat` field 22 is the process's start time in clock ticks, and
 * the registry records it as `procStart`. Comparing it defeats pid reuse, which
 * on a host that launches sessions all day is not theoretical. Field 22 is
 * counted from after the comm field — which can itself contain spaces and
 * parentheses — so the split starts past the LAST ')'.
 *
 * ⚠ LIVENESS IS THIS, NEVER `updatedAt`. A healthy idle session's
 * `statusUpdatedAt` was measured SEVEN HOURS stale while its process was fine
 * and listed; `status` is written per turn, not as a heartbeat.
 */
function pidLive(pid, procStart) {
  const n = Number(pid);
  if (!Number.isInteger(n) || n <= 0) return false;
  let stat;
  try { stat = fs.readFileSync(`/proc/${n}/stat`, 'utf8'); } catch { return false; }
  const close = stat.lastIndexOf(')');
  if (close < 0) return false;
  const fields = stat.slice(close + 2).split(' ');
  const started = fields[19];
  if (procStart == null || procStart === '') return true;
  return String(started) === String(procStart);
}

/**
 * Claude Code's own session registry — `~/.claude/sessions/<pid>.json`, one file
 * per live process (peer-registry spike §0), every row with `alive` computed.
 *
 * ⚠ ONE READER, MEMOISED. Projects' dashboard asks about twelve members on a
 * five-second poll and the resume path asks about one; a per-lookup directory
 * scan would be twelve readdirs and twelve times N file reads per tick. Two
 * seconds is short enough that a busy/idle flip is never stale on screen and
 * long enough that a whole dashboard pass costs one scan.
 *
 * The fields worth trusting: `sessionId` (the join key), `pid` + `procStart`
 * (liveness), `name`/`nameSource` (the verified peer name), `entrypoint` —
 * `'cli'` for a real interactive TTY, `'sdk-cli'` for `-p` and SDK runs, and the
 * only honest discriminator in the file, because `kind` says `"interactive"`
 * even for a one-shot. `status` is per-turn (`busy`/`idle`/`waiting`).
 *
 * ⚠ AND `tmux` IS NOT ONE OF THEM. It is inherited `$TMUX`: socket-blind,
 * duplicated across nested launches, and wrong for a `-p` run, which records the
 * coordinates of the pane that launched it. Never resolve a session by it.
 */
const NATIVE_REGISTRY_MEMO_MS = 2_000;
let nativeRegistryAt = 0;
let nativeRegistryRows = [];

function readNativeRegistry(now = Date.now(), { force = false } = {}) {
  if (!force && now - nativeRegistryAt < NATIVE_REGISTRY_MEMO_MS) return nativeRegistryRows;
  const dir = path.join(CLAUDE_DIR, 'sessions');
  const rows = [];
  let names = [];
  try { names = fs.readdirSync(dir); } catch { names = []; }
  for (const n of names) {
    if (!n.endsWith('.json')) continue;
    try {
      const o = JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8'));
      if (o && o.sessionId) rows.push({ ...o, alive: pidLive(o.pid, o.procStart) });
    } catch { /* a file being written, or one we have no business reading */ }
  }
  nativeRegistryRows = rows;
  nativeRegistryAt = now;
  return rows;
}

/**
 * The row for one Claude session id, or null — which is the honest answer for a
 * process that has exited: its entry is removed within seconds.
 */
function nativeRegistryEntry(claudeSessionId) {
  if (!claudeSessionId) return null;
  const hit = readNativeRegistry().find((r) => r.sessionId === claudeSessionId);
  if (hit) return hit;
  // ⚠ A MISS RE-READS, AND THAT IS NOT BELT-AND-BRACES. The memo is there so a
  // twelve-member dashboard costs one directory scan, and a two-second-old row
  // is harmless when the row is THERE. It is not harmless when it is absent: a
  // session's registry row appears within a second of launch, and the one moment
  // this answer is read for a decision that is NEVER REVISITED — is Claude
  // Code's own usage-limit wait armed for this session? — is the first tick
  // after a stall is seen. A stale "no row" there makes appd type its
  // continuation while the CLI is about to send its own, and the task runs
  // twice. Caught by routes-resume.test.js the day the memo was added.
  return readNativeRegistry(Date.now(), { force: true })
    .find((r) => r.sessionId === claudeSessionId) || null;
}

/**
 * The raw records at the end of a transcript, newest last.
 *
 * `readTranscript` renders EVENTS, and the stall rules read raw record fields
 * (`isApiErrorMessage`, `apiErrorStatus`, `isMeta`) that rendering deliberately
 * drops. Unparseable lines are skipped, which also disposes of the partial first
 * line every tail read starts with.
 */
function tailRecords(file, bytes = 64 * 1024) {
  const tail = file ? transcriptTail(file, null, bytes) : null;
  if (!tail || !tail.text) return [];
  const out = [];
  for (const line of tail.text.split('\n')) {
    const t = line.trim();
    if (!t) continue;
    try { const rec = JSON.parse(t); if (rec && typeof rec === 'object') out.push(rec); } catch { /* partial */ }
  }
  return out;
}

// ---- settings --------------------------------------------------------------

function saveHeadroomSettings(s) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(`${HEADROOM_SETTINGS_FILE}.tmp`, `${JSON.stringify(s, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(`${HEADROOM_SETTINGS_FILE}.tmp`, HEADROOM_SETTINGS_FILE);
  return s;
}

/**
 * The owner's settings, or the defaults.
 *
 * A stored file that no longer validates falls back to the DEFAULTS rather than
 * being half-honoured: a `ladderPct` below `headsUpPct` (hand-edited, or left by
 * an older shape) makes the arbiter incoherent, and running on a mixture of a
 * broken file and defaults is harder to diagnose than running on defaults and
 * saying so.
 */
function loadHeadroomSettings() {
  let raw = null;
  try { raw = JSON.parse(fs.readFileSync(HEADROOM_SETTINGS_FILE, 'utf8')); } catch { return headroomLib.defaults(); }
  if (!raw || typeof raw !== 'object') return headroomLib.defaults();
  // ⚠ THE FILE IS THE PATCH, NOT THE BASE. validateSettings skips every
  // per-field rule for a key that is not in the PATCH, so passing the stored
  // file as the base ran none of them: a hand-edited or half-written
  // `resumePhrase: "/clear"` loaded clean and was typed into every stalled
  // session at the next window reset. As the patch, each field is judged by the
  // same rule the PATCH route applies, and a bad one falls back to the defaults.
  const v = headroomLib.validateSettings(raw, headroomLib.defaults());
  if (v.ok) return v.settings;
  log(`headroom: ${HEADROOM_SETTINGS_FILE} does not validate (${v.error}); using defaults`);
  return headroomLib.defaults();
}

/**
 * First boot: write the settings file, seeding `defaultModel` from whatever
 * `~/.claude/settings.json` currently says and `accountSwitch` from the
 * autoswitch state this replaces. Idempotent — an existing file is left alone.
 *
 * Also the body of `--seed-headroom-defaults`, which deploy.sh runs.
 */
function seedHeadroomDefaults() {
  if (fs.existsSync(HEADROOM_SETTINGS_FILE)) {
    return { created: false, settings: loadHeadroomSettings() };
  }
  const s = headroomLib.defaults();
  try {
    const cur = JSON.parse(fs.readFileSync(path.join(CLAUDE_DIR, 'settings.json'), 'utf8'));
    if (cur && typeof cur.model === 'string' && cur.model.trim()) s.defaultModel = cur.model.trim();
  } catch { /* no settings file: the contract default stands */ }
  let migrated = null;
  try {
    if (fs.existsSync(AUTOSWITCH_STATE)) {
      s.accountSwitch = headroomLib.migrateAutoswitch(loadAutoswitch());
      migrated = s.accountSwitch;
    }
  } catch { /* history, not state: a bad file costs nothing */ }
  saveHeadroomSettings(s);
  log(`headroom: seeded ${HEADROOM_SETTINGS_FILE} (defaultModel ${s.defaultModel}`
    + `${migrated ? `, accountSwitch migrated from autoswitch.json: ${migrated.enabled ? 'on' : 'off'} at ${migrated.threshold}%` : ''})`);
  return { created: true, settings: s, migrated };
}

// ---- state -----------------------------------------------------------------

function blankStall() {
  return {
    at: null, window: null, resetsAt: null, resetsAtSource: null, resumedAt: null,
    queuedAt: null, gaveUpAt: null,
    how: null, attempts: 0, nativeArmed: false, notifiedAt: null, why: null, text: null,
  };
}

function normalizeHeadroomState(o) {
  const src = o && typeof o === 'object' ? o : {};
  return {
    v: 1,
    mode: typeof src.mode === 'string' ? src.mode : 'ok',
    accounts: src.accounts && typeof src.accounts === 'object' ? src.accounts : {},
    resets: Array.isArray(src.resets) ? src.resets : [],
    sessions: src.sessions && typeof src.sessions === 'object' ? src.sessions : {},
    sentinels: src.sentinels && typeof src.sentinels === 'object' ? src.sentinels : { STOP: null, 'STOP-FABLE': null },
    arbiter: {
      lastSwitchAt: 0, lastLadderAt: 0, lastAction: null, why: 'not run yet',
      // ⚠ 0, NEVER null. The Kotlin client declares `lastResumeAt: Long = 0`
      // and the Json config has no `coerceInputValues`, so an explicit null
      // fails the WHOLE /v1/watch decode — the desktop watch loop, every
      // notification decision and the headroom toasts are dead on a fresh
      // daemon until something resumes once.
      switches: 0, lastIdleWarnAt: 0, lastResumeAt: 0,
      ...(src.arbiter && typeof src.arbiter === 'object' ? src.arbiter : {}),
    },
    // ⚠ NORMALISED, NOT SPREAD. A headroom.json written by a daemon that
    // predates keep-awake has no such key, and the whole once-per-window guard
    // is `now - lastAt`: an absent or half-written number that reached the
    // comparison as undefined would make every tick a fresh ping. normalize()
    // coerces each field to a real number, so a missing file reads as "never
    // pinged" rather than as "no idea".
    keepAwake: keepAwakeLib.normalize(src.keepAwake),
  };
}

let headroomStateCache = null;
/** The live state, read from disk once and kept in memory thereafter. */
function hstate() {
  if (!headroomStateCache) {
    let raw = null;
    try { raw = JSON.parse(fs.readFileSync(HEADROOM_STATE_FILE, 'utf8')); } catch { /* first run */ }
    headroomStateCache = normalizeHeadroomState(raw);
  }
  return headroomStateCache;
}

function saveHeadroomState(st) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(`${HEADROOM_STATE_FILE}.tmp`, JSON.stringify(st, null, 2), { mode: 0o600 });
    fs.renameSync(`${HEADROOM_STATE_FILE}.tmp`, HEADROOM_STATE_FILE);
  } catch (e) { log('headroom: could not persist state', e.message); }
}

// ---- reading a session's model --------------------------------------------

/**
 * What model this session is ACTUALLY on, and whether a person put it there.
 *
 * Both answers come from the transcript rather than the pane: the status line
 * carries a display name that has to be mapped back, while an assistant record
 * carries the id verbatim. A human's `/model` leaves a command record with a
 * timestamp, which is the only way to know that the owner has just answered the
 * question the ladder is about to answer (and so must not be overruled).
 *
 * The `model_consent_fallback` scan is the CLI's own Fable-consent event. It is
 * read off the raw tail because readTranscript does not surface system
 * subtypes. ⚠ UNVERIFIED AGAINST A REAL CAPTURE — the shape comes from the
 * binary's strings (spike native-rl §7), so it is written defensively: an
 * absent record simply means nothing is detected, which is today's behaviour.
 */
function readSessionModel(name) {
  const st = readSessionState(name);
  const out = { model: null, humanSetModelAt: null, consent: null, transcript: null, sessionId: st ? st.sessionId : null };
  if (!st || !st.transcript) return out;
  out.transcript = st.transcript;
  try {
    const t = readTranscript(st.transcript, { limit: 24 });
    if (t && t.model) out.model = t.model;
    for (const ev of (t && t.events) || []) {
      if (ev.kind === 'command' && /^\/model\b/.test(String(ev.text || ''))) {
        out.humanSetModelAt = Number(ev.ts) ? Number(ev.ts) * 1000 : Date.now();
      }
    }
  } catch { /* unreadable transcript is not fatal for a tick */ }
  const tail = transcriptTail(st.transcript, null, 32 * 1024);
  if (tail && tail.text.includes('model_consent_fallback')) {
    for (const line of tail.text.split('\n')) {
      if (!line.includes('model_consent_fallback')) continue;
      try {
        const rec = JSON.parse(line);
        if (rec && rec.type === 'system' && rec.subtype === 'model_consent_fallback') {
          out.consent = {
            choice: rec.choice ?? null,
            to: rec.toModel ?? rec.to_model ?? rec.model ?? null,
            persistedAsDefault: rec.persisted_as_default === true || rec.persistedAsDefault === true,
          };
        }
      } catch { /* a truncated first line in the tail */ }
    }
  }
  return out;
}

/**
 * Put the host's default model back after the CLI wrote one itself.
 *
 * NOT the primary path and deliberately narrow. A `/model <name>` typed by a
 * human persists their default and that is theirs to keep — this restores the
 * key only when the native Fable consent dialog recorded
 * `persisted_as_default: true`, i.e. when the CLI rewrote a shared host setting
 * as a side effect of one session running out of Fable. Every other concurrent
 * session on this host reads that file.
 *
 * Refuses on an unparseable file rather than replacing it: half of a settings
 * file is worse than a wrong model.
 */
function repairDefaultModel(expected) {
  const file = path.join(CLAUDE_DIR, 'settings.json');
  let raw;
  try { raw = fs.readFileSync(file, 'utf8'); } catch { return { ok: false, error: 'no settings.json to repair' }; }
  let o;
  try { o = JSON.parse(raw); } catch { return { ok: false, error: 'settings.json is unparseable — refusing to rewrite it' }; }
  if (!o || typeof o !== 'object' || Array.isArray(o)) {
    return { ok: false, error: 'settings.json is not an object — refusing to rewrite it' };
  }
  if (o.model === expected) return { ok: true, changed: false };
  const was = o.model ?? null;
  o.model = expected;
  try {
    // 2-space JSON, and the trailing newline the file had (or did not have).
    const body = `${JSON.stringify(o, null, 2)}${raw.endsWith('\n') ? '\n' : ''}`;
    fs.writeFileSync(`${file}.tmp`, body, { mode: 0o600 });
    fs.renameSync(`${file}.tmp`, file);
  } catch (e) { return { ok: false, error: e.message }; }
  log(`headroom: restored the host default model to ${expected} (was ${was})`);
  return { ok: true, changed: true, was };
}

// ---- the ladder ------------------------------------------------------------

async function paneLines(name) {
  const c = await run('tmux', ['capture-pane', '-p', '-t', `=${name}:`]);
  if (c.err) return null;
  return c.stdout.replace(/\n$/, '').split('\n');
}

/**
 * Move a LIVE session to another model for THIS SESSION ONLY.
 *
 * Measured (native-rl spike): `/model <name>` takes no flags and always writes
 * the host default; the argument-less picker's `s` key is the only in-session
 * switch that writes nothing. So this drives the picker:
 *
 *   /model + Enter -> wait for `Select model` -> find the target row BY LABEL
 *   -> walk the ❯ cursor one key at a time, re-reading the pane after each ->
 *   press `s` -> expect `… for this session only`.
 *
 * ⚠ BY LABEL, NEVER BY ROW NUMBER. The list is built from the installed CLI's
 * model table and a `claude update` renumbers it; a remembered number would
 * press `s` on a different model and report success.
 *
 * Every failure path presses Esc and returns `delivery_unconfirmed` rather than
 * guessing: an abandoned picker left open blocks the next message the owner
 * types, and a ladder that claims a move it did not make is worse than one that
 * says it could not.
 *
 * Runs as one queue JOB, so it starts only at a turn boundary with a bare caret.
 */
async function applyLadder(name, to) {
  const target = `=${name}:`;
  const esc = () => run('tmux', ['send-keys', '-t', target, 'Escape']).catch(() => { });
  // ⚠ ESCAPE **AND** C-u, because the two ways this can fail leave different
  // wreckage. Esc closes whatever `/model` opened — a picker left open blocks
  // the next message the owner types. C-u clears the COMPOSER, which is where
  // `/model` itself is sitting if the Enter never went: the pump then released
  // the next entry into it and the session received
  // `/modelYour usage limit has reset. Continue the task…`.
  const fail = async (reason) => {
    await esc();
    await run('tmux', ['send-keys', '-t', target, 'C-u']).catch(() => { });
    return { ok: false, delivery: 'delivery_unconfirmed', reason };
  };

  const typed = await run('tmux', ['send-keys', '-t', target, '-l', '--', '/model']);
  if (typed.err) return { ok: false, delivery: 'delivery_unconfirmed', reason: 'could not type /model' };
  await sleep(SUBMIT_BEAT_MS);
  const ent = await run('tmux', ['send-keys', '-t', target, 'Enter']);
  // ⚠ THROUGH fail(), because `/model` is ALREADY IN THE COMPOSER by here. This
  // was the one early exit that skipped the Escape after typing text, so the
  // pump released the next entry into a composer holding `/model` and the
  // session received `/modelYour usage limit has reset. Continue the task…`.
  if (ent.err) return fail('could not submit /model');

  let rows = null;
  const openBy = Date.now() + PICKER_WAIT_MS;
  for (;;) {
    rows = parseModelPicker(await paneLines(name));
    if (rows) break;
    if (Date.now() >= openBy) break;
    await sleep(200);
  }
  // Nothing typed further: if the picker is not there, the keys would land in
  // whatever IS there — which is the failure this whole path exists to avoid.
  if (!rows) return { ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' };

  let want = rows.find((r) => r.family === to);
  if (!want) return fail(`the picker offers no ${to} row`);
  let cursor = rows.find((r) => r.cursor) || null;
  if (!cursor) return fail('the picker draws no cursor');

  for (let moves = 0; cursor.label !== want.label; moves++) {
    if (moves >= MAX_CURSOR_MOVES) return fail(`the cursor never reached ${want.label}`);
    const key = cursor.n < want.n ? 'Down' : 'Up';
    const r = await run('tmux', ['send-keys', '-t', target, key]);
    if (r.err) return fail(`tmux refused ${key}`);
    await sleep(150);
    // Re-read EVERY time: verifying the highlighted label before the next key is
    // what makes a shifted list a no-op instead of a wrong model.
    const fresh = parseModelPicker(await paneLines(name));
    if (!fresh) return fail('the picker vanished mid-move');
    const c2 = fresh.find((x) => x.cursor);
    const w2 = fresh.find((x) => x.family === to);
    if (!c2 || !w2) return fail('the picker redrew without a cursor or a target row');
    cursor = c2;
    want = w2;
  }

  // One LAST read before the key that actually changes the model. Every cursor
  // move re-reads, but the gap between the final move and `s` was not checked —
  // and if the picker closed in it, `s` lands in the composer instead.
  const lastLook = parseModelPicker(await paneLines(name));
  if (!lastLook) return fail('the picker closed before the model could be set');
  const stillThere = lastLook.find((x) => x.cursor);
  if (!stillThere || stillThere.label !== want.label) {
    return fail(`the cursor left ${want.label} before the model could be set`);
  }
  const set = await run('tmux', ['send-keys', '-t', target, '-l', '--', 's']);
  if (set.err) return fail('tmux refused the s key');
  const by = Date.now() + LADDER_FEEDBACK_MS;
  for (;;) {
    const lines = await paneLines(name);
    if (lines && LADDER_FEEDBACK_RE.test(lines.join('\n'))) {
      return { ok: true, delivery: 'confirmed', to };
    }
    if (Date.now() >= by) break;
    await sleep(200);
  }
  return fail('no "for this session only" confirmation appeared');
}

// ---- the tick --------------------------------------------------------------

let headroomBusy = false;
let headroomTimer = null;
const savedPlanAt = new Map();      // slug -> ms of its last pricing

/** One account's row in `headroom.json`, carrying the RED clocks forward. */
function accountRow(email, windows, live, now, prev, settings) {
  const prevRed = (prev && prev.red) || {};
  const red = {};
  for (const w of headroomLib.WINDOWS) {
    const win = windows[w];
    const mode = win ? headroomLib.classify(win, settings) : 'ok';
    if (mode === 'red' || mode === 'exhausted') {
      // `since` is kept from the previous pass: a clock that restarts every tick
      // cannot say how long a window has been red, which is the one thing it is
      // for.
      red[w] = { since: (prevRed[w] && prevRed[w].since) || now, resetsAt: (win && win.resetsAt) || (prevRed[w] && prevRed[w].resetsAt) || null };
    } else {
      red[w] = null;
    }
  }
  return { email: email ?? null, readAt: now, live: !!live, windows, red };
}

function sessionRecord(state, id, name) {
  let rec = state.sessions[id];
  if (!rec) {
    rec = {
      name, model: null, family: null, ladder: null, headsUpAt: null,
      nativeSwitch: { seenAt: null, to: null }, offeredLadderUpAt: null,
      humanSetModelAt: null, stall: blankStall(),
    };
    state.sessions[id] = rec;
  }
  rec.name = name;
  if (!rec.nativeSwitch) rec.nativeSwitch = { seenAt: null, to: null };
  if (!rec.stall) rec.stall = blankStall();
  return rec;
}

/** Whichever cadence the host is actually running at right now. */
function headroomCadence(sessions) {
  const busy = (sessions || []).some((s) => s.state === 'running' || s.state === 'attention')
    || activeRuns.size > 0;
  return busy ? HEADROOM_ACTIVE_MS : HEADROOM_IDLE_MS;
}

function scheduleHeadroom(ms) {
  if (headroomTimer) clearTimeout(headroomTimer);
  headroomTimer = setTimeout(() => {
    headroomTick().catch((e) => log('headroom: tick failed', e.message));
  }, ms);
  if (headroomTimer.unref) headroomTimer.unref();
}

async function headroomTick() {
  if (headroomBusy) return;
  headroomBusy = true;
  let next = HEADROOM_IDLE_MS;
  try {
    next = await headroomTickInner();
  } catch (e) {
    hstate().arbiter.why = `last tick failed: ${e.message}`;
    log('headroom: tick failed', e.message);
  } finally {
    headroomBusy = false;
    scheduleHeadroom(next);
  }
}

async function headroomTickInner() {
  const settings = loadHeadroomSettings();
  const state = hstate();
  const now = Date.now();
  const prevAccounts = state.accounts || {};

  // ---- 1. the accounts -----------------------------------------------------
  // Keep the live login's profile in step with its rotating tokens first, or
  // nothing below can tell which stored record is the active one.
  await saveIdentified(null, accounts.readActive()).catch(() => null);
  if (Date.now() - planCache.at > PLAN_TTL_MS && !planCache.running) await fetchPlan();

  const saved = accounts.list();
  const activeRec = saved.find((a) => a.isActive) || null;
  const ident = (planCache.data && planCache.data.account) || null;
  const activeSlug = (activeRec && activeRec.slug) || (ident && ident.slug) || 'active';
  const activeEmail = (activeRec && activeRec.email) || (ident && ident.email) || null;
  const activeWindows = headroomLib.windowsOf((planCache.data && planCache.data.limits) || []);

  const nextAccounts = {};
  // A host with no login at all gets NO active row rather than an empty one:
  // "no active account is identifiable" is a true and useful `why`, while a row
  // of nulls reads as an account whose usage nobody can see.
  const haveActive = !!activeRec || !!(planCache.data && planCache.data.limits && planCache.data.limits.length);
  if (haveActive) {
    nextAccounts[activeSlug] = accountRow(activeEmail, activeWindows, true, now, prevAccounts[activeSlug], settings);
  }

  for (const a of saved) {
    if (a.isActive || a.slug === activeSlug) continue;
    const prev = prevAccounts[a.slug] || null;
    const due = now - (savedPlanAt.get(a.slug) || 0) >= SAVED_PLAN_MS;
    let windows = prev ? prev.windows : {};
    if (due) {
      savedPlanAt.set(a.slug, now);
      const rec = accounts.readProfile(a.slug);
      // A live read if the stored token still authenticates (it usually does
      // not — an access token outlives its account's turn by hours at most);
      // otherwise the last reading taken while it WAS active, aged forward.
      const plan = rec ? await planForCredentials(rec.credentials) : null;
      if (plan) {
        accounts.recordPlan(a.slug, plan);
        windows = headroomLib.windowsOf(plan.limits);
      } else if (rec) {
        windows = headroomLib.windowsOf(agedLimits(rec.lastPlan, now));
      }
    }
    nextAccounts[a.slug] = accountRow(a.email, windows || {}, false, due ? now : (prev ? prev.readAt : now), prevAccounts[a.slug], settings);
    if (!due && prev) nextAccounts[a.slug].readAt = prev.readAt;
  }

  // ---- 2. resets -----------------------------------------------------------
  const resets = headroomLib.detectResets(prevAccounts, nextAccounts, now, settings);
  for (const r of resets) {
    state.resets.push({ ...r, at: now });
    log(`headroom: ${r.window} reset for ${nextAccounts[r.slug] ? nextAccounts[r.slug].email || r.slug : r.slug} (now ${Math.round(r.percent)}%)`);
  }
  if (state.resets.length > 50) state.resets = state.resets.slice(-50);
  state.accounts = nextAccounts;
  const worst = headroomLib.worstWindow(activeWindows, settings);
  state.mode = worst ? worst.mode : 'ok';
  const lastFableResetAt = state.resets
    .filter((r) => r.window === 'weekly_fable' && r.slug === activeSlug)
    .reduce((acc, r) => Math.max(acc, Number(r.at) || 0), 0);

  // ---- 3. the sessions -----------------------------------------------------
  const live = (await listSessions()) || [];
  const liveIds = new Set();
  const model = [];
  for (const s of live) {
    if (!s.claudeSessionId) continue;
    liveIds.add(s.claudeSessionId);
    const rec = sessionRecord(state, s.claudeSessionId, s.name);
    const read = readSessionModel(s.name);
    const wasFamily = rec.family;
    const family = headroomLib.familyOf(read.model) || rec.family;
    rec.model = read.model || rec.model;
    rec.family = family;
    if (read.humanSetModelAt) rec.humanSetModelAt = read.humanSetModelAt;

    // A NATIVE switch: the family moved away from fable and appd typed nothing.
    // Recorded, logged, and never fought — the 92% ladder exists precisely so
    // the consent dialog does not appear on an attended session in the first
    // place, and once it has, the owner may well prefer to stay where it put
    // them.
    //
    // ⚠ NOT WHILE A HAND-SET MODEL IS STILL WARM. The family is read off the
    // last assistant record, which still names the OLD model until the session
    // next replies — so an undo (which moves the pane back to fable and stamps
    // `humanSetModelAt`) was immediately followed by a tick that read the stale
    // record as a brand-new native fallback and re-armed the very mark the undo
    // had just cleared, leaving the session unladderable for good. Same grace
    // window `decide` already holds ladder_down off for.
    const handSet = rec.humanSetModelAt
      && now - Number(rec.humanSetModelAt) < headroomLib.HUMAN_MODEL_GRACE_MS;
    if (wasFamily === 'fable' && family && family !== 'fable' && !handSet
      && !(rec.ladder && rec.ladder.to) && !rec.nativeSwitch.seenAt) {
      rec.nativeSwitch = { seenAt: now, to: family, how: 'native' };
      log(`headroom: native fallback observed on ${s.name} -> ${family}`);
    }
    if (read.consent) {
      if (!rec.nativeSwitch.seenAt) {
        rec.nativeSwitch = { seenAt: now, to: read.consent.to || family, how: 'consent', choice: read.consent.choice };
      }
      if (read.consent.persistedAsDefault) {
        const r = repairDefaultModel(settings.defaultModel);
        if (r.ok && r.changed) rec.ladder = { ...(rec.ladder || {}), repairedDefault: true };
        else if (!r.ok) log(`headroom: could not restore the host default model (${r.error})`);
      }
    }

    // Stall detection on EVERY tick (design §4). Cheap: one tail read per live
    // session, the same read the ladder's model probe already pays for.
    try { await noteStall(rec, s, settings, activeWindows, now); }
    catch (e) { log(`headroom: reading ${s.name}'s tail failed: ${e.message}`); }

    model.push({
      claudeSessionId: s.claudeSessionId,
      name: s.name,
      state: s.state,
      model: rec.model,
      family: rec.family,
      ladder: rec.ladder,
      headsUpAt: rec.headsUpAt,
      nativeSwitch: rec.nativeSwitch,
      offeredLadderUpAt: rec.offeredLadderUpAt,
      humanSetModelAt: rec.humanSetModelAt,
    });
  }
  // Prune records for sessions that are neither live nor on the restore
  // registry: a ladder belongs to a run, and a run that has ended takes it.
  const registered = new Set(Object.values(loadRegistry()).map((r) => r && r.claudeSessionId).filter(Boolean));
  for (const id of Object.keys(state.sessions)) {
    if (!liveIds.has(id) && !registered.has(id)) delete state.sessions[id];
  }

  // ---- 4. the arbiter ------------------------------------------------------
  const candidates = Object.entries(nextAccounts)
    .filter(([slug]) => slug !== activeSlug)
    .map(([slug, a]) => ({ slug, email: a.email, windows: a.windows }));
  const verdict = headroomLib.decide({
    active: haveActive ? { slug: activeSlug, email: activeEmail, windows: activeWindows } : null,
    candidates,
    sessions: model,
    settings,
    state: { lastSwitchAt: state.arbiter.lastSwitchAt || 0, lastLadderAt: state.arbiter.lastLadderAt || 0 },
    sentinels: state.sentinels,
    lastFableResetAt,
    now,
  });
  state.arbiter.why = verdict.why;

  for (const action of verdict.actions) {
    try {
      await applyHeadroomAction(action, { state, settings, now, activeWindows, activeSlug, activeEmail });
    } catch (e) {
      log(`headroom: applying ${action.type} failed: ${e.message}`);
    }
  }

  // Auto-resume runs AFTER the arbiter, deliberately: a session that just
  // laddered to opus is not stalled, and a stalled session is not a candidate
  // for a ladder (a 5-hour cap blocks every model, design §2). Letting the
  // arbiter go first also means a `switch_account` has already landed before
  // anything reads the window percentages back.
  //
  // ⚠ ONE RESUME PASS AT A TIME, ACROSS BOTH CLOCKS. The 10-second poll and this
  // minute tick both apply resumes against the same in-memory state, and both
  // await a send queue that can park for a turn boundary — so without this the
  // window between "decide to resume" and "mark it resumed" is wide enough for
  // the other clock to decide the same thing and type the phrase twice.
  if (!resumeBusy) {
    resumeBusy = true;
    try {
      await applyResumes({
        state, settings, now, activeWindows,
        resetWindows: recentResetWindows(state, now, activeSlug),
        sessions: model,
      });
      await consentWatch(state, settings, live, now);
    } catch (e) {
      log(`headroom: applying resumes failed: ${e.message}`);
    } finally {
      resumeBusy = false;
    }
  }

  // ---- 6. keep-awake -------------------------------------------------------
  // LAST, deliberately. Everything above may change the picture this decides on
  // — the arbiter can move the active account, a resume can start a window on
  // its own — and a ping that went out first would be spending a window on the
  // login we were about to leave.
  await maybeKeepAwake({ state, settings, activeWindows, worst, verdict, now });

  saveHeadroomState(state);
  return headroomCadence(live);
}

/** One ping at a time, within this process. The disk `lastAt` covers the rest. */
let keepAwakeBusy = false;

/**
 * Keep one 5-hour window rotating: send a tiny request when none is running.
 *
 * The decision is [keepAwakeLib.decide]'s and every veto in it is asserted; this
 * function is the I/O half — read the sentinels, spawn the CLI, write the
 * counters — and the ORDER inside it is the load-bearing part:
 *
 *   1. stamp `lastAt` and SAVE IT, then
 *   2. spawn.
 *
 * Never the other way round. A daemon that dies between the two must come back
 * believing the window has been dealt with; the cost of a missed ping is that
 * the window starts when the owner does, which is exactly today's behaviour,
 * while the cost of a double ping is real money and a wrong number on the
 * Status page.
 */
async function maybeKeepAwake({ state, settings, activeWindows, worst, verdict, now }) {
  if (keepAwakeBusy) return;

  let sentinels = state.sentinels;
  try { sentinels = sentinelsLib.state(HEADROOM_DIR); } catch { /* no dir yet: nothing armed */ }

  const d = keepAwakeLib.decide({
    settings,
    windows: activeWindows,
    worst,
    sentinels,
    keepAwake: state.keepAwake,
    actions: (verdict && verdict.actions) || [],
    arbiter: state.arbiter,
    planError: planCache.error,
    haveReading: !!planCache.data,
    now,
    minutes: keepAwakeLib.minutesOfDay(now),
  });
  // Recorded either way. A feature that is switched on and doing nothing has a
  // dozen legitimate reasons for it, and this is the only one of them the owner
  // can read without a shell on the host. It also makes the wire testable: "did
  // not ping" and "never even looked" are the same picture without it, which is
  // exactly how the first version of the red-week test passed with its veto
  // deleted.
  state.keepAwake = keepAwakeLib.noteDecision(state.keepAwake, d, now);
  if (!d.fire) return;

  keepAwakeBusy = true;
  const before = keepAwakeLib.normalize(state.keepAwake);
  state.keepAwake = keepAwakeLib.noteFired(before, now);
  saveHeadroomState(state);

  const model = settings.keepAwakeModel || keepAwakeLib.DEFAULT_MODEL;
  const argv = keepAwakeLib.argvFor(model);
  const started = Date.now();
  try {
    const r = await run('claude', argv, { timeout: keepAwakeLib.TIMEOUT_MS, cwd: DATA_DIR });
    const outcome = keepAwakeLib.classifyOutcome(r);
    state.keepAwake = keepAwakeLib.afterSpawn(state.keepAwake, outcome, now);
    const ms = Date.now() - started;
    if (outcome === 'ok') {
      log(`headroom: kept the 5-hour window awake (${model}, ${ms}ms, `
        + `${state.keepAwake.keptAwakeToday}x today)`);
    } else {
      // The reason, not just the verdict: "hold" and "retry" are the difference
      // between a window lost for five hours and one retried in a minute, and
      // the journal is the only place that distinction is ever visible.
      const why = ((r.stderr || r.err.message || '').trim() || 'no output').slice(0, 160);
      log(`headroom: keep-awake ping failed after ${ms}ms (${outcome}): ${why}`);
    }
  } catch (e) {
    state.keepAwake = keepAwakeLib.afterSpawn(state.keepAwake, 'hold', now);
    log(`headroom: keep-awake ping threw: ${e.message}`);
  } finally {
    keepAwakeBusy = false;
    saveHeadroomState(state);
  }
}

/** One action from the arbiter, with its preconditions re-checked at apply time. */
async function applyHeadroomAction(action, ctx) {
  const { state, settings, now } = ctx;
  switch (action.type) {
    case 'switch_account': {
      const d = action.detail;
      // The same gate the owner's own button has. Skipped, logged, and written
      // into `why` rather than attempted: a dead pair installed unattended signs
      // the host out of Claude Code entirely.
      const gate = await ensureSwitchable(action.slug);
      if (!gate.ok) {
        log(`headroom: not switching to ${d.toEmail || action.slug}: ${gate.error}`);
        state.arbiter.why = gate.error;
        return;
      }
      const r = await performSwitch(action.slug);
      if (!r.ok) { log(`headroom: activate failed: ${r.error}`); return; }
      state.arbiter.lastSwitchAt = now;
      state.arbiter.switches = (state.arbiter.switches || 0) + 1;
      state.arbiter.lastAction = { type: 'switch_account', at: now, ...d };
      // A silent identity change would be spooky: the phone and Telegram both
      // hear about it, whatever the alert toggle says. Statement, not question.
      const text = `${d.fromEmail || d.from} hit ${d.fromPercent}% (${d.fromLabel}) — `
        + `now on ${d.toEmail || d.to} at ${d.toPercent}%. Running sessions keep the `
        + `old account until they restart.`;
      const push = await deliverPush({ kind: 'account_switch', title: 'Switched Claude account', text, subject: d.to });
      if (!push.sent) await deliverTelegram(`\u{1F501} Switched Claude account\n${text}`);
      log(`headroom: ${d.fromEmail} (${d.fromPercent}%) -> ${d.toEmail} (${d.toPercent}%)`);
      return;
    }
    case 'heads_up': {
      const rec = state.sessions[action.claudeSessionId];
      if (!rec || rec.headsUpAt) return;
      const text = settings.headsUpText
        .replace(/\{pct\}/g, String(action.pct))
        .replace(/\{next\}/g, action.next)
        .replace(/\{ladderPct\}/g, String(settings.ladderPct));
      // Through the queue: a line typed mid-turn is absorbed INTO that turn and
      // silently rewrites what it was told to do (spike E4).
      const out = await enqueueSend(action.name, text, { automated: true, origin: 'headroom', kind: 'headsUp' });
      // Marked once it is accepted, not once it lands: the queue is the thing
      // holding it, and re-queueing the same note every 60 s would pile up six
      // copies behind one long turn.
      rec.headsUpAt = now;
      state.arbiter.lastAction = { type: 'heads_up', at: now, name: action.name, queued: !out.delivered };
      log(`headroom: heads-up queued for ${action.name} at ${action.pct}% of the Fable week`);
      return;
    }
    case 'ladder_down':
    case 'ladder_up': {
      const rec = state.sessions[action.claudeSessionId];
      if (!rec) return;
      const from = action.from;
      const to = action.to;
      const r = await enqueueJob(action.name, () => applyLadder(action.name, to), {
        automated: true, origin: 'headroom', kind: 'model', family: from,
        onSettle: (v) => resolvePendingLadder(state, action, v),
      });
      // One cooldown for every ladder move on the host, spent when the move is
      // ATTEMPTED: a picker that never opened must not let the next tick try
      // again immediately.
      state.arbiter.lastLadderAt = now;
      const result = r.result || {};
      if (r.dropped) {
        log(`headroom: ladder for ${action.name} dropped (${r.dropped})`);
        return;
      }
      // ⚠ QUEUED IS NOT FAILED. enqueueJob resolves after ONE pump pass and the
      // picker may not open until the running turn ends, so `r.result` is null
      // for a job that has not started — which used to be indistinguishable from
      // a job that ran and could not confirm. The consequence was worse than the
      // label: `rec.ladder.to` was written for a move that had not happened, so
      // `decide` reported "already on opus" and the session later became a
      // ladder_up candidate — appd typing /model to move a session BACK from a
      // rung it never left. The record says `pending`, and the job's own settle
      // (resolvePendingLadder) turns that into confirmed or unconfirmed.
      if (r.result == null) {
        rec.ladder = action.type === 'ladder_up'
          ? { ...(rec.ladder || {}), delivery: 'pending' }
          : { from, to, at: now, delivery: 'pending', sessionOnly: true };
        state.arbiter.lastAction = { type: action.type, at: now, name: action.name, to, delivery: 'pending' };
        log(`headroom: ladder ${from} -> ${to} on ${action.name} is queued for a turn boundary`);
        return;
      }
      if (!result.ok) {
        rec.ladder = { ...(rec.ladder || {}), from, to, at: now, delivery: 'delivery_unconfirmed', sessionOnly: true };
        log(`headroom: ladder ${from} -> ${to} on ${action.name} unconfirmed (${result.reason || result.message || 'no confirmation'})`);
        state.arbiter.lastAction = { type: action.type, at: now, name: action.name, delivery: 'delivery_unconfirmed' };
        return;
      }
      if (action.type === 'ladder_up') {
        rec.ladder = null;
        rec.family = to;
        log(`headroom: ${action.name} moved back to ${to}`);
        state.arbiter.lastAction = { type: 'ladder_up', at: now, name: action.name, to };
        return;
      }
      rec.ladder = { from, to, at: now, delivery: 'confirmed', sessionOnly: true, repairedDefault: false };
      rec.family = to;
      state.arbiter.lastAction = { type: 'ladder_down', at: now, name: action.name, from, to };
      log(`headroom: ${action.name} moved ${from} -> ${to} for this session only`);
      const text = `${action.name} was at ${action.pct}% of the Fable week — it is on ${to} for this `
        + `session only. The host's default model is untouched.`;
      const push = await deliverPush({
        kind: 'headroom_downgraded',
        title: `Moved ${action.name} to ${to}`,
        text,
        subject: action.name,
        // The buttons, and what they need to act on. An "Undo" with no session
        // name is a button the app cannot aim.
        options: ['Undo', 'OK'],
        payload: { session: action.name, to, from },
      });
      if (!push.sent) await deliverTelegram(`\u{1F4C9} Moved ${action.name} to ${to}\n${text}`);
      return;
    }
    case 'offer_ladder_up': {
      // OFFERED, never applied: a native consent swap never returns to Fable by
      // itself (measured), and the owner may prefer to stay on the cheaper model.
      const rec = state.sessions[action.claudeSessionId];
      if (!rec || rec.offeredLadderUpAt) return;
      rec.offeredLadderUpAt = now;
      const text = `${action.name} was moved to ${action.from} by Claude Code when Fable ran out. `
        + 'The Fable week has reset — move it back?';
      const push = await deliverPush({
        kind: 'headroom_ladder_up',
        title: 'Back to Fable?',
        text,
        subject: action.name,
        options: ['Back to Fable', 'Stay'],
        payload: { session: action.name, to: action.to },
      });
      if (!push.sent) await deliverTelegram(`\u{1F199} Back to Fable?\n${text}`);
      log(`headroom: offered ${action.name} a move back to fable`);
      return;
    }
    case 'sentinels': {
      const plan = action.plan;
      writeSentinels(plan, state, ctx.settings);
      return;
    }
    default:
      log(`headroom: unknown action ${action.type}`);
  }
}

/**
 * Turn a `pending` ladder record into what actually happened.
 *
 * Runs from the queue entry's own settle, which is the ONLY place that knows —
 * `applyHeadroomAction` returned minutes earlier, when the job was merely
 * accepted. Only a record we ourselves marked pending is resolved: anything else
 * has been rewritten since (an undo, a native switch, a later move), and the
 * later writer is the one that is right.
 */
function resolvePendingLadder(state, action, v) {
  const rec = state.sessions[action.claudeSessionId];
  const cur = rec && rec.ladder;
  if (!cur || cur.delivery !== 'pending') return;
  const { name, from, to } = action;
  const up = action.type === 'ladder_up';
  const result = (v && v.result) || {};

  if (v && v.dropped) {
    // Binned before it ran (the family already moved, the owner typed, the wait
    // timed out). Put the record back to what it was before we marked it.
    rec.ladder = up ? { ...cur, delivery: 'confirmed' } : null;
    log(`headroom: the queued ladder for ${name} was dropped (${v.dropped})`);
  } else if (!result.ok) {
    rec.ladder = { ...cur, delivery: 'delivery_unconfirmed' };
    log(`headroom: queued ladder ${from} -> ${to} on ${name} unconfirmed (${result.reason || result.message || 'no confirmation'})`);
  } else if (up) {
    rec.ladder = null;
    rec.family = to;
    log(`headroom: ${name} moved back to ${to}`);
  } else {
    rec.ladder = { ...cur, delivery: 'confirmed', repairedDefault: false };
    rec.family = to;
    log(`headroom: ${name} moved ${from} -> ${to} for this session only`);
    // The notification the synchronous path sends, sent here instead — the owner
    // hears about the move when it HAPPENS, not when it was queued.
    const text = `${name} was moved off Fable — it is on ${to} for this session only. `
      + "The host's default model is untouched.";
    deliverPush({
      kind: 'headroom_downgraded',
      title: `Moved ${name} to ${to}`,
      text,
      subject: name,
      options: ['Undo', 'OK'],
      payload: { session: name, to, from },
    }).then((push) => {
      if (!push.sent) return deliverTelegram(`\u{1F4C9} Moved ${name} to ${to}\n${text}`);
      return null;
    }).catch((e) => log(`headroom: could not announce the queued ladder: ${e.message}`));
  }
  saveHeadroomState(state);
}

/**
 * Arm and clear the files the hook gate watches, and rewrite `fable-sessions`.
 *
 * The list is what lets a bash hook with no daemon access answer "is the session
 * that is spawning on Fable?" — the SubagentStart payload carries no model.
 */
function writeSentinels(plan, state, settings) {
  try {
    if (plan.STOP) {
      const a = sentinelsLib.arm(HEADROOM_DIR, 'STOP', plan.reasons.STOP || plan.reason);
      state.sentinels.STOP = { since: a.since, reason: a.reason };
      if (a.created) log(`headroom: armed STOP (${a.reason})`);
      // The HEARTBEAT. An armed sentinel with no expiry wedges every spawn for
      // half an hour if this process dies while it is up; the gate ages it out
      // against this mtime, so re-asserting the plan must also say "still me".
      sentinelsLib.touch(HEADROOM_DIR, 'STOP');
    } else if (state.sentinels.STOP || sentinelsLib.state(HEADROOM_DIR).STOP) {
      if (sentinelsLib.clear(HEADROOM_DIR, 'STOP')) log('headroom: cleared STOP');
      state.sentinels.STOP = null;
    }
    if (plan.STOP_FABLE) {
      const a = sentinelsLib.arm(HEADROOM_DIR, 'STOP-FABLE', plan.reasons['STOP-FABLE'] || plan.reason);
      state.sentinels['STOP-FABLE'] = { since: a.since, reason: a.reason };
      if (a.created) log(`headroom: armed STOP-FABLE (${a.reason})`);
      sentinelsLib.touch(HEADROOM_DIR, 'STOP-FABLE');
    } else if (state.sentinels['STOP-FABLE'] || sentinelsLib.state(HEADROOM_DIR)['STOP-FABLE']) {
      if (sentinelsLib.clear(HEADROOM_DIR, 'STOP-FABLE')) log('headroom: cleared STOP-FABLE');
      state.sentinels['STOP-FABLE'] = null;
    }
    const fable = Object.entries(state.sessions)
      .filter(([, r]) => r && r.family === 'fable')
      .map(([id]) => id);
    sentinelsLib.writeFableSessions(HEADROOM_DIR, fable);
  } catch (e) {
    log('headroom: could not write the sentinels', e.message);
  }
  void settings;
}


// ---- auto-resume ------------------------------------------------------------
//
// Four things happen here, in this order, on every headroom tick and on the
// 10-second poll a stalled host adds:
//
//   1. NOTICE.   A session whose last transcript record is the 429 is stalled.
//                Recorded on the session's headroom row, announced once.
//   2. WAIT.     If Claude Code's own in-process wait is armed for that session
//                (interactive, not restored since the stall, reset inside 24 h)
//                appd does nothing for NATIVE_GRACE_MS. Two continuations would
//                run the task twice.
//   3. RESUME.   Otherwise: the resume phrase through the send queue for a live
//                session, a re-run for a headless chat or Round, a re-queue for
//                a run that was executing on another machine.
//   4. SAY SO.   One notification listing what came back, and how.
//
// The rules are all in lib/resume.js. What is here is the I/O and the one thing
// a pure function cannot decide: whether it is still true NOW.

/** What the owner calls each window in a notification. */
function windowWords(w) {
  if (w === 'session') return '5-hour';
  if (w === 'weekly_fable') return 'weekly Fable';
  if (w === 'weekly_all') return 'weekly';
  return 'usage';
}

/** A reset time as a person reads it, or 'soon' when we never learned one. */
function clockOf(ms) {
  if (!ms) return 'soon';
  try { return new Date(Number(ms)).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }); }
  catch { return 'soon'; }
}

/**
 * Notice a stall, or notice one has cleared.
 *
 * ⚠ THE RECORD IS THE EVIDENCE, NOT THE PERCENTAGE. A session at 99% is still
 * working and a session at 100% whose last record is a human message was stopped
 * by a person — so this keys off the 429 record and nothing else (design §4).
 *
 * @returns the transcript tail it read, so the caller does not read it twice.
 */
async function noteStall(rec, s, settings, activeWindows, now) {
  const file = transcriptPath(s.name);
  const records = tailRecords(file);
  if (!records.length) return records;
  const found = resumeLib.stallOf(records, activeWindows, { now });
  if (!found) {
    // ⚠ NOT NECESSARILY CLEARED. A native continuation, a person answering, and
    // appd's own phrase all land AFTER the 429 and all make it "not the last
    // record" — and only the resume pass can tell them apart. Clearing here
    // would delete the evidence in the same tick it arrived, so `how:'native'`
    // would never be recorded and the resumed notification would never name the
    // sessions that came back on their own.
    //
    // So: cleared once the stall is resolved, or once the 429 has scrolled out
    // of the tail entirely (nothing left to classify). Kept otherwise.
    const stillInTail = resumeLib.recordsAfterStall(records).length > 0;
    if (rec.stall && rec.stall.at && (rec.stall.resumedAt || !stillInTail)) rec.stall = blankStall();
    return records;
  }
  if (rec.stall && rec.stall.at === found.at) {
    // The same stall. Only ever UPGRADE what we know about it: a resetsAt that
    // arrived from the endpoint after a text-derived guess is better evidence,
    // and the reverse never is.
    if (found.resetsAtSource === 'endpoint' && rec.stall.resetsAtSource !== 'endpoint' && found.resetsAt) {
      rec.stall.resetsAt = found.resetsAt;
      rec.stall.resetsAtSource = 'endpoint';
    }
    return records;
  }
  const entry = nativeRegistryEntry(s.claudeSessionId);
  const reg = loadRegistry()[s.name] || null;
  const armed = resumeLib.nativeArmed({
    registryEntry: entry,
    restoredAt: reg && reg.restoredAt ? reg.restoredAt * 1000 : null,
    stallAt: found.at,
    resetsAt: found.resetsAt,
    now,
  });
  rec.stall = { ...blankStall(), ...found, nativeArmed: armed };
  log(`headroom: ${s.name} stalled on the ${found.window || 'usage'} limit`
    + `${found.resetsAt ? `, resets ${new Date(found.resetsAt).toISOString()}` : ''}`
    + ` (native wait ${armed ? 'armed' : 'not armed'})`);
  // ONE notification per stall, not per tick. The tick runs every minute while
  // anything is live and the stall persists for as long as the window takes to
  // reset — up to a week for a Fable weekly — so a per-tick notification would
  // be ten thousand buzzes for one event.
  rec.stall.notifiedAt = now;
  const text = `${s.name} hit the ${windowWords(found.window)} limit · resets ${clockOf(found.resetsAt)}`;
  const push = await deliverPush({
    kind: 'headroom_limit',
    title: 'Usage limit reached',
    text,
    subject: s.name,
    payload: {
      session: s.name,
      window: found.window || '',
      resetsAt: found.resetsAt ? new Date(found.resetsAt).toISOString() : '',
    },
  });
  if (!push.sent) await deliverTelegram(`\u{1F6D1} Usage limit reached\n${text}`);
  return records;
}

/**
 * Act on one stalled SESSION.
 *
 * Returns a line for the notification (`"jtyper (native)"`) or null when there
 * is nothing to say — which is the usual answer, because the usual answer is
 * "the window has not reset yet".
 */
async function resumeSession(rec, s, settings, ctx) {
  const { now, activeWindows, resetWindows } = ctx;
  const stall = rec.stall;
  const records = tailRecords(transcriptPath(s.name));
  const after = resumeLib.recordsAfterStall(records);

  // Did it come back WITHOUT us? Checked before eligibility, because anything
  // that landed after the 429 — a native continuation included — would otherwise
  // read as "the session moved on", which is true and the wrong reason.
  //
  // ⚠ THE INJECTED SENTENCE IS ITSELF A `user` RECORD, so the native test has to
  // come first. Judged the other way round, every native resume would be filed
  // as "a person answered it" and the notification would never say a session
  // came back on its own.
  if (after.length) {
    const nativeLine = resumeLib.nativeResumed(after, null);
    const human = after.some((r) => resumeLib.isHumanRecord(r));
    if (nativeLine || human) {
      stall.resumedAt = now;
      stall.how = nativeLine ? 'native' : 'human';
      stall.why = nativeLine ? 'Claude Code continued it itself' : 'a person answered it';
      log(`headroom: ${s.name} resumed by ${stall.how}`);
      return nativeLine ? `${s.name} (native)` : null;
    }
  }

  // Already in the queue. It has NOT happened — re-queueing every pass would
  // stack six copies of the phrase behind one long turn — and its settle is what
  // decides. Age-bounded only for the daemon-restart case (see
  // RESUME_QUEUE_MAX_MS).
  if (stall.queuedAt && now - Number(stall.queuedAt) < RESUME_QUEUE_MAX_MS) {
    stall.why = 'the resume is queued, waiting for a turn boundary';
    return null;
  }
  if (stall.queuedAt) stall.queuedAt = null;

  // A stall with NO reset time can never become `due`, so without this it waits
  // for a `detectResets` event that may never come — for the life of the daemon,
  // holding the ten-second poll open and, for a Round, holding its report unfiled.
  if (stall.resetsAt == null) {
    const v = resumeLib.unclockedVerdict({ stall, activeWindows, now });
    if (v.action === 'adopt') {
      stall.resetsAt = v.resetsAt;
      stall.resetsAtSource = 'windows';
      log(`headroom: ${s.name} had no reset time; taking ${new Date(v.resetsAt).toISOString()} from the ${stall.window} window`);
    } else if (v.action === 'give_up') {
      stall.gaveUpAt = now;
      stall.why = v.why;
      log(`headroom: giving up on ${s.name}: ${v.why}`);
      // ONCE. The record is what carries the reason from here on.
      if (!stall.gaveUpNotifiedAt) {
        stall.gaveUpNotifiedAt = now;
        const text = `${s.name} hit a usage limit that named no reset time, and none appeared. `
          + 'It is waiting for you — auto-resume has stopped trying.';
        const push = await deliverPush({
          kind: 'headroom_limit',
          title: 'Auto-resume gave up',
          text,
          subject: s.name,
          payload: { session: s.name, window: stall.window || '' },
        });
        if (!push.sent) await deliverTelegram(`\u{1F6D1} Auto-resume gave up\n${text}`);
      }
      return null;
    } else {
      stall.why = v.why;
    }
  }

  const meta = loadSessionMeta(s.claudeSessionId);
  const w = stall.window;
  const percent = w && activeWindows && activeWindows[w] ? activeWindows[w].percent : null;
  const verdict = resumeLib.eligible({
    settings, meta, stall, records, resetSeen: resetSeenFor(resetWindows, stall), percent, now,
  });
  if (!verdict.ok) { stall.why = verdict.why; return null; }

  const cancelled = resumeLib.nativeCancelled(after.length ? after : records);
  const plan = resumeLib.resumePlan({
    kind: 'session', armed: !!stall.nativeArmed, stall, cancelled, now, graceMs: NATIVE_GRACE_MS,
  });
  stall.why = plan.why;
  if (plan.action !== 'type_phrase') return null;

  // Through the QUEUE, at a turn boundary, and the queue drops it by itself if
  // the owner typed while it waited (typing.dropReason 'human'). A phrase typed
  // mid-turn is absorbed into that turn and silently rewrites what it was told
  // to do — the one failure this whole path must not cause.
  //
  // ⚠ THE OUTCOME IS THE SETTLE, NOT THIS CALL. enqueueSend returns after one
  // pump pass: a session behind a modal answers `delivered:false, dropped:null`,
  // and ten minutes later the pump drops the entry for 'timeout' or 'human'.
  // Marking the stall resumed here meant `applyResumes` skipped the session
  // forever and `noteStall` eventually cleared the record — the one session appd
  // most needed to speak to was the one it silently gave up on.
  const out = await enqueueSend(s.name, settings.resumePhrase, {
    automated: true, origin: 'headroom', kind: 'resume',
    onSettle: (v) => settleResume(rec, s.name, v),
  });
  if (out.dropped) {
    // settleResume has already written the reason and left it eligible.
    return null;
  }
  if (!out.delivered) {
    stall.queuedAt = now;
    stall.why = 'the resume is queued, waiting for a turn boundary';
    log(`headroom: resume for ${s.name} queued for a turn boundary`);
    return `${s.name} (queued)`;
  }
  // Delivered inside that first pass — settleResume ran synchronously and has
  // already stamped `resumedAt`, `how` and `attempts`.
  return `${s.name} (typed)`;
}

/**
 * How long a queued resume may sit before the record stops believing in it.
 *
 * The pump drops an entry at QUEUE_MAX_WAIT_MS, so in a living daemon the settle
 * always arrives. This is the DAEMON-RESTART case: `queuedAt` is persisted, the
 * queue is not, so without an age bound a restart in the wrong second would
 * leave the stall waiting on a send that no longer exists anywhere.
 */
const RESUME_QUEUE_MAX_MS = typing.QUEUE_MAX_WAIT_MS + 60_000;

/**
 * What became of a resume that went through the queue.
 *
 * The queue entry's own settle: delivered, dropped, or failed at the pane. This
 * is the only place that knows, because `resumeSession` returned as soon as the
 * send was ACCEPTED.
 */
function settleResume(rec, name, v) {
  const stall = rec && rec.stall;
  if (!stall || !stall.at) return;
  stall.queuedAt = null;
  if (v && v.delivered) {
    stall.resumedAt = Date.now();
    stall.how = 'appd';
    stall.attempts = (Number(stall.attempts) || 0) + 1;
    stall.why = 'the window reset and appd typed the resume phrase';
    log(`headroom: resumed ${name} (attempt ${stall.attempts})`);
    return;
  }
  // Nothing was typed, so nothing was spent: the session is still holding the
  // 429 and the next eligible pass tries again. An attempt is only consumed by a
  // phrase that actually reached the pane.
  stall.why = v && v.dropped
    ? `the resume was dropped (${v.dropped})`
    : `the resume could not be delivered${v && v.result && v.result.message ? ` (${v.result.message})` : ''}`;
  log(`headroom: resume for ${name} did not land: ${stall.why}`);
}

/**
 * Act on every stalled session and every stalled chat, and say what came back.
 *
 * One notification for the lot. Six sessions resuming at 3 a.m. is one event —
 * the window reset — and six notifications for it is how a channel is taught to
 * be ignored.
 */
async function applyResumes(ctx) {
  const { state, settings, now, sessions } = ctx;
  const resumed = [];
  for (const s of sessions) {
    const rec = state.sessions[s.claudeSessionId];
    if (!rec || !rec.stall || !rec.stall.at || rec.stall.resumedAt || rec.stall.gaveUpAt) continue;
    try {
      const line = await resumeSession(rec, s, settings, ctx);
      if (line) resumed.push(line);
    } catch (e) {
      log(`headroom: resuming ${s.name} failed: ${e.message}`);
    }
  }
  for (const line of await resumeStalledChats(settings, ctx)) resumed.push(line);
  if (!resumed.length) return;
  // MILLISECONDS, like every other epoch in this payload (lib/headroom's banner).
  state.arbiter.lastResumeAt = now;
  const text = `Usage limit reset · resumed: ${resumed.join(', ')}`;
  // The lines read "jtyper (typed)"; the SUBJECT is the bare name, so a
  // notification about one session can be opened at that session. Several at
  // once is one event and keeps the host-wide subject.
  const first = String(resumed[0] || '').split(' ')[0];
  const push = await deliverPush({
    kind: 'headroom_resumed',
    title: 'Usage limit reset',
    text,
    subject: resumed.length === 1 && first ? first : 'headroom',
    payload: { sessions: resumed.map((l) => String(l).split(' ')[0]).join(',') },
  });
  if (!push.sent) await deliverTelegram(`\u{1F504} Usage limit reset\n${text}`);
  log(`headroom: ${text}`);
}

/**
 * The headless half: chats and Round runs whose `claude -p` died on a 429.
 *
 * These have no native wait at all — the feature is an interactive main-thread
 * one (native-rl §1) — so there is nothing to defer to and no grace to serve.
 * The moment the window is back, the turn is re-run.
 */
async function resumeStalledChats(settings, ctx) {
  const { now, activeWindows, resetWindows } = ctx;
  const out = [];
  let pending = false;
  let ids = [];
  try { ids = fs.readdirSync(CHATS_DIR); } catch { chatStallsPending = false; return out; }
  for (const id of ids) {
    const meta = loadMeta(id);
    if (!meta || !meta.stall || !meta.stall.at || meta.stall.resumedAt || meta.stall.gaveUpAt) continue;
    pending = true;
    if (activeRuns.has(id)) continue;
    // The same backstop the sessions get. It matters MORE here: `noteRunStall`
    // returning true is what holds a Round's report open, so a stall that can
    // never become due is a scheduled job that never reports at all.
    if (meta.stall.resetsAt == null) {
      const v = resumeLib.unclockedVerdict({ stall: meta.stall, activeWindows, now });
      if (v.action === 'adopt') {
        updateMeta(id, (m) => {
          if (m.stall) { m.stall.resetsAt = v.resetsAt; m.stall.resetsAtSource = 'windows'; }
        });
        meta.stall.resetsAt = v.resetsAt;
      } else if (v.action === 'give_up') {
        updateMeta(id, (m) => { if (m.stall) { m.stall.gaveUpAt = now; m.stall.why = v.why; } });
        pending = false;
        giveUpOnStalledRun(id, v.why);
        continue;
      } else if (meta.stall.why !== v.why) {
        updateMeta(id, (m) => { if (m.stall) m.stall.why = v.why; });
      }
    }
    const w = meta.stall.window;
    const percent = w && activeWindows && activeWindows[w] ? activeWindows[w].percent : null;
    // No per-chat toggle in 3.0 (design §4): chats and Rounds obey the global.
    const verdict = resumeLib.eligible({
      settings, meta: null, stall: meta.stall, records: null,
      resetSeen: resetSeenFor(resetWindows, meta.stall), percent, now,
    });
    if (!verdict.ok) {
      if (meta.stall.why !== verdict.why) updateMeta(id, (m) => { if (m.stall) m.stall.why = verdict.why; });
      continue;
    }
    const r = rerunStalledRun(id);
    if (r.ok) { out.push(`${r.title} (re-run)`); pending = false; }
    else log(`headroom: could not re-run ${id}: ${r.error}`);
  }
  // Recomputed each pass rather than latched: this flag is the ONLY thing that
  // keeps the 10-second poll alive for a stalled chat on an otherwise green
  // host, and a latched one would poll for the life of the daemon.
  chatStallsPending = pending;
  return out;
}

/**
 * Stop waiting for a reset that is never going to be identifiable, and let the
 * Round say so.
 *
 * ⚠ A HELD ROUND IS THE REAL COST. `noteRunStall` returning true stops
 * `finishRoundRun` being called at all — deliberately, so one scheduled job
 * files one run — so a stall that can never become due is a job that reports
 * NOTHING, for ever, with `currentChatId` still pointing at it. Filed as
 * `attention` rather than `action`: nothing is wrong with the world, something
 * is wrong with the arrangement.
 */
function giveUpOnStalledRun(chatId, why) {
  const meta = loadMeta(chatId);
  if (!meta) return;
  const ts = Math.floor(Date.now() / 1000);
  log(`chat ${chatId}: giving up on the usage-limit re-run (${why})`);
  try {
    appendMsg(chatId, { type: 'system', text: `the usage-limit re-run was abandoned: ${why}`, ts });
  } catch (e) { log(`chat ${chatId}: could not note the give-up: ${e.message}`); }
  if (!meta.roundId) return;
  try { finishRoundRun(loadMeta(chatId) || meta, `did not finish: ${why}`, { status: 'attention' }); }
  catch (e) { log(`round run ${chatId} could not be recorded: ${e.message}`); }
}

/**
 * Re-run the turn a usage limit killed, on the same conversation.
 *
 * `startRun` already does everything this needs — it appends `--resume
 * <claudeSessionId>` whenever the meta carries one and writes the user text to
 * the child's stdin — so the re-run is the ORIGINAL call with the original text,
 * not a second code path that would drift from it. The only additions are the
 * marks: `resumedAfterLimit` on the chat (which is also what stops a daemon
 * restart re-running it a second time, inherited gotcha 3) and a `system` line
 * in the transcript so the conversation says why it starts again.
 */
function rerunStalledRun(chatId) {
  const meta = loadMeta(chatId);
  if (!meta) return { ok: false, error: 'no such chat' };
  const stall = meta.stall;
  if (!stall || !stall.at) return { ok: false, error: 'that run did not stall on a limit' };
  if (stall.resumedAt) return { ok: false, error: 'already re-run' };
  const text = stall.userText;
  if (!text) return { ok: false, error: 'the stalled turn left no text to re-send' };
  const ts = Math.floor(Date.now() / 1000);
  // Written BEFORE the run starts. If the spawn throws, a chat marked resumed is
  // a chat that will not be resumed again in a loop; the opposite mistake pays
  // for the same long answer twice.
  updateMeta(chatId, (m) => {
    m.resumedAfterLimit = true;
    if (m.stall) {
      m.stall.resumedAt = ts * 1000;
      m.stall.how = 'rerun';
      m.stall.attempts = (Number(m.stall.attempts) || 0) + 1;
    }
    // A Round run that was held open for this is live again.
    if (m.roundId) m.sealed = false;
  });
  appendMsg(chatId, { type: 'system', text: 'resumed after the limit reset', ts });
  const fresh = loadMeta(chatId);
  const started = startRunAnywhere(fresh, text);
  if (started && started.error) {
    appendMsg(chatId, { type: 'error', text: `could not resume: ${started.error}`, ts });
    return { ok: false, error: started.error };
  }
  const title = fresh.title || (fresh.roundId ? 'a scheduled run' : `chat ${chatId.slice(0, 8)}`);
  log(`chat ${chatId} re-run after the limit reset (resume=${fresh.claudeSessionId || 'new'})`);
  return { ok: true, title };
}

/**
 * Record a run that died on a usage limit, so the reset can bring it back.
 *
 * Called from settleRun, which is the ONE place a run ends wherever it ran. The
 * evidence is the failure text: a `-p` run reports the limit through its result
 * event (`is_error` with the apology in `result`, kept as `errorText`) rather
 * than as a transcript record, so `parseLimitError` reads the window off that.
 *
 * @returns true when a resume is pending — which for a Round run also means its
 *   report must NOT be filed yet: the run is not over, it is waiting.
 */
function noteRunStall(chatId, failureText) {
  const parsed = resumeLib.stallOf(
    { type: 'assistant', isApiErrorMessage: true, apiErrorStatus: 429, message: { content: failureText || '' } },
    activeWindowsNow(),
    { now: Date.now() },
  );
  if (!parsed || !parsed.window) return false;
  const settings = loadHeadroomSettings();
  if (settings.autoResume === false) return false;
  // The text of the turn that died, because it is the only copy: the run is over
  // and the sender's composer was cleared when it was accepted.
  const msgs = loadMsgs(chatId);
  let userText = null;
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i] && msgs[i].type === 'user' && msgs[i].text) { userText = msgs[i].text; break; }
  }
  if (!userText) return false;
  const prior = loadMeta(chatId);
  const attempts = (prior && prior.stall && Number(prior.stall.attempts)) || 0;
  if (attempts >= resumeLib.MAX_ATTEMPTS) return false;
  updateMeta(chatId, (m) => {
    m.stall = { ...blankStall(), ...parsed, attempts, userText };
  });
  chatStallsPending = true;
  log(`chat ${chatId} stalled on the ${parsed.window} limit; a re-run is pending the reset`);
  return true;
}

/**
 * The model a NEW headless run should be launched on, or null for "as configured".
 *
 * Only ever a downgrade, and only while the Fable weekly window is red: chats
 * and Rounds are unattended, and an unattended run that meets a consent dialog
 * it cannot answer does not get a slower answer, it gets no answer at all.
 */
function fableRedDowngrade() {
  try {
    const settings = loadHeadroomSettings();
    const w = activeWindowsNow().weekly_fable;
    if (!w || w.percent == null) return null;
    const mode = headroomLib.classify(w.percent, settings);
    if (mode !== 'red' && mode !== 'exhausted') return null;
    return headroomLib.nextDown('fable', settings.ladder) || null;
  } catch { return null; }
}

/**
 * The chat row's "ran on" caption, or null when the run got what it asked for.
 *
 * The comparison is by FAMILY, not by id: `claude-opus-5` and the 1M-context
 * variant are the same answer to "did this run get moved", and an id-level
 * comparison would caption every run whose model string was spelled differently
 * from the settings key.
 */
function ranOnLabel(askedModel, sawModel) {
  if (!sawModel) return null;
  const got = headroomLib.familyOf(sawModel);
  if (!got) return null;                       // '<synthetic>' — the CLI wrote it, not a model
  let want = null;
  try { want = headroomLib.familyOf(askedModel || loadHeadroomSettings().defaultModel); } catch { /* defaults */ }
  if (!want || want === got) return null;
  // Named for the reason, because the reason is the whole point of the caption:
  // an owner seeing "opus" on a chat they expected Fable to answer needs to know
  // it was the cap and not a setting they mis-typed.
  return want === 'fable' ? `${got} (Fable limit)` : got;
}

/** The active account's windows as the last tick read them. */
function activeWindowsNow() {
  const st = hstate();
  const live = Object.values(st.accounts || {}).find((a) => a && a.live) || null;
  return (live && live.windows) || {};
}

// ---- the Fable consent dialog ----------------------------------------------
//
// Measured (native-rl §7): when Fable passes the point where further use bills
// usage credits, an interactive session gets a dialog — and an UNANSWERED one
// loses the turn ("nothing was sent", `{reason:"model_error"}`). `detectPrompt`
// surfaces it as an ordinary prompt card carrying `recommended`, so both clients
// draw it with no new surface and a person can answer it from a lock screen.
//
// This is the backstop for when nobody does. After CONSENT_GRACE_MS on a session
// whose auto-resume is on, appd presses the recommended row itself: a
// session-only model change is a smaller loss than a destroyed turn, and it
// writes nothing to the shared settings.json.
const consentSeen = new Map();   // session name -> first seen at (ms)

async function consentWatch(state, settings, sessions, now) {
  const live = new Set();
  for (const s of sessions) {
    // Only where the dialog can BE: a session that is asking something. The
    // Fable window being red is NOT used as a second gate, deliberately — the
    // reading can be stale or missing exactly when the cap has just been hit,
    // and a backstop that switches itself off in the one situation it exists
    // for is worse than one pane capture per attention session per pass.
    if (s.state !== 'attention') continue;
    live.add(s.name);
    let screen = null;
    try { screen = await captureScreen(s.name); } catch { /* pane gone */ }
    const prompt = screen ? promptFor(s.name, screen.lines).prompt : null;
    if (!prompt || !prompt.recommended) { consentSeen.delete(s.name); continue; }
    const since = consentSeen.get(s.name) || now;
    consentSeen.set(s.name, since);
    if (now - since < CONSENT_GRACE_MS) continue;
    const meta = loadSessionMeta(s.claudeSessionId);
    const on = meta && typeof meta.autoResume === 'boolean' ? meta.autoResume : settings.autoResume !== false;
    if (!on) continue;
    const row = prompt.options.find((o) => o.number === prompt.recommended);
    // ⚠ RE-READ IMMEDIATELY BEFORE THE KEY, and require the SAME dialog.
    //
    // consentRecommended's own contract says the number is validated against a
    // freshly-read fingerprint, the way /answer does it for a person's tap. This
    // path did not: it pressed a digit against a capture taken a grace period
    // ago. If the owner answered in that window — or the dialog simply moved —
    // a bare digit and an Enter are submitted as a chat message into a working
    // conversation. The row is matched by LABEL as well as by number, because
    // the number alone is what a renumbered list keeps.
    // A LEAN read: the pane, straight, with none of captureScreen's geometry
    // round trip — the only question being asked is "is the same dialog still
    // there", and this has to sit as close to the keystroke as it can.
    const again = await run('tmux', ['capture-pane', '-p', '-t', `=${s.name}:`]);
    const fresh = again.err
      ? null
      : promptFor(s.name, again.stdout.replace(/\n$/, '').split('\n')).prompt;
    if (!row || !sameConsent(prompt, fresh)) {
      log(`headroom: the consent dialog on ${s.name} moved between the read and the keypress — nothing typed`);
      consentSeen.delete(s.name);
      continue;
    }
    const typed = await run('tmux', ['send-keys', '-t', `=${s.name}:`, '-l', '--', String(prompt.recommended)]);
    if (typed.err) { log(`headroom: consent answer failed on ${s.name}: ${typed.stderr.trim()}`); continue; }
    await run('tmux', ['send-keys', '-t', `=${s.name}:`, 'Enter']);
    consentSeen.delete(s.name);
    const rec = s.claudeSessionId ? sessionRecord(state, s.claudeSessionId, s.name) : null;
    const to = headroomLib.familyOf(row ? row.label : null) || (row ? row.label : null);
    if (rec) rec.nativeSwitch = { seenAt: now, to, how: 'appd-consent' };
    log(`headroom: answered the Fable consent dialog on ${s.name} with "${(row && row.label) || prompt.recommended}"`);
    const text = `${s.name} was asked to keep going on Fable using usage credits and nobody answered — `
      + `huginn switched it for this session instead.`;
    const push = await deliverPush({
      kind: 'headroom_downgraded',
      title: `Moved ${s.name} off Fable`,
      text,
      subject: s.name,
      options: ['Undo', 'OK'],
      payload: { session: s.name, to: to || '', from: 'fable' },
    });
    if (!push.sent) await deliverTelegram(`\u{1F4C9} Moved ${s.name} off Fable\n${text}`);
  }
  for (const name of [...consentSeen.keys()]) if (!live.has(name)) consentSeen.delete(name);
}

// ---- the stalled-host poll --------------------------------------------------

let resumeTimer = null;
let resumeBusy = false;
/**
 * Is a headless run waiting for a reset?
 *
 * In memory, and recomputed on every pass that reads the chats. A stalled CHAT
 * does not make the host red — its window may be perfectly green by the time
 * anyone looks — so without this the poll below would switch itself off and the
 * re-run would wait for the five-minute idle tick. Reading every chat meta on a
 * ten-second timer to find that out is the thing the flag exists to avoid.
 */
let chatStallsPending = false;
/**
 * Ten seconds, but ONLY while something is stalled or the account is out of
 * room. The headroom tick's own cadence is a minute at its fastest and five
 * minutes at its idlest, and a host waiting for a reset is idle by definition —
 * so the one moment this subsystem exists for is the one moment the tick is
 * slowest. This runs alongside it and stops as soon as there is nothing to watch.
 */
async function resumeTick() {
  // The minute tick owns the same state and the same queue; it reschedules
  // itself in a `finally`, so skipping a poll here costs ten seconds and never
  // a stopped clock.
  if (resumeBusy || headroomBusy) return;
  const state = hstate();
  const mode = state.mode || 'ok';
  // Arms on "a reset is NEAR", not on "anything is stalled" — the rule, and the
  // six-day loop it replaces, are lib/resume's.
  const arm = resumeLib.pollShouldArm({
    stalls: Object.values(state.sessions || {}).map((r) => r && r.stall).filter(Boolean),
    chatStallsPending,
    mode,
    now: Date.now(),
  });
  if (!arm) return;
  resumeBusy = true;
  try {
    const settings = loadHeadroomSettings();
    const now = Date.now();
    const sessions = (await listSessions()) || [];
    const activeWindows = activeWindowsNow();
    const resetWindows = recentResetWindows(state, now);
    const watched = [];
    for (const s of sessions) {
      if (!s.claudeSessionId) continue;
      const rec = sessionRecord(state, s.claudeSessionId, s.name);
      const records = await noteStall(rec, s, settings, activeWindows, now);
      watched.push({ s, rec, records });
    }
    await applyResumes({
      state, settings, now, activeWindows, resetWindows,
      sessions: watched.map((x) => x.s),
    });
    await consentWatch(state, settings, sessions, now);
    saveHeadroomState(state);
  } catch (e) {
    log(`headroom: resume poll failed: ${e.message}`);
  } finally {
    resumeBusy = false;
  }
}
resumeTimer = setInterval(() => { resumeTick().catch(() => { }); }, RESUME_POLL_MS);
if (resumeTimer.unref) resumeTimer.unref();

/**
 * Which windows have reset recently enough to count.
 *
 * `detectResets` fires once, on the tick that sees the drop; the resume that
 * follows may need two or three passes (the queue waits for a turn boundary),
 * so the event is remembered for an hour rather than consumed by whoever reads
 * it first.
 */
const RESET_MEMORY_MS = resumeLib.RESET_MEMORY_MS;
/** The slug of the login that is live right now, or null. */
function activeSlugOf(state) {
  const accts = (state && state.accounts) || {};
  return Object.keys(accts).find((k) => accts[k] && accts[k].live) || null;
}
/**
 * The reset log, narrowed to what can prove anything about a stall on the
 * CURRENTLY ACTIVE login. The rule itself is lib/resume's, with the reason.
 */
function recentResetWindows(state, now, activeSlug = activeSlugOf(state)) {
  return resumeLib.recentResetWindows(state.resets, { now, activeSlug });
}

const resetSeenFor = resumeLib.resetSeenFor;

// ---- what the rest of the daemon asks headroom -----------------------------

/** The session's family RIGHT NOW, for the send queue's `kind:'model'` drop rule. */
function familyOfSession(name) {
  const st = hstate();
  for (const rec of Object.values(st.sessions)) {
    if (rec && rec.name === name) return rec.family || null;
  }
  return null;
}
setFamilyProbe(familyOfSession);

/** The per-row block on `GET /v1/sessions`. Cheap: memory only, no transcript walk. */
function headroomForSession(claudeSessionId) {
  if (!claudeSessionId) return null;
  const st = hstate();
  const rec = st.sessions[claudeSessionId];
  const meta = loadSessionMeta(claudeSessionId);
  const settings = loadHeadroomSettings();
  const autoResume = meta && typeof meta.autoResume === 'boolean' ? meta.autoResume : settings.autoResume;
  return {
    family: (rec && rec.family) || null,
    ladder: (rec && rec.ladder && rec.ladder.to) || null,
    autoResume,
    // Stall detection and auto-resume are the next component; the field exists
    // now so the clients can be built against the final shape.
    stalled: !!(rec && rec.stall && rec.stall.at),
  };
}

/** The facts the watch digest hashes. In-memory only — this runs on every poll. */
function headroomFacts() {
  const st = hstate();
  const armed = Object.entries(st.sentinels || {}).filter(([, v]) => !!v).map(([k]) => k);
  const rows = Object.values(st.sessions || {}).filter((r) => r && r.name);
  const stalled = rows.filter((r) => r.stall && r.stall.at).map((r) => r.name);
  // The two MAPS the desktop's notification rules need. `stalled` answers "is
  // anything stuck"; `LimitHit(session, resetsAt)` has to say WHEN it comes back
  // and `Downgraded(session, to)` has to say WHAT it moved to, and neither fact
  // can be recovered from a list of names. ISO strings, not epochs: the client
  // renders them, and a client that has to guess the unit renders 1970.
  const stalls = {};
  for (const r of rows) {
    if (!r.stall || !r.stall.at) continue;
    stalls[r.name] = r.stall.resetsAt ? new Date(Number(r.stall.resetsAt)).toISOString() : null;
  }
  const laddered = {};
  for (const r of rows) if (r.ladder && r.ladder.to) laddered[r.name] = r.ladder.to;
  return {
    mode: st.mode || 'ok',
    stalled,
    stalls,
    laddered,
    lastResumeAt: Number(st.arbiter.lastResumeAt) || 0,
    lastLadderAt: st.arbiter.lastLadderAt || 0,
    sentinels: armed,
  };
}

/** The one-line summary on `GET /v1/status`. */
function headroomStatus() {
  const st = hstate();
  const settings = loadHeadroomSettings();
  const active = Object.values(st.accounts || {}).find((a) => a && a.live) || null;
  const worst = active ? headroomLib.worstWindow(active.windows, settings) : null;
  const now = Date.now();
  let held = [];
  try { held = sentinelsLib.listHeld(HEADROOM_DIR, now); } catch { /* no dir yet */ }
  // Is a 5-hour window running at all? ONE field on the wire decides it: while
  // no window is running the endpoint reports the session row with
  // `resets_at: null`, and `agedLimits` copies that through verbatim rather than
  // inventing it. Everything the keep-awake line says rests on this tell.
  const session = (active && active.windows && active.windows.session) || null;
  const windowResetsAt = (session && session.resetsAt) || null;
  return {
    worstPercent: worst ? worst.percent : null,
    worstLabel: worst ? worst.label : null,
    nextResetAt: worst ? worst.resetsAt : null,
    mode: st.mode || 'ok',
    sentinels: Object.entries(st.sentinels || {}).filter(([, v]) => !!v).map(([k]) => k),
    paused: held.length,
    windowRunning: windowResetsAt != null,
    windowResetsAt,
    keepAwake: keepAwakeLib.view(st.keepAwake, settings, now),
  };
}

/** The whole picture: `GET /v1/headroom`. */
function headroomPayload() {
  const st = hstate();
  const settings = loadHeadroomSettings();
  const now = Date.now();
  const activeSlug = Object.keys(st.accounts || {}).find((k) => st.accounts[k] && st.accounts[k].live) || null;
  const active = activeSlug ? st.accounts[activeSlug] : null;
  const worst = active ? headroomLib.worstWindow(active.windows, settings) : null;
  const sessions = Object.entries(st.sessions || {}).map(([id, r]) => {
    const meta = loadSessionMeta(id);
    return {
      name: r.name,
      claudeSessionId: id,
      family: r.family || null,
      ladder: r.ladder || null,
      autoResume: meta && typeof meta.autoResume === 'boolean' ? meta.autoResume : settings.autoResume,
      stalled: !!(r.stall && r.stall.at),
      // The whole record, not just the flag: the phone's card says which window
      // and when it comes back, and `why` is the only place a refusal to resume
      // ("auto-resume is off for this session") is ever written down.
      stall: r.stall && r.stall.at ? r.stall : null,
      headsUpAt: r.headsUpAt ?? null,
      nativeSwitch: r.nativeSwitch || { seenAt: null, to: null },
      // The other half of the native story: once the owner has answered the
      // "back to Fable?" question, THIS is the field that says the arbiter must
      // leave the session alone — and the clients have no other way to see it.
      humanSetModelAt: r.humanSetModelAt ?? null,
    };
  });
  let sentinelState = st.sentinels || { STOP: null, 'STOP-FABLE': null };
  let held = [];
  try {
    sentinelState = sentinelsLib.state(HEADROOM_DIR);
    held = sentinelsLib.listHeld(HEADROOM_DIR, now);
  } catch { /* the directory appears on the first armed sentinel */ }
  return {
    mode: st.mode || 'ok',
    worst: worst
      ? {
        slug: activeSlug, email: active.email ?? null, window: worst.window,
        percent: worst.percent, label: worst.label, resetsAt: worst.resetsAt,
      }
      : null,
    accounts: st.accounts || {},
    sessions,
    sentinels: sentinelState,
    held,
    resets: (st.resets || []).slice(-20),
    arbiter: st.arbiter,
    // Beside the arbiter because it is the same KIND of fact: bookkeeping about
    // what the daemon did on its own. `nextEligibleAt` is derived rather than
    // stored — it is `lastAt` plus a window, and storing it would be a second
    // copy of the guard to get out of step with the first.
    keepAwake: {
      ...keepAwakeLib.view(st.keepAwake, settings, now),
      nextEligibleAt: (st.keepAwake && st.keepAwake.lastAt)
        ? st.keepAwake.lastAt + keepAwakeLib.FIVE_HOURS_MS
        : 0,
    },
    settings,
    // ⚠ MILLISECONDS. It used to be the one seconds field in a payload whose
    // `ladder.at`, `headsUpAt`, `accounts[].readAt` and `arbiter.last*At` are
    // all ms — and a client treats this as the host clock, so getting it wrong
    // makes every relative time in the payload wrong by a factor of a thousand.
    serverTime: now,
  };
}

/**
 * `GET /v1/autoswitch`'s answer, rebuilt from headroom state.
 *
 * Kept for one release because the shipped clients still call it; the fields are
 * the same ones they read, sourced from the arbiter instead of from
 * autoswitch.json (which is now read-only history).
 */
function autoswitchAliasView() {
  const st = hstate();
  const settings = loadHeadroomSettings();
  // ⚠ SPREAD FIRST, THEN OVERRIDE `at`. Written the other way round the spread
  // WON, so this alias silently changed an existing 2.x field from seconds to
  // milliseconds — the one pre-existing field this compatibility shim touched.
  // `AutoswitchEvent.at` has been seconds since 2.x and is what the shipped
  // clients parse.
  const last = st.arbiter.lastAction && st.arbiter.lastAction.type === 'switch_account'
    ? { ...st.arbiter.lastAction, at: Math.floor((st.arbiter.lastAction.at || 0) / 1000) }
    : null;
  return {
    enabled: !!settings.accountSwitch.enabled,
    switches: st.arbiter.switches || 0,
    last,
    accounts: accounts.list().length,
    threshold: settings.accountSwitch.threshold,
    // Null while a switch is in hand, as before.
    idleBecause: st.arbiter.why ?? null,
  };
}

/**
 * The old tick, now a thin alias.
 *
 * `POST /v1/autoswitch` calls it to act immediately on being enabled, and the
 * name survives one release so nothing else has to change at the same time as
 * the arbiter landing.
 */
async function autoswitchTick() {
  return headroomTick();
}

// The first pass runs a few seconds after start rather than immediately: the
// session list, the state files and the account store all settle first, and a
// tick that runs before them sees an empty host and writes it down.
setTimeout(() => { headroomTick().catch(() => { }); }, 5_000).unref();


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
  const d = digest(sessions, chatStates(), headroomFacts());
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
    if (req.method === 'GET' && p === '/v1/ping') {
      // `via` — WHICH LISTENER ANSWERED. Clients pin several routes to this one
      // daemon now (tailnet, mesh, loopback), and two pins that resolve to the
      // same listener are one path wearing two names; this is how a client can
      // tell without being told. Additive: the Ping model's fields are all
      // nullable-with-defaults, so an older client ignores it.
      //
      // ⚠ PING IS UNAUTHENTICATED, so this echoes ONLY the address the caller
      // already dialled — it is the local end of their own socket. A LIST of the
      // daemon's other addresses would be a disclosure and belongs on
      // token-gated /v1/status, which already carries the hostname.
      const via = { addr: req.socket.localAddress || null, port: req.socket.localPort || null };
      return sendJson(res, 200, { ok: true, version: VERSION, host: os.hostname(), via });
    }
    if (req.method === 'GET' && p === '/v1/status') return sendJson(res, 200, await statusPayload());

    // --- the wording behind the four selection buttons, which /v1/status carries.
    //     No GET: the current values already ride the status poll every client
    //     runs, and a second way to read them is a second thing to keep in step.
    if (req.method === 'PATCH' && p === '/v1/quick-actions') {
      const body = JSON.parse(await readBody(req) || '{}');
      // One call decides everything — the stale-revision check, the per-field
      // rules and the new record — so there is no order in which the route can
      // write a half-validated file.
      const r = quickLib.merge(loadQuickActions(), body, Math.floor(Date.now() / 1000));
      if (!r.ok) return sendErr(res, r.status, r.error);
      if (r.changed) {
        saveQuickActions(r.record);
        log(`quick-actions: updated (rev ${r.record.rev})`);
      }
      // The WHOLE object, not just what changed: the client that sent one field
      // needs the other three and the new rev to keep editing without a poll.
      return sendJson(res, 200, quickLib.view(r.record));
    }

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

    // --- headroom: the whole usage picture, and the settings behind it
    if (req.method === 'GET' && p === '/v1/headroom') {
      return sendJson(res, 200, headroomPayload());
    }
    if (req.method === 'PATCH' && p === '/v1/headroom/settings') {
      const body = JSON.parse(await readBody(req) || '{}');
      // Validated BEFORE anything is written, and the rule that failed is the
      // message: "invalid settings" makes a slider that silently will not move.
      const v = headroomLib.validateSettings(body, loadHeadroomSettings());
      if (!v.ok) return sendErr(res, 400, v.error);
      saveHeadroomSettings(v.settings);
      log('headroom: settings updated');
      // Act on the new numbers now rather than at the next tick: a threshold
      // lowered past where the account already sits should do something.
      headroomTick().catch(() => { });
      return sendJson(res, 200, v.settings);
    }

    // --- automatic account switching — ALIASES onto settings.accountSwitch,
    //     kept for one release so the shipped clients keep working. Removed in
    //     3.1; the response shape is deliberately unchanged.
    if (req.method === 'GET' && p === '/v1/autoswitch') {
      return sendJson(res, 200, autoswitchAliasView());
    }
    if (req.method === 'POST' && p === '/v1/autoswitch') {
      const body = JSON.parse(await readBody(req) || '{}');
      const patch = {};
      if (typeof body.enabled === 'boolean') patch.enabled = body.enabled;
      // The default fires at 95%, on the reasoning that a limit resetting in
      // twenty minutes is not worth spending a fresh account on. That is a taste
      // question, not a fact, so it is tunable without a deploy.
      if (typeof body.threshold === 'number' && body.threshold >= 50 && body.threshold <= 100) {
        patch.threshold = Math.round(body.threshold);
      }
      const v = headroomLib.validateSettings({ accountSwitch: patch }, loadHeadroomSettings());
      if (!v.ok) return sendErr(res, 400, v.error);
      saveHeadroomSettings(v.settings);
      log(`autoswitch: ${v.settings.accountSwitch.enabled ? 'enabled' : 'disabled'} at ${v.settings.accountSwitch.threshold}%`);
      // An immediate look, so enabling it against an already-dry account acts
      // now rather than in five minutes.
      if (v.settings.accountSwitch.enabled) autoswitchTick().catch(() => { });
      return sendJson(res, 200, {
        enabled: v.settings.accountSwitch.enabled,
        threshold: v.settings.accountSwitch.threshold,
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
          const d = digest(sess ?? [], chatStates(), headroomFacts());
          // A failed observation must not be published as a change: the phone
          // would see every session disappear and act on it.
          if (sess !== null && d.hash !== last) {
            last = d.hash;
            // One read for both, so a frame can never pair a tally with an epoch
            // from a different read of the file.
            const pushSt = streamInstall ? loadPushState() : null;
            res.write(`event: state\ndata: ${JSON.stringify({
              ...d, changed: true, serverTime: Math.floor(Date.now() / 1000),
              // Same field the long poll returns. Without it the app decodes the
              // absent value as 0 and overwrites its real tally, which silently
              // disables push-deficit detection: the phone can no longer tell a
              // quiet night from a broken delivery path, so it never tightens
              // its fallback cadence no matter how many pushes go missing.
              pushesSent: streamInstall ? pushLib.sentTo(pushSt, streamInstall) : null,
              // Which run of counting that number belongs to. The phone rebaselines
              // its own tally when this changes, because a count that restarts
              // without saying so reads as a permanent "nothing is ever dropped".
              pushEpoch: streamInstall ? pushLib.epochOf(pushSt, streamInstall) : null,
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
      let d = digest(sess ?? [], chatStates(), headroomFacts());
      while (known && (sess === null || d.hash === known) && Date.now() < deadline && !req.destroyed) {
        await sleep(3000);
        sess = await listSessions();
        d = digest(sess ?? [], chatStates(), headroomFacts());
      }
      if (req.destroyed) return;
      const installId = String(req.headers['x-huginn-client'] || '').trim().slice(0, 64);
      const pushSt = installId ? loadPushState() : null;
      return sendJson(res, 200, {
        ...d,
        changed: !known || d.hash !== known,
        serverTime: Math.floor(Date.now() / 1000),
        // What this host thinks it has delivered to the caller. The phone compares
        // it against what it actually received, which is the only way it can tell a
        // quiet night from a broken delivery path — and that distinction is worth a
        // hundred and twenty device wake-ups a day.
        pushesSent: installId ? pushLib.sentTo(pushSt, installId) : 0,
        // ⚠ AND THE RUN IT IS COUNTED IN. Two counters kept by two processes
        // drift apart the moment one of them restarts — a token rotation, a
        // retired registration, an unreadable push.json — and the phone, whose
        // own count only ever climbs, then reads the difference as proof that
        // nothing is ever dropped. It rebaselines when this string changes.
        // Null on an install this host has no row for, which means "unknown" and
        // must not be acted on. See lib/pushtokens.mintEpoch.
        pushEpoch: installId ? pushLib.epochOf(pushSt, installId) : null,
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
      // `freshness`, `expiresAt`, `refreshTokenExpiresAt` and `refresh` all come
      // out of accounts.list(); the only thing it cannot know is which slugs
      // have a POST in flight RIGHT NOW, so that one word is corrected here.
      for (const a of saved) if (refreshInFlight.has(a.slug)) a.freshness = 'refreshing';
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

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})\/refresh$/)) && req.method === 'POST') {
      const slug = m[1];
      if (!accounts.readProfile(slug)) return sendErr(res, 404, 'no such saved account');
      const status = await refreshProfile(slug);
      // `ok` is about the profile being usable afterwards, not about a POST
      // having happened: `not_needed` means the token is already good, and
      // `active_skipped` means the CLI is keeping it fresh itself.
      const ok = ['refreshed', 'not_needed', 'active_skipped'].includes(status);
      // `slug` and the `refresh` block travel too: the client's model declares
      // both, and without them a settings screen has to re-fetch /v1/accounts to
      // find out when the next attempt is due — for the row it is already
      // looking at. `describe()` is the same shape /v1/accounts renders.
      const after = accounts.readProfile(slug);
      return sendJson(res, 200, {
        ok,
        slug,
        status,
        refresh: after ? (oauthRefresh.refreshOf(after) || null) : null,
      });
    }

    if ((m = p.match(/^\/v1\/accounts\/([a-z0-9-]{1,60})\/activate$/)) && req.method === 'POST') {
      const slug = m[1];
      const gate = await ensureSwitchable(slug);
      if (!gate.ok) return sendErr(res, 409, gate.error);
      const r = await performSwitch(slug);
      if (!r.ok) return sendErr(res, r.status ? 409 : 404, r.error);
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
        // Explicit rather than only riding the spread: an older cache entry, or
        // a lookup that failed, must still answer the field rather than omitting
        // it — a missing key and a null one decode differently on the clients.
        account: (planCache.data && planCache.data.account) || null,
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
      const created = (q.stdout || '').trim() || name;
      // On the restore list from birth. The claudeSessionId is not known yet — the
      // title hook writes it once Claude boots — so the reconcile timer fills it in;
      // until then a reboot would bring this session back as a fresh `claude`, which
      // is still the create route's own default and better than losing the name.
      registryAdd(created, { cwd: WORKDIR });
      // ⚠ AND MARKED AS STILL COMING UP. This 201 is the client's cue to show a
      // composer, and a message typed into it arrives while the pane is still
      // empty — `claude` needs about two more seconds to paint anything that
      // reads stdin, and a paste before that is lost whole, Enter included. The
      // send queue holds those until the composer appears; see `launchingAt`.
      markLaunching(created);
      return sendJson(res, 201, { ok: true, name: created });
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

    // --- the overview: what this session has spent, and the map of what it did
    //
    // DELIBERATELY NOT ON /v1/sessions?preview=1 AND NOT IN THE WATCH DIGEST.
    // The list is polled by every client and by the notification poller; a
    // whole-file walk per session per poll would make the cheapest route on
    // this daemon the most expensive one. And nothing here is news worth waking
    // a parked phone for — it is a page somebody opens on purpose.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/overview$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || !st.sessionId || !st.transcript) {
        return sendErr(res, 409, 'no transcript recorded for this session yet — the Claude hook fires on the first prompt');
      }
      const o = sessionOverview(st.transcript, st.sessionId);
      if (!o) return sendErr(res, 409, 'recorded transcript file is gone');
      return sendJson(res, 200, {
        name,
        claudeSessionId: st.sessionId,
        ...o,
        meta: sessionMetaView(st.sessionId),
      });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/graph$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || !st.sessionId || !st.transcript) {
        return sendErr(res, 409, 'no transcript recorded for this session yet — the Claude hook fires on the first prompt');
      }
      // The cursor is TWO numbers because a fan-out grows in two places. A
      // parent writes nothing at all while six agents run, so a parent-size
      // cursor reports "unchanged" for as long as the fan-out lasts — which is
      // exactly the stretch the map is worth watching.
      const g = sessionGraph(st.transcript, st.sessionId);
      if (!g) return sendErr(res, 409, 'recorded transcript file is gone');
      // ⚠ has() BEFORE Number(), because `Number(null)` is 0 and an EMPTY
      // transcript's cursor is also 0 — so a cursor-less first fetch of a
      // session that has not written a byte yet was answered `unchanged`, and
      // the client had nothing to be unchanged FROM. It sat on an empty screen
      // for as long as the session stayed quiet. The agentBytes check below
      // already modelled this; the size check did not.
      const hasSize = u.searchParams.has('size');
      const size = Number(u.searchParams.get('size'));
      const agentBytes = Number(u.searchParams.get('agentBytes'));
      if (hasSize && Number.isFinite(size) && size === g.cursor.size
        && (!u.searchParams.has('agentBytes') || agentBytes === g.cursor.agentBytes)) {
        // ⚠ THE META RIDES BOTH SHAPES. Goals and notes are edited on a phone
        // while the desktop watches an IDLE session, and an idle session is
        // precisely the one whose cursor never moves — so a short-circuit that
        // carried only the cursor meant an edit made on one device reached the
        // other only when the run happened to write something. The contract the
        // clients are built against is: `meta` is present on the unchanged reply
        // and on the full one, and is applied from either.
        return sendJson(res, 200, { unchanged: true, cursor: g.cursor, meta: sessionMetaView(st.sessionId) });
      }
      return sendJson(res, 200, { name, ...g, meta: sessionMetaView(st.sessionId) });
    }

    /**
     * Put a session back on the model it started on.
     *
     * TWO kinds of move end up here, because the phone and the desktop route
     * both of their "back to Fable" buttons to this one route:
     *
     *   * the Undo on `headroom_downgraded` — a rung APPD typed, recorded in
     *     `ladder`; and
     *   * "Back to Fable" on `headroom_ladder_up` — a rung CLAUDE CODE took by
     *     itself when the Fable week ran out, recorded in `nativeSwitch` with no
     *     `ladder` at all, because appd typed nothing.
     *
     * ⚠ THE SECOND ONE USED TO BE UNPRESSABLE. The route insisted on
     * `ladder.to`, which is precisely the field a native switch does not have —
     * so the only sessions the offer is ever pushed for were the only sessions
     * the route refused. A session with NEITHER is still a 409.
     *
     * Either way it stamps `humanSetModelAt`, which is what stops the arbiter
     * from moving the session straight back down on the next tick — the owner
     * has now answered the question the ladder was asking, and appd does not
     * argue with that for the next ten minutes.
     */
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/headroom\/undo$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || !st.sessionId) return sendErr(res, 409, 'this session has no Claude session id yet');
      const state = hstate();
      const rec = state.sessions[st.sessionId];
      const laddered = !!(rec && rec.ladder && rec.ladder.to);
      const native = !laddered && rec && rec.nativeSwitch && rec.nativeSwitch.to ? rec.nativeSwitch : null;
      if (!laddered && !native) return sendErr(res, 409, 'huginn has not moved this session');
      // Where it was BEFORE the move. A native switch is observed rather than
      // performed, so it does not record the rung it left; the ladder's own
      // first rung is the answer, because that is the model the ladder exists
      // to put sessions back on.
      const back = laddered
        ? rec.ladder.from
        : (native.from || loadHeadroomSettings().ladder[0]);
      const now = Date.now();
      rec.humanSetModelAt = now;
      // ⚠ MARK THE NATIVE MOVE IN FLIGHT. `decide` reads `ladder.delivery ===
      // 'pending'` as "a model move is waiting for a turn boundary" and holds
      // every other lever off; without a record here a tick landing between the
      // button and the picker would start a ladder_down behind it.
      if (native) {
        rec.ladder = {
          from: native.to, to: back, at: now, delivery: 'pending', sessionOnly: true, how: 'undo-native',
        };
      }
      let settled = false;
      // The settle is what clears the record when the job only QUEUED. The
      // picker must not open inside a running turn, so an undo pressed mid-turn
      // waits for the boundary — and without this the session stayed marked as
      // laddered after the move had actually happened.
      const landed = () => {
        settled = true;
        const st2 = hstate();
        const r2 = st2.sessions[st.sessionId];
        if (!r2) return;
        r2.ladder = null;
        // ⚠ THE NATIVE MARK GOES TOO, and only for the native case. `decide`
        // refuses to ladder a session carrying one at all ("appd never fights a
        // native switch"), so a mark left standing after the owner has chosen
        // Fable would mean this session could never be moved down again however
        // red the week got. `humanSetModelAt` is what holds the arbiter off for
        // the next ten minutes instead.
        if (native) r2.nativeSwitch = { seenAt: null, to: null };
        r2.family = back;
        st2.arbiter.lastLadderAt = Date.now();
        st2.arbiter.lastAction = { type: 'ladder_up', at: Date.now(), name, to: back, by: 'undo' };
        log(`headroom: ${name} put back on ${back} by hand`);
        saveHeadroomState(st2);
      };
      const out = await enqueueJob(name, () => applyLadder(name, back), {
        automated: false,
        origin: 'headroom',
        kind: 'model',
        onSettle: (v) => { if (v && v.result && v.result.ok) landed(); },
      });
      const result = out.result || {};
      // A native undo that RAN and could not confirm must not leave behind a
      // record claiming appd put this session on Fable — nothing moved, so the
      // native mark is still the truth. A job that is merely queued keeps its
      // pending record; the settle resolves that one.
      if (native && !settled && out.result != null) rec.ladder = null;
      saveHeadroomState(state);
      // ⚠ `ok` IS "THE REQUEST WAS ACCEPTED", NOT "THE MODEL MOVED". A client
      // that reported success on any 2xx announced "put back on its own model"
      // for an undo that was still sitting in the queue — under the same toast
      // key as the downgrade it claimed to have reversed. `applied` is the field
      // that answers the question the reader is actually asking.
      const applied = !!result.ok;
      const queued = out.result == null && !out.dropped;
      const body = { ok: true, applied, queued, to: back };
      if (!queued) {
        body.delivery = result.delivery
          || (out.dropped ? `dropped: ${out.dropped}` : 'delivery_unconfirmed');
      }
      return sendJson(res, 200, body);
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/meta$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      // A plain shell, or a session whose first prompt has not landed yet. Said
      // in words rather than by writing the file under the tmux name, which is
      // the one thing this store must never do.
      if (!st || !st.sessionId) {
        return sendErr(res, 409, 'this session has no Claude session id yet — notes are kept against the run, not the window name');
      }
      const body = JSON.parse(await readBody(req) || '{}');
      if (body.goals !== undefined && typeof body.goals !== 'string') return sendErr(res, 400, 'goals must be text');
      if (body.notes !== undefined && typeof body.notes !== 'string') return sendErr(res, 400, 'notes must be text');
      if (typeof body.goals === 'string' && body.goals.length > MAX_GOALS) {
        return sendErr(res, 400, `goals are at most ${MAX_GOALS.toLocaleString('en-US')} characters`);
      }
      if (typeof body.notes === 'string' && body.notes.length > MAX_NOTES) {
        return sendErr(res, 400, `notes are at most ${MAX_NOTES.toLocaleString('en-US')} characters`);
      }
      // THREE-valued on purpose: true / false / null, where null hands the
      // decision back to the global setting. `'autoResume' in body` rather than
      // a truthiness check, because `false` and `null` are both meaningful and
      // both falsy.
      if ('autoResume' in body && body.autoResume !== null && typeof body.autoResume !== 'boolean') {
        return sendErr(res, 400, 'autoResume must be true, false or null');
      }
      const saved = updateSessionMeta(st.sessionId, (meta) => {
        if (typeof body.goals === 'string') meta.goals = body.goals;
        if (typeof body.notes === 'string') meta.notes = body.notes;
        if ('autoResume' in body) {
          if (body.autoResume === null) delete meta.autoResume;
          else meta.autoResume = body.autoResume;
        }
      });
      return sendJson(res, 200, {
        ok: true,
        claudeSessionId: st.sessionId,
        meta: sessionMetaView(st.sessionId),
        autoResume: typeof saved.autoResume === 'boolean' ? saved.autoResume : null,
      });
    }

    // --- the individual agents behind "0/4 agents done"
    //
    // `?all=1` is the STREAM PICKER's view: every agent this session ever
    // spawned, not just the ones still warm. The default stays the 45-minute
    // window because the progress popup that has always called this route wants
    // "what is happening", and a sheet that grows to two hundred corpses is a
    // worse answer to that question.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/agents$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      const dir = st ? agentsDirFor(st.transcript, st.sessionId) : null;
      const all = u.searchParams.get('all') === '1';
      const agents = dir
        ? listAgents(dir, Math.floor(Date.now() / 1000), fs, all ? 200 : 24, { all })
        : [];
      return sendJson(res, 200, {
        agents,
        active: agents.filter((a) => a.active).length,
        serverTime: Math.floor(Date.now() / 1000),
      });
    }

    // --- ONE subagent's conversation, so the app can switch the transcript view
    //     from the parent to whichever agent or workflow member it is watching.
    //
    // An agent file is an ordinary Claude transcript, so this is the session
    // route's paging contract over a different file — same `offset`/`until`/
    // `limit`, same page shape — and NOT the session route's extras: `activity`
    // and `tasks` describe the parent's pane and its background shells, which
    // belong to the parent no matter which stream is on screen.
    //
    // ⚠ The id is never joined onto a path. `agentId` is matched against the
    // BASENAMES that listAgentFiles found; a `path.join` of a client-supplied
    // value would make `../../..` a file read, and the regex alone is not the
    // guard (a regex is a guess about what a path resolver will do — the
    // enumeration is the fact).
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/agents\/([^/]{1,80})\/transcript$/)) && req.method === 'GET') {
      const name = m[1];
      // Decoded before matching, so a percent-encoded traversal is judged as
      // what it means rather than as the literal it arrived as. A malformed
      // escape is simply not a valid id.
      let raw = null;
      try { raw = decodeURIComponent(m[2]); } catch { raw = null; }
      // ⚠ BOTH SPELLINGS. `GET /agents` emits the BARE hex (the basename with
      // `agent-` and `.jsonl` stripped) and the client hands that straight back
      // here — so a route that took only the prefixed form 400'd every stream
      // chip. A 400 is not the 404 the client's compat path watches for, so it
      // did not even degrade: the strip showed the raw daemon error. Normalised
      // to the prefixed form, which is what the file on disk is called and what
      // this route has always echoed.
      const idm = raw ? /^(?:agent-)?([0-9a-f]{6,32})$/.exec(raw) : null;
      if (!idm) return sendErr(res, 400, 'invalid agent id');
      const agentId = `agent-${idm[1]}`;
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const st = readSessionState(name);
      if (!st || !st.transcript || !st.sessionId) {
        return sendErr(res, 409, 'no transcript recorded for this session yet — the Claude hook fires on the first prompt');
      }
      const dir = agentsDirFor(st.transcript, st.sessionId);
      const hit = (dir ? listAgentFiles(dir) : [])
        .find((f) => path.basename(f.file) === `${agentId}.jsonl`);
      if (!hit) return sendErr(res, 404, 'no such agent');
      const offsetParam = u.searchParams.get('offset');
      const offsetNum = offsetParam == null ? null : Number(offsetParam);
      if (offsetNum !== null && !Number.isFinite(offsetNum)) return sendErr(res, 400, 'offset must be a number');
      const untilParam = u.searchParams.get('until');
      const untilNum = untilParam == null ? null : Number(untilParam);
      if (untilNum !== null && !Number.isFinite(untilNum)) return sendErr(res, 400, 'until must be a number');
      const t = readTranscript(hit.file, {
        offset: offsetNum,
        until: untilNum,
        limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
      });
      return sendJson(res, 200, {
        ...t,
        agentId,
        workflowId: hit.workflow,
        modelDisplay: formatModel(t.model),
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
      // ⚠ EVERY MOVE BELOW IS `set(new) THEN delete(old)`, WHICH ERASES THE ROW
      // WHEN THE TWO NAMES ARE THE SAME. A rename to the name it already has is a
      // legitimate no-op — the desktop's rename field answers with whatever is in
      // it, and tmux's own rewrite can land back on `from` — and it used to cost
      // the session its pane lease and its soft-end.
      if (actual !== from) {
        if (leases.has(from)) { leases.set(actual, leases.get(from)); leases.delete(from); }
        if (softEnds.has(from)) { softEnds.set(actual, softEnds.get(from)); softEnds.delete(from); }
        // ⚠ AND THE TWO THE RENAME USED TO DROP ON THE FLOOR. Both belong to a
        // session that was created SECONDS ago, which is exactly when a client
        // renames one — the app's create sheet names the session after the fact.
        //
        //   launchingAt   the startup gate's mark. Dropped, the send queued
        //                 against the new name has no gate at all and goes
        //                 straight at a pane `claude` has not painted yet, which
        //                 is the 3.0.7 P1 coming back through the rename.
        //   sendQueues    the queue ITSELF. Dropped, every message already held
        //                 for this session is stranded in a map nothing will ever
        //                 pump again: not delivered, not dropped, not reported.
        //                 `GET /typing` under the new name says nothing is queued.
        if (launchingAt.has(from)) { launchingAt.set(actual, launchingAt.get(from)); launchingAt.delete(from); }
        if (unmarkedHeld.delete(from)) unmarkedHeld.add(actual);
        const q = sendQueues.get(from);
        if (q) {
          // ⚠ AND ITS TIMER, WHICH IS CLOSED OVER THE OLD NAME. Left running it
          // pumps a name nothing is queued under any more, sets `q.timer = null`
          // on the way past, and nothing re-arms — so the queue would be moved
          // correctly and then never polled again.
          if (q.timer) { clearTimeout(q.timer); q.timer = null; }
          sendQueues.set(actual, q);
          sendQueues.delete(from);
          armQueueTimer(actual);
        }
      }
      // The restore registry is keyed by name too; move it or the live session goes
      // unrecorded under the new name and the old name gets pruned as "ended".
      registryRename(from, actual);
      return sendJson(res, 200, { ok: true, name: actual });
    }

    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/keys$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      /**
       * 512 KB, not the 256 KB default.
       *
       * SESSION_TEXT_MAX is 100,000 CHARACTERS and the body is UTF-8: a message
       * of emoji or CJK is four bytes a character, so a perfectly legal send
       * exceeds 256 KB of body. Against the default limit that arrived as a
       * malformed body — the sender was told their JSON was broken, for a
       * message the route was about to accept.
       */
      const body = JSON.parse(await readBody(req, 512 * 1024) || '{}');
      let typedKeys = typeof body.text === 'string' ? body.text : '';
      if (typedKeys.length > typing.SESSION_TEXT_MAX) return sendErr(res, 400, 'text too long');
      // Validate the key names BEFORE anything is typed. They used to be checked
      // after the text had already landed in the pane, so a bad key name gave a
      // 400 for a send that had half happened.
      const keys = Array.isArray(body.keys) ? body.keys : [];
      if (keys.length > 32) return sendErr(res, 400, 'too many keys');
      for (const k of keys) if (!validKey(k)) return sendErr(res, 400, `key not allowed: ${k}`);
      /**
       * The keys that are genuinely KEY PRESSES, as opposed to the composer's
       * own submit.
       *
       * Both clients send `{text, keys:["Enter"]}` for "send this message", and
       * that Enter is part of the message, not an interrupt: the paste path
       * presses it, with the right beat, once. Everything else in `keys` — an
       * Escape, a BTab, an arrow — is a raw press that cannot be queued and
       * cannot wait, which is what the modal refusal below is about.
       */
      const rawKeys = keys.filter((k) => !(k === 'Enter' && typedKeys.length > 0));
      /**
       * A scratchpad reference, as a PATH rather than as the page itself.
       *
       * A pane takes a message and a page holds up to 100,000 characters, so
       * pasting one in would refuse most of the pages worth attaching even now
       * that the two caps match. The session's Claude has Read and the file is
       * on this host, so it gets the better half of the trade: a pointer it can
       * re-read, and a message that still fits.
       *
       * ⚠ OUTSIDE THE `text` GUARD, WHICH IS WHERE IT USED TO BE. A page with no
       * message is a legitimate thing to send — "read this" — and the reference
       * used to be silently dropped in that case while the route answered ok.
       * Attaching a page and being told it worked, with nothing arriving in the
       * pane, is the worst of the three possible behaviours.
       */
      if ('scratchpadId' in body && body.scratchpadId !== null) {
        const ref = padForReference(body.scratchpadId);
        if (ref.badRequest) return sendErr(res, 400, ref.badRequest);
        if (ref.error) return sendErr(res, 404, ref.error);
        // ONE render per send — renderPad writes a file each time it is called.
        const rendered = renderPad(ref.pad);
        const frame = scratchpadsLib.sessionFrame(ref.pad.name, rendered);
        typedKeys = typedKeys
          ? scratchpadsLib.composeForSession(ref.pad, rendered, typedKeys)
          : frame;
        // The SESSION wording, not the chat one: only this one line travels, so
        // the number the sender is given is the room left for what they typed.
        const overflow = scratchpadsLib.sessionFitProblem(typedKeys, frame, typing.SESSION_TEXT_MAX);
        if (overflow) return sendErr(res, 413, overflow);
      }
      /**
       * ⚠ RAW KEYS ARE NEVER REFUSED FOR A MODAL, and there is no gate here.
       *
       * A key send is a PERSON driving the pane — the Screen tab sends every
       * keypress this way, plus its dedicated Escape and BTab buttons. Refusing
       * them at a dialog took the terminal keyboard and the Interrupt button
       * away at exactly the moment they are needed, since Escape, the arrows,
       * BTab and the digits are the keys that ANSWER a dialog; and it told the
       * reader to go to the Screen tab they were already on. It broke the
       * shipped 2.88.0 client identically.
       *
       * The turn-boundary QUEUE below still applies to `text`, and only to
       * `text`: a message absorbed into a running turn silently rewrites what
       * that turn was told to do, and a message delivered into a modal is
       * swallowed with no trace. Neither is true of a keystroke a person just
       * pressed.
       */
      let queued = 0;
      let position = 0;
      let delivered = false;
      /**
       * WHY THE SEND'S OWN ANSWER CARRIES THE REASON, and not just the count.
       *
       * Both clients seed their "queued" line from this response and only then
       * start polling `/typing` — so for the first poll interval the line is drawn
       * from whatever the seed knew, and the seed knew nothing but a number. The
       * default sentence is "will send when Claude finishes its turn", which for a
       * session held by `starting` is about a turn that has not begun: for ~2 s
       * after creating a session, every client says the wrong thing about the one
       * wait a reader is most likely to see. Same word as `/typing` reports, so
       * the sentence does not change under the reader when the first poll lands.
       */
      let blockedBy = null;
      if (typedKeys.length > 0) {
        /**
         * Through the QUEUE, not straight at the pane.
         *
         * A message delivered mid-turn is absorbed into the running turn and can
         * override what that turn was told to do; a message delivered into a
         * modal is swallowed with no trace anywhere. Both gates are re-checked
         * every 400 ms until they open, and the client watches
         * `GET /v1/sessions/:name/typing` rather than holding this request open
         * for what can legitimately be a ten-minute wait.
         *
         * The Enter rides WITH the text on the paste path: `keys:["Enter"]` from
         * a composer send means "submit this message", and pressing it again
         * after the paste has already submitted would send an empty second one.
         */
        const wantsEnter = keys.includes('Enter');
        const out = await enqueueSend(name, typedKeys, { origin: 'client', submit: wantsEnter });
        if (out.result && !out.result.ok) {
          return sendErr(res, out.result.code || 500, out.result.message);
        }
        delivered = out.delivered;
        position = out.position;
        queued = out.queued;
        blockedBy = out.blockedBy;
      }
      for (const k of rawKeys) {
        const r = await run('tmux', ['send-keys', '-t', `=${name}:`, k]);
        if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim()}`);
      }
      return sendJson(res, 200, { ok: true, queued, position, delivered, blockedBy });
    }

    /**
     * Where a send got to — the progress poll behind the clients' "queued" mark.
     *
     * Cheap on purpose: the in-memory queue and the gate verdict cached by the
     * last poll pass, no transcript walk and no tmux call. A poll rather than a
     * stream on the POST because a dropped socket must not lose a send (a
     * backgrounded phone drops one inside a doze window, and a send can wait ten
     * minutes for a boundary), because two clients can watch one session, and
     * because nothing else about a tmux session streams.
     */
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/typing$/)) && req.method === 'GET') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      return sendJson(res, 200, typing.typingSnapshot(sendQueues.get(name), Date.now()));
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
      // presence check and silently also accepts ''. The Electron client (since
      // deleted) rejected both in its own notification path; the host did not.
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
    // --- desktop update channel: the manifest and the installers themselves.
    // Auth like every route — the updater sends the Bearer header on the
    // manifest AND artifact GETs. This is the daemon's first streaming-OUT path
    // (uploads is the streaming-in precedent): an installer is ~90 MB and must
    // never transit the heap.
    //
    // One helper, and the uploads route below borrows it. The directory is the
    // ONLY difference between the callers here; keeping that a parameter rather
    // than a second copy of the route is what stops a future fix landing in one
    // and not the other.
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
    // --- the Compose Multiplatform client's channel, and the only one: /v1/desktop-kt,
    // stocked by mobile/scripts/release-desktop.sh. The updater that reads it pins
    // these paths at compile time (UpdateFeed.kt), because the builds are unsigned
    // and whoever controls the feed controls what executes. Kept under its own name
    // rather than promoted to /v1/desktop now that the Electron channel is gone: the
    // 0.5.x clients still installed poll THIS path, and the retired one is better
    // left as a 404 than quietly re-pointed at a different application's feed.
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

    // --- a HOST image the assistant named by path, so a transcript can draw it
    // instead of printing "/home/me/shot.png" and leaving the reader to go and
    // look. `?path=` is absolute, or relative to the named session's cwd.
    //
    // This is the one route where the CLIENT supplies a path, which is why it
    // does not reuse serveArtifact: resolveArtifact's containment is a filename
    // regex with no realpath, correct for names the SERVER chose and worthless
    // for a path a client sent. lib/files does the resolving (two containment
    // passes, lexical then canonical), and only the streaming shape is borrowed
    // from serveArtifact — including its res.on('close') fd-leak fix, which
    // matters more here: a transcript that scrolls past an image aborts the GET.
    //
    // THE ROOTS, and nothing else:
    //   1. UPLOADS_DIR              — what the phone already sent
    //   2. SCRATCHPAD_RENDER_DIR    — what a scratchpad rendered
    //   3. CLAUDE_SCRATCH_DIR       — where Claude Code's own scratchpad lives
    //   4. the named session's cwd  — ONLY with ?session=<a live session>
    //
    // Root 4 is the widening one and it is gated twice: the session must be live
    // on tmux RIGHT NOW (sessionExists, not just a state file — a name outlives
    // the session that owned it and the state dir is full of corpses), and the
    // state must carry a cwd. An unknown or ended name simply contributes no
    // root, so it can only ever narrow what is servable, never widen it.
    if (req.method === 'GET' && p === '/v1/files/image') {
      const roots = [UPLOADS_DIR, SCRATCHPAD_RENDER_DIR, CLAUDE_SCRATCH_DIR];
      let cwd = null;
      const sessName = String(u.searchParams.get('session') || '').trim();
      if (sessName && await sessionExists(sessName)) {
        const st = readSessionState(sessName);
        if (st && st.cwd) { cwd = st.cwd; roots.push(st.cwd); }
      }
      const found = filesLib.resolveImage({
        requested: u.searchParams.get('path'),
        roots, cwd, maxBytes: IMAGE_SERVE_MAX_BYTES, fs,
      });
      if (!found.ok) return sendErr(res, found.status, found.error);

      // Private, because the token is the only thing between this and the host's
      // filesystem and a shared cache keyed on the URL alone would serve it to
      // the next caller. Five minutes because a transcript re-renders on every
      // poll and these bytes do not change; the ETag covers the case where they
      // do.
      const cacheHeaders = {
        'Cache-Control': 'private, max-age=300',
        ETag: found.etag,
        'X-Content-Type-Options': 'nosniff',
      };
      if (filesLib.etagMatches(req.headers['if-none-match'], found.etag)) {
        res.writeHead(304, cacheHeaders);
        return res.end();
      }
      res.writeHead(200, {
        'Content-Type': found.contentType,
        'Content-Length': found.size,
        ...cacheHeaders,
      });
      const stream = fs.createReadStream(found.file);
      res.on('close', () => stream.destroy());
      stream.pipe(res);
      stream.on('error', () => { try { res.destroy(); } catch { } });
      return undefined;
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
        // A beat is liveness for the device's in-flight run, not only for the row.
        // A runner beats every 60s from a timer that is independent of its work
        // loop, so a long QUIET tool call — a build that streams nothing for
        // minutes — is not silence: the machine is plainly still there. Without
        // this refresh the run is declared lost at REMOTE_SILENCE_MS and the build
        // is killed mid-work on a false connectivity failure. `cancel` rides the
        // beat too, so a Stop reaches a run that is inside a quiet tool and not
        // currently posting event batches (the ack channel it would otherwise
        // wait on). Runners that do not yet read `cancel` here simply ignore it.
        let cancel = false;
        for (const entry of remoteRuns.values()) {
          if (entry.deviceId !== devId) continue;
          entry.lastHeard = now;
          if (entry.run_.cancelled) cancel = true;
        }
        return sendJson(res, 200, {
          ok: true,
          effectiveScope: devicesLib.effectiveScope(deviceState.devices[devId]),
          cancel,
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
    /**
     * A better draft of one field, from the model — never applied by it.
     *
     * ⚠ PLACED BEFORE THE `/v1/rounds/<id>` BRANCH ON PURPOSE. That branch matches
     * a 36-character uuid, so "polish" could not be captured by it either way, and
     * this exact-path branch is above it so the reading order matches the matching
     * order: a reader who sees the id branch first would have to hold the regex in
     * their head to know this one is still reachable.
     *
     * It operates on the DRAFT in somebody's editor, not on a stored Round, so
     * there is no id, nothing is written, and the PATCH re-read hazard two branches
     * below simply does not exist here. The answer goes back as a proposal and a
     * person accepts or discards it — AI drafts, human accepts.
     */
    if (req.method === 'POST' && p === '/v1/rounds/polish') {
      const body = JSON.parse(await readBody(req) || '{}');
      const field = typeof body.field === 'string' ? body.field.trim() : '';
      if (!POLISH_FIELDS.includes(field)) {
        return sendErr(res, 400, `field must be one of ${POLISH_FIELDS.join(', ')}`);
      }
      const draft = {
        title: roundsLib.oneLine(body.title, 80),
        prompt: typeof body.prompt === 'string' ? body.prompt.trim().slice(0, MAX_ROUND_PROMPT) : '',
        goal: typeof body.goal === 'string' ? body.goal.trim().slice(0, MAX_ROUND_GOAL) : '',
        mode: body.mode === 'act' ? 'act' : 'ask',
      };
      // Nothing to work from. Refused loudly rather than answered with an invented
      // Round: polish improves what somebody wrote, and a model handed only a
      // schedule would write the job itself and present it as their words.
      if (!draft.prompt && !draft.goal) {
        return sendErr(res, 400, 'write something first — polish improves a draft, it does not invent one');
      }
      const cap = field === 'prompt' ? MAX_ROUND_PROMPT : MAX_ROUND_GOAL;
      // 200 with {error} even when the model failed, exactly like the suggestions
      // path: the person is mid-sentence in a text field, and a 5xx there would be
      // handled by whatever the client does with a broken daemon rather than by the
      // one quiet line this deserves.
      return sendJson(res, 200, await polishFor(field, draft, cap));
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

    // ---- archive: a session ended on purpose, kept with the way back
    //
    // Its OWN routes rather than a flag on Session, and that is the whole design.
    // An archived session never appears in /v1/sessions, so the send-target
    // picker, the desktop's command palette and the home-screen widget — three
    // surfaces that take a plain session list and have no concept of state —
    // inherit nothing and are correct for free.

    // Archive a live session. GRACEFUL by default: the wrap-up phrase goes in,
    // the turn is allowed to finish, and the card is written in the instant
    // before the kill. `mode: "now"` is the escape hatch for a session there is
    // nothing to wrap up in.
    if ((m = p.match(/^\/v1\/sessions\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\/archive$/)) && req.method === 'POST') {
      const name = m[1];
      if (!(await sessionExists(name))) return sendErr(res, 404, 'no such session');
      const body = JSON.parse(await readBody(req) || '{}');
      // The query string as well as the body: the CLI reaches this through a
      // bodyless `curl -X POST` over ssh (see server/bin/huginn-archive), and a
      // verb whose only option needs a JSON body would mean teaching a laptop to
      // build one.
      const mode = String(body.mode || u.searchParams.get('mode') || 'graceful');
      if (mode !== 'graceful' && mode !== 'now') return sendErr(res, 400, 'mode is "graceful" or "now"');
      const note = typeof body.note === 'string' ? body.note : null;

      const st = readSessionState(name);
      // Nothing to bring back. A pane that has never run Claude has no session
      // id, so its "archive" would be a row whose resume command cannot work —
      // the same refusal /meta makes, in the same words.
      if (!st || !st.sessionId) {
        return sendErr(res, 409, 'no Claude session recorded for this one yet — there is nothing to bring back, so it can only be ended');
      }
      if (mode === 'graceful') {
        // ⚠ THE SAME GUARD /soft-end HAS, AND FOR A SHARPER REASON. A question is
        // on screen waiting for a person; typing prose into a numbered prompt is
        // lost or misread, and archiving a session mid-question throws away the
        // one thing it was waiting to be told.
        if (st.state === 'attention') {
          return sendErr(res, 409, 'answer the waiting question first, then archive the session');
        }
        // Mid-turn is fine and is not a wait the caller has to sit through: the
        // phrase queues in the composer, and the settle timer will not end
        // anything until idle has held. The 202 says so.
        const queued = st.state === 'running';
        const r = await sendLineToPane(name, SOFT_END_PHRASE);
        if (r.err) return sendErr(res, 500, `tmux: ${(r.stderr || '').trim()}`);
        // Armed regardless of the host's softEndAuto. That setting decides
        // whether a WIND-DOWN ends the session; an archive was asked for by name
        // and has to end it, or the row would describe a session still running.
        softEnds.set(name, createPending(Date.now()));
        archiveIntents.set(name, { note });
        return sendJson(res, 202, {
          ok: true,
          id: st.sessionId,
          archived: false,
          pending: true,
          mode: 'graceful',
          phrase: SOFT_END_PHRASE,
          queued,
        });
      }
      const r = await archiveAndEnd(name, { note });
      if (r.err) return sendErr(res, r.rec ? 500 : 409, r.err);
      return sendJson(res, 202, { ok: true, id: r.rec.id, archived: true, pending: false, mode: 'now', archive: r.rec });
    }

    // The list, and the FEATURE PROBE both clients use. A daemon without archive
    // answers 404 here and the clients hide the whole section rather than showing
    // a door that leads to an error — the scratchpads/refreshRounds precedent.
    if (req.method === 'GET' && p === '/v1/archive') {
      const liveIds = await liveSessionIds();
      const rows = archiveLib.sortArchives(listArchives()).map((rec) => archiveLib.archiveRow(rec, {
        // Live means THIS conversation is running under that name — the id has to
        // match, because a tmux name is reused and a stranger holding it is not
        // this archive coming back.
        live: !!archiveLiveName(rec, liveIds),
        // Recomputed every list, never stored: the thing it describes (Claude
        // Code's 21-day sweep of its own transcripts) happens while this daemon
        // is not looking.
        transcriptPresent: fs.existsSync(archiveTranscriptPath(rec.id)) || !!findTranscriptFile(rec.id),
      }));
      return sendJson(res, 200, { archives: rows, max: archiveLib.MAX_ARCHIVES });
    }

    // The conversation of an archived session, read from the COPY.
    //
    // No sessionExists gate — that is the point. Every other transcript route
    // begins by checking the tmux session is live and then reads state keyed on
    // its NAME, and both of those are gone by the time a session is archived. The
    // row carries its own path instead, and readTranscript is pure and takes one.
    if ((m = p.match(/^\/v1\/archive\/([0-9a-f-]{36})\/transcript$/)) && req.method === 'GET') {
      const id = m[1];
      const rec = loadArchive(id);
      if (!rec) return sendErr(res, 404, 'no such archived session');
      const kept = archiveTranscriptPath(id);
      // The kept copy FIRST and Claude Code's own only as a fallback: ours cannot
      // be swept out from under this route, and after a revive the two are the
      // same file anyway.
      const file = fs.existsSync(kept) ? kept : findTranscriptFile(id);
      if (!file) return sendErr(res, 409, 'no transcript was kept for this archive, and Claude Code no longer has one');
      const offsetParam = u.searchParams.get('offset');
      const offsetNum = offsetParam == null ? null : Number(offsetParam);
      if (offsetNum !== null && !Number.isFinite(offsetNum)) return sendErr(res, 400, 'offset must be a number');
      const untilParam = u.searchParams.get('until');
      const untilNum = untilParam == null ? null : Number(untilParam);
      if (untilNum !== null && !Number.isFinite(untilNum)) return sendErr(res, 400, 'until must be a number');
      const t = readTranscript(file, {
        offset: offsetNum,
        until: untilNum,
        limit: Math.max(1, Math.min(800, Number(u.searchParams.get('limit')) || 400)),
      });
      return sendJson(res, 200, {
        ...t,
        modelDisplay: formatModel(t.model),
        claudeSessionId: id,
        archived: true,
        // Said on every window, because an archive of a truncated transcript
        // starts mid-conversation and a reader scrolling to the top would
        // otherwise conclude that is where it began.
        transcriptTruncated: !!rec.transcriptTruncated,
      });
    }

    // Bring one back to life: recreate the tmux session and resume into it.
    if ((m = p.match(/^\/v1\/archive\/([0-9a-f-]{36})\/revive$/)) && req.method === 'POST') {
      const id = m[1];
      const rec = loadArchive(id);
      if (!rec) return sendErr(res, 404, 'no such archived session');
      const body = JSON.parse(await readBody(req) || '{}');

      const liveIds = await liveSessionIds();
      if (liveIds === null) return sendErr(res, 503, 'tmux is not answering right now');
      // Already back. Reviving again would put a SECOND Claude on one transcript,
      // and two processes appending to one jsonl is how a conversation becomes
      // unreadable to both of them.
      for (const [liveName, liveId] of liveIds) {
        if (liveId === id) return sendErr(res, 409, `that conversation is already running as '${liveName}'`);
      }

      let asked = null;
      if (typeof body.name === 'string' && body.name.trim()) {
        asked = canonName(body.name);
        if (!asked) return sendErr(res, 400, 'invalid session name (letters, digits, underscore)');
      }
      const want = archiveLib.reviveName(asked || rec.tmuxName || 'session', new Set(liveIds.keys()));
      const cwd = rec.cwd || WORKDIR;

      // ⚠ THE TRANSCRIPT GOES BACK FIRST. `claude --resume <id>` with nothing on
      // disk to resume is not an error: the CLI opens a fresh conversation, so a
      // revive that ran after the 21-day sweep would come up looking exactly like
      // a success and remember nothing. Restoring before the launch is the only
      // ordering where that cannot happen.
      const restored = restoreArchivedTranscript(id, cwd);
      const hasTranscript = !!findTranscriptFile(id);
      const { canResume, command } = sessreg.resumeCommand({ claudeSessionId: id }, hasTranscript);

      // Whatever the last holder of this name left behind goes now, exactly as the
      // create route does it, and for the same reason: the new session must not be
      // observed through a corpse's state file.
      clearSessionState(want);
      await ensureTmuxServerScope();
      const r = await run('tmux', ['new-session', '-d', '-s', want, '-c', cwd, command]);
      if (r.err) return sendErr(res, 500, `tmux: ${(r.stderr || r.err.message || '').trim() || 'could not recreate the session'}`);
      // What tmux CALLED it. A '.' becomes '_' with a zero exit, and a client told
      // the wrong name gets a 404 on everything it does next.
      const q = await run('tmux', ['display-message', '-p', '-t', `=${want}:`, '#S']);
      const created = (q.stdout || '').trim() || want;

      // On the restore list again, with the id already known this time — and
      // marked `restoredAt`, which means "the CLI's in-process usage-limit wait
      // died with the old process". As true for a revive as for a reboot: without
      // it auto-resume sits out its 90-second native grace waiting for a
      // continuation that can never come.
      registryAdd(created, { cwd, claudeSessionId: id, restoredAt: Math.floor(Date.now() / 1000) });
      // The same startup hold the create route takes: `claude` needs about two
      // seconds before anything reads stdin, and a revive is followed immediately
      // by somebody typing into it.
      markLaunching(created);

      const saved = updateArchive(id, (rr) => {
        rr.revivedAt = Math.floor(Date.now() / 1000);
        rr.revivedAs = created;
      }) || rec;
      log(`archive: ${id} revived as ${created}${canResume ? '' : ' (nothing resumable on disk — fresh)'}`);
      return sendJson(res, 201, {
        ok: true,
        name: created,
        // Whether the conversation actually came back, or only the name and the
        // directory did. The clients say which; a revive that quietly started a
        // blank session is the failure this whole feature exists to prevent.
        resumed: canResume,
        restoredTranscript: !!restored.restored,
        archive: saved,
      });
    }

    if ((m = p.match(/^\/v1\/archive\/([0-9a-f-]{36})$/))) {
      const id = m[1];
      const rec = loadArchive(id);
      if (!rec) return sendErr(res, 404, 'no such archived session');
      if (req.method === 'GET') {
        const liveIds = await liveSessionIds();
        return sendJson(res, 200, archiveLib.archiveRow(rec, {
          live: !!archiveLiveName(rec, liveIds),
          transcriptPresent: fs.existsSync(archiveTranscriptPath(id)) || !!findTranscriptFile(id),
        }));
      }
      if (req.method === 'DELETE') {
        // The row AND its transcript copy. The copy is the bulk of what an archive
        // costs, and a delete that left it behind would be a store that only grows.
        removeArchive(id);
        log(`archive: ${id} deleted${rec.title ? ` (${rec.title})` : ''}`);
        return sendJson(res, 200, { ok: true });
      }
      return sendErr(res, 404, 'no such archive route');
    }

    // ---- projects: a cluster of sessions with roles, a lead, and a dashboard
    //
    // The list is also the FEATURE PROBE both clients use: a daemon without
    // Projects answers 404 here and the clients hide the tree, the rail item and
    // the palette rows rather than showing a door that leads to an error (the
    // scratchpads/refreshRounds precedent).
    if (req.method === 'GET' && p === '/v1/projects') {
      const all = u.searchParams.get('all') === '1';
      const sessions = await listSessions();
      const rows = [];
      for (const stored of listProjects()) {
        // Adopting a manifest on a READ is deliberate; see [detectManifest].
        const project = detectManifest(stored);
        if (!all && project.status === 'archived') continue;
        rows.push(projectsLib.projectRow(project,
          projectsLib.joinMembers(project, sessions || [], readNativeRegistry())));
      }
      return sendJson(res, 200, { projects: rows, max: projectsLib.MAX_PROJECTS });
    }

    if (req.method === 'POST' && p === '/v1/projects') {
      const body = JSON.parse(await readBody(req) || '{}');
      const existing = listProjects();
      if (existing.length >= projectsLib.MAX_PROJECTS) {
        return sendErr(res, 400, `that is the ${projectsLib.MAX_PROJECTS}-project limit — archive one first`);
      }
      const badName = projectsLib.nameProblem(body.name, existing.map((x) => x.name));
      if (badName) return sendErr(res, 400, badName);
      const name = projectsLib.cleanName(body.name);
      const slug = projectsLib.slugFor(name);
      const badSlug = projectsLib.slugProblem(slug, existing.map((x) => x.slug));
      if (badSlug) return sendErr(res, 409, badSlug);
      const kind = projectsLib.KINDS.includes(body.kind) ? body.kind : null;
      if (!kind) return sendErr(res, 400, `kind is one of ${projectsLib.KINDS.join(', ')}`);
      const badBrief = projectsLib.briefProblem(body.brief);
      if (badBrief) return sendErr(res, 400, badBrief);

      const cwd = typeof body.cwd === 'string' && body.cwd.trim() ? body.cwd.trim().replace(/\/+$/, '') : WORKDIR;
      if (!cwd.startsWith('/')) return sendErr(res, 400, 'cwd must be an absolute path');
      try { if (!fs.statSync(cwd).isDirectory()) throw new Error('not a directory'); } catch {
        return sendErr(res, 400, `${cwd} is not a directory on this host`);
      }
      // ⚠ TRUST IS CHECKED, NEVER GRANTED. The folder-trust dialog blocks Claude
      // Code's session registration entirely and preselects "No, exit", so a lead
      // launched into an untrusted directory registers no peer name and cannot be
      // messaged — and answering the dialog for it would mean WRITING into
      // ~/.claude.json, a 115 KB file every live `claude` rewrites continuously.
      // Refused with the fix instead (decision 50). See [cwdIsTrusted].
      if (!cwdIsTrusted(cwd)) {
        return sendErr(res, 409, `${cwd} has not been trusted in Claude Code yet — open it once with `
          + '`claude` there and accept the folder-trust question, then create the project');
      }

      const leadTmux = projectsLib.tmuxNameFor(slug, projectsLib.LEAD_ROLE);
      if (await sessionExists(leadTmux)) return sendErr(res, 409, `a tmux session called '${leadTmux}' already exists`);

      const now = Math.floor(Date.now() / 1000);
      const project = {
        id: crypto.randomUUID(),
        name,
        slug,
        kind,
        status: 'drafting',
        brief: String(body.brief).trim().slice(0, projectsLib.MAX_BRIEF),
        cwd,
        lead: {
          role: projectsLib.LEAD_ROLE,
          name: leadTmux,
          claudeName: projectsLib.claudeNameFor(slug, projectsLib.LEAD_ROLE),
          sessionId: null,
          spawnedAt: now,
          endedAt: null,
        },
        members: [],
        manifest: {
          // Minted here, present ONLY in the lead's persona, and the reason a
          // `huginn-project` block found in anything the lead READS cannot be
          // mistaken for its own proposal. Same control as a Round's report tag.
          tag: crypto.randomBytes(5).toString('hex'),
          rev: 0,
          receivedAt: null,
          type: null,
          scope: '',
          summary: null,
          sessions: [],
          untaggedSeen: false,
          spawnedRev: 0,
        },
        endedReason: null,
        endedAt: null,
        createdAt: now,
        updatedAt: now,
        rev: 1,
      };
      const persona = writePersona(project.id, projectsLib.LEAD_ROLE, projectsLib.leadPersona(project));
      const launched = await launchProjectSession(leadTmux, cwd,
        claudeLaunchCommand({ claudeName: project.lead.claudeName, persona }));
      if (launched.error) {
        unlinkPersonas(project.id);
        return sendErr(res, 500, `tmux: ${launched.error}`);
      }
      project.lead.name = launched.name;
      saveProject(project);
      // The brief rides the ordinary queue behind the startup hold, so it lands
      // in a composer rather than in a pty nobody is reading yet.
      enqueueSend(launched.name, projectsLib.briefFrame(project), {
        automated: true, origin: 'project', kind: 'brief',
      }).catch((e) => log(`project ${slug}: brief failed: ${e.message}`));
      log(`project ${slug}: created (${project.id}), lead ${launched.name} in ${cwd}`);
      // ⚠ EVERY BODY THAT CARRIES A PROJECT GOES THROUGH [publicProject]. The
      // manifest tag is the anti-injection control and belongs in the lead's
      // system prompt and in the store — never on this port.
      return sendJson(res, 201, projectsLib.publicProject(project));
    }

    if ((m = p.match(/^\/v1\/projects\/([0-9a-f-]{36})(\/[a-z]+)?$/))) {
      const projectId = m[1];
      const sub = m[2] || '';
      const stored = loadProject(projectId);
      if (!stored) return sendErr(res, 404, 'no such project');

      if (req.method === 'GET' && sub === '') {
        const project = detectManifest(stored);
        const sessions = await listSessions();
        const joined = projectsLib.joinMembers(project, sessions || [], readNativeRegistry());
        return sendJson(res, 200, {
          ...projectsLib.publicProject(project),
          row: projectsLib.projectRow(project, joined),
          live: joined,
        });
      }

      if (req.method === 'GET' && sub === '/dashboard') {
        return sendJson(res, 200, await projectDashboard(detectManifest(stored)));
      }

      /**
       * Rename, re-brief, pause/resume, archive, and an edited manifest.
       *
       * ⚠ RE-READ AFTER THE AWAIT, then compare the rev. Two clients showing the
       * same project is the ordinary case, and a stale save is answered 409 with
       * the CURRENT project in the body so the editor can adopt it and say so —
       * the shipped scratchpad contract, which both clients already know.
       */
      if (req.method === 'PATCH' && sub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        const current = loadProject(projectId);
        if (!current) return sendErr(res, 404, 'no such project');
        const rev = Number(body.rev);
        if (!Number.isInteger(rev)) return sendErr(res, 400, 'rev is required — it is what makes a save safe');
        if (rev !== (Number(current.rev) || 0)) return sendJson(res, 409, projectsLib.publicProject(current));

        let name = null;
        if (typeof body.name === 'string') {
          const taken = listProjects().filter((x) => x.id !== projectId).map((x) => x.name);
          const bad = projectsLib.nameProblem(body.name, taken);
          if (bad) return sendErr(res, 400, bad);
          // The SLUG never moves: it is the tmux and peer namespace every member
          // is already named in, and renaming it would orphan the cluster.
          name = projectsLib.cleanName(body.name);
        }
        if (typeof body.brief === 'string') {
          const bad = projectsLib.briefProblem(body.brief);
          if (bad) return sendErr(res, 400, bad);
        }
        if (body.status != null) {
          const bad = projectsLib.transitionProblem(current.status, String(body.status));
          if (bad) return sendErr(res, 409, bad);
        }
        let manifest = null;
        if (body.manifest != null) {
          // An edited manifest is re-validated with the SAME rules the parser
          // applies to the lead's own block: the client's editor is a
          // convenience, not an authority, and a role or a cwd that could not
          // have been proposed must not become spawnable by being typed instead.
          const edited = projectsLib.parseManifest(
            `\`\`\`huginn-project edit\n${JSON.stringify(body.manifest)}\n\`\`\``,
            'edit', { cwd: current.cwd },
          );
          if (!edited) return sendErr(res, 400, 'that manifest is not valid — check the roles, the prompts and the directories');
          manifest = edited;
        }
        const saved = updateProject(projectId, (proj) => {
          if (name !== null) proj.name = name;
          if (typeof body.brief === 'string') proj.brief = body.brief.trim();
          if (body.status != null) proj.status = String(body.status);
          if (manifest) {
            proj.manifest = {
              ...proj.manifest,
              ...manifest,
              rev: (Number(proj.manifest.rev) || 0) + 1,
              receivedAt: Math.floor(Date.now() / 1000),
            };
            proj.status = 'proposed';
          }
        });
        if (!saved) return sendErr(res, 404, 'no such project');
        return sendJson(res, 200, projectsLib.publicProject(saved));
      }

      /**
       * Create the members the owner approved.
       *
       * The rev check is the whole point of the card: a notification that has
       * been sitting on a lock screen while the lead revised its plan must not
       * spawn the revision the owner never saw.
       */
      if (req.method === 'POST' && sub === '/spawn') {
        const body = JSON.parse(await readBody(req) || '{}');
        const project = loadProject(projectId);
        if (!project) return sendErr(res, 404, 'no such project');
        if (body.approve !== true) return sendErr(res, 400, 'approve must be true — spawning is the owner\'s decision');
        if (project.status !== 'proposed') return sendErr(res, 409, `this project is ${project.status}, not proposed`);
        const wantRev = Number(body.manifestRev);
        if (!Number.isInteger(wantRev)) return sendErr(res, 400, 'manifestRev is required');
        if (wantRev !== (Number(project.manifest.rev) || 0)) {
          return sendJson(res, 409, {
            error: 'the proposal has changed since that card was drawn',
            project: projectsLib.publicProject(project),
          });
        }
        if (!(project.manifest.sessions || []).length) return sendErr(res, 409, 'this proposal has no sessions in it');
        // ⚠ NOT INTO A RED WINDOW. Twelve fresh sessions on an account the
        // headroom arbiter has already stopped is how a cluster dies half-born:
        // the first few come up, the rest open on a 429, and the lead is left
        // messaging peers that never registered. Said in the 409 rather than
        // silently deferred, because the owner is standing at the card.
        let stop = null;
        try { stop = sentinelsLib.state(HEADROOM_DIR).STOP; } catch { /* no dir yet: nothing armed */ }
        if (stop) {
          return sendErr(res, 409, `there is no room on this account right now (${stop.reason || 'usage stop'}) `
            + '— spawn when the window resets');
        }
        const out = await spawnProject(project);
        return sendJson(res, 200, { ...out, project: projectsLib.publicProject(out.project) });
      }

      if (req.method === 'POST' && sub === '/discard') {
        const project = loadProject(projectId);
        if (!project) return sendErr(res, 404, 'no such project');
        if (project.status !== 'proposed') return sendErr(res, 409, `this project is ${project.status}, not proposed`);
        // The manifest is KEPT at its rev so Edit can still open it; only the
        // status moves. The lead is told, because otherwise it waits forever for
        // an approval that is not coming.
        const saved = updateProject(projectId, (proj) => { proj.status = 'drafting'; });
        if (project.lead) {
          enqueueSend(project.lead.name,
            '[Huginn] The proposal was discarded; the owner may send a revised brief.',
            { automated: true, origin: 'project', kind: 'discard' }).catch(() => { });
        }
        return sendJson(res, 200, projectsLib.publicProject(saved));
      }

      /**
       * A message from one member to another, typed by appd.
       *
       * ⚠ THIS IS NOT HOW THE SESSIONS TALK. Lead and members use Claude Code's
       * own `SendMessage`, which appd neither sees nor routes. This route exists
       * so the OWNER can put words in one member's pane addressed from another,
       * and because it is appd doing the typing it rides the SEND QUEUE and every
       * gate on it — a member sitting on a permission dialog holds the message
       * instead of having it typed into the dialog. The native path has no such
       * protection, which is exactly why this one keeps it.
       */
      if (req.method === 'POST' && sub === '/message') {
        const body = JSON.parse(await readBody(req) || '{}');
        const project = loadProject(projectId);
        if (!project) return sendErr(res, 404, 'no such project');
        const from = projectMemberNamed(project, body.from);
        const to = projectMemberNamed(project, body.to);
        if (!from) return sendErr(res, 400, 'from must name a role in this project');
        if (!to) return sendErr(res, 400, 'to must name a role in this project');
        if (from.role === to.role) return sendErr(res, 400, 'a session cannot be messaged from itself');
        const text = typeof body.text === 'string' ? body.text.trim() : '';
        if (!text) return sendErr(res, 400, 'text is required');
        if (text.length > projectsLib.MAX_PROMPT) return sendErr(res, 400, 'text too long');
        if (!(await sessionExists(to.name))) return sendErr(res, 409, `${to.claudeName} is not running`);
        const r = await enqueueSend(to.name, projectsLib.peerMessageFrame(from.claudeName, text), {
          automated: true, origin: 'project', kind: 'peerMessage',
        });
        return sendJson(res, 202, {
          ok: true, to: to.claudeName, from: from.claudeName,
          delivered: !!r.delivered, queued: r.queued, blockedBy: r.blockedBy || null, dropped: r.dropped || null,
        });
      }

      if (req.method === 'DELETE' && sub === '') {
        const body = JSON.parse(await readBody(req) || '{}');
        const project = loadProject(projectId);
        if (!project) return sendErr(res, 404, 'no such project');
        // `?end=1` is the contract's spelling and means the gentle one; the body
        // says which. Anything else ends NOTHING and only deletes the record —
        // the safe default, because a delete that silently kills twelve live
        // sessions is not a delete anybody meant.
        const q = u.searchParams.get('end');
        const raw = String(body.end || (q === '1' ? 'graceful' : q || ''));
        const end = raw === 'now' || raw === 'graceful' ? raw : '';
        const ended = [];
        if (end === 'now' || end === 'graceful') {
          for (const member of projectsLib.memberList(project)) {
            if (!(await sessionExists(member.name))) continue;
            if (end === 'graceful') {
              // The existing wind-down: the phrase goes into the composer and the
              // settle timer ends the session once idle has held. Not a wait the
              // caller sits through.
              const r = await sendLineToPane(member.name, SOFT_END_PHRASE);
              if (!r.err) { softEnds.set(member.name, createPending(Date.now())); ended.push(member.name); }
            } else {
              const r = await hardEndSession(member.name);
              if (!r.err) ended.push(member.name);
            }
          }
        }
        try { fs.unlinkSync(projectPath(projectId)); } catch { /* already gone */ }
        unlinkPersonas(projectId);
        log(`project ${project.slug}: deleted${ended.length ? `, ended ${ended.join(', ')}` : ''}`);
        return sendJson(res, 200, { ok: true, ended, mode: end || 'none' });
      }

      return sendErr(res, 404, 'no such project route');
    }


    // ---- consoles: the internal pages this host serves ------------------------
    //
    // A hand-curated registry of URLs with a liveness probe. NOT a second devices
    // registry — a Device is a machine that enrols under a scope lattice, a
    // console is an address (lib/consoles.js opens with the whole argument).
    //
    // ⚠ NOTHING IN HERE RUNS ANYTHING. The one operational step this feature
    // needs — binding three units to 0.0.0.0 here, four rules in heimdall's
    // /etc/pve/firewall/117.fw — is the OWNER's to run in a netplan session
    // (decision 47). It travels as text on `approval`, with `applied:false`
    // until the marker file the daemon never creates shows up.
    //
    // One block, one store: consolesLib.store() is memoised per DATA_DIR, so the
    // probe cache and the five-minute sweep are built once however often this is
    // called. Everything else about consoles lives in the lib, which is what
    // keeps this branch's footprint in this file to one require and one block.
    if (p === '/v1/consoles' || p.startsWith('/v1/consoles/')) {
      const consoles = consolesLib.store(DATA_DIR, { log });
      const CONSOLE_ID = '([a-z0-9][a-z0-9-]{0,23})';

      // The list, and the FEATURE PROBE both clients use — a 404 from an older
      // daemon hides the whole surface rather than showing a door that leads to
      // an error (the archive/scratchpads precedent).
      //
      // ⚠ IT DOES NOT AWAIT THE NETWORK. `refreshSoon` schedules a sweep when the
      // last one has aged out and returns immediately; this list is polled while
      // a view is open, and a route that waited for four probes would turn one
      // wedged listener into a slow app. Rows carry the last observation, and
      // `up:null` where there has never been one.
      if (req.method === 'GET' && p === '/v1/consoles') {
        consoles.refreshSoon();
        return sendJson(res, 200, {
          consoles: consoles.rows(),
          max: consolesLib.MAX_CONSOLES,
          kinds: consolesLib.KINDS,
          // Said once at the top as well as on every row: the probe ran HERE.
          reachableFrom: 'host',
          probeIntervalMs: consolesLib.PROBE_INTERVAL_MS,
          approval: consoles.approval(),
        });
      }

      if (req.method === 'POST' && p === '/v1/consoles') {
        const body = JSON.parse(await readBody(req, 16 * 1024) || '{}');
        const r = consoles.add(body);
        if (!r.ok) return sendErr(res, r.status || 400, r.error);
        log(`consoles: added ${r.console.id} (${r.console.url})`);
        return sendJson(res, 201, consolesLib.consoleRow(r.console, consoles.probeOf(r.console.id)));
      }

      const probeMatch = p.match(new RegExp(`^/v1/consoles/${CONSOLE_ID}/probe$`));
      if (probeMatch && req.method === 'POST') {
        // On demand, awaited, and still bounded by the same 2 s deadline — the
        // person tapping this is looking at a spinner, and an unbounded probe
        // here is a request that never comes back.
        const row = await consoles.probeNow(probeMatch[1]);
        if (!row) return sendErr(res, 404, 'no such console');
        return sendJson(res, 200, row);
      }

      const idMatch = p.match(new RegExp(`^/v1/consoles/${CONSOLE_ID}$`));
      if (idMatch) {
        const id = idMatch[1];
        if (req.method === 'PATCH') {
          const body = JSON.parse(await readBody(req, 16 * 1024) || '{}');
          const r = consoles.patch(id, body);
          // 409 CARRIES THE CURRENT ROW, not just a sentence: the editor that
          // collided needs to show what it collided WITH, which is the contract
          // saveScratchpad already knows how to adopt as an answer.
          if (!r.ok && r.status === 409) return sendJson(res, 409, { error: r.error, console: r.console });
          if (!r.ok) return sendErr(res, r.status || 400, r.error);
          return sendJson(res, 200, consolesLib.consoleRow(r.console, consoles.probeOf(id)));
        }
        if (req.method === 'DELETE') {
          const r = consoles.remove(id);
          if (!r.ok) return sendErr(res, r.status || 404, r.error);
          log(`consoles: removed ${id}`);
          return sendJson(res, 200, { ok: true });
        }
      }
      return sendErr(res, 404, 'no such consoles route');
    }

    // ---- scratchpads: the user's own pages, and nothing this host writes to
    if (req.method === 'GET' && p === '/v1/scratchpads') {
      // Main is minted HERE and not at startup, so listing is also what creates
      // the page every reference falls back to. This route is also the probe both
      // clients use to decide whether this daemon has the feature at all — a 404
      // from an older one hides every scratchpad control rather than showing a
      // door that leads to an error (the refreshRounds precedent).
      //
      // One directory scan, not two: this is polled every five seconds by an open
      // desktop.
      const pads = listPads();
      if (!pads.some(scratchpadsLib.isMain)) pads.push(ensureMain());
      return sendJson(res, 200, {
        pads: scratchpadsLib.sortPads(pads).map(scratchpadsLib.padRow),
      });
    }
    if (req.method === 'POST' && p === '/v1/scratchpads') {
      const body = JSON.parse(await readBody(req, SCRATCHPAD_BODY_MAX) || '{}');
      // ⚠ ensureMain BEFORE THE UNIQUENESS CHECK, the same one line the GET does
      // and for a sharper reason. Main is minted lazily by the first LIST, so on
      // an install where a client created a page before ever listing one, "Main"
      // was not taken — the create was allowed, and the ensureMain that ran on
      // the next list minted a SECOND page with that name. Two rows reading
      // "Main", one of them the fallback every unspecified reference resolves to
      // and no way to tell which from the picker.
      const pads = listPads();
      if (!pads.some(scratchpadsLib.isMain)) pads.push(ensureMain());
      if (pads.length >= scratchpadsLib.MAX_PADS) {
        return sendErr(res, 400, `that is the ${scratchpadsLib.MAX_PADS}-page limit — delete one first`);
      }
      const bad = scratchpadsLib.nameProblem(body.name, pads.map((p2) => p2.name));
      if (bad) return sendErr(res, 400, bad);
      const content = typeof body.content === 'string' ? body.content : '';
      const badContent = scratchpadsLib.contentProblem(content);
      if (badContent) return sendErr(res, 400, badContent);
      const now = Math.floor(Date.now() / 1000);
      return sendJson(res, 201, savePad({
        id: crypto.randomUUID(),
        name: scratchpadsLib.cleanName(body.name),
        content,
        main: false,
        createdAt: now,
        updatedAt: now,
        rev: 1,
      }));
    }
    if ((m = p.match(/^\/v1\/scratchpads\/([0-9a-f-]{36})$/))) {
      const padId = m[1];
      const pad = loadPad(padId);
      if (!pad) return sendErr(res, 404, 'no such scratchpad');

      if (req.method === 'GET') return sendJson(res, 200, pad);

      /**
       * The autosave. `rev` is what the editor last read, not a hint.
       *
       * ⚠ RE-READ AFTER THE AWAIT, and then compare — the same hazard the Round
       * PATCH documents, made routine here because two clients editing one page
       * IS the feature. A stale save is answered 409 with the CURRENT pad in the
       * body so the editor can adopt it and say so, rather than being told only
       * that it lost.
       */
      if (req.method === 'PATCH') {
        const body = JSON.parse(await readBody(req, SCRATCHPAD_BODY_MAX) || '{}');
        const current = loadPad(padId);
        if (!current) return sendErr(res, 404, 'no such scratchpad');
        const rev = Number(body.rev);
        if (!Number.isInteger(rev)) return sendErr(res, 400, 'rev is required — it is what makes a save safe');
        if (rev !== (Number(current.rev) || 0)) return sendJson(res, 409, current);

        let name = null;
        if (typeof body.name === 'string') {
          // Main's name is load-bearing: it is what a reference with no pad named
          // falls back to, and what every client calls that fallback.
          if (scratchpadsLib.isMain(current)) return sendErr(res, 400, 'the Main page cannot be renamed');
          const taken = listPads().filter((p2) => p2.id !== padId).map((p2) => p2.name);
          const bad = scratchpadsLib.nameProblem(body.name, taken);
          if (bad) return sendErr(res, 400, bad);
          name = scratchpadsLib.cleanName(body.name);
        }
        if (typeof body.content === 'string') {
          const bad = scratchpadsLib.contentProblem(body.content);
          if (bad) return sendErr(res, 400, bad);
        }
        const saved = updatePad(padId, (p2) => {
          if (name !== null) p2.name = name;
          if (typeof body.content === 'string') p2.content = body.content;
        });
        if (!saved) return sendErr(res, 404, 'no such scratchpad');
        return sendJson(res, 200, saved);
      }

      if (req.method === 'DELETE') {
        if (scratchpadsLib.isMain(pad)) return sendErr(res, 400, 'the Main page cannot be deleted');
        try { fs.unlinkSync(padPath(padId)); } catch { /* already gone */ }
        // Every rendered copy goes too, not one — there is a file per SEND now.
        // Leaving any of them would hand a session's Claude a readable path to a
        // page the owner has just deleted.
        unlinkRenderFiles(padId);
        return sendJson(res, 200, { ok: true });
      }
      return sendErr(res, 405, 'method not allowed');
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
        const served = devicesLib.canServe(r.device, Date.now(), deviceState);
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
        const verdict = devicesLib.canRun(dev, mode, Date.now(), deviceState);
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
        const typed = typeof body.text === 'string' ? body.text.trim() : '';
        if (!typed) return sendErr(res, 400, 'text required');
        if (typed.length > 100_000) return sendErr(res, 400, 'text too long');
        /**
         * The scratchpad the sender attached, composed HERE and now.
         *
         * ⚠ AT RECEIPT, NOT AT DELIVERY, and that matters most for the queued
         * path below. A reference resolved when the queue drains would quote
         * whatever the page said minutes later — after the sender had gone on
         * editing it — so the message the transcript shows and the message the
         * run received would be different text. Composing here makes the queue
         * entry a snapshot of what the person actually sent.
         *
         * Absent field = no reference at all. Attaching Main to every message
         * that never asked for one would put a page into conversations the owner
         * never meant it to reach.
         */
        let text = typed;
        if ('scratchpadId' in body && body.scratchpadId !== null) {
          const ref = padForReference(body.scratchpadId);
          if (ref.badRequest) return sendErr(res, 400, ref.badRequest);
          if (ref.error) return sendErr(res, 404, ref.error);
          text = scratchpadsLib.composeForChat(ref.pad, typed);
          const overflow = scratchpadsLib.fitProblem(text, 100_000);
          if (overflow) return sendErr(res, 413, overflow);
        }
        // ⚠ RE-CHECKED ON A FRESH READ. `meta` predates the readBody await, and a
        // Round can seal inside that window — settleRun runs whole in one turn, so
        // the only gap it can slip through is this request's own body arriving.
        // The stale check above then waves the message past the seal and
        // startRunAnywhere reopens a chat that just closed itself, re-running
        // finishRoundRun on whatever the new run says. Same stale-snapshot class
        // the queue path below documents; same cure: never trust a pre-await read
        // for a decision made after it.
        const live = loadMeta(id);
        if (!live) return sendErr(res, 404, 'no such chat');
        if (live.sealed) {
          return sendErr(res, 409, 'this run has finished and is kept for review — start a new chat to continue');
        }
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
        // `live`, not `meta`: the run must start from the state the seal check
        // just read, not the pre-await snapshot.
        const started = startRunAnywhere(live, text);
        if (started.error) return sendErr(res, started.code, started.error);
        // Auto-title from the first message, through updateMeta.
        //
        // This was the LAST stale-snapshot writer in this route, and it was found
        // by its damage rather than by reading: `meta` is loaded before the
        // readBody await AND before startRun, which writes to disk through
        // updateMeta — so saving this whole object put the pre-run meta back,
        // silently erasing the in-flight marker startRun had just recorded. The
        // interrupted-run test failed on a missing marker and this is why.
        if (!live.title) {
          const title = humanizeUserText(text).slice(0, 60);
          live.title = title;
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
            const served = devicesLib.canServe(r.device, Date.now(), deviceState);
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

/**
 * `node huginn-appd.js --seed-headroom-defaults`
 *
 * Run by deploy.sh after the install step. Writes `headroom-settings.json` if it
 * is not there — taking `defaultModel` from whatever `~/.claude/settings.json`
 * currently names and `accountSwitch` from the autoswitch state it replaces —
 * and exits 0. Idempotent: a second run says so and changes nothing, so a
 * re-deploy can never stomp the owner's edited thresholds.
 *
 * Before resolveBind(), because seeding must not need tailscaled, a port, or a
 * token — it is a file write and a print.
 */
if (process.argv.includes('--seed-headroom-defaults')) {
  const r = seedHeadroomDefaults();
  console.log(r.created
    ? `wrote ${HEADROOM_SETTINGS_FILE}`
    : `${HEADROOM_SETTINGS_FILE} already exists — left alone`);
  process.exit(0);
}

resolveBind().then(async (bind) => {
  // Recover from a previous crash BEFORE serving: a session left at `window-size
  // manual` by a killed daemon would otherwise keep a laptop's window shrunken
  // with nothing left to release it.
  await sweepStrandedSizes('startup');
  // Which conversations are archived, BEFORE anything can list a session or plan
  // a restore. The set is what keeps an archived session out of /v1/sessions, and
  // a daemon that learned it only on the next archive would spend its first
  // minutes reporting sessions somebody deliberately ended.
  refreshArchivedIds();
  // Bring back the tmux sessions a reboot/power-cut killed. A no-op when the tmux
  // server survived (an ordinary appd restart), so it is safe to run every start.
  // Before listen, like the sweep above, so the session list is whole by the time a
  // client can ask for it.
  await restoreSessionsAfterReboot().catch((e) => log(`session restore failed: ${e.message}`));
  // Keep the durable registry in step with live tmux: learn ids the hook writes
  // late, pick up sessions started outside the daemon, drop the ones that ended. The
  // early tick catches a just-restored session's fresh id; the interval carries it.
  // Projects ride the same clock: the same question (what is still live?) asked
  // of a second store, so a member whose session ended stops being counted and a
  // project whose lead is gone stops claiming to be running. It also picks up a
  // manifest the lead wrote while no client was looking — the GETs do it too,
  // but a proposal has to reach the owner's phone whether or not the app is open.
  setTimeout(() => { reconcileSessionRegistry().catch(() => { }); }, 15_000).unref();
  setTimeout(() => { reconcileProjects().catch((e) => log(`projects: reconcile failed: ${e.message}`)); }, 15_000).unref();
  setInterval(() => { reconcileSessionRegistry().catch(() => { }); }, 60_000).unref();
  setInterval(() => { reconcileProjects().catch((e) => log(`projects: reconcile failed: ${e.message}`)); }, 60_000).unref();
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
  // Settings before the first tick, so a fresh host runs on the owner's default
  // model and the migrated account-switch preference rather than on the
  // contract defaults for one pass.
  try { seedHeadroomDefaults(); } catch (e) { log('headroom: could not seed settings', e.message); }
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
