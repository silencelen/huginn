import { describe, expect, it } from 'vitest'
import { decodeWireError, encodeWireError, humanError } from '../../src/shared/ipc/errors'

describe('wire errors across IPC', () => {
  it('round-trips a status through the message Electron leaves us', () => {
    const encoded = encodeWireError({ status: 409, serverError: 'no transcript recorded' })
    // Electron wraps whatever main throws; the decoder must survive that.
    const wrapped = `Error invoking remote method 'sessions.transcript': Error: ${encoded}`
    expect(decodeWireError(wrapped)).toEqual({ status: 409, serverError: 'no transcript recorded' })
  })

  it('returns null for ordinary messages', () => {
    expect(decodeWireError('read timeout')).toBeNull()
    expect(decodeWireError('')).toBeNull()
  })

  it('survives a null server error', () => {
    const wrapped = `x ${encodeWireError({ status: 503, serverError: null })}`
    expect(decodeWireError(wrapped)).toEqual({ status: 503, serverError: null })
  })

  it('humanError shows the daemon sentence, not our plumbing', () => {
    const encoded = encodeWireError({ status: 409, serverError: 'this chat is stopping' })
    expect(humanError(`Error invoking remote method 'chats.send': Error: ${encoded}`)).toBe(
      'this chat is stopping',
    )
  })

  it('humanError falls back to the status when the server said nothing', () => {
    expect(humanError(encodeWireError({ status: 502, serverError: null }))).toBe('HTTP 502')
  })

  it('humanError strips the Electron prefix from non-HTTP failures', () => {
    expect(humanError("Error invoking remote method 'chats.get': Error: socket hang up")).toBe(
      'socket hang up',
    )
    expect(humanError('plain failure')).toBe('plain failure')
  })
})
