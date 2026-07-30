import { describe, expect, it } from 'vitest'
import { isValidSessionName, normalizeBaseUrl, routes } from '../../src/shared/api/routes'

describe('normalizeBaseUrl', () => {
  it('trims whitespace and trailing slashes', () => {
    expect(normalizeBaseUrl(' http://100.97.198.90:8787/ ')).toBe('http://100.97.198.90:8787')
    expect(normalizeBaseUrl('http://h:8787///')).toBe('http://h:8787')
    expect(normalizeBaseUrl('http://h:8787')).toBe('http://h:8787')
  })
})

describe('isValidSessionName', () => {
  it('accepts canonical names', () => {
    expect(isValidSessionName('jtyper')).toBe(true)
    expect(isValidSessionName('a')).toBe(true)
    expect(isValidSessionName('_x')).toBe(true)
    expect(isValidSessionName('with-dash.dot')).toBe(true)
    expect(isValidSessionName('x'.repeat(50))).toBe(true)
  })
  it('rejects what the daemon would refuse or rewrite', () => {
    expect(isValidSessionName('')).toBe(false)
    expect(isValidSessionName('Upper')).toBe(false)
    expect(isValidSessionName('-leading')).toBe(false)
    expect(isValidSessionName('.leading')).toBe(false)
    expect(isValidSessionName('has space')).toBe(false)
    expect(isValidSessionName('x'.repeat(51))).toBe(false)
  })
})

describe('routes', () => {
  it('builds screen URLs with only the requested params', () => {
    expect(routes.screen('dev', { cols: 200, rows: 50, hash: 'abc', waitMs: 25000 })).toBe(
      '/v1/sessions/dev/screen?cols=200&rows=50&hash=abc&wait=25000',
    )
    expect(routes.screen('dev')).toBe('/v1/sessions/dev/screen')
    expect(routes.screen('dev', { force: true })).toBe('/v1/sessions/dev/screen?force=1')
    expect(routes.screen('dev', { history: 500 })).toBe('/v1/sessions/dev/screen?history=500')
  })

  it('escapes path segments', () => {
    expect(routes.session('a.b')).toBe('/v1/sessions/a.b')
    expect(routes.chat('123e4567-e89b-12d3-a456-426614174000')).toBe(
      '/v1/chats/123e4567-e89b-12d3-a456-426614174000',
    )
  })

  it('builds watch URLs for both the long poll and the stream', () => {
    expect(routes.watch({ hash: 'h1', waitMs: 300000 })).toBe('/v1/watch?hash=h1&wait=300000')
    expect(routes.watch({ stream: true })).toBe('/v1/watch?stream=1')
    expect(routes.watch()).toBe('/v1/watch')
  })

  it('builds chat stream and transcript URLs', () => {
    expect(routes.chatStream('id1', 42)).toBe('/v1/chats/id1/stream?since=42')
    expect(routes.chatMessages('id1', true)).toBe('/v1/chats/id1/messages?stream=1')
    expect(routes.chatMessages('id1')).toBe('/v1/chats/id1/messages')
    expect(routes.sessionTranscript('dev', { offset: 1108213, limit: 400 })).toBe(
      '/v1/sessions/dev/transcript?offset=1108213&limit=400',
    )
    expect(routes.sessionTranscript('dev')).toBe('/v1/sessions/dev/transcript')
  })

  it('encodes upload names', () => {
    expect(routes.uploads('my file.png')).toBe('/v1/uploads?name=my%20file.png')
    expect(routes.uploads()).toBe('/v1/uploads')
  })
})
