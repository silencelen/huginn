#!/usr/bin/env node
// capture.mjs — rebuild the review artefacts of anim-02-assemble. Run: node capture.mjs
//   preview.png   row 1: six frames on bone, 350 ms apart, 0.25 s to 2.00 s (256 px)
//                 row 2: the same six frames on the app's dark ground (bone ink)
//                 row 3: the RESTING state (animations disabled, what a thumbnailer or
//                        librsvg shows) at 256 / 64 / 32 / 16 px, small ones pixel-upscaled,
//                        then the live first frame (t=0.00: empty by design)
//   poster.png    the resting state at 256 px on bone, for surfaces that cannot animate
//   fallback.gif  the 2 s assembly + 1.5 s hold as a 25 fps GIF (256 px, bone), for
//                 surfaces that rasterise SVG without CSS animation (Slack, npm, social cards)
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-02-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
const six = '0.25,0.6,0.95,1.3,1.65,2.0';
run([join(tmp, 'light'), '--times', six, '--size', '256', '--strip', join(tmp, 'row-light.png')]);
run([join(tmp, 'dark'), '--times', six, '--size', '256', '--query', 'theme=dark', '--strip', join(tmp, 'row-dark.png')]);
for (const s of [256, 64, 32, 16]) run([join(tmp, 'static' + s), '--times', '0', '--size', String(s), '--query', 'static=1']);
run([join(tmp, 'first'), '--times', '0', '--size', '256']);
const label = (src, out, text, up) => execSync(`convert "${src}" ${up ? `-filter point -resize ${up}x${up}` : ''} -background '#808080' -fill '#F0ECE6' -font DejaVu-Sans-Mono -pointsize 13 -gravity South -splice 0x20 -annotate +0+3 '${text}' "${out}"`);
label(join(tmp, 'static256', 'f-0.00.png'), join(tmp, 's256.png'), 'resting 256', 0);
label(join(tmp, 'static64', 'f-0.00.png'), join(tmp, 's64.png'), 'resting 64', 128);
label(join(tmp, 'static32', 'f-0.00.png'), join(tmp, 's32.png'), 'resting 32', 128);
label(join(tmp, 'static16', 'f-0.00.png'), join(tmp, 's16.png'), 'resting 16', 128);
label(join(tmp, 'first', 'f-0.00.png'), join(tmp, 'first.png'), 'live t=0.00s (empty by design)', 0);
execSync(`montage "${join(tmp, 's256.png')}" "${join(tmp, 's64.png')}" "${join(tmp, 's32.png')}" "${join(tmp, 's16.png')}" "${join(tmp, 'first.png')}" -tile x1 -geometry +6+6 -background '#808080' "${join(tmp, 'row-static.png')}"`);
execSync(`montage "${join(tmp, 'row-light.png')}" "${join(tmp, 'row-dark.png')}" "${join(tmp, 'row-static.png')}" -tile 1x3 -geometry +0+0 -gravity West -background '#808080' "${join(here, 'preview.png')}"`);
copyFileSync(join(tmp, 'static256', 'f-0.00.png'), join(here, 'poster.png'));
// GIF fallback: 50 frames at 25 fps + hold, palette-optimised by ffmpeg
const times = Array.from({ length: 51 }, (_, i) => (i * 0.04).toFixed(2)).join(',');
run([join(tmp, 'gif'), '--times', times, '--size', '256']);
execSync(`cd "${join(tmp, 'gif')}" && ls f-*.png | sort -t- -k2 -n | awk '{printf "file %s\\nduration 0.04\\n", $0} END {printf "file %s\\nduration 1.5\\n", $0}' > list.txt && ffmpeg -y -loglevel error -f concat -safe 0 -i list.txt -vf "fps=25,split[a][b];[a]palettegen=max_colors=32:stats_mode=diff[p];[b][p]paletteuse=dither=none" -loop 0 "${join(here, 'fallback.gif')}"`);
console.log('preview.png poster.png fallback.gif');
