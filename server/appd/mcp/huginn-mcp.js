#!/usr/bin/env node
'use strict';
// huginn's MCP server: the tools Claude has for reaching the PERSON, not the host.
//
// One tool so far, `send_file`: copy a file into huginn's outbox, where the
// person's apps show it - an image inline, anything else as a download - with
// Download and Open. The rules (what may be sent, the copy, the ids) are in
// lib/outbox.js; this file is only the protocol.
//
// MCP over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout. Nothing else
// may be written to stdout - a stray line there is a protocol error to Claude
// Code - so diagnostics go to stderr.

const path = require('node:path');
const outbox = require('../lib/outbox');

const DATA_DIR = process.env.HUGINN_APPD_DATA || '/var/lib/huginn-appd';
const OUTBOX_DIR = process.env.HUGINN_OUTBOX_DIR || path.join(DATA_DIR, 'outbox');
const VERSION = '1.0.0';

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
}];

function reply(id, result) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n'); }
function fail(id, code, message) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n'); }

function callTool(name, args) {
  if (name !== 'send_file') return { isError: true, content: [{ type: 'text', text: `unknown tool ${name}` }] };
  try {
    const meta = outbox.deposit(args && args.path, {
      outboxDir: OUTBOX_DIR, dataDir: DATA_DIR, caption: args && args.caption,
    });
    // The result is JSON on purpose: the daemon reads it back out of the
    // transcript (outbox.fileFromResult) to put the file on the tool event.
    return { content: [{ type: 'text', text: JSON.stringify({ sent: true, ...meta }) }] };
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
      return reply(msg.id, callTool(msg.params && msg.params.name, msg.params && msg.params.arguments));
    default:
      // Notifications (notifications/initialized, cancelled) need no answer; an
      // unknown REQUEST gets the standard "method not found".
      if (isRequest) fail(msg.id, -32601, `method not found: ${msg.method}`);
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
process.stdin.on('end', () => process.exit(0));
