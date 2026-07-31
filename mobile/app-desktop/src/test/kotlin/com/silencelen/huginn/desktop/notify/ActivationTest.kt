package com.silencelen.huginn.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
        val a = Activations.parse("huginn://open?view=sessions&id=jtyper")
        assertEquals(Activation.Open(NavTarget(TargetKind.SESSIONS, "jtyper")), a)
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
    fun `answer needs session, option and fingerprint`() {
        val a = Activations.parse("huginn://answer?session=jtyper&option=2&fp=abc123")
        assertEquals(Activation.Answer("jtyper", 2, "abc123"), a)
    }

    @Test
    fun `AN ANSWER WITH NO FINGERPRINT IS REFUSED`() {
        // The single most important line in this file. Anything on this machine
        // can fire a scheme URL — a local process, a background tab, a link the
        // owner clicks — and huginn's host is root-equivalent. Without a
        // fingerprint the daemon answers whatever question is on the pane at that
        // instant, so a forged link approves an arbitrary tool-use prompt.
        assertNull(Activations.parse("huginn://answer?session=jtyper&option=2"))
        assertNull(Activations.parse("huginn://answer?session=jtyper&option=2&fp="))
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
        val raw = "huginn://answer?session=a%20b&option=1&fp=zz"
        assertEquals(raw, Activations.urlFromArgv(listOf("--x", raw)))
        assertNull(Activations.urlFromArgv(listOf("--x", "huginn://answer?session=a&option=1")))
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
            val answer = Activations.answerUrl(name, 3, "fp+with space&stuff")
            assertEquals(
                Activation.Answer(name, 3, "fp+with space&stuff"),
                Activations.parse(answer),
                "answer round-trip for '$name'",
            )
        }
    }

    @Test
    fun `a notification key is stable and distinguishes the two lists`() {
        assertEquals("sess:jtyper", NavTarget(TargetKind.SESSIONS, "jtyper").key)
        assertEquals("chat:jtyper", NavTarget(TargetKind.CHATS, "jtyper").key)
    }
}
