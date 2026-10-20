'use strict';
// Lift top-level functions and constants out of huginn-appd.js and run them in
// a sandbox, so the headroom tests exercise the daemon's REAL code rather than
// a copy of it — the same move attach-markers-words.test.js makes for one
// function, for the ladder's several.
//
// Only what is named is lifted. Every other name a lifted body refers to
// (`log`, `hstate`, `enqueueJob`, a lib module) is supplied by the test as a
// sandbox global, which is what keeps tmux, the network and the real data
// directory out of reach.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC_FILE = path.join(__dirname, '..', 'huginn-appd.js');

/** One top-level declaration of huginn-appd.js, as source text. */
function pick(src, name) {
  for (const head of [`\nasync function ${name}(`, `\nfunction ${name}(`]) {
    const a = src.indexOf(head);
    if (a < 0) continue;
    // Top-level bodies close on a column-0 brace; nested ones are indented.
    const b = src.indexOf('\n}\n', a + 1);
    if (b < 0) throw new Error(`${name} has no closing brace in huginn-appd.js`);
    return src.slice(a + 1, b + 3);
  }
  const c = src.indexOf(`\nconst ${name} =`);
  if (c >= 0) {
    const e = src.indexOf(';\n', c);
    return src.slice(c + 1, e + 2);
  }
  throw new Error(`${name} is gone from huginn-appd.js`);
}

/**
 * The source between two needles, inclusive — for a block that is not a
 * declaration of its own (a route inside the request handler).
 */
function between(startNeedle, endNeedle) {
  const src = fs.readFileSync(SRC_FILE, 'utf8');
  const a = src.indexOf(startNeedle);
  if (a < 0) throw new Error(`${startNeedle.slice(0, 60)} is gone from huginn-appd.js`);
  const b = src.indexOf(endNeedle, a + startNeedle.length);
  if (b < 0) throw new Error(`no ${JSON.stringify(endNeedle)} after ${startNeedle.slice(0, 60)}`);
  return src.slice(a, b + endNeedle.length);
}

/** Is this a top-level function or const of huginn-appd.js? */
function declared(src, name) {
  return [`\nasync function ${name}(`, `\nfunction ${name}(`, `\nconst ${name} =`].some((h) => src.includes(h));
}

/**
 * Run the named declarations (plus any `extra` source) in a fresh context whose
 * globals are `globals`; returns the context and the named functions.
 *
 * `helpers` are lifted only where the source declares them: the private
 * helpers of a lifted function come and go between versions, and a test that
 * named them all would fail on the version without one for the wrong reason.
 */
function lift(names, globals = {}, { extra = '', helpers = [] } = {}) {
  const src = fs.readFileSync(SRC_FILE, 'utf8');
  const all = [...names, ...helpers.filter((n) => declared(src, n))];
  const exported = all.filter((n) => !/^[A-Z0-9_]+$/.test(n));
  const code = `${all.map((n) => pick(src, n)).join('\n')}\n${extra}\n`
    + `this.__lifted = { ${exported.join(', ')} };`;
  const box = vm.createContext({ ...globals });
  vm.runInContext(code, box, { filename: 'huginn-appd.js (lifted)' });
  return { box, ...box.__lifted };
}

module.exports = { lift, between, SRC_FILE };
