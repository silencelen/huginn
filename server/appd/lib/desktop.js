'use strict';
// The desktop update channels' pure parts: filename validation, content types,
// manifest reading, and the one path resolution both channels share. The desktop
// release scripts stock DATA_DIR/<channel> by local file moves (never over
// HTTP); the daemon only ever reads from it, so this file has no write path at
// all.
//
// TWO CHANNELS, and the separation is load-bearing rather than tidy:
//
//   DATA_DIR/desktop     -> /v1/desktop      the Electron client (0.4.0, in use)
//   DATA_DIR/desktop-kt  -> /v1/desktop-kt   the Compose Multiplatform client
//
// They are different applications that happen to be called the same thing. The
// owner's installed Electron client polls /v1/desktop and will install whatever
// it finds there; publishing a Compose build into that directory would hand a
// running program an "update" that replaces it with something else. Until the
// Compose client reaches parity and the two are deliberately merged, nothing
// writes across the line — see CUTOVER in scripts/release-desktop.sh.
//
// Everything below takes the directory as an argument for exactly that reason:
// there is no module-level "the desktop dir", so no code path can drift into
// serving one channel from the other's files.

const fs = require('fs');
const path = require('path');

// Valid by construction — no separator can appear, so no traversal exists.
// electron-builder artifact names are short ASCII (Huginn-Setup-0.1.0.exe,
// huginn-desktop-0.1.0.AppImage, latest.yml).
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
 *  channel has never been stocked. electron-updater never reads this; the
 *  About dialog and release-script version gate do. */
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
 * Shared by BOTH channels on purpose. The alternative — a copy per channel —
 * means the next hardening lands in one of them, and the one it misses is
 * whichever the author was not looking at.
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
