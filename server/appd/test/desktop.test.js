'use strict';
// The desktop update channel's gate: names are safe by construction (the
// route builds paths only from names this regex passed), and the manifest
// reader never throws at a request.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { validName, contentTypeFor, readManifest, resolveArtifact } = require('../lib/desktop');

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

test('the Compose client\'s artifact names pass too', () => {
  // /v1/desktop-kt serves jpackage/NSIS output, not electron-builder's.
  assert.ok(validName('Huginn-Desktop-Setup-0.1.0.exe'));
  assert.ok(validName('huginn-desktop-kt_0.1.0-1_amd64.deb'));
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

test('resolveArtifact answers with what the route must send', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-test-'));
  try {
    fs.writeFileSync(path.join(dir, 'Setup-0.1.0.exe'), 'MZ-ish');
    const ok = resolveArtifact(dir, 'Setup-0.1.0.exe');
    assert.equal(ok.ok, true);
    assert.equal(ok.size, 6);
    assert.equal(ok.contentType, 'application/octet-stream');
    assert.equal(ok.file, path.join(dir, 'Setup-0.1.0.exe'));

    assert.deepEqual(resolveArtifact(dir, 'nope.exe'), { ok: false, status: 404, error: 'no such file' });
    assert.deepEqual(resolveArtifact(dir, '../etc'), { ok: false, status: 400, error: 'bad name' });

    // A directory used to be a 200 with a Content-Length, then an EISDIR after
    // the headers had already gone out.
    fs.mkdirSync(path.join(dir, 'subdir'));
    assert.deepEqual(resolveArtifact(dir, 'subdir'), { ok: false, status: 404, error: 'no such file' });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the two channels are separate directories, not a shared one', () => {
  // The point of the whole split: the owner is RUNNING the Electron client
  // against /v1/desktop, and it installs whatever that channel offers. A
  // Compose artifact resolvable from the Electron directory would be an
  // "update" into a different application.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-test-'));
  try {
    const electron = path.join(root, 'desktop');
    const compose = path.join(root, 'desktop-kt');
    fs.mkdirSync(electron); fs.mkdirSync(compose);
    fs.writeFileSync(path.join(electron, 'manifest.json'), JSON.stringify({ version: '0.4.0' }));
    fs.writeFileSync(path.join(electron, 'Huginn-Setup-0.4.0.exe'), 'electron');
    fs.writeFileSync(path.join(compose, 'manifest.json'), JSON.stringify({ version: '0.1.0' }));
    fs.writeFileSync(path.join(compose, 'Huginn-Desktop-Setup-0.1.0.exe'), 'compose');

    assert.equal(readManifest(electron).version, '0.4.0');
    assert.equal(readManifest(compose).version, '0.1.0');
    // Neither channel can reach the other's files, by name or by traversal.
    assert.equal(resolveArtifact(electron, 'Huginn-Desktop-Setup-0.1.0.exe').ok, false);
    assert.equal(resolveArtifact(compose, 'Huginn-Setup-0.4.0.exe').ok, false);
    assert.equal(resolveArtifact(compose, '../desktop/Huginn-Setup-0.4.0.exe').status, 400);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
