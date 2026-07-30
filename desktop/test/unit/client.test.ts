// Client-level port of the Android SseTest's transport behaviours, against an
// in-process node:http stub: header contract, the server's own error text on a
// 401, mid-answer cuts, and the silence-means-dead read timeout.

import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AppdClient, HuginnHttpError } from '../../src/main/appd/client'
import { decodeChatFrame } from '../../src/shared/core/sse'
import type { ChatEvent } from '../../src/shared/api/types'

type Handler = (req: http.IncomingMessage, res: http.ServerResponse) => void

let server: http.Server
let handler: Handler
let baseUrl: string

beforeEach(async () => {
  handler = (_req, res) => res.end()
  server = http.createServer((req, res) => handler(req, res))
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
})

afterEach(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()))
})

const client = (notify = false): AppdClient =>
  new AppdClient({
    baseUrl: () => baseUrl,
    token: () => 'test-token',
    installId: () => 'install-1',
    notify: () => notify,
    tiers: {
      chatStream: { idleMs: 250, totalMs: null },
      normal: { idleMs: 500, totalMs: null },
    },
  })

const collectChat = async (
  c: AppdClient,
  path: string,
  opts?: { method?: 'GET' | 'POST'; json?: unknown },
): Promise<{ events: ChatEvent[]; error: string | null }> => {
  const events: ChatEvent[] = []
  const handle = c.stream(path, 'chatStream', (item) => {
    if (item.kind !== 'frame') return
    const ev = decodeChatFrame(item.event, item.data)
    if (ev) events.push(ev)
  }, opts)
  const { error } = await handle.done
  return { events, error }
}

describe('AppdClient', () => {
  it('sends the bearer token, client id, and notify claim on every request', async () => {
    let seen: http.IncomingHttpHeaders = {}
    handler = (req, res) => {
      seen = req.headers
      res.setHeader('Content-Type', 'application/json')
      res.end('{"ok":true}')
    }
    await client(true).request('/v1/ping')
    expect(seen.authorization).toBe('Bearer test-token')
    expect(seen['x-huginn-client']).toBe('install-1')
    expect(seen['x-huginn-notify']).toBe('1')
    await client(false).request('/v1/ping')
    expect(seen['x-huginn-notify']).toBe('0')
  })

  it('streams a full run in order over POST ?stream=1', async () => {
    let method = ''
    let path = ''
    handler = (req, res) => {
      method = req.method ?? ''
      path = req.url ?? ''
      res.setHeader('Content-Type', 'text/event-stream')
      res.write('event: delta\ndata: {"text":"raven check ok"}\n\n')
      res.write('event: done\ndata: {"exitCode":0}\n\n')
      res.end()
    }
    const { events, error } = await collectChat(client(), '/v1/chats/abc/messages?stream=1', {
      method: 'POST',
      json: { text: 'hi' },
    })
    expect(method).toBe('POST')
    expect(path).toBe('/v1/chats/abc/messages?stream=1')
    expect(error).toBeNull()
    expect(events).toEqual([{ type: 'delta', text: 'raven check ok' }, { type: 'done' }])
  })

  it('a 401 surfaces the servers own error text, not a generic code', async () => {
    handler = (_req, res) => {
      res.statusCode = 401
      res.setHeader('Content-Type', 'application/json')
      res.end('{"error":"unauthorized"}')
    }
    const { events, error } = await collectChat(client(), '/v1/chats/abc/stream?since=0')
    expect(events).toEqual([])
    expect(error).toBe('unauthorized')
  })

  it('a stream cut mid-answer keeps earlier events and completes', async () => {
    handler = (_req, res) => {
      res.setHeader('Content-Type', 'text/event-stream')
      res.write('event: delta\ndata: {"text":"whole"}\n\n', () => {
        res.write('event: delta\ndata: {"text":"half an ans', () => {
          res.socket?.destroy()
        })
      })
    }
    const { events } = await collectChat(client(), '/v1/chats/abc/stream?since=0')
    expect(events).toEqual([{ type: 'delta', text: 'whole' }])
  })

  it('kills a silent stream at the tier read timeout', async () => {
    handler = (_req, res) => {
      res.setHeader('Content-Type', 'text/event-stream')
      res.write('event: delta\ndata: {"text":"a"}\n\n')
      // ...then silence. The daemon keepalives every 15-20s, so silence past the
      // tier's idleMs means a dead path, and the reader must not hang forever.
    }
    const start = Date.now()
    const { events, error } = await collectChat(client(), '/v1/chats/abc/stream?since=0')
    expect(events).toEqual([{ type: 'delta', text: 'a' }])
    expect(error).toContain('read timeout')
    expect(Date.now() - start).toBeGreaterThanOrEqual(200)
    expect(Date.now() - start).toBeLessThan(2000)
  })

  it('cancel detaches without reporting an error', async () => {
    handler = (_req, res) => {
      res.setHeader('Content-Type', 'text/event-stream')
      res.write('event: delta\ndata: {"text":"a"}\n\n')
    }
    const handle = client().stream('/v1/chats/abc/stream?since=0', 'chatStream', () => {})
    await new Promise((r) => setTimeout(r, 50))
    handle.cancel()
    const { error } = await handle.done
    expect(error).toBeNull()
  })

  it('request throws HuginnHttpError carrying the server text and parsed body', async () => {
    handler = (_req, res) => {
      res.statusCode = 409
      res.setHeader('Content-Type', 'application/json')
      res.end('{"error":"question changed","reason":"changed"}')
    }
    const err = await client()
      .request('/v1/sessions/dev/answer', { method: 'POST', json: { option: 1 } })
      .then(() => null)
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(HuginnHttpError)
    const httpErr = err as HuginnHttpError
    expect(httpErr.status).toBe(409)
    expect(httpErr.serverError).toBe('question changed')
    expect((httpErr.body as { reason: string }).reason).toBe('changed')
  })

  it('acceptStatuses returns the body instead of throwing', async () => {
    handler = (_req, res) => {
      res.statusCode = 409
      res.setHeader('Content-Type', 'application/json')
      res.end('{"ok":false,"reason":"gone"}')
    }
    const body = (await client().request('/v1/sessions/dev/answer', {
      method: 'POST',
      json: { option: 1 },
      acceptStatuses: [409],
    })) as { reason: string }
    expect(body.reason).toBe('gone')
  })

  it('posts JSON bodies the server can read back', async () => {
    let received = ''
    handler = (req, res) => {
      let buf = ''
      req.on('data', (c: Buffer) => {
        buf += c.toString('utf8')
      })
      req.on('end', () => {
        received = buf
        res.end('{"ok":true}')
      })
    }
    await client().request('/v1/sessions', { method: 'POST', json: { name: 'dev' } })
    expect(JSON.parse(received)).toEqual({ name: 'dev' })
  })
})
