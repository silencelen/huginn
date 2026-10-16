package com.silencelen.huginn.desktop.setup

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting with the session — where the entry goes and what is in it.
 *
 * ⚠ EVERY FAILURE HERE IS SILENT. An entry written to the wrong folder does
 * nothing at all and looks exactly like one that works; a `.desktop` file
 * missing `X-GNOME-Autostart-enabled` is ignored by some sessions and honoured
 * by others; an unquoted Exec with a space in it starts a different program; and
 * a PowerShell string that lets a path be read as a variable writes a shortcut
 * to somewhere else entirely. None of that throws, and none of it is visible
 * until somebody reboots.
 *
 * The Windows half cannot be executed on this box at all, which is why the
 * location and the script text are separated from the IO and asserted here.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AutostartTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun tempDir(): File =
        Files.createTempDirectory("huginn-autostart").toFile().also { dirs += it }

    // ------------------------------------------------------------- location

    @Test
    fun `windows writes into the per-user Startup folder`() {
        val at = assertNotNull(
            Autostart.location(
                windows = true,
                env = { if (it == "APPDATA") """C:\Users\alex\AppData\Roaming""" else null },
                home = """C:\Users\alex""",
            ),
        )
        assertEquals(
            """C:\Users\alex\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup""",
            at.parent,
        )
        assertEquals("${Autostart.WINDOWS_LNK}.lnk", at.name)
    }

    @Test
    fun `an unset APPDATA falls back to the profile rather than to the drive root`() {
        // %APPDATA% moves on a roaming or redirected profile. Reading it first
        // and the profile second is the order that survives both; joining onto
        // an empty string would aim the write at the root of the system drive.
        val at = assertNotNull(
            Autostart.location(windows = true, env = { null }, home = """C:\Users\alex"""),
        )
        assertTrue(at.path.startsWith("""C:\Users\alex\AppData\Roaming"""), at.path)
        assertNull(
            Autostart.location(windows = true, env = { null }, home = null),
            "with no profile at all there is nowhere honest to write",
        )
    }

    @Test
    fun `linux honours XDG_CONFIG_HOME first, exactly like the settings file`() {
        // ⚠ THE SAME ORDER DesktopSettings.defaultFile() READS. Two different
        // answers to "which profile is this install" is how an app writes its
        // settings to one place and its startup entry to another.
        val xdg = assertNotNull(
            Autostart.location(
                windows = false,
                env = { if (it == "XDG_CONFIG_HOME") "/home/alex/.myconfig" else null },
                home = "/home/alex",
            ),
        )
        assertEquals("/home/alex/.myconfig/autostart", xdg.parent)
        assertEquals("${Autostart.LINUX_DESKTOP_FILE}.desktop", xdg.name)

        val plain = assertNotNull(Autostart.location(false, { null }, "/home/alex"))
        assertEquals("/home/alex/.config/autostart", plain.parent)

        // An EMPTY variable is not a set one — a shell that exports
        // XDG_CONFIG_HOME= would otherwise put the entry in "/autostart".
        val blank = assertNotNull(
            Autostart.location(false, { if (it == "XDG_CONFIG_HOME") "" else null }, "/home/alex"),
        )
        assertEquals("/home/alex/.config/autostart", blank.parent)
    }

    // ---------------------------------------------------------- file content

    @Test
    fun `the desktop entry says all three things a session may ask`() {
        val text = Autostart.desktopEntry("/opt/huginn-desktop-kt/bin/huginn-desktop-kt")
        assertTrue(text.startsWith("[Desktop Entry]"), text)
        assertTrue(text.contains("Type=Application"), text)
        // Desktop environments disagree about which key means "off". Writing
        // only one of them means a session that once turned this off through
        // its own Settings app has its choice silently overwritten.
        assertTrue(text.contains("Hidden=false"), text)
        assertTrue(text.contains("X-GNOME-Autostart-enabled=true"), text)
        // A tray app that opens a console window on every login is how somebody
        // learns to turn this off permanently.
        assertTrue(text.contains("Terminal=false"), text)
    }

    @Test
    fun `the Exec line is quoted, because an installed path can contain a space`() {
        val text = Autostart.desktopEntry("/opt/Huginn Desktop/bin/huginn")
        assertTrue(
            text.contains("""Exec="/opt/Huginn Desktop/bin/huginn""""),
            "an unquoted Exec with a space starts a program that is not this one:\n$text",
        )
    }

    @Test
    fun `the shortcut script cannot be talked into expanding a path`() {
        val script = Autostart.shortcutScript(
            """C:\Users\alex\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Huginn Desktop.lnk""",
            """C:\Users\alex\AppData\Local\Programs\huginn-desktop-kt\huginn-desktop-kt.exe""",
        )
        // SINGLE QUOTES. PowerShell expands nothing inside them, which is what
        // keeps a path containing $ from being read as a variable — and profile
        // names contain all sorts of things.
        assertTrue(script.contains("""CreateShortcut('C:\Users\alex"""), script)
        assertFalse(script.contains("\"C:"), "a double-quoted path would expand: $script")
        assertTrue(script.contains("WScript.Shell"), script)
        // Without a working directory the shortcut starts the app in system32,
        // where a relative path the JVM resolves goes somewhere nobody expects.
        assertTrue(script.contains("WorkingDirectory="), script)
        assertTrue(script.contains(".Save()"), "a script that never saves writes nothing: $script")
    }

    @Test
    fun `an apostrophe in a profile name is escaped the way PowerShell escapes it`() {
        val script = Autostart.shortcutScript("""C:\Users\O'Brien\s.lnk""", """C:\Users\O'Brien\h.exe""")
        // Doubling is that language's own rule; a single apostrophe would end
        // the string and turn the rest of the path into broken syntax.
        assertTrue(script.contains("""C:\Users\O''Brien\s.lnk"""), script)
        // A LONE apostrophe would end the string and turn the rest of the path
        // into broken PowerShell — so the un-doubled form must not survive
        // anywhere in the script.
        assertFalse(script.contains("""O'B"""), "an unescaped apostrophe is still in: $script")
        // And the working directory is the real parent rather than ".", which is
        // what `File(...).parent` hands back for a Windows path on a Linux JVM.
        assertTrue(script.contains("""WorkingDirectory='C:\Users\O''Brien'"""), script)
    }

    @Test
    fun `each platform is described in its own terms`() {
        assertTrue(Autostart.describe(true).contains("Startup folder"))
        assertTrue(Autostart.describe(true).contains("Task Manager"), "name where to turn it off")
        assertTrue(Autostart.describe(false).contains(".config/autostart"))
        assertTrue(Autostart.describe(false).contains("Startup Applications"))
        // Honest per OS: neither sentence may claim the other's mechanism.
        assertFalse(Autostart.describe(false).contains("Task Manager"))
        assertFalse(Autostart.describe(true).contains(".config/autostart"))
    }

    // --------------------------------------------------------------- the IO

    @Test
    fun `enabling on linux writes the file and proves it landed`() {
        val home = tempDir()
        val env = { k: String -> if (k == "XDG_CONFIG_HOME") "${home.absolutePath}/.config" else null }
        val outcome = Autostart.enable(
            windows = false, env = env, home = home.absolutePath, launcher = "/opt/h/bin/h",
        )
        assertTrue(outcome.isSuccess, "enable failed: ${outcome.exceptionOrNull()?.message}")
        val f = assertNotNull(Autostart.location(false, env, home.absolutePath))
        assertTrue(f.isFile, "nothing at ${f.absolutePath}")
        assertTrue(f.readText().contains("""Exec="/opt/h/bin/h""""))
        assertTrue(Autostart.isEnabled(false, env, home.absolutePath))
        // The detail line names the file, because "on" with no location is the
        // one thing a person cannot check for themselves.
        assertTrue(outcome.getOrNull().orEmpty().contains(f.absolutePath))
    }

    @Test
    fun `disabling removes it, and removing what is not there is success`() {
        val home = tempDir()
        val env = { _: String -> null as String? }
        // The requested STATE is "not there", so a missing file is the outcome
        // asked for rather than an error to report.
        assertTrue(Autostart.disable(false, env, home.absolutePath).isSuccess)
        Autostart.enable(false, env, home.absolutePath, "/opt/h/bin/h")
        assertTrue(Autostart.isEnabled(false, env, home.absolutePath))
        assertTrue(Autostart.disable(false, env, home.absolutePath).isSuccess)
        assertFalse(Autostart.isEnabled(false, env, home.absolutePath))
    }

    @Test
    fun `a build running from Gradle refuses rather than pointing at nothing`() {
        // `jpackage.app-path` is stamped on every launcher jpackage generates and
        // by nothing else, so its absence is "running from source" — and there
        // is no installed launcher for a startup entry to point at.
        val home = tempDir()
        val outcome = Autostart.enable(false, { null }, home.absolutePath, launcher = null)
        assertTrue(outcome.isFailure)
        assertEquals(Autostart.NOT_PACKAGED, outcome.exceptionOrNull()?.message)
        assertFalse(Autostart.isEnabled(false, { null }, home.absolutePath), "nothing was written")
    }

    // ---- the hand-over to huginn core's tray (owner, 2026-10-16) ----------------

    private fun settings(): com.silencelen.huginn.desktop.DesktopSettings =
        com.silencelen.huginn.desktop.DesktopSettings(File(Files.createTempDirectory("as-hand").toFile(), "settings.json"))

    @Test
    fun `the app's own sign-in entry is turned off once where the core's tray starts at login`() {
        val s = settings()
        s.setAutostart(true)
        Autostart.handOverToCoreTray(s) { true }
        assertFalse(s.autostartNow(), "the core's tray starts at sign-in; the app's own entry goes")
        assertTrue(s.autostartHandedToCore())
        // Turned back on by the person: left alone from then on.
        s.setAutostart(true)
        Autostart.handOverToCoreTray(s) { true }
        assertTrue(s.autostartNow(), "a choice made after the hand-over must stick")
    }

    @Test
    fun `without the core's tray at login nothing is handed over`() {
        val s = settings()
        s.setAutostart(true)
        Autostart.handOverToCoreTray(s) { false }
        assertTrue(s.autostartNow(), "no core tray at login: the app's own entry is the only thing at sign-in")
        assertFalse(s.autostartHandedToCore(), "and the hand-over is still due for when the core's tray arrives")
    }

    @Test
    fun `the core tray's login entry is found where the core writes it`() {
        val home = Files.createTempDirectory("as-core").toFile()
        val appData = File(home, "Roaming").apply { mkdirs() }
        val env = { k: String -> if (k == "APPDATA") appData.absolutePath else null }
        assertFalse(Autostart.coreTrayStartsAtLogin(true, env, home.absolutePath))
        // Built exactly as the check builds it: on Linux the backslashes are
        // part of one directory name, so this asserts the string, not the OS.
        File("${appData.absolutePath}\\Microsoft\\Windows\\Start Menu\\Programs\\Startup").let {
            it.mkdirs(); File(it, "Huginn.lnk").writeText("lnk")
        }
        assertTrue(Autostart.coreTrayStartsAtLogin(true, env, home.absolutePath))
        assertFalse(Autostart.coreTrayStartsAtLogin(false, { null }, home.absolutePath))
        File(home, ".config/autostart").mkdirs()
        File(home, ".config/autostart/huginn-core-tray.desktop").writeText("[Desktop Entry]")
        assertTrue(Autostart.coreTrayStartsAtLogin(false, { null }, home.absolutePath))
    }
}
