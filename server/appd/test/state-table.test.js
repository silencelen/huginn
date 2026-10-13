'use strict';
// The brand state table (server/lib/state-table.json) reaches the daemon as a
// GENERATED module, lib/state-table.js, because deploy.sh ships lib/*.js and
// nothing above it. This pins that the copy the daemon requires is the one the
// table generates, so a hand edit here cannot quietly give Telegram a different
// glyph from every renderer on the host.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { spawnSync } = require('child_process');

const REPO = path.resolve(__dirname, '..', '..', '..');

test('every state-table reader matches server/lib/state-table.json', () => {
  const r = spawnSync(process.execPath, [path.join(REPO, 'scripts/gen-state-table.js'), '--check'], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr || r.stdout);
});

test('the daemon reads the seven words, one 7-bit mark each', () => {
  const { STATES, WORD, MARK, GLYPH } = require('../lib/state-table');
  assert.deepEqual(STATES.map((s) => s.key), ['running', 'needsYou', 'waiting', 'ok', 'error', 'info', 'shell']);
  assert.equal(WORD.needsYou, 'needs you');
  assert.equal(new Set(Object.values(MARK)).size, 7);
  for (const m of Object.values(MARK)) assert.match(m, /^[\x21-\x7e]$/);
  for (const g of Object.values(GLYPH)) assert.equal([...g].length, 1);
});
