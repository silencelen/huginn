'use strict';
// Every route suite binds a real port out of its own block, `base + pid % width`,
// and `node --test` runs the files concurrently. Two blocks that overlap pass
// most runs and then fail a whole file on an unlucky pair of pids, so the blocks
// are read from the code itself here, not from a table kept by hand.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const BLOCK = /(?:const|let)\s+\w*PORT\w*\s*=\s*(\d+)\s*\+\s*\(?\s*process\.pid\s*%\s*(\d+)\s*\)?/g;

test('no two test files draw their ports from overlapping blocks', () => {
  const blocks = [];
  for (const f of fs.readdirSync(__dirname).filter((n) => n.endsWith('.test.js'))) {
    const src = fs.readFileSync(path.join(__dirname, f), 'utf8');
    for (const m of src.matchAll(BLOCK)) {
      const lo = Number(m[1]);
      blocks.push({ f, lo, hi: lo + Number(m[2]) - 1 });
    }
  }
  assert.ok(blocks.length > 20, `found only ${blocks.length} blocks: the pattern no longer matches the suites`);
  blocks.sort((a, b) => a.lo - b.lo);
  const clashes = [];
  for (let i = 0; i < blocks.length; i++) {
    for (let j = i + 1; j < blocks.length && blocks[j].lo <= blocks[i].hi; j++) {
      if (blocks[j].f !== blocks[i].f) {
        clashes.push(`${blocks[i].f} ${blocks[i].lo}-${blocks[i].hi} overlaps ${blocks[j].f} ${blocks[j].lo}-${blocks[j].hi}`);
      }
    }
  }
  assert.deepEqual(clashes, []);
});
