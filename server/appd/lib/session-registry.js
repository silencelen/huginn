'use strict';
// A durable list of the tmux sessions worth bringing back after the box reboots.
//
// tmux keeps a session alive only as long as its server process lives, and the
// server lives in its own systemd scope (see ensureTmuxServerScope in the daemon),
// so an ordinary `systemctl restart huginn-appd` leaves every session untouched.
// A POWER CUT or a host reboot is different: the server dies with everything else,
// and Claude Code's own transcripts are the only thing left — a conversation you
// could `claude --resume` if you still knew its session id and which tmux session
// it belonged to. This registry is that missing map, written to DATA_DIR (which
// survives the reboot) so the daemon can recreate each session on the way back up.
//
// The per-session STATE files the title hook writes cannot serve this: they live
// under /run, which is tmpfs and is wiped by the very reboot we exist for. So the
// id is copied into DATA_DIR here while the session is alive, and read back from
// DATA_DIR after the reboot.
//
// This module is the pure decision half — merge, prune, plan, command. The daemon
// owns the fs and tmux I/O around it, and unit tests drive these directly.

// The auth flow's throwaway session (server sign-in). It is never a conversation
// worth resuming and recreating it would relaunch a login prompt nobody asked for.
const RESERVED = new Set(['login']);

// A Claude Code session id is a v4-shaped uuid; findTranscriptFile gates on the
// same shape. Validating it here is also what makes it safe to drop into the shell
// string tmux runs — nothing but [0-9a-f-] can reach the command line.
const UUID_RE = /^[0-9a-f-]{36}$/;

function isReserved(name) { return RESERVED.has(name); }

/**
 * Folds the live tmux sessions into the stored registry: records ones not seen
 * before, fills in a claudeSessionId/cwd once the title hook has written it, and
 * moves updatedAt. Sessions created outside the daemon (`cc`, the interactive
 * launcher) are picked up here rather than at a create route they never hit.
 *
 * Deliberately ADD-ONLY. Dropping ended sessions is pruneDead's job and a separate
 * step, because the restore path has to read the registry at a moment when NOTHING
 * is live yet — folding "not live -> drop" into the merge would erase everything a
 * reboot is supposed to bring back.
 *
 * An id/cwd, once known, is sticky: a later merge whose state read came back empty
 * (the /run file is gone, or the hook has not rewritten it yet) keeps the value it
 * had rather than blanking it.
 */
function mergeLive(registry, live, now) {
  const next = { ...registry };
  for (const s of live || []) {
    if (!s || !s.name || isReserved(s.name)) continue;
    const prev = next[s.name];
    next[s.name] = {
      name: s.name,
      createdAt: (prev && prev.createdAt) || s.createdAt || now,
      claudeSessionId: s.claudeSessionId || (prev && prev.claudeSessionId) || null,
      cwd: s.cwd || (prev && prev.cwd) || null,
      updatedAt: now,
    };
  }
  return next;
}

/**
 * Drops the entries whose session is no longer live — the ones that ended without
 * going through the kill path (a bare `exit`, a closed `cc` window). Returns the
 * trimmed registry and the names removed, for a log line.
 *
 * ⚠ ONLY call this against a CLEAN tmux listing. A failed `list-sessions` must
 * never reach here: read as "nothing is live" it would empty the registry in one
 * tick, and the next reboot would then restore nothing. The daemon skips the whole
 * reconcile when the listing errors — the same failure-to-observe guard listSessions
 * carries, learned the same way.
 */
function pruneDead(registry, liveNames) {
  const set = liveNames instanceof Set ? liveNames : new Set(liveNames || []);
  const next = {};
  const removed = [];
  for (const [name, entry] of Object.entries(registry || {})) {
    if (set.has(name)) next[name] = entry;
    else removed.push(name);
  }
  return { next, removed };
}

/**
 * The sessions to recreate at boot: everything recorded that is not already live
 * and not reserved, oldest first so the list comes back in roughly the order it
 * grew.
 *
 * `liveNames` is the crux of not double-creating. On a cold boot the tmux server
 * is gone and the set is empty, so every recorded session is planned. On an
 * appd-only restart the server survived, every session is already in the set, and
 * this returns nothing — which is exactly right, because they never died.
 */
function restorePlan(registry, liveNames) {
  const set = liveNames instanceof Set ? liveNames : new Set(liveNames || []);
  const plan = [];
  for (const entry of Object.values(registry || {})) {
    if (!entry || !entry.name || isReserved(entry.name)) continue;
    if (set.has(entry.name)) continue;
    plan.push(entry);
  }
  plan.sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
  return plan;
}

/**
 * The shell command a restored tmux session runs. Resumes by id when we have a
 * valid one AND its transcript is still on disk; otherwise a plain `claude`, which
 * is the create route's own default — a named session back in the right place is
 * worth more than no session when there is nothing left to resume.
 *
 * `hasTranscript` is passed in because that is an fs check the caller already knows
 * how to make (findTranscriptFile); keeping it out of here keeps the module pure.
 * The tail (`exec "$SHELL" -l`) mirrors the create route exactly, so a resumed
 * session that exits drops to a login shell the same way a fresh one does.
 */
function resumeCommand(entry, hasTranscript) {
  const id = entry && entry.claudeSessionId;
  const canResume = !!id && UUID_RE.test(id) && !!hasTranscript;
  const launch = canResume ? `claude --resume ${id}` : 'claude';
  return { canResume, command: `${launch}; exec "$SHELL" -l` };
}

module.exports = { mergeLive, pruneDead, restorePlan, resumeCommand, isReserved, RESERVED, UUID_RE };
