// Incremental SSE parser for huginn-appd's three streaming endpoints. Hand
// rolled (~100 lines) because the daemon's dialect is small and the two
// consumers disagree about comments: the chat reader skips them as noise, the
// watch reader treats one as proof the path is open. The parser surfaces both
// and lets each reader decide.

import { asObj, bool, boolOr, int, num, str, strOr } from '../api/json'
import { parseWatch, type ChatEvent, type WatchEvent } from '../api/types'

export type SseItem =
  | { kind: 'frame'; event: string | null; id: string | null; data: string }
  | { kind: 'comment'; text: string }

export class SseParser {
  private buf = ''
  private event: string | null = null
  private id: string | null = null
  private dataLines: string[] = []

  /**
   * Feed a chunk, get back every item it completed. A frame is dispatched only
   * at its terminating blank line — a stream cut mid-frame therefore discards
   * the partial frame, which is the correct reading of a dropped link.
   */
  push(chunk: string): SseItem[] {
    this.buf += chunk
    const items: SseItem[] = []
    let nl: number
    while ((nl = this.buf.indexOf('\n')) !== -1) {
      let line = this.buf.slice(0, nl)
      this.buf = this.buf.slice(nl + 1)
      if (line.endsWith('\r')) line = line.slice(0, -1)
      const item = this.line(line)
      if (item) items.push(item)
    }
    return items
  }

  private line(line: string): SseItem | null {
    if (line === '') {
      // Dispatch boundary. An empty data buffer (e.g. after a comment) resets
      // the frame state without emitting anything, per the SSE spec.
      if (this.dataLines.length === 0 && this.event === null) return null
      const item: SseItem = {
        kind: 'frame',
        event: this.event,
        id: this.id,
        data: this.dataLines.join('\n'),
      }
      this.event = null
      this.id = null
      this.dataLines = []
      return item
    }
    if (line.startsWith(':')) {
      return { kind: 'comment', text: line.slice(1).trimStart() }
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        this.event = value
        break
      case 'data':
        this.dataLines.push(value)
        break
      case 'id':
        this.id = value
        break
      default:
        // Unknown fields are ignored, per spec — forward compatibility.
        break
    }
    return null
  }
}

const json = (data: string): Record<string, unknown> => {
  try {
    return asObj(JSON.parse(data))
  } catch {
    return {}
  }
}

/**
 * Decode one chat-run frame. Returns null for unknown event kinds so a newer
 * daemon can add frames without breaking an older client.
 */
export const decodeChatFrame = (event: string | null, data: string): ChatEvent | null => {
  const o = json(data)
  switch (event) {
    case 'started':
      return { type: 'started', chatId: strOr(o.chatId) }
    case 'delta':
      return { type: 'delta', text: strOr(o.text) }
    case 'assistant':
      return { type: 'assistant', text: strOr(o.text) }
    case 'tool_start':
      return { type: 'tool_start', name: strOr(o.name) }
    case 'tool':
      return { type: 'tool', name: strOr(o.name), input: str(o.input) }
    case 'result':
      return {
        type: 'result',
        ok: boolOr(o.ok),
        durationMs: int(o.durationMs),
        costUsd: num(o.costUsd),
      }
    case 'error':
      return { type: 'error', text: strOr(o.text) }
    case 'done':
      return { type: 'done' }
    default:
      return null
  }
}

/**
 * Decode one watch-stream item. A comment IS the payload here: it carries
 * nothing but the fact that the path is still open.
 */
export const decodeWatchItem = (item: SseItem): WatchEvent | null => {
  if (item.kind === 'comment') return { type: 'alive' }
  switch (item.event) {
    case 'state':
      return { type: 'state', watch: parseWatch(json(item.data)) }
    case 'bye':
      return { type: 'rotated' }
    default:
      return null
  }
}
