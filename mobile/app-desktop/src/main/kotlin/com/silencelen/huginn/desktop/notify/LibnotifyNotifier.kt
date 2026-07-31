package com.silencelen.huginn.desktop.notify

import java.util.concurrent.TimeUnit

/**
 * Linux desktop notifications through libnotify's `notify-send`, which is what
 * every Linux desktop already routes its own alerts through.
 *
 * Chosen over the AWT balloon on Linux for one reason that matters: it can TAKE A
 * NOTIFICATION DOWN. `notify-send --print-id` hands back the daemon's id and
 * `org.freedesktop.Notifications.CloseNotification` closes it, so a question
 * answered in tmux stops nagging from the shade — the withdraw rule the router is
 * built around is real here and fictional on the AWT path.
 *
 * What it does NOT get:
 *
 * - **No buttons.** `notify-send --action` needs `--wait`, which parks a process
 *   per live notification for as long as it is on screen, and the reply arrives
 *   on stdout of that process. That is a real design, and it is not this one: it
 *   is unverifiable from this dev box (no notification daemon here), and an
 *   unverified process-per-notification path in the always-on layer is a worse
 *   trade than a toast the reader clicks through to the app.
 * - **No click routing**, for the same reason.
 *
 * Availability is probed once, not assumed: a headless box, a container, or a
 * session with no notification daemon all have to fall through to the next
 * backend rather than swallow every alert.
 */
class LibnotifyNotifier private constructor(private val canClose: Boolean) : Notifier {

    override val name: String = if (canClose) "libnotify" else "libnotify(no-close)"

    override val supportsWithdraw: Boolean get() = canClose

    /** key → the daemon's notification id, so a replace or a close can find it. */
    private val live = HashMap<String, Long>()

    override fun post(request: NotifyRequest) {
        val cmd = ArrayList<String>()
        cmd += NOTIFY_SEND
        cmd += "--app-name=Huginn"
        cmd += "--print-id"
        // Replacing rather than stacking: a session whose question changes should
        // update its notification in place, not leave a queue of stale ones the
        // reader has to dismiss individually.
        synchronized(live) { live[request.key] }?.let { cmd += "--replace-id=$it" }
        if (request.urgent) {
            // critical is what makes a "needs you" persist rather than time out —
            // the question does not go away on a timer, so neither should the
            // notification. Most daemons also play a sound for it.
            cmd += "--urgency=critical"
            cmd += "--expire-time=0"
            cmd += "--icon=dialog-warning"
            cmd += "--hint=string:sound-name:message-new-instant"
        } else {
            cmd += "--urgency=low"
            cmd += "--expire-time=8000"
            cmd += "--icon=dialog-information"
            cmd += "--hint=string:suppress-sound:true"
        }
        // "--" so a title that starts with a dash is a title and not a flag.
        cmd += "--"
        cmd += request.title.take(120)
        cmd += request.body.take(400)

        val id = runQuiet(cmd, TIMEOUT_MS)?.trim()?.toLongOrNull()
        if (id != null) synchronized(live) { live[request.key] = id }
    }

    override fun withdraw(key: String) {
        val id = synchronized(live) { live.remove(key) } ?: return
        if (!canClose) return
        runQuiet(
            listOf(
                GDBUS, "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.CloseNotification",
                id.toString(),
            ),
            TIMEOUT_MS,
        )
    }

    companion object {
        private const val NOTIFY_SEND = "notify-send"
        private const val GDBUS = "gdbus"
        private const val TIMEOUT_MS = 4_000L

        /**
         * Null when this desktop cannot be reached this way — no `notify-send`, no
         * DISPLAY/WAYLAND_DISPLAY, or the binary is there but refuses to talk to a
         * bus that is not.
         */
        fun createOrNull(): LibnotifyNotifier? {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return null
            if (System.getenv("DISPLAY").isNullOrBlank() &&
                System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
            ) return null
            if (!onPath(NOTIFY_SEND)) return null
            return LibnotifyNotifier(canClose = onPath(GDBUS))
        }

        private fun onPath(binary: String): Boolean =
            runQuiet(listOf("sh", "-c", "command -v ${shellQuote(binary)}"), 2_000L) != null

        private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /**
         * Runs a command and returns its stdout, or null if it could not be run,
         * timed out, or exited non-zero. Everything here is best-effort: a
         * notification that fails must never be able to take the app with it.
         */
        private fun runQuiet(cmd: List<String>, timeoutMs: Long): String? = runCatching {
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            p.outputStream.close()
            val out = p.inputStream.readBytes().decodeToString()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) null else out
        }.getOrNull()
    }
}
