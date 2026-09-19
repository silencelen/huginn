package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop twin of the phone's RouteWitnessTest: a route's "last reached" must
 * be kept current by ORDINARY traffic (the status poll), not only by a probe.
 * Source-level, like CapBeforeFillTest, because AppStore needs a window.
 */
class RouteWitnessDesktopTest {
    private val store = File("src/main/kotlin/com/silencelen/huginn/desktop/AppStore.kt").readText()

    @Test
    fun `a successful status poll marks the active route as reached`() {
        val hits = Regex("""faults\.ok\(Faults\.STATUS\); routeFailures\.ok\(\); noteRouteReached\(\)""").findAll(store).count()
        assertTrue(hits >= 2, "both status-poll success branches must call noteRouteReached(); found $hits")
        assertTrue(store.contains("RouteResolver.touch("), "noteRouteReached must go through RouteResolver.touch (lastSeenAt, never lastOkAt)")
    }
}
