'use strict';
// The desktop update channel's pure parts: filename validation, content types,
// manifest reading. The desktop app's release script stocks DATA_DIR/desktop
// by local file moves (never over HTTP); the daemon only ever reads from it,
// so this file has no write path at all.

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

module.exports = { validName, contentTypeFor, readManifest };
