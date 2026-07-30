// Scenario-for-scenario port of the Android SyntaxTest (mobile/app/src/test/
// kotlin/com/silencelen/huginn/SyntaxTest.kt), plus pins for langForTool /
// resultLang, which had no Kotlin tests (they lived untested next to the
// Compose renderer in TranscriptView.kt).
//
// The highlighter is a lexer, so the tests care about two things: that the
// spans it produces are *safe* (in range, non-overlapping enough to apply),
// and that the classes which actually aid reading land on the right text. A
// missed keyword is cosmetic; an out-of-range span would crash the render.

import { describe, expect, it } from 'vitest'
import { dialect, highlight, langForTool, resultLang, type Span, type Tok } from '../../src/shared/core/syntax'

const spansOf = (code: string, lang: string | null): Span[] => highlight(code, lang)

const textOf = (code: string, lang: string | null, tok: Tok): string[] =>
  spansOf(code, lang)
    .filter((s) => s.tok === tok)
    .map((s) => code.slice(s.start, s.end))

describe('highlight', () => {
  // ---- safety: this is what would crash a screen ------------------------

  it('every span stays inside the string', () => {
    const samples = [
      'echo "unterminated',
      '# just a comment',
      'val x = "a\\"b"',
      '/* unclosed block',
      "'",
      '',
      '\n\n\n',
      '0x',
      '$VAR',
    ]
    for (const lang of ['bash', 'kotlin', 'json', 'diff', null, 'unknownlang']) {
      for (const s of samples) {
        for (const sp of spansOf(s, lang)) {
          expect(sp.start, `start in range for '${s}'/${lang}`).toBeGreaterThanOrEqual(0)
          expect(sp.start, `start in range for '${s}'/${lang}`).toBeLessThanOrEqual(s.length)
          expect(sp.end, `end in range for '${s}'/${lang}`).toBeGreaterThanOrEqual(sp.start)
          expect(sp.end, `end in range for '${s}'/${lang}`).toBeLessThanOrEqual(s.length)
        }
      }
    }
  })

  it('a plain fence produces no spans at all', () => {
    expect(spansOf('just some text', null)).toEqual([])
  })

  it('an unterminated string does not swallow the following lines', () => {
    const code = 'echo "oops\nls -la\n'
    const strings = textOf(code, 'bash', 'string')
    // string span must stop at the newline
    expect(strings.every((s) => !s.includes('ls -la'))).toBe(true)
  })

  it('an unclosed block comment ends at the end of input rather than looping', () => {
    const code = '/* forever'
    expect(textOf(code, 'kotlin', 'comment')).toEqual(['/* forever'])
  })

  // ---- the classes that aid reading -------------------------------------

  it('a shell command colours its comment, string and flags', () => {
    const code = '# deploy it\nrsync -av "src/" host:/dest   # trailing'
    expect(textOf(code, 'bash', 'comment')).toEqual(['# deploy it', '# trailing'])
    expect(textOf(code, 'bash', 'string')).toEqual(['"src/"'])
    // the -av flag should read as structure
    expect(textOf(code, 'bash', 'meta')).toContain('-av')
  })

  it('shell keywords are recognised but ordinary words are not', () => {
    const kw = textOf('if test -f x; then echo hi; fi', 'bash', 'keyword')
    expect(kw).toEqual(expect.arrayContaining(['if', 'then', 'echo', 'fi']))
    // a path is not a keyword
    expect(kw).not.toContain('x')
  })

  it('kotlin keywords and numbers are classified', () => {
    const code = 'private val timeout = 8000  // ms'
    expect(textOf(code, 'kotlin', 'keyword')).toEqual(expect.arrayContaining(['private', 'val']))
    expect(textOf(code, 'kotlin', 'number')).toEqual(['8000'])
    expect(textOf(code, 'kotlin', 'comment')).toEqual(['// ms'])
  })

  it('a called function is distinguished from a bare word', () => {
    expect(textOf('compute(x) + other', 'kotlin', 'function')).toEqual(['compute'])
  })

  it('an identifier containing digits is not read as a number', () => {
    expect(textOf('claude_fable_5 = 1', 'kotlin', 'number').filter((t) => t !== '1')).toEqual([])
  })

  it('an escaped quote does not terminate the string early', () => {
    expect(textOf('val s = "a\\"b"', 'kotlin', 'string')).toEqual(['"a\\"b"'])
  })

  // ---- diffs -------------------------------------------------------------

  it('a diff colours whole lines by their sign', () => {
    const code = '--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old line\n+new line\n unchanged'
    expect(textOf(code, 'diff', 'removed')).toEqual(['-old line'])
    expect(textOf(code, 'diff', 'added')).toEqual(['+new line'])
    expect(textOf(code, 'diff', 'meta')).toEqual(['--- a/x', '+++ b/x', '@@ -1,2 +1,2 @@'])
  })

  it('an unchanged diff line gets no colour', () => {
    expect(spansOf(' context only', 'diff')).toEqual([])
  })
})

// ---- dialect mapping ---------------------------------------------------

describe('dialect', () => {
  it('fence labels map onto the dialects we actually tokenize', () => {
    expect(dialect('bash')).toBe('shell')
    expect(dialect('Console')).toBe('shell')
    expect(dialect('patch')).toBe('diff')
    expect(dialect('json')).toBe('json')
    expect(dialect('yml')).toBe('conf')
    expect(dialect(null)).toBe('plain')
    expect(dialect('kotlin')).toBe('code')
  })
})

describe('langForTool', () => {
  it('shell tools get the shell dialect, file tools stay plain', () => {
    expect(langForTool('Bash')).toBe('shell')
    expect(langForTool('BashOutput')).toBe('shell')
    expect(langForTool('Read')).toBe('plain')
    expect(langForTool('Grep')).toBe('plain')
    expect(langForTool('SomeMcpTool')).toBe('plain')
    expect(langForTool(null)).toBe('plain')
  })
})

describe('resultLang', () => {
  it('a result that looks like a diff in its first lines gets diff colouring', () => {
    expect(resultLang('--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new')).toBe('diff')
    expect(resultLang('context\n+added line')).toBe('diff')
  })

  it('ordinary output stays plain, even if a sign appears past the probe window', () => {
    expect(resultLang('total 12\ndrwxr-xr-x 2 root root')).toBe('plain')
    expect(resultLang('l1\nl2\nl3\nl4\nl5\nl6\n+too late')).toBe('plain')
    expect(resultLang('')).toBe('plain')
  })
})
