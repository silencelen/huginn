// Settings live in userData/config.json. The appd token is the one secret: it
// is stored through safeStorage (DPAPI on Windows, Keychain on mac, libsecret
// on Linux) and falls back to plaintext WITH A VISIBLE FLAG when no keyring
// exists — pretending to encrypt would be worse than saying so.

import { app, safeStorage } from 'electron'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

export interface SettingsView {
  baseUrl: string
  hasToken: boolean
  tokenPlaintextFallback: boolean
  installId: string
  notifyEnabled: boolean
  launchAtLogin: boolean
  closeToTray: boolean
  terminalFontPx: number
}

interface StoredSettings {
  baseUrl: string
  tokenEncrypted: string | null
  tokenPlain: string | null
  installId: string
  notifyEnabled: boolean
  launchAtLogin: boolean
  closeToTray: boolean
  terminalFontPx: number
  drafts: Record<string, string>
}

const DEFAULTS: StoredSettings = {
  baseUrl: 'http://100.97.198.90:8787',
  tokenEncrypted: null,
  tokenPlain: null,
  installId: '',
  notifyEnabled: true,
  launchAtLogin: false,
  closeToTray: true,
  terminalFontPx: 14,
  drafts: {},
}

export class Settings {
  private state: StoredSettings
  private readonly file: string
  private token = ''

  constructor() {
    this.file = path.join(app.getPath('userData'), 'config.json')
    this.state = { ...DEFAULTS }
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8')) as Partial<StoredSettings>
      this.state = { ...DEFAULTS, ...raw }
    } catch {
      // First run.
    }
    if (this.state.installId === '') {
      this.state.installId = `desktop-${randomUUID()}`
      this.save()
    }
    this.token = this.decryptToken()
    if (this.token === '') this.bootstrapDevToken()
  }

  private decryptToken(): string {
    if (this.state.tokenEncrypted !== null && safeStorage.isEncryptionAvailable()) {
      try {
        return safeStorage.decryptString(Buffer.from(this.state.tokenEncrypted, 'base64'))
      } catch {
        return ''
      }
    }
    return this.state.tokenPlain ?? ''
  }

  /**
   * Dev convenience only: when running unpackaged ON huginn itself, the daemon
   * token is readable at its canonical path — use it rather than making the
   * developer paste a token into their own machine.
   */
  private bootstrapDevToken(): void {
    if (app.isPackaged) return
    try {
      const t = fs.readFileSync('/etc/huginn-appd/token', 'utf8').trim()
      if (t.length >= 32) this.setToken(t)
    } catch {
      // Not on huginn; the settings screen will ask.
    }
  }

  private save(): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    const tmp = `${this.file}.tmp`
    fs.writeFileSync(tmp, JSON.stringify(this.state, null, 1), { mode: 0o600 })
    fs.renameSync(tmp, this.file)
  }

  view(): SettingsView {
    return {
      baseUrl: this.state.baseUrl,
      hasToken: this.token !== '',
      tokenPlaintextFallback: this.token !== '' && this.state.tokenPlain !== null,
      installId: this.state.installId,
      notifyEnabled: this.state.notifyEnabled,
      launchAtLogin: this.state.launchAtLogin,
      closeToTray: this.state.closeToTray,
      terminalFontPx: this.state.terminalFontPx,
    }
  }

  getBaseUrl(): string {
    return this.state.baseUrl
  }

  getToken(): string {
    return this.token
  }

  getInstallId(): string {
    return this.state.installId
  }

  getNotifyEnabled(): boolean {
    return this.state.notifyEnabled
  }

  getCloseToTray(): boolean {
    return this.state.closeToTray
  }

  setToken(token: string): void {
    this.token = token.trim()
    if (safeStorage.isEncryptionAvailable()) {
      this.state.tokenEncrypted = safeStorage.encryptString(this.token).toString('base64')
      this.state.tokenPlain = null
    } else {
      this.state.tokenEncrypted = null
      this.state.tokenPlain = this.token
    }
    this.save()
  }

  update(patch: {
    baseUrl?: string
    token?: string
    notifyEnabled?: boolean
    launchAtLogin?: boolean
    closeToTray?: boolean
    terminalFontPx?: number
  }): SettingsView {
    if (patch.token !== undefined) this.setToken(patch.token)
    if (patch.baseUrl !== undefined) this.state.baseUrl = patch.baseUrl.trim().replace(/\/+$/, '')
    if (patch.notifyEnabled !== undefined) this.state.notifyEnabled = patch.notifyEnabled
    if (patch.launchAtLogin !== undefined) {
      this.state.launchAtLogin = patch.launchAtLogin
      app.setLoginItemSettings({ openAtLogin: patch.launchAtLogin })
    }
    if (patch.closeToTray !== undefined) this.state.closeToTray = patch.closeToTray
    if (patch.terminalFontPx !== undefined) this.state.terminalFontPx = patch.terminalFontPx
    this.save()
    return this.view()
  }

  /** Drafts persist per target ('chat:<id>' / 'sess:<name>'), like the phone. */
  getDraft(key: string): string {
    return this.state.drafts[key] ?? ''
  }

  setDraft(key: string, text: string): void {
    if (text === '') delete this.state.drafts[key]
    else this.state.drafts[key] = text
    this.save()
  }
}
