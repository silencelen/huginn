'use strict';
// Reads a Claude Code transcript (~/.claude/projects/<slug>/<session>.jsonl) and
// normalizes it into the event stream the app renders.
//
// This is the primary render path for BOTH surfaces: a phone chat and a tmux
// session both have a transcript here, so thinking, tool calls, subagent output
// and workflow runs come from structured data rather than from scraping a TUI.
// Screen capture is kept only for live interaction, not for content.
//
// Transcripts are append-only, so tailing is a byte offset: callers pass the
// `nextOffset` from the previous response and get only what is new.

const fs = require('node:fs');

// A cold open reads at most this much from the END of the file. A long session's
// transcript reaches many MB and a phone neither needs nor wants the head of it.
const TAIL_BYTES = 256 * 1024;
// Fewer events than this in the tail window means the window was mostly one
// giant row (an embedded image) and must grow. Small on purpose: it only has
// to distinguish "a screenful of conversation" from "half a base64 blob".
const MIN_TAIL_EVENTS = 12;

/**
 * The record types readTranscript has a case for. Every other type falls
 * through to `default: continue` and draws nothing.
 */
const HANDLED_TYPES = new Set(['queue-operation', 'ai-title', 'permission-mode', 'mode', 'user', 'assistant', 'system']);
const TYPE_FIRST = Buffer.from('{"type":"');

/**
 * The type a record names in its FIRST key, read off the raw bytes of
 * buf[a, b) without decoding or parsing the line; null when the line does not
 * open with `{"type":"<name>"` or the name carries an escape.
 *
 * ⚠ WHY THE BYTES AND NOT JSON.parse. Claude Code 2.1.300 writes every API
 * request it sends into the transcript: `api-request` records of 100 KB to over
 * a megabyte each, beside `api-request-blob` and `api-request-shape`. In real
 * transcripts written by 2.1.300 and 2.1.302 they are 25 to 86% of the bytes,
 * and one history page of 400 events had to read a 64 MB window to find them.
 * Every one of those records was decoded and JSON.parsed so that the switch
 * below could send it to `default: continue`. All of them write `type` first, so
 * the first bytes of the line say what the record is.
 *
 * Exact for a line JSON.stringify wrote: the first key of an object it writes is
 * the key `JSON.parse` reports, because a stringified object never repeats one.
 */
function leadingType(buf, a, b) {
  const at = a + TYPE_FIRST.length;
  if (b - at < 2 || buf[a] !== 0x7b || buf.compare(TYPE_FIRST, 0, TYPE_FIRST.length, a, at) !== 0) return null;
  const name = buf.subarray(at, Math.min(b, at + 65));
  const q = name.indexOf(0x22);
  if (q === -1) return null;
  if (name.subarray(0, q).indexOf(0x5c) !== -1) return null;   // an escape: let JSON.parse read it
  return name.toString('latin1', 0, q);
}

/** A record readTranscript would parse only to drop: see [leadingType]. */
function ignoredRecord(buf, a, b) {
  const t = leadingType(buf, a, b);
  return t !== null && !HANDLED_TYPES.has(t);
}

/** Parse failed: the record is skipped exactly as a `JSON.parse` throw skipped it. */
const UNPARSEABLE = Symbol('unparseable');
/**
 * How many bytes of records one read keeps parsed between the window sizes it
 * tries. Past it a record is parsed again on every size, which is what all of
 * them used to be; the cap bounds what a pathological history page can hold in
 * memory at once.
 */
const PARSED_KEEP_BYTES = 24 * 1024 * 1024;

/**
 * The bytes of one transcript that one read is working through, ending at
 * `end` and grown DOWNWARDS as a cold open tries larger windows.
 *
 * ⚠ THE COST THIS REMOVES. A cold open probes window sizes 256 KB, 512 KB, 1 MB
 * … until one holds enough events, and every probe re-read and re-parsed its
 * whole window from scratch, then the winning size was read and parsed AGAIN to
 * build the answer: about three times the final window, read and parsed, for one
 * page. Here each probe reads only the bytes the previous one did not have, and
 * each record is parsed once however many sizes include it.
 */
function openWindow(path, st, end) {
  // `cap` holds the bytes [lo, end) at its END: cap[i] is byte `capLo + i`.
  // `nls`: where every newline in [lo, end) is, ascending, found once per byte.
  return { path, st, end, lo: end, cap: Buffer.alloc(0), capLo: end, nls: [], parsed: new Map(), keptBytes: 0 };
}

/**
 * The non-empty lines of the file's bytes [s, e) — both inside win — as
 * [from, to) offsets from `s`: exactly the pieces `split('\n')` leaves once the
 * empty ones are dropped, the first and the last included whole or not.
 */
function windowLines(win, s, e) {
  const nls = win.nls;
  let i = 0;
  for (let hi = nls.length; i < hi;) {
    const mid = (i + hi) >> 1;
    if (nls[mid] < s) i = mid + 1; else hi = mid;
  }
  const out = [];
  let a = s;
  for (; i < nls.length && nls[i] < e; i++) {
    if (nls[i] > a) out.push([a - s, nls[i] - s]);
    a = nls[i] + 1;
  }
  if (e > a) out.push([a - s, e - s]);
  return out;
}

/**
 * How much further down a window reserves room when it has to grow, as a
 * multiple of what it needs now. Reserved, not touched: the pages of a buffer
 * nobody has written cost nothing, and growing in place is what saves copying
 * (and first-touching) every byte already read at every doubling.
 */
const WINDOW_RESERVE = 16;

/** win's bytes [start, end), reading only what it does not hold yet. */
function windowBytes(win, start, end) {
  if (start < win.lo) {
    if (start < win.capLo) {
      // Out of room: a bigger buffer, the bytes held so far copied to its end.
      const want = Math.min(win.end, Math.max(win.end - start, (win.end - win.lo) * WINDOW_RESERVE));
      const next = Buffer.allocUnsafe(want);
      const nextLo = win.end - want;
      win.cap.copy(next, win.lo - nextLo, win.lo - win.capLo);
      win.cap = next;
      win.capLo = nextLo;
    }
    readFully(win.path, win.cap, start - win.capLo, win.lo - start, start);
    const found = [];
    for (let i = start - win.capLo, stop = win.lo - win.capLo; ;) {
      const j = win.cap.indexOf(0x0a, i);
      if (j === -1 || j >= stop) break;
      found.push(win.capLo + j);
      i = j + 1;
    }
    win.nls = win.nls.length ? found.concat(win.nls) : found;
    win.lo = start;
  }
  return win.cap.subarray(start - win.capLo, end - win.capLo);
}

/**
 * Reads `len` bytes at `pos` into buf at `at`. A file that has shrunk under the
 * read leaves ZEROS behind, never what the reused memory held before: the old
 * zero-filled buffer did the same, and it is the bytes after a short read that
 * would otherwise reach a client.
 */
function readFully(path, buf, at, len, pos) {
  const fd = fs.openSync(path, 'r');
  try {
    let got = 0;
    while (got < len) {
      const n = fs.readSync(fd, buf, at + got, len - got, pos + got);
      if (n <= 0) break;
      got += n;
    }
    if (got < len) buf.fill(0, at + got, at + len);
  } finally { fs.closeSync(fd); }
}

/** The record in buf[a, b), which starts at byte `abs` of the file, parsed at most once per read. */
function parseRecord(win, abs, buf, a, b) {
  const hit = win.parsed.get(abs);
  if (hit && hit.len === b - a) return hit.d;
  let d;
  try { d = JSON.parse(buf.toString('utf8', a, b)); } catch { d = UNPARSEABLE; }
  if (win.keptBytes + (b - a) <= PARSED_KEEP_BYTES) {
    win.parsed.set(abs, { len: b - a, d });
    win.keptBytes += b - a;
  }
  return d;
}

/** Trims a tool input down to the one field a human would want to see. */
function digestToolInput(input, limit = 400) {
  if (input == null) return '';
  if (typeof input === 'string') return clip(input, limit);
  if (typeof input !== 'object') return String(input);
  const pick = input.command ?? input.file_path ?? input.pattern ?? input.url ??
    input.query ?? input.prompt ?? input.description ?? input.path;
  if (typeof pick === 'string') return clip(pick, limit);
  try { return clip(JSON.stringify(input), limit); } catch { return ''; }
}

/**
 * AskUserQuestion input, reduced to what a card needs. Defensive throughout:
 * this shape belongs to the CLI and may grow fields; a question we cannot parse
 * falls back to the generic tool card rather than a broken one.
 */
function parseAsk(input) {
  if (!input || !Array.isArray(input.questions) || !input.questions.length) return null;
  const questions = [];
  for (const q of input.questions.slice(0, 4)) {
    if (!q || typeof q.question !== 'string' || !q.question.trim()) continue;
    questions.push({
      question: clip(q.question.trim(), 240),
      header: typeof q.header === 'string' ? clip(q.header, 24) : null,
      multiSelect: q.multiSelect === true,
      options: Array.isArray(q.options)
        ? q.options.slice(0, 6).map((o) => clip(
            typeof o === 'string' ? o : (o && o.label) || '', 80,
          )).filter(Boolean)
        : [],
    });
  }
  return questions.length ? { questions } : null;
}

/**
 * Machine-generated text that Claude Code injects into the conversation
 * (background-task notifications, hook output). It is real input to the model
 * but it is not something a person said, so it is shown as a one-line note
 * rather than a message bubble.
 */
function machineText(s) {
  // The bracketed preamble is the newer wrapper around task notifications:
  // "[SYSTEM NOTIFICATION - NOT USER INPUT] …" followed by the tagged element.
  // Without matching it, the whole notification rendered as a message the user
  // supposedly sent.
  // "[Image: original 1530x2048, displayed at 1494x2000. Multiply…]" is the
  // caption Claude Code writes beside an image it ingested — coordinate-mapping
  // instructions for the MODEL. Rendered as a user bubble it reads like the
  // owner recited camera metadata at their own phone.
  // "[Your previous response had no visible output…]" is Claude Code prodding
  // the model after an empty turn. Found in real transcripts on this host; it
  // rendered as a bubble the owner appeared to have typed.
  //
  // Deliberately a list of KNOWN openings rather than "any bracketed line":
  // this app's own attachment markers ("[Attached image at …]") are bracketed
  // too and ARE the user's message, so a blanket rule would silently swallow
  // every photo and file the owner sends.
  return /^\s*(<(task-notification|system-reminder|command-name|command-message|command-args|local-command)|\[SYSTEM NOTIFICATION|\[Image: original \d+x\d+|\[Your previous response)/.test(s);
}

// ---- tags, found in one pass -------------------------------------------------
//
// ⚠ LAZY TAG PATTERNS ARE QUADRATIC, OR WORSE, IN THE TEXT THEY ARE GIVEN, and
// that text is a transcript record a model wrote: a background agent's report
// is copied into its parent's transcript word for word. `/<x>[\s\S]*?<\/x>/`
// retries from every opening it finds and scans to the end each time the closer
// is missing, and `/<x>\s*([^<]+?)\s*<\/x>/` backtracks through every split of
// a run of spaces: 2,000 spaces after an unclosed `<command-name>` held the
// event loop 3 s, ten thousand would hold it for minutes, and every transcript
// read (each poll, each tick) starts over. These do the same jobs with indexOf.
// A closer that is not after the first opening is not after any later one.

/**
 * The first `open … close` in [s] as [start, end), or null: what
 * `/<open>[\s\S]*?<close>/` finds. `boundary` adds the `\b` after the opening.
 */
function elementSpan(s, open, close, { boundary = false } = {}) {
  let i = s.indexOf(open);
  while (i >= 0 && boundary && /\w/.test(s.charAt(i + open.length))) i = s.indexOf(open, i + 1);
  if (i < 0) return null;
  const j = s.indexOf(close, i + open.length);
  return j < 0 ? null : [i, j + close.length];
}

/** The text inside the first `open … close` in [s], or null. */
function innerText(s, open, close) {
  const span = elementSpan(s, open, close);
  return span ? s.slice(span[0] + open.length, span[1] - close.length) : null;
}

/**
 * The text inside the first `open … close` with no `<` in it (and, with
 * `nonEmpty`, at least one character), or null: the reading of
 * `/<open>\s*([^<]*?)\s*<close>/`, untrimmed.
 */
function plainInner(s, open, close, { nonEmpty = false } = {}) {
  let i = s.indexOf(open);
  while (i >= 0) {
    const start = i + open.length;
    const lt = s.indexOf('<', start);
    if (lt < 0) return null;
    if (s.startsWith(close, lt) && (!nonEmpty || lt > start)) return s.slice(start, lt);
    i = s.indexOf(open, lt);
  }
  return null;
}

/**
 * [s] with every element of the given kinds removed, earliest first: what
 * `.replace(/<a>[\s\S]*?<\/a>|<b>[\s\S]*?<\/b>/g, '')` leaves.
 * @param pairs [[open, close], …]
 */
function stripElements(s, pairs) {
  const next = pairs.map(([open]) => s.indexOf(open));
  let out = '';
  let at = 0;
  for (;;) {
    let k = -1;
    for (let p = 0; p < pairs.length; p++) {
      if (next[p] >= 0 && next[p] < at) next[p] = s.indexOf(pairs[p][0], at);
      if (next[p] >= 0 && (k < 0 || next[p] < next[k])) k = p;
    }
    if (k < 0) break;
    const [open, close] = pairs[k];
    const j = s.indexOf(close, next[k] + open.length);
    if (j < 0) { next[k] = -1; continue; }
    out += s.slice(at, next[k]);
    at = j + close.length;
  }
  return out + s.slice(at);
}

/**
 * Turns Claude Code's slash-command bookkeeping into something worth reading, or
 * into nothing.
 *
 * Running `/model fable` writes THREE separate `user` records: a caveat telling
 * the model to ignore what follows, the command itself wrapped in tags, and the
 * command's stdout. Rendered verbatim they look like three garbled messages the
 * user never sent — which is exactly how it looked in the app.
 *
 * @returns {{kind: string, text: string}|null} null means "show nothing"
 */
function describeMachineText(s) {
  // Pure plumbing aimed at the model, not at a reader.
  if (/^\s*<local-command-caveat/.test(s)) return null;
  if (/^\s*<system-reminder/.test(s)) return null;

  const cmd = plainInner(s, '<command-name>', '</command-name>', { nonEmpty: true });
  if (cmd !== null) {
    const args = (plainInner(s, '<command-args>', '</command-args>') || '').trim();
    const text = args ? `${cmd.trim()} ${args}` : cmd;
    return { kind: 'command', text: text.trim() };
  }

  const out = innerText(s, '<local-command-stdout>', '</local-command-stdout>');
  if (out !== null) {
    const body = stripAnsiText(out).trim();
    return body ? { kind: 'command_result', text: clip(body, 300) } : null;
  }

  // The image-ingestion caption: the Read card above it already says an image
  // was viewed, and coordinate-mapping instructions add nothing for a person.
  if (/^\s*\[Image: original \d+x\d+/.test(s)) return null;

  // A nudge aimed at the model after an empty turn; nothing for a reader to do.
  if (/^\s*\[Your previous response/.test(s)) return null;

  if (/<task-notification/.test(s) || /^\s*\[SYSTEM NOTIFICATION/.test(s)) {
    // The summary is written for exactly this purpose; use it.
    const sum = innerText(s, '<summary>', '</summary>');
    return { kind: 'system', text: sum !== null ? clip(sum.trim().replace(/\s+/g, ' '), 160) : 'background task reported back' };
  }
  return { kind: 'system', text: 'injected input' };
}

/**
 * Whether a `user` record is content a TOOL pushed back into the conversation
 * rather than anything a person sent.
 *
 * A skill — invoked by the `Skill` tool, by a slash command that is backed by
 * one, or auto-loaded — has its ENTIRE instruction body written into the
 * transcript as a `user` record, and nothing in the text marks it: it opens
 * with "Base directory for this skill: …" or straight into the skill's own
 * prose, so every machine-text heuristic in this file sails past it and it
 * rendered as a multi-KB message the owner appeared to have typed. That is the
 * complaint this function answers ("the skill is being printed in the session
 * chat tab as a message from the user").
 *
 * `sourceToolUseID` is the record naming the tool call it came out of, and it
 * is exact: across all 891 transcripts on this host, every one of the 65
 * records carrying it is a Skill body, all 65 are `type: 'user'` with
 * `isMeta: true`, and no other record carries the field at all. Keyed on the
 * field rather than on `isMeta`, which is far broader — an image caption, a
 * `/model` caveat and a resumed session's "Continue from where you left off"
 * are all `isMeta` too, and two of those the reader must keep showing.
 */
function injectedByTool(d) {
  return typeof d.sourceToolUseID === 'string' && d.sourceToolUseID !== '';
}

/**
 * Whether a record is one the CLI recorded as actually coming from the person.
 *
 * `origin.kind === 'human'` accompanies `promptSource` "typed"/"queued"/
 * "suggestion_accepted" and appears on nothing the CLI writes for itself: of
 * the 459 machine-text records on this host (command plumbing, task
 * notifications, image captions) not one carries it. So it settles the
 * otherwise-unanswerable case — a person who literally types "<command-name>"
 * into the chat gets their message, while the CLI's identical-looking
 * bookkeeping still collapses to a chip.
 *
 * Absent from older transcripts, where it reads as false and the content
 * heuristic decides exactly as it did before. That is the safe direction: with
 * no evidence of a human, injected wins.
 */
function typedByHuman(d) {
  return !!(d && d.origin && d.origin.kind === 'human');
}

/**
 * Whether a `user` record is Claude Code's ECHO of a background-task
 * notification it has ALREADY written as a queue record.
 *
 * A task-notification is written twice. First as a `queue-operation` enqueue
 * carrying the `<task-notification>` element; then — only when the queue drains
 * into a Claude that has gone idle — as an ordinary `user` record carrying the
 * same element about 40 ms later. Both drew a note, which is the pairs of
 * identical `system` events the phone review found: same `ts`, same text,
 * adjacent `seq`, twice per finished agent and twice per background command.
 *
 * Keyed on `origin.kind`, the structural tell `peerNote` and `injectedByTool`
 * are keyed on, because the echo has to be told apart from machine text that
 * never went through the queue at all — a slash command, an image caption, a
 * harness nudge — which is the ONLY copy there is and must keep rendering.
 * Exact on this host: of 955 transcripts, 386 `user` records carry
 * `origin.kind === 'task-notification'`, every one of them has an identical
 * enqueue above it, and they are exactly the 386 records whose text opens
 * `<task-notification` — the field and the shape never disagree.
 */
function notificationEcho(d) {
  return !!(d && d.origin && d.origin.kind === 'task-notification');
}

// ------------------------------------------------- another Claude session
//
// Claude Code 2.1.258 lets one session message another: `SendMessage` travels
// process-to-process over `/tmp/cc-socks/<pid>.sock`, identity kernel-verified
// by peer credentials, and AUTO-TRIGGERS A TURN in the recipient with no
// keypress. On this side it lands as three records in a row — a `queue-operation`
// enqueue whose content is a `<cross-session-message …>` element, a `dequeue`
// about 40 ms later, and a `type:"user"` record carrying the same element inside
// a longer safety preamble, with `isMeta:true`, `promptSource:"system"`,
// `userType:"external"` and an `origin:{kind:"peer", name, verifiedPeerPid, …}`.
//
// ⚠ UNTIL NOW BOTH OF THOSE RENDERED AS THE OWNER'S OWN MESSAGES — two bubbles
// for one peer message, the second one carrying the whole "this came from
// another Claude session" paragraph as if the owner had recited it. The same
// family as the skill-body bug, and worse on a Projects surface, where the
// sessions talk to each other constantly and nothing else distinguishes a
// teammate's request from an instruction the person gave.
//
// Keyed STRUCTURALLY on `origin.kind === 'peer'` rather than on the text, for
// the reason `injectedByTool` is keyed on `sourceToolUseID`: the preamble is
// English that a person could type, and the field is written by the harness.
// The queue record has no `origin` to key on, so it is matched on the element
// itself — which is also what pairs the two records, since the enqueue's content
// is exactly the element the `user` record embeds.

/** The `<cross-session-message …>…</cross-session-message>` element (elementSpan). */
const CROSS_TAG = ['<cross-session-message', '</cross-session-message>'];

/**
 * A subagent's report, `<agent-message from="…">…</agent-message>`, with no
 * cross-session wrapper. Its QUEUE copy carries the bare element (the `user`
 * record a moment later says "Another Claude session sent a message:" before
 * it), so it matched nothing here and was drawn as a raw bubble beside the note.
 */
const AGENT_TAG = ['<agent-message', '</agent-message>'];

/** The first element of [tag] in [s] as {text, index}, or null. */
function firstElement(s, [open, close]) {
  const span = elementSpan(s, open, close, { boundary: true });
  return span ? { text: s.slice(span[0], span[1]), index: span[0] } : null;
}

/** The largest peer or summary body sent for a folded card; past it, clipped. */
const FOLD_MAX = 200_000;

// A long paste into Claude Code's prompt is stored wrapped:
//   <pasted_content id="d842">\n…\n</pasted_content id="d842">\n
// The id is four hex digits, repeats across messages, and the closer names its
// opener's, which is what keeps a paste that QUOTES the tag from closing early.
// The clients fold the block into a card; text that is a title or a label
// wants the words without the markup.
const PASTE_OPEN = '<pasted_content id="';
const PASTE_CLOSE = '</pasted_content id="';

/**
 * The id of the paste tag whose literal ends at `at` — 1 to 32 characters that
 * are neither a quote nor a newline, then `">` — and where the tag ends; null
 * when what follows the literal is not that.
 */
function pasteTag(s, at) {
  const lim = Math.min(s.length, at + 33);
  let k = at;
  while (k < lim && s[k] !== '"' && s[k] !== '\n') k++;
  if (k === at || k === lim || s[k] !== '"' || s[k + 1] !== '>') return null;
  return { id: s.slice(at, k), end: k + 2 };
}

/**
 * The text with every paste unwrapped: the pasted words stay, the tags go.
 *
 * Left to right, a block is an opener, one optional newline, the body up to the
 * FIRST closer naming the same id (less one newline right before that closer),
 * the closer, and one optional newline; it becomes its body and a newline.
 * Scanning resumes after the closer, so a block inside another one's body is
 * left as it is, and an opener with no closer after it is left as text.
 *
 * ⚠ A SCAN, NOT A REGEX. This was one backreferenced regex with a lazy body,
 * and that restarts at every opener: a text of many openers whose closer never
 * comes walked to the end once per opener, which is quadratic, and a check that
 * both literals occur somewhere does not stop it — one stray closer is enough.
 * Here every closer is indexed by id in one pass, and each id's cursor only
 * moves forward, so the cost is linear in the text whatever it holds.
 */
function unwrapPasted(t) {
  const s = String(t || '');
  if (!s.includes(PASTE_OPEN) || !s.includes(PASTE_CLOSE)) return s;
  const closers = new Map();   // id -> { at: [closer offsets, ascending], next }
  for (let at = s.indexOf(PASTE_CLOSE); at >= 0; at = s.indexOf(PASTE_CLOSE, at + 1)) {
    const tag = pasteTag(s, at + PASTE_CLOSE.length);
    if (!tag) continue;
    let c = closers.get(tag.id);
    if (!c) closers.set(tag.id, (c = { at: [], next: 0 }));
    c.at.push(at);
  }
  let out = '';
  let copied = 0;              // s[0, copied) is already in `out`
  let at = s.indexOf(PASTE_OPEN);
  while (at >= 0) {
    const tag = pasteTag(s, at + PASTE_OPEN.length);
    const c = tag && closers.get(tag.id);
    if (c) {
      const body = s[tag.end] === '\n' ? tag.end + 1 : tag.end;
      // Openers of one id are met in order and their bodies start in order, so
      // a closer before this body can never pair with a later opener either.
      while (c.next < c.at.length && c.at[c.next] < body) c.next++;
      if (c.next < c.at.length) {
        const close = c.at[c.next];
        const bodyEnd = close > body && s[close - 1] === '\n' ? close - 1 : close;
        let end = close + PASTE_CLOSE.length + tag.id.length + 2;
        if (s[end] === '\n') end++;
        out += s.slice(copied, at) + s.slice(body, bodyEnd) + '\n';
        copied = end;
        at = s.indexOf(PASTE_OPEN, end);
        continue;
      }
    }
    at = s.indexOf(PASTE_OPEN, at + 1);
  }
  return out + s.slice(copied);
}

/** The peer's own words, with the subagent wrapper (if any) taken off. */
function peerBody(raw) {
  const s = String(raw || '');
  const inner = /<agent-message\b[^>]*>([\s\S]*?)<\/agent-message>/.exec(s);
  let body = inner ? inner[1] : s.replace(/<\/?cross-session-message\b[^>]*>/g, '');
  // A subagent's hand-back opens with the harness's one-line frame ("[Subagent
  // hand-back] The text below is the final report … The report follows:") and
  // indents every line of the report by two spaces. Read back as is, the frame
  // was the preview line of every report and the indent made a four-space list
  // into a code block. Display only: nothing here decides who sent it.
  const frame = /^\s*\[Subagent hand-back\][^\n]*\n/.exec(body);
  if (frame) body = body.slice(frame[0].length).replace(/^ {2}/gm, '');
  return body.trim();
}

/**
 * The harness's own idle notice — the completion signal a lead waits on when it
 * sends work with `notify_when_idle`.
 *
 * It arrives as a `user` record with `isMeta` and `promptSource:"system"` and NO
 * `origin` at all (it is harness-generated, not a peer message), so it has to be
 * recognised by its opening, which the CLI writes verbatim. Included here
 * because on a project surface these are most of the traffic: a lead running
 * four members gets one per finished task, and drawn as owner bubbles they read
 * as the person narrating their own cluster back to themselves.
 */
const IDLE_NOTICE_RE = /^\s*\[Cross-session idle notice\]\s*"([^"]+)"/;
const IDLE_DETAIL_RE = /«([^»]*)»/;

/**
 * ─── A PROJECT MESSAGE THE DAEMON RELAYED ──────────────────────────────────
 *
 * ⚠ M1 (round-2 review, 2026-09-19). `POST /v1/projects/:id/message` is the
 * button a person presses to put one member's words in another member's pane.
 * It does not travel Claude Code's native peer channel — appd PASTES a frame —
 * so the record it leaves behind is a plain `user` one with no `origin`, and
 * `peerNote` above, which keys structurally on `origin.kind === 'peer'`, had
 * nothing to recognise. The member's transcript therefore drew the owner's relay
 * as the member's OWN bubble, safety paragraph and all:
 *
 *   {"seq":17,"kind":"user","text":"[Huginn] Relayed message from rv-proj/lead
 *    (a peer session, not your owner):\nPEER-PING …"}
 *
 * The 3.4.0 changelog claimed the opposite of this route specifically. So the
 * frame is recognised by its header, and the header now carries the project.
 *
 * ⚠ THE 3.5.x HEADER IS STILL MATCHED. Transcripts written before the project
 * clause existed are on disk and will be read; they produce the same system note
 * with `project.id`/`project.name` null rather than a user bubble.
 */
const RELAY_HEAD_RE = new RegExp(
  '^\\s*\\[Huginn\\] Relayed message from (.+?)'
  + '(?: in project "([^"]*)" ([0-9a-fA-F][0-9a-fA-F-]{7,63}))?'
  + ' \\(a peer session, not your owner\\):[ \\t]*\\r?\\n',
);
/** The closing bookkeeping line, which is for the model and not for the reader. */
const RELAY_TAIL_RE = /\n\[End of message from [^\]]*\]\s*$/;

function projectRelayNote(text) {
  const s = String(text || '');
  const head = RELAY_HEAD_RE.exec(s);
  if (!head) return null;
  const from = head[1];
  const body = s.slice(head[0].length).replace(RELAY_TAIL_RE, '').trim();
  return {
    key: head[0].trim(),
    text: body ? `Message from ${from}: ${clip(body, 300)}` : `Message from ${from}`,
    // The same `peer` shape the native channel produces, so a client written
    // against 3.4.0 draws this note without knowing the route exists.
    peer: { name: from, sessionId: null, pid: null },
    project: { id: head[3] || null, name: head[2] || null, from },
  };
}

/**
 * The one-line note a peer record becomes, or null if this is not one.
 *
 * `peerIds` maps a verified peer PID to that session's Claude session id. The
 * record cannot say it itself — the address of record is the socket path — and
 * the only thing that can answer it is the daemon's native-registry reader,
 * which has no business being called from a pure tail parser. Absent, the id is
 * null and clients label from the NAME, which is what the spike says to do
 * anyway (the `@handle` spelling slugifies `/` to `-`, so the rendered preview
 * is not the identity).
 */
function peerNote(d, text, peerIds) {
  // appd's own relay first: it is the only one of the three that is recognised
  // by its text alone, and the `user` record it produces carries no origin for
  // the structural rule below to key on.
  const relay = projectRelayNote(text);
  if (relay) return relay;
  const origin = d && d.origin;
  if (origin && origin.kind === 'peer') {
    const name = typeof origin.name === 'string' && origin.name ? origin.name : 'another session';
    const element = firstElement(String(text || ''), CROSS_TAG)?.text || firstElement(String(text || ''), AGENT_TAG)?.text || '';
    const body = peerBody(origin.body || element);
    const pid = Number(origin.verifiedPeerPid) || null;
    return {
      key: element || null,
      text: body ? `Message from ${name}: ${clip(body, 300)}` : `Message from ${name}`,
      // The whole message, for a client that folds it into a card (2026-10-17:
      // the note clipped at 300 characters was the only way to read a report).
      body: body ? clip(body, FOLD_MAX) : null,
      peer: { name, sessionId: (pid && peerIds && peerIds.get(pid)) || null, pid },
    };
  }
  const idle = IDLE_NOTICE_RE.exec(String(text || ''));
  if (idle) {
    const detail = IDLE_DETAIL_RE.exec(String(text || ''));
    return {
      key: String(text || '').trim(),
      text: detail && detail[1] ? `${idle[1]} is idle — ${clip(detail[1], 200)}` : `${idle[1]} is idle`,
      peer: { name: idle[1], sessionId: null, pid: null },
    };
  }
  return null;
}

/** The same note, from the QUEUE record that lands ~90 ms before the user one. */
function peerNoteFromQueue(content, peerIds) {
  const s = String(content || '');
  const block = firstElement(s, CROSS_TAG);
  if (block) {
    const name = /from-name="([^"]*)"/.exec(block.text);
    const pid = /from="uds:[^"]*?\/(\d+)\.sock"/.exec(block.text);
    const body = peerBody(block.text);
    const who = (name && name[1]) || 'another session';
    return {
      key: block.text,
      text: body ? `Message from ${who}: ${clip(body, 300)}` : `Message from ${who}`,
      body: body ? clip(body, FOLD_MAX) : null,
      peer: {
        name: who,
        sessionId: (pid && peerIds && peerIds.get(Number(pid[1]))) || null,
        pid: pid ? Number(pid[1]) : null,
      },
    };
  }
  // A subagent's report: the bare element. Its name comes with the `user`
  // record that follows (origin.name), which then relabels this note.
  const agent = firstElement(s, AGENT_TAG);
  if (agent && agent.index <= 2) {
    const body = peerBody(agent.text);
    const who = 'a subagent';
    return {
      key: agent.text,
      text: body ? `Message from ${who}: ${clip(body, 300)}` : `Message from ${who}`,
      body: body ? clip(body, FOLD_MAX) : null,
      peer: { name: who, sessionId: null, pid: null },
    };
  }
  return peerNote({}, s, peerIds);
}

/**
 * The skill name to put on a chip, from the injected body when the `Skill` call
 * it came from is not in this window. Project skills open with their own
 * directory, whose last segment is the name; bundled ones ("artifact-design")
 * carry no marker at all and get a generic label rather than a guess.
 */
function skillNameFromBody(s) {
  const m = /^\s*Base directory for this skill:\s*(\S+)/.exec(String(s || ''));
  if (!m) return '';
  const seg = m[1].replace(/\/+$/, '').split('/').pop();
  return seg && seg !== '.' ? seg : '';
}

/**
 * Whatever the user actually wrote in a record that also carries command
 * plumbing. Dropping a record wholesale because it STARTS with a machine tag
 * would silently swallow a real message if the two were ever concatenated —
 * the worst failure this file can have, and cheap to rule out.
 */
function humanRemainder(s) {
  let t = stripElements(String(s), [['<local-command-caveat>', '</local-command-caveat>']]);
  t = stripElements(t, [['<local-command-stdout>', '</local-command-stdout>']]);
  t = stripElements(t, [['<command-name>', '</command-name>'], ['<command-message>', '</command-message>'],
    ['<command-args>', '</command-args>']]);
  // The bracketed preamble FIRST, while its terminating tag still exists —
  // stripped after the element it would run to end-of-string and eat any real
  // message concatenated behind the notification.
  t = t.replace(/\[SYSTEM NOTIFICATION - NOT USER INPUT\][\s\S]*?(?=<task-notification|$)/g, '');
  // Then the WHOLE element, not just its tags: stripping tags alone left the
  // inner task-id/summary text to render as a message the user supposedly wrote.
  t = stripElements(t, [['<task-notification>', '</task-notification>']]);
  t = stripElements(t, [['<system-reminder>', '</system-reminder>']]);
  // Stray tags. Only text up to the last `>` can hold one, and an opening past
  // it would scan to the end of the string for a `>` that is not there.
  const gt = t.lastIndexOf('>');
  const stripped = (t.slice(0, gt + 1).replace(/<\/?(task-notification|system-reminder)[^>]*>/g, '')
    + t.slice(gt + 1)).trim();
  // Short leftovers are tag debris, not prose.
  return stripped.length >= 12 ? stripped : '';
}

const ESC_RE = /\u001B\[[0-9;?]*[a-zA-Z]/g;
function stripAnsiText(s) { return String(s).replace(ESC_RE, ''); }

function clip(s, n) {
  s = String(s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** Text out of a content field that may be a string or a block array. */
function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b) => b && typeof b === 'object' && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('\n');
}

/**
 * Workflow scripts start with `export const meta = {name: '...'}`. Pulling the
 * name out turns an opaque 4KB script blob into a legible card title.
 */
function workflowName(script) {
  if (typeof script !== 'string') return null;
  const m = /name:\s*['"`]([^'"`]{1,80})['"`]/.exec(script);
  return m ? m[1] : null;
}

/**
 * @returns {{events: Array, nextOffset: number, truncated: boolean, title: string|null,
 *            permissionMode: string|null, model: string|null, gitBranch: string|null,
 *            cwd: string|null, lastActivityTs: number|null}}
 */
/**
 * Whether a byte offset sits immediately after a newline, i.e. at the start of a
 * whole record. One byte read; cheaper than being wrong.
 */
function startsAtBoundary(path, start) {
  if (start <= 0) return true;
  let fd;
  try {
    fd = fs.openSync(path, 'r');
    const b = Buffer.alloc(1);
    fs.readSync(fd, b, 0, 1, start - 1);
    return b[0] === 0x0a;
  } catch { return false; } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { } }
  }
}

const QUEUE_OP = Buffer.from('queue-operation');
const SCAN_CHUNK = 4 * 1024 * 1024;

/**
 * Which of a history page's still-queued messages a record BELOW the page
 * delivered: the first `remove` of the same text (the queue hands copies over
 * oldest first), or any `dequeue`, which drains everything waiting.
 *
 * Reads forward from `from` only as far as it takes to account for every one of
 * them, and parses only the lines that are queue records — the rest of a long
 * session is tool output, and a page should not pay for parsing it.
 */
function deliveredBelow(path, from, to, queued) {
  const waiting = new Map();
  for (const [content, evs] of queued) if (evs.length) waiting.set(content, evs.slice());
  const done = new Set();
  let fd;
  try { fd = fs.openSync(path, 'r'); } catch { return done; }
  try {
    let pos = from;
    let carry = Buffer.alloc(0);
    while (pos < to && waiting.size) {
      const buf = Buffer.alloc(Math.min(SCAN_CHUNK, to - pos));
      const n = fs.readSync(fd, buf, 0, buf.length, pos);
      if (n <= 0) break;
      pos += n;
      const chunk = carry.length ? Buffer.concat([carry, buf.subarray(0, n)]) : buf.subarray(0, n);
      for (let at = 0; waiting.size;) {
        const hit = chunk.indexOf(QUEUE_OP, at);
        if (hit === -1) break;
        const lineEnd = chunk.indexOf(0x0a, hit);
        if (lineEnd === -1) break;                   // still being written: the carry has it
        const lineStart = chunk.lastIndexOf(0x0a, hit) + 1;
        at = lineEnd + 1;
        let d;
        try { d = JSON.parse(chunk.toString('utf8', lineStart, lineEnd)); } catch { continue; }
        if (!d || d.type !== 'queue-operation') continue;
        if (d.operation === 'dequeue') {
          for (const evs of waiting.values()) for (const ev of evs) done.add(ev);
          waiting.clear();
        } else if (d.operation === 'remove') {
          const content = typeof d.content === 'string' ? d.content : '';
          const evs = waiting.get(content);
          if (evs) {
            done.add(evs.shift());
            if (!evs.length) waiting.delete(content);
          }
        }
      }
      const lastNl = chunk.lastIndexOf(0x0a);
      carry = Buffer.from(lastNl === -1 ? chunk : chunk.subarray(lastNl + 1));
    }
  } finally { try { fs.closeSync(fd); } catch { } }
  return done;
}

const DRAIN_LOOKBACK_BYTES = 256 * 1024;

/**
 * The queue drain just above `start` whose echoes may still be arriving: the
 * last `dequeue` before `start`, unless an assistant record of the turn it fed
 * (stamped at or after it) has followed it since. Null when there is none.
 *
 * Bounded: an echo lands a second or two behind its drain, behind at most a few
 * records the old turn flushed late, so a drain further up than
 * DRAIN_LOOKBACK_BYTES is not one this window can be the rest of.
 */
function drainAbove(path, start) {
  if (!(start > 0)) return null;
  const from = Math.max(0, start - DRAIN_LOOKBACK_BYTES);
  let buf;
  let fd;
  try {
    fd = fs.openSync(path, 'r');
    buf = Buffer.alloc(start - from);
    buf = buf.subarray(0, fs.readSync(fd, buf, 0, buf.length, from));
  } catch { return null; } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { } }
  }
  // The first line is a fragment unless the read began at the top of the file.
  const first = from > 0 ? buf.indexOf(0x0a) + 1 : 0;
  if (from > 0 && first === 0) return null;
  let drain = null;
  for (let a = first; a < buf.length;) {
    let b = buf.indexOf(0x0a, a);
    if (b === -1) b = buf.length;
    const at = a;
    a = b + 1;
    if (b === at) continue;
    // Only a dequeue and an assistant record decide anything here: a record that
    // names any other type first is skipped unread (see [leadingType]).
    const t = leadingType(buf, at, b);
    if (t !== null && t !== 'queue-operation' && t !== 'assistant') continue;
    let d;
    try { d = JSON.parse(buf.toString('utf8', at, b)); } catch { continue; }
    const tsMs = d.timestamp ? Date.parse(d.timestamp) || null : null;
    if (d.type === 'queue-operation' && d.operation === 'dequeue') drain = { tsMs };
    else if (drain && d.type === 'assistant'
      && (drain.tsMs === null || tsMs === null || tsMs >= drain.tsMs)) drain = null;
  }
  return drain;
}

function readTranscript(path, { offset = null, limit = 400, until = null, peerIds = null, _resuming = null, _win = null } = {}) {
  // A window-size probe (below) reads the file as the open that asked for it saw
  // it: one stat, one end, and the bytes already read.
  const st = _win ? _win.st : fs.statSync(path);
  // Where this window ENDS. Normally the end of the file; `until` walks backwards
  // into history instead, and is how a reader gets the part of a conversation the
  // tail cut off. Pass the PREVIOUS window's `windowStart` and the two abut
  // exactly — no overlap to dedupe, no gap to notice — because a windowStart is
  // always a record boundary.
  const end = until == null ? st.size : Math.max(0, Math.min(until, st.size));
  let start = offset;
  let truncated = false;
  // Is the caller RESUMING a tail it has already read, or opening cold?
  //
  // It changes what an orphaned `remove` means (see the queue-operation case).
  // `_resuming` exists because the cold-open windowing below calls this function
  // recursively WITH an offset to probe window sizes; those probes are still
  // part of a cold open and must not be classified as resumption.
  const resuming = _resuming != null
    ? _resuming
    : (offset != null && offset >= 0 && offset <= st.size);
  const win = _win || openWindow(path, st, end);
  if (start == null || start < 0 || start > st.size) {
    // Tail by EVENTS, not by a fixed byte count. A transcript row that Read an
    // image carries the whole image as base64 — megabytes per photo — so a
    // fixed window measured in bytes holds an arbitrary, sometimes zero, number
    // of turns. Measured on a real chat: two photos made the file 2.1MB, the
    // 256KB tail started mid-turn-2, and the app rendered the second exchange
    // only — which read, from the phone, as sending a photo having DELETED the
    // conversation above it. The window doubles until it holds a useful number
    // of events or spans the whole file.
    let size = TAIL_BYTES;
    for (;;) {
      start = Math.max(0, end - size);
      truncated = start > 0;
      if (start === 0) break;
      const probe = readTranscript(path, { offset: start, limit, until, peerIds, _resuming: false, _win: win });
      // A TAIL only has to be worth showing — MIN_TAIL_EVENTS distinguishes "a
      // screenful of conversation" from "half a base64 blob", and reading more
      // than that costs a phone bytes it did not ask for. A HISTORY page is the
      // opposite: it was asked for explicitly, and stopping at twelve events
      // would make walking back through a long session a hundred round trips.
      const want = until == null ? Math.min(limit, MIN_TAIL_EVENTS) : limit;
      if (probe.events.length >= want) break;
      size *= 2;
    }
  }

  const buf = end > start ? windowBytes(win, start, end) : Buffer.alloc(0);

  // The records are found and measured on the RAW BYTES, and only the ones that
  // are read get decoded. A newline is one byte in UTF-8 and never part of a
  // multi-byte character, so these are the cuts `text.split('\n')` made.
  let consumed = buf.length;
  let usable = buf.length;
  // A concurrent writer can leave a partial final line; leave it for next time.
  // Only at the LIVE end of the file — a window that stops at `until` ends on a
  // record boundary we chose, so there is nothing half-written down there.
  if (until != null) {
    if (buf.length === 0 && offset != null) return emptyResult(start, truncated);
  } else {
    const lastNl = buf.lastIndexOf(0x0a);
    if (lastNl === -1) {
      if (offset != null) return emptyResult(start, truncated);
    } else if (lastNl !== buf.length - 1) {
      // In BYTES, off the buffer — never `Buffer.byteLength` of the decoded text.
      // A tail window is a byte count and lands where it lands, including inside a
      // multi-byte character, and `toString('utf8')` turns each stray continuation
      // byte into U+FFFD: three bytes where the file has one. Measuring the
      // decoded string therefore overshot by two bytes per stray byte, `nextOffset`
      // landed INSIDE the next record, and that record's line failed JSON.parse and
      // vanished from the live view until a cold reload — always the newest record,
      // which is the one the reader opened the session to watch. Same byte-wise
      // pattern `copyTranscriptForArchive` already uses.
      consumed = lastNl + 1;
      usable = consumed;
    }
  }
  // [from, to) of each non-empty line, as offsets into buf.
  let lines = end > start ? windowLines(win, start, start + usable) : [];
  // Where the records below actually START, which is not `start` when the window
  // opened mid-record. This is what a reader pages backwards with: hand it back
  // as `until` and the next window ends exactly where this one begins.
  let windowStart = start;
  // A tail read USUALLY starts mid-line, and that fragment is not JSON — but not
  // always: when the window lands exactly on a record boundary the first line is
  // whole, and dropping it unconditionally lost a real message from the top of
  // the view. Ask the file instead of assuming: the byte before the window is a
  // newline exactly when the window starts at a boundary.
  if (truncated && lines.length && offset == null && !startsAtBoundary(path, start)) {
    // Where the fragment ends, in bytes — decoded length lies here for exactly
    // the reason it lies above, and this number is what a reader pages BACKWARDS
    // with, so a wrong one leaves a gap or an overlap between two pages.
    let at = 0;
    while (at < buf.length && buf[at] === 0x0a) at += 1;
    const nl = buf.indexOf(0x0a, at);
    windowStart = nl === -1 ? start + consumed : start + nl + 1;
    lines = lines.slice(1);
  }

  const out = {
    events: [],
    // Text of queued messages DELIVERED in this window whose enqueue was in an
    // earlier one. The reader already holds those bubbles, still badged
    // `queued`; this is how it learns to drop the badge without the message
    // being sent twice. Empty on a cold open, where the bubble is emitted
    // outright instead.
    deliveredQueued: [],
    nextOffset: start + consumed,
    /**
     * The byte this window's first record begins at. Zero means the reader is
     * looking at the very start of the conversation; anything else is where to
     * resume reading BACKWARDS, by passing it as `until`.
     */
    windowStart,
    truncated,
    title: null,
    permissionMode: null,
    model: null,
    gitBranch: null,
    cwd: null,
    effort: null,
    lastActivityTs: null,
  };

  // tool_use id -> the event we appended, so a later tool_result can complete it
  // in place instead of arriving as a separate orphan card.
  const pendingTools = new Map();
  // The same index, but kept AFTER the result lands: a tool can go on producing
  // records once it has returned (a skill's body arrives behind its
  // "Launching skill: …" result), and those need to find the card they belong
  // to, which `pendingTools` has by then deleted. Bounded by the window, like
  // every other map here.
  const toolById = new Map();
  // Queued messages by content, so a later `remove` can mark the same event
  // delivered instead of adding a duplicate.
  // content -> FIFO of still-queued events with that exact text. A plain
  // one-event-per-content map lost a duplicate: sending "ok" twice while Claude
  // was busy overwrote the first event, leaving it stuck with a `queued` badge
  // forever, and the second `remove` then found nothing and pushed a THIRD
  // bubble. Two sends, three messages, one of them permanently "queued".
  const queued = new Map();
  /**
   * Messages the queue DRAINED, by content — a FIFO per content, for the same
   * duplicate reason `queued` above is one.
   *
   * `remove` and `dequeue` are not two names for one thing. A `remove` hands a
   * message into a turn that is STILL RUNNING, and Claude Code never writes it
   * as a `user` record afterwards. A `dequeue` drains the queue into a Claude
   * that has gone IDLE, and every message it drained is then written as an
   * ordinary `user` record a second or two later. Measured across all 907
   * transcripts on this host: 1,244 of 1,281 drained messages have a matching
   * `user` record after them, and removed ones have none at all.
   *
   * So a drained message's bubble has to SWALLOW the record that follows it,
   * once per drained copy — keyed by content and left standing, the guard would
   * swallow every later "ok" / "continue" / "yes" as well.
   */
  const drained = new Map();
  /**
   * A drain ran in this window whose enqueues were all ABOVE it — so `queued`
   * was empty, nothing could be moved, and the record carries no content to name
   * what it drained with. The `user` records that follow ARE those messages,
   * written by Claude Code a second or two later.
   *
   * A reader RESUMING a tail already holds them, badged `queued` from the page
   * the enqueue landed in, so emitting them again appends a second identical
   * bubble while the first keeps a badge nothing will ever clear (#34/#37). The
   * delivery is reported through `deliveredQueued` instead, exactly as an
   * orphaned `remove` does — the one difference being that a drain can cover
   * several messages, so this stands until the turn it fed begins.
   */
  let drainedAbove = false;
  /**
   * The drain's own timestamp, and whether a drain in this window has already
   * delivered something.
   *
   * Both are bounds on the guard above, and both were written from real data
   * (930 transcripts, replayed — they cost two real messages before they were
   * added). A dequeue that drains nothing AFTER one that drained everything is
   * a no-op the CLI writes in pairs, not a drain whose messages are above; and
   * the records a drain produces land a second or two behind it, so a message
   * typed long afterwards is a new message even when no assistant record happens
   * to sit between the two (measured: seven hours, in one real transcript).
   */
  let drainedAboveTs = null;
  let drainedHere = false;
  const DRAIN_ECHO_S = 120;
  /**
   * What this window knows of a drain on the other side of its own start: whether
   * it has a dequeue of its own, whether it has already asked above (once per
   * read), and the newest assistant record it has passed — a turn that began in
   * here ends the echoes of a drain up there. See [drainAbove].
   */
  let sawDequeue = false;
  let lookedAbove = false;
  let newestAssistantMs = null;
  let seq = 0;
  /**
   * A queued message has been delivered: un-badge it and move it to HERE.
   *
   * MOVED to the delivery point, not just un-badged where it sat. An enqueue
   * happens mid-turn, so the bubble first appears INTERLEAVED into the output of
   * the turn that was still running. Clearing the badge in place left it
   * stranded there — above the rest of an answer it was sent after, and above
   * the answer it actually prompted. What a reader expects is what the pane
   * does: the message waits at the bottom while queued, then takes its real
   * place in the conversation once delivered. Re-seq'd so ordering stays
   * monotonic.
   */
  const deliverQueued = (ev, atTs) => {
    const at = out.events.indexOf(ev);
    if (at >= 0) out.events.splice(at, 1);
    delete ev.queued;
    ev.seq = ++seq;
    if (atTs) ev.ts = atTs;
    out.events.push(ev);
  };
  // Which record each event came out of, by byte. Kept OUTSIDE the events so it
  // never reaches the wire, and in a WeakMap rather than a parallel array
  // because the queued-message handling below moves events around — an index
  // would desync, an object identity cannot.
  const recordAt = new WeakMap();
  /**
   * The peer messages already drawn from their QUEUE record, so the `user`
   * record that follows ~90 ms later does not draw a second note for the same
   * message. Keyed on the `<cross-session-message>` element the two records
   * share; one-shot per copy, because the same peer saying the same words again
   * later IS a second message.
   */
  const peerSeen = [];
  // The note a queue copy drew, by its element, so the `user` record can name the sender.
  const peerEvents = new Map();
  /**
   * The machine-text notes already drawn from their QUEUE record, by content, so
   * the `user` record repeating the same notification ~40 ms later does not draw
   * a second one. A list rather than a set, and consumed one entry per match,
   * because the guard is ONE-SHOT: the same background command finishing twice
   * writes the same text twice and both times it is news.
   */
  const notifySeen = [];
  let lineOffset = windowStart;
  // The lines [ignoredRecord] sent past unparsed, and the last PARSED line that
  // set each of the three fields every record sets before its switch — the
  // skipped ones after it are read once the loop is done. See below.
  const skipped = [];
  const setAt = { ts: -1, git: -1, cwd: -1 };

  for (let li = 0; li < lines.length; li++) {
    const [a, b] = lines[li];
    const eventsBefore = out.events.length;
    const recordOffset = lineOffset;
    // The DECODED length, as it always was: a line a window cut mid-character
    // opens with stray continuation bytes, and each decodes to three. Whole lines
    // are valid UTF-8 and decode to exactly their bytes.
    lineOffset += (buf[a] >= 0x80 && buf[a] < 0xc0
      ? Buffer.byteLength(buf.toString('utf8', a, b), 'utf8') : b - a) + 1;
    // `finally` because almost every branch below ends in `continue`; this has to
    // run whichever way the record leaves.
    try {
    if (ignoredRecord(buf, a, b)) { skipped.push(li); continue; }
    const d = parseRecord(win, start + a, buf, a, b);
    if (d === UNPARSEABLE) continue;
    const ts = d.timestamp ? Math.floor(Date.parse(d.timestamp) / 1000) || null : null;
    // The same instant in MILLISECONDS, for the one question a second's
    // resolution cannot answer: whether an assistant record written after a
    // dequeue belongs to the turn the dequeue started or is a late flush of the
    // one before it. Real transcripts carry both, 150 ms apart, in that order.
    const tsMs = d.timestamp ? Date.parse(d.timestamp) || null : null;
    if (ts) { out.lastActivityTs = ts; setAt.ts = li; }
    if (d.gitBranch) { out.gitBranch = d.gitBranch; setAt.git = li; }
    if (d.cwd) { out.cwd = d.cwd; setAt.cwd = li; }
    const sidechain = d.isSidechain === true;

    switch (d.type) {
      case 'queue-operation': {
        // A message typed while Claude is mid-turn is QUEUED, and while it waits
        // these records are the ONLY trace of it. Dropping them (as v2.2.0 did)
        // made every follow-up message invisible in the conversation while still
        // being visible in the pane, which is exactly what a user notices.
        //
        // Whether a `user` record ever follows depends on HOW the queue let it
        // go, and this comment used to say flatly that none ever does. That is
        // true of a `remove` — absorbed into a turn already running — and false
        // of a `dequeue`, which drains into an idle Claude and is written out
        // again as an ordinary `user` record a moment later. Both branches below
        // are built on the distinction (see `drained`), and the one place that
        // still believed the flat version drew every background-task
        // notification twice. See [notificationEcho].
        const content = typeof d.content === 'string' ? d.content : '';
        if (d.operation === 'enqueue') {
          if (!content.trim()) continue;
          // ANOTHER CLAUDE SESSION, not the owner. A peer message and a harness
          // idle notice both arrive through this queue, and both used to become
          // user bubbles. Drawn here rather than waiting for the `user` record
          // because the tail can begin between the two; whichever of them this
          // window holds produces exactly one note.
          const peerQ = peerNoteFromQueue(content, peerIds);
          if (peerQ) {
            const ev = {
              seq: ++seq, kind: 'system', ts, sidechain, text: peerQ.text, peer: peerQ.peer,
              ...(peerQ.body ? { body: peerQ.body, fold: 'peer' } : {}),
              ...(peerQ.project ? { project: peerQ.project } : {}),
            };
            out.events.push(ev);
            if (peerQ.key) { peerSeen.push(peerQ.key); peerEvents.set(peerQ.key, ev); }
            continue;
          }
          let ev;
          if (machineText(content)) {
            const d2 = describeMachineText(content);
            if (!d2) continue;
            ev = { seq: ++seq, kind: d2.kind, ts, sidechain, text: d2.text };
            // ...and the `user` record that repeats this notification verbatim a
            // moment later must not draw it again. See [notificationEcho].
            notifySeen.push(content);
          } else {
            ev = { seq: ++seq, kind: 'user', ts, sidechain, text: content, queued: true };
          }
          out.events.push(ev);
          if (ev.kind === 'user') {
            const pending = queued.get(content) || [];
            pending.push(ev);
            queued.set(content, pending);
          }
        } else if (d.operation === 'remove') {
          // Delivered into the turn: it is now an ordinary message.
          // Oldest first: the queue delivers in send order, so the first
          // still-pending copy is the one being delivered now.
          const pending = queued.get(content);
          const ev = pending && pending.length ? pending.shift() : null;
          if (pending && pending.length === 0) queued.delete(content);
          if (ev) {
            // Delivered INTO a running turn, so no `user` record will follow it
            // and nothing needs to swallow one — the difference from the drain
            // below, and the whole reason the two operations are read apart.
            deliverQueued(ev, ts);
          }
          else if (content.trim() && !machineText(content) && peerNoteFromQueue(content, peerIds)) {
            // A PEER NOTE, which the enqueue drew and never queued. A subagent's
            // hand-back absorbed into a running turn leaves only this record and
            // no `user` one, and read as text it was the whole report in a bubble.
            // Drawn here only on a cold open whose enqueue scrolled off the top; a
            // resumed tail already has the note from an earlier page.
            const peerR = peerNoteFromQueue(content, peerIds);
            if (!resuming && !(peerR.key && peerSeen.includes(peerR.key))) {
              const ev = {
                seq: ++seq, kind: 'system', ts, sidechain, text: peerR.text, peer: peerR.peer,
                ...(peerR.body ? { body: peerR.body, fold: 'peer' } : {}),
                ...(peerR.project ? { project: peerR.project } : {}),
              };
              out.events.push(ev);
              if (peerR.key) { peerSeen.push(peerR.key); peerEvents.set(peerR.key, ev); }
            }
          }
          else if (content.trim() && !machineText(content)) {
            // The enqueue is outside this window, and what to do about that
            // depends entirely on whether the reader has seen it already.
            //
            // COLD OPEN: it scrolled off the top. This is the only copy the
            // reader will get, so emit it.
            //
            // RESUMING a tail: they have it — it arrived in an earlier page,
            // badged `queued`. Emitting it again appended a SECOND identical
            // bubble, and since the client merge is a plain concat with no
            // reconciliation, the first copy also kept its badge. Two bubbles,
            // one of them permanently claiming to be still waiting, on the
            // primary conversation view of both clients. It fired on nearly
            // every send-while-busy: the poll is 2.5s and real enqueue->remove
            // gaps run to minutes, so the two records almost always land in
            // different windows.
            //
            // `deliveredQueued` carries the news instead, so a client that
            // understands it can drop the badge from the copy it already has.
            if (!resuming) {
              out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: content });
            } else {
              out.deliveredQueued.push(content);
            }
          }
        } else if (d.operation === 'dequeue') {
          sawDequeue = true;
          // THE QUEUE DRAINED, into a Claude that had gone idle: everything
          // still waiting was delivered HERE, in the order it was sent.
          //
          // Read as a no-op until 2026-09-15, which is how the owner's opening
          // message ended up UNDERNEATH the answer to it. A `dequeue` carries no
          // content, so nothing cleared the `queued` badge its enqueue had set;
          // the badge then floated the message to the bottom, and its real
          // `user` record was swallowed by the duplicate guard as an echo of a
          // message that was still, as far as this reader knew, waiting to be
          // sent. Claude Code writes an enqueue/dequeue pair for the FIRST
          // prompt of every run, so every chat and every session opened with its
          // own opening prompt printed last — loudest in the escalate-to-Claude
          // handoff, where the conversation is one long message and one answer
          // and the whole screen therefore read upside down.
          const waiting = [];
          for (const [content, pending] of queued) {
            for (const ev of pending) waiting.push({ content, ev, at: out.events.indexOf(ev) });
          }
          // SEND order, not content-bucket order: `queued` is grouped by text, so
          // iterating it delivers all the copies of one message before the first
          // copy of another. Position in the events list is the order they were
          // typed, which is the order the queue hands them over.
          waiting.sort((a, b) => a.at - b.at);
          queued.clear();
          for (const w of waiting) {
            deliverQueued(w.ev, ts);
            const copies = drained.get(w.content) || [];
            copies.push(w.ev);
            drained.set(w.content, copies);
          }
          if (waiting.length) drainedHere = true;
          // Nothing here to drain, and no earlier drain in this window took
          // anything either — so whatever this one drained was enqueued above,
          // where the reader has it badged. See [drainedAbove].
          else if (resuming && !drainedHere) {
            drainedAbove = true;
            drainedAboveTs = tsMs;
          }
        }
        // A `dequeue` whose enqueues are all above this window drained messages
        // this pass never saw, and the record carries no content to name them
        // with — so there is nothing to say about them here, and the `user`
        // records that follow are read as the ordinary messages they are.
        continue;
      }
      case 'ai-title':
        if (d.aiTitle) out.title = d.aiTitle;
        continue;
      case 'permission-mode':
        if (d.permissionMode) out.permissionMode = d.permissionMode;
        continue;
      case 'mode':
        continue;
      case 'user': {
        const c = d.message && d.message.content;
        // A "user" record also carries tool_result blocks, which are Claude Code
        // handing a tool's output back to the model, not something a human said.
        if (Array.isArray(c)) {
          for (const b of c) {
            if (!b || typeof b !== 'object') continue;
            if (b.type === 'tool_result') {
              const ev = pendingTools.get(b.tool_use_id);
              const body = clip(textOf(b.content) || '', 600);
              if (ev) {
                ev.result = body;
                ev.ok = b.is_error !== true;
                pendingTools.delete(b.tool_use_id);
              } else {
                out.events.push({ seq: ++seq, kind: 'tool_result', ts, sidechain, ok: b.is_error !== true, result: body });
              }
            }
          }
        }
        const t = textOf(c);
        if (!t.trim() || queued.has(t)) continue;
        // The compaction summary Claude Code writes as a `user` record: the
        // model's own account of the conversation so far, never the owner's
        // words. A card, folded (2026-10-17); it read as a page-long message
        // the owner had typed.
        if (d.isCompactSummary === true) {
          out.events.push({
            seq: ++seq, kind: 'system', ts, sidechain,
            text: 'Summary of the earlier conversation', body: clip(t.trim(), FOLD_MAX), fold: 'compact',
          });
          continue;
        }
        // A skill's instruction body, pushed in by the tool call that loaded it.
        // The `Skill` card a couple of records above is ALREADY the one event
        // this invocation deserves — it carries the skill's name and, opened,
        // its "Launching skill: …" result — so the body is the same news twice
        // and belongs to that card, exactly as a tool_result does.
        //
        // Only when the call itself fell outside this window (the tail begins
        // between the two records, or the reader paged to here) does the body
        // become the only trace a skill loaded, and then it is worth one chip.
        if (injectedByTool(d)) {
          if (!toolById.has(d.sourceToolUseID)) {
            const name = skillNameFromBody(t);
            out.events.push({
              seq: ++seq, kind: 'command', ts, sidechain,
              text: name ? `Skill: ${name}` : 'Skill loaded',
            });
          }
          continue;
        }
        // A message from another Claude session, or the harness's idle notice
        // about one. Never a user bubble — see [peerNote]. The queue record
        // above has usually already drawn it, in which case this one is
        // bookkeeping; when the window began after that record, this is the only
        // trace and it draws the note itself.
        const peerU = peerNote(d, t, peerIds);
        if (peerU) {
          const at = peerU.key ? peerSeen.indexOf(peerU.key) : -1;
          if (at >= 0) {
            peerSeen.splice(at, 1);
            // The queue copy drew the note without the sender's name (a
            // subagent's report names nobody); this record has it.
            const drawn = peerEvents.get(peerU.key);
            if (drawn && drawn.peer && drawn.peer.name === 'a subagent' && peerU.peer && peerU.peer.name) {
              drawn.peer = peerU.peer;
              drawn.text = peerU.text;
            }
            peerEvents.delete(peerU.key);
            continue;
          }
          out.events.push({
            seq: ++seq, kind: 'system', ts, sidechain, text: peerU.text, peer: peerU.peer,
            ...(peerU.body ? { body: peerU.body, fold: 'peer' } : {}),
            ...(peerU.project ? { project: peerU.project } : {}),
          });
          continue;
        }
        // The record Claude Code writes for a message the queue just DRAINED.
        // Its bubble is already in this window — moved down to the drain point a
        // few records ago — so this is bookkeeping, not a second message. Shifted
        // rather than merely looked up, because the guard is one-shot per drained
        // copy: the same words typed again later are a real second message.
        const drainedCopies = drained.get(t);
        if (drainedCopies && drainedCopies.length) {
          drainedCopies.shift();
          if (!drainedCopies.length) drained.delete(t);
          continue;
        }
        // Claude Code's ECHO of a background-task notification — the element it
        // already wrote as a queue record a few records above, which is where
        // the note was drawn. See [notificationEcho].
        //
        // Drawn from the QUEUE record and suppressed HERE, rather than the other
        // way round, because the queue record is the copy that ALWAYS exists: a
        // notification landing while Claude is still working is absorbed into
        // the running turn (`remove`, `reason: "absorbed_mid_turn"`) and no
        // `user` record ever follows it. Measured across 955 transcripts on this
        // host, 645 of 1031 notification enqueues have no echo at all, so
        // drawing from the echo instead would lose two notifications in three.
        // It also leaves the note where the agent actually reported back — above
        // the turn the notification started, not inside it.
        //
        // One-shot per copy, like every other dedupe in this file.
        if (notificationEcho(d)) {
          const at = notifySeen.indexOf(t);
          if (at >= 0) { notifySeen.splice(at, 1); continue; }
          // The queue record is ABOVE this window. A reader RESUMING a tail
          // holds the note already, from the page that record landed in, and the
          // client merge is a plain concat with no reconciliation — so emitting
          // it here appends a second identical note, the same failure as #34/#37
          // one record class over. A COLD open has no such page, and then this
          // record is the only copy of the notification it will ever get.
          if (resuming) continue;
        }
        // Recorded as typed: no heuristic gets to overrule that. Without it a
        // person asking about "<command-name>" had their question collapsed
        // into a `/…` chip, because the CLI's own command bookkeeping is the
        // same record type with the same-looking text.
        if (machineText(t) && !typedByHuman(d)) {
          // Slash-command bookkeeping arrives as ordinary user records.
          const d2 = describeMachineText(t);
          if (d2) {
            out.events.push({ seq: ++seq, kind: d2.kind, ts, sidechain, text: d2.text });
            // `/effort max` changes the setting NOW. Waiting for the next
            // assistant record to stamp it would leave a control showing the
            // previous value for as long as the turn takes.
            if (d2.kind === 'command') {
              const setting = /^\/(effort|model)\s+(\S+)$/.exec(d2.text);
              if (setting) {
                if (setting[1] === 'effort') out.effort = setting[2].toLowerCase();
                else out.model = setting[2].toLowerCase();
              }
            }
          }
          // ...and if a human wrote something in the same record, show that too.
          const rest = humanRemainder(t);
          if (rest) out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: rest });
          continue;
        }
        // ⚠ THE DRAIN MAY BE JUST ABOVE THIS WINDOW. Claude Code writes the
        // dequeue and then its echoes, and a poll can end between the two —
        // right after the dequeue, or after records the old turn flushed behind
        // it. Knowing only a dequeue in its own window, the next read took each
        // echo for a new message: the bubble twice, and the badge on the
        // reader's copy never cleared. So a resumed read that reaches a message
        // with no drain of its own in view asks the records above its start
        // whether one is still waiting for echoes — the state this guard would
        // have had if the poll had not split there.
        if (!drainedAbove && resuming && !sawDequeue && !lookedAbove) {
          lookedAbove = true;
          const above = drainAbove(path, windowStart);
          if (above && (newestAssistantMs === null || above.tsMs === null || newestAssistantMs < above.tsMs)) {
            drainedAbove = true;
            drainedAboveTs = above.tsMs;
          }
        }
        // The records a drain we could not see delivered — see [drainedAbove].
        // Reported, not emitted: the reader is resuming and already has them.
        //
        // ⚠ ONLY RECORDS STAMPED AT OR AFTER THE DRAIN. An echo is written behind
        // its dequeue — across this host's transcripts, 1,663 echoes and every one
        // 4 ms to 9 s after it — but the old turn's own records are flushed behind
        // the dequeue too, stamped BEFORE it, and a person's message among those
        // is a message, not the echo of one. Taking it for an echo dropped it.
        if (drainedAbove
          && (drainedAboveTs === null || tsMs === null
            || (tsMs >= drainedAboveTs && tsMs - drainedAboveTs <= DRAIN_ECHO_S * 1000))) {
          out.deliveredQueued.push(t);
          continue;
        }
        out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: t });
        continue;
      }
      case 'assistant': {
        // The turn the drain fed has begun, so the drained messages are all
        // behind us: anything typed from here on is a NEW message. Without this
        // bound the guard would swallow every later thing the owner said.
        //
        // By TIMESTAMP, not by position: Claude Code flushes the previous turn's
        // assistant records AFTER the dequeue record on nearly every real drain
        // (seen 150 ms out of order in three transcripts), and taking those as
        // the new turn released the guard before the drained `user` record it
        // exists for even arrived.
        if (drainedAboveTs === null || tsMs === null || tsMs >= drainedAboveTs) drainedAbove = false;
        if (tsMs !== null && (newestAssistantMs === null || tsMs > newestAssistantMs)) newestAssistantMs = tsMs;
        const m = d.message || {};
        if (m.model) out.model = m.model;
        // Claude Code stamps the effort level on each assistant record, so the
        // app can show the session's current setting without asking for it.
        if (d.effort) out.effort = d.effort;
        // An API error is written as an ORDINARY assistant record whose text is
        // the apology — "You've hit your session limit · resets 10:10pm …".
        // Without this flag it renders as Claude choosing to say that, and the
        // only thing that distinguishes a stalled session from an idle one is
        // invisible to every client. The kind stays `assistant` so old clients
        // keep rendering the text; `apiError` is the new, nullable fact.
        // `?? 0` because the flag can land without a status and "an error with
        // no number" must not read as "no error" (lib/limits.js keys on 429).
        const apiError = d.isApiErrorMessage === true ? (d.apiErrorStatus ?? 0) : null;
        const c = m.content;
        if (!Array.isArray(c)) {
          const t = textOf(c);
          if (t.trim()) {
            const ev = { seq: ++seq, kind: 'assistant', ts, sidechain, text: t };
            if (apiError !== null) ev.apiError = apiError;
            out.events.push(ev);
          }
          continue;
        }
        for (const b of c) {
          if (!b || typeof b !== 'object') continue;
          if (b.type === 'text' && b.text && b.text.trim()) {
            const ev = { seq: ++seq, kind: 'assistant', ts, sidechain, text: b.text };
            if (apiError !== null) ev.apiError = apiError;
            out.events.push(ev);
          } else if (b.type === 'thinking' && typeof b.thinking === 'string' && b.thinking.trim()) {
            out.events.push({ seq: ++seq, kind: 'thinking', ts, sidechain, text: b.thinking });
          } else if (b.type === 'tool_use') {
            const ev = {
              seq: ++seq,
              kind: 'tool',
              ts,
              sidechain,
              name: b.name || 'tool',
              input: digestToolInput(b.input),
              result: null,
              ok: null,
            };
            if (b.name === 'Workflow') {
              // The script is a multi-KB blob; its meta name is the whole story,
              // so carry that as the detail and show no raw input at all.
              ev.detail = workflowName(b.input && b.input.script) || 'workflow';
              ev.input = '';
            } else if (b.name === 'Agent' || b.name === 'Task') {
              const t = b.input && (b.input.description || b.input.subagent_type);
              if (t) ev.detail = clip(t, 80);
            } else if (b.name === 'Skill') {
              // This card is the WHOLE of what a skill load shows now, so it has
              // to say which skill: `digestToolInput` found no field it knows
              // and fell back to dumping the JSON, which read as
              // `Skill {"skill":"incident-triage","args":"monitor-down alerts…`.
              // Name on the card, the prompt that invoked it behind the tap.
              if (b.input && typeof b.input.skill === 'string') ev.detail = clip(b.input.skill, 80);
              ev.input = b.input && typeof b.input.args === 'string' ? clip(b.input.args, 400) : '';
            } else if (b.name === 'AskUserQuestion') {
              // Rendered as a question card, never as raw JSON — which is
              // exactly how it looked: `{"questions":[{"question":"The push…`.
              const ask = parseAsk(b.input);
              if (ask) {
                ev.ask = ask;
                ev.detail = clip(ask.questions[0].question, 80);
                ev.input = '';
              }
            }
            out.events.push(ev);
            if (b.id) { pendingTools.set(b.id, ev); toolById.set(b.id, ev); }
          }
        }
        continue;
      }
      case 'system': {
        // Compaction notices and similar. Only surface ones with real content.
        const t = typeof d.content === 'string' ? d.content : textOf(d.content);
        if (t && t.trim() && t.length < 400) {
          out.events.push({ seq: ++seq, kind: 'system', ts, sidechain, text: t.trim() });
        }
        continue;
      }
      default:
        continue;
    }
    } finally {
      for (let k = eventsBefore; k < out.events.length; k++) {
        recordAt.set(out.events[k], recordOffset);
      }
    }
  }

  // ⚠ WHAT A SKIPPED RECORD STILL SAYS. A record [ignoredRecord] sent past draws
  // nothing, but before the switch EVERY record sets the window's last activity,
  // git branch and cwd when it carries them, and an `api-request` carries a
  // timestamp. The last record to set a field is the one the page reports, so a
  // skipped record matters only when it comes after every parsed record that set
  // it: those are read now, newest first, and that is ordinarily the one or two
  // at the end of a live tail. Not in the window-size probes, which only count.
  if (skipped.length && _resuming == null) {
    let tsOpen = true;
    let gitOpen = true;
    let cwdOpen = true;
    for (let k = skipped.length - 1; k >= 0; k--) {
      const li = skipped[k];
      tsOpen = tsOpen && li > setAt.ts;
      gitOpen = gitOpen && li > setAt.git;
      cwdOpen = cwdOpen && li > setAt.cwd;
      if (!tsOpen && !gitOpen && !cwdOpen) break;
      const [a, b] = lines[li];
      const d = parseRecord(win, start + a, buf, a, b);
      if (d === UNPARSEABLE) continue;
      if (tsOpen) {
        const ts = d.timestamp ? Math.floor(Date.parse(d.timestamp) / 1000) || null : null;
        if (ts) { out.lastActivityTs = ts; tsOpen = false; }
      }
      if (gitOpen && d.gitBranch) { out.gitBranch = d.gitBranch; gitOpen = false; }
      if (cwdOpen && d.cwd) { out.cwd = d.cwd; cwdOpen = false; }
    }
  }

  // ⚠ A HISTORY PAGE DOES NOT DRAW A MESSAGE THAT WAS DELIVERED BELOW IT. A
  // window that ends at `until` sees a mid-turn enqueue but not the remove or
  // dequeue that delivered it minutes of tool output later — and kept it
  // `queued`, floated to the bottom of the page. The page below already drew it:
  // it was read cold (a tail, or a newer history page; `until` is always where
  // one of those began), and a cold read draws a delivery whose enqueue it
  // cannot see as the message itself, at its delivery point. The client
  // prepends pages without reconciling them, so the view showed it twice, one
  // copy forever claiming to be waiting. Only a message nothing below delivered
  // is still waiting, and only that one keeps its badge and its float.
  // Not in the window-size probes at the top, which only count.
  if (until != null && _resuming == null && end < st.size && queued.size) {
    const below = deliveredBelow(path, end, st.size, queued);
    if (below.size) out.events = out.events.filter((e) => !below.has(e));
  }

  // Anything STILL waiting floats to the bottom.
  //
  // An enqueue is recorded the moment it is typed, which is mid-turn — so left
  // where it lands, a waiting message sits interleaved above the rest of the
  // answer it was typed during, and above the answer it is waiting for. That is
  // not where the pane shows it and not where a reader looks for it: a message
  // that has not been delivered has not happened yet, so it belongs after
  // everything that has. Relative order among several waiting messages is
  // preserved, because that is the order they will be delivered in.
  const waiting = out.events.filter((e) => e.queued);
  if (waiting.length && waiting.length < out.events.length) {
    out.events = out.events.filter((e) => !e.queued).concat(waiting);
  }

  if (out.events.length > limit) {
    const kept = out.events.slice(-limit);
    // windowStart MOVES with the trim. It is the promise that paging backwards
    // from it loses nothing, and dropping the oldest events while still pointing
    // at the record they came from silently breaks that: the next page ends
    // before them, so they are never returned by any page. Rare — across 400
    // real transcripts on this host the trim never fired, the largest tail being
    // 54 events against a limit of 400 — which is exactly why it would have gone
    // unnoticed.
    const firstKeptAt = recordAt.get(kept[0]);
    if (firstKeptAt != null) out.windowStart = firstKeptAt;
    out.events = kept;
    out.truncated = true;
  }
  // Renumbered last, so list keys stay monotonic after a float or a move. The
  // seq is a per-response ordinal, not an identity that outlives the request.
  out.events.forEach((e, i) => { e.seq = i + 1; });
  return out;
}

function emptyResult(offset, truncated) {
  return {
    events: [], deliveredQueued: [], nextOffset: offset, windowStart: offset, truncated,
    title: null, permissionMode: null, model: null, gitBranch: null, cwd: null,
    effort: null, lastActivityTs: null,
  };
}


/**
 * What a session is in the middle of, judged from its transcript tail.
 *
 * The transcript only speaks when a block completes, so "in the middle of" means
 * an unresolved tool: a call whose result has not landed. Freshness-capped
 * because a cancelled or crashed turn leaves its last call unresolved forever,
 * and a strip claiming "running npm test" about yesterday would be worse than
 * nothing. Subagents are counted the same way — their own unresolved tools —
 * which approximates active agents well, since an agent runs one tool at a time.
 */
const ACTIVITY_FRESH_S = 3600;

function liveActivity(events, nowSec) {
  let tool = null;
  let subagents = 0;
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.kind !== 'tool' || e.result || e.ok !== undefined && e.ok !== null) continue;
    if (e.ts && nowSec - e.ts > ACTIVITY_FRESH_S) break;   // older is only older
    if (e.sidechain) subagents++;
    else if (!tool) tool = { tool: e.name || 'tool', detail: e.detail || null, sinceTs: e.ts || null };
  }
  if (!tool && subagents === 0) return null;
  return { ...(tool || {}), subagents };
}

/**
 * When this conversation last did something a person would call work, in epoch
 * seconds: a message, a tool call or its result, the end of a turn, a
 * compaction. Null when the tail holds none.
 *
 * ⚠ NOT tmux's window_activity, which is what "last activity" used to be. Every
 * redraw of the TUI and every title the state hook writes counts as pane output,
 * so a session idle for a day read "5 minutes ago". Nor the file's mtime: an
 * idle transcript is touched without a record being added (seen on this host,
 * 2026-10-17: mtime 17 minutes old, last record 26 hours old). Only records of
 * these three types carry a timestamp a person did something at.
 *
 * Cached on size and mtime, so a poll of an unchanged transcript reads nothing;
 * a changed one costs one tail read, parsed from the end until the first hit.
 */
const WORK_RECORD_TYPES = new Set(['user', 'assistant', 'system']);
const TIMESTAMP_KEY = Buffer.from('"timestamp"');
const lastWorkCache = new Map();

function lastWorkAt(file) {
  let st;
  try { st = fs.statSync(file); } catch { return null; }
  const hit = lastWorkCache.get(file);
  if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs) return hit.at;
  let at = null;
  // One long tool result can be a whole tail on its own, hence the second try.
  for (const want of [256 * 1024, 4 * 1024 * 1024]) {
    at = lastWorkInTail(file, st.size, want);
    if (at !== null || want >= st.size) break;
  }
  if (lastWorkCache.size > 500) lastWorkCache.clear();
  lastWorkCache.set(file, { size: st.size, mtimeMs: st.mtimeMs, at });
  return at;
}

function lastWorkInTail(file, size, want) {
  const len = Math.min(size, want);
  if (len <= 0) return null;
  const buf = Buffer.alloc(len);
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    fs.readSync(fd, buf, 0, len, size - len);
  } catch { return null; } finally { if (fd !== undefined) try { fs.closeSync(fd); } catch { /* closed */ } }
  // Line by line from the end, on the raw bytes: only a line that can answer is
  // decoded, and a record that names another type first (the `api-request`
  // records Claude Code now writes between turns, up to MBs each) is passed over
  // unread — see [leadingType].
  for (let b = buf.length; ;) {
    const nl = b > 0 ? buf.lastIndexOf(0x0a, b - 1) : -1;
    const a = nl + 1;
    // Started mid-file: the first line is the end of a record, not a record.
    if (a === 0 && size > len) return null;
    if (b > a && buf[a] === 0x7b /* { */ && buf.subarray(a, b).indexOf(TIMESTAMP_KEY) !== -1) {
      const t = leadingType(buf, a, b);
      if (t === null || WORK_RECORD_TYPES.has(t)) {
        let d;
        try { d = JSON.parse(buf.toString('utf8', a, b)); } catch { d = null; }
        if (d && WORK_RECORD_TYPES.has(d.type) && typeof d.timestamp === 'string') {
          const ms = Date.parse(d.timestamp);
          if (Number.isFinite(ms)) return Math.floor(ms / 1000);
        }
      }
    }
    if (nl === -1) return null;
    b = nl;
  }
}

/**
 * What a LIST row takes from a transcript: Claude Code's own title and the
 * permission mode, exactly as `readTranscript(file, { limit: 1 })` reports them.
 *
 * ⚠ CACHED ON SIZE AND MTIME, like lastWorkAt. The session list and the chat
 * list are polled by every client every few seconds, and both read this for
 * every row on every poll — a tail read and parse per session or chat, of a
 * file that had not changed since the last poll. A row whose transcript has not
 * moved now costs one stat.
 */
const headCache = new Map();

function transcriptHead(file) {
  const st = fs.statSync(file);
  const hit = headCache.get(file);
  if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs) return hit.head;
  const t = readTranscript(file, { limit: 1 });
  const head = { title: t.title, permissionMode: t.permissionMode };
  if (headCache.size > 500) headCache.clear();
  headCache.set(file, { size: st.size, mtimeMs: st.mtimeMs, head });
  return head;
}

module.exports = { readTranscript, digestToolInput, workflowName, textOf, liveActivity, machineText, describeMachineText, humanRemainder, parseAsk, startsAtBoundary,
  injectedByTool, typedByHuman, notificationEcho, skillNameFromBody,
  peerBody, peerNote, peerNoteFromQueue, projectRelayNote, unwrapPasted, lastWorkAt, transcriptHead,
  firstElement, CROSS_TAG, AGENT_TAG,
};
