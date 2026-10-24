'use strict';
// The request registry: what a session is asking right now, as REQUESTS rather
// than as a pane shape.
//
// Claude Code 2.1.306 runs a `PermissionRequest` hook for every tool prompt —
// AskUserQuestion, ExitPlanMode, Bash, Edit, MCP tools, all of them — and a hook
// that prints a decision answers the prompt: `allow` (with `updatedInput`, and
// `updatedPermissions` for "always allow") or `deny` (`message`, `interrupt`).
// hooks/huginn-permission-hook parks every such prompt as a file under
// `.perm/<session>/` and waits; this module turns those files, together with the
// AskUserQuestion / ExitPlanMode sidecars huginn-claude-title writes, into the
// list the apps show and answer:
//
//   Q = { id, kind: "ask"|"permission"|"plan", toolName, agentId?, agentType?,
//         mcpServer?, input, suggestions?, fingerprint, since, bound, fallback }
//
// A request is LIVE while its hook is waiting (the file exists and the pid is
// alive — lib/permhook.liveRequests). On top of that:
//
//   * an AskUserQuestion request is confirmed by the `.ask` sidecar whose input
//     deep-equals its own; the sidecar is cleared the moment the question is
//     answered anywhere (PostToolUse), while the hook lingers until its deadline,
//     so a request without its sidecar is a question already answered;
//   * a plan approval comes from the `.plan` sidecar (kind "plan", the plan text
//     and file path); a live ExitPlanMode request beside it lets feedback ride
//     the hook (`deny` + `message` is honoured there; `allow` is not — measured
//     2.1.306), approval stays on the keys;
//   * a permission request answered at the keyboard is dropped by the title hook
//     on the tool's PostToolUse / PostToolUseFailure. Until that arrives (a long
//     Bash) it still reads as live, which is honest: the hook is. One refinement:
//     a request backed by a pane whose composer is drawn with no dialog on it,
//     and older than a moment, was answered by hand (a keyboard "No" interrupts
//     the turn and fires nothing); it is left out.
//
// `bound` means "answerable from the card through the hook or the keys";
// `fallback: "screen"` is the steer the clients fall back to when it is not.
//
// Pure: the daemon reads the files and the pane and passes them in. Fingerprints
// identify the REQUEST (its kind, tool and exact input), not the pane, so they do
// not move when the terminal is resized; two parallel subagents asking the same
// thing share one, which is why an answer by id is the primary path and the pane
// only breaks ties on the legacy `/answer` route.

const { createHash } = require('crypto');

const STALE_AFTER_MS = 2000;          // a pane-backed request this old with an idle composer was answered by hand
const PLAN_TTL_MS = 24 * 60 * 60 * 1000;
const TEXT_MAX = 4000;                // one answer / message / note
const DEFAULT_DENY = 'The owner declined this from huginn.';
// The ExitPlanMode dialog has two shapes on 2.1.306: in auto mode "Ready to code?"
// over the plan, with "Yes, and use auto mode" / "Yes, manually approve edits" /
// "Tell Claude what to change" (an input row); in the default mode a bare "Exit
// plan mode?" / "Claude wants to exit plan mode" with "Yes, and switch to default
// (ask each time) for this session" / "No" — no plan text and no feedback row, so
// there feedback reaches Claude only through the hook.
const PLAN_DIALOG_RE = /tell claude what to change|keep planning/i;
const PLAN_QUESTION_RE = /exit plan mode|ready to code/i;

const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v);

/** Key-order independent canonical form, so two equal inputs compare and hash equal. */
function canon(v) {
  if (Array.isArray(v)) return v.map(canon);
  if (isObj(v)) {
    const o = {};
    for (const k of Object.keys(v).sort()) o[k] = canon(v[k]);
    return o;
  }
  return v;
}
function deepEqual(a, b) { return JSON.stringify(canon(a)) === JSON.stringify(canon(b)); }

function kindOf(tool) {
  if (tool === 'AskUserQuestion') return 'ask';
  if (tool === 'ExitPlanMode') return 'plan';
  return 'permission';
}

function fingerprintFor(kind, tool, input) {
  return createHash('sha1').update(JSON.stringify([kind, tool, canon(input)])).digest('hex').slice(0, 12);
}

/**
 * The MCP server behind a tool: the hook's `mcp_server` (`{name, source}` since
 * Claude Code 2.1.274, recorded as is), else the name inside `mcp__<server>__<tool>`.
 */
function mcpServerOf(tool, recorded) {
  if (typeof recorded === 'string' && recorded) return recorded;
  if (isObj(recorded) && typeof recorded.name === 'string' && recorded.name) return recorded.name;
  const m = /^mcp__([^_]+(?:_[^_]+)*)__/.exec(String(tool || ''));
  return m ? m[1] : null;
}

/**
 * Build the list.
 * @param requests  live hook requests for this session (permhook.liveRequests, already
 *                  filtered to the session id)
 * @param askInput  the `.ask` sidecar's exact tool_input, or null
 * @param plan      parsePlanSidecar's result ({ts, plan?, planFilePath?}), or null
 * @param paneIdle  true when the pane shows Claude Code's composer and no dialog;
 *                  false when a dialog (or anything else) is up; null when unknown
 */
function buildQuestions({ requests = [], askInput = null, plan = null, now = Date.now(), paneIdle = null } = {}) {
  const out = [];
  let planHook = null;
  for (const r of requests) {
    if (!r || typeof r.tool !== 'string') continue;
    const kind = kindOf(r.tool);
    if (kind === 'plan') { if (!planHook || r.ts < planHook.ts) planHook = r; continue; }
    if (kind === 'ask') {
      if (!askInput || !deepEqual(askInput, r.input)) continue;      // answered already, or not this question
    } else if (paneIdle === true && r.pane !== false && now - r.ts > STALE_AFTER_MS) {
      continue;                                                       // answered by hand; the hook lingers
    }
    out.push(toQuestion(r, kind));
  }
  const planTs = plan && Number(plan.ts) ? Number(plan.ts) * 1000 : 0;
  if (plan && planTs && now - planTs < PLAN_TTL_MS) {
    const input = {};
    if (plan.plan) input.plan = plan.plan;
    if (plan.planFilePath) input.planFilePath = plan.planFilePath;
    const hook = planHook && (!plan.plan || !planHook.input || planHook.input.plan === plan.plan) ? planHook : null;
    out.push({
      id: hook ? hook.id : `plan-${Math.floor(planTs)}`,
      kind: 'plan',
      toolName: 'ExitPlanMode',
      agentId: null, agentType: null, mcpServer: null,
      input,
      suggestions: null,
      fingerprint: fingerprintFor('plan', 'ExitPlanMode', input),
      since: hook ? hook.ts : planTs,
      // Approval is keystrokes and feedback is the hook when it is there, the
      // keys when it is not: either way the card can act, so a fresh plan is
      // always answerable from it.
      bound: true,
      fallback: null,
      hookId: hook ? hook.id : null,
    });
  }
  out.sort((a, b) => a.since - b.since);
  return out;
}

function toQuestion(r, kind) {
  return {
    id: r.id,
    kind,
    toolName: r.tool,
    agentId: typeof r.agentId === 'string' ? r.agentId : null,
    agentType: typeof r.agentType === 'string' ? r.agentType : null,
    mcpServer: kind === 'permission' ? mcpServerOf(r.tool, r.mcpServer) : null,
    input: isObj(r.input) ? r.input : {},
    suggestions: Array.isArray(r.suggestions) && r.suggestions.length ? r.suggestions : null,
    fingerprint: fingerprintFor(kind, r.tool, isObj(r.input) ? r.input : {}),
    since: Number(r.ts) || 0,
    bound: true,
    fallback: null,
    hookId: r.id,
  };
}

/** What the apps are sent: the hook's file id stays server-side. */
function publicQuestion(q) {
  const { hookId, ...pub } = q;   // eslint-disable-line no-unused-vars
  return pub;
}

/** A short identity of the whole list, folded into the screen hash so a long poll wakes when it changes. */
function digest(questions) {
  if (!questions || !questions.length) return '';
  return createHash('sha1')
    .update(questions.map((q) => `${q.id}:${q.bound ? 1 : 0}:${q.fingerprint}`).join('|'))
    .digest('hex').slice(0, 8);
}

// ---- answers ---------------------------------------------------------------

const bad = (error) => ({ ok: false, status: 400, error });

/**
 * The hook decision for an answer body, or a refusal. Pure validation: the body
 * came off the network and goes into a decision that approves a tool call on a
 * root-equivalent host, so nothing passes through unexamined.
 *
 * Ask:        { answers: { "<question text>": string | string[] }, annotations?, response? }
 *             every question answered; an array only for a multiSelect question
 * Permission: { behavior: "allow", updatedPermissions?: [...] }   (each entry one of the
 *             request's own permission_suggestions)
 *             { behavior: "deny", message?: string }               (a message carries the
 *             person's words and lets the turn go on; none interrupts, like the TUI's No)
 * Plan:       { behavior: "allow", mode?: "manual"|"auto" } | { behavior: "deny", message }
 *             -> { plan: {approve} } or { plan: {feedback} }; the daemon drives the keys / the hook
 */
function decisionFor(q, body) {
  if (!q || !isObj(body)) return bad('a JSON object body is required');
  if (q.kind === 'ask') return askDecision(q, body);
  if (q.kind === 'plan') return planAnswer(body);
  return permissionDecision(q, body);
}

function askDecision(q, body) {
  const specs = Array.isArray(q.input && q.input.questions) ? q.input.questions : [];
  if (!specs.length) return bad('this question carries no questions');
  const answers = body.answers;
  if (!isObj(answers)) return bad('answers is required: {"<question text>": string | string[]}');
  const out = {};
  for (const spec of specs) {
    if (!spec || typeof spec.question !== 'string') return bad('malformed question');
    const v = answers[spec.question];
    if (v === undefined) return bad(`no answer for "${spec.question.slice(0, 80)}"`);
    if (Array.isArray(v)) {
      if (!spec.multiSelect) return bad(`"${spec.question.slice(0, 80)}" takes one answer, not a list`);
      const n = Array.isArray(spec.options) ? spec.options.length : 0;
      if (v.length > n + 1 || !v.every((s) => typeof s === 'string' && s.length <= TEXT_MAX)) {
        return bad('a multi-select answer is a list of short strings');
      }
      out[spec.question] = v.slice();
    } else if (typeof v === 'string') {
      if (v.length > TEXT_MAX) return bad('an answer is at most 4000 characters');
      out[spec.question] = v;
    } else {
      return bad(`the answer for "${spec.question.slice(0, 80)}" must be a string or a list`);
    }
  }
  for (const k of Object.keys(answers)) {
    if (!specs.some((s) => s && s.question === k)) return bad(`"${k.slice(0, 80)}" is not one of the questions`);
  }
  const updatedInput = { ...q.input, answers: out };
  if (body.annotations !== undefined) {
    if (!isObj(body.annotations)) return bad('annotations must be an object keyed by question text');
    const ann = {};
    for (const [k, v] of Object.entries(body.annotations)) {
      if (!specs.some((s) => s && s.question === k)) return bad(`annotations: "${k.slice(0, 80)}" is not one of the questions`);
      if (!isObj(v)) return bad('each annotation is an object');
      const a = {};
      for (const [f, val] of Object.entries(v)) {
        if ((f !== 'preview' && f !== 'notes') || typeof val !== 'string' || val.length > TEXT_MAX * 2) {
          return bad('an annotation carries only preview and notes strings');
        }
        a[f] = val;
      }
      ann[k] = a;
    }
    updatedInput.annotations = ann;
  }
  if (body.response !== undefined) {
    if (typeof body.response !== 'string' || body.response.length > TEXT_MAX) return bad('response is a short string');
    if (body.response) updatedInput.response = body.response;
  }
  return { ok: true, decision: { behavior: 'allow', updatedInput } };
}

function permissionDecision(q, body) {
  if (body.behavior === 'allow') {
    const decision = { behavior: 'allow' };
    if (body.updatedPermissions !== undefined) {
      if (!Array.isArray(body.updatedPermissions) || !body.updatedPermissions.length) {
        return bad('updatedPermissions is a non-empty list');
      }
      const offered = Array.isArray(q.suggestions) ? q.suggestions : [];
      for (const p of body.updatedPermissions) {
        if (!offered.some((s) => deepEqual(s, p))) return bad('updatedPermissions must be among the suggestions the prompt offered');
      }
      decision.updatedPermissions = body.updatedPermissions.map(canon);
    }
    return { ok: true, decision };
  }
  if (body.behavior === 'deny') {
    const msg = typeof body.message === 'string' ? body.message.trim().slice(0, TEXT_MAX) : '';
    // ⚠ A bare No interrupts, as the TUI's does ("Interrupted · What should
    // Claude do instead?"); a No WITH words hands them to Claude and the turn
    // goes on, which is what "tell Claude what to change" means.
    return { ok: true, decision: { behavior: 'deny', message: msg || DEFAULT_DENY, interrupt: !msg } };
  }
  return bad('behavior must be "allow" or "deny"');
}

function planAnswer(body) {
  if (body.behavior === 'allow') {
    return { ok: true, plan: { approve: body.mode === 'auto' ? 'auto' : 'manual' } };
  }
  if (body.behavior === 'deny') {
    const msg = typeof body.message === 'string' ? body.message.trim().slice(0, TEXT_MAX) : '';
    if (!msg) return bad('a plan is sent back with a message: what should change');
    return { ok: true, plan: { feedback: msg } };
  }
  return bad('behavior must be "allow" or "deny"');
}

// ---- the plan dialog on the pane --------------------------------------------

/** Is this pane prompt the ExitPlanMode approval dialog, in either of its shapes? */
function isPlanPrompt(prompt) {
  if (!prompt || !Array.isArray(prompt.options)) return false;
  const yes = prompt.options.some((o) => /^yes\b/i.test(String(o.label || '')));
  if (!yes) return false;
  if (prompt.options.some((o) => PLAN_DIALOG_RE.test(String(o.label || '')))) return true;
  return PLAN_QUESTION_RE.test(String(prompt.question || ''))
    && prompt.options.some((o) => /^no\b/i.test(String(o.label || '')));
}

/** The row to press for an approval (`manual` prefers the row that keeps the mode), or null. */
function planApproveRow(prompt, mode) {
  if (!isPlanPrompt(prompt)) return null;
  const yes = prompt.options.filter((o) => /^yes\b/i.test(String(o.label || '')));
  if (!yes.length) return null;
  const pick = mode === 'auto'
    ? (yes.find((o) => /auto/i.test(o.label)) || yes[0])
    : (yes.find((o) => /manual/i.test(o.label)) || yes.find((o) => !/auto/i.test(o.label)) || yes[0]);
  return pick;
}

/** The feedback row ("Tell Claude what to change" / "No, keep planning"), or null. */
function planFeedbackRow(prompt) {
  if (!isPlanPrompt(prompt)) return null;
  return prompt.options.find((o) => PLAN_DIALOG_RE.test(String(o.label || ''))) || null;
}

// ---- notifications and the legacy /answer route ------------------------------

/**
 * One-tap buttons for a notification: the exact option labels of a single
 * single-select question (never the TUI's own "Type something." / "Chat about
 * this" rows, which are not in the tool input), Yes / No for a permission. A
 * multi-select or multi-question ask gets none: one tap cannot answer it.
 */
function notificationButtons(q) {
  if (!q) return [];
  if (q.kind === 'ask') {
    const specs = Array.isArray(q.input.questions) ? q.input.questions : [];
    if (specs.length !== 1) return [];
    const s = specs[0];
    if (!s || s.multiSelect || (s.kind && s.kind !== 'choice') || !Array.isArray(s.options) || !s.options.length) return [];
    return s.options.map((o, i) => ({ number: i + 1, label: String(o && o.label || '') }));
  }
  if (q.kind === 'permission') return [{ number: 1, label: 'Yes' }, { number: 2, label: 'No' }];
  return [];
}

/** The answer body a numbered notification button stands for, or null. */
function buttonAnswer(q, number) {
  const b = notificationButtons(q).find((x) => x.number === number);
  if (!b) return null;
  if (q.kind === 'ask') return { answers: { [q.input.questions[0].question]: b.label } };
  return { behavior: number === 1 ? 'allow' : 'deny' };
}

/** One line saying what is asked, for an alert or a card title. */
function describe(q) {
  if (!q) return '';
  if (q.kind === 'ask') {
    const specs = Array.isArray(q.input.questions) ? q.input.questions : [];
    return specs[0] && typeof specs[0].question === 'string' ? specs[0].question : 'Claude is asking a question';
  }
  if (q.kind === 'plan') return 'Plan ready — approve to continue';
  const i = q.input || {};
  const detail = typeof i.command === 'string' ? i.command
    : typeof i.file_path === 'string' ? i.file_path
      : typeof i.url === 'string' ? i.url
        : typeof i.pattern === 'string' ? i.pattern
          : Object.values(i).find((v) => typeof v === 'string') || '';
  const head = q.mcpServer ? `${q.mcpServer} · ${q.toolName.replace(/^mcp__[^_]+(?:_[^_]+)*__/, '')}` : q.toolName;
  return detail ? `${head}: ${String(detail).replace(/\s+/g, ' ').slice(0, 200)}` : head;
}

module.exports = {
  buildQuestions, publicQuestion, digest, decisionFor, fingerprintFor, canon, deepEqual, kindOf,
  isPlanPrompt, planApproveRow, planFeedbackRow, notificationButtons, buttonAnswer, describe,
  STALE_AFTER_MS, DEFAULT_DENY,
};
