package com.silencelen.huginn.desktop.notify

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * WHAT WINDOWS IS ACTUALLY HANDED WHEN A BUTTON IS PRESSED.
 *
 * Everything between the router and the owner's click is covered somewhere:
 * [ActivationTest] proves `huginn://…` parses, [SchemeRegistrarTest] proves the
 * scheme is claimed. This is the seam between them — the toast XML — and it was
 * covered nowhere, which is how "the URL is fine" and "the registry is fine"
 * could both be true while the button did nothing.
 *
 * The chain a real click runs is: this app writes the XML → Windows parses it →
 * the shell takes the `arguments` attribute VERBATIM as a URL → the registered
 * handler is launched with it in argv → [Activations.parse] reads it back. So the
 * test parses the XML with a real XML parser rather than grepping it: the `&`
 * between query parameters is `&amp;` in the document and a plain `&` in the
 * attribute value, and a test that matched the escaped text would pass against
 * XML the shell would hand back broken.
 *
 * The assertion at the end of every case is the same one: the triple that comes
 * back out is exactly the triple `HuginnClient.answerPrompt(session, option, fp)`
 * takes — the identical call the in-app answer makes from `SessionController`.
 * One transport, whether the owner clicks in the window or on the toast.
 */
class ToastActivationRoundTripTest {

    private fun request(
        options: List<AnswerOption>,
        fingerprint: String? = "fp-abc123",
        session: String = "jtyper",
    ) = NotifyRequest(
        key = "sess:$session",
        title = session,
        body = "needs you",
        urgent = true,
        target = NavTarget(TargetKind.SESSIONS, session),
        options = options,
        fingerprint = fingerprint,
    )

    /** The `arguments` of every `<action>`, as the shell would receive them. */
    private fun actionUrls(xml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("arguments") }
    }

    private fun actionLabels(xml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("content") }
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `every option button parses back to the answer it offered`() {
        val options = listOf(
            AnswerOption(1, "Yes"),
            AnswerOption(2, "Yes, and don't ask again"),
            AnswerOption(3, "No, and tell Claude what to do differently"),
        )
        val xml = WindowsToastNotifier.attentionXml(request(options))
        val urls = actionUrls(xml)
        assertEquals(3, urls.size, xml)
        urls.forEachIndexed { i, url ->
            assertEquals(
                Activation.Answer("jtyper", i + 1, "fp-abc123"),
                Activations.parse(url),
                "button ${i + 1} did not round-trip: <$url>",
            )
        }
    }

    @Test
    fun `labels with spaces, punctuation and unicode do not disturb the url`() {
        // The label is NOT in the URL — the option NUMBER is, and the daemon owns
        // the mapping. That is the bounded-choices rule holding: whatever the
        // toast printed, what gets answered is the row the host offered.
        val options = listOf(
            AnswerOption(1, "Yes & proceed"),
            AnswerOption(2, "No — stop <here>"),
            AnswerOption(3, "Já, halda áfram 🐦"),
        )
        val xml = WindowsToastNotifier.attentionXml(request(options))
        val urls = actionUrls(xml)
        urls.forEachIndexed { i, url ->
            assertEquals(Activation.Answer("jtyper", i + 1, "fp-abc123"), Activations.parse(url))
        }
        // And the labels survive the XML escaping intact, numbered the way the
        // owner reads them off the pane.
        assertEquals(
            listOf("1. Yes & proceed", "2. No — stop <here>", "3. Já, halda áfram 🐦"),
            actionLabels(xml),
        )
    }

    @Test
    fun `a session name with a space, an ampersand or unicode survives`() {
        for (name in listOf("my session", "a&b", "sesión", "50% done", "a+b", "a b/c")) {
            val xml = WindowsToastNotifier.attentionXml(
                request(listOf(AnswerOption(2, "Yes")), session = name),
            )
            val url = actionUrls(xml).single()
            assertEquals(
                Activation.Answer(name, 2, "fp-abc123"),
                Activations.parse(url),
                "session <$name> did not round-trip through <$url>",
            )
        }
    }

    @Test
    fun `a fingerprint with url-significant characters survives`() {
        // Fingerprints are the host's, not ours — base64url, hex, whatever it
        // decides. `+` and `=` are the ones that would quietly change value.
        for (fp in listOf("abc+def=", "a/b+c==", "plain123", "ünïcode")) {
            val xml = WindowsToastNotifier.attentionXml(
                request(listOf(AnswerOption(1, "Yes")), fingerprint = fp),
            )
            val url = actionUrls(xml).single()
            assertEquals(
                Activation.Answer("jtyper", 1, fp),
                Activations.parse(url),
                "fingerprint <$fp> did not round-trip through <$url>",
            )
        }
    }

    // ------------------------------------------------------------- the rules

    @Test
    fun `every action activates by protocol with a huginn url`() {
        val xml = WindowsToastNotifier.attentionXml(request(listOf(AnswerOption(1, "Yes"))))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("action")
        assertTrue(nodes.length > 0)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            assertEquals("protocol", el.getAttribute("activationType"))
            // `huginn://`, not a bare `huginn:`. A scheme with no authority is
            // what a shell reports as a link it does not know how to open even
            // when the handler IS registered.
            assertTrue(
                el.getAttribute("arguments").startsWith("huginn://"),
                "not a well-formed huginn:// URI: <${el.getAttribute("arguments")}>",
            )
        }
        // The body tap, too — the one that fires when the toast itself is clicked.
        val toast = doc.documentElement
        assertEquals("protocol", toast.getAttribute("activationType"))
        assertTrue(toast.getAttribute("launch").startsWith("huginn://"), toast.getAttribute("launch"))
    }

    @Test
    fun `NO FINGERPRINT MEANS NO BUTTONS AT ALL`() {
        // Not "buttons that answer whatever is on the pane". See Activations.parse.
        val xml = WindowsToastNotifier.attentionXml(
            request(listOf(AnswerOption(1, "Yes"), AnswerOption(2, "No")), fingerprint = null),
        )
        assertEquals(emptyList(), actionUrls(xml))
    }

    @Test
    fun `at most three answer buttons, which is the bounded-choice cap`() {
        val many = (1..7).map { AnswerOption(it, "option $it") }
        assertEquals(3, actionUrls(WindowsToastNotifier.attentionXml(request(many))).size)
    }

    @Test
    fun `a finished toast's own action urls round-trip too`() {
        val req = NotifyRequest(
            key = "ladder:jtyper",
            title = "jtyper",
            body = "moved to a smaller model",
            urgent = false,
            target = NavTarget(TargetKind.SESSIONS, "jtyper"),
            actions = listOf(
                ToastAction("Undo", Activations.undoUrl("jtyper")),
                ToastAction("OK", Activations.ackUrl("sess:jtyper")),
            ),
        )
        val urls = actionUrls(WindowsToastNotifier.finishedXml(req))
        assertEquals(Activation.Undo("jtyper"), Activations.parse(urls[0]))
        assertEquals(Activation.Ack("sess:jtyper"), Activations.parse(urls[1]))
    }

    @Test
    fun `a title or body carrying XML metacharacters still yields parseable XML`() {
        val req = request(listOf(AnswerOption(1, "Yes"))).copy(
            title = """<script>&"'""",
            body = "tool: rm -rf / & echo \"done\" <ok>",
        )
        // The whole point: an unescapable character in tool output used to be a
        // toast that never appeared. Parsing at all is the assertion.
        val urls = actionUrls(WindowsToastNotifier.attentionXml(req))
        assertEquals(Activation.Answer("jtyper", 1, "fp-abc123"), Activations.parse(urls.single()))
    }
}
