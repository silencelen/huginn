package com.silencelen.huginn.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared update models + version/release logic, tested where they live now
 * (:core), so the desktop and phone updaters inherit the same guarantees.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual)`.
 */
class SemverTest {

    @Test
    fun `numeric components, not string ones`() {
        // The whole reason this exists: as strings "0.10.0" < "0.9.0", so a string
        // compare stops the app updating forever at the tenth minor.
        assertTrue(Semver.isNewer("0.10.0", "0.9.0"))
        assertTrue(Semver.isNewer("1.0.0", "0.99.99"))
        assertTrue(Semver.isNewer("0.1.10", "0.1.9"))
        assertFalse(Semver.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `equal is not newer`() {
        assertFalse(Semver.isNewer("1.2.3", "1.2.3"))
        assertEquals(0, Semver.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `a pre-release loses to its own release`() {
        assertFalse(Semver.isNewer("0.2.0-rc1", "0.2.0"))
        assertTrue(Semver.isNewer("0.2.0", "0.2.0-rc1"))
        assertTrue(Semver.isNewer("0.2.0-rc2", "0.2.0-rc1"))
        assertTrue(Semver.isNewer("0.2.0-rc1", "0.1.9"))
    }

    @Test
    fun `build metadata is ignored for precedence`() {
        assertEquals(0, Semver.compare("1.2.3+build9", "1.2.3"))
    }

    @Test
    fun `garbage never reads as an update`() {
        assertFalse(Semver.isNewer("", "0.1.0"))
        assertFalse(Semver.isNewer("nightly", "0.1.0"))
        assertFalse(Semver.isNewer("9", "0.1.0"))
        assertFalse(Semver.isNewer("9.9", "0.1.0"))
        assertNull(Semver.parse("1.2.3.4"))
        assertFailsWith<IllegalArgumentException> { Semver.compare("x", "1.0.0") }
    }
}

class UpdateManifestParseTest {

    private val real = """
        {"version":"0.1.0","releasedAt":"2026-07-30T21:00:00Z","notes":"first",
         "artifacts":{
           "windows-x64":{"file":"Huginn-Desktop-Setup-0.1.0.exe","sha256":"aa","size":10},
           "linux-x64":{"file":"huginn-desktop-kt_0.1.0-1_amd64.deb","sha256":"bb","size":20}}}
    """.trimIndent()

    @Test
    fun `parses what the release script writes`() {
        val m = UpdateManifestCodec.parse(real)
        assertEquals("0.1.0", m.version)
        assertEquals("first", m.notes)
        assertEquals("Huginn-Desktop-Setup-0.1.0.exe", m.artifactFor("windows-x64")?.file)
        assertEquals(20L, m.artifactFor("linux-x64")?.size)
        assertNull(m.artifactFor("macos-arm64"))
    }

    @Test
    fun `an unknown platform key does not break an older client`() {
        val withNew = real.replace(
            """"linux-x64":""",
            """"macos-arm64":{"file":"x.dmg","sha256":"cc","size":1,"newField":true},"linux-x64":""",
        )
        val m = UpdateManifestCodec.parse(withNew)
        assertEquals("Huginn-Desktop-Setup-0.1.0.exe", m.artifactFor("windows-x64")?.file)
    }

    @Test
    fun `a non-manifest is null, not an exception at the call site`() {
        assertNull(UpdateManifestCodec.parseOrNull("not json"))
        assertNull(UpdateManifestCodec.parseOrNull("""{"nope":1}"""))
        assertNull(UpdateManifestCodec.parseOrNull(""))
        assertNull(UpdateManifestCodec.parseOrNull("<html>401</html>"))
    }
}

class AppManifestTest {

    // The exact document the phone build writes (mobile/app/build.gradle.kts) and
    // now also publishes to the app-v* release, so the app can update itself.
    private val real = """
        {"package":"com.silencelen.huginn","versionCode":19209878,"versionName":"2.59.0",
         "apk":"Huginn-release-19209878-1786435479.apk","sha256":"7e28f0","sizeBytes":50289767,
         "timestamp":1786435479,"builtAt":"2026-08-11T01:05:40Z","config":"Release"}
    """.trimIndent()

    @Test
    fun `parses the devstore latest json, ignoring fields the updater ignores`() {
        val m = AppManifestCodec.parse(real)
        assertEquals("com.silencelen.huginn", m.pkg)
        assertEquals(19209878L, m.versionCode)
        assertEquals("2.59.0", m.versionName)
        assertEquals("7e28f0", m.sha256)
        assertEquals(50289767L, m.sizeBytes)
    }

    @Test
    fun `rubbish is null, never a zero-code manifest that reads as an update`() {
        assertNull(AppManifestCodec.parseOrNull("<html>500</html>"))
        assertNull(AppManifestCodec.parseOrNull(""))
    }
}

class GithubReleaseIndexTest {

    private fun rel(tag: String, draft: Boolean = false, pre: Boolean = false) =
        GhRelease(tagName = tag, draft = draft, prerelease = pre)

    @Test
    fun `newest is by semver, not string order or list order`() {
        val releases = listOf(
            rel("desktop-v0.9.0"),
            rel("desktop-v0.10.0"), // string-less-than 0.9.0, semver-greater
            rel("desktop-v0.2.0"),
        )
        assertEquals("desktop-v0.10.0", GithubReleaseIndex.newest(releases, "desktop-v")?.tagName)
    }

    @Test
    fun `only the matching tag prefix counts`() {
        val releases = listOf(rel("app-v3.0.0"), rel("desktop-v0.1.0"), rel("v9.9.9"))
        assertEquals("desktop-v0.1.0", GithubReleaseIndex.newest(releases, "desktop-v")?.tagName)
        assertEquals("app-v3.0.0", GithubReleaseIndex.newest(releases, "app-v")?.tagName)
    }

    @Test
    fun `drafts and prereleases are never offered`() {
        val releases = listOf(
            rel("desktop-v0.1.0"),
            rel("desktop-v0.9.0", draft = true),
            rel("desktop-v0.8.0", pre = true),
        )
        assertEquals("desktop-v0.1.0", GithubReleaseIndex.newest(releases, "desktop-v")?.tagName)
    }

    @Test
    fun `no match is null, and a non-semver tag is skipped`() {
        assertNull(GithubReleaseIndex.newest(listOf(rel("app-v1.0.0")), "desktop-v"))
        assertNull(GithubReleaseIndex.newest(listOf(rel("desktop-vnightly")), "desktop-v"))
    }

    @Test
    fun `versionOf strips the prefix, and asset lookups find by name`() {
        val r = GhRelease(
            tagName = "desktop-v0.6.0",
            assets = listOf(
                GhAsset("manifest.json", "https://x/manifest.json", 1),
                GhAsset("Huginn-0.6.0.exe", "https://x/Huginn-0.6.0.exe", 2),
            ),
        )
        assertEquals("0.6.0", GithubReleaseIndex.versionOf(r, "desktop-v"))
        assertEquals("https://x/manifest.json", r.asset("manifest.json")?.browserDownloadUrl)
        assertEquals("Huginn-0.6.0.exe", r.assetEndingWith(".exe")?.name)
        assertNull(r.asset("nope.json"))
    }

    @Test
    fun `the update source is a compile-time-pinned public repo`() {
        // The trust anchor: whoever can push releases here controls what installs.
        // It must be a constant, never derived from a user setting.
        assertEquals("silencelen/huginn", GithubReleases.REPO)
    }
}
