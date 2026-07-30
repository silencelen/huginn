// Typed renderer-side wrapper over the preload bridge. All calls funnel
// through `call`, so the InvokeApi contract is enforced at every call site.

import type { InvokeApi, PushApi } from '../../shared/ipc/contract'

interface Bridge {
  invoke: (channel: string, ...args: unknown[]) => Promise<unknown>
  on: (channel: string, listener: (payload: unknown) => void) => () => void
  pathForFile: (file: File) => string
}

declare global {
  interface Window {
    huginn: Bridge
  }
}

export const call = async <C extends keyof InvokeApi>(
  channel: C,
  ...args: InvokeApi[C]['args']
): Promise<InvokeApi[C]['result']> =>
  window.huginn.invoke(channel, ...args) as Promise<InvokeApi[C]['result']>

export const on = <C extends keyof PushApi>(
  channel: C,
  listener: (payload: PushApi[C]) => void,
): (() => void) =>
  window.huginn.on(channel, (payload: unknown) => listener(payload as PushApi[C]))
