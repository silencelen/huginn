package com.silencelen.huginn.desktop

import java.io.File
import java.util.Base64

/**
 * "Open in PowerShell": the one thing a tmux session NAME is for that this
 * window cannot do itself — a real terminal attached to it through the huginn
 * CLI (`client/huginn.ps1`: `huginn <name>`). Desktop-only by nature; the phone
 * has Termux for this and the shared modules know nothing about shells.
 *
 * ⚠ THE CLI IS A PROFILE FUNCTION, NOT AN EXE. `install.ps1` dot-sources
 * `~\.huginn\huginn.ps1` from `$PROFILE`, so the window that runs `huginn` must
 * load the profile (no `-NoProfile`) — and because a profile is the user's to
 * break, the script dot-sources the file itself first, which is harmless when
 * the profile already did. The OUTER powershell that merely starts the window
 * is `-NoProfile`, like every other spawn in this client.
 *
 * ⚠ `-EncodedCommand`, NEVER A QUOTED `-Command`. The inner command carries
 * `$HOME` paths and quotes through two command lines (Start-Process joins its
 * ArgumentList into one), and a user directory with a space in it — most of
 * them — is exactly what a hand-quoted string loses on the way. Base64 UTF-16LE
 * has nothing a parser can eat.
 *
 * ⚠ A SESSION NAME IS NOT ALWAYS A SESSION TO THE CLI (desktop breaker,
 * 2026-10-02). `huginn <word>` reads the word as a VERB first and a session
 * only when it is not one, and the daemon happily makes sessions called
 * `update`, `solo`, `list` or `uninstall`. "Open in PowerShell" on one of those
 * ran the verb: `update` replaced the installed client, `solo` threw every
 * other device off main, `uninstall` started the uninstall flow. So a name that
 * is a CLI verb goes through `huginn attach <name>`, which never dispatches —
 * and because an older installed CLI has no `attach`, the window ASKS the CLI
 * it loaded (`_Huginn-AttachNamed` / `_huginn_attach_named` exist exactly when
 * `attach` does) and, without it, says so instead of running the verb. An
 * ordinary name keeps the plain `huginn <name>` on an older CLI.
 *
 * ⚠ THE NAME GATE IS THE DAEMON'S RULE, not one of its own (same round). It
 * used to be `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`: it refused `_build` (valid
 * everywhere else) blaming "characters a shell would read", and opened `a.b`,
 * 51-character and `Foo` names that the CLI then refused or quietly turned into
 * a different session (`foo`) — reporting success every time. Now it is
 * appd's `nameProblem` grammar, which is also shell-safe, plus the CLI's case
 * fold: a name with capitals is one the CLI cannot reach, so it is not opened
 * (the header offers the raw tmux line instead — see [offer]).
 */
object TerminalLaunch {
    enum class Os { WINDOWS, MAC, LINUX }

    val os: Os
        get() = System.getProperty("os.name").orEmpty().let {
            when {
                it.startsWith("Windows") -> Os.WINDOWS
                it.startsWith("Mac") -> Os.MAC
                else -> Os.LINUX
            }
        }

    /**
     * The Terminal setting (Settings > Appearance & behaviour), read at the moment
     * a window opens: "" is Auto, else a [Candidate.id]. A hook rather than a
     * parameter because the session header's name menu and every row menu call
     * [open] and [label] without a settings object in reach; the app points it at
     * `DesktopSettings.terminalNow()` once at launch, and a test leaves it Auto.
     */
    @Volatile
    var preferred: () -> String = { "" }

    /**
     * The terminals Windows can open, in the order Auto tries them: Windows
     * Terminal when it is installed (its own tabs, the owner's profile), then
     * PowerShell 7, then the Windows PowerShell every machine has. [powershell]
     * says whether the WINDOW is a PowerShell one, which is what the menu label
     * names; a Windows Terminal tab runs PowerShell inside it, but the thing that
     * opens is the terminal.
     */
    enum class WindowsTerminal(val id: String, val exe: String, val title: String, val powershell: Boolean) {
        WINDOWS_TERMINAL("wt", "wt.exe", "Windows Terminal", false),
        PWSH("pwsh", "pwsh.exe", "PowerShell 7", true),
        POWERSHELL("powershell", "powershell.exe", "Windows PowerShell", true),
    }

    /**
     * What [pickWindowsTerminal] decided: the terminal, where it is (an absolute
     * path when the machine answered, the bare exe when it did not), and the
     * shell a Windows Terminal tab runs — PowerShell 7 when present, else Windows
     * PowerShell. The shell is passed to `wt` by its bare name on purpose: `wt`
     * rebuilds the tab's command line from the words it is given, and a path with
     * a space in it (`C:\Program Files\PowerShell\7\pwsh.exe`) is exactly the word
     * that comes apart there. Both shells resolve through PATH the way `wt`
     * itself does.
     */
    class WindowsPick(val terminal: WindowsTerminal, val path: String, val shell: WindowsTerminal)

    /**
     * The terminal a Windows window opens in: the setting's [choice] when that
     * terminal is on this machine, else Auto's order. [which] answers where an
     * exe is (null when it is not), so the order is testable with a fake machine.
     * Windows PowerShell is the floor: it is on every Windows install, and a PATH
     * walk from an app started by a shortcut can still miss it, so it is returned
     * by name rather than reported absent.
     */
    fun pickWindowsTerminal(choice: String, which: (String) -> String?): WindowsPick {
        val chosen = WindowsTerminal.entries.firstOrNull { it.id == choice }
        val order = (listOfNotNull(chosen) + WindowsTerminal.entries).distinct()
        val shell = when {
            which(WindowsTerminal.PWSH.exe) != null -> WindowsTerminal.PWSH
            else -> WindowsTerminal.POWERSHELL
        }
        for (t in order) {
            val path = which(t.exe) ?: continue
            return WindowsPick(t, path, if (t == WindowsTerminal.WINDOWS_TERMINAL) shell else t)
        }
        return WindowsPick(WindowsTerminal.POWERSHELL, WindowsTerminal.POWERSHELL.exe, WindowsTerminal.POWERSHELL)
    }

    /** One terminal the Settings row can offer: the id the setting stores, and its name. */
    class Candidate(val id: String, val title: String)

    /** Whether this OS has a choice to make at all: macOS has the one Terminal. */
    fun offersChoice(os: Os = this.os): Boolean = os != Os.MAC

    /**
     * The terminals on THIS machine, for the Terminal row: Windows lists the
     * three it knows that answer (Windows PowerShell always), Linux the ones of
     * [LINUX_TERMINALS] that are installed — launchers included, since
     * `xdg-terminal-exec` is how a person says "my terminal" on a desktop that
     * reads xdg-terminals.list.
     */
    fun candidates(
        os: Os = this.os,
        env: (String) -> String? = System::getenv,
        which: (String) -> String? = whichFor(os, env),
    ): List<Candidate> = when (os) {
        Os.WINDOWS -> WindowsTerminal.entries
            .filter { it == WindowsTerminal.POWERSHELL || which(it.exe) != null }
            .map { Candidate(it.id, it.title) }
        Os.LINUX -> LINUX_TERMINALS.filter { which(it.name) != null }.map { Candidate(it.name, it.name) }
        Os.MAC -> emptyList()
    }

    /** appd's NAME_RE (server/appd/huginn-appd.js) — the one rule for the product. */
    private val NAME_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9_-]{0,49}$")

    /** What a tmux name may hold for it to sit inside the quoted raw-tmux line. */
    private val TMUX_SAFE = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,99}$")

    /**
     * Every word the CLI's dispatcher reads as a verb that is also a legal
     * session name — in BOTH clients (`client/huginn.sh`, `client/huginn.ps1`).
     * TerminalLaunchNamesTest reads the dispatchers and fails when this drifts.
     */
    val CLI_VERBS: Set<String> = setOf(
        "help", "version", "update", "list", "ls", "status", "st", "rounds", "round",
        "headroom", "devices", "projects", "project", "llm", "device", "core", "local",
        "uninstall", "desktop", "usage", "cost", "ccusage", "solo", "rename", "mv",
        "kill", "end", "archive", "revive", "unarchive", "attach", "console", "setup",
    )

    /**
     * Why the CLI cannot open [name], in the daemon's words (nameProblem), or
     * null when it can. Checked in the daemon's order, so the sentence matches
     * the one `huginn <name>` itself would print.
     */
    fun problem(name: String): String? = when {
        name.isEmpty() -> "a session needs a name"
        '.' in name -> "a \".\" is rewritten to \"_\" by tmux, so it is not a name the CLI can open"
        !name[0].let { it.isLetterOrDigit() && it.code < 128 || it == '_' } ->
            "it starts with '${name[0]}' - a name starts with a letter, digit or _"
        name.length > 50 -> "it is ${name.length} characters long - a name is at most 50"
        !NAME_RE.matches(name) -> "it has characters a session name cannot (letters, digits, _ and - only)"
        name != name.lowercase() ->
            "the CLI folds names to lowercase, so it would open '${name.lowercase()}', a different session"
        else -> null
    }

    /**
     * The menu word: the thing that opens. On Windows it names the shell only
     * when the terminal that will start IS PowerShell — with Windows Terminal
     * installed (or chosen) the window is a terminal, and "Open in PowerShell"
     * would name the thing inside it. Asked of the machine the way [open] will
     * ask it, so the label and the window cannot disagree.
     */
    fun label(
        os: Os = this.os,
        choice: String = preferred(),
        env: (String) -> String? = System::getenv,
        which: (String) -> String? = whichFor(os, env),
    ): String = if (os == Os.WINDOWS) label(pickWindowsTerminal(choice, which).terminal) else "Open in terminal"

    /** The label for a decided Windows terminal. */
    fun label(terminal: WindowsTerminal?): String =
        if (terminal?.powershell == true) "Open in PowerShell" else "Open in terminal"

    /** The palette's row for [subject]: "Open <session> in terminal", in the label's own words. */
    fun paletteLabel(subject: String, label: String): String = label.replaceFirst("Open ", "Open $subject ")

    /** True when `huginn <name>` would run a CLI verb rather than attach. */
    fun isVerb(name: String): Boolean = name.lowercase() in CLI_VERBS

    /**
     * The line a person would type. Also what lands on the clipboard when opening
     * fails. A verb-named session gets the explicit attach form; anything else the
     * plain one, which every CLI version understands.
     */
    fun shellLine(name: String): String = if (isVerb(name)) "huginn attach '$name'" else "huginn '$name'"

    /** What an older CLI (no `attach`) should do instead: plain, or refuse a verb. */
    private fun oldCliMessage(name: String): String =
        "huginn: this CLI predates 'huginn attach', and 'huginn $name' would run the $name command " +
            "instead of opening the session. Run 'huginn update', then open it again."

    /** The PowerShell script the new window runs. */
    fun script(name: String): String {
        val fallback = if (isVerb(name)) "Write-Host \"${oldCliMessage(name)}\"" else "huginn '$name'"
        return "if (Test-Path \"\$HOME\\.huginn\\huginn.ps1\") { . \"\$HOME\\.huginn\\huginn.ps1\" }; " +
            "if (Get-Command _Huginn-AttachNamed -ErrorAction SilentlyContinue) { huginn attach '$name' } " +
            "else { $fallback }"
    }

    /** The same decision for a POSIX shell (bash on Linux, zsh in macOS Terminal). */
    private fun posixLine(name: String): String {
        val fallback = if (isVerb(name)) "echo \"${oldCliMessage(name)}\" >&2" else "huginn '$name'"
        return "if type _huginn_attach_named >/dev/null 2>&1; then huginn attach '$name'; else $fallback; fi"
    }

    /**
     * What the Linux window runs, after whatever terminal [pickLinuxTerminal] chose.
     *
     * ⚠ THE CLI IS SOURCED HERE, NOT LEFT TO THE LOGIN SHELL (desktop breaker,
     * 2026-10-02). `bash -lc` reads only the FIRST of ~/.bash_profile,
     * ~/.bash_login, ~/.profile — install.sh wires ~/.profile and ~/.bashrc — so
     * a user's own ~/.bash_profile hides the first, and Debian's .bashrc returns
     * early for a non-interactive shell before reaching the second: the window
     * said "huginn: command not found". The Windows script dot-sources its file
     * for the same reason; harmless when a startup file already did.
     */
    fun linuxCommand(name: String): List<String> = listOf(
        "bash", "-lc",
        "[ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh; ${posixLine(name)}; exec bash",
    )

    /**
     * One terminal, and how it is told "run this command": [run] goes between the
     * terminal and the command. The words are each terminal's own — the same ones
     * its desktop entry gives `xdg-terminal-exec` as `X-TerminalArgExec`.
     */
    class Terminal(val name: String, val run: List<String>)

    /**
     * ⚠ `x-terminal-emulator` IS DEBIAN'S, NOT LINUX'S (owner report, 2026-10-23:
     * "open in terminal is not working on linux/ arch"). It is a Debian
     * alternatives link and this was the only thing ever tried, so on Arch, Fedora
     * or anything else that is not Debian the spawn threw "No such file or
     * directory" — and the failure went to stderr, which nobody launching from a
     * menu ever sees. Now it is one candidate of many: after the person's own
     * choice ($TERMINAL, then `xdg-terminal-exec`, which is how Omarchy and
     * current GNOME launch a terminal), the Debian default, then the terminals
     * people actually install, then the ones a desktop ships, then the old ones.
     *
     * ⚠ THE "RUN THIS" WORD DIFFERS, AND THE WRONG ONE IS SILENT. gnome-terminal
     * takes `--` (`-e` is deprecated and takes ONE string), xfce4-terminal and
     * mate-terminal take `-x` for the same reason, kitty and foot read the
     * command as plain trailing arguments (`--` keeps their option parser off
     * bash's `-lc`), and wezterm needs its `start` subcommand.
     */
    val LINUX_TERMINALS: List<Terminal> = listOf(
        // A launcher, not a terminal: it takes the command as is and runs the one
        // the person chose in xdg-terminals.list. `-e` is optional there and
        // accepted, so nothing goes between.
        Terminal("xdg-terminal-exec", emptyList()),
        Terminal("x-terminal-emulator", listOf("-e")),
        Terminal("ghostty", listOf("-e")),
        Terminal("alacritty", listOf("-e")),
        Terminal("kitty", listOf("--")),
        Terminal("foot", listOf("--")),
        Terminal("wezterm", listOf("start", "--")),
        Terminal("konsole", listOf("-e")),
        Terminal("gnome-terminal", listOf("--")),
        Terminal("ptyxis", listOf("--")),
        Terminal("kgx", listOf("--")),
        Terminal("xfce4-terminal", listOf("-x")),
        Terminal("mate-terminal", listOf("-x")),
        Terminal("terminator", listOf("-x")),
        Terminal("qterminal", listOf("-e")),
        Terminal("urxvt", listOf("-e")),
        Terminal("st", listOf("-e")),
        Terminal("xterm", listOf("-e")),
    )

    /** What [pickLinuxTerminal] decided. */
    sealed interface TerminalPick {
        /** [launcher] is the argv up to the command: where the terminal is, then its "run this" words. */
        class Found(val launcher: List<String>) : TerminalPick

        /** Nothing answered. [tried] names every candidate, in the order it was looked for. */
        class NotFound(val tried: List<String>) : TerminalPick
    }

    /**
     * The terminal a Linux window opens in, decided without touching the machine:
     * [env] reads the environment and [which] says where a program is (null when
     * it is not there), so the whole order is testable with a fake PATH.
     *
     * The Terminal setting ([choice], a [LINUX_TERMINALS] name) comes first: it
     * is this app's own answer, given on its Settings page. Then $TERMINAL,
     * because it is the one place a person SAYS which terminal is theirs
     * machine-wide. Its first word is the program and the rest are its own
     * arguments (the convention every launcher that reads it follows); a terminal
     * this file knows gets its own "run this" word, any other gets `-e`, which is
     * xterm's and the default every launcher assumes. A setting naming a terminal
     * that is gone is skipped and said, like a $TERMINAL that is.
     */
    fun pickLinuxTerminal(env: (String) -> String?, which: (String) -> String?, choice: String = ""): TerminalPick {
        val tried = mutableListOf<String>()
        if (choice.isNotBlank()) {
            tried += "Terminal setting ($choice)"
            which(choice)?.let { path ->
                val run = LINUX_TERMINALS.firstOrNull { it.name == File(choice).name }?.run ?: listOf("-e")
                return TerminalPick.Found(listOf(path) + run)
            }
        }
        val chosen = env("TERMINAL")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (chosen.isNotEmpty()) {
            tried += "\$TERMINAL (${chosen.joinToString(" ")})"
            which(chosen[0])?.let { path ->
                val run = LINUX_TERMINALS.firstOrNull { it.name == File(chosen[0]).name }?.run ?: listOf("-e")
                return TerminalPick.Found(listOf(path) + chosen.drop(1) + run)
            }
        }
        for (t in LINUX_TERMINALS) {
            tried += t.name
            which(t.name)?.let { return TerminalPick.Found(listOf(it) + t.run) }
        }
        return TerminalPick.NotFound(tried)
    }

    /**
     * Where [name] is: on PATH, or in the directories a desktop launcher's PATH
     * tends to leave out. An app started from a menu, a tray or a service gets the
     * session's PATH or less (systemd's user default has no ~/.local/bin), not the
     * one an interactive shell builds. The answer is an absolute path, so the
     * spawn itself never depends on PATH either. A [name] with a `/` in it is
     * already a path and is only checked.
     */
    fun onPath(
        name: String,
        env: (String) -> String?,
        runnable: (File) -> Boolean = { it.isFile && it.canExecute() },
    ): String? {
        if ('/' in name) return File(name).takeIf(runnable)?.path
        val home = env("HOME")?.takeIf { it.isNotBlank() }
        val dirs = env("PATH").orEmpty().split(':').filter { it.isNotBlank() } +
            listOfNotNull(home?.let { "$it/.local/bin" }) +
            listOf("/usr/local/bin", "/usr/bin", "/bin", "/snap/bin")
        return dirs.distinct().map { File(it, name) }.firstOrNull(runnable)?.path
    }

    /**
     * [onPath] for Windows: `;`-separated, and the three places a terminal lives
     * that a shortcut-started app's PATH tends to lack — the Store alias
     * directory `wt.exe` is published through, PowerShell 7's install directory,
     * and Windows PowerShell's. A [name] with a separator in it is a path and is
     * only checked. Nothing here runs anything.
     */
    fun onPathWindows(
        name: String,
        env: (String) -> String?,
        runnable: (File) -> Boolean = { it.isFile },
    ): String? {
        if ('\\' in name || '/' in name) return File(name).takeIf(runnable)?.path
        val dirs = env("PATH").orEmpty().split(';').filter { it.isNotBlank() } +
            listOfNotNull(
                env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { "$it\\Microsoft\\WindowsApps" },
                env("ProgramFiles")?.takeIf { it.isNotBlank() }?.let { "$it\\PowerShell\\7" },
                env("SystemRoot")?.takeIf { it.isNotBlank() }?.let { "$it\\System32\\WindowsPowerShell\\v1.0" },
            )
        return dirs.distinct().map { File(it, name) }.firstOrNull(runnable)?.path
    }

    /** The machine's "where is this program" for [os], over [env]. */
    fun whichFor(os: Os, env: (String) -> String?): (String) -> String? =
        if (os == Os.WINDOWS) ({ onPathWindows(it, env) }) else ({ onPath(it, env) })

    /** The sentence for a Linux machine where nothing in [pickLinuxTerminal]'s list answered. */
    fun noTerminal(tried: List<String>): String =
        "no terminal was found to open it in (tried ${tried.joinToString(", ")}); " +
            "set \$TERMINAL or install one of those"

    fun encoded(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    fun decoded(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    /** An AppleScript string literal's escaping. */
    private fun appleQuoted(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * A word for a PowerShell command line: bare when it is a plain program name,
     * single-quoted (with `'` doubled) when it is a path — `C:\Program Files\…`
     * has a space, and a user directory can hold an apostrophe.
     */
    private fun psArg(s: String): String =
        if (s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) s else "'" + s.replace("'", "''") + "'"

    /**
     * The argv to spawn, or null when the CLI cannot open the name (see [problem]).
     *
     * On Linux [terminal] is the launcher [pickLinuxTerminal] found — the
     * terminal and its "run this" words — and the window's command follows it.
     * On Windows [windows] is [pickWindowsTerminal]'s answer (Windows PowerShell
     * when null, which is what every build before the Terminal setting did).
     * macOS has the one terminal and ignores both.
     *
     * ⚠ THE OUTER `powershell.exe -NoProfile -NonInteractive -Command Start-Process …`
     * STAYS, whichever terminal opens. A console program started straight from
     * this JVM gets pipes for its stdio and no console of its own, so an
     * interactive shell spawned directly would read from a pipe nothing writes.
     * `Start-Process` gives the window a console and detaches it; the outer
     * shell is Windows PowerShell because it is on every machine.
     */
    fun argv(
        name: String,
        os: Os = this.os,
        terminal: List<String> = emptyList(),
        windows: WindowsPick? = null,
    ): List<String>? {
        if (problem(name) != null) return null
        return when (os) {
            Os.WINDOWS -> {
                val w = windows ?: WindowsPick(WindowsTerminal.POWERSHELL, WindowsTerminal.POWERSHELL.exe, WindowsTerminal.POWERSHELL)
                val b64 = encoded(script(name))
                val inner = when (w.terminal) {
                    // `-w 0`: into the existing window, or a new one when there is
                    // none; `nt`: a new tab running the shell with our script.
                    WindowsTerminal.WINDOWS_TERMINAL ->
                        "Start-Process ${psArg(w.path)} -ArgumentList '-w','0','nt','${w.shell.exe}','-NoExit','-EncodedCommand','$b64'"
                    else -> "Start-Process ${psArg(w.path)} -ArgumentList '-NoExit','-EncodedCommand','$b64'"
                }
                listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", inner)
            }
            Os.MAC -> listOf(
                "osascript",
                "-e", "tell application \"Terminal\" to do script \"${appleQuoted(posixLine(name))}\"",
                "-e", "tell application \"Terminal\" to activate",
            )
            Os.LINUX -> terminal + linuxCommand(name)
        }
    }

    /** Where the CLI lives on this OS — where install.ps1 / install.sh put it. */
    fun cliFile(os: Os, home: File): File =
        File(home, if (os == Os.WINDOWS) ".huginn/huginn.ps1" else ".huginn/huginn.sh")

    /**
     * What the header's menu offers for [name]: open it, or — when the CLI
     * cannot — copy the raw tmux line that reaches the session exactly as named
     * (the line the CLI's own refusal suggests). [copy] is null when even that
     * would not be safe to paste into a shell.
     */
    class Offer(val label: String, val opens: Boolean, val copy: String?, val why: String?)

    fun offer(name: String, os: Os = this.os): Offer {
        val why = problem(name) ?: return Offer(label(os), true, shellLine(name), null)
        val raw = if (TMUX_SAFE.matches(name)) "ssh -t huginn \"tmux attach -t '=$name'\"" else null
        return Offer("Copy tmux attach command", false, raw, why)
    }

    /**
     * Open the window. Returns null on success, else one sentence saying why not —
     * the caller decides where that goes (the session header puts the shell line
     * on the clipboard so the person can paste it themselves).
     *
     * ⚠ A SPAWN IS NOT A SUCCESS WHEN THERE IS NO CLI TO RUN (2026-10-02). The
     * window's own exit status never comes back, so a machine without the CLI
     * opened a window saying "'huginn' is not recognized" and this returned
     * null — no clipboard, no log. The one failure that CAN be seen from here is
     * checked first: the file every install path puts the CLI in.
     *
     * [env] and [which] are the machine as the terminal choice sees it
     * ([pickLinuxTerminal], [pickWindowsTerminal]); a test hands in its own.
     * [choice] is the Terminal setting, [preferred] unless a test says otherwise.
     */
    fun open(
        name: String,
        os: Os = this.os,
        home: File = File(System.getProperty("user.home") ?: "."),
        env: (String) -> String? = System::getenv,
        which: (String) -> String? = whichFor(os, env),
        choice: String = preferred(),
        start: (List<String>) -> Unit = { spawn(it, os) },
    ): String? {
        problem(name)?.let { return "'$name' cannot be opened through the huginn CLI: $it; not opening it" }
        val cli = cliFile(os, home)
        if (!cli.isFile) return "the huginn CLI is not installed here (no ${cli.path}); install it, then run: ${shellLine(name)}"
        val terminal = if (os != Os.LINUX) emptyList() else when (val pick = pickLinuxTerminal(env, which, choice)) {
            is TerminalPick.Found -> pick.launcher
            is TerminalPick.NotFound -> return noTerminal(pick.tried)
        }
        val windows = if (os == Os.WINDOWS) pickWindowsTerminal(choice, which) else null
        val argv = argv(name, os, terminal, windows) ?: return "'$name' cannot be opened through the huginn CLI; not opening it"
        return try { start(argv); null } catch (e: Exception) { "could not open a terminal: ${e.message}" }
    }

    /**
     * ⚠ ON LINUX THE TERMINAL'S OWN OUTPUT GOES NOWHERE. Left on the default pipe
     * that nothing reads, a terminal that logs to stderr (GTK warnings, debug
     * lines) fills it and blocks on its next write, and once this app quits the
     * pipe has no reader at all: a terminal that does not ignore SIGPIPE is then
     * killed by its next write, taking the person's session with it. The window's
     * shell has the terminal's own pty; only the emulator's chatter is discarded.
     * Windows and macOS are spawned exactly as before.
     */
    private fun spawn(argv: List<String>, os: Os) {
        val pb = ProcessBuilder(argv)
        if (os == Os.LINUX) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        } else {
            pb.redirectErrorStream(true)
        }
        pb.start()
    }

    /**
     * What the reader is told when [open] could not open [name]. What they can do
     * comes first — the line to type is on the clipboard (the caller puts it
     * there) — because the status line shows one line and cuts the rest; the
     * reason follows, whole in its tooltip.
     */
    fun failureNote(name: String, why: String): String =
        "Copied ${shellLine(name)} to the clipboard instead of opening a terminal: $why"
}
