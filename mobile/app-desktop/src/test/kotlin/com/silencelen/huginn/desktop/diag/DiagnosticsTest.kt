package com.silencelen.huginn.desktop.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnostics blob is meant to be PASTED — into a chat, into a message to
 * somebody else, into a bug report. Everything here is about the one property
 * that makes that safe.
 *
 * NOTE the argument order: kotlin.test is `assertEquals(expected, actual, message)`,
 * the reverse of JUnit's.
 */
class DiagnosticsScrubTest {

    /** A real-shaped daemon token: 64 hex characters, as `/etc/huginn-appd/token` holds. */
    private val token = "a3f9c1e7b40d2856f1ac93be07d5124ef8b6a02c7d3915ea4c8f60b17d29e35a"

    @Test
    fun `the token never appears, from any field, ever`() {
        // Every free-text field gets the token, because the ones that are not
        // schema-constrained are exactly where a credential hides: a 401 message,
        // a URL somebody typed, a log line from a library.
        val text = Diagnostics.build(
            Diagnostics.Input(
                generatedAt = "2026-07-30T12:00:00Z",
                appVersion = "0.1.0",
                packaged = false,
                platform = "Linux 6.17.9 (amd64)",
                jvm = "OpenJDK 64-Bit Server VM 17.0.13",
                uptimeSec = 42,
                heapUsedMb = 100,
                heapMaxMb = 4096,
                baseUrl = "https://user:$token@100.97.198.90",
                routePinned = true,
                hasToken = true,
                clientId = "desktop-kt-1234",
                watchConnected = false,
                lastWatchError = "401 for Bearer $token",
                lastWatchErrorAt = "2026-07-30T11:59:00Z",
                appdVersion = "2.55.0",
                notifyEnabled = true,
                present = false,
                visible = true,
                claiming = false,
                notifier = null,
                updateStatus = "error: manifest fetch failed",
                updateVersion = null,
                updateError = "GET /v1/desktop-kt/manifest.json?token=$token -> 403",
                lastError = "network error: token=$token",
                logPath = "/home/x/.config/huginn-desktop-kt/huginn-desktop-kt.log",
                log = "2026-07-30T11:00:00Z WARN  watch stream dropped: Bearer $token rejected",
            )
        )

        assertFalse(token in text, "THE TOKEN MUST NEVER APPEAR IN A SHAREABLE REPORT")
        // And the redaction is visible rather than a silent deletion, so a reader
        // can tell "there was a credential here" from "there was nothing here".
        assertTrue("<hex-redacted>" in text || "<redacted>" in text, "the removal is stated")
    }

    @Test
    fun `the input type has no field a token could travel in`() {
        // The first line of defence is structural: there is a `hasToken` boolean
        // and no `token`. If this assertion ever needs changing, the change is the
        // bug — scrubbing is a backstop, not the mechanism.
        // Java reflection, not KClass.members: kotlin-reflect is not on this
        // module's test classpath and `members` would throw rather than assert.
        val names = Diagnostics.Input::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { it == "token" || it == "bearer" || it == "secret" }, "no token-shaped field: $names")
        assertTrue("hastoken" in names, "only whether there IS one")
    }

    @Test
    fun `it says what matters and says missing plainly`() {
        val text = Diagnostics.build(sample(hasToken = false, watchConnected = false))
        assertTrue(Regex("""token\s+MISSING""").containsMatchIn(text), "a missing token is the first thing to check")
        assertTrue(Regex("""watch stream\s+DISCONNECTED""").containsMatchIn(text))
        assertTrue("NOT WIRED" in text, "an unwired notifier must not read as working")
    }

    @Test
    fun `an empty log says so rather than trailing off`() {
        val text = Diagnostics.build(sample(hasToken = true, watchConnected = true).copy(log = ""))
        assertTrue("(empty)" in text)
        assertTrue(Regex("""watch stream\s+connected""").containsMatchIn(text))
    }

    private fun sample(hasToken: Boolean, watchConnected: Boolean) = Diagnostics.Input(
        generatedAt = "2026-07-30T12:00:00Z",
        appVersion = "0.1.0",
        packaged = true,
        platform = "Windows 11 (amd64)",
        jvm = "OpenJDK 17",
        uptimeSec = 10,
        heapUsedMb = 1,
        heapMaxMb = 2,
        baseUrl = "https://100.97.198.90",
        routePinned = false,
        hasToken = hasToken,
        clientId = "desktop-kt-abc",
        watchConnected = watchConnected,
        lastWatchError = null,
        lastWatchErrorAt = null,
        appdVersion = null,
        notifyEnabled = true,
        present = true,
        visible = true,
        claiming = true,
        notifier = null,
        updateStatus = "idle",
        updateVersion = null,
        updateError = null,
        lastError = null,
        logPath = null,
        log = "line",
    )
}

class RingLogTest {

    @Test
    fun `keeps only the most recent lines`() {
        val log = RingLog(file = null, maxLines = 3, now = { 0L })
        repeat(5) { log.info("area", "line $it") }
        val lines = log.text().lines()
        assertEquals(3, lines.size, "the cap holds")
        assertTrue(lines.first().endsWith("line 2"), "the oldest went first")
        assertTrue(lines.last().endsWith("line 4"))
    }

    @Test
    fun `scrubs on the way in, not only on the way out`() {
        val log = RingLog(file = null, now = { 0L })
        log.warn("watch", "401 for Bearer deadbeefdeadbeefdeadbeefdeadbeef0123456789abcdef")
        val text = log.text()
        assertFalse("deadbeef" in text, "a credential must not sit in memory in the clear either")
        assertTrue("Bearer <redacted>" in text)
    }

    @Test
    fun `the stamp is UTC and sortable`() {
        assertEquals("1970-01-01T00:00:00Z", RingLog.stamp(0))
    }

    @Test
    fun `scrub covers the four shapes a credential arrives in`() {
        assertTrue("Bearer <redacted>" in RingLog.scrub("Authorization: Bearer abc.def-123"))
        assertTrue("<hex-redacted>" in RingLog.scrub("token " + "f".repeat(40)))
        assertTrue("token=<redacted>" in RingLog.scrub("?token=hunter2&x=1"))
        assertTrue("://<redacted>@" in RingLog.scrub("https://u:p@host/x"))
    }

    @Test
    fun `an ordinary hex-looking word is left alone`() {
        // The 32-character floor exists so a short commit sha or an ANSI dump does
        // not come back as <hex-redacted> and make the log unreadable.
        assertEquals("commit c0c3b18 deadbeef", RingLog.scrub("commit c0c3b18 deadbeef"))
    }
}
