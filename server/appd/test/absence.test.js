'use strict';
// NOTHING DESTRUCTIVE ON ONE LOOK (2026-11-06): the "absent since" ledger the
// projects and registry reconciles keep between passes.
//
// One listing of tmux came back empty while every session was alive, and the
// projects reconcile archived five projects on it in a single tick. A thing
// missing from a listing is now only noted, falls due after a window of
// listings that all lack it, and is forgotten the moment one has it again.
//
// Pure: a clock passed in, no daemon, no tmux.

const { test } = require('node:test');
const assert = require('node:assert');
const { absenceLedger } = require('../lib/absence');

const T0 = 1_789_460_000_000;

test('a key falls due only after it has been missing for the whole window', () => {
  const l = absenceLedger(55_000);
  assert.equal(l.missing('p\tlead', T0), 'new', 'one look notes it');
  assert.equal(l.missing('p\tlead', T0 + 30_000), 'waiting');
  assert.equal(l.missing('p\tlead', T0 + 54_999), 'waiting', 'one millisecond short is not enough');
  assert.equal(l.missing('p\tlead', T0 + 55_000), 'due');
  assert.equal(l.missing('p\tlead', T0 + 120_000), 'due', 'and stays due until the caller writes it off');
  assert.equal(l.row('p\tlead').since, T0, 'counted from the FIRST listing that lacked it');
});

test('one listing that has it again forgets the absence, and the clock starts over', () => {
  const l = absenceLedger(55_000);
  l.missing('p\tdocs', T0);
  const was = l.seen('p\tdocs');
  assert.ok(was, 'seen() hands back the row it had while missing, for a "back" log line');
  assert.equal(was.since, T0);
  assert.equal(l.seen('p\tdocs'), null, 'a key that was not missing has nothing to hand back');
  assert.equal(l.missing('p\tdocs', T0 + 60_000), 'new',
    'missing again after being seen is a new absence, not one that has been running since T0');
  assert.equal(l.missing('p\tdocs', T0 + 100_000), 'waiting');
});

test('keys nobody looked at this pass are dropped; forget() ends one', () => {
  const l = absenceLedger(1_000);
  l.missing('a', T0);
  l.missing('b', T0);
  l.missing('c', T0);
  l.retain(new Set(['a', 'c']));
  assert.equal(l.size, 2);
  assert.equal(l.row('b'), null, 'b left the store by another road');
  l.forget('a');
  assert.equal(l.row('a'), null);
  assert.equal(l.missing('a', T0 + 5_000), 'new', 'a forgotten key starts from scratch');
});
