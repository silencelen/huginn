package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.ui.chatEmptyCopy
import com.silencelen.huginn.ui.chatMessagesGone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AN OLD CHAT THAT OPENS EMPTY IS NOT A NEW CHAT.
 *
 * ⚠⚠ THE DAEMON ANSWERS 409 FOR TWO DIFFERENT FACTS: "this chat has not run yet"
 * and "transcript not found for this chat" — the second being Claude Code having
 * swept its own JSONL on its own schedule. `ChatController.loadTranscript` read
 * both as the first (`val neverRan = … code == 409`) and seeded an empty page,
 * so a chat whose list row still quotes its last answer opened under the
 * brand-new-chat placeholder: "Ask mode · Reasoning and memory, no tools." The
 * screen told the reader a conversation they can see quoted two panes away never
 * happened.
 *
 * The rule is the phone's, moved to `:core` rather than written twice — one
 * daemon behaviour, one answer, both clients. GONE needs BOTH facts: the chat
 * ran, AND the transcript route refused. `started` alone would greet somebody's
 * opening message with "its history is missing", because a first send sets it
 * before a single token comes back.
 */
class ChatEmptyDesktopTest {

    @Test
    fun `a chat that never ran is an absence, not a loss`() {
        assertTrue(!chatMessagesGone(hasRun = false, transcriptRefused = true))
        assertEquals("Ask mode", chatEmptyCopy(messagesGone = false, mode = "ask").title)
        assertEquals("Act mode", chatEmptyCopy(messagesGone = false, mode = "act").title)
    }

    @Test
    fun `a chat that ran and has no transcript says its messages are gone`() {
        assertTrue(chatMessagesGone(hasRun = true, transcriptRefused = true))
        val copy = chatEmptyCopy(messagesGone = true, mode = "ask")
        assertEquals("This chat's messages are no longer on the host", copy.title)
        assertTrue(copy.body.contains("swept"), copy.body)
        // The mode is irrelevant once the messages are gone: what the chat COULD
        // have done is not the fact the reader needs.
        assertEquals(copy, chatEmptyCopy(messagesGone = true, mode = "act"))
    }

    // --------------------------------------------------------------- wiring
    //
    // The rule is pure and tested above; what a source scan adds is that this
    // client actually consults it. Both halves have to be there — a controller
    // that computes the flag and a view that still hardcodes "Reasoning and
    // memory, no tools" is the same screen it was.

    private fun source(rel: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, rel)
        assertTrue(f.isFile, "$rel not found at ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 5_000, "$rel read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `the controller decides which of the two 409s it got`() {
        val text = source("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ChatController.kt")
        assertTrue(
            text.contains("chatMessagesGone("),
            "a 409 read as 'nothing here yet' is the bug; the controller has to ask which 409 this is",
        )
    }

    @Test
    fun `the view draws the answer rather than a hardcoded placeholder`() {
        val text = source("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/ChatView.kt")
        assertTrue(text.contains("chatEmptyCopy("), "the empty state has two meanings and one drawing")
        assertTrue(
            !text.contains("\"Reasoning and memory, no tools.\""),
            "the new-chat copy is the shared rule's to write, not this view's to repeat",
        )
    }
}
