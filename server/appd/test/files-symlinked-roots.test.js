'use strict';
// lib/files.js — a ROOT that is reached through a symlink, asked for by the name
// the daemon itself hands out.
//
// The usual way to move DATA_DIR to a bigger disk is to move it and link the
// old path back (/var/lib/huginn-appd -> /mnt/data/huginn-appd). The daemon
// keeps building paths from the configured name — POST /v1/uploads answers
// `<DATA_DIR>/uploads/up-….png` — and a relative request hangs off the session
// cwd as recorded. The lexical pass compared those spellings with the roots'
// REALPATHS only, so every file in such a root was 403 by the very name the
// daemon gave it, and served only by a name nobody is ever told.
//
// files.test.js roots everything in os.tmpdir(), which is not a symlink on
// Linux, so it never saw this. Every case here goes through a link on purpose.
//
// SAFETY: no daemon, no socket. Real files and links, only inside a mkdtemp'd
// scratch dir that is removed again.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { resolveImage } = require('../lib/files');

const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');
const UPLOAD = 'up-1760000000000-abc123.png';

let tmp, realData, linkData, uploads, render, scratch, realProj, linkProj, outside;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-files-linked-'));
  // The bigger disk, and the configured name that points at it.
  realData = path.join(tmp, 'mnt-data', 'huginn-appd');
  linkData = path.join(tmp, 'var-lib-huginn-appd');
  fs.mkdirSync(path.join(realData, 'uploads'), { recursive: true });
  fs.mkdirSync(path.join(realData, 'scratchpads', 'render'), { recursive: true });
  fs.symlinkSync(realData, linkData);
  // The route's roots, built from the configured name exactly as huginn-appd.js does.
  uploads = path.join(linkData, 'uploads');
  render = path.join(linkData, 'scratchpads', 'render');
  scratch = path.join(tmp, 'claude-0');
  fs.mkdirSync(scratch);
  fs.writeFileSync(path.join(uploads, UPLOAD), PNG_1PX);

  // A session cwd recorded through a link.
  realProj = path.join(tmp, 'real-proj');
  linkProj = path.join(tmp, 'proj-link');
  fs.mkdirSync(path.join(realProj, 'shots'), { recursive: true });
  fs.writeFileSync(path.join(realProj, 'shots', 'a.png'), PNG_1PX);
  fs.symlinkSync(realProj, linkProj);

  // What must stay out of reach whichever name is used.
  outside = path.join(tmp, 'elsewhere');
  fs.mkdirSync(outside);
  fs.writeFileSync(path.join(outside, 'secret.png'), 'SECRET');
  fs.symlinkSync(path.join(outside, 'secret.png'), path.join(uploads, 'innocent.png'));
});

after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

const ROOTS = () => [uploads, render, scratch];
const resolve = (requested, extra = {}) => resolveImage({ requested, roots: ROOTS(), cwd: null, ...extra });

test('an upload is served by the path the upload route returned, through the link', () => {
  const r = resolve(path.join(uploads, UPLOAD));
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.file, path.join(realData, 'uploads', UPLOAD), 'served from where it really is');
});

test('the realpath spelling still serves, as it always did', () => {
  const r = resolve(path.join(realData, 'uploads', UPLOAD));
  assert.equal(r.ok, true, JSON.stringify(r));
});

test('a missing file under the link name is 404 — the name is ours, the file is not there', () => {
  const r = resolve(path.join(uploads, 'gone.png'));
  assert.equal(r.status, 404, JSON.stringify(r));
});

test('a session cwd recorded through a link serves relative and absolute requests', () => {
  const roots = [...ROOTS(), linkProj];
  const rel = resolveImage({ requested: 'shots/a.png', roots, cwd: linkProj });
  assert.equal(rel.ok, true, JSON.stringify(rel));
  assert.equal(rel.file, path.join(realProj, 'shots', 'a.png'));
  const abs = resolveImage({ requested: path.join(linkProj, 'shots', 'a.png'), roots, cwd: linkProj });
  assert.equal(abs.ok, true, JSON.stringify(abs));
});

test('the link name widens nothing: the escapes are refused through it too', () => {
  // A symlink inside the root pointing out of it — pass 2's job, still done.
  assert.equal(resolve(path.join(uploads, 'innocent.png')).status, 403);
  // `..` out of the root, spelled through the link.
  assert.equal(resolve(`${uploads}/../../elsewhere/secret.png`).status, 403);
  assert.equal(resolve(path.join(linkData, 'tls', 'key.pem')).status, 403, 'a sibling of the root, not the root');
  // A directory whose name merely starts with the root's.
  fs.mkdirSync(`${realData}/uploads-evil`, { recursive: true });
  fs.writeFileSync(path.join(`${realData}/uploads-evil`, 'x.png'), PNG_1PX);
  assert.equal(resolve(path.join(`${uploads}-evil`, 'x.png')).status, 403);
  assert.equal(resolve('/etc/passwd').status, 403);
});

test('a root whose link dangles has no names at all', () => {
  const dangling = path.join(tmp, 'dangling');
  fs.symlinkSync(path.join(tmp, 'never-created'), dangling);
  const r = resolveImage({ requested: path.join(dangling, 'x.png'), roots: [dangling] });
  assert.equal(r.status, 403);
});
