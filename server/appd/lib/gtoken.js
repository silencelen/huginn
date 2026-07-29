'use strict';
// Access tokens from a Google service-account key, without the Google SDK.
//
// This daemon has no dependencies and adding googleapis for one token exchange
// would be the largest thing in the tree by a wide margin. The exchange itself is
// small and stable: sign a JWT with the key, post it, get an hour's token back.
//
// Cached until shortly before expiry, because FCM sends happen in bursts and each
// exchange is a network round trip. The margin exists so a token is never handed out
// with seconds left on it — a request that starts valid and arrives expired fails in
// a way that looks like a permissions problem and is not.

const crypto = require('node:crypto');
const fs = require('node:fs');

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const EXPIRY_MARGIN_MS = 60_000;

function base64url(buf) {
  return Buffer.from(buf).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Builds the signed assertion Google exchanges for an access token.
 * @param key parsed service-account JSON
 * @param scope space-separated OAuth scopes
 */
function buildAssertion(key, scope, nowSeconds) {
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = {
    iss: key.client_email,
    scope,
    aud: key.token_uri || TOKEN_URL,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
  const signingInput =
    `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const signature = crypto.createSign('RSA-SHA256')
    .update(signingInput)
    .sign(key.private_key);
  return `${signingInput}.${base64url(signature)}`;
}

/**
 * A service-account credential that hands out access tokens.
 *
 * Reads the key ONCE at construction and keeps it in memory: it is a private key,
 * and re-reading it on every send would put it through the filesystem far more often
 * than necessary for no benefit.
 */
class ServiceAccount {
  constructor(keyPath, scope = 'https://www.googleapis.com/auth/cloud-platform') {
    this.keyPath = keyPath;
    this.scope = scope;
    this.key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
    if (!this.key.client_email || !this.key.private_key) {
      throw new Error(`${keyPath} is not a service-account key`);
    }
    this.projectId = this.key.project_id || null;
    this._token = null;
    this._expiresAt = 0;
  }

  get email() { return this.key.client_email; }

  async accessToken(fetchImpl = fetch, now = Date.now) {
    const t = now();
    if (this._token && t < this._expiresAt - EXPIRY_MARGIN_MS) return this._token;

    const assertion = buildAssertion(this.key, this.scope, Math.floor(t / 1000));
    // Bounded for the same reason as the FCM send: the alert tick is
    // single-flight, and a hung token exchange stalls every alert behind it.
    const res = await fetchImpl(this.key.token_uri || TOKEN_URL, {
      signal: AbortSignal.timeout(15_000),
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion,
      }).toString(),
    });
    const body = await res.text();
    if (!res.ok) throw new Error(`token exchange failed (${res.status}): ${body.slice(0, 300)}`);
    const parsed = JSON.parse(body);
    if (!parsed.access_token) throw new Error('token exchange returned no access_token');
    this._token = parsed.access_token;
    this._expiresAt = t + (Number(parsed.expires_in) || 3600) * 1000;
    return this._token;
  }
}

module.exports = { ServiceAccount, buildAssertion, base64url, EXPIRY_MARGIN_MS };
