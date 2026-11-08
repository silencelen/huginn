'use strict';
// Claude Code's own count of its background work, and its `/exit` prompt
// (appd 3.19.1, 2026-11-08) — fed real captures from a throwaway 2.1.316
// session on a private socket (test/fixtures/prompts/, see the README there).
//
// The restart sweep's first real run cycled nothing in six hours, and one of
// the reasons was here: a session with a live Monitor read `bgShells: 0,
// bgAgents: 0` — a Monitor leaves nothing in the tasks directory until its
// first event and never holds its file open — so `/exit` was typed, Claude Code
// answered with "Background work is running", and the dialog sat on the pane
// for two hours. The count that prompt is built from is on the pane the whole
// time, in the mode hint under the box.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const pane = require('../lib/pane');
const typing = require('../lib/typing');

const DIR = path.join(__dirname, 'fixtures', 'prompts');
const load = (name) => fs.readFileSync(path.join(DIR, name), 'utf8').split('\n');

test('the mode hint under the box carries the TUI\'s own background count: a shell and a Monitor', () => {
  const lines = load('statusline-background-80.txt');
  assert.deepStrictEqual(pane.parseBackgroundHint(lines), { shells: 1, monitors: 1, agents: 0, total: 2 });
  // The same pane is an ordinary idle one to every other reader.
  assert.equal(typing.paneReadyForInput(lines).why, 'busy', 'no dialog: the last row is the hint, as on every live pane');
  assert.equal(typing.composerDrawn(lines), true);
  assert.equal(pane.exitDialog(lines), null);
});

test('a hint that counts nothing is zeros; no hint row at all (a dialog has the pane) is null', () => {
  const idle = load('autoupdate-banner-tip-110.txt');
  assert.deepStrictEqual(pane.parseBackgroundHint(idle), { shells: 0, monitors: 0, agents: 0, total: 0 });
  assert.equal(pane.parseBackgroundHint(load('exit-background-work-80.txt')), null);
  assert.equal(pane.parseBackgroundHint(load('bash-permission-80.txt')), null);
  assert.equal(pane.parseBackgroundHint([]), null);
  // Assembled from the same row shape: agents count too, and several of a kind add up.
  const made = ['─'.repeat(40), '❯ ', '─'.repeat(40), '  [alex] Fable 5.1 · ctx 4%', '  ⏵⏵ auto mode on · 2 shells, 1 agent · ↓ to manage'];
  assert.deepStrictEqual(pane.parseBackgroundHint(made), { shells: 2, monitors: 0, agents: 1, total: 3 });
});

test('an idle pane with the auto-update banner and a tip is NOT a dialog', () => {
  // The capture the sweep's drops were blamed on: `Tip: …`, `✘ Auto-update
  // failed · Try claude doctor or npm i -g …`, the composer, the status line,
  // the mode hint. It reads as an ordinary live pane; the `modal` the journal
  // showed was Claude Code's exit prompt, which a person had since dismissed.
  const lines = load('autoupdate-banner-tip-110.txt');
  const why = typing.paneReadyForInput(lines).why;
  assert.equal(typing.paneBlocks(why), false, `read as ${why}`);
  assert.equal(typing.composerDrawn(lines), true);
  assert.equal(pane.detectPrompt(lines), null);
  assert.equal(pane.detectUnnumberedSelector(lines), false);
  assert.equal(pane.exitDialog(lines), null);
});

test('the /exit prompt over background work: a numbered dialog, recognised with the rows it will stop', () => {
  const lines = load('exit-background-work-80.txt');
  const why = typing.paneReadyForInput(lines).why;
  assert.equal(why, 'modal', 'it is a dialog to the send gate: a message pasted now would go into it');
  const ex = pane.exitDialog(lines);
  assert.ok(ex, 'recognised');
  assert.deepStrictEqual(ex.work, ['shell · sleep 900', 'monitor · r5 probe']);
  assert.equal(ex.prompt.options.length, 3);
  assert.equal(ex.prompt.options[0].selected, true, 'Enter would take `Exit and stop tasks`');
  assert.match(ex.prompt.options[0].label, /^Exit and stop tasks/);
  assert.match(ex.prompt.options[2].label, /^Stay/);
});

test('no other dialog is the /exit prompt, and its heading alone is not either', () => {
  for (const f of ['bash-permission-80.txt', 'plan-approval-v3-80.txt', 'trust-dialog-80.txt', 'trust-dialog-unnumbered-80.txt', 'ask-simple-80.txt', 'model-picker-80.txt']) {
    assert.equal(pane.exitDialog(load(f)), null, f);
  }
  // Claude quoting the prompt in prose, over a live composer: words, not a dialog.
  const prose = [
    '● The prompt says "Background work is running" and offers',
    '  1. Exit and stop tasks',
    '  2. Move to background and exit',
    '  3. Stay',
    '─'.repeat(40), '❯ ', '─'.repeat(40), '  ⏵⏵ auto mode on (shift+tab to cycle)',
  ];
  assert.equal(pane.exitDialog(prose), null);
  assert.equal(typing.paneBlocks(typing.paneReadyForInput(prose).why), false);
});
