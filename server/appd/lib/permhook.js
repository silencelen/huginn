'use strict';
// PermissionRequest answering — the daemon's half of the hook protocol.
//
// Claude Code (>= 2.1.29x) runs a `PermissionRequest` hook for every tool prompt,
// AskUserQuestion included, and a hook that prints a decision ANSWERS it: the tool
// runs (or doesn't) and the TUI dialog closes itself. hooks/huginn-permission-hook
// is that hook. It writes the request where this module can read it and waits;
// /answer, when a person taps an option, writes the decision back here instead of
// typing a digit at the pane. Measured live on 2.1.296 (2026-10-10):
//
//   * allow                          -> the tool runs, the dialog closes
//   * allow + updatedInput.answers   -> AskUserQuestion gets the pick, no keys;
//     {"<question text>": "<label>"}    multi-select is the labels joined ", "
//   * ExitPlanMode                   -> an allow is consumed and IGNORED; plan
//                                       approval stays on the keystroke path
//   * the TUI dialog is drawn WHILE the hook waits, and answering it there does
//     NOT stop the hook — it lingers until its own deadline. A request file is
//     therefore evidence that a hook is alive, never that its prompt is.
//
// So every request is matched against the dialog actually on screen, EXACTLY,
// before anything is written: AskUserQuestion by its whole question text and its
// whole option list, a Bash prompt by its whole command against the dialog's own
// command block (the lines between its `╌╌╌` rules). No other tool takes the hook
// path — their dialog shapes were never captured — and when more than one live
// request matches, nothing does: an approval must bind to exactly one request
// (claude-security scan 2026-10-11, F1/F2: a prefix or basename match plus a
// newest-wins tie-break could route a Yes to a different, unseen tool call when
// parallel subagents — which share the session id — have prompts pending).
// Anything this module is not sure about returns null, and /answer types the
// digit exactly as it always has.
//
// Hand-off is two renames, so exactly one side owns an answer: appd renames
// `<id>.ans.tmp` -> `<id>.ans`; the hook claims it by renaming `<id>.ans` ->
// `<id>.taken`. If the hook has not claimed it within a moment, appd retracts it by
// renaming `<id>.ans` -> `<id>.retracted` — success means the hook never saw it and
// the keystroke path takes over; ENOENT means the hook won the race and the answer
// was delivered.

const fs = require('fs');
const path = require('path');

const PERM_DIRNAME = '.perm';
const ID_RE = /^[0-9]{6,25}-[0-9]{1,10}$/;
const REQ_MAX_AGE_MS = 20 * 60 * 1000;   // past the hook's own 900 s deadline

function permDir(stateDir, name) { return path.join(stateDir, PERM_DIRNAME, name); }

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

const stripAnsi = (s) => String(s || '').replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '');
const norm = (s) => String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
/**
 * The dialog's wrapped command lines, butted back into one string so the same
 * command wrapped across pane rows compares equal to the one on a single line.
 * The TUI hard-wraps a long command mid-token (no hyphen) and pads each line by
 * one column, so drop that one-space pad and the line breaks and keep EVERY
 * other character — spaces and case included.
 *
 * ⚠ THIS IS THE AUTHORIZATION BINDING; it must be LOSSLESS about spaces and
 * case. The old `squash()` deleted all whitespace and lowercased, which made
 * different commands — `rm -rf ~/tmp/x` and `rm -rf ~ /tmp/x` — compare equal, so
 * a tap meant for the dialog on screen could approve a different, unseen live
 * request (claude-security 2026-10-18, F20). `joinBlock` strips the dialog pad;
 * `joinLines` does not. A wrapped-but-identical command still matches; a command
 * that differs by a single space no longer does.
 */
const joinLines = (s) => String(s || '').split('\n').map((l) => l.replace(/\s+$/, '')).join('');
const joinBlock = (s) => String(s || '').split('\n')
  .map((l) => l.replace(/\s+$/, '').replace(/^ /, '')).join('');

/** Requests whose hook process is still alive, oldest first. Never throws. */
function liveRequests(stateDir, name, { alive = pidAlive, now = Date.now() } = {}) {
  let files;
  try { files = fs.readdirSync(permDir(stateDir, name)); } catch { return []; }
  const out = [];
  for (const f of files) {
    if (!f.endsWith('.json')) continue;
    const id = f.slice(0, -5);
    if (!ID_RE.test(id)) continue;
    let r;
    try { r = JSON.parse(fs.readFileSync(path.join(permDir(stateDir, name), f), 'utf8')); } catch { continue; }
    if (!r || r.v !== 1 || r.id !== id || !Number.isInteger(r.pid) || typeof r.tool !== 'string') continue;
    if (!Number.isFinite(r.ts) || now - r.ts > REQ_MAX_AGE_MS || r.ts - now > 60_000) continue;
    if (!alive(r.pid)) continue;
    out.push(r);
  }
  return out.sort((a, b) => a.ts - b.ts);
}

const OPTION_ONE_RE = /^\s*(?:❯\s*)?1\.\s/;
const OPTION_ROW_RE = /^\s*(?:❯\s*)?\d{1,2}[.)]\s/;
const indentOf = (s) => /^\s*/.exec(String(s))[0].length;

/**
 * The dialog's CONTENT column: the least-indented option row from option 1 down.
 *
 * ⚠ WHY A RULE HAS TO SIT LEFT OF IT TO COUNT. The border and the dashed rules
 * that frame the command are glyphs a command can print too. Read by glyph, a
 * Bash command carrying its own `───` and `╌╌╌` rows moved the region's start
 * and the command block onto ITS rows, and the hand-off could then match a
 * different live request whose command was only the tail. Claude Code draws a
 * dialog's own rules at its edge and every content row (the command included)
 * one column in, so only a rule left of the options is the dialog's. A layout
 * with no such pad (0) is an AskUserQuestion's, never a Bash dialog's: it has a
 * region but no command block (dialogCommandBlock).
 */
function optionIndent(rows, first) {
  let min = Infinity;
  for (let i = first; i < rows.length; i++) {
    if (OPTION_ROW_RE.test(rows[i])) min = Math.min(min, indentOf(rows[i]));
  }
  return Number.isFinite(min) ? min : 0;
}
const structuralIn = (optIndent) => (row) => optIndent === 0 || indentOf(row) < optIndent;

/**
 * The dialog's own lines: from its top border (the full-width `───` rule the
 * TUI draws above every selector) down to the bottom of the pane. Without the
 * border, the last 30 lines.
 */
function dialogRegion(lines) {
  const plain = (lines || []).map(stripAnsi);
  let first = -1;
  for (let i = plain.length - 1; i >= 0; i--) {
    if (OPTION_ONE_RE.test(plain[i])) { first = i; break; }
  }
  if (first < 0) return plain.slice(-30).join('\n');
  const structural = structuralIn(optionIndent(plain, first));
  for (let i = first - 1; i >= 0 && i >= first - 60; i--) {
    if (/^\s*─{20,}\s*$/.test(plain[i]) && structural(plain[i])) return plain.slice(i).join('\n');
  }
  return plain.slice(Math.max(0, first - 30)).join('\n');
}

/**
 * The Bash dialog's own command: the lines between the first two `╌╌╌` rules of
 * the dialog region that are the DIALOG's (see optionIndent), or null when the
 * region has no such block.
 */
function dialogCommandBlock(region) {
  const lines = String(region || '').split('\n');
  let first = -1;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (OPTION_ONE_RE.test(lines[i])) { first = i; break; }
  }
  // ⚠ A BASH DIALOG PADS ITS CONTENT ONE COLUMN IN, so a region with no option
  // row, or with options at the very edge, has no command block at all. An
  // AskUserQuestion draws its first option at the edge, and read with no pad
  // every row of the model's own question text counted as the dialog's rules.
  const pad = first < 0 ? 0 : optionIndent(lines, first);
  if (pad === 0) return null;
  const structural = structuralIn(pad);
  const rules = [];
  for (let i = 0; i < lines.length && rules.length < 2; i++) {
    if (/^\s*╌{10,}\s*$/.test(lines[i]) && structural(lines[i])) rules.push(i);
  }
  if (rules.length < 2 || rules[1] - rules[0] < 2) return null;
  return lines.slice(rules[0] + 1, rules[1]).join('\n');
}

function askQuestion(req) {
  const qs = req && req.input && Array.isArray(req.input.questions) ? req.input.questions : [];
  return qs.length === 1 && qs[0] && typeof qs[0].question === 'string' ? qs[0] : null;
}

/** Rows the TUI adds under every AskUserQuestion; not part of the tool's options. */
const TUI_ASK_ROWS = new Set(['type something.', 'chat about this']);
const drawsAskRows = (prompt) => prompt.options.some((o) => TUI_ASK_ROWS.has(norm(o && o.label)));

/**
 * Is this request EXACTLY the dialog on screen?
 * @param opts.askPending the session's hook says a question is waiting
 */
function matchesPrompt(req, prompt, region, { askPending = false } = {}) {
  if (!req || !prompt || !Array.isArray(prompt.options)) return false;
  if (req.tool === 'AskUserQuestion') {
    // One question only: a tabbed dialog is answered a tab at a time on the
    // pane, and the hook can only answer the whole tool call at once.
    const q = askQuestion(req);
    if (!q) return false;
    if (!norm(q.question) || norm(q.question) !== norm(prompt.question)) return false;
    const want = (Array.isArray(q.options) ? q.options : []).map((o) => norm(o && o.label));
    const got = prompt.options.map((o) => norm(o.label)).filter((l) => !TUI_ASK_ROWS.has(l));
    return want.length > 0 && want.length === got.length && want.every((l, i) => l === got[i]);
  }
  if (req.tool !== 'Bash') return false;
  // ⚠ ONLY A PERMISSION DIALOG ANSWERS A BASH REQUEST. A question's text is the
  // model's own: one that printed `╌╌╌` rows around a command and offered Yes
  // and No read as a Bash dialog showing that command, so a Yes tapped on the
  // QUESTION went to a live, unseen Bash request for it (a background agent's,
  // queued behind the question). While a question is on screen, by its own TUI
  // rows or by the hook's word that one is waiting, nothing here answers Bash.
  if (askPending || drawsAskRows(prompt)) return false;
  const labels = prompt.options.map((o) => o.label);
  if (labels[0] !== 'Yes' || !labels.includes('No')) return false;
  const cmd = req.input && typeof req.input.command === 'string' ? req.input.command : '';
  const block = dialogCommandBlock(region);
  return !!cmd && block != null && joinBlock(block) === joinLines(cmd);
}

/**
 * The ONE request a tap on this dialog answers, or null. Zero matches or more
 * than one: null, and the tap goes to the keys.
 */
function pickRequest(reqs, prompt, region, opts = {}) {
  const hits = reqs.filter((r) => matchesPrompt(r, prompt, region, opts));
  return hits.length === 1 ? hits[0] : null;
}

function exactLabel(q, paneLabel) {
  const opts = Array.isArray(q.options) ? q.options : [];
  const p = String(paneLabel || '').replace(/…$/, '').trim();
  if (!p) return null;
  const exact = opts.find((o) => o && norm(o.label) === norm(p));
  return exact ? exact.label : null;
}

/**
 * The hook decision for a tap, or null when this tap should go to the keys.
 * @param choice {option: n} | {options: [n…]} — the /answer body, validated
 */
function decisionFor(req, prompt, choice) {
  const byNum = (n) => prompt.options.find((o) => o.number === n);
  if (req.tool === 'AskUserQuestion') {
    const q = askQuestion(req);
    if (!q) return null;
    let picked;
    if (Array.isArray(choice.options)) {
      if (!q.multiSelect) return null;
      picked = choice.options.map((n) => exactLabel(q, (byNum(n) || {}).label));
    } else {
      if (q.multiSelect) return null;
      picked = [exactLabel(q, (byNum(choice.option) || {}).label)];
    }
    // "Type something." / "Chat about this" and anything we could not map: keys.
    if (!picked.length || picked.some((l) => !l)) return null;
    return {
      behavior: 'allow',
      updatedInput: { ...req.input, answers: { [q.question]: picked.join(', ') } },
    };
  }
  if (Array.isArray(choice.options)) return null;
  const label = (byNum(choice.option) || {}).label;
  if (label === 'Yes') return { behavior: 'allow' };
  if (label === 'No') {
    return { behavior: 'deny', message: 'The owner declined this from huginn.', interrupt: true };
  }
  // "Yes, and always allow …", "switch to auto mode": these change standing
  // permissions, which the keystroke path does exactly as the TUI defines.
  return null;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Hand a decision to the waiting hook. Resolves true when the hook took it,
 * false when it did not (and the answer was withdrawn — the caller types keys).
 */
async function deliver(stateDir, name, req, decision, { waitMs = 2500, pollMs = 50 } = {}) {
  const dir = permDir(stateDir, name);
  const ans = path.join(dir, `${req.id}.ans`);
  const tmp = `${ans}.tmp`;
  const out = { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision } };
  try {
    fs.writeFileSync(tmp, JSON.stringify(out), { mode: 0o600 });
    fs.renameSync(tmp, ans);
  } catch {
    try { fs.unlinkSync(tmp); } catch { /* never written */ }
    return false;
  }
  const by = Date.now() + waitMs;
  while (Date.now() < by) {
    if (!fs.existsSync(ans)) return true;
    await wait(pollMs);
  }
  try {
    fs.renameSync(ans, path.join(dir, `${req.id}.retracted`));
  } catch (e) {
    return e.code === 'ENOENT';            // claimed in the last instant: delivered
  }
  try { fs.unlinkSync(path.join(dir, `${req.id}.retracted`)); } catch { /* fine */ }
  return false;
}

/** Remove every request file for a name (a name changing hands). */
function clearRequests(stateDir, name) {
  try { fs.rmSync(permDir(stateDir, name), { recursive: true, force: true }); } catch { /* gone */ }
}

module.exports = {
  PERM_DIRNAME, permDir, liveRequests, dialogRegion, dialogCommandBlock, matchesPrompt, pickRequest,
  decisionFor, deliver, clearRequests,
};
