'use strict';
// What a session's tokens WOULD have cost, at Anthropic's published list prices.
//
// The rates below are USD per MILLION tokens — Anthropic first-party list
// prices, cached 2026-08-27. They are a static table on purpose: nothing here
// asks the network what anything costs, so a transcript prices the same way on a
// box with no route out, and the number somebody reads today is one they can
// reproduce tomorrow. The cost of that choice is that the table goes stale
// silently; the date above is the only thing that says when it was true.
//
// ⚠ THIS ACCOUNT DOES NOT PAY THESE PRICES. Everything huginn runs is on a
// subscription, so no invoice anywhere carries these dollars. The figure answers
// exactly one question — "what would this session have billed at API list
// rates?" — and the session overview presents it as an ESTIMATE covered by
// subscription billing. That labelling is why it is allowed on the screen at
// all: a money number without it is a claim about a bill, and there is no bill.
//
// A family is matched on a SUBSTRING of the model id, because the ids that
// actually reach a transcript carry date suffixes no table can enumerate
// ("claude-haiku-4-5-20251001"). A model that matches nothing is never priced at
// a neighbour's rate and never dropped — see `unpricedTokens`.

/**
 * Cache multipliers, applied to a family's INPUT rate: a read is a tenth of
 * fresh input, a 5-minute write a quarter more, a 1-hour write double.
 *
 * Derived rather than typed out per family, so correcting a base rate can never
 * leave a stale cache rate sitting beside it — which on a cache-heavy session is
 * the error that would matter, since reads outnumber everything else by an order
 * of magnitude.
 */
const CACHE_READ = 0.1;
const CACHE_WRITE_5M = 1.25;
const CACHE_WRITE_1H = 2;

/**
 * USD per million tokens, in MATCH ORDER. The first family whose marker appears
 * in the (lowercased) id wins. No two markers can appear in one real id, but the
 * ordering makes that a rule here rather than a coincidence out there.
 */
const FAMILIES = [
  { family: 'fable', markers: ['fable', 'mythos'], input: 10.00, output: 50.00 },
  { family: 'opus', markers: ['opus'], input: 5.00, output: 25.00 },
  { family: 'sonnet', markers: ['sonnet'], input: 3.00, output: 15.00 },
  { family: 'haiku', markers: ['haiku'], input: 1.00, output: 5.00 },
];

/** family -> the whole rate card, cache rates included. */
const RATES = Object.fromEntries(FAMILIES.map((f) => [f.family, {
  input: f.input,
  output: f.output,
  cacheRead: f.input * CACHE_READ,
  cacheWrite5m: f.input * CACHE_WRITE_5M,
  cacheWrite1h: f.input * CACHE_WRITE_1H,
}]));

/**
 * The rate family a model id belongs to, or null.
 *
 * Null is a real answer and the only honest one for an id this table has never
 * seen: a new model priced at a guessed neighbour's rate produces a plausible
 * total that is quietly wrong, which is worse than a total that says out loud
 * how many tokens it could not price.
 */
function matchFamily(modelId) {
  const id = String(modelId == null ? '' : modelId).toLowerCase();
  if (!id) return null;
  for (const f of FAMILIES) {
    for (const marker of f.markers) if (id.includes(marker)) return f.family;
  }
  return null;
}

/**
 * Money, to the millionth of a dollar.
 *
 * Rounded HERE and only here, and the total is the sum of the ALREADY-ROUNDED
 * per-model figures — so a client that renders both a breakdown and a total
 * never shows parts that fail to add up to the whole.
 */
function usd(n) { return Math.round(n * 1e6) / 1e6; }

/**
 * Prices a per-model token accumulation.
 *
 * @param byModel {modelId: {input, output, cacheRead, cacheCreation5m,
 *   cacheCreation1h, cacheCreationUnsplit}}
 * @returns {usd, byModel: [{model, usd}], unpricedTokens}
 *
 * `byModel` comes back sorted by spend, biggest first, so a client that has room
 * for one line can render the model the session actually ran on.
 */
function priceTokens(byModel) {
  const rows = [];
  let unpricedTokens = 0;
  for (const [model, t] of Object.entries(byModel || {})) {
    const tok = t || {};
    const input = tok.input || 0;
    const output = tok.output || 0;
    const cacheRead = tok.cacheRead || 0;
    const c5m = tok.cacheCreation5m || 0;
    const c1h = tok.cacheCreation1h || 0;
    const unsplit = tok.cacheCreationUnsplit || 0;
    const family = matchFamily(model);
    if (!family) {
      // Counted, and counted separately. These tokens were spent whether or not
      // this table knows the model, and a total that silently omits them is a
      // wrong total wearing a right one's face.
      unpricedTokens += input + output + cacheRead + c5m + c1h + unsplit;
      continue;
    }
    const r = RATES[family];
    const dollars = (input * r.input
      + output * r.output
      + cacheRead * r.cacheRead
      // ⚠ THE UNSPLIT REMAINDER PRICES AT THE 5-MINUTE RATE. When a record does
      // not say which TTL a cache write used, the two candidates are 1.25x and
      // 2x input, and this takes the cheaper: an estimate put in front of a
      // person should under-state what it cannot evidence rather than invent
      // spend it has no record of. It is also the likelier of the two — five
      // minutes is the default TTL, and the one-hour write is the opt-in.
      + (c5m + unsplit) * r.cacheWrite5m
      + c1h * r.cacheWrite1h) / 1e6;
    rows.push({ model, usd: usd(dollars) });
  }
  rows.sort((a, b) => b.usd - a.usd || a.model.localeCompare(b.model));
  return {
    usd: usd(rows.reduce((sum, r) => sum + r.usd, 0)),
    byModel: rows,
    unpricedTokens,
  };
}

module.exports = { matchFamily, priceTokens, RATES, CACHE_READ, CACHE_WRITE_5M, CACHE_WRITE_1H };
