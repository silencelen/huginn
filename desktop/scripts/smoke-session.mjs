// Session transcript smoke: open the Sessions tab, click into a session, let
// the Conversation tab poll the live daemon, screenshot, and assert that at
// least one transcript row rendered. The first list row may be a session that
// never prompted Claude, so a few rows are tried before giving up.
// Usage: xvfb-run -a node scripts/smoke-session.mjs <shot-dir>

import { _electron } from 'playwright-core'

const shotDir = process.argv[2] ?? '.'
const app = await _electron.launch({ args: ['out/main/index.js', '--no-sandbox'] })
const win = await app.firstWindow()
await win.waitForSelector('.rail', { timeout: 15_000 })

await win.locator('.rail-item', { hasText: 'Sessions' }).click()
await win.waitForSelector('.list-pane .row', { timeout: 15_000 })
const rowCount = await win.locator('.list-pane .row').count()
console.log('session rows =', rowCount)

let transcriptRows = 0
for (let i = 0; i < Math.min(rowCount, 4); i++) {
  await win.locator('.list-pane .row').nth(i).click()
  await win.waitForTimeout(4_000)
  transcriptRows = await win.locator('.transcript-row').count()
  console.log(`row ${i}: transcript rows = ${transcriptRows}`)
  if (transcriptRows > 0) break
}

await win.screenshot({ path: `${shotDir}/smoke-conversation.png` })

if (transcriptRows < 1) {
  console.error('NO TRANSCRIPT ROWS RENDERED')
  await app.close()
  process.exit(1)
}

await app.close()
console.log('SESSION_SMOKE_OK')
