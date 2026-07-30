// In-app dialogs. Electron does not implement window.prompt() — it THROWS —
// so every rename in the app was silently dead. window.confirm/alert do work
// but block the renderer and lose focus on Windows, so they go too.

import { useEffect, useRef, useState } from 'react'

function Shell(props: {
  title: string
  children: React.ReactNode
  onCancel: () => void
}): React.JSX.Element {
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') props.onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [props])

  return (
    <div className="dlg-backdrop" onMouseDown={props.onCancel}>
      <div className="dlg" onMouseDown={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="dlg-title">{props.title}</div>
        {props.children}
      </div>
    </div>
  )
}

export function InputDialog(props: {
  title: string
  label?: string
  initial?: string
  confirmLabel?: string
  onSubmit: (value: string) => void
  onCancel: () => void
}): React.JSX.Element {
  const [value, setValue] = useState(props.initial ?? '')
  const ref = useRef<HTMLInputElement | null>(null)
  useEffect(() => {
    ref.current?.focus()
    ref.current?.select()
  }, [])

  const submit = (): void => {
    const v = value.trim()
    if (v === '') return
    props.onSubmit(v)
  }

  return (
    <Shell title={props.title} onCancel={props.onCancel}>
      {props.label !== undefined ? <div className="dlg-label">{props.label}</div> : null}
      <input
        ref={ref}
        className="dlg-input"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            submit()
          }
        }}
      />
      <div className="dlg-actions">
        <button type="button" onClick={props.onCancel}>
          Cancel
        </button>
        <button type="button" className="dlg-primary" disabled={value.trim() === ''} onClick={submit}>
          {props.confirmLabel ?? 'Save'}
        </button>
      </div>
    </Shell>
  )
}

export function ConfirmDialog(props: {
  title: string
  body: string
  confirmLabel?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}): React.JSX.Element {
  const ref = useRef<HTMLButtonElement | null>(null)
  useEffect(() => ref.current?.focus(), [])
  return (
    <Shell title={props.title} onCancel={props.onCancel}>
      <div className="dlg-body">{props.body}</div>
      <div className="dlg-actions">
        <button type="button" onClick={props.onCancel}>
          Cancel
        </button>
        <button
          ref={ref}
          type="button"
          className={props.danger === true ? 'dlg-danger' : 'dlg-primary'}
          onClick={props.onConfirm}
        >
          {props.confirmLabel ?? 'OK'}
        </button>
      </div>
    </Shell>
  )
}
