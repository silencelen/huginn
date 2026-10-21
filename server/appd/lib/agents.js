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

// The first record is read in chunks until its newline, because a fixed head is
// a guess about prompt length and a wrong one costs the label silently. 17 of
// 1050 agent transcripts on this host open with a line over 16 KB (largest 793
// KB) — the workflow fan-outs, i.e. exactly the long-running agents whose label
// is worth most. The cap is what keeps a corrupt file (no newline at all) from
// being read into memory whole.
const TASK_CHUNK = 65536;
const TASK_MAX = 1024 * 1024;

/**
 * The task line and whether it is FINAL: nothing appended to the file later can
 * change it, because the first record's newline has landed (or the cap has been
 * read — the first megabyte of an append-only file is what it is).
 */
function readTaskHead(file, fsImpl = fs) {
  let fd = null;
  try {
    fd = fsImpl.openSync(file, 'r');
    let head = Buffer.alloc(0);
    let nl = -1;
    for (;;) {
      const chunk = Buffer.alloc(TASK_CHUNK);
      const n = fsImpl.readSync(fd, chunk, 0, TASK_CHUNK, head.length);
      if (!n || n <= 0) break;
      const at = chunk.indexOf(0x0a);
      head = Buffer.concat([head, chunk.subarray(0, n)]);
      if (at >= 0 && at < n) { nl = head.length - n + at; break; }
      if (head.length >= TASK_MAX) break;
    }
    const final = nl >= 0 || head.length >= TASK_MAX;
    try {
      const firstLine = nl >= 0 ? head.toString('utf8', 0, nl) : head.toString('utf8');
      const rec = JSON.parse(firstLine);
      const c = rec && rec.message && rec.message.content;
      const text = typeof c === 'string'
        ? c
        : Array.isArray(c) ? c.filter((b) => b.type === 'text').map((b) => b.text).join(' ') : '';
      // Prompts often open with a boilerplate CONTEXT block; the first line is
      // still the best available one-line label.
      return { task: text.replace(/\s+/g, ' ').trim().slice(0, 160) || null, final };
    } catch { return { task: null, final }; }
  } catch { return { task: null, final: false }; } finally {
    if (fd !== null) { try { fsImpl.closeSync(fd); } catch { } }
  }
}

/**
 * The task an agent was given: its first user record, read from the file HEAD.
 * readTranscript tails the file, which for the task would mean reading megabytes
 * to find the first line.
 */
function agentTask(file, fsImpl = fs) {
  return readTaskHead(file, fsImpl).task;
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
 * Everything one journal says, in ONE read.
 *
 * `journalSummaries` and `journalSettled` each re-read the file; asking all
 * three questions a status needs (settled? failed? started at all?) that way
 * would mean three passes over a journal that reaches a megabyte on a real
 * fan-out. Same tolerance for half-written lines — a journal is another
 * process's file mid-write.
 *
 * `failed` is a real record type the runner writes (319 of them across this
 * host's journals on 2026-09-15), which is what makes a *cheap* `failed` status
 * possible at all: the parent's tool_result, which is how `/graph` tells a
 * failure from a success, needs the whole parent transcript walked.
 */
function journalIndex(journalFile, fsImpl = fs) {
  const out = emptyJournalIndex();
  let text = '';
  try { text = fsImpl.readFileSync(journalFile, 'utf8'); } catch { return out; }
  for (const line of text.split('\n')) applyJournalLine(out, line);
  return out;
}

function emptyJournalIndex() {
  return { summaries: new Map(), settled: new Set(), failed: new Set(), started: new Set() };
}

/** One journal line's contribution to a journalIndex. */
function applyJournalLine(out, line) {
  if (!line.trim()) return;
  let d;
  try { d = JSON.parse(line); } catch { return; /* half-written line */ }
  applyJournalRecord(out, d);
}

function applyJournalRecord(out, d) {
  try {
    if (!d || !d.agentId) return;
    if (d.type === 'started') { out.started.add(d.agentId); return; }
    if (d.type === 'failed') { out.failed.add(d.agentId); return; }
    if (d.type !== 'result') return;
    out.settled.add(d.agentId);
    const r = d.result;
    const summary = typeof r === 'string' ? r
      : r && typeof r.summary === 'string' ? r.summary
        : null;
    if (summary) out.summaries.set(d.agentId, summary.replace(/\s+/g, ' ').trim().slice(0, 160));
  } catch { /* a shape nothing above expected */ }
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

// ------------------------------------------------------------- the last line
//
// "What it is doing now" is the newest tool call or line of prose among the last
// six events readTranscript draws from the file's tail. That read starts 256 KB
// from the end and doubles until the window holds six events, parsing all of
// it — and since mid-October an agent file is mostly REQUEST RECORDS: Claude
// Code writes the whole API request before every call, 40-130 KB a line, 85-90%
// of an agent file's bytes (measured on this host's agent folders, 2026-10-20).
// Six events now sit past two or three of them, so a row cost a 512 KB read
// parsed twice, and a long-running agent's several megabytes.
//
// The same answer is found from a SHORT window, and is provably the same one:
//
//   * every event it can return (a tool call, prose, thinking) comes from an
//     assistant record alone, never from what surrounds it;
//   * what a wider window adds is context — a tool_result paired with the call
//     above it, a skill body folded into the card that loaded it — and context
//     only ever REMOVES events, never adds one after the line found;
//   * so a line found in the last few records of a window inside the old one is
//     the old answer too: nothing the old window adds can come after it.
//
// When the short window finds nothing — every recent event is a result or a
// message — the old read runs instead, unchanged. The one shape this argument
// does not cover is a queued message that never got delivered (it floats to the
// bottom of the old window); agent transcripts carry no queue records at all
// (none of the 1,856 on this host on 2026-10-20 does).

/** readTranscript's own first tail window: the old read never starts closer. */
const OLD_TAIL = 256 * 1024;
/** What one backward read of the tail takes. */
const TAIL_STEP = 16 * 1024;
/**
 * Lines that can draw an event, asked about in turn: the newest two (a finished
 * agent's last call and its result), the newest four (a running agent's last
 * turn, from its thinking to the result it is waiting behind), a wider handful,
 * and then the old read.
 */
const TAIL_TRIES = [2, 4, 16];

/**
 * `{"type":"api-request` — a request record, by its first bytes: the CLI writes
 * `type` as its first key. Such a line can only ever be a request record (it
 * draws no event), so the tail scan steps over it without decoding it.
 */
const REQUEST_HEAD = Buffer.from('{"type":"api-request');

function isRequestLine(buf, start, end) {
  return end - start >= REQUEST_HEAD.length
    && buf.compare(REQUEST_HEAD, 0, REQUEST_HEAD.length, start, start + REQUEST_HEAD.length) === 0;
}

/**
 * Walks a file's tail backwards, a line at a time, collecting the lines that can
 * draw an event (anything but a request record), newest first. Reads in growing
 * blocks and never further back than OLD_TAIL, so every window it hands out lies
 * inside the old read's.
 *
 * `window(want)` is {start, until, lines, whole} for the newest `want` such
 * lines: `start` is the oldest one's first byte, `until` the byte after the
 * newest one's newline. Past `until` there is only request records and the line
 * a writer is still on, and neither draws an event. `whole` says the window
 * holds EVERY such line in the file — the file fits in OLD_TAIL, so the old read
 * was this window plus lines that draw nothing, and its answer is this window's,
 * null included. Null when there is no such line to hand out.
 */
function tailScanner(file, size, fsImpl = fs) {
  const fd = fsImpl.openSync(file, 'r');
  let lo = size;                // the first byte `buf` holds
  let buf = Buffer.alloc(0);
  let nl = -1;                  // the newline ending the next line to look at
  let done = false;
  let atStart = false;          // the line starting at byte 0 has been looked at
  const found = [];             // [start, newline] per line, newest first
  const grow = () => {
    if (lo === 0) return false;
    const from = size - Math.min(size, OLD_TAIL, Math.max(TAIL_STEP, (size - lo) * 2));
    if (from >= lo) return false;                       // as far back as it may look
    const more = Buffer.alloc(lo - from);
    if (fsImpl.readSync(fd, more, 0, more.length, from) !== more.length) return false;   // shrank
    buf = buf.length ? Buffer.concat([more, buf]) : more;
    lo = from;
    return true;
  };
  const step = () => {
    if (done) return false;
    while (nl < 0) {                                    // the file's last newline
      const at = buf.length ? buf.lastIndexOf(0x0a) : -1;
      if (at >= 0) nl = lo + at;
      else if (!grow()) { done = true; return false; }
    }
    let start;
    for (;;) {
      const from = nl - 1 - lo;
      const at = from >= 0 ? buf.lastIndexOf(0x0a, from) : -1;
      if (at >= 0) { start = lo + at + 1; break; }
      if (lo === 0) { start = 0; break; }
      if (!grow()) { done = true; return false; }       // begins before what we hold
    }
    if (nl > start && !isRequestLine(buf, start - lo, nl - lo)) found.push([start, nl]);
    if (start === 0) { done = true; atStart = true; } else nl = start - 1;
    return true;
  };
  return {
    window(want) {
      while (found.length < want && step());
      if (!found.length) return null;
      const lines = Math.min(want, found.length);
      return { start: found[lines - 1][0], until: found[0][1] + 1, lines, whole: atStart && found.length <= want };
    },
    close() { try { fsImpl.closeSync(fd); } catch { } },
  };
}

/** The newest tool call or line of prose among `events`, as the sheet shows it. */
function lastLineOf(events) {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.kind === 'tool') {
      return `${e.name || 'tool'}${e.detail ? ` ${e.detail}` : ''}`.slice(0, 120);
    }
    if ((e.kind === 'assistant' || e.kind === 'thinking') && e.text) {
      const line = e.text.trim().split('\n').find((l) => l.trim());
      if (line) return (e.kind === 'thinking' ? '…' : '') + line.trim().slice(0, 120);
    }
  }
  return null;
}

/**
 * What the agent is doing right now, from its transcript tail: the answer
 * `readTranscript(file, {limit: 6})` gives, from as little of the tail as holds
 * it (see the section head). `size` is the caller's stat, when it has one.
 */
function agentLastLine(file, fsImpl = fs, size = null) {
  let scan = null;
  try {
    scan = tailScanner(file, size == null ? fsImpl.statSync(file).size : size, fsImpl);
    for (const want of TAIL_TRIES) {
      const w = scan.window(want);
      if (!w) break;
      // The window-size probe readTranscript itself runs on a cold open: an
      // explicit window, read as part of a cold open rather than a resumption.
      const t = readTranscript(file, { offset: w.start, until: w.until, limit: 6, _resuming: false });
      const line = lastLineOf(t.events);
      if (line !== null || w.whole) return line;
      if (w.lines < want) break;                       // as far back as it may look
    }
    return lastLineOf(readTranscript(file, { limit: 6 }).events);
  } catch { } finally {
    if (scan) scan.close();
  }
  return null;
}

/**
 * What an agent's `.meta.json` says about it: the type the parent asked for and
 * how deep the spawn was. Absent for an agent whose meta never landed, which is
 * the only cheap tell that nothing can attribute it to a parent.
 */
function agentMeta(file, fsImpl = fs) {
  try {
    const d = JSON.parse(fsImpl.readFileSync(file.replace(/\.jsonl$/, '.meta.json'), 'utf8'));
    return d && typeof d === 'object' ? d : null;
  } catch { return null; }
}

// ---------------------------------------------------- what a file already said
//
// A listing used to derive every row from scratch on every call: the head read
// for the task, a tail parse for the last line, the sibling meta, and the whole
// run journal once per run. The stream picker asks for ALL of a session's
// agents each time a session is opened, and a finished agent never changes
// again, so nearly all of that work was the same answer read twice — 190 MB of
// reading per `?all=1` on a session with 167 agents (2026-10-20), with the
// daemon's one thread held for the whole of it.
//
// So each answer is remembered against the version of the file it came from:
// inode, size and mtime, the key `lastWorkAt` (lib/transcript.js) uses for the
// same reason. An unchanged file costs one stat. A grown one re-reads only what
// can have moved: the tail. The task is the FIRST record, and an append-only
// file never rewrites that once its newline has landed.

/** Files remembered. Each entry is two short strings and a few numbers. */
const FACTS_MAX = 4096;
/** Journals remembered: one per workflow run, each a few hundred ids. */
const JOURNALS_MAX = 256;

/** agent transcript path -> {ino, size, mtimeMs, task, taskFinal, lastLine} */
const facts = new Map();
/** `.meta.json` path -> {ino, size, mtimeMs, value} */
const metas = new Map();
/** journal path -> {ino, size, mtimeMs, at, index, view} */
const journals = new Map();

function remember(map, key, value, max) {
  map.delete(key);
  map.set(key, value);
  while (map.size > max) map.delete(map.keys().next().value);
}

function sameVersion(hit, st) {
  return !!hit && hit.ino === st.ino && hit.size === st.size && hit.mtimeMs === st.mtimeMs;
}

/**
 * The remembered facts for this version of an agent file, filling in what is
 * missing. `st` is the caller's own stat of the file — every caller has one
 * already, for the mtime and the size it reports.
 *
 * `lastLine` is computed only when asked for (`/graph` never shows it), and is
 * `undefined` on an entry that has not been asked yet. `read` says whether this
 * call touched the file at all, which is what a caller that yields keys on.
 */
function factsFor(file, st, fsImpl = fs, { lastLine = false } = {}) {
  let e = facts.get(file);
  let read = false;
  if (!sameVersion(e, st)) {
    const keep = !!e && e.taskFinal && e.ino === st.ino && st.size >= e.size;
    const head = keep ? { task: e.task, final: true } : readTaskHead(file, fsImpl);
    read = !keep;
    e = {
      ino: st.ino, size: st.size, mtimeMs: st.mtimeMs,
      task: head.task, taskFinal: head.final, lastLine: undefined,
    };
  }
  if (lastLine && e.lastLine === undefined) {
    e.lastLine = agentLastLine(file, fsImpl, st.size);
    read = true;
  }
  remember(facts, file, e, FACTS_MAX);
  return { facts: e, read };
}

/** agentTask, remembered per file version. */
function agentTaskCached(file, st, fsImpl = fs) {
  return factsFor(file, st, fsImpl).facts.task;
}

/**
 * agentMeta, remembered against the meta file's own stat. A meta lands on its
 * own schedule, a moment after the transcript starts, so a missing one is asked
 * for again on every call — which costs one failed stat.
 */
function agentMetaCached(file, fsImpl = fs) {
  const metaFile = file.replace(/\.jsonl$/, '.meta.json');
  let st;
  try { st = fsImpl.statSync(metaFile); } catch { metas.delete(metaFile); return null; }
  const hit = metas.get(metaFile);
  if (sameVersion(hit, st)) { remember(metas, metaFile, hit, FACTS_MAX); return hit.value; }
  const value = agentMeta(file, fsImpl);
  remember(metas, metaFile, { ino: st.ino, size: st.size, mtimeMs: st.mtimeMs, value }, FACTS_MAX);
  return value;
}

/**
 * journalIndex, remembered per file version and read INCREMENTALLY: a journal
 * is appended to as a run's agents start and settle, so a grown one is parsed
 * from where its last complete line ended. Smaller, rewritten in place (same
 * size, new mtime) or another inode is a different file, and is read whole.
 *
 * The answer is the one journalIndex gives for the same bytes, including its
 * one subtlety: a final line with no newline yet still counts when it parses.
 * That line is applied to a COPY, because it is not finished and the next read
 * starts again from its first byte.
 *
 * ⚠ The index is SHARED between callers: read it, never write to it.
 */
function journalIndexCached(journalFile, fsImpl = fs) {
  return journalIndexFor(journalFile, fsImpl).index;
}

/** journalIndexCached, saying whether it had to read the file. */
function journalIndexFor(journalFile, fsImpl = fs) {
  let st;
  try { st = fsImpl.statSync(journalFile); } catch { journals.delete(journalFile); return { index: emptyJournalIndex(), read: false }; }
  let e = journals.get(journalFile);
  if (sameVersion(e, st)) { remember(journals, journalFile, e, JOURNALS_MAX); return { index: e.view, read: false }; }
  if (!e || e.ino !== st.ino || st.size <= e.size) {
    e = { ino: st.ino, size: 0, mtimeMs: 0, at: 0, index: emptyJournalIndex(), view: null };
  }
  let buf = Buffer.alloc(0);
  if (st.size > e.at) {
    let fd = null;
    try {
      fd = fsImpl.openSync(journalFile, 'r');
      const want = Buffer.alloc(st.size - e.at);
      const n = fsImpl.readSync(fd, want, 0, want.length, e.at);
      buf = want.subarray(0, Math.max(0, n || 0));
    } catch {
      journals.delete(journalFile);
      return { index: emptyJournalIndex(), read: true };
    } finally {
      if (fd !== null) { try { fsImpl.closeSync(fd); } catch { } }
    }
  }
  const lastNl = buf.lastIndexOf(0x0a);
  if (lastNl >= 0) {
    for (const line of buf.toString('utf8', 0, lastNl).split('\n')) applyJournalLine(e.index, line);
    e.at += lastNl + 1;
  }
  e.view = e.index;
  const rest = buf.toString('utf8', lastNl + 1);
  if (rest.trim()) {
    let d = null;
    try { d = JSON.parse(rest); } catch { /* still being written */ }
    if (d && d.agentId) {
      const view = {
        summaries: new Map(e.index.summaries),
        settled: new Set(e.index.settled),
        failed: new Set(e.index.failed),
        started: new Set(e.index.started),
      };
      applyJournalRecord(view, d);
      e.view = view;
    }
  }
  e.size = st.size;
  e.mtimeMs = st.mtimeMs;
  remember(journals, journalFile, e, JOURNALS_MAX);
  return { index: e.view, read: true };
}

/** Forgets every remembered file. For tests. */
function resetAgentCaches() {
  facts.clear();
  metas.clear();
  journals.clear();
}

/**
 * A status a STREAM PICKER can trust, from the meta file and the mtime alone.
 *
 * Deliberately narrower than `/graph`'s: that one walks the parent transcript
 * to pair each spawn with its tool_result, which is the only place a DIRECT
 * agent's outcome is written (an agent leaves no completion marker in its own
 * file — see the header). This route must stay cheap enough to poll, so:
 *
 *   running  the file grew inside ACTIVE_S
 *   done     a workflow journal `result` line names it
 *   failed   a workflow journal `failed` line names it
 *   orphan   cold and unattributable: no `.meta.json`, or a workflow member the
 *            run journal never mentions
 *   null     cold, attributable, and only the parent's tool_result can say how
 *            it ended — `/graph` is where that answer lives, and guessing
 *            "done" here would put a wrong word on a live picker row.
 */
function agentStatus({ fresh, meta, workflow, journal, id }) {
  if (workflow && journal) {
    if (journal.failed.has(id)) return 'failed';
    if (journal.settled.has(id)) return 'done';
    if (fresh) return 'running';
    return journal.started.has(id) ? null : 'orphan';
  }
  if (fresh) return 'running';
  return meta ? null : 'orphan';
}

/**
 * All recent agents of one session, newest activity first.
 *
 * `opts.all` lifts the RECENT_S filter: the sheet answers "what is happening"
 * and settled agents stop being part of that answer, but a stream picker is
 * asking a different question — "what ran in this session" — and a 45-minute
 * window hides most of the answer to it.
 *
 * @returns {Array<{id, workflow, workflowId, agentType, status, depth, task,
 *                  lastLine, summary, active, updatedAt, startedAt, bytes}>}
 */
function listAgents(dir, nowSec, fsImpl = fs, max = 24, opts = {}) {
  const steps = listAgentsSteps(dir, nowSec, fsImpl, max, opts);
  for (;;) {
    const r = steps.next();
    if (r.done) return r.value;
  }
}

/**
 * listAgents without holding the event loop.
 *
 * The route used to call listAgents synchronously, and appd has one thread: a
 * cold `?all=1` on a session with a couple of hundred agents held every other
 * request — the session list, the screen polls, the transcript — until it was
 * done (a phone's cold open measured /screen and /v1/sessions completing in the
 * same instant as /agents, 3.5 s in). The answer is the same rows; the work is
 * the same work, cut where it can be cut: the loop gets a turn after every
 * file this call had to read, so the cost of one turn is one agent's head and
 * short tail, never a whole session's. A listing that reads nothing (every
 * file remembered) never yields at all.
 *
 * `opts.yieldFn` replaces the pause, for tests that count them.
 */
async function listAgentsAsync(dir, nowSec, fsImpl = fs, max = 24, opts = {}) {
  const pause = opts.yieldFn || nextTurn;
  const steps = listAgentsSteps(dir, nowSec, fsImpl, max, opts);
  for (;;) {
    const r = steps.next();
    if (r.done) return r.value;
    await pause();
  }
}

/** One turn of the event loop for everything else that is waiting. */
function nextTurn() {
  return new Promise((resolve) => setImmediate(resolve));
}

/**
 * The listing as steps: it yields after each read it had to make, and returns
 * the rows. Both drivers above run exactly this, so the sync and the async
 * listing cannot drift apart.
 */
function* listAgentsSteps(dir, nowSec, fsImpl, max, opts) {
  const files = listAgentFiles(dir, fsImpl);
  const out = [];
  // The caller's stat of each kept file, which is the version its remembered
  // facts are checked against. Beside the rows rather than on them: a row is
  // what goes on the wire.
  const stats = new Map();
  for (const f of files) {
    let st;
    try { st = fsImpl.statSync(f.file); } catch { continue; }
    const updatedAt = Math.floor(st.mtimeMs / 1000);
    if (!opts.all && nowSec - updatedAt > RECENT_S) continue;
    const row = {
      id: path.basename(f.file).replace(/^agent-|\.jsonl$/g, ''),
      workflow: f.workflow,
      updatedAt,
      startedAt: Math.floor((st.birthtimeMs || st.ctimeMs || st.mtimeMs) / 1000),
      bytes: st.size,
      active: nowSec - updatedAt <= ACTIVE_S,
      file: f.file,
    };
    stats.set(row, st);
    out.push(row);
  }
  out.sort((a, b) => b.updatedAt - a.updatedAt);
  const kept = out.slice(0, max);
  // Journal summaries, one per workflow run represented in the kept rows.
  // The journal sits beside the agent's OWN file rather than under an assumed
  // root — a run may have come from either workflow location (see workflowDirs).
  const runs = new Map();
  for (const a of kept) {
    if (!a.workflow || runs.has(a.workflow)) continue;
    const j = journalIndexFor(path.join(path.dirname(a.file), 'journal.jsonl'), fsImpl);
    runs.set(a.workflow, j.index);
    if (j.read) yield;
  }
  // The transcript reads are the expensive part; only the kept rows pay them,
  // and only for a file that changed since it was last read.
  for (const a of kept) {
    const { facts: known, read } = factsFor(a.file, stats.get(a), fsImpl, { lastLine: true });
    if (read) yield;
    a.task = known.task;
    a.lastLine = known.lastLine;
    const j = a.workflow ? runs.get(a.workflow) : null;
    a.summary = (j && j.summaries.get(a.id)) || null;
    const meta = agentMetaCached(a.file, fsImpl);
    // `workflowId` alongside `workflow`: the same fact under the name the rest
    // of the wire already uses for it (`/graph` agents[], sessiongraph:731).
    // `workflow` stays because clients on 2.88.0 read that one.
    a.workflowId = a.workflow;
    a.agentType = (meta && typeof meta.agentType === 'string') ? meta.agentType : null;
    // ⚠ A NUMBER, NEVER null — unlike `status`, which the client declares
    // nullable. `AgentRun.depth` is a non-nullable `Int` and nothing in the
    // client tree sets `coerceInputValues`, so ONE orphan agent (a file with no
    // `.meta.json`, which is the deliberate `orphan` case) failed the whole
    // decode: the stream picker AND the pre-existing work sheet, which has
    // called /agents since 2.x, both went blank. 0 is the right answer anyway —
    // an agent whose depth is unknown was spawned by the session itself as far
    // as anything downstream can tell.
    a.depth = (meta && typeof meta.spawnDepth === 'number') ? meta.spawnDepth : 0;
    a.status = agentStatus({ fresh: a.active, meta, workflow: a.workflow, journal: j, id: a.id });
    delete a.file;
  }
  return kept;
}

module.exports = {
  agentsDirFor, workflowDirs, listAgentFiles, agentTask, agentLastLine, agentMeta,
  journalSummaries, journalSettled, journalIndex, agentStatus, listAgents, listAgentsAsync,
  agentTaskCached, agentMetaCached, journalIndexCached, resetAgentCaches, nextTurn, isRequestLine,
  ACTIVE_S, RECENT_S,
};
