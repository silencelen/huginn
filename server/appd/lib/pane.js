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
// '⏵⏵ auto mode on…', '⏸ manual mode on…', '⏸ plan mode on…'. The paused/plan
// glyph ⏸ (U+23F8) and stop ⏹ (U+23F9) were missing, so a manual- or plan-mode
// hint line was NOT recognised as chrome: it leaked into previews and spent one
// of detectPrompt's four footer-line budget units (captured live 2026-08-10,
// fixtures statusline-{manual,plan}).
const MODE_HINT_RE = /^\s*[⏵⏴⏸⏹▶]{1,2}\s/;
const STATUS_RE = /^\s*\[[^\]]+\]\s+\S/;                // '[andrev] Opus 5 · main'
// The RUNNING-turn spinner, e.g. '✽ Gallivanting…', '◷ …'. ENUMERATED, not a
// range: the old class was [◀-◿…] = U+25C0–U+25FF, which swept in the TUI's
// progress-row glyphs ◯ (workflow) and ◐◑◒◓ (phase) as well as the spinner.
// isChrome() uses this to decide a numbered run above it is a finished turn's
// history — so a workflow progress row drawn under a live plan-approval dialog
// was read as chrome and the whole prompt was discarded (no buttons on either
// client). Now only the true spinner glyphs: the sparkle/asterisk class shared
// with LIVE_STATUS_RE plus the four clock glyphs ◴◵◶◷; the progress/board/effort
// glyphs ◯◐◑◒◓◉⧉ are deliberately excluded (captured live 2026-08-10, fixture
// plan-approval-with-task).
const SPINNER_RE = /^\s*[✢✦✧✳✴✶✷✸✹✺✻✼✽◴◵◶◷·∙∗*+]\s/;

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
    // Footer furniture: workflow/board rows persist there long after the run
    // ends, so a dead workflow was headlining the session's preview forever;
    // selector help lines are instructions, not content.
    if (/^\s*[\u25EF\u25D0\u25D1\u25D2\u25D3\u29C9]\s/.test(t)) continue;
    if (/enter to select|to navigate|esc to cancel/i.test(t)) continue;
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
  // Ignore anything well above the last non-empty row; the AskUserQuestion
  // dialog is tall (per-option descriptions), so the window is generous.
  let lastContent = -1;
  for (let i = plain.length - 1; i >= 0; i--) {
    if (plain[i].trim()) { lastContent = i; break; }
  }
  if (lastContent < 0) return null;
  const floor = Math.max(0, lastContent - 24);

  const OPTION_RE = /^(\s*)(?:[\u276F>]\s*)?(\d{1,2})[.)]\s+(\S.*)$/;
  // The selector's own help line, drawn under the options.
  const HINT_RE = /enter to select|to navigate|esc to cancel|tab\/arrows/i;
  // Multi-question tab headers above the question text.
  const HEADER_RE = /^\s*([\u2610\u2611\u2612]\s|\u2190\s)/;

  // Lines that mean the ORDINARY chrome is drawn below this run: the composer,
  // the status line, the mode hint, a spinner. A live selector replaces all of
  // that while it is up, so finding one means the numbered run above it is
  // history rather than a question being asked now. This is the real test, and
  // it is why the footer walk below can afford to be forgiving.
  const isChrome = (line) =>
    PROMPT_MARK_RE.test(line) || STATUS_RE.test(line) ||
    MODE_HINT_RE.test(line) || SPINNER_RE.test(line);

  // How many unrecognised lines may sit between the last option and the bottom.
  // A selector footer is a couple of short help lines; prose under a stale list
  // runs longer, and the chrome test above catches the common case outright.
  const MAX_FOOTER_LINES = 4;

  // Step 1: from the bottom, walk up past the dialog's own furniture to the
  // last option line. Ordinary chrome down there means the options are history.
  //
  // This used to reject ANY line it did not recognise as a hint or a rule, with
  // HINT_RE listing the exact phrases Claude Code drew under a selector. Claude
  // Code then drew different ones — "shift+tab to approve with this feedback",
  // "ctrl+g to edit in Vim · ~/.claude/plans/….md" — and every plan approval and
  // every edit permission stopped being detected at all. The owner saw questions
  // vanish from both clients' conversation views and had to answer them through
  // the raw tmux screen. Matching a list of sentences was the mistake; a footer
  // is now allowed to say anything, in bounded quantity, as long as the chrome
  // that marks a finished turn is absent.
  let i = lastContent;
  let footerLines = 0;
  while (i >= floor && !OPTION_RE.test(plain[i])) {
    const t = plain[i].trim();
    if (t) {
      if (isChrome(plain[i])) return null;
      if (!HINT_RE.test(t) && !RULE_RE.test(plain[i]) && ++footerLines > MAX_FOOTER_LINES) return null;
    }
    i--;
  }
  if (i < floor) return null;

  // Step 2: collect the run, allowing what the AskUserQuestion dialog actually
  // draws between numbered lines (verified against a live capture): indented
  // description lines under each option, and a rule separating the built-in
  // trailing options. Everything else ends the run.
  const opts = [];
  let firstIdx = i;
  // Multi-select options carry a checkbox after the number: "2. [\u2714] Mushrooms".
  // The checkbox is STATE, not label — it is stripped so the label (and with it
  // the fingerprint) stays stable while the user toggles.
  const CHECK_RE = /^\[( |x|X|\u2714|\u2713)\]\s+/;
  for (let j = i; j >= floor; j--) {
    const m = OPTION_RE.exec(plain[j]);
    if (m) {
      let label = m[3].trim();
      let checked = null;
      const cb = CHECK_RE.exec(label);
      if (cb) {
        checked = cb[1] !== ' ';
        label = label.slice(cb[0].length).trim();
      }
      opts.unshift({
        number: Number(m[2]),
        label: label.slice(0, 120),
        // Anchored to the line START, where the selection caret is actually
        // drawn. Testing the whole line meant any '>' inside an option's TEXT
        // counted as a caret — and "Yes, and don't ask again for echo a > b" is
        // ordinary Claude Code wording. The consequence was not a mis-marked
        // option but a prompt that vanished entirely: two options looked
        // selected, the exactly-one-caret guard below rejected the whole thing,
        // and that permission dialog got no buttons in the app, no options in
        // its notification, and no lock-screen answer. Verified: adding '>' to
        // one option's text flipped detection from true to false.
        selected: /^\s*[\u276F>]/.test(plain[j]),
        ...(checked === null ? {} : { checked }),
      });
      firstIdx = j;
      // The run STARTS at 1 — the contiguity check below requires it — so
      // nothing above option 1 can belong to this dialog. Stopping here is what
      // keeps a numbered PLAN BODY out of the run.
      //
      // Without it the walk kept climbing: the description-line rule two lines
      // down treats any 2+-space indent as an option's description, a plan
      // approval draws its question at indent-3, so the walk sailed past the
      // question and started collecting the plan's own "1. … 2. …" steps as
      // options. Contiguity then failed against the mixed run and the whole
      // prompt was discarded — no card on the phone, none on the desktop, no
      // options in the notification. Permission dialogs escaped only by accident,
      // their question being at indent-1.
      //
      // Asking for a plan and getting numbered steps is the DEFAULT shape, so
      // this was most plan approvals, and the owner had to answer them through
      // the raw Screen tab. Found in the 2026-08 audit; fixtures for both shapes
      // are in test/pane.test.js.
      if (Number(m[2]) === 1) break;
      continue;
    }
    const t = plain[j].trim();
    if (!t) continue;
    if (RULE_RE.test(plain[j])) continue;
    // A description line: indented under its option. A question or any other
    // flush-left content ends the run.
    if (/^\s{2,}/.test(plain[j]) && !MODE_HINT_RE.test(plain[j]) &&
        !STATUS_RE.test(plain[j]) && !PROMPT_MARK_RE.test(plain[j])) continue;
    break;
  }
  if (opts.length < 2) return null;
  // A real dialog is small: AskUserQuestion caps at 4 options and the TUI adds
  // at most "Type something" + "Chat about this" (so <=6), a permission dialog
  // ~4. Ten or more contiguous numbered rows with one caret is a numbered
  // document — a plan body, a markdown list — not a prompt. Refusing it also
  // retires the never-tested multi-digit send path for options >= 10.
  if (opts.length > 9) return null;
  // Must be 1..n contiguous, else this is prose that happens to have numbers.
  for (let k = 0; k < opts.length; k++) {
    if (opts[k].number !== k + 1) return null;
  }
  // A LIVE prompt always has exactly one option marked with the selection
  // caret. Without this, an assistant answer ending in a markdown numbered list
  // matched every other rule — and a false positive is not benign here, because
  // tapping the resulting button types a digit into Claude's composer.
  if (opts.filter((o) => o.selected).length !== 1) return null;

  // The multi-question tab strip, e.g. "\u2190  \u2610 Database  \u2611 Cache  \u2714 Submit  \u2192".
  // Skipped when hunting the question, but returned so the fusion layer can tell
  // WHICH of an N-question AskUserQuestion is on screen (its options alone can be
  // ambiguous between sibling questions with identical choices). Additive: absent
  // for ordinary single-question dialogs.
  const headers = [];
  for (let k = firstIdx - 1; k >= floor; k--) {
    if (!HEADER_RE.test(plain[k])) continue;
    for (const part of plain[k].trim().split(/\s{2,}/)) {
      const hm = /^([\u2610\u2611\u2612])\s+(.+)$/.exec(part.trim());
      if (hm && !/^submit$/i.test(hm[2].trim())) {
        headers.push({ label: hm[2].trim(), checked: hm[1] !== '\u2610' });
      }
    }
  }

  // Question: the nearest line above the run that is not furniture or a tab
  // header. But a built-in dialog draws PROSE between its question and its
  // options \u2014 the trust dialog's OSC-8 "Security guide" link and a
  // "Claude can read/edit/execute\u2026" notice sit directly above the options, so
  // "nearest" picked the link text as the question. An AskUserQuestion question
  // ENDS with '?' and the built-in ones CONTAIN one, so a line bearing a '?'
  // beats the merely-nearest (captured live 2026-08-10, fixture trust-dialog).
  let question = '';
  let questionFallback = '';
  for (let k = firstIdx - 1; k >= floor; k--) {
    const t = plain[k].trim();
    if (!t || RULE_RE.test(plain[k]) || HEADER_RE.test(plain[k])) continue;
    if (!questionFallback) questionFallback = t.slice(0, 240);
    if (t.includes('?')) { question = t.slice(0, 240); break; }
  }
  if (!question) question = questionFallback;
  return {
    question,
    options: opts,
    // Any checkbox present means the dialog wants a SET, and answering it takes
    // the toggle-review-submit dance rather than digit-and-Enter.
    multiSelect: opts.some((o) => typeof o.checked === 'boolean'),
    ...(headers.length ? { headers } : {}),
  };
}

/**
 * The keystrokes that move a multi-select dialog from its CURRENT state to the
 * DESIRED one: a digit per option whose state must flip (digits toggle,
 * verified live), computed as a diff because the user may have half-answered in
 * tmux already — blindly pressing every desired digit would UN-check those.
 */
function multiToggleDigits(options, desired) {
  const want = new Set(desired);
  const digits = [];
  for (const o of options || []) {
    if (typeof o.checked !== 'boolean') continue;      // not a checkbox row
    const should = want.has(o.number);
    if (should !== o.checked) digits.push(String(o.number));
  }
  return digits;
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
const LIVE_STATUS_RE = /^\s*[\u2722\u2726\u2727\u2733\u273D\u273B\u2736\u2738\u2739\u273A\u00B7\u2219\u2217*+]\s+(\S[^(]*?\u2026)\s*(?:\((.*)\))?\s*$/;

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

// \u2726\u2727 (\u2726\u2727) are in the class because of the capture recorded above: the
// comment documenting "\u2727 Waiting for 1 dynamic workflow to finish" as the reason
// this second pass exists sat directly over a class that could not match it, so the
// one case it was written for returned null. The pass had been proving itself on a
// DIFFERENT glyph.
const GLYPH_STATUS_RE = /^\s*[\u2722\u2726\u2727\u2733\u273D\u273B\u2736\u2738\u2739\u273A\u00B7\u2219\u2217*+]\s+(\S.*)$/;

// The TUI's own progress furniture, captured live 2026-07-27:
//   \u25EF andvari-polish-wave3  Wave 3   0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens
//   \u29C9  audit-board
//   Running 1 shell command \u00B7 2s\u2026
// ONLY the glyphs observed on real progress rows: \u25EF workflow, \u29C9 board,
// \u25D0-\u25D3 phase spinner. \u25CF is NOT here — it is the TUI's ordinary message
// bullet, and including it promoted scroll text ("\u25CF Two clean asks…",
// "\u25CF Write(file)") into the status strip.
const PROGRESS_ROW_RE = /^\s*[\u25EF\u25D0\u25D1\u25D2\u25D3\u29C9]\s+(\S.*)$/;
const RUNNING_ROW_RE = /^\s*(Running \d+ (?:agents?|shells?|shell commands?|tasks?|commands?)\b.*)$/i;

// The transient per-tool summary the TUI redraws constantly: "Running 2 shell
// commands \u00B7 4s\u2026", "Searching for 1 pattern\u2026". Distinct from the durable rows
// because it appears and vanishes at tool speed — rendered naively it made the
// strip grow a line and lose it again on repeat.
const TRANSIENT_ROW_RE = /^\s*((?:Running|Searching|Spawning|Reading|Writing|Fetching|Building|Testing|Analyzing|Editing)\b.*(?:\d.*|\u2026))$/i;

/**
 * The progress rows the TUI draws besides the spinner, split by lifetime.
 * Durable rows (workflow phases \u25EF, boards \u29C9, "Running N agents") persist for
 * the length of the work; the transient row turns over with every tool call and
 * is reported separately so the client can update it in place.
 */
function parseStatusExtras(lines, max = 3) {
  const durable = [];
  let transient = null;
  // Only the pane tail: the live status area is always at the bottom, and the
  // scroll above it can contain text that LOOKS like a status row — old renders
  // of tool activity, prose bullets — which is history, not status.
  for (const raw of lines.slice(-10)) {
    const t = stripAnsi(raw).trimEnd();
    if (!t.trim() || /Tip:/.test(t)) continue;
    const p = PROGRESS_ROW_RE.exec(t);
    if (p) {
      const text = p[1].replace(/\s{2,}/g, ' \u00B7 ').trim().slice(0, 120);
      if (!durable.includes(text) && durable.length < max) durable.push(text);
      continue;
    }
    const r = RUNNING_ROW_RE.exec(t) || TRANSIENT_ROW_RE.exec(t);
    // Bottom-most wins: the newest state of a row that redraws in place.
    if (r) transient = r[1].replace(/\s{2,}/g, ' \u00B7 ').trim().slice(0, 120);
  }
  // "Running N agents" is durable in spirit (it lasts the fan-out), so promote it.
  if (transient && /^Running \d+ agents?\b/i.test(transient) && durable.length < max) {
    durable.push(transient);
    transient = null;
  }
  // A workflow row reporting every agent done is FINISHED — the TUI keeps the
  // row on screen, but mirrored into a status strip it reads as still running.
  // Confirmed as a ghost by the user against the actual pane.
  const alive = durable.filter((d) => {
    const m = /(\d+)\/(\d+) agents done/.exec(d);
    return !(m && m[1] === m[2]);
  });
  return { durable: alive, transient };
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
  // Neither the caret nor a checkbox state is part of the question's identity:
  // moving the highlight or half-toggling in tmux does not change what is asked,
  // and labels arrive with checkbox markers already stripped.
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
  const out = { model: null, branch: null, mode: null, contextPercent: null };
  const plain = lines.map((l) => stripAnsi(l).replace(/\s+$/, ''));
  for (let i = plain.length - 1; i >= 0 && i >= plain.length - 8; i--) {
    const t = plain[i].trim();
    if (!t) continue;
    if (!out.mode) {
      // Most modes read "<word> mode on" (auto/manual/plan), but accept-edits
      // reads "accept edits on" — no "mode", two words. The old `(\w+)\s+mode`
      // matched neither the space nor the missing "mode", so accept-edits gave
      // liveMode: null. Lazy multi-word capture with an optional "mode" covers
      // both (captured live 2026-08-10, fixture statusline-plan).
      const m = /^[⏵⏴⏸⏹▶]{1,2}\s*(.+?)\s+(?:mode\s+)?on\b/.exec(t);
      if (m) { out.mode = m[1].toLowerCase(); continue; }
    }
    if (out.model == null) {
      // huginn-statusline.sh draws "[name] Model · ctx N% · branch ~N · ⚠ N …".
      // The fields after the model are OPTIONAL and order-independent (ctx only
      // when the harness reports a context window; the tree-session warning and
      // the git branch may each be absent), so split on the ` · ` separators and
      // classify — the old regex grabbed the SECOND field as the branch, which
      // put "ctx N%" in `branch` and dropped both the real branch AND the context
      // pressure the context meter needs.
      const bracket = /^\[[^\]]+\]\s+(.+)$/.exec(t);
      if (bracket) {
        const fields = bracket[1].split(/\s+·\s+/).map((f) => f.trim()).filter(Boolean);
        if (fields.length) {
          out.model = fields[0] || null;
          for (const f of fields.slice(1)) {
            const cm = /^ctx\s+(\d+)%/.exec(f);
            if (cm) { out.contextPercent = Number(cm[1]); continue; }
            // The concurrency warning ("⚠ N sessions in this tree"), not a branch.
            if (/^⚠/.test(f) || /sessions?\s+in\s+this\s+tree/i.test(f)) continue;
            // The git branch, minus the "~N" dirty-count the statusline appends.
            if (out.branch == null) out.branch = f.replace(/\s+~\d+\s*$/, '').trim() || null;
          }
        }
      }
    }
  }
  return out;
}

/**
 * Is the session compacting its context right now? Claude Code draws
 * "Compacting conversation…" as the live spinner while it does; that arrives via
 * parseSpinner as the spinner string, so this reads it there. A dedicated
 * PreCompact/PostCompact marker (huginn-claude-title) is the reliable,
 * poll-independent signal the daemon layers on top for the sessions list.
 */
function spinnerIsCompacting(spinner) {
  return !!spinner && /compact/i.test(spinner);
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
  screenHash, stripAnsi, previewLines, detectPrompt, promptFingerprint, multiToggleDigits,
  parseSpinner, parseStatusExtras, spinnerIsCompacting,
  extractLoginUrl, parseStatusLine, loginPaneState,
};
