'use strict';
// Every test file that starts huginn-appd must keep it from reaching the owner:
// it requires ./no-owner-contact, directly or through ./retry-fetch, before it
// spawns anything (see no-owner-contact.js for why).

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

test('every test that spawns huginn-appd blanks the relay and the push key first', () => {
  const dir = __dirname;
  const missing = [];
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.test.js'))) {
    const src = fs.readFileSync(path.join(dir, f), 'utf8');
    const startsDaemon = /huginn-appd\.js/.test(src) && /\b(spawn|fork)\s*\(/.test(src);
    if (!startsDaemon) continue;
    if (!/require\('\.\/(no-owner-contact|retry-fetch)'\)/.test(src)) missing.push(f);
  }
  assert.deepEqual(missing, [], `these start a daemon that could reach the owner: ${missing.join(', ')}`);
});

test('the module points both at nothing', () => {
  const saved = { t: process.env.HUGINN_APPD_TELEGRAM_SCRIPT, k: process.env.HUGINN_FCM_KEY };
  process.env.HUGINN_APPD_TELEGRAM_SCRIPT = '/usr/local/bin/some-relay';
  delete require.cache[require.resolve('./no-owner-contact')];
  require('./no-owner-contact');
  assert.equal(process.env.HUGINN_APPD_TELEGRAM_SCRIPT, '');
  assert.ok(!fs.existsSync(process.env.HUGINN_FCM_KEY), process.env.HUGINN_FCM_KEY);
  process.env.HUGINN_APPD_TELEGRAM_SCRIPT = saved.t; process.env.HUGINN_FCM_KEY = saved.k;
  if (saved.t === undefined) delete process.env.HUGINN_APPD_TELEGRAM_SCRIPT;
  if (saved.k === undefined) delete process.env.HUGINN_FCM_KEY;
});
