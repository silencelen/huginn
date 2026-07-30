// Windows toast XML with answer buttons. Protocol activation (huginn://)
// rather than COM: bounded-choice buttons work from the lock of a toast with
// nothing but a registered URL scheme — the same owner rule as the phone's
// lock-screen answering: predetermined choices only, never free text.
//
// NEEDS ON-WINDOWS VERIFICATION (no Windows box in this dev loop): toastXml
// requires the app identity an NSIS install provides; unpackaged dev falls
// back to plain notifications via winToastsUsable().

import { app, Notification } from 'electron'

const esc = (s: string): string =>
  s
    // C0 controls and lone surrogates are illegal in XML: one raw byte from
    // tool output would make the whole toast unparseable, and the failure mode
    // is a "needs you" notification that silently never appears.
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')

export const winToastsUsable = (): boolean => process.platform === 'win32' && app.isPackaged

const openUrl = (view: string, id: string): string =>
  `huginn://open?view=${encodeURIComponent(view)}&id=${encodeURIComponent(id)}`

export function buildAttentionToast(
  session: string,
  question: string,
  options: { number: number; label: string }[],
  fingerprint: string | null,
): Notification {
  // Up to 3 answer buttons, like the phone's lock-screen split; the rest is a
  // tap-through to the app.
  const actions = options
    .slice(0, 3)
    .map((o) => {
      const args = `huginn://answer?session=${encodeURIComponent(session)}&option=${o.number}${
        fingerprint !== null ? `&fp=${encodeURIComponent(fingerprint)}` : ''
      }`
      return `<action content="${esc(`${o.number}. ${o.label}`.slice(0, 40))}" activationType="protocol" arguments="${esc(args)}"/>`
    })
    .join('')
  const toastXml =
    `<toast activationType="protocol" launch="${esc(openUrl('sessions', session))}">` +
    `<visual><binding template="ToastGeneric">` +
    `<text>${esc(`${session} needs you`)}</text>` +
    `<text>${esc(question.slice(0, 200))}</text>` +
    `</binding></visual>` +
    `<actions>${actions}</actions>` +
    `<audio src="ms-winsoundevent:Notification.Default"/>` +
    `</toast>`
  return new Notification({ toastXml })
}

export function buildFinishedToast(chatId: string, title: string, body: string): Notification {
  const toastXml =
    `<toast activationType="protocol" launch="${esc(openUrl('chats', chatId))}">` +
    `<visual><binding template="ToastGeneric">` +
    `<text>${esc(title.slice(0, 100))}</text>` +
    `<text>${esc(body.slice(0, 200))}</text>` +
    `</binding></visual>` +
    `<audio silent="true"/>` +
    `</toast>`
  return new Notification({ toastXml })
}
