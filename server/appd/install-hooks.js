#!/usr/bin/env node
'use strict';
// install-hooks.js — put the headroom gate into ~/.claude/settings.json, and
// take it out again. Run by deploy.sh after the daemon's files are in place.
//
//   node install-hooks.js [--settings <path>] [--script <abs path>]
//                         [--uninstall] [--dry-run]
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

function defaultSettingsPath() {
  return process.env.HUGINN_CLAUDE_SETTINGS
    || path.join(os.homedir(), '.claude', 'settings.json');
}

function parseArgs(argv) {
  const opts = {
    settings: null, script: DEFAULT_SCRIPT, uninstall: false, dryRun: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--settings') { opts.settings = argv[++i]; continue; }
    if (a === '--script') { opts.script = argv[++i]; continue; }
    if (a === '--uninstall') { opts.uninstall = true; continue; }
    if (a === '--dry-run') { opts.dryRun = true; continue; }
    if (a === '-h' || a === '--help') { opts.help = true; continue; }
    throw new Error(`unknown argument: ${a}`);
  }
  if (!opts.settings) opts.settings = defaultSettingsPath();
  if (!opts.script) throw new Error('--script needs a path');
  return opts;
}

function ruleFor(matcher, script) {
  return {
    matcher,
    hooks: [{ type: 'command', command: script, timeout: TIMEOUT_S }],
  };
}

function hasOurCommand(rule, script) {
  return Array.isArray(rule && rule.hooks)
    && rule.hooks.some((h) => h && h.command === script);
}

/**
 * Pure merge. Returns { settings, changes: [{event, action}] } where action is
 * 'added' | 'kept' | 'removed'. `settings` is the SAME object, mutated in place
 * on the parts we own — every other key, and every other rule, keeps its
 * identity (and therefore its bytes on the way back out through stringify).
 */
function mergeHooks(settings, script, { uninstall = false } = {}) {
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
        const others = rule.hooks.filter((h) => !(h && h.command === script));
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

  if (!settings.hooks || typeof settings.hooks !== 'object' || Array.isArray(settings.hooks)) {
    settings.hooks = {};
  }
  for (const [event, matcher] of ENTRIES) {
    const list = Array.isArray(settings.hooks[event]) ? settings.hooks[event] : [];
    if (list.some((rule) => hasOurCommand(rule, script))) {
      changes.push({ event, action: 'kept' });
      settings.hooks[event] = list;
      continue;
    }
    // Appended, never prepended: the title hook on PreToolUse `.*` runs first
    // today and it is async, so it costs nothing to leave it where the operator
    // put it.
    settings.hooks[event] = [...list, ruleFor(matcher, script)];
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
      'usage: install-hooks.js [--settings <path>] [--script <abs path>] [--uninstall] [--dry-run]\n',
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
  }

  const { changes } = mergeHooks(settings, opts.script, { uninstall: opts.uninstall });
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
  const tmp = `${opts.settings}.huginn.tmp`;
  try {
    fs.mkdirSync(path.dirname(opts.settings), { recursive: true });
    fs.writeFileSync(tmp, body, { mode: 0o600 });
    fs.renameSync(tmp, opts.settings);
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch { /* the rename is what matters */ }
    process.stderr.write(`[install-hooks] cannot write ${opts.settings}: ${e.message}\n`);
    return 2;
  }
  process.stdout.write(`[install-hooks] ${opts.settings}: ${summary} (gate: ${opts.script})\n`);
  return 0;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));

module.exports = {
  DEFAULT_SCRIPT, TIMEOUT_S, ENTRIES, mergeHooks, parseArgs, main,
};
