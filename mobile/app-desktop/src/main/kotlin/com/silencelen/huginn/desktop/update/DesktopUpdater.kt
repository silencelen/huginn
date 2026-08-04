package com.silencelen.huginn.desktop.update

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
 * Checks the pinned channel on launch and every four hours, downloads a newer
 * build, verifies the hash the manifest carries, and stops.
 *
 * Hand-rolled because there is nothing to adopt: electron-updater has no JVM
 * equivalent, and Hydraulic Conveyor — the one product that does this properly
 * for Compose Desktop — drives Windows updates through the OS MSIX engine, which
 * cannot be given a Bearer header, so it requires a PUBLIC unauthenticated update
 * site. This channel is Bearer-authed on the tailnet and is going to stay that
 * way. update4j is archived.
 *
 * Everything security-relevant is deliberately small and testable in isolation:
 * [UpdateFeed] pins where bytes may come from, [Semver] decides whether they are
 * newer, [Sha256] decides whether they are intact, and this class only sequences
 * them. Nothing here parses, hashes or compares by hand.
 */
class DesktopUpdater(
    private val currentVersion: String = BuildInfo.VERSION,
    /** The daemon token. Same one every other request carries; the FEED is pinned, the token is not a secret to it. */
    private val tokenProvider: () -> String,
    private val platform: String? = UpdatePlatform.current(),
    private val http: UpdateHttp = KtorUpdateHttp(),
    private val bases: List<String> = UpdateFeed.PINNED_BASES,
    private val cacheDir: File = defaultCacheDir(),
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
    /** Injected so a test never spawns anything. Returns false when the launch failed. */
    private val launcher: (File) -> Boolean = ::launchInstaller,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** For the Settings screen: what this build believes it is. */
    val installedVersion: String get() = currentVersion

    /**
     * Launch check, then every [INTERVAL_MS] — but sooner if the last one failed,
     * and at once if the token changes. Cancelling the returned job stops it;
     * there is no other lifecycle to get wrong.
     *
     * The plain four-hour loop this replaces made a solved problem keep looking
     * unsolved. The app checks on launch, which on a fresh install is BEFORE the
     * owner has typed a token, so the first pass fails with "no daemon token yet"
     * — and then Settings said that for four hours after the token was entered
     * and everything else in the app was plainly working. A wrong token that was
     * then corrected read the same way, as a stale 401.
     *
     * So: a failed pass retries on a backoff instead of sleeping the full
     * interval, and either kind of wait ends early when the token changes,
     * because the token is the thing that was usually wrong.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        var backoff = RETRY_MS
        while (true) {
            val outcome = runCatching { check() }
            // A thrown exception is a failed pass too. Reading only the returned
            // state would treat a crash as a success and sleep four hours on it.
            if (outcome.isFailure || outcome.getOrNull() is UpdateState.Error) {
                waitOrTokenChange(backoff)
                backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            } else {
                backoff = RETRY_MS
                waitOrTokenChange(INTERVAL_MS)
            }
        }
    }

    /**
     * Sleeps, but wakes as soon as the token is different from the one the pass
     * just ran with.
     *
     * Polled rather than collected from a flow so that this class keeps taking a
     * plain `() -> String` and stays constructible in a test with no settings
     * object at all. A comparison once a second against a string in memory is not
     * a cost worth an abstraction.
     */
    private suspend fun waitOrTokenChange(total: Long) {
        val was = tokenProvider().trim()
        var waited = 0L
        while (waited < total) {
            val step = minOf(WAKE_MS, total - waited)
            delay(step)
            waited += step
            if (tokenProvider().trim() != was) return
        }
    }

    /**
     * One pass. Safe to call from a "check now" button; the state it leaves
     * behind is the return value too, so a caller need not observe the flow.
     *
     * Once [UpdateState.Ready], further checks are a no-op unless the feed has
     * moved on again — re-downloading 90 MB every four hours while the owner has
     * not restarted yet is the kind of thing nobody notices until the link bill.
     */
    suspend fun check(): UpdateState {
        val token = tokenProvider().trim()
        if (token.isEmpty()) return fail("no daemon token yet")
        val plat = platform ?: return fail("no build for this platform")

        _state.value = UpdateState.Checking
        var lastError: String? = null
        for (base in bases) {
            // Belt and braces: `bases` is a constant, but this class is
            // constructible with another list and the refusal must live at the
            // point of use, not only at the point of declaration.
            if (!UpdateFeed.isPinned(base)) return fail(UpdateFeed.REFUSED)
            // Not `.getOrElse { … continue }`: `continue` inside an inline lambda
            // needs language version 2.2 and this module is on 2.1.
            val fetched = runCatching { http.getText(UpdateFeed.manifestUrl(base), token) }
            val failure = fetched.exceptionOrNull()
            if (failure != null) {
                lastError = failure.message ?: failure.toString()
                continue // this route is down; the other pinned one may not be
            }
            val text = fetched.getOrThrow()

            val manifest = UpdateManifestCodec.parseOrNull(text)
                ?: return fail("update feed returned something that is not a manifest")

            if (!Semver.isNewer(manifest.version, currentVersion)) {
                return settle(UpdateState.UpToDate(currentVersion))
            }
            val artifact = manifest.artifactFor(plat)
                ?: return fail("release ${manifest.version} has no $plat build")

            val ready = _state.value as? UpdateState.Ready
            if (ready != null && ready.version == manifest.version && Sha256.matches(ready.file, artifact.sha256)) {
                return ready // already fetched and verified; wait for the human
            }
            return download(base, token, manifest, artifact)
        }
        return fail(lastError ?: "no pinned update route answered")
    }

    private suspend fun download(
        base: String,
        token: String,
        manifest: UpdateManifest,
        artifact: UpdateArtifact,
    ): UpdateState {
        val url = runCatching { UpdateFeed.artifactUrl(base, artifact.file) }
            .getOrElse { return fail(it.message ?: UpdateFeed.REFUSED) }
        val dest = File(cacheDir, artifact.file)

        // Already on disk from an earlier run, and still the right bytes.
        if (dest.isFile && Sha256.matches(dest, artifact.sha256)) {
            return settle(readyState(manifest, artifact, dest))
        }

        _state.value = UpdateState.Downloading(manifest.version, 0, artifact.size)
        runCatching {
            http.download(url, token, dest) { seen, total ->
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
        /** Four hours. The owner restarts this client far more often than that. */
        const val INTERVAL_MS: Long = 4 * 60 * 60 * 1000L

        /**
         * First retry after a failed pass, doubling to [RETRY_MAX_MS].
         *
         * It backs off rather than hammering because a check that fails usually
         * keeps failing — an unreachable route, a daemon that is down — and each
         * pass writes a line to the diagnostics log. Half a minute is quick
         * enough that a fixed problem stops being reported as broken, and the cap
         * keeps a genuinely dead feed from filling that log.
         */
        const val RETRY_MS: Long = 30 * 1000L
        const val RETRY_MAX_MS: Long = 30 * 60 * 1000L

        /** How often a wait looks at the token. */
        const val WAKE_MS: Long = 1000L

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
