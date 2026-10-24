package com.silencelen.huginn.update

/**
 * When a verified update is announced outside Settings — the card at the top of
 * the lists, the dot on the rail, the row in the tray menu — and when it is not.
 *
 * The updater's end state is [UpdateState.Ready] parked in Settings, where the
 * owner found it only by going to look (the desktop had no other surface). This
 * is the rule for the surfaces that go to them instead. Three answers can put
 * the notice away and they mean three different things, so they are three
 * inputs rather than one flag:
 *
 *  - **Later** ([dismissedUntilLaunch]): "not now". Kept in memory by the app, so
 *    a relaunch — which is also what installing needs — brings the card back. A
 *    newer version arriving meanwhile shows at once: it is a different question.
 *  - **Don't remind me** ([silencedVersion]): "not this version". Persisted, and
 *    exact: the next release is a new decision, so it shows again.
 *  - **The switch** ([notifyUpdates], Settings > Updates): "never, here". Hides
 *    every announcing surface; the Updates page keeps saying what is parked.
 *
 * Pure and in `:core` so the phone can read the same rule the day it grows a
 * notice of its own. Nothing here knows what a card is.
 */
object UpdateReminder {

    /**
     * Whether the CARD shows: a verified [readyVersion] that nobody has put off
     * this run, silenced, or switched off.
     */
    fun show(
        readyVersion: String?,
        dismissedUntilLaunch: String?,
        silencedVersion: String?,
        notifyUpdates: Boolean,
    ): Boolean {
        val v = readyVersion?.takeIf { it.isNotBlank() } ?: return false
        if (!notifyUpdates) return false
        if (silencedVersion == v) return false
        if (dismissedUntilLaunch == v) return false
        return true
    }

    /**
     * Whether the QUIET marks show — the rail dot and the tray row. Later does
     * not take them down: "not now" leaves a reminder that costs nothing, and the
     * rail is where somebody who put it off goes to find it again. The switch and
     * Don't remind me do.
     */
    fun mark(readyVersion: String?, silencedVersion: String?, notifyUpdates: Boolean): Boolean =
        show(readyVersion, dismissedUntilLaunch = null, silencedVersion = silencedVersion, notifyUpdates = notifyUpdates)

    /** The card's title. */
    fun title(version: String): String = "Huginn $version is ready"

    /**
     * One line from the release notes: the first bullet, else the first line that
     * is not a heading, cut to [max] with an ellipsis. Null when the notes hold
     * nothing worth a line (empty, or headings alone).
     *
     * The notes are the changelog section the release script copies into the
     * manifest, so they begin with a "Carries core x.y.z." line and `###
     * Added`-style headings before the first bullet; a card that quoted the
     * heading would say "Added" and nothing else.
     */
    fun firstLine(notes: String, max: Int = 140): String? {
        val lines = notes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val line = lines.firstOrNull { isBullet(it) } ?: lines.firstOrNull() ?: return null
        val text = line.trimStart('-', '*', '•', ' ', '\t').trim()
        if (text.isEmpty()) return null
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
    }

    private fun isBullet(line: String): Boolean =
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")

    /**
     * The command inside a manual-install hint ("… install it with: sudo apt
     * install /path (or switch …)"), for a Copy command button; null when the hint
     * names no command, in which case the card shows the hint alone.
     */
    fun commandOf(hint: String): String? {
        val after = hint.substringAfter(COMMAND_LEAD, missingDelimiterValue = "")
        if (after.isEmpty()) return null
        return after.substringBefore(" (").trim().takeIf { it.isNotEmpty() }
    }

    /** The phrase `LinuxInstall.manualHint` puts before the command. */
    private const val COMMAND_LEAD = "install it with: "
}
