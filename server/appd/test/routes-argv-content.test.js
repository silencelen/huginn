'use strict';
// Words go to a child process on stdin or in a file, never on its command line.
//
// A process's argv is readable by every account on the host (`ps`,
// /proc/<pid>/cmdline), and this daemon runs as root. The children here carry
// the owner's words: the operator persona on every chat and Round turn, the
// conversation a suggestion call quotes, the Round draft a polish call rewrites,
// and the alert text handed to the relay script. Each used to ride on argv.
//
// A STUB `claude` at the front of the daemon's PATH and a stub relay record
// what they were given — their argv, what came on stdin, and (for the relay) the
// command line of the very process it runs in — so the assertions read exactly
// what another account on the host would have been able to read.
//
// SAFETY: one throwaway daemon on a kernel-assigned port, with a scratch data
// dir, a scratch ~/.claude, a private tmux socket, no push key, no API key, and
// a token of its own. No real claude and no real relay run.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');

const MARK = crypto.randomBytes(4).toString('hex');
const PERSONA = `PERSONA-${MARK}: answer like a lighthouse keeper.`;
const CHAT_SAYS = `CHAT-${MARK} please look at the disks`;
const ASSISTANT_SAYS = `ASSISTANT-${MARK} the disks look fine`;
const DRAFT = `DRAFT-${MARK} read the alerts and say what changed`;
const ROUND_PROMPT = `ROUND-${MARK} check the backups`;
const HEADLINE = `HEADLINE-${MARK} backups are behind`;
const HAS_PROC = fs.existsSync('/proc/self/cmdline');

/**
 * Records every `claude -p` call as one JSON line: its argv, the command line
 * the kernel reports for it, what came on stdin, and the persona it could read.
 * A run (stream-json) also writes a transcript, so the chat has something for
 * the suggestion call to quote, and answers with the Round report a Round needs.
 */
const STUB = `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const F = String.fromCharCode(96, 96, 96);
const NL = String.fromCharCode(10);
const argv = process.argv.slice(2);
if (!argv.includes('-p')) { console.log('{}'); process.exit(0); }   // auth status, --version
const kind = argv.includes('stream-json') ? 'run' : argv.includes('haiku') ? 'suggest' : 'polish';
let cmdline = null;
try { cmdline = fs.readFileSync('/proc/self/cmdline', 'utf8').split(String.fromCharCode(0)).filter(Boolean); } catch { }
let inp = '';
let done = false;
// A prompt passed on argv leaves stdin open and empty, so do not wait for an end
// that never comes: the record is then written with whatever arrived.
const finish = () => {
  if (done) return;
  done = true;
  const rec = { kind, argv, cmdline, stdin: inp };
  const pf = argv.indexOf('--append-system-prompt-file');
  if (pf >= 0) { try { rec.personaRead = fs.readFileSync(argv[pf + 1], 'utf8'); } catch (e) { rec.personaRead = 'unreadable: ' + e.code; } }
  fs.appendFileSync(process.env.ARGV_TEST_LOG, JSON.stringify(rec) + NL);
  if (kind === 'suggest') { process.stdout.write('first idea' + NL + 'second idea' + NL + 'third idea' + NL); process.exit(0); }
  if (kind === 'polish') { process.stdout.write('rewritten draft' + NL); process.exit(0); }
  const sid = crypto.randomUUID();
  const dir = path.join(process.env.HUGINN_APPD_CLAUDE_DIR, 'projects', 'stub');
  fs.mkdirSync(dir, { recursive: true });
  const ts = new Date().toISOString();
  fs.writeFileSync(path.join(dir, sid + '.jsonl'),
    JSON.stringify({ type: 'user', message: { content: inp }, timestamp: ts }) + NL +
    JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: process.env.ASSISTANT_SAYS }] }, timestamp: ts }) + NL);
  const TAG = (inp.match(/THIS RUN'S TAG: ([A-Za-z0-9_-]{1,64})/) || [])[1] || '';
  const text = TAG
    ? 'Done.' + NL + NL + F + 'huginn-report ' + TAG + NL
      + JSON.stringify({ status: 'action', headline: process.env.HEADLINE, items: [] }) + NL + F
    : process.env.ASSISTANT_SAYS;
  console.log(JSON.stringify({ type: 'system', subtype: 'init', session_id: sid }));
  console.log(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text }] } }));
  console.log(JSON.stringify({ type: 'result', is_error: false, duration_ms: 5, num_turns: 1 }));
};
process.stdin.on('data', (c) => { inp += c; });
process.stdin.on('end', finish);
setTimeout(finish, 1500);
`;

/**
 * The relay, as a script an operator might have written. It is handed its words
 * as "$2" and keeps them; next to them it keeps the command line of the process
 * it is running in, which is what `ps` would have shown anybody.
 */
// The .msg file is the one the test waits for, so it appears LAST, whole (a
// rename): its .args and .cmdline are already there when it is seen.
const RELAY = `#!/usr/bin/env bash
n="$RELAY_TEST_DIR/$(date +%s%N)-$$"
cat /proc/$$/cmdline > "$n.cmdline" 2>/dev/null || :
printf '%s\\n' "$#" "$1" "$3" "$4" "$0" > "$n.args"
printf '%s' "$2" > "$n.msg.part" && mv "$n.msg.part" "$n.msg"
exit 0
`;

let tmp, token, port, daemon, relay, relayDir, argvLog, personaPath;

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const a = srv.address().port; srv.close(() => resolve(a)); });
  });
}

async function api(pathname, init = {}) {
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** Every claude call the stub saw, oldest first. */
function calls() {
  try {
    return fs.readFileSync(argvLog, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
  } catch { return []; }
}

async function until(what, fn, timeoutMs = 20_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const v = await fn();
    if (v) return v;
    await wait(100);
  }
  throw new Error(`timed out waiting for ${what}`);
}

/** No element of `list` holds any of the words. */
function assertNoneHolds(list, words, where) {
  for (const w of words) {
    const hit = (list || []).find((a) => String(a).includes(w));
    assert.equal(hit, undefined, `${where} carries ${JSON.stringify(w.slice(0, 20))}: ${JSON.stringify(hit)}`);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-argv-'));
  const bin = path.join(tmp, 'bin');
  relayDir = path.join(tmp, 'relay');
  for (const d of [bin, relayDir, path.join(tmp, 'data'), path.join(tmp, 'state'), path.join(tmp, 'claude')]) {
    fs.mkdirSync(d, { recursive: true });
  }
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });
  relay = path.join(tmp, 'relay.sh');
  fs.writeFileSync(relay, RELAY, { mode: 0o755 });
  personaPath = path.join(tmp, 'persona.md');
  fs.writeFileSync(personaPath, PERSONA);
  argvLog = path.join(tmp, 'claude-calls.jsonl');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  port = await freePort();

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-argv-${process.pid}-${port}`,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_PERSONA_FILE: personaPath,
      HUGINN_APPD_TELEGRAM_SCRIPT: relay,
      HUGINN_FCM_KEY: path.join(tmp, 'no-push-key.json'),
      HUGINN_API_KEY_FILE: '',
      HUGINN_API_KEY_VAR: '',
      ARGV_TEST_LOG: argvLog,
      RELAY_TEST_DIR: relayDir,
      ASSISTANT_SAYS,
      HEADLINE,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  await until('the daemon', async () => {
    try { return (await api('/v1/ping')).status === 200; } catch { return false; }
  }, 30_000);
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

let chatId = null;

test('a chat turn takes the persona as a file and the message on stdin', async () => {
  const chat = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ title: 'argv check', mode: 'ask' }) });
  assert.ok(chat.body && chat.body.id, JSON.stringify(chat));
  chatId = chat.body.id;
  const sent = await api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text: CHAT_SAYS }) });
  assert.ok(sent.status < 300, JSON.stringify(sent));
  await until('the chat turn to finish', async () => {
    const { body } = await api(`/v1/chats/${chatId}`);
    return body && body.running === false && body.claudeSessionId;
  });

  const run = calls().find((c) => c.kind === 'run' && c.stdin.includes(CHAT_SAYS));
  assert.ok(run, 'the stub recorded the chat turn, its message on stdin');
  assertNoneHolds(run.argv, [PERSONA.slice(0, 20), CHAT_SAYS], 'the chat turn\'s argv');
  if (HAS_PROC) assertNoneHolds(run.cmdline, [PERSONA.slice(0, 20), CHAT_SAYS], 'the chat turn\'s command line');
  const at = run.argv.indexOf('--append-system-prompt-file');
  assert.ok(at >= 0, `the persona goes by file: ${JSON.stringify(run.argv)}`);
  assert.equal(run.argv[at + 1], path.resolve(personaPath), 'the operator\'s file, by absolute path');
  assert.equal(run.personaRead, PERSONA, 'and the run can read exactly the persona');
  assert.ok(!run.argv.includes('--append-system-prompt'), 'never the text form');
});

test('a suggestion call takes the conversation on stdin, inside the same cage', async () => {
  assert.ok(chatId, 'needs the chat from the test above');
  const sug = await api(`/v1/chats/${chatId}/suggestions`);
  assert.equal(sug.status, 200, JSON.stringify(sug));
  assert.deepEqual(sug.body.suggestions, ['first idea', 'second idea', 'third idea'], JSON.stringify(sug.body));
  const s = calls().find((c) => c.kind === 'suggest');
  assert.ok(s, 'the stub recorded the suggestion call');
  assertNoneHolds(s.argv, [CHAT_SAYS, ASSISTANT_SAYS], 'the suggestion call\'s argv');
  if (HAS_PROC) assertNoneHolds(s.cmdline, [CHAT_SAYS, ASSISTANT_SAYS], 'the suggestion call\'s command line');
  assert.ok(s.stdin.includes(ASSISTANT_SAYS) && s.stdin.includes(CHAT_SAYS), 'the conversation reached the model on stdin');
  assert.deepEqual(s.argv, ['-p', '--setting-sources', '', '--model', 'haiku', '--max-turns', '1', '--tools', ''],
    'and the cage is unchanged');
});

test('a Round draft goes to the polish call on stdin', async () => {
  const r = await api('/v1/rounds/polish', { method: 'POST', body: JSON.stringify({ field: 'prompt', prompt: DRAFT }) });
  assert.equal(r.status, 200, JSON.stringify(r));
  assert.equal(r.body.polished, 'rewritten draft');
  const p = calls().filter((c) => c.kind === 'polish').pop();
  assert.ok(p, 'the stub recorded the polish call');
  assertNoneHolds(p.argv, [DRAFT], 'the polish call\'s argv');
  if (HAS_PROC) assertNoneHolds(p.cmdline, [DRAFT], 'the polish call\'s command line');
  assert.ok(p.stdin.includes(DRAFT), 'the draft reached the model on stdin');
});

test('a Round turn keeps its prompt and the persona off argv', async () => {
  const made = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: `Round ${MARK}`, prompt: ROUND_PROMPT, notifyWhen: 'always',
      schedule: { kind: 'weekly', days: [0], at: '19:00', tz: 'UTC' },
    }),
  });
  assert.equal(made.status, 201, JSON.stringify(made));
  const fired = await api(`/v1/rounds/${made.body.id}/run`, { method: 'POST', body: '{}' });
  assert.equal(fired.status, 202, JSON.stringify(fired));

  const run = await until('the Round turn', () => calls().find((c) => c.kind === 'run' && c.stdin.includes(ROUND_PROMPT)));
  assertNoneHolds(run.argv, [PERSONA.slice(0, 20), ROUND_PROMPT], 'the Round turn\'s argv');
  if (HAS_PROC) assertNoneHolds(run.cmdline, [PERSONA.slice(0, 20), ROUND_PROMPT], 'the Round turn\'s command line');
  assert.equal(run.personaRead, PERSONA, 'the Round turn reads the persona from its file');
});

test('the Round report reaches the relay as "$2", and no relay command line holds the words', async () => {
  // The report the Round above delivers, with no app to push to. Every relay
  // call made so far is checked, not just that one: a finished chat may have
  // sent its own alert through the same relay.
  const base = await until('the relay to carry the report', () => {
    for (const n of fs.readdirSync(relayDir).filter((f) => f.endsWith('.msg'))) {
      if (fs.readFileSync(path.join(relayDir, n), 'utf8').includes(HEADLINE)) return path.join(relayDir, n.slice(0, -4));
    }
    return null;
  });
  const [argc, first, third, fourth, zero] = fs.readFileSync(`${base}.args`, 'utf8').split('\n');
  assert.deepEqual([argc, first, third, fourth, zero], ['4', '--message', '--source', 'huginn-app', relay],
    'the documented contract holds: bash <script> --message <text> --source huginn-app');
  if (!HAS_PROC) return;
  const records = fs.readdirSync(relayDir).filter((f) => f.endsWith('.cmdline'));
  assert.ok(records.length > 0);
  for (const n of records) {
    const cmdline = fs.readFileSync(path.join(relayDir, n), 'utf8').split('\0').filter(Boolean);
    assert.ok(cmdline.length > 0, 'the relay could read its own command line');
    assertNoneHolds(cmdline, [HEADLINE, MARK], `relay call ${n}'s command line`);
  }
});
