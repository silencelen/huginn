'use strict';
// What an upload is stored as, and what can be done with it once it is there.
//
// The first version refused anything Claude's Read tool could not open, which
// conflated two different questions. "May this be stored on huginn" and "can Read
// display it" are not the same: a UniFi backup, a tarball, a sqlite database and
// a pcap are all useful to an `act` chat that can run `file`, `unzip`, `sqlite3`
// or `tcpdump` on them — and the owner hit exactly that wall sending a router
// backup, refused as `application/octet-stream` + `.unifi`.
//
// So nothing is refused for its TYPE any more. The type decides how the message
// tells Claude to open it instead, which is what the refusal was really
// protecting against: a binary handed to Read comes back as mojibake and the
// answer is a shrug about an unreadable file.
//
// Mime strings remain hints only. Android providers do not do exact-match: a
// Samsung file manager hands ".csv" over as text/comma-separated-values, ".txt"
// sometimes arrives typeless, and the same file reports differently from
// Downloads than from Drive.

/** Exact mimes whose extension we know outright. */
const MIME_EXTS = {
  'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif',
  'application/pdf': 'pdf',
  'application/json': 'json',
  'application/xml': 'xml',
  'application/x-yaml': 'yaml', 'application/yaml': 'yaml',
  'application/toml': 'toml',
  'application/zip': 'zip',
  'application/gzip': 'gz',
  'application/x-tar': 'tar',
  'application/x-sqlite3': 'sqlite',
  'text/markdown': 'md', 'text/csv': 'csv', 'text/html': 'html',
};

/** Extensions Read opens directly: images, PDFs, and text in its many suits. */
const READABLE_EXTS = new Set([
  'jpg', 'jpeg', 'png', 'webp', 'gif', 'pdf',
  'txt', 'md', 'markdown', 'csv', 'tsv', 'html', 'htm', 'json', 'jsonl',
  'xml', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg', 'log', 'env',
  'sh', 'bash', 'py', 'js', 'ts', 'kt', 'java', 'go', 'rs', 'c', 'h', 'cpp',
  'sql', 'diff', 'patch', 'ipynb',
]);

/**
 * A filename's extension, reduced to something safe to build a path from.
 *
 * The server names the stored file, so this is never trusted as a path component
 * — but an extension is still attacker-influenced text and has no business
 * carrying a dot, a separator, or two hundred characters.
 */
function safeExt(name) {
  const n = String(name || '');
  const dot = n.lastIndexOf('.');
  if (dot <= 0 || dot === n.length - 1) return null;
  const raw = n.slice(dot + 1).toLowerCase();
  if (!/^[a-z0-9]{1,12}$/.test(raw)) return null;
  return raw === 'jpeg' ? 'jpg' : raw;
}

/**
 * The extension to store an upload under. NEVER null: an upload with nothing to
 * go on is stored as `.bin`, which is honest and still inspectable by a shell.
 *
 * Order of trust: a known mime; then the FILENAME the picker reported (in
 * practice more honest than provider mimes, and it is what the user sees); then
 * the text/* family wholesale, since Read opens any text.
 */
function uploadExtFor(mime, name) {
  const m = String(mime || '').split(';')[0].trim().toLowerCase();
  // hasOwn, not a bare lookup: `MIME_EXTS['constructor']` inherits a truthy
  // FUNCTION from Object.prototype, so a Content-Type of `constructor` became
  // the stored file's "extension". A type table must answer only for the types
  // it actually declares.
  if (Object.hasOwn(MIME_EXTS, m)) return MIME_EXTS[m];
  const fromName = safeExt(name);
  if (fromName) return fromName;
  if (m.startsWith('text/')) return 'txt';
  return 'bin';
}

/** Whether Read can display this directly, or whether it needs a shell. */
function isReadable(ext) {
  return READABLE_EXTS.has(String(ext || '').toLowerCase());
}

const IMAGE_EXTS = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif']);

/**
 * Whether a stored upload filename is an image. Used to (a) exempt images from
 * the retention prune — they back chat-history thumbnails and are kept until
 * manually deleted — and (b) decide the pruneable set. Reads the extension off
 * the stored name, which the server assigned, so it is trustworthy.
 */
function isImageUpload(name) {
  const n = String(name || '').toLowerCase();
  const dot = n.lastIndexOf('.');
  if (dot < 0) return false;
  return IMAGE_EXTS.has(n.slice(dot + 1));
}

/**
 * The Content-Type to SERVE a stored upload as. Deliberately conservative:
 * a handful of known media types, and `application/octet-stream` for everything
 * else. User-supplied bytes must never go out as an active type (text/html,
 * image/svg+xml) — the GET route also sets X-Content-Type-Options: nosniff — so
 * even a `.html` upload downloads rather than renders.
 */
const SERVE_TYPES = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
  webp: 'image/webp', gif: 'image/gif', pdf: 'application/pdf',
};
function contentTypeForUpload(name) {
  const n = String(name || '').toLowerCase();
  const dot = n.lastIndexOf('.');
  const ext = dot >= 0 ? n.slice(dot + 1) : '';
  return SERVE_TYPES[ext] || 'application/octet-stream';
}

/**
 * At most one directory sweep per minute, however many files arrive.
 *
 * The sweep itself is unchanged and still correct: non-image uploads older than
 * the retention window go, IMAGES NEVER DO (see isImageUpload). What changed is
 * how often it is allowed to run. It hangs off POST /v1/uploads — a dir that
 * only grows when the feature is used only needs sweeping then — and that was
 * fine while an attach was one file. Multi-attach makes it ten POSTs inside a
 * second, so the same directory was being read ten times to delete the same
 * nothing, each readdir+stat-per-entry blocking the event loop of a daemon that
 * is also streaming those very uploads to disk.
 *
 * A minute, not a smarter trigger: retention is measured in DAYS, so the sweep
 * has no deadline at all and the only thing the interval must beat is a burst.
 * The first call after start always sweeps (lastPrune starts at 0).
 *
 * The clock and the fs are injected so the throttle can be asserted with a
 * readdirSync spy instead of a sleep — a test that proves a rate limit by
 * waiting for it is a test that takes a minute to run.
 *
 * @returns {() => boolean} true when this call actually swept.
 */
const PRUNE_MIN_INTERVAL_MS = 60_000;
function createUploadPruner(opts = {}) {
  const dir = opts.dir;
  const fsImpl = opts.fs || require('node:fs');
  const pathImpl = opts.path || require('node:path');
  const now = opts.now || Date.now;
  const minIntervalMs = Number.isFinite(opts.minIntervalMs) ? opts.minIntervalMs : PRUNE_MIN_INTERVAL_MS;
  const defaultKeepMs = Number.isFinite(opts.keepMs) ? opts.keepMs : 7 * 24 * 60 * 60 * 1000;

  let lastPrune = 0;

  return function prune(maxAgeMs = defaultKeepMs) {
    const t = now();
    // `>=` on the interval, so a test that advances the clock by exactly the
    // interval gets a sweep rather than an off-by-one mystery.
    if (lastPrune && t - lastPrune < minIntervalMs) return false;
    lastPrune = t;
    let names;
    try { names = fsImpl.readdirSync(dir); } catch { return false; }
    const cutoff = t - maxAgeMs;
    for (const n of names) {
      if (isImageUpload(n)) continue;                 // kept until manually deleted
      const f = pathImpl.join(dir, n);
      try { if (fsImpl.statSync(f).mtimeMs < cutoff) fsImpl.unlinkSync(f); } catch { /* raced; fine */ }
    }
    return true;
  };
}

module.exports = {
  uploadExtFor, safeExt, isReadable, isImageUpload, contentTypeForUpload,
  createUploadPruner, PRUNE_MIN_INTERVAL_MS,
  MIME_EXTS, READABLE_EXTS, IMAGE_EXTS,
};
