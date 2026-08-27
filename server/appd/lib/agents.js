'use strict';
// The individual agents behind a fan-out, so "0/4 agents done" can be opened.
//
// Layout, read off a live workflow rather than documentation (2026-07-27):
//
//   <projects>/<slug>/<sessionId>/subagents/agent-<id>.jsonl          direct agents
//   <projects>/<slug>/<sessionId>/subagents/workflows/wf_<id>/…       one dir per
//       agent-<id>.jsonl + journal.jsonl                              workflow run
//
// Each agent file is an ordinary Claude transcript: its FIRST user record is the
// task the parent wrote for it, and its tail is whatever it is doing right now.
// The workflow journal carries {type:"started"} lines AND — the useful part —
// {type:"result", agentId, result:{summary}} once an agent settles: the agent's
// own account of what it concluded, which beats "last tool: StructuredOutput"
// as the epitaph on a settled row. Liveness is the transcript growing: an agent
// leaves no completion marker in its OWN file, but one that has not written in
// a while has either finished or died, and for a progress popup those are the
// same thing.

const fs = require('node:fs');
const path = require('node:path');
const { readTranscript } = require('./transcript');

/** Same window the tasks scanner uses for agents: growth within this = active. */
const ACTIVE_S = 90;

/**
 * Ignore runs older than this. Was six hours, which filled the sheet with the
 * corpses of every fan-out since lunch — the sheet answers "what is happening",
 * and settled agents stop being part of that answer quickly.
 */
const RECENT_S = 45 * 60;

function agentsDirFor(transcriptPath, sessionId) {
  if (!transcriptPath || !sessionId) return null;
  return path.join(path.dirname(transcriptPath), sessionId, 'subagents');
}

/**
 * Where workflow runs put their agents. BOTH, because the CLI has used both.
 *
 * `<sessionId>/subagents/workflows/` is where the runs on this host land today;
 * `<sessionId>/workflows/` is the sibling the CLI also writes into — on this
 * host it currently holds the run MANIFESTS and a scripts dir rather than
 * transcripts, but it is the same namespace, and scanning only one of the two
 * is a blind spot that reports "no agents" for a fan-out that ran. Both are
 * scanned and the results merged by run name.
 */
function workflowDirs(dir) {
  return [path.join(dir, 'workflows'), path.join(path.dirname(dir), 'workflows')];
}

/** Every agent transcript, with the workflow run it belongs to (null = direct). */
function listAgentFiles(dir, fsImpl = fs) {
  const out = [];
  const seen = new Set();
  let entries = [];
  try { entries = fsImpl.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (e.isFile() && /^agent-.*\.jsonl$/.test(e.name)) {
      out.push({ file: path.join(dir, e.name), workflow: null });
    }
  }
  for (const wfRoot of workflowDirs(dir)) {
    let wfs = [];
    try { wfs = fsImpl.readdirSync(wfRoot, { withFileTypes: true }); } catch { continue; }
    for (const w of wfs) {
      if (!w.isDirectory()) continue;
      let files = [];
      try { files = fsImpl.readdirSync(path.join(wfRoot, w.name), { withFileTypes: true }); } catch { continue; }
      for (const f of files) {
        if (!f.isFile() || !/^agent-.*\.jsonl$/.test(f.name)) continue;
        // Keyed on run + agent, not on the path: the same run reached through
        // two roots is one run, and listing it twice would double every token
        // total computed from these files.
        const key = `${w.name}/${f.name}`;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({ file: path.join(wfRoot, w.name, f.name), workflow: w.name });
      }
    }
  }
  return out;
}

/**
 * The task an agent was given: its first user record, read from the file HEAD.
 * readTranscript tails the file, which for the task would mean reading megabytes
 * to find the first line; a 16KB head covers any real prompt's first line.
 */
function agentTask(file, fsImpl = fs) {
  let fd = null;
  try {
    fd = fsImpl.openSync(file, 'r');
    const buf = Buffer.alloc(16384);
    const n = fsImpl.readSync(fd, buf, 0, buf.length, 0);
    const firstLine = buf.toString('utf8', 0, n).split('\n')[0];
    const rec = JSON.parse(firstLine);
    const c = rec && rec.message && rec.message.content;
    const text = typeof c === 'string'
      ? c
      : Array.isArray(c) ? c.filter((b) => b.type === 'text').map((b) => b.text).join(' ') : '';
    // Prompts often open with a boilerplate CONTEXT block; the first line is
    // still the best available one-line label.
    return text.replace(/\s+/g, ' ').trim().slice(0, 160) || null;
  } catch { return null; } finally {
    if (fd !== null) { try { fsImpl.closeSync(fd); } catch { } }
  }
}

/**
 * Settled agents' outcomes, read from a workflow run's journal: agentId -> the
 * first line of its result summary. Tolerant of every shape problem — a journal
 * is another process's file mid-write.
 */
function journalSummaries(journalFile, fsImpl = fs) {
  const out = new Map();
  let text = '';
  try { text = fsImpl.readFileSync(journalFile, 'utf8'); } catch { return out; }
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    try {
      const d = JSON.parse(line);
      if (d.type !== 'result' || !d.agentId) continue;
      const r = d.result;
      const summary = typeof r === 'string' ? r
        : r && typeof r.summary === 'string' ? r.summary
          : null;
      if (summary) out.set(d.agentId, summary.replace(/\s+/g, ' ').trim().slice(0, 160));
    } catch { /* half-written line */ }
  }
  return out;
}

/**
 * Which agents in a workflow run have SETTLED, whatever they concluded.
 *
 * Separate from journalSummaries because a result is not always a summary: real
 * journals on this host carry `result:{findings:[…]}` with no summary field at
 * all, so "did it finish" and "what did it say" are two different questions and
 * only the first one always has an answer.
 */
function journalSettled(journalFile, fsImpl = fs) {
  const out = new Set();
  let text = '';
  try { text = fsImpl.readFileSync(journalFile, 'utf8'); } catch { return out; }
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    try {
      const d = JSON.parse(line);
      if (d.type === 'result' && d.agentId) out.add(d.agentId);
    } catch { /* half-written line */ }
  }
  return out;
}

/** What the agent is doing right now, from its transcript tail. */
function agentLastLine(file) {
  try {
    const t = readTranscript(file, { limit: 6 });
    for (let i = t.events.length - 1; i >= 0; i--) {
      const e = t.events[i];
      if (e.kind === 'tool') {
        return `${e.name || 'tool'}${e.detail ? ` ${e.detail}` : ''}`.slice(0, 120);
      }
      if ((e.kind === 'assistant' || e.kind === 'thinking') && e.text) {
        const line = e.text.trim().split('\n').find((l) => l.trim());
        if (line) return (e.kind === 'thinking' ? '…' : '') + line.trim().slice(0, 120);
      }
    }
  } catch { }
  return null;
}

/**
 * All recent agents of one session, newest activity first.
 *
 * @returns {Array<{id, workflow, task, lastLine, active, updatedAt, startedAt, bytes}>}
 */
function listAgents(dir, nowSec, fsImpl = fs, max = 24) {
  const files = listAgentFiles(dir, fsImpl);
  const out = [];
  for (const f of files) {
    let st;
    try { st = fsImpl.statSync(f.file); } catch { continue; }
    const updatedAt = Math.floor(st.mtimeMs / 1000);
    if (nowSec - updatedAt > RECENT_S) continue;
    out.push({
      id: path.basename(f.file).replace(/^agent-|\.jsonl$/g, ''),
      workflow: f.workflow,
      updatedAt,
      startedAt: Math.floor((st.birthtimeMs || st.ctimeMs || st.mtimeMs) / 1000),
      bytes: st.size,
      active: nowSec - updatedAt <= ACTIVE_S,
      file: f.file,
    });
  }
  out.sort((a, b) => b.updatedAt - a.updatedAt);
  const kept = out.slice(0, max);
  // Journal summaries, one read per workflow run represented in the kept rows.
  // The journal sits beside the agent's OWN file rather than under an assumed
  // root — a run may have come from either workflow location (see workflowDirs).
  const summaries = new Map();
  for (const a of kept) {
    if (!a.workflow || summaries.has(a.workflow)) continue;
    summaries.set(a.workflow,
      journalSummaries(path.join(path.dirname(a.file), 'journal.jsonl'), fsImpl));
  }
  // The transcript reads are the expensive part; only the kept rows pay them.
  for (const a of kept) {
    a.task = agentTask(a.file, fsImpl);
    a.lastLine = agentLastLine(a.file);
    const j = a.workflow ? summaries.get(a.workflow) : null;
    a.summary = (j && j.get(a.id)) || null;
    delete a.file;
  }
  return kept;
}

module.exports = {
  agentsDirFor, workflowDirs, listAgentFiles, agentTask, agentLastLine,
  journalSummaries, journalSettled, listAgents,
  ACTIVE_S, RECENT_S,
};
