'use strict';
// Fuse the exact AskUserQuestion tool_input (written to a sidecar by
// huginn-claude-title) with the pane run that lib/pane.js detects. The pane is
// the only source of LIVENESS and the selection caret, but its option labels are
// truncated to the window width — a wrapped option loses its tail, a previewed
// one gains its neighbour's column — which both mislabels the buttons and, since
// the label feeds the answer fingerprint, makes the same question fingerprint
// differently at different widths. The hook has the real labels; this marries the
// two so the buttons are correct and width-stable while the caret still comes
// from the screen.
//
// Pure: the daemon reads the sidecar file and passes the parsed object in.

/** Clip a string, tolerating non-strings. */
function clip(s, n) { return String(s == null ? '' : s).slice(0, n); }
/** Collapse internal whitespace so a wrapped label compares to an unwrapped one. */
function collapse(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }

/**
 * Validate + clip a parsed ask sidecar ({v,tool,sessionId,ts,input}). Returns
 * { tool, sessionId, ts, questions:[{question, header?, multiSelect, options:[{label, description?}]}] }
 * or null. Mirrors parseAsk's defensiveness in transcript.js: anything malformed
 * degrades to the pane-only path rather than throwing.
 */
function parseAskSidecar(obj) {
  if (!obj || typeof obj !== 'object' || obj.v !== 1) return null;
  const input = obj.input;
  if (!input || !Array.isArray(input.questions) || !input.questions.length) return null;
  const questions = [];
  for (const q of input.questions.slice(0, 4)) {
    if (!q || typeof q.question !== 'string' || !q.question.trim()) return null;
    if (!Array.isArray(q.options) || q.options.length < 2) return null;
    const options = [];
    for (const o of q.options.slice(0, 8)) {
      if (!o || typeof o.label !== 'string' || !o.label.trim()) return null;
      const opt = { label: clip(o.label, 200) };
      if (typeof o.description === 'string' && o.description.trim()) opt.description = clip(o.description, 500);
      options.push(opt);
    }
    const qq = { question: clip(q.question, 1000), multiSelect: !!q.multiSelect, options };
    if (typeof q.header === 'string' && q.header.trim()) qq.header = clip(q.header, 24);
    questions.push(qq);
  }
  if (!questions.length) return null;
  return {
    tool: obj.tool || 'AskUserQuestion',
    sessionId: obj.sessionId || null,
    ts: Number(obj.ts) || null,
    questions,
  };
}

/**
 * Does a pane-scraped label correspond to a hook label? A truncation in EITHER
 * direction makes one a prefix of the other (pane truncated by width; hook
 * truncated by a preview column landing on the same physical line), so accept a
 * symmetric prefix — with full equality demanded when the shorter side is tiny,
 * so "Red" does not spuriously match "Recreate".
 */
function labelsMatch(paneLabel, hookLabel) {
  const a = collapse(paneLabel), b = collapse(hookLabel);
  if (!a || !b) return false;
  const [shorter, longer] = a.length <= b.length ? [a, b] : [b, a];
  if (shorter.length < 4) return a === b;
  return longer.startsWith(shorter);
}

/** Match one sidecar question against the pane run. Returns {k, m} or null. */
function matchQuestion(paneOpts, q) {
  const k = q.options.length;
  const m = paneOpts.length;
  // The TUI appends at most "Type something" + "Chat about this" (and, on a
  // multiSelect, a "Submit" row) — allow up to 3 extras beyond the hook options.
  if (m < k || m > k + 3) return null;
  for (let i = 0; i < k; i++) {
    if (!labelsMatch(paneOpts[i].label, q.options[i].label)) return null;
  }
  // multiSelect must agree between hook and pane, or the answer choreography
  // (digit vs toggle-review-submit) would be wrong. Disagreement -> pane-only.
  const paneMulti = paneOpts.slice(0, k).some((o) => typeof o.checked === 'boolean');
  if (!!q.multiSelect !== paneMulti) return null;
  return { k, m };
}

/**
 * Fuse. Returns { prompt, questionIndex, questionCount } or null (fall back to
 * the pane-only prompt). The returned prompt carries the hook's exact question
 * and labels + descriptions for the real options, the pane's caret/checkbox
 * state, and the TUI-added rows flagged `extra`. promptFingerprint() over it is
 * width-stable because the hook labels do not depend on the window.
 */
function fuseAskPrompt(panePrompt, sidecar) {
  if (!panePrompt || !Array.isArray(panePrompt.options) || !panePrompt.options.length) return null;
  if (!sidecar || !Array.isArray(sidecar.questions) || !sidecar.questions.length) return null;
  const paneOpts = panePrompt.options;

  const matches = [];
  for (let qi = 0; qi < sidecar.questions.length; qi++) {
    const r = matchQuestion(paneOpts, sidecar.questions[qi]);
    if (r) matches.push({ qi, ...r });
  }
  if (!matches.length) return null;

  // Usually one question's options are on screen. If several sibling questions
  // share an identical option set, the pane cannot say which is showing; prefer
  // the header hint (the next un-checked tab), else the first match. Worst case
  // is the right options under a sibling's question text — accepted, documented.
  let pick = matches[0];
  if (matches.length > 1 && Array.isArray(panePrompt.headers) && panePrompt.headers.length) {
    const answered = panePrompt.headers.filter((h) => h.checked).length;
    const byHeader = matches.find((mm) => mm.qi === answered);
    if (byHeader) pick = byHeader;
  }

  const q = sidecar.questions[pick.qi];
  const { k, m } = pick;
  const options = [];
  for (let i = 0; i < k; i++) {
    const po = paneOpts[i];
    const opt = { number: po.number, label: q.options[i].label, selected: !!po.selected };
    if (q.options[i].description) opt.description = q.options[i].description;
    if (typeof po.checked === 'boolean') opt.checked = po.checked;
    options.push(opt);
  }
  for (let i = k; i < m; i++) {
    const po = paneOpts[i];
    const opt = { number: po.number, label: po.label, selected: !!po.selected, extra: true };
    if (typeof po.checked === 'boolean') opt.checked = po.checked;
    options.push(opt);
  }

  return {
    prompt: {
      question: q.question,
      options,
      multiSelect: !!q.multiSelect,
      questionIndex: pick.qi,
      questionCount: sidecar.questions.length,
      source: 'hook',
    },
    questionIndex: pick.qi,
    questionCount: sidecar.questions.length,
  };
}

/**
 * The degraded card: the hook says a question is waiting but the pane scrape did
 * not find a run (a wrap/preview/tab shape lib/pane.js still cannot read). Show
 * the FIRST question's buttons so the client renders something instead of
 * nothing; answerable is false until a live pane run confirms it at answer time.
 */
function degradedAskCard(sidecar, fingerprint) {
  if (!sidecar || !Array.isArray(sidecar.questions) || !sidecar.questions.length) return null;
  const q = sidecar.questions[0];
  return {
    question: q.question,
    ...(q.header ? { header: q.header } : {}),
    options: q.options.map((o, i) => ({
      number: i + 1,
      label: o.label,
      ...(o.description ? { description: o.description } : {}),
    })),
    multiSelect: !!q.multiSelect,
    questionIndex: 0,
    questionCount: sidecar.questions.length,
    answerable: false,
    ...(fingerprint ? { fingerprint } : {}),
  };
}

/** The plan sidecar (ExitPlanMode): presence + the plan text if the runtime shipped it. */
function parsePlanSidecar(obj) {
  if (!obj || typeof obj !== 'object' || obj.v !== 1) return null;
  const out = { ts: Number(obj.ts) || null };
  const input = obj.input;
  if (input && typeof input.plan === 'string' && input.plan.trim()) out.plan = clip(input.plan, 8000);
  if (input && typeof input.planFilePath === 'string') out.planFilePath = clip(input.planFilePath, 500);
  return out;
}

module.exports = {
  parseAskSidecar, fuseAskPrompt, degradedAskCard, parsePlanSidecar,
  labelsMatch, collapse,
};
