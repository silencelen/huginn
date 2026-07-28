'use strict';
// Pure pane-text analysis, split out of the daemon so it can be tested with
// `node --test` against real captured panes (server/test/).

const { createHash } = require('node:crypto');

/**
 * Cheap stable hash of the screen text. Used to answer "did anything change?"
 * for long-polling, so an idle session costs one held request instead of a
 * capture every second. FNV-1a: not cryptographic, just needs to be fast and
 * to differ when a single cell differs.
 */
function screenHash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = (h + (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24)) >>> 0;
  }
  return h.toString(16);
}

const ESC = '\u001B';

/** Strips CSI/OSC escapes. Mirrors the client's Ansi.strip. */
function stripAnsi(line) {
  let out = '';
  let i = 0;
  while (i < line.length) {
    if (line[i] === ESC) {
      if (line[i + 1] === '[') {
        let j = i + 2;
        while (j < line.length && !(line[j] >= '@' && line[j] <= '~')) j++;
        i = j < line.length ? j + 1 : line.length;
      } else if (line[i + 1] === ']') {
        let j = i + 2;
        while (j < line.length && line[j] !== '\u0007' && line[j] !== ESC) j++;
        i = j < line.length && line[j] === '\u0007' ? j + 1 : j;
      } else i += 2;
    } else { out += line[i]; i++; }
  }
  return out;
}

// Claude Code's TUI furniture, so a preview can skip it and show real content.
// Verified against live panes on 2026-07-27: '─' rules frame the composer, '❯'
// is the input prompt, '⏵⏵' is the permission-mode hint, and the status line
// carries the model and branch.
const RULE_RE = /^[─-╿\s]+$/;                 // box-drawing only
const PROMPT_MARK_RE = /^\s*[❯>]\s*/;              // '❯ ' input line
const MODE_HINT_RE = /^\s*[⏵⏴]{1,2}\s/;       // '⏵⏵ auto mode on…'
const STATUS_RE = /^\s*\[[^\]]+\]\s+\S/;                // '[andrev] Opus 5 · main'
const SPINNER_RE = /^\s*[◀-◿✹✳✴✻-✽✶]\s/; // '◷ …', '✻ …'

/**
 * The most recent lines that say something about what the session is doing,
 * newest last. Skips the composer furniture and blank filler; keeps assistant
 * text, tool lines and the spinner status (which is often the only live signal).
 */
function previewLines(lines, max = 3) {
  const keep = [];
  for (let i = lines.length - 1; i >= 0 && keep.length < max; i--) {
    const raw = stripAnsi(lines[i]);
    const t = raw.replace(/\s+$/, '');
    if (!t.trim()) continue;
    if (RULE_RE.test(t)) continue;
    if (PROMPT_MARK_RE.test(t) && t.replace(PROMPT_MARK_RE, '').trim() === '') continue;
    if (MODE_HINT_RE.test(t)) continue;
    if (STATUS_RE.test(t)) continue;
    keep.push(t.trim().slice(0, 220));
  }
  return keep.reverse();
}

/**
 * Finds a Claude Code choice prompt in the pane tail and returns it structured,
 * so the phone can offer real buttons instead of asking the user to hit a digit
 * on a screen with no keyboard. Shape being matched (verified against live
 * panes):
 *
 *     Do you want to proceed?
 *     ❯ 1. Yes
 *       2. Yes, and don't ask again
 *       3. No, and tell Claude what to do differently (esc)
 *
 * Only the LAST contiguous run of numbered options is considered, and only when
 * it sits near the bottom of the pane, because older prompts scrolled up in the
 * history are already answered. Returns null when there is nothing to answer —
 * a false positive here would put fake buttons in front of the user, so the
 * matching is deliberately strict: options must be numbered from 1, contiguous,
 * and at a consistent indent.
 */
function detectPrompt(lines) {
  const plain = lines.map((l) => stripAnsi(l).replace(/\s+$/, ''));
  // Ignore anything more than 14 rows above the last non-empty row.
  let lastContent = -1;
  for (let i = plain.length - 1; i >= 0; i--) {
    if (plain[i].trim()) { lastContent = i; break; }
  }
  if (lastContent < 0) return null;
  const floor = Math.max(0, lastContent - 14);

  const OPTION_RE = /^(\s*)(?:[❯>]\s*)?(\d{1,2})[.)]\s+(\S.*)$/;
  const opts = [];
  let firstIdx = -1;
  let lastIdx = -1;
  for (let i = lastContent; i >= floor; i--) {
    const m = OPTION_RE.exec(plain[i]);
    if (m) {
      opts.unshift({ number: Number(m[2]), label: m[3].trim().slice(0, 120), selected: /[❯>]/.test(plain[i]) });
      firstIdx = i;
      if (lastIdx < 0) lastIdx = i;
    } else if (opts.length) {
      // Run ended; the line above it is the question.
      break;
    }
  }
  if (opts.length < 2) return null;
  // Must be 1..n contiguous, else this is prose that happens to have numbers.
  for (let k = 0; k < opts.length; k++) {
    if (opts[k].number !== k + 1) return null;
  }
  // A LIVE prompt always has exactly one option marked with the selection
  // caret. Without this, an assistant answer ending in a markdown numbered list
  // matched every other rule — and a false positive is not benign here, because
  // tapping the resulting button types a digit into Claude's composer.
  if (opts.filter((o) => o.selected).length !== 1) return null;
  // A composer below the run means the options are message content that has
  // already scrolled behind the input box, not a question being asked now.
  for (let i = lastIdx + 1; i <= lastContent; i++) {
    const t = plain[i];
    if (!t.trim()) continue;
    if (RULE_RE.test(t) || MODE_HINT_RE.test(t) || STATUS_RE.test(t)) return null;
    if (PROMPT_MARK_RE.test(t)) return null;
  }
  // Question: nearest non-empty line above the run that is not furniture.
  let question = '';
  for (let i = firstIdx - 1; i >= floor; i--) {
    const t = plain[i].trim();
    if (!t || RULE_RE.test(t)) continue;
    question = t.slice(0, 240);
    break;
  }
  return { question, options: opts };
}

/**
 * The live "what is Claude doing right now" line, read off the pane.
 *
 * While a turn runs, Claude Code renders a spinner line like
 *
 *     ✽ Gallivanting… (3m 15s · ↓ 10.0k tokens)
 *
 * (captured verbatim from a live pane) and that line is the ONLY place the
 * moment-to-moment status exists: the transcript stays silent until whole blocks
 * complete, which is exactly why the conversation view felt dead right after
 * sending a message. The glyph cycles and the verb is whimsical-random, so the
 * match keys on the shape — glyph, a word ending in an ellipsis, an optional
 * parenthetical — rather than any word list.
 *
 * Keyboard hints inside the parenthetical ("esc to interrupt", "ctrl+t …") are
 * dropped: they are instructions for the terminal the reader is not at.
 */
const LIVE_STATUS_RE = /^\s*[\u2722\u2733\u273D\u273B\u2736\u2738\u2739\u273A\u00B7\u2219\u2217*+]\s+(\S[^(]*?\u2026)\s*(?:\((.*)\))?\s*$/;

function parseSpinner(lines) {
  for (let i = lines.length - 1; i >= 0; i--) {
    const m = LIVE_STATUS_RE.exec(stripAnsi(lines[i]).trimEnd());
    if (!m) continue;
    const verb = m[1].trim();
    const extra = (m[2] || '')
      .split('\u00B7')
      .map((part) => part.trim())
      .filter((part) => part && !/esc to interrupt|ctrl\+|shift\+|tab to/i.test(part));
    return [verb, ...extra].join(' \u00B7 ').slice(0, 140);
  }
  // No ellipsis form on screen. The TUI also uses the spinner glyph for waiting
  // states with no ellipsis at all — captured live: "\u2727 Waiting for 1 dynamic
  // workflow to finish" — which is exactly the workflow case, so missing it made
  // workflows invisible in the status strip.
  for (let i = lines.length - 1; i >= 0; i--) {
    const m = GLYPH_STATUS_RE.exec(stripAnsi(lines[i]).trimEnd());
    if (m && /waiting|running|finishing|spawning|workflow|agent|task/i.test(m[1])) {
      return m[1].trim().slice(0, 140);
    }
  }
  return null;
}

const GLYPH_STATUS_RE = /^\s*[\u2722\u2733\u273D\u273B\u2736\u2738\u2739\u273A\u00B7\u2219\u2217*+]\s+(\S.*)$/;

// The TUI's own progress furniture, captured live 2026-07-27:
//   \u25EF andvari-polish-wave3  Wave 3   0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens
//   \u29C9  audit-board
//   Running 1 shell command \u00B7 2s\u2026
const PROGRESS_ROW_RE = /^\s*[\u25EF\u25CB\u25CF\u25D0\u25D1\u25D2\u25D3\u29C9\u25C9\u25CE]\s+(\S.*)$/;
const RUNNING_ROW_RE = /^\s*(Running \d+ (?:agents?|shells?|shell commands?|tasks?|commands?)\b.*)$/i;

/**
 * The progress rows the TUI draws besides the spinner: workflow phase lines,
 * "Running N agents/shells", board markers. These are how Claude Code itself
 * shows fan-out work, so the phone's status strip shows the same rows rather
 * than inventing its own vocabulary.
 */
function parseStatusExtras(lines, max = 3) {
  const out = [];
  for (const raw of lines) {
    const t = stripAnsi(raw).trimEnd();
    if (!t.trim() || /Tip:/.test(t)) continue;
    let m = PROGRESS_ROW_RE.exec(t) || RUNNING_ROW_RE.exec(t);
    if (!m) continue;
    const text = m[1].replace(/\s{2,}/g, ' \u00B7 ').trim().slice(0, 120);
    if (!out.includes(text)) out.push(text);
    if (out.length >= max) break;
  }
  return out;
}

/**
 * The identity of a question, so an answer can be matched to what it answers.
 *
 * This exists to close a race with teeth. An alert can offer "1) Yes  2) No" on a
 * lock screen, and by the time it is tapped the session may have been answered in
 * tmux, moved to an entirely different question, or gone back to an idle composer.
 * Sending the digit regardless would type it into whatever is there now — which in a
 * Claude Code pane is not a harmless no-op; it could accept a *different* prompt.
 *
 * So an answer carries the fingerprint of the question it was offered for, and the
 * host refuses to deliver it if the pane no longer shows that same question. The
 * selection caret is deliberately EXCLUDED: moving the highlight up and down does not
 * change which question is being asked, and including it would reject a valid answer
 * simply because the cursor had moved.
 */
function promptFingerprint(prompt) {
  if (!prompt || !Array.isArray(prompt.options) || !prompt.options.length) return null;
  const stable = JSON.stringify([
    prompt.question || '',
    prompt.options.map((o) => [o.number, o.label]),
  ]);
  return createHash('sha1').update(stable).digest('hex').slice(0, 12);
}

/**
 * Reads the live model, branch and permission mode off Claude Code's status
 * line, e.g.
 *
 *     [huginnapp] Fable 5 · main · ⚠ 3 sessions in this tree
 *     ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
 *
 * The pane is the only source that is current: the transcript reports the model
 * of the LAST assistant turn, so after a `/model` change it keeps naming the old
 * one until the next turn happens — which made the app's model control look like
 * it had done nothing.
 */
function parseStatusLine(lines) {
  const out = { model: null, branch: null, mode: null };
  const plain = lines.map((l) => stripAnsi(l).replace(/\s+$/, ''));
  for (let i = plain.length - 1; i >= 0 && i >= plain.length - 8; i--) {
    const t = plain[i].trim();
    if (!t) continue;
    if (!out.mode) {
      const m = /^[⏵⏴⏸⏹▶]{1,2}\s*(\w+)\s+mode\s+on/.exec(t);
      if (m) { out.mode = m[1].toLowerCase(); continue; }
    }
    if (!out.model) {
      // "[name] Model Name · branch · ..." — the model is the first field after
      // the bracketed session name.
      const m = /^\[[^\]]+\]\s+([^·]+?)(?:\s+·\s+([^·]+))?(?:\s+·|$)/.exec(t);
      if (m) {
        out.model = m[1].trim() || null;
        out.branch = (m[2] || '').trim() || null;
      }
    }
  }
  return out;
}

/**
 * Pulls the sign-in URL out of a `claude auth login` pane.
 *
 * Claude Code wraps the URL in an OSC 8 hyperlink, whose target carries the
 * whole 450-character URL on one line — the visible label is hard-wrapped at the
 * pane width and is useless to copy on a phone. Prefer the OSC target; fall back
 * to rejoining the wrapped label for a terminal that dropped the hyperlink.
 */
function extractLoginUrl(lines) {
  for (const line of lines) {
    const m = /\u001B\]8;;(https?:\/\/[^\u0007\u001B]+)/.exec(line);
    if (m && m[1].length > 20) return m[1];
  }
  // Fallback: the label, rejoined. Continuation lines of a wrapped URL contain
  // no spaces, so the first line with a space (or a blank) ends it.
  const plain = lines.map(stripAnsi);
  for (let i = 0; i < plain.length; i++) {
    const at = plain[i].indexOf('https://');
    if (at < 0) continue;
    let url = plain[i].slice(at).trim();
    for (let j = i + 1; j < plain.length; j++) {
      const next = plain[j].replace(/\s+$/, '');
      if (!next.trim() || /\s/.test(next.trim())) break;
      url += next.trim();
    }
    if (url.length > 20) return url;
  }
  return null;
}

/**
 * Where a `claude auth login` pane has got to, so the app can run the whole
 * sign-in itself instead of sending the user into a terminal to type.
 *
 * Read from the pane because the flow is a TUI: there is no status file or exit
 * code to consult while it waits.
 */
function loginPaneState(lines) {
  const plain = lines.map((l) => stripAnsi(l).replace(/\s+$/, ''));
  const tail = plain.slice(-25).join('\n');
  const awaitingCode = /Paste code here/i.test(tail);
  const done = /(Login successful|Logged in|You are logged in|Signed in as)/i.test(tail);

  // Quote the line that SAYS something went wrong, not simply the last line: a
  // pane wraps, so the last line is often a fragment like "opied." and showing
  // that to somebody wondering why sign-in failed is worse than saying nothing.
  const ERROR_RE = /(error|failed|invalid|expired|denied|not authorized)/i;
  let failedLine = null;
  for (let i = plain.length - 1; i >= 0 && i >= plain.length - 25; i--) {
    const t = plain[i].trim();
    if (t.length >= 12 && ERROR_RE.test(t)) { failedLine = t.slice(0, 200); break; }
  }

  let message = failedLine;
  if (!message) {
    for (let i = plain.length - 1; i >= 0; i--) {
      const t = plain[i].trim();
      // Skip blanks, our own shell epilogue, and wrap fragments.
      if (t.length < 12 || /^\[done\]/.test(t)) continue;
      message = t.slice(0, 200);
      break;
    }
  }
  return { awaitingCode, done, failed: !!failedLine, message };
}

module.exports = {
  screenHash, stripAnsi, previewLines, detectPrompt, promptFingerprint, parseSpinner, parseStatusExtras,
  extractLoginUrl, parseStatusLine, loginPaneState,
};
