'use strict';

// Requiring this makes the process's global fetch() retry a NETWORK-LEVEL failure
// — a thrown "TypeError: fetch failed" — a few times before giving up. It does NOT
// retry an HTTP response: any status (including 4xx/5xx) returns unchanged for the
// caller to assert on. Only a connection that never completed is retried.
//
// Why: every route/*.test.js here spawns its own huginn-appd child and talks to it
// over HTTP. On a loaded host a just-spawned daemon can be momentarily slow to
// accept, and a single bare fetch() then throws "fetch failed" and fails the whole
// test — which fails the DEPLOY GATE (deploy.sh runs this suite and refuses on any
// red). That is host load, not a daemon defect. The retry rides out the blip; the
// bound (~2s total) means a genuinely dead daemon still fails, just a moment later.
//
// node --test runs each test FILE in its own process, so this patches only the
// current file's global fetch; the guard makes a double-require a no-op.
if (!globalThis.__retryFetchInstalled) {
  globalThis.__retryFetchInstalled = true;
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (...args) => {
    let lastErr;
    for (let i = 0; i < 6; i++) {
      try {
        return await realFetch(...args);
      } catch (e) {
        lastErr = e;
        await new Promise((r) => setTimeout(r, 100 * (i + 1)));
      }
    }
    throw lastErr;
  };
}
