'use strict';
// A local run's output keeps every character when a read ends inside one.
//
// startRun decoded each stdout chunk on its own. A stream-json line that arrives in
// two reads (any frame over 64 KiB, or a pipe that backed up) can be cut inside a
// multi-byte character, and each half then decoded to U+FFFD; JSON.parse took the
// line anyway, so "—" or "е" became "���" in the transcript, the live stream and a
// Round's report. stderr, which becomes the failure text, had the same flaw.
//
// The daemon's own startRun, Run and handleClaudeEvent, lifted into a sandbox, run
// a real child process that writes each line in two pieces split mid-character.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const childProcess = require('node:child_process');
const { lift } = require('./appd-lift');

// Writes a buffer in two pieces, the first ending INSIDE the first multi-byte
// character of `mark`, with a pause between so they reach the parent as two reads.
const CHILD = `
const halves = (s, mark) => {
  const b = Buffer.from(s);
  const at = b.indexOf(Buffer.from(mark)) + 1;
  return [b.subarray(0, at), b.subarray(at)];
};
const answer = JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: process.env.ANSWER }] } }) + '\\n';
const [a1, a2] = halves(answer, process.env.MARK);
process.stdin.resume();
process.stdin.on('end', () => {
  process.stdout.write(a1);
  setTimeout(() => {
    process.stdout.write(a2);
    if (process.env.FAIL) {
      const [e1, e2] = halves(process.env.FAIL, process.env.MARK);
      process.stderr.write(e1);
      setTimeout(() => { process.stderr.write(e2); setTimeout(() => process.exit(1), 50); }, 150);
    } else {
      process.stdout.write(JSON.stringify({ type: 'result', is_error: false, result: process.env.ANSWER, num_turns: 1 }) + '\\n');
    }
  }, 150);
});
`;

let tmp, script;
before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-run-utf8-'));
  script = path.join(tmp, 'child.js');
  fs.writeFileSync(script, CHILD);
});
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/** One local run of the stand-in child; resolves with what the daemon recorded. */
function runWith(env) {
  return new Promise((resolve) => {
    const messages = [];
    const { fns } = lift(['startRun', 'handleClaudeEvent', 'toolInputDigest', 'exitFailureText', 'isSessionId', 'Run'], {
      log: () => {},
      activeRuns: new Map(),
      appendMsg: (id, rec) => { messages.push(rec); },
      updateMeta: () => {},
      humanizeUserText: (t) => t,
      fableRedDowngrade: () => null,
      personaFile: () => null,
      ranOnLabel: () => null,
      WORKDIR: tmp,
      // The stand-in runs where `claude` would, with the argv startRun built.
      spawn: (cmd, args, opts) => childProcess.spawn(process.execPath, [script, ...args],
        { ...opts, env: { ...process.env, ...env } }),
      settleRun: (run_, { failureText }) => resolve({ messages, failureText }),
      process: { env: {} },
    }, { constants: ['MAX_CONCURRENT_RUNS', 'RUN_HARD_CAP_MS', 'EXTRA_TOOLS', 'TOOLS', 'DISALLOWED', 'isLocalFamily'] });
    const started = fns.startRun({ id: 'c1', mode: 'ask' }, 'how full is the disk?');
    assert.ok(started.run, started.error);
  });
}

test('an answer split inside an em dash is recorded whole', async () => {
  const ANSWER = 'disk 93% full — rotate logs';
  const { messages } = await runWith({ ANSWER, MARK: '—' });
  const said = messages.filter((m) => m.type === 'assistant').map((m) => m.text);
  assert.deepEqual(said, [ANSWER]);
});

test('an answer in Cyrillic split mid-letter is recorded whole', async () => {
  const ANSWER = 'Диск заполнен на 93%, нужно почистить журналы.';
  const { messages } = await runWith({ ANSWER, MARK: 'заполнен' });
  const said = messages.filter((m) => m.type === 'assistant').map((m) => m.text);
  assert.deepEqual(said, [ANSWER]);
  assert.ok(!said.join('').includes('�'));
});

test('stderr split mid-letter reaches the failure text whole', async () => {
  const FAIL = 'ошибка: нет места на устройстве';
  const { failureText } = await runWith({ ANSWER: 'начинаю', MARK: 'нет', FAIL });
  assert.ok(failureText.includes(FAIL), failureText);
  assert.ok(!failureText.includes('�'), failureText);
});
