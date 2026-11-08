package com.silencelen.huginn.update

/**
 * What the chats list's notice card says about an update, on the phone.
 *
 * The desktop's card installs a parked file. The phone's hands off: its button
 * opens the store app that installs the release, or the release page on a phone
 * without it ([UpdateHandoff]) — chosen when the card is built, so the button
 * says where the tap goes.
 */
data class PhoneUpdateNotice(
    val version: String,
    /** The release notes' first bullet ([UpdateReminder.firstLine]), or null. */
    val line: String?,
    val handoff: UpdateHandoff,
) {
    /** The button's words. */
    val buttonLabel: String get() = handoff.label
}

/**
 * The card's state from the updater's, gated by the shared [UpdateReminder]
 * rule: a found version nobody has put off this run ([later]) or silenced
 * ([silenced]). [storeInstalled] is asked only when there is a card to build —
 * it is a package lookup. The phone has no "never" switch, so [notifyUpdates]
 * stays true; the parameter is kept so the rule is read whole.
 *
 * Idle, Checking, UpToDate and Error say nothing: a check that failed is a
 * Settings matter, not a card.
 */
fun phoneUpdateNotice(
    state: AppUpdateState,
    later: String?,
    silenced: String?,
    storeInstalled: () -> Boolean,
    notifyUpdates: Boolean = true,
): PhoneUpdateNotice? {
    val found = state as? AppUpdateState.Available ?: return null
    if (!UpdateReminder.show(found.versionName, later, silenced, notifyUpdates)) return null
    return PhoneUpdateNotice(
        version = found.versionName,
        line = UpdateReminder.firstLine(found.notes),
        handoff = UpdateHandoff.choose(storeInstalled, found.releasePageUrl),
    )
}
