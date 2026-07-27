'use strict';
// Multiple Claude logins on one host, switchable when a plan runs out.
//
// Claude Code keeps exactly one active login in ~/.claude/.credentials.json and
// offers no notion of profiles. Switching therefore means keeping a copy of each
// account's credentials and swapping the active file — which works, and has
// consequences worth being explicit about:
//
//   * A `claude` process reads its credentials at startup and holds the token in
//     memory, so swapping the file does NOT move a RUNNING session to the new
//     account. New runs pick it up; existing ones keep going until they restart.
//   * A running process also writes REFRESHED tokens back to that file, so the
//     outgoing account is snapshotted immediately before every swap.
//
// IDENTITY (learned the hard way, 2026-07-27). Profiles used to be keyed by the
// email that `claude auth status` reported, paired with credentials read from the
// file. Those are two separate reads of two separate things, and any skew between
// them writes one account's secrets under another account's name — which does not
// merely mislabel, it OVERWRITES that other account's profile and loses a login.
// It happened in practice within minutes of the feature shipping.
//
// So a profile is keyed by a fingerprint of the credentials themselves. The email
// is a label. A skew can now only produce a wrong label, never a lost account,
// and two different logins can never collide.
//
// Stored copies are credentials: same secrets, same host, 0600, under the
// daemon's own 0700 data dir.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

function refreshTokenOf(creds) {
  return (creds && creds.claudeAiOauth && creds.claudeAiOauth.refreshToken) || null;
}

/**
 * Stable per-login id, derived from the credentials so it cannot be confused
 * with another account's. Truncated to keep it readable in a URL; 16 hex chars
 * of SHA-256 is far beyond collision range for a handful of logins.
 */
function fingerprint(creds) {
  const rt = refreshTokenOf(creds);
  if (!rt) return null;
  return crypto.createHash('sha256').update(rt).digest('hex').slice(0, 16);
}

/** Two credential blobs are the same login when their refresh token matches. */
function sameAccount(a, b) {
  const ta = refreshTokenOf(a);
  const tb = refreshTokenOf(b);
  return !!ta && !!tb && ta === tb;
}

/** Identifying fields, minus the secrets. */
function describe(creds) {
  const o = (creds && creds.claudeAiOauth) || {};
  return {
    subscriptionType: o.subscriptionType ?? null,
    expiresAt: o.expiresAt ?? null,
    scopes: Array.isArray(o.scopes) ? o.scopes.length : 0,
  };
}

class AccountStore {
  constructor(dir, credentialsPath) {
    this.dir = dir;
    this.credentialsPath = credentialsPath;
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
  }

  readActive() {
    try { return JSON.parse(fs.readFileSync(this.credentialsPath, 'utf8')); } catch { return null; }
  }

  _path(slug) { return path.join(this.dir, `${slug}.json`); }

  _readFile(file) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
  }

  readProfile(slug) { return this._readFile(this._path(slug)); }

  /**
   * Stores credentials under their own fingerprint. `email` is recorded as a
   * label only; getting it wrong costs a wrong name in a list, not an account.
   */
  save(email, creds, extra = {}) {
    const slug = fingerprint(creds);
    if (!slug) return null;
    const existing = this.readProfile(slug);
    const record = {
      slug,
      // Keep a known-good label rather than replacing it with a blank one.
      email: email ?? (existing && existing.email) ?? null,
      firstSeen: (existing && existing.firstSeen) ?? Math.floor(Date.now() / 1000),
      savedAt: Math.floor(Date.now() / 1000),
      ...extra,
      credentials: creds,
    };
    const tmp = `${this._path(slug)}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(record), { mode: 0o600 });
    fs.renameSync(tmp, this._path(slug));
    return slug;
  }

  /**
   * Rewrites any profile still keyed by the old email-slug scheme, and removes
   * duplicates left behind by it. Two files holding the SAME credentials are one
   * login that was written under two names; the surplus copies are deleted and
   * counted, because each one represents an account whose real credentials were
   * overwritten and which therefore has to be signed in again.
   *
   * @returns {{migrated: number, duplicates: number}}
   */
  migrate() {
    let files = [];
    try { files = fs.readdirSync(this.dir).filter((f) => f.endsWith('.json')); } catch { return { migrated: 0, duplicates: 0 }; }
    const byPrint = new Map();
    let migrated = 0;
    let duplicates = 0;

    for (const f of files) {
      const file = path.join(this.dir, f);
      const rec = this._readFile(file);
      const print = rec && fingerprint(rec.credentials);
      if (!print) { fs.rmSync(file, { force: true }); continue; }

      const seen = byPrint.get(print);
      if (seen) {
        // Same login under two names: keep the newer record's label.
        duplicates++;
        const keepNewer = (rec.savedAt ?? 0) > (seen.savedAt ?? 0);
        if (keepNewer) {
          this.save(rec.email, rec.credentials, pickExtra(rec));
          byPrint.set(print, rec);
        }
        if (path.basename(file) !== `${print}.json`) fs.rmSync(file, { force: true });
        continue;
      }
      byPrint.set(print, rec);
      if (f !== `${print}.json`) {
        this.save(rec.email, rec.credentials, pickExtra(rec));
        fs.rmSync(file, { force: true });
        migrated++;
      }
    }
    return { migrated, duplicates };
  }

  list() {
    let files = [];
    try { files = fs.readdirSync(this.dir).filter((f) => f.endsWith('.json')); } catch { return []; }
    const active = this.readActive();
    const out = [];
    for (const f of files) {
      const rec = this._readFile(path.join(this.dir, f));
      if (!rec) continue;
      out.push({
        slug: rec.slug || f.replace(/\.json$/, ''),
        email: rec.email ?? null,
        orgName: rec.orgName ?? null,
        savedAt: rec.savedAt ?? null,
        firstSeen: rec.firstSeen ?? null,
        isActive: sameAccount(rec.credentials, active),
        ...describe(rec.credentials),
      });
    }
    out.sort((a, b) =>
      (b.isActive ? 1 : 0) - (a.isActive ? 1 : 0) ||
      String(a.email ?? a.slug).localeCompare(String(b.email ?? b.slug)));
    return out;
  }

  remove(slug) {
    try { fs.unlinkSync(this._path(slug)); return true; } catch { return false; }
  }

  /**
   * Makes a stored account the active login. Snapshots the outgoing one first,
   * then writes atomically so a reader never sees a half-written file.
   */
  activate(slug, activeEmail) {
    const rec = this.readProfile(slug);
    if (!rec || !rec.credentials) return { ok: false, error: 'no such saved account' };

    const current = this.readActive();
    if (current && !sameAccount(current, rec.credentials)) {
      // Keyed by its own fingerprint, so this cannot overwrite the incoming one
      // even if the email we were handed belongs to somebody else.
      this.save(activeEmail || null, current);
    }
    const tmp = `${this.credentialsPath}.huginn-tmp`;
    fs.writeFileSync(tmp, JSON.stringify(rec.credentials), { mode: 0o600 });
    fs.renameSync(tmp, this.credentialsPath);
    return { ok: true, email: rec.email ?? null, slug };
  }
}

function pickExtra(rec) {
  const extra = {};
  if (rec.orgName) extra.orgName = rec.orgName;
  if (rec.firstSeen) extra.firstSeen = rec.firstSeen;
  return extra;
}

module.exports = { AccountStore, fingerprint, sameAccount, describe };
