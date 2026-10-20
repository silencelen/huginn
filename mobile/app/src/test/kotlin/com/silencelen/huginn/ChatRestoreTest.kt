package com.silencelen.huginn

import com.silencelen.huginn.ui.chatOpenWanted
import com.silencelen.huginn.ui.detachWanted
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ A CHAT SCREEN THAT COMES BACK WITHOUT A TAP STILL LOADS ITS CHAT.
 *
 * `dest` is saveable, so the chat screen returns on its own after the system
 * kills the backgrounded process (onto a NEW view model that has opened
 * nothing) and after a fold or rotate (whose teardown detaches the chat on the
 * way out). Every call that loads a chat sat in a tap's callback, so both drew
 * the spinner forever, with the default mode and model, a sealed Round run
 * offering a composer the daemon refuses, and a running answer never
 * reattached. And a restored activity re-read its launch intent, which after a
 * process kill carries the original notification's extras again.
 *
 * The open/teardown pair is pure and asserted as such; the two places the shell
 * uses it are held against the source, as `PhoneRoundThreeTest` holds the rest
 * of the shell.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ChatRestoreTest {

    @Test
    fun `a chat screen restored onto a fresh view model loads its chat`() {
        assertTrue("the restored screen waits for a load nothing will start", chatOpenWanted("chat-C", null))
    }

    @Test
    fun `a chat screen reached by a tap finds the chat already open`() {
        assertFalse("the tap's load would be thrown away and done again", chatOpenWanted("chat-C", "chat-C"))
    }

    @Test
    fun `a fold or rotate detaches the chat, and the rebuilt screen loads it again`() {
        var openNow: String? = "chat-C"
        // The outgoing screen's teardown: the same chat, so it detaches.
        if (detachWanted("chat-C", openNow)) openNow = null
        assertEquals(null, openNow)
        assertTrue("the rebuilt screen is left on the spinner", chatOpenWanted("chat-C", openNow))
    }

    @Test
    fun `a hop between chats loads the new one once`() {
        // The tap opens B before the screen for A is torn down.
        val openNow = "chat-B"
        assertFalse("A's teardown blanked B", detachWanted("chat-A", openNow))
        assertFalse("B's screen loads B a second time", chatOpenWanted("chat-B", openNow))
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

    private fun mainActivity() = read("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")

    private fun slice(text: String, from: String, to: String, max: Int): String {
        val s = text.substringAfter(from, "").substringBefore(to, "")
        assertTrue("'$from' read as ${s.length} chars — wrong slice", s.length in 1..max)
        return s
    }

    @Test
    fun `the chat screen opens its chat on entry`() {
        val detail = slice(mainActivity(), "val chatDetail: @Composable (String) -> Unit = { id ->", "ChatScreen(", 6_000)
        assertTrue(
            "the chat screen loads nothing unless a tap already did",
            "LaunchedEffect(id) { vm.ensureChatOpen(id) }" in detail,
        )
    }

    @Test
    fun `the view model's entry load is the guarded one`() {
        val vm = read("app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        val ensure = slice(vm, "fun ensureChatOpen(id: String)", "\n    }", 300)
        assertTrue("an entry load that ignores what is already open", "chatOpenWanted(id, openChatId)" in ensure)
        assertTrue("an entry load that loads nothing", "openChat(id)" in ensure)
    }

    @Test
    fun `a restored activity does not read its launch intent again`() {
        val create = slice(mainActivity(), "override fun onCreate(savedInstanceState: Bundle?)", "setContent {", 6_000)
        assertTrue(
            "a restored activity re-reads the launch intent, which a process kill hands back unstripped",
            "if (savedInstanceState == null) readTarget(intent)" in create,
        )
        assertEquals("the launch intent is read twice", 1, Regex("""readTarget\(intent\)""").findAll(create).count())
    }
}
