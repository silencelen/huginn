package com.silencelen.huginn.ui

import com.silencelen.huginn.data.SentFile

/**
 * The rules for a file Claude sent (the card's words, and what Open may do) -
 * pure, so both shells share them and a test pins them.
 */
object SentFiles {
    /**
     * Never OPENED from the app, only downloaded: anything that runs or installs
     * when opened, and the types a browser would render as an active page. A file
     * Claude sent is a file someone could have steered Claude into sending; the
     * person can still save it and decide.
     */
    private val DOWNLOAD_ONLY = setOf(
        "apk", "aab", "xapk", "exe", "msi", "msix", "appx", "bat", "cmd", "com", "scr", "ps1", "psm1", "vbs", "js",
        "jse", "wsf", "hta", "lnk", "reg", "jar", "sh", "bash", "zsh", "run", "bin", "appimage", "deb", "rpm",
        "desktop", "command", "app", "dmg", "pkg", "html", "htm", "xhtml", "svg", "xml", "mht", "mhtml",
    )

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Whether the card offers Open (a viewer chosen by the device) as well as Download. */
    fun openable(file: SentFile): Boolean = extOf(file.name.ifBlank { file.id }) !in DOWNLOAD_ONLY &&
        extOf(file.id) !in DOWNLOAD_ONLY

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
