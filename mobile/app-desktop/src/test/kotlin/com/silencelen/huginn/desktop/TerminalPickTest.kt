package com.silencelen.huginn.desktop

import com.silencelen.huginn.desktop.TerminalLaunch.TerminalPick
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which terminal "Open in terminal" uses on Linux, against fake machines.
 *
 * ⚠ THE BUG (owner, 2026-10-23): "open in terminal is not working on linux/
 * arch versions desktop versions." The window was always `x-terminal-emulator
 * -e …`, which is a Debian alternatives link: on Arch (the owner's Omarchy
 * laptop launches terminals through `xdg-terminal-exec`) the spawn threw, the
 * reason went to stderr, and the click did nothing anyone could see.
 *
 * Nothing here touches the real machine: [which] is a map from program name to
 * path, standing in for a PATH lookup. NOTE kotlin.test's argument order is
 * (expected, actual, message).
 */
class TerminalPickTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private fun machine(vararg installed: String): (String) -> String? = { name ->
        when {
            name.startsWith("/") -> name.takeIf { it in installed }
            else -> installed.firstOrNull { File(it).name == name }
        }
    }

    private fun launcher(pick: TerminalPick): List<String> =
        (pick as? TerminalPick.Found)?.launcher ?: error("expected a terminal, got none: ${(pick as TerminalPick.NotFound).tried}")

    @Test
    fun `an Arch desktop with xdg-terminal-exec opens through it, with no -e`() {
        // Omarchy: no Debian link, the person's terminal chosen in
        // xdg-terminals.list. The launcher takes the command as is.
        val pick = TerminalLaunch.pickLinuxTerminal(env(), machine("/usr/bin/xdg-terminal-exec", "/usr/bin/alacritty"))
        assertEquals(listOf("/usr/bin/xdg-terminal-exec"), launcher(pick))
    }

    @Test
    fun `TERMINAL is the person's choice and comes first`() {
        val m = machine("/usr/bin/xdg-terminal-exec", "/usr/bin/alacritty", "/usr/bin/ghostty")
        assertEquals(listOf("/usr/bin/ghostty", "-e"), launcher(TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "ghostty"), m)))
        // Its own arguments stay, and a terminal this file knows gets its own word.
        assertEquals(
            listOf("/usr/bin/kitty", "--single-instance", "--"),
            launcher(TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "kitty --single-instance"), machine("/usr/bin/kitty"))),
        )
        // Omarchy may name the launcher itself.
        assertEquals(
            listOf("/usr/bin/xdg-terminal-exec"),
            launcher(TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "xdg-terminal-exec"), m)),
        )
    }

    @Test
    fun `an unknown TERMINAL gets xterm's -e, and a path is used as a path`() {
        assertEquals(
            listOf("/opt/myterm/bin/myterm", "-e"),
            launcher(TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "/opt/myterm/bin/myterm"), machine("/opt/myterm/bin/myterm"))),
        )
    }

    @Test
    fun `a TERMINAL that is not installed is skipped, and said`() {
        val pick = TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "nosuchterm"), machine("/usr/bin/foot"))
        assertEquals(listOf("/usr/bin/foot", "--"), launcher(pick))
        val none = TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "nosuchterm"), machine())
        assertEquals("\$TERMINAL (nosuchterm)", (none as TerminalPick.NotFound).tried.first())
    }

    @Test
    fun `Debian keeps its configured default`() {
        val pick = TerminalLaunch.pickLinuxTerminal(env(), machine("/usr/bin/x-terminal-emulator", "/usr/bin/xterm"))
        assertEquals(listOf("/usr/bin/x-terminal-emulator", "-e"), launcher(pick))
    }

    /**
     * Each terminal's own "run this" word. The wrong one fails without a sound:
     * gnome-terminal's `-e` takes ONE string, so `-e bash -lc …` runs "bash" and
     * hands the rest to the terminal; xfce4-terminal and mate-terminal want `-x`
     * for the same reason; wezterm needs its subcommand.
     */
    @Test
    fun `every terminal is told to run the command in its own words`() {
        val expected = mapOf(
            "ghostty" to listOf("-e"),
            "alacritty" to listOf("-e"),
            "kitty" to listOf("--"),
            "foot" to listOf("--"),
            "wezterm" to listOf("start", "--"),
            "konsole" to listOf("-e"),
            "gnome-terminal" to listOf("--"),
            "ptyxis" to listOf("--"),
            "kgx" to listOf("--"),
            "xfce4-terminal" to listOf("-x"),
            "mate-terminal" to listOf("-x"),
            "terminator" to listOf("-x"),
            "xterm" to listOf("-e"),
        )
        for ((name, run) in expected) {
            val pick = TerminalLaunch.pickLinuxTerminal(env(), machine("/usr/bin/$name"))
            assertEquals(listOf("/usr/bin/$name") + run, launcher(pick), name)
        }
    }

    @Test
    fun `the person's launcher outranks the Debian default, which outranks the list`() {
        val names = TerminalLaunch.LINUX_TERMINALS.map { it.name }
        assertEquals("xdg-terminal-exec", names[0])
        assertEquals("x-terminal-emulator", names[1])
        assertEquals("xterm", names.last(), "the terminal that comes with everything is the last resort")
        assertEquals(names.toSet().size, names.size, "a terminal listed twice: $names")
    }

    @Test
    fun `nothing installed names everything it tried, in order`() {
        val none = TerminalLaunch.pickLinuxTerminal(env(), machine()) as TerminalPick.NotFound
        assertEquals(TerminalLaunch.LINUX_TERMINALS.map { it.name }, none.tried)
        val sentence = TerminalLaunch.noTerminal(none.tried)
        assertTrue("xdg-terminal-exec" in sentence && "alacritty" in sentence && "xterm" in sentence, sentence)
        assertTrue("\$TERMINAL" in sentence, "and how to tell it which one: $sentence")
    }

    // --------------------------------------------------------------- PATH

    @Test
    fun `a desktop launcher's short PATH still finds a terminal in the usual places`() {
        // systemd's user default PATH has no ~/.local/bin; an interactive shell's does.
        val home = "/home/alex"
        val files = setOf("$home/.local/bin/ghostty", "/usr/bin/alacritty")
        val runnable: (File) -> Boolean = { it.path in files }
        val shortPath = env("PATH" to "/usr/local/sbin:/usr/local/bin", "HOME" to home)
        assertEquals("$home/.local/bin/ghostty", TerminalLaunch.onPath("ghostty", shortPath, runnable))
        assertEquals("/usr/bin/alacritty", TerminalLaunch.onPath("alacritty", shortPath, runnable))
        assertNull(TerminalLaunch.onPath("kitty", shortPath, runnable))
    }

    @Test
    fun `PATH order wins, and a name with a slash is only checked`() {
        val files = setOf("/opt/a/foot", "/usr/bin/foot")
        val runnable: (File) -> Boolean = { it.path in files }
        assertEquals("/opt/a/foot", TerminalLaunch.onPath("foot", env("PATH" to "/opt/a:/usr/bin"), runnable))
        assertEquals("/usr/bin/foot", TerminalLaunch.onPath("/usr/bin/foot", env(), runnable))
        assertNull(TerminalLaunch.onPath("/opt/b/foot", env("PATH" to "/opt/b"), runnable))
    }

    // ---------------------------------------------------------------- open

    private fun homeWithCli(): File = Files.createTempDirectory("tp-home").toFile().also {
        File(it, ".huginn").mkdirs()
        File(it, ".huginn/huginn.sh").writeText("# stub\n")
        it.deleteOnExit()
    }

    @Test
    fun `open spawns the chosen terminal with the attach line after it`() {
        var got: List<String>? = null
        val why = TerminalLaunch.open(
            "jotter", TerminalLaunch.Os.LINUX, homeWithCli(),
            env = env("TERMINAL" to "alacritty"), which = machine("/usr/bin/alacritty"),
        ) { got = it }
        assertNull(why)
        val argv = got!!
        assertEquals(listOf("/usr/bin/alacritty", "-e", "bash", "-lc"), argv.take(4))
        assertTrue("huginn attach 'jotter'" in argv.last(), argv.last())
    }

    @Test
    fun `open with no terminal anywhere spawns nothing and says what it tried`() {
        var spawned = false
        val why = TerminalLaunch.open(
            "jotter", TerminalLaunch.Os.LINUX, homeWithCli(), env = env(), which = machine(),
        ) { spawned = true }
        assertFalse(spawned)
        assertTrue(why != null && "no terminal was found" in why && "xdg-terminal-exec" in why, why)
        // What to do first: the status line shows one line and cuts the rest.
        val note = TerminalLaunch.failureNote("jotter", why!!)
        assertTrue(note.startsWith("Copied huginn 'jotter' to the clipboard"), note)
        assertTrue(why in note, note)
    }

    // ------------------------------------------------------------- the setting

    /**
     * Settings > Appearance & behaviour > Terminal. The app's own answer comes
     * before the machine's ($TERMINAL), and a terminal that has gone since it was
     * chosen is skipped and named, like a $TERMINAL that is.
     */
    @Test
    fun `the Terminal setting outranks TERMINAL on Linux, and is skipped and said when gone`() {
        val m = machine("/usr/bin/kitty", "/usr/bin/alacritty", "/usr/bin/xdg-terminal-exec")
        assertEquals(
            listOf("/usr/bin/kitty", "--"),
            launcher(TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "alacritty"), m, choice = "kitty")),
        )
        val gone = TerminalLaunch.pickLinuxTerminal(env("TERMINAL" to "alacritty"), m, choice = "foot")
        assertEquals(listOf("/usr/bin/alacritty", "-e"), launcher(gone))
        val none = TerminalLaunch.pickLinuxTerminal(env(), machine(), choice = "foot") as TerminalPick.NotFound
        assertEquals("Terminal setting (foot)", none.tried.first())
        // open() reads the setting through the hook the app points at DesktopSettings.
        var got: List<String>? = null
        TerminalLaunch.preferred = { "kitty" }
        try {
            assertNull(TerminalLaunch.open("jotter", TerminalLaunch.Os.LINUX, homeWithCli(), env = env(), which = m) { got = it })
        } finally {
            TerminalLaunch.preferred = { "" }
        }
        assertEquals(listOf("/usr/bin/kitty", "--"), got!!.take(2))
    }

    @Test
    fun `the Terminal row offers what this machine has, Windows PowerShell always`() {
        assertEquals(
            listOf("wt", "pwsh", "powershell"),
            TerminalLaunch.candidates(TerminalLaunch.Os.WINDOWS, env(), windows(WT, PWSH, POWERSHELL)).map { it.id },
        )
        assertEquals(listOf("powershell"), TerminalLaunch.candidates(TerminalLaunch.Os.WINDOWS, env(), windows()).map { it.id })
        assertEquals(
            listOf("xdg-terminal-exec", "kitty"),
            TerminalLaunch.candidates(TerminalLaunch.Os.LINUX, env(), machine("/usr/bin/xdg-terminal-exec", "/usr/bin/kitty")).map { it.id },
        )
        assertEquals(emptyList(), TerminalLaunch.candidates(TerminalLaunch.Os.MAC, env()) { "/usr/bin/anything" })
        assertTrue(TerminalLaunch.offersChoice(TerminalLaunch.Os.WINDOWS) && TerminalLaunch.offersChoice(TerminalLaunch.Os.LINUX))
        assertFalse(TerminalLaunch.offersChoice(TerminalLaunch.Os.MAC), "macOS has the one Terminal")
    }

    // ---------------------------------------------------------------- windows
    //
    // Reasoned, not run: no Windows box takes part in this gate. What is pinned
    // is everything up to the spawn — which terminal, from where, with which
    // shell inside it, and the command line the outer shell is handed.

    private val WT = """C:\Users\alex\AppData\Local\Microsoft\WindowsApps\wt.exe"""
    private val PWSH = """C:\Program Files\PowerShell\7\pwsh.exe"""
    private val POWERSHELL = """C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"""

    /** A Windows machine: which exes answer, by their bare names. */
    private fun windows(vararg installed: String): (String) -> String? = { exe ->
        installed.firstOrNull { it.substringAfterLast('\\') == exe }
    }

    @Test
    fun `Auto on Windows is Windows Terminal, then PowerShell 7, then Windows PowerShell`() {
        val full = TerminalLaunch.pickWindowsTerminal("", windows(WT, PWSH, POWERSHELL))
        assertEquals(TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL, full.terminal)
        assertEquals(WT, full.path)
        assertEquals(TerminalLaunch.WindowsTerminal.PWSH, full.shell, "a Windows Terminal tab runs PowerShell 7 when it is there")
        assertEquals(TerminalLaunch.WindowsTerminal.PWSH, TerminalLaunch.pickWindowsTerminal("", windows(PWSH, POWERSHELL)).terminal)
        assertEquals(TerminalLaunch.WindowsTerminal.POWERSHELL, TerminalLaunch.pickWindowsTerminal("", windows(POWERSHELL)).terminal)
        // Windows Terminal without PowerShell 7 runs Windows PowerShell in its tab.
        assertEquals(TerminalLaunch.WindowsTerminal.POWERSHELL, TerminalLaunch.pickWindowsTerminal("", windows(WT, POWERSHELL)).shell)
        // A PATH walk that finds nothing still opens Windows PowerShell, by name:
        // it is on every install, and a shortcut-started app's PATH can miss it.
        val none = TerminalLaunch.pickWindowsTerminal("", windows())
        assertEquals(TerminalLaunch.WindowsTerminal.POWERSHELL, none.terminal)
        assertEquals("powershell.exe", none.path)
    }

    @Test
    fun `the Terminal setting outranks Auto on Windows, when that terminal is there`() {
        val full = windows(WT, PWSH, POWERSHELL)
        assertEquals(TerminalLaunch.WindowsTerminal.PWSH, TerminalLaunch.pickWindowsTerminal("pwsh", full).terminal)
        assertEquals(TerminalLaunch.WindowsTerminal.POWERSHELL, TerminalLaunch.pickWindowsTerminal("powershell", full).terminal)
        assertEquals(TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL, TerminalLaunch.pickWindowsTerminal("wt", full).terminal)
        // Chosen but gone: Auto's order. An id this build does not know: Auto.
        assertEquals(TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL, TerminalLaunch.pickWindowsTerminal("pwsh", windows(WT, POWERSHELL)).terminal)
        assertEquals(TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL, TerminalLaunch.pickWindowsTerminal("cmd", full).terminal)
    }

    @Test
    fun `the label names PowerShell only when PowerShell is what opens`() {
        assertEquals("Open in terminal", TerminalLaunch.label(TerminalLaunch.Os.WINDOWS, "", which = windows(WT, PWSH, POWERSHELL)))
        assertEquals("Open in PowerShell", TerminalLaunch.label(TerminalLaunch.Os.WINDOWS, "", which = windows(PWSH, POWERSHELL)))
        assertEquals("Open in PowerShell", TerminalLaunch.label(TerminalLaunch.Os.WINDOWS, "pwsh", which = windows(WT, PWSH, POWERSHELL)))
        assertEquals("Open in terminal", TerminalLaunch.label(TerminalLaunch.Os.LINUX, "", which = { null }))
        assertEquals("Open dev in terminal", TerminalLaunch.paletteLabel("dev", "Open in terminal"))
        assertEquals("Open dev in PowerShell", TerminalLaunch.paletteLabel("dev", "Open in PowerShell"))
    }

    @Test
    fun `a Windows Terminal tab runs PowerShell 7 only when PATH itself has it`() {
        // pwsh installed without "Add to PATH" (or from the zip): the app finds it
        // through PowerShell 7's install directory, but wt resolves the tab's bare
        // `pwsh.exe` through PATH alone and would open the tab onto an error.
        val onPathOnly = windows(WT, POWERSHELL)
        val pick = TerminalLaunch.pickWindowsTerminal("", windows(WT, PWSH, POWERSHELL), onPathOnly)
        assertEquals(TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL, pick.terminal)
        assertEquals(TerminalLaunch.WindowsTerminal.POWERSHELL, pick.shell, "a shell wt cannot find is not asked for")
        assertEquals(TerminalLaunch.WindowsTerminal.PWSH, TerminalLaunch.pickWindowsTerminal("", windows(WT, PWSH, POWERSHELL), windows(PWSH)).shell)
        // open() walks PATH alone for the tab's shell: with an empty environment nothing is on it.
        val home = homeWithCli().also { File(it, ".huginn/huginn.ps1").writeText("# stub\n") }
        var got: List<String>? = null
        assertNull(TerminalLaunch.open("jotter", TerminalLaunch.Os.WINDOWS, home, env = { null }, which = windows(WT, PWSH, POWERSHELL)) { got = it })
        assertTrue(got!!.last().contains("-ArgumentList '-w','0','nt','powershell.exe','-NoExit'"), got!!.last())
        // PowerShell 7 chosen outright is started from where it was found, PATH or not.
        assertNull(TerminalLaunch.open("jotter", TerminalLaunch.Os.WINDOWS, home, env = { null }, which = windows(WT, PWSH, POWERSHELL), choice = "pwsh") { got = it })
        assertTrue(got!!.last().startsWith("Start-Process 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' -ArgumentList '-NoExit'"), got!!.last())
    }

    @Test
    fun `windows opens the chosen terminal through the outer shell, with the tab's shell by name`() {
        val home = homeWithCli().also { File(it, ".huginn/huginn.ps1").writeText("# stub\n") }
        var got: List<String>? = null
        // pwsh on PATH itself here, so the tab may run it.
        val why = TerminalLaunch.open("jotter", TerminalLaunch.Os.WINDOWS, home, env = { null }, which = windows(WT, PWSH, POWERSHELL), onPath = windows(PWSH)) { got = it }
        assertNull(why)
        val argv = got!!
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), argv.take(4), "the outer shell is the one every machine has")
        val cmd = argv.last()
        assertTrue(
            cmd.startsWith(
                "Start-Process 'C:\\Users\\alex\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe' " +
                    "-ArgumentList '-w','0','nt','pwsh.exe','-NoExit','-EncodedCommand','",
            ),
            cmd,
        )
        assertEquals(TerminalLaunch.script("jotter"), TerminalLaunch.decoded(cmd.substringAfterLast("','").removeSuffix("'")))
        // PowerShell 7 chosen: its own window, from where it was found.
        assertNull(
            TerminalLaunch.open("jotter", TerminalLaunch.Os.WINDOWS, home, env = { null }, which = windows(WT, PWSH, POWERSHELL), choice = "pwsh") { got = it },
        )
        assertTrue(
            got!!.last().startsWith("Start-Process 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' -ArgumentList '-NoExit','-EncodedCommand','"),
            got!!.last(),
        )
        // Nothing found: Windows PowerShell by name, exactly as every build before.
        assertNull(TerminalLaunch.open("jotter", TerminalLaunch.Os.WINDOWS, home, env = { null }, which = windows()) { got = it })
        assertTrue(got!!.last().startsWith("Start-Process powershell.exe -ArgumentList '-NoExit','-EncodedCommand','"), got!!.last())
    }

    @Test
    fun `a path with a quote in it is quoted for PowerShell`() {
        val pick = TerminalLaunch.WindowsPick(
            TerminalLaunch.WindowsTerminal.WINDOWS_TERMINAL,
            """C:\Users\O'Brien\AppData\Local\Microsoft\WindowsApps\wt.exe""",
            TerminalLaunch.WindowsTerminal.POWERSHELL,
        )
        val cmd = TerminalLaunch.argv("jotter", TerminalLaunch.Os.WINDOWS, windows = pick)!!.last()
        assertTrue(
            cmd.startsWith("Start-Process 'C:\\Users\\O''Brien\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe' -ArgumentList '-w','0','nt','powershell.exe'"),
            cmd,
        )
    }

    @Test
    fun `the Windows PATH walk is semicolon-separated and knows the three homes`() {
        val files = setOf(WT, PWSH, POWERSHELL, """C:\Tools\kitty.exe""")
        // java.io.File joins with '/' on this box; the names under test are Windows'.
        val runnable: (File) -> Boolean = { it.path.replace('/', '\\') in files }
        val env = env(
            "PATH" to """C:\Windows\System32;C:\Tools""",
            "LOCALAPPDATA" to """C:\Users\alex\AppData\Local""",
            "ProgramFiles" to """C:\Program Files""",
            "SystemRoot" to """C:\Windows""",
        )
        assertTrue(TerminalLaunch.onPathWindows("kitty.exe", env, runnable)!!.endsWith("kitty.exe"), "PATH itself")
        assertTrue(TerminalLaunch.onPathWindows("wt.exe", env, runnable)!!.endsWith("wt.exe"), "the Store alias directory")
        assertTrue(TerminalLaunch.onPathWindows("pwsh.exe", env, runnable)!!.endsWith("pwsh.exe"), "PowerShell 7's install directory")
        assertTrue(TerminalLaunch.onPathWindows("powershell.exe", env, runnable)!!.endsWith("powershell.exe"), "Windows PowerShell's")
        assertNull(TerminalLaunch.onPathWindows("cmd.exe", env, runnable))
    }
}
