#!/usr/bin/env node
// Full-page screenshot of an HTML file at a fixed width (tools/render.mjs is square).
//   node build/shot.mjs <file.html> <out.png> [width=1200]
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { resolve } from 'node:path';
const globalRoot = process.env.NODE_GLOBAL_ROOT || execSync('npm root -g').toString().trim();
const { chromium } = createRequire(globalRoot + '/')('playwright');

const [,, input, out, widthArg = '1200'] = process.argv;
if (!input || !out) { console.error('usage: shot.mjs <file.html> <out.png> [width]'); process.exit(2); }
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: Number(widthArg), height: 800 }, deviceScaleFactor: 1 });
  await page.goto('file://' + resolve(input), { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  await page.screenshot({ path: out, fullPage: true });
  console.log(out);
} finally {
  await browser.close();
}
