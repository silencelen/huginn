'use strict';
// The shape of a session, read off its transcript: what it has spent, how fast,
// and a map of what it actually did.
//
// This is the OTHER way to read a transcript. lib/transcript.js tails it for a
// conversation — the last few hundred events, newest first, because that is what
// a phone renders. Nothing there can answer "how many tokens has this session
// spent" or "which turn spawned that agent", because those questions are about
// the WHOLE file and the tail is deliberately not the whole file. So this walks
// forward from byte zero, keeps a running summary, and remembers where it
// stopped: the second call parses only what was appended since the first.
//
// ⚠ readTranscript's tail-only behaviour is load-bearing for the phone and is not
// touched here. The two share an idiom (a byte window, JSON.parse per line, a
// partial trailing line left for next time) and nothing else.
//
// WHAT THE FILE ACTUALLY CONTAINS (measured 2026-08-27 across three real
// transcripts on this host — 832KB / 15.5MB / 33.8MB, 4,942 assistant records):
//
//   • Records that share a `requestId` describe ONE API call and every one of
//     them carries an IDENTICAL `message.usage`. 1,378 multi-record requestIds,
//     1,378 with identical usage, zero differing. Summing per RECORD overcounted
//     cache reads by 2.4-2.7x (1,017,507,823 vs 530,134,922 on the 33.8MB file).
//     So usage is counted ONCE PER DISTINCT requestId.
//   • `usage.iterations[]` is a BREAKDOWN of that same call, not extra spend:
//     the sum of its output_tokens equalled the top-level output_tokens on
//     4,940 of 4,940 records carrying it. Adding it would double-count, so the
//     top-level numbers are used and iterations is read only for the count.
//   • `system/turn_duration.durationMs` is NOT this turn's duration. Observed:
//     30,942,368 ms (8.6h) and 69,980,631 ms (19.4h) on a session whose turns
//     were minutes long, both resolving back to the session's FIRST prompt. It
//     is used here as a boundary MARKER only; every duration on the wire is
//     computed from record timestamps, which cannot disagree with themselves.
//   • `<synthetic>` appears as a model id on records the CLI generates itself.
//     It is not a model anybody chose, so it is kept out of `models`.
//   • `usage.cache_creation` is a NESTED breakdown of the flat
//     `cache_creation_input_tokens` by TTL — {ephemeral_5m_input_tokens,
//     ephemeral_1h_input_tokens} — and the two halves summed to the flat total
//     on every record measured. It is read for pricing (the two TTLs bill at
//     1.25x and 2x input), but the FLAT number stays the source of truth: what
//     the halves do not account for is carried as `unsplit` rather than assigned
//     to a TTL nobody reported.

const fs = require('node:fs');
const path = require('node:path');
const { listAgentFiles, journalSummaries, journalSettled, agentTask, ACTIVE_S } = require('./agents');
const { priceTokens } = require('./pricing');

/** Bytes read per pass. Bounded so a 32MB transcript never lands in RAM whole. */
const CHUNK = 4 * 1024 * 1024;

/** Sessions kept warm. A phone and a desktop watching two sessions each, plus slack. */
const CACHE_MAX = 8;

/** Longest label that travels; the client ellipsizes, it does not need the rest. */
const LABEL_MAX = 80;
const USER_LABEL_MAX = 120;
const DETAIL_MAX = 400;

/** Tool names whose input names a file this session changed. */
const EDIT_TOOLS = new Set(['Edit', 'Write', 'NotebookEdit', 'MultiEdit']);

/** The rate windows, in seconds. */
const RATE_10M = 600;
const RATE_60M = 3600;

function clip(s, n) {
  const t = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
}

function tsOf(d) {
  if (!d || !d.timestamp) return null;
  const n = Date.parse(d.timestamp);
  return Number.isFinite(n) ? Math.floor(n / 1000) : null;
}

function zeroTokens() {
  return { input: 0, output: 0, cacheRead: 0, cacheCreation: 0 };
}

function addTokens(into, from) {
  into.input += from.input; into.output += from.output;
  into.cacheRead += from.cacheRead; into.cacheCreation += from.cacheCreation;
  return into;
}

// ------------------------------------------------------------ per-model spend
//
// A parallel accumulation to `tokens`, kept per MODEL because that is the only
// unit a price applies to: the same session can run opus for the work and haiku
// for a subagent, and one blended figure over both is not a number about
// anything. Deliberately NOT folded into zeroTokens(): that shape is on the wire
// under every node and every agent, and widening it would change the client's
// contract for a field only the pricing pass reads.

function zeroModelTokens() {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheCreation5m: 0,
    cacheCreation1h: 0,
    cacheCreationUnsplit: 0,
  };
}

/**
 * One counted call's cache-creation tokens, split by TTL.
 *
 * The flat `cache_creation_input_tokens` is the total that has always been
 * there; the nested `cache_creation` object is the breakdown. So the flat number
 * decides how much was written and the split only decides how it is priced —
 * anything the two named halves do not cover comes back as `unsplit`, which
 * lib/pricing.js charges at the cheaper of the two rates rather than picking a
 * TTL the record never claimed.
 */
function creationSplit(d, cacheCreation) {
  const cc = d && d.message && d.message.usage && d.message.usage.cache_creation;
  const c5m = (cc && cc.ephemeral_5m_input_tokens) || 0;
  const c1h = (cc && cc.ephemeral_1h_input_tokens) || 0;
  const named = c5m + c1h;
  return { c5m, c1h, unsplit: named >= cacheCreation ? 0 : cacheCreation - named };
}

/**
 * Adds one counted call to the per-model accumulation.
 *
 * A record with no model at all is filed under 'unknown' rather than dropped —
 * it lands in the estimate's `unpricedTokens`, which is the honest place for
 * spend nobody can attribute.
 */
function addModelUsage(byModel, model, tok, split) {
  const key = model || 'unknown';
  let m = byModel.get(key);
  if (!m) { m = zeroModelTokens(); byModel.set(key, m); }
  m.input += tok.input;
  m.output += tok.output;
  m.cacheRead += tok.cacheRead;
  m.cacheCreation5m += split.c5m;
  m.cacheCreation1h += split.c1h;
  m.cacheCreationUnsplit += split.unsplit;
  return m;
}

/**
 * Merges per-model accumulations into the plain object lib/pricing.js prices.
 *
 * ⚠ ALWAYS INTO A FRESH OBJECT. The session's own accumulation is cached across
 * polls; folding the agents' totals into it in place would add them again on
 * every five-second poll, and the estimate would climb on a session that had
 * stopped running — the same trap the spine nodes' `agents` array is assigned
 * rather than appended for.
 */
function mergeByModel(...maps) {
  const out = {};
  for (const map of maps) {
    for (const [model, t] of map) {
      const into = out[model] || (out[model] = zeroModelTokens());
      for (const k of Object.keys(into)) into[k] += t[k] || 0;
    }
  }
  return out;
}

/**
 * Reads whole lines appended since `from`, in bounded chunks.
 *
 * Returns where to resume, which is the byte after the LAST COMPLETE LINE — a
 * half-written record at the live end of the file is left for the next call
 * rather than parsed as garbage and skipped forever.
 *
 * The carry between chunks is a Buffer, not a string: a record boundary and a
 * UTF-8 character boundary are different things, and slicing a chunk into a
 * string first turns a multi-byte character straddling the seam into two
 * replacement characters inside a JSON string.
 */
function walkLines(file, from, to, onLine) {
  if (to <= from) return from;
  let fd;
  try { fd = fs.openSync(file, 'r'); } catch { return from; }
  let pos = from;
  let carry = Buffer.alloc(0);
  try {
    while (pos < to) {
      const want = Math.min(CHUNK, to - pos);
      const buf = Buffer.alloc(want);
      const n = fs.readSync(fd, buf, 0, want, pos);
      if (n <= 0) break;
      pos += n;
      const chunk = carry.length ? Buffer.concat([carry, buf.subarray(0, n)]) : buf.subarray(0, n);
      const lastNl = chunk.lastIndexOf(0x0a);
      if (lastNl === -1) { carry = chunk; continue; }
      const text = chunk.toString('utf8', 0, lastNl + 1);
      carry = Buffer.from(chunk.subarray(lastNl + 1));
      for (const line of text.split('\n')) {
        if (!line) continue;
        let d;
        try { d = JSON.parse(line); } catch { continue; }
        onLine(d);
      }
    }
  } finally { try { fs.closeSync(fd); } catch { } }
  return pos - carry.length;
}

// ------------------------------------------------------------------ usage

/**
 * One API call's usage, or null when this record repeats a call already counted.
 *
 * The dedupe set is the whole trick: see the requestId finding at the top.
 * Records without a requestId (rare, and none were seen in the three files
 * measured) fall back to their own uuid, which counts them once each rather
 * than collapsing them all into one.
 */
function usageOnce(d, seen) {
  const u = d && d.message && d.message.usage;
  if (!u || typeof u !== 'object') return null;
  const key = d.requestId || (d.uuid ? `u:${d.uuid}` : null);
  if (key) {
    if (seen.has(key)) return null;
    seen.add(key);
  }
  return {
    input: u.input_tokens || 0,
    output: u.output_tokens || 0,
    cacheRead: u.cache_read_input_tokens || 0,
    cacheCreation: u.cache_creation_input_tokens || 0,
  };
}

// ------------------------------------------------------------- spine state

function newTurn(ts) {
  return {
    startTs: ts,
    lastTs: ts,
    lastToolTs: null,
    toolCalls: 0,
    errors: 0,
    tools: new Map(),
    files: new Set(),
    firstText: null,
    tailText: null,
    tokensAction: zeroTokens(),
    tokensTail: zeroTokens(),
    models: new Set(),
  };
}

function newState(sessionId) {
  return {
    sessionId,
    parsedBytes: 0,
    nodes: [],
    turn: null,
    seenRequests: new Set(),
    totals: {
      turns: 0,
      userMessages: 0,
      toolCalls: 0,
      errors: 0,
      tokens: zeroTokens(),
      compactions: 0,
      droppedTokens: 0,
    },
    files: new Set(),
    models: new Set(),
    efforts: new Set(),
    // model id -> zeroModelTokens(), for the cost estimate. Same dedup as
    // `totals.tokens` — it is filled from the same `usageOnce` result.
    tokensByModel: new Map(),
    // tool_use id -> the spine node that issued it, for the agent join.
    spawns: new Map(),
    // tool_use id -> {ts, isError}, for the agent merge point.
    merges: new Map(),
    // workflow run id -> {nodeId, ts}, joined through the Workflow tool_result.
    workflows: new Map(),
    // {ts, written, all} per API call, pruned to the trailing rate window.
    samples: [],
    firstTs: null,
    lastTs: null,
  };
}

/** The tokens a rate is about: what this session WROTE, not what it re-read. */
function written(t) { return t.input + t.output + t.cacheCreation; }
function allOf(t) { return t.input + t.output + t.cacheCreation + t.cacheRead; }

function pushSample(s, ts, tok) {
  if (!ts) return;
  s.samples.push({ ts, written: written(tok), all: allOf(tok) });
  // Bounded by the widest window the rate ever asks for, so a 33MB transcript
  // holds a handful of samples rather than one per API call.
  const cut = ts - RATE_60M;
  if (s.samples.length > 512 && s.samples[0].ts < cut) {
    s.samples = s.samples.filter((x) => x.ts >= cut);
  }
}

/**
 * A synthesized label for a turn that produced no prose at all — which is most
 * of a long auto-mode run, where the model works for twenty minutes without
 * addressing anybody.
 */
function synthLabel(turn) {
  const parts = [`${turn.toolCalls} tool call${turn.toolCalls === 1 ? '' : 's'}`];
  if (turn.files.size) parts.push(`edited ${turn.files.size} file${turn.files.size === 1 ? '' : 's'}`);
  else {
    const top = [...turn.tools.entries()].sort((a, b) => b[1] - a[1])[0];
    if (top) parts.push(`mostly ${top[0]}`);
  }
  return parts.join(' · ');
}

function toolHistogram(turn) {
  return [...turn.tools.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)
    .map(([name, count]) => ({ name, count }));
}

/**
 * Closes the open turn into its spine nodes WITHOUT mutating state, so the same
 * function serves both the real close and the preview of a turn still running.
 *
 * The split is where the last tool call sits: everything up to it is the work
 * (an `action` block), and prose after it is the model turning back to the
 * person (a `response` block). A turn that only ever spoke is a response alone.
 */
function turnNodes(turn, baseIndex) {
  if (!turn) return [];
  const out = [];
  const idAt = (i) => `n${baseIndex + i}`;
  if (turn.toolCalls > 0) {
    const endTs = turn.lastToolTs || turn.lastTs;
    out.push({
      id: idAt(out.length),
      kind: 'action',
      ts: turn.startTs,
      endTs,
      durMs: turn.startTs && endTs ? Math.max(0, (endTs - turn.startTs) * 1000) : 0,
      label: turn.firstText ? clip(turn.firstText, LABEL_MAX) : synthLabel(turn),
      detail: toolHistogram(turn).map((t) => `${t.name}×${t.count}`).join(' · ') || null,
      tokens: turn.tokensAction,
      toolCalls: turn.toolCalls,
      tools: toolHistogram(turn),
      files: turn.files.size,
      errors: turn.errors,
      agents: [],
      models: [...turn.models],
    });
  }
  if (turn.tailText) {
    const startTs = turn.toolCalls > 0 ? (turn.lastToolTs || turn.startTs) : turn.startTs;
    out.push({
      id: idAt(out.length),
      kind: 'response',
      ts: startTs,
      endTs: turn.lastTs,
      durMs: startTs && turn.lastTs ? Math.max(0, (turn.lastTs - startTs) * 1000) : 0,
      label: clip(turn.tailText, LABEL_MAX),
      detail: clip(turn.tailText, DETAIL_MAX) || null,
      tokens: turn.tokensTail,
      toolCalls: 0,
      tools: [],
      files: 0,
      errors: 0,
      agents: [],
      models: [...turn.models],
    });
  }
  return out;
}

function closeTurn(s) {
  if (!s.turn) return;
  // ⚠ THE ONLY PLACE PER-TURN ERRORS BECOME A TOTAL, and it is why the roll-up
  // sits above the `made.length` check rather than inside it. Failures are
  // counted on the OPEN turn as tool_results arrive and the turn object is then
  // dropped, so `totals.errors` — which the overview card and the map header
  // both render — read zero for a session with a hundred failed tool calls. A
  // number that is always zero is worse than no number: it says the run went
  // fine.
  s.totals.errors += s.turn.errors;
  const made = turnNodes(s.turn, s.nodes.length);
  if (made.length) {
    for (const n of made) s.nodes.push(n);
    s.totals.turns++;
  }
  s.turn = null;
}

function ensureTurn(s, ts) {
  if (!s.turn) s.turn = newTurn(ts);
  return s.turn;
}

/**
 * A `user` record that a PERSON produced.
 *
 * Everything else wearing the user role — tool results, the injected skill and
 * reminder blocks (`isMeta`), Claude Code's own notifications, and the pane's
 * bash mode — is machinery. Counting those as messages made a session that had
 * been given one instruction look like a conversation of fourteen hundred.
 */
const MACHINE_USER = /^\s*(<(task-notification|system-reminder|command-name|command-message|command-args|local-command|bash-input|bash-stdout|bash-stderr)|\[SYSTEM NOTIFICATION|\[Image: original \d+x\d+|\[Your previous response|Caveat: The messages below)/;

function userText(d) {
  const c = d && d.message && d.message.content;
  if (typeof c === 'string') return c;
  if (!Array.isArray(c)) return '';
  if (c.some((b) => b && b.type === 'tool_result')) return null;      // machinery
  return c.filter((b) => b && b.type === 'text').map((b) => b.text || '').join(' ');
}

function firstLine(s) {
  for (const l of String(s || '').split('\n')) if (l.trim()) return l.trim();
  return '';
}

// -------------------------------------------------------------- the walker

function consume(s, d) {
  const ts = tsOf(d);
  if (ts) {
    if (!s.firstTs) s.firstTs = ts;
    s.lastTs = ts;
  }

  if (d.type === 'system') {
    if (d.subtype === 'compact_boundary') {
      closeTurn(s);
      const cm = d.compactMetadata || {};
      const dropped = cm.cumulativeDroppedTokens || Math.max(0, (cm.preTokens || 0) - (cm.postTokens || 0));
      s.totals.compactions++;
      s.totals.droppedTokens += dropped;
      s.nodes.push({
        id: `n${s.nodes.length}`,
        kind: 'compact',
        ts,
        endTs: ts,
        durMs: cm.durationMs || 0,
        label: cm.trigger === 'manual' ? 'Compacted (manual)' : 'Compacted',
        detail: `${(cm.preTokens || 0).toLocaleString('en-US')} → ${(cm.postTokens || 0).toLocaleString('en-US')} tokens`,
        tokens: zeroTokens(),
        toolCalls: 0,
        tools: [],
        files: 0,
        errors: 0,
        agents: [],
        models: [],
        pre: cm.preTokens ?? null,
        post: cm.postTokens ?? null,
        dropped,
      });
      return;
    }
    // A boundary marker, and only that — see the durationMs finding up top.
    if (d.subtype === 'turn_duration') closeTurn(s);
    return;
  }

  if (d.type === 'user') {
    const text = userText(d);
    if (text === null) {
      // A tool result. Its only contribution is whether the tool failed and how
      // far the turn's clock has run.
      const c = d.message && d.message.content;
      if (Array.isArray(c)) {
        for (const b of c) {
          if (!b || b.type !== 'tool_result' || !b.tool_use_id) continue;
          // Inside a turn it rides the turn and reaches the totals at close.
          // OUTSIDE one it is counted straight into the totals: a result landing
          // after a compact boundary (or a `turn_duration` marker) closed the
          // turn it belonged to still describes a tool that failed, and the
          // alternative to counting it here is not counting it at all.
          if (b.is_error === true) { if (s.turn) s.turn.errors++; else s.totals.errors++; }
          if (!s.spawns.has(b.tool_use_id)) continue;
          s.merges.set(b.tool_use_id, { ts, isError: b.is_error === true });
          // The Workflow tool_result is the ONE place the parent names the run
          // directory its agents will write into, so it is the only honest join
          // between a workflow's transcripts and the turn that launched it.
          const sp = s.spawns.get(b.tool_use_id);
          if (sp.kind === 'workflow') {
            const body = typeof b.content === 'string' ? b.content
              : Array.isArray(b.content) ? b.content.map((x) => (x && x.text) || '').join(' ') : '';
            const run = /(wf_[A-Za-z0-9_-]+)/.exec(body);
            if (run) s.workflows.set(run[1], { nodeId: sp.nodeId, ts: sp.ts });
          }
        }
      }
      if (s.turn && ts) s.turn.lastTs = ts;
      return;
    }
    if (d.isMeta === true || MACHINE_USER.test(text) || !text.trim()) {
      if (s.turn && ts) s.turn.lastTs = ts;
      return;
    }
    closeTurn(s);
    s.totals.userMessages++;
    s.nodes.push({
      id: `n${s.nodes.length}`,
      kind: 'user',
      ts,
      endTs: ts,
      durMs: 0,
      label: clip(firstLine(text), USER_LABEL_MAX),
      detail: clip(text, DETAIL_MAX) || null,
      tokens: zeroTokens(),
      toolCalls: 0,
      tools: [],
      files: 0,
      errors: 0,
      agents: [],
      models: [],
    });
    s.turn = newTurn(ts);
    return;
  }

  if (d.type !== 'assistant') return;

  const turn = ensureTurn(s, ts);
  if (ts) turn.lastTs = ts;
  const model = d.message && d.message.model;
  if (model && model !== '<synthetic>') { s.models.add(model); turn.models.add(model); }
  if (d.effort) s.efforts.add(d.effort);

  const tok = usageOnce(d, s.seenRequests);
  if (tok) {
    addTokens(s.totals.tokens, tok);
    addTokens(turn.tokensTail, tok);
    // Off the SAME deduped result, so the priced tokens and the displayed
    // tokens can never be two different numbers about one API call.
    addModelUsage(s.tokensByModel, model, tok, creationSplit(d, tok.cacheCreation));
    pushSample(s, ts, tok);
  }

  const content = d.message && d.message.content;
  if (!Array.isArray(content)) return;
  for (const b of content) {
    if (!b) continue;
    if (b.type === 'text' && b.text && b.text.trim()) {
      const line = firstLine(b.text);
      if (line) {
        if (!turn.firstText) turn.firstText = line;
        turn.tailText = turn.tailText ? `${turn.tailText} ${line}` : line;
      }
      continue;
    }
    if (b.type !== 'tool_use') continue;
    // Prose BEFORE a tool call is the intent of the work; prose after the LAST
    // one is the model addressing the reader. Only the second is a response, so
    // the tail resets every time work resumes.
    turn.tailText = null;
    addTokens(turn.tokensAction, turn.tokensTail);
    turn.tokensTail = zeroTokens();
    turn.toolCalls++;
    s.totals.toolCalls++;
    turn.lastToolTs = ts;
    const name = b.name || 'tool';
    turn.tools.set(name, (turn.tools.get(name) || 0) + 1);
    const input = b.input || {};
    if (EDIT_TOOLS.has(name) && typeof input.file_path === 'string') {
      turn.files.add(input.file_path);
      s.files.add(input.file_path);
    }
    if (b.id && (name === 'Agent' || name === 'Task')) {
      // The node this belongs to is not built yet; its id is where the turn's
      // action block will land, which is knowable because nodes only append.
      s.spawns.set(b.id, {
        kind: 'agent',
        nodeId: `n${s.nodes.length}`,
        ts,
        description: typeof input.description === 'string' ? clip(input.description, 160) : null,
        agentType: typeof input.subagent_type === 'string' ? input.subagent_type : null,
      });
    }
    if (b.id && name === 'Workflow') {
      s.spawns.set(b.id, { kind: 'workflow', nodeId: `n${s.nodes.length}`, ts });
    }
  }
}

// --------------------------------------------------------------- the agents

/**
 * One agent transcript's cost, walked the same way and cached the same way.
 *
 * Agents are where a fan-out session actually spends: a parent that issued six
 * Agent calls has six transcripts roughly the size of its own. Re-reading all of
 * them on a five-second poll is the difference between this surface being free
 * and it being the most expensive thing the daemon does, so each file keeps its
 * own resume point and a poll costs only what was appended.
 */
function agentStats(cache, file, size) {
  const hit = cache.get(file);
  const st = hit && hit.parsedBytes <= size ? hit : {
    parsedBytes: 0, seen: new Set(), tokens: zeroTokens(), byModel: new Map(), toolCalls: 0,
    firstTs: null, lastTs: null, meta: null, task: null,
  };
  // The meta file and the task line live outside the append window — one is a
  // sibling, the other is the file's HEAD — so they are read beside the walk and
  // kept. Retried while empty rather than cached as empty: an agent directory is
  // created a moment before it is populated, and a null taken in that moment
  // would be the label for the rest of the run.
  //
  // ⚠ THE RETRY MUST SIT ABOVE THE EQUAL-SIZE EARLY RETURN, which is where it
  // did NOT sit. `.meta.json` is a SIBLING of the transcript and lands on its
  // own schedule, so the ordinary case is a first poll that reads an agent with
  // bytes already written and no meta yet — and every poll after that returned
  // on size alone without ever looking again. The retry the comment promises
  // could only ever run on a poll that also found new bytes, which for a
  // finished agent is never: it kept the null forever, and the branch was drawn
  // with no name and no parent for the rest of the session.
  if (!st.meta || !Object.keys(st.meta).length) st.meta = readAgentMeta(file);
  if (!st.task) st.task = agentTask(file);
  // Only NOW is an unchanged file free. Cheap by construction: what runs above
  // is one small sibling read (and one HEAD read) for agents still missing them,
  // and nothing at all for the ones that have both — which is all of them, a
  // moment after they start.
  if (hit && hit.parsedBytes === size) return hit;
  st.parsedBytes = walkLines(file, st.parsedBytes, size, (d) => {
    const ts = tsOf(d);
    if (ts) { if (!st.firstTs) st.firstTs = ts; st.lastTs = ts; }
    if (d.type !== 'assistant') return;
    const tok = usageOnce(d, st.seen);
    if (tok) {
      addTokens(st.tokens, tok);
      // An agent picks its own model — a haiku Explore under an opus parent is
      // the ordinary shape of a fan-out — so its spend is priced against the
      // model ITS records name, never the parent's.
      addModelUsage(st.byModel, d.message && d.message.model, tok, creationSplit(d, tok.cacheCreation));
    }
    const c = d.message && d.message.content;
    if (Array.isArray(c)) for (const b of c) if (b && b.type === 'tool_use') st.toolCalls++;
  });
  cache.set(file, st);
  return st;
}

function readAgentMeta(file) {
  try {
    return JSON.parse(fs.readFileSync(file.replace(/\.jsonl$/, '.meta.json'), 'utf8')) || {};
  } catch { return {}; }
}

/**
 * Every agent this session spawned, joined to the spine.
 *
 * A DIRECT agent's `.meta.json` carries the parent's `Agent` tool_use id, so the
 * join is exact in both directions: the tool_use says where it branched, the
 * matching tool_result says where it came back. A WORKFLOW member's meta carries
 * no such id — it is `{agentType:"workflow-subagent", spawnDepth:1}` and nothing
 * else — so those hang off the workflow run, which is joined to its turn through
 * the run directory named in the Workflow tool_result.
 *
 * An agent with neither join is `orphan` rather than dropped: it ran, it cost
 * tokens, and a map that silently omits work is worse than one with a loose end.
 */
function collectAgents(dir, state, nodes, agentCache, nowSec) {
  const files = listAgentFiles(dir);
  const agents = [];
  const runs = new Map();
  const agentModelMaps = [];
  let agentBytes = 0;
  const summaries = new Map();
  const settled = new Map();
  for (const f of files) {
    let st;
    try { st = fs.statSync(f.file); } catch { continue; }
    agentBytes += st.size;
    const id = path.basename(f.file).replace(/^agent-|\.jsonl$/g, '');
    const stats = agentStats(agentCache, f.file, st.size);
    agentModelMaps.push(stats.byModel);
    const meta = stats.meta || {};
    const mtime = Math.floor(st.mtimeMs / 1000);
    const spawn = meta.toolUseId ? state.spawns.get(meta.toolUseId) : null;
    const merge = meta.toolUseId ? state.merges.get(meta.toolUseId) : null;
    const wf = f.workflow ? state.workflows.get(f.workflow) : null;
    if (f.workflow && !summaries.has(f.workflow)) {
      const journal = path.join(path.dirname(f.file), 'journal.jsonl');
      summaries.set(f.workflow, journalSummaries(journal));
      settled.set(f.workflow, journalSettled(journal));
    }
    const summary = f.workflow ? (summaries.get(f.workflow) || new Map()).get(id) || null : null;
    // An agent leaves no completion marker in its OWN file (see lib/agents.js),
    // so finishing is something a third party has to say. A direct agent's
    // epitaph is the parent's tool_result; a workflow member's is a `result`
    // line in the run journal, which its parent never sees because the Workflow
    // tool returns the moment the run is launched. Neither, and still growing,
    // is running. Neither, and cold, is an agent that stopped without being
    // collected — which is worth a different word from "done".
    const done = merge || (f.workflow && (settled.get(f.workflow) || new Set()).has(id));
    const status = merge && merge.isError ? 'failed'
      : done ? 'done'
        : nowSec - mtime <= ACTIVE_S ? 'running'
          : (spawn || wf) ? 'stalled' : 'orphan';
    const spawnTs = spawn ? spawn.ts : (wf ? wf.ts : stats.firstTs);
    if (f.workflow && !runs.has(f.workflow)) {
      runs.set(f.workflow, {
        id: f.workflow,
        nodeId: wf ? wf.nodeId : null,
        ts: wf ? wf.ts : stats.firstTs,
        members: 0,
      });
    }
    if (f.workflow) runs.get(f.workflow).members++;
    agents.push({
      id,
      agentType: meta.agentType || null,
      description: (spawn && spawn.description) || summary || stats.task || null,
      summary,
      spawnNodeId: spawn ? spawn.nodeId : (wf ? wf.nodeId : null),
      spawnTs,
      mergeNodeId: merge ? nodeAt(nodes, merge.ts) : null,
      mergeTs: merge ? merge.ts : null,
      status,
      tokens: stats.tokens,
      toolCalls: stats.toolCalls,
      durMs: stats.firstTs && stats.lastTs ? (stats.lastTs - stats.firstTs) * 1000 : 0,
      updatedAt: mtime,
      workflowId: f.workflow,
      depth: typeof meta.spawnDepth === 'number' ? meta.spawnDepth : 1,
    });
  }
  agents.sort((a, b) => (a.spawnTs || 0) - (b.spawnTs || 0) || a.id.localeCompare(b.id));
  return { agents, workflows: [...runs.values()], agentBytes, agentModelMaps };
}

/**
 * The spine node a moment in time belongs to — where a returning agent rejoins.
 *
 * Over the FULL node list including the turn still in flight, which is the
 * common case: an agent that comes back during the turn that spawned it merges
 * into a block that has not been closed yet, and matching against the closed
 * nodes alone rejoined it to the user message above.
 */
function nodeAt(nodes, ts) {
  if (!ts) return null;
  let best = null;
  for (const n of nodes) {
    if (n.ts != null && n.ts <= ts) best = n.id; else break;
  }
  return best;
}

// ----------------------------------------------------------------- the wire

function rateOf(state, nowSec) {
  // Anchored on the last API CALL, not on the last record in the file. Measured
  // on a real transcript: its final record is an `attachment` written 108 minutes
  // after the last assistant record, so a window ending at the file's last
  // timestamp contained no spend at all and the screen reported a rate of zero
  // for a session that had been burning 40k/min while it worked. `activeRecently`
  // is the field that answers "is this rate current" — it is still measured
  // against the wall clock.
  const lastSample = state.samples.length ? state.samples[state.samples.length - 1].ts : 0;
  const last = lastSample || state.lastTs || 0;
  const win = (secs, field) => {
    if (!state.samples.length) return 0;
    const cut = last - secs;
    let sum = 0;
    for (const x of state.samples) if (x.ts >= cut) sum += x[field];
    const span = Math.max(60, Math.min(secs, last - (state.firstTs || last)));
    return Math.round((sum / span) * 60);
  };
  return {
    tokensPerMin10: win(RATE_10M, 'written'),
    tokensPerMin60: win(RATE_60M, 'written'),
    allTokensPerMin10: win(RATE_10M, 'all'),
    allTokensPerMin60: win(RATE_60M, 'all'),
    lastActivityTs: state.lastTs,
    activeRecently: !!(state.lastTs && nowSec - state.lastTs <= ACTIVE_S),
  };
}

function buildWire(state, dir, agentCache, nowSec, size) {
  const preview = turnNodes(state.turn, state.nodes.length);
  const nodes = preview.length ? state.nodes.concat(preview) : state.nodes;
  const { agents, workflows, agentBytes, agentModelMaps } = dir
    ? collectAgents(dir, state, nodes, agentCache, nowSec)
    : { agents: [], workflows: [], agentBytes: 0, agentModelMaps: [] };
  const agentTokens = zeroTokens();
  for (const a of agents) addTokens(agentTokens, a.tokens);
  // The estimate covers the WHOLE session — the parent's own calls and every
  // agent it spawned — because that is what a person means by "what did this
  // cost". `agentEstCostUsd` is the share of it that came from agent files, so a
  // fan-out can say how much of the bill it was.
  //
  // Null only when nothing carried usage at all. A session that ran entirely on
  // models this table has never seen still gets an object, with usd 0 and the
  // tokens counted in `unpricedTokens` — null there would drop the one fact
  // worth knowing, which is that there is spend nobody could price.
  const byModel = mergeByModel(state.tokensByModel, ...agentModelMaps);
  const estCost = Object.keys(byModel).length ? priceTokens(byModel) : null;
  // The branches are read off the AGENTS, not built during the walk: which node
  // an agent hangs from is known only once its meta file has been matched to a
  // tool_use, which happens here. Assigned fresh rather than appended to — the
  // spine nodes are cached objects and would otherwise collect a duplicate set
  // of branches on every poll.
  const byNode = new Map();
  for (const a of agents) {
    if (!a.spawnNodeId) continue;
    if (!byNode.has(a.spawnNodeId)) byNode.set(a.spawnNodeId, []);
    byNode.get(a.spawnNodeId).push(a.id);
  }
  for (const n of nodes) n.agents = byNode.get(n.id) || [];
  // The turn still in flight is PREVIEWED, not closed, so its failures have not
  // reached state.totals yet — added here for exactly the reason `turns` is, and
  // so the header and the nodes under it never disagree while a turn is running.
  let previewErrors = 0;
  for (const n of preview) previewErrors += n.errors || 0;
  return {
    v: 1,
    sessionId: state.sessionId,
    generatedAt: nowSec,
    totals: {
      wallMs: state.firstTs && state.lastTs ? (state.lastTs - state.firstTs) * 1000 : 0,
      startedAt: state.firstTs,
      lastActivityTs: state.lastTs,
      turns: state.totals.turns + (preview.length ? 1 : 0),
      userMessages: state.totals.userMessages,
      toolCalls: state.totals.toolCalls,
      errors: state.totals.errors + previewErrors,
      tokens: { ...state.totals.tokens },
      agentCount: agents.length,
      agentTokens,
      estCost,
      agentEstCostUsd: estCost ? priceTokens(mergeByModel(...agentModelMaps)).usd : null,
      activeAgents: agents.filter((a) => a.status === 'running').length,
      compactions: state.totals.compactions,
      droppedTokens: state.totals.droppedTokens,
      filesTouched: state.files.size,
      models: [...state.models],
      efforts: [...state.efforts],
    },
    rate: rateOf(state, nowSec),
    nodes,
    agents,
    workflows,
    cursor: { size, agentBytes },
  };
}

// ---------------------------------------------------------------- the cache

/** claudeSessionId -> {file, state, agentCache}. Plain Map, LRU by insertion. */
const cache = new Map();

function touch(key, value) {
  cache.delete(key);
  cache.set(key, value);
  while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
}

/**
 * The graph for one session, parsing only what is new since last time.
 *
 * A transcript is append-only, so equal size means an unchanged answer and a
 * larger size means the tail is the only thing worth reading. SHRUNK is the case
 * worth naming: it is not corruption, it is a different session that took the
 * same path, and the only correct response is to forget everything.
 */
function sessionGraph(file, sessionId, { now = Date.now() } = {}) {
  let st;
  try { st = fs.statSync(file); } catch { return null; }
  const nowSec = Math.floor(now / 1000);
  const key = sessionId || file;
  let entry = cache.get(key);
  if (!entry || entry.file !== file || entry.state.parsedBytes > st.size) {
    entry = { file, state: newState(sessionId || null), agentCache: new Map() };
  }
  if (entry.state.parsedBytes < st.size) {
    entry.state.parsedBytes = walkLines(file, entry.state.parsedBytes, st.size,
      (d) => consume(entry.state, d));
  }
  touch(key, entry);
  const dir = sessionId ? path.join(path.dirname(file), sessionId, 'subagents') : null;
  return buildWire(entry.state, dir, entry.agentCache, nowSec, st.size);
}

/** Totals and rate without the map — what the overview card needs, and no more. */
function sessionOverview(file, sessionId, opts) {
  const g = sessionGraph(file, sessionId, opts);
  if (!g) return null;
  const { nodes, agents, workflows, ...rest } = g;
  return rest;
}

function resetCache() { cache.clear(); }

module.exports = {
  sessionGraph, sessionOverview, resetCache,
  walkLines, usageOnce, turnNodes, newState, consume, buildWire, clip,
  CACHE_MAX, LABEL_MAX,
};
