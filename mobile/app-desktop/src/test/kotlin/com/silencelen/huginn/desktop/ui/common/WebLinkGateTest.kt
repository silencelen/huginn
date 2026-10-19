package com.silencelen.huginn.desktop.ui.common

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [openInBrowser] will hand to the operating system: http(s) and nothing
 * else, whoever wrote the address — the daemon's sign-in URL, an app's address,
 * a link in an answer.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`.
 */
class WebLinkGateTest {

    private val opened = mutableListOf<URI>()
    private var refusals = 0

    private fun open(url: String): Boolean =
        browseWebLink(url, browse = { opened += it; true }, refused = { refusals++ })

    @Test
    fun `nothing but http and https reaches the browser`() {
        val refused = listOf(
            // This app's own scheme: a sign-in "URL" must not be a second door to it.
            "huginn://answer?session=main&option=1&fp=0123456789ab&nonce=n",
            "huginn://ack?key=sess%3Amain",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "ms-settings:privacy",
            "mailto:someone@example.invalid",
            "smb://example.invalid/share",
            "intent://scan/#Intent;scheme=zxing;end",
            "not a url",
            "",
        )
        for (url in refused) assertFalse(open(url), "opened <$url>")
        assertEquals(emptyList(), opened)
        assertEquals(refused.size, refusals)
    }

    @Test
    fun `a sign-in page and an app's address open`() {
        assertTrue(open("https://example.invalid/oauth/authorize?code=true&state=abc"))
        assertTrue(open("http://example.invalid:8088/"))
        assertTrue(open("HTTPS://example.invalid/"), "the scheme is case-insensitive")
        assertEquals(3, opened.size)
        assertEquals(0, refusals)
    }

    @Test
    fun `the predicate agrees with the gate`() {
        assertTrue(isWebLink("https://example.invalid/x"))
        assertFalse(isWebLink("huginn://open?view=chats&id=x"))
        assertFalse(isWebLink("file:///x"))
    }
}
