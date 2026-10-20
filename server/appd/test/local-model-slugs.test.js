'use strict';
// Two local-model machines with the same name, or one name extending another,
// can both be used.
//
// A local-model id is `local-<llmSlug>-<modelSlug>`, and the slugs were meant to
// never dash-prefix each other. mintLlmSlug detected a clash and then minted
// `<base>-<hash>`, which still starts with `<base>-`: a re-enrolled "gpubox-llm"
// got "gpubox-llm-2dad". resolveLocalModel walked the rows in enrolment order and
// stopped at the first prefix that matched, the OLD row's, so every model the new
// machine served was listed as available and answered 400 when picked.

const { test } = require('node:test');
const assert = require('node:assert');
const devicesLib = require('../lib/devices');
const { lift } = require('./appd-lift');

const uuid = (head) => `${head}-0000-4000-8000-000000000000`;

/** Neither slug can be read as the other followed by a model id. */
function unambiguous(a, b) {
  return a !== b && !a.startsWith(`${b}-`) && !b.startsWith(`${a}-`);
}

test('a clash is broken without leaving either slug a dash-prefix of the other', () => {
  const cases = [
    ['twin', ['twin']],                    // same name, short
    ['gpubox-llm', ['gpubox-llm']],        // a re-enrolled machine, default "<host>-llm"
    ['desktop-llm', ['desktop-llm']],      // exactly 11 characters, the old boundary
    ['box-2', ['box']],                    // the new name extends a taken one
    ['box', ['box-2']],                    // a taken name extends the new one
  ];
  for (const [name, taken] of cases) {
    const got = devicesLib.mintLlmSlug(name, uuid('2dad7a11'), taken);
    for (const t of taken) assert.ok(unambiguous(got, t), `${name} beside ${t} minted ${got}`);
  }
});

test('many enrolments under one name stay pairwise unambiguous', () => {
  const taken = [];
  for (const head of ['a1b2c3d4', 'a1b2ffff', '0000aaaa', 'ffff0000', 'a1b20000', '12345678']) {
    taken.push(devicesLib.mintLlmSlug('gpubox-llm', uuid(head), taken));
  }
  for (let i = 0; i < taken.length; i++) {
    for (let j = i + 1; j < taken.length; j++) {
      assert.ok(unambiguous(taken[i], taken[j]), `${taken[i]} and ${taken[j]}`);
    }
  }
});

test('names that never clashed, and long ones that were already safe, mint as before', () => {
  assert.equal(devicesLib.mintLlmSlug('gpubox-llm', uuid('2dad7a11'), []), 'gpubox-llm');
  assert.equal(devicesLib.mintLlmSlug('office-pc-llm', uuid('03627a11'), ['office-pc-llm']), 'office-pc-l-0362');
});

/** The daemon's own resolveLocalModel over a registry. */
function resolverOver(devices) {
  const deviceState = { devices };
  const { fns } = lift(['resolveLocalModel'], { deviceState, devicesLib });
  return fns.resolveLocalModel;
}

// What the old minting left on disk for a machine enrolled twice: the stale row
// first (kept 30 days, or for ever if watched), the live one after it.
const OLD_ROW = {
  name: 'gpubox-llm', scope: 'generate', llmSlug: 'gpubox-llm',
  models: [{ slug: 'qwen3-8b', display: 'Qwen3 8B' }], lastSeen: Date.now() - 2 * 86_400_000,
};
const NEW_ROW = {
  name: 'gpubox-llm', scope: 'generate', llmSlug: 'gpubox-llm-2dad',
  models: [{ slug: 'qwen3-8b', display: 'Qwen3 8B' }, { slug: 'llama3', display: 'Llama 3' }], lastSeen: Date.now(),
};

test('a model on the re-enrolled machine resolves to it, not to the stale row', () => {
  const resolve = resolverOver({ [uuid('00000001')]: OLD_ROW, [uuid('00000002')]: NEW_ROW });
  for (const model of ['qwen3-8b', 'llama3']) {
    const r = resolve(`local-gpubox-llm-2dad-${model}`);
    assert.equal(r.error, undefined, r.error);
    assert.equal(r.deviceId, uuid('00000002'));
    assert.equal(r.slug, model);
  }
  const old = resolve('local-gpubox-llm-qwen3-8b');
  assert.equal(old.deviceId, uuid('00000001'), 'the old row\'s own ids still reach it');
});

test('a model nobody serves is refused, naming the closest machine', () => {
  const resolve = resolverOver({ [uuid('00000001')]: OLD_ROW, [uuid('00000002')]: NEW_ROW });
  const r = resolve('local-gpubox-llm-2dad-mistral');
  assert.match(r.error || '', /does not serve "mistral"/);
  assert.match(resolve('local-otherbox-qwen3-8b').error || '', /no enrolled machine serves this model/);
});

test('every row the model picker lists resolves to the machine it was listed for', () => {
  const devices = { [uuid('00000001')]: OLD_ROW, [uuid('00000002')]: NEW_ROW };
  const resolve = resolverOver(devices);
  const rows = devicesLib.localModelRows({ devices }, Date.now());
  assert.equal(rows.length, 3);
  for (const row of rows) {
    const r = resolve(row.id);
    assert.equal(r.deviceId, row.host, `${row.id}: ${r.error || `resolved to ${r.deviceId}`}`);
  }
});
