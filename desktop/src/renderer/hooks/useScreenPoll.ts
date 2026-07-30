// Screen-poll lifecycle for one session's live pane. Measures the box the
// terminal actually has (ResizeObserver on the container) and the cell the
// mono font actually draws (canvas measureText, via metrics), reports
// cols × rows so the daemon leases the tmux window to this shape, and hands
// back last-write-wins frames from the push channel. Geometry changes (box
// resize, font change) stop and restart the poll after a 300 ms debounce;
// unmount stops it, and main releases the size lease.

import { useCallback, useEffect, useRef, useState } from 'react'
import type { Screen } from '../../shared/api/types'
import { call, on } from '../lib/ipc'
import { cellMetrics } from '../components/terminal/metrics'

const MIN_COLS = 20
const MAX_COLS = 300
const MIN_ROWS = 10
const MAX_ROWS = 200
export const MIN_FONT_PX = 7
export const MAX_FONT_PX = 28
const DEFAULT_FONT_PX = 14
const RESIZE_DEBOUNCE_MS = 300
const PERSIST_DEBOUNCE_MS = 600

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v))
const clampFont = (px: number): number => clamp(Math.round(px), MIN_FONT_PX, MAX_FONT_PX)

export interface ScreenPoll {
  screen: Screen | null
  cellW: number
  cellH: number
  baseline: number
  fontPx: number
  setFontPx: (px: number) => void
  /** The user's explicit "fit anyway" while another client holds the pane. */
  forceFit: () => void
  /** Attach to the element whose box the terminal must fit. */
  containerRef: (el: HTMLElement | null) => void
}

export function useScreenPoll(name: string): ScreenPoll {
  const [screen, setScreen] = useState<Screen | null>(null)
  const [fontPx, setFontPxState] = useState(DEFAULT_FONT_PX)
  // Polling waits for the persisted font size: starting at the default and
  // restarting 50 ms later would lease two different sizes back to back.
  const [fontReady, setFontReady] = useState(false)
  const [geo, setGeo] = useState<{ cols: number; rows: number } | null>(null)
  const [force, setForce] = useState(false)

  const boxRef = useRef<{ w: number; h: number } | null>(null)
  const fontRef = useRef(fontPx)
  fontRef.current = fontPx
  const geoRef = useRef(geo)
  geoRef.current = geo
  const subIdRef = useRef<number | null>(null)
  const roRef = useRef<ResizeObserver | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const persistRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const computeGeo = useCallback((): void => {
    const box = boxRef.current
    if (box === null || box.w <= 0 || box.h <= 0) return
    const m = cellMetrics(fontRef.current)
    const cols = clamp(Math.floor(box.w / m.cellW), MIN_COLS, MAX_COLS)
    const rows = clamp(Math.floor(box.h / m.cellH), MIN_ROWS, MAX_ROWS)
    const cur = geoRef.current
    if (cur !== null && cur.cols === cols && cur.rows === rows) return
    setGeo({ cols, rows })
  }, [])

  const scheduleGeo = useCallback((): void => {
    // First measurement fits immediately; later ones (drag-resizing a window
    // fires dozens of observations) settle for 300 ms first.
    if (geoRef.current === null) {
      computeGeo()
      return
    }
    if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      debounceRef.current = null
      computeGeo()
    }, RESIZE_DEBOUNCE_MS)
  }, [computeGeo])

  const containerRef = useCallback(
    (el: HTMLElement | null): void => {
      roRef.current?.disconnect()
      roRef.current = null
      if (el === null) return
      const ro = new ResizeObserver((entries) => {
        const e = entries[entries.length - 1]
        if (e === undefined) return
        boxRef.current = { w: e.contentRect.width, h: e.contentRect.height }
        scheduleGeo()
      })
      ro.observe(el)
      roRef.current = ro
    },
    [scheduleGeo],
  )

  // Load the persisted font size once.
  useEffect(() => {
    let alive = true
    call('settings.get')
      .then((s) => {
        if (!alive) return
        setFontPxState(clampFont(s.terminalFontPx))
        setFontReady(true)
      })
      .catch(() => {
        if (alive) setFontReady(true)
      })
    return () => {
      alive = false
    }
  }, [])

  // A font change resizes the cell, so the same box now fits a different grid.
  useEffect(() => {
    if (geoRef.current !== null) scheduleGeo()
  }, [fontPx, scheduleGeo])

  // The poll subscription itself: start for this name+geometry, stop (which
  // releases the lease in main) when either changes or the view unmounts.
  useEffect(() => {
    if (!fontReady || geo === null) return
    let stopped = false
    let id: number | null = null
    call('screenPoll.start', name, { cols: geo.cols, rows: geo.rows, force })
      .then((r) => {
        if (stopped) {
          void call('screenPoll.stop', r.subscriptionId)
          return
        }
        id = r.subscriptionId
        subIdRef.current = r.subscriptionId
      })
      .catch(() => {})
    return () => {
      stopped = true
      if (id !== null) {
        if (subIdRef.current === id) subIdRef.current = null
        void call('screenPoll.stop', id)
      }
    }
  }, [name, geo, fontReady, force])

  // Frames: last-write-wins, filtered to the live subscription so a stale
  // frame from a just-stopped poll cannot repaint over a fresh one.
  useEffect(
    () =>
      on('push.screen', (p) => {
        if (subIdRef.current !== null && p.subscriptionId === subIdRef.current) setScreen(p.screen)
      }),
    [],
  )

  // Fresh session, fresh pane: never show one session's pixels under another's
  // name — and a force-fit is a per-view decision, not a sticky one.
  useEffect(() => {
    setScreen(null)
    setForce(false)
  }, [name])

  const setFontPx = useCallback((px: number): void => {
    const v = clampFont(px)
    setFontPxState(v)
    if (persistRef.current !== null) clearTimeout(persistRef.current)
    persistRef.current = setTimeout(() => {
      persistRef.current = null
      void call('settings.update', { terminalFontPx: v }).catch(() => {})
    }, PERSIST_DEBOUNCE_MS)
  }, [])

  useEffect(
    () => () => {
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
      if (persistRef.current !== null) clearTimeout(persistRef.current)
      roRef.current?.disconnect()
    },
    [],
  )

  const m = cellMetrics(fontPx)
  return {
    screen,
    cellW: m.cellW,
    cellH: m.cellH,
    baseline: m.baseline,
    fontPx,
    setFontPx,
    forceFit: useCallback(() => setForce(true), []),
    containerRef,
  }
}
