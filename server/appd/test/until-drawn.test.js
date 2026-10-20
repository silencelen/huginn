'use strict';
// until-drawn.js: the wait that every fixture helper drawing a pane goes through
// before it hands that pane to a test.
//
// tmux 3.6 starts a new pane's command only AFTER `new-session` has returned,
// wherever it can reach a user bus (until-drawn.js says why). The two
// precondition asserts this wait now protects passed wherever tmux is older or
// no user bus is reachable, and failed on GitHub's 26.04 runners, so this file
// reproduces that shape on ANY host. tmux runs every pane command as `<default-shell> -c
// <command>`, so a private server whose default-shell sleeps before it execs
// starts each pane's real command late, the way 3.6's forked child waits on
// systemd before it execs.
//
// SAFETY: a private tmux server on its own `-L` socket, killed in after(). Its
// panes run `printf; sleep` or `cat >/dev/null`, so nothing typed executes, and
// no daemon is started, so no port is taken.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { untilDrawn } = require('./until-drawn');

const SOCK = `huginn-drawn-${process.pid}`;
// How late every pane's command starts. Long enough that a capture taken at once
// cannot win the race even on a loaded host.
const LATE_S = '0.8';
const DIALOG = 'Switch model?\\n\\n 1. Yes\\n 2. No\\n\\n Enter to confirm\\n';

let tmp;

function tmux(args) {
  return execFileSync('tmux', ['-L', SOCK, ...args], { encoding: 'utf8' });
}
function capture(name) {
  return tmux(['capture-pane', '-p', '-t', `=${name}:`]);
}

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-drawn-'));
  const late = path.join(tmp, 'late-sh');
  fs.writeFileSync(late, `#!/bin/sh\nsleep ${LATE_S}\nexec /bin/sh "$@"\n`, { mode: 0o755 });
  // The server needs a session to exist before an option can be set on it. This
  // one starts on the ordinary shell; every pane after it starts late.
  tmux(['new-session', '-d', '-s', 'keep', 'cat >/dev/null']);
  tmux(['set-option', '-g', 'default-shell', late]);
});

after(() => {
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('a pane whose command starts after new-session returns is handed over only once drawn', () => {
  tmux(['new-session', '-d', '-s', 'late', '-x', '80', '-y', '24', `printf "${DIALOG}"; sleep 600`]);
  // The shape tmux 3.6 hands back: a session that exists, over a pane that has
  // drawn nothing. If this fails, the late shell did not take (a noexec tmp
  // makes tmux fall back to /bin/sh), and the assertions below would prove
  // nothing.
  assert.doesNotMatch(capture('late'), /Switch model\?/,
    'precondition: new-session returned before the pane drew anything');

  const screen = untilDrawn(SOCK, 'late', /Enter to confirm/);
  assert.match(screen, /Switch model\?/, 'it returns the screen it waited for');
  assert.match(capture('late'), /Switch model\?/,
    'and a capture taken straight after it reads the dialog, which is what every precondition does');
});

test('a pane that never draws is an error that names it and says what IS on screen', () => {
  tmux(['new-session', '-d', '-s', 'blank', '-x', '80', '-y', '24', 'cat >/dev/null']);
  assert.throws(() => untilDrawn(SOCK, 'blank', /Enter to confirm/, { ms: 300 }),
    /fixture pane blank never drew \/Enter to confirm\/ in 300 ms; it shows ""/);
});
