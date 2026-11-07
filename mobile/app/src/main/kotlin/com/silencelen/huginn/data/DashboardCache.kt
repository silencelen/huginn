package com.silencelen.huginn.data

import com.silencelen.huginn.ui.DashboardBook
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The last dashboard each project answered with, ON DISK — one small JSON file
 * per project under the app's files directory.
 *
 * ⚠ THE PROJECT PAGE MUST NEVER SAY "Nothing to show yet." OVER A CLUSTER IT
 * HAS DRAWN BEFORE (owner, 2026-11-07). [DashboardBook] already keeps what each
 * project last said for the life of the process, so a walk back into a project
 * draws at once; but the book dies with the process, and the phone's process
 * dies all the time — Android reclaims it in the background, the app lock
 * restarts the activity, a notification tap lands on a project page in a
 * process that has fetched nothing yet. That first answer waits on the store
 * loading, the pinned handshake and the daemon's one-at-a-time queue behind
 * the cold start's own fan-out, and the page read "Nothing to show yet." for
 * the whole of it. This is the book's memory across those deaths: what the
 * project last said is drawn at once, with its own "as of <time>", and the
 * poll replaces it when it lands.
 *
 * Plain files rather than DataStore: a dashboard is a few kilobytes of the
 * daemon's own JSON and is written on every poll that moved, and the
 * preferences store is a single serialised blob rewritten whole on each edit.
 * Written to a sibling and renamed, so a process killed mid-write leaves the
 * last whole file rather than half of one; a file that will not parse is read
 * as nothing, never as a crash.
 *
 * Synchronous on purpose, like every file store in this app: the caller picks
 * the dispatcher (the view model reads and writes it on IO).
 */
class DashboardCache(
    private val dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true },
) {

    /** What [id] last answered with, or null when nothing was kept (or it will not parse). */
    fun read(id: String): ProjectDashboard? {
        val f = fileFor(id)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<ProjectDashboard>(f.readText()) }.getOrNull()
    }

    /** Keep [dashboard] as what [id] last said. */
    fun write(id: String, dashboard: ProjectDashboard) {
        runCatching {
            dir.mkdirs()
            val f = fileFor(id)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(ProjectDashboard.serializer(), dashboard))
            if (!tmp.renameTo(f)) {
                // A rename over an existing file can refuse on some filesystems;
                // the old copy is still whole, so replace it in two steps.
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /** A project that is gone takes its last dashboard with it — the book's [DashboardBook.forget]. */
    fun forget(id: String) {
        runCatching { fileFor(id).delete() }
    }

    /** Drop the dashboards of projects that are gone, keeping those in [alive] — the book's [DashboardBook.retain]. */
    fun retain(alive: Collection<String>) {
        val keep = alive.mapTo(HashSet()) { fileName(it) }
        runCatching {
            dir.listFiles()?.forEach { f -> if (f.name.endsWith(SUFFIX) && f.name !in keep) f.delete() }
        }
    }

    /**
     * What the project page draws FIRST, before its poll has answered: what the
     * book holds for [id], else what this cache kept, put into the book so the
     * poll's answer replaces it by the book's one rule ([DashboardBook.adopt]).
     * Null only when the project has never answered in this process OR any
     * earlier one — the one case "Nothing to show yet." is true.
     *
     * The disk is read only when the book has nothing: within a process the
     * book is the fresher of the two, and a reopen must not fall back to an
     * older file.
     */
    fun seed(book: DashboardBook, id: String): ProjectDashboard? {
        book.held(id)?.let { return it }
        val cached = read(id) ?: return null
        book.adopt(id, cached)
        return book.held(id)
    }

    private fun fileFor(id: String): File = File(dir, fileName(id))

    companion object {
        const val SUFFIX: String = ".json"

        /**
         * The file a project's dashboard is kept in. A project id is a uuid, but
         * the name is scrubbed to a safe alphabet anyway: an id this app did not
         * mint must not be able to name a path.
         */
        fun fileName(id: String): String =
            id.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
                .joinToString("")
                .ifEmpty { "_" } + SUFFIX
    }
}
