'use strict';
// AI polish for the two fields a Round is actually written in: what it should do,
// and how you will know it finished.
//
// A Round is a prompt written ONCE and then run unattended for months, so the
// difference between a good prompt and a vague one is not a nicer session — it is
// months of reports nobody can act on. But the person writing it cannot see the
// frame their words land in: the daemon prepends the goal, appends a report
// contract, supplies the unattended persona as a system prompt, and grants tools
// by the Round's mode. This file teaches a model that frame and asks it to rewrite
// ONE field to fit it.
//
// AI DRAFTS, HUMAN ACCEPTS. Nothing here writes a Round. The answer is handed back
// to the editor as a proposal, and a person presses Use this or Discard — the same
// rule the escalation path follows, for the same reason: a schedule that rewrote
// itself is a schedule nobody read.
//
// The call is caged exactly like lib/suggest's: `--setting-sources ""` so the
// global CLAUDE.md never leaks in (the hermod lesson — a headless claude quietly
// inherits the whole persona), `--tools ""` so it cannot act, `--max-turns 1` so it
// cannot wander, and a scratch cwd so no project instructions load. What it gets is
// the text below and the draft; what it may do is write a reply.

/** The two fields that can be polished. Anything else is a 400 at the route. */
const FIELDS = ['prompt', 'goal'];

/** More draft context than this adds cost without changing the rewrite. */
const MAX_CONTEXT_CHARS = 24_000;

/**
 * What a Round's run actually receives, stated as facts rather than as advice.
 *
 * Kept as one block for both fields because both fields land in the same frame and
 * a model rewriting the goal still needs to know the prompt follows it. These
 * sentences track lib/rounds.js `promptFor` + `reportContract` and the mode
 * toolsets in huginn-appd.js — if either moves, this moves with it, or the polish
 * starts teaching a frame that no longer exists.
 */
const RUNTIME_FRAME = [
  'HOW A ROUND ACTUALLY RUNS — the frame this text has to fit:',
  '- It is ONE unattended `claude -p` turn on a schedule. Nobody is watching it, it',
  '  cannot ask anything, and there is no second message: it ends when it stops.',
  '- The daemon composes the turn\'s input itself, in exactly this order:',
  '      GOAL — this run is done when: <the goal field>',
  '',
  '      <the prompt field>',
  '      <a report contract, appended by the daemon>',
  '- An unattended operator persona is supplied separately, as the system prompt.',
  '- Tools are granted by the Round\'s mode and nothing else is reachable:',
  '      ask  — Skill, mempalace (memory search), WebFetch, WebSearch, and reading files',
  '      act  — all of those plus Bash, Read, Edit, Write, Glob, Grep',
  '- The run answers through a fenced report block the daemon asks for: a status, a',
  '  one-line headline, an honest goalMet, and items that each name a next step. A',
  '  clean run reports an empty list.',
].join('\n');

const PROMPT_RULES = [
  'REWRITE THE "what should it do" FIELD.',
  'Take what is there — usually a sentence or two of basics — and make it an',
  'instruction that holds up unattended.',
  '',
  'It MUST NOT contain any of these. The daemon supplies all three, and a field that',
  'repeats them argues with the real ones:',
  '- report, JSON or output-format instructions of any kind',
  '- a restatement of the goal',
  '- persona or role framing ("you are…", "act as…")',
  '',
  'It SHOULD say, where each applies to this particular work:',
  '- the read-vs-change boundary, matching the mode above: in ask mode, that it',
  '  looks and reports and changes nothing; in act mode, what it may change and what',
  '  it must leave alone',
  '- which tools or skills to lean on, and where to look first',
  '- how to tell a NEW finding from a chronic one that is already known and accepted',
  '- explicitly: if nothing needs anyone, say so and report no items',
  '',
  'Keep the owner\'s own subject, scope and constraints. Improve how the work is',
  'asked for, never what is being asked for, and do not invent hosts, paths, tools or',
  'thresholds that are not already in the draft.',
];

const GOAL_RULES = [
  'REWRITE THE "how will you know it finished" FIELD.',
  'The text is pasted directly after "this run is done when:", so it has to continue',
  'that sentence — a clause or a noun phrase, never an imperative and never a topic.',
  '',
  'It has to be:',
  '- a completion TEST the run can answer yes or no about its own work',
  '- decidable inside a SINGLE run — nothing that needs next week or another person',
  '- honestly failable: "no" must be a possible true answer, or it tests nothing',
  '- concrete and observable, naming what will have been produced or checked',
  '',
  'Not a goal: "review the alerts" (a topic) · "do a good job" (unmeasurable) ·',
  '"the fleet is healthy" (not something this run decides).',
  'A goal: "every alert from the last 7 days has been read, and each one is either',
  'explained here or listed as an item".',
];

const OUTPUT_RULES = [
  'ANSWER WITH THE FIELD TEXT ALONE.',
  'No preamble, no "Here is", no explanation after it, no code fence, no surrounding',
  'quotes, no "Prompt:" or "Goal:" label, no markdown heading. The first character you',
  'write is the first character of the field.',
];

function clip(s, n) {
  s = String(s == null ? '' : s);
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

/**
 * The whole instruction for one polish.
 *
 * @param field  'prompt' | 'goal'
 * @param draft  {title, prompt, goal, mode} as the editor currently holds it
 * @param cap    the field's stored maximum, passed in rather than duplicated here:
 *               huginn-appd.js owns MAX_ROUND_PROMPT / MAX_ROUND_GOAL, and a second
 *               copy of a number that decides whether a saved value is refused is a
 *               drift waiting to happen.
 */
function buildPolishPrompt(field, draft = {}, cap = 20_000) {
  const mode = draft.mode === 'act' ? 'act' : 'ask';
  const title = String(draft.title || '').trim();
  const prompt = clip(String(draft.prompt || '').trim(), MAX_CONTEXT_CHARS);
  const goal = String(draft.goal || '').trim();
  // Said as a sentence rather than as a bare word: "act" alone was read as a label
  // and the rewrite kept describing changes an ask Round can never make.
  const modeLine = mode === 'act'
    ? 'act — it MAY change things on the host (Bash, Edit, Write are granted)'
    : 'ask — it may look and report; it CANNOT change anything';

  return [
    'You are improving one field of a scheduled, unattended job before a person saves it.',
    '',
    RUNTIME_FRAME,
    '',
    'THE JOB AS IT STANDS',
    `Name: ${title || '(not named yet)'}`,
    `Mode: ${modeLine}`,
    `Goal: ${goal || '(empty)'}`,
    'Prompt:',
    prompt || '(empty)',
    '',
    ...(field === 'goal' ? GOAL_RULES : PROMPT_RULES),
    '',
    `Stay under ${cap} characters.${field === 'goal' ? ' One sentence if it can be.' : ''}`,
    '',
    ...OUTPUT_RULES,
  ].join('\n');
}

// A fenced block, wrapping the WHOLE answer: ```optional-word\n…\n```
const WRAPPING_FENCE = /^```[^\n`]*\n([\s\S]*?)\n?```$/;
// A leading label the output rules forbid and models write anyway.
const LEADING_LABEL = /^(?:the\s+)?(?:improved|polished|revised|rewritten)?\s*(?:prompt|goal)\s*(?:field)?\s*:\s*/i;
const WRAPPING_QUOTES = [['"', '"'], ["'", "'"], ['“', '”'], ['‘', '’']];

/**
 * The model's answer, distrusted.
 *
 * Two of these checks are refusals rather than repairs, and deliberately. A polish
 * that came back carrying `huginn-report` or a fenced json block has echoed the
 * daemon's own output contract into the field — the exact failure lib/rounds.js
 * defends the PARSER against, arriving from the other end. Salvaging the prose
 * around it would save a proposal whose defining feature is that it is wrong, so
 * it is refused and the person sees "unavailable" and can press again.
 *
 * @returns {{polished: string, note?: string}|{error: string}}
 */
function parsePolish(field, stdout, cap = 20_000) {
  let s = String(stdout == null ? '' : stdout).trim();
  if (!s) return { error: 'the model said nothing' };

  // Checked on the RAW answer, before any unwrapping: a contract echo inside a
  // fence is still a contract echo, and unwrapping first would hide the fence that
  // proves it.
  if (/huginn-report/i.test(s)) return { error: 'the draft echoed the report contract' };
  if (/```[ \t]*json\b/i.test(s)) return { error: 'the draft echoed the report contract' };

  const fenced = s.match(WRAPPING_FENCE);
  if (fenced) s = fenced[1].trim();
  for (const [open, close] of WRAPPING_QUOTES) {
    if (s.length >= 2 && s.startsWith(open) && s.endsWith(close)) {
      s = s.slice(1, -1).trim();
      break;                                  // one pair, not a stripping loop
    }
  }
  s = s.replace(LEADING_LABEL, '').trim();
  // A goal is pasted mid-sentence after "this run is done when:", so a line break
  // in it splits that sentence in the run's own input. Collapsed here rather than
  // hoped for, the same way lib/rounds oneLine treats a headline.
  if (field === 'goal') s = s.replace(/\s+/g, ' ').trim();

  if (!s) return { error: 'nothing was left after the decoration was stripped' };

  // Clamped AFTER the label and the fence come off, so the cap is spent on the
  // field rather than on "Polished prompt:".
  if (s.length > cap) {
    return { polished: s.slice(0, cap).trim(), note: `Trimmed to ${cap} characters.` };
  }
  return { polished: s };
}

module.exports = { FIELDS, MAX_CONTEXT_CHARS, buildPolishPrompt, parsePolish };
