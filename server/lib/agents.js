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
// The journal is thin ({type:"started"} lines) and carries no labels, so the
// agent's own transcript is the whole story. Liveness is the file growing: an
// agent leaves no completion marker, but one that has not written in a while has
// either finished or died, and for a progress popup those are the same thing.

const fs = require('node:fs');
const path = require('node:path');
const { readTranscript } = require('./transcript');

/** Same window the tasks scanner uses for agents: growth within this = active. */
const ACTIVE_S = 90;

/** Ignore runs older than this: yesterday's fan-out is history, not progress. */
const RECENT_S = 6 * 3600;

function agentsDirFor(transcriptPath, sessionId) {
  if (!transcriptPath || !sessionId) return null;
  return path.join(path.dirname(transcriptPath), sessionId, 'subagents');
}

/** Every agent transcript, with the workflow run it belongs to (null = direct). */
function listAgentFiles(dir, fsImpl = fs) {
  const out = [];
  let entries = [];
  try { entries = fsImpl.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (e.isFile() && /^agent-.*\.jsonl$/.test(e.name)) {
      out.push({ file: path.join(dir, e.name), workflow: null });
    }
  }
  let wfs = [];
  try { wfs = fsImpl.readdirSync(path.join(dir, 'workflows'), { withFileTypes: true }); } catch { return out; }
  for (const w of wfs) {
    if (!w.isDirectory()) continue;
    let files = [];
    try { files = fsImpl.readdirSync(path.join(dir, 'workflows', w.name), { withFileTypes: true }); } catch { continue; }
    for (const f of files) {
      if (f.isFile() && /^agent-.*\.jsonl$/.test(f.name)) {
        out.push({ file: path.join(dir, 'workflows', w.name, f.name), workflow: w.name });
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
  // The transcript reads are the expensive part; only the kept rows pay them.
  for (const a of kept) {
    a.task = agentTask(a.file, fsImpl);
    a.lastLine = agentLastLine(a.file);
    delete a.file;
  }
  return kept;
}

module.exports = { agentsDirFor, listAgentFiles, agentTask, agentLastLine, listAgents, ACTIVE_S, RECENT_S };
