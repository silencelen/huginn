// Word and line ranges over a parsed terminal grid — what a double-click and a
// triple-click select.
//
// The interesting part is the word rule. A terminal is not prose: the things a
// person double-clicks are paths, URLs, flags and identifiers, and a rule that
// broke on every `/` or `.` would turn `/opt/huginn/desktop/src` into eleven
// separate selections. So the classifier is inverted from the usual one — it
// starts from "everything joins" and names the characters that split:
// whitespace, quotes, brackets, and the shell's own punctuation. Anything a
// path or URL is made of (`/ . - _ ~ : @ + = % ? # & $ \`) stays inside the
// word, and glyphs the TUI draws its furniture with (box drawing, ●, ❯, ⏵)
// split, because a double-click on a box border should not drag in the text
// beside it.
//
// Pure, and separate from the canvas, so the boundary rule can be tested
// without a DOM.

import type { TermGrid } from '../../../shared/core/terminalGrid'

/** Splits a word. Prose and shell punctuation, plus every kind of bracket. */
const BREAK = new Set(['`', "'", '"', '(', ')', '[', ']', '{', '}', '<', '>', '|', ';', ',', '!', '*'])

/** Held inside a word so paths, URLs, flags and env vars select whole. */
const KEEP = new Set([
  '_', '-', '.', '/', '\\', ':', '~', '@', '+', '=', '%', '?', '#', '&', '$',
])

/** Inclusive linear cell offsets (`row * cols + col`), as the canvas uses. */
export interface CellRange {
  readonly from: number
  readonly to: number
}

/** Is this cell's glyph part of a word? "" (a wide glyph's second half) is not
    — callers absorb those separately, because they belong to the cell left of
    them rather than to whatever is on their right. */
export function isWordCell(text: string): boolean {
  if (text === '' || /\s/.test(text)) return false
  if (BREAK.has(text)) return false
  if (KEEP.has(text)) return true
  return /[\p{L}\p{N}]/u.test(text)
}

const glyph = (cells: readonly { readonly text: string }[], col: number): string =>
  cells[col]?.text ?? ''

/** The column that OWNS `col`: a wide glyph's trailing "" belongs to its left. */
const owner = (cells: readonly { readonly text: string }[], col: number): number => {
  let c = col
  while (c > 0 && glyph(cells, c) === '') c--
  return c
}

const rowOf = (grid: TermGrid, off: number): { row: number; col: number } | null => {
  if (grid.cols <= 0) return null
  const row = Math.floor(off / grid.cols)
  if (row < 0 || row >= grid.rows.length) return null
  return { row, col: Math.min(Math.max(off % grid.cols, 0), grid.cols - 1) }
}

/**
 * The word under `off`, or null when that cell is whitespace or a separator —
 * double-clicking empty space selects nothing rather than something arbitrary.
 */
export function wordRangeAt(grid: TermGrid, off: number): CellRange | null {
  const at = rowOf(grid, off)
  if (at === null) return null
  const cells = grid.rows[at.row]
  if (cells === undefined) return null

  const start = owner(cells, at.col)
  if (!isWordCell(glyph(cells, start))) return null

  let lo = start
  while (lo > 0) {
    const prev = owner(cells, lo - 1)
    if (!isWordCell(glyph(cells, prev))) break
    lo = prev
  }

  let hi = start
  while (hi + 1 < cells.length) {
    if (glyph(cells, hi + 1) === '') {
      hi += 1
      continue
    }
    if (!isWordCell(glyph(cells, hi + 1))) break
    hi += 1
  }

  return { from: at.row * grid.cols + lo, to: at.row * grid.cols + hi }
}

/**
 * The whole line under `off`, trimmed at the last glyph. Untrimmed it would
 * paint the selection highlight across the pane's full width, which reads as
 * "you selected 200 spaces" — and the copy already drops them anyway.
 */
export function lineRangeAt(grid: TermGrid, off: number): CellRange | null {
  const at = rowOf(grid, off)
  if (at === null) return null
  const cells = grid.rows[at.row]
  if (cells === undefined) return null

  let last = -1
  for (let c = 0; c < cells.length; c++) {
    const t = glyph(cells, c)
    if (t !== '' && !/^\s+$/.test(t)) last = c
  }
  if (last < 0) return null
  // A wide glyph at the end owns the cell after it too.
  while (last + 1 < cells.length && glyph(cells, last + 1) === '') last++

  return { from: at.row * grid.cols, to: at.row * grid.cols + last }
}
