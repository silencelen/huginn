package com.silencelen.huginn.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One downloadable file, and the hash that says it arrived intact.
 *
 * The sha256 is written by the release script from the bytes it just built and
 * travels IN the manifest, so a client needs exactly one fetch to learn both
 * what to download and what it must hash to. There is no separate `.sha256`
 * sidecar to go stale beside a re-uploaded artifact.
 */
@Serializable
data class UpdateArtifact(
    val file: String,
    val sha256: String,
    val size: Long,
)

/**
 * The desktop `manifest.json`, attached to a `desktop-v*` GitHub release.
 *
 * Keyed by PLATFORM rather than a per-OS shape, because this client will grow
 * arm64 and macOS targets and a nested per-OS record needs a new parser branch
 * for each, while a map needs a new key. Unknown keys are ignored on purpose: an
 * older installed client must keep updating after a newer release adds a
 * platform it has never heard of.
 */
@Serializable
data class UpdateManifest(
    val version: String,
    @SerialName("releasedAt") val releasedAt: String = "",
    val notes: String = "",
    val artifacts: Map<String, UpdateArtifact> = emptyMap(),
) {
    fun artifactFor(platform: String): UpdateArtifact? = artifacts[platform]
}

object UpdateManifestCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Throws on anything that is not this document. */
    fun parse(text: String): UpdateManifest = json.decodeFromString(UpdateManifest.serializer(), text)

    /** Null instead of throwing, for the call sites that treat a bad feed as "no update". */
    fun parseOrNull(text: String): UpdateManifest? = runCatching { parse(text) }.getOrNull()
}

/**
 * The phone `latest.json`, attached to an `app-v*` GitHub release (the same
 * document the build already writes for the devstore feed — now also published
 * so the app can update ITSELF from GitHub instead of only via devstore).
 *
 * Comparison is by [versionCode] (monotonic), which is the number Android itself
 * orders installs by; [versionName] is only shown. [sha256] gates the APK before
 * it is handed to the installer.
 */
@Serializable
data class AppManifest(
    @SerialName("package") val pkg: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val apk: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val notes: String = "",
)

object AppManifestCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(text: String): AppManifest = json.decodeFromString(AppManifest.serializer(), text)
    fun parseOrNull(text: String): AppManifest? = runCatching { parse(text) }.getOrNull()
}
