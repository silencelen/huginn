package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectDashboard
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [DashboardShelf] in a directory: one `<name>.json` per project, written
 * whole beside the target and renamed over it, so a crash mid-write leaves the
 * last good rollup rather than half of a new one.
 *
 * ONE SOURCE, BOTH TARGETS (jvmShared, like the TLS transport): the desktop
 * points it at its settings directory, the phone at its cache directory, and
 * the format is [DashboardBook.encode]'s — the id travels INSIDE the file, so
 * the file name is only a handle. Anything that cannot be read is skipped; a
 * shelf is a convenience, and a convenience that can crash a launch is not one.
 */
class FileDashboardShelf(private val dir: File) : DashboardShelf {

    override fun load(): Map<String, ProjectDashboard> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyMap()
        val out = HashMap<String, ProjectDashboard>()
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val (id, dashboard) = DashboardBook.decode(text) ?: continue
            out[id] = dashboard
        }
        return out
    }

    override fun save(id: String, dashboard: ProjectDashboard) {
        dir.mkdirs()
        val target = fileFor(id)
        // `.tmp` does not end in the suffix, so a half-written file is never
        // loaded, and the rename is atomic where the filesystem allows.
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(DashboardBook.encode(id, dashboard))
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun remove(id: String) {
        fileFor(id).delete()
    }

    /**
     * The file for [id]. Project ids are slugs or uuids, which are file names
     * already; anything else is sanitised and gets a hash suffix so two ids
     * that sanitise alike still get two files. Never used as the id when
     * reading — see the class header.
     */
    internal fun fileFor(id: String): File {
        val safe = id.map { c ->
            if ((c.isLetterOrDigit() && c.code < 128) || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("")
        val name = if (safe == id && safe.isNotEmpty() && !safe.startsWith(".")) safe else "$safe-${id.hashCode().toUInt().toString(16)}"
        return File(dir, name + SUFFIX)
    }

    private companion object {
        const val SUFFIX = ".json"
    }
}
