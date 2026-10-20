'use strict';

// A fixture pane is handed to a test once tmux has DRAWN it, never merely when
// `new-session` has returned.
//
// ⚠ ON tmux 3.6, `new-session -d` RETURNS BEFORE THE PANE'S COMMAND HAS EVEN
// STARTED. Built with systemd, as Debian and Ubuntu build it, tmux moves every
// new pane into a scope of its own over the user bus. Through 3.5a the SERVER
// made that call, after the fork and before it answered the client, so the
// pane's command had the whole round trip to paint in, and a capture taken
// straight after `new-session` read the fixture. 3.6 makes the call from the
// forked child, BEFORE exec, and waits for systemd to report the job done
// (upstream f0a85d0469), while the server answers at once. Wherever a user bus
// is reachable (GitHub's Ubuntu runners have one: linger is on and
// XDG_RUNTIME_DIR is set), the pane is still blank, and still a forked tmux,
// when `new-session` returns. Ubuntu 26.04 ships 3.6a, and the two
// preconditions that captured at once read thirty blank lines there.
//
// So every helper that hands a test "a pane showing X" waits here until X is on
// screen, and says what IS there if it never comes. Synchronous on purpose: the
// helpers it serves are, and every test calls them without `await`.

const { execFileSync } = require('node:child_process');

/**
 * Block until pane `=<name>:` on the private `-L <socket>` server shows `re`,
 * and return what it shows. Throws if it has not appeared within `ms`, or at
 * once if the session is gone, since nothing will ever draw there.
 *
 * `-u`: some fixtures are recognised by a non-ASCII glyph (the `❯` caret), and
 * a client in a non-UTF-8 locale prints it as `_`.
 */
function untilDrawn(socket, name, re, { ms = 10_000, tickMs = 20 } = {}) {
  const until = Date.now() + ms;
  for (;;) {
    const screen = execFileSync('tmux', ['-u', '-L', socket, 'capture-pane', '-p', '-t', `=${name}:`],
      { encoding: 'utf8' });
    if (re.test(screen)) return screen;
    if (Date.now() >= until) {
      throw new Error(`fixture pane ${name} never drew ${re} in ${ms} ms; it shows ${JSON.stringify(screen.trim())}`);
    }
    // A synchronous sleep. It blocks this test process only, never the daemon
    // under test, which is a separate process.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, tickMs);
  }
}

module.exports = { untilDrawn };
