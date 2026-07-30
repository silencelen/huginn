// Renders the shared markdown block model. The lexer (shared/core/markdown)
// owns correctness; this file only maps blocks/spans to elements. Links are
// plain anchors — the main process intercepts navigation and routes http(s)
// to the system browser.

import type { InlineText, MdBlock } from '../../../shared/core/markdown'
import { parseMarkdown } from '../../../shared/core/markdown'
import { CodeCard } from '../transcript/CodeCard'

function Inline({ t }: { t: InlineText }): React.JSX.Element {
  const bounds = new Set<number>([0, t.text.length])
  for (const s of t.spans) {
    bounds.add(s.start)
    bounds.add(s.end)
  }
  const points = [...bounds].sort((a, b) => a - b)
  const parts: React.ReactNode[] = []
  for (let i = 0; i < points.length - 1; i += 1) {
    const start = points[i]!
    const end = points[i + 1]!
    if (start >= end) continue
    const text = t.text.slice(start, end)
    const active = t.spans.filter((s) => s.start <= start && s.end >= end)
    const link = active.find((s) => s.kind === 'link')
    const cls = active
      .filter((s) => s.kind !== 'link')
      .map((s) => `md-${s.kind}`)
      .join(' ')
    if (link?.href !== undefined) {
      parts.push(
        <a key={start} href={link.href} className={cls === '' ? 'md-link' : `md-link ${cls}`}>
          {text}
        </a>,
      )
    } else if (cls !== '') {
      parts.push(
        <span key={start} className={cls}>
          {text}
        </span>,
      )
    } else {
      parts.push(text)
    }
  }
  return <>{parts}</>
}

function Block({ block }: { block: MdBlock }): React.JSX.Element {
  switch (block.kind) {
    case 'heading': {
      const level = Math.min(6, Math.max(1, block.level))
      const Tag = `h${level}` as 'h1'
      return (
        <Tag className="md-heading">
          <Inline t={block.text} />
        </Tag>
      )
    }
    case 'bullet':
      return (
        <div className="md-bullet">
          <span className="md-marker">{block.ordinal ?? '•'}</span>
          <span>
            <Inline t={block.text} />
          </span>
        </div>
      )
    case 'code':
      return <CodeCard code={block.code} lang={block.lang} />
    case 'quote':
      return (
        <blockquote className="md-quote">
          <Inline t={block.text} />
        </blockquote>
      )
    case 'rule':
      return <hr className="md-rule" />
    case 'paragraph':
      return (
        <p className="md-p">
          <Inline t={block.text} />
        </p>
      )
  }
}

export function MarkdownView({ text }: { text: string }): React.JSX.Element {
  const blocks = parseMarkdown(text)
  return (
    <div className="md">
      {blocks.map((b, i) => (
        <Block key={i} block={b} />
      ))}
    </div>
  )
}
