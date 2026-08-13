package com.silencelen.huginn

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchChat
import com.silencelen.huginn.notify.Fleet
import com.silencelen.huginn.notify.FleetSession
import com.silencelen.huginn.notify.FleetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The widget's view of the fleet. The ranking is a safety property in miniature:
 * a session waiting on a person must be the first thing a glance lands on, and
 * the order must hold still between observations — a list that reshuffles is a
 * list nobody can tap accurately.
 */
class FleetTest {

    private fun watch(vararg sessions: Pair<String, String?>) =
        Watch(sessions = sessions.toMap())

    @Test
    fun `attention outranks running outranks quiet`() {
        val snap = Fleet.snapshot(
            watch("build" to "running", "shell" to null, "review" to "attention", "docs" to "idle"),
            atMs = 5L,
        )
        assertEquals(listOf("review", "build", "docs", "shell"), snap.sessions.map { it.name })
    }

    @Test
    fun `alphabetical within a band, case-insensitively, so order is stable`() {
        val snap = Fleet.snapshot(
            watch("Zeta" to "running", "alpha" to "running", "Mid" to "running"),
            atMs = 0L,
        )
        assertEquals(listOf("alpha", "Mid", "Zeta"), snap.sessions.map { it.name })
    }

    @Test
    fun `counts split the fleet without overlap`() {
        val snap = Fleet.snapshot(
            watch("a" to "attention", "b" to "running", "c" to "running", "d" to "idle", "e" to null),
            atMs = 0L,
        )
        assertEquals(1, snap.attention)
        assertEquals(2, snap.running)
        assertEquals(2, snap.quiet)
    }

    @Test
    fun `chats running counted from the same observation`() {
        val snap = Fleet.snapshot(
            Watch(chats = mapOf("c1" to WatchChat(running = true), "c2" to WatchChat(running = false))),
            atMs = 0L,
        )
        assertEquals(1, snap.chatsRunning)
    }

    @Test
    fun `round trip survives the codec`() {
        val snap = FleetSnapshot(
            sessions = listOf(FleetSession("review", "attention"), FleetSession("shell", null)),
            chatsRunning = 3,
            asOf = 1_723_400_000_000L,
        )
        assertEquals(snap, Fleet.decode(Fleet.encode(snap)))
    }

    @Test
    fun `unknown fields in a stored snapshot are ignored, not fatal`() {
        val decoded = Fleet.decode(
            """{"sessions":[{"name":"x","state":"running","later":1}],"chatsRunning":0,"asOf":9,"future":true}"""
        )
        assertEquals(FleetSnapshot(listOf(FleetSession("x", "running")), 0, 9), decoded)
    }

    @Test
    fun `absent or garbled cache reads as no data, never a crash`() {
        assertNull(Fleet.decode(null))
        assertNull(Fleet.decode(""))
        assertNull(Fleet.decode("{half a json"))
    }
}
