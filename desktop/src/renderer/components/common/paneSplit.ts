// The list pane's width: bounds, parsing, and where it is kept.
//
// WHY localStorage AND NOT SETTINGS: every other preference in this app lives
// in main's settings store, reached over IPC — but adding a preference means
// adding an IPC channel and a field to the shared contract, and both of those
// are owned elsewhere right now. localStorage is renderer-local and survives
// restarts (Electron persists it per partition), which is all a pane width
// needs. If this ever has to follow the user to another machine, or be visible
// to main, it belongs in settings instead and this module is the one place to
// change.

export const LIST_W_MIN = 220
export const LIST_W_MAX = 560
export const LIST_W_DEFAULT = 300
export const LIST_W_KEY = 'huginn.desktop.listPaneWidth'

/** Bound a candidate width to something a list pane can actually be. */
export function clampListWidth(px: number): number {
  if (!Number.isFinite(px)) return LIST_W_DEFAULT
  return Math.min(LIST_W_MAX, Math.max(LIST_W_MIN, Math.round(px)))
}

/** Read back what was stored, tolerating anything at all in that slot. */
export function parseListWidth(raw: string | null): number {
  if (raw === null || raw.trim() === '') return LIST_W_DEFAULT
  const n = Number(raw)
  if (!Number.isFinite(n)) return LIST_W_DEFAULT
  return clampListWidth(n)
}

export function loadListWidth(): number {
  try {
    return parseListWidth(window.localStorage.getItem(LIST_W_KEY))
  } catch {
    // Storage disabled or unavailable: the default is a fine answer.
    return LIST_W_DEFAULT
  }
}

export function saveListWidth(px: number): void {
  try {
    window.localStorage.setItem(LIST_W_KEY, String(clampListWidth(px)))
  } catch {
    // A width that does not persist is worth less than a crash costs.
  }
}
