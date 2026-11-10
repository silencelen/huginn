'use strict';
// The model list as Anthropic's Models API states it, for the active login.
//
// Model discovery used to be a string scan of the installed Claude Code binary
// alone (lib/models.discoverModels). That lags: on 2026-11-09 Claude Code
// 2.1.316 had no `claude-fable-5-2` in it, yet ran that model and listed it in
// its own /model picker, because Claude Code's picker is fed by the server. The
// menus here waited for a CLI update that the model did not need.
//
// So the daemon asks the same source: `GET /v1/models` with the login's OAuth
// token, the way it already asks `/api/oauth/usage` and `/api/oauth/account`.
// The result is MERGED with the binary scan, never a replacement: either source
// alone is incomplete on some day, and both feed the same selectModels().
//
// Never on the request path's critical section: the first call waits at most
// `timeoutMs`, every later one is served from the cache while a refresh runs.
// A failed call keeps the last good list and retries sooner; a missing token is
// an empty list, not an error.

/**
 * @param {object} o
 * @param {string} o.url                 the Models API list URL
 * @param {() => string|null} o.readToken the active OAuth access token, or null
 * @param {typeof fetch} [o.fetchImpl]
 * @param {number} [o.ttlMs]             how long a good list is fresh
 * @param {number} [o.retryMs]           how soon a failed refresh is retried
 * @param {number} [o.timeoutMs]         per-call budget
 * @param {(s: string) => void} [o.log]  told once per status CHANGE
 * @param {() => number} [o.now]
 */
function createModelsApi({
  url, readToken, fetchImpl = fetch,
  ttlMs = 30 * 60_000, retryMs = 5 * 60_000, timeoutMs = 5_000,
  log = () => {}, now = Date.now,
}) {
  let ids = [];
  let nextAt = 0;
  let status = 'unasked';
  let inflight = null;

  function setStatus(s) {
    if (s !== status) log(`models api: ${s}`);
    status = s;
  }

  async function refresh() {
    const token = readToken();
    if (!token) {
      ids = [];
      nextAt = now() + retryMs;
      setStatus('no login');
      return;
    }
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), timeoutMs);
    try {
      const resp = await fetchImpl(url, {
        headers: {
          Authorization: `Bearer ${token}`,
          'anthropic-version': '2023-06-01',
          'anthropic-beta': 'oauth-2025-04-20',
        },
        signal: ac.signal,
      });
      if (!resp.ok) {
        nextAt = now() + retryMs;
        setStatus(`HTTP ${resp.status} (keeping ${ids.length} known ids)`);
        return;
      }
      const body = await resp.json();
      const data = body && Array.isArray(body.data) ? body.data : [];
      ids = data.map((m) => m && m.id).filter((id) => typeof id === 'string' && id.startsWith('claude-'));
      nextAt = now() + ttlMs;
      setStatus(`ok (${ids.length} ids)`);
    } catch (e) {
      nextAt = now() + retryMs;
      setStatus(`unreachable: ${e && e.name === 'AbortError' ? 'timeout' : (e && e.message) || e} (keeping ${ids.length} known ids)`);
    } finally {
      clearTimeout(timer);
    }
  }

  /** The latest known ids. Waits for the FIRST refresh only; never throws. */
  async function list() {
    if (now() >= nextAt && !inflight) {
      inflight = refresh().finally(() => { inflight = null; });
    }
    if (status === 'unasked' && inflight) await inflight;
    return ids.slice();
  }

  return { list, status: () => status };
}

module.exports = { createModelsApi };
