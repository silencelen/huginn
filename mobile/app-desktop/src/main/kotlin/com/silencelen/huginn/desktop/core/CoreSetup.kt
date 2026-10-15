package com.silencelen.huginn.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Getting `huginn core` onto this machine from the app (D9: a GUI install always
 * brings its core).
 *
 * Two steps, and only the second is a decision the person makes:
 *  1. [ensureInstalled] — copy the core that SHIPS INSIDE this app into the
 *     per-user layout (the core's own `self-install`; no admin, no prompt). The
 *     app does this by itself at launch, because a GUI without its core is the
 *     state this whole design exists to make impossible.
 *  2. [installService] — "Set up huginn core": start it at boot, as this user,
 *     with nobody signed in. On Windows that registers a scheduled task, which
 *     needs ONE UAC prompt; on Linux it is a systemd user unit + linger.
 *
 * The paths MUST match core/internal/install (Layout.Binary()).
 */
internal object CoreSetup {
    private val windows = (System.getProperty("os.name") ?: "").startsWith("Windows")

    /** The installed core, or null. */
    fun installedBinary(): Path? {
        val p = if (windows) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "huginn", "bin", "huginn.exe") }
        } else {
            Path.of(System.getProperty("user.home"), ".local", "bin", "huginn")
        }
        return p?.takeIf { Files.isRegularFile(it) }
    }

    /**
     * The core that shipped inside this app: Linux packages carry it in the
     * Compose app-resources directory, the Windows installer in `<app>\core\`.
     * Null for a from-source run.
     */
    fun bundledBinary(): Path? {
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it, if (windows) "huginn.exe" else "huginn")) }
            System.getProperty("jpackage.app-path")?.let { add(Path.of(it).parent.resolve("core").resolve(if (windows) "huginn.exe" else "huginn")) }
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    data class Ran(val exit: Int, val output: String)

    private fun run(cmd: List<String>, timeoutS: Long = 120): Ran {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        // The core installs under $HOME; this side looks under user.home. They
        // are the same in practice — said explicitly so they can never disagree.
        if (!windows) pb.environment()["HOME"] = System.getProperty("user.home")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return Ran(-1, out + "\n(timed out)") }
        return Ran(p.exitValue(), out.trim())
    }

    /**
     * The installed core, installing the bundled one first when there is none.
     *
     * ⚠ RUN FROM A TEMP COPY, not in place: the Linux packager does not keep the
     * executable bit on app-resources (measured: 0644 inside the app image), and
     * a file in an app bundle is the wrong thing to make executable anyway.
     */
    suspend fun ensureInstalled(): Result<Path> = withContext(Dispatchers.IO) {
        runCatching {
            installedBinary()?.let { return@runCatching it }
            val bundled = bundledBinary() ?: error("this build carries no huginn core — install it with `huginn core install`")
            val tmp = Files.createTempFile("huginn-core-", if (windows) ".exe" else "")
            try {
                Files.copy(bundled, tmp, StandardCopyOption.REPLACE_EXISTING)
                tmp.toFile().setExecutable(true, true)
                val r = run(listOf(tmp.toString(), "core", "self-install"))
                check(r.exit == 0) { "self-install failed: ${r.output.takeLast(300)}" }
            } finally {
                Files.deleteIfExists(tmp)
            }
            installedBinary() ?: error("self-install reported success but no core is at the install path")
        }
    }

    /** "Set up huginn core": install it to start at boot. Returns the core's own report. */
    suspend fun installService(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bin = ensureInstalled().getOrThrow()
            val r = if (windows) {
                // One UAC prompt, for the task registration only; the elevated
                // child is this same user, so the task runs as them. Its output
                // cannot cross the elevation boundary, so the exit code is the answer
                // and `core status` (unelevated) is what the person then reads.
                val ps = "\$p = Start-Process -FilePath '${bin.toString().replace("'", "''")}' " +
                    "-ArgumentList 'core','install' -Verb RunAs -Wait -PassThru; exit \$p.ExitCode"
                run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps), timeoutS = 300)
            } else {
                run(listOf(bin.toString(), "core", "install"), timeoutS = 180)
            }
            check(r.exit == 0) { r.output.lines().lastOrNull { it.isNotBlank() } ?: "huginn core install failed (exit ${r.exit})" }
            r.output.ifBlank { "huginn core installed" }
        }
    }
}
