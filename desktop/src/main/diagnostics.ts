// The "Copy diagnostics" payload: everything worth pasting into a chat when
// something looks wrong, and nothing that shouldn't leave the machine. The
// token, the log's own scrubbing, and any URL credentials are excluded by
// construction — this text is meant to be shared.

import { app, Notification, powerMonitor } from 'electron'
import os from 'node:os'
import { logPath, logText } from './log'
import type { Settings } from './settings'
import type { UpdateState } from './updater'

export interface DiagnosticsInput {
  settings: Settings
  update: UpdateState
  watchConnected: boolean
  appdVersion: string | null
  lastError: string | null
}

export function buildDiagnostics(input: DiagnosticsInput): string {
  const s = input.settings.view()
  const mem = process.memoryUsage()
  const lines = [
    `# Huginn Desktop diagnostics`,
    `generated       ${new Date().toISOString()}`,
    ``,
    `## App`,
    `version         ${app.getVersion()}`,
    `packaged        ${app.isPackaged}`,
    `electron        ${process.versions['electron'] ?? '?'}`,
    `chrome          ${process.versions['chrome'] ?? '?'}`,
    `platform        ${process.platform} ${process.arch} (${os.release()})`,
    `uptime          ${Math.round(process.uptime())}s`,
    `heap            ${Math.round(mem.heapUsed / 1024 / 1024)}MB used / ${Math.round(mem.rss / 1024 / 1024)}MB rss`,
    `idle            ${powerMonitor.getSystemIdleTime()}s`,
    ``,
    `## Connection`,
    `server          ${s.baseUrl}`,
    `token           ${s.hasToken ? 'set' : 'MISSING'}${s.tokenPlaintextFallback ? ' (plaintext fallback — no OS keyring)' : ''}`,
    `install id      ${s.installId}`,
    `watch stream    ${input.watchConnected ? 'connected' : 'DISCONNECTED'}`,
    `appd version    ${input.appdVersion ?? 'unknown'}`,
    ``,
    `## Notifications`,
    `enabled         ${s.notifyEnabled}`,
    `os support      ${Notification.isSupported()}`,
    `claiming route  ${s.notifyEnabled && Notification.isSupported() && powerMonitor.getSystemIdleTime() < 600}`,
    ``,
    `## Update`,
    `status          ${input.update.status}`,
    `version         ${input.update.version ?? '-'}`,
    `error           ${input.update.error ?? '-'}`,
    ``,
    `## Last error`,
    input.lastError ?? '-',
    ``,
    `## Log (${logPath() ?? 'file unavailable'})`,
    logText() || '(empty)',
  ]
  return lines.join('\n')
}
