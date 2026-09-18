'use strict';
// The containment rules behind GET /v1/files/image, away from HTTP.
//
// This is the security-critical half of that route and the reason lib/files
// exists as a separate module: the route can only be as safe as its resolver,
// and a resolver that can only be exercised through a live daemon on a real
// socket is one nobody re-reads before changing.
//
// THE CASE THIS FILE IS REALLY FOR is `a symlink inside an allowed root cannot
// point out of it`. A prefix test on the requested string — which is what the
// obvious implementation is, and what lib/desktop's resolveArtifact correctly
// does for names the SERVER chose — passes that one. It was made to fail
// against exactly that shape before the realpath pass was written.
//
// SAFETY: no daemon, no socket, no tmux. Real files, but only inside a
// mkdtemp'd scratch dir that is removed again.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  resolveImage, imageContentType, extOf, canonicalRoots, within,
  etagFor, etagMatches, IMAGE_SERVE_MAX_BYTES,
} = require('../lib/files');

// A one-pixel PNG, so the bytes on disk are a real image rather than a lie the
// extension tells. Nothing reads them — the allowlist is on the extension — but
// a fixture that would not open in a viewer invites the next reader to "fix" the
// module by sniffing content, which is a different (and worse) design.
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

let tmp, uploads, render, outside, cwd;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-files-'));
  uploads = path.join(tmp, 'data', 'uploads');
  render = path.join(tmp, 'data', 'scratchpads', 'render');
  outside = path.join(tmp, 'elsewhere');
  cwd = path.join(tmp, 'project');
  for (const d of [uploads, render, outside, cwd, path.join(cwd, 'shots')]) {
    fs.mkdirSync(d, { recursive: true });
  }
  fs.writeFileSync(path.join(uploads, 'shot.png'), PNG_1PX);
  fs.writeFileSync(path.join(render, 'chart.webp'), PNG_1PX);
  fs.writeFileSync(path.join(uploads, 'page.html'), '<script>alert(1)</script>');
  fs.writeFileSync(path.join(uploads, 'logo.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  fs.writeFileSync(path.join(uploads, 'huge.png'), Buffer.alloc(4096));
  fs.writeFileSync(path.join(cwd, 'shots', 'a.jpg'), PNG_1PX);
  // The secret this route must never hand over, and the symlink that tries.
  fs.writeFileSync(path.join(outside, 'secret.png'), 'SECRET');
  fs.symlinkSync(path.join(outside, 'secret.png'), path.join(uploads, 'innocent.png'));
  fs.symlinkSync(outside, path.join(uploads, 'sideways'));
});

after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

const ROOTS = () => [uploads, render];
const resolve = (requested, extra = {}) =>
  resolveImage({ requested, roots: ROOTS(), ...extra });

// ------------------------------------------------------------ the happy paths

test('a file directly inside a root is served, with its type, size and etag', () => {
  const r = resolve(path.join(uploads, 'shot.png'));
  assert.equal(r.ok, true, r.error);
  assert.equal(r.contentType, 'image/png');
  assert.equal(r.size, PNG_1PX.length);
  assert.equal(r.file, fs.realpathSync(path.join(uploads, 'shot.png')));
  assert.match(r.etag, /^"[0-9a-f]+-[0-9a-f]+"$/);
});

test('every root in the list serves, not just the first', () => {
  assert.equal(resolve(path.join(render, 'chart.webp')).contentType, 'image/webp');
});

// --------------------------------------------------------------- cwd-relative

test('a relative path resolves against the session cwd, subdirectories included', () => {
  const r = resolve('shots/a.jpg', { roots: [...ROOTS(), cwd], cwd });
  assert.equal(r.ok, true, r.error);
  assert.equal(r.contentType, 'image/jpeg');
  assert.equal(r.file, fs.realpathSync(path.join(cwd, 'shots', 'a.jpg')));
});

test('a relative path with NO session cwd is a 400, never the daemon\'s own cwd', () => {
  const r = resolve('shots/a.jpg');
  assert.equal(r.ok, false);
  assert.equal(r.status, 400);
  assert.equal(r.error, 'path must be absolute');
});

test('an unknown session contributes no root and no cwd — it cannot widen anything', () => {
  // What the route does when ?session= names a session that is not live: the
  // cwd root is simply not added. The same request that worked above must now
  // fail, and fail as 400/403 rather than serving out of some fallback.
  const r = resolve('shots/a.jpg', { roots: ROOTS(), cwd: null });
  assert.equal(r.ok, false);
  assert.equal(r.status, 400);
  const abs = resolveImage({ requested: path.join(cwd, 'shots', 'a.jpg'), roots: ROOTS(), cwd: null });
  assert.equal(abs.ok, false);
  assert.equal(abs.status, 403, 'and the absolute form of the same file is outside the roots');
});

// -------------------------------------------------------------- the escapes

test('a symlink inside a root pointing OUT of it is refused', () => {
  // ⚠ THE REGRESSION THIS FILE GUARDS. `<uploads>/innocent.png` is a real entry
  // inside a real root; every lexical test in the world passes it. Only
  // realpath sees /elsewhere/secret.png on the other end.
  const r = resolve(path.join(uploads, 'innocent.png'));
  assert.equal(r.ok, false, 'a symlink out of the root must not serve');
  assert.equal(r.status, 403);
  assert.equal(r.error, 'that path is not in an allowed directory');
});

test('a symlinked DIRECTORY inside a root does not drag its contents in either', () => {
  const r = resolve(path.join(uploads, 'sideways', 'secret.png'));
  assert.equal(r.ok, false);
  assert.equal(r.status, 403);
});

test('.. traversal out of a root is refused, however deeply it is buried', () => {
  for (const req of [
    path.join(uploads, '..', '..', 'elsewhere', 'secret.png'),
    `${uploads}/../../elsewhere/secret.png`,
    path.join(uploads, 'a', 'b', '..', '..', '..', '..', 'elsewhere', 'secret.png'),
  ]) {
    const r = resolve(req);
    assert.equal(r.ok, false, req);
    assert.equal(r.status, 403, req);
  }
});

test('a .. inside the root is fine — traversal is about where it LANDS', () => {
  const r = resolve(path.join(uploads, 'sub', '..', 'shot.png'));
  assert.equal(r.ok, true, r.error);
});

test('a path that was never ours is 403, not 404 — this route is not an existence oracle', () => {
  for (const req of ['/etc/passwd', '/etc/shadow.png', '/nonexistent/nowhere/x.png']) {
    const r = resolve(req);
    assert.equal(r.status, 403, req);
  }
});

test('a cwd-relative path that climbs out of the cwd is refused', () => {
  const r = resolve('../elsewhere/secret.png', { roots: [...ROOTS(), cwd], cwd });
  assert.equal(r.ok, false);
  assert.equal(r.status, 403);
});

// ------------------------------------------------------------- the type gate

test('a .html inside a root is 415, not served as anything', () => {
  const r = resolve(path.join(uploads, 'page.html'));
  assert.equal(r.ok, false);
  assert.equal(r.status, 415);
  assert.equal(r.error, 'not an image this daemon will serve');
});

test('an .svg is 415 AND says why — it is a script host, not an unsupported format', () => {
  const r = resolve(path.join(uploads, 'logo.svg'));
  assert.equal(r.ok, false);
  assert.equal(r.status, 415);
  assert.match(r.error, /script/i, 'the refusal must explain itself, or someone re-adds svg');
});

test('the MIME table is the allowlist, and it has no active types in it', () => {
  assert.deepEqual(
    Object.entries({
      'a.png': 'image/png', 'a.jpg': 'image/jpeg', 'a.jpeg': 'image/jpeg',
      'a.gif': 'image/gif', 'a.webp': 'image/webp', 'a.bmp': 'image/bmp',
      'A.PNG': 'image/png', '/deep/path/with.dots.jpg': 'image/jpeg',
    }).filter(([n, want]) => imageContentType(n).type !== want),
    [], 'every allowed extension maps to exactly one image type');

  for (const n of ['a.svg', 'a.svgz', 'a.html', 'a.htm', 'a.pdf', 'a.txt', 'a.tiff',
    'a.ico', 'noext', '.bashrc', 'a.png.html', 'a.jpg.exe']) {
    assert.equal(imageContentType(n).ok, false, n);
  }
  assert.equal(imageContentType('a.png.html').ok, false,
    'the LAST extension decides — a double extension must not smuggle an html through');
});

test('extOf reads the last extension of the basename, and a dotfile has none', () => {
  assert.equal(extOf('/a/b/c.PNG'), 'png');
  assert.equal(extOf('/a/b.d/c'), '');
  assert.equal(extOf('/a/.bashrc'), '');
  assert.equal(extOf('/a/x.tar.gz'), 'gz');
  assert.equal(extOf(''), '');
});

// ------------------------------------------------------------- the size gate

test('a file over the cap is 413, and the cap is inclusive of the limit itself', () => {
  const big = resolve(path.join(uploads, 'huge.png'), { maxBytes: 100 });
  assert.equal(big.ok, false);
  assert.equal(big.status, 413);
  assert.equal(big.error, 'that image is too large to serve');
  assert.equal(resolve(path.join(uploads, 'huge.png'), { maxBytes: 4096 }).ok, true,
    'exactly at the cap still serves');
  assert.equal(resolve(path.join(uploads, 'huge.png'), { maxBytes: 4095 }).ok, false);
});

test('the default cap is the contract\'s twelve megabytes', () => {
  assert.equal(IMAGE_SERVE_MAX_BYTES, 12 * 1024 * 1024);
});

test('type is decided before size — a huge .html is told it is an .html', () => {
  const r = resolve(path.join(uploads, 'page.html'), { maxBytes: 1 });
  assert.equal(r.status, 415, 'the more useful of the two refusals wins');
});

// ------------------------------------------------------------- 404 vs 403

test('a missing file INSIDE a root is 404', () => {
  const r = resolve(path.join(uploads, 'gone.png'));
  assert.equal(r.ok, false);
  assert.equal(r.status, 404);
  assert.equal(r.error, 'no such file');
});

test('a directory is 404, never a 200 that dies as EISDIR mid-stream', () => {
  assert.equal(resolve(uploads).status, 404);
  assert.equal(resolve(path.join(uploads, 'sideways')).status, 403, 'and a symlinked dir is still 403');
});

// ------------------------------------------------------------- malformed

test('an empty or NUL-bearing path is 400', () => {
  assert.equal(resolve('').status, 400);
  assert.equal(resolve(null).status, 400);
  assert.equal(resolve(undefined).status, 400);
  assert.equal(resolveImage({ roots: ROOTS() }).status, 400);
  const nul = resolve(`${path.join(uploads, 'shot.png')}\0.txt`);
  assert.equal(nul.status, 400);
  assert.match(nul.error, /NUL/);
});

// ------------------------------------------------------------- the roots

test('a root that cannot be realpath\'d is DROPPED, not compared as a string', () => {
  const ghost = path.join(tmp, 'never-created');
  assert.deepEqual(canonicalRoots([ghost]), [], 'a root that does not exist widens nothing');
  // And a request under that name must not be served just because the string
  // matches — this is the "fall back to the literal" bug the contract names.
  const r = resolveImage({ requested: path.join(ghost, 'x.png'), roots: [ghost] });
  assert.equal(r.ok, false);
  assert.equal(r.status, 403);
});

test('with no usable roots at all, everything is 403', () => {
  assert.equal(resolveImage({ requested: path.join(uploads, 'shot.png'), roots: [] }).status, 403);
  assert.equal(resolveImage({ requested: path.join(uploads, 'shot.png'), roots: null }).status, 403);
});

test('a relative root is ignored — roots are absolute by definition', () => {
  assert.deepEqual(canonicalRoots(['relative/dir', '', null, 42]), []);
});

test('duplicate roots collapse, including two names for one directory', () => {
  const link = path.join(tmp, 'uploads-alias');
  fs.symlinkSync(uploads, link);
  assert.deepEqual(canonicalRoots([uploads, link, uploads]), [fs.realpathSync(uploads)]);
});

test('within() is a path-segment test, not a string prefix', () => {
  // The classic: /var/lib/huginn-appd/uploads-evil must not be inside
  // /var/lib/huginn-appd/uploads.
  assert.equal(within('/a/b/c.png', '/a/b'), true);
  assert.equal(within('/a/b', '/a/b'), true);
  assert.equal(within('/a/bevil/c.png', '/a/b'), false);
  assert.equal(within('/a/b-evil/c.png', '/a/b'), false);
  assert.equal(within('/a/c.png', '/a/b'), false);
});

test('a sibling directory whose name merely starts with a root\'s is refused', () => {
  const evil = `${uploads}-evil`;
  fs.mkdirSync(evil, { recursive: true });
  fs.writeFileSync(path.join(evil, 'x.png'), PNG_1PX);
  assert.equal(resolve(path.join(evil, 'x.png')).status, 403);
});

// ------------------------------------------------------------- the etag

test('the etag changes with size and with mtime, and nothing else', () => {
  const a = etagFor(10, 1_700_000_000_000);
  assert.equal(etagFor(10, 1_700_000_000_000), a, 'same inputs, same tag');
  assert.notEqual(etagFor(11, 1_700_000_000_000), a);
  assert.notEqual(etagFor(10, 1_700_000_000_001), a);
  assert.match(a, /^".+"$/, 'an etag is a quoted-string on the wire');
});

test('If-None-Match matches the tag, a list containing it, and *', () => {
  const tag = etagFor(10, 1_700_000_000_000);
  assert.equal(etagMatches(tag, tag), true);
  assert.equal(etagMatches(`W/${tag}`, tag), true, 'a proxy may have weakened it');
  assert.equal(etagMatches(`"other", ${tag}`, tag), true);
  assert.equal(etagMatches('*', tag), true);
  assert.equal(etagMatches('"other"', tag), false);
  assert.equal(etagMatches('', tag), false);
  assert.equal(etagMatches(undefined, tag), false);
});
