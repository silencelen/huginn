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
    const fingerprint = url.searchParams.get('fp')
    if (session === null || session === '' || !Number.isInteger(option) || option < 1) return null
    // The fingerprint is MANDATORY here, and this is the whole security story
    // of the verb. Anything on this machine can fire a huginn:// URL — a local
    // process, or a web page the owner clicks through. Without a fingerprint
    // the daemon answers whatever question happens to be on the pane right
    // now, so a forged link could approve an arbitrary tool-use prompt on a
    // root-equivalent agent host. With it, the answer only lands if it matches
    // the exact question this app was showing.
    if (fingerprint === null || fingerprint === '') return null
    return { kind: 'answer', session, option, fingerprint }
  }
  return null
}

/**
 * Find a huginn:// URL in a second instance's argv. Only the trailing
 * non-flag argument is considered — that is where the OS puts the activation,
 * and scanning everything would also sweep Chromium's own switches.
 */
export function activationFromArgv(argv: string[]): Activation | null {
  for (let i = argv.length - 1; i >= 0; i -= 1) {
    const arg = argv[i]
    if (arg === undefined || arg.startsWith('--')) continue
    return parseActivation(arg)
  }
  return null
}
