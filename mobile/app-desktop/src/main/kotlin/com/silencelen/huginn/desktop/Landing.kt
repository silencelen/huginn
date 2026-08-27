package com.silencelen.huginn.desktop

/**
 * WHERE THE WINDOW OPENS.
 *
 * The client used to open on Chats, always, because that is the first enum
 * constant. The owner's report was "the sessions tab is not being regarded as
 * default" — and the honest reading of that is not "make Sessions the constant",
 * it is "stop throwing away where I was". This is an always-on client that hides
 * to the tray; a relaunch is a rare event and almost always follows an update or
 * a crash, which is exactly when landing somewhere other than where you were
 * costs the most.
 *
 * So: REOPEN WHERE HE LEFT IT, with two deliberate limits.
 *
 *  1. **Only Chats and Sessions are remembered.** Status and Settings are errands
 *     — you go there to answer a question and leave. Reopening into Settings
 *     would be reopening into an errand already finished, and it would also mean
 *     one glance at the disk usage decided tomorrow's landing view. When the
 *     current view is one of those, the last real position stays stored.
 *  2. **[DEFAULT] is Sessions**, which is the first-run answer and the answer for
 *     every install that predates this field — so the owner gets what he asked
 *     for on the very next launch, without needing to visit Sessions first.
 *
 * The open TARGET is remembered too, but restoring it is conditional: see
 * `AppStore.restoreLanding`. A chat that was deleted from the phone, or a session
 * that ended overnight, must not reopen into a pane addressing something that is
 * not there.
 */
object Landing {

    /** First run, and any install whose settings file predates the field. */
    val DEFAULT: View = View.SESSIONS

    /** True for the views worth reopening into. */
    fun persistable(view: View): Boolean = view == View.CHATS || view == View.SESSIONS

    fun encode(view: View): String = when (view) {
        View.CHATS -> "chats"
        View.SESSIONS -> "sessions"
        // Encodable but NOT persistable, deliberately. Rounds is a place you visit
        // to read a report, not a place to be returned to on every launch — and a
        // window that opens on a schedule list answers a question nobody asked
        // first thing. Kept here so `parse` and `encode` stay mutual inverses.
        View.ROUNDS -> "rounds"
        View.DEVICES -> "devices"
        // Same reasoning as Rounds: encodable so the two halves stay mutual
        // inverses, not persistable because a window that opens on somebody's
        // notes answers a question nobody asked first thing.
        View.SCRATCHPADS -> "pages"
        // Never written — the caller filters on [persistable] — but an encoder that
        // silently produced "chats" for Settings would be a landing bug nobody
        // could read off the file.
        View.STATUS -> "status"
        View.SETTINGS -> "settings"
    }

    /**
     * What a stored string means. Anything unrecognised — an empty field, a value
     * written by a newer build, a hand-edited file — is [DEFAULT] rather than a
     * throw: this runs before the window exists, and refusing to launch over a
     * navigation preference is not a trade anyone would make.
     */
    fun parse(raw: String?): View = when (raw?.trim()?.lowercase()) {
        "chats" -> View.CHATS
        "sessions" -> View.SESSIONS
        "rounds" -> View.ROUNDS
        "devices" -> View.DEVICES
        "pages" -> View.SCRATCHPADS
        else -> DEFAULT
    }
}
