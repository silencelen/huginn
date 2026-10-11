'use strict';
// API lane — one-shot, tool-free model calls paid from an Anthropic API key
// instead of the Claude subscription's 5-hour / weekly windows.
//
// Why it exists: Max plans include a monthly Claude Platform API credit (since
// 2026-10-07). That credit pays for the Messages API but NOT for Claude Code, so
// the caged `claude -p --tools ''` calls this daemon makes (suggested replies)
// spend subscription headroom on work that needs no harness at all. Moved here,
// they cost a fraction of a cent and leave the windows to the sessions.
//
// It is OFF unless `HUGINN_API_KEY_FILE` names a readable file holding the key —
// either the bare key, or (with `HUGINN_API_KEY_VAR=NAME`) a shell env-file whose
// `NAME=value` line holds it, so an existing secrets file is used in place rather
// than the key being copied somewhere new.
// The key is read from a file, never from the environment, and never as
// ANTHROPIC_API_KEY: a `claude` child inheriting that name abandons the
// subscription login for the key — and Claude Code is exactly what the credit
// does not cover.
//
// Every failure — no key, the local monthly cap reached, a network error, a
// non-2xx, an unparseable body — returns `null`, and the caller falls back to
// the `claude -p` path it always had. The local cap (`HUGINN_API_MONTHLY_USD`,
// default $5) is a ceiling this daemon enforces from the usage the API reports,
// in its own ledger, so a forgotten card or auto-reload on the Console side
// cannot turn a suggestion feature into a bill.

const fs = require('fs');
const path = require('path');

const API_BASE = process.env.HUGINN_API_BASE || 'https://api.anthropic.com';
const DEFAULT_MODEL = 'claude-haiku-5-5';

/** USD per million tokens, list price (platform.claude.com pricing, 2026-10-10). */
const PRICES = {
  'claude-haiku-5-5': { in: 0.10, out: 0.50 },
  'claude-sonnet-5-5': { in: 2, out: 10 },
  'claude-opus-5-5': { in: 4, out: 20 },
};

function monthOf(now) {
  const d = new Date(now);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** Cost of one response's `usage`, at list price. Unknown model → priced as Opus, the safe side. */
function costOf(model, usage) {
  const p = PRICES[model] || PRICES['claude-opus-5-5'];
  const inTok = (usage && (usage.input_tokens || 0) + (usage.cache_creation_input_tokens || 0)
    + (usage.cache_read_input_tokens || 0)) || 0;
  const outTok = (usage && usage.output_tokens) || 0;
  return (inTok * p.in + outTok * p.out) / 1e6;
}

function readLedger(file, now) {
  const month = monthOf(now);
  try {
    const l = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (l && l.month === month && Number.isFinite(l.usd)) return l;
  } catch { /* first call, or a torn file: start the month over */ }
  return { month, usd: 0, calls: 0 };
}

function writeLedger(file, ledger) {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(ledger));
  fs.renameSync(tmp, file);
}

function readKey(keyFile, varName) {
  if (!keyFile) return null;
  let raw;
  try { raw = fs.readFileSync(keyFile, 'utf8'); } catch { return null; }
  if (!varName) return raw.trim() || null;
  for (const line of raw.split('\n')) {
    const m = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
    if (!m || m[1] !== varName) continue;
    const v = m[2].trim().replace(/^(['"])(.*)\1$/, '$2');
    return v || null;
  }
  return null;
}

/**
 * @param opts.dataDir   where the spend ledger lives
 * @param opts.env       process.env (injectable for tests)
 * @param opts.now       () => ms
 * @param opts.fetchImpl fetch (injectable for tests)
 */
function createApiLane({ dataDir, env = process.env, now = Date.now, fetchImpl = fetch } = {}) {
  const keyFile = env.HUGINN_API_KEY_FILE || '';
  const keyVar = env.HUGINN_API_KEY_VAR || '';
  const capUsd = Number(env.HUGINN_API_MONTHLY_USD || 5);
  const ledgerFile = path.join(dataDir, 'api-lane-spend.json');

  function status() {
    const l = readLedger(ledgerFile, now());
    return { enabled: !!readKey(keyFile, keyVar), capUsd, month: l.month, spentUsd: l.usd, calls: l.calls };
  }

  /**
   * One user-turn completion. Resolves to the text, or null on ANY failure
   * (the caller's fallback is the signal, so this never throws).
   */
  async function complete(prompt, { model = DEFAULT_MODEL, maxTokens = 300, timeoutMs = 20_000 } = {}) {
    const key = readKey(keyFile, keyVar);
    if (!key) return null;
    const before = readLedger(ledgerFile, now());
    if (before.usd >= capUsd) return null;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const resp = await fetchImpl(`${API_BASE}/v1/messages`, {
        method: 'POST',
        signal: ctl.signal,
        headers: {
          'x-api-key': key,
          'anthropic-version': '2023-06-01',
          'content-type': 'application/json',
        },
        // ⚠ THINKING OFF. Haiku 5.5 thinks by default (measured 2026-10-11): on a
        // 200-token budget it spent 175 thinking and returned a truncated line,
        // sometimes no text at all — a charged call that then fell back to the CLI.
        // A one-shot rewrite needs no reasoning. A model that refuses a disabled-
        // thinking request (Opus 5.5 does) gets a 400 here, i.e. null, i.e. the
        // CLI path — never a wrong answer.
        body: JSON.stringify({
          model, max_tokens: maxTokens, thinking: { type: 'disabled' },
          messages: [{ role: 'user', content: prompt }],
        }),
      });
      if (!resp.ok) return null;
      const body = await resp.json();
      // Charge what the API says was used, even if the text turns out unusable.
      const l = readLedger(ledgerFile, now());
      l.usd += costOf(body.model || model, body.usage);
      l.calls += 1;
      try { writeLedger(ledgerFile, l); } catch { /* a ledger we cannot write must not fail the call */ }
      const text = (body.content || []).filter((b) => b && b.type === 'text').map((b) => b.text).join('');
      return text || null;
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  return { complete, status };
}

module.exports = { createApiLane, costOf, monthOf, PRICES, DEFAULT_MODEL };
