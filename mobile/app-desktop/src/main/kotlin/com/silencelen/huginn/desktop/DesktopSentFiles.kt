package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SentFile
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.ui.SentFileHost
import com.silencelen.huginn.ui.SentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

/**
 * The desktop's [SentFileHost]: Download into the user's Downloads folder under a
 * name that never overwrites (`report (2).pdf`), Open by writing a private temp
 * copy and handing it to the system's default app for the type.
 *
 * Open is refused here too for what [SentFiles.openable] calls download-only -
 * the card hides the button, and this is the second gate, because handing an
 * `.exe` or a `.desktop` file to the OS's opener RUNS it.
 */
class DesktopSentFiles(
    private val client: () -> HuginnClient,
    private val downloads: () -> File = ::downloadsDir,
    private val opener: (File) -> Boolean = ::systemOpen,
) : SentFileHost {

    override suspend fun fetch(id: String): ByteArray = client().outboxBytes(id)

    override suspend fun download(file: SentFile, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = downloads().apply { mkdirs() }
        val dest = freeName(dir, SentFiles.saveName(file))
        dest.writeBytes(bytes)
        markOfTheWeb(dest)
        AppLog.info("files", "saved a sent file to ${dest.path}")
        "Saved to ${dest.path}"
    }

    override suspend fun open(file: SentFile, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!SentFiles.openable(file)) return@withContext "This type is download-only"
        val dir = Files.createTempDirectory("huginn-sent-").toFile().apply { deleteOnExit() }
        val copy = File(dir, SentFiles.saveName(file)).apply { writeBytes(bytes); deleteOnExit() }
        markOfTheWeb(copy)
        if (opener(copy)) null else "Nothing on this computer opens it - Download keeps it"
    }

    companion object {
        /** `~/Downloads`, or the XDG download dir a Linux desktop names in user-dirs.dirs. */
        fun downloadsDir(): File {
            val home = File(System.getProperty("user.home"))
            val xdg = File(home, ".config/user-dirs.dirs")
            if (xdg.isFile) {
                val line = runCatching { xdg.readLines() }.getOrDefault(emptyList())
                    .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
                val v = line?.substringAfter('=')?.trim()?.trim('"')?.replace("\$HOME", home.path)
                if (!v.isNullOrBlank() && v.startsWith("/")) return File(v)
            }
            return File(home, "Downloads")
        }

        /**
         * Windows: tags [f] as downloaded from the internet (the `Zone.Identifier`
         * stream, ZoneId 3), so Office opens it in Protected View and SmartScreen
         * asks before anything runs - the protections a browser download gets.
         * A file Claude sent came from content someone else may have steered.
         * Best-effort and a no-op elsewhere (the security scan's F1, 2026-10-31).
         */
        internal fun markOfTheWeb(f: File, windows: Boolean = System.getProperty("os.name").lowercase().startsWith("windows")) {
            if (!windows) return
            runCatching { File(f.path + ":Zone.Identifier").writeText("[ZoneTransfer]\r\nZoneId=3\r\n") }
                .onFailure { AppLog.warn("files", "could not mark ${f.name} as downloaded: ${it.message}") }
        }

        /** [name] in [dir], or `name (2).ext`, `(3)`… - never an existing file. */
        internal fun freeName(dir: File, name: String): File {
            val first = File(dir, name)
            if (!first.exists()) return first
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val (stem, ext) = name.substring(0, dot) to name.substring(dot)
            var n = 2
            while (true) {
                val f = File(dir, "$stem ($n)$ext")
                if (!f.exists()) return f
                n++
            }
        }

        private fun systemOpen(f: File): Boolean = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f)
                true
            } else if (System.getProperty("os.name").lowercase().contains("linux")) {
                // A bare window manager has no AWT desktop integration; xdg-open is
                // what the session's own file associations answer to.
                ProcessBuilder("xdg-open", f.path).start()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
