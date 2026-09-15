package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.DraftBook
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where text staged from outside a composer actually lands.
 *
 * Both rules below are invisible when they are wrong. A stage that CLOBBERS looks
 * exactly like a stage that worked — right up until the half-typed message that
 * was in the box is gone, and there is no undo for a draft. And "Ask in new chat"
 * putting its text in the chat you were READING rather than the one it just made
 * is a message sent into the wrong conversation the next time Enter is pressed.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppStoreStagingTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-store-test").toFile()
        dirs += dir
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        // Never started: nothing in this suite is allowed to reach a daemon, and
        // a store whose poll loop found the owner's live one would be writing into
        // his drafts. Chat creation arrives through the `create` seam instead.
        return AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `staging into a composer appends and never clobbers what was typed`() {
        val s = store()
        val key = DraftBook.chatKey("c1")
        s.drafts.set(key, "half a thought")
        s.appendToDraft(key, "> alpha")
        assertEquals("half a thought\n\n> alpha", s.drafts[key])

        // And into an empty composer it is the addition alone — no leading blank
        // line to delete before typing.
        val empty = DraftBook.chatKey("c2")
        s.appendToDraft(empty, "> alpha")
        assertEquals("> alpha", s.drafts[empty])
    }

    @Test
    fun `Ask in new chat stages into the NEW chat's draft, not the one being read`() = runBlocking {
        val s = store()
        val here = DraftBook.chatKey("c1")
        s.drafts.set(here, "half a thought")

        s.askInNewChat("boom\n\nWhat is going on here?", mode = "ask", fallbackKey = here) {
            Chat(id = "made-1", title = null, mode = "ask", running = false, pending = 0, turns = 0, updatedAt = 0)
        }

        assertEquals("boom\n\nWhat is going on here?", s.drafts[DraftBook.chatKey("made-1")])
        assertEquals("half a thought", s.drafts[here], "the chat being read keeps its own draft")
        assertEquals("made-1", s.chatId.value, "and the view follows the text")
        assertEquals(View.CHATS, s.view.value)
    }

    @Test
    fun `a chat that could not be made leaves the text where the reader is, and says why`() {
        val s = store()
        val here = DraftBook.chatKey("c1")
        runBlocking {
            s.askInNewChat("boom", mode = "ask", fallbackKey = here) {
                throw IllegalStateException("daemon said no")
            }
        }
        val draft = s.drafts[here]
        assertTrue(draft.startsWith("boom"), draft)
        assertTrue("could not open a new chat" in draft, draft)
        assertTrue("daemon said no" in draft, draft)
    }
}
