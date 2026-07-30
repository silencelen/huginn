import { contextBridge, ipcRenderer } from 'electron'

// The one bridge between the sandboxed renderer and the main process. Typed on
// both sides by shared/ipc/contract.ts; this file is deliberately dumb — it
// forwards, it does not decide.

const PUSH_CHANNELS = new Set([
  'push.chatEvents',
  'push.watch',
  'push.screen',
  'push.listsChanged',
])

contextBridge.exposeInMainWorld('huginn', {
  invoke: (channel: string, ...args: unknown[]): Promise<unknown> =>
    ipcRenderer.invoke(channel, ...args),
  on: (channel: string, listener: (payload: unknown) => void): (() => void) => {
    if (!PUSH_CHANNELS.has(channel)) throw new Error(`unknown push channel: ${channel}`)
    const wrapped = (_event: unknown, payload: unknown): void => listener(payload)
    ipcRenderer.on(channel, wrapped)
    return () => ipcRenderer.removeListener(channel, wrapped)
  },
})
