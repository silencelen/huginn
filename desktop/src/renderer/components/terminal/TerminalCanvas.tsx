// Canvas painter for a parsed terminal grid, following the Android
// TerminalCanvas: every cell lands at col * cellW so a glyph whose font advance
// is not one cell (box drawing, ●, ❯, emoji) cannot push the row sideways.
// Two passes per row — background run rects, then text runs — plus overlays for
// selection and the cursor. The parser (terminalGrid) has already resolved
// reverse video and dim into the cell's fg/bg colours (dim = alpha 153 on fg),
// so this painter only handles bold/italic (font variants) and underline.
//
// Desktop extras over the Kotlin: devicePixelRatio-scaled backing store,
// cell-indexed mouse selection with clipboard copy, and clickable OSC 8
// hyperlinks (http/https only; main routes window.open to the system browser).

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { parse, type TermCell, type TermGrid } from '../../../shared/core/terminalGrid'
import type { TermColor } from '../../../shared/core/ansiPalette'
import { cellMetrics, cssColor, fontString, TERM_BG, TERM_FG } from './metrics'

const SELECTION_CSS = 'rgba(122,162,247,0.30)' // --accent at ~30%
const CURSOR_CSS = '#7aa2f7' // --accent
/** Pending local echo: accent-tinted and a shade transparent, so promised text
    is tellable from confirmed text without being hard to read. */
const ECHO_CSS = 'rgba(122,162,247,0.85)'

const isHttp = (u: string): boolean => /^https?:\/\//i.test(u)

const isPlainAscii = (s: string): boolean =>
  s.length === 1 && s.charCodeAt(0) >= 0x20 && s.charCodeAt(0) <= 0x7e

const eqColor = (a: TermColor | null, b: TermColor | null): boolean =>
  a === b || (a !== null && b !== null && a.r === b.r && a.g === b.g && a.b === b.b && a.a === b.a)

const sameStyle = (a: TermCell, b: TermCell): boolean =>
  eqColor(a.fg, b.fg) &&
  a.bold === b.bold &&
  a.italic === b.italic &&
  a.underline === b.underline &&
  a.link === b.link

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v))

interface Sel {
  /** Linear cell offsets (row * cols + col); anchor and head, either order. */
  readonly a: number
  readonly h: number
}

export interface TerminalCanvasProps {
  lines: readonly string[]
  /** The pane's own width — the grid the server composed, not our wish. */
  cols: number
  fontPx: number
  cursor: { x: number; y: number } | null
  /**
   * Characters typed but not yet confirmed by the pane (shared/core/localEcho
   * decides what may be here). Drawn from the cursor cell and clipped at the
   * row's end; empty on the scrollback canvas, which is settled history.
   */
  echo?: string
}

export function TerminalCanvas({
  lines,
  cols,
  fontPx,
  cursor,
  echo = '',
}: TerminalCanvasProps): React.JSX.Element {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const [sel, setSel] = useState<Sel | null>(null)
  const [hover, setHover] = useState<string | null>(null)
  const dragRef = useRef<{ anchor: number; moved: boolean; downLink: string | null } | null>(null)

  const grid: TermGrid = useMemo(() => parse(lines, cols, TERM_FG, TERM_BG), [lines, cols])

  const cellAtOff = useCallback(
    (off: number): TermCell | undefined =>
      grid.rows[Math.floor(off / grid.cols)]?.[off % grid.cols],
    [grid],
  )

  const offFromClient = useCallback(
    (clientX: number, clientY: number): number => {
      const canvas = canvasRef.current
      if (canvas === null || grid.rows.length === 0) return 0
      const r = canvas.getBoundingClientRect()
      const m = cellMetrics(fontPx)
      const col = clamp(Math.floor((clientX - r.left) / m.cellW), 0, grid.cols - 1)
      const row = clamp(Math.floor((clientY - r.top) / m.cellH), 0, grid.rows.length - 1)
      return row * grid.cols + col
    },
    [fontPx, grid],
  )

  /** Rows joined with newlines, trailing spaces trimmed per row. */
  const copySelection = useCallback(() => {
    if (sel === null) return
    const lo = Math.min(sel.a, sel.h)
    const hi = Math.max(sel.a, sel.h)
    const rLo = Math.floor(lo / grid.cols)
    const rHi = Math.floor(hi / grid.cols)
    const out: string[] = []
    for (let r = rLo; r <= rHi && r < grid.rows.length; r++) {
      const row = grid.rows[r]
      if (row === undefined) continue
      const cs = r === rLo ? lo % grid.cols : 0
      const ce = r === rHi ? hi % grid.cols : grid.cols - 1
      let line = ''
      // Wide-glyph trailing halves are "" and contribute nothing.
      for (let c = cs; c <= ce && c < row.length; c++) line += row[c]!.text
      out.push(line.replace(/\s+$/, ''))
    }
    const text = out.join('\n')
    if (text !== '') void navigator.clipboard.writeText(text).catch(() => {})
  }, [sel, grid])

  // Ctrl+C copies while a selection exists. Capture-phase on window so it wins
  // over the live-typing capture div (which would otherwise send C-c to the
  // pane); a click clears the selection and returns Ctrl+C to the terminal.
  useEffect(() => {
    if (sel === null) return
    const onKey = (e: KeyboardEvent): void => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'c') {
        e.preventDefault()
        e.stopPropagation()
        copySelection()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [sel, copySelection])

  const onMouseDown = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>): void => {
      if (e.button !== 0) return
      const off = offFromClient(e.clientX, e.clientY)
      dragRef.current = { anchor: off, moved: false, downLink: cellAtOff(off)?.link ?? null }
      setSel({ a: off, h: off })
      const onMove = (ev: MouseEvent): void => {
        const d = dragRef.current
        if (d === null) return
        const o = offFromClient(ev.clientX, ev.clientY)
        if (o !== d.anchor) d.moved = true
        setSel({ a: d.anchor, h: o })
      }
      const onUp = (): void => {
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
        const d = dragRef.current
        dragRef.current = null
        if (d === null || d.moved) return
        // A plain click: no selection — and if it landed on a web link, open it
        // (main's setWindowOpenHandler routes it to the system browser).
        setSel(null)
        if (d.downLink !== null && isHttp(d.downLink)) window.open(d.downLink)
      }
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    },
    [offFromClient, cellAtOff],
  )

  const onMouseMove = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>): void => {
      if (dragRef.current !== null) return
      const link = cellAtOff(offFromClient(e.clientX, e.clientY))?.link ?? null
      const next = link !== null && isHttp(link) ? link : null
      setHover((prev) => (prev === next ? prev : next))
    },
    [offFromClient, cellAtOff],
  )

  const onContextMenu = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>): void => {
      if (sel === null) return
      e.preventDefault()
      copySelection()
    },
    [sel, copySelection],
  )

  // ------------------------------------------------------------------ painting
  // Layout phase, not passive: the parent reconciles the local echo against
  // each new frame in ITS layout effect, which schedules one more render before
  // the browser paints. A passive painter would have already put the previous
  // (unreconciled) echo on screen by then, showing the character just confirmed
  // twice for a frame — exactly the ghost the echo is supposed to avoid.
  useLayoutEffect(() => {
    const canvas = canvasRef.current
    if (canvas === null) return
    const m = cellMetrics(fontPx)
    const rows = grid.rows.length
    const cssW = grid.cols * m.cellW
    const cssH = rows * m.cellH
    const dpr = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1
    canvas.width = Math.max(1, Math.round(cssW * dpr))
    canvas.height = Math.max(1, Math.round(cssH * dpr))
    canvas.style.width = `${cssW}px`
    canvas.style.height = `${cssH}px`
    const ctx = canvas.getContext('2d')
    if (ctx === null) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssW, cssH)
    ctx.textBaseline = 'alphabetic'

    const underline = (cell: TermCell): boolean =>
      cell.underline || (hover !== null && cell.link === hover)
    const underlineRect = (col: number, row: number, cells: number, fg: TermColor): void => {
      ctx.fillStyle = cssColor(fg)
      const y = Math.min(row * m.cellH + m.baseline + 2, (row + 1) * m.cellH - 1)
      ctx.fillRect(col * m.cellW, y, cells * m.cellW, 1)
    }

    // Pass 1: background runs, so a coloured span cannot paint over the glyph
    // of the cell to its left.
    grid.rows.forEach((row, ri) => {
      const y = ri * m.cellH
      let c = 0
      while (c < row.length) {
        const bg = row[c]!.bg
        if (bg === null) {
          c++
          continue
        }
        let end = c
        while (end + 1 < row.length && eqColor(row[end + 1]!.bg, bg)) end++
        ctx.fillStyle = cssColor(bg)
        ctx.fillRect(c * m.cellW, y, (end - c + 1) * m.cellW, m.cellH)
        c = end + 1
      }
    })

    // Pass 2: text. Runs of plain ASCII sharing a style draw as one string —
    // correct because the mono face advances those uniformly — and anything
    // else draws per cell, centred in its own span.
    grid.rows.forEach((row, ri) => {
      const baseY = ri * m.cellH + m.baseline
      let c = 0
      while (c < row.length) {
        const cell = row[c]!
        if (cell.text === '' || cell.text === ' ') {
          c++
          continue
        }
        ctx.font = fontString(m.fontPx, cell.bold, cell.italic)
        ctx.fillStyle = cssColor(cell.fg)
        if (isPlainAscii(cell.text)) {
          let text = cell.text
          let end = c
          while (end + 1 < row.length) {
            const n = row[end + 1]!
            if (n.text === '' || !isPlainAscii(n.text) || !sameStyle(cell, n)) break
            text += n.text
            end++
          }
          ctx.fillText(text, c * m.cellW, baseY)
          if (underline(cell)) underlineRect(c, ri, end - c + 1, cell.fg)
          c = end + 1
        } else {
          const span = cell.wide ? 2 : 1
          const boxW = span * m.cellW
          const adv = ctx.measureText(cell.text).width
          ctx.fillText(cell.text, c * m.cellW + Math.max(0, (boxW - adv) / 2), baseY)
          if (underline(cell)) underlineRect(c, ri, span, cell.fg)
          c += span
        }
      }
    })

    // Selection overlay: translucent accent over the selected linear range.
    if (sel !== null) {
      const lo = Math.min(sel.a, sel.h)
      const hi = Math.max(sel.a, sel.h)
      const rLo = Math.floor(lo / grid.cols)
      const rHi = Math.floor(hi / grid.cols)
      ctx.fillStyle = SELECTION_CSS
      for (let r = rLo; r <= rHi && r < rows; r++) {
        const cs = r === rLo ? lo % grid.cols : 0
        const ce = r === rHi ? hi % grid.cols : grid.cols - 1
        ctx.fillRect(cs * m.cellW, r * m.cellH, (ce - cs + 1) * m.cellW, m.cellH)
      }
    }

    const onGrid =
      cursor !== null &&
      cursor.y >= 0 &&
      cursor.y < rows &&
      cursor.x >= 0 &&
      cursor.x < grid.cols

    // Optimistic echo: typed but unconfirmed characters, drawn from the cursor
    // cell and CLIPPED at the row's end — the echo never invents a wrap,
    // because predicting the composer's wrapping is where ghosts come from.
    // The span is blanked first: an echo laid over whatever the pane last had
    // there would be two glyphs in one cell, which reads as corruption.
    let drawnEcho = 0
    if (onGrid && cursor !== null && echo !== '') {
      const chars = [...echo]
      const n = Math.min(chars.length, grid.cols - cursor.x)
      if (n > 0) {
        const x = cursor.x * m.cellW
        const y = cursor.y * m.cellH
        ctx.fillStyle = cssColor(TERM_BG)
        ctx.fillRect(x, y, n * m.cellW, m.cellH)
        ctx.font = fontString(m.fontPx, false, false)
        ctx.fillStyle = ECHO_CSS
        for (let i = 0; i < n; i++) {
          const ch = chars[i]!
          if (ch !== ' ') ctx.fillText(ch, x + i * m.cellW, y + m.baseline)
        }
        drawnEcho = n
      }
    }

    // The cursor sits AFTER the echo: that is where the next character goes,
    // which is what a cursor is for. Filled block normally; hollow while an
    // echo is pending, so "the pane has not confirmed this yet" is visible in
    // the terminal's own vernacular rather than as a second colour of text.
    if (onGrid && cursor !== null) {
      const cx = Math.min(cursor.x + drawnEcho, grid.cols - 1)
      const x = cx * m.cellW
      const y = cursor.y * m.cellH
      ctx.fillStyle = CURSOR_CSS
      if (drawnEcho > 0) {
        const t = Math.max(1, m.cellW * 0.12)
        ctx.fillRect(x, y, m.cellW, t)
        ctx.fillRect(x, y + m.cellH - t, m.cellW, t)
        ctx.fillRect(x, y, t, m.cellH)
        ctx.fillRect(x + m.cellW - t, y, t, m.cellH)
      } else {
        ctx.fillRect(x, y, m.cellW, m.cellH)
        const cell = grid.rows[cursor.y]?.[cx]
        if (cell !== undefined && cell.text !== '' && cell.text !== ' ') {
          ctx.font = fontString(m.fontPx, cell.bold, cell.italic)
          ctx.fillStyle = cssColor(TERM_BG)
          ctx.fillText(cell.text, x, y + m.baseline)
        }
      }
    }
  }, [grid, fontPx, cursor, sel, hover, echo])

  return (
    <canvas
      ref={canvasRef}
      className="term-canvas"
      style={{ cursor: hover !== null ? 'pointer' : 'text' }}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseLeave={() => setHover(null)}
      onContextMenu={onContextMenu}
    />
  )
}
