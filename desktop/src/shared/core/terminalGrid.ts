// Cell-grid parser for `tmux capture-pane -e` lines, ported from the Android
// app's ui/TerminalGrid.kt (the reference implementation; its unit tests are
// the executable spec). Why a grid at all: the mobile v1 drew each captured
// line as one text run and let the font decide advances, but Claude Code's TUI
// is full of glyphs whose font advance is not one monospace cell (box drawing,
// `●`, `❯`, `⏵⏵`, emoji), so columns drifted and box borders never lined up.
// Laying the line out into cells first, using the SAME width rule tmux used
// when it composed the pane (wcwidth), makes the client's grid agree with the
// server's grid by construction. Renderer-agnostic: the canvas painter lives
// elsewhere; this is a pure data transform.
//
// One deliberate departure from the Kotlin: it consumes OSC 8 hyperlinks whole
// and drops the URI, leaving only the visible label. The desktop keeps the URI
// as a `link` attribute on every cell painted while the hyperlink is open (a
// clickable file path is worth having on a real screen). Text output is
// byte-for-byte identical to the Kotlin either way.

import { palette, rgb, type TermColor } from './ansiPalette'

/**
 * One fixed-width cell of the parsed screen.
 * `text` is "" for the trailing half of a wide glyph.
 */
export type TermCell = {
  readonly text: string
  readonly fg: TermColor
  readonly bg: TermColor | null
  readonly bold: boolean
  readonly dim: boolean
  readonly italic: boolean
  readonly underline: boolean
  /** Occupies this cell plus the next. */
  readonly wide: boolean
  /** OSC 8 hyperlink URI covering this cell, if any (desktop extension). */
  readonly link: string | null
}

export const isBlank = (c: TermCell): boolean => c.text === '' || c.text === ' '

export type TermGrid = {
  readonly rows: readonly (readonly TermCell[])[]
  readonly cols: number
}

/**
 * Display width of a code point, matching the wcwidth rule a terminal uses.
 * Only the classes that actually occur matter here: zero-width combining
 * marks and joiners, and the wide East Asian / emoji blocks.
 */
export const charWidth = (cp: number): number => {
  if (cp === 0) return 0
  if (cp < 32) return 0
  // Combining marks, joiners, variation selectors: they attach to the
  // previous cell rather than taking one of their own.
  if (
    (cp >= 0x0300 && cp <= 0x036f) || (cp >= 0x1ab0 && cp <= 0x1aff) ||
    (cp >= 0x1dc0 && cp <= 0x1dff) || (cp >= 0x20d0 && cp <= 0x20f0) ||
    (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xfe20 && cp <= 0xfe2f) ||
    (cp >= 0x200b && cp <= 0x200f) || (cp >= 0x2060 && cp <= 0x2064)
  ) {
    return 0
  }
  // Wide (East Asian W/F) and emoji-presentation blocks.
  if (
    (cp >= 0x1100 && cp <= 0x115f) || (cp >= 0x2e80 && cp <= 0x303e) ||
    (cp >= 0x3041 && cp <= 0x33ff) || (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0xa000 && cp <= 0xa4cf) ||
    (cp >= 0xac00 && cp <= 0xd7a3) || (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe30 && cp <= 0xfe6f) || (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) ||
    (cp >= 0x1f300 && cp <= 0x1f64f) || (cp >= 0x1f680 && cp <= 0x1f6ff) ||
    (cp >= 0x1f900 && cp <= 0x1f9ff) || (cp >= 0x1fa70 && cp <= 0x1faff) ||
    (cp >= 0x20000 && cp <= 0x3fffd)
  ) {
    return 2
  }
  return 1
}

const ESC = '\u001B'
const BEL = '\u0007'

/** The Kotlin dims via `alpha = 0.6f` on a float colour; 0.6 × 255 ≈ 153. */
const DIM_ALPHA = 153

type Style = {
  fg: TermColor | null
  bg: TermColor | null
  bold: boolean
  dim: boolean
  italic: boolean
  underline: boolean
  reverse: boolean
  /**
   * Open OSC 8 hyperlink. Not part of SGR state: `ESC[0m` does not close a
   * hyperlink — only `ESC]8;;` (an empty URI) does.
   */
  link: string | null
}

const resetSgr = (st: Style): void => {
  st.fg = null
  st.bg = null
  st.bold = false
  st.dim = false
  st.italic = false
  st.underline = false
  st.reverse = false
}

/**
 * Parses `capture-pane -e` lines into a grid.
 *
 * @param cols pad/truncate every row to this width so the grid is rectangular
 *   and a row that ends early cannot make the next row's cells shift left.
 */
export const parse = (
  lines: readonly string[],
  cols: number,
  defaultFg: TermColor,
  defaultBg: TermColor,
): TermGrid => ({
  rows: lines.map((l) => parseLine(l, cols, defaultFg, defaultBg)),
  cols,
})

const parseLine = (
  line: string,
  cols: number,
  defaultFg: TermColor,
  defaultBg: TermColor,
): TermCell[] => {
  const st: Style = {
    fg: null, bg: null, bold: false, dim: false,
    italic: false, underline: false, reverse: false, link: null,
  }
  const out: TermCell[] = []
  let i = 0
  while (i < line.length) {
    if (line.charCodeAt(i) === 0x1b) {
      i = skipEscape(line, i, st)
      continue
    }
    const cp = line.codePointAt(i)!
    const charCount = cp > 0xffff ? 2 : 1
    const s = line.slice(i, i + charCount)
    i += charCount
    const w = charWidth(cp)
    if (w === 0) {
      // Attach to the previous cell so an accent or a variation selector
      // renders with its base instead of eating a column.
      const last = out[out.length - 1]
      if (last !== undefined) out[out.length - 1] = { ...last, text: last.text + s }
      continue
    }
    const fg0 = st.fg ?? defaultFg
    const bg0 = st.bg
    const fg = st.reverse ? (bg0 ?? defaultBg) : fg0
    const bg = st.reverse ? fg0 : bg0
    const cell: TermCell = {
      text: s,
      fg: st.dim && !st.reverse ? { ...fg, a: DIM_ALPHA } : fg,
      bg,
      bold: st.bold,
      dim: st.dim,
      italic: st.italic,
      underline: st.underline,
      wide: w === 2,
      link: st.link,
    }
    out.push(cell)
    // The trailing half of a wide glyph is a real cell that draws nothing,
    // so column arithmetic stays honest.
    if (w === 2) out.push({ ...cell, text: '', wide: false })
  }
  // Rectangular rows: pad short lines, drop overflow.
  const blank: TermCell = {
    text: ' ', fg: defaultFg, bg: null,
    bold: false, dim: false, italic: false, underline: false, wide: false, link: null,
  }
  while (out.length < cols) out.push(blank)
  return out.length > cols ? out.slice(0, cols) : out
}

/** @returns the index just past the escape sequence starting at `start`. */
const skipEscape = (line: string, start: number, st: Style): number => {
  if (start + 1 >= line.length) return line.length
  switch (line[start + 1]) {
    case '[': {
      let j = start + 2
      while (j < line.length) {
        const cc = line.charCodeAt(j)
        if (cc >= 0x40 && cc <= 0x7e) break // final byte '@'..'~'
        j++
      }
      if (j >= line.length) return line.length
      if (line[j] === 'm') applySgr(line.slice(start + 2, j), st)
      return j + 1
    }
    // OSC: title changes and, notably, OSC 8 hyperlinks, which Claude Code
    // emits around file paths. Consumed whole — the visible label follows as
    // ordinary text. Terminated by BEL or by ST (ESC \); an ST's ESC is left
    // in place and swallowed by the two-byte default branch on the next pass,
    // exactly as the Kotlin does it.
    case ']': {
      let j = start + 2
      while (j < line.length && line[j] !== BEL && line[j] !== ESC) j++
      // Desktop extension: remember the OSC 8 URI so the cells under the
      // label become clickable. `ESC]8;;` (empty URI) closes the hyperlink.
      const payload = line.slice(start + 2, j)
      if (payload.startsWith('8;')) {
        const second = payload.indexOf(';', 2)
        const uri = second === -1 ? '' : payload.slice(second + 1)
        st.link = uri === '' ? null : uri
      }
      return j < line.length && line[j] === BEL ? j + 1 : j
    }
    default:
      return start + 2
  }
}

const applySgr = (params: string, st: Style): void => {
  if (params === '') {
    resetSgr(st)
    return
  }
  const codes = params.split(';').map((s) => {
    const n = parseInt(s, 10)
    return Number.isNaN(n) ? 0 : n
  })
  let k = 0
  while (k < codes.length) {
    const code = codes[k]!
    if (code === 0) resetSgr(st)
    else if (code === 1) st.bold = true
    else if (code === 2) st.dim = true
    else if (code === 3) st.italic = true
    else if (code === 4) st.underline = true
    else if (code === 7) st.reverse = true
    else if (code === 22) { st.bold = false; st.dim = false }
    else if (code === 23) st.italic = false
    else if (code === 24) st.underline = false
    else if (code === 27) st.reverse = false
    else if (code >= 30 && code <= 37) st.fg = palette[code - 30] ?? null
    else if (code === 39) st.fg = null
    else if (code >= 40 && code <= 47) st.bg = palette[code - 40] ?? null
    else if (code === 49) st.bg = null
    else if (code >= 90 && code <= 97) st.fg = palette[code - 90 + 8] ?? null
    else if (code >= 100 && code <= 107) st.bg = palette[code - 100 + 8] ?? null
    else if (code === 38 || code === 48) {
      const isFg = code === 38
      const mode = codes[k + 1]
      if (mode === 5) {
        const col = palette[codes[k + 2] ?? 0] ?? null
        if (isFg) st.fg = col
        else st.bg = col
        k += 2
      } else if (mode === 2) {
        const col = rgb(codes[k + 2] ?? 0, codes[k + 3] ?? 0, codes[k + 4] ?? 0)
        if (isFg) st.fg = col
        else st.bg = col
        k += 4
      }
    }
    k++
  }
}
