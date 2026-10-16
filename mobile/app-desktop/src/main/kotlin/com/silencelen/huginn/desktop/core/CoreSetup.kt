package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.update.BuildInfo
import com.silencelen.huginn.update.Semver
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

    /** The version an installed core reports (`huginn version`), or null. */
    fun installedVersion(bin: Path): String? =
        runCatching { VERSION_LINE.find(run(listOf(bin.toString(), "version"), timeoutS = 15).output)?.groupValues?.get(1) }.getOrNull()

    private val VERSION_LINE = Regex("^\\(o> huginn (\\S+) ", RegexOption.MULTILINE)

    /**
     * Whether the bundled core (this app's own version: lockstep, D10) should
     * replace an installed core reporting [have]. Unreadable keeps what is there.
     */
    fun bundledIsNewer(have: String?, bundled: String = BuildInfo.VERSION): Boolean =
        have != null && Semver.isNewer(bundled, have)

    /**
     * The installed core, installing the bundled one first when there is none
     * or the bundled one is newer: an app update brings its core, and the
     * core's self-install restarts the running core onto it and starts the tray.
     *
     * ⚠ RUN FROM A TEMP COPY, not in place: the Linux packager does not keep the
     * executable bit on app-resources (measured: 0644 inside the app image), and
     * a file in an app bundle is the wrong thing to make executable anyway.
     */
    suspend fun ensureInstalled(): Result<Path> = withContext(Dispatchers.IO) {
        runCatching {
            val found = bundledBinary()
            installedBinary()?.let { inst ->
                if (found == null || !bundledIsNewer(installedVersion(inst))) return@runCatching inst
            }
            val bundled = found ?: error("this build carries no huginn core — install it with `huginn core install`")
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

    /**
     * The core's boot service is registered for this user (the path and the
     * task name are core/internal/service's: UnitPath() and TaskName).
     */
    fun serviceInstalled(): Boolean = if (windows) {
        runCatching { run(listOf("schtasks", "/Query", "/TN", "Huginn Core"), timeoutS = 15).exit == 0 }.getOrDefault(false)
    } else {
        val cfg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }
            ?: (System.getProperty("user.home") + "/.config")
        Files.isRegularFile(Path.of(cfg, "systemd", "user", "huginn-core.service"))
    }

    /**
     * Starts an installed core that is not running: `huginn core start`, which
     * uses the boot service and, on Windows, starts the core directly when Task
     * Scheduler holds the task back (Energy Saver on battery queued every start
     * on PC01, 2026-10-16). No prompt on either platform.
     */
    suspend fun startService(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bin = installedBinary() ?: error("huginn core is not installed")
            val r = run(listOf(bin.toString(), "core", "start"), timeoutS = 60)
            check(r.exit == 0) { r.output.lines().lastOrNull { it.isNotBlank() } ?: "could not start huginn core (exit ${r.exit})" }
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
            // The elevated step never starts the tray (it would run as
            // administrator); this unelevated self-install does, once the task's
            // core answers.
            if (windows) run(listOf(bin.toString(), "core", "self-install"), timeoutS = 120)
            r.output.ifBlank { "huginn core installed" }
        }
    }
}
