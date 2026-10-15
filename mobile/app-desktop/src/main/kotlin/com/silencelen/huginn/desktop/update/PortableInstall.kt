package com.silencelen.huginn.desktop.update

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The portable self-update: unpack the verified tarball BESIDE the running
 * version, swap one symlink, relaunch. The layout is the CLI's
 * (`huginn desktop install`, client/huginn.sh) and the two must agree exactly:
 *
 * ```
 * R/.huginn-portable          marker: "huginn laid this tree out"
 * R/versions/<ver>/           one extracted app-image per version
 * R/current -> versions/<ver> RELATIVE link; the only thing an update changes
 * ```
 *
 * ⚠ ANY FAILURE LEAVES `current` UNTOUCHED. Every step before the swap works in
 * `versions/.tmp-<ver>`, which nothing launches; the swap itself is one rename(2)
 * of a symlink over a symlink, which the kernel does atomically — a reader sees
 * the old version or the new one, never neither. So an update that dies halfway
 * (a full disk, a refused archive, a missing launcher) costs a temp dir and an
 * error line, and the app on the menu still starts.
 *
 * ⚠ THE ARCHIVE IS LISTED BEFORE IT IS UNPACKED, by our own parser, not by
 * trusting tar. The sha256 says the bytes are the release's; this says the
 * release cannot write outside its own directory even if the build that made it
 * was wrong. Then the extracted tree is walked again (see [verifyTree]) — two
 * readers of one archive can disagree, and the second check is about what
 * actually landed on disk.
 */
object PortableInstall {

    private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

    /** The single directory every entry of `huginn-desktop-kt-<ver>-linux-x64.tar.gz` lives under. */
    fun topDirFor(version: String): String = "huginn-desktop-kt-$version"

    // ---------------------------------------------------------------- the listing

    /**
     * Lists a `.tar.gz` and returns its single top-level directory, or a failure
     * naming the first entry that would escape it.
     *
     * Refused: an absolute name, any `..` component, a symlink whose target is
     * absolute or resolves outside the top dir, a hardlink to anything outside it,
     * device/fifo/sparse entries, a second top-level name, a top that is not a
     * directory, a corrupt header (checksum), and an archive with no entries.
     * Understands ustar, GNU long names (`L`/`K`) and pax `path`/`linkpath` —
     * the forms GNU tar and bsdtar actually write — so a long path cannot slip a
     * different name past this reader than the one tar will use.
     */
    fun checkTar(tarGz: File): Result<String> = runCatching {
        GZIPInputStream(tarGz.inputStream().buffered(), 64 * 1024).use { checkEntries(readEntries(it)) }
    }

    internal data class Entry(val name: String, val type: Char, val link: String)

    internal fun checkEntries(entries: List<Entry>): String {
        if (entries.isEmpty()) throw UnsafeArchive("the archive is empty")
        var top: String? = null
        for (e in entries) {
            if (e.name.startsWith("/")) throw UnsafeArchive("absolute path: ${e.name}")
            val parts = components(e.name)
                ?: throw UnsafeArchive("path climbs out with '..': ${e.name}")
            if (parts.isEmpty()) throw UnsafeArchive("an entry names the archive root itself: '${e.name}'")
            val first = parts[0]
            if (top == null) top = first
            if (first != top) throw UnsafeArchive("more than one top-level entry: '$top' and '$first'")
            if (parts.size == 1 && e.type != '5') throw UnsafeArchive("the top-level entry '$first' is not a directory")
            when (e.type) {
                '0', '\u0000', '7', '5' -> Unit
                '2' -> {
                    if (e.link.isEmpty() || e.link.startsWith("/")) throw UnsafeArchive("symlink ${e.name} -> ${e.link} is absolute or empty")
                    // Resolved from the LINK'S OWN directory, the way the kernel will.
                    val resolved = resolveLink(parts.dropLast(1), e.link)
                    if (resolved == null || resolved.isEmpty() || resolved[0] != top) {
                        throw UnsafeArchive("symlink ${e.name} -> ${e.link} escapes $top/")
                    }
                }
                '1' -> {
                    // A hardlink target is an ARCHIVE path, not a relative one.
                    if (e.link.startsWith("/")) throw UnsafeArchive("hardlink ${e.name} -> ${e.link} is absolute")
                    val target = components(e.link)
                    if (target == null || target.isEmpty() || target[0] != top) {
                        throw UnsafeArchive("hardlink ${e.name} -> ${e.link} escapes $top/")
                    }
                }
                else -> throw UnsafeArchive("entry ${e.name} has unsupported type '${e.type}'")
            }
        }
        return top!!
    }

    /**
     * An entry NAME's components with `.` and empty segments dropped, or null if
     * any segment is `..`. Refused outright even when it would stay inside
     * (`a/../a/x`): GNU tar drops such members with a warning, so what this
     * approved and what tar writes would stop being the same set.
     */
    internal fun components(path: String): List<String>? {
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
        return if (segs.any { it == ".." }) null else segs
    }

    /**
     * A symlink TARGET resolved from its directory, `..` applied the way the
     * kernel will; null the moment it climbs above the archive root. Unlike a
     * name, a target may legitimately say `../lib/x`.
     */
    internal fun resolveLink(dir: List<String>, target: String): List<String>? {
        val out = ArrayList(dir)
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> { if (out.isEmpty()) return null; out.removeAt(out.size - 1) }
                else -> out += seg
            }
        }
        return out
    }

    internal fun readEntries(input: InputStream): List<Entry> {
        val out = ArrayList<Entry>()
        val block = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (!readBlock(input, block)) throw UnsafeArchive("truncated archive (no end-of-archive marker)")
            if (block.all { it == 0.toByte() }) break
            verifyChecksum(block)
            val type = block[156].toInt().toChar()
            val size = parseSize(block)
            val ustar = String(block, 257, 5, Charsets.US_ASCII) == "ustar"
            val prefix = if (ustar) cstr(block, 345, 155) else ""
            val rawName = cstr(block, 0, 100).let { if (prefix.isNotEmpty()) "$prefix/$it" else it }
            val rawLink = cstr(block, 157, 100)
            when (type) {
                'L' -> { longName = readData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = readData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> {
                    val pax = parsePax(readData(input, size))
                    pax["path"]?.let { paxPath = it }
                    pax["linkpath"]?.let { paxLink = it }
                    continue
                }
                'g' -> {
                    val pax = parsePax(readData(input, size))
                    if (pax.containsKey("path") || pax.containsKey("linkpath")) {
                        throw UnsafeArchive("a global pax header renames entries")
                    }
                    continue
                }
            }
            val name = paxPath ?: longName ?: rawName
            val link = paxLink ?: longLink ?: rawLink
            longName = null; longLink = null; paxPath = null; paxLink = null
            out += Entry(name, type, link)
            // Only regular files carry data; a hardlink/symlink/dir header's size
            // field is advisory and tar skips no data for it.
            if (type == '0' || type == '\u0000' || type == '7') skip(input, size)
        }
        return out
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > 1_048_576) throw UnsafeArchive("an extended header of $size bytes")
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) throw EOFException("truncated extended header")
            off += n
        }
        skipPadding(input, size)
        return data
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated entry")
            left -= n
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0 && !readBlock(input, ByteArray(pad))) throw EOFException("truncated padding")
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseSize(b: ByteArray): Long {
        // GNU base-256 for files over 8 GiB: high bit of the first byte set.
        if (b[124].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = cstr(b, 124, 12).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun verifyChecksum(b: ByteArray) {
        val stored = cstr(b, 148, 8).trim().trimEnd('\u0000').trim()
        val want = stored.toLongOrNull(8) ?: throw UnsafeArchive("unreadable header checksum")
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32 else (b[i].toLong() and 0xff)
        if (sum != want) throw UnsafeArchive("corrupt tar header (checksum)")
    }

    /** `"<len> key=value\n"` records. */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > data.size) break
            val rec = String(data, sp + 1, i + len - sp - 1, Charsets.UTF_8).removeSuffix("\n")
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }

    class UnsafeArchive(message: String) : Exception(message)

    // ------------------------------------------------------------------ the apply

    /**
     * Unpacks [tarball] (already sha256-verified by the caller) as version
     * [version] under [root], swaps `current` to it and prunes. Returns the new
     * STABLE launcher (`R/current/bin/huginn-desktop-kt`).
     *
     * @param running the version dir this process was started from; never pruned,
     *   whatever else happens, because deleting a JVM's own jars under it is how a
     *   tray click becomes a NoClassDefFoundError.
     */
    fun apply(
        root: File,
        tarball: File,
        version: String,
        running: File? = null,
        tar: String = "tar",
    ): Result<File> = runCatching {
        if (!VERSION.matches(version)) throw IllegalArgumentException("refusing version '$version' (not x.y.z)")
        if (!File(root, LinuxInstall.MARKER).isFile) throw IllegalStateException("$root is not a portable install (no ${LinuxInstall.MARKER})")
        val top = checkTar(tarball).getOrElse { throw IllegalStateException("refused the archive: ${it.message}") }
        if (top != topDirFor(version)) throw IllegalStateException("the archive holds '$top/', expected '${topDirFor(version)}/'")

        val versions = File(root, "versions").apply { mkdirs() }
        val tmp = File(versions, ".tmp-$version")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IllegalStateException("could not create $tmp")
        try {
            val p = ProcessBuilder(tar, "-xzf", tarball.absolutePath, "-C", tmp.absolutePath, "--no-same-owner")
                .redirectErrorStream(true)
                .start()
            p.outputStream.close()
            val said = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw IllegalStateException("tar timed out") }
            if (p.exitValue() != 0) throw IllegalStateException("tar failed (${p.exitValue()}): ${said.take(300)}")

            val extracted = File(tmp, top)
            verifyTree(extracted.toPath())
            val launcher = File(extracted, LinuxInstall.LAUNCHER_REL)
            if (!launcher.isFile || !launcher.canExecute()) throw IllegalStateException("the archive has no executable ${LinuxInstall.LAUNCHER_REL}")

            val dest = File(versions, version)
            val currentLink = File(root, "current").toPath()
            val before = currentTarget(root)
            if (dest.exists()) {
                // A leftover from an attempt that died between the rename and the
                // swap. Never the live one: that is refused rather than replaced.
                if (before?.canonicalFile == dest.canonicalFile || running?.canonicalFile == dest.canonicalFile) {
                    throw IllegalStateException("$version is already the installed version")
                }
                dest.deleteRecursively()
            }
            Files.move(extracted.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)

            swapCurrent(root, version)
            prune(versions, keep = setOfNotNull(dest, before, running))
            File(currentLink.toFile(), LinuxInstall.LAUNCHER_REL)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** What `R/current` points at, as a File, or null when it does not exist. */
    fun currentTarget(root: File): File? {
        val link = File(root, "current").toPath()
        if (!Files.isSymbolicLink(link)) return null
        val t = Files.readSymbolicLink(link)
        return (if (t.isAbsolute) t else link.parent.resolve(t)).normalize().toFile()
    }

    /**
     * The swap: a fresh RELATIVE link beside `current`, renamed over it. Relative,
     * so a home directory that moves (or is reached through a different mount)
     * keeps working — and it is what the CLI writes, so either tool can update a
     * tree the other made.
     */
    fun swapCurrent(root: File, version: String) {
        val link = File(root, "current").toPath()
        val tmpLink = File(root, ".current.tmp-${ProcessHandle.current().pid()}").toPath()
        Files.deleteIfExists(tmpLink)
        Files.createSymbolicLink(tmpLink, Paths.get("versions", version))
        try {
            Files.move(tmpLink, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            Files.deleteIfExists(tmpLink)
            throw t
        }
    }

    /**
     * Everything under `versions/` that is not in [keep] goes, including stale
     * `.tmp-*` dirs from an interrupted update. Two versions stay: the new one and
     * the one we are running now, which becomes "previous" — the rollback target,
     * and the jars this JVM still has open until it exits.
     */
    fun prune(versions: File, keep: Set<File>) {
        val keepCanon = keep.map { it.canonicalFile }.toSet()
        versions.listFiles()?.forEach { f ->
            if (f.canonicalFile !in keepCanon) f.deleteRecursively()
        }
    }

    /** Every symlink in the extracted tree must still point inside it. */
    internal fun verifyTree(top: Path) {
        if (!Files.isDirectory(top, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("the archive did not unpack a directory")
        val base = top.toAbsolutePath().normalize()
        Files.walk(base).use { s ->
            s.forEach { p ->
                if (Files.isSymbolicLink(p)) {
                    val t = Files.readSymbolicLink(p)
                    val resolved = (if (t.isAbsolute) t else p.parent.resolve(t)).normalize()
                    if (!resolved.startsWith(base)) throw IllegalStateException("unpacked symlink $p escapes the app dir")
                }
            }
        }
    }

    // ---------------------------------------------------------------- the relaunch

    /**
     * The shell that waits for THIS process to be gone, then starts [launcher].
     *
     * ⚠ WAITING ON THE PID IS THE SINGLE-INSTANCE ANSWER. `SingleInstance` hands a
     * second launch's argv to whoever holds the loopback slot and the second
     * launch then exits — so a relaunch that starts while this instance is still
     * shutting down would be "forwarded" to the dying process and vanish. A fixed
     * sleep only guesses how long a quit takes; `kill -0` knows. Capped at 30 s so
     * a process that hangs on its way out still gets its successor (the new one
     * then wins the slot from a dead record, which `claimOrForward` handles).
     */
    internal const val RELAUNCH_SCRIPT: String =
        "i=0; while kill -0 \"\$1\" 2>/dev/null && [ \$i -lt 300 ]; do sleep 0.1; i=\$((i+1)); done; " +
            "sleep 0.3; exec \"\$2\""

    /**
     * Starts the waiter detached (`setsid`: its own session, so it is not killed
     * with this process's group or terminal) and returns whether it started. The
     * caller quits next; the waiter outlives us by design.
     */
    fun relaunchAfterExit(launcher: File, pid: Long = ProcessHandle.current().pid()): Boolean {
        fun start(vararg cmd: String) = ProcessBuilder(*cmd)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val args = arrayOf("sh", "-c", RELAUNCH_SCRIPT, "huginn-relaunch", pid.toString(), launcher.path)
        return runCatching { start("setsid", *args); true }
            // No setsid (a very minimal container): a plain child still survives
            // the JVM's exit on Linux; it merely shares our session.
            .recoverCatching { start(*args); true }
            .getOrDefault(false)
    }
}
