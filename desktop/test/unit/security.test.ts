// Regression tests for the audit's security findings. Each one names the
// attack it closes, so a future refactor that reopens it fails loudly.

import { describe, expect, it } from 'vitest'
import { parseActivation, activationFromArgv } from '../../src/main/notify/activation'
import { isAllowedBaseUrl } from '../../src/main/settings'
import { parseInline } from '../../src/shared/core/markdown'

describe('huginn:// activation', () => {
  it('REFUSES an answer without a fingerprint', () => {
    // Without this, any local process or clicked web link answers whatever
    // question is on the pane right now — on a root-equivalent agent host.
    expect(parseActivation('huginn://answer?session=dev&option=1')).toBeNull()
    expect(parseActivation('huginn://answer?session=dev&option=1&fp=')).toBeNull()
  })

  it('accepts a fingerprinted answer', () => {
    expect(parseActivation('huginn://answer?session=dev&option=2&fp=abc123')).toEqual({
      kind: 'answer',
      session: 'dev',
      option: 2,
      fingerprint: 'abc123',
    })
  })

  it('rejects malformed option numbers', () => {
    expect(parseActivation('huginn://answer?session=dev&option=0&fp=x')).toBeNull()
    expect(parseActivation('huginn://answer?session=dev&option=-1&fp=x')).toBeNull()
    expect(parseActivation('huginn://answer?session=dev&option=abc&fp=x')).toBeNull()
    expect(parseActivation('huginn://answer?session=&option=1&fp=x')).toBeNull()
  })

  it('parses open activations and rejects unknown verbs/views', () => {
    expect(parseActivation('huginn://open?view=chats&id=abc')).toEqual({
      kind: 'open',
      view: 'chats',
      id: 'abc',
    })
    expect(parseActivation('huginn://open?view=evil&id=abc')).toBeNull()
    expect(parseActivation('huginn://wipe?all=1')).toBeNull()
    expect(parseActivation('https://example.com')).toBeNull()
    expect(parseActivation('not a url')).toBeNull()
  })

  it('reads only the trailing non-flag argv entry', () => {
    expect(
      activationFromArgv(['Huginn.exe', '--flag', 'huginn://open?view=chats&id=z']),
    ).not.toBeNull()
    // Chromium switches must not be scanned for activations.
    expect(activationFromArgv(['Huginn.exe', '--enable-features=x'])).toBeNull()
  })
})

describe('baseUrl allowlist', () => {
  it('accepts huginn own addresses', () => {
    expect(isAllowedBaseUrl('http://100.97.198.90:8787')).toBe(true)
    expect(isAllowedBaseUrl('http://192.168.2.117:8787')).toBe(true)
    expect(isAllowedBaseUrl('http://localhost:8787')).toBe(true)
  })

  it('REFUSES a hostile host', () => {
    // The Bearer token follows baseUrl on every request; before this check a
    // single settings write handed the daemon token to any host.
    expect(isAllowedBaseUrl('http://evil.example.com:8787')).toBe(false)
    expect(isAllowedBaseUrl('http://100.97.198.90.evil.com')).toBe(false)
    expect(isAllowedBaseUrl('http://user@evil.com')).toBe(false)
    expect(isAllowedBaseUrl('file:///etc/passwd')).toBe(false)
    expect(isAllowedBaseUrl('javascript:alert(1)')).toBe(false)
    expect(isAllowedBaseUrl('not a url')).toBe(false)
    expect(isAllowedBaseUrl('')).toBe(false)
  })
})

describe('markdown link schemes', () => {
  it('drops the href on dangerous schemes but keeps the label', () => {
    const out = parseInline('[click me](javascript:alert(1))')
    expect(out.text).toContain('click me')
    const link = out.spans.find((s) => s.kind === 'link')
    expect(link?.href).toBeUndefined()
  })

  it('keeps http(s) and mailto hrefs', () => {
    expect(parseInline('[x](https://example.com)').spans.find((s) => s.kind === 'link')?.href).toBe(
      'https://example.com',
    )
    expect(parseInline('[x](mailto:a@b.c)').spans.find((s) => s.kind === 'link')?.href).toBe(
      'mailto:a@b.c',
    )
    expect(parseInline('[x](file:///etc/passwd)').spans.find((s) => s.kind === 'link')?.href)
      .toBeUndefined()
  })
})
