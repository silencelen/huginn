// Session (tmux) operations + the screen long-poll loops. Each open Screen view
// holds one subscription; the loop parks on ?hash=&wait=25000 and pushes frames
// last-write-wins. Reporting cols/rows leases a manual window size on the
// daemon (90s, renewed by the polling itself), so stopping a subscription MUST
// release the lease — a stranded lease leaves the tmux window stuck at desktop
// size for everyone else.

import type { WebContents } from 'electron'
import { routes } from '../../shared/api/routes'
import {
  parseAgentsInfo, parseAnswerResult, parseScreen, parseSessionList, parseSuggestions,
  parseTranscriptPage, type AgentsInfo, type AnswerResult, type Screen, type Session,
  type Suggestions, type TranscriptPage,
} from '../../shared/api/types'
import { HuginnHttpError, type AppdClient } from './client'

const WAIT_MS = 25_000
const ERROR_BACKOFF_MIN_MS = 1_000
const ERROR_BACKOFF_MAX_MS = 15_000

interface ScreenSub {
  id: number
  name: string
  wc: WebContents
  cols: number | null
  rows: number | null
  hash: string | null
  active: boolean
  timer: NodeJS.Timeout | null
  backoffMs: number
}

export class Sessions {
  private readonly screenSubs = new Map<number, ScreenSub>()
  private nextSubId = 1

  constructor(private readonly client: () => AppdClient) {}

  /** null = tmux unobservable (503) — deliberately distinct from "no sessions". */
  async list(): Promise<Session[] | null> {
    try {
      return parseSessionList(await this.client().request(routes.sessions(true)))
    } catch (e) {
      if (e instanceof HuginnHttpError && e.status === 503) return null
      throw e
    }
  }

  async create(name: string): Promise<void> {
    await this.client().request(routes.sessions(), { method: 'POST', json: { name } })
  }

  async kill(name: string): Promise<void> {
    await this.client().request(routes.session(name), { method: 'DELETE' })
  }

  async rename(name: string, to: string): Promise<void> {
    await this.client().request(routes.sessionRename(name), { method: 'POST', json: { name: to } })
  }

  async transcript(name: string, offset: number | null): Promise<TranscriptPage> {
    return parseTranscriptPage(
      await this.client().request(routes.sessionTranscript(name, { offset })),
    )
  }

  async suggestions(name: string): Promise<Suggestions> {
    return parseSuggestions(
      await this.client().request(routes.sessionSuggestions(name), { tier: 'longPoll' }),
    )
  }

  async agents(name: string): Promise<AgentsInfo> {
    return parseAgentsInfo(await this.client().request(routes.sessionAgents(name)))
  }

  async keys(name: string, body: { text?: string; keys?: string[] }): Promise<void> {
    await this.client().request(routes.sessionKeys(name), { method: 'POST', json: body })
  }

  /**
   * Check-and-act answer. A 409 is an ordinary outcome (reason gone|changed):
   * the click was right when it was offered — report it, never retry it.
   */
  async answer(
    name: string,
    body: { option?: number; options?: number[]; fingerprint?: string },
  ): Promise<AnswerResult> {
    return parseAnswerResult(
      await this.client().request(routes.sessionAnswer(name), {
        method: 'POST',
        json: body,
        acceptStatuses: [409],
      }),
    )
  }

  /** One-shot screen fetch (scrollback loads etc.) — no lease, no loop. */
  async screenOnce(name: string, opts: { history?: number }): Promise<Screen> {
    return parseScreen(
      await this.client().request(routes.screen(name, { history: opts.history }), {
        tier: 'longPoll',
      }),
    )
  }

  async releaseSize(name: string): Promise<void> {
    try {
      await this.client().request(routes.sessionSize(name), { method: 'DELETE' })
    } catch {
      // The daemon sweeps stranded leases anyway; failing to release must not
      // block whatever the caller is tearing down.
    }
  }

  startScreenPoll(name: string, wc: WebContents, opts: { cols?: number; rows?: number }): number {
    const id = this.nextSubId
    this.nextSubId += 1
    const sub: ScreenSub = {
      id,
      name,
      wc,
      cols: opts.cols ?? null,
      rows: opts.rows ?? null,
      hash: null,
      active: true,
      timer: null,
      backoffMs: ERROR_BACKOFF_MIN_MS,
    }
    this.screenSubs.set(id, sub)
    wc.once('destroyed', () => void this.stopScreenPoll(id))
    void this.pollLoop(sub)
    return id
  }

  async stopScreenPoll(id: number): Promise<void> {
    const sub = this.screenSubs.get(id)
    if (!sub) return
    sub.active = false
    if (sub.timer) clearTimeout(sub.timer)
    this.screenSubs.delete(id)
    // Release the lease only when no other live subscription still views this
    // session — two windows on one pane share the lease.
    const stillViewed = [...this.screenSubs.values()].some(
      (s) => s.name === sub.name && (s.cols !== null || s.rows !== null),
    )
    if (!stillViewed && (sub.cols !== null || sub.rows !== null)) {
      await this.releaseSize(sub.name)
    }
  }

  releaseAllLeases(): Promise<void[]> {
    const names = new Set(
      [...this.screenSubs.values()]
        .filter((s) => s.cols !== null || s.rows !== null)
        .map((s) => s.name),
    )
    return Promise.all([...names].map((n) => this.releaseSize(n)))
  }

  private async pollLoop(sub: ScreenSub): Promise<void> {
    while (sub.active) {
      try {
        const screen = parseScreen(
          await this.client().request(
            routes.screen(sub.name, {
              cols: sub.cols ?? undefined,
              rows: sub.rows ?? undefined,
              hash: sub.hash,
              waitMs: WAIT_MS,
            }),
            { tier: 'longPoll' },
          ),
        )
        sub.backoffMs = ERROR_BACKOFF_MIN_MS
        if (!sub.active) break
        if (screen.unchanged) continue
        sub.hash = screen.hash
        if (!sub.wc.isDestroyed()) {
          sub.wc.send('push.screen', { subscriptionId: sub.id, screen })
        }
      } catch (e) {
        if (!sub.active) break
        if (e instanceof HuginnHttpError && e.status === 404) {
          // Session died under the viewer; the renderer navigates back off the
          // sessions list update — stop looping rather than 404 forever.
          break
        }
        await new Promise<void>((resolve) => {
          sub.timer = setTimeout(resolve, sub.backoffMs)
        })
        sub.backoffMs = Math.min(ERROR_BACKOFF_MAX_MS, sub.backoffMs * 2)
      }
    }
  }
}
