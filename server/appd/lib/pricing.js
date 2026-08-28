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
//
// A price is also a fact about a MOMENT. List prices change, and a token bills
// at the rate that was in force when it was SPENT — permanently, not until the
// change expires. So the family card is the standing rate and `RATE_ERAS` below
// carries the exceptions, each record reaching the pricer with its own
// timestamp. That is the same principle the unsplit cache write already follows:
// price what the record says happened.

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

/** A whole rate card from the two published numbers. The ONE derivation. */
function rateCard(input, output) {
  return {
    input,
    output,
    cacheRead: input * CACHE_READ,
    cacheWrite5m: input * CACHE_WRITE_5M,
    cacheWrite1h: input * CACHE_WRITE_1H,
  };
}

/** family -> the whole rate card, cache rates included. */
const RATES = Object.fromEntries(FAMILIES.map((f) => [f.family, rateCard(f.input, f.output)]));

/**
 * Windows in which ONE model billed at something other than its family's card.
 *
 * Claude Sonnet 5 launched on an introductory rate — $2/$10 per MTok against the
 * $3/$15 sticker — through 2026-08-31. Every token spent while that was open was
 * spent at it, so this is not a patch that expires when the window does: a
 * session from August, priced next March, still bills what August cost.
 *
 * `endsBefore` is the first instant at the sticker rate, in UTC — "through
 * 2026-08-31" is a date, and a date ends where the next one begins. ⚠ UTC IS AN
 * ASSUMPTION: a transcript timestamp is an absolute instant, but the published
 * window is a calendar date with no zone attached, so the boundary is placed at
 * midnight UTC and a few hours either side of it are a coin toss.
 *
 * ⚠ `match` IS DELIBERATELY TIGHTER THAN A FAMILY MARKER. A family can afford a
 * loose substring because families are disjoint out there; an era lives INSIDE
 * one, beside siblings that were never on it. `claude-sonnet-4-6` has been
 * $3/$15 its whole life, and a marker of 'sonnet' — or even 'sonnet-5' unanchored
 * — would quietly understate every 4-6 session by a third. So the id must END at
 * the model, or at one of the date suffixes that really reach a transcript
 * (`-20260815`, and Vertex's `@20260815`). Anything else falls through to the
 * family card, which is the same thing that happens to any id this file has not
 * been taught — and, like the table above, it goes stale silently.
 */
const RATE_ERAS = [
  {
    era: 'sonnet-5-intro',
    match: /sonnet-5(?:[-@]\d{8})?$/,
    endsBefore: Date.parse('2026-09-01T00:00:00Z') / 1000,
    input: 2.00,
    output: 10.00,
  },
];

/** era id -> its rate card, derived exactly as a family's is. */
const ERA_RATES = Object.fromEntries(RATE_ERAS.map((e) => [e.era, rateCard(e.input, e.output)]));

/**
 * The separator inside a bucket key.
 *
 * A SPACE, because a model id is an API identifier and cannot contain one, while
 * every punctuation mark that suggests itself is already spoken for by some
 * provider's id form — '.' by Bedrock's prefix (`anthropic.claude-opus-5`), '-'
 * and '@' by dated snapshots (`claude-opus-4-5@20251101`). A key built on any of
 * those is a key this file could not take apart again.
 *
 * NUL is the usual answer to "a character that cannot appear in data", and it is
 * the wrong one HERE: this is tracked source that rides the weekly push, and a
 * literal NUL byte in it makes git call the file binary, `file` report "data",
 * and grep go silent — while an editor still renders the byte as a space, so
 * nothing looks wrong. A space costs nothing and stays text.
 */
const KEY_SEP = ' ';

/**
 * The bucket a record's tokens accumulate into: its model, and the rate era it
 * was written in.
 *
 * This is the WHOLE of the caller's involvement in eras. A walker hands over the
 * model and the record's timestamp and gets back an opaque key; every judgment
 * about which rate was in force lives here and in `priceTokens`, so a second
 * accumulation path can never quietly disagree with the first about a price.
 *
 * @param model the id the record named, or null
 * @param ts    epoch SECONDS — the unit a transcript's timestamps arrive in
 */
function bucketKey(model, ts) {
  const raw = model == null ? '' : String(model);
  const id = raw.toLowerCase();
  for (const e of RATE_ERAS) {
    if (!e.match.test(id)) continue;
    // An era overrides a card; with no family there is no card to override, and
    // the tokens are heading for `unpricedTokens` either way.
    const family = matchFamily(id);
    if (!family) break;
    if (Number.isFinite(ts)) return ts < e.endsBefore ? raw + KEY_SEP + e.era : raw;
    // ⚠ NO TIMESTAMP TAKES THE CHEAPER OF THE TWO CARDS, which is the rule the
    // unsplit cache write already follows and for the same reason: an estimate
    // put in front of a person should under-state what it cannot evidence
    // rather than invent spend it has no record of. An undated record was
    // written on one side of the boundary or the other and nothing says which,
    // so it gets the lower of the two candidates — read off the cards rather
    // than assumed, because an era is not necessarily a discount.
    const era = ERA_RATES[e.era];
    const base = RATES[family];
    return era.input <= base.input && era.output <= base.output ? raw + KEY_SEP + e.era : raw;
  }
  return raw;
}

/** A bucket key back into the model a client reads and the era it was priced in. */
function splitKey(key) {
  const at = key.indexOf(KEY_SEP);
  return at === -1
    ? { model: key, era: null }
    : { model: key.slice(0, at), era: key.slice(at + 1) };
}

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
 * Prices a per-bucket token accumulation.
 *
 * @param byModel {bucketKey: {input, output, cacheRead, cacheCreation5m,
 *   cacheCreation1h, cacheCreationUnsplit}} — keys minted by `bucketKey`, which
 *   is a model id plus the rate era its tokens were spent in. A plain model id
 *   is a valid key and prices at the standing family card.
 * @returns {usd, byModel: [{model, usd}], unpricedTokens}
 *
 * ⚠ ONE ROW PER MODEL NAME, WHATEVER ERAS IT WAS SPREAD ACROSS. The eras are an
 * accounting detail of this file, not something a client decodes: a session that
 * ran either side of a price change shows a single row whose dollars are the
 * era-correct sum. Two rows for one model would be the same model rendered twice
 * and a breakdown nobody can check against the total.
 *
 * `byModel` comes back sorted by spend, biggest first, so a client that has room
 * for one line can render the model the session actually ran on.
 */
function priceTokens(byModel) {
  // model name -> dollars, summed RAW across that model's eras. Rounding stays
  // once per rendered row rather than once per era, so the parts still add up to
  // the whole exactly as they did before eras existed.
  const perModel = new Map();
  let unpricedTokens = 0;
  for (const [key, t] of Object.entries(byModel || {})) {
    const { model, era } = splitKey(key);
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
    // The card that was in force for THESE tokens: the era's if the key named
    // one, else the family's standing rate. An era id this file no longer
    // carries falls back to the family card rather than throwing — a stale
    // bucket key should cost the estimate its precision, not the whole number.
    const r = (era && ERA_RATES[era]) || RATES[family];
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
    perModel.set(model, (perModel.get(model) || 0) + dollars);
  }
  const rows = [...perModel].map(([model, dollars]) => ({ model, usd: usd(dollars) }));
  rows.sort((a, b) => b.usd - a.usd || a.model.localeCompare(b.model));
  return {
    usd: usd(rows.reduce((sum, r) => sum + r.usd, 0)),
    byModel: rows,
    unpricedTokens,
  };
}

module.exports = {
  matchFamily, priceTokens, bucketKey,
  RATES, ERA_RATES, RATE_ERAS,
  CACHE_READ, CACHE_WRITE_5M, CACHE_WRITE_1H,
};
