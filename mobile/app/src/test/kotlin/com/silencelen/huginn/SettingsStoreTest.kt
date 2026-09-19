package com.silencelen.huginn

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteHealth
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.lockEnabledOrLocked
import com.silencelen.huginn.data.orEmptyOnIoFailure
import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * What the settings store does when the device cannot read it.
 *
 * The store itself needs a Context; the two rules that turn an unreadable file
 * from a permanent crash-on-launch into a recoverable one do not. This source
 * set has no Robolectric, which is why they are stated as functions rather than
 * left inline in the reader.
 */
class SettingsStoreTest {

    @Test
    fun `an unparseable store reads as empty rather than throwing`() = runTest {
        // CorruptionException is what a garbage .preferences_pb produces, and it
        // extends IOException — which is why the catch, not the corruption
        // handler, is the load-bearing half.
        val flow = flow<String> { throw CorruptionException("bad magic") }
        assertEquals(listOf("empty"), flow.orEmptyOnIoFailure("empty").toList())
    }

    @Test
    fun `an unreadable store path reads as empty too`() = runTest {
        // With the path itself unreadable, stores with and without a
        // ReplaceFileCorruptionHandler both throw the identical
        // FileNotFoundException — the handler catches CorruptionException only.
        val flow = flow<String> { throw FileNotFoundException("/data/.../huginn_settings.preferences_pb") }
        assertEquals("empty", flow.orEmptyOnIoFailure("empty").first())
    }

    @Test
    fun `a good store is untouched`() = runTest {
        assertEquals(listOf("a", "b"), flowOf("a", "b").orEmptyOnIoFailure("empty").toList())
    }

    @Test
    fun `anything that is not an IO failure still throws`() = runTest {
        val flow = flow<String> { throw IllegalStateException("a programming error") }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { flow.orEmptyOnIoFailure("empty").first() }
        }
    }

    @Test
    fun `the startup lock read fails CLOSED`() = runTest {
        assertTrue("an unreadable store must lock, not open", lockEnabledOrLocked { throw IOException() })
        assertTrue(lockEnabledOrLocked { true })
        assertEquals(false, lockEnabledOrLocked { false })
    }
}

/**
 * What the route half of the store keeps across a restart.
 *
 * The store itself needs a Context and this source set has no Robolectric, so
 * the two mappings that decide what survives are extracted as pure companion
 * functions — the same shape as [orEmptyOnIoFailure] above and for the same
 * reason.
 */
class SettingsStoreRoutesTest {

    private fun book() = RouteBook()
        .add("Tailscale", "http://100.97.198.90:8787", now = 10)
        .add("Home", "http://192.168.2.117:8787", now = 20)

    @Test
    fun `the dropped-address notice survives a write and a read`() {
        // ⚠ THIS IS THE WHOLE POINT. The drop happens during the pre-3.x
        // migration — usually on the very first read, before any screen exists —
        // and the notice used to live only in the in-memory book, so the one
        // reader it was written for had already missed it by the time they
        // opened Settings to find out why the app stopped connecting.
        val dropped = book().copy(droppedUrl = "http://huginn.lan:8787")
        val p = mutablePreferencesOf()
        SettingsStore.putRouteBook(p, dropped)
        assertEquals("http://huginn.lan:8787", SettingsStore.routeBookFrom(p).droppedUrl)
    }

    @Test
    fun `a book with nothing dropped reads back with nothing dropped`() {
        // The empty string the write puts down is "no notice", not a notice
        // whose address happens to be blank.
        val p = mutablePreferencesOf()
        SettingsStore.putRouteBook(p, book())
        assertNull(SettingsStore.routeBookFrom(p).droppedUrl)
    }

    @Test
    fun `the whole book round-trips`() {
        val b = book().activate(book().routes[1].id).withAutoSwitch(false)
        val p = mutablePreferencesOf()
        SettingsStore.putRouteBook(p, b)
        val back = SettingsStore.routeBookFrom(p)
        assertEquals(b.routes, back.routes)
        assertEquals(b.activeId, back.activeId)
        assertEquals(false, back.autoSwitch)
    }

    @Test
    fun `route health round-trips, so a cold start does not re-sweep the book`() {
        // ⚠ AN EMPTY MAP IS NOT NEUTRAL. With nothing remembered every launch
        // skips RouteResolver's hysteresis, probes the whole book and takes the
        // first address that answers in the owner's order — which is how a
        // stranger on a route pinned above the real daemon wins on every start.
        val health = mapOf(
            "r-a" to RouteHealth(lastOkAt = 1_700_000_000_000, lastRttMs = 42),
            "r-b" to RouteHealth(lastFailAt = 1_600_000_000_000, lastSeenAt = 1_650_000_000_000),
        )
        val p = mutablePreferencesOf()
        SettingsStore.putRouteHealth(p, health, atMs = 1_700_000_000_000)
        assertEquals(health, SettingsStore.routeHealthFrom(p))
    }

    @Test
    fun `an unwritten health key reads as an empty map, never a throw`() {
        assertEquals(emptyMap<String, RouteHealth>(), SettingsStore.routeHealthFrom(mutablePreferencesOf()))
    }

    @Test
    fun `THE MIGRATION IS UNTOUCHED - an unwritten route list still rebuilds from base_url`() {
        // The pre-3.x store: a free-text base_url and the old pin flag, and no
        // `pinned_routes` key at all. That absence is the migration trigger, and
        // adding two keys beside it must not have moved it.
        val p = mutablePreferencesOf()
        p[stringPreferencesKey("base_url")] = "http://100.97.198.90:8787"
        p[booleanPreferencesKey("appd_route_pinned")] = true
        val migrated = SettingsStore.routeBookFrom(p)
        assertEquals(AppdRoutes.migrate("http://100.97.198.90:8787", true), migrated)
        assertEquals(false, migrated.autoSwitch)
        assertTrue("the built-ins are seeded", migrated.routes.isNotEmpty())
    }

    @Test
    fun `THE MIGRATION IS UNTOUCHED - a refused address is still carried back, not pinned`() {
        // The one row that is not behaviour-identical: a plain-http HOSTNAME the
        // old free-text field accepted. It must come back as a notice with NO
        // active route, and now it must still be there after a restart.
        val p = mutablePreferencesOf()
        p[stringPreferencesKey("base_url")] = "http://huginn.lan:8787"
        val migrated = SettingsStore.routeBookFrom(p)
        assertEquals("http://huginn.lan:8787", migrated.droppedUrl)
        assertNull(migrated.activeId)

        // ...and once it has been written down, the next read comes from the
        // book rather than from the migration, with the notice intact.
        val saved = mutablePreferencesOf()
        SettingsStore.putRouteBook(saved, migrated)
        assertEquals("http://huginn.lan:8787", SettingsStore.routeBookFrom(saved).droppedUrl)
    }
}
