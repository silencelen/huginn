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
     * Verified and on disk. [file] is an installer on Windows; on Linux it is the
     * app-image tarball for a portable install, else the distro package (`.deb`
     * or Arch `.pkg.tar.zst`). [installable] says whether this client can do
     * anything with it beyond telling the user where it is, and [hint] is that
     * sentence when it cannot (blank when it can). [sha256] is the manifest's
     * claim, kept so the file is re-hashed at the moment of use.
     */
    data class Ready(
        val version: String,
        val file: File,
        val notes: String,
        val installable: Boolean,
        val hint: String = "",
        val sha256: String = "",
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
    /**
     * How this copy was installed (Linux). A Gradle run — every unit test that
     * does not pass one — reads as [LinuxInstall.Kind.DEV], which keeps the
     * pre-portable behaviour: parked, not installable.
     */
    private val linux: LinuxInstall.Mode =
        if (isWindows) LinuxInstall.Mode(LinuxInstall.Kind.DEV) else LinuxInstall.current(),
    /** The portable apply; injected so a test can drive it on a temp tree. */
    private val portableApply: (root: File, tarball: File, version: String, running: File?) -> Result<File> =
        { r, t, v, run -> PortableInstall.apply(r, t, v, run) },
    /** Starts the new version once this process has gone. Injected for the same reason as [launcher]. */
    private val relauncher: (File) -> Boolean = { PortableInstall.relaunchAfterExit(it) },
) {

    /** The manifest key this install looks up — see [UpdatePlatform.forInstall]. */
    private val key: String? = UpdatePlatform.forInstall(platform, linux.kind)

    private val portable: Boolean get() = !isWindows && linux.kind == LinuxInstall.Kind.PORTABLE && linux.root != null

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
     * And a check that FAILS leaves it Ready: see [fail].
     */
    suspend fun check(): UpdateState {
        val plat = key ?: return fail("no build for this platform")

        // ⚠ READ BEFORE `Checking` REPLACES IT. It used to be read after, where
        // it was always null: the "already fetched" short-cut below never fired,
        // and a check that failed (offline, GitHub's API answering 403) turned a
        // verified, parked update into an Error — no Install button, with the
        // installer still sitting in the cache.
        val parked = _state.value as? UpdateState.Ready
        _state.value = UpdateState.Checking
        val releases = runCatching { feed.list() }
            .getOrElse { return fail("could not reach GitHub releases: ${it.message}", parked) }
        val release = GithubReleaseIndex.newest(releases, tagPrefix)
            ?: return fail("no $tagPrefix release published yet", parked)
        val tagVersion = GithubReleaseIndex.versionOf(release, tagPrefix)
        if (!Semver.isNewer(tagVersion, currentVersion)) {
            return settle(UpdateState.UpToDate(currentVersion))
        }

        val manifestAsset = release.asset(MANIFEST_NAME)
            ?: return fail("release $tagVersion has no $MANIFEST_NAME — cannot verify it, so it is refused", parked)
        val text = runCatching { feed.getText(manifestAsset.browserDownloadUrl) }
            .getOrElse { return fail("could not fetch the release manifest: ${it.message}", parked) }
        val manifest = UpdateManifestCodec.parseOrNull(text)
            ?: return fail("the release manifest was not readable", parked)

        // The tag says newer; the manifest is the authority on the artifact + its
        // hash. Guard against a manifest that is somehow NOT newer than us.
        if (!Semver.isNewer(manifest.version, currentVersion)) {
            return settle(UpdateState.UpToDate(currentVersion))
        }
        val artifact = manifest.artifactFor(plat)
            ?: return fail("release ${manifest.version} has no $plat build", parked)
        val asset = release.asset(artifact.file)
            ?: return fail("release ${manifest.version} lists ${artifact.file} but does not carry it", parked)

        if (parked != null && parked.version == manifest.version && Sha256.matches(parked.file, artifact.sha256)) {
            // Settled, not just returned: the state reads Checking until it is.
            return settle(parked) // already fetched and verified; wait for the human
        }
        return download(asset.browserDownloadUrl, manifest, artifact, parked)
    }

    private suspend fun download(
        url: String,
        manifest: UpdateManifest,
        artifact: UpdateArtifact,
        parked: UpdateState.Ready?,
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
        }.getOrElse { return fail("download failed: ${it.message}", parked) }

        // THE GATE. Verified before the file is named as installable and long
        // before anything is allowed to execute it — these builds are unsigned,
        // so this hash is the only integrity check in the chain.
        if (!Sha256.matches(dest, artifact.sha256)) {
            dest.delete()
            return fail("downloaded ${artifact.file} did not match its sha256 — discarded", parked)
        }
        return settle(readyState(manifest, artifact, dest))
    }

    private fun readyState(manifest: UpdateManifest, artifact: UpdateArtifact, dest: File): UpdateState.Ready {
        // Windows runs its per-user installer; a PORTABLE Linux install applies
        // its own tarball. A distro-packaged Linux install gets the package and a
        // sentence: installing it needs root, and an app that asks for a password
        // to update itself is an app teaching the owner to type one into
        // whatever asks.
        val installable = (isWindows && artifact.file.endsWith(".exe")) ||
            (portable && artifact.file.endsWith(".tar.gz"))
        return UpdateState.Ready(
            version = manifest.version,
            file = dest,
            notes = manifest.notes,
            installable = installable,
            hint = if (installable) "" else LinuxInstall.manualHint(linux.kind, dest),
            sha256 = artifact.sha256,
        )
    }

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
        if (!portable) return launcher(ready.file)
        return installPortable(ready)
    }

    /**
     * The Linux self-update: re-hash, unpack beside, swap `current`, prune, and
     * arm a relaunch that waits for this process to exit. [installThenQuit] then
     * quits. Every failure before the swap leaves `current` — and so the menu
     * entry, the scheme handler and autostart — on the version that is running.
     */
    private fun installPortable(ready: UpdateState.Ready): Boolean {
        val root = linux.root ?: return false
        // Hashed AGAIN here, not only at download: this is the last moment before
        // the bytes become the program the menu starts, and the cache it sat in
        // is a plain user directory.
        if (!Sha256.matches(ready.file, ready.sha256)) {
            _state.value = UpdateState.Error("the downloaded update no longer matches its sha256 — not installed")
            return false
        }
        val launcherFile = portableApply(root, ready.file, ready.version, linux.versionDir)
            .getOrElse {
                _state.value = UpdateState.Error("update not installed: ${it.message}")
                return false
            }
        if (!relauncher(launcherFile)) {
            // Installed, but nothing will bring the app back: say so and STAY.
            // Quitting now would look exactly like an update that worked.
            _state.value = UpdateState.Error("${ready.version} installed, but the app could not restart itself — quit and start it again")
            return false
        }
        return true
    }

    private fun settle(s: UpdateState): UpdateState { _state.value = s; return s }

    /**
     * A pass that could not finish. It says nothing about an update already
     * [parked], so that one stays Ready and installable: the check's own failure
     * is no reason to take the Install button away.
     *
     * ⚠ RE-HASHED FIRST. A re-download of the same version writes over the very
     * file that was parked, and a parked file that no longer matches must come
     * back as the Error, never as installable.
     */
    private fun fail(message: String, parked: UpdateState.Ready? = null): UpdateState =
        settle(parked?.takeIf { it.file.isFile && Sha256.matches(it.file, it.sha256) } ?: UpdateState.Error(message))

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
