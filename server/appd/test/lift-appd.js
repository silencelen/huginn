'use strict';
// Runs top-level functions from the daemon's entry file without starting it.
//
// Some of the account wiring lives only in huginn-appd.js — the refresh timer,
// its owner warning — and a daemon cannot be made to run its 60-second refresh
// tick on demand. So, like attach-markers-words.test.js, the functions are
// lifted out of the source and run in a sandbox whose free names the test
// supplies: the test exercises the real function, not a copy of it.
//
// A top-level function is taken from its `function NAME(` line to the first `}`
// in column 0 after it, which is where every top-level function in the file ends.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'huginn-appd.js'), 'utf8');

function sourceOf(name) {
  const at = SRC.search(new RegExp(`^(?:async )?function ${name}\\(`, 'm'));
  if (at < 0) throw new Error(`${name} is gone from huginn-appd.js`);
  const end = SRC.indexOf('\n}\n', at);
  if (end < 0) throw new Error(`${name} has no closing brace in column 0`);
  return SRC.slice(at, end + 2);
}

/**
 * @param names    the functions to lift, in any order
 * @param globals  every free name they read (the sandbox's global object)
 * @param prelude  source run first in the same script, for module-level `let`s
 * @returns the sandbox, with each lifted function on it by name
 */
function lift(names, globals, prelude = '') {
  const box = vm.createContext({ ...globals });
  vm.runInContext(`${prelude}\n${names.map(sourceOf).join('\n')}`, box);
  return box;
}

module.exports = { lift, sourceOf };
