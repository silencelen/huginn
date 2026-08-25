package com.silencelen.huginn.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.silencelen.huginn.data.huginnHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the Settings "Software update" section shows and acts on.
 *
 * The phone splits find/download/install into three explicit user steps rather
 * than the desktop's auto-download: an APK is ~50 MB and the owner may be on
 * mobile data, so nothing is fetched until they ask, and nothing is installed
 * until they ask again — with the system's own "install unknown apps"
 * confirmation on top of that.
 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val versionName: String) : AppUpdateState
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val notes: String,
        val assetUrl: String,
        val sha256: String,
        val size: Long,
        val apkName: String,
    ) : AppUpdateState
    data class Downloading(val versionName: String, val bytes: Long, val total: Long) : AppUpdateState {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Ready(val versionName: String, val file: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

/**
 * Updates the phone app FROM GitHub releases (the owner's ask: self-update in the
 * app, not siloed to the private devstore). It reads the pinned public repo,
 * verifies the APK's sha256 against the release manifest, and hands the verified
 * file to the system installer.
 *
 * The decision + fetch flow is injectable so it tests without Android: the real
 * app builds one with [forApp]. Integrity is the manifest's sha256 (these APKs
 * are signed too, and Android re-checks the signature at install — but the
 * updater's own gate is the hash, taken before the file reaches the installer).
 */
class PhoneUpdater(
    private val installedVersionCode: Long,
    private val installedVersionName: String = "",
    private val feed: ReleaseFeed = GithubReleases(),
    private val tagPrefixes: List<String> = GithubReleases.MOBILE_TAG_PREFIXES,
    private val cacheDir: File,
    private val downloader: suspend (url: String, dest: File, onProgress: (Long, Long) -> Unit) -> Unit =
        ::downloadTo,
    private val installer: (File) -> Boolean,
    private val sha256Matches: (File, String) -> Boolean = Sha256::matches,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    val installedVersion: String get() = installedVersionName
    val sourceRepo: String get() = feed.repo

    /**
     * Looks for a newer release. Does NOT download — that is a separate tap, so a
     * background check on app open never spends the owner's data. Leaves
     * [AppUpdateState.Available] or [AppUpdateState.UpToDate].
     */
    suspend fun check(): AppUpdateState {
        _state.value = AppUpdateState.Checking
        val releases = runCatching { feed.list() }
            .getOrElse { return fail("could not reach GitHub releases: ${it.message}") }
        val release = GithubReleaseIndex.newest(releases, tagPrefixes)
            ?: return fail("no ${tagPrefixes.first()} release published yet")

        val manifestAsset = release.asset(MANIFEST_NAME)
            ?: return fail("release ${release.tagName} has no $MANIFEST_NAME — cannot verify it")
        val text = runCatching { feed.getText(manifestAsset.browserDownloadUrl) }
            .getOrElse { return fail("could not fetch the release manifest: ${it.message}") }
        val manifest = AppManifestCodec.parseOrNull(text)
            ?: return fail("the release manifest was not readable")

        if (manifest.versionCode <= installedVersionCode) {
            return settle(AppUpdateState.UpToDate(installedVersionName.ifBlank { manifest.versionName }))
        }
        val apk = release.assetEndingWith(".apk")
            ?: return fail("release ${manifest.versionName} has no APK asset")
        if (manifest.sha256.length != 64) {
            return fail("release ${manifest.versionName} manifest has no usable sha256")
        }
        return settle(
            AppUpdateState.Available(
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                notes = manifest.notes,
                assetUrl = apk.browserDownloadUrl,
                sha256 = manifest.sha256,
                size = if (apk.size > 0) apk.size else manifest.sizeBytes,
                apkName = apk.name,
            ),
        )
    }

    /**
     * Downloads the APK named by the current [AppUpdateState.Available] and
     * verifies it. THE GATE is the sha256, taken before the file is named Ready
     * and long before it reaches the installer.
     */
    suspend fun download(): AppUpdateState {
        val available = _state.value as? AppUpdateState.Available
            ?: return fail("nothing to download — check for an update first")
        val dest = File(cacheDir, available.apkName)

        if (dest.isFile && sha256Matches(dest, available.sha256)) {
            return settle(AppUpdateState.Ready(available.versionName, dest))
        }

        _state.value = AppUpdateState.Downloading(available.versionName, 0, available.size)
        runCatching {
            downloader(available.assetUrl, dest) { seen, total ->
                _state.value = AppUpdateState.Downloading(
                    available.versionName, seen, if (total > 0) total else available.size,
                )
            }
        }.getOrElse { return fail("download failed: ${it.message}") }

        if (!sha256Matches(dest, available.sha256)) {
            dest.delete()
            return fail("the downloaded APK did not match its sha256 — discarded")
        }
        return settle(AppUpdateState.Ready(available.versionName, dest))
    }

    /**
     * Hands the verified APK to the system installer. THE CALLER MUST BE A USER
     * ACTION. The system shows its own "install unknown apps" confirmation; this
     * never installs silently.
     *
     * @return false when there is nothing ready or the launch failed.
     */
    fun install(): Boolean {
        val ready = _state.value as? AppUpdateState.Ready ?: return false
        if (!ready.file.isFile || ready.file.length() <= 0) {
            _state.value = AppUpdateState.Error("the downloaded update is gone")
            return false
        }
        return installer(ready.file)
    }

    private fun settle(s: AppUpdateState): AppUpdateState { _state.value = s; return s }
    private fun fail(message: String): AppUpdateState = settle(AppUpdateState.Error(message))

    companion object {
        const val MANIFEST_NAME: String = "latest.json"

        /** The real updater, wired to this install of the app. */
        fun forApp(context: Context): PhoneUpdater {
            val app = context.applicationContext
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            return PhoneUpdater(
                installedVersionCode = PackageInfoCompat.getLongVersionCode(info),
                installedVersionName = info.versionName ?: "",
                cacheDir = File(app.cacheDir, "updates"),
                installer = { file -> installApk(app, file) },
            )
        }

        /**
         * Streams [url] into [dest] without buffering the whole ~50 MB body. Its
         * own tiny client — NEVER HuginnClient, whose base URL is user-editable
         * and must not decide where an installer comes from.
         */
        private suspend fun downloadTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
            withContext(Dispatchers.IO) {
                val client = HttpClient(huginnHttpEngine()) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 8_000
                        requestTimeoutMillis = 30 * 60_000
                        socketTimeoutMillis = 60_000
                    }
                }
                try {
                    dest.parentFile?.mkdirs()
                    val part = File(dest.parentFile, dest.name + ".part")
                    client.prepareGet(url) { header("User-Agent", GithubReleases.USER_AGENT) }.execute { resp ->
                        if (!resp.status.isSuccess()) error("${resp.status.value} fetching ${dest.name}")
                        val total = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
                        val channel = resp.bodyAsChannel()
                        var seen = 0L
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = channel.readAvailable(buf, 0, buf.size)
                                if (n < 0 || (n == 0 && channel.isClosedForRead)) break
                                if (n > 0) { out.write(buf, 0, n); seen += n; onProgress(seen, total) }
                            }
                        }
                    }
                    if (dest.exists()) dest.delete()
                    check(part.renameTo(dest)) { "could not move ${part.name} into place" }
                } finally {
                    client.close()
                }
            }

        /**
         * Opens the verified APK with the system installer via a FileProvider
         * content URI (a file:// URI is refused since Android N). Needs the
         * REQUEST_INSTALL_PACKAGES permission and the user's "install unknown
         * apps" grant, which the system prompts for.
         */
        private fun installApk(context: Context, apk: File): Boolean = runCatching {
            // The app's existing FileProvider (authority <pkg>.fileprovider) — the
            // downloaded APK lives under cache/updates/, exposed via file_paths.xml.
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }
}
