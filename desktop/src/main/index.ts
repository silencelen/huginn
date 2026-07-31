import {
  app, BrowserWindow, globalShortcut, ipcMain, Notification, powerMonitor, session, shell,
} from 'electron'
import path from 'node:path'
import { AppdClient } from './appd/client'
import { Chats } from './appd/chats'
import { Host } from './appd/host'
import { Sessions } from './appd/sessions'
import { WatchLoop } from './appd/watch'
import { registerIpc } from './ipc'
import { initLog, log } from './log'
import { installMenu } from './menu'
import { activationFromArgv, type Activation } from './notify/activation'
import { NotifyRouter, type NavTarget } from './notify/router'
import { Settings } from './settings'
import { AppTray } from './tray'
import { Updater } from './updater'

// A second launch focuses the existing window instead of starting a twin —
// two instances would double every poll and fight over the pane-size lease.
// It is also how a toast button's huginn:// activation reaches us on Windows.
const gotLock = app.requestSingleInstanceLock()
if (!gotLock) app.quit()

app.setAsDefaultProtocolClient('huginn')

// MUST match electron-builder's appId and the AUMID on the NSIS Start Menu
// shortcut. Without it the process publishes toasts under Electron's default
// identity, which matches no installed shortcut — and Windows then drops them
// silently. That is why no notification ever appeared in the field.
if (process.platform === 'win32') app.setAppUserModelId('com.silencelen.huginn.desktop')

let win: BrowserWindow | null = null
let quitting = false
// Read by the window-all-closed handler, which lives outside whenReady().
let closeToTray = (): boolean => true

const broadcast = (channel: string, payload: unknown): void => {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.webContents.isDestroyed()) w.webContents.send(channel, payload)
  }
}

void app.whenReady().then(() => {
  initLog()

  // Electron GRANTS permissions by default, and a renderer that displays
  // remote content should never be one prompt away from the webcam or the
  // microphone. Everything is denied EXCEPT writing to the clipboard.
  //
  // Denying the lot was the first cut, and it silently broke every copy in
  // the app: navigator.clipboard.writeText rejected, the terminal's Ctrl+C
  // caught the rejection and did nothing, and the selection staying
  // highlighted made it look like it had worked. Copying out is the whole
  // point of a transcript you read.
  const ALLOWED = new Set(['clipboard-sanitized-write'])
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) =>
    callback(ALLOWED.has(permission)),
  )
  session.defaultSession.setPermissionCheckHandler((_wc, permission) => ALLOWED.has(permission))

  const settings = new Settings()
  closeToTray = () => settings.getCloseToTray()
  const client = new AppdClient({
    baseUrl: () => settings.getBaseUrl(),
    token: () => settings.getToken(),
    installId: () => settings.getInstallId(),
    // The claim policy: only a desktop someone is actually AT counts as a
    // delivery route. An always-on machine claiming while nobody sits there
    // would silently mute the household Telegram fallback (lib/clients.js).
    notify: () =>
      settings.getNotifyEnabled() &&
      Notification.isSupported() &&
      powerMonitor.getSystemIdleTime() < 600,
  })
  const getClient = (): AppdClient => client

  const chats = new Chats(getClient, () => broadcast('push.listsChanged', {}))
  const sessions = new Sessions(getClient)
  const host = new Host(getClient)
  const updater = new Updater(settings, broadcast)

  let focusedTarget: NavTarget | null = null

  const showWindow = (): void => {
    if (win === null) createWindow()
    else {
      if (win.isMinimized()) win.restore()
      win.show()
      win.focus()
    }
  }

  const navigate = (target: NavTarget): void => {
    showWindow()
    broadcast('push.navigate', target)
    router.onViewed(target)
  }

  const router = new NotifyRouter({
    sessions,
    enabled: () => settings.getNotifyEnabled(),
    focusedTarget: () => (win !== null && win.isFocused() ? focusedTarget : null),
    navigate,
  })

  const tray = new AppTray({
    openApp: showWindow,
    openTarget: (view, id) => navigate({ view, id }),
    quit: () => {
      quitting = true
      app.quit()
    },
  })

  const watch = new WatchLoop(
    getClient,
    (watchState, connected) => {
      broadcast('push.watch', { watch: watchState, connected })
      router.onDigest(watchState)
      tray.onDigest(watchState)
    },
    (connected) => broadcast('push.watch', { watch: null, connected }),
  )

  const handleActivation = (activation: Activation): void => {
    if (activation.kind === 'open' && activation.view !== undefined && activation.id !== undefined) {
      navigate({ view: activation.view, id: activation.id })
      return
    }
    if (activation.kind === 'answer' && activation.session !== undefined) {
      // Answer from the toast, then say what actually happened — a 409 means
      // the question moved on, which was still a correct tap when offered.
      void sessions
        .answer(activation.session, {
          option: activation.option ?? 1,
          ...(activation.fingerprint != null ? { fingerprint: activation.fingerprint } : {}),
        })
        .then((result) => {
          new Notification({
            title: result.ok ? 'Answered' : 'Not answered',
            body: result.ok
              ? `${activation.session}: option ${activation.option}`
              : `${activation.session}: ${result.reason === 'gone' ? 'the question is gone' : result.reason === 'changed' ? 'the question changed' : (result.error ?? 'failed')}`,
            silent: true,
          }).show()
        })
        .catch(() => {})
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
    win.on('close', (e) => {
      // Close-to-tray: the watch stream and notifications live on.
      if (!quitting && settings.getCloseToTray()) {
        e.preventDefault()
        win?.hide()
      }
    })
    win.on('closed', () => {
      win = null
    })

    // The renderer shows remote content (markdown, tool output, pane text);
    // navigation and window-opening are denied wholesale. Vetted http(s)
    // links go to the system browser.
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (/^https?:\/\//.test(url)) void shell.openExternal(url)
      return { action: 'deny' }
    })
    win.webContents.on('will-navigate', (e, url) => {
      e.preventDefault()
      if (/^https?:\/\//.test(url)) void shell.openExternal(url)
    })

    const devUrl = process.env['ELECTRON_RENDERER_URL']
    if (devUrl) void win.loadURL(devUrl)
    else void win.loadFile(path.join(import.meta.dirname, '../renderer/index.html'))
  }

  app.on('second-instance', (_event, argv) => {
    const activation = activationFromArgv(argv)
    if (activation !== null) handleActivation(activation)
    else showWindow()
  })
  app.on('open-url', (_event, url) => {
    const activation = activationFromArgv([url])
    if (activation !== null) handleActivation(activation)
  })

  // Anything that fails where nobody is looking lands here and in the log, so
  // the diagnostics blob can answer "what went wrong" without an SSH session.
  let lastError: string | null = null
  const noteError = (area: string, e: unknown): void => {
    const msg = e instanceof Error ? e.message : String(e)
    lastError = `${new Date().toISOString()} ${area}: ${msg}`
    log('error', area, msg)
  }
  process.on('uncaughtException', (e) => noteError('uncaught', e))
  process.on('unhandledRejection', (e) => noteError('unhandled-rejection', e))

  registerIpc({
    settings,
    client: getClient,
    chats,
    sessions,
    host,
    watch,
    updater,
    lastError: () => lastError,
  })
  ipcMain.handle('app:version', () => app.getVersion())
  ipcMain.handle('ui.viewed', (_event, target: NavTarget | null) => {
    focusedTarget = target
    if (target !== null) router.onViewed(target)
  })

  // A real menu replaces Electron's default, which bound Ctrl+R (reload —
  // orphaning main-side subscriptions and stranding the tmux size lease) and
  // Ctrl+W (destroy the window). Neither role is defined here, so the
  // renderer no longer has to swallow them, and Ctrl+W becomes close-to-tray.
  installMenu({
    showWindow,
    hideWindow: () => win?.hide(),
    openPalette: () => {
      showWindow()
      broadcast('push.command', { command: 'palette' })
    },
    openSettings: () => {
      showWindow()
      broadcast('push.command', { command: 'settings' })
    },
    openStatus: () => {
      showWindow()
      broadcast('push.command', { command: 'status' })
    },
    newChat: (mode) => {
      showWindow()
      broadcast('push.command', { command: mode === 'act' ? 'new-act' : 'new-ask' })
    },
    checkForUpdates: () => updater.check(),
    copyDiagnostics: () => {
      showWindow()
      broadcast('push.command', { command: 'diagnostics' })
    },
    quit: () => {
      quitting = true
      app.quit()
    },
  })

  watch.start()
  updater.start()
  tray.start()

  globalShortcut.register('CommandOrControl+Shift+H', () => {
    if (win !== null && win.isVisible() && win.isFocused()) win.hide()
    else showWindow()
  })

  // Sleep black-holes every socket at once; on resume nothing errors, it just
  // hangs until the idle timeouts fire (up to 180s of frozen pane, and a chat
  // run that never reports finishing). Reset all three proactively.
  const resumeAll = (): void => {
    watch.reset()
    chats.resetStreams()
    sessions.resetPolls()
  }
  powerMonitor.on('resume', resumeAll)
  powerMonitor.on('unlock-screen', resumeAll)

  // The notify claim rides on request headers, but a parked SSE re-stamps its
  // CONNECT-time header on every keepalive — so walking away from the desk
  // left the daemon believing this desktop was still a delivery route (and
  // holding the household Telegram fallback) until the 30-minute rotation.
  // Reconnect the watch stream when the idle state crosses the threshold, so
  // the claim it carries is the true one.
  let wasIdle = false
  setInterval(() => {
    const idleNow = powerMonitor.getSystemIdleTime() >= 600
    if (idleNow !== wasIdle) {
      wasIdle = idleNow
      log('info', 'notify', `claim now ${idleNow ? 'off (idle)' : 'on (at the keyboard)'}`)
      watch.reset()
    }
  }, 60_000)

  app.on('before-quit', () => {
    quitting = true
  })
  app.on('will-quit', (event) => {
    // Leases die with us gracefully rather than stranding tmux windows at
    // desktop geometry until the daemon's sweeper notices.
    event.preventDefault()
    globalShortcut.unregisterAll()
    tray.destroy()
    void sessions.releaseAllLeases().finally(() => {
      watch.stop()
      app.exit(0)
    })
  })

  const startupActivation = activationFromArgv(process.argv)
  createWindow()
  if (startupActivation !== null) handleActivation(startupActivation)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  // The tray keeps the app alive ONLY when the user asked for that. With
  // close-to-tray off this used to keep running headless forever — window
  // gone, watch stream and notify claim still live.
  if (quitting || !closeToTray()) app.quit()
})
