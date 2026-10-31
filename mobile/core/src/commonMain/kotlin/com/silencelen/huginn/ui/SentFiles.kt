package com.silencelen.huginn.ui

import com.silencelen.huginn.data.SentFile

/**
 * The rules for a file Claude sent (the card's words, and what Open may do) -
 * pure, so both shells share them and a test pins them.
 */
object SentFiles {
    /**
     * The only types the app will OPEN - hand to the device's viewer - rather
     * than only download: documents, text, images, audio and video, the formats
     * whose viewers display and do not execute.
     *
     * ⚠ AN ALLOWLIST, NOT A DENYLIST (security scan, 2026-10-31, F1). The first
     * version refused a list of dangerous types and opened everything else, and
     * the list missed what Windows runs when opened (`.vbe`, `.wsh`, `.cpl`,
     * `.chm`, `.application`…) - a file a prompt-injected Claude could send and
     * the person could start with one click. Anything not named here, including
     * archives and macro-enabled Office files, is download-only: saved, never
     * started from the app.
     */
    private val OPENABLE = setOf(
        "pdf", "txt", "md", "markdown", "csv", "tsv", "log", "json", "yaml", "yml", "toml", "ini", "rtf",
        "docx", "xlsx", "pptx", "odt", "ods", "odp",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff",
        "mp3", "wav", "ogg", "oga", "opus", "flac", "m4a", "aac",
        "mp4", "m4v", "mov", "webm", "mkv",
    )

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Whether the card offers Open as well as Download. BOTH the original name and
     * the outbox id must carry an openable extension, so a harmless-looking name
     * on an executable copy is judged by the copy.
     */
    fun openable(file: SentFile): Boolean = extOf(file.name.ifBlank { file.id }) in OPENABLE && extOf(file.id) in OPENABLE

    /** The card's second line: the size, and the type when the name does not say it. */
    fun meta(file: SentFile): String {
        val size = file.size?.let { sizeWords(it) }
        val ext = extOf(file.name).uppercase().takeIf { it.isNotEmpty() && it.length <= 5 }
        return listOfNotNull(ext, size).joinToString(" · ")
    }

    /** 0 B, 1.2 KB, 3.4 MB - one decimal under ten, none above. */
    fun sizeWords(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        val shown = if (v < 10) ((v * 10).toLong() / 10.0).toString().removeSuffix(".0") else v.toLong().toString()
        return "$shown ${units[i]}"
    }

    /**
     * What the card says when fetching the file failed: a 404 is the outbox
     * having let it go (only images are kept past the retention window), anything
     * else is the moment, worth another tap.
     */
    fun failure(code: Int?, message: String?): String = when (code) {
        404, 410 -> "No longer on the server: sent files other than images are kept for a week"
        else -> "Could not fetch it${message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" } ?: ""}. Try again."
    }

    /**
     * The name a download is saved under: the original name with anything a file
     * system or a shell would choke on replaced, never empty, never a path.
     */
    fun saveName(file: SentFile): String {
        val base = file.name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ ()+,-]"), "_").trim().trimStart('.')
        return base.ifBlank { file.id }.take(120)
    }
}
