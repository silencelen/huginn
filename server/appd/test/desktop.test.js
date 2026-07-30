'use strict';
// The desktop update channel's gate: names are safe by construction (the
// route builds paths only from names this regex passed), and the manifest
// reader never throws at a request.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { validName, contentTypeFor, readManifest } = require('../lib/desktop');

test('real artifact names pass', () => {
  assert.ok(validName('Huginn-Setup-0.1.0.exe'));
  assert.ok(validName('huginn-desktop-0.1.0.AppImage'));
  assert.ok(validName('huginn-desktop-0.1.0.deb'));
  assert.ok(validName('latest.yml'));
  assert.ok(validName('latest-linux.yml'));
  assert.ok(validName('manifest.json'));
  assert.ok(validName('CHANGELOG.md'));
  assert.ok(validName('Huginn-Setup-0.1.0.exe.blockmap'));
});

test('traversal and separator shapes cannot pass', () => {
  assert.ok(!validName('../secrets'));
  assert.ok(!validName('..'));
  assert.ok(!validName('a/../../etc/passwd'));
  assert.ok(!validName('a/b'));
  assert.ok(!validName('a\\b'));
  assert.ok(!validName('.hidden'));
  assert.ok(!validName('-leading'));
  assert.ok(!validName(''));
  assert.ok(!validName('x'.repeat(90)));
  assert.ok(!validName('name with space.exe'));
  assert.ok(!validName('name%00.exe'));
});

test('content types map by extension, octet-stream otherwise', () => {
  assert.equal(contentTypeFor('latest.yml'), 'text/yaml; charset=utf-8');
  assert.equal(contentTypeFor('manifest.json'), 'application/json; charset=utf-8');
  assert.equal(contentTypeFor('Huginn-Setup-0.1.0.exe'), 'application/octet-stream');
  assert.equal(contentTypeFor('huginn-desktop-0.1.0.AppImage'), 'application/octet-stream');
  assert.equal(contentTypeFor('weird.xyz'), 'application/octet-stream');
});

test('manifest reads back, and absence is null not a throw', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-test-'));
  try {
    assert.equal(readManifest(dir), null);
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ version: '0.1.0' }));
    assert.deepEqual(readManifest(dir), { version: '0.1.0' });
    fs.writeFileSync(path.join(dir, 'manifest.json'), 'not json');
    assert.equal(readManifest(dir), null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
