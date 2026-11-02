package com.silencelen.huginn.update

/**
 * The one update check a launch makes.
 *
 * The phone used to look for an update only when Settings opened, so the owner
 * learned of a release by going to look (2026-11-01). Now the view model asks
 * once as it starts — and ONLY asks: [PhoneUpdater.check] finds a newer release
 * and stops. The download is ~50 MB, the phone may be on mobile data, and an
 * install is the system's own question; both stay the owner's taps, on the
 * notice card or in Settings > Updates. Nothing in this class can reach either.
 *
 * Once per instance: a second call (a screen that re-composes, a resume) is a
 * no-op, so a launch costs the GitHub API one request.
 */
class UpdateLaunchCheck {
    private var ran = false

    /**
     * Run [check] the first time only.
     *
     * @return true when this call made the check; false when a previous one did.
     */
    suspend fun runOnce(check: suspend () -> Unit): Boolean {
        if (ran) return false
        ran = true
        check()
        return true
    }
}
