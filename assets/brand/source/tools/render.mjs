#!/usr/bin/env node
// Render an SVG (or an .html file) to PNG at one or more sizes with Chromium.
//   node render.mjs <file.svg|file.html> <out-prefix> [sizes=512,64,32,16] [bg=#E8E2DA|transparent]
// Writes <out-prefix>-<size>.png per size. For an .html file the viewport is size x size.
// Used by the brand explorations to check that a mark still reads at tray size.
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
// playwright is installed globally in this environment; resolve it from the global root.
const globalRoot = process.env.NODE_GLOBAL_ROOT || execSync('npm root -g').toString().trim();
const { chromium } = createRequire(globalRoot + '/')('playwright');
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [,, input, outPrefix, sizesArg = '512,64,32,16', bg = 'transparent'] = process.argv;
if (!input || !outPrefix) {
  console.error('usage: render.mjs <file.svg|file.html> <out-prefix> [sizes] [bg]');
  process.exit(2);
}
const sizes = sizesArg.split(',').map(Number);
const src = readFileSync(resolve(input), 'utf8');
const isSvg = /\.svg$/i.test(input);
const browser = await chromium.launch();
try {
  for (const s of sizes) {
    const page = await browser.newPage({ viewport: { width: s, height: s }, deviceScaleFactor: 1 });
    const html = isSvg
      ? `<!doctype html><html><body style="margin:0;background:${bg};width:${s}px;height:${s}px;overflow:hidden">` +
        `<div style="width:${s}px;height:${s}px;display:flex">${src.replace(/<svg([^>]*)>/i, (m, a) =>
          `<svg${a.replace(/\s(width|height)="[^"]*"/g, '')} width="${s}" height="${s}" shape-rendering="geometricPrecision">`)}</div></body></html>`
      : src;
    await page.setContent(html, { waitUntil: 'load' });
    await page.screenshot({ path: `${outPrefix}-${s}.png`, omitBackground: bg === 'transparent' });
    await page.close();
    console.log(`${outPrefix}-${s}.png`);
  }
} finally {
  await browser.close();
}
