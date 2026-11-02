package com.silencelen.huginn.update


/**
 * What the chats list's notice card says about an update, on the phone.
 *
 * The desktop's updater parks a verified file, so its card has one step. The
 * phone's has three explicit steps (find, download, install — see
 * [PhoneUpdater]), so the card follows the state through them: [Action.Download]
 * names the size because that tap spends data, [Action.Downloading] holds the
 * card still rather than letting it vanish and come back, and [Action.Install]
 * hands the verified APK to the system installer.
 */
data class PhoneUpdateNotice(
    val version: String,
    /** The release notes' first bullet ([UpdateReminder.firstLine]), or null. */
    val line: String?,
    val action: Action,
) {
    sealed interface Action {
        /** Found, not fetched: the card's button downloads it. [sizeBytes] 0 when unknown. */
        data class Download(val sizeBytes: Long) : Action
        /** Being fetched: the button is inert and says how far. */
        data class Downloading(val fraction: Float?) : Action
        /** Verified and on disk: the button installs. */
        data object Install : Action
    }

    /** The Install button's words for this step. */
    val buttonLabel: String get() = when (action) {
        is Action.Download -> if (action.sizeBytes > 0) "Download (${action.sizeBytes / 1_000_000} MB)" else "Download"
        is Action.Downloading -> action.fraction?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading…"
        Action.Install -> "Install and restart"
    }

    val buttonEnabled: Boolean get() = action !is Action.Downloading
}

/**
 * The card's state from the updater's, gated by the shared [UpdateReminder]
 * rule: a found or fetched version nobody has put off this run ([later]) or
 * silenced ([silenced]). The phone has no "never" switch, so [notifyUpdates]
 * stays true; the parameter is kept so the rule is read whole.
 *
 * Idle, Checking, UpToDate and Error say nothing: a check that failed is a
 * Settings matter, not a card.
 */
fun phoneUpdateNotice(
    state: AppUpdateState,
    later: String?,
    silenced: String?,
    notifyUpdates: Boolean = true,
): PhoneUpdateNotice? {
    val notice = when (state) {
        is AppUpdateState.Available -> PhoneUpdateNotice(
            state.versionName, UpdateReminder.firstLine(state.notes), PhoneUpdateNotice.Action.Download(state.size),
        )
        is AppUpdateState.Downloading -> PhoneUpdateNotice(
            state.versionName, null, PhoneUpdateNotice.Action.Downloading(state.fraction),
        )
        is AppUpdateState.Ready -> PhoneUpdateNotice(state.versionName, null, PhoneUpdateNotice.Action.Install)
        AppUpdateState.Idle, AppUpdateState.Checking, is AppUpdateState.UpToDate, is AppUpdateState.Error -> return null
    }
    return notice.takeIf { UpdateReminder.show(it.version, later, silenced, notifyUpdates) }
}
