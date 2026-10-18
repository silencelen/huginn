#!/usr/bin/env node
// capture.mjs — rebuild the review artefacts of anim-01-blink. Run: node capture.mjs
//   preview.png   row 1: six frames of anim.html, 60 ms apart, across one blink (close
//                 100 ms / hold 60 ms / open 160 ms), 256 px on bone
//                 row 2: the same six frames on the app's dark ground (bone ink)
//                 row 3: the closed-eye frame zoomed 4x on both grounds (a ring here would
//                 mean the lid does not cover the hole's anti-aliased rim) and the resting
//                 state with animations disabled at 256 / 64 / 16 px
//   poster.png    the resting state (animations disabled) at 256 px on bone
//   fallback.gif  one 12 s loop at 25 fps (256 px, bone); unchanged frames cost almost nothing
import { execFileSync, execSync } from 'node:child_process';
import { mkdtempSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cap = join(here, '..', 'capture.mjs');
const html = join(here, 'anim.html');
const tmp = mkdtempSync(join(tmpdir(), 'huginn-anim-01-'));
const run = (args) => execFileSync('node', [cap, html, ...args], { stdio: 'inherit' });
const six = '0,0.06,0.12,0.18,0.24,0.30';
run([join(tmp, 'light'), '--times', six, '--size', '256', '--strip', join(tmp, 'row-light.png')]);
run([join(tmp, 'dark'), '--times', six, '--size', '256', '--query', 'theme=dark', '--strip', join(tmp, 'row-dark.png')]);
run([join(tmp, 'z'), '--times', '0.12', '--size', '512']);
run([join(tmp, 'zd'), '--times', '0.12', '--size', '512', '--query', 'theme=dark']);
for (const s of [256, 64, 16]) run([join(tmp, 'static' + s), '--times', '0', '--size', String(s), '--query', 'static=1']);
const label = (src, out, text, up, crop) => execSync(`convert "${src}" ${crop ? `-crop ${crop} +repage` : ''} ${up ? `-filter point -resize ${up}x${up}` : ''} -background '#808080' -fill '#F0ECE6' -font DejaVu-Sans-Mono -pointsize 13 -gravity South -splice 0x20 -annotate +0+3 '${text}' "${out}"`);
label(join(tmp, 'z', 'f-0.12.png'), join(tmp, 'z.png'), 'closed, eye at 512 px x4', 256, '64x64+284+142');
label(join(tmp, 'zd', 'f-0.12.png'), join(tmp, 'zd.png'), 'closed, dark, x4', 256, '64x64+284+142');
label(join(tmp, 'static256', 'f-0.00.png'), join(tmp, 's256.png'), 'resting 256', 0);
label(join(tmp, 'static64', 'f-0.00.png'), join(tmp, 's64.png'), 'resting 64', 128);
label(join(tmp, 'static16', 'f-0.00.png'), join(tmp, 's16.png'), 'resting 16', 128);
execSync(`montage "${join(tmp, 'z.png')}" "${join(tmp, 'zd.png')}" "${join(tmp, 's256.png')}" "${join(tmp, 's64.png')}" "${join(tmp, 's16.png')}" -tile x1 -geometry +6+6 -background '#808080' "${join(tmp, 'row-3.png')}"`);
execSync(`montage "${join(tmp, 'row-light.png')}" "${join(tmp, 'row-dark.png')}" "${join(tmp, 'row-3.png')}" -tile 1x3 -geometry +0+0 -gravity West -background '#808080' "${join(here, 'preview.png')}"`);
copyFileSync(join(tmp, 'static256', 'f-0.00.png'), join(here, 'poster.png'));
const times = Array.from({ length: 300 }, (_, i) => (i * 0.04).toFixed(2)).join(',');
run([join(tmp, 'gif'), '--times', times, '--size', '256']);
execSync(`cd "${join(tmp, 'gif')}" && ls f-*.png | sort -t- -k2 -n | awk '{printf "file %s\\nduration 0.04\\n", $0}' > list.txt && ffmpeg -y -loglevel error -f concat -safe 0 -i list.txt -vf "fps=25,split[a][b];[a]palettegen=max_colors=32:stats_mode=diff[p];[b][p]paletteuse=dither=none" -loop 0 "${join(here, 'fallback.gif')}"`);
console.log('preview.png poster.png fallback.gif');
