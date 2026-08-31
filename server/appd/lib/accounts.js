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
// The fix was to key a profile by a fingerprint of the credentials themselves, so
// a skew could only produce a wrong label and never a lost account. That held —
// and then produced the opposite failure (2026-08-03). OAuth refresh tokens
// ROTATE: Claude Code trades the stored one for a fresh pair every few hours and
// writes them back. The fingerprint therefore changes several times a day, and
// one login accumulated a NEW profile on every rotation — 13 files for 3 real
// accounts, twelve of them holding tokens that no longer authenticate.
//
// So identity is now a ladder, strongest rung first:
//
//   1. `accountUuid` — the `uuid` the OAuth /account endpoint returns for the
//      access token it was asked with. Derived FROM the credentials, so it cannot
//      skew the way a separate file read can, and it survives rotation.
//   2. the refresh-token fingerprint, when no uuid is known (offline, or an
//      expired access token). Two blobs with the same refresh token are the same
//      login by definition, so this is always safe — merely short-lived.
//
// The uuid is only ever accepted from a caller that asked the TOKEN. It is never
// read out of ~/.claude.json in the save path: that file describes whatever is
// live, which is the exact separate-read skew that lost an account before.
//
// The bias throughout is that a surplus profile is a nuisance and a missing one
// is a lost login, so nothing is dropped unless it is provably the same account
// as something being kept.
//
// Stored copies are credentials: same secrets, same host, 0600, under the
// daemon's own 0700 data dir.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

/** Where consolidate() puts records it folded into another. Never deleted. */
const ARCHIVE_DIR = 'superseded';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A uuid, lowercased, or null. Anything not shaped like one is not identity. */
function normUuid(v) {
  return typeof v === 'string' && UUID_RE.test(v.trim()) ? v.trim().toLowerCase() : null;
}

function refreshTokenOf(creds) {
  return (creds && creds.claudeAiOauth && creds.claudeAiOauth.refreshToken) || null;
}

/**
 * Per-CREDENTIAL id: stable for one token pair, different across logins, and
 * useful precisely because two blobs sharing it are certainly the same account.
 * It is NOT stable for a login over time — the refresh token rotates — which is
 * why it is the fallback rung rather than the key. Truncated to stay readable in
 * a URL; 16 hex chars of SHA-256 is far beyond collision range for a handful of
 * logins.
 */
function fingerprint(creds) {
  const rt = refreshTokenOf(creds);
  if (!rt) return null;
  return crypto.createHash('sha256').update(rt).digest('hex').slice(0, 16);
}

/**
 * The uuid a stored record can be identified by WITHOUT a network call, and only
 * when two independent fields inside it agree.
 *
 * `oauthAccount` is a copy of ~/.claude.json's identity block, taken when these
 * credentials were live. That is the skew-prone source, so it is trusted for one
 * purpose only — the one-time consolidation of profiles whose access tokens have
 * expired and can no longer be asked — and only when the block's own email
 * matches the label the record was filed under. consolidate() adds a second
 * guard on top of this one.
 */
function storedUuid(rec) {
  const direct = normUuid(rec && rec.accountUuid);
  if (direct) return direct;
  const block = rec && rec.oauthAccount;
  const u = normUuid(block && block.accountUuid);
  if (!u) return null;
  const be = typeof block.emailAddress === 'string' ? block.emailAddress.toLowerCase() : null;
  const re = typeof rec.email === 'string' ? rec.email.toLowerCase() : null;
  if (!be || !re || be !== re) return null;      // block and label disagree: not identity
  return u;
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
  /**
   * @param configPath ~/.claude.json — holds the `oauthAccount` block (email,
   *   org, rate-limit tier) that the CLI reports as its identity. It lives apart
   *   from the credentials, so swapping tokens alone leaves the CLI convinced it
   *   is still the previous account: measured, the tokens moved correctly while
   *   `claude auth status` kept naming the old one.
   */
  constructor(dir, credentialsPath, configPath = null) {
    this.dir = dir;
    this.credentialsPath = credentialsPath;
    this.configPath = configPath;
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
  }

  /** The identity block the CLI is currently presenting, if any. */
  readOauthAccount() {
    if (!this.configPath) return null;
    try {
      const cfg = JSON.parse(fs.readFileSync(this.configPath, 'utf8'));
      return cfg && cfg.oauthAccount ? cfg.oauthAccount : null;
    } catch { return null; }
  }

  /**
   * Replaces just the identity block, leaving the rest of that file alone — it
   * holds a great deal of unrelated state. Passing null REMOVES the block, which
   * is the right move when we have no identity to install: the CLI can re-derive
   * it from the token, whereas a stale block would keep misreporting.
   */
  writeOauthAccount(account) {
    if (!this.configPath) return false;
    let cfg;
    try { cfg = JSON.parse(fs.readFileSync(this.configPath, 'utf8')); } catch { return false; }
    if (account) cfg.oauthAccount = account; else delete cfg.oauthAccount;
    const tmp = `${this.configPath}.huginn-tmp`;
    try {
      const mode = (fs.statSync(this.configPath).mode & 0o777) || 0o600;
      fs.writeFileSync(tmp, JSON.stringify(cfg), { mode });
      fs.renameSync(tmp, this.configPath);
      return true;
    } catch {
      try { fs.unlinkSync(tmp); } catch { }
      return false;
    }
  }

  readActive() {
    try { return JSON.parse(fs.readFileSync(this.credentialsPath, 'utf8')); } catch { return null; }
  }

  _path(slug) { return path.join(this.dir, `${slug}.json`); }

  _readFile(file) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
  }

  readProfile(slug) { return this._readFile(this._path(slug)); }

  /** Every stored record, as {slug, rec}. Unreadable files are skipped, not deleted. */
  _records() {
    let files = [];
    try { files = fs.readdirSync(this.dir).filter((f) => f.endsWith('.json')); } catch { return []; }
    const out = [];
    for (const f of files) {
      const rec = this._readFile(path.join(this.dir, f));
      if (rec) out.push({ slug: f.replace(/\.json$/, ''), rec });
    }
    return out;
  }

  _write(slug, record) {
    const tmp = `${this._path(slug)}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(record), { mode: 0o600 });
    fs.renameSync(tmp, this._path(slug));
  }

  /**
   * Moves a record out of the way instead of deleting it. Used wherever a record
   * is folded into another on evidence that could in principle be wrong: an
   * archived login can be put back, a deleted one cannot.
   */
  _archive(slug, rec) {
    const dir = path.join(this.dir, ARCHIVE_DIR);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const dest = path.join(dir, `${slug}-${(rec && rec.savedAt) || Math.floor(Date.now() / 1000)}.json`);
    fs.renameSync(this._path(slug), dest);
    return dest;
  }

  /**
   * Stores credentials for a login, folding them into that login's existing
   * profile rather than starting a new one.
   *
   * `email` is a label; getting it wrong costs a wrong name in a list. The
   * identity that decides WHICH profile is written is `extra.accountUuid`, which
   * the caller must have resolved from these very credentials — see the ladder at
   * the top of this file. With no uuid the fingerprint is used, which is correct
   * but only until the token rotates.
   */
  save(email, creds, extra = {}) {
    const fp = fingerprint(creds);
    if (!fp) return null;
    const all = this._records();

    // Same refresh token = same login, so a stored record matching this blob can
    // lend it the uuid somebody resolved for it earlier. Prefer one that has a
    // uuid over one that does not.
    const byFingerprint = all.filter((e) => fingerprint(e.rec.credentials) === fp);
    const uuid = normUuid(extra.accountUuid)
      ?? byFingerprint.map((e) => normUuid(e.rec.accountUuid)).find(Boolean)
      ?? null;
    const slug = uuid || fp;

    // Every record that is PROVABLY this same login: it carries the same account
    // uuid, or it holds the same refresh token, or it is the file we are writing.
    const mine = all.filter((e) => e.slug === slug
      || (uuid && normUuid(e.rec.accountUuid) === uuid)
      || fingerprint(e.rec.credentials) === fp);

    // Prefer the record already living at the target name; otherwise the oldest,
    // so a login's history survives being re-keyed.
    const base = (mine.find((e) => e.slug === slug)
      ?? mine.slice().sort((a, b) => (a.rec.firstSeen ?? 0) - (b.rec.firstSeen ?? 0))[0])?.rec ?? null;

    // Capture the identity block ONLY when these are the credentials currently in
    // use, since that is the only time the block describes them.
    const live = this.readActive();
    const isLive = live && sameAccount(live, creds);
    const oauthAccount = isLive
      ? (this.readOauthAccount() ?? (base && base.oauthAccount) ?? null)
      : ((base && base.oauthAccount) ?? null);

    const now = Math.floor(Date.now() / 1000);
    const { accountUuid: _u, taggedId, ...rest } = extra;
    const firstSeen = Math.min(
      ...mine.map((e) => e.rec.firstSeen ?? now),
      typeof extra.firstSeen === 'number' ? extra.firstSeen : now,
      now,
    );
    const record = {
      // Callers pass incidental labels here (orgName). They come first so the
      // fields below cannot be overwritten by one.
      ...rest,
      slug,
      // Keep a known-good label rather than replacing it with a blank one.
      email: email ?? (base && base.email) ?? null,
      orgName: rest.orgName ?? (base && base.orgName) ?? null,
      accountUuid: uuid,
      taggedId: (typeof taggedId === 'string' && taggedId) || (base && base.taggedId) || null,
      firstSeen,
      savedAt: now,
      // Headroom last seen for this login, kept across rotations — it is the only
      // thing the auto-switcher can read once the stored access token expires.
      lastPlan: (base && base.lastPlan) ?? null,
      oauthAccount,
      credentials: creds,
    };
    this._write(slug, record);

    // The other files are now stale copies of THIS login: same account, older
    // tokens. Clearing them is what stops one login growing a new profile every
    // time its refresh token rotates.
    for (const e of mine) {
      if (e.slug === slug) continue;
      // Belt and braces on the one mistake that cannot be walked back. A record
      // naming a DIFFERENT account is not this login however it got into the set,
      // and is left alone.
      const other = normUuid(e.rec.accountUuid);
      if (other && uuid && other !== uuid) continue;
      try {
        if (fingerprint(e.rec.credentials) === fp) {
          fs.rmSync(this._path(e.slug), { force: true });   // byte-for-byte the same tokens
        } else {
          this._archive(e.slug, e.rec);                     // an older pair: keep it recoverable
        }
      } catch { /* a later pass gets it */ }
    }
    return slug;
  }

  /**
   * Remembers the utilization last read for an account.
   *
   * A saved account that is not active accrues no usage on this host, so its last
   * reading stays an upper bound on what it is using now — and any limit whose
   * window has since rolled over is simply back to zero. That makes a stored
   * snapshot a sound basis for choosing which login to switch TO, which matters
   * because a stored access token expires within hours and the live figures then
   * become unreadable.
   */
  recordPlan(slug, plan) {
    const rec = this.readProfile(slug);
    if (!rec) return false;
    rec.lastPlan = {
      at: Math.floor(Date.now() / 1000),
      limits: (plan && Array.isArray(plan.limits)) ? plan.limits : [],
    };
    try { this._write(slug, rec); return true; } catch { return false; }
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

    // Decided from a snapshot taken before anything is written, because saving
    // one record now clears the other files holding those same credentials —
    // so a loop that read as it went would find them already gone and lose the
    // choice of which label to keep.
    const byPrint = new Map();
    let migrated = 0;
    let duplicates = 0;
    for (const f of files) {
      const file = path.join(this.dir, f);
      const rec = this._readFile(file);
      const print = rec && fingerprint(rec.credentials);
      if (!print) { fs.rmSync(file, { force: true }); continue; }
      if (!byPrint.has(print)) byPrint.set(print, []);
      byPrint.get(print).push({ name: f, rec });
    }

    for (const [print, group] of byPrint) {
      duplicates += group.length - 1;
      // A record already filed under the name its own identity gives it needs
      // nothing doing. Checked FIRST, so this is a no-op on a settled store
      // rather than a rewrite of every profile on every boot.
      const settledName = `${normUuid(group[0].rec.accountUuid) || print}.json`;
      if (group.length === 1 && group[0].name === settledName) continue;

      // Same login under several names: keep the newest record's label.
      const keep = group.slice().sort((a, b) => (b.rec.savedAt ?? 0) - (a.rec.savedAt ?? 0))[0];
      // Clean up relative to where save() ACTUALLY put it. It is not necessarily
      // `<print>.json` any more — a record carrying an account uuid is filed
      // under that instead, and assuming otherwise deletes the file just written.
      const written = this.save(keep.rec.email, keep.rec.credentials, pickExtra(keep.rec));
      if (!written) continue;
      for (const g of group) {
        if (g.name === `${written}.json`) continue;
        fs.rmSync(path.join(this.dir, g.name), { force: true });
        migrated++;
      }
    }
    return { migrated, duplicates };
  }

  /**
   * Folds the profiles that rotation left behind into one per real login.
   *
   * Grouping is by account uuid — a resolved one where we have it, otherwise the
   * one sitting in the record's own identity block (see storedUuid, which already
   * refuses a block that disagrees with its label). That local block is the only
   * evidence available for a profile whose access token expired days ago, so it
   * is used, hedged twice:
   *
   *   * a group is left ENTIRELY alone unless every member carries the same
   *     email, so a skewed record can never be pulled into another account, and
   *   * surplus records are archived rather than deleted, so even a wrong
   *     grouping costs nothing that cannot be handed back.
   *
   * The surviving record keeps the freshest credentials in the group and the
   * earliest firstSeen, and is filed under the uuid, which does not rotate.
   * Records that cannot be identified are not touched at all.
   *
   * Idempotent: a group already consisting of one correctly named record is
   * skipped, so a second run writes nothing. Each group is independent, so a
   * failure in one leaves both it and every other group as they were.
   *
   * @returns {{groups: number, merged: number, archived: number, failed: number}}
   */
  consolidate() {
    const out = { groups: 0, merged: 0, archived: 0, failed: 0 };
    const groups = new Map();
    for (const e of this._records()) {
      const uuid = storedUuid(e.rec);
      if (!uuid) continue;                       // unidentifiable: leave it exactly as it is
      if (!groups.has(uuid)) groups.set(uuid, []);
      groups.get(uuid).push(e);
    }

    for (const [uuid, members] of groups) {
      const emails = new Set(members.map((m) => (m.rec.email || '').toLowerCase()).filter(Boolean));
      if (emails.size > 1) continue;             // members disagree about who they are: hands off
      const settled = members.length === 1
        && members[0].slug === uuid
        && normUuid(members[0].rec.accountUuid) === uuid;
      if (settled) continue;
      out.groups++;

      try {
        const ranked = members.slice().sort((a, b) => credentialAge(b.rec) - credentialAge(a.rec)
          || (b.rec.savedAt ?? 0) - (a.rec.savedAt ?? 0));
        const freshest = ranked[0].rec;
        const first = (k) => ranked.map((m) => m.rec[k]).find((v) => v != null) ?? null;
        const record = {
          slug: uuid,
          email: first('email'),
          orgName: first('orgName'),
          accountUuid: uuid,
          taggedId: first('taggedId'),
          firstSeen: Math.min(...members.map((m) => m.rec.firstSeen ?? m.rec.savedAt ?? 0).filter(Boolean)),
          savedAt: Math.max(...members.map((m) => m.rec.savedAt ?? 0)),
          lastPlan: ranked.map((m) => m.rec.lastPlan).find((v) => v != null) ?? null,
          oauthAccount: freshest.oauthAccount ?? first('oauthAccount'),
          credentials: freshest.credentials,
        };
        // The survivor is written FIRST. Until it lands, every original is still
        // in place; after it lands, the freshest credentials exist under the new
        // name whatever happens to the archiving below.
        this._write(uuid, record);
        out.merged++;
        for (const m of members) {
          if (m.slug === uuid) continue;
          try { this._archive(m.slug, m.rec); out.archived++; } catch { /* a rerun retries it */ }
        }
      } catch { out.failed++; }
    }
    return out;
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
        accountUuid: normUuid(rec.accountUuid),
        taggedId: rec.taggedId ?? null,
        savedAt: rec.savedAt ?? null,
        firstSeen: rec.firstSeen ?? null,
        planSeenAt: (rec.lastPlan && rec.lastPlan.at) ?? null,
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
      // even if the email we were handed belongs to somebody else. Saved while it
      // is still live, so its identity block is captured with it.
      this.save(activeEmail || null, current);
    }
    const tmp = `${this.credentialsPath}.huginn-tmp`;
    fs.writeFileSync(tmp, JSON.stringify(rec.credentials), { mode: 0o600 });
    fs.renameSync(tmp, this.credentialsPath);
    // And move the identity with the tokens, or the CLI keeps naming the old
    // account. No stored block means removing the stale one and letting the CLI
    // re-derive: wrong is worse than absent.
    const wroteIdentity = this.writeOauthAccount(rec.oauthAccount ?? null);
    return {
      ok: true, email: rec.email ?? null, slug,
      // Only claim the identity followed the tokens if the write actually landed.
      // writeOauthAccount returns false on a read/write failure; reporting
      // identityRestored:true then tells the caller the CLI will name the new
      // account when it will in fact keep naming the old one.
      identityRestored: !!rec.oauthAccount && wroteIdentity,
    };
  }
}

function pickExtra(rec) {
  const extra = {};
  if (rec.orgName) extra.orgName = rec.orgName;
  if (rec.firstSeen) extra.firstSeen = rec.firstSeen;
  if (rec.accountUuid) extra.accountUuid = rec.accountUuid;
  if (rec.taggedId) extra.taggedId = rec.taggedId;
  return extra;
}

/** How recent a record's credentials are, for picking the freshest of a group. */
function credentialAge(rec) {
  const o = (rec && rec.credentials && rec.credentials.claudeAiOauth) || {};
  return typeof o.expiresAt === 'number' ? o.expiresAt : 0;
}

module.exports = { AccountStore, fingerprint, sameAccount, describe, normUuid, storedUuid };
