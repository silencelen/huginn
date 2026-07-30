// Parser-level port of the Android SseTest: the frame bytes are verbatim from
// the live daemon (captured 2026-07-27). Client-level behaviours (401 body
// surfacing, timeouts) are covered by the main-process client tests against the
// stub daemon; this file pins the byte→frame→event layer.

import { describe, expect, it } from 'vitest'
import { decodeChatFrame, decodeWatchItem, SseParser, type SseItem } from '../../src/shared/core/sse'
import type { ChatEvent } from '../../src/shared/api/types'

const frames = (items: SseItem[]): Extract<SseItem, { kind: 'frame' }>[] =>
  items.filter((i): i is Extract<SseItem, { kind: 'frame' }> => i.kind === 'frame')

const decodeAll = (body: string, chunkSize = body.length): ChatEvent[] => {
  const parser = new SseParser()
  const events: ChatEvent[] = []
  for (let i = 0; i < body.length; i += chunkSize) {
    for (const item of parser.push(body.slice(i, i + chunkSize))) {
      if (item.kind !== 'frame') continue
      const ev = decodeChatFrame(item.event, item.data)
      if (ev) events.push(ev)
    }
  }
  return events
}

// Verbatim from the live daemon.
const FULL_RUN =
  'id: 1\nevent: started\ndata: {"chatId":"86ed1440-e7ad-4dc4-aa2d-1d2142c570a1","ts":1785138269}\n\n' +
  'id: 2\nevent: delta\ndata: {"text":"raven check ok"}\n\n' +
  'id: 3\nevent: assistant\ndata: {"text":"raven check ok"}\n\n' +
  'id: 4\nevent: result\ndata: {"type":"result","ok":true,"durationMs":5417,"costUsd":0.46641999999999995,"turns":1,"ts":1785138276}\n\n' +
  'id: 5\nevent: done\ndata: {"exitCode":0}\n\n'

describe('SseParser + decodeChatFrame', () => {
  it('decodes a full run in order', () => {
    const events = decodeAll(FULL_RUN)
    expect(events).toHaveLength(5)
    expect(events[0]).toEqual({ type: 'started', chatId: '86ed1440-e7ad-4dc4-aa2d-1d2142c570a1' })
    expect(events[1]).toEqual({ type: 'delta', text: 'raven check ok' })
    expect(events[2]).toEqual({ type: 'assistant', text: 'raven check ok' })
    const r = events[3]!
    if (r.type !== 'result') throw new Error('expected result')
    expect(r.ok).toBe(true)
    expect(r.durationMs).toBe(5417)
    expect(r.costUsd).toBeCloseTo(0.466, 3)
    expect(events[4]).toEqual({ type: 'done' })
  })

  it('survives hostile chunk boundaries', () => {
    // Byte-at-a-time is the worst possible fragmentation; every split point is hit.
    expect(decodeAll(FULL_RUN, 1)).toEqual(decodeAll(FULL_RUN))
    expect(decodeAll(FULL_RUN, 7)).toEqual(decodeAll(FULL_RUN))
  })

  it('ignores heartbeat comments between frames', () => {
    const events = decodeAll(': ping\n\nevent: delta\ndata: {"text":"a"}\n\n: ping\n\nevent: done\ndata: {}\n\n')
    expect(events).toEqual([{ type: 'delta', text: 'a' }, { type: 'done' }])
  })

  it('surfaces comments to the caller (the watch reader needs them)', () => {
    const parser = new SseParser()
    const items = parser.push(': ka 1785138269\n\nevent: bye\ndata: {"reason":"rotate"}\n\n')
    expect(items[0]).toEqual({ kind: 'comment', text: 'ka 1785138269' })
    expect(decodeWatchItem(items[0]!)).toEqual({ type: 'alive' })
    expect(decodeWatchItem(items[1]!)).toEqual({ type: 'rotated' })
  })

  it('decodes watch state frames', () => {
    const parser = new SseParser()
    const items = parser.push(
      'event: state\ndata: {"hash":"h1","sessions":{"dev":"running"},"chats":{"c1":{"running":true,"pending":2,"finishedRuns":3}},"changed":true,"serverTime":5}\n\n',
    )
    const ev = decodeWatchItem(items[0]!)
    if (ev?.type !== 'state') throw new Error('expected state')
    expect(ev.watch.hash).toBe('h1')
    expect(ev.watch.sessions['dev']).toBe('running')
    expect(ev.watch.chats['c1']!.finishedRuns).toBe(3)
    expect(ev.watch.pushesSent).toBeNull()
  })

  it('tool frames carry name and digested input', () => {
    const events = decodeAll(
      'event: tool_start\ndata: {"name":"Bash"}\n\n' +
        'event: tool\ndata: {"type":"tool","name":"Bash","input":"df -h /"}\n\n' +
        'event: done\ndata: {}\n\n',
    )
    expect(events[0]).toEqual({ type: 'tool_start', name: 'Bash' })
    expect(events[1]).toEqual({ type: 'tool', name: 'Bash', input: 'df -h /' })
  })

  it('server error frame becomes an error event', () => {
    const events = decodeAll('event: error\ndata: {"text":"claude exited 1"}\n\nevent: done\ndata: {}\n\n')
    expect(events[0]).toEqual({ type: 'error', text: 'claude exited 1' })
  })

  it('an idle chat reports done immediately', () => {
    expect(decodeAll('event: done\ndata: {"idle":true}\n\n')).toEqual([{ type: 'done' }])
  })

  it('a stream cut mid-answer discards the unterminated frame', () => {
    const events = decodeAll('event: delta\ndata: {"text":"whole"}\n\nevent: delta\ndata: {"text":"half an ans')
    expect(events).toEqual([{ type: 'delta', text: 'whole' }])
  })

  it('handles CRLF line endings', () => {
    const events = decodeAll('event: delta\r\ndata: {"text":"a"}\r\n\r\n')
    expect(events).toEqual([{ type: 'delta', text: 'a' }])
  })

  it('joins multi-line data with newlines', () => {
    const parser = new SseParser()
    const items = frames(parser.push('data: line1\ndata: line2\n\n'))
    expect(items[0]!.data).toBe('line1\nline2')
  })

  it('carries frame ids for the ?since= resume cursor', () => {
    const parser = new SseParser()
    const items = frames(parser.push(FULL_RUN))
    expect(items.map((f) => f.id)).toEqual(['1', '2', '3', '4', '5'])
  })

  it('returns null for unknown event kinds (forward compatibility)', () => {
    expect(decodeChatFrame('shiny_new_thing', '{"x":1}')).toBeNull()
    expect(decodeChatFrame('delta', 'not json at all')).toEqual({ type: 'delta', text: '' })
  })

  it('parses a 4000-frame burst replay whole', () => {
    // The daemon replays up to 4000 buffered frames in one write on reattach.
    let body = ''
    for (let i = 1; i <= 4000; i += 1) body += `id: ${i}\nevent: delta\ndata: {"text":"${i} "}\n\n`
    body += 'event: done\ndata: {}\n\n'
    const events = decodeAll(body, 65536)
    const deltas = events.filter((e) => e.type === 'delta')
    expect(deltas).toHaveLength(4000)
    expect(deltas[0]).toEqual({ type: 'delta', text: '1 ' })
    expect(deltas[3999]).toEqual({ type: 'delta', text: '4000 ' })
    expect(events[events.length - 1]).toEqual({ type: 'done' })
  })
})
