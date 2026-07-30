// Scenario-for-scenario port of the Android MarkdownTest (mobile/app/src/test/
// kotlin/com/silencelen/huginn/MarkdownTest.kt). The renderer only has to
// handle what Claude actually writes in an answer. The property that matters
// most is that a code fence survives as a code block: flattening a shell
// command into prose is what made the phone app's v1 chat unusable, and a
// mangled command is worse than no command.
//
// (The Kotlin file also contains TailRevisionTest, which tests tailRevision
// from Common.kt — a different unit, not part of the markdown port.)

import { describe, expect, it } from 'vitest'
import { parseInline, parseMarkdown, type MdBlock } from '../../src/shared/core/markdown'

function blockAs<K extends MdBlock['kind']>(b: MdBlock | undefined, kind: K): Extract<MdBlock, { kind: K }> {
  if (!b || b.kind !== kind) throw new Error(`expected a ${kind} block, got ${b ? b.kind : 'nothing'}`)
  return b as Extract<MdBlock, { kind: K }>
}

const bulletsOf = (blocks: MdBlock[]): Extract<MdBlock, { kind: 'bullet' }>[] =>
  blocks.filter((b): b is Extract<MdBlock, { kind: 'bullet' }> => b.kind === 'bullet')

describe('parseMarkdown', () => {
  it('a fenced block becomes a code block with its language', () => {
    const b = parseMarkdown('Run this:\n\n```bash\ncd /root/netplan\ngit status\n```\n')
    expect(b).toHaveLength(2)
    expect(b[0]?.kind).toBe('paragraph')
    const code = blockAs(b[1], 'code')
    expect(code.lang).toBe('bash')
    expect(code.code).toBe('cd /root/netplan\ngit status')
  })

  it('markdown inside a fence is left completely alone', () => {
    const code = blockAs(parseMarkdown('```\n**not bold** and *not italic* and `not code`\n```')[0], 'code')
    expect(code.code).toBe('**not bold** and *not italic* and `not code`')
  })

  it('an unclosed fence still yields a code block rather than eating the answer', () => {
    const b = parseMarkdown('```python\nprint(1)\n')
    const code = blockAs(b[0], 'code')
    expect(code.code).toBe('print(1)')
  })

  it('a tilde fence works like a backtick fence', () => {
    const code = blockAs(parseMarkdown('~~~\nplain\n~~~')[0], 'code')
    expect(code.code).toBe('plain')
  })

  it('headings carry their level', () => {
    const b = parseMarkdown('# One\n## Two\n### Three')
    expect(blockAs(b[0], 'heading').level).toBe(1)
    expect(blockAs(b[1], 'heading').level).toBe(2)
    expect(blockAs(b[2], 'heading').level).toBe(3)
    expect(blockAs(b[0], 'heading').text.text).toBe('One')
  })

  it('bullets and numbered items are separate blocks', () => {
    const bullets = bulletsOf(parseMarkdown('- first\n- second\n\n1. one\n2. two'))
    expect(bullets).toHaveLength(4)
    expect(bullets[0]?.ordinal).toBe(null)
    expect(bullets[2]?.ordinal).toBe('1.')
    expect(bullets[2]?.text.text).toBe('one')
  })

  it('a wrapped bullet stays one bullet', () => {
    const bullets = bulletsOf(parseMarkdown('- a long item that\n  continues on the next line\n- second'))
    expect(bullets).toHaveLength(2)
    expect(bullets[0]?.text.text).toBe('a long item that continues on the next line')
  })

  it('blank lines separate paragraphs', () => {
    const b = parseMarkdown('one\n\ntwo')
    expect(b.filter((x) => x.kind === 'paragraph')).toHaveLength(2)
  })

  it('a horizontal rule is its own block', () => {
    const b = parseMarkdown('above\n\n---\n\nbelow')
    expect(b.some((x) => x.kind === 'rule')).toBe(true)
  })

  it('a blockquote is recognised', () => {
    const q = blockAs(parseMarkdown('> quoted text')[0], 'quote')
    expect(q.text.text).toBe('quoted text')
  })

  it('plain prose with no markup survives unchanged', () => {
    const text = 'Disk is at 62% and the daemon is healthy.'
    expect(blockAs(parseMarkdown(text)[0], 'paragraph').text.text).toBe(text)
  })
})

describe('parseInline', () => {
  it('inline styles are applied and their markers removed', () => {
    const s = parseInline('**bold** and *italic* and `code` and ~~gone~~')
    expect(s.text).toBe('bold and italic and code and gone')
    // expected several styled spans
    expect(s.spans.length).toBeGreaterThanOrEqual(4)
    expect(s.spans.map((sp) => sp.kind)).toEqual(['bold', 'italic', 'code', 'strike'])
  })

  it('an unmatched marker is shown literally instead of vanishing', () => {
    expect(parseInline('2 * 3 = 6').text).toBe('2 * 3 = 6')
    expect(parseInline('a `dangling').text).toBe('a `dangling')
    expect(parseInline('**not closed').text).toBe('**not closed')
  })

  it('snake_case identifiers are not treated as emphasis', () => {
    // This one bites constantly in a codebase full of file_path and tool_use.
    expect(parseInline('some_long_name here').text).toBe('some_long_name here')
  })

  it('a link keeps its label and appends the url only when it adds something', () => {
    const linked = parseInline('[docs](https://x.test/a)')
    expect(linked.text).toBe('docs (https://x.test/a)')
    expect(linked.spans[0]).toEqual({ start: 0, end: 4, kind: 'link', href: 'https://x.test/a' })
    expect(parseInline('[https://x.test](https://x.test)').text).toBe('https://x.test')
  })
})
