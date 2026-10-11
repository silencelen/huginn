#!/usr/bin/env node
// capture.mjs — rebuild preview.png: six frames of anim.html, 600 ms apart, across the
// 3 s flight (2.0 s crouch, 2.6 s out, 3.2 s exit, 3.8 s re-entry, 4.4 s glide, 5.0 s perched).
// Run: node capture.mjs
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const frames = mkdtempSync(join(tmpdir(), 'huginn-anim-03-'));
execFileSync('node', [join(here, '..', 'capture.mjs'), join(here, 'anim.html'), frames,
  '--times', '2.0,2.6,3.2,3.8,4.4,5.0', '--size', '256', '--strip', join(here, 'preview.png')], { stdio: 'inherit' });
