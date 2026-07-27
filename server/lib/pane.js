'use strict';
// Pure pane-text analysis, split out of the daemon so it can be tested with
// `node --test` against real captured panes (server/test/).

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

module.exports = { screenHash, stripAnsi, previewLines, detectPrompt, extractLoginUrl };
