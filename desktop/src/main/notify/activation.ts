// huginn:// protocol activation: how a toast button reaches back into the app
// (second-instance argv on Windows/Linux, open-url on macOS). Two verbs:
//   huginn://open?view=chats|sessions&id=…       → focus + navigate
//   huginn://answer?session=…&option=N&fp=…      → guarded pane answer
// The fingerprint rides along so the host can refuse a question that moved on
// — same guarantee as answering in-app.

export interface Activation {
  kind: 'open' | 'answer'
  view?: 'chats' | 'sessions'
  id?: string
  session?: string
  option?: number
  fingerprint?: string | null
}

export function parseActivation(raw: string): Activation | null {
  if (!raw.startsWith('huginn://')) return null
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return null
  }
  const verb = url.hostname
  if (verb === 'open') {
    const view = url.searchParams.get('view')
    const id = url.searchParams.get('id')
    if ((view !== 'chats' && view !== 'sessions') || id === null || id === '') return null
    return { kind: 'open', view, id }
  }
  if (verb === 'answer') {
    const session = url.searchParams.get('session')
    const option = Number(url.searchParams.get('option'))
    if (session === null || session === '' || !Number.isInteger(option) || option < 1) return null
    return { kind: 'answer', session, option, fingerprint: url.searchParams.get('fp') }
  }
  return null
}

/** Find a huginn:// URL anywhere in a second instance's argv. */
export function activationFromArgv(argv: string[]): Activation | null {
  for (const arg of argv) {
    const a = parseActivation(arg)
    if (a !== null) return a
  }
  return null
}
