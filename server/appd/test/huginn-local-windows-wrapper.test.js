'use strict';
// The WinSW wrapper `huginn local on` installs each Windows service with. The
// wrapper is the service's own process, and Windows will not open a running
// image for writing, so a re-run of `on` over running services (the retry an
// enrolment failure asks for) must not write over it.
//
// Simulated where this runs: the stand-in wrapper is a shell script answering
// WinSW's verbs, and fs.copyFileSync refuses to write to it while it "runs",
// with the EBUSY libuv reports for that sharing violation.
//
// Binds no ports, so no range in the port table.

const { test, before, after, afterEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const MGR = path.join(__dirname, '..', '..', '..', 'client', 'huginn-local');

let tmp;
let local;
const realCopy = fs.copyFileSync;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'local-winsw-'));
  process.env.HUGINN_LOCAL_DIR = tmp;
  local = require(MGR);
});
afterEach(() => { fs.copyFileSync = realCopy; local.setRunner(null); });
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/** A WinSW stand-in that logs each verb; `stop` marks it stopped, `start` running. */
function wrapper(version, log, stopped) {
  return `#!/bin/sh
# WinSW ${version}
echo "$1" >> ${log}
case "$1" in stop) : > ${stopped} ;; start) rm -f ${stopped} ;; esac
exit 0
`;
}

/**
 * The running llm service's wrapper, the pinned WinSW beside it, and the lock.
 *
 * The stop now goes through the Service Control Manager (`sc stop <name>`), not
 * by executing the wrapper — a wrapper a standard user may have planted here
 * must never be run with the installer's elevated token. The injected runner
 * stands in for sc: it records the call and, on `stop`, marks the service
 * stopped so the image can be replaced. install/start still run the real
 * stand-in exe (the copy this step wrote), which is why they appear in the log.
 */
function installed(pinned) {
  const dir = path.join(tmp, 'runtime', 'winsw');
  fs.rmSync(path.join(tmp, 'runtime'), { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const log = path.join(tmp, 'verbs.log');
  const stopped = path.join(tmp, 'stopped');
  fs.rmSync(log, { force: true });
  fs.rmSync(stopped, { force: true });
  const exe = path.join(dir, `${local.LLM_SERVICE}.exe`);
  fs.writeFileSync(exe, wrapper('v2.12.0', log, stopped), { mode: 0o755 });
  fs.writeFileSync(path.join(tmp, 'runtime', 'WinSW-x64.exe'), wrapper(pinned, log, stopped), { mode: 0o755 });
  fs.copyFileSync = (src, dst, ...rest) => {
    if (path.resolve(String(dst)) === exe && !fs.existsSync(stopped)) {
      throw Object.assign(new Error(`EBUSY: resource busy or locked, copyfile '${src}' -> '${dst}'`), { code: 'EBUSY' });
    }
    return realCopy(src, dst, ...rest);
  };
  const sc = [];
  local.setRunner((cmd, args = []) => {
    if (path.basename(String(cmd)) === 'sc') {
      sc.push([cmd, ...args].join(' '));
      if (args[0] === 'stop') { try { fs.writeFileSync(stopped, ''); } catch { /* fs busy */ } }
    }
    return { status: 0, stdout: '', stderr: '' };
  });
  return { exe, sc, verbs: () => fs.readFileSync(log, 'utf8').trim().split('\n') };
}

test('a re-run of on leaves the running service\'s own wrapper alone', { skip: process.platform === 'win32' && 'the stand-in is a shell script' }, () => {
  const w = installed('v2.12.0');
  local.installServiceWindows('llm');
  assert.deepEqual(w.verbs(), ['install', 'start'], 'never stopped: the same WinSW needs no replacing');
  assert.deepEqual(w.sc, [], 'and the service is not even asked to stop');
});

test('a different WinSW stops its service BY NAME, never by running the old wrapper', { skip: process.platform === 'win32' && 'the stand-in is a shell script' }, () => {
  const w = installed('v2.13.0');
  local.installServiceWindows('llm');
  // The stop is not a verb the (possibly planted) wrapper was run with.
  assert.deepEqual(w.verbs(), ['install', 'start']);
  assert.deepEqual(w.sc, [`sc stop ${local.LLM_SERVICE}`], 'the service is stopped through sc, by name');
  assert.match(fs.readFileSync(w.exe, 'utf8'), /WinSW v2\.13\.0/, 'and the wrapper is the pinned one now');
});
