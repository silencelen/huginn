'use strict';
// start_session requests: Claude asks for a new session, the owner approves it
// on a card. The file registry both halves share.
//
// The asking side is huginn's MCP server (mcp/huginn-mcp.js), running inside a
// tmux session's Claude Code or inside a chat's `claude -p`. Its `start_session`
// tool PARKS a request here and blocks until the daemon answers it or it
// expires; the daemon's request registry (lib/questions.js) lists the parked
// file as a question of kind `session` on the asker's card, and the card's
// answer route either creates the session (huginn-appd.js createSession) and
// queues the opening prompt, or declines. The tool then returns exactly what the
// daemon wrote — `{started: true, name, cwd}` or `{started: false, reason}` — so
// Claude is never told a session exists unless the daemon made one.
//
// Layout, beside the permission requests (lib/permhook.js `.perm/<session>/`):
//
//   <stateDir>/.start/session/<tmux session>/<id>.json   a session's request
//   <stateDir>/.start/chat/<chat id>/<id>.json            a chat's request
//
// Hand-off: the daemon writes `<id>.ans` (tmp + rename), THEN unlinks the
// request, so a poller that sees the request gone looks for the answer once
// more before calling it withdrawn. The tool claims the answer by renaming it
// to `<id>.taken`, reads it and removes it. A request is live while its file
// exists, its process is alive and it is younger than EXPIRE_MS; past that the
// registry names nothing and the tool, which keeps its own clock, returns
// `{started: false, reason: "expired"}` and removes its file.
//
// Security: the prompt and directory travel verbatim to the card and nowhere
// else until the owner approves; the daemon re-checks the directory with the
// create route's own rule; a request carries who asked (`requester`) so the card
// can say so. Nothing here types, spawns or sends.

const fs = require('fs');
const path = require('path');

const START_DIRNAME = '.start';
const EXPIRE_MS = 60 * 60 * 1000;             // one hour: the tool's own deadline
const ID_RE = /^[0-9]{6,25}-[0-9]{1,10}$/;      // `<ms+seq>-<pid>`, like a permission request's
const PROMPT_MAX = 20_000;
const DIR_MAX = 4096;
const NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_-]{0,49}$/;   // the create route's grammar (huginn-appd NAME_RE)
const SESSION_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}$/; // a tmux name as the routes accept it
const CHAT_ID_RE = /^[0-9a-f-]{36}$/;
/** The tool's full name as Claude Code reports it: server `huginn`, tool `start_session`. */
const START_SESSION_TOOL = 'mcp__huginn__start_session';

const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v);

/** A requester is `{kind: "session", id: <tmux name>}` or `{kind: "chat", id: <chat id>}`. */
function validRequester(r) {
  if (!isObj(r) || typeof r.id !== 'string') return false;
  if (r.kind === 'session') return SESSION_RE.test(r.id);
  if (r.kind === 'chat') return CHAT_ID_RE.test(r.id);
  return false;
}

function requestDir(stateDir, requester) {
  if (!validRequester(requester)) throw new Error('invalid requester');
  return path.join(stateDir, START_DIRNAME, requester.kind, requester.id);
}

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

/**
 * Why a tool call's arguments cannot be parked, as a sentence, or null.
 * Light checks only — the directory's existence and the name's availability are
 * the daemon's to judge when the owner approves — but a shape that could never
 * be approved is refused here so the card is never shown nonsense.
 */
function argsProblem(args) {
  if (!isObj(args)) return 'arguments must be an object';
  const { directory, prompt, name } = args;
  if (typeof directory !== 'string' || !directory.trim()) return 'directory is required';
  if (directory.length > DIR_MAX || directory.includes('\0')) return 'directory is not a path';
  if (!path.isAbsolute(directory)) return 'directory must be an absolute path on this server';
  if (typeof prompt !== 'string' || !prompt.trim()) return 'prompt is required: what the new session should start on';
  if (prompt.length > PROMPT_MAX) return `prompt is at most ${PROMPT_MAX} characters`;
  if (name !== undefined && name !== null) {
    if (typeof name !== 'string' || !name.trim()) return 'name, when given, is a session name';
    if (name.includes('.')) return 'a session name cannot contain a "."';
    if (!NAME_RE.test(name.trim())) return 'name: letters, digits, underscore and dash, up to 50 characters';
  }
  return null;
}

let seq = 0;
function mintId(now = Date.now(), pid = process.pid) {
  seq = (seq + 1) % 1000;
  return `${now}${String(seq).padStart(3, '0')}-${pid}`;
}

/**
 * Park a request. Returns the record written, or throws with the reason.
 * @param requester  who is asking (validRequester)
 * @param args       {directory, prompt, name?} — argsProblem-checked
 */
function park(stateDir, requester, args, { pid = process.pid, now = Date.now() } = {}) {
  const bad = argsProblem(args);
  if (bad) throw new Error(bad);
  const dir = requestDir(stateDir, requester);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const rec = {
    v: 1,
    id: mintId(now, pid),
    pid,
    ts: now,
    expiresAt: now + EXPIRE_MS,
    kind: 'session',
    requester: { kind: requester.kind, id: requester.id },
    directory: path.resolve(args.directory),
    prompt: args.prompt,
    name: typeof args.name === 'string' && args.name.trim() ? args.name.trim().toLowerCase() : null,
  };
  const file = path.join(dir, `${rec.id}.json`);
  const tmp = path.join(dir, `.${rec.id}.tmp`);
  fs.writeFileSync(tmp, JSON.stringify(rec), { mode: 0o600 });
  fs.renameSync(tmp, file);
  return rec;
}

function readRequest(dir, id) {
  let r;
  try { r = JSON.parse(fs.readFileSync(path.join(dir, `${id}.json`), 'utf8')); } catch { return null; }
  if (!isObj(r) || r.v !== 1 || r.id !== id || r.kind !== 'session') return null;
  if (!Number.isInteger(r.pid) || !Number.isFinite(r.ts)) return null;
  if (typeof r.directory !== 'string' || typeof r.prompt !== 'string') return null;
  if (r.name !== null && typeof r.name !== 'string') return null;
  if (!validRequester(r.requester)) return null;
  return r;
}

/**
 * The live requests of one requester, oldest first. Never throws. A request is
 * live while its file exists, its process is alive and it has not expired —
 * so an hour after it was parked the registry names nothing of it, whatever
 * its asker did.
 */
function liveRequests(stateDir, requester, { alive = pidAlive, now = Date.now() } = {}) {
  let dir;
  try { dir = requestDir(stateDir, requester); } catch { return []; }
  let files;
  try { files = fs.readdirSync(dir); } catch { return []; }
  const out = [];
  for (const f of files) {
    if (!f.endsWith('.json')) continue;
    const id = f.slice(0, -5);
    if (!ID_RE.test(id)) continue;
    const r = readRequest(dir, id);
    if (!r) continue;
    if (now - r.ts > EXPIRE_MS || r.ts - now > 60_000) continue;
    if (r.requester.kind !== requester.kind || r.requester.id !== requester.id) continue;
    if (!alive(r.pid)) continue;
    out.push(r);
  }
  return out.sort((a, b) => a.ts - b.ts);
}

/**
 * Answer a request: the result the tool will return, exactly. `{started: true,
 * name, cwd}` after the daemon made the session, `{started: false, reason}`
 * otherwise. The answer lands first, then the request goes, so the card drops
 * it at once and the poller cannot miss the answer. True when written.
 */
function answer(stateDir, requester, id, result) {
  if (!ID_RE.test(String(id))) return false;
  if (!isObj(result) || typeof result.started !== 'boolean') return false;
  if (result.started && (typeof result.name !== 'string' || typeof result.cwd !== 'string')) return false;
  if (!result.started && typeof result.reason !== 'string') return false;
  let dir;
  try { dir = requestDir(stateDir, requester); } catch { return false; }
  const ans = path.join(dir, `${id}.ans`);
  const tmp = `${ans}.tmp`;
  try {
    fs.writeFileSync(tmp, JSON.stringify(result), { mode: 0o600 });
    fs.renameSync(tmp, ans);
  } catch {
    try { fs.unlinkSync(tmp); } catch { /* never written */ }
    return false;
  }
  try { fs.unlinkSync(path.join(dir, `${id}.json`)); } catch { /* already gone */ }
  return true;
}

/** Remove whatever is left of a request (the asker's side: expiry, cancel, exit). */
function withdraw(stateDir, requester, id) {
  let dir;
  try { dir = requestDir(stateDir, requester); } catch { return; }
  for (const ext of ['json', 'ans', 'taken', 'ans.tmp']) {
    try { fs.unlinkSync(path.join(dir, `${id}.${ext}`)); } catch { /* fine */ }
  }
  try { fs.unlinkSync(path.join(dir, `.${id}.tmp`)); } catch { /* fine */ }
}

/** Claim the answer if one is there: rename, read, remove. The result or null. */
function claimAnswer(stateDir, requester, id) {
  let dir;
  try { dir = requestDir(stateDir, requester); } catch { return null; }
  const ans = path.join(dir, `${id}.ans`);
  const taken = path.join(dir, `${id}.taken`);
  try { fs.renameSync(ans, taken); } catch { return null; }
  let result = null;
  try { result = JSON.parse(fs.readFileSync(taken, 'utf8')); } catch { result = null; }
  try { fs.unlinkSync(taken); } catch { /* fine */ }
  if (!isObj(result) || typeof result.started !== 'boolean') {
    return { started: false, reason: 'the answer could not be read' };
  }
  return result;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The asker's wait: poll for the answer until it comes, the request is gone, or
 * the deadline passes. Resolves to the result the daemon wrote, or to
 * `{started: false, reason: "expired" | "withdrawn" | "cancelled"}`. Never
 * resolves to `started: true` on its own authority.
 *
 * @param opts.deadlineAt  ms epoch; default the record's expiry
 * @param opts.pollMs      250 by default (the permission hook's pace)
 * @param opts.cancelled   () => boolean — the MCP client cancelled the call
 */
async function awaitAnswer(stateDir, requester, rec, { deadlineAt = null, pollMs = 250, cancelled = () => false, now = Date.now } = {}) {
  const by = deadlineAt != null ? deadlineAt : (Number(rec.expiresAt) || (rec.ts + EXPIRE_MS));
  let dir;
  try { dir = requestDir(stateDir, requester); } catch { return { started: false, reason: 'withdrawn' }; }
  const reqFile = path.join(dir, `${rec.id}.json`);
  for (;;) {
    const got = claimAnswer(stateDir, requester, rec.id);
    if (got) return got;
    if (cancelled()) { withdraw(stateDir, requester, rec.id); return { started: false, reason: 'cancelled' }; }
    if (!fs.existsSync(reqFile)) {
      // The daemon writes the answer BEFORE removing the request; one more look.
      await wait(Math.min(pollMs, 100));
      const late = claimAnswer(stateDir, requester, rec.id);
      if (late) return late;
      return { started: false, reason: 'withdrawn' };
    }
    if (now() >= by) { withdraw(stateDir, requester, rec.id); return { started: false, reason: 'expired' }; }
    await wait(pollMs);
  }
}

/** Remove expired requests of every requester (the daemon's housekeeping). Never throws. */
function sweepExpired(stateDir, { now = Date.now() } = {}) {
  const root = path.join(stateDir, START_DIRNAME);
  let removed = 0;
  for (const kind of ['session', 'chat']) {
    let ids;
    try { ids = fs.readdirSync(path.join(root, kind)); } catch { continue; }
    for (const id of ids) {
      const dir = path.join(root, kind, id);
      let files;
      try { files = fs.readdirSync(dir); } catch { continue; }
      for (const f of files) {
        if (!f.endsWith('.json')) continue;
        const r = readRequest(dir, f.slice(0, -5));
        if (r && now - r.ts <= EXPIRE_MS) continue;
        try { fs.unlinkSync(path.join(dir, f)); removed++; } catch { /* fine */ }
      }
    }
  }
  return removed;
}

module.exports = {
  START_DIRNAME, START_SESSION_TOOL, EXPIRE_MS, ID_RE, PROMPT_MAX,
  validRequester, requestDir, argsProblem, park, liveRequests, answer, withdraw, claimAnswer,
  awaitAnswer, sweepExpired,
};
