import { describe, expect, it } from 'vitest'
import { displayText, fileMarker, imageMarker } from '../../src/shared/core/attachmentMarker'

describe('attachment markers', () => {
  it('image marker names the Read tool', () => {
    expect(imageMarker('/var/lib/huginn-appd/uploads/up-1.jpg')).toBe(
      '[Attached image at /var/lib/huginn-appd/uploads/up-1.jpg — view it with the Read tool.]',
    )
  })

  it('file marker carries the original name and readability wording', () => {
    expect(fileMarker('/u/up-2.csv', 'data.csv', true)).toBe(
      '[Attached file at /u/up-2.csv (data.csv) — view it with the Read tool.]',
    )
    expect(fileMarker('/u/up-3.bin', 'router.bak', false)).toContain('Requires act mode.]')
    expect(fileMarker('/u/up-4.txt', null, true)).toBe(
      '[Attached file at /u/up-4.txt — view it with the Read tool.]',
    )
  })

  it('displayText renders markers for humans, not storage paths', () => {
    expect(displayText(`what is this?\n\n${imageMarker('/u/up-1.jpg')}`)).toBe(
      'what is this?\n\n📷 Photo attached',
    )
    expect(displayText(fileMarker('/u/up-2.csv', 'data.csv', true))).toBe('📎 data.csv')
    expect(displayText(fileMarker('/u/up-9.bin', null, false))).toBe('📎 File attached')
    expect(displayText('no markers here')).toBe('no markers here')
    expect(displayText(imageMarker('/u/only.jpg'))).toBe('📷 Photo attached')
  })
})
