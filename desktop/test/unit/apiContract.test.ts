// Contract tests decoding CAPTURED real daemon responses (scrubbed), shared
// verbatim with the Android app's ApiContractTest — one set of fixture bytes is
// the cross-client contract. A renamed server field fails here before it fails
// in front of the owner.

import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  parseChatList, parseScreen, parseSessionList, parseStatus, parseTranscriptPage,
} from '../../src/shared/api/types'

const fixture = (name: string): unknown =>
  JSON.parse(
    readFileSync(new URL(`../../../mobile/app/src/test/resources/${name}`, import.meta.url), 'utf8'),
  )

describe('api contract (captured daemon fixtures)', () => {
  it('decodes the sessions list', () => {
    const sessions = parseSessionList(fixture('sessions.json'))
    expect(sessions.length).toBeGreaterThanOrEqual(2)
    const first = sessions[0]!
    expect(first.name).toBe('andrev')
    expect(first.cols).toBe(56)
    expect(first.rows).toBe(64)
    expect(first.state).toBe('idle')
    expect(first.windowSize).toBe('smallest')
    expect(first.sizeLeased).toBe(false)
    expect(first.hasTranscript).toBe(true)
    expect(first.attachedClients).toBe(1)
    expect(first.permissionMode).toBe('auto')
    expect(first.preview).toHaveLength(2)
    expect(first.title).not.toBeNull()
    // Fields the capture predates must default, not fail.
    expect(first.bgShells).toBe(0)
    expect(first.bgTask).toBeNull()
  })

  it('decodes the chat list', () => {
    const chats = parseChatList(fixture('chats.json'))
    expect(chats).toHaveLength(1)
    const chat = chats[0]!
    expect(chat.mode).toBe('ask')
    expect(chat.turns).toBe(1)
    expect(chat.running).toBe(false)
    expect(chat.pending).toBe(0)
    expect(chat.id).not.toBe('')
    expect(chat.title).not.toBeNull()
    expect(chat.lastSnippet).not.toBeNull()
  })

  it('decodes a screen frame', () => {
    const screen = parseScreen(fixture('screen.json'))
    expect(screen.width).toBe(56)
    expect(screen.height).toBe(64)
    expect(screen.cursorX).toBe(3)
    expect(screen.cursorY).toBe(59)
    expect(screen.altScreen).toBe(true)
    expect(screen.historySize).toBe(0)
    expect(screen.hash).toBe('e509b8fa')
    expect(screen.prompt).toBeNull()
    expect(screen.lines).toHaveLength(64)
    expect(screen.scrollback).toHaveLength(0)
    expect(screen.unchanged).toBe(false)
    // ANSI escapes must survive decoding byte-for-byte.
    expect(screen.lines[22]).toBe('  \u001b[1mWorth knowing\u001b[0m')
  })

  it('decodes host status', () => {
    const status = parseStatus(fixture('status.json'))
    expect(status.host).toBe('huginn')
    expect(status.appdVersion).toBe('2.0.0')
    expect(status.uptimeSec).toBe(31502)
    expect(status.load).toEqual([8.29, 8.84, 7.32])
    expect(status.cores).toBe(8)
    expect(status.claude).toContain('Claude Code')
    expect(status.mempalace).toBe('ok')
    expect(status.disk?.usedPercent).toBe('65%')
    expect(status.sessions).toBe(4)
    expect(status.chatsRunning).toBe(0)
  })

  it('decodes a transcript page', () => {
    const page = parseTranscriptPage(fixture('transcript.json'))
    expect(page.events).toHaveLength(8)
    expect(page.events[0]!.seq).toBe(19)
    expect(page.events[0]!.kind).toBe('assistant')
    const bash = page.events[3]!
    expect(bash.kind).toBe('tool')
    expect(bash.name).toBe('Bash')
    expect(bash.ok).toBe(true)
    expect(page.events[4]!.ok).toBe(false)
    expect(page.nextOffset).toBe(1108213)
    expect(page.truncated).toBe(true)
    expect(page.model).toBe('claude-opus-5')
    expect(page.gitBranch).toBe('main')
    expect(page.permissionMode).toBe('auto')
    expect(page.state).toBe('idle')
    // Absent fields decode to defaults, never throw.
    expect(page.running).toBe(false)
    expect(page.pending).toBe(0)
    expect(page.activity).toBeNull()
    expect(page.tasks).toEqual([])
  })

  it('tolerates unknown fields and wrong types without throwing', () => {
    const sessions = parseSessionList({
      sessions: [{ name: 'x', futureField: { deep: true }, cols: 'not-a-number' }],
      alsoNew: 1,
    })
    expect(sessions).toHaveLength(1)
    expect(sessions[0]!.name).toBe('x')
    expect(sessions[0]!.cols).toBe(0)
  })
})
