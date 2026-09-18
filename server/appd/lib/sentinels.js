'use strict';
// The headroom sentinels — the small directory the daemon and the hook gate talk
// through (`DATA_DIR/headroom`, 0700).
//
//   STOP            armed when the account is out of room: EVERY spawn waits.
//   STOP-FABLE      armed when the Fable weekly window is out of room: only the
//                   sessions listed in `fable-sessions` wait.
//   fable-sessions  one Claude session id per line, rewritten each tick. The
//                   SubagentStart payload carries no model, so this list is how
//                   a bash hook with no daemon access answers "is the session
//                   that is spawning on Fable?".
//   held/<id>       written by the gate WHILE it holds a spawn, removed on
//                   release. Reading this directory is how /v1/headroom can say
//                   what is waiting.
//   gate.log        the gate's own append-only log (rotated by the gate).
//
// Everything here is pure-ish: filesystem in, plain objects out, no daemon
// state, no timers. The tick calls arm/clear/writeFableSessions; the routes call
// state/listHeld.
//
// A sentinel FILE is the contract, not a field in headroom.json: the gate must
// be able to answer with one `test -e` and no parsing, and the operator must be
// able to arm one by hand with `touch` when the daemon itself is the problem.
// Content is a single JSON line for humans (and for `reason`); an unparseable or
// empty file is still ARMED — existence is the signal, the body is commentary.

const fs = require('node:fs');
const path = require('node:path');

const NAMES = ['STOP', 'STOP-FABLE'];
// A held row older than this is the gate having died without its trap running
// (SIGKILL on the CLI's own hook timeout is the way that happens). Reporting it
// as still waiting would tell the operator a spawn is pinned that is in fact
// running — worse than forgetting it, because it is unfalsifiable from the UI.
const HELD_STALE_MS = 2 * 60 * 60 * 1000;

function assertName(name) {
  if (!NAMES.includes(name)) throw new Error(`unknown sentinel: ${name}`);
  return name;
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
}

function writeAtomic(file, body, mode = 0o600) {
  fs.writeFileSync(`${file}.tmp`, body, { mode });
  fs.renameSync(`${file}.tmp`, file);
}

/**
 * A stored epoch, in MILLISECONDS.
 *
 * ⚠ The whole headroom payload is milliseconds (lib/headroom.js's banner), and
 * `since` used to be the one seconds field in it — beside `resets[].at`,
 * `ladder.at` and `arbiter.last*At`, all ms, with no field-name tell. Older
 * files (and the bash gate's held rows, which are written with `date +%s`) are
 * normalised on READ rather than migrated: anything below the year-2001 line is
 * seconds.
 */
function msOf(v) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return null;
  return n < 1e11 ? Math.floor(n * 1000) : Math.floor(n);
}

/**
 * Arm a sentinel. Idempotent BY DESIGN: an already-armed sentinel keeps its
 * original `since` and reason, because the tick re-asserts its plan every pass
 * and a `since` that resets every 30 s is a number no hysteresis can use and no
 * operator can read ("armed 4 seconds ago" for a stop that has held all night).
 * Returns { name, since, reason, created }.
 */
function arm(dir, name, reason, nowMs = Date.now()) {
  assertName(name);
  ensureDir(dir);
  const file = path.join(dir, name);
  const existing = readSentinel(file);
  if (existing) {
    return { name, since: existing.since, reason: existing.reason, by: existing.by, created: false };
  }
  const since = Math.floor(nowMs);
  // ⚠ `by` IS WHAT MAKES THE ESCAPE HATCH REAL (#12). The tick has to keep
  // reclaiming a sentinel a CRASHED daemon left armed — that is what the
  // else-branch in writeSentinels is for — but it was reaping the operator's
  // hand-`touch`ed STOP with it, because the two are indistinguishable from
  // outside. A file this daemon wrote says so; a file somebody touched cannot,
  // and that asymmetry is exactly the signal. Measured lifetimes for a
  // hand-armed hold before this: 299 s idle, 23 ms after a settings PATCH.
  const body = `${JSON.stringify({ reason: String(reason == null ? '' : reason), since, by: 'appd' })}\n`;
  writeAtomic(file, body);
  return { name, since, reason: String(reason == null ? '' : reason), by: 'appd', created: true };
}

/**
 * The HEARTBEAT on an armed sentinel — its mtime, and nothing else.
 *
 * An armed sentinel has no expiry of its own: `arm` is deliberately idempotent
 * and does not move `since`, so there was nothing to age against. A daemon that
 * died while STOP was armed therefore wedged every Agent/Workflow spawn until
 * the CLI's own hook timeout — half an hour of sessions that merely look hung,
 * with no symptom anybody can read. So the tick touches what it is asserting,
 * and the gate treats a sentinel nobody has touched for HUGINN_GATE_STALE_S as
 * abandoned and lets the spawn through.
 *
 * ⚠ mtime, never the BODY. `since` is the arming time and the operator reads it;
 * rewriting the file each tick would make "armed 4 seconds ago" the permanent
 * answer for a stop that has held all night.
 *
 * @returns true when there was a sentinel to touch.
 */
function touch(dir, name, nowMs = Date.now()) {
  assertName(name);
  const file = path.join(dir, name);
  try {
    const t = new Date(nowMs);
    fs.utimesSync(file, t, t);
    return true;
  } catch {
    return false;                    // not armed, or gone between the two calls
  }
}

/** Disarm a sentinel. Returns true when one was actually there. */
function clear(dir, name) {
  assertName(name);
  const file = path.join(dir, name);
  try {
    fs.unlinkSync(file);
    return true;
  } catch {
    return false;
  }
}

function readSentinel(file) {
  let st;
  try {
    st = fs.statSync(file);
  } catch {
    return null;
  }
  let reason = '';
  let by = null;
  let since = Math.floor(st.mtimeMs);
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (parsed && typeof parsed === 'object') {
      if (typeof parsed.reason === 'string') reason = parsed.reason;
      if (typeof parsed.by === 'string' && parsed.by) by = parsed.by;
      const stored = msOf(parsed.since);
      if (stored !== null) since = stored;
    }
  } catch {
    // Armed by hand with `touch`, or half-written. Still armed — mtime is the
    // arming time and that is the whole reason the file carries one.
  }
  // `by` stays null for a hand-armed file AND for one an older daemon wrote.
  // Both are "not mine to reap", which is the safe side of that ambiguity: the
  // worst case is a stale sentinel a human can delete, against the old worst
  // case of a fleet-wide pause evaporating inside one tick.
  return { since, reason, by };
}

/** { STOP: {since, reason, by} | null, 'STOP-FABLE': {since, reason, by} | null } */
function state(dir) {
  const out = {};
  for (const name of NAMES) out[name] = readSentinel(path.join(dir, name));
  return out;
}

/**
 * Rewrite `fable-sessions`. Atomic because the gate reads it without any lock:
 * a truncated read is a Fable session that slips the gate, which is the exact
 * spawn the sentinel exists to hold.
 */
function writeFableSessions(dir, ids) {
  ensureDir(dir);
  const seen = new Set();
  const clean = [];
  for (const raw of ids || []) {
    if (typeof raw !== 'string') continue;
    const id = raw.trim();
    // The gate matches whole lines with sed; anything with whitespace in it
    // could never match, and a stray newline would forge extra entries.
    if (!id || /[^A-Za-z0-9_-]/.test(id)) continue;
    if (seen.has(id)) continue;
    seen.add(id);
    clean.push(id);
  }
  const body = clean.length ? `${clean.join('\n')}\n` : '';
  writeAtomic(path.join(dir, 'fable-sessions'), body);
  return clean;
}

/**
 * What the gate is currently holding: [{ agentId, agentType, sessionId, since }].
 * The FILENAME is the id — the body is what the gate knew about it. Unparseable
 * and stale rows are dropped rather than rendered half-empty.
 */
function listHeld(dir, nowMs = Date.now()) {
  let names;
  try {
    names = fs.readdirSync(path.join(dir, 'held'));
  } catch {
    return [];
  }
  const out = [];
  for (const name of names) {
    if (name.endsWith('.tmp')) continue; // a write in flight, not a hold
    const file = path.join(dir, 'held', name);
    let body;
    let st;
    try {
      st = fs.statSync(file);
      if (!st.isFile()) continue;
      body = fs.readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    let rec;
    try {
      rec = JSON.parse(body);
    } catch {
      continue;
    }
    if (!rec || typeof rec !== 'object') continue;
    // The gate is bash and writes `date +%s`; msOf normalises that to the
    // milliseconds the rest of the payload speaks.
    const since = msOf(rec.since) ?? Math.floor(st.mtimeMs);
    if (nowMs - since > HELD_STALE_MS) continue;
    out.push({
      agentId: name,
      agentType: typeof rec.agent_type === 'string' ? rec.agent_type : '',
      sessionId: typeof rec.session_id === 'string' ? rec.session_id : '',
      since,
    });
  }
  out.sort((a, b) => a.since - b.since || a.agentId.localeCompare(b.agentId));
  return out;
}

module.exports = {
  NAMES,
  HELD_STALE_MS,
  msOf,
  arm,
  touch,
  clear,
  state,
  writeFableSessions,
  listHeld,
};
