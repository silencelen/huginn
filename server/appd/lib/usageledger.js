'use strict';
// The daemon's own token ledger: what `GET /v1/usage` serves instead of ccusage.
//
// ccusage answers "how much did today cost?" by walking EVERY transcript under
// ~/.claude/projects on every call. On a server where that tree is gigabytes it
// takes minutes, saturates the disk, and times out — which the Usage tab showed
// as an error. This module keeps a per-day ledger on disk and feeds it
// INCREMENTALLY: each transcript is read only past the byte offset it was last
// read to, and a sweep looks only at files that changed recently or grew. The
// whole tree is never walked for its contents; a sweep costs a `stat` per file
// plus the bytes that are new.
//
// Everything here is pure except the small I/O surface: `load`/`save` for the
// ledger file, `scanFile` for one transcript, `sweep` for a tree. The daemon
// owns scheduling; the tests drive these directly.
//
// SHAPES (verified against Claude Code 2.1.316):
//
//   An `assistant` record carries `message.model`, `message.id` and
//   `message.usage` {input_tokens, output_tokens, cache_creation_input_tokens,
//   cache_read_input_tokens, cache_creation: {ephemeral_5m_input_tokens,
//   ephemeral_1h_input_tokens}}. One API message is written as SEVERAL assistant
//   records — one per content block (`apiBlockIndex` 0, 1, …) — each repeating
//   the same `message.id` and the same usage. The ledger counts a message id
//   once. Subagent records (`isSidechain: true`, and the files under
//   `<session>/subagents/`) are real spend and are counted. A record with
//   `isApiErrorMessage: true` (model `<synthetic>`, zero usage) is not.
//
//   `api-request` records hold the whole outgoing request — 40–150 KB each and
//   most of a transcript's bytes. They embed earlier assistant messages, so a
//   naive substring test for `"type":"assistant"` would match them. They are
//   skipped on the raw line, before any JSON.parse, by their leading
//   `{"type":"api-request"`.
//
//   Headless chats (`claude -p`, the daemon's own Chats) write transcripts into
//   the same tree, keyed by the daemon's working directory, so their spend is
//   counted by the same sweep. The chat store's `messages.jsonl` keeps only the
//   `result` event's `costUsd`, not tokens, and ingesting it as well would count
//   the same API messages twice. So: transcripts only, one source of truth.
//
// DAYS are the host's local calendar days, the same bucketing `ccusage daily`
// uses by default, so the weekly correlation compares like with like.

const fs = require('node:fs');
const path = require('node:path');
const { priceTokens, bucketKey, splitKey } = require('./pricing');

const LEDGER_VERSION = 1;
/** Bytes read per `readSync`; an api-request line can be 150 KB, so a chunk holds a few. */
const CHUNK = 256 * 1024;
/** Message ids remembered per file for de-duplication. Blocks of one message are consecutive. */
const SEEN_RING = 64;
/** Days kept in the ledger. The tab shows 7; the correlation compares 7; a month is slack. */
const KEEP_DAYS = 35;
/** A file entry not seen by a sweep for this long is forgotten (the transcript was deleted or archived). */
const FORGET_FILE_MS = 30 * 86_400_000;
/** The default "recently changed" window a sweep looks at. */
const DEFAULT_SINCE_MS = 10 * 60 * 1000;
/** How deep under a projects root transcripts live: <slug>/<id>.jsonl, <slug>/<id>/subagents/*.jsonl and <slug>/<id>/subagents/workflows/<wf>/*.jsonl (Claude Code 2.1.316). */
const MAX_DEPTH = 6;

const RAW_ASSISTANT = Buffer.from('"type":"assistant"');
const RAW_USAGE = Buffer.from('"usage"');
const RAW_API_REQUEST = Buffer.from('"api-request"');

function emptyLedger() {
  return { v: LEDGER_VERSION, days: {}, files: {} };
}

/** Read the ledger file; a missing or corrupt file is an empty ledger (the next sweep rebuilds). */
function load(file) {
  try {
    const d = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!d || typeof d !== 'object' || d.v !== LEDGER_VERSION) return emptyLedger();
    return { v: LEDGER_VERSION, days: d.days || {}, files: d.files || {} };
  } catch {
    return emptyLedger();
  }
}

/** tmp + rename: a daemon killed mid-write leaves the old ledger, never a truncated one. */
function save(file, ledger) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(ledger), { mode: 0o600 });
  fs.renameSync(tmp, file);
}

/** 'YYYY-MM-DD' in the host's time zone. */
function localDay(d) {
  const t = d instanceof Date ? d : new Date(d);
  if (Number.isNaN(t.getTime())) return null;
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
}

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : 0; }

function emptyBucket() {
  return { input: 0, output: 0, cacheCreation: 0, cacheCreation5m: 0, cacheCreation1h: 0, cacheRead: 0, messages: 0 };
}

/**
 * Count one transcript record into the ledger.
 *
 * @param ledger the ledger to mutate
 * @param rec    a parsed transcript record
 * @param day    'YYYY-MM-DD' to book it under; defaults to the record's own timestamp, local
 * @param seen   optional ring (array, mutated) of message ids already counted for this source
 * @returns true when the record added tokens, false when it was not a countable message
 */
function ingestRecord(ledger, rec, day, seen) {
  if (!rec || rec.type !== 'assistant' || rec.isApiErrorMessage) return false;
  const m = rec.message;
  if (!m || typeof m !== 'object' || !m.usage || typeof m.usage !== 'object') return false;
  const model = typeof m.model === 'string' ? m.model : '';
  if (!model || model === '<synthetic>') return false;
  const id = typeof m.id === 'string' ? m.id : null;
  if (id && Array.isArray(seen)) {
    if (seen.includes(id)) return false;
    seen.push(id);
    if (seen.length > SEEN_RING) seen.splice(0, seen.length - SEEN_RING);
  }
  const u = m.usage;
  const input = num(u.input_tokens);
  const output = num(u.output_tokens);
  const cacheCreation = num(u.cache_creation_input_tokens);
  const cacheRead = num(u.cache_read_input_tokens);
  const c5m = u.cache_creation && typeof u.cache_creation === 'object' ? num(u.cache_creation.ephemeral_5m_input_tokens) : 0;
  const c1h = u.cache_creation && typeof u.cache_creation === 'object' ? num(u.cache_creation.ephemeral_1h_input_tokens) : 0;
  if (input + output + cacheCreation + cacheRead === 0) return false;
  const when = day || localDay(rec.timestamp) || localDay(new Date());
  const tsSec = rec.timestamp ? Date.parse(rec.timestamp) / 1000 : NaN;
  const key = bucketKey(model, tsSec);
  const dayRow = ledger.days[when] || (ledger.days[when] = { byModel: {} });
  const b = dayRow.byModel[key] || (dayRow.byModel[key] = emptyBucket());
  b.input += input;
  b.output += output;
  b.cacheCreation += cacheCreation;
  // The split never exceeds the total it splits; a record that disagrees is read as unsplit.
  if (c5m + c1h <= cacheCreation) { b.cacheCreation5m += c5m; b.cacheCreation1h += c1h; }
  b.cacheRead += cacheRead;
  b.messages += 1;
  return true;
}

/** Is this raw line worth parsing? Cheap byte tests, the api-request skip first. */
function wantLine(line) {
  if (line.length < 64) return false;
  // The record type is the first key Claude Code writes, so the skip is a test on
  // the head alone — never a scan of the 150 KB behind it.
  if (line.subarray(0, 40).indexOf(RAW_API_REQUEST) !== -1) return false;
  if (line.indexOf(RAW_ASSISTANT) === -1) return false;
  if (line.indexOf(RAW_USAGE) === -1) return false;
  return true;
}

/**
 * Read one transcript past its stored offset and count what is new.
 *
 * Only whole lines are consumed: a half-written last line (Claude Code appends
 * in pieces) is left for the next scan, and the offset stops before it. A file
 * shorter than its offset was replaced — it is re-read from the start.
 *
 * Async so a long backfill can yield between chunks; the reads themselves are
 * synchronous and small.
 *
 * @returns {bytesRead, lines, counted}
 */
async function scanFile(ledger, file, { now = Date.now(), yieldEvery = 4 } = {}) {
  const out = { bytesRead: 0, lines: 0, counted: 0 };
  let st;
  try { st = fs.statSync(file); } catch { return out; }
  const entry = ledger.files[file] || (ledger.files[file] = { offset: 0, seen: [] });
  if (!Array.isArray(entry.seen)) entry.seen = [];
  entry.seenAt = now;
  entry.mtimeMs = st.mtimeMs;
  if (st.size < entry.offset) { entry.offset = 0; entry.seen = []; }
  if (st.size === entry.offset) return out;

  let fd;
  try { fd = fs.openSync(file, 'r'); } catch { return out; }
  try {
    let pos = entry.offset;
    let carry = Buffer.alloc(0);
    let chunks = 0;
    const end = st.size;
    while (pos < end) {
      const buf = Buffer.alloc(Math.min(CHUNK, end - pos));
      const n = fs.readSync(fd, buf, 0, buf.length, pos);
      if (n <= 0) break;
      pos += n;
      out.bytesRead += n;
      const chunk = carry.length ? Buffer.concat([carry, buf.subarray(0, n)]) : buf.subarray(0, n);
      let at = 0;
      for (;;) {
        const nl = chunk.indexOf(0x0a, at);
        if (nl === -1) break;
        const line = chunk.subarray(at, nl);
        at = nl + 1;
        out.lines += 1;
        // Consumed up to and including this newline.
        entry.offset = pos - chunk.length + at;
        if (!wantLine(line)) continue;
        let d;
        try { d = JSON.parse(line.toString('utf8')); } catch { continue; }
        if (ingestRecord(ledger, d, null, entry.seen)) out.counted += 1;
      }
      carry = Buffer.from(chunk.subarray(at));
      if (++chunks % yieldEvery === 0) await new Promise((r) => setImmediate(r));
    }
  } finally {
    try { fs.closeSync(fd); } catch { /* closed */ }
  }
  return out;
}

/** Every *.jsonl under a projects root, with its stat, to a bounded depth. */
function listTranscripts(root, depth = 0, out = []) {
  let names;
  try { names = fs.readdirSync(root, { withFileTypes: true }); } catch { return out; }
  for (const e of names) {
    const p = path.join(root, e.name);
    if (e.isDirectory()) {
      if (depth < MAX_DEPTH) listTranscripts(p, depth + 1, out);
    } else if (e.isFile() && e.name.endsWith('.jsonl')) {
      try { out.push({ file: p, st: fs.statSync(p) }); } catch { /* raced away */ }
    }
  }
  return out;
}

/**
 * Bring the ledger up to date from the transcript trees.
 *
 * A file is read when it changed within `sinceMs`, when it is known and grew past
 * its stored offset, or — for a file this ledger has never seen — when it
 * changed within `backfillMs` (the daemon's first sweep after a start, so the
 * week fills in; 0 otherwise). Nothing else is opened. Entries for files that
 * are gone or unseen for a month, and days older than KEEP_DAYS, are dropped.
 *
 * @returns {files: stat'ed, candidates: opened, scanned: read (had new bytes), bytesRead, counted, ms}
 */
async function sweep(ledger, roots, { sinceMs = DEFAULT_SINCE_MS, backfillMs = 0, now = Date.now() } = {}) {
  const t0 = Date.now();
  const stats = { files: 0, candidates: 0, scanned: 0, bytesRead: 0, counted: 0, ms: 0 };
  const present = new Set();
  for (const root of roots) {
    for (const { file, st } of listTranscripts(root)) {
      stats.files += 1;
      present.add(file);
      const entry = ledger.files[file];
      const age = now - st.mtimeMs;
      const recent = age <= sinceMs;
      const grew = entry ? st.size !== entry.offset : false;
      const backfill = !entry && backfillMs > 0 && age <= backfillMs;
      if (!recent && !grew && !backfill) {
        if (entry) entry.seenAt = now;
        continue;
      }
      const r = await scanFile(ledger, file, { now });
      stats.candidates += 1;
      if (r.bytesRead) stats.scanned += 1;
      stats.bytesRead += r.bytesRead;
      stats.counted += r.counted;
    }
  }
  for (const [file, entry] of Object.entries(ledger.files)) {
    if (!present.has(file) && now - (entry.seenAt || 0) > FORGET_FILE_MS) delete ledger.files[file];
  }
  const cutoff = localDay(new Date(now - KEEP_DAYS * 86_400_000));
  for (const day of Object.keys(ledger.days)) if (day < cutoff) delete ledger.days[day];
  stats.ms = Date.now() - t0;
  return stats;
}

/** One day's totals plus its priced cost, in the row shape the apps decode (lib/usage normalizeDay). */
function dayRow(date, row) {
  const byModel = (row && row.byModel) || {};
  let input = 0; let output = 0; let cacheCreate = 0; let cacheRead = 0; let messages = 0;
  const priced = {};
  for (const [key, b] of Object.entries(byModel)) {
    input += b.input || 0;
    output += b.output || 0;
    cacheCreate += b.cacheCreation || 0;
    cacheRead += b.cacheRead || 0;
    messages += b.messages || 0;
    const c5m = b.cacheCreation5m || 0;
    const c1h = b.cacheCreation1h || 0;
    priced[key] = {
      input: b.input || 0,
      output: b.output || 0,
      cacheRead: b.cacheRead || 0,
      cacheCreation5m: c5m,
      cacheCreation1h: c1h,
      cacheCreationUnsplit: Math.max(0, (b.cacheCreation || 0) - c5m - c1h),
    };
  }
  const cost = priceTokens(priced);
  return {
    date,
    inputTokens: input,
    outputTokens: output,
    cacheCreationTokens: cacheCreate,
    cacheReadTokens: cacheRead,
    totalTokens: input + output + cacheCreate + cacheRead,
    costUsd: cost.usd,
    messages,
    models: cost.byModel.map((r) => r.model),
    unpricedTokens: cost.unpricedTokens,
  };
}

/** The last `days` local days ending at `today`, oldest first. */
function dayWindow(today, days) {
  const [y, m, d] = String(today).split('-').map(Number);
  const out = [];
  for (let i = days - 1; i >= 0; i--) out.push(localDay(new Date(y, m - 1, d - i)));
  return out;
}

/**
 * The summary `GET /v1/usage` serves: the SAME shape lib/usage's summarizeUsage
 * built from ccusage — {today, week:{days, totalTokens, inputTokens, outputTokens,
 * cacheReadTokens, cacheCreationTokens, costUsd}, daily:[rows]} — so the apps
 * need no change. `daily` holds only days with spend, like ccusage's output;
 * `week.days` is their count.
 */
function summarize(ledger, today = localDay(new Date()), days = 7) {
  const rows = [];
  for (const date of dayWindow(today, days)) {
    const row = ledger.days[date];
    if (!row || !Object.keys(row.byModel || {}).length) continue;
    rows.push(dayRow(date, row));
  }
  const sum = (k) => rows.reduce((a, r) => a + (r[k] || 0), 0);
  return {
    today: rows.find((r) => r.date === today) ?? null,
    week: {
      days: rows.length,
      totalTokens: sum('totalTokens'),
      inputTokens: sum('inputTokens'),
      outputTokens: sum('outputTokens'),
      cacheReadTokens: sum('cacheReadTokens'),
      cacheCreationTokens: sum('cacheCreationTokens'),
      costUsd: Math.round(sum('costUsd') * 1e6) / 1e6,
    },
    daily: rows,
  };
}

/** Plain per-day totals of the ledger (unpriced), for the correlation file. */
function dayTotals(ledger, date) {
  const row = ledger.days[date];
  if (!row) return null;
  const r = dayRow(date, row);
  return {
    inputTokens: r.inputTokens,
    outputTokens: r.outputTokens,
    cacheCreationTokens: r.cacheCreationTokens,
    cacheReadTokens: r.cacheReadTokens,
    totalTokens: r.totalTokens,
    costUsd: r.costUsd,
    messages: r.messages,
  };
}

/**
 * Compare `ccusage daily --json` with the ledger for the same days.
 *
 * @returns {at, days: {date: ccusage totals}, ledger: {date: our totals}, driftPercent: {date: pct}}
 *   driftPercent = (ledger - ccusage) / ccusage * 100 on totalTokens, 1 decimal;
 *   null when ccusage has no tokens for the day.
 */
function correlate(ledger, parsed, dates, at = Date.now()) {
  const cc = {};
  for (const d of Array.isArray(parsed && parsed.daily) ? parsed.daily : []) {
    const date = String(d.period ?? d.date ?? '');
    if (!date) continue;
    const input = d.inputTokens ?? 0; const output = d.outputTokens ?? 0;
    const cr = d.cacheCreationTokens ?? 0; const rd = d.cacheReadTokens ?? 0;
    cc[date] = {
      inputTokens: input, outputTokens: output, cacheCreationTokens: cr, cacheReadTokens: rd,
      totalTokens: d.totalTokens ?? (input + output + cr + rd),
      costUsd: d.totalCost ?? d.cost ?? null,
    };
  }
  const out = { at, days: {}, ledger: {}, driftPercent: {} };
  for (const date of dates) {
    const a = cc[date] || null;
    const b = dayTotals(ledger, date);
    out.days[date] = a;
    out.ledger[date] = b;
    const base = a ? a.totalTokens : 0;
    out.driftPercent[date] = base > 0
      ? Math.round(((b ? b.totalTokens : 0) - base) / base * 1000) / 10
      : null;
  }
  return out;
}

/**
 * Is the weekly ccusage correlation due? Sunday at or after 05:00 host time, and
 * not already run since the start of that Sunday (so a restart later in the day
 * does not run it twice, and a missed Sunday catches up as soon as the daemon is
 * up on one).
 */
function correlationDue(now, lastAt) {
  const t = new Date(now);
  if (t.getDay() !== 0 || t.getHours() < 5) return false;
  const sundayStart = new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
  return !(lastAt >= sundayStart);
}

/** Model ids present in a ledger day, era keys folded back to the id (for the tests and the correlation). */
function modelsOf(ledger, date) {
  const row = ledger.days[date];
  return row ? [...new Set(Object.keys(row.byModel).map((k) => splitKey(k).model))] : [];
}

module.exports = {
  emptyLedger, load, save, localDay, ingestRecord, wantLine, scanFile, listTranscripts, sweep,
  summarize, dayRow, dayWindow, dayTotals, correlate, correlationDue, modelsOf,
  LEDGER_VERSION, SEEN_RING, KEEP_DAYS, DEFAULT_SINCE_MS, MAX_DEPTH,
};
