'use strict';
// server/bin/huginn-statusline, run for real against a private tmux pane: the
// record it writes for the daemon, the line it prints, the chain to a previous
// status line, and the guards that keep it from writing where it should not.
//
// SAFETY: its own tmux server on its own socket (`-L`), a scratch state
// directory, never /run and never the operator's sessions. Nothing here starts
// a real `claude`.
require('./tmux-cleanup');
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const SCRIPT = path.join(__dirname, '..', '..', 'bin', 'huginn-statusline');
const FIXTURE = path.join(__dirname, 'fixtures', 'statusline-2.1.306.json');
const PAYLOAD = fs.readFileSync(FIXTURE, 'utf8');
const SOCK = `huginn-slscript-${process.pid}`;
const SESSION = `sls-${process.pid}`;
let stateDir;
let pane;
let sockPath;

const tmux = (args) => execFileSync('tmux', ['-L', SOCK, ...args], { encoding: 'utf8' }).trim();

before(() => {
  stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'slscript-state-'));
  tmux(['new-session', '-d', '-s', SESSION, 'sleep 600']);
  pane = tmux(['display-message', '-p', '-t', `=${SESSION}:`, '#{pane_id}']);
  sockPath = path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`, SOCK);
});
after(() => {
  try { tmux(['kill-server']); } catch { /* gone */ }
  fs.rmSync(stateDir, { recursive: true, force: true });
});

/** Run the script as Claude Code does: JSON on stdin, argv as installed. {code, out, err, ms}. */
function run(input, args = [], env = {}) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    // No TMUX in the inherited environment: the test runner may itself be in a
    // pane, and the script must find OUR pane, not the runner's.
    const base = { ...process.env };
    delete base.TMUX; delete base.TMUX_PANE; delete base.CLAUDE_JOB_DIR; delete base.CLAUDE_CODE_SESSION_KIND;
    const child = spawn(SCRIPT, args, {
      env: {
        ...base, HUGINN_CLAUDE_STATE_DIR: stateDir, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8',
        TMUX: `${sockPath},0,0`, TMUX_PANE: pane, ...env,
      },
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('close', (code) => resolve({ code, out, err, ms: Date.now() - t0 }));
    child.stdin.end(input);
  });
}
const recordFile = () => path.join(stateDir, '.status', SESSION);
const readRecord = () => JSON.parse(fs.readFileSync(recordFile(), 'utf8'));
const clear = () => fs.rmSync(path.join(stateDir, '.status'), { recursive: true, force: true });

test('records the JSON verbatim for the pane\'s session: 0700 dir, 0600 file, no temp left', async () => {
  clear();
  const r = await run(PAYLOAD);
  assert.equal(r.code, 0);
  assert.equal(r.err, '', 'never prints an error');
  assert.ok(fs.existsSync(recordFile()), 'record written under .status/<session>');
  assert.equal(fs.readFileSync(recordFile(), 'utf8'), `${PAYLOAD}\n`, 'verbatim');
  assert.equal(fs.statSync(path.join(stateDir, '.status')).mode & 0o777, 0o700);
  assert.equal(fs.statSync(recordFile()).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(path.join(stateDir, '.status')), [SESSION], 'no .tmp beside it');
  assert.equal(readRecord().session_id, '5e7af109-c414-4c06-97b0-718f216cc78d');
});

test('with no previous status line it prints `<model> · ctx N%`', async () => {
  const r = await run(PAYLOAD);
  assert.equal(r.out, 'Haiku 5.5 · ctx 4%\n');
});

test('before the first response the line is just the model', async () => {
  const j = JSON.parse(PAYLOAD);
  j.context_window.current_usage = null;
  j.context_window.used_percentage = null;
  const r = await run(JSON.stringify(j));
  assert.equal(r.out, 'Haiku 5.5\n');
  assert.equal(readRecord().context_window.used_percentage, null, 'and the record is still written');
});

test('a previous status line, given after --, is run with the same JSON and its output printed', async () => {
  const r = await run(PAYLOAD, ['--', 'jq -r \'"[prev] \\(.model.id) \\(.effort.level)"\'']);
  assert.equal(r.code, 0);
  assert.equal(r.out, '[prev] claude-haiku-5-5 medium\n');
  assert.equal(readRecord().model.id, 'claude-haiku-5-5', 'recorded first, whatever the chain does');
});

test('a previous status line that fails, prints nothing or hangs falls back to the plain line', async () => {
  assert.equal((await run(PAYLOAD, ['--', 'exit 3'])).out, 'Haiku 5.5 · ctx 4%\n');
  assert.equal((await run(PAYLOAD, ['--', 'true'])).out, 'Haiku 5.5 · ctx 4%\n');
  assert.equal((await run(PAYLOAD, ['--', 'echo "   "'])).out, 'Haiku 5.5 · ctx 4%\n');
  const slow = await run(PAYLOAD, ['--', 'sleep 30']);
  assert.equal(slow.out, 'Haiku 5.5 · ctx 4%\n');
  assert.ok(slow.ms < 10_000, `bounded: took ${slow.ms} ms`);
  assert.equal(slow.code, 0);
});

test('the previous status line may print several lines; all of them are kept', async () => {
  const r = await run(PAYLOAD, ['--', 'printf "one\\ntwo\\n"']);
  assert.equal(r.out, 'one\ntwo\n');
});

test('outside tmux nothing is recorded, but the line is still printed', async () => {
  clear();
  const r = await run(PAYLOAD, [], { TMUX: '', TMUX_PANE: '' });
  assert.equal(r.code, 0);
  assert.equal(r.out, 'Haiku 5.5 · ctx 4%\n');
  const written = fs.existsSync(path.join(stateDir, '.status')) ? fs.readdirSync(path.join(stateDir, '.status')) : [];
  if (process.env.TMUX && process.env.TMUX_PANE) {
    // This test runner is itself in a tmux pane. The recovery that serves Claude
    // Code's background PTY host walks the process tree for the nearest ancestor
    // holding TMUX/TMUX_PANE and finds the RUNNER's pane — correctly, that pane's
    // shell is an ancestor — so the record lands under that session's name,
    // never under our private pane's.
    assert.ok(!written.includes(SESSION), `recorded for our private pane: ${written}`);
    return;
  }
  assert.deepEqual(written, []);
});

test('a nested claude (one a Bash tool started inside the session) records nothing', async () => {
  clear();
  const r = await run(PAYLOAD, [], { HUGINN_CLAUDE_TITLE_NESTED: '1' });
  assert.equal(r.code, 0);
  assert.equal(r.out, 'Haiku 5.5 · ctx 4%\n');
  assert.equal(fs.existsSync(path.join(stateDir, '.status')), false);
});

test('stdin that is not JSON: exit 0, nothing printed, nothing written', async () => {
  clear();
  for (const input of ['', 'not json {', '[1,2]', '"a string"']) {
    const r = await run(input);
    assert.equal(r.code, 0, JSON.stringify(input));
    assert.equal(r.out, '', JSON.stringify(input));
    assert.equal(r.err, '');
  }
  assert.equal(fs.existsSync(path.join(stateDir, '.status')), false);
});

test('a C locale prints the 7-bit separator', async () => {
  const r = await run(PAYLOAD, [], { LANG: 'C', LC_ALL: 'C' });
  assert.equal(r.out, 'Haiku 5.5 - ctx 4%\n');
});

test('a second render replaces the record whole', async () => {
  await run(PAYLOAD);
  const j = JSON.parse(PAYLOAD);
  j.context_window.used_percentage = 57;
  j.fast_mode = true;
  await run(JSON.stringify(j));
  const rec = readRecord();
  assert.equal(rec.context_window.used_percentage, 57);
  assert.equal(rec.fast_mode, true);
  assert.deepEqual(fs.readdirSync(path.join(stateDir, '.status')), [SESSION]);
});

test('fast: a redraw costs well under a second', async () => {
  const r = await run(PAYLOAD);
  assert.ok(r.ms < 1500, `took ${r.ms} ms`);
});
