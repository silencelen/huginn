// The message composer: Enter sends, Shift+Enter breaks the line, drafts
// persist per target (survive navigation and restarts, like the phone), and
// the send button becomes Stop while a run is active with nothing typed —
// a suggestion is a draft, not a decision.

import { useEffect, useRef, useState } from 'react'
import { call } from '../../lib/ipc'

export function Composer(props: {
  draftKey: string
  running: boolean
  seedText: string | null
  onSeedConsumed: () => void
  onSend: (text: string) => void
  onStop: () => void
}): React.JSX.Element {
  const [text, setText] = useState('')
  const loadedFor = useRef<string | null>(null)
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    let alive = true
    loadedFor.current = null
    setText('')
    void call('drafts.get', props.draftKey).then((draft) => {
      if (alive) {
        setText(draft)
        loadedFor.current = props.draftKey
      }
    })
    return () => {
      alive = false
    }
  }, [props.draftKey])

  useEffect(() => {
    if (props.seedText !== null) {
      setText(props.seedText)
      props.onSeedConsumed()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.seedText])

  const persist = (value: string): void => {
    if (loadedFor.current !== props.draftKey) return
    if (saveTimer.current !== null) clearTimeout(saveTimer.current)
    saveTimer.current = setTimeout(() => {
      void call('drafts.set', props.draftKey, value)
    }, 400)
  }

  const send = (): void => {
    const t = text.trim()
    if (t === '') return
    setText('')
    void call('drafts.set', props.draftKey, '')
    props.onSend(t)
  }

  const showStop = props.running && text.trim() === ''
  return (
    <div className="composer">
      <textarea
        className="composer-input"
        rows={3}
        placeholder="Message huginn…"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          persist(e.target.value)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            send()
          }
        }}
      />
      {showStop ? (
        <button type="button" className="composer-btn composer-stop" onClick={props.onStop}>
          Stop
        </button>
      ) : (
        <button
          type="button"
          className="composer-btn composer-send"
          disabled={text.trim() === ''}
          onClick={send}
        >
          Send
        </button>
      )}
    </div>
  )
}
