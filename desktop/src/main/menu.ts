// A real application menu, replacing Electron's default one.
//
// The default menu is why the renderer had to swallow keys: it binds Ctrl+R
// (reload — which orphaned main-side subscriptions and stranded the tmux size
// lease) and Ctrl+W (close). Not defining those roles is the durable fix, and
// it frees Ctrl+W to mean close-to-tray and lets Ctrl+R reach tmux as C-r.
//
// The menu bar stays hidden (autoHideMenuBar); this exists for its
// accelerators, its right-click-free discoverability via Alt, and the macOS
// application menu, which is not optional there.

import { app, Menu, shell } from 'electron'

export interface MenuActions {
  showWindow: () => void
  hideWindow: () => void
  openPalette: () => void
  openSettings: () => void
  openStatus: () => void
  newChat: (mode: 'ask' | 'act') => void
  checkForUpdates: () => void
  copyDiagnostics: () => void
  quit: () => void
}

export function installMenu(actions: MenuActions): void {
  const isMac = process.platform === 'darwin'

  const template: Electron.MenuItemConstructorOptions[] = [
    ...(isMac
      ? [
          {
            label: app.name,
            submenu: [
              { role: 'about' as const },
              { type: 'separator' as const },
              { label: 'Settings…', accelerator: 'Cmd+,', click: actions.openSettings },
              { type: 'separator' as const },
              { role: 'hide' as const },
              { role: 'hideOthers' as const },
              { type: 'separator' as const },
              { label: 'Quit Huginn', accelerator: 'Cmd+Q', click: actions.quit },
            ],
          },
        ]
      : []),
    {
      label: '&File',
      submenu: [
        {
          label: 'New Ask chat',
          accelerator: 'CmdOrCtrl+N',
          click: () => actions.newChat('ask'),
        },
        {
          label: 'New Act chat',
          accelerator: 'CmdOrCtrl+Shift+N',
          click: () => actions.newChat('act'),
        },
        { type: 'separator' },
        ...(isMac
          ? []
          : [
              {
                label: 'Settings',
                accelerator: 'CmdOrCtrl+,',
                click: actions.openSettings,
              },
            ]),
        {
          // Ctrl+W is close-to-tray rather than "destroy the window", because
          // the watch stream and notifications are the point of staying alive.
          label: 'Close to tray',
          accelerator: 'CmdOrCtrl+W',
          click: actions.hideWindow,
        },
        ...(isMac ? [] : [{ label: 'Quit Huginn', click: actions.quit }]),
      ],
    },
    {
      label: '&Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
    {
      label: '&View',
      submenu: [
        { label: 'Find anything…', accelerator: 'CmdOrCtrl+K', click: actions.openPalette },
        { label: 'Status', accelerator: 'CmdOrCtrl+3', click: actions.openStatus },
        { type: 'separator' },
        // Zoom is safe; reload/forceReload/toggleDevTools are deliberately absent.
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: '&Help',
      submenu: [
        // No F1 item: the renderer owns that key for the cheat sheet, and a
        // menu accelerator would take it before the window ever sees it.
        { label: 'Check for updates', click: actions.checkForUpdates },
        { label: 'Copy diagnostics', click: actions.copyDiagnostics },
        { type: 'separator' },
        {
          label: 'Open the log folder',
          click: () => void shell.openPath(app.getPath('userData')),
        },
      ],
    },
  ]

  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}
