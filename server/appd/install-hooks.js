#!/usr/bin/env node
'use strict';
// install-hooks.js — put the headroom gate into ~/.claude/settings.json, and
// take it out again. Run by deploy.sh after the daemon's files are in place.
//
//   node install-hooks.js [--settings <path>] [--script <abs path>]
//                         [--headroom-dir <path>] [--uninstall] [--dry-run]
//
// --headroom-dir binds the daemon's sentinel directory to the hook (`env
// HUGINN_HEADROOM_DIR=… <script>`). Without it the gate uses its own compiled-in
// default, which is wrong for any daemon started with HUGINN_APPD_DATA or
// HUGINN_HEADROOM_DIR set — and wrong there means a pause button wired to
// nothing, with no symptom. deploy.sh passes the dir the service will really use.
//
// The two entries it manages:
//
//   SubagentStart  matcher "*"               timeout 1800
//   PreToolUse     matcher "Agent|Workflow"  timeout 1800
//
// Both are needed and neither is redundant. SubagentStart is the only event that
// sees an agent spawned INSIDE a Workflow script (the parent turn keeps running
// while the inner agent waits — the graceful shape); PreToolUse is the only one
// that sees a foreground Agent or Workflow tool call before it starts. `timeout`
// is SECONDS, and it is the ceiling on the hold: the gate self-releases 30 s
// under it so that a release is always the gate's rather than a SIGKILL's.
//
// ⚠ Our entries must NEVER carry `async: true`. An async hook is fired and
// forgotten — it cannot hold anything — so an "async" gate would be a pause
// button wired to nothing, with no symptom but the spawns it failed to stop.
//
// ⚠ This file rewrites the user's settings.json, which is also where the CLI
// keeps `model`, `permissions` and everyone else's hooks. Three rules follow
// from that: parse first and REFUSE on failure (exit 2, nothing written) rather
// than overwrite a file we could not read; add only, by `command`, so a second
// run is a no-op; and write tmp+rename with the same 2-space JSON the CLI uses,
// so the diff is our two entries and nothing else. `claude config` is gone in
// 2.1.258 (it starts a billed interactive session) — direct file editing is the
// supported path, not a shortcut.

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DEFAULT_SCRIPT = '/opt/huginn-appd/hooks/huginn-headroom-gate';
const TIMEOUT_S = 1800;
// event -> matcher. The matcher is a regex over tool_name (PreToolUse) and over
// agent_type (SubagentStart), where "*" is how the CLI's own examples spell
// "every agent type".
const ENTRIES = [
  ['SubagentStart', '*'],
  ['PreToolUse', 'Agent|Workflow'],
];

// PRESENCE, not truthiness. An empty HUGINN_CLAUDE_SETTINGS used to fall back to
// the live shared file, so a caller that meant to point this tool at a scratch
// copy and got an unset variable rewrote ~/.claude/settings.json and exited 0 —
// including every case in test/install-hooks.test.js, whose helper sets the
// variable to '' on purpose so a forgotten --settings cannot reach it.
function defaultSettingsPath() {
  const env = process.env.HUGINN_CLAUDE_SETTINGS;
  if (env !== undefined) {
    if (!env.trim()) throw new Error('HUGINN_CLAUDE_SETTINGS is set but empty — name a file or unset it');
    return env;
  }
  return path.join(os.homedir(), '.claude', 'settings.json');
}

/**
 * A flag's value, refused when it is missing, empty, or the NEXT FLAG.
 *
 * `--settings --dry-run` used to swallow the flag behind it and perform a real
 * install into a file named `./--dry-run`; `--settings ""` (an unset shell
 * variable) silently targeted the live settings file. `--script` has always
 * refused both, and the asymmetry is the whole finding.
 */
function valueFor(flag, argv, i) {
  const v = argv[i];
  if (!v || v.startsWith('--')) throw new Error(`${flag} needs a path`);
  return v;
}

/** Shell-quote, for the one place a path is interpolated into a command line. */
function shq(v) {
  const s = String(v);
  return /^[A-Za-z0-9_@%+=:,./-]+$/.test(s) ? s : `'${s.replace(/'/g, "'\\''")}'`;
}

/**
 * The command we install for `script`.
 *
 * With a headroom dir it BINDS that dir to the hook: the daemon's sentinel
 * directory moves with HUGINN_APPD_DATA, the gate has its own compiled-in
 * default, and nothing reconnected them — so a relocated data root left the
 * pause button wired to nothing, with no symptom (the gate releases every spawn
 * at waited=0 and writes its log into the abandoned directory, while
 * /v1/headroom cheerfully reports the sentinel armed).
 */
function commandFor(script, headroomDir = null) {
  return headroomDir ? `env HUGINN_HEADROOM_DIR=${shq(headroomDir)} ${shq(script)}` : String(script);
}

/** The script path out of a command that may carry an `env VAR=… ` prefix. */
function scriptOf(command) {
  let s = String(command || '').trim();
  if (/^env\s/.test(s)) {
    s = s.replace(/^env\s+/, '');
    for (;;) {
      const m = /^[A-Za-z_][A-Za-z0-9_]*=(?:'[^']*'|"[^"]*"|\S*)\s+/.exec(s);
      if (!m) break;
      s = s.slice(m[0].length);
    }
  }
  const m = /^(?:'([^']*)'|"([^"]*)"|(\S+))/.exec(s);
  if (!m) return s;
  return m[1] ?? m[2] ?? m[3];
}

function parseArgs(argv) {
  const opts = {
    settings: null, script: DEFAULT_SCRIPT, headroomDir: null, uninstall: false, dryRun: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--settings') { opts.settings = valueFor('--settings', argv, ++i); continue; }
    if (a === '--script') { opts.script = valueFor('--script', argv, ++i); continue; }
    if (a === '--headroom-dir') { opts.headroomDir = valueFor('--headroom-dir', argv, ++i); continue; }
    if (a === '--uninstall') { opts.uninstall = true; continue; }
    if (a === '--dry-run') { opts.dryRun = true; continue; }
    if (a === '-h' || a === '--help') { opts.help = true; continue; }
    throw new Error(`unknown argument: ${a}`);
  }
  if (!opts.settings) opts.settings = defaultSettingsPath();
  if (!opts.script) throw new Error('--script needs a path');
  return opts;
}

function ruleFor(matcher, script, headroomDir = null) {
  return {
    matcher,
    hooks: [{ type: 'command', command: commandFor(script, headroomDir), timeout: TIMEOUT_S }],
  };
}

/**
 * Is this hook entry OURS?
 *
 * By BASENAME, not by full path. `$DEST` moving (a rename, a relocation of
 * /opt/huginn-appd) used to append a SECOND rule and leave the old one pointing
 * at a script that no longer exists — and a hook whose command cannot be
 * executed is read by the CLI as a BLOCK on every Agent/Workflow call. The
 * basename is the identity; the path is a detail this installer owns.
 */
function isOurCommand(h, script) {
  return !!h && typeof h.command === 'string'
    && path.basename(scriptOf(h.command)) === path.basename(script);
}

function hasOurCommand(rule, script) {
  return Array.isArray(rule && rule.hooks)
    && rule.hooks.some((h) => isOurCommand(h, script));
}

/**
 * Pure merge. Returns { settings, changes: [{event, action}] } where action is
 * 'added' | 'kept' | 'removed'. `settings` is the SAME object, mutated in place
 * on the parts we own — every other key, and every other rule, keeps its
 * identity (and therefore its bytes on the way back out through stringify).
 */
function mergeHooks(settings, script, { uninstall = false, exists = fs.existsSync, headroomDir = null } = {}) {
  const want = commandFor(script, headroomDir);
  const changes = [];
  if (uninstall) {
    const hooks = settings.hooks;
    if (!hooks || typeof hooks !== 'object') return { settings, changes };
    for (const [event] of ENTRIES) {
      const list = hooks[event];
      if (!Array.isArray(list)) continue;
      let removed = 0;
      const kept = [];
      for (const rule of list) {
        if (!hasOurCommand(rule, script)) { kept.push(rule); continue; }
        // Only OUR command leaves; a rule that also carried somebody else's
        // hook keeps that hook and stays.
        const others = rule.hooks.filter((h) => !isOurCommand(h, script));
        removed += rule.hooks.length - others.length;
        if (others.length) { rule.hooks = others; kept.push(rule); }
      }
      if (!removed) continue;
      changes.push({ event, action: 'removed' });
      if (kept.length) hooks[event] = kept;
      else delete hooks[event];
    }
    if (Object.keys(hooks).length === 0 && changes.length) delete settings.hooks;
    return { settings, changes };
  }

  // A present-but-wrong-shaped `hooks` is REFUSED by main() before we are
  // called (it is somebody's file, not ours to replace with `{}`), so the only
  // thing left to do here is create it when it is genuinely absent.
  if (settings.hooks === undefined) settings.hooks = {};
  for (const [event, matcher] of ENTRIES) {
    const list = Array.isArray(settings.hooks[event]) ? settings.hooks[event] : [];
    // Sweep OUR OWN entries first: prune one whose script is gone, repoint one
    // that moved, and drop a second copy. Everyone else's hooks are untouched,
    // and a rule that also carried somebody else's hook keeps that hook.
    let pruned = 0;
    let repointed = 0;
    let seen = 0;
    const kept = [];
    for (const rule of list) {
      if (!Array.isArray(rule && rule.hooks)) { kept.push(rule); continue; }
      const hooks = [];
      for (const h of rule.hooks) {
        if (!isOurCommand(h, script)) { hooks.push(h); continue; }
        if (h.command !== want && !exists(scriptOf(h.command))) { pruned += 1; continue; }
        if (seen > 0) { pruned += 1; continue; }
        if (h.command !== want) { h.command = want; repointed += 1; }
        seen += 1;
        hooks.push(h);
      }
      if (hooks.length !== rule.hooks.length) rule.hooks = hooks;
      if (rule.hooks.length) kept.push(rule);
    }
    if (seen) {
      changes.push({ event, action: pruned || repointed ? 'repaired' : 'kept' });
      settings.hooks[event] = kept;
      continue;
    }
    // Appended, never prepended: the title hook on PreToolUse `.*` runs first
    // today and it is async, so it costs nothing to leave it where the operator
    // put it.
    settings.hooks[event] = [...kept, ruleFor(matcher, script, headroomDir)];
    changes.push({ event, action: 'added' });
  }
  return { settings, changes };
}

function main(argv) {
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (e) {
    process.stderr.write(`[install-hooks] ${e.message}\n`);
    return 2;
  }
  if (opts.help) {
    process.stdout.write(
      'usage: install-hooks.js [--settings <path>] [--script <abs path>]\n'
      + '                        [--headroom-dir <path>] [--uninstall] [--dry-run]\n',
    );
    return 0;
  }

  let raw = null;
  try {
    raw = fs.readFileSync(opts.settings, 'utf8');
  } catch (e) {
    if (e.code !== 'ENOENT') {
      process.stderr.write(`[install-hooks] cannot read ${opts.settings}: ${e.message}\n`);
      return 2;
    }
    if (opts.uninstall) {
      process.stdout.write(`[install-hooks] no settings file at ${opts.settings} — nothing to remove\n`);
      return 0;
    }
  }

  let settings;
  if (raw === null) {
    settings = {};
  } else {
    try {
      settings = JSON.parse(raw);
    } catch (e) {
      // The one case where doing nothing is the whole job. A settings file we
      // cannot parse is a file somebody is mid-edit in, or one the CLI itself
      // will complain about — either way, rewriting it from our own model of it
      // would delete keys we never read.
      process.stderr.write(`[install-hooks] REFUSING: ${opts.settings} is not valid JSON (${e.message})\n`);
      process.stderr.write('[install-hooks] nothing was written. Fix the file and re-run.\n');
      return 2;
    }
    if (!settings || typeof settings !== 'object' || Array.isArray(settings)) {
      process.stderr.write(`[install-hooks] REFUSING: ${opts.settings} is not a JSON object\n`);
      return 2;
    }
    // Same doctrine one level down. A `hooks` that is an array, a string or null
    // — an older schema, a hand-edit, a generator — used to be REPLACED with an
    // empty object and written away, which is the one thing the refusal above
    // exists to prevent. Nothing is written; the message names the key.
    if ('hooks' in settings
      && (settings.hooks === null || typeof settings.hooks !== 'object' || Array.isArray(settings.hooks))) {
      process.stderr.write(`[install-hooks] REFUSING: ${opts.settings} has a "hooks" value that is not an object\n`);
      process.stderr.write('[install-hooks] nothing was written. Fix the file and re-run.\n');
      return 2;
    }
  }

  const { changes } = mergeHooks(settings, opts.script, {
    uninstall: opts.uninstall, headroomDir: opts.headroomDir,
  });
  const body = `${JSON.stringify(settings, null, 2)}\n`;
  const touched = changes.some((c) => c.action !== 'kept');
  const summary = changes.length
    ? changes.map((c) => `${c.action} ${c.event}`).join(', ')
    : 'nothing to do';

  if (opts.dryRun) {
    process.stdout.write(`[install-hooks] dry run — would write ${opts.settings}: ${summary}\n`);
    return 0;
  }
  // Nothing of ours changed — so do not touch the file at all. Re-serialising a
  // settings file we had no edit for would rewrite somebody's formatting (and
  // their mtime, and their backup diff) to say exactly the same thing.
  if (!touched && raw !== null) {
    process.stdout.write(`[install-hooks] ${opts.settings} already current: ${summary}\n`);
    return 0;
  }
  // ⚠ WRITE THROUGH A SYMLINK, AND KEEP THE MODE.
  //
  // readFileSync FOLLOWS a link; renameSync REPLACES it. `~/.claude/settings.json`
  // linked into a dotfiles repo was therefore detached by the first deploy —
  // after which repo edits stopped reaching the CLI and our gate entry never
  // landed in the tracked file. Resolving the target first keeps the link, and
  // carrying the existing mode forward keeps whatever the owner chose (compare
  // accounts.writeOauthAccount, which does the same twenty lines away).
  let dest = opts.settings;
  try { dest = fs.realpathSync(opts.settings); } catch { /* ENOENT: a new file */ }
  let mode = 0o600;
  try { mode = (fs.statSync(dest).mode & 0o777) || 0o600; } catch { /* a new file */ }
  const tmp = `${dest}.huginn.tmp`;
  try {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(tmp, body, { mode });
    // writeFileSync only applies `mode` when it CREATES the file; a tmp left by
    // an interrupted run would keep its old bits.
    fs.chmodSync(tmp, mode);
    fs.renameSync(tmp, dest);
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch { /* the rename is what matters */ }
    process.stderr.write(`[install-hooks] cannot write ${opts.settings}: ${e.message}\n`);
    return 2;
  }
  process.stdout.write(`[install-hooks] ${opts.settings}: ${summary} (gate: ${commandFor(opts.script, opts.headroomDir)})\n`);
  return 0;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));

module.exports = {
  DEFAULT_SCRIPT, TIMEOUT_S, ENTRIES, mergeHooks, parseArgs, main, isOurCommand,
  commandFor, scriptOf,
};
