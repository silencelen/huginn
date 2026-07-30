// Is this window actually being looked at? Everything that polls the daemon
// asks first. Without it a minimized or closed-to-tray window kept a 5s list
// poll, a 2.5s transcript poll AND the pane long-poll running — the last of
// which HOLDS THE TMUX SIZE LEASE, so a hidden desktop could pin a session's
// window to desktop geometry indefinitely while the owner worked on it from a
// terminal. The phone learned the same lesson in its 2.0.1.

import { useEffect, useState } from 'react'

export function useWindowVisible(): boolean {
  const [visible, setVisible] = useState(!document.hidden)
  useEffect(() => {
    const update = (): void => setVisible(!document.hidden)
    document.addEventListener('visibilitychange', update)
    return () => document.removeEventListener('visibilitychange', update)
  }, [])
  return visible
}
