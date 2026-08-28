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

const { matchFamily, priceTokens, RATES } = require('../lib/pricing');

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
