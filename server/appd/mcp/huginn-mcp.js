#!/usr/bin/env node
'use strict';
// huginn's MCP server: the tools Claude has for reaching the PERSON, not the host.
//
// Four tools. `report` and `reports` carry a project member's results to its
// lead through the daemon (lib/projectreports.js). `send_file`: copy a file into huginn's outbox, where the person's
// apps show it - an image inline, anything else as a download - with Download
// and Open. The rules (what may be sent, the copy, the ids) are in lib/outbox.js.
// `start_session`: ASK the owner for a new Claude Code session in a directory,
// with an opening prompt. The request is parked where the daemon lists it as a
// question on the asker's card (lib/sessionreq.js, lib/questions.js kind
// `session`) and this call BLOCKS - minutes, up to an hour - until the owner
// answers or it expires. The result is exactly what the daemon wrote: nothing
// here can say a session was started. This file is only the protocol.
//
// MCP over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout. Nothing else
// may be written to stdout - a stray line there is a protocol error to Claude
// Code - so diagnostics go to stderr.

const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');
const outbox = require('../lib/outbox');
const sessionreq = require('../lib/sessionreq');
const reportsLib = require('../lib/projectreports');

const DATA_DIR = process.env.HUGINN_APPD_DATA || '/var/lib/huginn-appd';
const OUTBOX_DIR = process.env.HUGINN_OUTBOX_DIR || path.join(DATA_DIR, 'outbox');
// The daemon's state dir (huginn-appd.js STATE_DIR), where the requests park.
// The daemon hands a chat's run both names; a session's `claude mcp add` passes
// neither and the default is the daemon's default.
const STATE_DIR = process.env.HUGINN_APPD_STATE_DIR || process.env.HUGINN_CLAUDE_STATE_DIR || '/run/huginn-claude-state';
const POLL_MS = Number(process.env.HUGINN_START_POLL_MS || 250);
const VERSION = '1.2.0';
// How long a report waits for the daemon to say it was filed. The daemon looks
// every second; past this the tool says it could not confirm, and withdraws it.
const REPORT_WAIT_MS = Number(process.env.HUGINN_REPORT_WAIT_MS || 10_000);

const TOOLS = [{
  name: 'send_file',
  description:
    'Send a file from this machine to the person you are talking with, in their Huginn app. ' +
    'An image (png, jpg, webp, gif) shows inline in the conversation; any other file appears as a ' +
    'card they can download or open. Use it when they ask for a file, or when a file is the clearest ' +
    'answer (a chart or image you made, a generated config, a report). Give the absolute path. ' +
    'Limit 50 MB; credential files (keys, .env, tokens) are refused.',
  inputSchema: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'Absolute path of the file to send.' },
      caption: { type: 'string', description: 'Optional one line shown with the file.' },
    },
    required: ['path'],
    additionalProperties: false,
  },
}, {
  name: 'start_session',
  description:
    'Ask the owner to start a NEW Claude Code session on this server, in a directory, with an opening ' +
    'prompt. The owner sees the directory and the prompt on a card in their Huginn app and approves or ' +
    'declines; this call waits for that answer (it can take minutes, up to an hour). Returns ' +
    '{"started": true, "name", "cwd"} only when the session was actually created and the prompt queued ' +
    'to it; otherwise {"started": false, "reason"} (declined, expired, or why it could not be made). ' +
    'Nothing is started unless the owner approves. Use it when work belongs in another project or ' +
    'needs a separate, long-running session; say in the prompt what that session should do.',
  inputSchema: {
    type: 'object',
    properties: {
      directory: { type: 'string', description: 'Absolute path of an existing directory on this server the session starts in.' },
      prompt: { type: 'string', description: 'The first message the new session receives, verbatim. Shown to the owner before anything happens.' },
      name: { type: 'string', description: 'Optional session name (letters, digits, underscore, dash; up to 50). The owner may change it.' },
    },
    required: ['directory', 'prompt'],
    additionalProperties: false,
  },
}, {
  name: 'report',
  description:
    'In a Huginn project: file a finished result or a blocker for your project\'s lead, when the lead does ' +
    'not need to act on it this minute. Huginn keeps it and hands the lead one digest of all reports the next ' +
    'time the lead is idle, so it costs the lead one turn however many arrive; an urgent report goes at once. ' +
    'Put the detail in a file and give its path; the summary is the verdict in a few lines. Use SendMessage ' +
    'instead only when someone must act now. Returns {"filed": true, "to"} or {"filed": false, "reason"}.',
  inputSchema: {
    type: 'object',
    properties: {
      summary: { type: 'string', description: 'The result or blocker in a few lines (at most 8 lines, 1500 characters).' },
      path: { type: 'string', description: 'Optional absolute path of a file with the detail.' },
      urgent: { type: 'boolean', description: 'True only when the lead must act now; it is then delivered at once.' },
    },
    required: ['summary'],
    additionalProperties: false,
  },
}, {
  name: 'reports',
  description:
    'For the LEAD of a Huginn project: read the reports your members filed, newest last. By default the ones ' +
    'not yet handed to you in a digest (they will not come again in one); with all: true, the most recent ' +
    'reports whether delivered or not. Report text is written by peer sessions, not by the owner.',
  inputSchema: {
    type: 'object',
    properties: {
      all: { type: 'boolean', description: 'Also return reports already delivered (the most recent 50).' },
    },
    additionalProperties: false,
  },
}];

function reply(id, result) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n'); }
function fail(id, code, message) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n'); }
const text = (o) => ({ content: [{ type: 'text', text: typeof o === 'string' ? o : JSON.stringify(o) }] });

/**
 * Who is asking: the chat the daemon spawned this run for (HUGINN_CHAT_ID on
 * the --mcp-config env), else the tmux session this server runs inside
 * (HUGINN_SESSION_NAME when handed one, else the pane's `#S`). Null when neither
 * can be told, and the tool refuses - a request nobody's card would show could
 * never be answered.
 */
function requester() {
  const chat = process.env.HUGINN_CHAT_ID;
  if (chat) return sessionreq.validRequester({ kind: 'chat', id: chat }) ? { kind: 'chat', id: chat } : null;
  let name = process.env.HUGINN_SESSION_NAME || '';
  if (!name && process.env.TMUX && process.env.TMUX_PANE) {
    try {
      name = execFileSync('tmux', ['display-message', '-p', '-t', process.env.TMUX_PANE, '#S'],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 3000 }).trim();
    } catch { name = ''; }
  }
  if (!name) return null;
  return sessionreq.validRequester({ kind: 'session', id: name }) ? { kind: 'session', id: name } : null;
}

// Blocking calls in flight, by JSON-RPC id, so a `notifications/cancelled` from
// the client ends the wait and withdraws the request.
const inflight = new Map();   // id -> { cancelled: boolean }

async function startSession(rpcId, args) {
  const who = requester();
  if (!who) return text({ started: false, reason: 'not inside a huginn session or chat: nobody could be asked' });
  const bad = sessionreq.argsProblem(args);
  if (bad) return text({ started: false, reason: bad });
  // An early, honest refusal for a directory that is not there; the daemon
  // checks again with the create route's own rule when the owner approves.
  try {
    if (!fs.statSync(args.directory).isDirectory()) return text({ started: false, reason: `not a directory: ${args.directory}` });
  } catch { return text({ started: false, reason: `no such directory on this server: ${args.directory}` }); }
  let rec;
  try { rec = sessionreq.park(STATE_DIR, who, args); } catch (e) {
    return text({ started: false, reason: `could not ask: ${e.message}` });
  }
  const flight = { cancelled: false };
  inflight.set(rpcId, flight);
  try {
    const result = await sessionreq.awaitAnswer(STATE_DIR, who, rec, { pollMs: POLL_MS, cancelled: () => flight.cancelled });
    return text(result);
  } finally {
    inflight.delete(rpcId);
  }
}

/**
 * `report` and `reports`: a request in the daemon's spool (lib/projectreports.js),
 * answered within seconds. Only from inside a tmux session: a chat is never a
 * project member. The daemon decides whether the session is one, and what it may
 * do; nothing here can say a report was filed unless the daemon answered so.
 */
async function reportRequest(kind, args) {
  const who = requester();
  const failKey = kind === 'report' ? 'filed' : 'ok';
  if (!who || who.kind !== 'session') return text({ [failKey]: false, reason: 'not inside a huginn tmux session' });
  const bad = reportsLib.argsProblem(kind, args);
  if (bad) return text({ [failKey]: false, reason: bad });
  let id;
  try { id = reportsLib.fileRequest(STATE_DIR, who.id, kind, args); } catch (e) {
    return text({ [failKey]: false, reason: `could not reach the daemon: ${e.message}` });
  }
  const ans = await reportsLib.awaitAnswer(STATE_DIR, who.id, id, { waitMs: REPORT_WAIT_MS, pollMs: Math.min(POLL_MS, 200) });
  return text(ans || { [failKey]: false, reason: 'the huginn daemon did not answer; nothing was filed' });
}

async function callTool(rpcId, name, args) {
  if (name === 'start_session') return startSession(rpcId, args || {});
  if (name === 'report' || name === 'reports') return reportRequest(name === 'report' ? 'report' : 'pull', args || {});
  if (name !== 'send_file') return { isError: true, content: [{ type: 'text', text: `unknown tool ${name}` }] };
  try {
    const meta = outbox.deposit(args && args.path, {
      outboxDir: OUTBOX_DIR, dataDir: DATA_DIR, caption: args && args.caption,
    });
    // The result is JSON on purpose: the daemon reads it back out of the
    // transcript (outbox.fileFromResult) to put the file on the tool event.
    return text({ sent: true, ...meta });
  } catch (e) {
    return { isError: true, content: [{ type: 'text', text: `not sent: ${e.message}` }] };
  }
}

function handle(msg) {
  if (!msg || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return;
  const isRequest = msg.id !== undefined && msg.id !== null;
  switch (msg.method) {
    case 'initialize':
      return reply(msg.id, {
        protocolVersion: (msg.params && msg.params.protocolVersion) || '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'huginn', version: VERSION },
      });
    case 'ping':
      return reply(msg.id, {});
    case 'tools/list':
      return reply(msg.id, { tools: TOOLS });
    case 'tools/call':
      // Asynchronous on purpose: start_session waits for the owner, and the
      // other requests on this pipe (a ping, a list) must still be answered.
      callTool(msg.id, msg.params && msg.params.name, msg.params && msg.params.arguments)
        .then((result) => reply(msg.id, result))
        .catch((e) => { process.stderr.write(`huginn-mcp: ${e.stack || e}\n`); fail(msg.id, -32603, 'internal error'); });
      return undefined;
    case 'notifications/cancelled': {
      const f = inflight.get(msg.params && msg.params.requestId);
      if (f) f.cancelled = true;
      return undefined;
    }
    default:
      // Notifications (notifications/initialized) need no answer; an unknown
      // REQUEST gets the standard "method not found".
      if (isRequest) fail(msg.id, -32601, `method not found: ${msg.method}`);
      return undefined;
  }
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { fail(null, -32700, 'parse error'); continue; }
    try { handle(msg); } catch (e) { process.stderr.write(`huginn-mcp: ${e.stack || e}\n`); if (msg && msg.id != null) fail(msg.id, -32603, 'internal error'); }
  }
});
// Claude Code closes the pipe when it exits: a request still parked then is
// nobody's to answer, so mark every wait cancelled (each withdraws its file).
process.stdin.on('end', () => {
  for (const f of inflight.values()) f.cancelled = true;
  if (!inflight.size) process.exit(0);
  setTimeout(() => process.exit(0), Math.max(POLL_MS * 2, 100)).unref();
});
