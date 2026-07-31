// The pure parts of the desktop-native affordances: the terminal's word/line
// boundary rule, the list-pane width bounds, the dropped-text append, and the
// tooltip wording.

import { describe, expect, it } from 'vitest'
import { parse } from '../../src/shared/core/terminalGrid'
import { rgb } from '../../src/shared/core/ansiPalette'
import { isWordCell, lineRangeAt, wordRangeAt } from '../../src/renderer/components/terminal/selection'
import {
  clampListWidth,
  LIST_W_DEFAULT,
  LIST_W_MAX,
  LIST_W_MIN,
  parseListWidth,
} from '../../src/renderer/components/common/paneSplit'
import { appendDropped } from '../../src/renderer/components/composer/dropText'
import {
  agentDotTip,
  agentsDoneTip,
  bgAgentsTip,
  bgShellsTip,
  chatDotTip,
  connectionTip,
  duration,
  queuedTip,
  sessionDotTip,
} from '../../src/renderer/components/common/tips'

const FG = rgb(0xd7, 0xdd, 0xe3)
const BG = rgb(0x0b, 0x0e, 0x12)

/** A grid from plain lines, using the same parser the canvas paints from. */
const gridOf = (lines: string[], cols: number): ReturnType<typeof parse> =>
  parse(lines, cols, FG, BG)

/** The text a range would copy, so assertions read as what the user gets. */
const textOf = (lines: string[], cols: number, from: number, to: number): string => {
  const g = gridOf(lines, cols)
  let out = ''
  for (let off = from; off <= to; off++) {
    out += g.rows[Math.floor(off / cols)]?.[off % cols]?.text ?? ''
  }
  return out
}

const wordAt = (line: string, col: number, cols = line.length + 10): string | null => {
  const r = wordRangeAt(gridOf([line], cols), col)
  return r === null ? null : textOf([line], cols, r.from, r.to)
}

describe('terminal word selection', () => {
  it('selects a plain word', () => {
    expect(wordAt('hello world', 2)).toBe('hello')
    expect(wordAt('hello world', 8)).toBe('world')
  })

  it('keeps a whole path together', () => {
    const line = 'cd /opt/huginn/desktop/src && ls'
    expect(wordAt(line, 12)).toBe('/opt/huginn/desktop/src')
    expect(wordAt(line, 3)).toBe('/opt/huginn/desktop/src')
  })

  it('keeps a whole URL together, query string and all', () => {
    const line = 'open https://huginn.example/a/b?x=1&y=2#frag now'
    expect(wordAt(line, 20)).toBe('https://huginn.example/a/b?x=1&y=2#frag')
  })

  it('keeps flags, env vars and dotted names together', () => {
    expect(wordAt('run --output-format json', 8)).toBe('--output-format')
    expect(wordAt('echo $HOME/bin', 7)).toBe('$HOME/bin')
    expect(wordAt('vi app.config.ts', 8)).toBe('app.config.ts')
  })

  it('breaks on quotes, brackets and shell punctuation', () => {
    expect(wordAt('say "hello" now', 6)).toBe('hello')
    expect(wordAt('fn(arg);', 4)).toBe('arg')
    expect(wordAt('a|b', 0)).toBe('a')
    expect(wordAt('one, two', 0)).toBe('one')
  })

  it('selects nothing on whitespace or on a separator itself', () => {
    expect(wordAt('hello world', 5)).toBeNull()
    expect(wordAt('fn(arg)', 2)).toBeNull()
    expect(wordAt('   ', 1)).toBeNull()
  })

  it('does not drag TUI furniture into a word', () => {
    // Box drawing and the prompt glyph are separators, not letters.
    expect(wordAt('│ status │', 3)).toBe('status')
    expect(wordAt('❯ deploy', 3)).toBe('deploy')
  })

  it('takes a wide glyph whole, trailing half included', () => {
    const cols = 12
    const g = gridOf(['一二 x'], cols)
    const r = wordRangeAt(g, 0)
    expect(r).not.toBeNull()
    // Two wide glyphs occupy four cells; the range covers all of them.
    expect(r?.from).toBe(0)
    expect(r?.to).toBe(3)
  })

  it('is row-local: a word never runs off the end of its line', () => {
    const cols = 8
    const g = gridOf(['abcdefgh', 'ijklmnop'], cols)
    const r = wordRangeAt(g, 4)
    expect(r).toEqual({ from: 0, to: 7 })
    const second = wordRangeAt(g, 10)
    expect(second).toEqual({ from: 8, to: 15 })
  })

  it('returns null off the bottom of the grid', () => {
    expect(wordRangeAt(gridOf(['abc'], 8), 99)).toBeNull()
  })

  it('classifies cells the way the rule says', () => {
    expect(isWordCell('a')).toBe(true)
    expect(isWordCell('7')).toBe(true)
    expect(isWordCell('/')).toBe(true)
    expect(isWordCell('-')).toBe(true)
    expect(isWordCell(' ')).toBe(false)
    expect(isWordCell('')).toBe(false)
    expect(isWordCell(';')).toBe(false)
    expect(isWordCell('│')).toBe(false)
  })
})

describe('terminal line selection', () => {
  it('takes the whole line, trimmed at the last glyph', () => {
    const cols = 20
    const g = gridOf(['  indented text     '], cols)
    const r = lineRangeAt(g, 5)
    // Leading indentation is part of the line; trailing padding is not.
    expect(r).toEqual({ from: 0, to: 14 })
    expect(textOf(['  indented text     '], cols, 0, 14)).toBe('  indented text')
  })

  it('picks the row the offset landed on', () => {
    const cols = 10
    const g = gridOf(['first', 'second'], cols)
    expect(lineRangeAt(g, 13)).toEqual({ from: 10, to: 15 })
  })

  it('selects nothing on a blank row', () => {
    expect(lineRangeAt(gridOf(['       '], 7), 3)).toBeNull()
  })
})

describe('list pane width', () => {
  it('clamps to the usable range', () => {
    expect(clampListWidth(300)).toBe(300)
    expect(clampListWidth(10)).toBe(LIST_W_MIN)
    expect(clampListWidth(5000)).toBe(LIST_W_MAX)
    expect(clampListWidth(LIST_W_MIN)).toBe(LIST_W_MIN)
    expect(clampListWidth(LIST_W_MAX)).toBe(LIST_W_MAX)
  })

  it('rounds and rejects nonsense', () => {
    expect(clampListWidth(320.6)).toBe(321)
    expect(clampListWidth(Number.NaN)).toBe(LIST_W_DEFAULT)
    expect(clampListWidth(Number.POSITIVE_INFINITY)).toBe(LIST_W_DEFAULT)
  })

  it('reads back anything at all from storage', () => {
    expect(parseListWidth('420')).toBe(420)
    expect(parseListWidth('  360 ')).toBe(360)
    expect(parseListWidth(null)).toBe(LIST_W_DEFAULT)
    expect(parseListWidth('')).toBe(LIST_W_DEFAULT)
    expect(parseListWidth('wide please')).toBe(LIST_W_DEFAULT)
    // A width stored before the bounds changed is brought inside them.
    expect(parseListWidth('9000')).toBe(LIST_W_MAX)
  })
})

describe('dropped text', () => {
  it('fills an empty draft', () => {
    expect(appendDropped('', '/opt/huginn')).toBe('/opt/huginn')
  })

  it('appends on a new line to a draft that ends mid-word', () => {
    expect(appendDropped('look at', '/opt/huginn')).toBe('look at\n/opt/huginn')
  })

  it('continues the sentence when the draft already ends in a space', () => {
    expect(appendDropped('look at ', '/opt/huginn')).toBe('look at /opt/huginn')
  })

  it('normalises line endings and drops trailing whitespace', () => {
    expect(appendDropped('', 'one\r\ntwo\r\n')).toBe('one\ntwo')
  })

  it('keeps leading indentation, which may be real', () => {
    expect(appendDropped('', '    indented')).toBe('    indented')
  })

  it('leaves the draft alone when the drop was empty', () => {
    expect(appendDropped('keep me', '   \n ')).toBe('keep me')
    expect(appendDropped('keep me', '')).toBe('keep me')
  })
})

describe('tooltip copy', () => {
  it('says durations the way a person would', () => {
    expect(duration(3)).toBe('less than a minute')
    expect(duration(60)).toBe('1 minute')
    expect(duration(600)).toBe('10 minutes')
    expect(duration(3600)).toBe('1 hour')
    expect(duration(3600 * 5)).toBe('5 hours')
    expect(duration(3600 * 72)).toBe('3 days')
    expect(duration(-10)).toBe('less than a minute')
  })

  it('names the session state and how long it has held', () => {
    const now = 1_000_000
    expect(
      sessionDotTip({ state: 'running', stateSince: now - 240, activityAt: now }, now),
    ).toBe('Working for 4 minutes')
    expect(
      sessionDotTip({ state: 'attention', stateSince: now - 3600, activityAt: now }, now),
    ).toBe('Needs input for 1 hour')
    expect(sessionDotTip({ state: 'idle', stateSince: now - 120, activityAt: now }, now)).toBe(
      'Idle for 2 minutes',
    )
  })

  it('falls back to last activity, and says so, when no transition was recorded', () => {
    const now = 1_000_000
    expect(sessionDotTip({ state: 'running', stateSince: null, activityAt: now - 300 }, now)).toBe(
      'Working · last activity 5 minutes ago',
    )
    expect(sessionDotTip({ state: null, stateSince: null, activityAt: 0 }, now)).toBe(
      'No state reported',
    )
  })

  it('speaks for a chat dot only while it is showing', () => {
    const now = 1_000_000
    expect(chatDotTip({ running: false, updatedAt: now }, now)).toBeNull()
    expect(chatDotTip({ running: true, updatedAt: now - 120 }, now)).toBe(
      'Working · last update 2 minutes ago',
    )
  })

  it('counts queued messages, and stays quiet at zero', () => {
    expect(queuedTip(0)).toBeNull()
    expect(queuedTip(-1)).toBeNull()
    expect(queuedTip(1)).toBe('1 message queued behind the current reply')
    expect(queuedTip(3)).toBe('3 messages queued behind the current reply')
  })

  it('says what the connection dot costs you', () => {
    expect(connectionTip(true)).toContain('notifications arrive')
    expect(connectionTip(false)).toContain('will not arrive')
  })

  it('explains the work-strip counts', () => {
    expect(bgShellsTip(2, 'npm test')).toBe('2 background shells running · longest: npm test')
    expect(bgShellsTip(1, null)).toBe('1 background shell running')
    expect(bgShellsTip(0, 'npm test')).toBe('Background shell running · longest: npm test')
    expect(bgAgentsTip(0)).toBeNull()
    expect(bgAgentsTip(1)).toBe('1 subagent running for this session')
    expect(bgAgentsTip(4)).toBe('4 subagents running for this session')
  })

  it('splits the agent denominator into finished and still working', () => {
    expect(agentsDoneTip(0, 3)).toBe('3 agents still working')
    expect(agentsDoneTip(1, 3)).toBe('1 finished · 2 still working')
    expect(agentsDoneTip(3, 3)).toBe('All 3 agents finished')
    // The planned total can lag behind the files; never a negative remainder.
    expect(agentsDoneTip(4, 3)).toBe('All 3 agents finished')
  })

  it('times a single agent from its own start', () => {
    expect(agentDotTip({ active: false, startedAt: 100 }, 500)).toBe('Finished')
    expect(agentDotTip({ active: true, startedAt: 500 - 180 }, 500)).toBe('Working for 3 minutes')
    expect(agentDotTip({ active: true, startedAt: 0 }, 500)).toBe('Working')
  })
})
