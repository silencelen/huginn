'use strict';
// The upload gate, tested against the mimes Android actually sends — which is
// the lesson: providers do not speak exact-match. A Samsung file manager hands
// .csv over as text/comma-separated-values, .txt sometimes arrives typeless,
// and the same file reports differently from Downloads and Drive.

const { test } = require('node:test');
const assert = require('node:assert');
const { uploadExtFor } = require('../lib/uploads');

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

test('what Read would print as garbage is refused, name tricks included', () => {
  assert.equal(uploadExtFor('application/zip', 'archive.zip'), null);
  assert.equal(uploadExtFor('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'doc.docx'), null);
  assert.equal(uploadExtFor('application/octet-stream', 'evil.exe'), null);
  assert.equal(uploadExtFor('application/octet-stream', 'noextension'), null);
  assert.equal(uploadExtFor('', ''), null);
  // A dotfile's "extension" is its whole name; refusing beats storing ".env" as env? No:
  // ".env" has dot at index 0 -> no ext -> and no text mime -> refused. Pinned.
  assert.equal(uploadExtFor('application/octet-stream', '.env'), null);
});

test('jpeg normalizes to jpg wherever it enters', () => {
  assert.equal(uploadExtFor('image/jpeg', null), 'jpg');
  assert.equal(uploadExtFor('application/octet-stream', 'photo.jpeg'), 'jpg');
});
