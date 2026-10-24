'use strict';
// The status-line record server/bin/huginn-statusline keeps under
// STATE_DIR/.status/<tmux session>: Claude Code's own status-line JSON, verbatim,
// rewritten on every redraw of the pane. This reads it into the objects the
// wire carries on a Session row and on /screen — context, model, effort, fast,
// limits, cost — and says nothing (every field null) when there is no record
// worth trusting.
//
// Shape verified against Claude Code 2.1.306 (test/fixtures/statusline-2.1.306.json):
//
//   context_window{total_input_tokens, total_output_tokens, context_window_size,
//                  current_usage{input_tokens, output_tokens,
//                  cache_creation_input_tokens, cache_read_input_tokens},
//                  used_percentage, remaining_percentage}   current_usage and both
//                                                           percentages are null
//                                                           before the first response
//   cost{total_cost_usd, total_duration_ms, total_api_duration_ms,
//        total_lines_added, total_lines_removed}
//   effort{level}  exceeds_200k_tokens  fast_mode (bool)  model{id, display_name}
//   rate_limits{five_hour{used_percentage, resets_at}, seven_day{…}, spend_limit?}
//   session_id  transcript_path  version  …
//
// Pure except for the two file reads; nothing here touches tmux.

const fs = require('node:fs');
const path = require('node:path');
const { fastModeUnavailable } = require('./pane');

const DIRNAME = '.status';

/**
 * A record older than this is nobody's: the status line redraws many times a
 * turn and at least once at every turn boundary, so a session that has not
 * written one in six hours ended without its SessionEnd (a kill, a reboot) and
 * the file is a corpse the daily sweep has not reached yet.
 */
const MAX_AGE_MS = 6 * 60 * 60 * 1000;

function statusFile(stateDir, name) { return path.join(stateDir, DIRNAME, name); }

/** The record as written — `{json, mtimeMs}` — or null. Never throws. */
function readRecord(stateDir, name) {
  const file = statusFile(stateDir, name);
  let raw;
  let mtimeMs;
  try {
    raw = fs.readFileSync(file, 'utf8');
    mtimeMs = fs.statSync(file).mtimeMs;
  } catch { return null; }
  let json;
  try { json = JSON.parse(raw); } catch { return null; }
  if (!json || typeof json !== 'object' || Array.isArray(json)) return null;
  return { json, mtimeMs };
}

function clearStatus(stateDir, name) {
  try { fs.unlinkSync(statusFile(stateDir, name)); } catch { /* gone */ }
}

const EMPTY = Object.freeze({ context: null, model: null, effort: null, fast: null, limits: null, cost: null });
/** Every wire field, null: what a session without a record serves. */
function empty() { return { ...EMPTY }; }

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : null; }
function int(v) { const n = num(v); return n === null ? null : Math.round(n); }
function obj(v) { return v && typeof v === 'object' && !Array.isArray(v) ? v : null; }

/**
 * `context`, or null before the first response: the harness then reports the
 * window size but no usage and no percentage, and a card showing "0 of 1m" would
 * be a claim nobody made. `used` is what Claude Code counts against the window —
 * the input tokens plus both cache figures; `percent` is its own rounding.
 */
function contextOf(j) {
  const cw = obj(j.context_window);
  if (!cw) return null;
  const pct = num(cw.used_percentage);
  const cu = obj(cw.current_usage);
  if (pct === null || !cu) return null;
  const used = ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens']
    .reduce((s, k) => s + (int(cu[k]) || 0), 0);
  return {
    used,
    window: int(cw.context_window_size),
    percent: Math.max(0, Math.min(100, Math.round(pct))),
    exceeds200k: j.exceeds_200k_tokens === true,
  };
}

/** One rate-limit window → {usedPercent, resetsAt (epoch seconds)}, or null. */
function limitOf(l) {
  const o = obj(l);
  if (!o) return null;
  const used = num(o.used_percentage);
  return { usedPercent: used === null ? null : Math.round(used), resetsAt: int(o.resets_at) };
}

function limitsOf(j) {
  const rl = obj(j.rate_limits);
  if (!rl) return null;
  const out = { fiveHour: limitOf(rl.five_hour), sevenDay: limitOf(rl.seven_day), spend: limitOf(rl.spend_limit) };
  return out.fiveHour || out.sevenDay || out.spend ? out : null;
}

function costOf(j) {
  const c = obj(j.cost);
  if (!c) return null;
  return {
    usd: num(c.total_cost_usd) ?? 0,
    durationMs: int(c.total_duration_ms) ?? 0,
    apiMs: int(c.total_api_duration_ms) ?? 0,
    linesAdded: int(c.total_lines_added) ?? 0,
    linesRemoved: int(c.total_lines_removed) ?? 0,
  };
}

const EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

/**
 * The wire objects out of one status-line JSON.
 *
 * `fast.available` is not in the JSON: the status line only says whether fast
 * mode is ON. Whether it CAN be is read off the pane, where Claude Code prints
 * its own sentence when a `/fast on` did not take (`Fast mode unavailable…`,
 * `Fast mode requires usage credits…`); without pane lines, or with none of
 * those on it, fast mode is taken to be available. `reason` is that sentence.
 */
function parseStatusRecord(json, { paneLines = null } = {}) {
  const j = obj(json) || {};
  const m = obj(j.model);
  const model = m && typeof m.display_name === 'string' && m.display_name.trim() ? m.display_name.trim() : null;
  const e = obj(j.effort);
  const level = e && typeof e.level === 'string' ? e.level.trim().toLowerCase() : null;
  const effort = level && EFFORT_LEVELS.has(level) ? level : null;
  let fast = null;
  if (typeof j.fast_mode === 'boolean') {
    const reason = paneLines ? fastModeUnavailable(paneLines) : null;
    fast = { on: j.fast_mode, available: reason === null, reason };
  }
  return { context: contextOf(j), model, effort, fast, limits: limitsOf(j), cost: costOf(j) };
}

/**
 * The wire objects for session `name`, or [empty] when there is no record, it is
 * older than [MAX_AGE_MS], or it was written by another Claude session under the
 * same tmux name (`sessionId` is the state file's; a record a previous run left
 * behind says nothing about this one, the same rule the prompt sidecars follow).
 */
function statusFor(stateDir, name, { sessionId = null, nowMs = Date.now(), paneLines = null } = {}) {
  const rec = readRecord(stateDir, name);
  if (!rec) return empty();
  if (nowMs - rec.mtimeMs > MAX_AGE_MS) return empty();
  const sid = typeof rec.json.session_id === 'string' ? rec.json.session_id : null;
  if (sessionId && sid && sid !== sessionId) return empty();
  return parseStatusRecord(rec.json, { paneLines });
}

module.exports = {
  DIRNAME, MAX_AGE_MS, statusFile, readRecord, clearStatus, empty, parseStatusRecord, statusFor,
};
