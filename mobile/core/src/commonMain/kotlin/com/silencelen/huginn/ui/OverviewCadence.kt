package com.silencelen.huginn.ui

/**
 * When a session's Overview is fetched — one rule for both clients.
 *
 * ⚠ THE OWNER (2026-10-01): the Overview tab "seems to fully fetch and rerender
 * from scratch everything but the user input boxes every time. lets ensure this is
 * a task of starting the session, and updated periodically in the background, with
 * a refresh upon navigation."
 *
 * Until then each client polled ONLY while the tab was on screen, and the phone
 * also blanked what it had and asked for the whole map again with no cursor on
 * every visit — so every return to the tab was an empty pane, a full
 * whole-transcript walk on the host, and a list built from nothing.
 *
 * Now:
 *  - the loop starts when the SESSION opens, so the first visit to the tab finds
 *    the map already there;
 *  - behind the other tabs it keeps the map current at [BACKGROUND_MS] — cheap,
 *    because the cursor makes an unchanged session two numbers on the wire;
 *  - arriving on the tab refreshes at once (the header too) and then polls at
 *    [FOREGROUND_MS];
 *  - nothing already fetched for this session is thrown away to do any of that.
 *
 * Neither client polls while it is not visible — that gate stays each shell's own
 * (a lifecycle on the phone, window presence on the desktop).
 */
object OverviewCadence {
    const val FOREGROUND_MS: Long = 5_000
    const val BACKGROUND_MS: Long = 30_000

    /** How long to wait between graph polls. */
    fun intervalMs(onTab: Boolean): Long = if (onTab) FOREGROUND_MS else BACKGROUND_MS

    /**
     * Whether this pass should also fetch the header (`/overview`, which carries
     * meta and the totals the map does not): on the first pass for a session, and
     * on every arrival at the tab. Behind the tab the cursor poll is enough.
     */
    fun fetchHeader(onTab: Boolean, haveHeader: Boolean): Boolean = onTab || !haveHeader

    /** Whether a cached answer may be kept when the loop (re)starts for [name]. */
    fun keepCache(cachedFor: String?, name: String): Boolean = cachedFor == name
}
