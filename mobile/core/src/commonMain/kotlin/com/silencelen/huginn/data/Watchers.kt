package com.silencelen.huginn.data

/**
 * Which surfaces still need a shared poll running.
 *
 * ⚠ THE BUG THIS EXISTS FOR. A poll started and stopped by each surface that
 * wants it works right up until two of them are on screen at once — which is what
 * a tablet, an unfolded phone or a two-pane desktop IS. Closing the pages editor
 * beside the pages LIST ran the editor's `onStopOrDispose`, which stopped the poll
 * the list was still reading from: the list then sat frozen, showing sizes and
 * "edited 4 minutes ago" that never moved again, with nothing on screen looking
 * wrong.
 *
 * A SET rather than a counter, because the callers are lifecycle effects and
 * lifecycle effects re-run: `LifecycleStartEffect` fires again on every return to
 * the foreground, and a counter would drift upward one leak at a time until the
 * poll could never stop. Entering twice under the same name is entering once.
 */
class Watchers {

    private val watching = mutableSetOf<String>()

    /** Whether anything is watching right now. */
    val any: Boolean get() = watching.isNotEmpty()

    /** @return true when this is the FIRST watcher — the caller should start. */
    fun enter(surface: String): Boolean {
        val had = watching.isNotEmpty()
        watching += surface
        return !had
    }

    /**
     * @return true when this was the LAST watcher — the caller should stop. False
     *   for a surface that was not watching, so a stray release cannot take a poll
     *   down under a surface that is still reading it.
     */
    fun leave(surface: String): Boolean {
        if (!watching.remove(surface)) return false
        return watching.isEmpty()
    }

    /** Everything goes: the whole feature is being torn down. */
    fun clear() {
        watching.clear()
    }
}
