'use strict';
// Apps: the web pages this host MAKES AND SERVES ITSELF, as a list you can look
// at from the phone — armap, the jtyper trainer, the board view, the BTC sim.
// Called "consoles" until 3.5.0 (decision 53); the word on every surface is now
// "apps", and so is the word in this daemon's journal.
//
// ⚠ TWO NAMES DELIBERATELY DID NOT CHANGE, and both are on disk: the store file
// is still [STORE_NAME] = `consoles.json` (with a `consoles` key inside its
// envelope) and the retrofit marker is still [REBIND_MARKER_NAME] =
// `consoles-rebind-applied`. Renaming either would strand every existing
// installation's rows and silently un-apply an owner's retrofit — a rebrand is
// not a migration, and a file name nobody reads is not a surface. `lib/consoles.js`
// survives for the same reason, as a one-line re-export.
//
// WHAT AN APP IS NOT: a Device. lib/devices.js enrols another MACHINE, hands it
// a scope lattice generated from shared/device-policy.json and long-polls it for
// work. An app is a URL with a name, a liveness probe and a favicon. No key, no
// enrolment, no lifecycle, no shared security story — the two registries are
// deliberately separate and must stay that way (w3-delta §2.5).
//
// ⚠ THE PRODUCT STILL NEVER OPENS A PORT AND NEVER TOUCHES A FIREWALL
// (decision 47, unchanged by 53-56). What changed in 3.5.0 is WHERE the
// remedy is said and WHEN it is demanded:
//
//   · decision 54 — REACHABILITY IS A PREREQUISITE. Every address a client has
//     actually arrived on ([noteClientAddress], the `via` of /v1/ping) plus this
//     host's own address is a place an app MUST answer, because a device that can
//     reach huginn to read this list can reach the app it is reading about. An
//     add that does not pass is REFUSED (422), not stored-and-marked. Rows that
//     were already here are marked instead of deleted — [reachabilityProbe].
//   · decision 55 — the big approval card is GONE from the list body. The exact
//     rebind and firewall lines ride the FAILING ROW, and only that row
//     ([fixLines]). The marker file survives as one boolean, `retrofitApplied`.
//
// Nothing here executes any of it. [fixLines] returns strings; the tests assert
// this file contains no verb that could run one.
//
// `up` still means "the daemon fetched it from huginn". `reachable` is the field
// that means "and your devices can too", per address, with the reason when they
// cannot. Conflating those was the one way this feature could lie, and the two
// fields exist so it cannot.
//
// Layout mirrors lib/archive.js: a PURE half (rules, shapes, the probes, the fix
// lines, the favicon fetch) and a store half. Unlike archive the store lives here
// rather than in huginn-appd.js, because Wave 3's merge discipline gives this
// branch exactly one require line and one route block in that file (w3-delta
// §3(c)) — so the fs and the timer are injected and memoised here instead.
// Everything takes its `fs` and its `fetch` as options, so the tests drive it
// without a daemon.

const nodeFs = require('node:fs');
const path = require('node:path');

// ------------------------------------------------------------------- limits

/**
 * How many apps one host keeps.
 *
 * The contract's number, and MAX_PADS' argument (lib/scratchpads.js): the list
 * is fetched whole by a polling client, and every row is a URL a person is
 * expected to recognise in a glance. A registry of the pages this host serves
 * that needs scrolling has stopped being a registry.
 */
const MAX_APPS = 32;

/** One line, the width of a row's title. cleanName's cap, as scratchpads. */
const MAX_NAME = 60;

/** The sentence under the name. One line, never a paragraph. */
const MAX_NOTES = 200;

/** `^[a-z0-9][a-z0-9-]{0,23}$` — the contract's id grammar, and a route segment. */
const ID_RE = /^[a-z0-9][a-z0-9-]{0,23}$/;

/**
 * What an app IS, for the row's chip. A label and nothing more — it grants
 * nothing, filters nothing and is not part of any join.
 *
 * An unknown word is COERCED to `other` rather than refused, the same tolerance
 * parseManifest applies to model/effort/mode: a client from a later version that
 * learned a fifth kind must not be unable to write a row against this daemon.
 */
const KINDS = ['dashboard', 'tool', 'docs', 'lab', 'other'];
const DEFAULT_KIND = 'other';

/**
 * The bound on ONE probe. The number that makes `up:false` mean "it did not
 * answer" instead of "the request is still open somewhere".
 *
 * ⚠ THIS CONSTANT IS THE FEATURE. A probe without a deadline against a socket
 * that accepts and never writes does not fail — it waits, forever, holding the
 * request that asked for it. `fetch` has NO default timeout in Node; the only
 * thing between this daemon and a wedged listener is the signal below. It is
 * asserted directly (apps.test.js: "a server that accepts and never
 * answers").
 */
const PROBE_TIMEOUT_MS = 2_000;

/** Never more than this many probes in flight, so 32 apps is 8 beats, not 32 sockets. */
const PROBE_CONCURRENCY = 4;

/**
 * How stale a sweep may be before LISTING the apps kicks off a fresh one.
 *
 * ⚠ The list route does NOT await it. A client polls this list every few seconds
 * while the view is open, and a route that waits for the network turns one dead
 * app into a slow page for everything else on it. So the list answers
 * from the last sweep — `up:null` when there has never been one — and schedules
 * the next in the background. That is why `up` is nullable at all.
 */
const PROBE_FRESH_MS = 30_000;

/**
 * The background sweep. Settings-free on purpose: there is no knob, because
 * there is no answer to "how often" that a person would ever want to change,
 * and an app's liveness is not an alerting signal (Uptime Kuma is). It exists
 * so a row that nobody is looking at is not wildly stale when somebody looks.
 */
const PROBE_INTERVAL_MS = 5 * 60_000;

// ------------------------------------------------ reachability (decision 54)

/**
 * How long an address a client ARRIVED ON stays in the set without being seen
 * again.
 *
 * ⚠ THIS SET IS THE PREREQUISITE'S WHOLE AUTHORITY, so it has to forget. A
 * laptop that was on the LAN once in March must not make every app on this host
 * unaddable in September because 192.168.2.117 is still in a file. Seven days is
 * the span in which "a device that reaches huginn" is a present-tense claim; one
 * poll from that device renews it, and every real client polls far more often
 * than weekly.
 */
const CLIENT_ADDR_TTL_SEC = 7 * 24 * 60 * 60;

/**
 * The cap on distinct arrival addresses. A host with more than this many live
 * local addresses is not a host this feature can reason about, and an unbounded
 * set is an unbounded fan-out on every add. Oldest-seen is dropped first.
 */
const MAX_CLIENT_ADDRS = 16;

/**
 * How often the address set is written back to the store.
 *
 * The set lives in MEMORY and is flushed lazily: a NEW address is written at
 * once (it changes what an add would refuse), a refreshed `lastSeenAt` waits.
 * Without this, every authorised request on a daemon serving three polling
 * clients would be a JSON rewrite of the store file.
 */
const CLIENT_ADDR_FLUSH_SEC = 5 * 60;

// ------------------------------------------------------- favicons (decision 53)

/** The directory under DATA_DIR holding one cached icon per app id. */
const ICONS_DIR_NAME = 'apps-icons';

/**
 * The cap on ONE cached icon, enforced on the declared length AND on the bytes
 * actually read.
 *
 * ⚠ CONTENT-LENGTH IS A CLAIM, NOT A LIMIT. A page that serves an endless
 * `image/png` with no length header would otherwise fill DATA_DIR one probe at a
 * time, on a five-minute timer, forever. [readBounded] stops reading at this
 * number whatever the header said.
 */
const ICON_MAX_BYTES = 256 * 1024;

/** The bound on ONE icon request — [PROBE_TIMEOUT_MS]'s argument, applied again. */
const ICON_TIMEOUT_MS = 2_000;

/**
 * How often an app's favicon is re-fetched. An icon is a brand, not a
 * measurement: hourly is far more often than one changes and far less often than
 * the sweep runs. A FAILED fetch is recorded with the same stamp, so a page with
 * no icon at all is asked once an hour rather than every five minutes.
 */
const ICON_REFRESH_MS = 60 * 60_000;

/**
 * At most one redirect, and only to the SAME ORIGIN.
 *
 * ⚠ THE ONLY PLACE THIS DAEMON FOLLOWS A REDIRECT AT ALL. The liveness probe
 * refuses to (a probe that followed one would be a probe of somewhere else), and
 * the same argument applies here with bytes attached: `/favicon.ico` redirecting
 * to `/static/icon.png` is ordinary and worth following; redirecting to another
 * host is a page steering a root-equivalent daemon's fetch, and the host rule
 * was applied to the address that was STORED.
 */
const ICON_MAX_REDIRECTS = 1;

/**
 * The file whose EXISTENCE means the owner has applied the rebind + firewall
 * work the rows ask for.
 *
 * ⚠ IT IS ONE BOOLEAN NOW, and no longer the thing the copy hangs on. Decision
 * 55 moved the remedy onto the failing row, where [reachabilityProbe] can say
 * per address whether it worked; a marker asserting "it is all applied" was a
 * claim the daemon could not check. It survives for the transition, as
 * `retrofitApplied` on the list, so a client that still renders the old sentence
 * has something true to render.
 *
 * Under DATA_DIR, so a test can make one and the default path is
 * `/var/lib/huginn-appd/consoles-rebind-applied`. THE DAEMON NEVER CREATES IT:
 * it is an assertion by the person who ran the commands, and a daemon that could
 * write it could also claim credit for work nobody did. Contents are ignored —
 * `touch` is the whole interface.
 */
const REBIND_MARKER_NAME = 'consoles-rebind-applied';

/** The store file, under DATA_DIR. */
const STORE_NAME = 'consoles.json';

/** The envelope's shape version. NOT the per-app `version`; see [buildRecord]. */
const SCHEMA = 1;

// C0/C1 plus DEL, out of anything that reaches a row or this daemon's journal —
// lib/archive.js:oneLine's argument, and the text here comes from a text field.
const CTRL_ALL = /[\u0000-\u001f\u007f-\u009f]/g;

/** One line, no controls, bounded. */
function oneLine(raw, max = MAX_NOTES) {
  if (typeof raw !== 'string') return '';
  return raw.replace(CTRL_ALL, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

/** The display name, cleaned the way a scratchpad's is. */
function cleanName(raw) {
  return oneLine(raw, MAX_NAME);
}

// ---------------------------------------------------------------- the URL rule

/**
 * The refusal sentence, one per rule, said to the person rather than logged —
 * RouteGuard's convention ("a setting that silently does not take is worse than
 * one that says no").
 */
const REFUSED_SCHEME = 'an app address must start with http:// or https://';
const REFUSED_USERINFO = 'an app address must not carry a username or password';
const REFUSED_TRAVERSAL = "an app address must not contain '..' path segments";
const REFUSED_HOST =
  'huginn only probes addresses it cannot reach the public internet with — '
  + 'loopback, a private LAN address, the tailnet (100.64.0.0/10 or *.ts.net), '
  + 'a mesh ULA (fc00::/7), or a name with no dot in it';

/**
 * Where a host sits, in RouteGuard's words.
 *
 * ⚠ COPIED FROM THE CLIENTS, DELIBERATELY, AND THEN MADE STRICTER.
 * `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/RouteGuard.kt`
 * classifies exactly these classes for exactly this reason — a host LIST cannot
 * survive user-added entries, so the rule is a SHAPE. Keeping the two in step
 * matters because the phone will hand an app URL to ACTION_VIEW and the
 * desktop to the shell, both of which already run RouteGuard's judgement on the
 * daemon's own address.
 *
 * The one place this is stricter than RouteGuard: **https does not open the
 * host up here.** RouteGuard lets `https://` go anywhere because the risk it
 * guards is sending a bearer token in the clear, and TLS answers that. The risk
 * HERE is different — the daemon itself fetches this URL, on a timer, forever —
 * so an arbitrary public host would make a root-equivalent daemon into a
 * scheduled outbound request, and a phone would open it as a real web page. The
 * class rule therefore applies to both schemes.
 *
 * The one place it is looser: a SINGLE-LABEL name (`huginn`, `mimir`) is
 * allowed, because that is what the contract's seed list uses and because a
 * dotless name cannot be a public FQDN — it resolves through /etc/hosts, a
 * MagicDNS search domain or a LAN suffix, all of which are already inside the
 * trust boundary this rule is drawing.
 */
function hostClass(rawHost) {
  const h = String(rawHost || '').toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
  if (!h) return 'public';
  if (h === 'localhost' || h.endsWith('.localhost')) return 'loopback';
  if (h.endsWith('.ts.net')) return 'tailnet';

  const v4 = ipv4(h);
  if (v4) {
    const [a, b] = v4;
    if (a === 127) return 'loopback';
    if (a === 10) return 'lan';
    if (a === 192 && b === 168) return 'lan';
    if (a === 172 && b >= 16 && b <= 31) return 'lan';
    // 100.64.0.0/10 — the CGNAT block tailscale allocates from.
    if (a === 100 && b >= 64 && b <= 127) return 'tailnet';
    return 'public';
  }

  if (h.includes(':')) return ipv6Class(h);
  // No dot: not a public FQDN by construction. See the ⚠ above.
  if (!h.includes('.')) return 'name';
  return 'public';
}

function ipv4(host) {
  const parts = host.split('.');
  if (parts.length !== 4) return null;
  const out = [];
  for (const p of parts) {
    if (!/^\d{1,3}$/.test(p)) return null;
    const n = Number(p);
    if (n < 0 || n > 255) return null;
    out.push(n);
  }
  return out;
}

/** RouteGuard.classifyIpv6, ported: `::1` in either spelling, and fc00::/7. */
function ipv6Class(host) {
  const groups = host.split('%')[0].split(':');
  const values = [];
  for (const g of groups) {
    if (g === '') { values.push(null); continue; }
    if (!/^[0-9a-f]{1,4}$/.test(g)) return 'public';
    values.push(parseInt(g, 16));
  }
  const significant = values.filter((v) => v !== null && v !== 0);
  // `::1` and its written-out form — NOT "ends with 1": `1::` has the same one
  // significant group and is a different address entirely (RouteGuard's note).
  if (significant.length === 1 && significant[0] === 1 && values[values.length - 1] === 1) return 'loopback';
  const first = values.find((v) => v !== null);
  if (first === undefined) return 'public';
  const top = first >> 8;
  return (top === 0xfc || top === 0xfd) ? 'mesh' : 'public';
}

/** The classes an app may live in. Everything else is refused. */
const ALLOWED_HOST_CLASSES = new Set(['loopback', 'lan', 'tailnet', 'mesh', 'name']);

/**
 * Parse and judge an app address.
 *
 * Unlike RouteGuard a PATH is allowed here — an app is a page, not a base
 * URL, and the seeds end in `/`. What is not allowed is a path that plays games:
 * `..` segments are refused on the RAW string, before WHATWG normalisation
 * quietly collapses them, so what is stored is what was judged.
 *
 * ⚠ THE SCHEME IS REQUIRED, unlike RouteGuard which assumes http for a bare
 * address. This string is handed to Android's ACTION_VIEW and to the desktop
 * shell's opener, and `huginn:8088` is not a host and a port — it is a URI in
 * the scheme `huginn`, which is THIS PRODUCT'S OWN deep-link scheme (defect D-E:
 * "don't know how to open the link 'huginn'"). Demanding the scheme is what
 * keeps a typo out of the handler that would otherwise catch it.
 */
function parseAppUrl(raw) {
  const s = typeof raw === 'string' ? raw.trim() : '';
  if (!s) return { ok: false, why: 'an app needs an address' };
  if (s.length > 2000) return { ok: false, why: 'that address is too long' };
  if (/[\u0000-\u0020\u007f-\u009f\\]/.test(s)) {
    return { ok: false, why: 'an app address must not contain spaces, control characters or backslashes' };
  }
  if (!/^https?:\/\//i.test(s)) return { ok: false, why: REFUSED_SCHEME };
  // Raw, before parsing: `new URL` resolves `a/../b` to `b` and a check on the
  // parsed form would therefore always pass.
  if (/(^|[/\\])\.\.([/\\]|$)/.test(s) || /%2e%2e/i.test(s)) {
    return { ok: false, why: REFUSED_TRAVERSAL };
  }

  let u;
  try { u = new URL(s); } catch { return { ok: false, why: 'that is not a valid address' }; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return { ok: false, why: REFUSED_SCHEME };
  // A credential in a field the diagnostics bundle prints, and no case for one
  // against a page on this host — RouteGuard refuses it for the same reason.
  if (u.username || u.password) return { ok: false, why: REFUSED_USERINFO };
  if (!u.hostname) return { ok: false, why: 'that address has no host' };
  if (u.hostname.length > 253) return { ok: false, why: 'that host name is too long' };

  const kind = hostClass(u.hostname);
  if (!ALLOWED_HOST_CLASSES.has(kind)) return { ok: false, why: REFUSED_HOST };
  return { ok: true, url: u.href, host: u.hostname, hostClass: kind };
}

/** The sentence, or null. The shape every *Problem in this tree has. */
function urlProblem(raw) {
  const r = parseAppUrl(raw);
  return r.ok ? null : r.why;
}

/** The address as it is stored: WHATWG-canonical, scheme explicit. */
function normalizeUrl(raw) {
  const r = parseAppUrl(raw);
  return r.ok ? r.url : '';
}

// ------------------------------------------------------------- the other rules

function nameProblem(raw) {
  const name = cleanName(raw);
  if (!name) return 'an app needs a name';
  if (typeof raw === 'string' && raw.trim().length > MAX_NAME) return `a name is at most ${MAX_NAME} characters`;
  return null;
}

function notesProblem(raw) {
  if (raw == null || raw === '') return null;
  if (typeof raw !== 'string') return 'notes must be text';
  if (raw.trim().length > MAX_NOTES) return `notes are at most ${MAX_NOTES} characters`;
  return null;
}

/**
 * The systemd unit that SERVES this app, when this host knows one.
 *
 * ⚠ IT IS A LABEL THIS DAEMON NEVER RUNS. It exists so [fixLines] can name the
 * unit in the one line the owner would have to type, instead of saying "whatever
 * serves port 8091". Nothing here starts, stops, edits or even looks the unit up
 * — the grammar below is what keeps a hand-typed value from becoming a shell
 * fragment inside a line somebody pastes into a root prompt.
 */
const UNIT_RE = /^[A-Za-z0-9][A-Za-z0-9@._-]{0,63}$/;

function cleanUnit(raw) {
  const s = typeof raw === 'string' ? raw.trim() : '';
  return UNIT_RE.test(s) ? s : '';
}

/**
 * Absent is fine; PRESENT AND MALFORMED IS A REFUSAL, unlike `kind`.
 *
 * `kind` is coerced because a newer client inventing a fifth word must still be
 * able to write a row, and the cost of being wrong is a chip with the wrong
 * label. A unit name ends up in a command line a person runs as root, so a value
 * that is not a unit name is not something to quietly coerce to something else.
 */
function unitProblem(raw) {
  if (raw == null || raw === '') return null;
  if (typeof raw !== 'string') return 'a unit name must be text';
  if (!UNIT_RE.test(raw.trim())) {
    return 'a unit name is letters, digits, @ . _ and -, at most 64 characters';
  }
  return null;
}

function idProblem(id, taken = []) {
  const s = String(id || '');
  if (!ID_RE.test(s)) {
    return 'an id is lowercase letters, digits and dashes, 1-24 characters, starting with a letter or digit';
  }
  const set = taken instanceof Set ? taken : new Set(taken || []);
  if (set.has(s)) return `there is already an app called '${s}'`;
  return null;
}

/** An id derived from the display name, the way projectsLib.slugFor derives one. */
function idFor(name) {
  return cleanName(name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24)
    || `app${Math.floor(Date.now() / 1000) % 100000}`;
}

/** Unknown words become `other` rather than an error. See [KINDS]. */
function cleanKind(raw) {
  const k = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
  return KINDS.includes(k) ? k : DEFAULT_KIND;
}

// -------------------------------------------------------------- the record

/**
 * The stored app.
 *
 * `version` is OPTIMISTIC CONCURRENCY, per row — the number the editor last
 * read, checked on every write, 409 with the current row when it has moved. The
 * scratchpad `rev` contract, which both clients already know how to adopt
 * (HuginnClient.saveScratchpad treats 409 as an ANSWER, not a throw). It is not
 * the envelope's `schema`, and the two words are kept apart on purpose: one
 * describes this row's edit history, the other the file's shape.
 *
 * Every field a client reads is present with a definite value, never absent — a
 * row that decoded is a row that renders (the archive rule).
 */
function buildRecord(input = {}, now = Math.floor(Date.now() / 1000)) {
  return {
    id: String(input.id || ''),
    name: cleanName(input.name),
    url: normalizeUrl(input.url),
    kind: cleanKind(input.kind),
    notes: oneLine(input.notes) || '',
    // '' rather than absent, like every other field here: a row that decoded is
    // a row that renders, and a client should never have to ask whether a key
    // was missing or empty. '' means "huginn does not know which unit serves
    // this" and [fixLines] says so in words.
    unit: cleanUnit(input.unit),
    addedAt: Number(input.addedAt) > 0 ? Number(input.addedAt) : now,
    version: Number(input.version) > 0 ? Number(input.version) : 1,
  };
}

/**
 * A STORED row's `addedAt`, which is a fact about the row and never about the
 * read.
 *
 * ⚠ ZERO, NOT `now`, WHEN THERE ISN'T ONE. `buildRecord` stamps the clock for a
 * row being CREATED, which is the one moment that stamp is true; passing a
 * stored row back through it with no `addedAt` re-stamped it on every read, so
 * the row said "added 2 seconds ago" forever — and permanently, once the next
 * write saved whichever value the last read had invented. Zero is the same
 * sentinel the clients' own route book uses for a migrated pin: "it was already
 * here". It is stable, and it is not a lie about when.
 */
function storedAddedAt(rec) {
  const n = Number(rec && rec.addedAt);
  return n > 0 ? n : 0;
}

/** The never-probed probe state. `up:null` is "no observation", not "down". */
function noProbe() {
  return { up: null, lastProbeAt: 0, latencyMs: null, httpStatus: null };
}

/** The never-checked reachability. `ok:null` is "nothing to check against", not "no". */
function noReach() {
  return { ok: null, checkedAt: 0, addresses: [], fix: [], note: '' };
}

/** A reachability result, with every field definite whatever it was handed. */
function reachOf(raw) {
  const r = raw && typeof raw === 'object' ? raw : null;
  if (!r) return noReach();
  return {
    ok: r.ok === true || r.ok === false ? r.ok : null,
    checkedAt: Number(r.checkedAt) || 0,
    addresses: Array.isArray(r.addresses) ? r.addresses : [],
    fix: Array.isArray(r.fix) ? r.fix : [],
    note: typeof r.note === 'string' ? r.note : '',
  };
}

/**
 * The row as a client reads it: the record, the last liveness observation, the
 * last REACHABILITY observation, and whether an icon is cached.
 *
 * ⚠ `up` AND `reachable` ARE TWO DIFFERENT QUESTIONS AND THE ROW MUST ASK BOTH.
 * `up` is "the daemon fetched it, from huginn, at the address the row stores" —
 * true of all four seeds today. `reachable` is "and a device that can reach
 * huginn can reach it too", per address, which is the question a person tapping
 * the row is actually asking. Until 3.5.0 only the first was on the wire and a
 * single sentence at the top of the list (`reachableFrom: "host"`) carried the
 * caveat for every row at once; decision 55 puts it on the row that has it, with
 * the exact lines that would fix it.
 *
 * `reachableFrom` is GONE for that reason, not by accident: a per-row
 * `reachable.addresses` says strictly more, and a client reading both would have
 * to decide which one it believed.
 */
function appRow(rec, probe, extra = {}) {
  const p = probe || noProbe();
  return {
    ...buildRecord(rec, storedAddedAt(rec)),
    up: p.up === true || p.up === false ? p.up : null,
    lastProbeAt: Number(p.lastProbeAt) || 0,
    latencyMs: Number.isFinite(p.latencyMs) ? p.latencyMs : null,
    httpStatus: Number.isFinite(p.httpStatus) ? p.httpStatus : null,
    icon: extra.icon === true,
    reachable: reachOf(extra.reachable),
  };
}

/** Insertion order, which is the order the owner chose. Ties never happen (ids are unique). */
function sortApps(rows) {
  return [...(rows || [])];
}

// ------------------------------------------------------------ list operations
//
// Pure list algebra: every one takes the current list and returns either
// `{ok:true, apps, app}` or `{ok:false, status, error, app?}`. The
// store half is then only fs — which is what makes all of this testable without
// one.

function findApp(list, id) {
  return (list || []).find((c) => c && c.id === String(id || '')) || null;
}

function add(list, input = {}, now = Math.floor(Date.now() / 1000)) {
  const current = list || [];
  if (current.length >= MAX_APPS) {
    return { ok: false, status: 400, error: `that is the ${MAX_APPS}-app limit — remove one first` };
  }
  const bad = nameProblem(input.name);
  if (bad) return { ok: false, status: 400, error: bad };
  const badUrl = urlProblem(input.url);
  if (badUrl) return { ok: false, status: 400, error: badUrl };
  const badNotes = notesProblem(input.notes);
  if (badNotes) return { ok: false, status: 400, error: badNotes };
  const badUnit = unitProblem(input.unit);
  if (badUnit) return { ok: false, status: 400, error: badUnit };

  const id = input.id ? String(input.id) : idFor(input.name);
  // Grammar is a 400 — what was typed cannot be an id at all. A COLLISION is a
  // 409: the id is fine, it is the list that already has one, which is a
  // different thing for a client to show and the status the contract names.
  const badGrammar = idProblem(id, []);
  if (badGrammar) return { ok: false, status: 400, error: badGrammar };
  const taken = findApp(current, id);
  if (taken) return { ok: false, status: 409, error: `there is already an app called '${id}'`, app: taken };

  const rec = buildRecord({ ...input, id, version: 1, addedAt: now }, now);
  return { ok: true, apps: [...current, rec], app: rec };
}

/**
 * The version-checked write. `version` is what the editor last read, and a write
 * against a stale one is refused WITH THE CURRENT ROW so the client can show
 * what it collided with instead of a bare error (the saveScratchpad contract).
 */
function patch(list, id, changes = {}) {
  const current = list || [];
  const rec = findApp(current, id);
  if (!rec) return { ok: false, status: 404, error: 'no such app' };

  const asked = Number(changes.version);
  if (!Number.isFinite(asked)) return { ok: false, status: 400, error: 'version is required' };
  if (asked !== rec.version) {
    return {
      ok: false,
      status: 409,
      error: 'that app changed somewhere else while you were editing it',
      app: rec,
    };
  }

  const next = { ...rec };
  if (changes.name !== undefined) {
    const bad = nameProblem(changes.name);
    if (bad) return { ok: false, status: 400, error: bad };
    next.name = cleanName(changes.name);
  }
  if (changes.url !== undefined) {
    const bad = urlProblem(changes.url);
    if (bad) return { ok: false, status: 400, error: bad };
    next.url = normalizeUrl(changes.url);
  }
  if (changes.notes !== undefined) {
    const bad = notesProblem(changes.notes);
    if (bad) return { ok: false, status: 400, error: bad };
    next.notes = oneLine(changes.notes) || '';
  }
  if (changes.unit !== undefined) {
    const bad = unitProblem(changes.unit);
    if (bad) return { ok: false, status: 400, error: bad };
    next.unit = cleanUnit(changes.unit);
  }
  if (changes.kind !== undefined) next.kind = cleanKind(changes.kind);
  next.version = rec.version + 1;

  return {
    ok: true,
    apps: current.map((c) => (c.id === rec.id ? next : c)),
    app: next,
    // Whether the thing the probe measures moved. A rename does not invalidate
    // an observation; a new address does, and a row still showing the old
    // address's latency is a lie with a number on it.
    urlChanged: next.url !== rec.url,
  };
}

/** Rename, as a named operation, because that is the edit people actually make. */
function rename(list, id, name) {
  const rec = findApp(list, id);
  return rec ? patch(list, id, { version: rec.version, name }) : { ok: false, status: 404, error: 'no such app' };
}

/** Re-point an app at a new address. Same guard, same version bump. */
function setUrl(list, id, url) {
  const rec = findApp(list, id);
  return rec ? patch(list, id, { version: rec.version, url }) : { ok: false, status: 404, error: 'no such app' };
}

function remove(list, id) {
  const current = list || [];
  const rec = findApp(current, id);
  if (!rec) return { ok: false, status: 404, error: 'no such app' };
  return { ok: true, apps: current.filter((c) => c.id !== rec.id), app: rec };
}

// -------------------------------------------------------------------- the seed

/**
 * The four pages this host serves, from the contract — name, port, kind and the
 * one line under the name. The ADDRESS is not in here, because it is not a
 * property of the page (see [seedHost]).
 */
const SEED_UNITS = [
  { id: 'armap', port: 8088, name: 'Architecture map', kind: 'docs', unit: 'armap.service',
    notes: 'Static armap dashboard (docs/current/armap).' },
  { id: 'jtyper', port: 8091, name: 'jtyper trainer', kind: 'lab', unit: 'jtyper-trainer.service',
    notes: 'Blind test + chat + hole-fill flywheel.' },
  { id: 'board', port: 8092, name: 'PCB board view', kind: 'tool', unit: 'boardserver.service',
    notes: 'Live KiCad board review (brokkr renders).' },
  { id: 'btc15m', port: 8093, name: 'BTC 15m simulator', kind: 'lab', unit: 'btc15m-sim.service',
    notes: 'Paper-trading sim for the 15-minute lab.' },
];

/**
 * Where a unit's bind actually lives, when it is not in the unit file.
 *
 * ⚠ ONE ENTRY, AND IT IS THE D11 SCAR. The old card carried a per-unit claim
 * that btc15m-sim "already falls back to 0.0.0.0" and therefore needed no
 * rebind; `ss -ltn` said `100.97.198.90:8093`, because that fallback is what
 * sim/app.py does when the host has NO tailscale address. The claim is gone; what
 * survives is the only part of it that was true and useful — `systemctl edit
 * btc15m-sim.service` would edit the wrong thing, so the line says where to look
 * instead. This map holds directions, never exemptions.
 */
const UNIT_BIND_NOTES = {
  'btc15m-sim.service': 'its bind is in sim/app.py, not the unit',
};

/**
 * The host the seed used to be pinned to, and the fallback for a daemon that
 * cannot say where it is.
 *
 * ⚠ THIS NAME IS THE DEFECT (D10). `huginn` resolves to this host's LAN
 * interface (192.168.2.117); all four seeded units bind its TAILNET address
 * (100.97.198.90). So the seeded rows named an interface on which NONE of them
 * listen — `curl http://huginn:8088/` answers nothing, `curl
 * http://100.97.198.90:8088/` answers 200 — and every row read "not answering
 * from the host" forever, in the one deployment that actually has these units.
 * The probe could not pass, whatever anybody did to the units or the firewall.
 *
 * It survives only as the fallback, because a daemon that has not been told an
 * address should write what it used to write rather than guess.
 */
const SEED_FALLBACK_HOST = 'huginn';

/**
 * `addr` as a URL authority this registry would accept, or '' — the one guard
 * both [seedHost] and [pickHostAddr] are built on.
 *
 * ⚠ THE DAEMON'S OWN ADDRESS IS NOT EXEMPT FROM THE DAEMON'S OWN URL RULE. A
 * wildcard (`0.0.0.0`, `::`) is a legal way to start this process and a
 * meaningless thing to put in a URL; a link-local address carries a zone id
 * that means nothing to another process. Anything urlProblem would refuse on a
 * hand-typed row is refused here too.
 */
function seedableHost(addr) {
  const s = typeof addr === 'string' ? addr.trim().split('%')[0] : '';
  if (!s) return '';
  // A literal v6 address needs its brackets back before it is a URL authority.
  const host = s.includes(':') && !s.startsWith('[') ? `[${s}]` : s;
  return urlProblem(`http://${host}:${SEED_UNITS[0].port}/`) ? '' : host;
}

/**
 * The first candidate the daemon offered that a seeded row could be written
 * with, or '' — trimmed, and without a v6 zone id.
 *
 * ⚠ THE BIND IS A WILDCARD ON THE HOST THIS SHIPS TO, which is why this takes a
 * LIST. huginn-appd's unit sets `HUGINN_APPD_BIND=0.0.0.0` so that the tailnet,
 * mesh and loopback pins a client holds all reach one listener — so "seed with
 * the address we bound" resolves to `0.0.0.0`, and a fix that only read
 * server.address() would change nothing on the one host that has this defect.
 * The daemon therefore offers what it has, in order (its bind, then the
 * `tailscale ip -4` resolveBind already knows how to ask for), and this takes
 * the first that stands up.
 *
 * ⚠ PASSED IN, NEVER DISCOVERED. This module runs no commands and holds no
 * process-spawning require — asserted directly against this file's source in
 * apps.test.js ("nothing in the fix lines is a verb this daemon could run"). It
 * cannot enumerate interfaces either: the unit runs under
 * `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, so the netlink socket
 * behind getifaddrs is closed to it and os.networkInterfaces() THROWS there.
 */
function pickHostAddr(...candidates) {
  for (const c of candidates) {
    if (seedableHost(c)) return c.trim().split('%')[0];
  }
  return '';
}

/** The host part of a seeded address: what the daemon handed in, or the old name. */
function seedHost(addr) {
  return seedableHost(addr) || SEED_FALLBACK_HOST;
}

/** One seeded unit's address, for `addr`. */
function seedUrl(addr, port) { return `http://${seedHost(addr)}:${port}/`; }

/**
 * What the seed wrote BEFORE D10 — the exact literal [migrateSeedUrls] matches.
 *
 * ⚠ A LITERAL, not a pattern. It is the only thing that distinguishes a row the
 * old seed wrote from a row the owner chose, and a looser rule here overwrites
 * somebody's edit.
 */
function legacySeedUrl(port) { return `http://${SEED_FALLBACK_HOST}:${port}/`; }

/**
 * The four pages this host serves — written once, on the first list against a
 * store that has never existed, so the feature arrives populated instead of
 * arriving as an empty list with a plus button.
 *
 * Seeded ONCE and recorded as such in the envelope: an owner who deletes all
 * four has said something, and a seeder that ran on every empty list would
 * argue with them forever.
 *
 * The address is the host's own, from [seedHost] — not 127.0.0.1, which would
 * be a different claim about where these pages are, and not a name, which is
 * what D10 was.
 */
function seedApps(addr = null, now = Math.floor(Date.now() / 1000)) {
  return SEED_UNITS.map((u) => buildRecord({
    id: u.id, name: u.name, url: seedUrl(addr, u.port), kind: u.kind, notes: u.notes, unit: u.unit,
  }, now));
}

/**
 * Re-apply the seed's ADDRESS to the rows that still carry the old one.
 *
 * ⚠ THE FIX HAS TO REACH THE STORES THAT ALREADY EXIST. Seeding correctly only
 * on a host that has never run this daemon would leave every real installation
 * with four rows that can never answer and no way to notice — the seed runs
 * once, by design, and never runs again.
 *
 * ⚠ AND IT MUST NEVER OVERWRITE AN EDIT. The test is the EXACT previous literal
 * for THAT row's id: a path, a different port, a host of their own, or an id
 * that is not one of the four is the owner speaking, and is left alone. The
 * `version` moves with the address, because a client holding the old number is
 * holding a row that no longer describes anything.
 */
function migrateSeedUrls(list, addr) {
  const changed = [];
  const host = seedHost(addr);
  // Nothing better to move them to: leave the store exactly as it is, and in
  // particular do not rewrite the file.
  if (host === SEED_FALLBACK_HOST) return { changed, apps: list || [] };
  const apps = (list || []).map((rec) => {
    const unit = SEED_UNITS.find((u) => u.id === (rec && rec.id));
    if (!unit || !rec || rec.url !== legacySeedUrl(unit.port)) return rec;
    changed.push(rec.id);
    return { ...rec, url: seedUrl(addr, unit.port), version: (Number(rec.version) || 1) + 1 };
  });
  return { changed, apps };
}

/**
 * Give the four rows this host ships with the UNIT that serves them, when they
 * do not already carry one.
 *
 * ⚠ SEPARATE FROM [migrateSeedUrls] ON PURPOSE. That one moves an address and
 * has to be paranoid about the exact literal, because an address is a thing the
 * owner chooses. A unit is a fact about THIS host: `armap.service` serves port
 * 8088 here whatever anybody pointed the row at, and a row with no unit falls
 * into the no-unit branch of [fixLines] and asks the owner to work out what to
 * edit — on the one deployment where the daemon knows the answer.
 *
 * ⚠ ONLY WHEN IT IS EMPTY. A unit the owner typed is theirs, even if it is not
 * the one shipped here. The `version` moves with it, because a client holding
 * the old number is holding a row that no longer describes the same thing.
 */
function migrateSeedUnits(list) {
  const changed = [];
  const apps = (list || []).map((rec) => {
    const unit = SEED_UNITS.find((u) => u.id === (rec && rec.id));
    if (!unit || !rec || cleanUnit(rec.unit)) return rec;
    changed.push(rec.id);
    return { ...rec, unit: unit.unit, version: (Number(rec.version) || 1) + 1 };
  });
  return { changed, apps };
}

// ------------------------------------------- the fix lines (decisions 54, 55)

/**
 * heimdall's firewall file for THIS container. Another machine's file.
 *
 * ⚠ huginn has no business editing /etc/pve/firewall/117.fw and this daemon has
 * no code that could (w3-delta §4, risk 8: "nothing in Wave 3 may touch heimdall
 * or run systemctl edit"). The name travels as text because naming the file is
 * half of what makes the step followable.
 */
const FIREWALL_FILE = '/etc/pve/firewall/117.fw';

/** The host an app's address names, or '' — never a throw. */
function hostnameOf(rawUrl) {
  try { return new URL(rawUrl).hostname; } catch { return ''; }
}

/** The port an app's address implies, scheme default included. */
function portOf(rawUrl) {
  try {
    const u = new URL(rawUrl);
    return u.port || (u.protocol === 'https:' ? '443' : '80');
  } catch {
    return '';
  }
}

/**
 * What it would take to make THIS app answer on the addresses it does not.
 *
 * Decision 55 in one function: the big approval card is gone, and what replaces
 * it is per row, computed from what the probe actually saw rather than
 * hard-coded. That is the whole difference. The old card listed four units and
 * four firewall rules on every list body forever, including on a host where
 * three of them were fine; it could not be checked, so it rotted (D11), and it
 * said nothing at all about an app the owner added themselves.
 *
 * Two steps, because there are two machines:
 *
 *   1. the REBIND, here — the app answers on one address and has to answer on
 *      all of them. `systemctl edit <unit>` when the row knows its unit, and a
 *      sentence naming the address it does answer on when it does not, because
 *      "bind 0.0.0.0" with no idea what to edit is not an instruction.
 *   2. the FIREWALL, on heimdall — one line per address that failed.
 *
 * ⚠ THE `-source` IS THE ADDRESS THE CLIENT ARRIVED ON, which is huginn's own
 * address on that network and therefore names the NETWORK, not the device. It is
 * what this daemon can honestly know: it sees which of its listeners a client
 * dialled, and the owner narrows the source to the device or subnet they mean.
 * Saying `-source <a device we guessed>` would be an invention; saying nothing
 * would be a rule that opens the port to everything.
 *
 * ⚠ EVERY RETURNED ELEMENT IS A STRING. There is no verb in this payload, no
 * route that takes one, and a test asserts this file holds no way to run one.
 */
function fixLines(rec, addresses = []) {
  const seen = Array.isArray(addresses) ? addresses : [];
  const failing = seen.filter((a) => a && a.ok === false).map((a) => a.addr);
  if (!failing.length) return [];
  const answering = seen.filter((a) => a && a.ok === true).map((a) => a.addr);
  const port = portOf(rec && rec.url);
  const unit = cleanUnit(rec && rec.unit);
  // The address it DOES answer on, which is the thing the rebind replaces. When
  // nothing answered there is no such address, and the line says that instead of
  // naming one that does not exist.
  const bound = answering[0] || hostnameOf(rec && rec.url);
  const out = [];

  out.push(`# on huginn — ${failing.join(', ')} ${failing.length === 1 ? 'does' : 'do'} not reach this app`);
  if (unit) {
    const note = UNIT_BIND_NOTES[unit];
    out.push(`systemctl edit ${unit}   # ExecStart: bind 0.0.0.0 instead of ${bound || 'one address'}`
      + (note ? `  (${note})` : ''));
    out.push(`systemctl restart ${unit}`);
  } else {
    out.push(`bind 0.0.0.0 instead of ${bound || 'the one address it answers on'}`
      + ' — huginn does not know which unit serves this app, so name it on the row to get the exact line');
  }
  out.push(`ss -ltn | grep :${port}`);
  out.push(`# on heimdall — ${FIREWALL_FILE}`);
  for (const addr of failing) {
    out.push(`IN ACCEPT -source ${addr} -p tcp -dport ${port} -log nolog`);
  }
  return out;
}

// -------------------------------------------- the reachability probe (dec. 54)

/**
 * Normalise one address a client arrived on, or '' when it is not one this
 * registry could ever put in a URL.
 *
 * ⚠ `::ffff:127.0.0.1` IS 127.0.0.1. A dual-stack listener reports v4 peers in
 * v4-mapped form, so the same interface would otherwise enter the set twice
 * under two spellings and be probed twice — and the second spelling is not a
 * thing anybody would paste into a firewall rule.
 *
 * The wildcards are refused by [seedableHost] rather than by a list here: the
 * daemon's own address is not exempt from the daemon's own URL rule, and
 * `0.0.0.0` is a legal way to bind and a meaningless thing to dial.
 */
function normalizeAddr(raw) {
  let a = typeof raw === 'string' ? raw.trim() : '';
  if (!a) return '';
  a = a.split('%')[0].replace(/^\[/, '').replace(/\]$/, '');
  if (/^::ffff:/i.test(a)) a = a.slice(7);
  a = a.toLowerCase();
  return seedableHost(a) ? a : '';
}

/** `addr` as a URL authority — a v6 literal gets its brackets back. */
function addrAuthority(addr) {
  return addr.includes(':') ? `[${addr}]` : addr;
}

/**
 * The same app, asked for at a DIFFERENT address: this row's scheme, path and
 * port, at `addr`.
 *
 * This is what makes the probe a reachability probe rather than a second
 * liveness probe. The row stores one address; the question is whether the thing
 * behind it also answers at every address a client of this daemon arrives on.
 */
function reachUrl(rawUrl, addr) {
  const u = new URL(rawUrl);
  const port = u.port || (u.protocol === 'https:' ? '443' : '80');
  return `${u.protocol}//${addrAuthority(addr)}:${port}${u.pathname}${u.search}`;
}

/** Why one address did not answer, in the words a row can show. */
function reachError(e) {
  const name = e && e.name;
  if (name === 'TimeoutError' || name === 'AbortError') return 'timed out';
  const code = e && e.cause && e.cause.code;
  if (code === 'ECONNREFUSED') return 'connection refused';
  if (code === 'EHOSTUNREACH' || code === 'ENETUNREACH') return 'no route';
  if (code === 'ENOTFOUND' || code === 'EAI_AGAIN') return 'name does not resolve';
  return code || 'did not answer';
}

/** One address, one bounded GET. `{addr, ok}` or `{addr, ok:false, error}`. */
async function reachOne(rawUrl, addr, opts = {}) {
  const doFetch = opts.fetch || globalThis.fetch;
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : PROBE_TIMEOUT_MS;
  let url;
  try { url = reachUrl(rawUrl, addr); } catch { return { addr, ok: false, error: 'not an address' }; }
  try {
    const res = await doFetch(url, {
      method: 'GET',
      redirect: 'manual',
      signal: AbortSignal.timeout(timeoutMs),
      headers: { 'user-agent': 'huginn-appd/app-reach' },
    });
    try { if (res.body && typeof res.body.cancel === 'function') await res.body.cancel(); } catch { /* gone */ }
    // The liveness rule, applied again: anything under 500 is something
    // answering, a 401 page included. A 5xx is the server saying it is broken,
    // which from this address is indistinguishable from not being there.
    if (res.status < 500) return { addr, ok: true };
    return { addr, ok: false, error: `http ${res.status}` };
  } catch (e) {
    return { addr, ok: false, error: reachError(e) };
  }
}

/**
 * Decision 54, as one function: does this app answer on EVERY address a client
 * of this daemon has arrived on?
 *
 * ⚠ `ok:null` IS NOT `ok:false`. It means there was nothing to check against —
 * a daemon nobody has connected to yet, which has not learned a single arrival
 * address and cannot have an opinion. An add in that state is allowed and says
 * so (`note`), because refusing every app on a fresh install until somebody
 * connects twice would make the prerequisite a wall rather than a check.
 *
 * ⚠ IN PARALLEL, EACH BOUNDED. Serially, one wedged address would add its whole
 * deadline to every other one, and `POST /v1/apps` awaits this.
 */
async function reachabilityProbe(rec, addrs = [], opts = {}) {
  const nowMs = opts.nowMs || (() => Date.now());
  const checkedAt = Math.floor(nowMs() / 1000);
  const list = [...new Set((addrs || []).map(normalizeAddr).filter(Boolean))];
  const url = rec && typeof rec.url === 'string' ? rec.url : '';
  if (!url || urlProblem(url)) {
    return { ok: false, checkedAt, addresses: [], fix: [], note: 'that address is not one huginn probes' };
  }
  if (!list.length) {
    return {
      ok: null,
      checkedAt,
      addresses: [],
      fix: [],
      note: 'huginn has not seen a client arrive on any address yet, so there was nothing to check this against',
    };
  }
  const addresses = await Promise.all(list.map((a) => reachOne(url, a, opts)));
  const ok = addresses.every((a) => a.ok === true);
  return { ok, checkedAt, addresses, fix: ok ? [] : fixLines(rec, addresses), note: '' };
}

/** The sentence a 422 says. Names the addresses, because that is the whole refusal. */
function reachRefusal(reach) {
  const failing = (reach && Array.isArray(reach.addresses) ? reach.addresses : [])
    .filter((a) => a && a.ok === false).map((a) => a.addr);
  return `this app does not answer at ${failing.join(', ') || 'the addresses huginn answers on'}`
    + ' — a device that can reach huginn has to be able to reach the app, so fix the bind first';
}

// ------------------------------------------------------- favicons (decision 53)

/**
 * Read at most `max` bytes of a response, whatever it claims to be.
 *
 * See [ICON_MAX_BYTES]: `content-length` is checked first because it is free,
 * and then ignored, because a response may not carry one and a response that
 * does may be lying. Returns null past the cap rather than truncating — half an
 * icon is not an icon, and a cache full of half icons is worse than an empty one.
 */
async function readBounded(res, max = ICON_MAX_BYTES) {
  const declared = Number(res.headers && res.headers.get && res.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > max) {
    try { if (res.body && res.body.cancel) await res.body.cancel(); } catch { /* gone */ }
    return null;
  }
  // ⚠ READ IT, DO NOT BUFFER IT. `res.arrayBuffer()` would hold the whole body
  // in memory before anything could measure it, which is the same unbounded
  // read the content-length check above is trying to avoid — a chunked response
  // with no length gets as far as the deadline allows. The reader stops at the
  // cap and cancels the rest.
  const body = res.body;
  if (!body || typeof body.getReader !== 'function') {
    const buf = Buffer.from(await res.arrayBuffer());
    return buf.length > max ? null : buf;
  }
  const reader = body.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > max) {
        try { await reader.cancel(); } catch { /* gone */ }
        return null;
      }
      chunks.push(Buffer.from(value));
    }
  } catch {
    return null;
  }
  return Buffer.concat(chunks, total);
}

const REDIRECT_CODES = new Set([301, 302, 303, 307, 308]);

/** One bounded GET, following at most [ICON_MAX_REDIRECTS] SAME-ORIGIN redirect. */
async function getBounded(url, opts = {}, hops = 0) {
  const doFetch = opts.fetch || globalThis.fetch;
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : ICON_TIMEOUT_MS;
  const res = await doFetch(url, {
    method: 'GET',
    redirect: 'manual',
    signal: AbortSignal.timeout(timeoutMs),
    headers: { 'user-agent': 'huginn-appd/app-icon' },
  });
  if (REDIRECT_CODES.has(res.status) && hops < ICON_MAX_REDIRECTS) {
    const loc = res.headers.get('location');
    try { if (res.body && res.body.cancel) await res.body.cancel(); } catch { /* gone */ }
    if (!loc) return { ok: false, why: 'redirect with no location' };
    let next;
    try { next = new URL(loc, url); } catch { return { ok: false, why: 'redirect to nowhere' }; }
    // ⚠ SAME ORIGIN ONLY. See [ICON_MAX_REDIRECTS].
    if (next.origin !== new URL(url).origin) return { ok: false, why: 'redirect off this host' };
    return getBounded(next.href, opts, hops + 1);
  }
  if (res.status >= 400) {
    try { if (res.body && res.body.cancel) await res.body.cancel(); } catch { /* gone */ }
    return { ok: false, why: `http ${res.status}` };
  }
  const contentType = String(res.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  const bytes = await readBounded(res, ICON_MAX_BYTES);
  if (!bytes) return { ok: false, why: `larger than ${ICON_MAX_BYTES} bytes` };
  return { ok: true, url, contentType, bytes };
}

/**
 * The `href` of the page's own icon link, or ''.
 *
 * ⚠ THE `rel` IS A TOKEN LIST AND IT IS MATCHED AS ONE. `/\bicon\b/` also
 * matches `apple-touch-icon` and `mask-icon`, which are a 180px PNG and a
 * monochrome SVG — neither is the favicon, and one of them is routinely 100 KB.
 * The tokens accepted are exactly `icon` and `shortcut icon`, which is what
 * decision 53 names.
 */
function iconHrefFromHtml(html) {
  const text = String(html || '');
  for (const tag of text.match(/<link\b[^>]*>/gi) || []) {
    const rel = (tag.match(/\brel\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i) || []).slice(1).find((x) => x != null);
    if (!rel) continue;
    const tokens = rel.trim().toLowerCase().split(/\s+/);
    if (!tokens.includes('icon')) continue;
    if (tokens.some((t) => t !== 'icon' && t !== 'shortcut')) continue;
    const href = (tag.match(/\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i) || []).slice(1).find((x) => x != null);
    if (href && href.trim()) return href.trim();
  }
  return '';
}

/**
 * This app's favicon: `/favicon.ico` first, then the page's own `<link rel=icon>`.
 *
 * Decision 53. Three bounded requests at the very worst — the well-known path,
 * the page, the href the page named — and every one of them capped at
 * [ICON_MAX_BYTES] and [ICON_TIMEOUT_MS].
 *
 * ⚠ `image/*` OR NOTHING. A host that answers `/favicon.ico` with its SPA's
 * index.html — which is most of them — must not have 40 KB of HTML cached as
 * this app's icon and served back with an image content type. The type is the
 * test, not the extension and not the status code.
 */
async function fetchIcon(rawUrl, opts = {}) {
  const isImage = (r) => r.ok && r.contentType.startsWith('image/');
  let wellKnown = null;
  try {
    wellKnown = await getBounded(new URL('/favicon.ico', rawUrl).href, opts);
  } catch (e) {
    wellKnown = { ok: false, why: reachError(e) };
  }
  if (wellKnown && isImage(wellKnown)) {
    return { ok: true, bytes: wellKnown.bytes, contentType: wellKnown.contentType, from: wellKnown.url };
  }

  let page;
  try {
    page = await getBounded(rawUrl, opts);
  } catch (e) {
    return { ok: false, why: reachError(e) };
  }
  if (!page.ok) return { ok: false, why: page.why };
  if (!/^text\/html|^application\/xhtml/.test(page.contentType)) {
    return { ok: false, why: 'no icon and the page is not html' };
  }
  const href = iconHrefFromHtml(page.bytes.toString('utf8'));
  if (!href) return { ok: false, why: 'the page names no icon' };
  let linked;
  try {
    linked = await getBounded(new URL(href, page.url).href, opts);
  } catch (e) {
    return { ok: false, why: reachError(e) };
  }
  if (!linked.ok) return { ok: false, why: linked.why };
  if (!linked.contentType.startsWith('image/')) return { ok: false, why: 'the linked icon is not an image' };
  return { ok: true, bytes: linked.bytes, contentType: linked.contentType, from: linked.url };
}

// --- the icon cache, on disk

function iconsDir(dir) { return path.join(dir, ICONS_DIR_NAME); }

/** The bytes. Named by id alone, which is why an id may not contain a dot or a slash. */
function iconFile(dir, id) { return path.join(iconsDir(dir), String(id)); }

/**
 * The content type and the stamp, beside the bytes.
 *
 * ⚠ WRITTEN ON FAILURE TOO. The stamp is what [ICON_REFRESH_MS] throttles, and
 * a page with no icon is the common case — without a record of having asked, the
 * sweep would ask it again every five minutes forever.
 */
function iconMetaFile(dir, id) { return `${iconFile(dir, id)}.json`; }

function readIconMeta(dir, id, fs = nodeFs) {
  try { return JSON.parse(fs.readFileSync(iconMetaFile(dir, id), 'utf8')); } catch { return null; }
}

function writeIconMeta(dir, id, meta, fs = nodeFs) {
  fs.mkdirSync(iconsDir(dir), { recursive: true });
  fs.writeFileSync(iconMetaFile(dir, id), JSON.stringify(meta, null, 2), { mode: 0o600 });
  return meta;
}

/** tmp+rename, like every other write here: a reader never sees half an icon. */
function writeIconBytes(dir, id, bytes, fs = nodeFs) {
  const file = iconFile(dir, id);
  fs.mkdirSync(iconsDir(dir), { recursive: true });
  fs.writeFileSync(`${file}.tmp`, bytes, { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  return file;
}

function removeIcon(dir, id, fs = nodeFs) {
  for (const f of [iconFile(dir, id), iconMetaFile(dir, id)]) {
    try { fs.unlinkSync(f); } catch { /* never was one */ }
  }
}

/** The cached icon as something a route can serve, or `{ok:false}`. */
function iconOf(dir, id, fs = nodeFs) {
  const meta = readIconMeta(dir, id, fs);
  if (!meta || !meta.contentType) return { ok: false };
  try {
    const st = fs.statSync(iconFile(dir, id));
    if (!st.size) return { ok: false };
    return { ok: true, file: iconFile(dir, id), contentType: meta.contentType, size: st.size, mtimeMs: st.mtimeMs };
  } catch {
    return { ok: false };
  }
}

/**
 * Is this app's icon due a fetch? New, pointed somewhere else, or an hour old.
 *
 * The URL test is what makes a re-pointed row drop the old page's brand rather
 * than wear it until the hour is up — the same argument that drops the old
 * address's latency.
 */
function iconDue(meta, rec, nowMs) {
  if (!meta) return true;
  if (meta.sourceUrl !== (rec && rec.url)) return true;
  return (nowMs - (Number(meta.fetchedAtMs) || 0)) >= ICON_REFRESH_MS;
}


// --------------------------------------------------------------- the probe

/**
 * One app, fetched with a deadline.
 *
 * `up` is `res.status < 500` — a 401 or a 403 is a PAGE, served by a process
 * that is alive, and calling that "down" would have the board view reading as an
 * outage for as long as it has auth on it. A 5xx is the server saying it is
 * broken, which is the one HTTP answer that means down.
 *
 * ⚠ `redirect:'manual'` — a probe that followed redirects would be a probe of
 * wherever it was sent, and the host rule was applied to the URL that was
 * STORED. The 30x is itself an answer, so it counts as up.
 *
 * ⚠ THE BODY IS CANCELLED. An un-consumed response body keeps a socket in the
 * pool; thirty-two of them on a five-minute timer is a slow leak that presents
 * as a daemon that will not exit.
 */
async function probeApp(rec, opts = {}) {
  const doFetch = opts.fetch || globalThis.fetch;
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : PROBE_TIMEOUT_MS;
  const nowMs = opts.nowMs || (() => Date.now());
  const started = nowMs();
  const stamp = () => Math.floor(nowMs() / 1000);

  const url = rec && typeof rec.url === 'string' ? rec.url : '';
  // A row whose address does not pass the rule is not fetched at all. Such a row
  // can only exist by hand-editing the store file, and a guard that the daemon
  // applies at the route and not at the fetch is not a guard.
  if (!url || urlProblem(url)) {
    return { up: false, lastProbeAt: stamp(), latencyMs: 0, httpStatus: null };
  }

  try {
    const res = await doFetch(url, {
      method: 'GET',
      redirect: 'manual',
      signal: AbortSignal.timeout(timeoutMs),
      headers: { 'user-agent': 'huginn-appd/app-probe' },
    });
    try { if (res.body && typeof res.body.cancel === 'function') await res.body.cancel(); } catch { /* already gone */ }
    return {
      up: res.status < 500,
      lastProbeAt: stamp(),
      latencyMs: Math.max(0, Math.round(nowMs() - started)),
      httpStatus: res.status,
    };
  } catch {
    // Timeout, connection refused, DNS, TLS — from the row's point of view they
    // are one fact: it did not answer. The elapsed time is still reported,
    // because "failed in 2000 ms" and "failed in 1 ms" are different stories.
    return {
      up: false,
      lastProbeAt: stamp(),
      latencyMs: Math.max(0, Math.round(nowMs() - started)),
      httpStatus: null,
    };
  }
}

/** Every app, [PROBE_CONCURRENCY] at a time. Returns `{id: probe}`. */
async function probeAll(recs, opts = {}) {
  const list = (recs || []).filter((r) => r && r.id);
  const width = Number(opts.concurrency) > 0 ? Number(opts.concurrency) : PROBE_CONCURRENCY;
  const out = {};
  let next = 0;
  const worker = async () => {
    for (;;) {
      const i = next++;
      if (i >= list.length) return;
      out[list[i].id] = await probeApp(list[i], opts);
    }
  };
  await Promise.all(Array.from({ length: Math.min(width, list.length) }, worker));
  return out;
}

/**
 * The journal line for one app, or null when nothing worth saying happened.
 *
 * ⚠ ON STATE CHANGE ONLY. A line every sweep is 288 lines a day per app,
 * which is how a journal stops being read. A first observation is silent when it
 * is `up` (the expected case) and speaks when it is not — an app that has
 * never answered since this daemon started is exactly the thing somebody would
 * want to find in the log.
 */
function probeChange(rec, before, after) {
  const was = before ? before.up : undefined;
  const now = after ? after.up : null;
  if (was === undefined) {
    return now === false ? `apps: ${rec.id} is not answering from the host` : null;
  }
  if (was === now) return null;
  if (now === true) return `apps: ${rec.id} is answering again from the host`;
  return `apps: ${rec.id} stopped answering from the host`;
}

// ------------------------------------------------------------------ the store
//
// The fs half. In lib/archive.js this lives in huginn-appd.js; here it does not,
// for the merge reason at the top of the file. The shape is the same one that
// file uses — tmp+rename at 0600, a reload-mutate-save funnel, and nothing that
// caches the file contents between calls.
//
// ⚠ THE ENVELOPE KEY IS STILL `consoles`. See the ⚠ at the top: renaming it
// would empty every existing installation's list on the first read after an
// upgrade. The word on the wire is `apps`; the word on disk is the one that is
// already there.

function storePath(dir) { return path.join(dir, STORE_NAME); }
function markerPath(dir) { return path.join(dir, REBIND_MARKER_NAME); }

/** One `{addr, lastSeenAt}`, cleaned, or null. */
function addrEntry(raw) {
  const addr = normalizeAddr(raw && raw.addr);
  if (!addr) return null;
  return { addr, lastSeenAt: Number(raw.lastSeenAt) > 0 ? Math.floor(Number(raw.lastSeenAt)) : 0 };
}

function readEnvelope(dir, fs = nodeFs) {
  try {
    const raw = JSON.parse(fs.readFileSync(storePath(dir), 'utf8'));
    return {
      schema: Number(raw.schema) || SCHEMA,
      seeded: !!raw.seeded,
      // Same rule on the way out of the file, which is where an invented
      // timestamp would become permanent — the next write saves what load read.
      consoles: Array.isArray(raw.consoles) ? raw.consoles.map((c) => buildRecord(c, storedAddedAt(c))) : [],
      // Added in 3.5.0. Absent in every store written before it, which is why
      // this is a default and not a migration.
      clientAddresses: Array.isArray(raw.clientAddresses)
        ? raw.clientAddresses.map(addrEntry).filter(Boolean) : [],
    };
  } catch {
    return null;
  }
}

/** tmp+rename at 0600, like every other store here: a reader never sees half a file. */
function writeEnvelope(dir, env, fs = nodeFs) {
  const file = storePath(dir);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(env, null, 2), { mode: 0o600 });
  fs.renameSync(`${file}.tmp`, file);
  return env;
}

/**
 * The live store: the file, the probe caches, the address set, the icon cache
 * and the background sweep.
 *
 * The probe and reachability caches are IN MEMORY and never written. An
 * observation is true for seconds; persisting it would mean a daemon that
 * restarts at 04:00 telling somebody at 09:00 that an app was up, on the
 * strength of a measurement made before the reboot. `up:null` and
 * `reachable.ok:null` after a restart are the honest answers, and are why both
 * fields are nullable.
 *
 * The ADDRESS SET is the exception and is persisted, because it is not an
 * observation about an app — it is a fact about this host's clients, accumulated
 * over days, and a daemon that forgot it on every restart would spend the first
 * minutes after a reboot allowing adds it would refuse an hour later.
 */
function createStore(opts = {}) {
  const dir = opts.dir;
  const fs = opts.fs || nodeFs;
  const log = opts.log || (() => {});
  // The address the DAEMON binds, handed down rather than discovered here — see
  // [seedHost]. Empty is honest: the seed then writes what it always wrote.
  const hostAddr = typeof opts.hostAddr === 'string' ? opts.hostAddr.trim() : '';
  const doFetch = opts.fetch || globalThis.fetch;
  const nowMs = opts.nowMs || (() => Date.now());
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : PROBE_TIMEOUT_MS;
  const intervalMs = Number(opts.intervalMs) > 0 ? Number(opts.intervalMs) : PROBE_INTERVAL_MS;
  const freshMs = Number(opts.freshMs) >= 0 ? Number(opts.freshMs) : PROBE_FRESH_MS;
  const addrTtlSec = Number(opts.addrTtlSec) > 0 ? Number(opts.addrTtlSec) : CLIENT_ADDR_TTL_SEC;
  const iconRefreshMs = Number(opts.iconRefreshMs) >= 0 ? Number(opts.iconRefreshMs) : ICON_REFRESH_MS;
  // Icons are off in the unit tests that only care about liveness, and in any
  // caller that has no business making three more requests per row.
  const iconsOn = opts.icons !== false;

  const probes = new Map();
  const reaches = new Map();
  /** addr -> lastSeenAt (epoch seconds). The prerequisite's authority. */
  const addrs = new Map();
  let addrsLoaded = false;
  let addrsFlushedAt = 0;
  let lastSweepStartedMs = 0;
  let sweeping = null;
  let timer = null;

  function stamp() { return Math.floor(nowMs() / 1000); }

  // ------------------------------------------------------- client addresses

  /** Drop anything older than the TTL, and anything past the cap, oldest first. */
  function pruneAddrs() {
    const cutoff = stamp() - addrTtlSec;
    for (const [addr, seen] of addrs) if (seen < cutoff) addrs.delete(addr);
    if (addrs.size <= MAX_CLIENT_ADDRS) return;
    const byAge = [...addrs.entries()].sort((a, b) => a[1] - b[1]);
    for (const [addr] of byAge.slice(0, addrs.size - MAX_CLIENT_ADDRS)) addrs.delete(addr);
  }

  function addrEntries() {
    pruneAddrs();
    return [...addrs.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([addr, lastSeenAt]) => ({ addr, lastSeenAt }));
  }

  /**
   * The file's addresses, merged in once. The newer stamp wins.
   *
   * ⚠ BEFORE THE FIRST FLUSH, ALWAYS. [noteClientAddress] writes the whole set
   * back; a flush that ran before the file had been read would replace what days
   * of clients taught this daemon with whatever the current request arrived on —
   * and the prerequisite would start refusing nothing, on a host that had been
   * checking two addresses the day before.
   */
  function loadAddrs(env) {
    if (addrsLoaded) return;
    addrsLoaded = true;
    for (const e of (env && env.clientAddresses) || []) {
      if (!addrs.has(e.addr) || addrs.get(e.addr) < e.lastSeenAt) addrs.set(e.addr, e.lastSeenAt);
    }
    pruneAddrs();
  }

  /**
   * Remember an address a client ARRIVED ON — `req.socket.localAddress`, which
   * is exactly what /v1/ping reports as `via`.
   *
   * ⚠ THIS IS THE ONLY WAY THE SET GROWS, and it grows from traffic rather than
   * from an interface enumeration this process could not do anyway (the unit's
   * `RestrictAddressFamilies` closes the netlink socket behind getifaddrs, and
   * os.networkInterfaces() throws there). It is also strictly better than
   * enumeration: an interface nobody has ever connected over is not an address
   * an app has to answer on.
   *
   * A NEW address is flushed at once, because it changes what an add would
   * refuse. A refreshed stamp waits — see [CLIENT_ADDR_FLUSH_SEC].
   */
  function noteClientAddress(raw) {
    const addr = normalizeAddr(raw);
    if (!addr) return false;
    // See the ⚠ on loadAddrs: one file read on the first authorised request of
    // this process's life, and a flag check on every one after it.
    if (!addrsLoaded) loadAddrs(readEnvelope(dir, fs));
    const fresh = !addrs.has(addr);
    addrs.set(addr, stamp());
    if (fresh) log(`apps: a client arrived on ${addr} — every app now has to answer there too`);
    if (fresh || stamp() - addrsFlushedAt >= CLIENT_ADDR_FLUSH_SEC) flushAddrs();
    return fresh;
  }

  /**
   * Write the address set back, but ONLY into a store that already exists.
   *
   * ⚠ NEVER CREATE THE FILE FROM HERE. `load()` seeds on a MISSING file; a flush
   * that wrote `{seeded:false, consoles:[]}` first would make the store exist
   * without ever seeding it, and the four pages this host serves would never
   * appear. Until something lists the apps, the set lives in memory, which is
   * exactly as long as it matters.
   */
  function flushAddrs() {
    const env = readEnvelope(dir, fs);
    if (!env) return;
    addrsFlushedAt = stamp();
    try { writeEnvelope(dir, { ...env, schema: SCHEMA, clientAddresses: addrEntries() }, fs); } catch { /* next time */ }
  }

  /**
   * Every address this app has to answer on: the ones clients arrive on, plus
   * this host's own.
   *
   * SELF_ADDR is in here because it is the address the seeded rows are written
   * with and the one the liveness probe uses — an app that does not answer there
   * is broken in a way no client could route around.
   */
  function addresses() {
    if (!addrsLoaded) loadAddrs(readEnvelope(dir, fs));
    const out = new Set(addrEntries().map((e) => e.addr));
    const self = normalizeAddr(hostAddr);
    if (self) out.add(self);
    return [...out];
  }

  // ------------------------------------------------------------------ the file

  function load() {
    const env = readEnvelope(dir, fs);
    // First ever list: seed, once, and record that it happened.
    if (!env) {
      loadAddrs(null);
      return writeEnvelope(dir, {
        schema: SCHEMA, seeded: true, consoles: seedApps(hostAddr, stamp()), clientAddresses: addrEntries(),
      }, fs);
    }
    loadAddrs(env);
    // And every load after that: re-apply the seed's ADDRESS to any row still
    // carrying the one D10 pinned. Costs a list scan and returns `env` unchanged
    // on every load but the first that finds one — after the rewrite no row
    // matches the old literal, so this cannot become a store that rewrites
    // itself forever.
    if (!env.seeded) return env;
    const moved = migrateSeedUrls(env.consoles, hostAddr);
    const united = migrateSeedUnits(moved.apps);
    if (!moved.changed.length && !united.changed.length) return env;
    // The old observation describes the old address. Same reason `patch` drops
    // one when the url changes: a row wearing the previous address's latency is
    // a lie with a number on it.
    for (const id of moved.changed) { probes.delete(id); reaches.delete(id); }
    if (moved.changed.length) {
      log(`apps: re-pointed ${moved.changed.join(', ')} at ${seedHost(hostAddr)} `
        + `(the seeded address named an interface nothing listens on)`);
    }
    if (united.changed.length) {
      log(`apps: learned the unit behind ${united.changed.join(', ')} — their fix lines can name it now`);
    }
    return writeEnvelope(dir, { ...env, schema: SCHEMA, consoles: united.apps }, fs);
  }

  function save(consoles) {
    const env = readEnvelope(dir, fs) || { schema: SCHEMA, seeded: true, consoles: [] };
    return writeEnvelope(dir, { ...env, schema: SCHEMA, consoles, clientAddresses: addrEntries() }, fs);
  }

  /** The marker the owner touches. The daemon reads it and never writes it. */
  function retrofitApplied() {
    try { return fs.existsSync(markerPath(dir)); } catch { return false; }
  }

  // ------------------------------------------------------------------- icons

  /** Fetch and cache this app's favicon, if it is due one. Never throws. */
  async function refreshIcon(rec) {
    if (!iconsOn) return false;
    const meta = readIconMeta(dir, rec.id, fs);
    if (!iconDue(meta, rec, nowMs())) return false;
    let got;
    try {
      got = await fetchIcon(rec.url, { fetch: doFetch, timeoutMs: Math.min(timeoutMs, ICON_TIMEOUT_MS) });
    } catch (e) {
      got = { ok: false, why: (e && e.message) || 'failed' };
    }
    try {
      forgetIcon(rec.id);
      if (got.ok) {
        writeIconBytes(dir, rec.id, got.bytes, fs);
        writeIconMeta(dir, rec.id, {
          contentType: got.contentType, sourceUrl: rec.url, from: got.from,
          bytes: got.bytes.length, fetchedAtMs: nowMs(),
        }, fs);
        return true;
      }
      // A failure is recorded too — see [iconMetaFile].
      writeIconMeta(dir, rec.id, { contentType: null, sourceUrl: rec.url, why: got.why, fetchedAtMs: nowMs() }, fs);
    } catch { /* a cache that cannot be written is a cache that is not used */ }
    return false;
  }

  /**
   * Whether this app has a cached icon, memoised.
   *
   * ⚠ THE LIST IS POLLED WHILE THE VIEW IS OPEN. Asking the filesystem per row
   * per poll is 32 stats and 32 small reads every few seconds for a boolean that
   * changes about hourly. The memo is invalidated by the only three things that
   * can change it, all of which are in this closure.
   */
  const iconKnown = new Map();
  function hasIcon(id) {
    const key = String(id);
    if (iconKnown.has(key)) return iconKnown.get(key);
    const ok = iconOf(dir, key, fs).ok;
    iconKnown.set(key, ok);
    return ok;
  }
  function forgetIcon(id) { iconKnown.delete(String(id)); }

  // ------------------------------------------------------------- the probes

  /**
   * One app: is it alive, and can this host's clients reach it?
   *
   * The two run TOGETHER because they answer one question between them and a row
   * that had a fresh `up` and a stale `reachable` would be read as the newer of
   * the two. The icon comes last and only when something answered — there is no
   * favicon behind a dead port.
   */
  async function observe(rec) {
    const [probe, reach] = await Promise.all([
      probeApp(rec, { fetch: doFetch, timeoutMs, nowMs }),
      reachabilityProbe(rec, addresses(), { fetch: doFetch, timeoutMs, nowMs }),
    ]);
    if (probe.up === true) await refreshIcon(rec);
    return { probe, reach };
  }

  /**
   * The journal line when an app's REACHABILITY changes. Same discipline as
   * [probeChange]: on a change only, and a first observation speaks only when it
   * is bad news.
   */
  function reachChange(rec, before, after) {
    const was = before ? before.ok : undefined;
    const now = after ? after.ok : null;
    if (was === now) return null;
    if (now === false) return `apps: ${rec.id} does not answer at ${failingOf(after).join(', ')} — needs retrofit`;
    if (now === true && was === false) return `apps: ${rec.id} now answers at every address huginn does`;
    return null;
  }

  function failingOf(reach) {
    return ((reach && reach.addresses) || []).filter((a) => a && a.ok === false).map((a) => a.addr);
  }

  function record(rec, probe, reach) {
    const line = probeChange(rec, probes.get(rec.id), probe);
    const rline = reachChange(rec, reaches.get(rec.id), reach);
    probes.set(rec.id, probe);
    reaches.set(rec.id, reach);
    if (line) log(line);
    if (rline) log(rline);
  }

  /**
   * One sweep of every app, never two at once.
   *
   * ⚠ A ROW THAT FAILS IS MARKED, NEVER REMOVED (decision 54). The prerequisite
   * is a gate on ADDING; an app that has been in this list since before the gate
   * existed, or that broke after passing it, carries `reachable.ok:false` and the
   * lines that would fix it. Deleting somebody's row because a port moved would
   * be this feature destroying the registry it exists to keep.
   */
  function sweep() {
    if (sweeping) return sweeping;
    lastSweepStartedMs = nowMs();
    const recs = load().consoles;
    const width = Math.min(PROBE_CONCURRENCY, recs.length);
    let next = 0;
    const worker = async () => {
      for (;;) {
        const i = next++;
        if (i >= recs.length) return;
        const rec = recs[i];
        try {
          const { probe, reach } = await observe(rec);
          record(rec, probe, reach);
        } catch { /* the next sweep asks again */ }
      }
    };
    sweeping = Promise.all(Array.from({ length: width }, worker))
      .catch(() => {})
      .finally(() => { sweeping = null; });
    return sweeping;
  }

  /**
   * Kick a sweep off when the last one is stale — and DO NOT await it. See
   * [PROBE_FRESH_MS]: the list route must answer at the speed of a file read no
   * matter what the network is doing.
   */
  function refreshSoon() {
    if (sweeping) return;
    if (nowMs() - lastSweepStartedMs < freshMs) return;
    sweep();
  }

  function rowOf(rec) {
    return appRow(rec, probes.get(rec.id), { icon: hasIcon(rec.id), reachable: reaches.get(rec.id) });
  }

  function rows() { return load().consoles.map(rowOf); }

  function arm() {
    if (timer) return;
    timer = setInterval(() => { try { sweep(); } catch { /* next tick */ } }, intervalMs);
    // The http server keeps this process alive; this timer must not be the
    // reason a test's daemon refuses to exit.
    if (typeof timer.unref === 'function') timer.unref();
  }
  arm();

  return {
    dir,
    list() { return load().consoles; },
    get(id) { return findApp(load().consoles, id); },
    probeOf(id) { return probes.get(String(id || '')) || noProbe(); },
    reachOf(id) { return reaches.get(String(id || '')) || noReach(); },
    row(id) { const rec = findApp(load().consoles, id); return rec ? rowOf(rec) : null; },
    rows,
    refreshSoon,
    sweep,
    retrofitApplied,
    markerPath: () => markerPath(dir),
    noteClientAddress,
    addresses,
    icon(id) { return iconOf(dir, id, fs); },

    /**
     * Add an app — AFTER proving this host's clients can reach it (decision 54).
     *
     * The order is the feature: the shape rules first (a 400 is about what was
     * typed and needs no network), then the reachability probe, then the write.
     * A row that would have to be marked "needs retrofit" the moment it landed is
     * a row nobody should be able to create.
     */
    async add(input) {
      const r = add(load().consoles, input, stamp());
      if (!r.ok) return r;
      const reach = await reachabilityProbe(r.app, addresses(), { fetch: doFetch, timeoutMs, nowMs });
      if (reach.ok === false) return { ok: false, status: 422, error: reachRefusal(reach), reachable: reach };
      save(r.apps);
      reaches.set(r.app.id, reach);
      return { ...r, reachable: reach };
    },
    patch(id, changes) {
      const r = patch(load().consoles, id, changes);
      if (r.ok) {
        save(r.apps);
        // A re-pointed app's old observation describes a different address —
        // liveness, reachability and the favicon all belonged to the old page.
        if (r.urlChanged) {
          probes.delete(r.app.id);
          reaches.delete(r.app.id);
          removeIcon(dir, r.app.id, fs);
          forgetIcon(r.app.id);
        }
      }
      return r;
    },
    remove(id) {
      const r = remove(load().consoles, id);
      if (r.ok) {
        save(r.apps);
        probes.delete(String(id));
        reaches.delete(String(id));
        removeIcon(dir, String(id), fs);
        forgetIcon(id);
      }
      return r;
    },
    /** The on-demand probe: one app, both questions, awaited, still bounded. */
    async probeNow(id) {
      const rec = findApp(load().consoles, id);
      if (!rec) return null;
      const { probe, reach } = await observe(rec);
      record(rec, probe, reach);
      return appRow(rec, probe, { icon: hasIcon(rec.id), reachable: reach });
    },
    stop() { if (timer) { clearInterval(timer); timer = null; } },
  };
}

/**
 * One store per DATA_DIR, for the life of the process.
 *
 * Memoised because the route block calls it on every request and the probe
 * caches, the address set and the sweep timer must not be rebuilt per call — and
 * because this branch is allowed exactly one require line and one route block in
 * huginn-appd.js, so there is nowhere else for the instance to live.
 */
const stores = new Map();
function store(dir, opts = {}) {
  const key = path.resolve(dir);
  let s = stores.get(key);
  if (!s) { s = createStore({ ...opts, dir: key }); stores.set(key, s); }
  return s;
}

module.exports = {
  MAX_APPS, MAX_NAME, MAX_NOTES, ID_RE, KINDS, DEFAULT_KIND, UNIT_RE,
  PROBE_TIMEOUT_MS, PROBE_CONCURRENCY, PROBE_FRESH_MS, PROBE_INTERVAL_MS,
  CLIENT_ADDR_TTL_SEC, MAX_CLIENT_ADDRS, CLIENT_ADDR_FLUSH_SEC,
  ICONS_DIR_NAME, ICON_MAX_BYTES, ICON_TIMEOUT_MS, ICON_REFRESH_MS, ICON_MAX_REDIRECTS,
  REBIND_MARKER_NAME, STORE_NAME, SCHEMA, FIREWALL_FILE, UNIT_BIND_NOTES,
  REFUSED_SCHEME, REFUSED_USERINFO, REFUSED_TRAVERSAL, REFUSED_HOST,
  oneLine, cleanName, cleanKind, cleanUnit, idFor,
  hostClass, parseAppUrl, urlProblem, normalizeUrl,
  nameProblem, notesProblem, idProblem, unitProblem,
  buildRecord, storedAddedAt, noProbe, noReach, reachOf, appRow, sortApps,
  findApp, add, patch, rename, setUrl, remove,
  SEED_UNITS, SEED_FALLBACK_HOST, seedableHost, pickHostAddr, seedHost, seedUrl, legacySeedUrl,
  seedApps, migrateSeedUrls, migrateSeedUnits,
  portOf, hostnameOf, fixLines, normalizeAddr, addrAuthority, reachUrl, reachOne, reachabilityProbe, reachRefusal,
  readBounded, getBounded, iconHrefFromHtml, fetchIcon,
  iconsDir, iconFile, iconMetaFile, readIconMeta, writeIconMeta, writeIconBytes, removeIcon, iconOf, iconDue,
  probeApp, probeAll, probeChange,
  storePath, markerPath, readEnvelope, writeEnvelope, createStore, store,
};
