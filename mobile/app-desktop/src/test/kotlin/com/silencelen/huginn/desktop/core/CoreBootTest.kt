package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whether a run goes through huginn core, and the hand-over of this app's own
 * link to it (CoreBoot, docs/design/desktop-via-core.md). The rule that matters most:
 * this app's token is deleted ONLY after a request through the core worked.
 */
class CoreBootTest {
    private class FakeCore(
        var present: Boolean = true,
        var st: CoreStatus = CoreStatus(version = "2.5.1", paired = true, url = "https://192.168.7.117:8787", pins = listOf("aa11")),
        var reachable: Boolean = true,
        var pairFails: Boolean = false,
    ) : CoreBoot.Ops {
        val paired = mutableListOf<Pair<String, String>>()
        val added = mutableListOf<String>()
        override val socket: Path = Path.of("/run/user/1000/huginn/core.sock")
        override fun present() = present
        override suspend fun status() = st
        override suspend fun pair(url: String, token: String) {
            if (pairFails) error("the token was refused")
            paired += url to token
            st = st.copy(paired = true, url = url)
        }
        override suspend fun addRoute(url: String, name: String, kind: String) { added += url }
        override suspend fun appdThroughCore() = reachable
    }

    private val mesh = PinnedRoute(id = "r1", name = "yggdrasil", url = "https://192.168.2.117:8787", kind = RouteKind.MESH)
    private fun own(token: String = "tok", pins: List<String> = listOf("aa11")) =
        CoreBoot.OwnLink(baseUrl = "https://192.168.2.117:8787", token = token, routes = listOf(mesh), pins = pins)

    private fun decide(core: FakeCore, link: CoreBoot.OwnLink): Pair<CoreBoot.Outcome, Boolean> {
        var cleared = false
        val out = runBlocking { CoreBoot.decide(core, link) { cleared = true } }
        return out to cleared
    }

    @Test
    fun `no core or an old core keeps this app's own link and touches nothing`() {
        val (a, ca) = decide(FakeCore(present = false), own())
        assertIs<CoreBoot.Outcome.Direct>(a); assertFalse(ca)
        val (b, cb) = decide(FakeCore(st = CoreStatus(version = "2.5.0", paired = true, url = "https://h:8787")), own())
        assertIs<CoreBoot.Outcome.Direct>(b); assertFalse(cb)
        assertTrue((b as CoreBoot.Outcome.Direct).why.contains("older than ${CoreBoot.MIN_CORE}"))
    }

    @Test
    fun `a paired core and no link of this app's own goes through the core`() {
        val (o, cleared) = decide(FakeCore(), own(token = ""))
        assertIs<CoreBoot.Outcome.ViaCore>(o)
        assertFalse(cleared)
    }

    @Test
    fun `the same huginn on another address (PRESTIGE's LAN and mesh) is handed over, then deleted`() {
        val core = FakeCore()
        val (o, cleared) = decide(core, own())
        assertIs<CoreBoot.Outcome.ViaCore>(o)
        assertTrue(cleared, "the token goes once a request through the core worked")
        assertEquals(listOf(mesh.url), core.added, "this app's addresses join the core's book")
    }

    @Test
    fun `an unpaired core is paired with this app's link`() {
        val core = FakeCore(st = CoreStatus(version = "2.5.1", paired = false))
        val (o, cleared) = decide(core, own())
        assertIs<CoreBoot.Outcome.ViaCore>(o)
        assertEquals(listOf("https://192.168.2.117:8787" to "tok"), core.paired)
        assertTrue(cleared)
    }

    @Test
    fun `a hand-over that cannot be proved keeps this app's token`() {
        val (a, ca) = decide(FakeCore(reachable = false), own())
        assertIs<CoreBoot.Outcome.Direct>(a)
        assertFalse(ca, "nothing is deleted before a request through the core works")
        val (b, cb) = decide(FakeCore(st = CoreStatus(version = "2.5.1", paired = false), pairFails = true), own())
        assertIs<CoreBoot.Outcome.Direct>(b)
        assertFalse(cb)
    }

    @Test
    fun `a core paired with a different huginn is never migrated into`() {
        val core = FakeCore(st = CoreStatus(version = "2.5.1", paired = true, url = "https://10.0.0.5:8787", pins = listOf("bb22")))
        val (o, cleared) = decide(core, own())
        assertIs<CoreBoot.Outcome.Direct>(o)
        assertFalse(cleared)
        assertTrue(core.added.isEmpty() && core.paired.isEmpty())
    }
}
