#!/usr/bin/env node
// capture.mjs — rebuild preview.png and the sprite sheets. Run: node capture.mjs
//   preview.png   three rows: 128 px light (t 0..1.2 s), 24 px dark and 16 px dark (pixel-upscaled)
//   sprite-ring.png   12 ring frames at 64 px, 100 ms apart, from the second cycle (badge static)
//   sprite-pop.png    3 badge-pop frames at 64 px (t 0.0, 0.1, 0.2 s), first cycle
// The sprites are rendered on the dark ground for viewing; the tray draws its own tint.
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-04-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
const ring = '0,0.24,0.48,0.72,0.96,1.2';
run([join(tmp, 'a'), '--times', ring, '--size', '128', '--strip', join(tmp, 'row128.png')]);
run([join(tmp, 'b'), '--times', ring, '--size', '24', '--query', 'theme=dark', '--upscale', '128', '--strip', join(tmp, 'row24.png')]);
run([join(tmp, 'c'), '--times', ring, '--size', '16', '--query', 'theme=dark', '--upscale', '128', '--strip', join(tmp, 'row16.png')]);
execSync(`montage "${join(tmp, 'row128.png')}" "${join(tmp, 'row24.png')}" "${join(tmp, 'row16.png')}" -tile 1x3 -geometry +0+0 -background '#808080' "${join(here, 'preview.png')}"`);
// sprites: plain frames, no labels, appended edge to edge
const ringTimes = Array.from({ length: 12 }, (_, i) => (1.2 + i / 10).toFixed(2)).join(',');
run([join(tmp, 'ring'), '--times', ringTimes, '--size', '64', '--query', 'theme=dark']);
run([join(tmp, 'pop'), '--times', '0,0.1,0.2', '--size', '64', '--query', 'theme=dark']);
const frames = (d) => readdirSync(d).filter(f => /^f-[\d.]+\.png$/.test(f)).sort((a, b) => parseFloat(a.slice(2)) - parseFloat(b.slice(2))).map(f => `"${join(d, f)}"`).join(' ');
execSync(`convert ${frames(join(tmp, 'ring'))} +append "${join(here, 'sprite-ring.png')}"`);
execSync(`convert ${frames(join(tmp, 'pop'))} +append "${join(here, 'sprite-pop.png')}"`);
console.log('preview.png sprite-ring.png sprite-pop.png');
