'use strict';
// The list-price table, and the one thing it is for: turning a pile of counted
// tokens into a dollar figure a person can read on the session overview.
//
// Everything here is arithmetic against a static table, so the tests are exact
// numbers rather than ranges — a rate that drifts should fail loudly, not shift
// a total by a few percent and keep rendering.
//
// The load-bearing cases are the two about HONESTY, because both fail silently:
// a model the table has never seen must not be priced at a neighbouring
// family's rate (a plausible wrong total is worse than a total that says what it
// could not price), and a cache write whose TTL nobody recorded must take the
// CHEAPER of the two candidate rates rather than the one that flatters the
// number.

const { test } = require('node:test');
const assert = require('node:assert');

const { matchFamily, priceTokens, bucketKey, RATES, ERA_RATES } = require('../lib/pricing');

/** One model's counted tokens, with everything the accumulator would have set. */
function tokens(t = {}) {
  return {
    input: t.input || 0,
    output: t.output || 0,
    cacheRead: t.cacheRead || 0,
    cacheCreation5m: t.cacheCreation5m || 0,
    cacheCreation1h: t.cacheCreation1h || 0,
    cacheCreationUnsplit: t.cacheCreationUnsplit || 0,
  };
}

const MILLION = 1_000_000;

// ---------------------------------------------------------------- the rates

test('each family prices a million tokens at its published list rate', () => {
  // USD per million, Anthropic first-party list prices as of 2026-08-27.
  const expected = [
    ['claude-fable-5', 10, 50],
    ['claude-opus-5', 5, 25],
    ['claude-sonnet-5', 3, 15],
    ['claude-haiku-4-5', 1, 5],
  ];
  for (const [model, inRate, outRate] of expected) {
    assert.equal(priceTokens({ [model]: tokens({ input: MILLION }) }).usd, inRate, `${model} input`);
    assert.equal(priceTokens({ [model]: tokens({ output: MILLION }) }).usd, outRate, `${model} output`);
  }
});

test('the cache rates are DERIVED from the input rate, never a second set of numbers', () => {
  // A base rate that gets corrected while a hand-typed cache rate stays behind
  // is the error that would actually matter here: on a long session cache reads
  // outnumber every other kind of token by an order of magnitude, so the wrong
  // read rate is most of the wrong bill.
  for (const [family, r] of Object.entries(RATES)) {
    assert.equal(r.cacheRead, r.input * 0.1, `${family} cache read is a tenth of input`);
    assert.equal(r.cacheWrite5m, r.input * 1.25, `${family} 5m write is 1.25x input`);
    assert.equal(r.cacheWrite1h, r.input * 2, `${family} 1h write is 2x input`);
  }
  // And through the pricing function, on the family whose arithmetic is easiest
  // to check by eye: opus input is $5/M.
  const opus = (t) => priceTokens({ 'claude-opus-5': tokens(t) }).usd;
  assert.equal(opus({ cacheRead: MILLION }), 0.5);
  assert.equal(opus({ cacheCreation5m: MILLION }), 6.25);
  assert.equal(opus({ cacheCreation1h: MILLION }), 10);
});

// -------------------------------------------------------------- the matching

test('a family is read out of the id, and an id nobody knows is null', () => {
  // Real ids carry date suffixes no table can enumerate, which is why this is a
  // substring match and not a lookup.
  assert.equal(matchFamily('claude-fable-5'), 'fable');
  assert.equal(matchFamily('claude-mythos-5'), 'fable', 'mythos bills as fable');
  assert.equal(matchFamily('claude-opus-5'), 'opus');
  assert.equal(matchFamily('CLAUDE-OPUS-5'), 'opus', 'case is not part of the id');
  assert.equal(matchFamily('claude-sonnet-5'), 'sonnet');
  assert.equal(matchFamily('claude-haiku-4-5-20251001'), 'haiku', 'a dated id is still a haiku');

  assert.equal(matchFamily('<synthetic>'), null, 'the CLI\'s own records are not a model anybody priced');
  assert.equal(matchFamily('qwen3-coder-30b'), null, 'the local tier is not on this table');
  assert.equal(matchFamily(''), null);
  assert.equal(matchFamily(null), null);
  assert.equal(matchFamily(undefined), null);
});

test('an unknown model is REPORTED, never priced at a neighbour\'s rate', () => {
  // The failure this exists for: a new model id lands, gets quietly rounded to
  // the nearest family, and the overview shows a total that is confidently
  // wrong. Counting them separately keeps the estimate answerable — "this is
  // what I could price, and here is what I could not".
  const res = priceTokens({
    'claude-opus-5': tokens({ input: MILLION }),
    'some-new-model-9': tokens({
      input: 10, output: 20, cacheRead: 30, cacheCreation5m: 1, cacheCreation1h: 2, cacheCreationUnsplit: 3,
    }),
  });
  assert.equal(res.usd, 5, 'the unpriced model adds nothing to the dollars');
  assert.equal(res.unpricedTokens, 66, 'every bucket of it is counted, not just input');
  assert.deepEqual(res.byModel.map((r) => r.model), ['claude-opus-5'],
    'and it gets no row, because a row saying $0 would be a claim it was free');
});

// --------------------------------------------------------------- the caching

test('a cache write with no TTL split prices at the CHEAPER 5-minute rate', () => {
  // The two candidates are 1.25x input (5m) and 2x (1h). An estimate put in
  // front of a person should under-state what it cannot evidence, so the
  // unsplit remainder takes the low one — and 5 minutes is also the default
  // TTL, so it is the likelier of the two as well.
  const unsplit = priceTokens({ 'claude-opus-5': tokens({ cacheCreationUnsplit: MILLION }) }).usd;
  assert.equal(unsplit, 6.25, 'the 5-minute rate');
  assert.notEqual(unsplit, 10, 'not the 1-hour rate, which would invent spend nothing recorded');

  // And the split, when it IS recorded, is honoured per half rather than being
  // collapsed into one rate.
  const split = priceTokens({
    'claude-opus-5': tokens({ cacheCreation5m: MILLION, cacheCreation1h: MILLION }),
  }).usd;
  assert.equal(split, 16.25, '6.25 at the 5m rate plus 10 at the 1h rate');
});

// ------------------------------------------------------------- the rate eras
//
// A price is a fact about a MOMENT, and the table above only knows about today.
// Sonnet 5 launched on an introductory rate — $2/$10 per MTok against the $3/$15
// sticker — and every token spent inside that window was spent at it, whether it
// is priced today or in a year. So a record carries its timestamp to the pricer
// and gets the card that was in force when it was written.
//
// The window is stated in UTC and closes at 2026-09-01T00:00:00Z, which is the
// first sticker-priced second: "through 2026-08-31" is a date, and a date ends
// where the next one begins.

/** Epoch SECONDS, the unit a transcript's timestamps reach the pricer in. */
const at = (iso) => Date.parse(iso) / 1000;
const IN_WINDOW = at('2026-08-30T12:00:00Z');
const BOUNDARY = at('2026-09-01T00:00:00Z');

/** One model's tokens, priced as of a moment. */
function priceAt(model, ts, t) {
  return priceTokens({ [bucketKey(model, ts)]: tokens(t) });
}

test('a Sonnet 5 record inside the intro window prices at the rate that was in force', () => {
  // The whole point: not a four-day patch that expires with the window, but the
  // permanent answer for tokens that were spent while it was open. A session
  // from August priced next March still bills at what August cost.
  assert.equal(priceAt('claude-sonnet-5', IN_WINDOW, { input: MILLION }).usd, 2);
  assert.equal(priceAt('claude-sonnet-5', IN_WINDOW, { output: MILLION }).usd, 10);
  assert.notEqual(priceAt('claude-sonnet-5', IN_WINDOW, { output: MILLION }).usd, 15,
    'the sticker rate would overstate that model by a third');
  // And the cache rates ride the era's input rate, not the sticker one — on a
  // cache-heavy session that is most of the difference.
  assert.equal(priceAt('claude-sonnet-5', IN_WINDOW, { cacheRead: MILLION }).usd, 0.2);
  assert.equal(priceAt('claude-sonnet-5', IN_WINDOW, { cacheCreationUnsplit: MILLION }).usd, 2.5);
});

test('the boundary second is the FIRST one at sticker, not the last one at intro', () => {
  // An off-by-one here is a whole day priced wrong and nothing on screen looks
  // different, so both sides of the instant are pinned rather than "a date in
  // September".
  const out = { output: MILLION };
  assert.equal(priceAt('claude-sonnet-5', BOUNDARY - 1, out).usd, 10, 'the last intro second');
  assert.equal(priceAt('claude-sonnet-5', BOUNDARY, out).usd, 15, 'the first sticker second');
  assert.equal(priceAt('claude-sonnet-5', BOUNDARY + 86_400, out).usd, 15, 'and every one after it');
});

test('the era is Sonnet 5\'s alone — its family sibling was never on it', () => {
  // ⚠ THE FAILURE THIS EXISTS FOR. `matchFamily` matches a family on a LOOSE
  // substring ('sonnet'), which is safe because families are disjoint out there.
  // An era sits INSIDE a family, so it cannot borrow that looseness: sonnet-4-6
  // has been $3/$15 its whole life, and an era that leaked onto it would
  // understate every 4-6 session by a third with a perfectly plausible number.
  const out = { output: MILLION };
  assert.equal(priceAt('claude-sonnet-4-6', IN_WINDOW, out).usd, 15, 'inside the window');
  assert.equal(priceAt('claude-sonnet-4-6', BOUNDARY, out).usd, 15, 'and outside it');

  // The dated variants of Sonnet 5 itself DO belong to it — those are the ids
  // that actually reach a transcript.
  assert.equal(priceAt('claude-sonnet-5-20260815', IN_WINDOW, out).usd, 10);
  assert.equal(priceAt('anthropic.claude-sonnet-5', IN_WINDOW, out).usd, 10, 'and the Bedrock form');

  // Nothing else in the table moved.
  assert.equal(priceAt('claude-opus-5', IN_WINDOW, out).usd, 25);
  assert.equal(priceAt('claude-haiku-4-5', IN_WINDOW, out).usd, 5);
});

test('a record with no timestamp takes the CHEAPER of the two cards it might have been', () => {
  // The same rule the unsplit cache write follows, for the same reason: an
  // estimate put in front of a person should under-state what it cannot
  // evidence rather than invent spend it has no record of. An undated Sonnet 5
  // record was written either side of the boundary and nothing says which, so it
  // takes the intro card.
  const out = { output: MILLION };
  assert.equal(priceAt('claude-sonnet-5', null, out).usd, 10, 'the intro rate');
  assert.equal(priceAt('claude-sonnet-5', undefined, out).usd, 10, 'however the absence arrives');
  assert.notEqual(priceAt('claude-sonnet-5', null, out).usd, 15);
  assert.equal(bucketKey('claude-sonnet-5', null), bucketKey('claude-sonnet-5', IN_WINDOW),
    'and it lands in the same bucket as a record that says it was in the window');
});

test('a model priced on both sides of a boundary is ONE row, summed at the right rates', () => {
  // The wire shape does not change: `byModel` is one row per model NAME, and a
  // session that ran across the boundary shows a single claude-sonnet-5 row
  // whose dollars are the era-correct sum. Two rows for one model would be a
  // client rendering the same model twice and a total nobody can check.
  const res = priceTokens({
    [bucketKey('claude-sonnet-5', IN_WINDOW)]: tokens({ output: MILLION }),  // $10
    [bucketKey('claude-sonnet-5', BOUNDARY)]: tokens({ output: MILLION }),   // $15
  });
  assert.deepEqual(res.byModel, [{ model: 'claude-sonnet-5', usd: 25 }]);
  assert.equal(res.usd, 25);
  assert.equal(res.unpricedTokens, 0);
});

test('an era card derives its cache rates the same way a family card does', () => {
  // The invariant the family table is built on, held on the second table too: a
  // corrected base rate can never leave a stale cache rate sitting beside it.
  for (const [era, r] of Object.entries(ERA_RATES)) {
    assert.equal(r.cacheRead, r.input * 0.1, `${era} cache read`);
    assert.equal(r.cacheWrite5m, r.input * 1.25, `${era} 5m write`);
    assert.equal(r.cacheWrite1h, r.input * 2, `${era} 1h write`);
  }
});

// ------------------------------------------------------------------ the edges

test('no tokens is zero dollars, which is a number — not null and not missing', () => {
  const empty = priceTokens({});
  assert.deepEqual(empty, { usd: 0, byModel: [], unpricedTokens: 0 });
  assert.deepEqual(priceTokens(null), { usd: 0, byModel: [], unpricedTokens: 0 });

  const idle = priceTokens({ 'claude-opus-5': tokens() });
  assert.equal(idle.usd, 0);
  assert.deepEqual(idle.byModel, [{ model: 'claude-opus-5', usd: 0 }],
    'a model that ran and spent nothing still ran');
});

test('the per-model rows add up to the total a client renders beside them', () => {
  // Rounding happens once, per row, and the total is the sum of the ROUNDED
  // rows. A total rounded separately from raw dollars drifts from the breakdown
  // under it — here by a third of a cent per row, which is exactly the kind of
  // arithmetic a reader checks and then stops trusting the screen over.
  const each = tokens({ cacheRead: 26 }); // 26 x $0.10/M = $0.0000026, rounding up
  const res = priceTokens({
    'claude-haiku-4-5': each,
    'claude-haiku-4-5-20251001': each,
    'claude-haiku-3': each,
  });
  assert.equal(res.byModel.length, 3);
  for (const r of res.byModel) assert.equal(r.usd, 0.000003, r.model);
  assert.equal(res.usd, 0.000009, 'three rows of $0.000003');
  const summed = res.byModel.reduce((s, r) => s + r.usd, 0);
  assert.equal(Math.round(summed * 1e6) / 1e6, res.usd, 'the parts are the whole');
});

test('a session that ran two models is priced per model, biggest spender first', () => {
  // The case the whole per-model split exists for: an opus parent that fanned
  // out to a haiku agent. One blended rate over both is a number about nothing.
  const res = priceTokens({
    'claude-haiku-4-5': tokens({ output: MILLION }),   // $5
    'claude-opus-5': tokens({ output: MILLION }),      // $25
  });
  assert.equal(res.usd, 30);
  assert.deepEqual(res.byModel, [
    { model: 'claude-opus-5', usd: 25 },
    { model: 'claude-haiku-4-5', usd: 5 },
  ], 'sorted by spend, so a client with room for one line names the model it ran on');
  assert.equal(res.unpricedTokens, 0);
});
