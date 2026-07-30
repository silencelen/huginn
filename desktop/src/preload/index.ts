import { contextBridge, ipcRenderer } from 'electron'

// The one bridge between the sandboxed renderer and the main process. The
// renderer never sees the token, the base URL, or a socket — only this surface.
contextBridge.exposeInMainWorld('huginn', {
  version: (): Promise<string> => ipcRenderer.invoke('app:version'),
})
