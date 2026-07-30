// Auto-update against huginn-appd's /v1/desktop channel. electron-updater's
// generic provider fetches latest.yml (latest-linux.yml on AppImage) and the
// installer from the same Bearer-authed daemon the app already talks to.
// Differential download stays off so the daemon never needs Range support.

import { app, type WebContents } from 'electron'
import electronUpdater from 'electron-updater'
import { log } from './log'
import type { Settings } from './settings'

const { autoUpdater } = electronUpdater

const CHECK_EVERY_MS = 4 * 60 * 60 * 1000

/**
 * The update feed is PINNED, deliberately not derived from the (user-editable)
 * baseUrl setting. These builds are unsigned, so electron-updater performs no
 * publisher check and the sha512 it verifies comes from the same latest.yml it
 * just downloaded — meaning whoever controls the feed URL controls what code
 * gets installed. baseUrl is now allowlisted too, but the update path must not
 * depend on that one check holding.
 */
const FEED_URL = 'http://100.97.198.90:8787/v1/desktop'

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
    // Closes the second attacker-controlled URL in the download path.
    autoUpdater.disableWebInstaller = true
    autoUpdater.setFeedURL({
      provider: 'generic',
      url: FEED_URL,
      useMultipleRangeRequest: false,
    })

    autoUpdater.on('checking-for-update', () => this.set({ status: 'checking' }))
    autoUpdater.on('update-available', (info) => {
      log('info', 'update', `available: ${info.version}`)
      this.set({ status: 'downloading', version: info.version })
    })
    autoUpdater.on('update-not-available', () => this.set({ status: 'none', version: null }))
    autoUpdater.on('update-downloaded', (info) => {
      log('info', 'update', `downloaded ${info.version}, will install on quit`)
      this.set({ status: 'ready', version: info.version })
    })
    autoUpdater.on('error', (e) => {
      log('error', 'update', e.message)
      this.set({ status: 'error', error: e.message })
    })

    this.check()
    this.timer = setInterval(() => this.check(), CHECK_EVERY_MS)
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  check(): void {
    if (!app.isPackaged) return
    // Re-arm the token on EVERY check rather than once at start(). Found in
    // the field: on a fresh install the app launches before the owner has
    // pasted a token, so the header was armed with "Bearer " and every later
    // check 401'd for the life of the process — self-update silently dead
    // until a restart, and the same bug on any token rotation.
    const token = this.settings.getToken()
    if (token === '') {
      log('warn', 'update', 'skipped: no token yet')
      return
    }
    autoUpdater.addAuthHeader(`Bearer ${token}`)
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
