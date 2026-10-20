'use strict';
// A copy of client/huginn-local whose generated MANIFEST block pins tiny local
// files, for tests that drive `on` and `update` end to end: every download is
// already in place with a matching hash, so nothing is fetched. Outside that
// block the copy is the shipped file byte for byte, which buildKit checks.
// Not a test file itself: required by the huginn-local-*.test.js files.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const sha = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');

/**
 * Into `dir`: the manager copy (`mgr`), the shim beside it, and the pinned
 * files (`files`: llama, swap, chat, embed) to place where a download would.
 */
function buildKit(dir, { manager, shim, swapVersion = 'v2' }) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o755 });
  fs.chmodSync(dir, 0o755);
  const stage = path.join(dir, 'stage');
  const exe = (f) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, '#!/bin/sh\nexit 0\n', { mode: 0o755 }); };
  exe(path.join(stage, 'llama', 'llama-b1', 'llama-server'));
  exe(path.join(stage, 'swap', 'llama-swap'));
  const files = {
    llama: path.join(dir, 'llama-b1-bin-ubuntu-x64.tar.gz'),
    swap: path.join(dir, `llama-swap_${swapVersion.replace(/^v/, '')}_linux_amd64.tar.gz`),
    chat: path.join(dir, 'tiny-chat.gguf'),
    embed: path.join(dir, 'tiny-embed.gguf'),
  };
  for (const [tar, from, what] of [[files.llama, 'llama', 'llama-b1'], [files.swap, 'swap', 'llama-swap']]) {
    const r = spawnSync('tar', ['-czf', tar, '-C', path.join(stage, from), what], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  }
  fs.writeFileSync(files.chat, 'tiny chat model\n');
  fs.writeFileSync(files.embed, 'tiny embed model\n');
  const asset = (f) => ({ name: path.basename(f), sha256: sha(f), bytes: fs.statSync(f).size });
  const model = (slug, role, f) => ({
    slug, display: slug, role, ctx: 512, gpu: false, repo: 'example/none', file: path.basename(f),
    sha256: sha(f), bytes: fs.statSync(f).size, license: 'test',
  });
  const models = [model('tiny-chat', 'chat', files.chat), model('tiny-embed', 'embed', files.embed)];
  const never = 'http://127.0.0.1:9/never-fetched/';
  const M = {
    manifestVersion: 1,
    llamaCpp: {
      release: 'b1', urlBase: never,
      serverBinary: { linux: 'llama-b1/llama-server', win32: 'llama-server.exe' },
      assets: Object.fromEntries(['linux-x64', 'linux-arm64', 'win-x64-cpu', 'win-x64-cuda', 'win-cudart'].map((k) => [k, asset(files.llama)])),
    },
    llamaSwap: { version: swapVersion, urlBase: never, assets: Object.fromEntries(['linux-x64', 'linux-arm64', 'win-x64'].map((k) => [k, asset(files.swap)])) },
    winsw: { version: 'v0', url: `${never}winsw.exe`, sha256: '00', bytes: 1 },
    models: { G8: models, C: models, A: models },
  };
  const src = fs.readFileSync(manager, 'utf8');
  const begin = src.indexOf('// ---8<--- BEGIN GENERATED MANIFEST');
  const bodyAt = src.indexOf('\n', begin) + 1;
  const end = src.indexOf('// ---8<--- END GENERATED MANIFEST');
  assert.ok(begin > 0 && end > bodyAt, 'the generated block is where the generator puts it');
  const out = `${src.slice(0, bodyAt)}const MANIFEST = ${JSON.stringify(M, null, 2)};\n${src.slice(end)}`;
  assert.equal(out.slice(0, bodyAt), src.slice(0, bodyAt));
  assert.equal(out.slice(out.indexOf('// ---8<--- END GENERATED MANIFEST')), src.slice(end), 'only the manifest differs');
  const mgr = path.join(dir, 'huginn-local');
  fs.writeFileSync(mgr, out, { mode: 0o755 });
  fs.copyFileSync(shim, path.join(dir, 'huginn-llm-shim'));
  fs.chmodSync(path.join(dir, 'huginn-llm-shim'), 0o644);
  return { mgr, files, M };
}

module.exports = { buildKit };
