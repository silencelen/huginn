package com.silencelen.huginn.desktop.update

/**
 * What "Install and restart" does with the answer [DesktopUpdater.install] hands
 * back — the caller's half of that method's contract, kept out of the click
 * handler so the judgment in it can be tested without a window.
 *
 * QUITTING IS WHAT MAKES THE UPDATE LAND, not politeness. The installer has to
 * replace files this process has open, so its own first act is to close this
 * client (`EnsureNotRunning` in huginn-desktop-kt.nsi: it asks, and then, because
 * this app hides to the tray rather than exiting, it ends the process outright).
 * On THIS path that is a process ending its own ancestor — the installer was
 * started by this JVM and is a child of it, so the force branch's
 * `taskkill /F /T` walks a tree the installer is standing in. Leaving first means
 * there is nothing for it to find, no tree to kill, and the checked box on its
 * finish page is what brings the app back.
 *
 * THE INSTALLER SURVIVES THIS. A child process outlives the parent that spawned
 * it on both platforms this ships to; exiting here does not cancel the install,
 * it only stops holding the files.
 *
 * ONLY ON A REAL LAUNCH, though. If nothing started, this client is the only
 * thing left that can say so — the Settings error line is on screen and a window
 * that vanishes instead looks exactly like an update that worked.
 *
 * @return whether [quit] was called, so a caller can act on the same answer.
 */
fun installThenQuit(install: () -> Boolean, quit: () -> Unit): Boolean {
    // A throw is a failure to install, not a reason to take the window down with
    // it. install() promises never to throw into a click handler; this is the
    // belt to that brace, because being wrong here costs the app on a machine
    // where nothing was ever spawned to bring it back.
    val started = runCatching { install() }.getOrDefault(false)
    if (started) quit()
    return started
}
