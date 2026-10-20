'use strict';
// A Round run cut off by its cap while writing its report block, then resumed for
// the wrap-up turn, files the wrap-up's report.
//
// The cut leaves an unterminated ```huginn-report fence in the partial message
// settleRun saves, and finishRoundRun joins every assistant message. The wrap-up
// turn's opening fence line was read as more body of the cut block, which ran on
// to the wrap-up's closing fence: one block, not JSON. The Round filed "unknown"
// with prose for a headline, no items and goalMet null, in place of the `action`
// report the wrap-up turn exists to save.

const { test } = require('node:test');
const assert = require('node:assert');
const R = require('../lib/rounds');
const { lift } = require('./appd-lift');

const F = '```';
const TAG = '3fa21c09b7';
const REPORT = {
  status: 'action',
  headline: 'PBS sync failing 6 days',
  goalMet: false,
  items: [
    { title: 'sync job s-1 failing', detail: 'last success 6 days ago', suggest: 'check the remote' },
    { title: 'datastore 91% full', detail: 'prune is behind', suggest: 'run a prune' },
  ],
};
const BLOCK = `${F}huginn-report ${TAG}\n${JSON.stringify(REPORT)}\n${F}`;

/** Where the cap can land in a block that is still streaming. */
const CUTS = {
  'mid-JSON': `${F}huginn-report ${TAG}\n{"status":"action","headline":"PBS sy`,
  'after the fence line': `${F}huginn-report ${TAG}\n`,
  'mid-tag': `${F}huginn-report ${TAG.slice(0, 4)}`,
  'before the closing fence': `${F}huginn-report ${TAG}\n${JSON.stringify(REPORT)}\n`,
};

/** The assistant texts as finishRoundRun joins them: earlier prose, the cut, the wrap-up. */
function joined(cut) {
  return [
    'Checked PBS, the datastores and the sync jobs.',
    `All checks done. Report:\n\n${cut}`,
    `Here is the report from what I gathered.\n\n${BLOCK}`,
  ].join('\n\n');
}

for (const [where, cut] of Object.entries(CUTS)) {
  test(`a block cut ${where} does not swallow the wrap-up's report`, () => {
    const p = R.parseReport(joined(cut), TAG);
    assert.ok(p, 'the wrap-up report was lost');
    assert.equal(p.status, 'action');
    assert.equal(p.headline, 'PBS sync failing 6 days');
    assert.equal(p.goalMet, false);
    assert.equal(p.items.length, 2);
    assert.equal(R.untaggedReport(joined(cut), TAG), false, 'and nothing reads as an untagged block');
  });
}

test('a cut block with no report after it still gives a clean fallback headline', () => {
  // The other half of the scanner: with nothing to restart at, the cut block is
  // still debris, and the fallback still cuts it off the prose.
  const text = ['Checked PBS.', `Report:\n\n${CUTS['mid-JSON']}`, 'Sorry, I ran out of time.'].join('\n\n');
  assert.equal(R.parseReport(text, TAG), null);
  const f = R.fallbackReport(text);
  assert.equal(f.headline, 'Checked PBS. Report:');
  assert.ok(!f.headline.includes('"status"'), f.headline);
});

test('finishRoundRun files the wrap-up report over a cut partial message', async () => {
  // The daemon's own finishRoundRun, fed the transcript a capped run leaves:
  // settleRun's partial message and "cancelled", the wrap-up note and prompt,
  // then the wrap-up turn's answer and its ok result.
  const ts = 1_760_000_000;
  const msgs = [
    { type: 'user', text: 'Check PBS.', ts },
    { type: 'assistant', text: 'Checked PBS, the datastores and the sync jobs.', ts },
    { type: 'assistant', text: `All checks done. Report:\n\n${CUTS['mid-JSON']}`, ts, partial: true },
    { type: 'error', text: 'cancelled', ts },
    { type: 'system', text: 'time is up after 15 min (budget 15 min)', ts },
    { type: 'user', text: 'TIME IS UP. Write the report now.', ts },
    { type: 'assistant', text: `Here is the report from what I gathered.\n\n${BLOCK}`, ts },
    { type: 'result', ok: true, ts },
  ];
  const round = { id: 'r1', title: 'PBS check', notifyWhen: 'attention', runs: [], currentChatId: 'c1' };
  const meta = { id: 'c1', roundId: 'r1', reportTag: TAG, roundStartedAt: ts - 960 };
  const delivered = [];
  const { fns } = lift(['finishRoundRun'], {
    log: () => {},
    roundsLib: R,
    drainPending: () => [],
    saveMeta: () => {},
    appendMsg: () => {},
    loadRound: () => round,
    loadMsgs: () => msgs,
    updateRound: (id, fn) => { fn(round); return round; },
    updateMeta: (id, fn) => { fn(meta); return meta; },
    activeRuns: new Map(),
    fs: { rmSync: () => {} },
    chatDir: (id) => `/nonexistent/${id}`,
    deliverRoundReport: async (r, report) => { delivered.push(report); },
  }, { constants: ['MAX_RUN_HISTORY'] });

  fns.finishRoundRun(meta, null);
  const run = round.lastRun;
  assert.equal(run.status, 'action', `filed ${run.status}: ${run.headline}`);
  assert.equal(run.headline, 'PBS sync failing 6 days');
  assert.equal(run.goalMet, false);
  assert.equal(run.items.length, 2);
  assert.equal(run.malformed, false);
  assert.equal(meta.sealed, true);
  assert.equal(delivered.length, 1, 'an action report notifies');
  assert.equal(delivered[0].status, 'action');
});
