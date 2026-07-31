// The one HTTP door to huginn-appd. Lives in the main process on purpose: the
// daemon has no CORS (renderer fetch would preflight-fail), EventSource cannot
// carry Authorization, and the token must never reach a renderer that renders
// remote markdown and pane bytes. Hand-rolled over node:http because the
// timeout model that matters here is OkHttp's readTimeout — maximum SILENCE on
// the socket, not total duration — and node:http exposes exactly that.

import http from 'node:http'
import { SseParser, type SseItem } from '../../shared/core/sse'
import { parseApiError } from '../../shared/api/types'

/**
 * The four timeout tiers, ported from HuginnClient.kt:58-101. idleMs is the
 * longest the socket may stay silent; totalMs caps the whole call.
 *
 * - normal: ordinary request/response.
 * - longPoll: /screen?wait= and /watch?wait= park up to 150s by design, plus
 *   slow endpoints (suggestions, login, test push).
 * - chatStream: the daemon keepalives every 15-20s, so 60s of silence means a
 *   dead socket, not a thinking model.
 * - watchStream: keepalive every 25s; same reasoning.
 */
export type Tier = 'normal' | 'longPoll' | 'chatStream' | 'watchStream' | 'upload'

const TIERS: Record<Tier, { idleMs: number; totalMs: number | null }> = {
  normal: { idleMs: 30_000, totalMs: null },
  longPoll: { idleMs: 150_000, totalMs: 180_000 },
  chatStream: { idleMs: 60_000, totalMs: null },
  watchStream: { idleMs: 60_000, totalMs: null },
  // No total cap: the daemon accepts up to 128MB, which over a relayed link
  // can outlast longPoll's 180s and die mid-transfer. Idle timeout still
  // catches a genuinely dead socket.
  upload: { idleMs: 60_000, totalMs: null },
}

const CONNECT_MS = 8_000

export class HuginnHttpError extends Error {
  constructor(
    readonly status: number,
    /** The server's own {"error": ...} text — surfaced verbatim, never a generic code. */
    readonly serverError: string | null,
    /** The parsed response body, for statuses that carry structure (409 answers). */
    readonly body: unknown,
  ) {
    super(serverError ?? `HTTP ${status}`)
    this.name = 'HuginnHttpError'
  }
}

export interface ClientConfig {
  baseUrl: () => string
  token: () => string
  installId: () => string
  /**
   * Whether this client currently claims to be a notification delivery route.
   * CAREFUL: claiming suppresses the household Telegram fallback (lib/clients.js
   * appOnline). The idle-aware policy lives in notify/, not here.
   */
  notify: () => boolean
  /** Per-tier overrides — tests shrink them; a slow link could stretch them. */
  tiers?: Partial<Record<Tier, { idleMs: number; totalMs: number | null }>>
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE' | 'PATCH'
  tier?: Tier
  json?: unknown
  /** Raw request body streamed as-is (uploads). */
  bodyStream?: NodeJS.ReadableStream
  contentType?: string
  contentLength?: number
  /** Statuses (beyond 2xx) whose body is returned instead of thrown — e.g. 409 answers. */
  acceptStatuses?: number[]
}

export interface StreamHandle {
  /**
   * Resolves when the stream ends (cleanly or not). Never rejects. notStream
   * carries the parsed body when the server answered 2xx with plain JSON
   * instead of SSE — the daemon does exactly that for a ?stream=1 send that
   * lands on a busy chat (202 {queued}).
   */
  done: Promise<{ error: string | null; notStream: unknown }>
  cancel: () => void
}

export class AppdClient {
  constructor(private readonly config: ClientConfig) {}

  private tier(name: Tier): { idleMs: number; totalMs: number | null } {
    return { ...TIERS[name], ...this.config.tiers?.[name] }
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    return {
      Authorization: `Bearer ${this.config.token()}`,
      'X-Huginn-Client': this.config.installId(),
      'X-Huginn-Notify': this.config.notify() ? '1' : '0',
      ...extra,
    }
  }

  /** Buffered JSON request. Throws HuginnHttpError with the server's own error text. */
  request(path: string, opts: RequestOptions = {}): Promise<unknown> {
    const tier = this.tier(opts.tier ?? 'normal')
    const url = new URL(this.config.baseUrl() + path)
    return new Promise((resolve, reject) => {
      const headers = this.headers(
        opts.json !== undefined
          ? { 'Content-Type': 'application/json' }
          : opts.bodyStream
            ? {
                'Content-Type': opts.contentType ?? 'application/octet-stream',
                ...(opts.contentLength !== undefined
                  ? { 'Content-Length': String(opts.contentLength) }
                  : {}),
              }
            : undefined,
      )
      const req = http.request(
        url,
        { method: opts.method ?? 'GET', headers },
        (res) => {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            cleanup()
            const text = Buffer.concat(chunks).toString('utf8')
            let body: unknown = null
            if (text !== '') {
              try {
                body = JSON.parse(text)
              } catch {
                body = null
              }
            }
            const status = res.statusCode ?? 0
            const okStatus =
              (status >= 200 && status < 300) || (opts.acceptStatuses?.includes(status) ?? false)
            if (okStatus) resolve(body)
            else reject(new HuginnHttpError(status, parseApiError(body), body))
          })
          res.on('error', (e) => {
            cleanup()
            reject(e)
          })
        },
      )

      const connectTimer = setTimeout(() => {
        req.destroy(new Error('connect timeout'))
      }, CONNECT_MS)
      req.on('socket', (socket) => {
        socket.once('connect', () => clearTimeout(connectTimer))
        if (socket.readyState === 'open') clearTimeout(connectTimer)
      })
      req.setTimeout(tier.idleMs, () => req.destroy(new Error('read timeout')))
      const totalTimer =
        tier.totalMs === null
          ? null
          : setTimeout(() => req.destroy(new Error('call timeout')), tier.totalMs)
      const cleanup = (): void => {
        clearTimeout(connectTimer)
        if (totalTimer) clearTimeout(totalTimer)
      }
      req.on('error', (e) => {
        cleanup()
        reject(e)
      })

      if (opts.json !== undefined) {
        req.end(JSON.stringify(opts.json))
      } else if (opts.bodyStream) {
        opts.bodyStream.pipe(req)
        opts.bodyStream.on('error', (e) => req.destroy(e))
      } else {
        req.end()
      }
    })
  }

  /**
   * SSE request. Items go to onItem as they complete; a non-2xx status rejects
   * into `done` with the server's own error text (the 401 must say
   * "unauthorized", not "HTTP 401"). The handle's cancel() detaches without
   * killing anything server-side — closing a chat stream never cancels the run.
   */
  stream(
    path: string,
    tier: Tier,
    onItem: (item: SseItem) => void,
    opts: { method?: 'GET' | 'POST'; json?: unknown } = {},
  ): StreamHandle {
    const { idleMs } = this.tier(tier)
    const url = new URL(this.config.baseUrl() + path)
    let cancelled = false
    let reqRef: http.ClientRequest | null = null

    const done = new Promise<{ error: string | null; notStream: unknown }>((resolve) => {
      const headers = this.headers(
        opts.json !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      )
      const req = http.request(url, { method: opts.method ?? 'GET', headers }, (res) => {
        const status = res.statusCode ?? 0
        const contentType = res.headers['content-type'] ?? ''
        if (status < 200 || status >= 300 || !contentType.includes('text/event-stream')) {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            let body: unknown = null
            try {
              body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            } catch {
              body = null
            }
            if (status >= 200 && status < 300) resolve({ error: null, notStream: body ?? {} })
            else resolve({ error: parseApiError(body) ?? `HTTP ${status}`, notStream: null })
          })
          res.on('error', () => resolve({ error: `HTTP ${status}`, notStream: null }))
          return
        }
        const parser = new SseParser()
        res.setEncoding('utf8')
        res.on('data', (chunk: string) => {
          for (const item of parser.push(chunk)) onItem(item)
        })
        res.on('end', () => resolve({ error: null, notStream: null }))
        res.on('error', (e) => resolve({ error: cancelled ? null : e.message, notStream: null }))
      })
      reqRef = req

      const connectTimer = setTimeout(() => req.destroy(new Error('connect timeout')), CONNECT_MS)
      req.on('socket', (socket) => {
        socket.once('connect', () => clearTimeout(connectTimer))
        if (socket.readyState === 'open') clearTimeout(connectTimer)
      })
      req.setTimeout(idleMs, () => req.destroy(new Error('read timeout')))
      req.on('error', (e) => {
        clearTimeout(connectTimer)
        resolve({ error: cancelled ? null : e.message, notStream: null })
      })

      if (opts.json !== undefined) req.end(JSON.stringify(opts.json))
      else req.end()
    })

    return {
      done,
      cancel: () => {
        cancelled = true
        reqRef?.destroy()
      },
    }
  }
}
