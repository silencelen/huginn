package com.silencelen.huginn

import com.silencelen.huginn.ui.chatEmptyCopy
import com.silencelen.huginn.ui.chatMessagesGone
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An old chat opening EMPTY under the new-chat placeholder, with its last answer
 * still quoted in the list behind it. Caught on the owner's Fold, 2026-09-18.
 *
 * The daemon answers 409 for two different facts — "chat has not run yet" and
 * "transcript not found for this chat" — and the client read both as the first.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ChatEmptyTest {

    @Test
    fun `a chat that ran and has no transcript left has lost its messages`() {
        assertTrue(chatMessagesGone(hasRun = true, transcriptRefused = true))
    }

    @Test
    fun `a chat that never ran is empty, not bereaved`() {
        assertTrue(!chatMessagesGone(hasRun = false, transcriptRefused = true))
    }

    @Test
    fun `a chat mid-first-send is not missing anything`() {
        // ⚠ THE REGRESSION THIS RULE EXISTS TO AVOID. `started` goes true the
        // moment a message is sent; if that alone decided it, an opening message
        // would be met with "your messages are gone".
        assertTrue(!chatMessagesGone(hasRun = true, transcriptRefused = false))
    }

    @Test
    fun `the lost-messages screen says so, and says where the last line is`() {
        val copy = chatEmptyCopy(messagesGone = true, mode = "ask")
        assertEquals(
            "This chat's messages are no longer on the host",
            copy.title,
        )
        assertTrue(
            "the reader has just come from a row that quoted the answer: say where it went",
            copy.body.contains("swept") && copy.body.contains("list"),
        )
    }

    @Test
    fun `a genuinely new chat still introduces its mode`() {
        assertEquals("Ask mode", chatEmptyCopy(messagesGone = false, mode = "ask").title)
        assertEquals("Act mode", chatEmptyCopy(messagesGone = false, mode = "act").title)
        assertEquals(
            "Runs on the host with tools: files, commands, the web.",
            chatEmptyCopy(messagesGone = false, mode = "act").body,
        )
        assertEquals(
            "Reasoning and memory, no tools.",
            chatEmptyCopy(messagesGone = false, mode = "ask").body,
        )
    }

    @Test
    fun `the chat screen asks for the copy rather than hard-coding a placeholder`() {
        // The rule is only worth having if the screen consults it. The empty
        // branch used to carry the two mode strings inline, which is how one of
        // them came to be shown for a chat that had run for months.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/ChatScreen.kt")
        assertTrue("ChatScreen.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("ChatScreen.kt read as ${text.length} chars — wrong file", text.length > 5_000)
        assertTrue("the empty state does not consult chatEmptyCopy", text.contains("chatEmptyCopy("))
        assertTrue(
            "the mode placeholder is hard-coded in the screen again",
            !text.contains("\"Reasoning and memory, no tools.\""),
        )
    }

    @Test
    fun `a lost chat says the same thing whichever mode it was`() {
        // The mode is what a NEW chat is about to do; it says nothing about a
        // conversation that has already happened and gone.
        assertEquals(
            chatEmptyCopy(messagesGone = true, mode = "ask"),
            chatEmptyCopy(messagesGone = true, mode = "act"),
        )
    }
}
