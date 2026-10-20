'use strict';
// discoverModels reads the claude binary's model ids with `strings`, through sh.
// The binary's path reaches sh as "$1", never inside the script text: in double
// quotes sh still expands $(...), backticks and $VAR, so a path holding one was
// run rather than read.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const models = require('../lib/models');

test('a binary path holding $(...) is read as a path, never run', async () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'models-binpath-'));
  const cwd = process.cwd();
  process.chdir(d); // where a `touch ran` would land
  try {
    const bin = path.join(d, 'cl$(touch ran)aude');
    fs.writeFileSync(bin, 'xx claude-opus-4-1-20250805 yy\n');
    await models.discoverModels(bin, 10000);
    assert.equal(fs.existsSync(path.join(d, 'ran')), false, 'the path was run as a command');
  } finally {
    process.chdir(cwd);
    fs.rmSync(d, { recursive: true, force: true });
  }
});
