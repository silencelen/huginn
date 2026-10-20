package com.silencelen.huginn

import com.silencelen.huginn.ui.AttachmentSlots
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.SendPresses
import com.silencelen.huginn.ui.UnsettledSends
import com.silencelen.huginn.ui.composeMessage
import com.silencelen.huginn.ui.foldBack
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ A MESSAGE THAT HAS LEFT THE BOX IS KEPT UNTIL THE HOST TAKES IT.
 *
 * The box empties at the tap, and a send can then wait up to twenty seconds for
 * its uploads before it reaches the host. An app closed out in that time (or
 * before the host answered) took the words with it. They are now folded into the
 * drafts the store keeps from the tap until the host takes them, so a cold start
 * or a restore finds them back in the composer, after anything typed since,
 * exactly where a refusal would have put them. Nothing is sent again on its own.
 *
 * The rules are [UnsettledSends], asserted here as a plain class; the view model
 * that drives it needs an `Application`, so its wiring is held against the
 * source, as `PhoneRoundThreeTest` holds the shell's.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class UnsettledSendsTest {

    private val owner = "chat:c1"
    private val path = "/srv/uploads/photo-1.jpg"
    private val composed = composeMessage("commit and push", listOf(AttachmentText.marker(path)))

    @Test
    fun `a message that has left the box is still in the stored drafts`() {
        val sends = UnsettledSends()
        sends.open(owner, "commit and push")
        assertEquals(
            "the box is empty and the words exist nowhere the store keeps",
            mapOf(owner to "commit and push"),
            sends.persisted(emptyMap()),
        )
    }

    @Test
    fun `once composed it is kept with its photo markers, as a refusal restores it`() {
        val sends = UnsettledSends()
        val ticket = sends.open(owner, "commit and push")
        sends.update(ticket, composed)
        assertEquals(mapOf(owner to composed), sends.persisted(emptyMap()))
    }

    @Test
    fun `taken by the host, it leaves the stored drafts and the box is as typed`() {
        val sends = UnsettledSends()
        val ticket = sends.open(owner, composed)
        sends.settle(ticket)
        assertEquals(mapOf(owner to "and then deploy"), sends.persisted(mapOf(owner to "and then deploy")))
        assertEquals(emptyMap<String, String>(), sends.persisted(emptyMap()))
    }

    @Test
    fun `a cold start finds it after what was typed since, where a refusal would put it`() {
        val sends = UnsettledSends()
        sends.open(owner, "commit and push")
        val stored = sends.persisted(mapOf(owner to "and then deploy"))
        assertEquals("and then deploy\ncommit and push", stored[owner])
        assertEquals("the restore and the refusal read differently", foldBack("and then deploy", "commit and push"), stored[owner])
    }

    @Test
    fun `two sends from one box both come back, in the order they were sent`() {
        val sends = UnsettledSends()
        sends.open(owner, "first")
        sends.open(owner, "second")
        assertEquals(mapOf(owner to "first\nsecond"), sends.persisted(emptyMap()))
    }

    @Test
    fun `other boxes are left as they are`() {
        val sends = UnsettledSends()
        sends.open(owner, "commit and push")
        val stored = sends.persisted(mapOf("sess:main" to "half a thought"))
        assertEquals("half a thought", stored["sess:main"])
    }

    @Test
    fun `a photo sent with no words keeps nothing until it is a message`() {
        val sends = UnsettledSends()
        val ticket = sends.open(owner, "")
        assertEquals(emptyMap<String, String>(), sends.persisted(emptyMap()))
        val photoOnly = composeMessage("", listOf(AttachmentText.marker(path)))
        sends.update(ticket, photoOnly)
        assertEquals(mapOf(owner to photoOnly), sends.persisted(emptyMap()))
    }

    /**
     * The case that made this necessary: tap Send while the photo uploads, then
     * close the app out before it lands. The press dies with the app; the words
     * do not, and a new composer that loads the store finds them.
     */
    @Test
    fun `an app closed out while the upload settles keeps the words`() = runTest {
        val slots = AttachmentSlots()
        val presses = SendPresses(slots)
        val sends = UnsettledSends()
        var box = "commit and push"
        val sent = mutableListOf<String>()
        slots.stage(owner, "Photo", image = true)

        val app = CoroutineScope(coroutineContext + Job())
        val ticket = sends.open(owner, box)
        presses.press(app, owner, clearDraft = { box = "" }) {
            sent += composeMessage("commit and push", slots.take(owner).markers)
            sends.settle(ticket)
        }
        runCurrent()
        app.cancel()            // closed out from Recents, the upload still going
        runCurrent()

        assertTrue("it was sent before the photo it waited for", sent.isEmpty())
        assertEquals("", box)
        val reloaded = sends.persisted(mapOf(owner to box))
        assertEquals("the words went with the app", "commit and push", reloaded[owner])
    }

    // ------------------------------------------------------------- the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun viewModel(): String {
        val rel = "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt"
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 100_000)
        return text
    }

    private fun slice(text: String, from: String, to: String, max: Int): String {
        val s = text.substringAfter(from, "").substringBefore(to, "")
        assertTrue("'$from' read as ${s.length} chars — wrong slice", s.length in 1..max)
        return s
    }

    @Test
    fun `every write of the drafts folds in the messages still unsettled`() {
        val vm = viewModel()
        val writes = Regex("""settings\.setDrafts\(""").findAll(vm).count()
        val folded = Regex("""settings\.setDrafts\(unsettled\.persisted\(""").findAll(vm).count()
        assertTrue("no drafts write at all — wrong file", writes >= 1)
        assertEquals("a drafts write leaves out the messages that have left their box", writes, folded)
    }

    @Test
    fun `both send doors keep the message before the box empties`() {
        val vm = viewModel()
        for ((door, from, to) in listOf(
            Triple("chat", "fun send(id: String, text: String)", "private fun sendNow("),
            Triple("session", "fun sendText(name: String", "private fun sendTextNow("),
        )) {
            val body = slice(vm, from, to, 1_500)
            val kept = body.indexOf("unsettled.open(")
            val press = body.indexOf("presses.press(")
            assertTrue("the $door door empties the box with the words kept nowhere", kept >= 0 && press >= 0)
            assertTrue("the $door door keeps the words only after the box is empty", kept < press)
            assertTrue("the $door door does not write the kept words at the tap", "keepDrafts()" in body)
        }
    }

    @Test
    fun `a message is let go only when the host takes it or the box has it back`() {
        val vm = viewModel()
        val session = slice(vm, "private fun sendTextNow(", "the send queue", 6_000)
        val queued = slice(vm, "client.queueMessage(id, text", "return\n", 1_500)
        for ((door, body) in listOf("session" to session, "queued chat" to queued)) {
            val taken = body.substringAfter(".onSuccess", "").substringBefore(".onFailure", "")
            val refused = body.substringAfter(".onFailure", "")
            assertTrue("a $door send the host took stays kept", "settleSend(ticket)" in taken)
            val restored = refused.indexOf("restoreDraft(")
            val letGo = refused.indexOf("settleSend(ticket)")
            assertTrue("a refused $door send is never let go", letGo >= 0)
            assertTrue("a refused $door send is let go before the box has it back", restored in 0 until letGo)
        }
        val stream = slice(vm, "private fun collect(", "resumeIfStillRunning(id)", 8_000)
        assertTrue(
            "a streamed chat send is let go before the host has taken it",
            "if (sawStream) keeping?.let { settleSend(it); keeping = null }" in stream,
        )
    }

    @Test
    fun `the write at the tap outlives the view model`() {
        val keep = slice(viewModel(), "private fun keepDrafts()", "\n    }", 300)
        assertTrue("the tap's write dies with the model it was made on", "draftKeeper.launch" in keep)
    }
}
