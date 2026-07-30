// The tray: the desktop analog of the phone's ongoing-notification summary.
// Icons are synthesized dots (no asset pipeline for three colored circles):
// gray = idle, blue = working, amber = a session needs you.

import { Menu, nativeImage, Tray } from 'electron'
import type { Watch } from '../shared/api/types'

type TrayState = 'idle' | 'working' | 'attention'

const COLORS: Record<TrayState, [number, number, number]> = {
  idle: [123, 135, 148],
  working: [122, 162, 247],
  attention: [224, 175, 104],
}

function dotIcon(state: TrayState, size = 16): Electron.NativeImage {
  const [r, g, b] = COLORS[state]
  const buf = Buffer.alloc(size * size * 4)
  const c = (size - 1) / 2
  const rad = size * 0.38
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const d = Math.sqrt((x - c) ** 2 + (y - c) ** 2)
      // Soft edge: one pixel of alpha falloff so it doesn't look like a die pip.
      const a = d <= rad ? 255 : d <= rad + 1 ? Math.round(255 * (rad + 1 - d)) : 0
      const i = (y * size + x) * 4
      buf[i] = b
      buf[i + 1] = g
      buf[i + 2] = r
      buf[i + 3] = a
    }
  }
  return nativeImage.createFromBitmap(buf, { width: size, height: size })
}

export class AppTray {
  private tray: Tray | null = null
  private state: TrayState = 'idle'

  constructor(
    private readonly deps: {
      openApp: () => void
      openTarget: (view: 'chats' | 'sessions', id: string) => void
      quit: () => void
    },
  ) {}

  start(): void {
    this.tray = new Tray(dotIcon('idle'))
    this.tray.setToolTip('Huginn')
    this.tray.on('click', () => this.deps.openApp())
    this.rebuildMenu([], 0, 0)
  }

  onDigest(watch: Watch): void {
    if (this.tray === null) return
    const attention = Object.entries(watch.sessions)
      .filter(([, state]) => state === 'attention')
      .map(([name]) => name)
    const runningSessions = Object.values(watch.sessions).filter((s) => s === 'running').length
    const runningChats = Object.values(watch.chats).filter((c) => c.running).length
    const working = runningSessions + runningChats

    const next: TrayState = attention.length > 0 ? 'attention' : working > 0 ? 'working' : 'idle'
    if (next !== this.state) {
      this.state = next
      this.tray.setImage(dotIcon(next))
    }
    const parts: string[] = []
    if (attention.length > 0) parts.push(`${attention.length} need${attention.length === 1 ? 's' : ''} you`)
    if (working > 0) parts.push(`${working} working`)
    this.tray.setToolTip(parts.length > 0 ? `Huginn — ${parts.join(' · ')}` : 'Huginn — idle')
    this.rebuildMenu(attention, working, runningChats)
  }

  private rebuildMenu(attention: string[], working: number, runningChats: number): void {
    if (this.tray === null) return
    const template: Electron.MenuItemConstructorOptions[] = [
      { label: 'Open Huginn', click: () => this.deps.openApp() },
    ]
    if (attention.length > 0) {
      template.push({ type: 'separator' })
      for (const name of attention.slice(0, 6)) {
        template.push({
          label: `⚠ ${name} needs you`,
          click: () => this.deps.openTarget('sessions', name),
        })
      }
    }
    if (working > 0) {
      template.push({ type: 'separator' })
      template.push({
        label: `${working} working${runningChats > 0 ? ` (${runningChats} chats)` : ''}`,
        enabled: false,
      })
    }
    template.push({ type: 'separator' }, { label: 'Quit Huginn', click: () => this.deps.quit() })
    this.tray.setContextMenu(Menu.buildFromTemplate(template))
  }

  destroy(): void {
    this.tray?.destroy()
    this.tray = null
  }
}
