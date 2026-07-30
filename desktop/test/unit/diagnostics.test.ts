// The diagnostics blob is meant to be pasted into a chat, so the one property
// that must never regress is: it carries no secret.

import { describe, expect, it } from 'vitest'

// The scrubber is the second line of defence inside log.ts; it is duplicated
// here as the contract under test because importing log.ts pulls in electron.
const scrub = (s: string): string =>
  s
    .replace(/Bearer\s+[\w.\-]+/gi, 'Bearer <redacted>')
    .replace(/[a-f0-9]{32,}/gi, '<hex-redacted>')

describe('log scrubbing', () => {
  it('redacts bearer tokens however they appear', () => {
    expect(scrub('failed with Authorization: Bearer abc123def456')).toContain('Bearer <redacted>')
    expect(scrub('bearer  sk-ant-oat01-XYZ')).not.toContain('sk-ant-oat01-XYZ')
  })

  it('redacts long hex strings (the daemon token shape)', () => {
    const token = 'a'.repeat(64)
    expect(scrub(`token=${token}`)).toBe('token=<hex-redacted>')
  })

  it('leaves ordinary messages alone', () => {
    const msg = 'watch stream lost: read timeout after 60s'
    expect(scrub(msg)).toBe(msg)
    expect(scrub('GET /v1/sessions 503')).toBe('GET /v1/sessions 503')
  })
})
