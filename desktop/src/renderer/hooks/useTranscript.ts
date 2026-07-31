// Polled transcript tail for a session or a chat. Cold open asks with
// offset=null (the daemon returns the tail window), then every poll passes the
// previous page's nextOffset so only new events cross the wire; pages land via
// mergeTranscriptPage, which renumbers seqs and carries session-level fields
// forward (see its header comment for why both matter).
//
// CONTRACT NOTE: the main-process handler for 'chats.transcript' currently
// drops the offset argument (src/main/ipc.ts passes only the id), so a chat
// poll gets the whole tail window every time. Only 'session' is wired to a
// screen today; before ChatView is unified onto this hook, that handler must
// forward the offset or the merge will append the tail repeatedly.

import { useCallback, useEffect, useRef, useState } from 'react'
import type { TranscriptPage } from '../../shared/api/types'
import { mergeTranscriptPage } from '../../shared/core/transcriptMerge'
import { decodeWireError, humanError } from '../../shared/ipc/errors'
import { call } from '../lib/ipc'

const POLL_MS = 2_500
const MAX_BACKOFF_MS = 60_000

export interface TranscriptState {
  /** The merged window on screen; null until the first page lands. */
  page: TranscriptPage | null
  error: string | null
  /**
   * The daemon 404/409'd the transcript: this session/chat has never prompted
   * Claude (the transcript hook fires on the first prompt). Distinct from a
   * transport error — the caller shows "no conversation yet", not a failure.
   */
  neverRan: boolean
  /** Fetch now instead of waiting out the poll interval. */
  refresh: () => void
}

/**
 * "This has never prompted Claude" is a 404/409, not a failure — and the
 * status now survives IPC (shared/ipc/errors), so this reads the real code
 * instead of matching the daemon's prose. The old string match would have
 * silently misclassified the day someone reworded an error.
 */
const looksNeverRan = (msg: string): boolean => {
  const wire = decodeWireError(msg)
  return wire !== null && (wire.status === 404 || wire.status === 409)
}

export function useTranscript(
  kind: 'session' | 'chat',
  id: string,
  /** Poll only while this view is on screen — see hooks/useVisible.ts. */
  active = true,
): TranscriptState {
  const [page, setPage] = useState<TranscriptPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [neverRan, setNeverRan] = useState(false)

  const pageRef = useRef<TranscriptPage | null>(null)
  const offsetRef = useRef<number | null>(null)
  const busyRef = useRef(false)
  const failuresRef = useRef(0)
  const aliveRef = useRef(true)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const stepRef = useRef<(() => void) | null>(null)

  const tick = useCallback(async (): Promise<void> => {
    if (busyRef.current) return
    busyRef.current = true
    try {
      const fetched =
        kind === 'session'
          ? await call('sessions.transcript', id, offsetRef.current)
          : await call('chats.transcript', id, offsetRef.current)
      if (!aliveRef.current) return
      offsetRef.current = fetched.nextOffset
      const current = pageRef.current
      // An empty incremental page with no live-state change: skip the setState
      // so an idle session does not re-render its whole list every 2.5s.
      const unchanged =
        current !== null &&
        fetched.events.length === 0 &&
        fetched.nextOffset === current.nextOffset &&
        fetched.running === current.running &&
        fetched.state === current.state &&
        fetched.pending === current.pending
      if (!unchanged) {
        const merged = mergeTranscriptPage(current, fetched)
        pageRef.current = merged
        setPage(merged)
      }
      setError(null)
      setNeverRan(false)
      failuresRef.current = 0
    } catch (e) {
      if (!aliveRef.current) return
      const msg = e instanceof Error ? e.message : String(e)
      // Show the daemon's own sentence, never Electron's plumbing prefix.
      setError(humanError(msg))
      setNeverRan(looksNeverRan(msg))
      failuresRef.current += 1
    } finally {
      busyRef.current = false
    }
  }, [kind, id])

  useEffect(() => {
    if (!active) return
    aliveRef.current = true
    busyRef.current = false
    failuresRef.current = 0

    const step = (): void => {
      if (!aliveRef.current) return
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
      void tick().then(() => {
        if (!aliveRef.current) return
        // Someone (a refresh) already re-armed while this tick was in flight.
        if (timerRef.current !== null) return
        // Back off on repeated failure. A session whose transcript file is
        // GONE 409s forever, and at a flat 2.5s that was ~24 daemon errors a
        // minute for as long as the tab stayed open.
        const f = failuresRef.current
        const delay = f === 0 ? POLL_MS : Math.min(MAX_BACKOFF_MS, POLL_MS * 2 ** Math.min(f, 6))
        timerRef.current = setTimeout(() => {
          timerRef.current = null
          step()
        }, delay)
      })
    }
    stepRef.current = step
    step()

    return () => {
      aliveRef.current = false
      stepRef.current = null
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [tick])

  const refresh = useCallback((): void => {
    stepRef.current?.()
  }, [])

  return { page, error, neverRan, refresh }
}
