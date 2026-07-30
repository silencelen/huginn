// Auto-update against huginn-appd's /v1/desktop channel. electron-updater's
// generic provider fetches latest.yml (latest-linux.yml on AppImage) and the
// installer from the same Bearer-authed daemon the app already talks to.
// Differential download stays off so the daemon never needs Range support.

import { app, type WebContents } from 'electron'
import electronUpdater from 'electron-updater'
import type { Settings } from './settings'

const { autoUpdater } = electronUpdater

const CHECK_EVERY_MS = 4 * 60 * 60 * 1000

export interface UpdateState {
  status: 'none' | 'checking' | 'available' | 'downloading' | 'ready' | 'error'
  version: string | null
  error: string | null
}

export class Updater {
  private state: UpdateState = { status: 'none', version: null, error: null }
  private timer: NodeJS.Timeout | null = null

  constructor(
    private readonly settings: Settings,
    private readonly broadcast: (channel: string, payload: unknown) => void,
  ) {}

  current(): UpdateState {
    return this.state
  }

  start(): void {
    // Dev builds have no update identity; the deb has no updater either
    // (electron-updater auto-updates NSIS and AppImage only).
    if (!app.isPackaged) return
    if (process.platform === 'linux' && process.env['APPIMAGE'] === undefined) return

    autoUpdater.autoDownload = true
    autoUpdater.autoInstallOnAppQuit = true
    autoUpdater.disableDifferentialDownload = true
    autoUpdater.setFeedURL({
      provider: 'generic',
      url: `${this.settings.getBaseUrl()}/v1/desktop`,
      useMultipleRangeRequest: false,
    })
    autoUpdater.addAuthHeader(`Bearer ${this.settings.getToken()}`)

    autoUpdater.on('checking-for-update', () => this.set({ status: 'checking' }))
    autoUpdater.on('update-available', (info) =>
      this.set({ status: 'downloading', version: info.version }),
    )
    autoUpdater.on('update-not-available', () => this.set({ status: 'none', version: null }))
    autoUpdater.on('update-downloaded', (info) =>
      this.set({ status: 'ready', version: info.version }),
    )
    autoUpdater.on('error', (e) => this.set({ status: 'error', error: e.message }))

    this.check()
    this.timer = setInterval(() => this.check(), CHECK_EVERY_MS)
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  check(): void {
    if (!app.isPackaged) return
    void autoUpdater.checkForUpdates().catch(() => {
      // Reported through the 'error' event; a dead daemon must not crash us.
    })
  }

  install(): void {
    autoUpdater.quitAndInstall()
  }

  private set(patch: Partial<UpdateState>): void {
    this.state = {
      status: patch.status ?? this.state.status,
      version: patch.version !== undefined ? patch.version : this.state.version,
      error: patch.error ?? null,
    }
    this.broadcast('push.update', this.state)
  }
}

/** Test hook (unused in prod): lets suites observe broadcast wiring cheaply. */
export type { WebContents }
