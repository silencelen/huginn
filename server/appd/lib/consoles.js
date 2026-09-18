'use strict';
// Consoles: the internal web pages this host serves, as a list you can look at
// from the phone — armap, the jtyper trainer, the board view, the BTC sim.
//
// WHAT A CONSOLE IS NOT: a Device. lib/devices.js enrols another MACHINE, hands
// it a scope lattice generated from shared/device-policy.json and long-polls it
// for work. A console is a URL with a name and a liveness probe. No key, no
// enrolment, no lifecycle, no shared security story — the two registries are
// deliberately separate and must stay that way (w3-delta §2.5). The only thing
// they share is that both are read-mostly lists of things that live elsewhere,
// which is a reason for the two SURFACES to look alike and no reason at all for
// the stores to touch.
//
// ⚠ THE PRODUCT NEVER OPENS A PORT AND NEVER TOUCHES A FIREWALL (decision 47).
// Three of the four seeded units are bound to this host's tailnet address only,
// so a phone cannot load them; making them loadable means editing a systemd unit
// here and four rules in heimdall's /etc/pve/firewall/117.fw. Those are the
// OWNER's commands, run in a netplan session. This module's contribution is
// [approvalCard] — the exact text, marked `applied:false`, which the app shows
// and NOTHING executes. The daemon learns that the owner did it by finding a
// marker file it never creates ([REBIND_MARKER_NAME]).
//
// Until then every row says `reachableFrom: "host"` and means it: `up:true` is
// "the daemon fetched it from huginn", not "your phone can open it". Conflating
// those is the one way this feature can lie.
//
// Layout mirrors lib/archive.js: a PURE half (rules, shapes, the card, one
// bounded probe) and a store half. Unlike archive the store lives here rather
// than in huginn-appd.js, because Wave 3's merge discipline gives this branch
// exactly one require line and one route block in that file (w3-delta §3(c)) —
// so the fs and the timer are injected and memoised here instead. Everything
// takes its `fs` and its `fetch` as options, so the tests drive it without a
// daemon.

const nodeFs = require('node:fs');
const path = require('node:path');

// ------------------------------------------------------------------- limits

/**
 * How many consoles one host keeps.
 *
 * The contract's number, and MAX_PADS' argument (lib/scratchpads.js): the list
 * is fetched whole by a polling client, and every row is a URL a person is
 * expected to recognise in a glance. A registry of the pages this host serves
 * that needs scrolling has stopped being a registry.
 */
const MAX_CONSOLES = 32;

/** One line, the width of a row's title. cleanName's cap, as scratchpads. */
const MAX_NAME = 60;

/** The sentence under the name. One line, never a paragraph. */
const MAX_NOTES = 200;

/** `^[a-z0-9][a-z0-9-]{0,23}$` — the contract's id grammar, and a route segment. */
const ID_RE = /^[a-z0-9][a-z0-9-]{0,23}$/;

/**
 * What a console IS, for the row's chip. A label and nothing more — it grants
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
 * asserted directly (consoles.test.js: "a server that accepts and never
 * answers").
 */
const PROBE_TIMEOUT_MS = 2_000;

/** Never more than this many probes in flight, so 32 consoles is 8 beats, not 32 sockets. */
const PROBE_CONCURRENCY = 4;

/**
 * How stale a sweep may be before LISTING the consoles kicks off a fresh one.
 *
 * ⚠ The list route does NOT await it. A client polls this list every few seconds
 * while the view is open, and a route that waits for the network turns one dead
 * console into a slow app for everything else on the page. So the list answers
 * from the last sweep — `up:null` when there has never been one — and schedules
 * the next in the background. That is why `up` is nullable at all.
 */
const PROBE_FRESH_MS = 30_000;

/**
 * The background sweep. Settings-free on purpose: there is no knob, because
 * there is no answer to "how often" that a person would ever want to change,
 * and a console's liveness is not an alerting signal (Uptime Kuma is). It exists
 * so a row that nobody is looking at is not wildly stale when somebody looks.
 */
const PROBE_INTERVAL_MS = 5 * 60_000;

/**
 * The file whose EXISTENCE means the owner has applied the rebind + firewall
 * card, flipping every row's copy from "reachable from the host" to "reachable
 * from your devices".
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

/** The envelope's shape version. NOT the per-console `version`; see [buildRecord]. */
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
const REFUSED_SCHEME = 'a console address must start with http:// or https://';
const REFUSED_USERINFO = 'a console address must not carry a username or password';
const REFUSED_TRAVERSAL = "a console address must not contain '..' path segments";
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
 * matters because the phone will hand a console URL to ACTION_VIEW and the
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

/** The classes a console may live in. Everything else is refused. */
const ALLOWED_HOST_CLASSES = new Set(['loopback', 'lan', 'tailnet', 'mesh', 'name']);

/**
 * Parse and judge a console address.
 *
 * Unlike RouteGuard a PATH is allowed here — a console is a page, not a base
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
function parseConsoleUrl(raw) {
  const s = typeof raw === 'string' ? raw.trim() : '';
  if (!s) return { ok: false, why: 'a console needs an address' };
  if (s.length > 2000) return { ok: false, why: 'that address is too long' };
  if (/[\u0000-\u0020\u007f-\u009f\\]/.test(s)) {
    return { ok: false, why: 'a console address must not contain spaces, control characters or backslashes' };
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
  const r = parseConsoleUrl(raw);
  return r.ok ? null : r.why;
}

/** The address as it is stored: WHATWG-canonical, scheme explicit. */
function normalizeUrl(raw) {
  const r = parseConsoleUrl(raw);
  return r.ok ? r.url : '';
}

// ------------------------------------------------------------- the other rules

function nameProblem(raw) {
  const name = cleanName(raw);
  if (!name) return 'a console needs a name';
  if (typeof raw === 'string' && raw.trim().length > MAX_NAME) return `a name is at most ${MAX_NAME} characters`;
  return null;
}

function notesProblem(raw) {
  if (raw == null || raw === '') return null;
  if (typeof raw !== 'string') return 'notes must be text';
  if (raw.trim().length > MAX_NOTES) return `notes are at most ${MAX_NOTES} characters`;
  return null;
}

function idProblem(id, taken = []) {
  const s = String(id || '');
  if (!ID_RE.test(s)) {
    return 'an id is lowercase letters, digits and dashes, 1-24 characters, starting with a letter or digit';
  }
  const set = taken instanceof Set ? taken : new Set(taken || []);
  if (set.has(s)) return `there is already a console called '${s}'`;
  return null;
}

/** An id derived from the display name, the way projectsLib.slugFor derives one. */
function idFor(name) {
  return cleanName(name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24)
    || `console${Math.floor(Date.now() / 1000) % 100000}`;
}

/** Unknown words become `other` rather than an error. See [KINDS]. */
function cleanKind(raw) {
  const k = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
  return KINDS.includes(k) ? k : DEFAULT_KIND;
}

// -------------------------------------------------------------- the record

/**
 * The stored console.
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
    addedAt: Number(input.addedAt) > 0 ? Number(input.addedAt) : now,
    version: Number(input.version) > 0 ? Number(input.version) : 1,
  };
}

/** The never-probed probe state. `up:null` is "no observation", not "down". */
function noProbe() {
  return { up: null, lastProbeAt: 0, latencyMs: null, httpStatus: null };
}

/**
 * The row as a client reads it: the record plus the last observation, plus the
 * one word that keeps this feature honest.
 *
 * ⚠ `reachableFrom` IS NOT DECORATION. The probe runs on huginn, where all four
 * seeds are reachable today; the phone asking for this list generally cannot
 * reach any of them. A row that said "up" without saying from WHERE would be
 * read as "tap this and it opens", which is exactly the thing that is not true
 * until the owner has applied [approvalCard]. In 3.4 the value is always
 * `"host"` — it is a field rather than a constant in the client so that the day
 * the marker file appears, the copy can change without a wire change.
 */
function consoleRow(rec, probe) {
  const p = probe || noProbe();
  return {
    ...buildRecord(rec, rec && rec.addedAt),
    up: p.up === true || p.up === false ? p.up : null,
    lastProbeAt: Number(p.lastProbeAt) || 0,
    latencyMs: Number.isFinite(p.latencyMs) ? p.latencyMs : null,
    httpStatus: Number.isFinite(p.httpStatus) ? p.httpStatus : null,
    reachableFrom: 'host',
  };
}

/** Insertion order, which is the order the owner chose. Ties never happen (ids are unique). */
function sortConsoles(rows) {
  return [...(rows || [])];
}

// ------------------------------------------------------------ list operations
//
// Pure list algebra: every one takes the current list and returns either
// `{ok:true, consoles, console}` or `{ok:false, status, error, console?}`. The
// store half is then only fs — which is what makes all of this testable without
// one.

function findConsole(list, id) {
  return (list || []).find((c) => c && c.id === String(id || '')) || null;
}

function add(list, input = {}, now = Math.floor(Date.now() / 1000)) {
  const current = list || [];
  if (current.length >= MAX_CONSOLES) {
    return { ok: false, status: 400, error: `that is the ${MAX_CONSOLES}-console limit — remove one first` };
  }
  const bad = nameProblem(input.name);
  if (bad) return { ok: false, status: 400, error: bad };
  const badUrl = urlProblem(input.url);
  if (badUrl) return { ok: false, status: 400, error: badUrl };
  const badNotes = notesProblem(input.notes);
  if (badNotes) return { ok: false, status: 400, error: badNotes };

  const id = input.id ? String(input.id) : idFor(input.name);
  const badId = idProblem(id, current.map((c) => c.id));
  if (badId) return { ok: false, status: 400, error: badId };

  const rec = buildRecord({ ...input, id, version: 1, addedAt: now }, now);
  return { ok: true, consoles: [...current, rec], console: rec };
}

/**
 * The version-checked write. `version` is what the editor last read, and a write
 * against a stale one is refused WITH THE CURRENT ROW so the client can show
 * what it collided with instead of a bare error (the saveScratchpad contract).
 */
function patch(list, id, changes = {}) {
  const current = list || [];
  const rec = findConsole(current, id);
  if (!rec) return { ok: false, status: 404, error: 'no such console' };

  const asked = Number(changes.version);
  if (!Number.isFinite(asked)) return { ok: false, status: 400, error: 'version is required' };
  if (asked !== rec.version) {
    return {
      ok: false,
      status: 409,
      error: 'that console changed somewhere else while you were editing it',
      console: rec,
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
  if (changes.kind !== undefined) next.kind = cleanKind(changes.kind);
  next.version = rec.version + 1;

  return {
    ok: true,
    consoles: current.map((c) => (c.id === rec.id ? next : c)),
    console: next,
    // Whether the thing the probe measures moved. A rename does not invalidate
    // an observation; a new address does, and a row still showing the old
    // address's latency is a lie with a number on it.
    urlChanged: next.url !== rec.url,
  };
}

/** Rename, as a named operation, because that is the edit people actually make. */
function rename(list, id, name) {
  const rec = findConsole(list, id);
  return rec ? patch(list, id, { version: rec.version, name }) : { ok: false, status: 404, error: 'no such console' };
}

/** Re-point a console at a new address. Same guard, same version bump. */
function setUrl(list, id, url) {
  const rec = findConsole(list, id);
  return rec ? patch(list, id, { version: rec.version, url }) : { ok: false, status: 404, error: 'no such console' };
}

function remove(list, id) {
  const current = list || [];
  const rec = findConsole(current, id);
  if (!rec) return { ok: false, status: 404, error: 'no such console' };
  return { ok: true, consoles: current.filter((c) => c.id !== rec.id), console: rec };
}

// -------------------------------------------------------------------- the seed

/**
 * The four pages this host serves, from the contract — written once, on the
 * first list against a store that has never existed, so the feature arrives
 * populated instead of arriving as an empty list with a plus button.
 *
 * Seeded ONCE and recorded as such in the envelope: an owner who deletes all
 * four has said something, and a seeder that ran on every empty list would
 * argue with them forever.
 *
 * The addresses are the contract's: the host's own name, not 127.0.0.1, because
 * this string is opened by a PHONE. Loopback would make the probe pass and the
 * tap fail, which is the exact confusion `reachableFrom` exists to prevent.
 */
function seedConsoles(now = Math.floor(Date.now() / 1000)) {
  return [
    { id: 'armap', name: 'Architecture map', url: 'http://huginn:8088/', kind: 'docs',
      notes: 'Static armap dashboard (docs/current/armap).' },
    { id: 'jtyper', name: 'jtyper trainer', url: 'http://huginn:8091/', kind: 'lab',
      notes: 'Blind test + chat + hole-fill flywheel.' },
    { id: 'board', name: 'PCB board view', url: 'http://huginn:8092/', kind: 'tool',
      notes: 'Live KiCad board review (brokkr renders).' },
    { id: 'btc15m', name: 'BTC 15m simulator', url: 'http://huginn:8093/', kind: 'lab',
      notes: 'Paper-trading sim for the 15-minute lab.' },
  ].map((c) => buildRecord(c, now));
}

// ---------------------------------------------------------------- the card

/**
 * The rebind, verbatim: three units on THIS host whose ExecStart binds the
 * tailnet address only. btc15m-sim already falls back to 0.0.0.0, which is why
 * it is named in the note and absent from the commands.
 */
const REBIND_COMMANDS = [
  'systemctl edit armap.service            # ExecStart: bind 0.0.0.0 instead of the tailnet address',
  'systemctl edit jtyper-trainer.service   # same',
  'systemctl edit boardserver.service      # same',
  'systemctl restart armap jtyper-trainer boardserver',
  "ss -ltnp | grep -E '8088|8091|8092'",
];

/**
 * heimdall's four firewall lines, verbatim from the contract — one source, one
 * port each, the convention already in that file at lines 14 and 18.
 *
 * ⚠ THIS IS ANOTHER MACHINE. huginn has no business editing heimdall's
 * /etc/pve/firewall/117.fw and this daemon has no code that could (w3-delta §4,
 * risk 8: "nothing in Wave 3 may touch heimdall or run systemctl edit").
 */
const FIREWALL_FILE = '/etc/pve/firewall/117.fw';
const FIREWALL_COMMANDS = [
  'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog',
  'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog',
  'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8092 -log nolog',
  'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8093 -log nolog',
];

/**
 * The approval card: what it would take to open these from a phone, as TEXT.
 *
 * Decision 47 in one object. The app draws it; nothing runs it; `applied` is
 * false until the marker file exists. Every field is data — there is no verb in
 * this payload and no route that takes one.
 */
function approvalCard(applied = false, markerPath = `<DATA_DIR>/${REBIND_MARKER_NAME}`) {
  return {
    applied: !!applied,
    runBy: 'owner',
    markerPath,
    title: 'Open these from your phone or laptop',
    why: 'Three of these pages are bound to this host only, so the probe below can reach them and '
      + 'your devices cannot. Applying this changes that. Huginn does not run these commands.',
    steps: [
      {
        id: 'rebind',
        where: 'huginn (this host)',
        summary: 'Bind the three units to 0.0.0.0. btc15m-sim already does.',
        file: null,
        commands: [...REBIND_COMMANDS],
      },
      {
        id: 'firewall',
        where: 'heimdall',
        summary: `Add four rules to ${FIREWALL_FILE}, one source and one port each.`,
        file: FIREWALL_FILE,
        commands: [...FIREWALL_COMMANDS],
      },
    ],
    // Said in the payload and not only in the UI, because this object travels to
    // two clients and a CLI renderer, and each of them would otherwise write its
    // own version of the sentence.
    note: 'Copy these into a netplan session and run them there. When they are done, '
      + `\`touch ${markerPath}\` and every console row will say so.`,
  };
}

// --------------------------------------------------------------- the probe

/**
 * One console, fetched with a deadline.
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
async function probeConsole(rec, opts = {}) {
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
      headers: { 'user-agent': 'huginn-appd/console-probe' },
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

/** Every console, [PROBE_CONCURRENCY] at a time. Returns `{id: probe}`. */
async function probeAll(recs, opts = {}) {
  const list = (recs || []).filter((r) => r && r.id);
  const width = Number(opts.concurrency) > 0 ? Number(opts.concurrency) : PROBE_CONCURRENCY;
  const out = {};
  let next = 0;
  const worker = async () => {
    for (;;) {
      const i = next++;
      if (i >= list.length) return;
      out[list[i].id] = await probeConsole(list[i], opts);
    }
  };
  await Promise.all(Array.from({ length: Math.min(width, list.length) }, worker));
  return out;
}

/**
 * The journal line for one console, or null when nothing worth saying happened.
 *
 * ⚠ ON STATE CHANGE ONLY. A line every sweep is 288 lines a day per console,
 * which is how a journal stops being read. A first observation is silent when it
 * is `up` (the expected case) and speaks when it is not — a console that has
 * never answered since this daemon started is exactly the thing somebody would
 * want to find in the log.
 */
function probeChange(rec, before, after) {
  const was = before ? before.up : undefined;
  const now = after ? after.up : null;
  if (was === undefined) {
    return now === false ? `consoles: ${rec.id} is not answering from the host` : null;
  }
  if (was === now) return null;
  if (now === true) return `consoles: ${rec.id} is answering again from the host`;
  return `consoles: ${rec.id} stopped answering from the host`;
}

// ------------------------------------------------------------------ the store
//
// The fs half. In lib/archive.js this lives in huginn-appd.js; here it does not,
// for the merge reason at the top of the file. The shape is the same one that
// file uses — tmp+rename at 0600, a reload-mutate-save funnel, and nothing that
// caches the file contents between calls.

function storePath(dir) { return path.join(dir, STORE_NAME); }
function markerPath(dir) { return path.join(dir, REBIND_MARKER_NAME); }

function readEnvelope(dir, fs = nodeFs) {
  try {
    const raw = JSON.parse(fs.readFileSync(storePath(dir), 'utf8'));
    return {
      schema: Number(raw.schema) || SCHEMA,
      seeded: !!raw.seeded,
      consoles: Array.isArray(raw.consoles) ? raw.consoles.map((c) => buildRecord(c, c && c.addedAt)) : [],
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
 * The live store: the file, the probe cache and the background sweep.
 *
 * The probe cache is IN MEMORY and never written. A liveness observation is true
 * for seconds; persisting it would mean a daemon that restarts at 04:00 telling
 * somebody at 09:00 that a console was up, on the strength of a measurement made
 * before the reboot. `up:null` after a restart is the honest answer, and it is
 * why the field is nullable.
 */
function createStore(opts = {}) {
  const dir = opts.dir;
  const fs = opts.fs || nodeFs;
  const log = opts.log || (() => {});
  const doFetch = opts.fetch || globalThis.fetch;
  const nowMs = opts.nowMs || (() => Date.now());
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : PROBE_TIMEOUT_MS;
  const intervalMs = Number(opts.intervalMs) > 0 ? Number(opts.intervalMs) : PROBE_INTERVAL_MS;
  const freshMs = Number(opts.freshMs) >= 0 ? Number(opts.freshMs) : PROBE_FRESH_MS;

  const probes = new Map();
  let lastSweepStartedMs = 0;
  let sweeping = null;
  let timer = null;

  function load() {
    const env = readEnvelope(dir, fs);
    if (env) return env;
    // First ever list: seed, once, and record that it happened.
    return writeEnvelope(dir, { schema: SCHEMA, seeded: true, consoles: seedConsoles(stamp()) }, fs);
  }

  function stamp() { return Math.floor(nowMs() / 1000); }

  function save(consoles) {
    const env = readEnvelope(dir, fs) || { schema: SCHEMA, seeded: true, consoles: [] };
    return writeEnvelope(dir, { ...env, schema: SCHEMA, consoles }, fs);
  }

  function applied() {
    try { return fs.existsSync(markerPath(dir)); } catch { return false; }
  }

  /** One sweep of every console, never two at once. */
  function sweep() {
    if (sweeping) return sweeping;
    lastSweepStartedMs = nowMs();
    const recs = load().consoles;
    sweeping = probeAll(recs, { fetch: doFetch, timeoutMs, nowMs })
      .then((results) => {
        for (const rec of recs) {
          const after = results[rec.id];
          if (!after) continue;
          const line = probeChange(rec, probes.get(rec.id), after);
          probes.set(rec.id, after);
          if (line) log(line);
        }
        return results;
      })
      .catch(() => ({}))
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

  function rows() {
    return load().consoles.map((rec) => consoleRow(rec, probes.get(rec.id)));
  }

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
    get(id) { return findConsole(load().consoles, id); },
    probeOf(id) { return probes.get(String(id || '')) || noProbe(); },
    rows,
    refreshSoon,
    sweep,
    applied,
    markerPath: () => markerPath(dir),
    approval() { return approvalCard(applied(), markerPath(dir)); },

    add(input) {
      const r = add(load().consoles, input, stamp());
      if (r.ok) save(r.consoles);
      return r;
    },
    patch(id, changes) {
      const r = patch(load().consoles, id, changes);
      if (r.ok) {
        save(r.consoles);
        // A re-pointed console's old observation describes a different address.
        if (r.urlChanged) probes.delete(r.console.id);
      }
      return r;
    },
    remove(id) {
      const r = remove(load().consoles, id);
      if (r.ok) { save(r.consoles); probes.delete(String(id)); }
      return r;
    },
    /** The on-demand probe: one console, awaited, still bounded. */
    async probeNow(id) {
      const rec = findConsole(load().consoles, id);
      if (!rec) return null;
      const after = await probeConsole(rec, { fetch: doFetch, timeoutMs, nowMs });
      const line = probeChange(rec, probes.get(rec.id), after);
      probes.set(rec.id, after);
      if (line) log(line);
      return consoleRow(rec, after);
    },
    stop() { if (timer) { clearInterval(timer); timer = null; } },
  };
}

/**
 * One store per DATA_DIR, for the life of the process.
 *
 * Memoised because the route block calls it on every request and the probe cache
 * and the sweep timer must not be rebuilt per call — and because this branch is
 * allowed exactly one require line and one route block in huginn-appd.js, so
 * there is nowhere else for the instance to live.
 */
const stores = new Map();
function store(dir, opts = {}) {
  const key = path.resolve(dir);
  let s = stores.get(key);
  if (!s) { s = createStore({ ...opts, dir: key }); stores.set(key, s); }
  return s;
}

module.exports = {
  MAX_CONSOLES, MAX_NAME, MAX_NOTES, ID_RE, KINDS, DEFAULT_KIND,
  PROBE_TIMEOUT_MS, PROBE_CONCURRENCY, PROBE_FRESH_MS, PROBE_INTERVAL_MS,
  REBIND_MARKER_NAME, STORE_NAME, SCHEMA,
  REBIND_COMMANDS, FIREWALL_COMMANDS, FIREWALL_FILE,
  REFUSED_SCHEME, REFUSED_USERINFO, REFUSED_TRAVERSAL, REFUSED_HOST,
  oneLine, cleanName, cleanKind, idFor,
  hostClass, parseConsoleUrl, urlProblem, normalizeUrl,
  nameProblem, notesProblem, idProblem,
  buildRecord, noProbe, consoleRow, sortConsoles,
  findConsole, add, patch, rename, setUrl, remove,
  seedConsoles, approvalCard,
  probeConsole, probeAll, probeChange,
  storePath, markerPath, readEnvelope, writeEnvelope, createStore, store,
};
