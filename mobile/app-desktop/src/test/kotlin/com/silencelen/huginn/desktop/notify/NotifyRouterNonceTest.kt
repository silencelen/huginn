package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PromptOption
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchHeadroom
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The router keeps the nonce table in step with the screen: a toast with buttons
 * that act for the owner gets a nonce, and loses it when the toast is replaced or
 * taken down. Driven through real digests, with a backend that records.
 */
class NotifyRouterNonceTest {

    private class Recorder(override val supportsActions: Boolean = true) : Notifier {
        override val name: String = "recorder"
        val posted = mutableListOf<NotifyRequest>()
        override fun post(request: NotifyRequest) {
            posted += request
        }
        override fun withdraw(key: String) = Unit
    }

    private val nonces = ActivationNonces()
    private var prompt: PanePrompt? = PanePrompt(
        question = "Do you want to make this edit?",
        options = listOf(PromptOption(1, "Yes"), PromptOption(2, "Yes, allow all"), PromptOption(3, "No")),
        fingerprint = "fp1",
    )

    private fun router(backend: Recorder) = NotifyRouter(
        // Unconfined: the enrichment coroutine runs to completion inside onDigest.
        scope = CoroutineScope(Dispatchers.Unconfined),
        notifier = { backend },
        fetchPrompt = { prompt },
        enabled = { true },
        focusedTarget = { null },
        nonces = nonces,
    )

    private fun watch(state: String, headroom: WatchHeadroom? = null) =
        Watch(hash = state + headroom.hashCode(), sessions = mapOf("main" to state), headroom = headroom)

    /** What the shell would hand back for each button: the parsed `arguments`. */
    private fun buttonUrls(r: NotifyRequest): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(WindowsToastNotifier.attentionXml(r).byteInputStream())
        val nodes = doc.getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("arguments") }
    }

    @Test
    fun `a needs-you toast carries a nonce that answers once`() {
        val backend = Recorder()
        val r = router(backend)
        r.onDigest(watch("running"))
        r.onDigest(watch("attention"))
        val toast = backend.posted.single()
        assertNotNull(toast.nonce, "a toast with buttons must carry this run's nonce")
        val urls = buttonUrls(toast)
        assertEquals(3, urls.size)
        assertEquals(ActivationStep.Answer("main", 1, "fp1"), Activations.resolve(urls[0], nonces))
        assertIs<ActivationStep.Navigate>(Activations.resolve(urls[0], nonces))
    }

    @Test
    fun `a toast with no buttons gets no nonce`() {
        prompt = prompt!!.copy(fingerprint = null)
        val backend = Recorder()
        val r = router(backend)
        r.onDigest(watch("running"))
        r.onDigest(watch("attention"))
        assertNull(backend.posted.single().nonce)

        val plain = Recorder(supportsActions = false)
        prompt = prompt!!.copy(fingerprint = "fp1")
        val r2 = router(plain)
        r2.onDigest(watch("running"))
        r2.onDigest(watch("attention"))
        assertNull(plain.posted.single().nonce, "a backend that draws no buttons needs no nonce")
    }

    @Test
    fun `answered elsewhere, the toast's buttons stop working`() {
        val backend = Recorder()
        val r = router(backend)
        r.onDigest(watch("running"))
        r.onDigest(watch("attention"))
        val urls = buttonUrls(backend.posted.single())
        // Answered in tmux: the digest moves on and the toast is withdrawn.
        r.onDigest(watch("running"))
        assertIs<ActivationStep.Navigate>(Activations.resolve(urls[0], nonces))
    }

    @Test
    fun `opening the session takes the toast's nonce with it`() {
        val backend = Recorder()
        val r = router(backend)
        r.onDigest(watch("running"))
        r.onDigest(watch("attention"))
        val urls = buttonUrls(backend.posted.single())
        r.onViewed(NavTarget(TargetKind.SESSIONS, "main"))
        assertIs<ActivationStep.Navigate>(Activations.resolve(urls[1], nonces))
    }

    @Test
    fun `a downgrade's Undo carries a nonce, and OK does not need one`() {
        val backend = Recorder()
        val r = router(backend)
        val calm = WatchHeadroom(mode = "ok")
        r.onDigest(watch("idle", calm))
        r.onDigest(watch("idle", calm.copy(laddered = mapOf("main" to "opus"))))
        val toast = backend.posted.single()
        val (undo, ok) = toast.actions.map { it.url }
        assertTrue(undo.contains("nonce="), undo)
        assertEquals(ActivationStep.Undo("main"), Activations.resolve(undo, nonces))
        assertIs<ActivationStep.Navigate>(Activations.resolve(undo, nonces), "an Undo replayed is navigation")
        assertEquals(ActivationStep.Dismiss("ladder:main"), Activations.resolve(ok, nonces))
    }

    @Test
    fun `a backend without buttons gets no Undo and mints nothing`() {
        val backend = Recorder(supportsActions = false)
        val r = router(backend)
        val calm = WatchHeadroom(mode = "ok")
        r.onDigest(watch("idle", calm))
        r.onDigest(watch("idle", calm.copy(laddered = mapOf("main" to "opus"))))
        assertEquals(emptyList(), backend.posted.single().actions)
    }
}
