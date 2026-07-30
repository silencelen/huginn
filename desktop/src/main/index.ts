import { app, BrowserWindow, ipcMain, powerMonitor, shell } from 'electron'
import path from 'node:path'
import { AppdClient } from './appd/client'
import { Chats } from './appd/chats'
import { Host } from './appd/host'
import { Sessions } from './appd/sessions'
import { WatchLoop } from './appd/watch'
import { registerIpc } from './ipc'
import { Settings } from './settings'
import { Updater } from './updater'

// A second launch focuses the existing window instead of starting a twin —
// two instances would double every poll and fight over the pane-size lease.
const gotLock = app.requestSingleInstanceLock()
if (!gotLock) app.quit()

let win: BrowserWindow | null = null

const broadcast = (channel: string, payload: unknown): void => {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.webContents.isDestroyed()) w.webContents.send(channel, payload)
  }
}

function createWindow(): void {
  win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#101418',
    webPreferences: {
      preload: path.join(import.meta.dirname, '../preload/index.cjs'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  })

  win.on('ready-to-show', () => win?.show())
  win.on('closed', () => {
    win = null
  })

  // The renderer shows remote content (markdown, tool output, pane text);
  // navigation and window-opening are denied wholesale. Vetted http(s) links
  // go to the system browser.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e) => e.preventDefault())

  const devUrl = process.env['ELECTRON_RENDERER_URL']
  if (devUrl) void win.loadURL(devUrl)
  else void win.loadFile(path.join(import.meta.dirname, '../renderer/index.html'))
}

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore()
    win.focus()
  }
})

void app.whenReady().then(() => {
  const settings = new Settings()
  const client = new AppdClient({
    baseUrl: () => settings.getBaseUrl(),
    token: () => settings.getToken(),
    installId: () => settings.getInstallId(),
    // Phase 3 replaces this with the idle-aware claim policy. Until the
    // desktop can actually SHOW notifications it must not claim to be a
    // delivery route, or it would silently mute the household Telegram
    // fallback for everyone.
    notify: () => false,
  })
  const getClient = (): AppdClient => client

  const chats = new Chats(getClient, () => broadcast('push.listsChanged', {}))
  const sessions = new Sessions(getClient)
  const host = new Host(getClient)
  const watch = new WatchLoop(
    getClient,
    (watchState, connected) => broadcast('push.watch', { watch: watchState, connected }),
    (connected) => broadcast('push.watch', { watch: null, connected }),
  )

  const updater = new Updater(settings, broadcast)

  registerIpc({ settings, client: getClient, chats, sessions, host, watch, updater })
  ipcMain.handle('app:version', () => app.getVersion())

  watch.start()
  updater.start()

  // Sleep black-holes every socket at once; on resume nothing errors, it just
  // hangs until the idle timeouts fire. Reset proactively instead.
  powerMonitor.on('resume', () => watch.reset())
  powerMonitor.on('unlock-screen', () => watch.reset())

  app.on('will-quit', (event) => {
    // Leases die with us gracefully rather than stranding tmux windows at
    // desktop geometry until the daemon's sweeper notices.
    event.preventDefault()
    void sessions.releaseAllLeases().finally(() => {
      watch.stop()
      app.exit(0)
    })
  })

  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  // Tray-resident behaviour arrives in phase 3; until then, quit like a normal app.
  if (process.platform !== 'darwin') app.quit()
})
