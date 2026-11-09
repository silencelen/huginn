'use strict';
/**
 * What a session's state file WOULD say when there is none (2026-11-09).
 *
 * The title hook writes `/run/huginn-claude-state/<session>` on every event, and
 * that file is the only link from a tmux session to its Claude session id and
 * transcript. A session with no file reads `state: null`, `claudeSessionId:
 * null`, `hasTranscript: false` on every row, and the restart sweep rightly
 * never cycles it: it cannot resume what it cannot name.
 *
 * ⚠ THE CASE THIS EXISTS FOR. Eight of 28 live sessions had no file on 11-09:
 * project members relaunched by the 10-27 reboot restore, whose last hook event
 * was 10-28 (or the launch itself, for a resumed conversation nobody spoke to
 * since). An idle session fires no hook, so their files aged past the state
 * sweep's week; the pre-3.17.3 sweep then took every aged file whose session
 * was missing from a listing that came back empty on a loaded host (the 11-06
 * mass-archive incident), and no event has happened since to write them back.
 * Yet the id was in plain sight: the pane's `claude --resume <uuid>`, the
 * project record, the registry row the hook had taught, the transcript itself.
 *
 * This module is the pure half: which argv word names the id, which candidate
 * to take, when the newest transcript in a directory may stand for a session,
 * and the record the daemon seeds. The daemon owns /proc, the fs and the cache.
 *
 * ⚠ AN ID COUNTS ONLY WITH ITS TRANSCRIPT ON DISK. `--resume <id>` is what the
 * process was TOLD, not what it is: one lead was relaunched with `--resume` of
 * an id whose transcript did not exist, and Claude Code started a fresh
 * conversation under a new id — the one the hook then wrote and the registry
 * kept. The argv id was a dead pointer; the registry's had the transcript.
 */

// The shape findTranscriptFile and lib/session-registry gate on. It is also
// what makes an id safe in a shell string: nothing but [0-9a-f-] gets through.
const UUID_RE = /^[0-9a-f-]{36}$/;

/**
 * The Claude session id a `claude` argv names: `--resume <id>`, `--resume=<id>`,
 * `-r <id>`, `--session-id <id>`, `--session-id=<id>`. The LAST one wins, as the
 * CLI takes the last of a repeated flag. `--resume` takes its value optionally
 * (a bare `--resume` opens the picker), so a following flag is not a value; a
 * value that is not a uuid is not an id (the picker's search text, a typo).
 * `args` are the flags after the program (lib/runtime claudeArgs), or a whole
 * argv — argv[0] is never a flag, so either works.
 */
function idFromArgs(args) {
  const list = Array.isArray(args) ? args.map(String) : [];
  let found = null;
  for (let i = 0; i < list.length; i++) {
    const a = list[i];
    let v = null;
    if (a === '--resume' || a === '-r' || a === '--session-id') {
      if (i + 1 < list.length && !list[i + 1].startsWith('-')) v = list[++i];
    } else if (a.startsWith('--resume=')) v = a.slice('--resume='.length);
    else if (a.startsWith('--session-id=')) v = a.slice('--session-id='.length);
    else continue;
    if (v && UUID_RE.test(v)) found = v;
  }
  return found;
}

/**
 * The directory names Claude Code may keep a cwd's transcripts under: every
 * non-alphanumeric character becomes `-` (`/root/.claude` → `-root--claude`),
 * and the older spelling that only replaced the slashes. Both are looked in.
 */
function projectSlugs(cwd) {
  const c = String(cwd || '');
  if (!c) return [];
  const out = [c.replace(/[^A-Za-z0-9]/g, '-'), c.replace(/\//g, '-')];
  return [...new Set(out)];
}

/**
 * The first candidate that is a uuid AND has a transcript on disk, in the order
 * given — the process's own argv, then the registry row (what the daemon
 * launched it with, or learned from the hook), then the project record, then
 * the transcript directory. `{id, source}`, or null when none qualifies.
 *
 * `hasTranscript(id)` is the fs question, asked here so the module stays pure.
 * `claimed` are the ids other live sessions already hold; a candidate from any
 * source but the process's own argv is skipped when another session holds it,
 * because binding it here would put a second Claude on one conversation.
 */
function pickId(candidates, { hasTranscript, claimed = new Set() } = {}) {
  for (const c of candidates || []) {
    if (!c || !c.id || !UUID_RE.test(c.id)) continue;
    if (c.source !== 'argv' && claimed.has(c.id)) continue;
    if (typeof hasTranscript === 'function' && !hasTranscript(c.id)) continue;
    return { id: c.id, source: c.source || 'unknown' };
  }
  return null;
}

/**
 * The one transcript in a cwd's directory that can be this session's, or null.
 *
 * A session launched without `--resume` mints its id at start and writes its
 * transcript at its first turn — so the file is newer than the tmux session
 * (`bornMs`, from `#{session_created}`), and nobody else holds its id. Of the
 * files that pass both, exactly ONE may stand for the session: two unclaimed
 * transcripts newer than its birth cannot be told apart from here, and the
 * hook's own rule for a job it cannot place applies — none, or more than one,
 * is not ours to choose. With no birth time known nothing is taken either: the
 * newest file in `/root/netplan` is almost never this session's.
 *
 * `files` are `{id, mtimeMs}`; `claimed` the ids other live sessions hold.
 */
function uniqueNewest(files, { bornMs = null, claimed = new Set() } = {}) {
  const born = Number(bornMs);
  if (!Number.isFinite(born) || born <= 0) return null;
  const fit = (files || []).filter((f) => f && UUID_RE.test(String(f.id || ''))
    && Number(f.mtimeMs) >= born && !claimed.has(f.id));
  if (fit.length !== 1) return null;
  return fit[0].id;
}

/**
 * The state word the transcript's tail gives (lib/typing boundaryFromTail): the
 * liveness authority the send gate already trusts over the pane. `unknown` —
 * no conversational record in the window read — is no word at all, never
 * `running` by default: a seeded file is read by every gate, and a false
 * `running` would hold every automated send to the session.
 */
function livenessWord(boundary) {
  if (!boundary || boundary.unknown) return null;
  return boundary.idle ? 'idle' : 'running';
}

/**
 * The record the daemon seeds in the hook's place: the hook's own five fields
 * (lib/typing stateStampMs reads `ts` as milliseconds), plus `seeded: true`
 * and where the id came from, so a reader of the file can tell it from one the
 * hook wrote. The hook's next event overwrites it whole, marks and all.
 */
function seedRecord({ state, sessionId, transcript, cwd, source, nowMs = Date.now() }) {
  return {
    state: state || null,
    sessionId: sessionId || null,
    transcript: transcript || null,
    cwd: cwd || null,
    ts: Math.floor(Number(nowMs) || Date.now()),
    seeded: true,
    source: source || 'unknown',
  };
}

module.exports = { UUID_RE, idFromArgs, projectSlugs, pickId, uniqueNewest, livenessWord, seedRecord };
