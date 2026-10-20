'use strict';
// The headroom `defaultModel` setting is documented as "your Claude Code
// default", and the first boot seeds it from `~/.claude/settings.json`. Claude
// Code's own `/model` picker and its own settings migrations write values there
// that are neither a bare family nor an id — `opus[1m]`, `fable[1m]`,
// `claude-opus-5-5[1m]`, `opusplan` — and the seed copied them verbatim into a
// file the loader then rejected as a WHOLE, on every load: the daemon ran on the
// contract defaults, so an account-switch preference migrated ON was off.
//
// The seed is driven through the real entry point (`--seed-headroom-defaults`)
// in a scratch directory, with every path the daemon reads at load pointed at
// that directory. It binds no port and exits.

const { test } = require('node:test');
const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const h = require('../lib/headroom');
const { familyOf } = require('../lib/models');
const { lift } = require('./ladder-lift');

const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');

/** A scratch host: Claude Code's settings say `model`, and autoswitch was ON at 90%. */
function host(model) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `hr-defmodel-${process.pid}-`));
  for (const d of ['claude', 'data', 'state']) fs.mkdirSync(path.join(dir, d));
  fs.writeFileSync(path.join(dir, 'token'), crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
  fs.writeFileSync(path.join(dir, 'claude', 'settings.json'), `${JSON.stringify({ model })}\n`);
  fs.writeFileSync(path.join(dir, 'data', 'autoswitch.json'), JSON.stringify({ enabled: true, threshold: 90 }));
  return dir;
}

/** `node huginn-appd.js --seed-headroom-defaults` against that host; its output. */
function seed(dir) {
  const r = spawnSync(process.execPath, [DAEMON, '--seed-headroom-defaults'], {
    encoding: 'utf8',
    timeout: 60_000,
    env: {
      PATH: process.env.PATH,
      HOME: dir,
      HUGINN_APPD_DATA: path.join(dir, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(dir, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(dir, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(dir, 'claude'),
      HUGINN_APPD_TMUX_SOCKET: `hr-defmodel-${process.pid}`,
      // Nothing outside the scratch dir: no push key, no Telegram.
      HUGINN_FCM_KEY: path.join(dir, 'no-push-key.json'),
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
  });
  assert.equal(r.status, 0, `seed exited ${r.status}: ${r.stdout}${r.stderr}`);
  return `${r.stdout}${r.stderr}`;
}

/** What loadHeadroomSettings does with the stored file: judge it as the patch. */
function load(dir) {
  const raw = JSON.parse(fs.readFileSync(path.join(dir, 'data', 'headroom-settings.json'), 'utf8'));
  return { raw, v: h.validateSettings(raw, h.defaults()) };
}

test('Claude Code\'s own model settings are a valid default model', () => {
  for (const m of ['opus[1m]', 'sonnet[1m]', 'fable[1m]', 'claude-opus-5-5[1m]', 'claude-fable-5-1[1m]',
    'opusplan', 'best', 'opus', 'claude-opus-5-5']) {
    const v = h.validateSettings({ defaultModel: m });
    assert.equal(v.ok, true, `${m}: ${v.error}`);
    assert.equal(v.settings.defaultModel, m, 'kept verbatim: it is written back to the CLI as it is');
  }
  for (const m of ['gpt-5', '[1m]', 'opus[', 'best[1m]', 'opus[1m]x', 'claude-gpt-5[1m]']) {
    assert.equal(h.validateSettings({ defaultModel: m }).ok, false, `${m} must still be refused`);
  }
});

test('the family behind a 1M-context setting is the family it is attached to', () => {
  assert.equal(familyOf('opus[1m]'), 'opus');
  assert.equal(familyOf('fable[1m]'), 'fable');
  assert.equal(familyOf('claude-sonnet-5-5[1m]'), 'sonnet');
  // An alias that names no single family has none — never a guess.
  assert.equal(familyOf('opusplan'), null);
  assert.equal(familyOf('best'), null);
});

test('a host whose Claude Code default is opus[1m] seeds a settings file that loads as written', () => {
  const dir = host('opus[1m]');
  seed(dir);
  const second = seed(dir);
  assert.doesNotMatch(second, /does not validate/, 'the stored file must load, not fall back to defaults');
  const { raw, v } = load(dir);
  assert.equal(v.ok, true, v.error);
  assert.equal(raw.defaultModel, 'opus[1m]', 'the CLI default, verbatim');
  assert.deepEqual(
    { enabled: v.settings.accountSwitch.enabled, threshold: v.settings.accountSwitch.threshold },
    { enabled: true, threshold: 90 },
    'and the account-switch preference migrated from autoswitch.json is still on');
});

test('a default the daemon cannot read is not seeded, and the migrated preference survives', () => {
  const dir = host('some-future-alias');
  const first = seed(dir);
  assert.match(first, /not one this daemon reads; seeding claude-fable-5-1/);
  const second = seed(dir);
  assert.doesNotMatch(second, /does not validate/);
  const { raw, v } = load(dir);
  assert.equal(v.ok, true, v.error);
  assert.equal(raw.defaultModel, h.defaults().defaultModel, 'the contract default stands');
  assert.equal(v.settings.accountSwitch.enabled, true);
});

test('a default-model chat on the host default is not captioned as moved off Fable', () => {
  // ranOnLabel compares the model a run reports against the settings' default.
  // With a CLI default of opus[1m], a chat that ran on opus got what it asked
  // for — while the settings fell back to the contract's Fable it was captioned
  // "opus (Fable limit)" on every run.
  const caption = (dir) => lift(['loadHeadroomSettings', 'ranOnLabel'], {
    fs, headroomLib: h, log: () => { },
    HEADROOM_SETTINGS_FILE: path.join(dir, 'data', 'headroom-settings.json'),
  }).ranOnLabel;
  const opusHost = host('opus[1m]');
  seed(opusHost);
  assert.equal(caption(opusHost)(null, 'claude-opus-5-5'), null);
  // ...while a run that WAS moved off a Fable default still says so.
  const fableHost = host('fable[1m]');
  seed(fableHost);
  assert.equal(caption(fableHost)(null, 'claude-opus-5-5'), 'opus (Fable limit)');
});
