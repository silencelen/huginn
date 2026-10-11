#!/usr/bin/env node
// Shared frame capturer for the anim-vector explorations.
//   node capture.mjs <anim.html> <out-dir> --times 0,0.2,0.4 [--size 256] [--w W --h H] [--query "theme=dark"] [--strip strip.png] [--scale 1]
// Loads the page with Chromium, pauses every animation, seeks each one to t seconds
// via window.seek(t) (every anim.html defines it), screenshots, and tiles the frames
// into one labelled strip with ImageMagick. Frames are written as <out-dir>/f-<t>.png.
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { resolve, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
const globalRoot = process.env.NODE_GLOBAL_ROOT || execSync('npm root -g').toString().trim();
const { chromium } = createRequire(globalRoot + '/')('playwright');

const args = process.argv.slice(2);
const pos = []; const opt = {};
for (let i = 0; i < args.length; i++) {
  if (args[i].startsWith('--')) { opt[args[i].slice(2)] = args[i + 1]; i++; } else pos.push(args[i]);
}
const [input, outDir] = pos;
if (!input || !outDir || !opt.times) {
  console.error('usage: capture.mjs <anim.html> <out-dir> --times 0,0.5,1 [--size 256] [--w W --h H] [--query q] [--strip out.png] [--scale n] [--upscale px] [--tile 3x2]');
  process.exit(2);
}
const times = opt.times.split(',').map(Number);
const size = Number(opt.size || 256);
const w = Number(opt.w || size), h = Number(opt.h || size);
const scale = Number(opt.scale || 1);
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const frames = [];
try {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  // freeze=1 makes the page start with every animation paused (see wrap.mjs), so the
  // compositor never holds a stale running-state frame when we seek
  const url = pathToFileURL(resolve(input)).href + '?freeze=1' + (opt.query ? '&' + opt.query : '');
  await page.goto(url, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts && document.fonts.ready);
  for (const t of times) {
    const n = await page.evaluate((t) => window.seek(t), t);
    // composited transform/opacity animations apply a seek on the next compositor frame;
    // wait two frames so the screenshot never catches the pre-seek state
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    const f = resolve(outDir, `f-${t.toFixed(2)}.png`);
    await page.screenshot({ path: f });
    frames.push({ t, f, n });
    console.log(`${f} (${n} animations)`);
  }
} finally {
  await browser.close();
}
if (opt.strip) {
  // Label each frame with its time, optionally pixel-upscale (for 16/24 px tray checks), then tile.
  const labelled = frames.map(({ t, f }) => {
    const out = f.replace(/\.png$/, '.l.png');
    const up = opt.upscale ? `-filter point -resize ${opt.upscale}x${opt.upscale}` : '';
    execSync(`convert "${f}" ${up} -background '#808080' -fill '#F0ECE6' -font DejaVu-Sans-Mono -pointsize 13 -gravity South -splice 0x20 -annotate +0+3 't=${t.toFixed(2)}s' "${out}"`);
    return out;
  });
  execSync(`montage ${labelled.map(f => `"${f}"`).join(' ')} -tile ${opt.tile || 'x1'} -geometry +6+6 -background '#808080' "${opt.strip}"`);
  console.log(opt.strip);
}
