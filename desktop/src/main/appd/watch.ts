// The watch stream: the desktop's entire delivery mechanism. The phone needs
// FCM + alarms + WorkManager because Android sleeps; a desktop keeps ONE SSE
// parked on /v1/watch?stream=1 and reconnects when it drops. `bye` is a clean
// 30-minute rotation — reconnect at once, no backoff. Real failures back off
// with jitter so a rebooting daemon isn't hammered.

import { routes } from '../../shared/api/routes'
import type { Watch } from '../../shared/api/types'
import { decodeWatchItem } from '../../shared/core/sse'
import type { AppdClient, StreamHandle } from './client'

const BACKOFF_MIN_MS = 1_000
const BACKOFF_MAX_MS = 30_000

export class WatchLoop {
  private handle: StreamHandle | null = null
  private stopped = true
  private backoffMs = BACKOFF_MIN_MS
  private reconnectTimer: NodeJS.Timeout | null = null
  private latestState: Watch | null = null
  private isConnected = false

  constructor(
    private readonly client: () => AppdClient,
    private readonly onState: (watch: Watch, connected: boolean) => void,
    private readonly onConnection: (connected: boolean) => void,
  ) {}

  latest(): Watch | null {
    return this.latestState
  }

  connected(): boolean {
    return this.isConnected
  }

  start(): void {
    if (!this.stopped) return
    this.stopped = false
    this.connect()
  }

  stop(): void {
    this.stopped = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.handle?.cancel()
    this.handle = null
    this.setConnected(false)
  }

  /** Sleep/resume and network changes call this: drop the socket, start clean. */
  reset(): void {
    if (this.stopped) return
    this.handle?.cancel()
    this.handle = null
    this.backoffMs = BACKOFF_MIN_MS
    this.connectSoon(0)
  }

  private setConnected(connected: boolean): void {
    if (this.isConnected === connected) return
    this.isConnected = connected
    this.onConnection(connected)
  }

  private connect(): void {
    if (this.stopped) return
    let rotated = false
    const handle = this.client().stream(routes.watch({ stream: true }), 'watchStream', (item) => {
      const ev = decodeWatchItem(item)
      if (!ev) return
      switch (ev.type) {
        case 'alive':
          this.setConnected(true)
          this.backoffMs = BACKOFF_MIN_MS
          break
        case 'state':
          this.setConnected(true)
          this.backoffMs = BACKOFF_MIN_MS
          this.latestState = ev.watch
          this.onState(ev.watch, true)
          break
        case 'rotated':
          rotated = true
          break
        case 'failure':
          break
      }
    })
    this.handle = handle
    void handle.done.then(({ error, notStream }) => {
      if (this.handle !== handle) return
      this.handle = null
      if (this.stopped) return
      if (rotated) {
        // Clean server-side rotation: reconnect immediately, do not back off.
        this.connectSoon(0)
        return
      }
      this.setConnected(false)
      // A 2xx that ISN'T event-stream (a captive portal splash, something else
      // answering on the port) used to reconnect with zero delay against an
      // answer that never changes — a tight loop on hotel Wi-Fi.
      const failed = error !== null || notStream !== null
      const delay = failed ? this.backoffMs : 0
      if (failed) {
        this.backoffMs = Math.min(
          BACKOFF_MAX_MS,
          Math.round(this.backoffMs * (1.6 + Math.random() * 0.4)),
        )
      }
      this.connectSoon(delay)
    })
  }

  private connectSoon(delayMs: number): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delayMs)
  }
}
