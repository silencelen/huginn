'use strict';
// Lifts named top-level pieces of huginn-appd.js out of the source and runs them
// in a sandbox, so a test can exercise the daemon's REAL code for one decision
// without starting a daemon, binding a port or touching tmux.
//
// The entry file exports nothing. Its top-level functions and classes start at
// column 0 and end at the first line that is exactly "}", which is what makes
// them liftable by text. A piece that is missing, appears twice, or does not
// compile on its own fails the test loudly instead of testing something else.
//
// Not a test file (no `.test.js`), so `node --test test/*.test.js` skips it.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'huginn-appd.js'), 'utf8');

/** The source of one top-level function, async function or class, by name. */
function piece(name) {
  const heads = [`\nfunction ${name}(`, `\nasync function ${name}(`, `\nclass ${name} {`];
  const found = heads.map((h) => [h, SRC.indexOf(h)]).filter(([, i]) => i >= 0);
  assert.equal(found.length, 1, `${name}: expected exactly one top-level definition in huginn-appd.js`);
  const [head, i] = found[0];
  assert.equal(SRC.indexOf(head, i + 1), -1, `${name} is defined twice in huginn-appd.js`);
  const start = i + 1;
  const end = SRC.indexOf('\n}\n', start);
  assert.ok(end > start, `${name} has no closing brace at column 0`);
  return SRC.slice(start, end + 2);
}

/** One top-level `const NAME = ...;` statement, by name. */
function constant(name) {
  const head = `\nconst ${name} = `;
  const i = SRC.indexOf(head);
  assert.ok(i >= 0, `const ${name} is gone from huginn-appd.js`);
  const end = SRC.indexOf(';\n', i);
  return SRC.slice(i + 1, end + 2);
}

/** The text from `startNeedle` to the first `endNeedle` after it, both included. */
function between(startNeedle, endNeedle) {
  const a = SRC.indexOf(startNeedle);
  assert.ok(a >= 0, `${JSON.stringify(startNeedle.slice(0, 60))} is gone from huginn-appd.js`);
  const b = SRC.indexOf(endNeedle, a + startNeedle.length);
  assert.ok(b > a, `no ${JSON.stringify(endNeedle)} after ${JSON.stringify(startNeedle.slice(0, 60))}`);
  return SRC.slice(a, b + endNeedle.length);
}

/**
 * Runs the named pieces in one fresh context whose globals are `stubs`.
 *
 * @param names functions and classes to lift; they call each other as in the daemon
 * @param stubs everything else they reach for (log, fs helpers, other functions)
 * @param opts.constants top-level consts to lift verbatim rather than stub
 * @param opts.extra source appended after the pieces (a wrapper around a route block)
 * @param opts.exports names from `extra` to hand back too
 * @returns { fns, ctx } — the lifted functions, and the context (to swap a stub later)
 */
function lift(names, stubs = {}, { constants = [], extra = '', exports = [] } = {}) {
  const all = [...names, ...exports];
  const code = [
    ...constants.map(constant),
    ...names.map(piece),
    extra,
    `globalThis.__lifted = { ${all.join(', ')} };`,
  ].join('\n');
  new vm.Script(code, { filename: 'huginn-appd.js (lifted)' });   // compiles, or says why here
  const ctx = vm.createContext({ ...stubs });
  vm.runInContext(code, ctx, { filename: 'huginn-appd.js (lifted)' });
  return { fns: ctx.__lifted, ctx };
}

module.exports = { SRC, piece, constant, between, lift };
