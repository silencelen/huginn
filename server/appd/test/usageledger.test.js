'use strict';
// The daemon's own usage ledger (lib/usageledger): what replaced ccusage behind
// GET /v1/usage. Fixtures are synthetic transcripts in a scratch dir — no real
// paths, no real ~/.claude.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const L = require('../lib/usageledger');
const { summarizeUsage } = require('../lib/usage');
const { priceTokens } = require('../lib/pricing');

/** A local-time instant on a given day, as the ISO string a transcript carries. */
function at(day, hhmm = '12:00') {
  const [y, m, d] = day.split('-').map(Number);
  const [hh, mm] = hhmm.split(':').map(Number);
  return new Date(y, m - 1, d, hh, mm, 0).toISOString();
}

function assistant({ id, model = 'claude-opus-5-5', ts, usage, block = { type: 'text', text: 'hi' }, extra = {} }) {
  return {
    parentUuid: null,
    isSidechain: false,
    type: 'assistant',
    uuid: `u-${id}-${Math.random().toString(16).slice(2, 8)}`,
    timestamp: ts,
    cwd: '/home/alex/work',
    sessionId: 's-alex',
    version: '2.1.316',
    ...extra,
    message: {
      model,
      id,
      type: 'message',
      role: 'assistant',
      content: [block],
      usage: {
        input_tokens: 2, output_tokens: 50, cache_creation_input_tokens: 1000, cache_read_input_tokens: 20000,
        cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 1000 },
        ...usage,
      },
    },
  };
}

function apiRequest(ts) {
  // Big, and it EMBEDS an earlier assistant message with a usage block, exactly
  // as the real record does. A reader that trusts a substring test counts it.
  return {
    type: 'api-request',
    sessionId: 's-alex',
    id: 'req-alex-1',
    timestamp: ts,
    request: {
      model: 'claude-opus-5-5',
      messages: [
        { role: 'user', content: 'x'.repeat(60_000) },
        { role: 'assistant', content: [{ type: 'text', text: 'earlier' }], usage: { input_tokens: 999999, output_tokens: 999999 } },
      ],
    },
  };
}

function lines(records) { return records.map((r) => JSON.stringify(r)).join('\n') + '\n'; }

const TODAY = L.localDay(new Date());
const YESTERDAY = L.localDay(new Date(Date.now() - 86_400_000));

// ------------------------------------------------------------- ingestRecord

test('one API message split into several assistant records is counted once', () => {
  const led = L.emptyLedger();
  const seen = [];
  const ts = at(TODAY);
  const a = assistant({ id: 'msg_alex_1', ts, block: { type: 'thinking', thinking: '…' } });
  const b = assistant({ id: 'msg_alex_1', ts, block: { type: 'tool_use', id: 't1', name: 'Read', input: {} } });
  const c = assistant({ id: 'msg_alex_1', ts, block: { type: 'text', text: 'done' } });
  assert.strictEqual(L.ingestRecord(led, a, null, seen), true);
  assert.strictEqual(L.ingestRecord(led, b, null, seen), false);
  assert.strictEqual(L.ingestRecord(led, c, null, seen), false);
  const bucket = led.days[TODAY].byModel['claude-opus-5-5'];
  assert.deepStrictEqual(bucket, {
    input: 2, output: 50, cacheCreation: 1000, cacheCreation5m: 0, cacheCreation1h: 1000, cacheRead: 20000, messages: 1,
  });
});

test('a second message id adds; an api-error record and a non-assistant record do not', () => {
  const led = L.emptyLedger();
  const seen = [];
  const ts = at(TODAY);
  L.ingestRecord(led, assistant({ id: 'm1', ts }), null, seen);
  L.ingestRecord(led, assistant({ id: 'm2', ts }), null, seen);
  assert.strictEqual(L.ingestRecord(led, assistant({
    id: 'm3', ts, model: '<synthetic>', extra: { isApiErrorMessage: true },
    usage: { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
  }), null, seen), false);
  assert.strictEqual(L.ingestRecord(led, { type: 'user', timestamp: ts, message: { role: 'user', content: 'x' } }, null, seen), false);
  assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].messages, 2);
  assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].output, 100);
});

test('subagent (isSidechain) records are real spend and are counted', () => {
  const led = L.emptyLedger();
  assert.strictEqual(L.ingestRecord(led, assistant({ id: 'm1', ts: at(TODAY), extra: { isSidechain: true } }), null, []), true);
  assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].messages, 1);
});

test('days are the host\'s local calendar days, not UTC', () => {
  const led = L.emptyLedger();
  // 00:30 local on TODAY: in any zone west of UTC that is YESTERDAY in UTC;
  // east of UTC, 23:30 local is TOMORROW in UTC. Both must land on the local day.
  L.ingestRecord(led, assistant({ id: 'early', ts: at(TODAY, '00:30') }), null, []);
  L.ingestRecord(led, assistant({ id: 'late', ts: at(TODAY, '23:30') }), null, []);
  assert.deepStrictEqual(Object.keys(led.days), [TODAY]);
  assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].messages, 2);
  // And the explicit day wins over the timestamp when a caller gives one.
  L.ingestRecord(led, assistant({ id: 'forced', ts: at(TODAY) }), YESTERDAY, []);
  assert.strictEqual(led.days[YESTERDAY].byModel['claude-opus-5-5'].messages, 1);
});

// ----------------------------------------------------------------- scanFile

test('scanFile resumes at the stored offset and leaves a half-written last line for next time', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'usageledger-'));
  try {
    const file = path.join(tmp, 's-alex.jsonl');
    const ts = at(TODAY);
    fs.writeFileSync(file, lines([assistant({ id: 'm1', ts })]));
    const led = L.emptyLedger();
    let r = await L.scanFile(led, file);
    assert.strictEqual(r.counted, 1);
    const afterFirst = led.files[file].offset;
    assert.strictEqual(afterFirst, fs.statSync(file).size);

    // Append a whole record and HALF of the next one (no newline).
    const whole = JSON.stringify(assistant({ id: 'm2', ts })) + '\n';
    const half = JSON.stringify(assistant({ id: 'm3', ts })).slice(0, 200);
    fs.appendFileSync(file, whole + half);
    r = await L.scanFile(led, file);
    assert.strictEqual(r.bytesRead, Buffer.byteLength(whole + half), 'only the new bytes are read');
    assert.strictEqual(r.counted, 1);
    assert.strictEqual(led.files[file].offset, afterFirst + Buffer.byteLength(whole), 'the offset stops before the half line');

    // Finish the half line: it is read from the start and counted once.
    fs.appendFileSync(file, JSON.stringify(assistant({ id: 'm3', ts })).slice(200) + '\n');
    r = await L.scanFile(led, file);
    assert.strictEqual(r.counted, 1);
    assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].messages, 3);

    // Nothing new: nothing read.
    r = await L.scanFile(led, file);
    assert.strictEqual(r.bytesRead, 0);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('api-request lines are skipped on the raw bytes, never parsed, and never counted', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'usageledger-'));
  try {
    const file = path.join(tmp, 's-alex.jsonl');
    const ts = at(TODAY);
    fs.writeFileSync(file, lines([apiRequest(ts), assistant({ id: 'm1', ts }), apiRequest(ts)]));
    const parse = JSON.parse;
    let parsedBytes = 0;
    JSON.parse = (s, ...rest) => { parsedBytes += s.length; return parse(s, ...rest); };
    try {
      const led = L.emptyLedger();
      const r = await L.scanFile(led, file);
      assert.strictEqual(r.lines, 3);
      assert.strictEqual(r.counted, 1);
      assert.ok(parsedBytes < 5000, `parsed ${parsedBytes} bytes: an api-request line went through JSON.parse`);
      assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].output, 50);
    } finally {
      JSON.parse = parse;
    }
    assert.strictEqual(L.wantLine(Buffer.from(JSON.stringify(apiRequest(ts)))), false);
    assert.strictEqual(L.wantLine(Buffer.from(JSON.stringify(assistant({ id: 'x', ts })))), true);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

// -------------------------------------------------------------------- sweep

test('sweep reads recent files, known files that grew, and backfills a window once — never the rest', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'usageledger-'));
  try {
    const root = path.join(tmp, 'projects');
    const slug = path.join(root, '-home-alex-work');
    fs.mkdirSync(path.join(slug, 's-old', 'subagents'), { recursive: true });
    const ts = at(TODAY);
    const recent = path.join(slug, 's-recent.jsonl');
    const sub = path.join(slug, 's-old', 'subagents', 'agent-1.jsonl');
    const old = path.join(slug, 's-old.jsonl');
    const ancient = path.join(slug, 's-ancient.jsonl');
    fs.writeFileSync(recent, lines([assistant({ id: 'r1', ts })]));
    fs.writeFileSync(sub, lines([assistant({ id: 'a1', ts, extra: { isSidechain: true } })]));
    fs.writeFileSync(old, lines([assistant({ id: 'o1', ts })]));
    fs.writeFileSync(ancient, lines([assistant({ id: 'z1', ts })]));
    const now = Date.now();
    const twoDays = new Date(now - 2 * 86_400_000);
    const twoWeeks = new Date(now - 14 * 86_400_000);
    fs.utimesSync(old, twoDays, twoDays);
    fs.utimesSync(sub, twoDays, twoDays);
    fs.utimesSync(ancient, twoWeeks, twoWeeks);

    // No backfill: only the file touched in the last 10 minutes is opened.
    let led = L.emptyLedger();
    let r = await L.sweep(led, [root], { now });
    assert.strictEqual(r.files, 4);
    assert.strictEqual(r.scanned, 1);
    assert.deepStrictEqual(Object.keys(led.files), [recent]);

    // With a 7-day backfill: the two-day-old files too (the subagent one under
    // <session>/subagents/), never the two-week-old one.
    led = L.emptyLedger();
    r = await L.sweep(led, [root], { now, backfillMs: 7 * 86_400_000 });
    assert.strictEqual(r.scanned, 3);
    assert.strictEqual(r.counted, 3);
    assert.ok(!led.files[ancient]);

    // A known file that grew is read again even though its mtime is old.
    fs.appendFileSync(old, lines([assistant({ id: 'o2', ts })]));
    fs.utimesSync(old, twoDays, twoDays);
    r = await L.sweep(led, [root], { now: now + 60_000 });
    assert.strictEqual(r.scanned, 1);
    assert.strictEqual(r.counted, 1);
    assert.strictEqual(led.days[TODAY].byModel['claude-opus-5-5'].messages, 4);

    // Nothing changed: nothing read.
    r = await L.sweep(led, [root], { now: now + 11 * 60_000 });
    assert.strictEqual(r.scanned, 0);
    assert.strictEqual(r.bytesRead, 0);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('load/save round-trip, and a corrupt ledger file is an empty ledger', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'usageledger-'));
  try {
    const file = path.join(tmp, 'usage', 'ledger.json');
    const led = L.emptyLedger();
    L.ingestRecord(led, assistant({ id: 'm1', ts: at(TODAY) }), null, []);
    L.save(file, led);
    assert.deepStrictEqual(L.load(file), led);
    fs.writeFileSync(file, '{"v":1,"days":');
    assert.deepStrictEqual(L.load(file), L.emptyLedger());
    assert.deepStrictEqual(L.load(path.join(tmp, 'nope.json')), L.emptyLedger());
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------- summarize

test('summarize has the shape the old ccusage summary had, with the same totals for the same fixture', () => {
  // Three days of alex's spend, two models. The ccusage side of the fixture is
  // what `ccusage daily --json` would have said for the same records.
  const d1 = L.localDay(new Date(Date.now() - 3 * 86_400_000));
  const d2 = L.localDay(new Date(Date.now() - 1 * 86_400_000));
  const led = L.emptyLedger();
  const u = (i, o, c, r) => ({ input_tokens: i, output_tokens: o, cache_creation_input_tokens: c, cache_read_input_tokens: r, cache_creation: { ephemeral_5m_input_tokens: c, ephemeral_1h_input_tokens: 0 } });
  L.ingestRecord(led, assistant({ id: 'a', ts: at(d1), usage: u(10, 100, 1000, 10000) }), null, []);
  L.ingestRecord(led, assistant({ id: 'b', ts: at(d1), model: 'claude-haiku-4-5-20251001', usage: u(5, 50, 500, 5000) }), null, []);
  L.ingestRecord(led, assistant({ id: 'c', ts: at(d2), usage: u(1, 1, 1, 1) }), null, []);
  L.ingestRecord(led, assistant({ id: 'd', ts: at(TODAY), usage: u(2, 20, 200, 2000) }), null, []);

  const cost = (byModel) => priceTokens(byModel).usd;
  const costD1 = cost({
    'claude-opus-5-5': { input: 10, output: 100, cacheRead: 10000, cacheCreation5m: 1000 },
    'claude-haiku-4-5-20251001': { input: 5, output: 50, cacheRead: 5000, cacheCreation5m: 500 },
  });
  const costD2 = cost({ 'claude-opus-5-5': { input: 1, output: 1, cacheRead: 1, cacheCreation5m: 1 } });
  const costToday = cost({ 'claude-opus-5-5': { input: 2, output: 20, cacheRead: 2000, cacheCreation5m: 200 } });
  const ccusage = {
    daily: [
      { period: d1, inputTokens: 15, outputTokens: 150, cacheCreationTokens: 1500, cacheReadTokens: 15000, totalTokens: 16665, totalCost: costD1 },
      { period: d2, inputTokens: 1, outputTokens: 1, cacheCreationTokens: 1, cacheReadTokens: 1, totalTokens: 4, totalCost: costD2 },
      { period: TODAY, inputTokens: 2, outputTokens: 20, cacheCreationTokens: 200, cacheReadTokens: 2000, totalTokens: 2222, totalCost: costToday },
    ],
    totals: {
      inputTokens: 18, outputTokens: 171, cacheCreationTokens: 1701, cacheReadTokens: 17001, totalTokens: 18891,
      totalCost: Math.round((costD1 + costD2 + costToday) * 1e6) / 1e6,
    },
  };
  const old = summarizeUsage(ccusage, TODAY.replace(/-/g, ''));
  const now = L.summarize(led, TODAY, 7);

  assert.deepStrictEqual(Object.keys(now).sort(), Object.keys(old).sort());
  assert.deepStrictEqual(now.week, old.week);
  assert.strictEqual(now.daily.length, 3);
  for (let i = 0; i < 3; i++) {
    for (const k of Object.keys(old.daily[i])) assert.strictEqual(now.daily[i][k], old.daily[i][k], `${old.daily[i].date}.${k}`);
  }
  for (const k of Object.keys(old.today)) assert.strictEqual(now.today[k], old.today[k], `today.${k}`);
  assert.ok(now.today.costUsd > 0);
  assert.deepStrictEqual(now.daily[0].models, ['claude-opus-5-5', 'claude-haiku-4-5-20251001']);
});

test('summarize: no spend in the window gives null today and an empty week, never a throw', () => {
  const s = L.summarize(L.emptyLedger(), TODAY, 7);
  assert.strictEqual(s.today, null);
  assert.deepStrictEqual(s.daily, []);
  assert.strictEqual(s.week.days, 0);
  assert.strictEqual(s.week.totalTokens, 0);
  assert.strictEqual(s.week.costUsd, 0);
});

test('dayWindow is the last N local days ending today, oldest first, across a month boundary', () => {
  assert.deepStrictEqual(L.dayWindow('2026-03-02', 3), ['2026-02-28', '2026-03-01', '2026-03-02']);
  assert.deepStrictEqual(L.dayWindow('2026-01-01', 2), ['2025-12-31', '2026-01-01']);
});

// -------------------------------------------------------------- correlation

test('correlate pairs ccusage days with the ledger\'s and reports drift on total tokens', () => {
  const led = L.emptyLedger();
  L.ingestRecord(led, assistant({ id: 'a', ts: at(TODAY), usage: { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 1100, cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 0 } } }), null, []);
  const parsed = { daily: [{ period: TODAY, cacheReadTokens: 1000, totalTokens: 1000, totalCost: 0.1 }] };
  const c = L.correlate(led, parsed, [YESTERDAY, TODAY], 123);
  assert.strictEqual(c.at, 123);
  assert.strictEqual(c.days[TODAY].totalTokens, 1000);
  assert.strictEqual(c.ledger[TODAY].totalTokens, 1100);
  assert.strictEqual(c.driftPercent[TODAY], 10);
  assert.strictEqual(c.days[YESTERDAY], null);
  assert.strictEqual(c.ledger[YESTERDAY], null);
  assert.strictEqual(c.driftPercent[YESTERDAY], null);
});

test('correlationDue: Sundays from 05:00 host time, once per Sunday', () => {
  const sunday = new Date(2026, 10, 8, 5, 0, 0); // 2026-11-08 is a Sunday
  assert.strictEqual(sunday.getDay(), 0);
  const sundayStart = new Date(2026, 10, 8).getTime();
  assert.strictEqual(L.correlationDue(sunday.getTime(), 0), true);
  assert.strictEqual(L.correlationDue(new Date(2026, 10, 8, 4, 59).getTime(), 0), false, 'before five');
  assert.strictEqual(L.correlationDue(new Date(2026, 10, 9, 5, 0).getTime(), 0), false, 'Monday');
  assert.strictEqual(L.correlationDue(new Date(2026, 10, 8, 23, 0).getTime(), sundayStart + 5 * 3_600_000), false, 'already ran today');
  assert.strictEqual(L.correlationDue(new Date(2026, 10, 8, 23, 0).getTime(), sundayStart - 86_400_000), true, 'last run was a week ago');
});
