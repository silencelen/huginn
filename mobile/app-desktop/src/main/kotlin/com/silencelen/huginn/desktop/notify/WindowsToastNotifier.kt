package com.silencelen.huginn.desktop.notify

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real Windows toasts — the only path from a plain JVM that gets ANSWER BUTTONS.
 *
 * There is no JDK API for this. `TrayIcon.displayMessage` produces a shell
 * balloon with no actions and no way to take it back, and the owner's whole
 * reason for a desktop client is answering a blocking question without going to
 * find the machine. So this drives the same WinRT API the Electron client used —
 * `Windows.UI.Notifications.ToastNotificationManager` — through `powershell.exe`,
 * with the toast XML written to a temp file rather than quoted into a command
 * line (a question containing a quote would otherwise be a broken toast, and a
 * broken toast is a "needs you" that silently never appears).
 *
 * Buttons activate by PROTOCOL, not COM: `huginn://answer?…&fp=…`. That is what
 * makes them work from the lock screen with nothing registered but a URL scheme,
 * and the fingerprint is what makes them safe — see [Activations.parse].
 *
 * ### What has to be true for this to work
 *
 * 1. **An AUMID that matches an installed Start Menu shortcut.** Windows files a
 *    toast under the calling application's identity, and drops it on the floor —
 *    silently, no error — when that identity matches no shortcut. This is the
 *    exact reason no notification ever appeared in the Electron client's field
 *    use before `setAppUserModelId` was added. The NSIS installer must stamp
 *    [AUMID] onto the shortcut it creates; nothing this process does at runtime
 *    can substitute for that. Releases 0.3.1 and earlier stamped NOTHING — the
 *    hand-written installer that replaced electron-builder's lost the step, and
 *    because the loss is invisible from this side (see [createOrNull]) it shipped
 *    and reported itself healthy. `packaging/huginn-desktop-kt.nsi` stamps it
 *    now, and `scripts/release-desktop.sh` refuses to publish an installer whose
 *    stamped identity is not this exact string.
 * 2. **The `huginn` scheme registered**, or the buttons do nothing when clicked.
 * 3. **A packaged install.** Running from Gradle there is no shortcut and no
 *    identity, so [createOrNull] refuses rather than posting into a void.
 *
 * ### Verification status
 *
 * NOT VERIFIED ON WINDOWS. There is no Windows machine in this dev loop; the
 * script, the XML and the fallback were written and read, not run. Everything
 * here fails CLOSED: the probe runs once at startup and returns null on any
 * error, each post checks the exit code, and a failure marks the backend dead so
 * the next notification takes the AWT balloon path instead of vanishing.
 */
class WindowsToastNotifier private constructor(private val script: File) : Notifier {

    override val name: String = "windows-toast"

    override val supportsActions: Boolean get() = true

    override val supportsWithdraw: Boolean get() = true

    override val healthy: Boolean get() = !failed

    /**
     * Set the first time a post fails. One dead backend that keeps being asked is
     * one notification lost per event; the router's caller re-reads this and
     * moves to the fallback.
     */
    @Volatile
    var failed: Boolean = false
        private set

    /**
     * Keys with a toast currently posted. Without it every navigation in the app
     * would spawn a PowerShell process to withdraw a notification that was never
     * shown — the acknowledgement path runs on every view change.
     */
    private val live = HashSet<String>()

    override fun post(request: NotifyRequest) {
        synchronized(live) { live += request.key }
        val tag = tagFor(request.key)
        val xml = if (request.urgent) attentionXml(request) else finishedXml(request)
        val file = runCatching {
            File.createTempFile("huginn-toast-", ".xml").also {
                it.writeText(xml, Charsets.UTF_8)
                it.deleteOnExit()
            }
        }.getOrNull() ?: run { failed = true; return }
        try {
            val ok = run(
                listOf(
                    "-Action", "show",
                    "-Aumid", AUMID,
                    "-XmlPath", file.absolutePath,
                    "-Tag", tag,
                )
            )
            if (!ok) failed = true
        } finally {
            file.delete()
        }
    }

    override fun withdraw(key: String) {
        if (!synchronized(live) { live.remove(key) }) return
        run(listOf("-Action", "remove", "-Aumid", AUMID, "-Tag", tagFor(key)))
    }

    private fun run(args: List<String>): Boolean = runCatching {
        val cmd = listOf(
            POWERSHELL, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
        ) + args
        val p = ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.outputStream.close()
        if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------- XML

    private fun attentionXml(request: NotifyRequest): String {
        // Up to three buttons, matching the phone's lock-screen split. The
        // fingerprint rides on every one; with none, there are no buttons at all
        // rather than buttons that answer whatever is on the pane.
        val fp = request.fingerprint
        val actions = if (fp.isNullOrEmpty()) "" else request.options.take(3).joinToString("") { o ->
            val url = Activations.answerUrl(sessionOf(request), o.number, fp)
            """<action content="${esc("${o.number}. ${o.label}".take(40))}" activationType="protocol" arguments="${esc(url)}"/>"""
        }
        return buildString {
            append("""<toast activationType="protocol" launch="${esc(Activations.openUrl(request.target))}" scenario="reminder">""")
            append("""<visual><binding template="ToastGeneric">""")
            append("<text>${esc(request.title.take(100))}</text>")
            append("<text>${esc(request.body.take(200))}</text>")
            append("</binding></visual>")
            append("<actions>$actions</actions>")
            append("""<audio src="ms-winsoundevent:Notification.Default"/>""")
            append("</toast>")
        }
    }

    private fun finishedXml(request: NotifyRequest): String = buildString {
        append("""<toast activationType="protocol" launch="${esc(Activations.openUrl(request.target))}">""")
        append("""<visual><binding template="ToastGeneric">""")
        append("<text>${esc(request.title.take(100))}</text>")
        append("<text>${esc(request.body.take(200))}</text>")
        append("</binding></visual>")
        append("""<audio silent="true"/>""")
        append("</toast>")
    }

    private fun sessionOf(request: NotifyRequest): String = request.target.id

    companion object {
        private const val POWERSHELL = "powershell.exe"
        private const val TIMEOUT_MS = 10_000L

        /**
         * MUST equal the `System.AppUserModel.ID` the NSIS installer stamps on the
         * Start Menu shortcut. If the two ever disagree, Windows drops every toast
         * without an error and the client looks silent rather than broken.
         *
         * `release-desktop.sh` reads this literal out of this file and compares it
         * to the one the compiled installer stamps, so the two cannot drift — but
         * it reads it as TEXT. Keep it a plain string literal on one line.
         */
        const val AUMID: String = "com.silencelen.huginn.desktop-kt"

        /** Toast group, so `History.Remove` can be scoped rather than global. */
        private const val GROUP = "huginn"

        fun createOrNull(configDir: File, packaged: Boolean): WindowsToastNotifier? {
            if (!System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) return null
            // Unpackaged there is no Start Menu shortcut carrying the AUMID, so the
            // toast would be accepted and never shown. Falling back to a balloon is
            // the honest behaviour for a dev run.
            if (!packaged) return null
            val script = runCatching {
                configDir.mkdirs()
                File(configDir, "huginn-toast.ps1").also { it.writeText(SCRIPT, Charsets.UTF_8) }
            }.getOrNull() ?: return null
            val notifier = WindowsToastNotifier(script)
            // Probe ONCE, for real: load the WinRT types and construct a notifier
            // for our AUMID. Anything that throws here — no WinRT, policy blocking
            // PowerShell — means this path would have swallowed notifications
            // silently, so we refuse it and take the balloon instead.
            //
            // What it does NOT cover, and cannot: an AUMID no shortcut carries.
            // CreateToastNotifier accepts any string; the drop happens later, at
            // display time, with a zero exit code. So `healthy` stays true and
            // every post reports success while nothing reaches the screen. That
            // hole is closed in the INSTALLER and gated in the release script,
            // because there is nothing to test for here.
            val ok = notifier.run(listOf("-Action", "probe", "-Aumid", AUMID))
            return if (ok) notifier else null
        }

        /**
         * C0 controls and lone surrogates are ILLEGAL in XML. One raw byte out of
         * tool output would make the whole toast unparseable, and the failure mode
         * is a "needs you" that never appears — so they are stripped before the
         * ordinary entity escaping.
         */
        internal fun esc(s: String): String {
            val sb = StringBuilder(s.length + 16)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isHighSurrogate() -> {
                        if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                            sb.append(c).append(s[i + 1]); i++
                        }
                        // else: a lone high surrogate, dropped
                    }
                    c.isLowSurrogate() -> Unit // lone low surrogate, dropped
                    c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                    c == '\u007F' -> Unit
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' -> sb.append("&quot;")
                    c == '\'' -> sb.append("&apos;")
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        /**
         * A toast tag is bounded (16 characters in the original contract, 64 since
         * Anniversary Update) and is also a PowerShell argument. Session names and
         * chat ids are neither bounded nor plain, so the tag is always a HASH of
         * the key rather than the key made safe — deterministic across a post and
         * its later withdraw, which is the only property a tag needs, and nothing
         * human ever reads it.
         */
        internal fun tagFor(key: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
            return "h" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /**
         * Written to disk rather than passed as `-Command`: a script FILE keeps the
         * XML out of any command line entirely, which is what stops a question
         * containing a quote from becoming a syntax error nobody sees.
         */
        private val SCRIPT: String = """
            |param(
            |  [Parameter(Mandatory=${'$'}true)][string]${'$'}Action,
            |  [string]${'$'}Aumid,
            |  [string]${'$'}XmlPath,
            |  [string]${'$'}Tag,
            |  [string]${'$'}Group = '$GROUP'
            |)
            |# Fail loudly to the exit code, quietly to the user: the JVM reads the
            |# exit code and falls back to a tray balloon. A dialog here would be a
            |# popup nobody asked for on a machine nobody is sitting at.
            |${'$'}ErrorActionPreference = 'Stop'
            |try {
            |  [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]
            |  [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime]
            |  switch (${'$'}Action) {
            |    'probe' {
            |      [void][Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}Aumid)
            |    }
            |    'show' {
            |      ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            |      ${'$'}xml.LoadXml([IO.File]::ReadAllText(${'$'}XmlPath, [Text.Encoding]::UTF8))
            |      ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}xml
            |      ${'$'}toast.Tag = ${'$'}Tag
            |      ${'$'}toast.Group = ${'$'}Group
            |      [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}Aumid).Show(${'$'}toast)
            |    }
            |    'remove' {
            |      [Windows.UI.Notifications.ToastNotificationManager]::History.Remove(${'$'}Tag, ${'$'}Group, ${'$'}Aumid)
            |    }
            |    default { exit 2 }
            |  }
            |  exit 0
            |} catch {
            |  exit 1
            |}
            |
        """.trimMargin()
    }
}
