#!/usr/bin/env node
// capture.mjs — rebuild preview.png: six frames of anim.html, 60 ms apart, across one
// blink (close 100 ms / hold 60 ms / open 160 ms). Frames go to a temp dir; the
// labelled strip is written beside this file. Run: node capture.mjs
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const frames = mkdtempSync(join(tmpdir(), 'huginn-anim-01-'));
execFileSync('node', [join(here, '..', 'capture.mjs'), join(here, 'anim.html'), frames,
  '--times', '0,0.06,0.12,0.18,0.24,0.30', '--size', '256', '--strip', join(here, 'preview.png')], { stdio: 'inherit' });
