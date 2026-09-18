'use strict';
// The upload gate, tested against the mimes Android actually sends — which is
// the lesson: providers do not speak exact-match. A Samsung file manager hands
// .csv over as text/comma-separated-values, .txt sometimes arrives typeless,
// and the same file reports differently from Downloads and Drive.

const { test } = require('node:test');
const assert = require('node:assert');
const { uploadExtFor, safeExt, isReadable, isImageUpload, contentTypeForUpload,
  createUploadPruner, PRUNE_MIN_INTERVAL_MS } = require('../lib/uploads');
const path = require('node:path');

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

// ------------------------------------------------- the retention sweep, throttled
//
// pruneUploads hangs off POST /v1/uploads, which was right while an attach was
// one file. Multi-attach makes it ten POSTs inside a second and therefore ten
// full readdir+stat sweeps of the same directory, to delete the same nothing,
// on the event loop that is simultaneously streaming those ten uploads to disk.
//
// The sweep is now rate-limited to one a minute. These three assert the limit,
// that it expires, and that it did not quietly change WHAT is swept — the fs and
// the clock are injected precisely so a rate limit can be proven in a
// millisecond instead of by sleeping through it.

/** A fake fs that counts sweeps and remembers what was unlinked. */
function spyFs(entries) {
  const spy = {
    readdirs: 0,
    unlinked: [],
    readdirSync() { spy.readdirs += 1; return Object.keys(entries); },
    statSync(f) {
      const n = path.basename(f);
      if (!(n in entries)) throw new Error('ENOENT');
      return { mtimeMs: entries[n] };
    },
    unlinkSync(f) { spy.unlinked.push(path.basename(f)); },
  };
  return spy;
}

test('ten uploads inside a minute sweep the directory exactly once', () => {
  const fsSpy = spyFs({});
  let now = 1_800_000_000_000;
  const prune = createUploadPruner({ dir: '/uploads', fs: fsSpy, now: () => now, keepMs: 1000 });
  for (let i = 0; i < 10; i++) { prune(); now += 100; }   // a whole multi-attach, one second
  assert.equal(fsSpy.readdirs, 1,
    'a ten-file attach must not read the uploads directory ten times');
});

test('the sweep runs again once the interval has passed', () => {
  const fsSpy = spyFs({});
  let now = 1_800_000_000_000;
  const prune = createUploadPruner({ dir: '/uploads', fs: fsSpy, now: () => now, keepMs: 1000 });
  assert.equal(prune(), true, 'the first call after start always sweeps');
  now += PRUNE_MIN_INTERVAL_MS - 1;
  assert.equal(prune(), false, 'one millisecond short is still inside the window');
  assert.equal(fsSpy.readdirs, 1);
  now += 1;
  assert.equal(prune(), true, 'exactly at the interval sweeps rather than off-by-one');
  assert.equal(fsSpy.readdirs, 2);
  now += PRUNE_MIN_INTERVAL_MS * 10;
  assert.equal(prune(), true);
  assert.equal(fsSpy.readdirs, 3);
});

test('throttled or not, images are never pruned and only the stale non-images go', () => {
  const now0 = 1_800_000_000_000;
  const old = now0 - 30 * 24 * 60 * 60 * 1000;    // a month back
  const fsSpy = spyFs({
    'up-1-aa.png': old, 'up-2-bb.jpg': old, 'up-3-cc.webp': old, 'up-4-dd.gif': old,
    'up-5-ee.txt': old, 'up-6-ff.unifi': old, 'up-7-gg.pdf': old,
    'up-8-hh.txt': now0,                          // fresh, stays
  });
  let now = now0;
  const prune = createUploadPruner({
    dir: '/uploads', fs: fsSpy, now: () => now, keepMs: 7 * 24 * 60 * 60 * 1000,
  });
  prune();
  assert.deepEqual(fsSpy.unlinked.sort(), ['up-5-ee.txt', 'up-6-ff.unifi', 'up-7-gg.pdf'],
    'images are kept until manually deleted; a fresh non-image is inside retention');
  // And the throttle does not smuggle a second sweep past the exemption either.
  fsSpy.unlinked.length = 0;
  now += PRUNE_MIN_INTERVAL_MS;
  prune();
  assert.equal(fsSpy.unlinked.filter((n) => isImageUpload(n)).length, 0);
});
