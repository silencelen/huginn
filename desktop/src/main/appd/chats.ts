// Chat-run orchestration. One live SSE per running chat, owned by the main
// process and independent of any window: closing the UI must never kill a run
// (detach ≠ cancel), and a run started from one window keeps streaming when
// another subscribes. Renderer subscriptions get one snapshot (the whole
// accumulated run in a single message — a 4000-event replay must not become
// 4000 IPC sends) and then coalesced seq'd batches; a seq gap on the renderer
// side means "re-subscribe", the same recover-by-resync shape as the daemon's
// ?since= contract.

import type { WebContents } from 'electron'
import { routes } from '../../shared/api/routes'
import {
  parseChat, parseChatDetail, parseChatList, parseSuggestions, parseTranscriptPage,
  type Chat, type ChatDetail, type ChatEvent, type Suggestions, type TranscriptPage,
} from '../../shared/api/types'
import type { ChatStreamSnapshot, SendOutcome, StreamBatch } from '../../shared/ipc/contract'
import { decodeChatFrame } from '../../shared/core/sse'
import { asObj, boolOr, intOr } from '../../shared/api/json'
import type { AppdClient, StreamHandle } from './client'

const MAX_RUN_EVENTS = 4000
const FLUSH_MS = 33

interface RunState {
  running: boolean
  partialText: string
  events: ChatEvent[]
  handle: StreamHandle | null
}

interface Subscription {
  id: number
  chatId: string
  wc: WebContents
  seq: number
  pending: ChatEvent[]
  flushTimer: NodeJS.Timeout | null
  /** Kept so the listener can be removed again — see unsubscribe(). */
  onGone: () => void
}

export class Chats {
  private readonly runs = new Map<string, RunState>()
  private readonly subs = new Map<number, Subscription>()
  private readonly attaching = new Map<string, Promise<RunState | null>>()
  private nextSubId = 1

  constructor(
    private readonly client: () => AppdClient,
    /** Fired when list-level state changed (run started/finished/queued). */
    private readonly onListsChanged: () => void,
  ) {}

  async list(): Promise<Chat[]> {
    return parseChatList(await this.client().request(routes.chats()))
  }

  async create(opts: {
    mode: 'ask' | 'act'
    title?: string
    model?: string
    effort?: string
  }): Promise<Chat> {
    return parseChat(await this.client().request(routes.chats(), { method: 'POST', json: opts }))
  }

  async get(id: string): Promise<ChatDetail> {
    return parseChatDetail(await this.client().request(routes.chat(id)))
  }

  async patch(
    id: string,
    patch: { title?: string; model?: string; effort?: string; mode?: string },
  ): Promise<ChatDetail> {
    return parseChatDetail(
      await this.client().request(routes.chat(id), { method: 'PATCH', json: patch }),
    )
  }

  async delete(id: string): Promise<void> {
    await this.client().request(routes.chat(id), { method: 'DELETE' })
    this.dropRun(id)
    this.onListsChanged()
  }

  async transcript(id: string, offset: number | null): Promise<TranscriptPage> {
    return parseTranscriptPage(await this.client().request(routes.chatTranscript(id, { offset })))
  }

  async suggestions(id: string): Promise<Suggestions> {
    return parseSuggestions(
      await this.client().request(routes.chatSuggestions(id), { tier: 'longPoll' }),
    )
  }

  async cancel(id: string): Promise<void> {
    await this.client().request(routes.chatCancel(id), { method: 'POST', json: {} })
    this.onListsChanged()
  }

  /**
   * Send a message. If this process already streams the chat's run, the send
   * goes non-streaming and the daemon queues it (202 {queued, position}) — the
   * existing stream keeps being the one account of the run. Otherwise the send
   * IS the stream request.
   */
  async send(id: string, text: string): Promise<SendOutcome> {
    const run = this.runs.get(id)
    if (run?.running) {
      const body = asObj(
        await this.client().request(routes.chatMessages(id), { method: 'POST', json: { text } }),
      )
      this.onListsChanged()
      return { queued: boolOr(body.queued), position: intOr(body.position, 0) || null }
    }
    this.startRun(id, { method: 'POST', path: routes.chatMessages(id, true), json: { text } })
    this.onListsChanged()
    return { queued: false, position: null }
  }

  /**
   * If the daemon says a run is in flight but this process has no live stream
   * for it (app restarted mid-run, or the run began on the phone), reattach
   * with ?since=<seq> seeded from partialText — never both seed AND replay
   * from zero, that renders the answer twice. Returns the local run, or null
   * when nothing is running. NEVER fabricates a running state: a phantom run
   * once made every send take the queued path while nobody streamed anything.
   */
  private async attachIfRunning(chatId: string): Promise<RunState | null> {
    // Single-flight: subscribe() is called on mount, on every seq gap, and
    // after send. Two overlapping calls both used to pass the running check
    // and both opened a stream — every delta rendered twice and one SSE
    // leaked. One attach per chat at a time.
    const inFlight = this.attaching.get(chatId)
    if (inFlight) return inFlight
    const p = this.doAttach(chatId).finally(() => {
      if (this.attaching.get(chatId) === p) this.attaching.delete(chatId)
    })
    this.attaching.set(chatId, p)
    return p
  }

  private async doAttach(chatId: string): Promise<RunState | null> {
    const existing = this.runs.get(chatId)
    if (existing?.running) return existing
    const detail = await this.get(chatId)
    if (!detail.running) return this.runs.get(chatId) ?? null
    if (detail.seq !== null) {
      const run = this.freshRun(chatId, detail.partialText ?? '')
      this.startRun(chatId, { method: 'GET', path: routes.chatStream(chatId, detail.seq) }, run)
      return run
    }
    // Daemon older than 2.48.0: no seq, so the replay is the single account
    // of the text — drop the seed and take the whole buffer.
    const run = this.freshRun(chatId, '')
    this.startRun(chatId, { method: 'GET', path: routes.chatStream(chatId, 0) }, run)
    return run
  }

  /** Renderer subscription: one snapshot now, coalesced seq'd batches after. */
  async subscribe(chatId: string, wc: WebContents): Promise<ChatStreamSnapshot> {
    const run = await this.attachIfRunning(chatId)

    const id = this.nextSubId
    this.nextSubId += 1
    const onGone = (): void => this.unsubscribe(id)
    const sub: Subscription = { id, chatId, wc, seq: 0, pending: [], flushTimer: null, onGone }
    this.subs.set(id, sub)
    wc.once('destroyed', onGone)
    return {
      subscriptionId: id,
      seq: 0,
      running: run?.running ?? false,
      partialText: run?.partialText ?? '',
      events: run === null ? [] : run.events.slice(),
    }
  }

  unsubscribe(id: number): void {
    const sub = this.subs.get(id)
    if (!sub) return
    if (sub.flushTimer) clearTimeout(sub.flushTimer)
    // Every chat opened added a 'destroyed' listener that was never removed;
    // on a tray-resident process that is unbounded growth plus Node's
    // MaxListenersExceededWarning after ten.
    if (!sub.wc.isDestroyed()) sub.wc.removeListener('destroyed', sub.onGone)
    this.subs.delete(id)
    // Deliberately NOT stopping the run's SSE: detach ≠ cancel, and the run
    // must keep accumulating for notifications and the next subscriber.
  }

  private freshRun(chatId: string, partialText: string): RunState {
    const run: RunState = { running: true, partialText, events: [], handle: null }
    this.runs.set(chatId, run)
    return run
  }

  private dropRun(chatId: string): void {
    const run = this.runs.get(chatId)
    run?.handle?.cancel()
    this.runs.delete(chatId)
  }

  private startRun(
    chatId: string,
    req: { method: 'GET' | 'POST'; path: string; json?: unknown },
    existing?: RunState,
  ): void {
    const run = existing ?? this.freshRun(chatId, '')
    run.running = true
    const handle = this.client().stream(
      req.path,
      'chatStream',
      (item) => {
        if (item.kind !== 'frame') return
        const ev = decodeChatFrame(item.event, item.data)
        if (ev) this.onEvent(chatId, run, ev)
      },
      { method: req.method, json: req.json },
    )
    run.handle = handle
    void handle.done.then(({ error, notStream }) => {
      if (this.runs.get(chatId) !== run) return
      if (notStream !== null) {
        // The daemon answered plain JSON instead of SSE: a ?stream=1 send that
        // landed on a busy chat (202 {queued}). The text IS safely queued
        // server-side — drop this never-was-a-stream run and attach to the
        // real one so its events start flowing.
        this.runs.delete(chatId)
        void this.attachIfRunning(chatId)
          .then(() => this.onListsChanged())
          .catch(() => this.streamLost(chatId, run, 'could not reattach'))
        return
      }
      // ANY end while we still believe the run is live is a lost stream, not
      // just an errored one: a `done` frame truncated mid-frame is discarded
      // by the parser and the socket then closes cleanly, which used to leave
      // the run permanently "running" — every later send took the queued path
      // and the chat never streamed again until restart.
      if (run.running) {
        this.streamLost(chatId, run, error ?? 'stream ended without a result')
      }
    })
  }

  /**
   * The socket died but the run is probably still going server-side. Tell
   * subscribers so the renderer re-subscribes, which reattaches with ?since=
   * and picks the answer back up where it left off.
   */
  private streamLost(chatId: string, run: RunState, why: string): void {
    run.running = false
    run.handle = null
    this.onEvent(chatId, run, { type: 'stream_lost', text: why })
    this.onListsChanged()
  }

  /** Sleep/resume black-holes every socket: reattach anything still running. */
  resetStreams(): void {
    for (const [chatId, run] of this.runs) {
      run.handle?.cancel()
      run.handle = null
      run.running = false
      void this.attachIfRunning(chatId).catch(() => {})
    }
  }

  private onEvent(chatId: string, run: RunState, ev: ChatEvent): void {
    switch (ev.type) {
      case 'delta':
        run.partialText += ev.text
        break
      case 'assistant':
        run.partialText = ''
        break
      case 'done':
        run.running = false
        run.handle = null
        this.onListsChanged()
        break
      case 'error':
        this.onListsChanged()
        break
      default:
        break
    }
    run.events.push(ev)
    if (run.events.length > MAX_RUN_EVENTS) run.events.splice(0, run.events.length - MAX_RUN_EVENTS)
    for (const sub of this.subs.values()) {
      if (sub.chatId !== chatId) continue
      sub.pending.push(ev)
      if (sub.flushTimer === null) {
        sub.flushTimer = setTimeout(() => this.flush(sub), FLUSH_MS)
      }
    }
  }

  private flush(sub: Subscription): void {
    sub.flushTimer = null
    if (sub.pending.length === 0 || sub.wc.isDestroyed()) return
    sub.seq += 1
    const batch: StreamBatch<ChatEvent> = {
      subscriptionId: sub.id,
      seq: sub.seq,
      items: sub.pending,
    }
    sub.pending = []
    sub.wc.send('push.chatEvents', batch)
  }
}
