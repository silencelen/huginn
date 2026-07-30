// The chats list pane: newest first, live state dots, queued badges, and the
// two-mode New button (a chat's ask/act nature is chosen at creation). State
// dots speak the same language as the sessions list: pulsing accent = working.

import { useEffect, useRef, useState } from 'react'
import type { Chat } from '../../shared/api/types'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { ConfirmDialog, InputDialog } from '../components/common/Dialog'
import { useContextMenu } from '../components/common/ContextMenu'
import { createChat, useKeyboardNav } from '../hooks/useShortcuts'

const relTime = (epochSec: number): string => {
  if (epochSec <= 0) return ''
  const s = Math.max(0, Math.floor(Date.now() / 1000 - epochSec))
  if (s < 60) return 'now'
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86400) return `${Math.floor(s / 3600)}h`
  return `${Math.floor(s / 86400)}d`
}

function ChatRow(props: {
  chat: Chat
  active: boolean
  onRename: (chat: Chat) => void
  onDelete: (chat: Chat) => void
}): React.JSX.Element {
  const { chat, active } = props
  const navigate = useApp((s) => s.navigate)
  const open = (): void => navigate({ view: 'chats', chatId: chat.id })
  const ctx = useContextMenu()

  // Keyboard selection has to be visible and has to stay on screen; a pointer
  // click already put the row where the user was looking, so neither applies.
  const kbNav = useKeyboardNav()
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (active && kbNav) ref.current?.scrollIntoView({ block: 'nearest' })
  }, [active, kbNav])

  return (
    <div
      ref={ref}
      className={`row ${active ? 'row-active' : ''} ${active && kbNav ? 'row-selected' : ''}`}
      onClick={open}
      onContextMenu={ctx.onContextMenu}
    >
      <div className="row-line1">
        {chat.running ? <span className="state-dot dot-running dot-pulse" /> : null}
        <span className="row-title">{chat.title ?? 'Untitled'}</span>
        {chat.mode === 'act' ? <span className="mode-mark">act</span> : null}
        <span className="row-time">{relTime(chat.updatedAt)}</span>
      </div>
      <div className="row-line2">
        {chat.pending > 0 ? <span className="queued-badge">+{chat.pending} queued</span> : null}
        <span className="row-snippet">{chat.lastSnippet ?? ''}</span>
        <button
          type="button"
          className="row-action"
          title="Rename chat"
          onClick={(e) => {
            e.stopPropagation()
            props.onRename(chat)
          }}
        >
          ✎
        </button>
        <button
          type="button"
          className="row-delete"
          title="Delete chat"
          onClick={(e) => {
            e.stopPropagation()
            props.onDelete(chat)
          }}
        >
          ✕
        </button>
      </div>
      {ctx.menu([
        { label: 'Open', onClick: open },
        { label: 'Rename', onClick: () => props.onRename(chat) },
        { label: 'Delete', danger: true, onClick: () => props.onDelete(chat) },
      ])}
    </div>
  )
}

export function ChatsList({ activeChatId }: { activeChatId: string | null }): React.JSX.Element {
  const chats = useApp((s) => s.chats)
  const refreshChats = useApp((s) => s.refreshChats)
  const [creating, setCreating] = useState(false)
  const [renaming, setRenaming] = useState<Chat | null>(null)
  const [deleting, setDeleting] = useState<Chat | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The store starts with an empty list; wait for the first fetch to settle
  // before claiming "no chats" on a cold start.
  useEffect(() => {
    let alive = true
    void refreshChats().then(() => {
      if (alive) setLoaded(true)
    })
    return () => {
      alive = false
    }
  }, [refreshChats])

  const fail = (err: unknown): void => setError(err instanceof Error ? err.message : String(err))

  // Same implementation Ctrl+N and the palette use — see hooks/useShortcuts.
  const create = (mode: 'ask' | 'act'): void => {
    setCreating(false)
    void createChat(mode).catch(fail)
  }

  return (
    <div className="list">
      <div className="list-header">
        <span>Chats</span>
        {creating ? (
          <span className="new-mode-picker">
            <button type="button" onClick={() => create('ask')}>
              Ask
            </button>
            <button type="button" onClick={() => create('act')}>
              Act
            </button>
            <button type="button" onClick={() => setCreating(false)}>
              ✕
            </button>
          </span>
        ) : (
          <button type="button" className="list-new" onClick={() => setCreating(true)}>
            + New
          </button>
        )}
      </div>
      {error !== null ? (
        <div className="list-note" title="Dismiss" onClick={() => setError(null)}>
          {error}
        </div>
      ) : null}
      {chats.length === 0 ? (
        loaded ? (
          <div className="list-empty">
            No chats yet. New starts one: Ask answers questions, Act can make changes on the host.
          </div>
        ) : (
          <div className="list-empty">Loading chats…</div>
        )
      ) : null}
      {chats.map((c) => (
        <ChatRow
          key={c.id}
          chat={c}
          active={c.id === activeChatId}
          onRename={setRenaming}
          onDelete={setDeleting}
        />
      ))}
      {renaming !== null ? (
        <InputDialog
          title="Rename chat"
          label="Title"
          initial={renaming.title ?? ''}
          onSubmit={(title) => {
            const id = renaming.id
            setRenaming(null)
            void call('chats.patch', id, { title })
              .then(() => refreshChats())
              .catch(fail)
          }}
          onCancel={() => setRenaming(null)}
        />
      ) : null}
      {deleting !== null ? (
        <ConfirmDialog
          title="Delete chat"
          body={`Delete "${deleting.title ?? 'Untitled'}" and its transcript? This cannot be undone.`}
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const id = deleting.id
            setDeleting(null)
            void call('chats.delete', id)
              .then(() => refreshChats())
              .catch(fail)
          }}
          onCancel={() => setDeleting(null)}
        />
      ) : null}
    </div>
  )
}
