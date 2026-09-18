'use strict';
// Keep-awake — one tiny request, whenever no 5-hour window is running.
//
// The owner's note, verbatim: "keep awake small cost session interval so we have
// a constant rotating 5 hour usage period." The mechanism behind it is a fact
// about the endpoint rather than a guess: while no window is running the
// `session` row comes back with `resets_at: null`, and the FIRST request of any
// kind starts a fresh five hours. So a session that begins at 09:00 has burned
// nothing of the window it is about to need, while one that begins ten minutes
// after a ping inherits four hours and fifty minutes of window that is already
// paid for. Keeping a window permanently rotating means the boundary lands while
// nobody is working instead of in the middle of a run.
//
// `session` is ACCOUNT-WIDE (`scope: null` on the wire), which is what makes the
// whole thing cheap: a Haiku turn starts and advances the same window a Fable
// session later uses. Only the weekly pools are model-scoped.
//
// ⚠ THIS FILE SPENDS THE OWNER'S QUOTA. It is the first thing appd does that
// costs money with nobody asking for it, which is why every decision in it is
// pure and asserted, the feature is OFF until switched on by hand, and the
// spend is shown on the Status page rather than only in the journal.
//
// WHY ITS OWN FILE rather than more of lib/headroom.js: headroom.js answers one
// question ("how much room is left, and what should be done about it"), and its
// verdict is a list of actions applied to SESSIONS. This answers a different one
// — whether to spend a sliver on the ACCOUNT — on a clock of its own, with its
// own persisted counters and its own failure vocabulary. Same discipline
// though: everything here is pure. Percentages, settings, sentinels and a clock
// reading in; a verdict out. No fs, no network, no Date.now() that was not
// passed in. huginn-appd.js does the I/O.
//
// ⚠ EVERY TIMESTAMP HERE IS MILLISECONDS.

/**
 * The window this feature exists to keep rotating.
 *
 * Also the minimum gap between two pings, which is what makes the feature
 * idempotent: fire only when no window is running AND the last ping was longer
 * ago than a whole window, and it can fire at most once per window no matter how
 * often the tick runs or how many times the daemon restarts.
 */
const FIVE_HOURS_MS = 5 * 60 * 60 * 1000;

/**
 * Haiku 4.5, pinned by its DATED id rather than the `haiku` alias.
 *
 * The alias is whatever the installed CLI decides it means, and a future release
 * that promotes it to a costlier tier would silently multiply the cost of a
 * feature whose entire justification is that it is cheap. Editable — it is a
 * settings field — but it changes only when somebody changes it.
 */
const DEFAULT_MODEL = 'claude-haiku-4-5-20251001';

/** What the ping says. Short on purpose: the answer is billed too. */
const PROMPT = 'Reply with the single word ok.';

/** Enough for a caged one-turn `-p` on a cold CLI, and not a minute more. */
const TIMEOUT_MS = 60_000;

/** One retry, and only for a spawn that never reached the API. See [afterSpawn]. */
const MAX_RETRIES = 1;

// ------------------------------------------------------------------- clock

/** Minutes since local midnight, 0..1439. LOCAL: quiet hours are the owner's. */
function minutesOfDay(nowMs) {
  const d = new Date(nowMs);
  return d.getHours() * 60 + d.getMinutes();
}

const pad2 = (n) => String(n).padStart(2, '0');

/**
 * `HH:MM` in the HOST's local time.
 *
 * Formatted HERE rather than on a client, deliberately. `:core` is commonMain
 * and has no timezone database; the one time a shell turned an instant into a
 * wall clock by hand it printed UTC as if it were local. The daemon knows what
 * time it is where the machine is, so it says so and the clients render the
 * string verbatim — the same rule the stall line's "resets 10:10pm" already
 * follows.
 */
function clockOf(nowMs) {
  const d = new Date(nowMs);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** `YYYY-MM-DD` in local time — "today" has to mean the owner's day. */
function dayKey(nowMs) {
  const d = new Date(nowMs);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

// ------------------------------------------------------------ quiet hours

/**
 * `"01:00-07:00"` → `{from: 60, to: 420}`, or null.
 *
 * Null for absent, empty, and for anything that is not two `HH:MM` clock times:
 * a spec that cannot be parsed must read as NO quiet hours rather than as a
 * silent all-day veto, because a feature that stops working with nothing on
 * screen to say why is worse than one that ignores a typo.
 *
 * An en dash is accepted alongside the hyphen. It is what a phone keyboard
 * produces from two taps and what the owner's own prose uses, and refusing it
 * would be a form arguing with its own copy.
 */
function parseQuietHours(spec) {
  if (spec === null || spec === undefined) return null;
  const raw = String(spec).trim();
  if (!raw) return null;
  const m = /^(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})$/.exec(raw);
  if (!m) return null;
  const fromH = Number(m[1]);
  const fromM = Number(m[2]);
  const toH = Number(m[3]);
  const toM = Number(m[4]);
  if (fromH > 23 || toH > 23 || fromM > 59 || toM > 59) return null;
  const from = fromH * 60 + fromM;
  const to = toH * 60 + toM;
  // A range that starts and ends on the same minute is either nothing or
  // everything, and no reader agrees on which.
  if (from === to) return null;
  return { from, to };
}

/**
 * Is this minute inside the quiet window?
 *
 * ⚠ THE RANGE MAY CROSS MIDNIGHT, and that is the ordinary case rather than the
 * edge one: `22:00-07:00` is what somebody means by quiet hours, and read as a
 * plain `from <= m < to` it is empty — the feature would keep pinging all night
 * and the knob would look broken. `from > to` is the tell, and the test on it is
 * a disjunction rather than a conjunction.
 *
 * Half-open by design: the `from` minute is quiet and the `to` minute is not, so
 * two adjacent ranges cannot both claim the same minute.
 */
function inQuietHours(spec, minutes) {
  const q = parseQuietHours(spec);
  if (!q) return false;
  const m = Number(minutes);
  if (!Number.isFinite(m)) return false;
  if (q.from < q.to) return m >= q.from && m < q.to;
  return m >= q.from || m < q.to;
}

// ------------------------------------------------------------- the record

/** The persisted bookkeeping, as a fresh install has it. */
function blank() {
  return {
    /** When the last ping was SENT — written before the spawn, never after. */
    lastAt: 0,
    /** That instant as the host's own `HH:MM`, for the Status line. */
    lastAtClock: null,
    /** What [lastAt] held before the ping in flight, so a spawn error can undo it. */
    prevAt: 0,
    /** Local day [keptAwakeToday] counts, so it can roll over without a cron. */
    todayKey: null,
    keptAwakeToday: 0,
    keptAwakeTotal: 0,
    /** Retries spent on the current attempt. Reset by any landed ping. */
    retries: 0,
    /** `ok` | `hold` | `retry` — what the last attempt did. Journal fodder. */
    lastOutcome: null,
    /**
     * [decide]'s reason from the last tick, and the only place the owner can
     * read why a feature they switched on is doing nothing.
     *
     * Published rather than merely logged because "it is not pinging" has a
     * dozen legitimate causes — a window already running, a red week, a
     * sentinel, quiet hours — and a Status line that cannot tell them apart
     * sends the reader to the journal of a daemon they may not have a shell on.
     */
    why: null,
    /** When that reason was reached. Distinguishes a stale `why` from a live one. */
    evaluatedAt: 0,
  };
}

/**
 * A record read off disk, brought up to shape.
 *
 * Field by field rather than a spread over [blank], because the file is written
 * by an OLDER daemon as often as by this one and a half-written number has to
 * degrade to zero rather than to `NaN` — which would compare false against every
 * threshold below and turn the once-per-window guard off entirely.
 */
function normalize(o) {
  const src = o && typeof o === 'object' ? o : {};
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const str = (v) => (typeof v === 'string' && v ? v : null);
  return {
    lastAt: num(src.lastAt),
    lastAtClock: str(src.lastAtClock),
    prevAt: num(src.prevAt),
    todayKey: str(src.todayKey),
    keptAwakeToday: num(src.keptAwakeToday),
    keptAwakeTotal: num(src.keptAwakeTotal),
    retries: num(src.retries),
    lastOutcome: str(src.lastOutcome),
    why: str(src.why),
    evaluatedAt: num(src.evaluatedAt),
  };
}

/** Record [decide]'s answer, whichever way it went. */
function noteDecision(ka, verdict, nowMs) {
  return { ...normalize(ka), why: (verdict && verdict.why) || null, evaluatedAt: nowMs };
}

/** The count for [nowMs]'s day — zero once the day has turned over. */
function todayCount(ka, nowMs) {
  const r = normalize(ka);
  return r.todayKey === dayKey(nowMs) ? r.keptAwakeToday : 0;
}

// ------------------------------------------------------------- the verdict

/**
 * Should a ping go out on this tick, and if not, why not.
 *
 * Every clause is a veto and every veto has words, because the `why` is the only
 * place the owner can ever read why a feature they switched on is doing nothing.
 * The order is cheapest-and-most-ordinary first: "it is off" and "a window is
 * already running" are the answer almost every time this is called, and the
 * expensive-to-explain safety vetoes below them are the rare ones.
 *
 * @param settings the merged headroom settings.
 * @param windows `windowsOf()` on the ACTIVE account's limits.
 * @param worst `worstWindow(windows, settings)`, or null. Passed in rather than
 *   computed so this file needs nothing from lib/headroom.js.
 * @param sentinels `{STOP, 'STOP-FABLE'}` — a truthy value is an ARMED sentinel.
 * @param keepAwake the persisted record.
 * @param actions this tick's arbiter verdict, so a ping cannot land on an
 *   account the daemon is about to leave.
 * @param arbiter `{lastSwitchAt}`.
 * @param planError `planCache.error`, or null.
 * @param haveReading whether the plan cache holds a reading AT ALL.
 * @param now epoch ms.
 * @param minutes minutes since local midnight.
 * @returns {{fire: boolean, why: string}}
 */
function decide({
  settings = {},
  windows = null,
  worst = null,
  sentinels = null,
  keepAwake = null,
  actions = [],
  arbiter = null,
  planError = null,
  haveReading = false,
  now = 0,
  minutes = 0,
} = {}) {
  if (!settings.keepAwake) return { fire: false, why: 'keep-awake is off' };

  // We must be able to SEE the window before deciding it is not running. An
  // unreadable endpoint and an idle account look identical from here otherwise,
  // and the failure mode is a ping every tick against an account whose window is
  // running fine.
  if (planError) return { fire: false, why: `cannot read the usage endpoint (${planError})` };
  if (!haveReading) return { fire: false, why: 'no usage reading yet' };

  const session = windows && windows.session;
  if (session && session.resetsAt != null) {
    return { fire: false, why: 'a 5-hour window is already running' };
  }

  // Nothing red. A ping is only ever WANTED at the calmest moment there is, but
  // the weekly pools run on their own clock and can be spent while the 5-hour
  // window is idle — and spending a weekly sliver to start a window nobody can
  // use is the one way this feature makes things worse.
  const mode = worst && worst.mode;
  if (mode && mode !== 'ok') {
    return { fire: false, why: `the ${(worst && worst.label) || 'usage'} window is ${mode}` };
  }

  // A sentinel is the owner's own agents being HELD. Spending window on a ping
  // while huginn is refusing to spawn subagents is indefensible.
  const armed = Object.entries(sentinels || {}).filter(([, v]) => !!v).map(([k]) => k);
  if (armed.length) return { fire: false, why: `${armed.join(' and ')} armed` };

  // A ping is a request on whatever login is ACTIVE, so it must not land while
  // the arbiter is moving off one — the window would be pinned to the account we
  // are leaving. Both halves: the verdict in hand, and the cooldown after one.
  if ((actions || []).some((a) => a && a.type === 'switch_account')) {
    return { fire: false, why: 'an account switch is in flight' };
  }
  const lastSwitchAt = Number((arbiter && arbiter.lastSwitchAt) || 0);
  const cooldown = Number(settings.cooldownMs || 0);
  if (lastSwitchAt > 0 && now - lastSwitchAt < cooldown) {
    return { fire: false, why: 'an account switch is still inside its cooldown' };
  }

  if (inQuietHours(settings.keepAwakeQuietHours, minutes)) {
    return { fire: false, why: `inside quiet hours (${String(settings.keepAwakeQuietHours).trim()})` };
  }

  // The once-per-window guard, and the reason this is idempotent across a
  // restart: `lastAt` is on disk, and it is written BEFORE the spawn.
  const ka = normalize(keepAwake);
  if (ka.lastAt > 0 && now - ka.lastAt < FIVE_HOURS_MS) {
    return { fire: false, why: 'already kept awake inside this window' };
  }
  // ⚠ NO RETRY CLAUSE HERE, and the first draft had one. "retries spent" is
  // exactly the state an ARMED retry is in — `lastAt` back at zero, `retries`
  // at one — so a veto on it cancelled the single retry it was meant to bound.
  // The bound lives in [afterSpawn] instead, where it belongs: a second failed
  // spawn keeps `lastAt`, and the once-per-window guard above does the rest.
  return { fire: true, why: 'no window is running' };
}

// ----------------------------------------------------------------- the ping

/**
 * The argv for one ping.
 *
 * The cage is `lib/suggest`'s and `lib/polish`'s, verbatim, for the same reason
 * they give: no CLAUDE.md (global or project), no tools, one turn, a scratch cwd.
 * This call must never inherit huginn's persona — it is a heartbeat, not an
 * agent. Two additions beyond those two call sites:
 *
 * - `--strict-mcp-config`, so a user-level MCP server cannot be started for a
 *   turn that will never call a tool.
 * - `--no-session-persistence`, so the ping leaves no resumable session and no
 *   transcript for stall detection to find and reason about.
 *
 * `--setting-sources ''` is also what keeps the ping out of the hook gate:
 * `install-hooks.js` writes `SubagentStart`/`PreToolUse` into `~/.claude/settings.json`,
 * which is a USER source and therefore not loaded here.
 *
 * ⛔ NOT `--bare`, ever. Its own help says Anthropic auth is then strictly
 * `ANTHROPIC_API_KEY` or `apiKeyHelper` and that OAuth is never read — it would
 * bill an API key or fail, and either way never touch the subscription window
 * this feature exists to keep open.
 *
 * @param model a full model id or a family alias.
 * @param prompt overridable only so a test can tell one ping from another.
 */
function argvFor(model = DEFAULT_MODEL, prompt = PROMPT) {
  const id = String(model || DEFAULT_MODEL).trim() || DEFAULT_MODEL;
  return [
    '-p',
    '--setting-sources', '',
    '--strict-mcp-config',
    '--no-session-persistence',
    '--model', id,
    '--max-turns', '1',
    '--tools', '',
    '--', String(prompt),
  ];
}

/**
 * Stamp the record as having fired, BEFORE the spawn.
 *
 * Before, never after, and this is the whole of the concurrency story: a daemon
 * that dies mid-ping, or a second tick that starts while the first is still
 * waiting on a 60-second `execFile`, both read a `lastAt` that already says the
 * window has been dealt with. Writing it afterwards makes the ping at-least-once
 * on a crash loop, which for a feature that spends money is the wrong side to
 * fail on.
 */
function noteFired(ka, nowMs) {
  const r = normalize(ka);
  return {
    ...r,
    prevAt: r.lastAt,
    lastAt: nowMs,
    lastAtClock: clockOf(nowMs),
  };
}

/**
 * What kind of failure a finished spawn was.
 *
 * The distinction is not cosmetic — it decides whether the next tick tries
 * again — and it has exactly two answers:
 *
 * - `retry`: the CLI never ran. ENOENT, a permissions error, anything where no
 *   request reached Anthropic. Nothing was spent, so nothing is lost by trying
 *   once more, and the alternative is losing the window to a transient PATH.
 * - `hold`: a request probably DID go out, or one would go out identically on
 *   the next tick. A limit answer, a timeout, a non-zero exit. Retrying any of
 *   those is how a heartbeat becomes a hammer against an account that is already
 *   saying no.
 *
 * A timeout is deliberately `hold` rather than `retry`: the turn may well have
 * landed and only the wait was cut short, and the recovery for "the CLI hangs
 * for a minute" is not "hang for another minute in sixty seconds' time".
 */
function classifyOutcome({ err = null, stdout = '', stderr = '' } = {}) {
  if (!err) return 'ok';
  if (err.killed || err.signal) return 'hold';
  const text = `${stderr || ''}\n${stdout || ''}\n${err.message || ''}`.toLowerCase();
  if (/\b429\b|rate.?limit|usage limit|quota|overloaded/.test(text)) return 'hold';
  const code = err.code || '';
  if (code === 'ENOENT' || code === 'EACCES' || code === 'EPERM') return 'retry';
  // A non-zero exit from a CLI that DID run: it reached far enough to have an
  // opinion, so treat it as spent.
  return 'hold';
}

/**
 * Fold a finished spawn back into the record.
 *
 * `ok` bumps the counters — "kept awake 3× today" counts landed pings, not
 * attempts, or the Status line quietly inflates on a host where the CLI is
 * missing. `hold` leaves `lastAt` where [noteFired] put it, which is what stops
 * the next tick trying again for five hours. `retry` puts `lastAt` back where it
 * was so the next tick fires once more — and only once: the second one is a hold
 * whatever it was, because a spawn that fails twice is a broken host rather than
 * a blip.
 */
function afterSpawn(ka, outcome, nowMs) {
  const r = normalize(ka);
  if (outcome === 'ok') {
    const key = dayKey(nowMs);
    const today = r.todayKey === key ? r.keptAwakeToday : 0;
    return {
      ...r,
      todayKey: key,
      keptAwakeToday: today + 1,
      keptAwakeTotal: r.keptAwakeTotal + 1,
      prevAt: 0,
      retries: 0,
      lastOutcome: 'ok',
    };
  }
  if (outcome === 'retry' && r.retries < MAX_RETRIES) {
    return { ...r, lastAt: r.prevAt, prevAt: 0, retries: r.retries + 1, lastOutcome: 'retry' };
  }
  return { ...r, prevAt: 0, retries: 0, lastOutcome: 'hold' };
}

/** What `GET /v1/status` and `GET /v1/headroom` publish about this feature. */
function view(ka, settings = {}, nowMs = 0) {
  const r = normalize(ka);
  return {
    enabled: !!settings.keepAwake,
    model: settings.keepAwakeModel || DEFAULT_MODEL,
    quietHours: settings.keepAwakeQuietHours || null,
    lastAt: r.lastAt || 0,
    lastAtClock: r.lastAtClock,
    keptAwakeToday: todayCount(r, nowMs),
    keptAwakeTotal: r.keptAwakeTotal,
    lastOutcome: r.lastOutcome,
    why: r.why,
    evaluatedAt: r.evaluatedAt,
  };
}

module.exports = {
  FIVE_HOURS_MS,
  DEFAULT_MODEL,
  PROMPT,
  TIMEOUT_MS,
  MAX_RETRIES,
  minutesOfDay,
  clockOf,
  dayKey,
  parseQuietHours,
  inQuietHours,
  blank,
  normalize,
  noteDecision,
  todayCount,
  decide,
  argvFor,
  noteFired,
  classifyOutcome,
  afterSpawn,
  view,
};
