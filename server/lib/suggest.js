'use strict';
// Suggested next messages for the conversation tab.
//
// Nothing on the host carries these — the transcript records what happened and
// the pane shows what is happening, but "what might the owner say next" exists
// nowhere. So the daemon generates them: a one-shot haiku call over a digest of
// the conversation tail, produced only when a turn has just ended and only for
// the session somebody is actually looking at.
//
// The call is deliberately caged: `--setting-sources ""` so the global CLAUDE.md
// never leaks into it (the hermod lesson — a headless claude quietly inherits
// the whole persona), `--tools ""` so it cannot act, `--max-turns 1` so it
// cannot wander, and a scratch cwd so no project instructions load. It writes
// three lines; everything else here is turning a transcript into a small prompt
// and being suspicious of what comes back.

/** More tail than this adds cost without adding signal about "what next". */
const MAX_CONTEXT_CHARS = 2600;

/**
 * The conversation tail as a compact digest: the last exchange, plus whether
 * the assistant left a question hanging — which is the single most useful thing
 * a suggestion can answer.
 */
function suggestionContext(events) {
  const parts = [];
  let lastUser = null;
  let lastAssistant = null;
  let failures = 0;
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.sidechain) continue;
    if (!lastAssistant && e.kind === 'assistant' && e.text) lastAssistant = e.text;
    else if (!lastUser && e.kind === 'user' && e.text) lastUser = e.text;
    if (e.kind === 'tool' && e.ok === false) failures++;
    if (lastUser && lastAssistant) break;
  }
  if (!lastAssistant) return null;               // nothing to react to
  if (lastUser) parts.push(`The owner last said:\n${clip(lastUser, 500)}`);
  parts.push(`The assistant just finished replying:\n${clip(lastAssistant, 1800)}`);
  if (failures > 0) parts.push(`${failures} tool call(s) failed this turn.`);
  return clip(parts.join('\n\n'), MAX_CONTEXT_CHARS);
}

function buildPrompt(context) {
  return [
    'You are helping the OWNER of a coding session pick their next message.',
    '', context, '',
    'Write exactly 3 replies the owner might plausibly send next.',
    'Rules: one per line; no numbering, bullets or quotes; each under 60',
    'characters; concrete to THIS conversation, never generic filler; if the',
    'assistant asked a question or offered options, the first reply answers it.',
  ].join('\n');
}

/**
 * The model's lines, distrusted. Numbering and quoting are stripped even though
 * the prompt forbids them (models decorate anyway), blanks and overlong lines
 * are dropped, and anything past three is ignored.
 */
function parseSuggestions(stdout) {
  const out = [];
  for (const raw of String(stdout || '').split('\n')) {
    let s = raw.trim()
      .replace(/^[-*•·]\s+/, '')
      .replace(/^\d+[.)]\s+/, '')
      .replace(/^["'“‘]|["'”’]$/g, '')
      .trim();
    if (!s || s.length > 100) continue;
    if (/^(here are|suggested|replies|options)\b/i.test(s)) continue;   // preamble
    if (!out.includes(s)) out.push(s);
    if (out.length >= 3) break;
  }
  return out;
}

function clip(s, n) {
  s = String(s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

module.exports = { suggestionContext, buildPrompt, parseSuggestions, MAX_CONTEXT_CHARS };
