package com.silencelen.huginn.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Settings "Software update" row and the chats-list card show.
 *
 * A check ends in [UpToDate], [Available] or [Error]. There is no step after
 * [Available]: the app installs nothing (see [PhoneUpdater]), so a found
 * release's one action is the hand-off in [UpdateHandoff].
 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val versionName: String) : AppUpdateState
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val notes: String,
        /** The found release's own page on GitHub: the hand-off for a phone without the store app. */
        val releasePageUrl: String,
    ) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

/**
 * Finds a newer release of the phone app in the pinned public GitHub repo —
 * and only finds it.
 *
 * Until 3.18.0 this class also downloaded the APK, verified its sha256 and
 * handed it to the system installer, under REQUEST_INSTALL_PACKAGES. Play
 * Protect flagged every new sideloaded build as possibly harmful, and that
 * permission was the strongest downloader-like signal the APK carried — for a
 * path the private store app already covers: it put this app on the phone and
 * installs each release from the same GitHub feed. So the app now reads the
 * release manifest, compares versions, and leaves the rest to the store, or to
 * the release page ([UpdateHandoff]). No APK, no FileProvider URI, no installer
 * intent, and `NoInstallerTest` holds the sources to that.
 *
 * The feed is injectable so the sequencing tests without Android; the real app
 * builds one with [forApp].
 */
class PhoneUpdater(
    private val installedVersionCode: Long,
    private val installedVersionName: String = "",
    private val feed: ReleaseFeed = GithubReleases(),
    private val tagPrefixes: List<String> = GithubReleases.MOBILE_TAG_PREFIXES,
    /** False in a build made with `-Phuginn.updateRepo=` (empty): no update checks at all. */
    private val enabled: Boolean = true,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    val installedVersion: String get() = installedVersionName
    val sourceRepo: String get() = feed.repo

    /**
     * Looks for a newer release: one listing of the feed and one small manifest
     * read, so the launch-time check costs no data worth mentioning. Leaves
     * [AppUpdateState.Available] or [AppUpdateState.UpToDate].
     */
    suspend fun check(): AppUpdateState {
        if (!enabled) return fail(DISABLED)
        _state.value = AppUpdateState.Checking
        val releases = runCatching { feed.list() }
            .getOrElse { return fail("could not reach GitHub releases: ${it.message}") }
        val release = GithubReleaseIndex.newest(releases, tagPrefixes)
            ?: return fail("no ${tagPrefixes.first()} release published yet")

        // The manifest is the version source: the tag is a name, the manifest is
        // what the build wrote — and it is what the store app reads too.
        val manifestAsset = release.asset(MANIFEST_NAME)
            ?: return fail("release ${release.tagName} has no $MANIFEST_NAME — cannot read its version")
        val text = runCatching { feed.getText(manifestAsset.browserDownloadUrl) }
            .getOrElse { return fail("could not fetch the release manifest: ${it.message}") }
        val manifest = AppManifestCodec.parseOrNull(text)
            ?: return fail("the release manifest was not readable")

        if (manifest.versionCode <= installedVersionCode) {
            return settle(AppUpdateState.UpToDate(installedVersionName.ifBlank { manifest.versionName }))
        }
        return settle(
            AppUpdateState.Available(
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                notes = manifest.notes,
                releasePageUrl = UpdateHandoff.releasePageUrl(feed.repo, release.tagName),
            ),
        )
    }

    private fun settle(s: AppUpdateState): AppUpdateState { _state.value = s; return s }
    private fun fail(message: String): AppUpdateState = settle(AppUpdateState.Error(message))

    companion object {
        const val MANIFEST_NAME: String = "latest.json"

        /** What the update row says in a build with update checks turned off. */
        const val DISABLED: String = "update checks are turned off in this build"

        /**
         * The real updater, wired to this install of the app, reading the release
         * repo the build was made with ([BuildConfig.UPDATE_REPO]; empty = off).
         */
        fun forApp(context: Context): PhoneUpdater {
            val app = context.applicationContext
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val repo = com.silencelen.huginn.BuildConfig.UPDATE_REPO
            return PhoneUpdater(
                installedVersionCode = PackageInfoCompat.getLongVersionCode(info),
                installedVersionName = info.versionName ?: "",
                feed = GithubReleases(repo = repo.ifEmpty { GithubReleases.REPO }),
                enabled = repo.isNotEmpty(),
            )
        }
    }
}
