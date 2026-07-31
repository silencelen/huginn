// Electron's ipcMain.handle serializes a rejection down to its message, so an
// HTTP status does not survive the trip: the renderer was left matching the
// daemon's literal error sentences to tell "this session never prompted
// Claude" (a 409 that is not a failure) from a real outage. A reworded daemon
// message would have silently misclassified it.
//
// So main encodes the parts that matter into the message, and the renderer
// decodes them back. Ugly at the boundary, honest everywhere else.

const MARK = '__huginn_err__'

export interface WireError {
  status: number
  serverError: string | null
}

export const encodeWireError = (e: WireError): string =>
  `${MARK}${JSON.stringify(e)}`

/** Pull the status back out of whatever Electron wrapped the message in. */
export function decodeWireError(message: string): WireError | null {
  const at = message.indexOf(MARK)
  if (at === -1) return null
  try {
    const parsed: unknown = JSON.parse(message.slice(at + MARK.length))
    if (typeof parsed !== 'object' || parsed === null) return null
    const o = parsed as Record<string, unknown>
    return {
      status: typeof o.status === 'number' ? o.status : 0,
      serverError: typeof o.serverError === 'string' ? o.serverError : null,
    }
  } catch {
    return null
  }
}

/** What a person should read: the daemon's own sentence, never our plumbing. */
export function humanError(message: string): string {
  const wire = decodeWireError(message)
  if (wire !== null) return wire.serverError ?? `HTTP ${wire.status}`
  // Strip Electron's "Error invoking remote method 'chats.get': Error: " prefix.
  return message.replace(/^Error invoking remote method '[^']*':\s*(Error:\s*)?/, '')
}
