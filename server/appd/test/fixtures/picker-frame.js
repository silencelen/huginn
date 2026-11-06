'use strict';
// Claude Code's `/model` picker as 2.1.316 draws it. One drawer for the stub
// picker (a real tmux pane, stub-picker.js) and the ladder's in-process tests
// (ladder-picker-window.test.js), so the two cannot drift apart on the layout
// the parser has to read.
//
// Measured on 2.1.316, 2026-11-05, in an 80x24 pane — the captures are
// prompts/model-picker-window-80x24.txt and prompts/model-picker-window-frames-80.txt:
//
//   * a short pane shows a WINDOW of the list: `visible` rows starting at
//     clamp(cursor - floor(visible / 2), 0, length - visible). Two rows at 80x24,
//     so the highlight sits on the lower one everywhere but row 1;
//   * the first row drawn is marked `↑` when rows are hidden above it and the
//     last `↓` when rows are hidden below it, unless that row is the highlight;
//   * `      … +N models` (`… +1 model`) follows while rows remain below;
//   * `N.` is four columns, the name column 23; a description longer than the
//     pane wraps at a space onto lines indented to its own column;
//   * the list is cyclic — moving the cursor is the caller's job (stub-picker.js).

const BLURB = [
  '   Switch between Claude models. Your pick becomes the default for new',
  '   sessions. For other/previous model names, specify with --model.',
];
const FOOTER = '   Enter to set as default · s to use this session only · Esc to cancel';

function wrap(text, max) {
  const out = [];
  let line = '';
  for (const word of String(text).split(' ')) {
    if (line && line.length + 1 + word.length > max) { out.push(line); line = word; } else line = line ? `${line} ${word}` : word;
  }
  out.push(line);
  return out;
}

/**
 * The picker's lines, heading to footer.
 *
 * @param rows     [{label, desc?, current?}] in list order
 * @param cursor   index of the highlighted row
 * @param visible  rows drawn; 0 draws the whole list
 * @param width    the pane's columns, for wrapping descriptions
 */
function pickerFrame(rows, cursor, { visible = 0, width = 80 } = {}) {
  const len = rows.length;
  const w = visible > 0 ? Math.min(visible, len) : len;
  const start = Math.max(0, Math.min(cursor - Math.floor(w / 2), len - w));
  const end = start + w;
  const out = ['   Select model', ...BLURB, ''];
  for (let i = start; i < end; i++) {
    const r = rows[i];
    let glyph = ' ';
    if (i === cursor) glyph = '❯';
    else if (i === start && start > 0) glyph = '↑';
    else if (i === end - 1 && end < len) glyph = '↓';
    const name = `${r.label}${r.current ? ' ✔' : ''}`;
    const prefix = `   ${glyph} ${`${i + 1}.`.padEnd(4)}${name.length >= 21 ? `${name}  ` : name.padEnd(23)}`;
    const desc = wrap(r.desc || 'a model', Math.max(10, width - prefix.length));
    out.push(prefix + desc[0]);
    for (const rest of desc.slice(1)) out.push(' '.repeat(prefix.length) + rest);
  }
  if (end < len) out.push(`      … +${len - end} model${len - end === 1 ? '' : 's'}`);
  out.push('', '   ● High effort ←/→ to adjust', '', '   Use /fast to turn on Fast mode (Opus 5.5).', '', FOOTER);
  return out;
}

/**
 * The list Claude Code 2.1.316 offered, in its order (newest first in each
 * family), as the captures show it. `id` is what the PostModelSwitch hook
 * records as `to`.
 */
const LIST_2_1_316 = [
  { label: 'Default (recommended)', desc: 'Opus 5.5 · Best for everyday, complex tasks' },
  { label: 'Opus 5.5', id: 'claude-opus-5-5', desc: 'For complex work and everyday tasks' },
  { label: 'Fable 5.1', id: 'claude-fable-5-1', desc: 'For your toughest challenges' },
  { label: 'Sonnet 5.5', id: 'claude-sonnet-5-5', desc: 'Most efficient for simpler tasks' },
  { label: 'Haiku 5.5', id: 'claude-haiku-5-5', desc: 'Fastest for quick answers' },
  { label: 'Haiku 4.5', id: 'claude-haiku-4-5', desc: 'Fastest for quick answers' },
  { label: 'Sonnet 5', id: 'claude-sonnet-5', desc: 'Efficient for routine tasks' },
  { label: 'Opus 5', id: 'claude-opus-5', desc: 'Best for everyday, complex tasks' },
  { label: 'Fable 5', id: 'claude-fable-5', desc: 'Most capable for your hardest and longest-running tasks' },
  { label: 'Opus 4.8', id: 'claude-opus-4-8', desc: 'Best for everyday, complex tasks' },
  { label: 'Opus 4.7', id: 'claude-opus-4-7', desc: 'Best for everyday, complex tasks' },
  { label: 'Opus 4.6', id: 'claude-opus-4-6', desc: 'Best for everyday, complex tasks' },
  { label: 'Sonnet 4.6', id: 'claude-sonnet-4-6', desc: 'Efficient for routine tasks' },
];

/** That list with the session on `label` (the ✔ row, where the highlight opens). */
function listOn(label, list = LIST_2_1_316) {
  if (!list.some((r) => r.label === label)) throw new Error(`no ${label} row`);
  return list.map((r) => ({ ...r, current: r.label === label }));
}

module.exports = { pickerFrame, LIST_2_1_316, listOn };
