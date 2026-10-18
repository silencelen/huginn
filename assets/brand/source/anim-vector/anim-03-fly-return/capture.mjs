#!/usr/bin/env node
// capture.mjs — rebuild the review artefacts of anim-03-fly-return. Run: node capture.mjs
//   preview.png  row 1: eight frames on bone (224 px): rest, wings unfurled, climb, exit,
//                       entry, glide/flare, fold, rest
//                row 2: the same on the app's dark ground
//                row 3: the same at 64 px (pixel-upscaled 2x): what the flight reads like small
//   poster.png   the resting state (animations disabled) at 256 px on bone
//   edge scan    prints, for every 50 ms of the flight, whether ink touches the top or
//                bottom edge of the canvas (it must not), and the frame's ink bbox
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-03-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
const eight = '2.0,2.42,2.62,3.0,3.9,4.45,4.7,5.0';
run([join(tmp, 'light'), '--times', eight, '--size', '224', '--strip', join(tmp, 'row-light.png')]);
run([join(tmp, 'dark'), '--times', eight, '--size', '224', '--query', 'theme=dark', '--strip', join(tmp, 'row-dark.png')]);
run([join(tmp, 's64'), '--times', eight, '--size', '64', '--upscale', '128', '--strip', join(tmp, 'row-64.png')]);
execSync(`montage "${join(tmp, 'row-light.png')}" "${join(tmp, 'row-dark.png')}" "${join(tmp, 'row-64.png')}" -tile 1x3 -geometry +0+0 -gravity West -background '#808080' "${join(here, 'preview.png')}"`);
run([join(tmp, 'static'), '--times', '0', '--size', '256', '--query', 'static=1']);
copyFileSync(join(tmp, 'static', 'f-0.00.png'), join(here, 'poster.png'));
// edge scan
const times = Array.from({ length: 54 }, (_, i) => (2.2 + i * 0.05).toFixed(2)).join(',');
run([join(tmp, 'scan'), '--times', times, '--size', '256']);
let clipped = 0;
for (const f of readdirSync(join(tmp, 'scan')).filter(f => /^f-[\d.]+\.png$/.test(f)).sort()) {
  const p = join(tmp, 'scan', f);
  const top = Number(execSync(`convert "${p}" -crop 256x1+0+0 +repage -threshold 50% -format "%[fx:256*(1-mean)]" info:`).toString());
  const bot = Number(execSync(`convert "${p}" -crop 256x1+0+255 +repage -threshold 50% -format "%[fx:256*(1-mean)]" info:`).toString());
  if (top > 0.5 || bot > 0.5) { clipped++; console.log(`EDGE CLIP at ${f}: top ${top.toFixed(0)} px, bottom ${bot.toFixed(0)} px`); }
}
console.log(clipped ? `${clipped} frames clip the top/bottom edge` : 'edge scan: no frame touches the top or bottom edge');
console.log('preview.png poster.png');
