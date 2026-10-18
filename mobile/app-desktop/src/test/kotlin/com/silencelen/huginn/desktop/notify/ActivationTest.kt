package com.silencelen.huginn.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `huginn://` parsing, which is a SECURITY surface before it is a convenience one.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`
 * — the REVERSE of JUnit's. With three String arguments both compile and one of
 * them asserts something else entirely. See the DESKTOP-MIGRATION trap list.
 */
class ActivationTest {

    // ------------------------------------------------------------------ open

    @Test
    fun `open carries a view and an id`() {
        val a = Activations.parse("huginn://open?view=sessions&id=jotter")
        assertEquals(Activation.Open(NavTarget(TargetKind.SESSIONS, "jotter")), a)
    }

    @Test
    fun `open accepts chats too`() {
        val a = Activations.parse("huginn://open?view=chats&id=abc123")
        assertEquals(Activation.Open(NavTarget(TargetKind.CHATS, "abc123")), a)
    }

    @Test
    fun `open with no id, an empty id or an unknown view is refused`() {
        assertNull(Activations.parse("huginn://open?view=sessions"))
        assertNull(Activations.parse("huginn://open?view=sessions&id="))
        assertNull(Activations.parse("huginn://open?view=settings&id=x"))
        assertNull(Activations.parse("huginn://open?id=x"))
    }

    // ---------------------------------------------------------------- answer

    @Test
    fun `answer needs session, option, fingerprint and nonce`() {
        val a = Activations.parse("huginn://answer?session=jotter&option=2&fp=abc123&nonce=n0nce")
        assertEquals(Activation.Answer("jotter", 2, "abc123", "n0nce"), a)
    }

    @Test
    fun `AN ANSWER WITH NO FINGERPRINT IS ONLY NAVIGATION`() {
        // Anything on this machine can fire a scheme URL — a local process, a
        // background tab, a link the owner clicks — and huginn's host is
        // root-equivalent. Without a fingerprint the daemon answers whatever
        // question is on the pane at that instant. So this opens the session and
        // answers nothing.
        val open = Activation.Open(NavTarget(TargetKind.SESSIONS, "jotter"))
        assertEquals(open, Activations.parse("huginn://answer?session=jotter&option=2&nonce=n0nce"))
        assertEquals(open, Activations.parse("huginn://answer?session=jotter&option=2&fp=&nonce=n0nce"))
    }

    @Test
    fun `AN ANSWER WITH NO NONCE IS ONLY NAVIGATION`() {
        // The fingerprint is an unkeyed hash of the question and its options:
        // anyone who can predict a stock Claude Code dialog can compute it, and
        // this exact link (the scan's exploit) used to approve the dialog.
        val open = Activation.Open(NavTarget(TargetKind.SESSIONS, "main"))
        assertEquals(open, Activations.parse("huginn://answer?session=main&option=1&fp=0123456789ab"))
        assertEquals(open, Activations.parse("huginn://answer?session=main&option=1&fp=0123456789ab&nonce="))
    }

    @Test
    fun `answer refuses a missing, non-numeric or out-of-range option`() {
        assertNull(Activations.parse("huginn://answer?session=j&fp=x"))
        assertNull(Activations.parse("huginn://answer?session=j&option=yes&fp=x"))
        assertNull(Activations.parse("huginn://answer?session=j&option=1.5&fp=x"))
        assertNull(Activations.parse("huginn://answer?session=j&option=0&fp=x"))
        assertNull(Activations.parse("huginn://answer?session=j&option=-1&fp=x"))
    }

    @Test
    fun `answer refuses an empty session`() {
        assertNull(Activations.parse("huginn://answer?session=&option=1&fp=x"))
        assertNull(Activations.parse("huginn://answer?option=1&fp=x"))
    }

    // ------------------------------------------------------------------ undo

    @Test
    fun `an undo with no nonce is only navigation`() {
        // It used to carry nothing but the name, so any link could put a
        // laddered session back on Fable and pause the ladder for ten minutes.
        assertEquals(
            Activation.Open(NavTarget(TargetKind.SESSIONS, "jotter")),
            Activations.parse("huginn://undo?session=jotter"),
        )
        assertEquals(Activation.Undo("jotter", "n0nce"), Activations.parse("huginn://undo?session=jotter&nonce=n0nce"))
        assertNull(Activations.parse("huginn://undo?nonce=n0nce"))
    }

    // ------------------------------------------------------- resolve: the gate

    @Test
    fun `A FORGED ANSWER OPENS THE SESSION AND ANSWERS NOTHING`() {
        // The scan's exploit as written: fp computed offline for a stock dialog.
        val nonces = ActivationNonces()
        val target = NavTarget(TargetKind.SESSIONS, "main")
        assertEquals(
            ActivationStep.Navigate(target),
            Activations.resolve("huginn://answer?session=main&option=1&fp=0123456789ab", nonces),
        )
        // With a made-up nonce as well: still navigation, and the log says why.
        val guessed = Activations.resolve("huginn://answer?session=main&option=1&fp=0123456789ab&nonce=guess", nonces)
        assertIs<ActivationStep.Navigate>(guessed)
        assertEquals(target, guessed.target)
        assertTrue(guessed.refused.orEmpty().contains("unknown"), guessed.refused)
    }

    @Test
    fun `this run's toast nonce answers, once, and a replay is navigation`() {
        val nonces = ActivationNonces()
        val nonce = nonces.issue("sess:main", ActivationNonces.Grant.Answer("main", "fp1", setOf(1, 2)))
        val url = Activations.answerUrl("main", 2, "fp1", nonce)
        assertEquals(ActivationStep.Answer("main", 2, "fp1"), Activations.resolve(url, nonces))
        assertIs<ActivationStep.Navigate>(Activations.resolve(url, nonces))
    }

    @Test
    fun `a link that started the process is navigation`() {
        // The app was not running when the button was pressed: the URL arrives on
        // argv, and the nonce in it belongs to a run that no longer exists.
        val earlier = ActivationNonces()
        val nonce = earlier.issue("sess:main", ActivationNonces.Grant.Answer("main", "fp1", setOf(1)))
        val step = Activations.resolve(Activations.answerUrl("main", 1, "fp1", nonce), ActivationNonces())
        assertEquals(NavTarget(TargetKind.SESSIONS, "main"), assertIs<ActivationStep.Navigate>(step).target)
    }

    @Test
    fun `an expired toast opens the session instead of answering`() {
        var clock = 0L
        val nonces = ActivationNonces(now = { clock })
        val nonce = nonces.issue("sess:main", ActivationNonces.Grant.Answer("main", "fp1", setOf(1)))
        clock += ActivationNonces.TTL_MS + 1
        assertIs<ActivationStep.Navigate>(Activations.resolve(Activations.answerUrl("main", 1, "fp1", nonce), nonces))
    }

    @Test
    fun `an undo acts only with its toast's nonce`() {
        val nonces = ActivationNonces()
        assertIs<ActivationStep.Navigate>(Activations.resolve("huginn://undo?session=main", nonces))
        val nonce = nonces.issue("ladder:main", ActivationNonces.Grant.Undo("main"))
        assertIs<ActivationStep.Navigate>(Activations.resolve(Activations.undoUrl("other", nonce), nonces))
        val fresh = nonces.issue("ladder:main", ActivationNonces.Grant.Undo("main"))
        assertEquals(ActivationStep.Undo("main"), Activations.resolve(Activations.undoUrl("main", fresh), nonces))
        assertIs<ActivationStep.Navigate>(Activations.resolve(Activations.undoUrl("main", fresh), nonces))
    }

    @Test
    fun `open, ack and rubbish resolve as they always did`() {
        val nonces = ActivationNonces()
        assertEquals(
            ActivationStep.Navigate(NavTarget(TargetKind.CHATS, "c1")),
            Activations.resolve("huginn://open?view=chats&id=c1", nonces),
        )
        assertEquals(ActivationStep.Dismiss("ladder:main"), Activations.resolve(Activations.ackUrl("ladder:main"), nonces))
        assertEquals(ActivationStep.Summon, Activations.resolve("huginn://answer?option=1&fp=x&nonce=y", nonces))
        assertEquals(ActivationStep.Summon, Activations.resolve(null, nonces))
    }

    // ------------------------------------------------------------- the frame

    @Test
    fun `another scheme, another verb, and rubbish are all refused`() {
        assertNull(Activations.parse("https://open?view=chats&id=x"))
        assertNull(Activations.parse("huginn://quit"))
        assertNull(Activations.parse("huginn://answer"))
        assertNull(Activations.parse("not a url at all"))
        assertNull(Activations.parse(""))
        assertNull(Activations.parse(null))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        // Windows hands the URL back in whatever case the registry recorded.
        assertIs<Activation.Open>(Activations.parse("HUGINN://open?view=chats&id=x"))
    }

    // ----------------------------------------------------------------- argv

    @Test
    fun `argv is read from the end, past flags`() {
        val argv = listOf("--enable-logging", "huginn://open?view=chats&id=z")
        assertEquals(Activation.Open(NavTarget(TargetKind.CHATS, "z")), Activations.fromArgv(argv))
    }

    @Test
    fun `argv with nothing to activate yields null`() {
        assertNull(Activations.fromArgv(listOf("--flag", "--other")))
        assertNull(Activations.fromArgv(emptyList()))
        assertNull(Activations.fromArgv(listOf("/some/file.txt")))
    }

    @Test
    fun `the raw url is what gets forwarded, not a re-serialization`() {
        val raw = "huginn://answer?session=a%20b&option=1&fp=zz&nonce=n0nce"
        assertEquals(raw, Activations.urlFromArgv(listOf("--x", raw)))
        // Forwarded too, since it is navigation now; the running app decides.
        val bare = "huginn://answer?session=a&option=1"
        assertEquals(bare, Activations.urlFromArgv(listOf("--x", bare)))
        assertNull(Activations.urlFromArgv(listOf("--x", "huginn://answer?option=1")))
    }

    // ------------------------------------------------------------- encoding

    @Test
    fun `urls this app builds round-trip through its own parser`() {
        // Session names and chat ids are not URL-safe, and both ends of every one
        // of these URLs are in Activations — so the only thing that matters is
        // that the encoder and the decoder agree.
        val awkward = listOf("has space", "amp&ersand", "plus+sign", "q?mark", "hash#tag", "sl/ash", "üñï")
        for (name in awkward) {
            val open = Activations.openUrl(NavTarget(TargetKind.SESSIONS, name))
            assertEquals(
                Activation.Open(NavTarget(TargetKind.SESSIONS, name)),
                Activations.parse(open),
                "open round-trip for '$name'",
            )
            val answer = Activations.answerUrl(name, 3, "fp+with space&stuff", "n0nce-_x")
            assertEquals(
                Activation.Answer(name, 3, "fp+with space&stuff", "n0nce-_x"),
                Activations.parse(answer),
                "answer round-trip for '$name'",
            )
            assertEquals(
                Activation.Undo(name, "n0nce-_x"),
                Activations.parse(Activations.undoUrl(name, "n0nce-_x")),
                "undo round-trip for '$name'",
            )
        }
    }

    @Test
    fun `a notification key is stable and distinguishes the two lists`() {
        assertEquals("sess:jotter", NavTarget(TargetKind.SESSIONS, "jotter").key)
        assertEquals("chat:jotter", NavTarget(TargetKind.CHATS, "jotter").key)
    }
}
