package com.silencelen.huginn.desktop.notify

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

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
 * **Linux / deb.** Ship a `.desktop` file with `MimeType=x-scheme-handler/huginn;`
 * and let `update-desktop-database` pick it up. What [register] writes below is
 * the same thing into the per-user applications directory.
 *
 * Everything here is best-effort and quiet: a client that cannot register a scheme
 * is a client with no toast buttons, not a client that fails to start. Quiet is
 * not SILENT, though — [register]'s sentence goes to [com.silencelen.huginn.desktop.diag.AppLog]
 * and so into "Copy diagnostics", because this failing invisibly for six weeks is
 * the only reason it took a field report to find.
 */
object SchemeRegistrar {

    /** Whether a launcher path is known — false when running from Gradle. */
    fun launcherPath(): String? = System.getProperty("jpackage.app-path")

    fun register(): String {
        val exe = launcherPath()
            // A Gradle run has no stable launcher to point the scheme at, and
            // registering one that will not exist tomorrow is worse than none.
            ?: return "scheme not registered: unpackaged run, no launcher path"
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.startsWith("windows") -> registerWindows(exe)
            os.contains("linux") -> registerLinux(exe)
            else -> "scheme not registered: unsupported platform"
        }
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

    private fun registerWindows(exe: String): String {
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
        if (!wrote) return "scheme registration failed: could not write ${file.absolutePath}"

        try {
            if (!run(listOf("reg", "import", file.absolutePath))) {
                return "scheme registration failed (reg import)"
            }
        } finally {
            file.delete()
        }

        // PROVEN, not reported. The bug this replaces reported success on two of
        // three writes and nothing read the third back; a `reg query` that exits
        // non-zero is the difference between a log line that is true and one that
        // is merely optimistic.
        return if (run(listOf("reg", "query", "HKCU\\$WINDOWS_COMMAND_KEY", "/ve"))) {
            "scheme huginn:// registered for this user -> $exe"
        } else {
            "scheme registration failed: reg import reported success but the key is absent"
        }
    }

    private fun registerLinux(exe: String): String {
        val home = System.getProperty("user.home") ?: return "scheme not registered: no home directory"
        val appsDir = File(
            System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share",
            "applications",
        )
        val name = "huginn-desktop-kt.desktop"
        val written = runCatching {
            appsDir.mkdirs()
            File(appsDir, name).writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=Huginn
                Comment=huginn desktop client
                Exec=${exe.replace("%", "%%")} %u
                Terminal=false
                Categories=Development;Utility;
                MimeType=x-scheme-handler/${Activations.SCHEME};

                """.trimIndent()
            )
            true
        }.getOrDefault(false)
        if (!written) return "scheme registration failed: could not write $appsDir/$name"
        // Best effort; neither tool is guaranteed present, and the .desktop file on
        // its own is enough for desktops that scan the directory.
        run(listOf("update-desktop-database", appsDir.absolutePath))
        run(listOf("xdg-mime", "default", name, "x-scheme-handler/${Activations.SCHEME}"))
        return "scheme huginn:// registered via $appsDir/$name"
    }

    private fun run(cmd: List<String>): Boolean = runCatching {
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
