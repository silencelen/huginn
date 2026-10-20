'use strict';
// Whose wind-down a pending soft end is.
//
// A soft end is asked of ONE run of Claude, the one the wrap-up phrase was typed
// into, but the daemon files it under the session's tmux NAME, and a name
// outlives its session: Claude exits to the shell and somebody starts it again,
// the session is killed at a terminal and `cc` makes a new one, a conversation is
// cleared. These pin the rule that keeps a pending auto-end (and the archive that
// may ride on it) from settling on whoever holds the name next: the end belongs
// to the holder it was first seen with — that Claude session id in that tmux
// session — and a different holder, or a run that has ended, means it is over.

const { test } = require('node:test');
const assert = require('node:assert');
const { createPending, stepSoftEnd, KILL_STABLE_MS, ARM_TIMEOUT_MS } = require('../lib/softend');

const RUN = { sessionId: '11111111-1111-4111-8111-111111111111', born: 1_000 };
/** A new `claude` started in the same pane: same tmux session, new conversation. */
const NEXT_CLAUDE = { sessionId: '22222222-2222-4222-8222-222222222222', born: 1_000 };
/** The same conversation resumed in a NEW tmux session that took the name. */
const NEXT_SESSION = { sessionId: RUN.sessionId, born: 2_000 };

/** Drive the machine through [state, dtMs, who] observations; the last action and record. */
function run(steps, t0 = 1_000_000) {
  let now = t0;
  let pending = createPending(now);
  let action = null;
  for (const [state, dt, who] of steps) {
    now += dt;
    ({ pending, action } = stepSoftEnd(pending, state, now, who));
  }
  return { action, pending };
}

test('the run it was asked of still settles and is ended', () => {
  const { action } = run([
    ['running', 100, RUN],
    ['idle', 100, RUN],
    ['idle', KILL_STABLE_MS + 10, RUN],
  ]);
  assert.equal(action, 'kill');
});

test('a Claude that exits during its wind-down ends it: the next one in the pane is not killed', () => {
  // SessionEnd removes the state file when Claude exits, and the idle timer
  // the wrap-up's own Stop started used to survive the gap — so the next
  // `claude` started in that pane was killed on its first idle.
  const exited = run([
    ['running', 100, RUN],
    ['idle', 100, RUN],
    [null, 500, RUN],
  ]);
  assert.equal(exited.action, 'gone', 'no state file after the run started: that Claude has exited');
  const next = run([
    ['running', 100, RUN],
    ['idle', 100, RUN],
    ['idle', KILL_STABLE_MS + 10, NEXT_CLAUDE],
  ]);
  assert.equal(next.action, 'gone', 'a different conversation now holds the name');
});

test('a session killed at a terminal and made again under the name is not the one to end', () => {
  // `tmux kill-session` leaves the dead run's `running` behind (no hook can
  // reach a dead pane), so nothing in between says the run ended; the new
  // session's own first idle is the next thing seen.
  const { action } = run([
    ['running', 100, RUN],
    ['running', 60_000, RUN],
    ['idle', 100, NEXT_CLAUDE],
    ['idle', KILL_STABLE_MS + 10, NEXT_CLAUDE],
  ]);
  assert.equal(action, 'gone');
});

test('the same conversation resumed in a new tmux session is a new holder too', () => {
  const { action } = run([
    ['running', 100, RUN],
    ['idle', 100, NEXT_SESSION],
    ['idle', KILL_STABLE_MS + 10, NEXT_SESSION],
  ]);
  assert.equal(action, 'gone');
});

test('a holder seen before the run started is held to it as well', () => {
  // The phrase lands in the session's own idle; a stranger that starts running
  // under the name before the wrap-up turn shows up is not what to arm on.
  const { action } = run([
    ['idle', 100, RUN],
    ['running', 100, NEXT_CLAUDE],
  ]);
  assert.equal(action, 'gone');
});

test('what cannot be observed never decides on its own', () => {
  // A bare-word state file has no session id, and a birth time is only known
  // once tmux has been asked; neither is a reason to drop or to kill.
  const unknown = { sessionId: null, born: null };
  const { action } = run([
    ['running', 100, unknown],
    ['idle', 100, RUN],
    ['idle', KILL_STABLE_MS + 10, unknown],
  ]);
  assert.equal(action, 'kill');
  const noWho = run([
    ['running', 100],
    ['idle', 100],
    ['idle', KILL_STABLE_MS + 10],
  ]);
  assert.equal(noWho.action, 'kill', 'a caller that passes no holder gets the old behaviour');
});

test('before the run starts, no state file is still only "not seen yet"', () => {
  assert.equal(run([[null, 100, RUN]]).action, 'wait');
  assert.equal(run([[null, 100, RUN], [null, ARM_TIMEOUT_MS + 10, RUN]]).action, 'expire');
});
