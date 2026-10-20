package com.silencelen.huginn

import com.silencelen.huginn.ui.AttachmentSlots
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.SendPresses
import com.silencelen.huginn.ui.composeMessage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ ONE TAP OF SEND IS ONE MESSAGE, EVEN WHILE A PHOTO IS STILL UPLOADING.
 *
 * A send waits for its composer's uploads before the message is composed. It
 * used to wait with the text still in the box and nothing marked, so a second
 * tap (made because the first seemed to do nothing) sent the same words again
 * when the photo landed: "commit and push" with the photo, then "commit and
 * push" alone, and Claude acted on it twice. Words typed during the wait were
 * wiped unsent.
 *
 * [SendPresses] is driven here the way the view model drives it: a tap hands
 * over the box's text at that moment, and the send takes the composer's
 * attachments and composes the message as `sendNow` / `sendTextNow` do. The view
 * model itself needs an `Application`, so its two doors are held against the
 * source, as `PhoneRoundThreeTest` holds the shell's.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class SendPressesTest {

    private class Composer(val slots: AttachmentSlots, val presses: SendPresses, val owner: String) {
        var box = ""
        val sent = mutableListOf<String>()

        fun tap(scope: CoroutineScope) {
            val text = box
            presses.press(scope, owner, clearDraft = { box = "" }) {
                val taken = slots.take(owner)
                val message = composeMessage(text, taken.markers)
                if (message.isNotBlank()) sent += message
            }
        }
    }

    private val path = "/srv/uploads/photo-1.jpg"

    @Test
    fun `the box empties at the tap, not when the uploads land`() = runTest {
        val slots = AttachmentSlots()
        val c = Composer(slots, SendPresses(slots), "sess:main")
        slots.stage(c.owner, "Photo", image = true)
        c.box = "commit and push"
        c.tap(this)
        runCurrent()
        assertEquals("the words sat in the box for the whole upload", "", c.box)
        assertTrue("it went before the photo it was sent with", c.sent.isEmpty())
    }

    @Test
    fun `a second tap while the photo uploads does not send the words twice`() = runTest {
        val slots = AttachmentSlots()
        val c = Composer(slots, SendPresses(slots), "chat:c1")
        val photo = slots.stage(c.owner, "Photo", image = true)!!
        c.box = "commit and push"
        c.tap(this)
        runCurrent()
        c.tap(this)      // nothing seemed to happen, so tap again
        runCurrent()
        slots.ready(photo, path, name = null, image = true, readable = true, bytes = 1L)
        advanceUntilIdle()
        assertEquals(
            "one tap's message, with its photo, once",
            listOf(composeMessage("commit and push", listOf(AttachmentText.marker(path)))),
            c.sent,
        )
    }

    @Test
    fun `words typed while a send waits are kept for the next message`() = runTest {
        val slots = AttachmentSlots()
        val c = Composer(slots, SendPresses(slots), "sess:main")
        val photo = slots.stage(c.owner, "Photo", image = true)!!
        c.box = "commit and push"
        c.tap(this)
        runCurrent()
        c.box += "and then deploy"
        slots.ready(photo, path, name = null, image = true, readable = true, bytes = 1L)
        advanceUntilIdle()
        assertEquals("the typing was wiped when the first send went", "and then deploy", c.box)
        assertEquals(1, c.sent.size)
    }

    @Test
    fun `a composer with nothing uploading is not held up by another that is`() = runTest {
        val slots = AttachmentSlots()
        val presses = SendPresses(slots)
        val busy = Composer(slots, presses, "chat:c1")
        val idle = Composer(slots, presses, "chat:c2")
        slots.stage(busy.owner, "Photo", image = true)
        busy.box = "about this photo"
        busy.tap(this)
        idle.box = "unrelated question"
        idle.tap(this)
        runCurrent()
        assertEquals(listOf("unrelated question"), idle.sent)
        assertTrue(busy.sent.isEmpty())
    }

    // ------------------------------------------------------------- the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun viewModel(): String {
        val f = File(root(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        assertTrue("HuginnViewModel.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("HuginnViewModel.kt read as ${text.length} chars — wrong file", text.length > 100_000)
        return text
    }

    private fun slice(text: String, from: String, to: String, max: Int): String {
        val s = text.substringAfter(from, "").substringBefore(to, "")
        assertTrue("'$from' read as ${s.length} chars — wrong slice", s.length in 1..max)
        return s
    }

    @Test
    fun `both of the view model's send doors go through the press`() {
        val vm = viewModel()
        val chat = slice(vm, "fun send(id: String, text: String)", "private fun sendNow(", 1_500)
        val session = slice(vm, "fun sendText(name: String", "private fun sendTextNow(", 1_500)
        assertTrue("a chat send waits with the text still in the box", "presses.press(" in chat)
        assertTrue("a session send waits with the text still in the box", "presses.press(" in session)
    }

    @Test
    fun `nothing empties the box again after the wait`() {
        val vm = viewModel()
        val chat = slice(vm, "private fun sendNow(", "fun interruptSession(", 6_000)
        val session = slice(vm, "private fun sendTextNow(", "the send queue", 6_000)
        assertFalse("sendNow clears the box after the uploads, wiping newer typing", "clearDraft(" in chat)
        assertFalse("sendTextNow clears the box after the uploads, wiping newer typing", "clearDraft(" in session)
    }

    @Test
    fun `a refused queue puts the message back`() {
        val queued = slice(viewModel(), "client.queueMessage(id, text", "return\n", 1_500)
        val refused = queued.substringAfter(".onFailure", "")
        assertTrue(
            "a queue refusal keeps nothing: the box was emptied at the press",
            "restoreDraft(chatDraftKey(id), text)" in refused,
        )
    }
}
