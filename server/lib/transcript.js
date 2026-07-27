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
    start = Math.max(0, st.size - TAIL_BYTES);
    truncated = start > 0;
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
    lastActivityTs: null,
  };

  // tool_use id -> the event we appended, so a later tool_result can complete it
  // in place instead of arriving as a separate orphan card.
  const pendingTools = new Map();
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
        if (t.trim()) out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: t });
        continue;
      }
      case 'assistant': {
        const m = d.message || {};
        if (m.model) out.model = m.model;
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
    lastActivityTs: null,
  };
}

module.exports = { readTranscript, digestToolInput, workflowName, textOf };
