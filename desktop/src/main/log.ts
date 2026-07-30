// A small ring-buffer log for the main process, mirrored to a file. The app
// had no logging at all, which was fine under a headless test harness and
// wrong for a daily driver on a machine nobody can reach: every field
// question so far ("did the updater run?", "why did the stream drop?") took
// an SSH session to answer. Now the answers ride along with the app, and
// Settings can copy them into a chat.
//
// Deliberately NOT a general logger: no levels beyond these, no rotation
// beyond truncation, no dependency. Secrets never reach it — call sites pass
// facts, and `scrub` is a second line of defence.

import { app } from 'electron'
import fs from 'node:fs'
import path from 'node:path'

const MAX_LINES = 500
const MAX_FILE_BYTES = 512 * 1024

export type LogKind = 'info' | 'warn' | 'error'

interface Entry {
  at: number
  kind: LogKind
  area: string
  message: string
}

const ring: Entry[] = []
let file: string | null = null

/** Never let a token reach the log, whatever a call site does. */
const scrub = (s: string): string =>
  s
    .replace(/Bearer\s+[\w.\-]+/gi, 'Bearer <redacted>')
    .replace(/[a-f0-9]{32,}/gi, '<hex-redacted>')

export function initLog(): void {
  try {
    const dir = app.getPath('userData')
    fs.mkdirSync(dir, { recursive: true })
    file = path.join(dir, 'huginn-desktop.log')
    // Truncate rather than rotate: this is a debugging aid, not an audit log.
    if (fs.existsSync(file) && fs.statSync(file).size > MAX_FILE_BYTES) fs.rmSync(file)
  } catch {
    file = null
  }
  log('info', 'app', `started v${app.getVersion()} on ${process.platform}`)
}

export function log(kind: LogKind, area: string, message: string): void {
  const entry: Entry = { at: Date.now(), kind, area, message: scrub(message) }
  ring.push(entry)
  if (ring.length > MAX_LINES) ring.splice(0, ring.length - MAX_LINES)
  if (file !== null) {
    const line = `${new Date(entry.at).toISOString()} ${kind.toUpperCase().padEnd(5)} ${area} ${entry.message}\n`
    try {
      fs.appendFileSync(file, line)
    } catch {
      // A log that cannot write must never break the app.
    }
  }
}

export function logPath(): string | null {
  return file
}

/** The whole ring as text — what the Settings "Copy diagnostics" button sends. */
export function logText(): string {
  return ring
    .map(
      (e) =>
        `${new Date(e.at).toISOString()} ${e.kind.toUpperCase().padEnd(5)} ${e.area} ${e.message}`,
    )
    .join('\n')
}
