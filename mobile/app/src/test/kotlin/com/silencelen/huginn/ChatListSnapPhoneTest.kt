package com.silencelen.huginn

import com.silencelen.huginn.ui.ChatListScroll
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone half of "the chat list lands on the latest chat".
 *
 * The RULE is asserted in `:core` (ChatListScrollTest); what this pins is that
 * the phone actually asks it, and — the part that is easy to undo without
 * noticing — that the list's position is held by the SHELL rather than by
 * ChatsScreen.
 *
 * ⚠ WHY THE STATE CANNOT LIVE IN ChatsScreen. Narrow, `Dest.Chat` REPLACES the
 * list, so the screen is disposed on the way into a conversation and a position
 * remembered inside it is gone before the reader presses back. Putting it there
 * would leave the snap working and the keep-my-place half silently doing
 * nothing — which looks exactly like the behaviour that was asked for, from a
 * screenshot.
 *
 * The transitions themselves come from `destToKey`, which is already round-
 * tripped by DestSaverTest; the two are checked against each other here so the
 * rule's idea of "the chat list" and the shell's cannot drift.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ChatListSnapPhoneTest {

    @Test
    fun `the shell's destination keys are the ones the rule answers for`() {
        assertEquals(ChatListScroll.CHATS, destToKey(Dest.Chats))
        assertTrue(
            "an open chat must read as a chat, not as the list",
            ChatListScroll.isChat(destToKey(Dest.Chat("fc322e02-cf53-4b09-a28d-6fc291cd66fa"))),
        )
        assertFalse("the list is not an open chat", ChatListScroll.isChat(destToKey(Dest.Chats)))
    }

    @Test
    fun `every other tab snaps, and only the chat does not`() {
        val chats = destToKey(Dest.Chats)
        for (d in listOf(Dest.Sessions, Dest.Status, Dest.Rounds, Dest.Settings, Dest.Projects, Dest.Scratchpads)) {
            assertTrue(
                "coming to Chats from $d must land on the newest chat",
                ChatListScroll.shouldSnap(destToKey(d), chats),
            )
        }
        assertFalse(
            "drilling back out of a chat must keep the reader's place",
            ChatListScroll.shouldSnap(destToKey(Dest.Chat("abc-123")), chats),
        )
    }

    // ------------------------------------------------------------ the wiring

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(path: String): String {
        val f = File(mobileRoot(), path)
        assertTrue("not found: ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("${f.name} read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `the shell holds the position and consults the rule`() {
        val main = source("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        assertTrue("the shell never asks where it came from", main.contains("ChatListScroll.shouldSnap("))
        assertTrue("the shell holds no list position", main.contains("rememberLazyListState()"))
        assertTrue("the list is never handed the shell's position", main.contains("listState = chatsListState"))
    }

    @Test
    fun `the list reads the position it is handed rather than one of its own`() {
        val screen = source("app/src/main/kotlin/com/silencelen/huginn/ui/ChatsScreen.kt")
        assertTrue("ChatsScreen takes no hoisted position", screen.contains("listState: LazyListState"))
        assertTrue(
            "the LazyColumn ignores the hoisted position, so nothing the shell does reaches it",
            screen.contains("state = listState"),
        )
    }
}
