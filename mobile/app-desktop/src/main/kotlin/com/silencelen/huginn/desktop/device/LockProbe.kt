package com.silencelen.huginn.desktop.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Whether somebody is sitting in front of this machine.
 *
 * The scope rule the owner chose — a machine at `own` drops to read-only while
 * locked — needs an answer to this, and the JVM has no portable way to ask. So
 * each platform gets the one probe that is actually reliable there:
 *
 *   Windows  LogonUI.exe runs exactly while the lock screen is up.
 *   Linux    loginctl reports LockedHint for the session.
 *
 * ⚠ UNKNOWN COUNTS AS LOCKED. On a platform with no probe (macOS today), or when
 * the probe itself fails, this reports `true` and the machine stays read-only.
 * That makes `act` unavailable rather than quietly unguarded — the opposite
 * default would honour the letter of the setting while dropping the thing it was
 * chosen for, and nobody would find out until something ran at 3am. The UI says
 * so out loud rather than leaving the owner to wonder why Act is refused.
 */
object LockProbe {

    /** True = locked or unknowable, false = definitely somebody's there. */
    suspend fun locked(): Boolean = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        when {
            os.contains("win") -> windowsLocked()
            os.contains("linux") -> linuxLocked()
            else -> true
        }
    }

    /** Whether this platform can answer at all — for the honest label in Settings. */
    fun supported(): Boolean {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return os.contains("win") || os.contains("linux")
    }

    private fun windowsLocked(): Boolean {
        // Get-Process throws when nothing matches, hence the explicit SilentlyContinue
        // and a printed word rather than an exit code.
        val out = run(
            listOf(
                "powershell", "-NoProfile", "-Command",
                "if (Get-Process LogonUI -ErrorAction SilentlyContinue) { 'locked' } else { 'open' }",
            ),
        ) ?: return true
        return !out.contains("open")
    }

    private fun linuxLocked(): Boolean {
        val session = System.getenv("XDG_SESSION_ID")
        val args = if (session.isNullOrBlank()) {
            listOf("loginctl", "show-session", "self", "-p", "LockedHint")
        } else {
            listOf("loginctl", "show-session", session, "-p", "LockedHint")
        }
        val out = run(args) ?: return true
        // `run` lowercases, so match lowercase: comparing against "LockedHint=no"
        // here would never match and every Linux machine would read as locked
        // forever — a fence that looks like it works because it only ever says no.
        return !out.contains("lockedhint=no")
    }

    private fun run(args: List<String>): String? = try {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        // Bounded: this runs on a loop, and a probe that hangs would freeze the
        // beat that reports whether this machine is still listening at all.
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else {
            p.inputStream.readBytes().decodeToString().trim().lowercase()
        }
    } catch (_: Exception) {
        null
    }
}
