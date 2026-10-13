#!/usr/bin/env node
// Render compare/in-context.html with Chromium:
//   in-context.png     the whole sheet, 2400 px wide, deviceScaleFactor 1
//   in-context-2x.png  rows 3-6 (tray, favicon, notification, title bar) at deviceScaleFactor 2, so the 16 px forms can be inspected
// Usage: node compare/shoot.mjs   (from assets/brand/explorations, or anywhere: paths are resolved from this file)
// Needs the global playwright (npm root -g) and its Chromium; quantise with ImageMagick afterwards if a PNG passes 1.5 MB.
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { statSync } from 'node:fs';

const here = dirname(fileURLToPath(import.meta.url));
const globalRoot = process.env.NODE_GLOBAL_ROOT || execSync('npm root -g').toString().trim();
const { chromium } = createRequire(globalRoot + '/')('playwright');

const html = pathToFileURL(resolve(here, 'in-context.html')).href;
const browser = await chromium.launch({ args: ['--allow-file-access-from-files'] });
try {
  async function open(dsf) {
    const page = await browser.newPage({ viewport: { width: 2400, height: 1200 }, deviceScaleFactor: dsf });
    await page.goto(html, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    return page;
  }
  const p1 = await open(1);
  const out1 = resolve(here, 'in-context.png');
  await p1.screenshot({ path: out1, fullPage: true });
  await p1.close();
  console.log(out1, statSync(out1).size, 'bytes');

  const p2 = await open(2);
  const out2 = resolve(here, 'in-context-2x.png');
  await p2.locator('#small-rows').screenshot({ path: out2 });
  await p2.close();
  console.log(out2, statSync(out2).size, 'bytes');
} finally {
  await browser.close();
}
