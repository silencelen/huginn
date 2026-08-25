'use strict';
// The report channel: what a run says, and what a person ends up reading.
//
// Every test here is pure — no daemon, no socket, no clock. That is the point of
// lib/rounds owning the parser: the defects below were all found by reading text
// into a function, and all of them shipped because nothing ever read text into
// that function.
//
// The shape of all of them is the same, and it is worth saying once: EVERY
// failure in this file made a run look BETTER than it was. A destroyed action
// report became "unknown", a forged clean report silenced the notification
// entirely, a 500-item finding read as 20, and a terminal was told to print ALL
// CLEAR over a DISK FULL headline. Not one made a healthy round look broken.

const { test } = require('node:test');
const assert = require('node:assert');
const R = require('../lib/rounds');

const F = '```';
const ESC = '\x1b';

/** A report block, written the way a run writes one. */
function block(obj, pre = '', post = '') {
  return `${pre}\n${F}huginn-report\n${JSON.stringify(obj)}\n${F}\n${post}`;
}

// ------------------------------------------------------- the contract itself

test('the contract quoted back is not a report', () => {
  // REPORT_CONTRACT contains a syntactically complete example: status ok,
  // goalMet true, one item. It used to parse, so a run that wrote its real
  // report and THEN quoted the instructions to explain itself had the
  // placeholder win under "the last block wins" — and because the forged status
  // was `ok`, shouldNotify() returned false. A real `action` report vanished in
  // total silence behind a clean green row.
  assert.equal(R.parseReport(R.REPORT_CONTRACT), null);
});

test('a real report followed by the contract still wins', () => {
  const real = { status: 'action', headline: 'root fs 99% full', goalMet: false, items: [] };
  const text = block(real, 'here is what I found') + '\n' + R.REPORT_CONTRACT;
  const p = R.parseReport(text);
  assert.ok(p, 'the real report was thrown away');
  assert.equal(p.headline, 'root fs 99% full');
  assert.equal(p.status, 'action');
  // And the thing that actually mattered: it still reaches somebody.
  assert.equal(R.shouldNotify('attention', R.effectiveStatus(p)), true);
});

test('the last REAL block wins over an earlier draft', () => {
  // The control for the test above. Skipping the contract must not turn into
  // skipping backwards past a corrected report to an abandoned draft.
  const text =
    block({ status: 'ok', headline: 'first draft', items: [] }) +
    block({ status: 'action', headline: 'corrected', items: [] });
  assert.equal(R.parseReport(text).headline, 'corrected');
});

// ------------------------------------------------------------ fence handling

test('an item may quote a fenced command', () => {
  // The report contract asks every item for "the next step", and for an ops
  // round the next step is a command. The old non-greedy regex stopped at the
  // first ``` ANYWHERE — including inside a JSON string — so writing the most
  // useful possible item destroyed the whole report.
  const rep = {
    status: 'action',
    headline: 'PBS sync has been failing for 6 days',
    goalMet: false,
    items: [{
      title: 'PBS sync stalled',
      detail: 'last success 2026-08-19',
      suggest: `run:\n${F}bash\nproxmox-backup-manager sync-job run s-1\n${F}`,
    }],
  };
  const p = R.parseReport(block(rep, 'I looked at the sync jobs.'));
  assert.ok(p, 'the report was destroyed by a fence inside a string');
  assert.equal(p.status, 'action');
  assert.equal(p.headline, 'PBS sync has been failing for 6 days');
  assert.equal(p.items.length, 1);
  assert.match(p.items[0].suggest, /proxmox-backup-manager/);
  assert.equal(p.malformed, false);
});

test('a headline may quote a fence', () => {
  const p = R.parseReport(block({
    status: 'attention', headline: `log line: ${F} marker`, goalMet: false, items: [],
  }));
  assert.ok(p, 'a fence in the headline killed the report');
  assert.equal(p.status, 'attention');
});

test('a closing fence must be alone on its line', () => {
  // The rule that makes the two tests above work, stated directly. This is
  // CommonMark's own rule, and it is exactly what separates a real terminator
  // from a quoted one: JSON cannot hold a literal newline inside a string, so a
  // quoted ``` is always mid-line.
  assert.equal(R.reportBlocks(`${F}huginn-report\n{"a":1}\n${F}\n`).length, 1);
  assert.equal(R.reportBlocks(`${F}huginn-report\n{"a":"x ${F} y"}\n${F}\n`).length, 1);
  assert.equal(R.reportBlocks(`${F}huginn-report\n{"a":1}\n`).length, 0, 'unterminated is not a block');
});

test('a run cut off mid-block does not put JSON debris in the notification', () => {
  // An unterminated block used to survive the fallback's fence stripper (the
  // same non-greedy regex), so the operator was buzzed with half a JSON object
  // as the notification text.
  const text = `Checking the backups now.\n${F}huginn-report\n{"status":"action","headline":"PBS sync fail`;
  assert.equal(R.parseReport(text), null);
  const f = R.fallbackReport(text);
  assert.equal(f.headline, 'Checking the backups now.');
  assert.ok(!f.headline.includes('"status"'), `raw JSON reached the operator: ${f.headline}`);
  assert.equal(f.malformed, true);
});

test('a fence quoted inside a closed block does not leave half of it as prose', () => {
  const text = `Ran the scan.\n${F}huginn-report\n{"headline":"saw ${F} in a log"\n${F}\nDone.`;
  const f = R.fallbackReport(text);   // deliberately malformed JSON: no closing brace
  // ⚠ Asserted as the WHOLE string, not as "the JSON is absent". The first
  // version of this test checked !includes('"headline"') and passed against the
  // broken stripper too — because the old non-greedy regex happened to eat the
  // opening fence AND the word `headline` before stopping at the quoted fence,
  // leaving `Ran the scan. in a log" ``` Done.` as the notification. A test that
  // passes both ways is not a test, and this one only showed it when it was run
  // against the previous build.
  assert.equal(f.headline, 'Ran the scan. Done.');
});

// -------------------------------------------------------- control characters

test('an escape sequence cannot rewrite the line it is printed on', () => {
  // Report text is written by a model, and a model writes what it READ — a log
  // line, a fetched page, a filename. A terminal executes some of what it is
  // handed, so `Nightly scan` + ESC [2K + CR + `ALL CLEAR` erased its own line
  // and reprinted: `huginn rounds` showed ALL CLEAR for a round holding a DISK
  // FULL headline and two action items.
  const p = R.parseReport(block({
    status: 'action',
    headline: `DISK FULL${ESC}[2K\rALL CLEAR`,
    items: [{ title: `root fs${ESC}[2K\rfine`, detail: 'x', suggest: 'y' }],
  }));
  assert.ok(!p.headline.includes(ESC), 'ESC survived into the headline');
  assert.ok(!p.headline.includes('\r'), 'CR survived into the headline');
  assert.ok(!p.items[0].title.includes(ESC), 'ESC survived into an item title');
  assert.match(p.headline, /DISK FULL/);
  assert.match(p.headline, /ALL CLEAR/, 'the text is kept, only the cursor move is not');
});

test('a newline in a short label does not become two findings', () => {
  const p = R.parseReport(block({
    status: 'attention', headline: 'one\ntwo', items: [{ title: 'a\nb', detail: 'x', suggest: '' }],
  }));
  assert.equal(p.headline, 'one two');
  assert.equal(p.items[0].title, 'a b');
});

test('a next step may still run to several lines', () => {
  // The other half of the rule: detail and suggest are free text and a command
  // block is a legitimate shape for them. Only the cursor-moving characters go.
  const p = R.parseReport(block({
    status: 'action', headline: 'h',
    items: [{ title: 't', detail: 'line one\nline two', suggest: `do:\nthis${ESC}[31m` }],
  }));
  assert.equal(p.items[0].detail, 'line one\nline two');
  assert.ok(!p.items[0].suggest.includes(ESC));
  assert.match(p.items[0].suggest, /do:\nthis/);
});

// ------------------------------------------------------------- item counting

test('a capped report remembers how many there really were', () => {
  // 500 findings were shown as "20 items" on the line directly under a headline
  // saying 500 — two numbers on one screen, and the one an operator acts on was
  // the wrong one.
  const items = Array.from({ length: 500 }, (_, i) => ({ title: `item ${i}`, detail: '', suggest: '' }));
  const p = R.parseReport(block({ status: 'action', headline: '500 things need you', items }));
  assert.equal(p.items.length, R.MAX_ITEMS);
  assert.equal(p.itemsTotal, 500);
});

test('an uncapped report says the same number twice', () => {
  const p = R.parseReport(block({
    status: 'attention', headline: 'h', items: [{ title: 'a' }, { title: 'b' }],
  }));
  assert.equal(p.items.length, 2);
  assert.equal(p.itemsTotal, 2);
});

test('every report shape carries itemsTotal', () => {
  // So a renderer can read one field without asking which constructor made it.
  assert.equal(R.fallbackReport('prose').itemsTotal, 0);
  assert.equal(R.errorReport('boom').itemsTotal, 0);
});

// ----------------------------------------------------------------- controls

test('an ordinary report is unchanged', () => {
  const p = R.parseReport(block({
    status: 'ok', headline: 'nothing to report', goalMet: true, items: [],
  }, 'I checked everything.'));
  assert.deepEqual(
    { status: p.status, headline: p.headline, goalMet: p.goalMet, items: p.items, malformed: p.malformed },
    { status: 'ok', headline: 'nothing to report', goalMet: true, items: [], malformed: false },
  );
  assert.equal(R.shouldNotify('attention', R.effectiveStatus(p)), false);
});

test('text with no block at all is still a fallback, not silence', () => {
  const f = R.fallbackReport('I could not reach the host.');
  assert.equal(f.status, 'unknown');
  assert.equal(f.headline, 'I could not reach the host.');
  assert.equal(R.shouldNotify('attention', f.status), true);
});
