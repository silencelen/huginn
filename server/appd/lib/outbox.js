'use strict';
// Files Claude hands to the person: the `send_file` tool's half on the host.
//
// THE RULES, each the answer to a question the feature raised:
//
//  * ONLY CLAUDE'S EXPLICIT CALL PICKS A FILE. Nothing here is reachable from a
//    client: a client only ever names an id this module MINTED, so no request can
//    walk the host's disk. This host is root-equivalent; that is the whole point.
//  * A COPY, NOT A LINK. The file is copied into the daemon's outbox under a name
//    the daemon chooses (`out-<ms>-<hex>.<ext>`), so what the person downloads is
//    what Claude sent at that moment, and a later edit, move or delete of the
//    original changes nothing.
//  * SECRETS STAY. A prompt-injected Claude could otherwise send a credential to
//    the owner's own devices. It cannot send it anywhere ELSE, but a key on a
//    phone is still a key out of its box: the denylist below refuses the obvious
//    ones by path and by name, after symlinks are resolved.
//  * A CEILING. 50 MB, regular files only.
//
// Pure where it can be (the checks take the facts as arguments); `deposit` does
// the I/O and is what the MCP server and the tests call.

const nodeFs = require('node:fs');
const nodePath = require('node:path');
const crypto = require('node:crypto');
const { safeExt } = require('./uploads');

const MAX_BYTES = 50 * 1024 * 1024;

/** A name this module minted: the only thing GET /v1/outbox/<id> will serve. */
const ID_RE = /^out-\d{10,16}-[0-9a-f]{6}\.[a-z0-9]{1,12}$/;

/** Shown inline: a picture the client may decode. Everything else is a download. */
const IMAGE_TYPES = { jpg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif' };

/**
 * Directories whose contents are never sent, resolved paths compared by prefix.
 * The daemon's own state (tokens, push tokens, accounts) is here too, EXCEPT the
 * uploads the person sent - sending one of those back is a fair thing to do.
 */
const DENY_DIRS = [
  '/etc/huginn-appd', '/etc/ssh', '/etc/ssl/private', '/etc/shadow', '/etc/gshadow', '/etc/sudoers',
  '/proc', '/sys', '/dev', '/run/secrets', '/boot',
];
/** Path segments that mark a credential store wherever it lives. */
const DENY_SEGMENTS = ['.ssh', '.gnupg', '.aws', '.azure', '.kube', '.docker', '.password-store', '.huginn-app', '.devstore', '.ledger'];
/** Files under a home dir that are credentials by name. */
const DENY_HOME_FILES = ['.claude.json', '.netrc', '.pgpass', '.git-credentials', '.npmrc', '.pypirc'];
/** Basenames refused anywhere. */
const DENY_NAME_RES = [
  /^\.env(\..*)?$/i, /\.env$/i, /^\.secrets?(\..*)?$/i, /secrets?\.env$/i,
  /^id_(rsa|dsa|ecdsa|ed25519)(\.pub)?$/i, /\.(pem|key|p12|pfx|jks|keystore|kdbx|ovpn)$/i,
  /^keystore\.properties$/i, /^\.credentials\.json$/i, /^credentials(\.json)?$/i, /^token$/i,
  /^shadow$/i, /^known_hosts$/i, /^authorized_keys$/i,
];

/**
 * Why [real] may not be sent, or null when it may. [real] is the path AFTER
 * realpath, so a symlink named `notes.txt` that points at a key is judged as the key.
 *
 * @param dataDir the daemon's DATA_DIR: refused but for its uploads/ and outbox/.
 */
function refusal(real, { dataDir = '/var/lib/huginn-appd' } = {}) {
  const p = nodePath.posix.normalize(String(real || ''));
  if (!p.startsWith('/')) return 'the path must be absolute';
  const under = (d) => p === d || p.startsWith(d + '/');
  for (const d of DENY_DIRS) if (under(d)) return `files under ${d} are never sent`;
  if (under(dataDir) && !under(nodePath.posix.join(dataDir, 'uploads')) && !under(nodePath.posix.join(dataDir, 'outbox'))) {
    return "huginn's own state is never sent";
  }
  const parts = p.split('/').filter(Boolean);
  for (const seg of parts.slice(0, -1)) {
    if (DENY_SEGMENTS.includes(seg)) return `files under a ${seg} directory are never sent`;
  }
  const base = parts[parts.length - 1] || '';
  if (DENY_HOME_FILES.includes(base)) return `${base} holds credentials and is never sent`;
  for (const re of DENY_NAME_RES) if (re.test(base)) return `${base} looks like a credential and is never sent`;
  return null;
}

/** The stored name for a file whose original name is [original]. */
function mintId(original, now = Date.now()) {
  const ext = safeExt(original) || 'bin';
  return `out-${now}-${crypto.randomBytes(3).toString('hex')}.${ext}`;
}

function extOf(id) {
  const dot = String(id).lastIndexOf('.');
  return dot >= 0 ? String(id).slice(dot + 1) : '';
}

/** The type it is served as: an image type for an image, otherwise octet-stream (a download, never rendered). */
function contentTypeFor(id) {
  return IMAGE_TYPES[extOf(id)] || 'application/octet-stream';
}

function isImage(id) {
  return Boolean(IMAGE_TYPES[extOf(id)]);
}

/** A download filename that cannot inject a header or a path. */
function safeDownloadName(name, id) {
  const base = nodePath.posix.basename(String(name || '')).replace(/[^\w.\- ()+,]/g, '_').slice(0, 120);
  return base && base !== '.' && base !== '..' ? base : id;
}

/**
 * Copies [src] into [outboxDir] and returns what the client is told:
 * `{ id, name, mime, size, image, caption }`. Throws an Error whose message is
 * the refusal, said to Claude as the tool's error.
 */
function deposit(src, { outboxDir, dataDir, caption = null, fs = nodeFs, now = Date.now } = {}) {
  if (typeof src !== 'string' || !src.trim()) throw new Error('give the absolute path of the file to send');
  if (!nodePath.isAbsolute(src)) throw new Error('the path must be absolute');
  let real;
  try { real = fs.realpathSync(src); } catch { throw new Error(`no such file: ${src}`); }
  const why = refusal(real, { dataDir });
  if (why) throw new Error(why);
  const st = fs.statSync(real);
  if (!st.isFile()) throw new Error('only a regular file can be sent (not a directory or a device)');
  if (st.size > MAX_BYTES) throw new Error(`the file is ${Math.ceil(st.size / 1048576)} MB; the limit is ${MAX_BYTES / 1048576} MB`);
  fs.mkdirSync(outboxDir, { recursive: true, mode: 0o700 });
  const id = mintId(nodePath.basename(real), now());
  const dest = nodePath.join(outboxDir, id);
  fs.copyFileSync(real, dest, fs.constants.COPYFILE_EXCL);
  fs.chmodSync(dest, 0o600);
  const meta = {
    id,
    name: safeDownloadName(nodePath.basename(src), id),
    mime: contentTypeFor(id),
    size: st.size,
    image: isImage(id),
    caption: typeof caption === 'string' && caption.trim() ? caption.trim().slice(0, 500) : null,
  };
  fs.writeFileSync(`${dest}.json`, JSON.stringify(meta), { mode: 0o600 });
  return meta;
}

/** The sidecar written beside [id], or null (pruned, or never ours). */
function metaOf(outboxDir, id, fs = nodeFs) {
  if (!ID_RE.test(String(id))) return null;
  try { return JSON.parse(fs.readFileSync(nodePath.join(outboxDir, `${id}.json`), 'utf8')); } catch { return null; }
}

/**
 * The `file` a transcript tool event carries, from `send_file`'s result text, or
 * null when the result is not a successful send. Only an id this module could
 * have minted passes, so a forged result cannot point a client anywhere else.
 */
function fileFromResult(text) {
  let o;
  try { o = JSON.parse(String(text || '')); } catch { return null; }
  if (!o || o.sent !== true || !ID_RE.test(String(o.id))) return null;
  return {
    id: o.id,
    name: safeDownloadName(o.name, o.id),
    mime: contentTypeFor(o.id),
    size: Number.isFinite(o.size) ? o.size : null,
    image: isImage(o.id),
    caption: typeof o.caption === 'string' ? o.caption.slice(0, 500) : null,
  };
}

/** The tool's full name as Claude Code reports it: server `huginn`, tool `send_file`. */
const SEND_FILE_TOOL = 'mcp__huginn__send_file';

module.exports = {
  deposit, refusal, mintId, contentTypeFor, isImage, safeDownloadName, metaOf, fileFromResult,
  ID_RE, MAX_BYTES, SEND_FILE_TOOL,
};
