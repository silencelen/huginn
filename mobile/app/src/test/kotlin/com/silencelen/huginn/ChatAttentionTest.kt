package com.silencelen.huginn

import com.silencelen.huginn.notify.ChatAttention
import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.SessionWatchWorker
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chat_attention`: a chat's Claude is waiting on a start_session approval.
 *
 * Until the phone knew the kind, the push fell through to the generic post —
 * the one slot every unknown kind shares, and a tap that opened nothing in
 * particular. What it must be instead: "needs you" on the blocking channel, a
 * tap that opens THAT chat, no reply box and no buttons (the card in the chat
 * is where Claude's prompt is shown whole), nothing while that chat is on
 * screen, and taken down once the chat is opened or has finished.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ChatAttentionTest {

    @After
    fun clearForeground() {
        Foreground.resumed = false
        Foreground.chat = null
        Foreground.session = null
    }

    private fun push(
        kind: String = ChatAttention.KIND,
        title: String = "Claude wants a new session",
        text: String = "in /srv/example — open the chat to read the prompt",
        subject: String? = "c-2f9a",
    ) = ChatAttention.fromPush(kind, title, text, subject)

    @Test
    fun `a chat waiting on you is a notice that opens that chat`() {
        val n = push()
        assertNotNull(n)
        assertEquals("c-2f9a", n!!.chatId)
        assertEquals("the daemon's own words are kept", "Claude wants a new session", n.title)
        assertEquals("in /srv/example — open the chat to read the prompt", n.text)
        val a = ChatAttention.postArgs(n)
        assertEquals("the tap opens THAT chat", "c-2f9a", a.openChat)
        assertEquals("its own slot", "chat-ask:c-2f9a", a.key)
    }

    /**
     * The doors held shut. A reply box is free text into Claude from the shade;
     * answer buttons would approve a prompt nobody has read; a session would
     * route the tap to the wrong place; `isResult` would file a chat that has
     * STOPPED under "Finished", where silencing news silences it too.
     */
    @Test
    fun `no reply box, no buttons, and the blocking channel`() {
        val a = ChatAttention.postArgs(push()!!)
        assertNull("free text must not be reachable from the shade", a.replyChat)
        assertTrue("no button may answer it", a.answers.isEmpty())
        assertNull(a.fingerprint)
        assertNull("the tap opens the chat, not a session", a.session)
        assertFalse(a.isResult)
        assertEquals(
            "needs you, not Finished",
            SessionWatchWorker.CHANNEL,
            SessionWatchWorker.channelFor(a.replyChat, a.isResult, proposal = false),
        )
    }

    @Test
    fun `its slot is neither the chat's finished thread nor any session's`() {
        // Unrouted, the kind fell into the generic slot every unknown push shares.
        val key = ChatAttention.postArgs(push()!!).key
        assertEquals(ChatAttention.keyFor("c-2f9a"), key)
        assertNotEquals("the finished thread keeps its own slot", "chat:c-2f9a", key)
        assertNotEquals(
            SessionWatchWorker.notificationIdFor("chat:c-2f9a"),
            SessionWatchWorker.notificationIdFor(key),
        )
        // A tmux session name cannot hold ':', so no session can hash here.
        assertTrue(key.contains(':'))
    }

    @Test
    fun `nothing is posted while that chat is on screen`() {
        val n = push()!!
        Foreground.resumed = true
        Foreground.chat = "c-2f9a"
        assertFalse("the card is in front of the reader already", ChatAttention.shows(n))
        // Another chat on screen, or the phone pocketed with it open: post.
        Foreground.chat = "c-other"
        assertTrue(ChatAttention.shows(n))
        Foreground.chat = "c-2f9a"
        Foreground.resumed = false
        assertTrue("open but nobody looking", ChatAttention.shows(n))
    }

    @Test
    fun `a finished chat takes its needs-you down, and nothing else does`() {
        assertEquals("chat-ask:c-2f9a", ChatAttention.withdraws(ChatAttention.KIND_FINISHED, "c-2f9a"))
        assertNull(ChatAttention.withdraws(ChatAttention.KIND_FINISHED, null))
        assertNull(ChatAttention.withdraws("session_finished", "c-2f9a"))
        assertNull(ChatAttention.withdraws(ChatAttention.KIND, "c-2f9a"))
    }

    @Test
    fun `other kinds are left alone, and one with no chat is refused`() {
        assertNull(push(kind = "chat_finished"))
        assertNull(push(kind = "session_attention"))
        assertNull(push(kind = "project_change"))
        assertNull("a tap that could open nothing", push(subject = null))
        assertNull(push(subject = "  "))
        // A silent push still says something.
        val bare = push(title = "", text = "")!!
        assertTrue(bare.title.isNotBlank())
        assertTrue(bare.text.isNotBlank())
    }

    // ------------------------------------------------------------ the wiring

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `the messaging service routes the kind and takes it down on a finish`() {
        val text = source("notify/HuginnMessagingService.kt")
        assertTrue("chat_attention is not routed", text.contains("ChatAttention.fromPush("))
        assertTrue("it is posted while its chat is on screen", text.contains("ChatAttention.shows("))
        assertTrue("its tap does not open the chat", text.contains("openChat = a.openChat"))
        assertTrue(
            "the generic post draws it as well",
            Regex("""val redundant = [^\n]*chatAsk != null""").containsMatchIn(text),
        )
        assertTrue("a finished chat leaves its needs-you up", text.contains("ChatAttention.withdraws("))
    }

    @Test
    fun `opening the chat, or the watch seeing it finish, takes it down`() {
        assertTrue(
            "read = dismissed leaves the chat's needs-you up",
            source("MainActivity.kt").contains("ChatAttention.keyFor(d.id)"),
        )
        assertTrue(
            "a finish the push never delivered leaves it up",
            source("notify/WatchNotifier.kt").contains("ChatAttention.keyFor(id)"),
        )
        val post = source("notify/SessionWatchWorker.kt")
        assertTrue("post() cannot open a chat without a reply box", post.contains("openChat: String? = null"))
        assertTrue("the chat is not put on the tap", post.contains("(replyChat ?: openChat)?.let { putExtra(EXTRA_CHAT, it) }"))
    }
}
