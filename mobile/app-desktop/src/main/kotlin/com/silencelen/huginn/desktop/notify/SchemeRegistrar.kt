package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.desktop.update.LinuxInstall
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers this install as the handler for `huginn://`.
 *
 * A BACKSTOP, not the mechanism. The right place to register a URL scheme is the
 * installer, because the registration has to be true before the app has ever been
 * launched — the very first thing a toast button does is fire a scheme URL, and a
 * scheme nobody has claimed fails silently with no error anywhere. Doing it at
 * startup only covers a machine where the app has already run once. The NSIS
 * installer writes the same three values (see `packaging/huginn-desktop-kt.nsi`,
 * "huginn:// protocol handler"); this is what heals an install that predates it.
 *
 * ### ⚠⚠ NEVER PUT THE COMMAND VALUE ON A COMMAND LINE
 *
 * This is the bug that shipped, and it is worth the paragraph. The value is
 * `"<exe>" "%1"` — it contains BOTH spaces and interior double quotes. Java's
 * Windows process builder wraps a space-containing argument in quotes and, in its
 * default `allowAmbiguousCommands` mode, does NOT escape the interior ones, so
 * `reg add … /d "\"C:\…\app.exe\" \"%1\""` reached reg.exe as two arguments:
 *
 * ```
 * /d  C:\…\app.exe   %1   /f        → reg: Invalid syntax.  (exit 1)
 * ```
 *
 * The first two `reg add` calls have plain values and SUCCEEDED, so the machine
 * was left claiming the scheme (`URL Protocol` present) with no `shell\open\command`
 * to run — which is precisely the state where Windows answers a toast button with
 * "don't know how to open the link huginn" instead of failing quietly. Half a
 * registration is worse than none.
 *
 * So the values travel in a `.reg` FILE and go in through `reg import`, whose only
 * argument is a path. [windowsRegFile] is pure and tested; nothing about the value
 * is subject to anyone's quoting rules but the registry file format's own.
 *
 * ### The other half
 *
 * **Linux.** A `.desktop` file with `MimeType=x-scheme-handler/huginn;`, made the
 * default with `xdg-mime default` and indexed by `update-desktop-database`. The
 * Arch package ships one under `/usr/share/applications`; `huginn desktop install`
 * and [register] write the same per-user one. [LinuxScheme] VERIFIES what the
 * machine answers before and after, because registering is not the same as being
 * resolved — see its header for the field report.
 *
 * Everything here is best-effort and quiet: a client that cannot register a scheme
 * is a client with no toast buttons, not a client that fails to start. Quiet is
 * not SILENT, though — [register]'s sentence goes to [com.silencelen.huginn.desktop.diag.AppLog]
 * and so into "Copy diagnostics", and a link that will not open the app is one
 * line on Settings → Notifications ([linkStatus]), because this failing invisibly
 * for six weeks is the only reason it took a field report to find.
 */
object SchemeRegistrar {

    /**
     * Whether a `huginn://` link on this machine reaches this app, as the
     * registrar last proved it.
     *
     * @property ok the link opens this app.
     * @property sentence one line for the log and the Settings row — the
     *   contract's wording when [ok] is false and [relevant] is true.
     * @property relevant false for a run nothing could register for (a Gradle
     *   run, an unsupported platform): not a fault, nothing to show.
     */
    data class LinkStatus(val ok: Boolean, val sentence: String, val relevant: Boolean = true)

    private val _linkStatus = MutableStateFlow<LinkStatus?>(null)

    /** The last [register] verdict, for Settings → Notifications. Null before the first. */
    val linkStatus: StateFlow<LinkStatus?> = _linkStatus.asStateFlow()

    /**
     * Whether a launcher path is known — false when running from Gradle. ⚠ The
     * STABLE one: a portable install's `R/current/bin/…`, never the versioned dir
     * the kernel reports, or the scheme keeps opening the old version after an
     * update and nothing at all once the prune deletes it.
     */
    fun launcherPath(): String? = LinuxInstall.stableLauncherForThisProcess()

    fun register(): LinkStatus {
        val exe = launcherPath()
            // A Gradle run has no stable launcher to point the scheme at, and
            // registering one that will not exist tomorrow is worse than none.
            ?: return LinkStatus(false, "scheme not registered: unpackaged run, no launcher path", relevant = false).also { _linkStatus.value = it }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val status = when {
            os.startsWith("windows") -> registerWindows(exe)
            os.contains("linux") -> LinuxScheme.forThisUser().register(exe)
            else -> LinkStatus(false, "scheme not registered: unsupported platform", relevant = false)
        }
        _linkStatus.value = status
        return status
    }

    /** `HKEY_CURRENT_USER\Software\Classes\huginn` — per-user, so no elevation. */
    internal const val WINDOWS_ROOT: String = "Software\\Classes\\${Activations.SCHEME}"

    /** The one subkey whose absence produced the field report. */
    internal const val WINDOWS_COMMAND_KEY: String = "$WINDOWS_ROOT\\shell\\open\\command"

    /**
     * The registry file that claims the scheme for [exe]. PURE — this is the whole
     * of what gets written, and it is asserted character for character in
     * `SchemeRegistrarTest` because there is no Windows in this dev loop.
     *
     * CRLF and the `Windows Registry Editor Version 5.00` banner are both load
     * bearing: `reg import` refuses a file without the banner, and the file is
     * written UTF-16LE with a BOM (see [registerWindows]) so a profile path with
     * non-ASCII in it survives.
     */
    internal fun windowsRegFile(exe: String): String = buildString {
        append("Windows Registry Editor Version 5.00\r\n\r\n")
        append("[HKEY_CURRENT_USER\\$WINDOWS_ROOT]\r\n")
        append("@=\"URL:Huginn Protocol\"\r\n")
        // Empty, and its PRESENCE is the flag — this is what tells the shell the
        // key is a protocol handler rather than a file association.
        append("\"URL Protocol\"=\"\"\r\n\r\n")
        append("[HKEY_CURRENT_USER\\$WINDOWS_COMMAND_KEY]\r\n")
        append("@=\"${regEscape("\"$exe\" \"%1\"")}\"\r\n\r\n")
    }

    /**
     * `.reg` string-value escaping, which is only these two characters — `%1` is
     * literal in a REG_SZ and must NOT be touched.
     *
     * Backslash first: doing quotes first would then double the backslash this
     * function just added in front of them.
     */
    internal fun regEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun registerWindows(exe: String): LinkStatus {
        // A FIXED NAME, not File.createTempFile. Two reasons, and the second is
        // why it is written down: the file is imported and deleted inside this
        // function so a unique name buys nothing, and `createTempFile` is the one
        // call in this path that HANGS under wine — which is the only place the
        // whole chain can be exercised before it reaches the owner. A step that
        // cannot be rehearsed is how this bug shipped in the first place.
        // SingleInstance means there is no second writer to collide with.
        val file = File(System.getProperty("java.io.tmpdir") ?: ".", "huginn-scheme.reg")
        val wrote = runCatching {
            // UTF-16LE + BOM: what regedit itself exports, and the encoding
            // `reg import` reads without guessing — so a profile path with
            // non-ASCII in it is not a silently mangled command.
            file.outputStream().use { out ->
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(windowsRegFile(exe).toByteArray(Charset.forName("UTF-16LE")))
            }
            true
        }.getOrDefault(false)
        if (!wrote) return broken("huginn:// handler could not be written (${file.absolutePath})")

        try {
            if (!run(listOf("reg", "import", file.absolutePath))) {
                return broken("huginn:// handler could not be registered (reg import failed)")
            }
        } finally {
            file.delete()
        }

        // PROVEN, not reported. The bug this replaces reported success on two of
        // three writes and nothing read the third back; a `reg query` that exits
        // non-zero is the difference between a log line that is true and one that
        // is merely optimistic.
        return if (run(listOf("reg", "query", "HKCU\\$WINDOWS_COMMAND_KEY", "/ve"))) {
            LinkStatus(true, "scheme huginn:// registered for this user -> $exe")
        } else {
            broken("huginn:// has no handler (reg import reported success but the key is absent)")
        }
    }

    /**
     * The contract's one line for a link that will not open the app, with what
     * was found in the brackets. Same words on Settings → Notifications, in the
     * log and in "Copy diagnostics".
     */
    internal fun broken(found: String): LinkStatus =
        LinkStatus(false, "Notification links will not open the app: $found")

    /**
     * The per-user entry's name. ⚠ ONE NAME, SHARED WITH `huginn desktop install`
     * (client/huginn.sh writes the same file at install time, before the first
     * launch): two writers with two names would give the menu two Huginns and the
     * scheme two competing handlers.
     */
    const val LINUX_DESKTOP_FILE: String = "huginn-desktop-kt.desktop"

    /** What the scheme is registered as, for xdg-mime. */
    const val LINUX_MIME_TYPE: String = "x-scheme-handler/${Activations.SCHEME}"

    /**
     * The entry, entire. PURE — asserted in `SchemeRegistrarTest`. The icon comes
     * from beside the launcher (`<app>/lib/huginn-desktop-kt.png`, where Compose's
     * app-image puts it), so a portable install's icon follows `current` too.
     *
     * ⚠ THE EXEC PATH IS BARE UNLESS THE SPEC FORBIDS IT — see [execArgument].
     * It used to be quoted always, "for a home with a space in it", and that is
     * exactly what broke every notification click on the owner's Omarchy laptop
     * (2026-11-07): see [LinuxScheme].
     */
    internal fun linuxDesktopEntry(exe: String): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=Huginn")
        appendLine("Comment=huginn desktop client")
        appendLine("Exec=${execArgument(exe)} %u")
        File(exe).parentFile?.parentFile?.let { app -> appendLine("Icon=${File(app, LinuxInstall.ICON_REL).path}") }
        appendLine("Terminal=false")
        appendLine("Categories=Development;Utility;")
        appendLine("MimeType=$LINUX_MIME_TYPE;")
    }

    /**
     * The characters the Desktop Entry Specification reserves in an Exec
     * argument — an argument holding any of them MUST be quoted, and one holding
     * none must not be, because of who reads the line (see [LinuxScheme]).
     */
    internal const val EXEC_RESERVED: String = " \t\n\"'\\><~|&;$*?#()`"

    /**
     * [exe] as the first Exec argument: bare when the spec allows it, and quoted
     * with the spec's own escapes (`\` `"` `` ` `` `$` get a backslash) when a
     * reserved character leaves no choice. `%` is doubled in both forms, because
     * the field-code pass runs over the whole value.
     *
     * ⚠ `huginn desktop install` (client/huginn.sh, `_huginn_desktop_exec_arg`)
     * applies the same rule; the two must produce the same bytes.
     */
    internal fun execArgument(exe: String): String {
        val arg = if (exe.any { it in EXEC_RESERVED }) {
            "\"" + exe.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\""
        } else {
            exe
        }
        return arg.replace("%", "%%")
    }

    /**
     * The Linux half, with the machine injected so the rules are testable.
     *
     * ⚠⚠ REGISTERED IS NOT RESOLVED, AND THIS IS THE FIELD REPORT THAT PROVED IT
     * (Omarchy, Hyprland/Wayland, portable install, 2026-11-07). The per-user
     * entry was written, `~/.config/mimeapps.list` named it, `mimeinfo.cache`
     * indexed it — and every notification click opened a blank browser tab.
     * Under a desktop xdg-utils does not know (Hyprland is "generic"), `xdg-open`
     * resolves the handler with its OWN shell code: `xdg-mime query default`
     * takes the entry's Exec line, splits it on whitespace, and runs
     * `command -v` on the FIRST WORD. The Exec was `"/home/…/huginn-desktop-kt" %u`
     * — quoted — so the first word was `"/home/…` with a literal double quote,
     * `command -v` said no, the entry was skipped as "the binary is gone", and
     * `xdg-open` fell through to `$BROWSER`. GIO and KDE parse quotes to the
     * spec; the shell scripts every bare compositor falls back to do not.
     *
     * So the entry is bare where the spec allows and this class PROVES the
     * result instead of reporting the write: query → write/register when it is
     * not ours → query again, plus the same first-word check xdg-utils makes,
     * which is the only way to see this bug from inside the app.
     */
    class LinuxScheme(
        private val appsDir: File,
        /** Runs a command for its exit status; false when it cannot run, times out or exits non-zero. */
        private val run: (List<String>) -> Boolean,
        /** Runs a command for its stdout; null when it cannot run, times out or exits non-zero. */
        private val query: (List<String>) -> String?,
        private val onPath: (String) -> Boolean,
        /** Whether xdg-open's `command -v` would accept this word as something to run. */
        private val canExecute: (String) -> Boolean = { File(it).canExecute() },
    ) {
        fun register(exe: String): LinkStatus {
            val entry = linuxDesktopEntry(exe)
            val file = File(appsDir, LINUX_DESKTOP_FILE)
            val haveXdgMime = onPath("xdg-mime")
            val haveDb = onPath("update-desktop-database")
            val before = if (haveXdgMime) defaultHandler() else null

            // The file follows `current` and carries the fix for an older entry,
            // so it is compared and rewritten when it differs — never rewritten
            // when it does not, which would churn the apps dir and its index at
            // every launch.
            val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            var indexed = false
            if (current != entry) {
                val written = runCatching {
                    appsDir.mkdirs()
                    file.writeText(entry)
                    true
                }.getOrDefault(false)
                if (!written) return broken("huginn:// handler could not be written ($file)")
                if (haveDb) { run(listOf("update-desktop-database", appsDir.absolutePath)); indexed = true }
            }
            if (before != LINUX_DESKTOP_FILE) {
                if (haveXdgMime) run(listOf("xdg-mime", "default", LINUX_DESKTOP_FILE, LINUX_MIME_TYPE))
                if (haveDb && !indexed) run(listOf("update-desktop-database", appsDir.absolutePath))
            }

            // The first-word check, BEFORE the query: a handler xdg-mime names
            // and xdg-open cannot run is the exact shape of the field report, and
            // the query alone passed it.
            launchProblem(entry, canExecute)?.let { return broken(it) }
            if (!haveXdgMime) return broken("huginn:// has no handler (xdg-utils is not installed, so nothing can route the link)")
            val after = defaultHandler()
            return if (after == LINUX_DESKTOP_FILE) {
                LinkStatus(true, "scheme huginn:// registered via $file (xdg-mime names it)")
            } else {
                broken("huginn:// has no handler (xdg-mime names ${after?.let { "'$it'" } ?: "nothing"} for $LINUX_MIME_TYPE)")
            }
        }

        private fun defaultHandler(): String? =
            query(listOf("xdg-mime", "query", "default", LINUX_MIME_TYPE))?.trim()?.takeIf { it.isNotEmpty() }

        companion object {
            /** The registrar for this user's XDG applications dir, running the real tools. */
            fun forThisUser(): LinuxScheme {
                val home = System.getProperty("user.home").orEmpty()
                val appsDir = File(
                    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share",
                    "applications",
                )
                return LinuxScheme(
                    appsDir = appsDir,
                    run = SchemeRegistrar::run,
                    query = { cmd -> LibnotifyNotifier.runQuiet(cmd, 5_000L) },
                    onPath = { bin -> LibnotifyNotifier.runQuiet(listOf("sh", "-c", "command -v '$bin'"), 2_000L) != null },
                )
            }

            /**
             * Why xdg-open could not launch [entry], or null when it can. PURE.
             *
             * Mirrors xdg-utils' `desktop_file_to_binary` / `search_desktop_file`:
             * the Exec value's first whitespace-delimited word is handed to
             * `command -v`. A word that opens with a quote never resolves, so a
             * launcher path the spec makes us quote (a space, a `$`) is one
             * xdg-open cannot run — said plainly, with the remedy, rather than
             * left for a blank browser tab to report.
             */
            fun launchProblem(entry: String, canExecute: (String) -> Boolean): String? {
                val exec = entry.lineSequence().firstOrNull { it.startsWith("Exec=") }?.removePrefix("Exec=")
                    ?: return "the desktop entry has no Exec line"
                val word = exec.trimStart().takeWhile { it != ' ' && it != '\t' }
                if (word.startsWith("\"")) {
                    return "the install path contains a character xdg-open cannot parse in a quoted Exec " +
                        "($exec) — reinstall under a path with no spaces or quotes"
                }
                if (word.isEmpty() || !canExecute(word.replace("%%", "%"))) {
                    return "xdg-open would run '${word.replace("%%", "%")}', which is not an executable file"
                }
                return null
            }
        }
    }

    internal fun run(cmd: List<String>): Boolean = runCatching {
        val p = ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.outputStream.close()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    }.getOrDefault(false)
}
