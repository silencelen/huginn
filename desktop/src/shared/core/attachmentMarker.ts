// Attachment markers, ported from ui/Attachments.kt. The bracketed line
// appended to a message is plumbing for Claude (where the file landed, which
// tool opens it); displayText() turns it back into something a person should
// see when reading their own message.

export const imageMarker = (path: string): string =>
  `[Attached image at ${path} — view it with the Read tool.]`

export const fileMarker = (path: string, name: string | null, readable: boolean): string => {
  const where = `${path}${name === null || name.trim() === '' ? '' : ` (${name})`}`
  // Telling Claude to Read a binary comes back as mojibake and a shrug —
  // naming the right tool instead is what makes accepting any file safe.
  return readable
    ? `[Attached file at ${where} — view it with the Read tool.]`
    : `[Attached file at ${where} — a binary; inspect it with shell tools ` +
        `(file, unzip, strings, sqlite3) rather than Read. Requires act mode.]`
}

const MARKER_RE = /\[Attached image at [^\]]+ — view it with the Read tool\.\]/g
const FILE_RE = /\[Attached file at \S+( \(([^)]{1,80})\))? — [^\]]*\]/g

export function displayText(text: string): string {
  if (!text.includes('[')) return text
  const cleaned = text
    .replace(MARKER_RE, '📷 Photo attached')
    .replace(FILE_RE, (_m, _g1, name: string | undefined) =>
      name !== undefined && name.trim() !== '' ? `📎 ${name}` : '📎 File attached',
    )
    .trim()
  return cleaned === '' ? '📷 Photo attached' : cleaned
}
