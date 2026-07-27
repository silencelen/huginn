'use strict';
// Multiple Claude logins on one host, switchable when a plan runs out.
//
// Claude Code keeps exactly one active login in ~/.claude/.credentials.json, and
// offers no notion of profiles. Switching therefore means keeping a copy of each
// account's credentials and swapping the active file — which works, and has
// consequences worth being explicit about:
//
//   * A `claude` process reads its credentials at startup and holds the token in
//     memory, so swapping the file does NOT move a RUNNING session to the new
//     account. New runs pick it up; existing ones keep going on the old one until
//     they restart.
//   * A running process also writes REFRESHED tokens back to that file. So the
//     outgoing account is snapshotted immediately before every swap, otherwise a
//     refresh landing at the wrong moment could strand it.
//
// Stored copies are credentials: same secrets, same host, 0600, under the
// daemon's own 0700 data dir.

const fs = require('node:fs');
const path = require('node:path');

/** A stable filename for an email, without inventing an id the user never sees. */
function slugFor(email) {
  const base = String(email || 'unknown').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return base.slice(0, 60) || 'unknown';
}

/** The identifying fields of a credentials file, without the secrets. */
function describe(creds) {
  const o = (creds && creds.claudeAiOauth) || {};
  return {
    subscriptionType: o.subscriptionType ?? null,
    expiresAt: o.expiresAt ?? null,
    scopes: Array.isArray(o.scopes) ? o.scopes.length : 0,
  };
}

/**
 * Two credential blobs belong to the same login when their refresh token
 * matches. Compared rather than stored so the token itself never leaves disk.
 */
function sameAccount(a, b) {
  const ta = a && a.claudeAiOauth && a.claudeAiOauth.refreshToken;
  const tb = b && b.claudeAiOauth && b.claudeAiOauth.refreshToken;
  return !!ta && !!tb && ta === tb;
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

  _profilePath(slug) { return path.join(this.dir, `${slug}.json`); }

  readProfile(slug) {
    try { return JSON.parse(fs.readFileSync(this._profilePath(slug), 'utf8')); } catch { return null; }
  }

  /**
   * Stores the current credentials under `email`. Called before every switch, so
   * the account being left is always recoverable.
   */
  save(email, creds, extra = {}) {
    if (!creds || !creds.claudeAiOauth) return null;
    const slug = slugFor(email);
    const record = {
      email: email ?? null,
      slug,
      savedAt: Math.floor(Date.now() / 1000),
      ...extra,
      credentials: creds,
    };
    const tmp = `${this._profilePath(slug)}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(record), { mode: 0o600 });
    fs.renameSync(tmp, this._profilePath(slug));
    return slug;
  }

  list() {
    let files = [];
    try { files = fs.readdirSync(this.dir).filter((f) => f.endsWith('.json')); } catch { return []; }
    const active = this.readActive();
    const out = [];
    for (const f of files) {
      let rec;
      try { rec = JSON.parse(fs.readFileSync(path.join(this.dir, f), 'utf8')); } catch { continue; }
      out.push({
        slug: rec.slug || f.replace(/\.json$/, ''),
        email: rec.email ?? null,
        orgName: rec.orgName ?? null,
        savedAt: rec.savedAt ?? null,
        isActive: sameAccount(rec.credentials, active),
        ...describe(rec.credentials),
      });
    }
    out.sort((a, b) => (b.isActive ? 1 : 0) - (a.isActive ? 1 : 0) || String(a.email).localeCompare(String(b.email)));
    return out;
  }

  remove(slug) {
    try { fs.unlinkSync(this._profilePath(slug)); return true; } catch { return false; }
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
      this.save(activeEmail || 'previous', current);
    }
    const tmp = `${this.credentialsPath}.huginn-tmp`;
    fs.writeFileSync(tmp, JSON.stringify(rec.credentials), { mode: 0o600 });
    fs.renameSync(tmp, this.credentialsPath);
    return { ok: true, email: rec.email ?? null };
  }
}

module.exports = { AccountStore, slugFor, sameAccount, describe };
