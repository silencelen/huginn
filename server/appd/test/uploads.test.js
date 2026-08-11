'use strict';
// The upload gate, tested against the mimes Android actually sends — which is
// the lesson: providers do not speak exact-match. A Samsung file manager hands
// .csv over as text/comma-separated-values, .txt sometimes arrives typeless,
// and the same file reports differently from Downloads and Drive.

const { test } = require('node:test');
const assert = require('node:assert');
const { uploadExtFor, safeExt, isReadable, isImageUpload, contentTypeForUpload } = require('../lib/uploads');

test('isImageUpload recognises the image extensions and nothing else', () => {
  for (const n of ['up-1-ab.jpg', 'img-2-cd.jpeg', 'x.png', 'y.webp', 'z.gif', 'A.PNG']) {
    assert.equal(isImageUpload(n), true, n);
  }
  for (const n of ['up-1-ab.pdf', 'backup.unifi', 'notes.txt', 'noext', 'x.zip', '']) {
    assert.equal(isImageUpload(n), false, n);
  }
});

test('contentTypeForUpload maps known media and defaults to octet-stream', () => {
  assert.equal(contentTypeForUpload('a.jpg'), 'image/jpeg');
  assert.equal(contentTypeForUpload('a.jpeg'), 'image/jpeg');
  assert.equal(contentTypeForUpload('a.png'), 'image/png');
  assert.equal(contentTypeForUpload('a.webp'), 'image/webp');
  assert.equal(contentTypeForUpload('a.gif'), 'image/gif');
  assert.equal(contentTypeForUpload('a.pdf'), 'application/pdf');
  // Active types must NOT be served as themselves — they download.
  assert.equal(contentTypeForUpload('evil.html'), 'application/octet-stream');
  assert.equal(contentTypeForUpload('evil.svg'), 'application/octet-stream');
  assert.equal(contentTypeForUpload('data.csv'), 'application/octet-stream');
  assert.equal(contentTypeForUpload('noext'), 'application/octet-stream');
});

test('the mimes the field actually produced are accepted', () => {
  assert.equal(uploadExtFor('text/plain', 'notes.txt'), 'txt');
  assert.equal(uploadExtFor('text/comma-separated-values', 'data.csv'), 'csv');   // the owner's .csv
  assert.equal(uploadExtFor('application/octet-stream', 'readme.md'), 'md');
  assert.equal(uploadExtFor('', 'config.yaml'), 'yaml');
  assert.equal(uploadExtFor(null, 'notes.txt'), 'txt');
  assert.equal(uploadExtFor('text/x-log', 'syslog.log'), 'log');
});

test('any text family without a usable name still lands as txt', () => {
  assert.equal(uploadExtFor('text/x-something-weird', null), 'txt');
  assert.equal(uploadExtFor('text/plain; charset=utf-8', ''), 'txt');
});

test('the filename outranks an unknown mime, never a known one', () => {
  assert.equal(uploadExtFor('application/pdf', 'renamed.txt'), 'pdf');
  assert.equal(uploadExtFor('application/x-mystery', 'report.pdf'), 'pdf');
});

// POLICY CHANGE 2026-07-29: nothing is refused for its TYPE. The owner was
// blocked sending a UniFi backup (octet-stream + ".unifi"), and "may this be
// stored" is a different question from "can Read display it" — a binary is still
// inspectable with a shell. The type now decides HOW the message says to open it.

test('a binary the owner actually tried is accepted, and marked binary', () => {
  const ext = uploadExtFor('application/octet-stream',
    'unifi_os_backup_1785393505000_8ace9142-8116-4fa4-9b9e-aa92431d2880.unifi');
  assert.equal(ext, 'unifi');
  assert.equal(isReadable(ext), false, 'Read cannot open it, so the message must not say Read');
});

test('archives and databases are stored under their own extension', () => {
  assert.equal(uploadExtFor('application/zip', 'a.zip'), 'zip');
  assert.equal(uploadExtFor('application/octet-stream', 'db.sqlite'), 'sqlite');
  assert.equal(uploadExtFor('application/octet-stream', 'cap.pcap'), 'pcap');
  for (const e of ['zip', 'sqlite', 'pcap']) assert.equal(isReadable(e), false, e);
});

test('nothing to go on still lands somewhere honest', () => {
  // .bin rather than a refusal: it can still be `file`d and unzipped.
  assert.equal(uploadExtFor('application/octet-stream', 'noextension'), 'bin');
  assert.equal(uploadExtFor('', ''), 'bin');
  assert.equal(isReadable('bin'), false);
});

test('an extension is never allowed to be a path or a paragraph', () => {
  // The server names the file, but an extension is still attacker-influenced.
  assert.equal(safeExt('x.tar/../../etc/passwd'), null);
  assert.equal(safeExt('x.' + 'a'.repeat(40)), null);
  assert.equal(safeExt('x.'), null);
  assert.equal(safeExt('.env'), null, 'a dotfile has no extension');
  assert.equal(safeExt('a.JPEG'), 'jpg');
  // Falling back to bin is what keeps those cases storable but inert.
  assert.equal(uploadExtFor('application/octet-stream', 'x.tar/../../etc/passwd'), 'bin');
});

test('readable formats are still recognised as readable', () => {
  for (const e of ['jpg', 'png', 'pdf', 'txt', 'md', 'csv', 'json', 'log']) {
    assert.equal(isReadable(e), true, e);
  }
});

test('legacy: garbage types are stored, not refused', () => {
  // These all used to return null. They now land under their own extension and
  // are flagged unreadable, which is what lets an `act` chat run `file` on them
  // instead of the upload failing before it starts.
  assert.equal(uploadExtFor('application/zip', 'archive.zip'), 'zip');
  assert.equal(uploadExtFor(
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'doc.docx'), 'docx');
  assert.equal(uploadExtFor('application/octet-stream', 'evil.exe'), 'exe');
  for (const e of ['zip', 'docx', 'exe']) assert.equal(isReadable(e), false, e);
});

test('jpeg normalizes to jpg wherever it enters', () => {
  assert.equal(uploadExtFor('image/jpeg', null), 'jpg');
  assert.equal(uploadExtFor('application/octet-stream', 'photo.jpeg'), 'jpg');
});

test('inherited Object properties are not file types', () => {
  // A bare `MIME_EXTS[mime]` answers truthily for constructor/__proto__/toString
  // and the inherited value became the stored extension. The answer is now the
  // honest fallback, and a real filename still wins.
  for (const m of ['constructor', '__proto__', 'toString', 'hasOwnProperty', 'valueOf']) {
    assert.strictEqual(uploadExtFor(m, 'x'), 'bin', m);
    assert.strictEqual(uploadExtFor(m, 'notes.txt'), 'txt', `${m} + real name`);
  }
});
