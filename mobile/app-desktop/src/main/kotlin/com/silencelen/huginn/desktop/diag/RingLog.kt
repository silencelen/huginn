package com.silencelen.huginn.desktop.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A small ring-buffer log, optionally mirrored to a file.
 *
 * This exists so the owner can answer "why did it do that" by pasting a blob
 * instead of someone SSH-ing into his laptop. Every field question about the
 * Electron client so far — did the updater run, why did the stream drop, did it
 * ever try to notify — took a terminal session on a machine nobody can reach.
 *
 * Deliberately NOT a general logger: three levels, no rotation beyond truncation,
 * no dependency, and a hard cap on lines. It is a debugging aid, not an audit
 * trail, and it is read by exactly one button.
 *
 * Secrets never reach it — call sites pass facts — and [scrub] is the second line
 * of defence for the ones that would arrive inside somebody else's error message.
 */
open class RingLog(
    private val file: File? = null,
    private val maxLines: Int = MAX_LINES,
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Kind { INFO, WARN, ERROR }

    data class Entry(val at: Long, val kind: Kind, val area: String, val message: String)

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()

    init {
        // Truncate rather than rotate. A 512 KB debugging aid that has grown to
        // 40 MB is a debugging aid nobody will ever paste.
        runCatching {
            val f = file ?: return@runCatching
            f.parentFile?.mkdirs()
            if (f.isFile && f.length() > MAX_FILE_BYTES) f.delete()
        }
    }

    fun info(area: String, message: String) = log(Kind.INFO, area, message)
    fun warn(area: String, message: String) = log(Kind.WARN, area, message)
    fun error(area: String, message: String) = log(Kind.ERROR, area, message)

    fun log(kind: Kind, area: String, message: String) {
        val entry = Entry(now(), kind, area, scrub(message))
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > maxLines) ring.removeFirst()
        }
        val f = file ?: return
        // A log that cannot write must never break the app — an unwritable home
        // directory is a bad afternoon, not a crash on launch.
        runCatching { f.appendText(format(entry) + "\n") }
    }

    /** The whole ring as text — what "Copy diagnostics" embeds. */
    fun text(): String = synchronized(lock) { ring.joinToString("\n") { format(it) } }

    fun entries(): List<Entry> = synchronized(lock) { ring.toList() }

    val path: String? get() = file?.absolutePath

    private fun format(e: Entry): String =
        "${stamp(e.at)} ${e.kind.name.padEnd(5)} ${e.area} ${e.message}"

    companion object {
        const val MAX_LINES: Int = 500
        const val MAX_FILE_BYTES: Long = 512L * 1024

        /**
         * Never let a credential reach the log or the diagnostics blob, whatever a
         * call site does.
         *
         * The daemon token is a long hex string and rides in an `Authorization`
         * header, so both shapes are covered; the URL form catches the one place a
         * credential can hide in something that looks like an address. This is the
         * SECOND line of defence — the first is that [Diagnostics.Input] has no
         * field the token could be put in.
         */
        fun scrub(s: String): String = s
            .replace(Regex("""(?i)Bearer\s+[\w.\-]+"""), "Bearer <redacted>")
            .replace(Regex("""(?i)\b[a-f0-9]{32,}\b"""), "<hex-redacted>")
            .replace(Regex("""(?i)\b(token|api[_-]?key|secret|password)\b(\s*[=:]\s*|%3D)\S+"""), "$1=<redacted>")
            .replace(Regex("""://[^/\s:@]+:[^/\s@]+@"""), "://<redacted>@")

        private val STAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        fun stamp(at: Long): String = STAMP.get().format(Date(at))
    }
}
