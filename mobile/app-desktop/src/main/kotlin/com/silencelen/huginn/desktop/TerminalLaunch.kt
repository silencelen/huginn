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
        "headroom", "devices", "projects", "project", "llm", "device", "local",
        "uninstall", "desktop", "usage", "cost", "ccusage", "solo", "rename", "mv",
        "kill", "end", "archive", "revive", "unarchive", "attach",
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

    /** The menu word: the thing that opens, named after the shell the OS has. */
    fun label(os: Os = this.os): String = if (os == Os.WINDOWS) "Open in PowerShell" else "Open in terminal"

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

    fun encoded(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    fun decoded(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    /** An AppleScript string literal's escaping. */
    private fun appleQuoted(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The argv to spawn, or null when the CLI cannot open the name (see [problem]). */
    fun argv(name: String, os: Os = this.os): List<String>? {
        if (problem(name) != null) return null
        return when (os) {
            Os.WINDOWS -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process powershell.exe -ArgumentList '-NoExit','-EncodedCommand','${encoded(script(name))}'",
            )
            Os.MAC -> listOf(
                "osascript",
                "-e", "tell application \"Terminal\" to do script \"${appleQuoted(posixLine(name))}\"",
                "-e", "tell application \"Terminal\" to activate",
            )
            // ⚠ THE CLI IS SOURCED HERE, NOT LEFT TO THE LOGIN SHELL (desktop
            // breaker, 2026-10-02). `bash -lc` reads only the FIRST of
            // ~/.bash_profile, ~/.bash_login, ~/.profile — install.sh wires
            // ~/.profile and ~/.bashrc — so a user's own ~/.bash_profile hides
            // the first, and Debian's .bashrc returns early for a
            // non-interactive shell before reaching the second: the window said
            // "huginn: command not found". The Windows script dot-sources its
            // file for the same reason; harmless when a startup file already did.
            Os.LINUX -> listOf(
                "x-terminal-emulator", "-e", "bash", "-lc",
                "[ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh; ${posixLine(name)}; exec bash",
            )
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
     */
    fun open(
        name: String,
        os: Os = this.os,
        home: File = File(System.getProperty("user.home") ?: "."),
        start: (List<String>) -> Unit = { ProcessBuilder(it).redirectErrorStream(true).start() },
    ): String? {
        problem(name)?.let { return "'$name' cannot be opened through the huginn CLI: $it; not opening it" }
        val argv = argv(name, os) ?: return "'$name' cannot be opened through the huginn CLI; not opening it"
        val cli = cliFile(os, home)
        if (!cli.isFile) return "the huginn CLI is not installed here (no ${cli.path}); install it, then run: ${shellLine(name)}"
        return try { start(argv); null } catch (e: Exception) { "could not open a terminal: ${e.message}" }
    }
}
