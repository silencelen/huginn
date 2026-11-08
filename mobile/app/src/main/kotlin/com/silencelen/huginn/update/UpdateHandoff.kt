package com.silencelen.huginn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.silencelen.huginn.ui.WebLinks

/**
 * Where a found update is taken. The app installs nothing itself (see
 * [PhoneUpdater]), so the one button a found release gets opens what does.
 *
 * [Store] is the private store app that put this app on the phone: the same
 * GitHub release reaches it on its own sync, and its list shows the update with
 * its own Update button. It declares no deep link to an app's page, so its
 * launch intent is as far as a hand-off can go. [ReleasePage] is for a phone
 * without it: the release's own page in a browser, where the APK and the notes
 * are.
 */
sealed interface UpdateHandoff {
    data object Store : UpdateHandoff
    data class ReleasePage(val url: String) : UpdateHandoff

    /** The button's words, on the card and on the Settings row. */
    val label: String
        get() = when (this) {
            Store -> "Open devstore"
            is ReleasePage -> "Open release page"
        }

    companion object {
        /**
         * The store app's package. Also named under `<queries>` in the manifest,
         * which [storeInstalled] depends on — `NoInstallerTest` holds it there.
         */
        const val STORE_PACKAGE: String = "com.silence.devstore"

        /** The store when it is on the phone, else the release page. */
        fun choose(storeInstalled: () -> Boolean, releasePageUrl: String): UpdateHandoff =
            if (storeInstalled()) Store else ReleasePage(releasePageUrl)

        /** The page of the release found under [tagName] in [repo]. */
        fun releasePageUrl(repo: String, tagName: String): String =
            "https://github.com/$repo/releases/tag/$tagName"

        /**
         * Whether the store app is on this phone. Android 11+ answers null here
         * for a package the manifest does not `<queries>` — the same silent null
         * that once removed the dictation mic — so the manifest names the package.
         */
        fun storeInstalled(context: Context): Boolean =
            context.packageManager.getLaunchIntentForPackage(STORE_PACKAGE) != null

        /**
         * Opens [handoff], and false when nothing on the phone could: the store
         * app gone since the check, or no browser. The release page goes through
         * [WebLinks] like every address this app hands out, http(s) only.
         */
        fun open(context: Context, handoff: UpdateHandoff): Boolean {
            val intent = when (handoff) {
                Store -> context.packageManager.getLaunchIntentForPackage(STORE_PACKAGE)
                is ReleasePage -> WebLinks.browsable(handoff.url)?.let {
                    Intent(Intent.ACTION_VIEW, Uri.parse(it).normalizeScheme())
                }
            } ?: return false
            return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
