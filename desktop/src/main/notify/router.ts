// The notification router: turns watch-digest transitions into OS
// notifications, and takes them down again when the world moves on. The two
// natures stay separate — "needs you" (a session is waiting on a question)
// versus "news" (a chat finished) — matching the phone's channel split.
//
// Detection rules ported from the Android WatchCycle/SessionWatchWorker:
// finishes are found by the finishedRuns COUNTER (an edge can be missed, a
// counter cannot); attention is the state-string edge; a question that
// disappears from the digest withdraws its notification (the phone's
// session_resolved push, derived locally here).

import { Notification } from 'electron'
import type { Watch } from '../../shared/api/types'
import { finishedSince } from '../../shared/core/watchCycle'
import type { Sessions } from '../appd/sessions'
import { buildAttentionToast, buildFinishedToast, winToastsUsable } from './toasts-win'

export interface NavTarget {
  view: 'chats' | 'sessions'
  id: string
}

export class NotifyRouter {
  private prevSessions: Record<string, string | null> | null = null
  private prevRuns: Record<string, number> = {}
  private prevRunning = new Set<string>()
  private seeded = false
  private readonly live = new Map<string, Notification>()

  constructor(
    private readonly deps: {
      sessions: Sessions
      enabled: () => boolean
      /** What the user is looking at right now, or null (window hidden/blurred). */
      focusedTarget: () => NavTarget | null
      navigate: (target: NavTarget) => void
    },
  ) {}

  onDigest(watch: Watch): void {
    if (!this.seeded) {
      // First observation is a baseline, never a wave of stale notifications.
      this.seeded = true
      this.prevSessions = watch.sessions
      this.prevRuns = this.runsOf(watch)
      this.prevRunning = this.runningOf(watch)
      return
    }

    const prevSessions = this.prevSessions ?? {}
    for (const [name, state] of Object.entries(watch.sessions)) {
      const was = prevSessions[name] ?? null
      if (state === 'attention' && was !== 'attention') void this.sessionAttention(name)
      if (state !== 'attention' && was === 'attention') this.withdraw(`sess:${name}`)
    }
    for (const name of Object.keys(prevSessions)) {
      if (!(name in watch.sessions)) this.withdraw(`sess:${name}`)
    }

    const runsNow = this.runsOf(watch)
    const runningNow = this.runningOf(watch)
    for (const chatId of finishedSince(this.prevRuns, runsNow, this.prevRunning, runningNow)) {
      this.chatFinished(chatId, watch)
    }

    this.prevSessions = watch.sessions
    this.prevRuns = runsNow
    this.prevRunning = runningNow
  }

  private runsOf(watch: Watch): Record<string, number> {
    const out: Record<string, number> = {}
    for (const [id, c] of Object.entries(watch.chats)) out[id] = c.finishedRuns
    return out
  }

  private runningOf(watch: Watch): Set<string> {
    const out = new Set<string>()
    for (const [id, c] of Object.entries(watch.chats)) if (c.running) out.add(id)
    return out
  }

  private shouldShow(target: NavTarget): boolean {
    if (!this.deps.enabled()) return false
    if (!Notification.isSupported()) return false
    const focused = this.deps.focusedTarget()
    // Nothing fires for the thing the user is already looking at.
    return !(focused !== null && focused.view === target.view && focused.id === target.id)
  }

  private async sessionAttention(name: string): Promise<void> {
    const target: NavTarget = { view: 'sessions', id: name }
    if (!this.shouldShow(target)) return
    // Enrich with the actual question, like the FCM payload did.
    let question = 'Needs you'
    let options: { number: number; label: string }[] = []
    let fingerprint: string | null = null
    try {
      const screen = await this.deps.sessions.screenOnce(name, {})
      if (screen.prompt !== null) {
        question = screen.prompt.question !== '' ? screen.prompt.question : question
        options = screen.prompt.options.map((o) => ({ number: o.number, label: o.label }))
        fingerprint = screen.prompt.fingerprint
        if (screen.prompt.multiSelect) options = [] // bounded single taps only
      }
    } catch {
      // The plain notification still carries the session name.
    }

    const n = winToastsUsable()
      ? buildAttentionToast(name, question, options, fingerprint)
      : new Notification({ title: `${name} needs you`, body: question, silent: false })
    n.on('click', () => this.deps.navigate(target))
    this.replace(`sess:${name}`, n)
  }

  private chatFinished(chatId: string, watch: Watch): void {
    const target: NavTarget = { view: 'chats', id: chatId }
    if (!this.shouldShow(target)) return
    const chat = watch.chats[chatId]
    const title = chat?.title ?? 'Chat finished'
    const body = chat?.snippet ?? ''
    const n = winToastsUsable()
      ? buildFinishedToast(chatId, title, body)
      : new Notification({ title, body, silent: true })
    n.on('click', () => this.deps.navigate(target))
    this.replace(`chat:${chatId}`, n)
  }

  private replace(key: string, n: Notification): void {
    this.withdraw(key)
    this.live.set(key, n)
    n.on('close', () => {
      if (this.live.get(key) === n) this.live.delete(key)
    })
    n.show()
  }

  /** The world moved on — the notification must not outlive the question. */
  private withdraw(key: string): void {
    const n = this.live.get(key)
    if (n) {
      this.live.delete(key)
      n.close()
    }
  }

  /** Opening a target reads as acknowledgement, like the phone. */
  onViewed(target: NavTarget): void {
    this.withdraw(target.view === 'chats' ? `chat:${target.id}` : `sess:${target.id}`)
  }
}
