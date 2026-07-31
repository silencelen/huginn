package com.silencelen.huginn.desktop.notify

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Registers this install as the handler for `huginn://`.
 *
 * A BACKSTOP, not the mechanism. The right place to register a URL scheme is the
 * installer, because the registration has to be true before the app has ever been
 * launched — the very first thing a toast button does is fire a scheme URL, and a
 * scheme nobody has claimed fails silently with no error anywhere. Doing it at
 * startup only covers a machine where the app has already run once.
 *
 * ### What the installers must do (phase 4 owns these files)
 *
 * **Windows / NSIS.** Write, at install time:
 * ```
 * HKCU\Software\Classes\huginn                      (default) = "URL:Huginn Protocol"
 * HKCU\Software\Classes\huginn                      "URL Protocol" = ""
 * HKCU\Software\Classes\huginn\shell\open\command   (default) = "\"<exe>\" \"%1\""
 * ```
 * HKCU rather than HKLM so a per-user install needs no elevation. The same
 * installer must ALSO stamp [WindowsToastNotifier.AUMID] as `System.AppUserModel.ID`
 * on the Start Menu shortcut — the two are one feature: without the AUMID the
 * toast is dropped, without the scheme its buttons do nothing.
 *
 * **Linux / deb.** Ship a `.desktop` file with `MimeType=x-scheme-handler/huginn;`
 * and let `update-desktop-database` pick it up. What [register] writes below is
 * the same thing into the per-user applications directory.
 *
 * Everything here is best-effort and quiet: a client that cannot register a scheme
 * is a client with no toast buttons, not a client that fails to start.
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

    private fun registerWindows(exe: String): String {
        val root = """HKCU\Software\Classes\${Activations.SCHEME}"""
        val ok = run(listOf("reg", "add", root, "/ve", "/d", "URL:Huginn Protocol", "/f")) &&
            run(listOf("reg", "add", root, "/v", "URL Protocol", "/d", "", "/f")) &&
            run(listOf("reg", "add", "$root\\shell\\open\\command", "/ve", "/d", "\"$exe\" \"%1\"", "/f"))
        return if (ok) "scheme huginn:// registered for this user" else "scheme registration failed (reg.exe)"
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
