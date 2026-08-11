package com.silencelen.huginn.update

import com.silencelen.huginn.data.huginnHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One asset attached to a GitHub release. */
@Serializable
data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * A GitHub release, as much of `/repos/:owner/:repo/releases` as the updater
 * reads. Unknown fields are ignored so the API growing a field never breaks a
 * shipped client.
 */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
) {
    fun asset(assetName: String): GhAsset? = assets.firstOrNull { it.name == assetName }
    fun assetEndingWith(suffix: String): GhAsset? = assets.firstOrNull { it.name.endsWith(suffix) }
}

/**
 * Picks which release a client should be looking at — pure, so both shells and
 * their tests agree without a network.
 */
object GithubReleaseIndex {
    /**
     * The newest published (non-draft, non-prerelease) release whose tag starts
     * with [tagPrefix] (e.g. "desktop-v"), by SEMVER of the tail — NOT by the
     * order GitHub returned them, and NOT by string order (which would stall at
     * a minor of ten). Null when nothing matches.
     */
    fun newest(releases: List<GhRelease>, tagPrefix: String): GhRelease? =
        releases
            .filter { !it.draft && !it.prerelease && it.tagName.startsWith(tagPrefix) }
            .filter { Semver.parse(versionOf(it, tagPrefix)) != null }
            .maxWithOrNull { a, b -> Semver.compare(versionOf(a, tagPrefix), versionOf(b, tagPrefix)) }

    /** The semver tail of a release tag: "desktop-v0.6.0" − "desktop-v" = "0.6.0". */
    fun versionOf(release: GhRelease, tagPrefix: String): String =
        release.tagName.removePrefix(tagPrefix)
}

/**
 * The API surface each updater reads, behind an interface so the sequencing
 * logic tests without a socket. [GithubReleases] is the real one.
 */
interface ReleaseFeed {
    /** The pinned repo these releases come from — for diagnostics/UI, never editable. */
    val repo: String
    suspend fun list(perPage: Int = 30): List<GhRelease>
    /** Fetch a small text asset (a release manifest) from its download URL. */
    suspend fun getText(url: String): String
}

class ReleaseFeedException(val status: Int, message: String) : Exception(message)

/**
 * Reads GitHub's PUBLIC Releases API for a PINNED repo.
 *
 * This is the update trust anchor now that huginn is a public project. The repo
 * slug is a compile-time constant, not a user setting — the same "never derived
 * from what the user typed" boundary the old private feed had ([REPO] replaces a
 * pinned tailnet host), just re-anchored to the public repo over HTTPS. It does
 * NOT reuse HuginnClient: that class's base URL is user-editable (routes change
 * with which VPN holds the tunnel), and an installer's origin must never move
 * with a Settings typo. Integrity is still the release manifest's sha256, which
 * each shell verifies before running or installing anything.
 */
class GithubReleases(
    override val repo: String = REPO,
    engine: HttpClientEngine = huginnHttpEngine(),
    private val client: HttpClient = HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    },
) : ReleaseFeed {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(perPage: Int): List<GhRelease> {
        val url = "https://api.github.com/repos/$repo/releases?per_page=$perPage"
        val resp = client.get(url) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", USER_AGENT) // GitHub rejects an API request with no UA
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw ReleaseFeedException(resp.status.value, "${resp.status.value} listing releases: ${body.take(200)}")
        }
        return json.decodeFromString(ListSerializer(GhRelease.serializer()), body)
    }

    override suspend fun getText(url: String): String {
        val resp = client.get(url) { header("User-Agent", USER_AGENT) }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw ReleaseFeedException(resp.status.value, "${resp.status.value} fetching manifest: ${body.take(200)}")
        }
        return body
    }

    companion object {
        /** The pinned public repo. Changing releases means pushing tags here. */
        const val REPO: String = "silencelen/huginn"
        const val USER_AGENT: String = "huginn-updater"
        const val DESKTOP_TAG_PREFIX: String = "desktop-v"
        const val APP_TAG_PREFIX: String = "app-v"
    }
}
