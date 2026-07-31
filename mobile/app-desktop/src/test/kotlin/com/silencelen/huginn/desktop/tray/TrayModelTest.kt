package com.silencelen.huginn.desktop.tray

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchChat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the tray says, tested where it is decided rather than where it is drawn.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`,
 * the REVERSE of JUnit's.
 */
class TrayModelTest {

    private fun watch(
        sessions: Map<String, String?> = emptyMap(),
        chats: Map<String, WatchChat> = emptyMap(),
    ) = Watch(hash = "h", sessions = sessions, chats = chats)

    @Test
    fun `nothing happening reads as idle`() {
        val s = TrayModel.summarize(watch(sessions = mapOf("a" to "idle")))
        assertEquals(TrayState.IDLE, s.state)
        assertEquals("Huginn — idle", s.tooltip)
    }

    @Test
    fun `no digest at all is distinguishable from a quiet one`() {
        // "Not connected" and "nothing is happening" look identical on a 16px dot,
        // so the tooltip is where they are told apart.
        assertEquals("Huginn — not connected", TrayModel.summarize(null).tooltip)
    }

    @Test
    fun `work in a session or a chat reads as working`() {
        val s = TrayModel.summarize(
            watch(sessions = mapOf("a" to "running"), chats = mapOf("c" to WatchChat(running = true)))
        )
        assertEquals(TrayState.WORKING, s.state)
        assertEquals(2, s.working)
        assertEquals("Huginn — 2 working", s.tooltip)
    }

    @Test
    fun `ATTENTION OUTRANKS WORKING`() {
        // A machine that is busy is ordinary; a machine that is BLOCKED on the
        // owner is the only thing the tray icon has to be able to say from across
        // a room, so it wins the colour outright.
        val s = TrayModel.summarize(
            watch(
                sessions = mapOf("a" to "attention", "b" to "running"),
                chats = mapOf("c" to WatchChat(running = true)),
            )
        )
        assertEquals(TrayState.ATTENTION, s.state)
        assertEquals(listOf("a"), s.attention)
        assertEquals("Huginn — 1 needs you · 2 working", s.tooltip)
    }

    @Test
    fun `the plural is right, because a tooltip that reads wrong reads as broken`() {
        val s = TrayModel.summarize(watch(sessions = mapOf("a" to "attention", "b" to "attention")))
        assertEquals("Huginn — 2 need you", s.tooltip)
    }

    @Test
    fun `the menu list is capped`() {
        val many = (1..12).associate { "s$it" to "attention" }
        val s = TrayModel.summarize(watch(sessions = many))
        assertEquals(TrayModel.MENU_ATTENTION_CAP, s.attention.size)
        // The COUNT still tells the truth even though the list is trimmed.
        assertEquals("Huginn — 12 need you", s.tooltip)
    }
}
