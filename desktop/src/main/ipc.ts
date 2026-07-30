// Binds the typed IPC contract to the appd modules. This is the only ipcMain
// surface in the app; every handler is registered here so the attack surface
// reachable from a compromised renderer is enumerable in one screen.

import { app, ipcMain, Notification, type WebContents } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import { buildDiagnostics } from './diagnostics'
import { log } from './log'
import { buildAttentionToast, winToastsUsable } from './notify/toasts-win'
import type { InvokeApi } from '../shared/ipc/contract'
import { parseUploadResult } from '../shared/api/types'
import { routes } from '../shared/api/routes'
import type { AppdClient } from './appd/client'
import type { Chats } from './appd/chats'
import type { Host } from './appd/host'
import type { Sessions } from './appd/sessions'
import type { WatchLoop } from './appd/watch'
import type { Settings } from './settings'
import type { Updater } from './updater'

export interface IpcDeps {
  settings: Settings
  client: () => AppdClient
  chats: Chats
  sessions: Sessions
  host: Host
  watch: WatchLoop
  updater: Updater
  /** Most recent unexpected failure, for the diagnostics blob. */
  lastError: () => string | null
}

type Handler<C extends keyof InvokeApi> = (
  wc: WebContents,
  ...args: InvokeApi[C]['args']
) => InvokeApi[C]['result'] | Promise<InvokeApi[C]['result']>

export function registerIpc(deps: IpcDeps): void {
  const { settings, chats, sessions, host, watch, updater } = deps

  const handle = <C extends keyof InvokeApi>(channel: C, fn: Handler<C>): void => {
    ipcMain.handle(channel, (event, ...args) =>
      fn(event.sender, ...(args as InvokeApi[C]['args'])),
    )
  }

  handle('app.version', () => app.getVersion())
  handle('settings.get', () => settings.view())
  handle('settings.update', (_wc, patch) => settings.update(patch))
  handle('drafts.get', (_wc, key) => settings.getDraft(key))
  handle('drafts.set', (_wc, key, text) => settings.setDraft(key, text))

  handle('host.ping', () => host.ping())
  handle('host.status', () => host.status())
  handle('host.plan', () => host.plan())
  handle('host.usage', () => host.usage())
  handle('host.models', () => host.models())
  handle('host.clients', () => host.clients())
  handle('host.alerts.get', () => host.alertsGet())
  handle('host.alerts.set', (_wc, body) => host.alertsSet(body))

  handle('account.current', () => host.account())
  handle('account.saved', (_wc, plan) => host.savedAccounts(plan))
  handle('account.activate', (_wc, slug) => host.activateAccount(slug))
  handle('account.forget', (_wc, slug) => host.forgetAccount(slug))
  handle('account.login.start', (_wc, email) => host.loginStart(email))
  handle('account.login.state', () => host.loginState())
  handle('account.login.code', (_wc, code) => host.loginCode(code))
  handle('account.autoswitch', () => host.autoswitch())

  handle('chats.list', () => chats.list())
  handle('chats.create', (_wc, opts) => chats.create(opts))
  handle('chats.get', (_wc, id) => chats.get(id))
  handle('chats.patch', (_wc, id, patch) => chats.patch(id, patch))
  handle('chats.delete', (_wc, id) => chats.delete(id))
  handle('chats.send', (_wc, id, text) => chats.send(id, text))
  handle('chats.cancel', (_wc, id) => chats.cancel(id))
  handle('chats.transcript', (_wc, id, offset) => chats.transcript(id, offset))
  handle('chats.suggestions', (_wc, id) => chats.suggestions(id))

  handle('sessions.list', () => sessions.list())
  handle('sessions.create', (_wc, name) => sessions.create(name))
  handle('sessions.kill', (_wc, name) => sessions.kill(name))
  handle('sessions.rename', (_wc, name, to) => sessions.rename(name, to))
  handle('sessions.transcript', (_wc, name, offset) => sessions.transcript(name, offset))
  handle('sessions.suggestions', (_wc, name) => sessions.suggestions(name))
  handle('sessions.agents', (_wc, name) => sessions.agents(name))
  handle('sessions.keys', (_wc, name, body) => sessions.keys(name, body))
  handle('sessions.answer', (_wc, name, body) => sessions.answer(name, body))
  handle('sessions.screen.once', (_wc, name, opts) => sessions.screenOnce(name, opts))
  handle('sessions.releaseSize', (_wc, name) => sessions.releaseSize(name))

  handle('uploads.file', async (_wc, filePath) => {
    const stat = fs.statSync(filePath)
    const stream = fs.createReadStream(filePath)
    return parseUploadResult(
      await deps.client().request(routes.uploads(path.basename(filePath)), {
        method: 'POST',
        bodyStream: stream,
        contentLength: stat.size,
        tier: 'longPoll',
      }),
    )
  })
  handle('uploads.bytes', async (_wc, body) => {
    const buf = Buffer.from(body.dataBase64, 'base64')
    const { Readable } = await import('node:stream')
    return parseUploadResult(
      await deps.client().request(routes.uploads(body.name), {
        method: 'POST',
        bodyStream: Readable.from(buf),
        contentType: body.contentType,
        contentLength: buf.length,
        tier: 'longPoll',
      }),
    )
  })

  handle('diagnostics.text', async () => {
    let appdVersion: string | null = null
    try {
      appdVersion = (await host.ping()).version
    } catch (e) {
      appdVersion = `unreachable (${e instanceof Error ? e.message : String(e)})`
    }
    return buildDiagnostics({
      settings,
      update: updater.current(),
      watchConnected: watch.connected(),
      appdVersion,
      lastError: deps.lastError(),
    })
  })
  handle('diagnostics.testNotification', () => {
    if (!Notification.isSupported()) {
      return { shown: false, reason: 'this OS reports no notification support' }
    }
    if (!settings.getNotifyEnabled()) {
      return { shown: false, reason: 'notifications are switched off in Settings' }
    }
    // Deliberately the SAME toast path a real attention alert uses, so a
    // success here proves the real thing works (the AUMID problem made every
    // toast vanish silently and nothing in the app could tell).
    const n = winToastsUsable()
      ? buildAttentionToast('test', 'Notifications are working. Pick one:', [
          { number: 1, label: 'Good' },
          { number: 2, label: 'Also good' },
        ], null)
      : new Notification({
          title: 'Huginn notifications work',
          body: 'This is what a session alert will look like.',
        })
    n.show()
    log('info', 'notify', 'test notification fired')
    return { shown: true, reason: winToastsUsable() ? 'sent as a Windows toast' : 'sent' }
  })

  handle('update.state', () => updater.current())
  handle('update.check', () => updater.check())
  handle('update.install', () => updater.install())

  handle('chatStream.subscribe', (wc, chatId) => chats.subscribe(chatId, wc))
  handle('chatStream.unsubscribe', (_wc, id) => chats.unsubscribe(id))
  handle('watch.latest', () => ({ watch: watch.latest(), connected: watch.connected() }))
  handle('screenPoll.start', (wc, name, opts) => ({
    subscriptionId: sessions.startScreenPoll(name, wc, opts),
  }))
  handle('screenPoll.stop', (_wc, id) => sessions.stopScreenPoll(id))
}
