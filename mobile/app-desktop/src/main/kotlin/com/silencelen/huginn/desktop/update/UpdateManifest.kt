package com.silencelen.huginn.desktop.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One downloadable file, and the hash that says it arrived intact.
 *
 * The sha256 is written by the release script from the bytes it just built and
 * travels IN the manifest, so a client needs exactly one authenticated fetch to
 * learn both what to download and what it must hash to. There is no separate
 * `.sha256` sidecar to go stale beside a re-uploaded artifact.
 */
@Serializable
data class UpdateArtifact(
    val file: String,
    val sha256: String,
    val size: Long,
)

/**
 * The `/v1/desktop-kt/manifest` document.
 *
 * Keyed by PLATFORM rather than the Electron channel's `windows` / `linux.deb`
 * shape, because this client will grow arm64 and macOS targets and a nested
 * per-OS record needs a new field (and a new parser branch) for each one, while
 * a map needs a new key. Unknown keys are ignored on purpose: an older installed
 * client must keep updating after a newer release adds a platform it has never
 * heard of.
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

    /** Throws on anything that is not this document — see [Semver.compare] for why. */
    fun parse(text: String): UpdateManifest = json.decodeFromString(UpdateManifest.serializer(), text)

    /** Null instead of throwing, for the call sites that treat a bad feed as "no update". */
    fun parseOrNull(text: String): UpdateManifest? = runCatching { parse(text) }.getOrNull()
}

/**
 * Which artifact this build should be looking for.
 *
 * The key is the platform, not the file name, so the release script and the
 * client never have to agree on a naming convention — only on this string.
 */
object UpdatePlatform {
    const val WINDOWS_X64 = "windows-x64"
    const val LINUX_X64 = "linux-x64"

    fun current(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x64"
            else -> return null // arm64 desktop is not built yet; say so rather than guess
        }
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") -> "windows-$arch"
            os.startsWith("linux") -> "linux-$arch"
            else -> null
        }
    }
}
