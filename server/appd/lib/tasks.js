'use strict';
// Background work a session has in flight: shells started with run_in_background,
// and agents running off the main thread.
//
// Why this exists: a session whose turn is blocked on a twenty-minute build LOOKS
// stalled from the conversation and the list — the transcript is silent, the
// spinner may be gone — and the only way to learn otherwise was to open the tmux
// screen and read the TUI. The facts live in two places, discovered by running a
// real background task and looking:
//
//   * `/tmp/claude-<uid>/<project-slug>/<session-uuid>/tasks/` holds one entry per
//     background task: a regular `<id>.output` file for a shell, and a SYMLINK to
//     `subagents/agent-<id>.jsonl` for a background agent.
//   * The shell's writer process keeps its `.output` open for the task's lifetime.
//     An open writer among the session's own descendants is therefore an exact
//     "still running" signal — and the writer's argv names the command, which no
//     file in the tasks dir does.
//
// TWO checks, because each alone over-counts — measured live, not theorized:
//
//   * The fd check: a task is running only while a descendant holds its `.output`
//     open. Without it, a finished task's file counts forever.
//   * The transcript check: FOREGROUND tool calls also write to a tasks file (the
//     daemon's own probe showed its foreground `cp && restart` held open exactly
//     like a background task), so held-open alone counts the command the user is
//     already watching as background work. A background launch is the one whose
//     tool RESULT says "running in background with ID: <id>" — so only ids the
//     transcript names as background count.

const fs = require('node:fs');
const path = require('node:path');

/** How recently an agent's transcript must have grown to count as active. */
const AGENT_FRESH_S = 90;

/**
 * Where a session's task files live. The project slug is read off the transcript
 * path rather than recomputed from cwd, because the slug belongs to the directory
 * Claude STARTED in and the state file's cwd moves as the session cd's around —
 * deriving from cwd pointed at a directory that does not exist.
 */
function taskDirFor(transcriptPath, sessionId, uid) {
  if (!transcriptPath || !sessionId) return null;
  const slug = path.basename(path.dirname(transcriptPath));
  return path.join('/tmp', `claude-${uid}`, slug, sessionId, 'tasks');
}

/** Parses `ps -eo pid,ppid,etimes,args --no-headers`. */
function parsePs(text) {
  const procs = new Map();
  for (const line of String(text || '').split('\n')) {
    const m = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    if (!m) continue;
    procs.set(Number(m[1]), { ppid: Number(m[2]), etimes: Number(m[3]), args: m[4] });
  }
  return procs;
}

/** Every pid under `root`, transitively. */
function descendantsOf(procs, root) {
  const children = new Map();
  for (const [pid, p] of procs) {
    if (!children.has(p.ppid)) children.set(p.ppid, []);
    children.get(p.ppid).push(pid);
  }
  const out = [];
  const stack = [...(children.get(root) || [])];
  while (stack.length) {
    const pid = stack.pop();
    out.push(pid);
    for (const c of children.get(pid) || []) stack.push(c);
  }
  return out;
}

/**
 * The human-worthy part of a task's argv. Background shells run as
 *
 *     /bin/bash -c source ~/.claude/shell-snapshots/snapshot-….sh … && <command>
 *
 * and the reader wants the command, not the plumbing. Falls back to the whole
 * argv when the shape is unfamiliar — wrong-but-visible beats hidden.
 */
function cleanCommand(args, max = 90) {
  let s = String(args || '');
  // The wrapper observed live (2026-07-27): source snapshot && shopt … && { … }
  // && eval '<the actual command>' < /dev/null && pwd -P >| /tmp/…-cwd
  // The command is the eval body; everything else is environment plumbing.
  const i = s.indexOf("eval '");
  if (i >= 0) {
    let body = s.slice(i + 6);
    const j = body.lastIndexOf("' < /dev/null");
    if (j >= 0) body = body.slice(0, j);
    else body = body.replace(/'\s*$/, '');
    // Bash single-quote escaping: an embedded quote travels as '\''.
    s = body.split("'\\''").join("'");
  } else {
    const k = s.indexOf('&& ');
    if (k >= 0 && /shell-snapshots/.test(s.slice(0, k))) s = s.slice(k + 3);
  }
  return s.trim().slice(0, max);
}

/**
 * Which tasks-dir files each descendant currently holds open for writing.
 * /proc is the source: no lsof dependency, and only the session's own handful of
 * descendants are scanned, never the whole process table.
 */
function openTaskFiles(dir, pids, fsImpl = fs) {
  const writers = new Map();   // absolute file path -> [pid, ...]
  for (const pid of pids) {
    let fds = [];
    try { fds = fsImpl.readdirSync(`/proc/${pid}/fd`); } catch { continue; }
    for (const fd of fds) {
      let target;
      try { target = fsImpl.readlinkSync(`/proc/${pid}/fd/${fd}`); } catch { continue; }
      if (!target.startsWith(dir + path.sep)) continue;
      if (!writers.has(target)) writers.set(target, []);
      if (!writers.get(target).includes(pid)) writers.get(target).push(pid);
    }
  }
  return writers;
}

/**
 * The task ids the transcript says were launched in the background. The launch's
 * tool result carries both the id and the output path; either names it.
 */
function extractBgIds(events) {
  const ids = new Set();
  const re = /background with ID:\s*([A-Za-z0-9_-]+)|tasks\/([A-Za-z0-9_-]+)\.output/g;
  for (const e of events || []) {
    const text = `${e.result || ''}\n${e.text || ''}`;
    let m;
    while ((m = re.exec(text)) !== null) ids.add(m[1] || m[2]);
  }
  return ids;
}

/**
 * A session's background work, summarized.
 *
 * @returns {{shells: Array<{id, command, forSeconds}>, agents: number}}
 */
function scanTasks(dir, procs, panePid, nowSec, fsImpl = fs, knownBgIds = null) {
  const empty = { shells: [], agents: 0 };
  if (!dir) return empty;
  let entries = [];
  try { entries = fsImpl.readdirSync(dir, { withFileTypes: true }); } catch { return empty; }

  const pids = descendantsOf(procs, panePid);
  const writers = openTaskFiles(dir, pids, fsImpl);

  const shells = [];
  let agents = 0;
  for (const e of entries) {
    if (!e.name.endsWith('.output')) continue;
    const full = path.join(dir, e.name);
    if (e.isSymbolicLink()) {
      // A background agent: the link points at its transcript, which grows while
      // the agent works. Growth is the only liveness signal an agent leaves.
      try {
        const st = fsImpl.statSync(full);            // follows the link
        if (nowSec - Math.floor(st.mtimeMs / 1000) <= AGENT_FRESH_S) agents++;
      } catch { /* target gone: the agent is over */ }
      continue;
    }
    const id = e.name.replace(/\.output$/, '');
    // Not named as background by the transcript: this is the foreground call the
    // user is already watching (or predates the transcript window; err quiet).
    if (knownBgIds && !knownBgIds.has(id)) continue;
    const holders = writers.get(full) || [];
    if (!holders.length) continue;                    // finished: nobody holds it
    // Both the wrapper bash and the command it spawned hold the file. The
    // wrapper's argv names the whole task; a child names only its own binary
    // ("sleep 500" for a task that is really "sleep 500; echo done") — so the
    // wrapper wins when present.
    const pid = holders.find((h) => {
      const q = procs.get(h);
      return q && /shell-snapshots|eval '/.test(q.args);
    }) ?? holders[0];
    const p = procs.get(pid);
    // Elapsed from the file's own birth, because ps etimes is garbage inside
    // this LXC (returned ~4.1e9 seconds — a time-namespace artifact).
    let started = 0;
    try {
      const st = fsImpl.statSync(full);
      started = Math.floor((st.birthtimeMs || st.ctimeMs || 0) / 1000);
    } catch { }
    shells.push({
      id,
      command: cleanCommand(p ? p.args : ''),
      forSeconds: started > 0 ? Math.max(0, nowSec - started) : 0,
    });
  }
  shells.sort((a, b) => b.forSeconds - a.forSeconds);
  return { shells, agents };
}

module.exports = {
  taskDirFor, parsePs, descendantsOf, cleanCommand, openTaskFiles, scanTasks, extractBgIds,
  AGENT_FRESH_S,
};
