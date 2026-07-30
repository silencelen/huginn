// The message composer: Enter sends, Shift+Enter breaks the line, drafts
// persist per target (survive restarts, like the phone), attachments arrive
// by paste, drop, or the clip button, and the send button becomes Stop while
// a run is active with nothing typed.

import { useEffect, useRef, useState } from 'react'
import { fileMarker, imageMarker } from '../../../shared/core/attachmentMarker'
import { isImageFile, transcodeImage } from '../../attach/transcode'
import { call } from '../../lib/ipc'

interface Attachment {
  label: string
  icon: '📷' | '📎'
  status: 'uploading' | 'ready' | 'error'
  marker: string | null
  settled: Promise<void>
}

export function Composer(props: {
  draftKey: string
  running: boolean
  seedText: string | null
  onSeedConsumed: () => void
  onSend: (text: string) => void
  onStop: () => void
}): React.JSX.Element {
  const [text, setText] = useState('')
  const [attachment, setAttachment] = useState<Attachment | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const loadedFor = useRef<string | null>(null)
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingSave = useRef<{ key: string; value: string } | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    let alive = true
    loadedFor.current = null
    // A pending save from the PREVIOUS target must not fire now: `persist`
    // resolves props.draftKey when the timer runs, so a late tick would write
    // the old text under the new key — one chat's draft landing in another.
    cancelPendingSave()
    setText('')
    setAttachment(null)
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

  // Closing a chat mid-debounce must not drop the last edit: flush the pending
  // value rather than cancelling it.
  useEffect(
    () => () => {
      if (saveTimer.current !== null) {
        clearTimeout(saveTimer.current)
        saveTimer.current = null
        const p = pendingSave.current
        if (p !== null) void call('drafts.set', p.key, p.value)
      }
    },
    [],
  )

  function cancelPendingSave(): void {
    if (saveTimer.current !== null) {
      clearTimeout(saveTimer.current)
      saveTimer.current = null
    }
    pendingSave.current = null
  }

  const persist = (value: string): void => {
    if (loadedFor.current !== props.draftKey) return
    cancelPendingSave()
    const key = props.draftKey
    pendingSave.current = { key, value }
    saveTimer.current = setTimeout(() => {
      saveTimer.current = null
      pendingSave.current = null
      void call('drafts.set', key, value)
    }, 400)
  }

  const attach = (label: string, icon: '📷' | '📎', work: Promise<string>): void => {
    let resolveSettled = (): void => {}
    const settled = new Promise<void>((resolve) => {
      resolveSettled = resolve
    })
    const a: Attachment = { label, icon, status: 'uploading', marker: null, settled }
    setAttachment(a)
    work
      .then((marker) => {
        setAttachment((cur) => (cur === a ? { ...a, status: 'ready', marker } : cur))
        a.marker = marker
        a.status = 'ready'
      })
      .catch(() => {
        setAttachment((cur) => (cur === a ? { ...a, status: 'error' } : cur))
        a.status = 'error'
      })
      .finally(resolveSettled)
  }

  const attachImageBlob = (blob: Blob, name: string): void => {
    attach(
      name,
      '📷',
      (async () => {
        const t = await transcodeImage(blob, name)
        const result = await call('uploads.bytes', {
          name: t.name,
          contentType: t.contentType,
          dataBase64: t.dataBase64,
        })
        return imageMarker(result.path)
      })(),
    )
  }

  const attachFile = (file: File): void => {
    if (isImageFile(file)) {
      attachImageBlob(file, file.name)
      return
    }
    attach(
      file.name,
      '📎',
      (async () => {
        const path = window.huginn.pathForFile(file)
        if (path === '') throw new Error('no path for dropped file')
        const result = await call('uploads.file', path)
        return fileMarker(result.path, file.name, result.readable)
      })(),
    )
  }

  const send = (): void => {
    const t = text.trim()
    if (t === '' && attachment === null) return
    const a = attachment
    setText('')
    setAttachment(null)
    // THE BUG THIS FIXES: the last keystroke's 400ms save was still pending and
    // fired AFTER this clear, re-saving the just-sent message as a draft — so
    // it reappeared in the box on the next visit.
    cancelPendingSave()
    void call('drafts.set', props.draftKey, '')
    void (async () => {
      if (a !== null) {
        // A send races its own upload: wait for it to settle (the daemon caps
        // uploads well under this) rather than sending a message about a file
        // that is not there yet.
        await Promise.race([a.settled, new Promise((r) => setTimeout(r, 20_000))])
      }
      const marker = a?.marker ?? null
      const full = marker === null ? t : t === '' ? marker : `${t}\n\n${marker}`
      if (full !== '') props.onSend(full)
    })()
  }

  const showStop = props.running && text.trim() === '' && attachment === null
  return (
    <div
      className={`composer ${dragOver ? 'composer-drag' : ''}`}
      onDragOver={(e) => {
        e.preventDefault()
        setDragOver(true)
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault()
        setDragOver(false)
        const file = e.dataTransfer.files[0]
        if (file !== undefined) attachFile(file)
      }}
    >
      {attachment !== null ? (
        <span className={`attach-chip attach-${attachment.status}`}>
          {attachment.icon} {attachment.label}
          {attachment.status === 'uploading' ? '…' : attachment.status === 'error' ? ' — failed' : ''}
          <button type="button" className="attach-remove" onClick={() => setAttachment(null)}>
            ✕
          </button>
        </span>
      ) : null}
      <button
        type="button"
        className="composer-attach"
        title="Attach a file"
        onClick={() => fileInput.current?.click()}
      >
        📎
      </button>
      <input
        ref={fileInput}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file !== undefined) attachFile(file)
          e.target.value = ''
        }}
      />
      <textarea
        className="composer-input"
        rows={3}
        placeholder="Message huginn…"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          persist(e.target.value)
        }}
        onPaste={(e) => {
          const item = [...e.clipboardData.items].find((i) => i.type.startsWith('image/'))
          const blob = item?.getAsFile()
          if (blob != null) {
            e.preventDefault()
            attachImageBlob(blob, 'pasted.png')
          }
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
          disabled={text.trim() === '' && (attachment === null || attachment.status === 'error')}
          onClick={send}
        >
          Send
        </button>
      )}
    </div>
  )
}
