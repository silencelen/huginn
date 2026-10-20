'use strict';
// "Load earlier" over a message that was queued above the page seam and
// delivered below it.
//
// ⚠ A HISTORY PAGE CALLED A DELIVERED MESSAGE "STILL WAITING". A message typed
// mid-turn is an `enqueue` record, and minutes of tool output later a `remove`
// or `dequeue` delivers it. The cold tail draws it at its delivery point. When
// the reader then pages back with `until`, that page holds the enqueue but not
// the delivery, so it kept the message `queued` and floated it to its bottom —
// and the client prepends pages with no reconciliation, so the view showed the
// message twice, one copy permanently badged "queued" above the newer page.
// The delivery is below the page; the page below is where it is drawn.
//
// The client is simulated the way the shells do it: a cold open, offset polls
// merged with clearDelivered, then Load earlier with `until=windowStart`
// prepended in front (TranscriptMerge.kt).
//
// SAFETY: scratch files only.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { readTranscript } = require('../lib/transcript');

const L = (o) => `${JSON.stringify(o)}\n`;
const MSG = 'please also run the linter';

/** TranscriptMerge.kt's clearDelivered. */
function clearDelivered(events, delivered) {
  if (!delivered.length) return events;
  const rest = delivered.slice();
  return events.map((ev) => {
    if (!ev.queued) return ev;
    const i = rest.indexOf(ev.text);
    if (i < 0) return ev;
    rest.splice(i, 1);
    const { queued, ...plain } = ev;
    return plain;
  });
}
const mergeTail = (view, page) => ({ ...view, events: clearDelivered(view.events, page.deliveredQueued).concat(page.events) });
const prepend = (view, older) => ({ ...view, events: older.events.concat(view.events), windowStart: older.windowStart });

/**
 * A long session: plenty of history, then a message queued at the start of a
 * tool-heavy turn and delivered at its end — far enough apart that the cold
 * tail begins between the two.
 */
function session(delivery) {
  let t = Date.parse('2026-10-19T10:00:00Z');
  const ts = () => new Date(t += 1000).toISOString();
  let out = '';
  for (let i = 0; i < 150; i++) {
    out += L({ type: 'user', timestamp: ts(), message: { content: `earlier message ${i}` } });
    out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: `reply ${i} ${'x'.repeat(2000)}` }] } });
  }
  out += L({ type: 'user', timestamp: ts(), message: { content: 'start the long build' } });
  out += L({ type: 'queue-operation', operation: 'enqueue', timestamp: ts(), content: MSG });
  for (let i = 0; i < 30; i++) {
    out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'tool_use', id: `tu${i}`, name: 'Bash', input: { command: `step ${i}` } }] } });
    out += L({ type: 'user', timestamp: ts(), message: { content: [{ type: 'tool_result', tool_use_id: `tu${i}`, content: 'o'.repeat(10000) }] } });
  }
  if (delivery === 'remove') {
    out += L({ type: 'queue-operation', operation: 'remove', timestamp: ts(), content: MSG });
    for (let i = 0; i < 6; i++) out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: `final ${i}` }] } });
  } else if (delivery === 'dequeue') {
    // The turn ends, the queue drains into an idle Claude, and Claude Code
    // writes the drained text as an ordinary user record.
    out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: 'build done' }] } });
    out += L({ type: 'queue-operation', operation: 'dequeue', timestamp: ts() });
    out += L({ type: 'user', timestamp: ts(), message: { content: MSG } });
    for (let i = 0; i < 6; i++) out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: `lint ${i}` }] } });
  } else {
    // Still waiting: the turn is still running at the live end.
    for (let i = 0; i < 6; i++) out += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: `still going ${i}` }] } });
  }
  return out;
}

function scratch(body) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-histq-'));
  const file = path.join(dir, 's.jsonl');
  fs.writeFileSync(file, body);
  return { dir, file };
}
const copies = (events) => events.filter((e) => e.kind === 'user' && e.text === MSG);

for (const delivery of ['remove', 'dequeue']) {
  test(`a message queued above the seam and delivered below it is drawn once (${delivery})`, () => {
    const { dir, file } = scratch(session(delivery));
    try {
      const raw = fs.readFileSync(file);
      let view = readTranscript(file, { limit: 400 });   // the cold open
      const enqAt = raw.indexOf('"enqueue"');
      const delAt = raw.indexOf(`"${delivery}"`);
      assert.ok(enqAt < view.windowStart && delAt > view.windowStart,
        `precondition: the pair straddles the tail's seam (${enqAt} < ${view.windowStart} < ${delAt})`);
      assert.equal(1, copies(view.events).length, 'precondition: the tail draws it, delivered');

      let offset = view.nextOffset;
      const poll = (text) => {
        fs.appendFileSync(file, L({ type: 'assistant', timestamp: new Date().toISOString(), message: { content: [{ type: 'text', text } ] } }));
        const page = readTranscript(file, { offset });
        view = mergeTail(view, page);
        offset = page.nextOffset;
      };
      poll('later 0');

      const older = readTranscript(file, { until: view.windowStart, limit: 400 });
      assert.equal(0, copies(older.events).filter((e) => e.queued).length,
        'the older page does not call a delivered message waiting');
      view = prepend(view, older);
      poll('later 1');

      const shown = copies(view.events);
      assert.equal(1, shown.length, `one copy in the merged view, not ${shown.length}`);
      assert.equal(undefined, shown[0].queued, 'and it is not badged queued');
      // Where the whole conversation, read at once, puts it.
      const whole = readTranscript(file, { offset: 0, limit: 100000 }).events;
      const after = (events) => events[events.indexOf(copies(events)[0]) - 1];
      assert.equal(after(whole).kind, after(view.events).kind, 'at its delivery point');
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
  });
}

test('a message still waiting at the live end keeps its badge on the page that holds it', () => {
  const { dir, file } = scratch(session('none'));
  try {
    const tail = readTranscript(file, { limit: 400 });
    const older = readTranscript(file, { until: tail.windowStart, limit: 400 });
    const mine = copies(older.events);
    assert.equal(1, mine.length);
    assert.equal(true, mine[0].queued, 'nothing delivered it, so it is still waiting');
    assert.equal(mine[0], older.events[older.events.length - 1], 'and it waits at the bottom of its page');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('a pair that both sit inside one history page is drawn there, delivered, as before', () => {
  let t = Date.parse('2026-10-19T10:00:00Z');
  const ts = () => new Date(t += 1000).toISOString();
  let body = '';
  body += L({ type: 'user', timestamp: ts(), message: { content: 'first' } });
  body += L({ type: 'queue-operation', operation: 'enqueue', timestamp: ts(), content: MSG });
  body += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: 'working' }] } });
  body += L({ type: 'queue-operation', operation: 'remove', timestamp: ts(), content: MSG });
  body += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: 'done' }] } });
  const seam = Buffer.byteLength(body);
  for (let i = 0; i < 40; i++) body += L({ type: 'assistant', timestamp: ts(), message: { content: [{ type: 'text', text: `more ${i}` }] } });
  const { dir, file } = scratch(body);
  try {
    const page = readTranscript(file, { until: seam, limit: 400 });
    const mine = copies(page.events);
    assert.equal(1, mine.length);
    assert.equal(undefined, mine[0].queued);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
