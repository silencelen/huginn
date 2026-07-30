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

module.exports = { uploadExtFor, safeExt, isReadable, MIME_EXTS, READABLE_EXTS };
