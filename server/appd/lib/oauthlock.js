'use strict';
// The lock Claude Code serialises its own token refreshes with, so this daemon
// can join the same queue instead of racing it.
//
// It is a DIRECTORY, not a file: `fs.mkdir` is the only filesystem primitive
// that is atomic-or-EEXIST across every platform the CLI ships on, and the CLI's
// `proper-lockfile` uses exactly that. A lock implemented as a file with O_EXCL
// would be a different lock, and two different locks protecting one file is the
// same as no lock at all.
//
// TWO paths are taken, in this order, because the CLI takes two:
//
//   1. <configDir>/.oauth_refresh.lock   — the current one
//   2. <realpath(configDir)>.lock        — the legacy one, i.e. ~/.claude.lock
//
// Failing on the second releases the first and reports contention, so we never
// sit holding half the lock while the CLI waits on the other half.
//
// WHY WE TAKE IT AT ALL. `accounts.activate()` installs a stored profile with a
// bare write+rename, and the refresher rotates a stored profile's token pair. If
// those interleave, the credentials file ends up one rotation behind: the CLI's
// first refresh then gets `invalid_grant` and — this is the documented CLI
// behaviour — blanks its own credentials. The owner is signed out for no reason
// they can see. The auto-switcher calls activate unattended every five minutes,
// so the window is real rather than theoretical.
//
// Liveness. A holder heartbeats the lock's mtime every `update` ms; a lock whose
// mtime has not moved in `stale` ms is assumed abandoned and stolen. The numbers
// are the CLI's (5 s / 60 s) and must stay the CLI's, or the two sides disagree
// about when a lock is dead. `onCompromised` fires when somebody steals OURS
// mid-flight, and its job is to abort the in-flight request rather than let a
// second refresher's write and ours both land.

const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = {
  /** Steal a lock whose holder has not heartbeated for this long. */
  stale: 60_000,
  /** Heartbeat interval. Must stay well under `stale`. */
  update: 5_000,
  /** Attempts before giving up, matching the CLI's own ladder. */
  attempts: 5,
  /** Backoff base; each wait is retryMs + random*retryMs, i.e. 1-2 s. */
  retryMs: 1000,
  /**
   * How long to watch a contended lock's mtime before deciding whether its
   * holder is alive. This is what separates "somebody else is refreshing right
   * now" from "somebody died holding it", and the two want different responses.
   */
  probeMs: 7500,
};

/**
 * Timings are overridable ONLY so the tests do not spend a quarter of a minute
 * proving a backoff ladder. Production passes nothing and gets the CLI's values.
 */
function tunables(opts = {}) {
  const env = process.env;
  const num = (v, d) => {
    const n = Number(v);
    return Number.isFinite(n) && n >= 0 ? n : d;
  };
  return {
    stale: opts.stale ?? num(env.HUGINN_APPD_OAUTH_LOCK_STALE_MS, DEFAULTS.stale),
    update: opts.update ?? num(env.HUGINN_APPD_OAUTH_LOCK_UPDATE_MS, DEFAULTS.update),
    attempts: opts.attempts ?? num(env.HUGINN_APPD_OAUTH_LOCK_ATTEMPTS, DEFAULTS.attempts),
    retryMs: opts.retryMs ?? num(env.HUGINN_APPD_OAUTH_LOCK_RETRY_MS, DEFAULTS.retryMs),
    probeMs: opts.probeMs ?? num(env.HUGINN_APPD_OAUTH_LOCK_PROBE_MS, DEFAULTS.probeMs),
  };
}

/**
 * The claude config dir this host's locks belong to.
 *
 * Injectable because every route test needs a credentials file of its own: a
 * suite that locked the real ~/.claude would block the owner's live CLI for as
 * long as it ran, which is a test reaching out and touching production.
 */
function configDirOf(explicit) {
  if (explicit) return explicit;
  if (process.env.HUGINN_APPD_CLAUDE_DIR) return process.env.HUGINN_APPD_CLAUDE_DIR;
  return path.join(require('node:os').homedir(), '.claude');
}

/** Both lock paths for a config dir, current first. */
function lockPaths(dir) {
  const d = configDirOf(dir).replace(/\/+$/, '');
  let real = d;
  // The legacy path is derived from the REAL directory, so a symlinked config
  // dir does not give us a second, private lock nobody else contends for.
  try { real = fs.realpathSync(d); } catch { /* not created yet: the literal path is right */ }
  return { current: path.join(d, '.oauth_refresh.lock'), legacy: `${real}.lock` };
}

function mtimeOf(p) {
  try { return fs.statSync(p).mtimeMs; } catch { return null; }
}

function touch(p) {
  const now = new Date();
  try { fs.utimesSync(p, now, now); return now.getTime(); } catch { return null; }
}

/**
 * One mkdir attempt, stealing the lock first if its holder has gone quiet.
 * @returns 'ok' | 'held' | 'error'
 */
function grab(p, t) {
  try {
    fs.mkdirSync(p, { recursive: false, mode: 0o700 });
    return 'ok';
  } catch (e) {
    if (e.code !== 'EEXIST') {
      // ENOENT means the config dir itself is missing, which on this host means
      // no CLI has ever run here. Create it and let the caller retry rather than
      // reporting a lock error for a directory we are allowed to make.
      if (e.code === 'ENOENT') {
        try { fs.mkdirSync(path.dirname(p), { recursive: true, mode: 0o700 }); } catch { return 'error'; }
        try { fs.mkdirSync(p, { recursive: false, mode: 0o700 }); return 'ok'; } catch { return 'held'; }
      }
      return 'error';
    }
    const age = Date.now() - (mtimeOf(p) ?? 0);
    if (age > t.stale) {
      // Abandoned. Removing and re-making it (rather than adopting it) keeps the
      // "I own this mtime" check below honest.
      try { fs.rmSync(p, { recursive: true, force: true }); } catch { return 'held'; }
      try { fs.mkdirSync(p, { recursive: false, mode: 0o700 }); return 'ok'; } catch { return 'held'; }
    }
    return 'held';
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Turns an exhausted retry ladder into the right word by watching the holder.
 *
 * `lock_busy` means somebody is genuinely refreshing and we should simply come
 * back later; `lock_timeout` means a process died holding the lock and somebody
 * should look. Same failure to the caller, very different thing to log.
 */
async function classifyHolder(p, t) {
  const before = mtimeOf(p);
  if (before === null) return 'lock_busy';        // released while we looked: not our problem
  await sleep(t.probeMs);
  const after = mtimeOf(p);
  if (after === null) return 'lock_busy';
  return after > before ? 'lock_busy' : 'lock_timeout';
}

/**
 * Builds the handle a holder gets back: a heartbeat, an abort signal wired to
 * `onCompromised`, and a release that is safe to call twice.
 */
function holder(paths, t, onCompromised) {
  const controller = new AbortController();
  const handle = {
    ok: true,
    paths,
    signal: controller.signal,
    compromised: false,
    release: null,
  };
  let mine = touch(paths.current) ?? Date.now();
  let timer = null;

  const stop = () => { if (timer) { clearInterval(timer); timer = null; } };

  const beat = () => {
    // Our own mtime is the ownership proof. If the directory is gone, or its
    // mtime is ahead of the last value WE wrote, somebody stole the lock and
    // anything still in flight under it must stop — two writers finishing a
    // refresh is exactly the interleaving the lock exists to prevent.
    const seen = mtimeOf(paths.current);
    if (seen === null || seen > mine + 1) {
      handle.compromised = true;
      stop();
      try { controller.abort(new Error('oauth refresh lock compromised')); } catch { /* aborted */ }
      if (typeof onCompromised === 'function') { try { onCompromised(); } catch { /* caller's problem */ } }
      return;
    }
    mine = touch(paths.current) ?? mine;
    touch(paths.legacy);
  };

  timer = setInterval(beat, t.update);
  if (typeof timer.unref === 'function') timer.unref();

  handle.release = () => {
    stop();
    // Ours are removed even when compromised: the thief re-made the directory,
    // so what is on disk is theirs, and leaving ours behind would only mean a
    // stale lock nobody steals for a minute.
    for (const p of [paths.legacy, paths.current]) {
      try { fs.rmSync(p, { recursive: true, force: true }); } catch { /* gone already */ }
    }
    return true;
  };
  handle.beat = beat;                              // exposed for the tests only
  return handle;
}

/**
 * ONE non-blocking attempt. Synchronous on purpose: it is what `activate()` uses,
 * and activate is called from a request handler that must not park the event
 * loop for the seven and a half seconds the async ladder can take. A busy lock
 * here is reported immediately and the caller decides what to do about it.
 */
function tryAcquire(dir, opts = {}) {
  const t = tunables(opts);
  const paths = lockPaths(dir);
  const first = grab(paths.current, t);
  if (first === 'held') return { ok: false, status: 'lock_busy', contended: 'current' };
  if (first === 'error') return { ok: false, status: 'lock_error', contended: 'current' };
  const second = grab(paths.legacy, t);
  if (second !== 'ok') {
    try { fs.rmSync(paths.current, { recursive: true, force: true }); } catch { /* best effort */ }
    return {
      ok: false,
      status: second === 'held' ? 'lock_busy' : 'lock_error',
      contended: 'legacy',
    };
  }
  return holder(paths, t, opts.onCompromised);
}

/**
 * The full ladder: up to `attempts` tries with 1-2 s of jitter between them,
 * then a verdict on the holder. Used by the refresher and by `performSwitch`,
 * both of which can afford to wait and would rather wait than fail.
 */
async function acquire(dir, opts = {}) {
  const t = tunables(opts);
  const paths = lockPaths(dir);
  let last = { ok: false, status: 'lock_busy', contended: 'current' };
  for (let i = 0; i < Math.max(1, t.attempts); i++) {
    if (i > 0) await sleep(t.retryMs + Math.random() * t.retryMs);
    const h = tryAcquire(dir, opts);
    if (h.ok) return h;
    last = h;
    if (h.status === 'lock_error') return h;
  }
  const which = last.contended === 'legacy' ? paths.legacy : paths.current;
  return { ok: false, status: await classifyHolder(which, t), contended: last.contended };
}

module.exports = { acquire, tryAcquire, lockPaths, configDirOf, DEFAULTS, tunables };
