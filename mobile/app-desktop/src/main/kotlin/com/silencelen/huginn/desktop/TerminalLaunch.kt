package com.silencelen.huginn.desktop

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
 * The name is checked against the slug alphabet before it goes anywhere near a
 * shell. tmux names made by appd are slugs; one that is not is not opened.
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

    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /** The menu word: the thing that opens, named after the shell the OS has. */
    fun label(os: Os = this.os): String = if (os == Os.WINDOWS) "Open in PowerShell" else "Open in terminal"

    /** The line a person would type. Also what lands on the clipboard when opening fails. */
    fun shellLine(name: String): String = "huginn '$name'"

    /** The PowerShell script the new window runs. */
    fun script(name: String): String =
        "if (Test-Path \"\$HOME\\.huginn\\huginn.ps1\") { . \"\$HOME\\.huginn\\huginn.ps1\" }; ${shellLine(name)}"

    fun encoded(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    fun decoded(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    /** The argv to spawn, or null when the name is not one a shell may see. */
    fun argv(name: String, os: Os = this.os): List<String>? {
        if (!SAFE_NAME.matches(name)) return null
        return when (os) {
            Os.WINDOWS -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process powershell.exe -ArgumentList '-NoExit','-EncodedCommand','${encoded(script(name))}'",
            )
            Os.MAC -> listOf(
                "osascript",
                "-e", "tell application \"Terminal\" to do script \"${shellLine(name)}\"",
                "-e", "tell application \"Terminal\" to activate",
            )
            Os.LINUX -> listOf("x-terminal-emulator", "-e", "bash", "-lc", "${shellLine(name)}; exec bash")
        }
    }

    /**
     * Open the window. Returns null on success, else one sentence saying why not —
     * the caller decides where that goes (the session header puts the shell line
     * on the clipboard so the person can paste it themselves).
     */
    fun open(
        name: String,
        os: Os = this.os,
        start: (List<String>) -> Unit = { ProcessBuilder(it).redirectErrorStream(true).start() },
    ): String? {
        val argv = argv(name, os) ?: return "'$name' has characters a shell would read; not opening it"
        return try { start(argv); null } catch (e: Exception) { "could not open a terminal: ${e.message}" }
    }
}
