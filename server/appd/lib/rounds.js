'use strict';
// Rounds: work this host does on a schedule, and the report it comes back with.
//
// A Round is not a new execution engine. It is "post this message to a chat on
// this cadence" — the headless chat path already spawns `claude -p`, injects the
// unattended persona, streams, pushes on finish and stores a transcript. What did
// not exist was a way to ASK for that on a schedule and get an answer back in a
// shape a person can act on without reading the whole run.
//
// Everything here is pure: schedule arithmetic, the report contract and its
// parser, and the notify decision. The daemon owns the files, the spawn and the
// delivery; this owns the rules, so they can be tested without a tmux server, a
// clock, or a network.

// ---------------------------------------------------------------- schedules
//
// A STRUCTURED schedule, not a cron string. Three reasons, in order of how much
// they have already cost somebody:
//
//   * DST. The briefing cron ran at 2pm for months because its hours were written
//     as if UTC on a box in America/Los_Angeles. A schedule that carries its own
//     zone and is resolved through Intl cannot drift into that.
//   * it renders itself ("Sundays at 7:00 PM") with no parser on the client, and
//     it is pickable on a phone, which a cron string is not.
//   * the four cadences below cover every scheduled job on the author's host —
//     two daily briefings, two weekly Sunday jobs, one 4-hourly scan.

const KINDS = ['daily', 'weekly', 'monthly', 'interval'];
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DOW_LONG = ['Sundays', 'Mondays', 'Tuesdays', 'Wednesdays', 'Thursdays', 'Fridays', 'Saturdays'];
const AT_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** How late a missed fire may be and still run. Beyond this it needs `catchUp`. */
const MISSED_GRACE_MS = 10 * 60 * 1000;

const MIN_INTERVAL_MIN = 5;
const MAX_INTERVAL_MIN = 7 * 24 * 60;

/** The wall-clock parts of an instant, as that zone sees them. */
function partsIn(tz, epochMs) {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const o = {};
  for (const p of f.formatToParts(new Date(epochMs))) o[p.type] = p.value;
  return {
    y: Number(o.year), mo: Number(o.month), d: Number(o.day),
    // en-US with hour12:false renders midnight as "24" in some ICU builds, which
    // silently shifts a 00:xx schedule by a day if taken at face value.
    h: Number(o.hour) % 24,
    mi: Number(o.minute), s: Number(o.second),
  };
}

/** What `tz` was offset from UTC at that instant, DST included. */
function offsetMs(tz, epochMs) {
  const p = partsIn(tz, epochMs);
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - epochMs;
}

/**
 * The instant at which `tz` reads exactly this wall-clock time.
 *
 * Two passes, because the offset needed to do the conversion is itself a function
 * of the answer: guess with the offset at the naive instant, then re-read the
 * offset at the guess and correct. One pass is wrong for any time within an
 * offset's distance of a DST boundary — precisely the 1-2 am window a nightly
 * job is most likely to be scheduled in.
 */
function epochForWallClock(tz, y, mo, d, h, mi) {
  const naive = Date.UTC(y, mo - 1, d, h, mi, 0);
  const first = naive - offsetMs(tz, naive);
  const t = naive - offsetMs(tz, first);
  // The two-pass above is right for every wall-clock time that EXISTS. One class
  // does not: the hour a spring-forward deletes.
  //
  // ⚠ AND WHEN THAT GAP BEGINS AT LOCAL MIDNIGHT, the resolution lands on the
  // PREVIOUS local day — 23:xx — which is a different date, a different weekday
  // and a different day-of-month. America/Havana, America/Santiago and
  // Atlantic/Azores all do this. A "Daily at 12:00 AM" Round then ran twice on
  // one calendar day and not at all on the transition day; "Sundays at 12:00 AM"
  // fired on SATURDAY while the row still read Sundays; "Monthly on the 8th"
  // fired on the 7th. Silent on every surface, because the daemon renders the
  // cadence from the schedule and the schedule was never wrong.
  //
  // So: if the instant does not land on the day that was asked for, take the
  // other candidate — the far side of the transition, which is the first instant
  // that day actually has. Eastern-offset gaps already resolve forward and keep
  // their date, which is why Cairo, Chatham, Troll and Lord Howe were correct.
  if (onDay(tz, t, y, mo, d)) return t;
  if (onDay(tz, first, y, mo, d)) return first;
  // Neither side is on the right day, which should not happen for any real zone.
  // Later of the two: a Round that fires late is recoverable, one that fires on
  // the wrong date is a job silently running on a day nobody scheduled.
  return Math.max(t, first);
}

/** Whether an instant falls on this local calendar day in `tz`. */
function onDay(tz, ms, y, mo, d) {
  const p = partsIn(tz, ms);
  return p.y === y && p.mo === mo && p.d === d;
}

/**
 * The next instant this schedule fires strictly after `afterMs`, or null.
 *
 * Walks CALENDAR days rather than adding 86_400_000 repeatedly: a 24-hour step
 * across a spring-forward lands on the day after next, skipping a day entirely,
 * so a Round scheduled for that weekday would silently not run that week.
 * `Date.UTC` normalises an overflowing day-of-month for us (Jan 32 -> Feb 1),
 * which is also what makes the monthly case fall out for free.
 */
function nextFireAt(schedule, afterMs) {
  const s = schedule || {};
  if (s.kind === 'interval') {
    const every = Number(s.everyMinutes) * 60_000;
    return Number.isFinite(every) && every > 0 ? afterMs + every : null;
  }
  const m = AT_RE.exec(String(s.at || ''));
  if (!m) return null;
  const h = Number(m[1]); const mi = Number(m[2]);
  const base = partsIn(s.tz, afterMs);
  // 400 days covers a monthly Round asking for the 31st, which some months skip.
  for (let i = 0; i <= 400; i++) {
    const day = new Date(Date.UTC(base.y, base.mo - 1, base.d + i));
    const y = day.getUTCFullYear(); const mo = day.getUTCMonth() + 1; const d = day.getUTCDate();
    if (s.kind === 'weekly' && !s.days.includes(day.getUTCDay())) continue;
    if (s.kind === 'monthly' && !s.dates.includes(d)) continue;
    const t = epochForWallClock(s.tz, y, mo, d, h, mi);
    if (t > afterMs) return t;
  }
  return null;
}

function isKnownZone(tz) {
  if (typeof tz !== 'string' || !tz) return false;
  try { new Intl.DateTimeFormat('en-US', { timeZone: tz }); return true; } catch { return false; }
}

/** @returns {{ok: true, schedule: object} | {ok: false, error: string}} */
/**
 * @param defaultTz used when the caller names no zone.
 *
 * A schedule cannot fire without one, and a client cannot always produce one:
 * the shared UI code is multiplatform and has no calendar. So an absent zone
 * means THIS HOST's zone — which is where the Round fires and whose DST rules it
 * obeys, so it is the honest default rather than a guess. A client that does know
 * its own zone still sends it and still wins.
 */
function validateSchedule(raw, defaultTz = null) {
  const s = raw && typeof raw === 'object' ? raw : {};
  if (!KINDS.includes(s.kind)) return { ok: false, error: `schedule.kind must be one of ${KINDS.join(', ')}` };

  if (s.kind === 'interval') {
    const n = Number(s.everyMinutes);
    if (!Number.isInteger(n) || n < MIN_INTERVAL_MIN || n > MAX_INTERVAL_MIN) {
      return { ok: false, error: `schedule.everyMinutes must be ${MIN_INTERVAL_MIN}-${MAX_INTERVAL_MIN}` };
    }
    // ⚠ THE ZONE IS KEPT even though an interval does not use it. Dropping it
    // DESTROYED it: `toDraft()` seeds the editor from `schedule.tz`, so a Round
    // toggled to Interval and back came out in whatever zone the editing device
    // happened to be in — a `9:00 AM Asia/Tokyo` Round landing on Los Angeles,
    // eight hours out, with no way to recover the original because it was gone.
    // Carried, not used; the cost is one field and it makes the toggle lossless.
    const tz = (typeof s.tz === 'string' && s.tz.trim()) ? s.tz.trim() : defaultTz;
    const out = { kind: 'interval', everyMinutes: n };
    if (isKnownZone(tz)) out.tz = tz;
    return { ok: true, schedule: out };
  }

  if (!AT_RE.test(String(s.at || ''))) return { ok: false, error: 'schedule.at must be "HH:MM" (24-hour)' };
  const tz = (typeof s.tz === 'string' && s.tz.trim()) ? s.tz.trim() : defaultTz;
  if (!isKnownZone(tz)) return { ok: false, error: 'schedule.tz must be an IANA zone, e.g. America/Los_Angeles' };
  const out = { kind: s.kind, at: s.at, tz };

  if (s.kind === 'weekly') {
    const days = Array.isArray(s.days) ? [...new Set(s.days.map(Number))].sort((a, b) => a - b) : [];
    if (!days.length || days.some((d) => !Number.isInteger(d) || d < 0 || d > 6)) {
      return { ok: false, error: 'schedule.days must be a non-empty list of 0-6 (0 = Sunday)' };
    }
    out.days = days;
  }
  if (s.kind === 'monthly') {
    const dates = Array.isArray(s.dates) ? [...new Set(s.dates.map(Number))].sort((a, b) => a - b) : [];
    if (!dates.length || dates.some((d) => !Number.isInteger(d) || d < 1 || d > 31)) {
      return { ok: false, error: 'schedule.dates must be a non-empty list of 1-31' };
    }
    out.dates = dates;
  }
  return { ok: true, schedule: out };
}

function clockWords(at) {
  const m = AT_RE.exec(at);
  if (!m) return at;
  const h = Number(m[1]);
  const suffix = h < 12 ? 'AM' : 'PM';
  return `${h % 12 === 0 ? 12 : h % 12}:${m[2]} ${suffix}`;
}

function ordinal(n) {
  const rem100 = n % 100;
  if (rem100 >= 11 && rem100 <= 13) return `${n}th`;
  return `${n}${['th', 'st', 'nd', 'rd'][n % 10] || 'th'}`;
}

/** The cadence in words, so no client has to own a second copy of these rules. */
function describeSchedule(s) {
  if (!s || !s.kind) return '';
  if (s.kind === 'interval') {
    const n = s.everyMinutes;
    if (n % 1440 === 0) return n === 1440 ? 'Every day' : `Every ${n / 1440} days`;
    if (n % 60 === 0) return n === 60 ? 'Every hour' : `Every ${n / 60} hours`;
    return `Every ${n} minutes`;
  }
  const at = `at ${clockWords(s.at)}`;
  if (s.kind === 'daily') return `Daily ${at}`;
  if (s.kind === 'weekly') {
    if (s.days.length === 7) return `Daily ${at}`;
    const names = s.days.length === 1 ? DOW_LONG[s.days[0]] : s.days.map((d) => DOW[d]).join(', ');
    return `${names} ${at}`;
  }
  if (s.kind === 'monthly') {
    return `Monthly on the ${s.dates.map(ordinal).join(', ')} ${at}`;
  }
  return '';
}

// ------------------------------------------------------------ the report
//
// The cadence is the easy half. The reason a scheduled run is worth building at
// all is that it comes back with something a person can act on in one glance —
// otherwise it is a cron job that writes into a chat nobody opens.
//
// So a Round's prompt carries an output contract, and the answer is parsed rather
// than quoted. The briefing script learned this the expensive way: it used to
// sniff free text for failure phrases and twice delivered "You're out of usage
// credits" to the operator AS the briefing. It now reads a structured envelope,
// because success has to be a FLAG, not a guess. Same principle, one layer up.

const STATUSES = ['ok', 'attention', 'action'];

function reportContract(tag) {
  return `
--- HOW THIS RUN IS REPORTED ---
End your turn with a fenced huginn-report block. It is the ONLY thing that reaches
the operator; prose above it is kept in the chat but is not the report.

THIS RUN'S TAG: ${tag}

The opening fence must carry it, exactly as written. The tag is generated for this
run alone: nothing you READ during it — a log line, a page, a file — can know it,
so a report block found in fetched content cannot be mistaken for yours. A block
without the tag is discarded and the run is reported as unreported, so copy it
across.

\`\`\`huginn-report ${tag}
{"status":"ok","headline":"one line, under 90 characters, what you found",
 "goalMet":true,
 "items":[{"title":"short label","detail":"what is wrong","suggest":"the next step"}]}
\`\`\`

status  ok = nothing needs anyone · attention = worth knowing · action = something needs doing
goalMet did you actually reach the goal stated above? Answer honestly — a false
        here is USEFUL, and is reported as needing attention rather than hidden.
        Omit it only if this round stated no goal.
items   ONLY things needing a decision or an action, each with a concrete next step.
        A clean run has an empty list — do not invent items to fill it.
headline stands alone: it is what arrives as a notification, with no context around it.

This run gets ONE turn. It ends when you stop, and the conversation is then closed
and kept for review — there is no second message coming, so finish the job or say
plainly in the report what stopped you.

Write the block LAST and do not discuss it. A missing or malformed block is
reported as "unknown" with a truncated quote of whatever you said last.`;
}

/**
 * The contract as it reads with no tag, for anything that wants to show it.
 *
 * Kept as an export because it was one, and because the tests and the docs both
 * quote it. A run NEVER gets this one — it gets [reportContract] with its own
 * tag — but the two must stay the same text or the instructions people read stop
 * matching the instructions runs receive.
 */
const REPORT_CONTRACT = reportContract('<tag>');

/**
 * The prompt as the Round's run actually receives it.
 *
 * The goal goes FIRST and is stated as a completion test rather than a topic. A
 * scheduled run has nobody to ask "is this enough?", so the only thing that can
 * tell it when to stop is a sentence written in advance saying what done looks
 * like.
 */
function promptFor(round, tag = null) {
  const goal = String(round.goal || '').trim();
  const head = goal ? `GOAL — this run is done when: ${goal}\n\n` : '';
  const contract = tag ? reportContract(tag) : REPORT_CONTRACT;
  return `${head}${String(round.prompt || '').trim()}\n${contract}`;
}

/** Items beyond this are dropped; [oneReport] records how many there really were. */
const MAX_ITEMS = 20;

// C0 and C1 controls plus DEL. Kept as one definition because two copies of a
// character class is how one renderer ends up stripping ESC and the other not.
const CTRL_ALL = /[\u0000-\u001f\u007f-\u009f]/g;
const CTRL_KEEP_NL = /[\u0000-\u0009\u000b-\u001f\u007f-\u009f]/g;

/**
 * A value that will be shown as ONE line: controls gone, whitespace collapsed.
 *
 * ⚠ Report text is written by a model, and a model writes what it read — a log
 * line, a fetched page, a filename. So this is not only reachable by whoever
 * holds the API token, and the terminal is a renderer that EXECUTES some of what
 * it is handed. A round titled
 *
 *     Nightly scan\x1b[2K\rALL CLEAR
 *
 * erases its own line and reprints, so `huginn rounds` showed ALL CLEAR for a
 * round holding a DISK FULL headline and two action items. A newline in a "short
 * label" split one finding into two rows just as effectively.
 *
 * Stripped at STORE time rather than only at render time because these three
 * fields are single-line by contract — a headline that "stands alone as a
 * notification" has no legitimate newline in it — and because the daemon is not
 * the only thing that ever prints them. The renderers strip again anyway: an
 * older daemon and a hand-edited store are both real.
 */
function oneLine(v, max) {
  if (typeof v !== 'string') return '';
  return v.replace(CTRL_ALL, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

/**
 * Free text that may legitimately run to several lines — a next step is often a
 * command block. Newlines survive; everything that moves a cursor does not.
 */
function safeText(v, max) {
  if (typeof v !== 'string') return '';
  return v.replace(CTRL_KEEP_NL, ' ').replace(/[ \t]+/g, ' ').trim().slice(0, max);
}

function cleanItem(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const title = oneLine(raw.title, 120);
  if (!title) return null;
  return {
    title,
    detail: safeText(raw.detail, 1000),
    suggest: safeText(raw.suggest, 500),
  };
}

const OPEN_FENCE = /^ {0,3}(`{3,})huginn-report(?:[ \t]+([A-Za-z0-9_-]{1,64}))?[ \t]*$/;
const CLOSE_FENCE = /^ {0,3}(`{3,})[ \t]*$/;

/**
 * Every CLOSED huginn-report block in a run's text, in order.
 *
 * ⚠ THIS IS A LINE SCANNER AND NOT A REGEX ON PURPOSE, and the regex it replaced
 * was the single most damaging bug in the report channel.
 *
 *     /```huginn-report[ \t]*\r?\n([\s\S]*?)```/g
 *
 * The non-greedy tail stops at the first ``` ANYWHERE, including one inside a
 * JSON string. The report contract asks every item for "the next step", and for
 * an ops round the next step is a command — so the moment a run wrote
 *
 *     {"status":"action","headline":"PBS sync failing 6 days",
 *      "items":[{"title":"...","suggest":"run:\n```bash\nproxmox-backup-manager sync\n```"}]}
 *
 * the block was cut at that inner fence, JSON.parse failed, and the whole report
 * — action status, headline, every item — was replaced by a fallback that
 * notifies as "unknown". The operator got a line that reads like a broken run
 * instead of the thing that actually needed them. The most likely report to
 * destroy was the one that mattered most.
 *
 * A closing fence is a line that is NOTHING BUT backticks, which is CommonMark's
 * own rule. That is exactly what distinguishes the real terminator from a fence
 * inside a string: JSON cannot contain a literal newline inside a string, so a
 * quoted ``` is always mid-line and can never be mistaken for the end.
 *
 * An UNTERMINATED block is not returned — it is not a block, it is a run that
 * stopped mid-sentence — but [fenceOpensAt] lets the fallback cut it off the
 * prose so half a JSON object cannot become somebody's notification.
 */
function reportBlocks(text) {
  const out = [];
  let fence = null; let body = null; let tag = null;
  for (const line of String(text).split(/\r?\n/)) {
    if (fence === null) {
      const m = OPEN_FENCE.exec(line);
      if (m) { fence = m[1]; tag = m[2] || null; body = []; }
      continue;
    }
    const c = CLOSE_FENCE.exec(line);
    if (c && c[1].length >= fence.length) {
      out.push({ body: body.join('\n'), tag });
      fence = null; body = null; tag = null;
      continue;
    }
    body.push(line);
  }
  return out;
}

/** Where an unterminated report block starts, or -1. Used to cut debris off prose. */
function fenceOpensAt(text) {
  const lines = String(text).split(/\r?\n/);
  let at = -1; let fence = null; let pos = 0;
  for (const line of lines) {
    if (fence === null) {
      const m = OPEN_FENCE.exec(line);
      if (m) { fence = m[1]; at = pos; }
    } else {
      const c = CLOSE_FENCE.exec(line);
      if (c && c[1].length >= fence.length) { fence = null; at = -1; }
    }
    pos += line.length + 1;
  }
  return fence === null ? -1 : at;
}

/**
 * The headline in REPORT_CONTRACT's own example.
 *
 * ⚠ The instructions are a syntactically complete report. `parseReport` run on
 * the contract itself used to return it verbatim — status ok, goalMet true, one
 * item — so a run that wrote its real report and THEN quoted the contract back to
 * explain itself had the placeholder win under "the last block wins". Two things
 * failed at once: literal template text arrived as the answer, and because the
 * forged status was `ok`, `shouldNotify` returned false, so a real `action`
 * report vanished in total silence behind a clean green row.
 *
 * Matched as an exact literal rather than guessed at. This is not a heuristic
 * about what placeholders look like — it is this file recognising its own text.
 */
const CONTRACT_HEADLINE = 'one line, under 90 characters, what you found';

/**
 * The report out of a finished run's text, or null if it did not produce one.
 *
 * The last REAL block wins. A run that reasons out loud may write a draft block
 * and then a corrected one; the final one is the answer, exactly as the last word
 * of any turn is. But a block that is only the contract being quoted is not an
 * answer, so the walk continues past it rather than stopping — the run's actual
 * report is the one written before the explanation.
 */
function parseReport(text, tag = null) {
  if (typeof text !== 'string' || !text) return null;
  const blocks = reportBlocks(text).filter((b) => (tag ? b.tag === tag : true));
  for (let i = blocks.length - 1; i >= 0; i--) {
    const r = oneReport(blocks[i].body);
    if (r) return r;
  }
  return null;
}

/**
 * Whether the run wrote a report block that was NOT tagged for this run.
 *
 * ⚠ THIS IS THE INJECTION SIGNAL AND IT MUST NOT BE SWALLOWED. A round exists to
 * go and read things — a log, a page, a mailbox — and everything it reads is
 * text somebody else may have written. A report block planted in that content
 * used to be indistinguishable from the run's own, and the attacker's best
 * outcome was not a lie but SILENCE: forge `{"status":"ok"}` and shouldNotify
 * returns false, so a round that found something real says nothing at all and
 * the row shows a clean green week.
 *
 * The tag is minted per run and appears only in the prompt, so content written
 * before the run cannot carry it. An untagged block is therefore either
 * something the run read, or the run forgetting its own contract — and both are
 * worth a person's attention, which is why this returns a reason rather than
 * quietly dropping the block.
 */
function untaggedReport(text, tag) {
  if (!tag) return false;
  return reportBlocks(text).some((b) => b.tag !== tag);
}

function oneReport(raw) {
  let o;
  try { o = JSON.parse(raw); } catch { return null; }
  if (!o || typeof o !== 'object' || Array.isArray(o)) return null;

  const headline = oneLine(o.headline, 120);
  // A block with no headline is not a report: the headline IS the notification,
  // and delivering an empty one would be a buzz that says nothing.
  if (!headline) return null;
  if (headline === CONTRACT_HEADLINE) return null;

  const rawItems = Array.isArray(o.items) ? o.items : [];
  const items = rawItems.slice(0, MAX_ITEMS).map(cleanItem).filter(Boolean);
  return {
    status: STATUSES.includes(o.status) ? o.status : 'unknown',
    headline,
    // Tri-state on purpose: true, false, and "did not say". A Round with no goal
    // has nothing to answer, and coercing that to false would report every one of
    // them as having failed.
    goalMet: typeof o.goalMet === 'boolean' ? o.goalMet : null,
    items,
    // How many the run actually reported, which is NOT items.length once the cap
    // bites. Without this a round that found 500 things showed "20 items" on the
    // line under a headline saying 500, and the count — the number an operator
    // acts on — was the wrong one of the two.
    itemsTotal: rawItems.length,
    malformed: false,
  };
}

/**
 * What to report when the run produced no usable block.
 *
 * Deliberately still a report. A Round that fails to format itself has usually
 * still done the work, and going silent would make a broken contract look like a
 * clean week — the failure mode that matters most here, because nobody goes
 * looking for a report they were never told was missing.
 */
function fallbackReport(text, why = 'no huginn-report block') {
  let src = String(text || '');
  // Cut an unterminated report block off the end FIRST. A run killed mid-block
  // leaves `{"status":"action","headline":"…` with no closing fence, and the old
  // stripper — the same non-greedy regex that broke the parser — left it in, so
  // the operator was buzzed with half a JSON object as the notification text.
  const open = fenceOpensAt(src);
  if (open >= 0) src = src.slice(0, open);
  // Then every closed fenced block, by the same line rule the parser uses, so a
  // fence quoted inside one cannot make the stripper leave the other half behind.
  src = stripFences(src);
  const flat = oneLine(src, 120);
  return {
    status: 'unknown',
    headline: flat || `run produced no output (${why})`,
    goalMet: null,
    items: [],
    itemsTotal: 0,
    malformed: true,
  };
}

/** Removes every closed fenced block, matching fences by CommonMark's line rule. */
function stripFences(text) {
  const out = []; let fence = null; let held = [];
  for (const line of String(text).split(/\r?\n/)) {
    if (fence === null) {
      const m = /^ {0,3}(`{3,})/.exec(line);
      if (m) { fence = m[1]; held = [line]; continue; }
      out.push(line);
      continue;
    }
    held.push(line);
    const c = CLOSE_FENCE.exec(line);
    if (c && c[1].length >= fence.length) { fence = null; held = []; }
  }
  // An unterminated fence at this point is prose that happened to start with
  // backticks, not a block — keeping it is kinder than deleting the only text.
  for (const l of held) out.push(l);
  return out.join('\n');
}

/** A run that never got as far as an answer. */
function errorReport(why) {
  return {
    status: 'action',
    headline: `run failed: ${oneLine(why, 100) || 'unknown error'}`,
    goalMet: false,
    items: [],
    itemsTotal: 0,
    malformed: true,
  };
}

/**
 * The status a Round's row should actually show.
 *
 * A run that says "ok" while admitting it did not reach its goal has not had a
 * clean week — it has quietly not done the job, which is the failure most worth
 * surfacing because nothing else about it looks wrong. So an unmet goal lifts a
 * clean status to `attention`; it never lowers anything.
 */
function effectiveStatus(report) {
  if (!report) return 'unknown';
  if (report.goalMet === false && report.status === 'ok') return 'attention';
  return report.status;
}

/**
 * Whether a run's report has been read and dealt with.
 *
 * ⚠ THE ACKNOWLEDGEMENT LIVES ON THE RUN, NOT ON THE ROUND, and that is the
 * whole design. `lastRun` is replaced wholesale when a Round fires again, so a
 * new report arrives un-acknowledged for free — there is no way to mark a Round
 * "done" and have it stay quiet through next week's findings. Storing this on the
 * Round would need code to remember to clear it, and that code would eventually
 * not run.
 *
 * A clean `ok` run is never offered the control: there is nothing to acknowledge
 * on an all-clear, and a button that appears on every row is a button nobody
 * reads.
 */
function isAcknowledged(run) {
  return !!run && typeof run.acknowledgedAt === 'number' && run.acknowledgedAt > 0;
}

/** Whether there is a report here that a person could mark as dealt with. */
function canAcknowledge(run) {
  if (!run) return false;
  if (isAcknowledged(run)) return false;
  return effectiveStatus(run) !== 'ok';
}

/**
 * Whether this report is worth interrupting somebody for.
 *
 * `attention` is the default rather than `always` because the weekly jobs this
 * replaces are silent by design when there is nothing to say — the crystallizer's
 * own note is "silent when there is nothing to say, which is the normal outcome".
 * A Round that pings every Sunday to say "ok" trains its reader to ignore it, and
 * then the one that says `action` is ignored too.
 *
 * `unknown` DOES notify: a report that could not be parsed is a report that needs
 * a human, not one to be quietly dropped.
 */
function shouldNotify(notifyWhen, status) {
  if (notifyWhen === 'never') return false;
  if (notifyWhen === 'always') return true;
  return status !== 'ok';
}

/**
 * Whether a Round is due, and when it should next be armed.
 *
 * The interesting case is a fire that was MISSED — the daemon was restarted, the
 * host was down, the pool was full for a while. Running it hours late is right for
 * a weekly review and wrong for a 9am post, and only the owner of the Round knows
 * which, so `catchUp` decides and the default is to skip. Either way the Round is
 * re-armed to its next real slot, so a missed fire never repeats.
 */
function dueDecision(round, nowMs) {
  const due = Number(round.nextRunAt) || 0;
  if (!round.enabled) return { run: false, nextRunAt: due, reason: 'disabled' };
  if (!due) return { run: false, nextRunAt: nextFireAt(round.schedule, nowMs), reason: 'armed' };
  if (due > nowMs) return { run: false, nextRunAt: due, reason: 'waiting' };

  const lateBy = nowMs - due;
  if (lateBy <= MISSED_GRACE_MS || round.catchUp) {
    return { run: true, nextRunAt: nextFireAt(round.schedule, nowMs), lateBy, reason: 'due' };
  }
  return { run: false, nextRunAt: nextFireAt(round.schedule, nowMs), lateBy, reason: 'missed' };
}

/**
 * How a report READS, for every channel that announces it.
 *
 * ⚠ ONE FUNCTION BECAUSE THERE WERE TWO, AND THEY DISAGREED. The push led with
 * "did not finish — " while the Telegram fallback indexed the REPORTED status,
 * so an `ok` report with `goalMet:false` — the single case this design calls out
 * as most worth surfacing — arrived as a green tick and a clean sentence. On the
 * channel used exactly when the app is not there to show the warning row.
 *
 * `status` is the EFFECTIVE one, so a promotion reaches every surface; `text`
 * carries the unmet goal in words, because a headline can be perfectly cheerful
 * about a job that did not finish.
 */
function reportDisplay(report) {
  return {
    status: effectiveStatus(report),
    text: report.goalMet === false ? `did not finish — ${report.headline}` : report.headline,
  };
}

module.exports = {
  KINDS, MISSED_GRACE_MS, STATUSES, REPORT_CONTRACT, reportDisplay,
  partsIn, offsetMs, epochForWallClock,
  nextFireAt, validateSchedule, describeSchedule, clockWords,
  promptFor, parseReport, fallbackReport, errorReport, effectiveStatus,
  shouldNotify, dueDecision,
  isAcknowledged, canAcknowledge,
  reportBlocks, fenceOpensAt, stripFences, oneLine, safeText,
  MAX_ITEMS, CONTRACT_HEADLINE, reportContract, untaggedReport,
};
