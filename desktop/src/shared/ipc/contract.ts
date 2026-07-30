// The ONE typed surface between the sandboxed renderer and the main process.
// Everything crosses as invoke (request/response) or as a subscribed stream
// (snapshot + seq'd batches). The renderer never sees the token or a socket.

import type {
  Account, AgentsInfo, Alerts, AnswerResult, Autoswitch, Chat, ChatDetail, ChatEvent,
  ClientsInfo, LoginSession, LoginState, ModelChoice, Plan, SavedAccount, Screen,
  Session, Status, Suggestions, TranscriptPage, UploadResult, Usage, Watch,
} from '../api/types'
import type { SettingsView } from '../../main/settings'
import type { UpdateState } from '../../main/updater'

/** Result of sending to a chat: either a run started (and streams) or it queued. */
export interface SendOutcome {
  queued: boolean
  position: number | null
}

/**
 * A batch pushed to stream subscribers. seq is per-subscription and gap-free;
 * a gap (window reloaded, devtools paused) means "re-subscribe for a fresh
 * snapshot" — the same recover-by-resync contract the daemon's ?since= gives us.
 */
export interface StreamBatch<T> {
  subscriptionId: number
  seq: number
  items: T[]
}

/** Chat stream snapshot: everything so far, then batches of ChatEvent. */
export interface ChatStreamSnapshot {
  subscriptionId: number
  seq: number
  running: boolean
  /** Accumulated partial answer text for the in-flight turn. */
  partialText: string
  events: ChatEvent[]
}

export interface InvokeApi {
  'app.version': { args: []; result: string }
  'settings.get': { args: []; result: SettingsView }
  'settings.update': {
    args: [
      {
        baseUrl?: string
        token?: string
        notifyEnabled?: boolean
        launchAtLogin?: boolean
        closeToTray?: boolean
        terminalFontPx?: number
      },
    ]
    result: SettingsView
  }
  'drafts.get': { args: [key: string]; result: string }
  'drafts.set': { args: [key: string, text: string]; result: void }

  'host.ping': { args: []; result: { ok: boolean; version: string | null } }
  'host.status': { args: []; result: Status }
  'host.plan': { args: []; result: Plan }
  'host.usage': { args: []; result: Usage }
  'host.models': { args: []; result: ModelChoice[] }
  'host.clients': { args: []; result: ClientsInfo }
  'host.alerts.get': { args: []; result: Alerts }
  'host.alerts.set': { args: [{ enabled?: boolean; mode?: string }]; result: Alerts }

  'account.current': { args: []; result: Account }
  'account.saved': { args: [plan: boolean]; result: SavedAccount[] }
  'account.activate': { args: [slug: string]; result: void }
  'account.forget': { args: [slug: string]; result: void }
  'account.login.start': { args: [email: string | null]; result: LoginSession }
  'account.login.state': { args: []; result: LoginState }
  'account.login.code': { args: [code: string]; result: LoginState }
  'account.autoswitch': { args: []; result: Autoswitch }

  'chats.list': { args: []; result: Chat[] }
  'chats.create': {
    args: [{ mode: 'ask' | 'act'; title?: string; model?: string; effort?: string }]
    result: Chat
  }
  'chats.get': { args: [id: string]; result: ChatDetail }
  'chats.patch': {
    args: [id: string, patch: { title?: string; model?: string; effort?: string; mode?: string }]
    result: ChatDetail
  }
  'chats.delete': { args: [id: string]; result: void }
  'chats.send': { args: [id: string, text: string]; result: SendOutcome }
  'chats.cancel': { args: [id: string]; result: void }
  'chats.transcript': { args: [id: string, offset: number | null]; result: TranscriptPage }
  'chats.suggestions': { args: [id: string]; result: Suggestions }

  'sessions.list': { args: []; result: Session[] | null }
  'sessions.create': { args: [name: string]; result: void }
  'sessions.kill': { args: [name: string]; result: void }
  'sessions.rename': { args: [name: string, to: string]; result: void }
  'sessions.transcript': { args: [name: string, offset: number | null]; result: TranscriptPage }
  'sessions.suggestions': { args: [name: string]; result: Suggestions }
  'sessions.agents': { args: [name: string]; result: AgentsInfo }
  'sessions.keys': { args: [name: string, body: { text?: string; keys?: string[] }]; result: void }
  'sessions.answer': {
    args: [name: string, body: { option?: number; options?: number[]; fingerprint?: string }]
    result: AnswerResult
  }
  'sessions.screen.once': {
    args: [name: string, opts: { history?: number }]
    result: Screen
  }
  'sessions.releaseSize': { args: [name: string]; result: void }

  'uploads.file': { args: [filePath: string]; result: UploadResult }
  'uploads.bytes': {
    args: [{ name: string; contentType: string; dataBase64: string }]
    result: UploadResult
  }

  /** The renderer reports what the user is looking at (notification suppression). */
  'ui.viewed': { args: [target: { view: 'chats' | 'sessions'; id: string } | null]; result: void }

  'update.state': { args: []; result: UpdateState }
  'update.check': { args: []; result: void }
  'update.install': { args: []; result: void }

  'chatStream.subscribe': { args: [chatId: string]; result: ChatStreamSnapshot }
  'chatStream.unsubscribe': { args: [subscriptionId: number]; result: void }
  'watch.latest': { args: []; result: { watch: Watch | null; connected: boolean } }
  'screenPoll.start': {
    args: [name: string, opts: { cols?: number; rows?: number }]
    result: { subscriptionId: number }
  }
  'screenPoll.stop': { args: [subscriptionId: number]; result: void }
}

/** Push channels (main → renderer). Payloads by channel name. */
export interface PushApi {
  /** Coalesced chat-run events for a subscription. */
  'push.chatEvents': StreamBatch<ChatEvent>
  /** Latest watch digest (state changed). */
  'push.watch': { watch: Watch; connected: boolean }
  /** A screen frame for a screen-poll subscription (last-write-wins). */
  'push.screen': { subscriptionId: number; screen: Screen }
  /** Something about the chats/sessions lists changed; renderer should refresh. */
  'push.listsChanged': Record<string, never>
  /** Auto-update progress (packaged builds only). */
  'push.update': UpdateState
  /** Main asks the renderer to show a target (notification/tray/toast click). */
  'push.navigate': { view: 'chats' | 'sessions'; id: string }
}

export type InvokeChannel = keyof InvokeApi
export type PushChannel = keyof PushApi
