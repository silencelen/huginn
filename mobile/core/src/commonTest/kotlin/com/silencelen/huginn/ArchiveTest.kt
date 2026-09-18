package com.silencelen.huginn

import com.silencelen.huginn.data.ArchiveList
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ReviveResult
import com.silencelen.huginn.ui.ArchiveRules
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archived-session half of the clients: what a row means, how a list of them
 * is ordered, and what the client does with a daemon that has never heard of any
 * of it.
 *
 * ⚠ THE DECODE CASES ARE AGAINST REAL DAEMON JSON, not a round trip of our own
 * encoder. A round trip passes with a field named wrongly on both sides, which is
 * exactly the failure this model can have: `resumeCommand` decoding to null turns
 * the row's one purpose into a missing button, and nothing on screen looks broken.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ArchiveTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Exactly what GET /v1/archive sends, keys and all. */
    private val WIRE = """
        {"archives":[
          {"id":"0123abcd-0000-4000-8000-00000000abcd",
           "claudeSessionId":"0123abcd-0000-4000-8000-00000000abcd",
           "tmuxName":"jtyper","title":"Archive session feature","cwd":"/root/netplan",
           "model":"claude-opus-4-5","effort":"high","permissionMode":"acceptEdits",
           "gitBranch":"huginn3/w2-archive",
           "resumeCommand":"cd '/root/netplan' && claude --resume 0123abcd-0000-4000-8000-00000000abcd",
           "archivedAt":1758100000,"endedAt":1758100005,"lastMessage":"suite green",
           "transcriptPath":"/root/.claude/projects/-root-netplan/x.jsonl",
           "transcriptBytes":148231,"transcriptTruncated":false,
           "revivedAt":null,"revivedAs":null,"note":null,
           "live":false,"transcriptPresent":true}
        ],"max":64}
    """.trimIndent()

    private fun row(
        id: String = "0123abcd-0000-4000-8000-00000000abcd",
        archivedAt: Long = 100,
        live: Boolean = false,
        transcriptPresent: Boolean = true,
        title: String? = null,
        tmuxName: String? = null,
        revivedAs: String? = null,
    ) = ArchivedSession(
        id = id, archivedAt = archivedAt, live = live, transcriptPresent = transcriptPresent,
        title = title, tmuxName = tmuxName, revivedAs = revivedAs,
    )

    // ------------------------------------------------------------- decode

    @Test
    fun `a real archive row decodes with its resume command intact`() {
        val list = json.decodeFromString(ArchiveList.serializer(), WIRE)
        assertEquals(1, list.archives.size)
        assertEquals(64, list.max, "the cap travels, so a client can say what it is")
        val r = list.archives.first()
        assertEquals("0123abcd-0000-4000-8000-00000000abcd", r.id)
        assertEquals("jtyper", r.tmuxName)
        assertEquals("Archive session feature", r.title)
        assertEquals("/root/netplan", r.cwd)
        // THE FIELD THE WHOLE FEATURE IS. A rename on either side makes this null
        // and the row simply loses a button — no error, nothing to notice.
        assertEquals(
            "cd '/root/netplan' && claude --resume 0123abcd-0000-4000-8000-00000000abcd",
            r.resumeCommand,
        )
        assertEquals(1758100005L, r.endedAt)
        assertEquals(148231L, r.transcriptBytes)
        assertTrue(r.transcriptPresent)
        assertFalse(r.live)
        assertNull(r.revivedAt)
    }

    @Test
    fun `a row from a NEWER daemon still decodes, and a sparse one still renders`() {
        // Both directions of the compat rule: unknown keys are ignored, and every
        // field the daemon may omit has a default rather than failing the decode.
        val forward = json.decodeFromString(
            ArchivedSession.serializer(),
            """{"id":"aaaa","somethingFromLater":{"deep":true},"archivedAt":7}""",
        )
        assertEquals("aaaa", forward.id)
        assertEquals(7L, forward.archivedAt)
        assertNull(forward.resumeCommand, "absent is null, not a crash")
        assertFalse(forward.transcriptPresent, "and the cautious default is 'nothing to resume'")
    }

    @Test
    fun `a revive answer says whether the CONVERSATION came back, not just the name`() {
        // `resumed:false` is a revive that worked and remembers nothing — the
        // failure the whole feature exists to prevent, so it has to survive the
        // wire as its own fact rather than being inferred from ok:true.
        val r = json.decodeFromString(
            ReviveResult.serializer(),
            """{"ok":true,"name":"jtyper2","resumed":false,"restoredTranscript":false}""",
        )
        assertEquals("jtyper2", r.name, "the name tmux ACTUALLY used, which may not be the old one")
        assertFalse(r.resumed)
    }

    // ------------------------------------------------------------ ordering

    @Test
    fun `the archive list is newest first — the opposite rule to the page picker`() {
        // A page picker is a PLACE and must hold still under the finger. Nobody
        // points at an archived session from memory: the row most likely to be
        // wanted back is the one archived last.
        val ordered = ArchiveRules.ordered(
            listOf(row(id = "a", archivedAt = 100), row(id = "b", archivedAt = 300), row(id = "c", archivedAt = 200)),
        )
        assertEquals(listOf("b", "c", "a"), ordered.map { it.id })
    }

    @Test
    fun `two archives in the same second still have one total order`() {
        val a = row(id = "aaa", archivedAt = 7)
        val b = row(id = "bbb", archivedAt = 7)
        assertEquals(
            ArchiveRules.ordered(listOf(a, b)).map { it.id },
            ArchiveRules.ordered(listOf(b, a)).map { it.id },
            "otherwise the list reshuffles between two polls that saw the same rows",
        )
    }

    // --------------------------------------------------------- what a row offers

    @Test
    fun `a row that is already running offers no revive`() {
        // A second Claude on one transcript leaves a jsonl neither of them can
        // read. The host refuses it too; this is what keeps the button away.
        assertFalse(ArchiveRules.canRevive(row(live = true)))
        assertTrue(ArchiveRules.canRevive(row(live = false)))
        assertEquals("jtyper2", ArchiveRules.liveName(row(live = true, tmuxName = "jtyper", revivedAs = "jtyper2")))
        assertNull(ArchiveRules.liveName(row(live = false, tmuxName = "jtyper")))
    }

    @Test
    fun `a row with nothing left to resume says so BEFORE anyone presses anything`() {
        // Claude Code deletes its own transcripts after cleanupPeriodDays. Past
        // that a revive opens a blank conversation in the right directory and
        // reports success, which is indistinguishable from it having worked.
        assertTrue(ArchiveRules.startsFresh(row(transcriptPresent = false)))
        assertFalse(ArchiveRules.startsFresh(row(transcriptPresent = true)))
    }

    @Test
    fun `a row always has something to call itself`() {
        assertEquals("Archive session feature", ArchiveRules.label(row(title = "Archive session feature", tmuxName = "jtyper")))
        assertEquals("jtyper", ArchiveRules.label(row(title = null, tmuxName = "jtyper")))
        // Neither a title nor a name: a short id, because "" is not distinguishable
        // from the row above it.
        assertEquals("0123abcd", ArchiveRules.label(row(title = null, tmuxName = null)))
        assertEquals("0123abcd", ArchiveRules.label(row(title = "  ", tmuxName = "")))
    }

    // --------------------------------------------------------- the client

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "test-token" },
            engine = MockEngine { request -> seen += request; handler(request) },
        )

    private val seen = mutableListOf<HttpRequestData>()

    @Test
    fun `the list route is the feature probe, and a 404 is an answer rather than a crash`() = runTest {
        // A daemon older than archive has no such route. Both clients turn the
        // whole section off on this, which is why it must arrive as a 404 the
        // caller can see and not as a parse failure or an empty list.
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.NotFound) }.archives()
        }
        assertEquals(404, e.code, "the code is what the clients steer on")
    }

    @Test
    fun `archive asks for a mode by name, and --now is not the default`() = runTest {
        // Graceful is the default the owner asked for: a wrap-up commit is what
        // makes a later revive pleasant. `now` is the explicit escape hatch, so
        // sending it by accident is the mistake worth pinning.
        val c = client { respond("""{"ok":true,"id":"x","archived":false,"pending":true}""",
            HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json")) }
        c.archiveSession("jtyper")
        assertEquals("http://appd.test/v1/sessions/jtyper/archive", seen.last().url.toString())
        assertTrue((seen.last().body as TextContent).text.contains("\"graceful\""))
        c.archiveSession("jtyper", now = true)
        assertTrue((seen.last().body as TextContent).text.contains("\"now\""))
    }

    @Test
    fun `a refused archive arrives as the daemon's own sentence`() = runTest {
        // "answer the waiting question first, then archive the session" tells
        // somebody exactly what to do. A client that replaced it with a summary
        // of its own would turn the one useful refusal in this feature into
        // "something went wrong".
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client {
                respond(
                    """{"error":"answer the waiting question first, then archive the session"}""",
                    HttpStatusCode.Conflict,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }.archiveSession("jtyper")
        }
        assertEquals(409, e.code)
        assertEquals("answer the waiting question first, then archive the session", e.message)
    }

    @Test
    fun `revive and forget address the archive by its id, never by a session name`() = runTest {
        // A tmux name is reused within hours on this host; an id is the
        // conversation. Addressing either route by name is how a revive brings
        // back a stranger.
        val c = client { respond("""{"ok":true,"name":"jtyper2","resumed":true}""",
            HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
        val r = c.reviveArchive("0123abcd-0000-4000-8000-00000000abcd")
        assertEquals("http://appd.test/v1/archive/0123abcd-0000-4000-8000-00000000abcd/revive", seen.last().url.toString())
        assertEquals("jtyper2", r.name)

        client { respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            .deleteArchive("0123abcd-0000-4000-8000-00000000abcd")
        assertEquals("http://appd.test/v1/archive/0123abcd-0000-4000-8000-00000000abcd", seen.last().url.toString())
        assertEquals("DELETE", seen.last().method.value)
    }
}
