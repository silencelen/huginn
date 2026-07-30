// Headless smoke: launch the built app under xvfb, wait for the shell, poke
// the real daemon through the real IPC path, screenshot for eyeball review.
// Usage: xvfb-run -a node scripts/smoke.mjs <shot-dir>

import { _electron } from 'playwright-core'

const shotDir = process.argv[2] ?? '.'
const app = await _electron.launch({ args: ['out/main/index.js', '--no-sandbox'] })
const win = await app.firstWindow()

await win.waitForSelector('.rail', { timeout: 15_000 })
console.log('shell rendered; title =', await win.title())

// Give the store its first refresh cycle against the live daemon.
await win.waitForTimeout(2_500)
const chatRows = await win.locator('.list-pane .row').count()
console.log('chat rows =', chatRows)
await win.screenshot({ path: `${shotDir}/smoke-chats.png` })

await win.locator('.rail-item', { hasText: 'Sessions' }).click()
await win.waitForTimeout(1_500)
console.log('session rows =', await win.locator('.list-pane .row').count())
await win.screenshot({ path: `${shotDir}/smoke-sessions.png` })

await win.locator('.rail-item', { hasText: 'Status' }).click()
await win.waitForTimeout(2_500)
await win.screenshot({ path: `${shotDir}/smoke-status.png` })

await win.locator('.rail-item', { hasText: 'Settings' }).click()
await win.waitForTimeout(800)
await win.screenshot({ path: `${shotDir}/smoke-settings.png` })

await app.close()
console.log('SMOKE_OK')
