package com.silencelen.huginn

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.RouteResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppdRoutesTest {

    @Test
    fun `normalize makes trailing slashes and whitespace irrelevant`() {
        val want = "http://100.97.198.90:8787"
        for (raw in listOf(want, "$want/", " $want ", "$want///")) {
            assertEquals(want, AppdRoutes.normalize(raw))
        }
    }

    @Test
    fun `known routes are labeled and anything else reads as custom`() {
        assertEquals("Tailscale", AppdRoutes.labelFor("http://100.97.198.90:8787/"))
        assertEquals("Yggdrasil", AppdRoutes.labelFor("http://192.168.2.117:8787"))
        assertEquals("Custom", AppdRoutes.labelFor("http://10.0.0.9:8787"))
        assertNull(AppdRoutes.match("http://10.0.0.9:8787"))
    }

    @Test
    fun `candidates put the current route first and never repeat it`() {
        val mesh = AppdRoutes.YGGDRASIL.url
        val c = AppdRoutes.candidates(mesh)
        assertEquals(AppdRoutes.normalize(mesh), c.first())
        assertEquals("no duplicates", c.size, c.distinct().size)
        assertTrue(c.contains(AppdRoutes.normalize(AppdRoutes.TAILSCALE.url)))
    }

    @Test
    fun `a custom current route is tried first but the defaults remain fallbacks`() {
        val custom = "http://10.0.0.9:8787"
        val c = AppdRoutes.candidates(custom)
        assertEquals(custom, c.first())
        assertEquals(3, c.size)
    }

    @Test
    fun `resolve returns the first reachable candidate and stops probing`() = runTest {
        val tried = mutableListOf<String>()
        val found = RouteResolver.resolve(listOf("a", "b", "c")) { url ->
            tried += url
            url == "b"
        }
        assertEquals("b", found)
        // "c" must never be probed once "b" answered.
        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun `resolve returns null when nothing answers so the caller keeps its setting`() = runTest {
        // The failure mode this guards: blanking the URL on a dead network turns
        // "no connectivity" into "app looks unconfigured".
        assertNull(RouteResolver.resolve(listOf("a", "b")) { false })
    }

    @Test
    fun `resolve on an empty candidate list is null rather than an exception`() = runTest {
        assertNull(RouteResolver.resolve(emptyList()) { true })
    }
}
