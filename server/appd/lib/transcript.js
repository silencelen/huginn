'use strict';
// Reads a Claude Code transcript (~/.claude/projects/<slug>/<session>.jsonl) and
// normalizes it into the event stream the app renders.
//
// This is the primary render path for BOTH surfaces: a phone chat and a tmux
// session both have a transcript here, so thinking, tool calls, subagent output
// and workflow runs come from structured data rather than from scraping a TUI.
// Screen capture is kept only for live interaction, not for content.
//
// Transcripts are append-only, so tailing is a byte offset: callers pass the
// `nextOffset` from the previous response and get only what is new.

const fs = require('node:fs');

// A cold open reads at most this much from the END of the file. A long session's
// transcript reaches many MB and a phone neither needs nor wants the head of it.
const TAIL_BYTES = 256 * 1024;
// Fewer events than this in the tail window means the window was mostly one
// giant row (an embedded image) and must grow. Small on purpose: it only has
// to distinguish "a screenful of conversation" from "half a base64 blob".
const MIN_TAIL_EVENTS = 12;

/** Trims a tool input down to the one field a human would want to see. */
function digestToolInput(input, limit = 400) {
  if (input == null) return '';
  if (typeof input === 'string') return clip(input, limit);
  if (typeof input !== 'object') return String(input);
  const pick = input.command ?? input.file_path ?? input.pattern ?? input.url ??
    input.query ?? input.prompt ?? input.description ?? input.path;
  if (typeof pick === 'string') return clip(pick, limit);
  try { return clip(JSON.stringify(input), limit); } catch { return ''; }
}

/**
 * AskUserQuestion input, reduced to what a card needs. Defensive throughout:
 * this shape belongs to the CLI and may grow fields; a question we cannot parse
 * falls back to the generic tool card rather than a broken one.
 */
function parseAsk(input) {
  if (!input || !Array.isArray(input.questions) || !input.questions.length) return null;
  const questions = [];
  for (const q of input.questions.slice(0, 4)) {
    if (!q || typeof q.question !== 'string' || !q.question.trim()) continue;
    questions.push({
      question: clip(q.question.trim(), 240),
      header: typeof q.header === 'string' ? clip(q.header, 24) : null,
      multiSelect: q.multiSelect === true,
      options: Array.isArray(q.options)
        ? q.options.slice(0, 6).map((o) => clip(
            typeof o === 'string' ? o : (o && o.label) || '', 80,
          )).filter(Boolean)
        : [],
    });
  }
  return questions.length ? { questions } : null;
}

/**
 * Machine-generated text that Claude Code injects into the conversation
 * (background-task notifications, hook output). It is real input to the model
 * but it is not something a person said, so it is shown as a one-line note
 * rather than a message bubble.
 */
function machineText(s) {
  // The bracketed preamble is the newer wrapper around task notifications:
  // "[SYSTEM NOTIFICATION - NOT USER INPUT] …" followed by the tagged element.
  // Without matching it, the whole notification rendered as a message the user
  // supposedly sent.
  // "[Image: original 1530x2048, displayed at 1494x2000. Multiply…]" is the
  // caption Claude Code writes beside an image it ingested — coordinate-mapping
  // instructions for the MODEL. Rendered as a user bubble it reads like the
  // owner recited camera metadata at their own phone.
  // "[Your previous response had no visible output…]" is Claude Code prodding
  // the model after an empty turn. Found in real transcripts on this host; it
  // rendered as a bubble the owner appeared to have typed.
  //
  // Deliberately a list of KNOWN openings rather than "any bracketed line":
  // this app's own attachment markers ("[Attached image at …]") are bracketed
  // too and ARE the user's message, so a blanket rule would silently swallow
  // every photo and file the owner sends.
  return /^\s*(<(task-notification|system-reminder|command-name|command-message|command-args|local-command)|\[SYSTEM NOTIFICATION|\[Image: original \d+x\d+|\[Your previous response)/.test(s);
}

/**
 * Turns Claude Code's slash-command bookkeeping into something worth reading, or
 * into nothing.
 *
 * Running `/model fable` writes THREE separate `user` records: a caveat telling
 * the model to ignore what follows, the command itself wrapped in tags, and the
 * command's stdout. Rendered verbatim they look like three garbled messages the
 * user never sent — which is exactly how it looked in the app.
 *
 * @returns {{kind: string, text: string}|null} null means "show nothing"
 */
function describeMachineText(s) {
  // Pure plumbing aimed at the model, not at a reader.
  if (/^\s*<local-command-caveat/.test(s)) return null;
  if (/^\s*<system-reminder/.test(s)) return null;

  const cmd = /<command-name>\s*([^<]+?)\s*<\/command-name>/.exec(s);
  if (cmd) {
    const args = /<command-args>\s*([^<]*?)\s*<\/command-args>/.exec(s);
    const text = args && args[1] ? `${cmd[1]} ${args[1]}` : cmd[1];
    return { kind: 'command', text: text.trim() };
  }

  const out = /<local-command-stdout>([\s\S]*?)<\/local-command-stdout>/.exec(s);
  if (out) {
    const body = stripAnsiText(out[1]).trim();
    return body ? { kind: 'command_result', text: clip(body, 300) } : null;
  }

  // The image-ingestion caption: the Read card above it already says an image
  // was viewed, and coordinate-mapping instructions add nothing for a person.
  if (/^\s*\[Image: original \d+x\d+/.test(s)) return null;

  // A nudge aimed at the model after an empty turn; nothing for a reader to do.
  if (/^\s*\[Your previous response/.test(s)) return null;

  if (/<task-notification/.test(s) || /^\s*\[SYSTEM NOTIFICATION/.test(s)) {
    // The summary is written for exactly this purpose; use it.
    const sum = /<summary>\s*([\s\S]*?)\s*<\/summary>/.exec(s);
    return { kind: 'system', text: sum ? clip(sum[1].replace(/\s+/g, ' '), 160) : 'background task reported back' };
  }
  return { kind: 'system', text: 'injected input' };
}

/**
 * Whatever the user actually wrote in a record that also carries command
 * plumbing. Dropping a record wholesale because it STARTS with a machine tag
 * would silently swallow a real message if the two were ever concatenated —
 * the worst failure this file can have, and cheap to rule out.
 */
function humanRemainder(s) {
  const stripped = String(s)
    .replace(/<local-command-caveat>[\s\S]*?<\/local-command-caveat>/g, '')
    .replace(/<local-command-stdout>[\s\S]*?<\/local-command-stdout>/g, '')
    .replace(/<command-(name|message|args)>[\s\S]*?<\/command-\1>/g, '')
    // The bracketed preamble FIRST, while its terminating tag still exists —
    // stripped after the element it would run to end-of-string and eat any real
    // message concatenated behind the notification.
    .replace(/\[SYSTEM NOTIFICATION - NOT USER INPUT\][\s\S]*?(?=<task-notification|$)/g, '')
    // Then the WHOLE element, not just its tags: stripping tags alone left the
    // inner task-id/summary text to render as a message the user supposedly wrote.
    .replace(/<task-notification>[\s\S]*?<\/task-notification>/g, '')
    .replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '')
    .replace(/<\/?(task-notification|system-reminder)[^>]*>/g, '')
    .trim();
  // Short leftovers are tag debris, not prose.
  return stripped.length >= 12 ? stripped : '';
}

const ESC_RE = /\u001B\[[0-9;?]*[a-zA-Z]/g;
function stripAnsiText(s) { return String(s).replace(ESC_RE, ''); }

function clip(s, n) {
  s = String(s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** Text out of a content field that may be a string or a block array. */
function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b) => b && typeof b === 'object' && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('\n');
}

/**
 * Workflow scripts start with `export const meta = {name: '...'}`. Pulling the
 * name out turns an opaque 4KB script blob into a legible card title.
 */
function workflowName(script) {
  if (typeof script !== 'string') return null;
  const m = /name:\s*['"`]([^'"`]{1,80})['"`]/.exec(script);
  return m ? m[1] : null;
}

/**
 * @returns {{events: Array, nextOffset: number, truncated: boolean, title: string|null,
 *            permissionMode: string|null, model: string|null, gitBranch: string|null,
 *            cwd: string|null, lastActivityTs: number|null}}
 */
function readTranscript(path, { offset = null, limit = 400 } = {}) {
  const st = fs.statSync(path);
  let start = offset;
  let truncated = false;
  if (start == null || start < 0 || start > st.size) {
    // Tail by EVENTS, not by a fixed byte count. A transcript row that Read an
    // image carries the whole image as base64 — megabytes per photo — so a
    // fixed window measured in bytes holds an arbitrary, sometimes zero, number
    // of turns. Measured on a real chat: two photos made the file 2.1MB, the
    // 256KB tail started mid-turn-2, and the app rendered the second exchange
    // only — which read, from the phone, as sending a photo having DELETED the
    // conversation above it. The window doubles until it holds a useful number
    // of events or spans the whole file.
    let win = TAIL_BYTES;
    for (;;) {
      start = Math.max(0, st.size - win);
      truncated = start > 0;
      if (start === 0) break;
      const probe = readTranscript(path, { offset: start, limit });
      if (probe.events.length >= Math.min(limit, MIN_TAIL_EVENTS)) break;
      win *= 2;
    }
  }

  let buf = Buffer.alloc(0);
  if (st.size > start) {
    const fd = fs.openSync(path, 'r');
    try {
      const len = st.size - start;
      buf = Buffer.alloc(len);
      fs.readSync(fd, buf, 0, len, start);
    } finally { fs.closeSync(fd); }
  }

  let consumed = buf.length;
  let text = buf.toString('utf8');
  // A concurrent writer can leave a partial final line; leave it for next time.
  const lastNl = text.lastIndexOf('\n');
  if (lastNl === -1) {
    if (offset != null) return emptyResult(start, truncated);
  } else if (lastNl !== text.length - 1) {
    const keep = Buffer.byteLength(text.slice(0, lastNl + 1), 'utf8');
    consumed = keep;
    text = text.slice(0, lastNl + 1);
  }
  let lines = text.split('\n').filter((l) => l.length > 0);
  // A tail read almost certainly starts mid-line; that fragment is not JSON.
  if (truncated && lines.length && offset == null) lines = lines.slice(1);

  const out = {
    events: [],
    nextOffset: start + consumed,
    truncated,
    title: null,
    permissionMode: null,
    model: null,
    gitBranch: null,
    cwd: null,
    effort: null,
    lastActivityTs: null,
  };

  // tool_use id -> the event we appended, so a later tool_result can complete it
  // in place instead of arriving as a separate orphan card.
  const pendingTools = new Map();
  // Queued messages by content, so a later `remove` can mark the same event
  // delivered instead of adding a duplicate.
  // content -> FIFO of still-queued events with that exact text. A plain
  // one-event-per-content map lost a duplicate: sending "ok" twice while Claude
  // was busy overwrote the first event, leaving it stuck with a `queued` badge
  // forever, and the second `remove` then found nothing and pushed a THIRD
  // bubble. Two sends, three messages, one of them permanently "queued".
  const queued = new Map();
  let seq = 0;

  for (const line of lines) {
    let d;
    try { d = JSON.parse(line); } catch { continue; }
    const ts = d.timestamp ? Math.floor(Date.parse(d.timestamp) / 1000) || null : null;
    if (ts) out.lastActivityTs = ts;
    if (d.gitBranch) out.gitBranch = d.gitBranch;
    if (d.cwd) out.cwd = d.cwd;
    const sidechain = d.isSidechain === true;

    switch (d.type) {
      case 'queue-operation': {
        // A message typed while Claude is mid-turn is QUEUED, and a queued
        // message is written ONLY as these records — it never becomes a `user`
        // record, even after it is delivered. Dropping them (as v2.2.0 did) made
        // every follow-up message invisible in the conversation while still
        // being visible in the pane, which is exactly what a user notices.
        const content = typeof d.content === 'string' ? d.content : '';
        if (d.operation === 'enqueue') {
          if (!content.trim()) continue;
          let ev;
          if (machineText(content)) {
            const d2 = describeMachineText(content);
            if (!d2) continue;
            ev = { seq: ++seq, kind: d2.kind, ts, sidechain, text: d2.text };
          } else {
            ev = { seq: ++seq, kind: 'user', ts, sidechain, text: content, queued: true };
          }
          out.events.push(ev);
          if (ev.kind === 'user') {
            const pending = queued.get(content) || [];
            pending.push(ev);
            queued.set(content, pending);
          }
        } else if (d.operation === 'remove') {
          // Delivered into the turn: it is now an ordinary message.
          // Oldest first: the queue delivers in send order, so the first
          // still-pending copy is the one being delivered now.
          const pending = queued.get(content);
          const ev = pending && pending.length ? pending.shift() : null;
          if (pending && pending.length === 0) queued.delete(content);
          if (ev) { delete ev.queued; }
          else if (content.trim() && !machineText(content)) {
            // The enqueue happened before the window we read.
            out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: content });
          }
        }
        // `dequeue` carries no content and just means the queue drained.
        continue;
      }
      case 'ai-title':
        if (d.aiTitle) out.title = d.aiTitle;
        continue;
      case 'permission-mode':
        if (d.permissionMode) out.permissionMode = d.permissionMode;
        continue;
      case 'mode':
        continue;
      case 'user': {
        const c = d.message && d.message.content;
        // A "user" record also carries tool_result blocks, which are Claude Code
        // handing a tool's output back to the model, not something a human said.
        if (Array.isArray(c)) {
          for (const b of c) {
            if (!b || typeof b !== 'object') continue;
            if (b.type === 'tool_result') {
              const ev = pendingTools.get(b.tool_use_id);
              const body = clip(textOf(b.content) || '', 600);
              if (ev) {
                ev.result = body;
                ev.ok = b.is_error !== true;
                pendingTools.delete(b.tool_use_id);
              } else {
                out.events.push({ seq: ++seq, kind: 'tool_result', ts, sidechain, ok: b.is_error !== true, result: body });
              }
            }
          }
        }
        const t = textOf(c);
        if (!t.trim() || queued.has(t)) continue;
        if (machineText(t)) {
          // Slash-command bookkeeping arrives as ordinary user records.
          const d2 = describeMachineText(t);
          if (d2) {
            out.events.push({ seq: ++seq, kind: d2.kind, ts, sidechain, text: d2.text });
            // `/effort max` changes the setting NOW. Waiting for the next
            // assistant record to stamp it would leave a control showing the
            // previous value for as long as the turn takes.
            if (d2.kind === 'command') {
              const setting = /^\/(effort|model)\s+(\S+)$/.exec(d2.text);
              if (setting) {
                if (setting[1] === 'effort') out.effort = setting[2].toLowerCase();
                else out.model = setting[2].toLowerCase();
              }
            }
          }
          // ...and if a human wrote something in the same record, show that too.
          const rest = humanRemainder(t);
          if (rest) out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: rest });
          continue;
        }
        out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: t });
        continue;
      }
      case 'assistant': {
        const m = d.message || {};
        if (m.model) out.model = m.model;
        // Claude Code stamps the effort level on each assistant record, so the
        // app can show the session's current setting without asking for it.
        if (d.effort) out.effort = d.effort;
        const c = m.content;
        if (!Array.isArray(c)) {
          const t = textOf(c);
          if (t.trim()) out.events.push({ seq: ++seq, kind: 'assistant', ts, sidechain, text: t });
          continue;
        }
        for (const b of c) {
          if (!b || typeof b !== 'object') continue;
          if (b.type === 'text' && b.text && b.text.trim()) {
            out.events.push({ seq: ++seq, kind: 'assistant', ts, sidechain, text: b.text });
          } else if (b.type === 'thinking' && typeof b.thinking === 'string' && b.thinking.trim()) {
            out.events.push({ seq: ++seq, kind: 'thinking', ts, sidechain, text: b.thinking });
          } else if (b.type === 'tool_use') {
            const ev = {
              seq: ++seq,
              kind: 'tool',
              ts,
              sidechain,
              name: b.name || 'tool',
              input: digestToolInput(b.input),
              result: null,
              ok: null,
            };
            if (b.name === 'Workflow') {
              // The script is a multi-KB blob; its meta name is the whole story,
              // so carry that as the detail and show no raw input at all.
              ev.detail = workflowName(b.input && b.input.script) || 'workflow';
              ev.input = '';
            } else if (b.name === 'Agent' || b.name === 'Task') {
              const t = b.input && (b.input.description || b.input.subagent_type);
              if (t) ev.detail = clip(t, 80);
            } else if (b.name === 'AskUserQuestion') {
              // Rendered as a question card, never as raw JSON — which is
              // exactly how it looked: `{"questions":[{"question":"The push…`.
              const ask = parseAsk(b.input);
              if (ask) {
                ev.ask = ask;
                ev.detail = clip(ask.questions[0].question, 80);
                ev.input = '';
              }
            }
            out.events.push(ev);
            if (b.id) pendingTools.set(b.id, ev);
          }
        }
        continue;
      }
      case 'system': {
        // Compaction notices and similar. Only surface ones with real content.
        const t = typeof d.content === 'string' ? d.content : textOf(d.content);
        if (t && t.trim() && t.length < 400) {
          out.events.push({ seq: ++seq, kind: 'system', ts, sidechain, text: t.trim() });
        }
        continue;
      }
      default:
        continue;
    }
  }

  if (out.events.length > limit) {
    out.events = out.events.slice(-limit);
    out.truncated = true;
  }
  return out;
}

function emptyResult(offset, truncated) {
  return {
    events: [], nextOffset: offset, truncated,
    title: null, permissionMode: null, model: null, gitBranch: null, cwd: null,
    effort: null, lastActivityTs: null,
  };
}


/**
 * What a session is in the middle of, judged from its transcript tail.
 *
 * The transcript only speaks when a block completes, so "in the middle of" means
 * an unresolved tool: a call whose result has not landed. Freshness-capped
 * because a cancelled or crashed turn leaves its last call unresolved forever,
 * and a strip claiming "running npm test" about yesterday would be worse than
 * nothing. Subagents are counted the same way — their own unresolved tools —
 * which approximates active agents well, since an agent runs one tool at a time.
 */
const ACTIVITY_FRESH_S = 3600;

function liveActivity(events, nowSec) {
  let tool = null;
  let subagents = 0;
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.kind !== 'tool' || e.result || e.ok !== undefined && e.ok !== null) continue;
    if (e.ts && nowSec - e.ts > ACTIVITY_FRESH_S) break;   // older is only older
    if (e.sidechain) subagents++;
    else if (!tool) tool = { tool: e.name || 'tool', detail: e.detail || null, sinceTs: e.ts || null };
  }
  if (!tool && subagents === 0) return null;
  return { ...(tool || {}), subagents };
}

module.exports = { readTranscript, digestToolInput, workflowName, textOf, liveActivity, machineText, describeMachineText, humanRemainder, parseAsk,
};
