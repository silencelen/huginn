package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.ui.ChatListScroll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The desktop half of "the chat list lands on the latest chat".
 *
 * Two things can go wrong here and neither shows up in a screenshot. The rule is
 * keyed by NAME, so a rename of the `View` constant would turn the snap off
 * silently; and the state it scrolls has to be the shell's, because the rail's
 * `when (view)` disposes the list pane and a position remembered inside it is a
 * position nobody keeps.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ChatListSnapDesktopTest {

    @Test
    fun `the rail's chats destination is the one key the rule answers for`() {
        assertEquals(ChatListScroll.CHATS, viewKey(View.CHATS))
        for (v in View.entries) {
            if (v == View.CHATS) continue
            assertNotEquals(ChatListScroll.CHATS, viewKey(v), "$v must not read as the chat list")
        }
    }

    @Test
    fun `arriving on Chats from any other rail item snaps to the latest`() {
        for (v in View.entries) {
            if (v == View.CHATS) continue
            assertTrue(
                ChatListScroll.shouldSnap(viewKey(v), viewKey(View.CHATS)),
                "coming to Chats from $v must land on the newest chat",
            )
        }
    }

    @Test
    fun `staying on Chats moves nothing`() {
        // Opening a chat on this client changes the id BESIDE the list, not the
        // view — so drilling into a conversation cannot reach the rule at all,
        // and the position is kept for free. This is the assertion that says the
        // desktop's shape gives it the phone's second rule without asking.
        assertFalse(ChatListScroll.shouldSnap(viewKey(View.CHATS), viewKey(View.CHATS)))
        for (v in View.entries) {
            assertFalse(ChatListScroll.isChat(viewKey(v)), "$v reads as an open chat, which no View is")
        }
    }

    // ------------------------------------------------------------ the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(path: String): String {
        val f = File(root(), path)
        assertTrue(f.isFile, "not found: ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 5_000, "${f.name} read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `the shell owns the list's position and asks the rule about it`() {
        // A rule nothing calls is a comment. The pane must take the state as a
        // parameter, and the shell must both hold it and consult the rule.
        val shell = source("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt")
        assertTrue(shell.contains("ChatListScroll.shouldSnap("), "the shell never asks where it came from")
        assertTrue(shell.contains("rows = chatRows"), "the shell keeps a position the list does not read")
        val lists = source("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt")
        // SCOPED TO ChatsList. The sessions pane below it still remembers its own
        // position and is deliberately untouched — a gate that scanned the whole
        // file would either fail on that or pass on nothing.
        val chatsList = lists.substringAfter("fun ChatsList(").substringBefore("private fun ChatRow(")
        assertTrue(chatsList.length > 500, "ChatsList not found in Lists.kt — this gate is scanning nothing")
        assertTrue(chatsList.contains("rows: LazyListState"), "ChatsList takes no hoisted position")
        assertFalse(
            chatsList.contains("val rows = rememberLazyListState()"),
            "ChatsList holds a second position, which the rail disposes on every view change",
        )
    }
}
