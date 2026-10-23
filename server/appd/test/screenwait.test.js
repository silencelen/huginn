'use strict';
// lib/screenwait: a parked screen poll peeks a pane fast just after it changed and
// slowly once it has been quiet.

const { test } = require('node:test');
const assert = require('node:assert');
const { peekInterval, QUIET_PEEK_MS } = require('../lib/screenwait');

test('a pane that just changed is peeked fast', () => {
  assert.equal(peekInterval(0), 130);
  assert.equal(peekInterval(2_999), 130);
});

test('a pane quiet for a few seconds is peeked less often', () => {
  assert.equal(peekInterval(3_000), 450);
  assert.equal(peekInterval(9_999), 450);
});

test('a pane quiet for ten seconds or more is peeked every 1.5 s', () => {
  assert.equal(QUIET_PEEK_MS, 1_500);
  assert.equal(peekInterval(10_000), 1_500);
  assert.equal(peekInterval(3_600_000), 1_500);
});
