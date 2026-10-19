package com.silencelen.huginn

import com.silencelen.huginn.ui.WebLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the phone hands to `ACTION_VIEW`: http(s), and nothing else, whoever
 * wrote the address. The sign-in URL and the Settings links come from the
 * daemon; an `ACTION_VIEW` is resolved by scheme, so anything else would open
 * whichever app claims it.
 */
class WebLinksTest {

    @Test
    fun `a web address passes, trimmed`() {
        val signIn = "https://example.invalid/oauth/authorize?code=true&client_id=x&state=y"
        assertEquals(signIn, WebLinks.browsable(signIn))
        assertEquals("http://example.invalid:8088/", WebLinks.browsable("  http://example.invalid:8088/\n"))
        assertEquals("HTTPS://example.invalid/", WebLinks.browsable("HTTPS://example.invalid/"))
    }

    @Test
    fun `every other scheme is refused`() {
        for (url in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example.scanner;end",
            "huginn://answer?session=main&option=1&fp=0123456789ab&nonce=n",
            "market://details?id=com.example.app",
            "content://com.example.provider/secret",
            "file:///sdcard/Download/x.apk",
            "javascript:alert(1)",
            "tel:5550100",
            "sms:5550100",
            "mailto:someone@example.invalid",
            "https:example.invalid",
            "https://",
            "example.invalid/no-scheme",
            "",
        )) {
            assertNull("let through <$url>", WebLinks.browsable(url))
        }
        assertNull(WebLinks.browsable(null))
    }
}
