package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE ADD-A-ROUTE FIELD STARTS EMPTY.
 *
 * ⚠ IT USED TO OPEN PRE-FILLED WITH `HuginnSettings.DEFAULT_BASE_URL` — one
 * household's tailnet IP, compiled into a public repo. A fresh install anywhere
 * else began by asking the reader to delete somebody else's machine address
 * before typing their own, and an Add pressed without reading pinned a route to
 * a host that is not theirs. `AppdRoutes` says in as many words that these
 * literals are what `RouteResolver` refuses to adopt on its own; a form should
 * not adopt one either.
 *
 * The replacement is ghost text, and it has to be an address that cannot exist:
 * `.example.` is reserved by RFC 2606 and resolves nowhere, so a placeholder
 * somebody types over by accident cannot become a live route.
 */
class RoutePlaceholderTest {

    @Test
    fun `the placeholder is not anybody's real address`() {
        val p = HuginnSettings.ROUTE_URL_PLACEHOLDER
        assertTrue(p.contains(".example."), "RFC 2606 reserved, so it resolves nowhere: $p")
        assertFalse(
            Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(p),
            "a literal IP in a placeholder is the bug in a lighter font: $p",
        )
    }

    @Test
    fun `it still looks like the thing being asked for`() {
        val p = HuginnSettings.ROUTE_URL_PLACEHOLDER
        assertTrue(p.startsWith("https://") || p.startsWith("http://"), p)
        assertTrue(p.endsWith(":8787"), "the port is the half people forget: $p")
    }
}
