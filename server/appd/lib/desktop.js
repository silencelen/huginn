'use strict';
// The desktop update channel's pure parts: filename validation, content types,
// manifest reading, and the path resolution the route serves from. The desktop
// release script stocks DATA_DIR/desktop-kt by local file moves (never over
// HTTP); the daemon only ever reads from it, so this file has no write path at
// all.
//
// ONE CHANNEL:
//
//   DATA_DIR/desktop-kt  -> /v1/desktop-kt   the Compose Multiplatform client
//
// There were two. DATA_DIR/desktop -> /v1/desktop served the Electron client,
// and the separation between them was load-bearing rather than tidy: they were
// different applications sharing a name, and a Compose build published into the
// Electron directory would have handed a running program an "update" that
// replaced it with something else. The Electron client was deleted from the
// repo on 2026-08-27 by owner directive — strictly Compose — and its channel
// went with it. A DATA_DIR/desktop left on a deployed host is stale bytes
// nothing reads.
//
// Everything below still takes the directory as an argument: there is no
// module-level "the desktop dir", which is also what lets the uploads route
// borrow resolveArtifact without inheriting a channel.

const fs = require('fs');
const path = require('path');

// Valid by construction — no separator can appear, so no traversal exists.
// Artifact names are short ASCII (Huginn-Desktop-Setup-0.1.0.exe,
// huginn-desktop-kt_0.1.0-1_amd64.deb, manifest.json).
const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{1,80}$/;

const CONTENT_TYPES = {
  '.yml': 'text/yaml; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.exe': 'application/octet-stream',
  '.AppImage': 'application/octet-stream',
  '.deb': 'application/octet-stream',
  '.zip': 'application/zip',
  '.blockmap': 'application/octet-stream',
};

function validName(name) {
  return NAME_RE.test(name);
}

function contentTypeFor(name) {
  return CONTENT_TYPES[path.extname(name)] || 'application/octet-stream';
}

/** The house-readable manifest (version, per-platform files) — null when the
 *  channel has never been stocked. Read by the release script's version gate,
 *  the About dialog, and the 0.5.x-transition clients that still poll here
 *  rather than at the GitHub release. */
function readManifest(dir) {
  try {
    return JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
  } catch {
    return null;
  }
}

/**
 * Everything the route needs to decide about `name` in `dir`, with no `res` in
 * sight so it can be asserted directly.
 *
 * Returns `{ ok: true, file, contentType, size }`, or
 * `{ ok: false, status, error }` with the status the caller should send.
 *
 * Shared with the uploads route on purpose. The alternative — a copy per
 * directory served — means the next hardening lands in one of them, and the one
 * it misses is whichever the author was not looking at.
 */
function resolveArtifact(dir, name) {
  // Safe by construction: no separator passes validName, so the joined path
  // cannot leave `dir`. This is the whole traversal defence; there is no
  // realpath check behind it because there is nothing for one to catch.
  if (!validName(name)) return { ok: false, status: 400, error: 'bad name' };
  const file = path.join(dir, name);
  let st;
  try { st = fs.statSync(file); } catch { return { ok: false, status: 404, error: 'no such file' }; }
  // A directory would otherwise be streamed as an EISDIR mid-response, after
  // the 200 and the Content-Length have already gone out.
  if (!st.isFile()) return { ok: false, status: 404, error: 'no such file' };
  return { ok: true, file, contentType: contentTypeFor(name), size: st.size };
}

module.exports = { validName, contentTypeFor, readManifest, resolveArtifact };
