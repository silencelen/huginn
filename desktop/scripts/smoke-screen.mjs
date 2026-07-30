// Headless smoke for the terminal Screen tab: launch the built app under xvfb,
// open the first real session, switch to Screen, let the poll deliver frames,
// screenshot for eyeball review, and assert the canvas actually has pixels.
// Usage: xvfb-run -a node scripts/smoke-screen.mjs <shot-dir>

import { _electron } from 'playwright-core'

const shotDir = process.argv[2] ?? '.'
const app = await _electron.launch({ args: ['out/main/index.js', '--no-sandbox'] })
const win = await app.firstWindow()

await win.waitForSelector('.rail', { timeout: 15_000 })
console.log('shell rendered; title =', await win.title())

await win.locator('.rail-item', { hasText: 'Sessions' }).click()
await win.waitForTimeout(2_000)
const rows = await win.locator('.list-pane .row').count()
console.log('session rows =', rows)
if (rows === 0) throw new Error('no sessions to open — smoke needs a live tmux session')

await win.locator('.list-pane .row').first().click()
await win.waitForTimeout(1_000)
// The tab switch is the house `.seg` control (it was `.tab-switch` until the
// 0.2.0 header rework; that class is dead CSS now).
await win.locator('.tab-seg .seg-btn', { hasText: 'Screen' }).click()

// Let the fit report geometry and the long-poll deliver a frame or two.
await win.waitForTimeout(5_000)
await win.screenshot({ path: `${shotDir}/smoke-screen.png` })

const size = await win
  .locator('canvas.term-canvas')
  .last()
  .evaluate((c) => ({ w: c.width, h: c.height, cssW: c.style.width, cssH: c.style.height }))
console.log('canvas size =', size)
if (!(size.w > 0 && size.h > 0)) throw new Error('terminal canvas has zero size')

await app.close()
console.log('SMOKE_SCREEN_OK')
