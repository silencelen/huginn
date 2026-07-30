// Fenced code as a copyable, horizontally scrollable card with fail-soft
// syntax colors from the shared lexer. A missed keyword costs a color, never
// text — the code string itself is rendered verbatim.

import { useState } from 'react'
import { highlight, type Span } from '../../../shared/core/syntax'

function colored(code: string, spans: Span[]): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  let pos = 0
  for (const s of spans) {
    if (s.start > pos) parts.push(code.slice(pos, s.start))
    parts.push(
      <span key={s.start} className={`tok-${s.tok}`}>
        {code.slice(s.start, s.end)}
      </span>,
    )
    pos = s.end
  }
  if (pos < code.length) parts.push(code.slice(pos))
  return parts
}

export function CodeCard({ code, lang }: { code: string; lang: string | null }): React.JSX.Element {
  const [copied, setCopied] = useState(false)
  const spans = highlight(code, lang)
  return (
    <div className="code-card">
      <div className="code-card-bar">
        <span className="code-card-lang">{lang ?? ''}</span>
        <button
          type="button"
          className="code-card-copy"
          onClick={() => {
            void navigator.clipboard.writeText(code).then(() => {
              setCopied(true)
              setTimeout(() => setCopied(false), 1200)
            })
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="code-card-body">
        <code>{colored(code, spans)}</code>
      </pre>
    </div>
  )
}
