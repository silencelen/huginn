#!/usr/bin/env node
// capture.mjs — rebuild preview.png: six frames of the three-option page (dark theme,
// the app's primary scheme), 500 ms apart, tiled 2 x 3. Run: node capture.mjs
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const frames = mkdtempSync(join(tmpdir(), 'huginn-anim-05-'));
execFileSync('node', [join(here, '..', 'capture.mjs'), join(here, 'anim.html'), frames,
  '--times', '0,0.5,1.0,1.5,2.0,2.5', '--w', '720', '--h', '300', '--query', 'theme=dark', '--tile', '2x3',
  '--strip', join(here, 'preview.png')], { stdio: 'inherit' });
