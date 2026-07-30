// Port of the Android TerminalGridTest. The fixtures are verbatim `tmux
// capture-pane -e` output from live Claude Code panes on huginn (2026-07-27),
// including the OSC 8 hyperlinks Claude Code wraps around file paths and the
// box-drawing/`●`/`❯` furniture that broke v1's per-line rendering. `<E>` is
// ESC and `<B>` is BEL, so no raw control byte lives in this source.
//
// The "desktop extension" describe block at the bottom is NOT from the Kotlin
// spec: it pins the desktop-only `link` cell attribute (the Kotlin consumes
// OSC 8 and drops the URI; the desktop keeps it).

import { describe, expect, it } from 'vitest'
import { argb, palette } from '../../src/shared/core/ansiPalette'
import { charWidth, parse, type TermCell } from '../../src/shared/core/terminalGrid'

const esc = (s: string): string => s.replaceAll('<E>', '\u001B').replaceAll('<B>', '\u0007')

const FG = argb(0xffe8e2da)
const BG = argb(0xff12100f)

const parseRow = (line: string, cols = 40): readonly TermCell[] =>
  parse([line], cols, FG, BG).rows[0]!

const textOf = (row: readonly TermCell[]): string =>
  row.map((c) => c.text).join('').trimEnd()

describe('TerminalGrid', () => {
  // ---- the property v1 got wrong -----------------------------------------

  it('a glyph wider than a cell does not shift the columns after it', () => {
    // '●' is what Claude Code puts at the head of every assistant line. In v1
    // the font's advance for it decided where the rest of the line landed.
    const row = parseRow('●abc')
    expect(row[0]!.text).toBe('●')
    expect(row[1]!.text).toBe('a')
    expect(row[2]!.text).toBe('b')
    expect(row[3]!.text).toBe('c')
  })

  it('box drawing keeps its column so borders line up', () => {
    const row = parseRow('─────x')
    // the 6th cell must be x, not shifted
    expect(row[5]!.text).toBe('x')
  })

  it('every row is padded to the same width so rows cannot drift', () => {
    const g = parse(['short', 'a much longer line here'], 30, FG, BG)
    expect(g.rows[0]!.length).toBe(30)
    expect(g.rows[1]!.length).toBe(30)
  })

  it('a row longer than the pane is truncated, not wrapped', () => {
    const row = parseRow('0123456789', 5)
    expect(row.length).toBe(5)
    expect(textOf(row)).toBe('01234')
  })

  // ---- character widths ---------------------------------------------------

  it('wide characters occupy two cells with a blank continuation', () => {
    const row = parseRow('日x')
    expect(row[0]!.text).toBe('日')
    expect(row[0]!.wide).toBe(true)
    // the second half of a wide glyph draws nothing
    expect(row[1]!.text).toBe('')
    // x sits in column 2, as the terminal placed it
    expect(row[2]!.text).toBe('x')
  })

  it('emoji beyond the BMP are handled as one wide cell, not two halves', () => {
    // Surrogate pairs must be read as a code point; otherwise a rocket
    // becomes two broken cells and everything after it shifts.
    const row = parseRow('🚀x')
    expect(row[0]!.text).toBe('🚀')
    expect(row[0]!.wide).toBe(true)
    expect(row[2]!.text).toBe('x')
  })

  it('combining marks attach to the previous cell instead of taking a column', () => {
    const row = parseRow('e\u0301x') // e + combining acute
    expect(row[0]!.text).toBe('e\u0301')
    expect(row[1]!.text).toBe('x')
  })

  it('charWidth classifies the glyph classes the TUI actually uses', () => {
    expect(charWidth('a'.codePointAt(0)!)).toBe(1)
    expect(charWidth('●'.codePointAt(0)!)).toBe(1)
    expect(charWidth('─'.codePointAt(0)!)).toBe(1)
    expect(charWidth('❯'.codePointAt(0)!)).toBe(1)
    expect(charWidth('日'.codePointAt(0)!)).toBe(2)
    expect(charWidth(0x1f680)).toBe(2)
    expect(charWidth(0x0301)).toBe(0)
    expect(charWidth(0xfe0f)).toBe(0)
  })

  // ---- SGR ----------------------------------------------------------------

  it('256 colour foreground and background both land on the cell', () => {
    const row = parseRow(esc('<E>[38;5;167m<E>[48;5;52mX<E>[39m<E>[49mY'))
    expect(row[0]!.bg).not.toBeNull()
    expect(row[0]!.fg).toEqual(palette[167]!)
    // after the reset the default returns
    expect(row[1]!.fg).toEqual(FG)
    expect(row[1]!.bg).toBeNull()
  })

  it('truecolour sets an exact rgb foreground', () => {
    const c = parseRow(esc('<E>[38;2;18;52;86mT'))[0]!.fg
    expect(c.r).toBe(18)
    expect(c.g).toBe(52)
    expect(c.b).toBe(86)
  })

  it('bold and dim are set and cleared together by 22', () => {
    const row = parseRow(esc('<E>[1mB<E>[22mN'))
    expect(row[0]!.bold).toBe(true)
    expect(row[1]!.bold).toBe(false)
  })

  it('reverse video swaps foreground and surface', () => {
    const cell = parseRow(esc('<E>[7mR'))[0]!
    expect(cell.fg).toEqual(BG)
    expect(cell.bg).toEqual(FG)
  })

  it('the 256 colour cube and greyscale ramp match the xterm table', () => {
    const cube0 = parseRow(esc('<E>[38;5;16mX'))[0]!.fg
    expect(cube0.r).toBe(0)
    const cubeWhite = parseRow(esc('<E>[38;5;231mX'))[0]!.fg
    expect(cubeWhite.r).toBe(255)
    const grey = parseRow(esc('<E>[38;5;255mX'))[0]!.fg
    expect(grey.r).toBe(238)
  })

  // ---- escapes that must not become visible text --------------------------

  it('an OSC 8 hyperlink is consumed and only its label is shown', () => {
    // Claude Code wraps file paths in OSC 8; v1 showed the whole URL inline.
    const row = parseRow(esc('<E>]8;id=x;file:///tmp/a.txt<B>a.txt<E>]8;;<B>'), 20)
    expect(textOf(row)).toBe('a.txt')
  })

  it('an unterminated escape at the pane edge does not leak or throw', () => {
    expect(textOf(parseRow(esc('text<E>[38;5;'), 20))).toBe('text')
  })

  it('a cursor-move escape is dropped rather than printed', () => {
    expect(textOf(parseRow(esc('a<E>[2Kb'), 20))).toBe('ab')
  })

  it('plain text passes through untouched', () => {
    const line = '  ▐▛███▜▌   Claude Code v2.1.220'
    expect(textOf(parseRow(line, 60))).toBe(line.trimEnd())
  })

  it('a real captured pane line renders its visible text exactly', () => {
    // Leading indent included: it is part of the captured line.
    const line = esc('     <E>[2m<E>[38;5;231m 76 <E>[0m<E>[38;5;231m   val last = 0<E>[39m')
    expect(textOf(parseRow(line, 60))).toBe('      76    val last = 0'.trimEnd())
  })
})

describe('TerminalGrid — OSC 8 link attribute (desktop extension)', () => {
  it('cells under the label carry the URI; cells outside it do not', () => {
    const row = parseRow(esc('go <E>]8;id=x;file:///tmp/a.txt<B>a.txt<E>]8;;<B> now'), 20)
    expect(textOf(row)).toBe('go a.txt now')
    // 'go ' precedes the hyperlink open
    expect(row[0]!.link).toBeNull()
    expect(row[2]!.link).toBeNull()
    // 'a.txt' is the label: five cells, all carrying the URI
    for (let i = 3; i < 8; i++) expect(row[i]!.link).toBe('file:///tmp/a.txt')
    // ' now' follows the close, and so does the padding
    expect(row[8]!.link).toBeNull()
    expect(row[11]!.link).toBeNull()
    expect(row[19]!.link).toBeNull()
  })

  it('an ST-terminated (ESC backslash) hyperlink works like a BEL-terminated one', () => {
    const row = parseRow(esc('<E>]8;;file:///x<E>\\a<E>]8;;<E>\\b'), 10)
    expect(textOf(row)).toBe('ab')
    expect(row[0]!.link).toBe('file:///x')
    expect(row[1]!.link).toBeNull()
  })

  it('SGR reset does not close a hyperlink — only OSC 8 with an empty URI does', () => {
    const row = parseRow(esc('<E>]8;;file:///x<B>a<E>[0mb<E>]8;;<B>c'), 10)
    expect(row[0]!.link).toBe('file:///x')
    expect(row[1]!.link).toBe('file:///x')
    expect(row[2]!.link).toBeNull()
  })
})
