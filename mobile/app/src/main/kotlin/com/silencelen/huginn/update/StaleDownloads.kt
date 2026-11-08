package com.silencelen.huginn.update

import java.io.File

/**
 * What the self-updater of releases before 3.18.1 left in the app's cache:
 * `updates/`, holding the downloaded APK (or the `.part` of one) that waited for
 * an install this app no longer makes. Swept once at start. Nothing creates the
 * directory any more, so a phone that has rolled past it finds nothing.
 */
object StaleDownloads {
    /** The cache subdirectory the old updater downloaded into. */
    const val DIR: String = "updates"

    /**
     * Removes the old download directory under [cacheDir], whole.
     *
     * @return how many entries went (files and directories, the top one
     *   included); 0 when there was nothing, or nothing could be removed.
     */
    fun sweep(cacheDir: File): Int {
        val dir = File(cacheDir, DIR)
        if (!dir.exists()) return 0
        val entries = dir.walkBottomUp().count()
        return if (dir.deleteRecursively()) entries else 0
    }
}
