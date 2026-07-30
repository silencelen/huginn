// The chats list pane: newest first, live state dots, queued badges, and the
// two-mode New button (a chat's ask/act nature is chosen at creation).

import { useState } from 'react'
import type { Chat } from '../../shared/api/types'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'

const relTime = (epochSec: number): string => {
  if (epochSec <= 0) return ''
  const s = Math.max(0, Math.floor(Date.now() / 1000 - epochSec))
  if (s < 60) return 'now'
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86400) return `${Math.floor(s / 3600)}h`
  return `${Math.floor(s / 86400)}d`
}

function ChatRow({ chat, active }: { chat: Chat; active: boolean }): React.JSX.Element {
  const navigate = useApp((s) => s.navigate)
  const refreshChats = useApp((s) => s.refreshChats)
  return (
    <div
      className={`row ${active ? 'row-active' : ''}`}
      onClick={() => navigate({ view: 'chats', chatId: chat.id })}
    >
      <div className="row-line1">
        {chat.running ? <span className="pulse-dot" /> : null}
        {chat.mode === 'act' ? <span className="mode-chip mode-act">ACT</span> : null}
        <span className="row-title">{chat.title ?? 'Untitled'}</span>
        <span className="row-time">{relTime(chat.updatedAt)}</span>
      </div>
      <div className="row-line2">
        {chat.pending > 0 ? <span className="queued-badge">+{chat.pending} queued</span> : null}
        <span className="row-snippet">{chat.lastSnippet ?? ''}</span>
        <button
          type="button"
          className="row-delete"
          title="Delete chat"
          onClick={(e) => {
            e.stopPropagation()
            if (window.confirm('Delete this chat?')) {
              void call('chats.delete', chat.id)
                .then(() => refreshChats())
                .catch((err: unknown) => {
                  window.alert(err instanceof Error ? err.message : String(err))
                })
            }
          }}
        >
          ✕
        </button>
      </div>
    </div>
  )
}

export function ChatsList({ activeChatId }: { activeChatId: string | null }): React.JSX.Element {
  const chats = useApp((s) => s.chats)
  const navigate = useApp((s) => s.navigate)
  const refreshChats = useApp((s) => s.refreshChats)
  const [creating, setCreating] = useState(false)

  const create = (mode: 'ask' | 'act'): void => {
    setCreating(false)
    void call('chats.create', { mode })
      .then(async (chat) => {
        await refreshChats()
        navigate({ view: 'chats', chatId: chat.id })
      })
      .catch((err: unknown) => window.alert(err instanceof Error ? err.message : String(err)))
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
            <button type="button" className="danger" onClick={() => create('act')}>
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
      {chats.length === 0 ? <div className="pane-placeholder">No chats yet</div> : null}
      {chats.map((c) => (
        <ChatRow key={c.id} chat={c} active={c.id === activeChatId} />
      ))}
    </div>
  )
}
