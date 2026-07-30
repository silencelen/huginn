// The transcript-merge cases from the Android app's ReattachPlanTest.kt (they
// share that file's concern: identity), plus the page-level carry-forward the
// desktop applies on every poll. Events are built through the wire parser so
// the fixtures stay honest about defaults.

import { describe, expect, it } from 'vitest'
import { parseTranscriptEvent, parseTranscriptPage } from '../../src/shared/api/types'
import {
  MAX_EVENTS, mergeTranscript, mergeTranscriptPage,
} from '../../src/shared/core/transcriptMerge'

const tev = (seq: number) => parseTranscriptEvent({ seq, kind: 'assistant', text: 'x' })

describe('mergeTranscript', () => {
  it('merging an incremental page renumbers it so seq stays unique', () => {
    // The daemon numbers every tail read from 0, so concatenated pages arrive
    // with repeated seqs — and seq is what row state and list keys are keyed on.
    const kept = [tev(0), tev(1), tev(2)]
    const incoming = [tev(0), tev(1)] // a fresh page, numbered from 0
    const merged = mergeTranscript(kept, incoming, 100)
    expect(merged.map((e) => e.seq)).toEqual([0, 1, 2, 3, 4])
  })

  it('the window stays capped and keeps climbing across a trim', () => {
    let window = [tev(0), tev(1), tev(2)]
    for (let i = 0; i < 3; i++) {
      window = mergeTranscript(window, [tev(0), tev(1)], 4)
      expect(window).toHaveLength(4)
      const seqs = window.map((e) => e.seq)
      expect(seqs).toEqual([...seqs].sort((a, b) => a - b))
      expect(new Set(seqs).size).toBe(window.length)
    }
  })

  it('the first page is taken as the server numbered it', () => {
    const merged = mergeTranscript([], [tev(0), tev(1)], 100)
    expect(merged.map((e) => e.seq)).toEqual([0, 1])
  })

  it('the default window cap matches the mobile client', () => {
    expect(MAX_EVENTS).toBe(600)
  })
})

describe('mergeTranscriptPage', () => {
  it('carries forward session-level fields a tail read no longer reports', () => {
    // A tail read only reports fields whose records happen to fall inside it,
    // so every one has to be carried forward or it reverts to null seconds
    // after the screen opens (missing `effort` is exactly why the mobile
    // effort control kept falling back to a placeholder).
    const first = parseTranscriptPage({
      events: [{ seq: 0, kind: 'assistant', text: 'x' }],
      title: 'Fixing the build',
      model: 'claude-opus-4',
      modelDisplay: 'Opus 4',
      effort: 'high',
      gitBranch: 'main',
      permissionMode: 'auto',
      cwd: '/root/netplan',
      state: 'running',
      claudeSessionId: 'abc-123',
      mode: 'code',
      lastActivityTs: 1000,
    })
    const tail = parseTranscriptPage({ events: [{ seq: 0, kind: 'assistant', text: 'y' }] })
    const merged = mergeTranscriptPage(first, tail)
    expect(merged.title).toBe('Fixing the build')
    expect(merged.model).toBe('claude-opus-4')
    expect(merged.modelDisplay).toBe('Opus 4')
    expect(merged.effort).toBe('high')
    expect(merged.gitBranch).toBe('main')
    expect(merged.permissionMode).toBe('auto')
    expect(merged.cwd).toBe('/root/netplan')
    expect(merged.state).toBe('running')
    expect(merged.claudeSessionId).toBe('abc-123')
    expect(merged.mode).toBe('code')
    expect(merged.lastActivityTs).toBe(1000)
    // And the events were appended and renumbered, not concatenated raw.
    expect(merged.events.map((e) => e.seq)).toEqual([0, 1])
  })

  it('a fresher value wins over the carried one', () => {
    const first = parseTranscriptPage({ title: 'Old title', effort: 'low' })
    const tail = parseTranscriptPage({ title: 'New title' })
    const merged = mergeTranscriptPage(first, tail)
    expect(merged.title).toBe('New title')
    expect(merged.effort).toBe('low')
  })

  it('truncated keeps the first page verdict, since a tail says nothing about the dropped head', () => {
    const first = parseTranscriptPage({ truncated: true })
    const tail = parseTranscriptPage({ truncated: false })
    expect(mergeTranscriptPage(first, tail).truncated).toBe(true)
  })

  it('the first page passes through untouched', () => {
    const page = parseTranscriptPage({ events: [{ seq: 3, kind: 'user', text: 'hi' }], title: 't' })
    expect(mergeTranscriptPage(null, page)).toBe(page)
  })

  it('the merged window honours the cap', () => {
    const first = parseTranscriptPage({
      events: [0, 1, 2].map((seq) => ({ seq, kind: 'assistant', text: 'x' })),
    })
    const tail = parseTranscriptPage({
      events: [0, 1].map((seq) => ({ seq, kind: 'assistant', text: 'x' })),
    })
    const merged = mergeTranscriptPage(first, tail, 4)
    expect(merged.events.map((e) => e.seq)).toEqual([1, 2, 3, 4])
  })
})
