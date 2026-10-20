'use strict';
// run() and runStdin() never start a shell; the relay's shell has a call of its own.
//
// The daemon's two argv runners carry request input: a session name from a
// route path lands in a tmux target, a model id in a claude flag. That is safe
// for one reason only — execFile hands each element to the program as one
// argument, and no shell ever reads it. The alert relay does need a shell (it is
// SOURCED by a `bash -c` shim, so the alert text stays off every command line),
// and it used to be started through runStdin(): the same helper, sometimes a
// shell, so whether request text could reach a script became a question about
// every caller. It now has its own execFile (runRelayShim), with an argv fixed
// in that function.
//
// The first test holds the runners to that by reading every call site, so the
// next shell routed through them fails here. The second runs the relay's own
// call for real, with alert text full of shell syntax — a waiting question or a
// Round's verdict is a model's words — and proves it arrives as data.
//
// runRelayShim is not exported (it lives in the daemon's entry file), so it is
// lifted out of the source with RELAY_SHIM and run in a sandbox, as
// attach-markers-words.test.js does: the real function, not a copy of it.
//
// SAFETY: no daemon and no port. The stand-in relay is a scratch script that
// records what it was given into its own temp dir.

const { test, after } = require('node:test');
const assert = require('node:assert');
const { execFile } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'huginn-appd.js'), 'utf8');
const HAS_PROC = fs.existsSync('/proc/self/cmdline');

/** Programs that run a script handed to them, by basename. */
const SHELLS = new Set(['sh', 'bash', 'dash', 'zsh', 'ksh', 'mksh', 'csh', 'tcsh', 'fish', 'busybox']);

test('no run() or runStdin() call starts a shell', () => {
  const calls = [];
  const re = /\b(run|runStdin)\(\s*(['"`])([^'"`]*)\2/g;
  for (let m; (m = re.exec(SRC));) {
    calls.push({ fn: m[1], cmd: m[3], line: SRC.slice(0, m.index).split('\n').length });
  }
  // Not blind: the daemon makes dozens of these calls, most of them to tmux.
  assert.ok(calls.length >= 40 && calls.some((c) => c.cmd === 'tmux'),
    `found only ${calls.length} run()/runStdin() calls - has the call shape changed? (update this test)`);
  const shells = calls.filter((c) => SHELLS.has(path.posix.basename(c.cmd)));
  assert.deepEqual(shells.map((c) => `huginn-appd.js:${c.line} ${c.fn}('${c.cmd}', ...)`), [],
    'a shell started through an argv runner: give it a call of its own with a fixed argv, as runRelayShim has');
});

function lift() {
  const pick = (startNeedle, endNeedle) => {
    const a = SRC.indexOf(startNeedle);
    assert.ok(a >= 0, `${startNeedle} is gone from huginn-appd.js`);
    const b = SRC.indexOf(endNeedle, a);
    assert.ok(b > a, `${startNeedle} has no ${JSON.stringify(endNeedle)} after it`);
    return SRC.slice(a, b + endNeedle.length);
  };
  const code = [
    pick('const RELAY_SHIM =', "';\n"),
    pick('function runRelayShim(', '\n}\n'),
    'this.runRelayShim = runRelayShim;',
  ].join('\n');
  const box = { execFile };
  vm.runInNewContext(code, box);
  return box.runRelayShim;
}

const tmps = [];
after(() => { for (const d of tmps) fs.rmSync(d, { recursive: true, force: true }); });

test('the relay\'s own shell takes the alert text as data, never as script', async () => {
  const runRelayShim = lift();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-shim-'));
  tmps.push(dir);
  const canary = path.join(dir, 'RAN');
  const record = path.join(dir, 'record');
  // A relay as an operator writes one, which also notes what the kernel shows
  // as the command line of the process it runs in (it is sourced, so that is
  // the shim's own bash).
  const relay = path.join(dir, 'relay.sh');
  fs.writeFileSync(relay, [
    '#!/usr/bin/env bash',
    `printf '%s\\0' "$0" "$#" "$@" > ${JSON.stringify(record)}`,
    `cat /proc/$$/cmdline > ${JSON.stringify(`${record}.cmdline`)} 2>/dev/null || true`,
    '',
  ].join('\n'), { mode: 0o755 });
  const text = `Round verdict: $(touch ${canary}) \`touch ${canary}\`; touch ${canary} "a quote" 'another' \\ $HOME\n\n`;

  const r = await runRelayShim(relay, text);
  assert.equal(r.err, null, r.stderr);
  assert.deepEqual(fs.readFileSync(record, 'utf8').split('\0').slice(0, -1),
    [relay, '4', '--message', text, '--source', 'huginn-app'],
    'the relay sees the same four arguments as `bash <relay> --message <text> --source huginn-app`');
  assert.equal(fs.existsSync(canary), false, 'something in the alert text ran');
  if (HAS_PROC) {
    const cmdline = fs.readFileSync(`${record}.cmdline`, 'utf8').split('\0').filter(Boolean);
    assert.equal(cmdline[0], 'bash');
    assert.equal(cmdline[cmdline.length - 1], relay, 'the command line ends with the relay path');
    assert.ok(!cmdline.some((a) => a.includes('Round verdict')), `the text reached a command line: ${JSON.stringify(cmdline)}`);
  }
});
