// Cell metrics for the mono face at a given size, measured from the font via
// canvas measureText rather than assumed — the Android CellMetrics lesson: v1
// guessed an advance of 0.6 em and the requested pane width came out subtly
// wrong. Cached per font size; the same numbers size the canvas, drive the
// cols/rows fit, and back the mouse→cell math, so they cannot disagree.

import { rgb, type TermColor } from '../../../shared/core/ansiPalette'

export interface CellMetrics {
  readonly fontPx: number
  readonly cellW: number
  readonly cellH: number
  /** Distance from a row's top to the text baseline. */
  readonly baseline: number
}

let monoStack: string | null = null

/** The house mono stack from app.css's --mono, resolved once per window. */
export const monoFamily = (): string => {
  if (monoStack === null) {
    const v = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim()
    monoStack = v !== '' ? v : 'monospace'
  }
  return monoStack
}

/** One canvas font string used for BOTH measuring and painting. */
export const fontString = (px: number, bold: boolean, italic: boolean): string =>
  `${italic ? 'italic ' : ''}${bold ? 'bold ' : ''}${px}px ${monoFamily()}`

const cache = new Map<number, CellMetrics>()

export const cellMetrics = (fontPx: number): CellMetrics => {
  const hit = cache.get(fontPx)
  if (hit !== undefined) return hit
  // Fallbacks are only for a missing 2d context (never in the real renderer).
  let cellW = fontPx * 0.6
  let ascent = fontPx * 0.8
  let descent = fontPx * 0.25
  const ctx = document.createElement('canvas').getContext('2d')
  if (ctx !== null) {
    ctx.font = fontString(fontPx, false, false)
    const m = ctx.measureText('M')
    if (m.width > 0) cellW = m.width
    if (typeof m.fontBoundingBoxAscent === 'number') ascent = m.fontBoundingBoxAscent
    if (typeof m.fontBoundingBoxDescent === 'number') descent = m.fontBoundingBoxDescent
  }
  // 1.06 line spacing, same as the Android CellMetrics.
  const out: CellMetrics = {
    fontPx,
    cellW,
    cellH: (ascent + descent) * 1.06,
    baseline: ascent,
  }
  cache.set(fontPx, out)
  return out
}

/** Default terminal colours: app.css --fg / --bg-rail as TermColors for parse(). */
export const TERM_FG: TermColor = rgb(0xd7, 0xdd, 0xe3)
export const TERM_BG: TermColor = rgb(0x0b, 0x0e, 0x12)

export const cssColor = (c: TermColor): string =>
  c.a >= 255 ? `rgb(${c.r},${c.g},${c.b})` : `rgba(${c.r},${c.g},${c.b},${(c.a / 255).toFixed(3)})`
