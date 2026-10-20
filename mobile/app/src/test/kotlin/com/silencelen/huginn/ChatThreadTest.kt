package com.silencelen.huginn

import com.silencelen.huginn.notify.ChatThread
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ A FINISHED CHAT'S ANSWER GOES INTO ITS THREAD ONCE.
 *
 * The same finish reaches the phone by push and again by the next observation
 * (the push's own reconcile, or the watch stream, which usually sees it first).
 * Every other notice replaces itself on a second post; a chat is a MessagingStyle
 * thread that appends, so the shade showed the answer twice under one exchange.
 *
 * The rule is pure ([ChatThread]); the shade itself needs a notification manager
 * this build cannot run, so the wiring into `SessionWatchWorker.post` is held
 * against the source, as `PhoneRoundThreeTest` holds the shell's.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ChatThreadTest {

    private val huginn = ChatThread.HUGINN
    private val owner = "owner"

    @Test
    fun `the same finish arriving a second way is not appended again`() {
        assertTrue(ChatThread.repeatsLast(huginn, "answer B", "answer B"))
    }

    @Test
    fun `a new answer is appended`() {
        assertFalse(ChatThread.repeatsLast(huginn, "answer A", "answer B"))
    }

    @Test
    fun `the same words after your own reply are a new answer`() {
        // You asked again from the shade; the thread ends on YOUR line, so an
        // answer that happens to read like the last one is still news.
        assertFalse(ChatThread.repeatsLast(owner, "and again?", "answer A"))
        assertFalse(ChatThread.repeatsLast(owner, "answer A", "answer A"))
    }

    @Test
    fun `a thread that is not on the shade yet starts with the answer`() {
        assertFalse(ChatThread.repeatsLast(null, null, "answer A"))
    }

    /**
     * The ordinary order after a reply typed in the shade: the push posts the
     * answer, then the push's own reconcile sees the run counter move and posts
     * it again. The thread is modelled as the list of lines the shade would hold.
     */
    @Test
    fun `push then reconcile leaves one copy of the answer`() {
        val thread = mutableListOf(huginn to "answer A", owner to "follow-up typed in the shade")
        fun post(text: String) {
            val last = thread.lastOrNull()
            if (ChatThread.repeatsLast(last?.first, last?.second, text)) return
            thread += huginn to text
        }
        post("answer B")     // the push
        post("answer B")     // its reconcile, or the stream
        assertEquals(
            "the answer is in the thread twice",
            listOf(huginn to "answer A", owner to "follow-up typed in the shade", huginn to "answer B"),
            thread,
        )
    }

    // ------------------------------------------------------------- the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(rel: String): String {
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    private fun worker() = read("app/src/main/kotlin/com/silencelen/huginn/notify/SessionWatchWorker.kt")

    @Test
    fun `post asks the thread before appending huginn's line`() {
        val post = worker().substringAfter("fun post(").substringBefore("private fun requestCodeFor(")
        assertTrue("post read as ${post.length} chars — wrong slice", post.length in 2_000..20_000)
        val ask = post.indexOf("ChatThread.repeatsLast(")
        val append = post.indexOf("addMessage(text")
        assertTrue("post appends huginn's line without asking the thread", ask >= 0)
        assertTrue("the append is gone from post", append >= 0)
        assertTrue("post appends first and asks afterwards", ask < append)
    }

    @Test
    fun `huginn's lines carry the key the rule looks for`() {
        val huginnPerson = worker().substringAfter("fun huginn()").substringBefore("\n\n")
        assertTrue(
            "huginn() and ChatThread disagree about huginn's key",
            "setKey(ChatThread.HUGINN)" in huginnPerson,
        )
    }
}
