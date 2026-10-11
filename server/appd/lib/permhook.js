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
// So every request is matched against the dialog actually on screen, by content,
// before anything is written: AskUserQuestion by its question text, a tool prompt
// by a fingerprint of its command / path / url found INSIDE the dialog's own
// border (the conversation above can show an earlier run of the same command).
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
const NEEDLE_CHARS = 40;

function permDir(stateDir, name) { return path.join(stateDir, PERM_DIRNAME, name); }

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

const stripAnsi = (s) => String(s || '').replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '');
const norm = (s) => String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
/** No whitespace at all: a pane wraps long commands, a wrap inserts a break. */
const squash = (s) => String(s || '').replace(/\s+/g, '').toLowerCase();

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

/**
 * The dialog's own lines: from its top border (the full-width `───` rule the
 * TUI draws above every selector) down to the bottom of the pane. Without the
 * border, the last 30 lines.
 */
function dialogRegion(lines) {
  const plain = (lines || []).map(stripAnsi);
  let first = -1;
  for (let i = plain.length - 1; i >= 0; i--) {
    if (/^\s*(?:❯\s*)?1\.\s/.test(plain[i])) { first = i; break; }
  }
  if (first < 0) return plain.slice(-30).join('\n');
  for (let i = first - 1; i >= 0 && i >= first - 60; i--) {
    if (/^\s*─{20,}\s*$/.test(plain[i])) return plain.slice(i).join('\n');
  }
  return plain.slice(Math.max(0, first - 30)).join('\n');
}

/** What identifies a tool prompt on screen, or null for a tool we can't fingerprint. */
function toolNeedle(input) {
  const i = input || {};
  let s = null;
  if (typeof i.command === 'string') s = i.command.split('\n')[0];
  else if (typeof i.file_path === 'string') s = path.basename(i.file_path);
  else if (typeof i.notebook_path === 'string') s = path.basename(i.notebook_path);
  else if (typeof i.url === 'string') s = i.url;
  if (!s) return null;
  const n = squash(s).slice(0, NEEDLE_CHARS);
  return n.length >= 4 ? n : null;
}

function askQuestion(req) {
  const qs = req && req.input && Array.isArray(req.input.questions) ? req.input.questions : [];
  return qs.length === 1 && qs[0] && typeof qs[0].question === 'string' ? qs[0] : null;
}

/** Is this request the dialog on screen? */
function matchesPrompt(req, prompt, region) {
  if (!req || !prompt || !Array.isArray(prompt.options)) return false;
  if (req.tool === 'ExitPlanMode') return false;
  if (req.tool === 'AskUserQuestion') {
    // One question only: a tabbed dialog is answered a tab at a time on the
    // pane, and the hook can only answer the whole tool call at once.
    const q = askQuestion(req);
    if (!q) return false;
    const want = norm(q.question);
    const got = norm(prompt.question);
    if (!want || !got) return false;
    return want === got || (got.length >= 12 && want.startsWith(got.replace(/…$/, '')));
  }
  // A tool permission: the plain "Yes … No" selector, and the tool's own
  // fingerprint inside the dialog border.
  const labels = prompt.options.map((o) => o.label);
  if (labels[0] !== 'Yes' || !labels.includes('No')) return false;
  const needle = toolNeedle(req.input);
  return !!needle && squash(region).includes(needle);
}

/**
 * The request a tap on this dialog answers, or null. Ties on content go to the
 * NEWEST: an identical earlier request is far more likely a lingering hook whose
 * prompt was answered at the keyboard than a concurrent twin.
 */
function pickRequest(reqs, prompt, region) {
  const hits = reqs.filter((r) => matchesPrompt(r, prompt, region));
  return hits.length ? hits[hits.length - 1] : null;
}

function exactLabel(q, paneLabel) {
  const opts = Array.isArray(q.options) ? q.options : [];
  const p = String(paneLabel || '').replace(/…$/, '').trim();
  if (!p) return null;
  const exact = opts.find((o) => o && o.label === p);
  if (exact) return exact.label;
  const pre = opts.filter((o) => o && typeof o.label === 'string' && o.label.startsWith(p));
  return pre.length === 1 ? pre[0].label : null;
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
  PERM_DIRNAME, permDir, liveRequests, dialogRegion, toolNeedle, matchesPrompt, pickRequest,
  decisionFor, deliver, clearRequests,
};
