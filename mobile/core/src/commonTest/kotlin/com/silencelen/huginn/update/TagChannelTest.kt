package com.silencelen.huginn.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Renaming an update channel without orphaning the clients already on it.
 *
 * A tag prefix is the ONLY thing an installed client knows to look for. Rename it
 * and the old client does not error — it matches nothing and reports "up to date"
 * forever, and nothing can tell it otherwise. So both names are accepted for as
 * long as any device might still be on the old one.
 */
class TagChannelTest {

    private fun rel(tag: String) = GhRelease(tagName = tag)

    @Test
    fun aClientOnBothNamesFindsAReleaseUnderEither() {
        val prefixes = GithubReleases.MOBILE_TAG_PREFIXES
        assertEquals("app-v2.73.0", GithubReleaseIndex.newest(listOf(rel("app-v2.73.0")), prefixes)?.tagName)
        assertEquals("mobile-v2.74.0", GithubReleaseIndex.newest(listOf(rel("mobile-v2.74.0")), prefixes)?.tagName)
    }

    @Test
    fun theNEWESTWinsAcrossTheRename() {
        // The whole point of the transition window: both names are live at once and
        // the version decides, not the name. A newer release under the old tag must
        // still beat an older one under the new tag, or a rollback would strand a
        // device on whichever name happened to sort first.
        val mixed = listOf(rel("app-v2.73.0"), rel("mobile-v2.74.0"), rel("app-v2.72.0"))
        assertEquals("mobile-v2.74.0", GithubReleaseIndex.newest(mixed, GithubReleases.MOBILE_TAG_PREFIXES)?.tagName)

        val oldIsNewer = listOf(rel("mobile-v2.73.0"), rel("app-v2.75.0"))
        assertEquals("app-v2.75.0", GithubReleaseIndex.newest(oldIsNewer, GithubReleases.MOBILE_TAG_PREFIXES)?.tagName)
    }

    @Test
    fun channelsStayApart() {
        // The failure this guards is a phone offering itself a desktop build. The
        // prefixes are what keep four release families in one repo distinguishable.
        val all = listOf(rel("desktop-v0.8.8"), rel("appd-v2.65.0"), rel("cli-v0.10.2"), rel("mobile-v2.73.0"))
        assertEquals("mobile-v2.73.0", GithubReleaseIndex.newest(all, GithubReleases.MOBILE_TAG_PREFIXES)?.tagName)
        assertEquals("desktop-v0.8.8", GithubReleaseIndex.newest(all, GithubReleases.DESKTOP_TAG_PREFIXES)?.tagName)
    }

    @Test
    fun theCliAndDaemonChannelsAreNotTheMobileOne() {
        // `cli-v` and `appd-v` both end in "-v" like the others; a sloppy match
        // would let the phone try to install the daemon's release.
        assertNull(GithubReleaseIndex.newest(listOf(rel("cli-v0.10.2"), rel("appd-v2.65.0")), GithubReleases.MOBILE_TAG_PREFIXES))
    }

    @Test
    fun theVersionIsReadFromWhicheverNameTheTagCarries() {
        assertEquals("2.73.0", GithubReleaseIndex.versionOf(rel("app-v2.73.0"), GithubReleases.MOBILE_TAG_PREFIXES))
        assertEquals("2.74.0", GithubReleaseIndex.versionOf(rel("mobile-v2.74.0"), GithubReleases.MOBILE_TAG_PREFIXES))
    }

    @Test
    fun aBareVTagIsNotMistakenForTheMobileChannel() {
        // The CLI published under a bare "v" for its whole life. Those tags are
        // still in the repo and must never look like a phone build.
        assertNull(GithubReleaseIndex.newest(listOf(rel("v0.10.1")), GithubReleases.MOBILE_TAG_PREFIXES))
    }
}
