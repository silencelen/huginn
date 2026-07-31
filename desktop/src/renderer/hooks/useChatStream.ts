// The live layer of an open chat: subscribe to the main-process run stream,
// apply batches in seq order, and re-subscribe on any gap (window reload,
// devtools pause). The snapshot + gap-resync contract mirrors the daemon's
// ?since= reattach shape one hop closer to the UI.

import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChatDetail, ChatEvent } from '../../shared/api/types'
import type { SendOutcome } from '../../shared/ipc/contract'
import { humanError } from '../../shared/ipc/errors'
import { call, on } from '../lib/ipc'

export interface ChatLive {
  detail: ChatDetail | null
  events: ChatEvent[]
  partialText: string
  running: boolean
  queuedNotice: number | null
  error: string | null
  send: (text: string) => Promise<void>
  cancel: () => Promise<void>
  refresh: () => Promise<void>
}

export function useChatStream(chatId: string | null): ChatLive {
  const [detail, setDetail] = useState<ChatDetail | null>(null)
  const [events, setEvents] = useState<ChatEvent[]>([])
  const [partialText, setPartialText] = useState('')
  const [running, setRunning] = useState(false)
  const [queuedNotice, setQueuedNotice] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const subRef = useRef<{ id: number; seq: number } | null>(null)
  const aliveRef = useRef(true)
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const refresh = useCallback(async () => {
    if (chatId === null) return
    try {
      setDetail(await call('chats.get', chatId))
      setError(null)
    } catch (e) {
      setError(humanError(e instanceof Error ? e.message : String(e)))
    }
  }, [chatId])

  /** The digest is the rendered truth; stream boundaries just say "it grew".
   *  Coalesce so a burst of tool events costs one fetch, not ten. */
  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current !== null) return
    refreshTimer.current = setTimeout(() => {
      refreshTimer.current = null
      void refresh()
    }, 300)
  }, [refresh])

  const subscribe = useCallback(async () => {
    if (chatId === null) return
    if (subRef.current !== null) {
      void call('chatStream.unsubscribe', subRef.current.id)
      subRef.current = null
    }
    const snapshot = await call('chatStream.subscribe', chatId)
    if (!aliveRef.current) {
      void call('chatStream.unsubscribe', snapshot.subscriptionId)
      return
    }
    subRef.current = { id: snapshot.subscriptionId, seq: snapshot.seq }
    setEvents(snapshot.events)
    setPartialText(snapshot.partialText)
    setRunning(snapshot.running)
  }, [chatId])

  useEffect(() => {
    aliveRef.current = true
    setDetail(null)
    setEvents([])
    setPartialText('')
    setRunning(false)
    setQueuedNotice(null)
    void refresh()
    void subscribe()

    const off = on('push.chatEvents', (batch) => {
      const sub = subRef.current
      if (sub === null || batch.subscriptionId !== sub.id) return
      if (batch.seq !== sub.seq + 1) {
        // Gap: recover by resync, never by guessing what was missed.
        void subscribe()
        return
      }
      sub.seq = batch.seq
      setEvents((prev) => [...prev, ...batch.items])
      for (const ev of batch.items) {
        switch (ev.type) {
          case 'delta':
            setPartialText((p) => p + ev.text)
            break
          case 'assistant':
            setPartialText('')
            scheduleRefresh()
            break
          case 'tool':
          case 'result':
            scheduleRefresh()
            break
          case 'started':
            setRunning(true)
            break
          case 'done':
            setRunning(false)
            setQueuedNotice(null)
            void refresh()
            break
          case 'stream_lost':
            // The run is probably still going on the host — reattach rather
            // than leaving a frozen half-answer on screen (this is what made
            // chats wedge after every laptop sleep).
            void subscribe()
            break
          case 'error':
            setRunning(false)
            scheduleRefresh()
            break
          default:
            break
        }
      }
    })

    return () => {
      aliveRef.current = false
      off()
      if (subRef.current !== null) {
        void call('chatStream.unsubscribe', subRef.current.id)
        subRef.current = null
      }
    }
  }, [chatId, refresh, subscribe])

  const send = useCallback(
    async (text: string) => {
      if (chatId === null) return
      const outcome: SendOutcome = await call('chats.send', chatId, text)
      if (outcome.queued) {
        setQueuedNotice(outcome.position)
      } else {
        setQueuedNotice(null)
        setRunning(true)
        // The send opened a fresh run stream in main; resubscribe to it.
        await subscribe()
      }
      await refresh()
    },
    [chatId, refresh, subscribe],
  )

  const cancel = useCallback(async () => {
    if (chatId === null) return
    await call('chats.cancel', chatId)
    setQueuedNotice(null)
    await refresh()
  }, [chatId, refresh])

  return { detail, events, partialText, running, queuedNotice, error, send, cancel, refresh }
}
