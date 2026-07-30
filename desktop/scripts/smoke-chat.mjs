// End-to-end chat proof: create an ask chat, send a trivial prompt, watch the
// stream render, screenshot, then delete the chat. Costs one tiny Claude turn.
// Usage: xvfb-run -a node scripts/smoke-chat.mjs <shot-dir>

import { _electron } from 'playwright-core'

const shotDir = process.argv[2] ?? '.'
const app = await _electron.launch({ args: ['out/main/index.js', '--no-sandbox'] })
const win = await app.firstWindow()
await win.waitForSelector('.rail', { timeout: 15_000 })
await win.waitForTimeout(1_500)

await win.locator('.list-new').click()
await win.locator('.new-mode-picker button', { hasText: 'Ask' }).click()
await win.waitForSelector('.composer-input', { timeout: 10_000 })

await win.locator('.composer-input').fill('Reply with exactly the word: ok')
await win.locator('.composer-send').click()
console.log('sent; waiting for the run…')

// The working indicator should appear, then give way to the answer.
await win.waitForSelector('.working', { timeout: 20_000 })
await win.screenshot({ path: `${shotDir}/smoke-chat-running.png` })
await win.waitForSelector('.working', { state: 'detached', timeout: 180_000 })
await win.waitForTimeout(800)
await win.screenshot({ path: `${shotDir}/smoke-chat-done.png` })

const answer = await win.locator('.msg-assistant').last().innerText()
console.log('answer =', JSON.stringify(answer.slice(0, 120)))

// Clean up the throwaway chat.
win.on('dialog', (d) => void d.accept())
await win.locator('.row-active').hover()
await win.locator('.row-active .row-delete').click()
await win.waitForTimeout(1_000)

await app.close()
console.log('CHAT_SMOKE_OK')
