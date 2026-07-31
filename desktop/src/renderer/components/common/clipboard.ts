// Putting text on the clipboard, from a renderer that is not allowed to.
//
// THE BUG THIS FIXES: main installs a blanket permission denial —
//
//   session.defaultSession.setPermissionRequestHandler((_wc, _p, cb) => cb(false))
//   session.defaultSession.setPermissionCheckHandler(() => false)
//
// — which is the right default for an app that needs no camera, microphone or
// geolocation, but it also covers `clipboard-sanitized-write`. So every
// `navigator.clipboard.writeText` in this renderer rejects with "Write
// permission denied", and the terminal's Ctrl+C, whose only error handling was
// an empty catch, has never actually copied anything. It looked like it worked
// because the selection stayed highlighted.
//
// `document.execCommand('copy')` is gated on user activation rather than on the
// Permissions API, so it is unaffected by the handler above and does work. It
// is deprecated, hence the modern path first: the day main allows the
// permission, this quietly starts using the real API and the fallback stops
// being reached.

/** Copies through a detached textarea. Synchronous, and needs a user gesture. */
function execCommandCopy(text: string): boolean {
  const scratch = document.createElement('textarea')
  scratch.value = text
  scratch.setAttribute('readonly', '')
  // Off-screen but still focusable — `display: none` cannot be selected.
  scratch.style.position = 'fixed'
  scratch.style.top = '0'
  scratch.style.left = '-9999px'
  document.body.appendChild(scratch)
  // The terminal keeps a focused capture div for live typing; taking focus
  // away and not giving it back would swallow the user's next keystroke.
  const previous = document.activeElement
  let ok = false
  try {
    scratch.select()
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  scratch.remove()
  if (previous instanceof HTMLElement) previous.focus()
  return ok
}

/** True when the text is on the clipboard. Never throws. */
export async function copyText(text: string): Promise<boolean> {
  if (text === '') return false
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // Transient activation outlives the rejected promise, so the gesture that
    // started this is still good for the fallback.
    return execCommandCopy(text)
  }
}
