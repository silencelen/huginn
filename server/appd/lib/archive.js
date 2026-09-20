'use strict';
// Archive: a session ended ON PURPOSE, kept as a row that can be brought back.
//
// The daemon has always been able to resurrect a session it lost — lib/session-
// registry.js reconstructs `claude --resume <uuid>` in the right cwd after a
// power cut. An archive is the same machinery pointed at a session somebody
// FINISHED with: wind it down, end it for real, and keep the one paragraph that
// makes "bring it back to life" possible later.
//
// ⚠ A STORED RESUME COMMAND EXPIRES, and nothing about it looks expired. Claude
// Code deletes its own transcripts after `cleanupPeriodDays` (21 on this host),
// and `claude --resume <uuid>` against a missing transcript degrades to a bare
// `claude` in the right directory — a revive that looks like it worked and has
// amnesia. That is why an archive COPIES the jsonl at archive time (the daemon
// does the fs; [transcriptWindow] is the rule for how much of it), and why a
// revive puts the copy back before it resumes.
//
// This module is the pure half — the record shape, the command, the ordering,
// the caps, the name a revive lands under. The daemon owns fs, tmux and the
// routes, the same split lib/session-registry.js and lib/scratchpads.js use.

const path = require('node:path');

const { UUID_RE } = require('./session-registry');

/**
 * How many archived sessions one host keeps.
 *
 * MAX_PADS' number and MAX_PADS' argument (lib/scratchpads.js): the list is
 * fetched whole by a polling client and every row is a directory holding a
 * transcript copy. Rows NEVER time-expire — an archive whose whole promise is
 * "this is still here when you want it" cannot have a clock on it — so the cap
 * is the only thing that ever removes one, and it removes the oldest.
 */
const MAX_ARCHIVES = 64;

/**
 * The most of one transcript that is copied into the archive.
 *
 * Measured over this host's 151 netplan transcripts: median 149 KB, total
 * 220 MB, max 58 MB. So 64 archives is ~10 MB typically and the tail is fat
 * enough to matter. Above the cap the LAST bytes are kept rather than the file
 * skipped: the end of a conversation is the part a person is coming back for,
 * and a resumed session that starts mid-history is worth more than one that
 * starts empty. Truncation is recorded on the row and said out loud, because a
 * silently shortened conversation is the same lie as a missing one.
 */
const TRANSCRIPT_CAP_BYTES = 32 * 1024 * 1024;

/** How long the label under a row may run before the row is doing the reading. */
const MAX_PREVIEW = 240;

// C0/C1 controls plus DEL, stripped from anything that reaches a terminal or a
// one-line row — the argument lib/rounds.js makes about a report headline, and
// this text comes out of a transcript, which is to say out of a model.
const CTRL_ALL = /[\u0000-\u001f\u007f-\u009f]/g;

/** One line, no controls, bounded. */
function oneLine(raw, max = MAX_PREVIEW) {
  if (typeof raw !== 'string') return '';
  return raw.replace(CTRL_ALL, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

/**
 * A path as a POSIX shell will read it back unchanged.
 *
 * The cwd is written by Claude Code's own hook and is an ordinary directory
 * name in every observed case — but this string is handed to a person to PASTE
 * INTO A SHELL, and a directory with a space in it that pastes as two arguments
 * is a resume command that silently starts in the wrong place.
 */
function shellQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

/**
 * The command that brings this conversation back, exactly as a person should
 * type it — including the `cd`, because the transcript is found by session id
 * but Claude Code opens in the directory it is started from, and a resume in the
 * wrong cwd is a conversation about the wrong repository.
 *
 * Null when there is no id to resume: a session whose title hook never fired has
 * nothing to archive, and a row that offered an unusable command would be worse
 * than no row. The uuid is shape-checked here as well as at the route, because
 * this string is the one thing from the record that reaches a shell.
 */
function resumeCommand(claudeSessionId, cwd) {
  const id = String(claudeSessionId || '');
  if (!UUID_RE.test(id)) return null;
  const dir = typeof cwd === 'string' && cwd.trim() ? cwd.trim() : null;
  return dir ? `cd ${shellQuote(dir)} && claude --resume ${id}` : `claude --resume ${id}`;
}

/**
 * The record, from everything the daemon could observe at archive time.
 *
 * Keyed by claudeSessionId, NEVER by the tmux name — the rule session-meta is
 * built on (huginn-appd.js:566-570) and the one this store needs most: names are
 * reused within hours here, and an archive filed under a reused name would hand
 * the next `dev` the previous one's conversation.
 *
 * Every field the clients read is present with a definite value (null, not
 * absent) so a row that decoded is a row that renders. `endedAt` is the
 * exception that matters: it is null until the session is actually dead, and
 * that is what tells a reader whether this row is a finished archive or the
 * wreckage of a crash between writing the row and killing the session.
 */
function buildRecord(input = {}) {
  const id = String(input.claudeSessionId || '');
  const cwd = input.cwd || null;
  return {
    id,
    claudeSessionId: id,
    /** The tmux name it had. Kept for a revive's preferred name, and for Wave-3
     *  Projects, which re-binds its members by tmux name. */
    tmuxName: input.tmuxName || null,
    /** Claude Code's own generated title — a far better label than the name. */
    title: oneLine(input.title, 200) || null,
    cwd,
    model: input.model || null,
    effort: input.effort || null,
    permissionMode: input.permissionMode || null,
    gitBranch: input.gitBranch || null,
    /** What to type to get this conversation back. See [resumeCommand]. */
    resumeCommand: resumeCommand(id, cwd),
    archivedAt: input.archivedAt || 0,
    /** When the session actually died. Null means the kill has not happened. */
    endedAt: input.endedAt ?? null,
    lastMessage: oneLine(input.lastMessage) || null,
    /** Where Claude Code's own copy was when this was archived. */
    transcriptPath: input.transcriptPath || null,
    /** Bytes of it kept here, and whether that is the whole file. */
    transcriptBytes: Number(input.transcriptBytes) || 0,
    transcriptTruncated: !!input.transcriptTruncated,
    /** Set by a revive, so a row that came back says so instead of offering to
     *  bring back a session that is already running. */
    revivedAt: input.revivedAt ?? null,
    revivedAs: input.revivedAs ?? null,
    note: oneLine(input.note) || null,
  };
}

/**
 * The row as a client reads it: the record plus the two facts that are only true
 * RIGHT NOW and so must never be stored.
 *
 * `live` is "a tmux session of this name is running this same conversation" —
 * either a revived row, or the crash window between writing the record and the
 * kill landing. Such a row offers Open, not Revive; reviving it would start a
 * second Claude on one transcript.
 *
 * `transcriptPresent` is whether anything is left to resume at all. It is
 * recomputed on every list rather than stored because the thing it describes
 * (Claude Code's 21-day sweep) happens while this daemon is not looking.
 */
function archiveRow(rec, { live = false, transcriptPresent = false } = {}) {
  return { ...rec, live: !!live, transcriptPresent: !!transcriptPresent };
}

/**
 * Newest archive first, id breaking the tie so the order is total.
 *
 * Recency IS the order here, unlike the scratchpad picker: nobody points at a
 * row in this list from memory, and the one you just archived is the one you are
 * most likely to want back.
 */
function sortArchives(rows) {
  return [...(rows || [])].sort((a, b) => {
    const at = (b.archivedAt || 0) - (a.archivedAt || 0);
    if (at !== 0) return at;
    return String(a.id || '') < String(b.id || '') ? -1 : 1;
  });
}

/**
 * Which rows the cap evicts, OLDEST ARCHIVED FIRST, to bring the store back to
 * [max].
 *
 * Oldest-first and not least-recently-revived: `archivedAt` is the only stamp
 * every row has, and a rule whose input can be missing is a rule that silently
 * picks at random. A row that has been revived is not protected either — it is
 * already back, so the copy here is the least load-bearing one in the store.
 */
function evictions(rows, max = MAX_ARCHIVES) {
  const sorted = sortArchives(rows);
  if (sorted.length <= max) return [];
  return sorted.slice(max).reverse();   // reverse() -> oldest first, for the log
}

/**
 * The tmux name a revive should land under: the old one when it is free, else
 * the old one with a digit.
 *
 * The old name first because everything that refers to this session by name —
 * a shell alias, a Wave-3 project member binding, the person's own muscle
 * memory — is still pointing at it. `<name>2` and not a uuid when it is taken,
 * for the same reason: still recognisably the thing that came back.
 *
 * ⚠ THE ANSWER IS A REQUEST, NOT A FACT. tmux rewrites a '.' to '_' and reports
 * success, so the caller must read the created name back out of tmux rather
 * than trusting what it asked for (huginn-appd.js:7712-7715).
 */
function reviveName(preferred, taken = []) {
  const set = taken instanceof Set ? taken : new Set(taken || []);
  const base = String(preferred || '').trim() || 'session';
  if (!set.has(base)) return base;
  for (let n = 2; n <= 99; n++) {
    const candidate = `${base}${n}`;
    if (!set.has(candidate)) return candidate;
  }
  return `${base}${Date.now().toString(36).slice(-4)}`;
}

/**
 * Which bytes of a transcript to copy, and whether that is all of them.
 *
 * The TAIL, not the head, when the file is over the cap — see
 * [TRANSCRIPT_CAP_BYTES]. The window is snapped forward to the next record
 * boundary by the caller (a jsonl file read from a mid-line offset begins with
 * half a record, and `readTranscript` would drop it), so this returns the
 * unsnapped byte range and the honest truncated flag.
 */
function transcriptWindow(size, cap = TRANSCRIPT_CAP_BYTES) {
  const n = Number(size) || 0;
  if (n <= cap) return { start: 0, bytes: n, truncated: false };
  return { start: n - cap, bytes: cap, truncated: true };
}

/**
 * The one line under an archived row: the last thing that was actually said.
 *
 * Assistant text is preferred over the user's own: a person scanning this list
 * is looking for where a conversation GOT TO, and their own last message is the
 * thing they already remember. Falls back to the user's when the run ended
 * without speaking (killed mid-tool, or a wrap-up that only committed).
 */
function lastMessageOf(events) {
  const list = Array.isArray(events) ? events : [];
  for (const kind of ['assistant', 'user']) {
    for (let i = list.length - 1; i >= 0; i--) {
      const e = list[i];
      if (!e || e.kind !== kind || e.sidechain) continue;
      const t = oneLine(e.text);
      if (t) return t;
    }
  }
  return '';
}

/**
 * Where Claude Code keeps one conversation, under the config directory THIS
 * daemon was told to use.
 *
 * ⚠ THE CONFIG DIRECTORY, NOT `os.homedir()`. The revive path built this out of
 * the process's home every time, so a daemon started with
 * HUGINN_APPD_CLAUDE_DIR pointing at a scratch directory — which is the whole
 * of what that knob is for, and what every route suite and the CLI's own gate
 * run under — restored its fixture transcript into the OWNER'S REAL
 * ~/.claude/projects/. It logged the real path while it did it (found that way,
 * in a review run: "archive: restored the kept transcript … to
 * /root/.claude/projects/…"), and the file then sat in the live store as a
 * conversation Claude Code would happily resume. The knob's own comment says
 * every other reader "builds a path from it and is isolated by construction";
 * this is the function that makes that true for the two readers that were not.
 *
 * The slug is Claude Code's own: the absolute cwd with every '/' replaced by a
 * '-'. Returned as both halves because the caller has to mkdir one and write
 * the other.
 */
function transcriptTarget(claudeDir, cwd, id) {
  const dir = path.join(String(claudeDir || ''), 'projects', String(cwd || '').replace(/\//g, '-'));
  return { dir, file: path.join(dir, `${id}.jsonl`) };
}

/** The directory those live under, for a reader that scans rather than guesses. */
function projectsRoot(claudeDir) {
  return path.join(String(claudeDir || ''), 'projects');
}

module.exports = {
  MAX_ARCHIVES, TRANSCRIPT_CAP_BYTES, MAX_PREVIEW,
  oneLine, shellQuote, resumeCommand, buildRecord, archiveRow,
  sortArchives, evictions, reviveName, transcriptWindow, lastMessageOf,
  transcriptTarget, projectsRoot,
};
