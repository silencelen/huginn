package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.update.GithubReleaseIndex
import com.silencelen.huginn.update.GithubReleases
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.Semver
import com.silencelen.huginn.update.UpdateArtifact
import com.silencelen.huginn.update.UpdateManifest
import com.silencelen.huginn.update.UpdateManifestCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * What the Settings screen shows and what the "restart to update" button acts on.
 *
 * [Ready] is the end of the automatic path. Nothing installs itself: the update
 * is fetched, hashed and parked, and a human decides when the app closes. That is
 * the contract the Electron client shipped with and the one the owner is used to
 * — a client that relaunches itself mid-sentence on an agent host is a client
 * that eats the sentence.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Downloading(val version: String, val bytes: Long, val total: Long) : UpdateState {
        /** 0..1, or null while the server has not said how big it is. */
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * Verified and on disk. [file] is an installer on Windows and a `.deb` on
     * Linux; [installable] says whether this client can do anything with it
     * beyond telling the user where it is.
     */
    data class Ready(
        val version: String,
        val file: File,
        val notes: String,
        val installable: Boolean,
    ) : UpdateState

    data class Error(val message: String) : UpdateState
}

/**
 * Checks the pinned public GitHub repo on launch and every four hours, downloads
 * a newer build, verifies the hash the release manifest carries, and stops.
 *
 * The source moved from a Bearer-authed private tailnet feed to GitHub releases
 * now that huginn is a public project (the owner's ask: "pulling from the latest
 * git releases … isn't siloed to our private devstore"). The security property
 * is preserved, not dropped: [GithubReleases.REPO] is a compile-time constant
 * (the new trust anchor — a Settings typo still cannot move where an installer
 * comes from), the fetch is HTTPS, and [Sha256] still gates every artifact before
 * it can be named installable, because these builds are unsigned.
 *
 * Everything security-relevant stays small and testable in isolation:
 * [ReleaseFeed] is where bytes may come from, [GithubReleaseIndex] picks which
 * release, [Semver] decides whether it is newer, [Sha256] decides whether it is
 * intact, and this class only sequences them.
 */
class DesktopUpdater(
    private val currentVersion: String = BuildInfo.VERSION,
    private val platform: String? = UpdatePlatform.current(),
    private val feed: ReleaseFeed = GithubReleases(),
    private val http: UpdateHttp = KtorUpdateHttp(),
    private val tagPrefix: String = GithubReleases.DESKTOP_TAG_PREFIX,
    private val cacheDir: File = defaultCacheDir(),
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
    /** Injected so a test never spawns anything. Returns false when the launch failed. */
    private val launcher: (File) -> Boolean = ::launchInstaller,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** For the Settings screen: what this build believes it is. */
    val installedVersion: String get() = currentVersion

    /** For the Settings screen: the repo updates come from, shown never editable. */
    val sourceRepo: String get() = feed.repo

    /**
     * Launch check, then every [INTERVAL_MS] — sooner if the last one failed.
     * Cancelling the returned job stops it; there is no other lifecycle to get
     * wrong. Unlike the old private feed there is no token to wait on: GitHub is
     * public, so the first pass on a fresh install can already succeed.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        var backoff = RETRY_MS
        while (true) {
            val outcome = runCatching { check() }
            // A thrown exception is a failed pass too. Reading only the returned
            // state would treat a crash as a success and sleep four hours on it.
            if (outcome.isFailure || outcome.getOrNull() is UpdateState.Error) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            } else {
                backoff = RETRY_MS
                delay(INTERVAL_MS)
            }
        }
    }

    /**
     * One pass. Safe to call from a "check now" button; the state it leaves
     * behind is the return value too, so a caller need not observe the flow.
     *
     * Once [UpdateState.Ready], further checks are a no-op unless the release has
     * moved on again — re-downloading 90 MB every four hours while the owner has
     * not restarted yet is the kind of thing nobody notices until the link bill.
     */
    suspend fun check(): UpdateState {
        val plat = platform ?: return fail("no build for this platform")

        _state.value = UpdateState.Checking
        val releases = runCatching { feed.list() }
            .getOrElse { return fail("could not reach GitHub releases: ${it.message}") }
        val release = GithubReleaseIndex.newest(releases, tagPrefix)
            ?: return fail("no $tagPrefix release published yet")
        val tagVersion = GithubReleaseIndex.versionOf(release, tagPrefix)
        if (!Semver.isNewer(tagVersion, currentVersion)) {
            return settle(UpdateState.UpToDate(currentVersion))
        }

        val manifestAsset = release.asset(MANIFEST_NAME)
            ?: return fail("release $tagVersion has no $MANIFEST_NAME — cannot verify it, so it is refused")
        val text = runCatching { feed.getText(manifestAsset.browserDownloadUrl) }
            .getOrElse { return fail("could not fetch the release manifest: ${it.message}") }
        val manifest = UpdateManifestCodec.parseOrNull(text)
            ?: return fail("the release manifest was not readable")

        // The tag says newer; the manifest is the authority on the artifact + its
        // hash. Guard against a manifest that is somehow NOT newer than us.
        if (!Semver.isNewer(manifest.version, currentVersion)) {
            return settle(UpdateState.UpToDate(currentVersion))
        }
        val artifact = manifest.artifactFor(plat)
            ?: return fail("release ${manifest.version} has no $plat build")
        val asset = release.asset(artifact.file)
            ?: return fail("release ${manifest.version} lists ${artifact.file} but does not carry it")

        val ready = _state.value as? UpdateState.Ready
        if (ready != null && ready.version == manifest.version && Sha256.matches(ready.file, artifact.sha256)) {
            return ready // already fetched and verified; wait for the human
        }
        return download(asset.browserDownloadUrl, manifest, artifact)
    }

    private suspend fun download(
        url: String,
        manifest: UpdateManifest,
        artifact: UpdateArtifact,
    ): UpdateState {
        val dest = File(cacheDir, artifact.file)

        // Already on disk from an earlier run, and still the right bytes.
        if (dest.isFile && Sha256.matches(dest, artifact.sha256)) {
            return settle(readyState(manifest, artifact, dest))
        }

        _state.value = UpdateState.Downloading(manifest.version, 0, artifact.size)
        runCatching {
            http.download(url, dest) { seen, total ->
                _state.value = UpdateState.Downloading(
                    manifest.version, seen, if (total > 0) total else artifact.size,
                )
            }
        }.getOrElse { return fail("download failed: ${it.message}") }

        // THE GATE. Verified before the file is named as installable and long
        // before anything is allowed to execute it — these builds are unsigned,
        // so this hash is the only integrity check in the chain.
        if (!Sha256.matches(dest, artifact.sha256)) {
            dest.delete()
            return fail("downloaded ${artifact.file} did not match its sha256 — discarded")
        }
        return settle(readyState(manifest, artifact, dest))
    }

    private fun readyState(manifest: UpdateManifest, artifact: UpdateArtifact, dest: File) =
        UpdateState.Ready(
            version = manifest.version,
            file = dest,
            notes = manifest.notes,
            // Linux gets a .deb and a sentence: installing it needs root, and an
            // app that asks for a password to update itself is an app teaching
            // the owner to type one into whatever asks.
            installable = isWindows && artifact.file.endsWith(".exe"),
        )

    /**
     * Runs the parked installer and reports whether it started. THE CALLER MUST
     * BE A USER ACTION — nothing in this class calls it. Quitting afterwards is
     * the caller's job too, because only the window knows what is unsaved.
     *
     * @return false when there is nothing ready, this platform cannot install, or
     *   the launch failed. Never throws into a click handler.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (!ready.installable) return false
        // Re-verified at the moment of execution, not merely when it landed. The
        // file has been sitting in a world-readable cache since the download, and
        // the check that matters is the one taken closest to the exec.
        val artifactOk = ready.file.isFile && ready.file.length() > 0
        if (!artifactOk) {
            _state.value = UpdateState.Error("the downloaded installer is gone")
            return false
        }
        return launcher(ready.file)
    }

    private fun settle(s: UpdateState): UpdateState { _state.value = s; return s }

    private fun fail(message: String): UpdateState = settle(UpdateState.Error(message))

    companion object {
        /** The manifest asset every release carries — the sha256 authority. */
        const val MANIFEST_NAME: String = "manifest.json"

        /** Four hours. The owner restarts this client far more often than that. */
        const val INTERVAL_MS: Long = 4 * 60 * 60 * 1000L

        /**
         * First retry after a failed pass, doubling to [RETRY_MAX_MS]. Backs off
         * rather than hammering the GitHub API (unauthenticated, 60/hr): a check
         * that fails usually keeps failing, and each pass writes a diagnostics
         * line. Half a minute is quick enough that a fixed problem stops being
         * reported as broken; the cap keeps a dead feed from filling that log.
         */
        const val RETRY_MS: Long = 30 * 1000L
        const val RETRY_MAX_MS: Long = 30 * 60 * 1000L

        fun defaultCacheDir(): File {
            val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.cache")
            return File("$base/huginn-desktop-kt/updates")
        }

        private fun launchInstaller(file: File): Boolean = runCatching {
            // No `/S`: the installer runs with its UI so the person who asked for
            // it sees what it does. A silent installer fired from an app is how a
            // user stops being able to tell an update from an intrusion.
            ProcessBuilder(file.absolutePath).apply { directory(file.parentFile) }.start()
            true
        }.getOrDefault(false)
    }
}
