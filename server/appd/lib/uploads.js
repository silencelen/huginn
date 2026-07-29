'use strict';
// Which uploads are worth accepting, decided from what Claude's Read tool can
// genuinely open. The gate exists so a docx or an apk fails HERE with a plain
// sentence instead of downstream as a chat shrugging at unreadable bytes.
//
// Extracted from the daemon after the first field failure: the original was an
// exact-match mime table, and Android providers do not do exact. A Samsung file
// manager hands over ".csv" as text/comma-separated-values, ".txt" arrives from
// some providers with no type at all, and the same file reports differently
// from Downloads and from Drive. Mime STRINGS are hints; the rules below are
// families plus a filename fallback, in that order of trust.

/** Exact mimes whose extension we know outright. */
const MIME_EXTS = {
  'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif',
  'application/pdf': 'pdf',
  'application/json': 'json',
  'application/xml': 'xml',
  'application/x-yaml': 'yaml', 'application/yaml': 'yaml',
  'application/toml': 'toml',
  'text/markdown': 'md', 'text/csv': 'csv', 'text/html': 'html',
};

/** Extensions Read opens as text (or as themselves), for the filename fallback. */
const NAME_EXTS = new Set([
  'jpg', 'jpeg', 'png', 'webp', 'gif', 'pdf',
  'txt', 'md', 'markdown', 'csv', 'tsv', 'html', 'htm', 'json', 'jsonl',
  'xml', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg', 'log', 'env',
  'sh', 'bash', 'py', 'js', 'ts', 'kt', 'java', 'go', 'rs', 'c', 'h', 'cpp',
  'sql', 'diff', 'patch', 'ipynb',
]);

/**
 * The extension to store an upload under, or null to refuse it.
 *
 * Order of trust: a known mime; then the FILENAME the picker reported (more
 * honest than provider mimes in practice — it is what the user sees); then the
 * text/* family wholesale, stored as .txt, because Read opens any text and a
 * refusal over an exotic subtype ("text/comma-separated-values") punishes the
 * user for their file manager's vocabulary.
 */
function uploadExtFor(mime, name) {
  const m = String(mime || '').split(';')[0].trim().toLowerCase();
  // hasOwn, not a bare lookup: `MIME_EXTS['constructor']` inherits a truthy
  // FUNCTION from Object.prototype, and a Content-Type of `constructor` or
  // `__proto__` therefore became the stored file's "extension" — writing
  // up-<ts>-<hex>.function Object() { [native code] }. No traversal (nothing
  // in the stringified value is a separator) and it needs the bearer token,
  // but a type table must answer only for the types it actually declares.
  if (Object.hasOwn(MIME_EXTS, m)) return MIME_EXTS[m];

  const n = String(name || '');
  const dot = n.lastIndexOf('.');
  const cand = dot > 0 ? n.slice(dot + 1).toLowerCase() : '';
  if (NAME_EXTS.has(cand)) return cand === 'jpeg' ? 'jpg' : cand;

  if (m.startsWith('text/')) return 'txt';
  return null;
}

module.exports = { uploadExtFor, MIME_EXTS, NAME_EXTS };
