#!/usr/bin/env node
'use strict';
// Embeds shared/local-runtime.json into client/huginn-local between GENERATED
// markers — the gen-device-policy.js precedent, for the same reason: the one
// fetched file must carry its own pins, so there is no second fetch to
// substitute, and a runtime or model bump is BY CONSTRUCTION a reviewed
// cli-v* release. There is no other path to change what `huginn local on` may
// install.
//
//   node scripts/gen-local-manifest.js           # write
//   node scripts/gen-local-manifest.js --check   # fail if anything would change

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'shared/local-runtime.json');
const TARGET = path.join(ROOT, 'client/huginn-local');
const CHECK = process.argv.includes('--check');

const M = JSON.parse(fs.readFileSync(SRC, 'utf8'));

// ------------------------------------------------------------- validation
// A manifest that cannot verify a download must never ship.

const fail = (msg) => { console.error(`shared/local-runtime.json: ${msg}`); process.exit(1); };
const isSha = (s) => /^[0-9a-f]{64}$/.test(s || '');

for (const [k, a] of Object.entries(M.llamaCpp.assets)) {
  if (!isSha(a.sha256)) fail(`llamaCpp.assets.${k}: bad sha256`);
  if (!(a.bytes > 0)) fail(`llamaCpp.assets.${k}: bytes must be > 0`);
}
for (const [k, a] of Object.entries(M.llamaSwap.assets)) {
  if (!isSha(a.sha256)) fail(`llamaSwap.assets.${k}: bad sha256`);
  if (!(a.bytes > 0)) fail(`llamaSwap.assets.${k}: bytes must be > 0`);
}
if (!isSha(M.winsw.sha256) || !(M.winsw.bytes > 0)) fail('winsw: bad sha256 or bytes');
for (const [cls, models] of Object.entries(M.models)) {
  const chat = models.filter((m) => m.role === 'chat');
  const embed = models.filter((m) => m.role === 'embed');
  if (chat.length !== 1 || embed.length !== 1) fail(`models.${cls}: exactly one chat and one embed model`);
  for (const m of models) {
    if (!/^[a-z0-9][a-z0-9-]{1,29}$/.test(m.slug)) fail(`models.${cls}.${m.slug}: bad slug`);
    if (!isSha(m.sha256)) fail(`models.${cls}.${m.slug}: bad sha256`);
    if (!(m.bytes > 0)) fail(`models.${cls}.${m.slug}: bytes must be > 0`);
    if (!m.license) fail(`models.${cls}.${m.slug}: license missing — weights are pulled from the vendor, and a bump must not quietly pull in a restrictive one`);
  }
}

// -------------------------------------------------------------- injection

const BEGIN = '// ---8<--- BEGIN GENERATED MANIFEST';
const END = '// ---8<--- END GENERATED MANIFEST';

const text = fs.readFileSync(TARGET, 'utf8');
const a = text.indexOf(BEGIN);
const b = text.indexOf(END);
if (a < 0 || b < 0) { console.error('client/huginn-local has lost its GENERATED MANIFEST markers'); process.exit(1); }
const head = text.slice(0, a);
const tail = text.slice(text.indexOf('\n', b) + 1);
const bare = JSON.parse(JSON.stringify(M, (k, v) => (k === '_comment' ? undefined : v)));
const block = `${BEGIN} — scripts/gen-local-manifest.js — DO NOT EDIT ---8<---
const MANIFEST = ${JSON.stringify(bare, null, 2)};
${END} ---8<---
`;
const next = head + block + tail;

if (next === text) {
  console.log(CHECK ? 'local manifest: in sync' : '  ok    client/huginn-local');
  process.exit(0);
}
if (CHECK) {
  console.error('client/huginn-local\'s embedded MANIFEST does not match shared/local-runtime.json.');
  console.error('Run: node scripts/gen-local-manifest.js   — and READ the diff.');
  process.exit(1);
}
fs.writeFileSync(TARGET, next);
console.log('  wrote client/huginn-local');
