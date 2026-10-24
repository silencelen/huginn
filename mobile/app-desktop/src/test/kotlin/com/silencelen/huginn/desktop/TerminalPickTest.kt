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

    @Test
    fun `windows does not ask the machine for a terminal`() {
        var got: List<String>? = null
        val why = TerminalLaunch.open(
            "jotter", TerminalLaunch.Os.WINDOWS, homeWithCli().also { File(it, ".huginn/huginn.ps1").writeText("# stub\n") },
            env = { error("Windows read the environment for a terminal") },
            which = { error("Windows looked for a terminal on PATH") },
        ) { got = it }
        assertNull(why)
        assertEquals("powershell.exe", got!![0])
    }
}
