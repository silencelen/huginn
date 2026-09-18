package com.silencelen.huginn.desktop.setup

/**
 * Which `claude` this machine runs, found the way a person at a terminal would
 * and PROVEN by running it.
 *
 * ⚠ THE FAILURE THIS EXISTS FOR IS NAMED IN THE CODE THAT SUFFERS IT.
 * `DeviceRunner` calls a missing claude "the most likely first-run failure by a
 * wide margin", and until now nothing anywhere looked: the Settings field was
 * free text with no detection and no probe, so a wrong or empty value was
 * discovered at the FIRST JOB, as a spawn failure posted into somebody's chat.
 *
 * The shape is [com.silencelen.huginn.desktop.LocalServe.findNode]'s, deliberately
 * — it is the one good detection in the product and it is field-proven. Every
 * candidate is run with `--version` and believed only if it exits 0; existence
 * on disk proves nothing, because the interesting failures are a shim that
 * cannot be spawned and a binary for the wrong architecture, and both of those
 * exist perfectly well.
 *
 * A GUI app's PATH is whatever the desktop session had at login. An fnm/nvm
 * shell hook, a per-user install, a `~/.local/bin` added by a dotfile — all of
 * them leave `claude` visible in every terminal and invisible to a process
 * started from a Start Menu shortcut. That is why the candidate list exists at
 * all rather than just trusting PATH.
 */
object ClaudePath {

    /** What a probe found, or why it did not. */
    data class Found(val path: String, val version: String)

    /**
     * Every place worth looking, PATH first, in the order a person would try.
     *
     * Pure, and taking its environment as a parameter, because the list per OS
     * is the part worth asserting and neither a test nor a reviewer can set
     * `%LOCALAPPDATA%` on the JVM they are running in.
     *
     * @param windows the host OS, asked once by the caller.
     * @param env a reader over the environment — `System::getenv` in production.
     * @param home `user.home`, which on Windows is also where `.claude` lives.
     */
    fun candidates(windows: Boolean, env: (String) -> String?, home: String?): List<String> =
        buildList {
            // PATH first and always: when the session's PATH is right this is
            // both the fastest answer and the one the user would expect to see
            // recorded — an absolute path pinned here would survive an upgrade
            // that moved the binary.
            add(if (windows) "claude.exe" else "claude")
            if (windows) {
                // ⚠ JOINED WITH A LITERAL BACKSLASH, NEVER WITH `File(parent,
                // child)`. `File` uses the separator of the JVM it is RUNNING
                // on, so on a Linux build box every Windows candidate came out
                // as `C:\Users\jacob\AppData\Local/Programs\claude\claude.exe` —
                // correct on Windows, nonsense anywhere else, and therefore
                // impossible to assert before it reached the owner's machine.
                // A Windows path is a Windows path wherever it is built.
                //
                // `claude.cmd` is the npm-global shim, and on Windows npm puts
                // it in %APPDATA%\npm rather than beside node.
                env("LOCALAPPDATA")?.let {
                    val base = it.trimEnd('\\')
                    add("$base\\Programs\\claude\\claude.exe")
                    add("$base\\Programs\\claude\\bin\\claude.exe")
                    add("$base\\AnthropicClaude\\claude.exe")
                }
                env("APPDATA")?.let { add("${it.trimEnd('\\')}\\npm\\claude.cmd") }
                env("ProgramFiles")?.let { add("${it.trimEnd('\\')}\\claude\\claude.exe") }
                home?.let {
                    val base = it.trimEnd('\\')
                    add("$base\\.local\\bin\\claude.exe")
                    add("$base\\.claude\\local\\claude.exe")
                    add("$base\\.bun\\bin\\claude.exe")
                }
            } else {
                add("/usr/local/bin/claude")
                add("/usr/bin/claude")
                // macOS, where Homebrew on Apple silicon is not on a GUI app's PATH.
                add("/opt/homebrew/bin/claude")
                home?.let {
                    add("$it/.local/bin/claude")
                    add("$it/.claude/local/claude")
                    add("$it/.bun/bin/claude")
                    add("$it/.npm-global/bin/claude")
                }
            }
        }

    /**
     * The argv that runs a candidate.
     *
     * ⚠ A `.cmd`/`.bat` SHIM CANNOT BE SPAWNED BY `ProcessBuilder`. Windows
     * resolves those through the command processor, not through CreateProcess,
     * so handing one straight to a ProcessBuilder fails with "CreateProcess
     * error=193, %1 is not a valid Win32 application" — which reads like a
     * corrupt binary rather than like a file that needs `cmd /c`. The npm-global
     * install of claude is exactly a `.cmd`, so this is the ordinary case on
     * Windows rather than an edge one.
     */
    fun commandFor(candidate: String): List<String> {
        val lower = candidate.lowercase()
        return when {
            lower.endsWith(".cmd") || lower.endsWith(".bat") ->
                listOf("cmd", "/c", candidate, "--version")
            // A PowerShell shim needs its own host, and `-File` rather than
            // `-Command` so a path with a space is one argument.
            lower.endsWith(".ps1") ->
                listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", candidate, "--version")
            else -> listOf(candidate, "--version")
        }
    }

    /**
     * What `claude --version` said, reduced to something worth showing.
     *
     * Deliberately forgiving about the SHAPE. The version line has changed
     * format before and will again; what the reader needs from this step is
     * evidence that something answered, so the first non-empty line is the
     * answer and a semver in it is merely preferred. Empty output with exit 0 is
     * NOT a pass — a shim that swallows its arguments exits 0 saying nothing,
     * and reporting that as a working claude is the failure all over again.
     */
    fun parseVersion(raw: String?): String? {
        val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val semver = Regex("""\d+\.\d+\.\d+\S*""").find(line)?.value
        return (semver ?: line).take(80)
    }

    /**
     * Runs one candidate and believes it only on exit 0 with something to say.
     *
     * The timeout is the load-bearing part: a candidate that is a directory, a
     * half-written download or a shim waiting on a locked file will otherwise
     * hang the whole detection behind it, on a screen whose entire job is to
     * answer quickly.
     */
    fun probe(candidate: String, timeoutMs: Long = PROBE_TIMEOUT_MS): String? = try {
        val p = ProcessBuilder(commandFor(candidate)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() == 0) {
            parseVersion(out)
        } else {
            null
        }
    } catch (_: Exception) {
        null // next candidate
    }

    /**
     * Detection proper: an explicit override first, then the candidate list.
     *
     * ⚠ AN OVERRIDE IS PROVEN, NOT TRUSTED. Somebody who has typed a path into
     * the Settings field has usually typed it because something was wrong, so
     * believing it unchecked would leave the one person most likely to be
     * mistaken with no feedback at all. A bad override FAILS here rather than
     * silently falling through to PATH — falling through would "work" and then
     * run a different binary than the one on screen.
     */
    fun detect(
        override: String? = null,
        windows: Boolean = isWindows(),
        env: (String) -> String? = System::getenv,
        home: String? = System.getProperty("user.home"),
    ): Result<Found> {
        val explicit = override?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            val version = probe(explicit)
            return if (version != null) {
                Result.success(Found(explicit, version))
            } else {
                Result.failure(IllegalStateException(REFUSED_OVERRIDE.format(explicit)))
            }
        }
        for (candidate in candidates(windows, env, home)) {
            val version = probe(candidate)
            if (version != null) return Result.success(Found(candidate, version))
        }
        return Result.failure(IllegalStateException(if (windows) NOT_FOUND_WINDOWS else NOT_FOUND_UNIX))
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    /** 4s per candidate: long enough for a cold start, short enough for a list of ten. */
    const val PROBE_TIMEOUT_MS: Long = 4_000

    const val REFUSED_OVERRIDE: String =
        "%s did not answer `claude --version`, so work would fail the moment it started. " +
            "Clear the field to search for claude instead."

    /** Named install instructions, per OS, because "not found" alone is a dead end. */
    const val NOT_FOUND_WINDOWS: String =
        "claude was not found on this machine. Install Claude Code (claude.ai/download, or " +
            "npm install -g @anthropic-ai/claude-code), or type the full path to claude.exe below."

    const val NOT_FOUND_UNIX: String =
        "claude was not found on this machine. Install Claude Code (claude.ai/download, or " +
            "npm install -g @anthropic-ai/claude-code), or type the full path to it below."
}
