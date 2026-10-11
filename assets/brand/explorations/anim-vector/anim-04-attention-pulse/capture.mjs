#!/usr/bin/env node
// capture.mjs — rebuild preview.png and the sprite sheets. Run: node capture.mjs
//   preview.png  four rows, eight frames each (t 0 .. 1.26 s: slide, pop, ring cycle 1):
//                128 px on bone (vector); 64 px on the dark ground = the sprite the tray
//                draws (pixel-upscaled 2x); then 24 px and 16 px made the way the OS makes
//                them, by Lanczos-DOWNSCALING the 64 px sprite frames (pixel-upscaled for
//                viewing). Earlier previews live-rendered at 24/16 px with a non-scaling
//                stroke and overstated the ring; these rows are faithful to the tray.
//   sprite-ring.png  12 ring frames at 64 px, 100 ms apart, cycle 2 (t 1.5 .. 2.6 s)
//   sprite-pop.png   6 entry frames at 64 px, 100 ms apart (t 0 .. 0.5 s: slide, pop)
//   poster.png       the resting ATTENTION icon at 128 px on bone
// The sprites are rendered on the dark ground for viewing; the tray draws its own tint.
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync, readdirSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-04-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
const times = ['0', '0.15', '0.3', '0.42', '0.54', '0.78', '1.02', '1.26'];
run([join(tmp, 'a'), '--times', times.join(','), '--size', '128', '--strip', join(tmp, 'row128.png')]);
run([join(tmp, 'b'), '--times', times.join(','), '--size', '64', '--query', 'theme=dark', '--upscale', '128', '--strip', join(tmp, 'row64.png')]);
// 24 and 16 px rows: downscale the 64 px sprite frames (Lanczos, like a tray/OS), then pixel-upscale to look at
const label = (src, out, text, up) => execSync(`convert "${src}" ${up ? `-filter point -resize ${up}x${up}` : ''} -background '#808080' -fill '#F0ECE6' -font DejaVu-Sans-Mono -pointsize 13 -gravity South -splice 0x20 -annotate +0+3 '${text}' "${out}"`);
for (const s of [24, 16]) {
  const outs = [];
  for (const t of times) {
    const f = join(tmp, 'b', `f-${Number(t).toFixed(2)}.png`);
    const d = join(tmp, `d${s}-${t}.png`);
    execSync(`convert "${f}" -filter Lanczos -resize ${s}x${s} "${d}"`);
    label(d, d.replace('.png', '.l.png'), `${s}px t=${Number(t).toFixed(2)}s`, 128);
    outs.push(d.replace('.png', '.l.png'));
  }
  execSync(`montage ${outs.map(o => `"${o}"`).join(' ')} -tile x1 -geometry +6+6 -background '#808080' "${join(tmp, `row${s}.png`)}"`);
}
execSync(`montage "${join(tmp, 'row128.png')}" "${join(tmp, 'row64.png')}" "${join(tmp, 'row24.png')}" "${join(tmp, 'row16.png')}" -tile 1x4 -geometry +0+0 -gravity West -background '#808080' "${join(here, 'preview.png')}"`);
// sprites: plain frames, no labels, appended edge to edge
const ringTimes = Array.from({ length: 12 }, (_, i) => (1.5 + i / 10).toFixed(2)).join(',');
run([join(tmp, 'ring'), '--times', ringTimes, '--size', '64', '--query', 'theme=dark']);
run([join(tmp, 'pop'), '--times', '0,0.1,0.2,0.3,0.4,0.5', '--size', '64', '--query', 'theme=dark']);
const frames = (d) => readdirSync(d).filter(f => /^f-[\d.]+\.png$/.test(f)).sort((a, b) => parseFloat(a.slice(2)) - parseFloat(b.slice(2))).map(f => `"${join(d, f)}"`).join(' ');
execSync(`convert ${frames(join(tmp, 'ring'))} +append "${join(here, 'sprite-ring.png')}"`);
execSync(`convert ${frames(join(tmp, 'pop'))} +append "${join(here, 'sprite-pop.png')}"`);
run([join(tmp, 'static'), '--times', '0', '--size', '128', '--query', 'static=1']);
copyFileSync(join(tmp, 'static', 'f-0.00.png'), join(here, 'poster.png'));
console.log('preview.png sprite-ring.png sprite-pop.png poster.png');
