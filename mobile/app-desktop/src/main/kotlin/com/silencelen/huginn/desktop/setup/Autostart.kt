package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.desktop.DesktopSettings
import java.io.File

/**
 * STARTING WITH THE SESSION — the one setting an always-on tray client was
 * missing entirely.
 *
 * The recon's grep over `app-desktop/src/main`, `packaging/` and `scripts/`
 * found nothing: no Run key, no Startup shortcut, no `.desktop` file, no flag.
 * For a client whose whole shape is "the window is a view onto something that
 * keeps running" — it hides to the tray, holds the notification claim and keeps
 * the watch stream open — an app that has to be launched by hand after every
 * reboot is an app that is not doing the job on the mornings it matters.
 *
 * ⚠ WRITTEN BY THE APP, NOT BY THE INSTALLER. The installer only PRE-ANSWERS
 * (the owner's rule): it writes `first-run.json` and installs nothing. So the
 * file below is created here, removed here when the flag goes off, and the
 * Windows uninstaller deletes the shortcut as a last resort for an app that was
 * removed while it was on.
 *
 * ⚠ AND IT IS A REAL FILE RATHER THAN A REGISTRY VALUE ON WINDOWS. `HKCU\…\Run`
 * would be one write instead of a PowerShell hop, and it is the wrong choice
 * here for two reasons: a Startup shortcut is visible in Task Manager's Startup
 * tab, where a person who wants this OFF will go looking for it, and it can
 * carry the AppUserModelID the toast identity depends on. A Run value is
 * invisible and carries nothing.
 *
 * The pure half — where the file goes and what is in it — is separated from the
 * IO so it can be asserted on a Linux box for a Windows machine, which is the
 * only way this gets tested at all before it reaches PRESTIGE.
 */
object Autostart {

    /**
     * The launcher to start. `jpackage` stamps this on every launcher it
     * generates and nothing else sets it, so its ABSENCE is "running from
     * Gradle" — and a from-source run has no installed launcher to point an
     * autostart entry at.
     */
    fun launcherPath(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /**
     * Where the entry lives.
     *
     * Pure and taking its environment, for the same reason [ClaudePath.candidates]
     * does: `%APPDATA%` cannot be set on the JVM running the test, and this is
     * the half most worth getting right — an entry written to the wrong folder
     * does nothing at all and looks exactly like one that works.
     */
    fun location(windows: Boolean, env: (String) -> String?, home: String?): File? {
        if (windows) {
            // The per-user Startup folder. %APPDATA% rather than a hardcoded
            // "AppData\Roaming": a roaming or redirected profile moves it, and
            // a shortcut in the wrong place is silent.
            val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
                ?: home?.let { "$it\\AppData\\Roaming" }
                ?: return null
            return File("$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Startup", "$WINDOWS_LNK.lnk")
        }
        // freedesktop: XDG_CONFIG_HOME first, exactly the order
        // DesktopSettings.defaultFile() reads it in, so the two never disagree
        // about which profile this install belongs to.
        val base = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: home?.let { "$it/.config" }
            ?: return null
        return File("$base/autostart", "$LINUX_DESKTOP_FILE.desktop")
    }

    /**
     * The freedesktop entry, entire.
     *
     * `X-GNOME-Autostart-enabled` and `Hidden=false` are both there because
     * desktop environments disagree about which one means "off", and a session
     * that once turned this off through its own Settings app would otherwise
     * have its choice silently overwritten the next time the flag was flipped
     * here. `Terminal=false` because a tray app that opens a console window on
     * every login is how somebody learns to turn this off permanently.
     */
    fun desktopEntry(exec: String): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=Huginn Desktop")
        appendLine("Comment=Keeps huginn watching while you are signed in")
        // Quoted: the installed launcher lives under a path the user chose, and
        // an unquoted Exec with a space in it starts a program that is not this.
        appendLine("Exec=\"$exec\"")
        appendLine("Terminal=false")
        appendLine("Hidden=false")
        appendLine("NoDisplay=false")
        appendLine("X-GNOME-Autostart-enabled=true")
    }

    /**
     * The PowerShell that writes the Windows shortcut.
     *
     * A `.lnk` is a binary shell-link structure, not a text file, and there is
     * no JDK API for one — so the shell's own COM object writes it, which is
     * what every installer on the platform does (including this app's own NSIS
     * script, through the equivalent plugin).
     *
     * ⚠ SINGLE QUOTES AND DOUBLED APOSTROPHES. PowerShell does not expand
     * anything inside single quotes, which is what keeps a path containing `$`
     * from being read as a variable; an apostrophe in a profile name ("O'Brien")
     * is escaped by doubling it, which is that language's own rule.
     */
    fun shortcutScript(lnkPath: String, exePath: String): String {
        val lnk = psQuote(lnkPath)
        val exe = psQuote(exePath)
        // ⚠ THE PARENT, WITHOUT `File`. This string is a WINDOWS path and this
        // code compiles on Linux too, where `File.getParent` does not know `\\`
        // is a separator and hands back "." for every one of them — a shortcut
        // that then starts the app in whatever directory the shell happened to
        // be in. Same trap as [ClaudePath.candidates].
        val dir = psQuote(windowsParent(exePath))
        return buildString {
            append("\$ErrorActionPreference='Stop'; ")
            append("\$w=New-Object -ComObject WScript.Shell; ")
            append("\$s=\$w.CreateShortcut($lnk); ")
            append("\$s.TargetPath=$exe; ")
            // Without this a shortcut starts the app in system32, where a
            // relative path the JVM resolves goes somewhere nobody expects.
            append("\$s.WorkingDirectory=$dir; ")
            append("\$s.Description='Huginn Desktop'; ")
            append("\$s.Save()")
        }
    }

    private fun psQuote(raw: String): String = "'" + raw.replace("'", "''") + "'"

    /** Everything before the last separator, either kind, or the drive itself. */
    internal fun windowsParent(path: String): String {
        val cut = maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'))
        if (cut <= 0) return path
        return path.substring(0, cut)
    }

    /**
     * The honest sentence for THIS operating system.
     *
     * Named out loud because the mechanisms are genuinely different and a
     * person who wants this off needs to know where to look — the Windows half
     * is removable from Task Manager without this app's help, and the Linux one
     * is a file the session's own Startup Applications editor shows.
     */
    fun describe(windows: Boolean): String = if (windows) {
        "Adds a shortcut to your Startup folder. Windows also lists it under " +
            "Task Manager → Startup, where you can turn it off without coming back here."
    } else {
        "Writes ~/.config/autostart/$LINUX_DESKTOP_FILE.desktop, which your desktop reads at " +
            "login. Most Linux desktops also list it under Startup Applications."
    }

    /** The one reason this cannot be offered: nothing installed to point at. */
    const val NOT_PACKAGED: String =
        "This copy is running from a build rather than from an install, so there is no " +
            "installed launcher for a startup entry to point at. Install Huginn Desktop first."

    const val NO_HOME: String =
        "This machine has no profile folder to write a startup entry into."

    const val WINDOWS_LNK: String = "Huginn Desktop"
    const val LINUX_DESKTOP_FILE: String = "huginn-desktop-kt"

    // ------------------------------------------------------------------ IO

    /** Whether the entry is on disk right now — the only honest reading of "on". */
    fun isEnabled(
        windows: Boolean = ClaudePath.isWindows(),
        env: (String) -> String? = System::getenv,
        home: String? = System.getProperty("user.home"),
    ): Boolean = location(windows, env, home)?.isFile == true

    /**
     * Writes the entry and PROVES it landed.
     *
     * The proof matters more here than anywhere else in this flow: on Windows
     * the write goes through another process, and a PowerShell that is blocked,
     * missing or refused by policy exits non-zero into a log nobody reads.
     * Reporting "on" over a folder with nothing in it is the shape of failure
     * this step exists to eliminate.
     */
    fun enable(
        windows: Boolean = ClaudePath.isWindows(),
        env: (String) -> String? = System::getenv,
        home: String? = System.getProperty("user.home"),
        launcher: String? = launcherPath(),
    ): Result<String> {
        if (launcher.isNullOrBlank()) return Result.failure(IllegalStateException(NOT_PACKAGED))
        val target = location(windows, env, home) ?: return Result.failure(IllegalStateException(NO_HOME))
        return runCatching {
            target.parentFile?.mkdirs()
            if (windows) {
                val script = shortcutScript(target.absolutePath, launcher)
                val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                check(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { "the shortcut write did not finish" }
                check(p.exitValue() == 0) { out.trim().take(200).ifBlank { "the shortcut could not be written" } }
            } else {
                target.writeText(desktopEntry(launcher))
                target.setExecutable(true)
            }
            check(target.isFile) { "nothing was written to ${target.absolutePath}" }
            "starts with your session — ${target.absolutePath}"
        }
    }

    /** Takes it away. A missing file is SUCCESS: the requested state is "not there". */
    fun disable(
        windows: Boolean = ClaudePath.isWindows(),
        env: (String) -> String? = System::getenv,
        home: String? = System.getProperty("user.home"),
    ): Result<String> {
        val target = location(windows, env, home) ?: return Result.success("nothing to remove")
        return runCatching {
            if (target.exists()) check(target.delete()) { "could not remove ${target.absolutePath}" }
            "will not start with your session"
        }
    }

    /**
     * Makes the disk agree with the flag, at every launch.
     *
     * Because the file can go without this app being told: an upgrade that
     * replaced the launcher, a profile restored from backup, a desktop
     * environment's own Startup editor. The flag is what the owner asked for,
     * so the flag wins — quietly, because a notification about a shortcut is
     * noise, and nothing here is destructive either way.
     */
    fun reconcile(settings: DesktopSettings) {
        val want = settings.autostartNow()
        if (want == isEnabled()) return
        if (want) enable() else disable()
    }
}
