'use strict';
// Serving a HOST file the assistant mentioned by path — the resolver behind
// GET /v1/files/image.
//
// This is a different problem from /v1/uploads, and the difference is the whole
// reason this file exists. An upload's name is chosen by the SERVER
// (`up-<ts>-<rand>.<ext>`), so lib/desktop's resolveArtifact can defend itself
// with a filename regex and no realpath — it says so in its own comment, and it
// is right, because no separator ever reaches it. Here the CLIENT supplies a
// whole path. Every defence resolveArtifact does not need, this one does.
//
// THE BUG THIS MODULE EXISTS TO NOT HAVE: a prefix test on the requested string.
//
//     if (requested.startsWith(root + '/')) serve(requested)   // WRONG
//
// passes for `<uploads>/shot.png` when `shot.png` is a symlink to
// `/etc/shadow`, and passes for `<uploads>/../../etc/shadow` on any platform
// whose path library does not normalise for you. Both are real: Claude writes
// screenshots into scratch dirs that are themselves symlinks on some hosts, so
// "there are no symlinks under there" was never true. The containment test is
// therefore run TWICE: lexically on the resolved-and-normalised request (which
// kills `..` and answers "not one of ours" without touching the disk), and then
// again on `fs.realpathSync` of that path against `fs.realpathSync` of the root
// (which kills the symlink). A root that cannot itself be realpath'd is DROPPED,
// never compared as a literal string — a root that does not exist must widen
// nothing.
//
// Order of refusals is deliberate and is asserted in files.test.js:
//
//   400 malformed         — no path; relative with no session cwd to hang it on;
//                           `~/…` with no home to expand it to
//   403 outside the roots — decided BEFORE the disk is consulted, so this route
//                           cannot be used to probe for the existence of files
//                           it would never serve (`/etc/passwd` is 403, not 404)
//   404 missing           — only ever said about a path already known to be ours
//   415 not an image      — extension allowlist; .svg is named separately below
//   413 too large
//
// Everything here is synchronous and takes its `fs` as an option, so the route
// tests can drive it without a daemon and the pure tests can drive it without a
// filesystem.

const nodeFs = require('node:fs');
const nodeOs = require('node:os');
const path = require('node:path');

/**
 * The only image types this daemon will hand back, and the exact Content-Type
 * each goes out as. An allowlist, not a denylist: an extension that is not a key
 * here is refused, so a new format is a deliberate addition rather than
 * something that leaks in.
 *
 * NO SVG. An SVG is a script host — it can carry <script>, external fetches and
 * foreignObject, and a browser rendering one from this origin runs it with this
 * origin's cookies. `nosniff` does not help, because image/svg+xml IS the
 * correct type for the bytes; the type is the problem. It is refused by name in
 * imageContentType so the 415 can say why rather than reading as "unsupported".
 *
 * Wider than lib/uploads' IMAGE_EXTS by one — bmp. Uploads never stores a bmp
 * (the phone transcodes to JPEG before it posts), but a host file the assistant
 * points at is whatever the host has, and a bmp is inert bytes.
 */
const IMAGE_SERVE_TYPES = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
};

/** Refused by NAME rather than by omission, so the 415 can explain itself. */
const SCRIPTABLE_EXTS = new Set(['svg', 'svgz']);

/**
 * The size past which an image is refused instead of streamed. The contract's
 * number. It is a serving cap, not a storage cap: uploads may be 128 MB
 * (a router backup), but a client is drawing this inline in a transcript, and
 * anything above this is a screenshot of a mistake.
 */
const IMAGE_SERVE_MAX_BYTES = 12 * 1024 * 1024;

/** The extension, lowercased, with no dot. '' when there is none. */
function extOf(p) {
  const base = path.basename(String(p || ''));
  const dot = base.lastIndexOf('.');
  if (dot <= 0) return '';                 // '.bashrc' is a dotfile, not a .bashrc file
  return base.slice(dot + 1).toLowerCase();
}

/**
 * The Content-Type to serve `p` as, or null when this daemon will not serve it.
 * Returns the reason alongside so the caller's 415 can be specific.
 */
function imageContentType(p) {
  const ext = extOf(p);
  if (SCRIPTABLE_EXTS.has(ext)) {
    return { ok: false, why: `.${ext} is not served: SVG can carry script, and image/svg+xml runs it` };
  }
  const type = IMAGE_SERVE_TYPES[ext];
  if (!type) {
    return { ok: false, why: 'not an image this daemon will serve' };
  }
  return { ok: true, type };
}

/**
 * The canonical form of each root, with the ones that do not exist dropped.
 *
 * Dropping rather than falling back to the literal string is the point: a root
 * that fails to realpath is a root whose real location is unknown, and comparing
 * against its unresolved name would happily contain paths that resolve somewhere
 * else entirely.
 */
function canonicalRoots(roots, fs = nodeFs) {
  return liveRoots(roots, fs).canon;
}

/**
 * Every root that exists, under BOTH its names: `canon`, its realpath, and
 * `named`, the spelling it was configured by (normalised, not resolved).
 *
 * ⚠ THE LEXICAL PASS NEEDS THE CONFIGURED NAME. A request is spelled the way
 * the daemon hands paths out — an upload's `path` is built from DATA_DIR as
 * configured, a relative request hangs off the cwd as recorded — and when that
 * spelling runs through a symlink (DATA_DIR moved to a bigger disk and linked
 * back) it never starts with the realpath. Testing it against `canon` alone
 * refused every file in the root by the very name the daemon gave it. Pass 2
 * still compares realpath to realpath, so the second name widens nothing.
 * Same drop rule as [canonicalRoots]: a root that does not exist has no names.
 */
function liveRoots(roots, fs = nodeFs) {
  const canon = [];
  const named = [];
  for (const r of roots || []) {
    if (!r || typeof r !== 'string' || !path.isAbsolute(r)) continue;
    let real;
    try { real = fs.realpathSync(r); } catch { continue; }   // no such root widens nothing
    if (!canon.includes(real)) canon.push(real);
    const asNamed = path.resolve(r);
    if (!named.includes(asNamed)) named.push(asNamed);
  }
  return { canon, named };
}

/** Is `file` inside `root`, both already canonical? */
function within(file, root) {
  return file === root || file.startsWith(root.endsWith(path.sep) ? root : root + path.sep);
}

/** This daemon's home — the one every session it starts shares — or '' when it has none. */
function homeDir() {
  try {
    const h = nodeOs.homedir();
    return path.isAbsolute(h) ? h : '';
  } catch {
    return '';
  }
}

/**
 * Resolve a requested image path against the allowed roots.
 *
 * @param {object} o
 * @param {string} o.requested   the raw ?path= value
 * @param {string[]} o.roots     absolute allowed roots (un-canonicalised; see canonicalRoots)
 * @param {string|null} o.cwd    the session cwd a RELATIVE request hangs off, or null
 * @param {string} [o.home]      what a leading `~` stands for; this daemon's home when absent
 * @param {number} o.maxBytes
 * @param {object} o.fs
 * @returns {{ok:true, file:string, contentType:string, size:number, mtimeMs:number, etag:string}
 *          |{ok:false, status:number, error:string}}
 */
function resolveImage(o = {}) {
  const fs = o.fs || nodeFs;
  const maxBytes = Number.isFinite(o.maxBytes) ? o.maxBytes : IMAGE_SERVE_MAX_BYTES;
  const requested = String(o.requested == null ? '' : o.requested);
  const cwd = o.cwd && path.isAbsolute(o.cwd) ? o.cwd : null;

  if (!requested) return { ok: false, status: 400, error: 'path is required' };
  // A NUL truncates at every syscall boundary below the runtime; node throws on
  // it, but throwing out of a resolver reads as a 500. Named here instead.
  if (requested.includes('\0')) return { ok: false, status: 400, error: 'path must not contain NUL' };

  // ⚠ `~/…` IS AN ABSOLUTE PATH IN THE ASSISTANT'S OWN WORDS, NOT A RELATIVE ONE.
  // It is how Claude writes a file in its home, and the clients extract it on
  // purpose (ImageMentions anchors on `~/`). Read as relative it became
  // `<cwd>/~/…`, a directory nobody has, so every such mention was a 404. It is
  // expanded to the home here and then judged by the roots exactly like any
  // absolute path — the expansion widens nothing. `~user/…` is somebody else's
  // home and is left alone; with no home to expand to it is refused, never
  // resolved against the cwd.
  const tilde = requested === '~' || requested.startsWith('~/');
  const home = o.home === undefined ? homeDir()
    : (typeof o.home === 'string' && path.isAbsolute(o.home) ? o.home : '');

  // Relative is allowed ONLY against a session cwd — which means only when the
  // caller named a live session. With no cwd there is nothing to resolve
  // against and a relative path would silently mean "the daemon's cwd", which is
  // not a root and never should be.
  let abs;
  if (tilde) {
    if (!home) return { ok: false, status: 400, error: 'path must be absolute' };
    // join, not resolve: `~//etc/x.png` is `<home>/etc/x.png` to a shell, and
    // resolve would make it /etc/x.png.
    abs = path.resolve(path.join(home, requested.slice(1)));
  } else if (path.isAbsolute(requested)) {
    abs = path.resolve(requested);
  } else if (cwd) {
    abs = path.resolve(cwd, requested);
  } else {
    return { ok: false, status: 400, error: 'path must be absolute' };
  }

  const { canon, named } = liveRoots(o.roots, fs);
  if (!canon.length) return { ok: false, status: 403, error: 'that path is not in an allowed directory' };

  // PASS 1 — lexical. path.resolve has already collapsed `..`, so a traversal
  // out of a root fails here, on a string, before the disk is touched. This is
  // also what keeps the route from being an existence oracle: a path that was
  // never ours is 403 whether or not it exists. A root counts under either of
  // its names — see [liveRoots].
  if (![...named, ...canon].some((r) => within(abs, r))) {
    return { ok: false, status: 403, error: 'that path is not in an allowed directory' };
  }

  // PASS 2 — canonical. The lexical pass cannot see a symlink; this is the one
  // that catches `<uploads>/shot.png -> /etc/shadow`.
  let real;
  try { real = fs.realpathSync(abs); } catch { return { ok: false, status: 404, error: 'no such file' }; }
  if (!canon.some((r) => within(real, r))) {
    return { ok: false, status: 403, error: 'that path is not in an allowed directory' };
  }

  let st;
  try { st = fs.statSync(real); } catch { return { ok: false, status: 404, error: 'no such file' }; }
  // A directory would otherwise be streamed as an EISDIR mid-response, after the
  // 200 and the Content-Length have already gone out (the bug lib/desktop names).
  if (!st.isFile()) return { ok: false, status: 404, error: 'no such file' };

  // Type before size: a 20 MB .html is refused for being an .html, which is the
  // more useful thing to be told.
  const ct = imageContentType(real);
  if (!ct.ok) return { ok: false, status: 415, error: ct.why };

  if (st.size > maxBytes) {
    return { ok: false, status: 413, error: 'that image is too large to serve' };
  }

  return {
    ok: true,
    file: real,
    contentType: ct.type,
    size: st.size,
    mtimeMs: st.mtimeMs,
    etag: etagFor(st.size, st.mtimeMs),
  };
}

/**
 * A validator, not a fingerprint. Size + mtime is what every static file server
 * uses and it is enough here: these files are written once by a screenshot tool
 * and read many times by a transcript that re-renders on every poll. Hashing
 * megabytes on each conditional GET to catch a same-size same-mtime rewrite
 * would cost more than the bytes it saves.
 */
function etagFor(size, mtimeMs) {
  return `"${Number(size).toString(16)}-${Math.floor(Number(mtimeMs) || 0).toString(16)}"`;
}

/**
 * Does an If-None-Match header match this etag?
 *
 * Handles the list form (`a, b`) and `*`, and tolerates the weak prefix a proxy
 * may have added on the way out — weak comparison is the correct one for a
 * conditional GET (RFC 9110 §13.1.2).
 */
function etagMatches(ifNoneMatch, etag) {
  const raw = String(ifNoneMatch || '').trim();
  if (!raw) return false;
  if (raw === '*') return true;
  const weaken = (s) => s.trim().replace(/^W\//, '');
  return raw.split(',').some((c) => weaken(c) === weaken(etag));
}

module.exports = {
  IMAGE_SERVE_TYPES, SCRIPTABLE_EXTS, IMAGE_SERVE_MAX_BYTES,
  extOf, imageContentType, canonicalRoots, within, resolveImage, etagFor, etagMatches,
};
