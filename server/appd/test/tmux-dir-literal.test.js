'use strict';
// Every directory huginn-appd.js hands to `tmux new-session -c` goes through
// tmuxDir(), which doubles `#`. tmux reads `-c` as a format, and `#(…)` in a
// format runs a command, so one call site that forgets is a directory name that
// runs something (see tmuxDir in huginn-appd.js, and the member-directory test
// in routes-projects.test.js for the same thing through a real tmux).

const { test } = require('node:test');
const assert = require('node:assert');
const { SRC, lift } = require('./appd-lift');

test('every -c handed to tmux new-session goes through tmuxDir', () => {
  const sites = SRC.split('\n').filter((l) => l.includes("'new-session'") && l.includes("'-c'"));
  assert.ok(sites.length >= 5, `the call sites are still found this way (${sites.length})`);
  const bare = sites.filter((l) => [...l.matchAll(/'-c',\s*([^,\]]+)/g)].some((m) => !m[1].trim().startsWith('tmuxDir(')));
  assert.deepEqual(bare.map((l) => l.trim()), []);
});

test('tmuxDir doubles every #, which tmux reads back as one literal #', () => {
  const { fns } = lift(['tmuxDir']);
  assert.equal(fns.tmuxDir('/srv/a#(touch x)b'), '/srv/a##(touch x)b');
  assert.equal(fns.tmuxDir('/srv/#{session_name}'), '/srv/##{session_name}');
  assert.equal(fns.tmuxDir('/srv/##'), '/srv/####');
  assert.equal(fns.tmuxDir('/srv/plain dir'), '/srv/plain dir');
});
