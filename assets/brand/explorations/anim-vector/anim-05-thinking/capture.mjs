#!/usr/bin/env node
// capture.mjs — rebuild preview.png: the three-option page (dark theme, the app's primary
// scheme) at six instants aligned to the PulseDot steps (0, 0.48, 0.80, 1.12, 1.44, 2.00 s),
// plus the RESTING state with animations disabled (what a non-animating renderer shows),
// tiled 2 x 4. Run: node capture.mjs
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-05-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
run([join(tmp, 'a'), '--times', '0,0.48,1.0,1.12,1.44,2.0', '--w', '720', '--h', '300', '--query', 'theme=dark', '--tile', '2x3', '--strip', join(tmp, 'six.png')]);
run([join(tmp, 's'), '--times', '0', '--w', '720', '--h', '300', '--query', 'theme=dark&static=1']);
execSync(`convert "${join(tmp, 's', 'f-0.00.png')}" -background '#808080' -fill '#F0ECE6' -font DejaVu-Sans-Mono -pointsize 13 -gravity South -splice 0x20 -annotate +0+3 'resting state (animations disabled)' "${join(tmp, 'static.png')}"`);
execSync(`montage "${join(tmp, 'six.png')}" "${join(tmp, 'static.png')}" -tile 1x2 -geometry +0+0 -gravity West -background '#808080' "${join(here, 'preview.png')}"`);
console.log('preview.png');
