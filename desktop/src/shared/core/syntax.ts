// Renderer-agnostic port of the Android client's fail-soft syntax tokenizer
// (mobile/app/src/main/kotlin/com/silencelen/huginn/ui/Syntax.kt), plus the
// two pure dialect pickers that lived beside the Compose renderer in
// TranscriptView.kt (langForTool / resultLang) — colocated here because they
// are plain data logic that any renderer needs.
//
// A deliberately small tokenizer for the code that actually shows up in a
// Claude conversation: shell commands, diffs, and short source snippets. It is
// a lexer, not a parser — and deliberately not shiki: getting a keyword wrong
// costs a colour, never text, and the whole point is readability at a glance.
// It favours the few token classes that carry meaning (comment, string,
// number, keyword, and diff signs) over completeness.
//
// Output is spans of token kinds, not colours: the theme maps kinds to
// colours separately, and a renderer must drop (never trust) any span that
// falls outside the string.

export type Tok =
  | 'plain'
  | 'keyword'
  | 'string'
  | 'number'
  | 'comment'
  | 'function'
  | 'punct'
  | 'added'
  | 'removed'
  | 'meta'

export interface Span {
  start: number
  end: number
  tok: Tok
}

export type Dialect = 'shell' | 'diff' | 'json' | 'conf' | 'plain' | 'code'

const SHELL: ReadonlySet<string> = new Set([
  'if', 'then', 'else', 'elif', 'fi', 'for', 'while', 'do', 'done', 'case', 'esac',
  'function', 'return', 'exit', 'export', 'local', 'readonly', 'set', 'unset',
  'echo', 'cd', 'sudo', 'source', 'trap', 'shift', 'eval', 'test', 'in',
])
const C_LIKE: ReadonlySet<string> = new Set([
  'val', 'var', 'fun', 'class', 'object', 'interface', 'data', 'sealed', 'enum',
  'if', 'else', 'when', 'for', 'while', 'do', 'return', 'break', 'continue',
  'import', 'package', 'private', 'public', 'internal', 'protected', 'override',
  'suspend', 'const', 'companion', 'null', 'true', 'false', 'this', 'super',
  'try', 'catch', 'finally', 'throw', 'new', 'function', 'let', 'async', 'await',
  'def', 'elif', 'None', 'True', 'False', 'self', 'lambda', 'yield', 'with', 'as',
  'from', 'type', 'struct', 'func', 'go', 'defer', 'static', 'void', 'int', 'string',
])
const JSON_KW: ReadonlySet<string> = new Set(['true', 'false', 'null'])
const NO_KW: ReadonlySet<string> = new Set()

/** Normalizes a fence label to the dialect used for tokenizing. */
export function dialect(lang: string | null | undefined): Dialect {
  const l = lang == null ? null : lang.toLowerCase().trim()
  switch (l) {
    case 'sh':
    case 'bash':
    case 'zsh':
    case 'shell':
    case 'console':
    case 'terminal':
      return 'shell'
    case 'diff':
    case 'patch':
      return 'diff'
    case 'json':
      return 'json'
    case 'yaml':
    case 'yml':
    case 'toml':
    case 'ini':
    case 'conf':
      return 'conf'
    case null:
    case '':
      return 'plain'
    default:
      return 'code'
  }
}

export function highlight(code: string, lang: string | null | undefined): Span[] {
  switch (dialect(lang)) {
    case 'diff':
      return diff(code)
    case 'shell':
      return generic(code, SHELL, true, false)
    case 'conf':
      return generic(code, NO_KW, true, false)
    case 'json':
      return generic(code, JSON_KW, false, false)
    case 'plain':
      return []
    default:
      return generic(code, C_LIKE, true, true)
  }
}

/** What a tool's input is written in, for colouring purposes. */
export function langForTool(name: string | null | undefined): string {
  switch (name) {
    case 'Bash':
    case 'BashOutput':
      return 'shell'
    default:
      return 'plain'
  }
}

/** Tool output that looks like a diff gets diff colouring; everything else plain. */
export function resultLang(result: string): string {
  const probe = result.split('\n', 6)
  return probe.some((l) => l.startsWith('+') || l.startsWith('-') || l.startsWith('@@'))
    ? 'diff'
    : 'plain'
}

/** Whole-line classification: the only thing that matters in a diff. */
function diff(code: string): Span[] {
  const out: Span[] = []
  let i = 0
  for (const line of code.split('\n')) {
    const end = i + line.length
    let tok: Tok | null = null
    if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) tok = 'meta'
    else if (line.startsWith('+')) tok = 'added'
    else if (line.startsWith('-')) tok = 'removed'
    if (tok !== null && end > i) out.push({ start: i, end, tok })
    i = end + 1
  }
  return out
}

function generic(code: string, keywords: ReadonlySet<string>, hash: boolean, slash: boolean): Span[] {
  const out: Span[] = []
  let i = 0
  const n = code.length
  while (i < n) {
    const c = code.charAt(i)
    if (hash && c === '#') {
      // Comments run to end of line.
      const end = lineEnd(code, i)
      out.push({ start: i, end, tok: 'comment' })
      i = end
    } else if (slash && c === '/' && i + 1 < n && code.charAt(i + 1) === '/') {
      const end = lineEnd(code, i)
      out.push({ start: i, end, tok: 'comment' })
      i = end
    } else if (slash && c === '/' && i + 1 < n && code.charAt(i + 1) === '*') {
      const close = code.indexOf('*/', i + 2)
      const end = close < 0 ? n : close + 2
      out.push({ start: i, end, tok: 'comment' })
      i = end
    } else if (c === '"' || c === "'" || c === '`') {
      const end = stringEnd(code, i, c)
      out.push({ start: i, end, tok: 'string' })
      i = end
    } else if (isDigit(c) && (i === 0 || !isWordChar(code.charAt(i - 1)))) {
      let j = i
      while (j < n && (isLetterOrDigit(code.charAt(j)) || code.charAt(j) === '.' || code.charAt(j) === '_')) j++
      out.push({ start: i, end: j, tok: 'number' })
      i = j
    } else if (isWordStart(c)) {
      let j = i
      while (j < n && isWordChar(code.charAt(j))) j++
      const word = code.slice(i, j)
      if (keywords.has(word)) {
        out.push({ start: i, end: j, tok: 'keyword' })
      } else if (j < n && code.charAt(j) === '(') {
        // A word directly followed by '(' is being called.
        out.push({ start: i, end: j, tok: 'function' })
      } else if (word.length > 1 && i > 0 && code.charAt(i - 1) === '-') {
        // A leading flag reads as structure in a shell command.
        out.push({ start: i - 1, end: j, tok: 'meta' })
      }
      i = j
    } else {
      i++
    }
  }
  return out
}

function lineEnd(code: string, from: number): number {
  const nl = code.indexOf('\n', from)
  return nl < 0 ? code.length : nl
}

/** Handles escapes so a `\"` inside a string does not end it. */
function stringEnd(code: string, from: number, quote: string): number {
  let j = from + 1
  while (j < code.length) {
    const ch = code.charAt(j)
    if (ch === '\\') {
      j += 2
      continue
    }
    if (ch === quote) return j + 1
    // An unterminated quote must not swallow the rest of the block.
    if (ch === '\n' && quote !== '`') return j
    j++
  }
  return code.length
}

const isDigit = (c: string): boolean => /\p{Nd}/u.test(c)
const isLetter = (c: string): boolean => /\p{L}/u.test(c)
const isLetterOrDigit = (c: string): boolean => /[\p{L}\p{Nd}]/u.test(c)
const isWordStart = (c: string): boolean => isLetter(c) || c === '_' || c === '$'
const isWordChar = (c: string): boolean => isLetterOrDigit(c) || c === '_' || c === '$'
