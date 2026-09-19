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
 *   Windows  LogonUI.exe runs exactly while a lock screen is up, per session,
 *            PLUS `quser` for the one case LogonUI alone gets wrong (below).
 *   Linux    loginctl reports LockedHint for the session.
 *
 * ⚠ AN RDP SESSION COUNTS AS BEING HERE. `Get-Process LogonUI` on its own asks
 * "is A lock screen up anywhere on this box", and on a machine somebody reaches
 * remotely the answer is permanently yes: the console session they are not using
 * sits locked while they work in the RDP one. The box then reports itself locked
 * for as long as it is switched on, drops to `look`, and refuses every Act — on
 * the machine whose entire purpose is to be worked on remotely. So an RDP row in
 * state `Active` is read as presence.
 *
 * ⚠ AND ONLY WHILE THAT SESSION IS ITSELF UNLOCKED. "RDP is active" alone would
 * grant Act to a remote session sitting on its own lock screen — the same
 * unwatched machine the rule exists for, reached from a different stack. The
 * session id on the quser row and the session ids LogonUI runs in are what
 * separate the two, so both halves come from one probe and are compared.
 * A `Disc` (disconnected) session is nobody: it is what the machine looks like
 * after somebody closes the remote window.
 *
 * ⚠ UNKNOWN COUNTS AS LOCKED — AND IS STILL SAID TO BE UNKNOWN. On a platform
 * with no probe (macOS today), or when the probe itself fails, [locked] reports
 * `true` and the machine stays read-only. That makes `act` unavailable rather
 * than quietly unguarded — the opposite default would honour the letter of the
 * setting while dropping the thing it was chosen for, and nobody would find out
 * until something ran at 3am.
 *
 * ⚠⚠ BUT THE FENCE'S DEFAULT IS NOT A READING, AND THE UI MUST NOT PRINT IT AS
 * ONE. [read] answers [Reading.UNKNOWN] rather than folding it into LOCKED,
 * because the two produce different sentences: a machine whose screen really is
 * locked can be unlocked by whoever is sitting at it, and a machine with no
 * logind session at all — an LXC, a container, `startx` without `pam_systemd`, a
 * kiosk — cannot, by anybody, ever. Settings used to choose between those two
 * sentences by asking `os.name`, so every such Linux box was told to go and
 * unlock a screen that does not exist. Ask the PROBE whether it answered; never
 * the platform.
 *
 * ⚠ THIS IS A PRESENCE PROBE, NOT A PERMISSION. What a locked machine is allowed
 * to do is [com.silencelen.huginn.device.DevicePolicy]'s answer, and whether a
 * lock withdraws anything at all on THIS box is the owner's — the
 * "Keep act mode while locked" setting, applied by [DeviceRunner] in front of
 * the policy. This file only ever answers "is somebody there".
 */
object LockProbe {

    /**
     * What the probe actually found — three answers, not two.
     *
     * [UNKNOWN] is the one that had to exist: it is what a box with no session to
     * ask about returns, and it is NOT the same state as a screen somebody has
     * locked. [locked] folds it back to "locked" for the fence, which is the
     * conservative default the policy wants; the UI reads [known] instead and
     * says the honest sentence.
     */
    enum class Reading {
        LOCKED,
        UNLOCKED,
        UNKNOWN,
        ;

        /** The fence's answer: unknown is locked. */
        val locked: Boolean get() = this != UNLOCKED

        /** Whether the probe answered at all. The UI's question, never the fence's. */
        val known: Boolean get() = this != UNKNOWN
    }

    /** True = locked or unknowable, false = definitely somebody's there. */
    suspend fun locked(): Boolean = read().locked

    /**
     * The same probe, ANSWERING RATHER THAN NODDING. The caller that has to
     * explain itself to a person needs the third state, and a boolean cannot
     * carry it — [locked] is that boolean and is all the policy ever wanted.
     */
    suspend fun read(): Reading = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        when {
            os.contains("win") -> windowsReading()
            os.contains("linux") -> linuxReading()
            // No probe on this platform (macOS today). Not "locked": nothing here
            // was ever asked, and telling the owner to unlock a Mac that is wide
            // open is the bug this enum exists to stop.
            else -> Reading.UNKNOWN
        }
    }

    // ------------------------------------------------------------- windows

    /**
     * One row of `quser` / `query user`, as printed.
     *
     * @param session the SESSIONNAME column — `console`, `rdp-tcp#7`, or EMPTY
     *   for a disconnected session, which prints nothing there at all.
     * @param current whether this is the session the probe itself ran in (the
     *   `>` quser puts in column 0).
     */
    internal data class QuserRow(
        val user: String,
        val session: String,
        val id: Int,
        val state: String,
        val current: Boolean,
    )

    private val RDP_SESSION = Regex("^rdp-tcp#\\d+$")

    /**
     * `quser` output → rows, tolerating the two column shapes it prints.
     *
     * ⚠ NOT BY COLUMN COUNT. A disconnected session prints no SESSIONNAME at
     * all, so that line has one field fewer than every other and a parser that
     * counted fields reads its ID as the state and its state as the idle time —
     * which turns "nobody is here" into an unrecognised state and, one `else`
     * later, into "somebody is". The ID is found by being the first field that
     * is a bare number (the username is not, the session name is not, and the
     * idle time that can also be a bare number comes after the state), and
     * everything else is placed relative to it.
     *
     * ⚠ AND THE INPUT IS WHATEVER THE SHELL PRINTED. quser writes "No User
     * exists for *" to stderr and exits non-zero when nobody is signed in, the
     * probe folds stderr in, and localised Windows prints different words for
     * everything. Anything that does not parse as a row is not a row — the
     * caller then falls back to the LogonUI rule, which is locale-independent
     * and is what this machine did before the RDP case existed.
     */
    internal fun parseQuser(text: String?): List<QuserRow> =
        (text ?: "").lineSequence().mapNotNull { raw ->
            val current = raw.startsWith(">")
            val fields = raw.removePrefix(">").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.size < 3) return@mapNotNull null
            val idAt = fields.indexOfFirst { it.toIntOrNull() != null }
            // idAt >= 1 keeps a bare-number first field from being read as an
            // id with no user; +1 has to exist because the state follows it.
            if (idAt < 1 || idAt + 1 >= fields.size) return@mapNotNull null
            QuserRow(
                user = fields[0],
                session = if (idAt >= 2) fields[1] else "",
                id = fields[idAt].toInt(),
                state = fields[idAt + 1].lowercase(),
                current = current,
            )
        }.toList()

    /**
     * The rule, as a pure function of what the probe saw.
     *
     * @param quser what `quser` printed, or null if it could not be asked.
     * @param logonUiSessions the session ids LogonUI is running in, or null if
     *   THAT could not be asked — in which case nothing can be ruled out and the
     *   answer is [Reading.UNKNOWN], which the fence still reads as locked,
     *   exactly as it did before this file knew about RDP.
     */
    internal fun reading(quser: String?, logonUiSessions: Set<Int>?): Reading {
        if (logonUiSessions == null) return Reading.UNKNOWN
        val here = parseQuser(quser).any {
            it.state == "active" && RDP_SESSION.matches(it.session) && it.id !in logonUiSessions
        }
        if (here) return Reading.UNLOCKED
        // Today's rule, unchanged: a lock screen anywhere means nobody is at the
        // one place this machine could otherwise be being used from.
        return if (logonUiSessions.isNotEmpty()) Reading.LOCKED else Reading.UNLOCKED
    }

    /** [reading] as the fence sees it. The rule's own tests are written against this. */
    internal fun verdict(quser: String?, logonUiSessions: Set<Int>?): Boolean =
        reading(quser, logonUiSessions).locked

    private fun windowsReading(): Reading {
        // ONE child, two answers, so the two halves cannot describe different
        // moments: a second spawn is a second chance for somebody to lock the
        // screen between them, and the comparison is between session IDS.
        //
        // Get-Process throws when nothing matches, hence SilentlyContinue; quser
        // is absent on some editions and exits non-zero when nobody is signed
        // in, hence the guard and the fold — a missing quser must read as "no
        // rows", never as a failed probe.
        val out = run(
            listOf(
                "powershell", "-NoProfile", "-Command",
                "\$ErrorActionPreference='SilentlyContinue'; " +
                    "\$s=@(Get-Process LogonUI | Select-Object -ExpandProperty SessionId); " +
                    "Write-Output ('LOGONUI ' + (\$s -join ' ')); " +
                    "Write-Output 'QUSER'; " +
                    "\$q = & quser 2>&1; if (\$q) { \$q | Write-Output }",
            ),
        ) ?: return Reading.UNKNOWN
        val marker = out.indexOf("logonui ")
        if (marker < 0) return Reading.UNKNOWN  // the shell answered something else entirely
        val body = out.substring(marker + "logonui ".length)
        val split = body.indexOf("quser")
        if (split < 0) return Reading.UNKNOWN
        val ids = body.substring(0, split).trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }.toSet()
        return reading(body.substring(split + "quser".length), ids)
    }

    // --------------------------------------------------------------- linux

    private fun linuxReading(): Reading {
        val session = System.getenv("XDG_SESSION_ID")
        val args = if (session.isNullOrBlank()) {
            listOf("loginctl", "show-session", "self", "-p", "LockedHint")
        } else {
            listOf("loginctl", "show-session", session, "-p", "LockedHint")
        }
        return linuxReading(run(args))
    }

    /**
     * `loginctl show-session … -p LockedHint` → a reading. Pure, because the
     * interesting cases are the ones that are NOT a LockedHint line.
     *
     * ⚠ `run` LOWERCASES, so match lowercase: comparing against "LockedHint=no"
     * here would never match and every Linux machine would read as locked
     * forever — a fence that looks like it works because it only ever says no.
     *
     * ⚠⚠ AND "NOT no" IS NOT "yes". `loginctl` exits non-zero with *"Caller does
     * not belong to any known session"* on a box with no logind session at all —
     * a container, an LXC, `startx` without `pam_systemd`, a kiosk — and the
     * probe folds stderr in, so that sentence arrived here and was read as a
     * locked screen. The device then sat `locked: true, effectiveScope: look`
     * for the life of the process while Settings told the owner to go and unlock
     * a machine that has no screen to unlock. Anything that is not one of the two
     * hints is [Reading.UNKNOWN], which is still locked to the fence and is a
     * different sentence to the reader.
     */
    internal fun linuxReading(out: String?): Reading = when {
        out == null -> Reading.UNKNOWN
        out.contains("lockedhint=no") -> Reading.UNLOCKED
        out.contains("lockedhint=yes") -> Reading.LOCKED
        else -> Reading.UNKNOWN
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
