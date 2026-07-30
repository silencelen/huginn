// Renderer-agnostic port of the Android client's Markdown lexer
// (mobile/app/src/main/kotlin/com/silencelen/huginn/ui/Markdown.kt). Just
// enough markdown for what Claude actually writes in an answer, split into
// blocks so a code fence can render as a real scrollable code card instead of
// being flattened into prose (which is what the phone app's v1 did, and it
// made any answer containing a command unreadable).
//
// Deliberately not a full CommonMark implementation and deliberately not
// react-markdown: no tables, no nested lists, no reference links. Unsupported
// syntax degrades to its literal text rather than disappearing, which is the
// safe failure for a reader — and the Kotlin lexer pins Claude-specific
// behaviours the library renderers get wrong (snake_case identifiers must not
// italicize, an unclosed fence must not eat the rest of the message).
//
// Output is plain data: a block list whose inline text carries span ranges
// with semantic kinds. The React layer maps them to elements separately.

export type InlineKind = 'code' | 'bold' | 'italic' | 'strike' | 'link'

export interface InlineSpan {
  start: number
  end: number
  kind: InlineKind
  /** Only on 'link' spans: the target the underlined label points at. */
  href?: string
}

/** The Kotlin AnnotatedString, reduced to data: rendered text + styled ranges. */
export interface InlineText {
  text: string
  spans: InlineSpan[]
}

export type MdBlock =
  | { kind: 'paragraph'; text: InlineText }
  | { kind: 'heading'; text: InlineText; level: number }
  | { kind: 'bullet'; text: InlineText; ordinal: string | null }
  | { kind: 'code'; code: string; lang: string | null }
  | { kind: 'quote'; text: InlineText }
  | { kind: 'rule' }

const FENCE = /^(`{3,}|~{3,})\s*([A-Za-z0-9+#._-]*)\s*$/
const HEADING = /^(#{1,6})\s+(.*)$/
const BULLET = /^(\s{0,3})([-*+]|\d{1,2}[.)])\s+(.*)$/
const QUOTE = /^>\s?(.*)$/
const RULE = /^(-{3,}|\*{3,}|_{3,})$/
const CONT = /^\s{2,}\S.*$/

const isLetterOrDigit = (c: string): boolean => /[\p{L}\p{Nd}]/u.test(c)

export function parseMarkdown(src: string): MdBlock[] {
  const out: MdBlock[] = []
  const lines = src.replaceAll('\r\n', '\n').split('\n')
  let i = 0
  let para = ''

  const flushPara = (): void => {
    if (para.trim() !== '') out.push({ kind: 'paragraph', text: parseInline(para.trim()) })
    para = ''
  }

  while (i < lines.length) {
    const line = lines[i] ?? ''
    const trimmed = line.trim()
    const fence = FENCE.exec(trimmed)
    if (fence) {
      flushPara()
      const tag = fence[2] ?? ''
      let body = ''
      i++
      while (i < lines.length && !FENCE.test((lines[i] ?? '').trim())) {
        body += (lines[i] ?? '') + '\n'
        i++
      }
      i++ // closing fence (or end of input, which we accept)
      out.push({ kind: 'code', code: body.replace(/\n+$/, ''), lang: tag !== '' ? tag : null })
      continue
    }
    if (trimmed === '') {
      flushPara()
      i++
      continue
    }
    if (RULE.test(trimmed)) {
      flushPara()
      out.push({ kind: 'rule' })
      i++
      continue
    }
    const h = HEADING.exec(line)
    if (h) {
      flushPara()
      out.push({ kind: 'heading', text: parseInline((h[2] ?? '').trim()), level: (h[1] ?? '').length })
      i++
      continue
    }
    const q = QUOTE.exec(line)
    if (q) {
      flushPara()
      out.push({ kind: 'quote', text: parseInline((q[1] ?? '').trim()) })
      i++
      continue
    }
    const b = BULLET.exec(line)
    if (b) {
      flushPara()
      // Continuation lines of the same item are indented; fold them in so a
      // wrapped bullet stays one bullet.
      let text = b[3] ?? ''
      i++
      while (i < lines.length && CONT.test(lines[i] ?? '') && !BULLET.test(lines[i] ?? '')) {
        text += ' ' + (lines[i] ?? '').trim()
        i++
      }
      const marker = b[2] ?? ''
      out.push({
        kind: 'bullet',
        text: parseInline(text.trim()),
        ordinal: /^\d/.test(marker) ? marker : null,
      })
      continue
    }
    para += line + '\n'
    i++
  }
  flushPara()
  return out
}

/**
 * Inline spans: `code`, **bold**, *italic*, ~~strike~~ and [text](url).
 * Scanned in one pass so a marker inside a code span is left alone.
 */
/** Schemes a rendered link may carry. Everything else keeps its label as text. */
const isSafeHref = (url: string): boolean => /^(https?:|mailto:)/i.test(url.trim())

export function parseInline(src: string): InlineText {
  let text = ''
  const spans: InlineSpan[] = []

  const styled = (content: string, kind: InlineKind, href?: string): void => {
    const start = text.length
    text += content
    const span: InlineSpan = { start, end: text.length, kind }
    if (href !== undefined) span.href = href
    spans.push(span)
  }

  let i = 0
  while (i < src.length) {
    const c = src.charAt(i)
    if (c === '`') {
      const end = src.indexOf('`', i + 1)
      if (end > i + 1) {
        styled(src.slice(i + 1, end), 'code')
        i = end + 1
      } else {
        text += c
        i++
      }
    } else if (c === '*' && src.startsWith('**', i)) {
      const end = src.indexOf('**', i + 2)
      if (end > i + 1) {
        styled(src.slice(i + 2, end), 'bold')
        i = end + 2
      } else {
        text += c
        i++
      }
    } else if (c === '*' || c === '_') {
      const end = src.indexOf(c, i + 1)
      // A lone underscore inside a word (snake_case) is not emphasis.
      const wordInternal = c === '_' && i > 0 && isLetterOrDigit(src.charAt(i - 1))
      if (end > i + 1 && !wordInternal) {
        styled(src.slice(i + 1, end), 'italic')
        i = end + 1
      } else {
        text += c
        i++
      }
    } else if (c === '~' && src.startsWith('~~', i)) {
      const end = src.indexOf('~~', i + 2)
      if (end > i + 1) {
        styled(src.slice(i + 2, end), 'strike')
        i = end + 2
      } else {
        text += c
        i++
      }
    } else if (c === '[') {
      const close = src.indexOf(']', i)
      if (close > i && close + 1 < src.length && src.charAt(close + 1) === '(') {
        const paren = src.indexOf(')', close)
        if (paren > close) {
          const label = src.slice(i + 1, close)
          const url = src.slice(close + 2, paren)
          // The label carries the meaning; the URL is appended as plain text
          // only when it adds information.
          //
          // Scheme filter: this text is written by a model reading untrusted
          // files, so a `javascript:` or `file:` href must never reach an
          // <a>. Downstream layers happen to stop it too (React sanitizes,
          // CSP blocks, will-navigate only forwards http(s)) — but a link
          // renderer should not depend on three accidents staying true.
          styled(label, 'link', isSafeHref(url) ? url : undefined)
          if (url.trim() !== '' && url !== label) {
            text += ` (${url})`
          }
          i = paren + 1
        } else {
          text += c
          i++
        }
      } else {
        text += c
        i++
      }
    } else {
      text += c
      i++
    }
  }
  return { text, spans }
}
