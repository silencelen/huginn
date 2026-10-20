'use strict';
// lib/files.js — a `~/` path the assistant writes, resolved against the HOME it
// means rather than read as a directory called `~` under the session cwd.
//
// Both clients extract `~/…` mentions on purpose (ImageMentions anchors on
// `~/`) and send them verbatim to GET /v1/files/image. The resolver used to
// treat them as relative, so `~/proj/shots/a.jpg` in a session whose cwd is
// ~/proj was looked for at `<cwd>/~/proj/shots/a.jpg` — a 404 for a file that
// sat inside an allowed root — and was a 400 with no session at all.
//
// SAFETY: no daemon, no socket. Real files, only inside a mkdtemp'd scratch dir
// that is removed again. HOME is pointed at that scratch dir for exactly one
// test and put back.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { resolveImage } = require('../lib/files');

const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

let tmp, home, proj, uploads, outside;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-files-home-'));
  home = path.join(tmp, 'home');
  proj = path.join(home, 'proj');
  uploads = path.join(tmp, 'data', 'uploads');
  outside = path.join(tmp, 'elsewhere');
  for (const d of [path.join(proj, 'shots'), uploads, outside]) fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(proj, 'shots', 'a.jpg'), PNG_1PX);
  fs.writeFileSync(path.join(home, 'shot.png'), PNG_1PX);
  fs.writeFileSync(path.join(outside, 'secret.png'), 'SECRET');
});

after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/** What the route hands the resolver for a request naming a live session whose cwd is `cwd`. */
const withSession = (requested, cwd, extra = {}) =>
  resolveImage({ requested, roots: [uploads, cwd], cwd, home, ...extra });

test('~/ in a session whose cwd is under home serves the file it names', () => {
  const r = withSession('~/proj/shots/a.jpg', proj);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.file, fs.realpathSync(path.join(proj, 'shots', 'a.jpg')));
  assert.equal(r.contentType, 'image/jpeg');
});

test('~/ with the cwd AT home (the default working folder) serves too', () => {
  const r = withSession('~/shot.png', home);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.file, fs.realpathSync(path.join(home, 'shot.png')));
});

test('a bare ~/ path with no session is judged by the roots, not refused as relative', () => {
  // Home is not a root without a session, so this is the roots' 403 — the same
  // answer the absolute spelling of the same file gets — and not the 400 a
  // relative path with nothing to hang on earns.
  const r = resolveImage({ requested: '~/shot.png', roots: [uploads], cwd: null, home });
  assert.equal(r.status, 403, JSON.stringify(r));
  const abs = resolveImage({ requested: path.join(home, 'shot.png'), roots: [uploads], cwd: null, home });
  assert.equal(abs.status, 403);
  // And when the file IS under a root, the ~/ spelling serves it with no session.
  const inRoot = resolveImage({ requested: '~/shot.png', roots: [home], cwd: null, home });
  assert.equal(inRoot.ok, true, JSON.stringify(inRoot));
});

test('the expansion widens nothing: climbing out of home is still refused by the roots', () => {
  for (const req of ['~/../elsewhere/secret.png', '~/proj/../../elsewhere/secret.png']) {
    const r = withSession(req, proj);
    assert.equal(r.ok, false, req);
    assert.equal(r.status, 403, req);
  }
  // `~//x` is <home>/x to a shell, never /x.
  const doubled = withSession('~//shot.png', home);
  assert.equal(doubled.ok, true, JSON.stringify(doubled));
  assert.equal(doubled.file, fs.realpathSync(path.join(home, 'shot.png')));
});

test('~user/ is somebody else\'s home and is not expanded', () => {
  const r = withSession('~someone/shot.png', home);
  assert.equal(r.ok, false);
  assert.equal(r.status, 404, 'read as relative to the cwd, where there is no such directory');
});

test('a literal directory called ~ under the cwd is never what ~/ means', () => {
  // The old reading served `<cwd>/~/…` whenever such a directory existed — a
  // stray `mkdir "~"` decided what a mention drew.
  const decoy = path.join(proj, '~', 'proj', 'shots');
  fs.mkdirSync(decoy, { recursive: true });
  fs.writeFileSync(path.join(decoy, 'a.jpg'), PNG_1PX);
  try {
    const r = withSession('~/proj/shots/a.jpg', proj);
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.file, fs.realpathSync(path.join(proj, 'shots', 'a.jpg')), 'the home file, not the decoy');
    // With no home to expand to, it is refused outright — never the decoy.
    const homeless = withSession('~/proj/shots/a.jpg', proj, { home: '' });
    assert.equal(homeless.ok, false);
    assert.equal(homeless.status, 400);
  } finally {
    fs.rmSync(path.join(proj, '~'), { recursive: true, force: true });
  }
});

test('with no home option the daemon\'s own home is used', () => {
  const was = process.env.HOME;
  process.env.HOME = home;     // os.homedir() reads $HOME first
  try {
    const r = resolveImage({ requested: '~/proj/shots/a.jpg', roots: [uploads, proj], cwd: proj });
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.file, fs.realpathSync(path.join(proj, 'shots', 'a.jpg')));
  } finally {
    if (was === undefined) delete process.env.HOME; else process.env.HOME = was;
  }
});
