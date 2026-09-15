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

function secondsOf(ms) {
  return Math.floor(ms / 1000);
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
  if (existing) return { name, since: existing.since, reason: existing.reason, created: false };
  const since = secondsOf(nowMs);
  const body = `${JSON.stringify({ reason: String(reason == null ? '' : reason), since })}\n`;
  writeAtomic(file, body);
  return { name, since, reason: String(reason == null ? '' : reason), created: true };
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
  let since = secondsOf(st.mtimeMs);
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (parsed && typeof parsed === 'object') {
      if (typeof parsed.reason === 'string') reason = parsed.reason;
      if (Number.isFinite(parsed.since) && parsed.since > 0) since = Math.floor(parsed.since);
    }
  } catch {
    // Armed by hand with `touch`, or half-written. Still armed — mtime is the
    // arming time and that is the whole reason the file carries one.
  }
  return { since, reason };
}

/** { STOP: {since, reason} | null, 'STOP-FABLE': {since, reason} | null } */
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
    const since = Number.isFinite(rec.since) && rec.since > 0
      ? Math.floor(rec.since)
      : secondsOf(st.mtimeMs);
    if (nowMs - since * 1000 > HELD_STALE_MS) continue;
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
  arm,
  clear,
  state,
  writeFableSessions,
  listHeld,
};
