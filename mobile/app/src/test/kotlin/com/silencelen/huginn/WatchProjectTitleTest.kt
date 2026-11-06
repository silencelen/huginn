package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.notify.WatchCycle
import com.silencelen.huginn.notify.projectsOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch cycle's own "needs you" names a project member by its project:
 * `<project name> · <role> needs you`, the lead as `lead` (owner decision,
 * 2026-11-06) — the daemon's `session_attention` title for one, so a question
 * reads the same whether the push or the alarm noticed it first.
 *
 * The cycle used to write `<name> needs you` for every session, and could not
 * have done better: the watch digest is name → state and nothing else. The
 * project is on the session ROWS, so the cycle reads them once when it is about
 * to post.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class WatchProjectTitleTest {

    /** `GET /v1/sessions` rows, with `project` exactly as `lib/projects.js` tags them. */
    private val rows = """{"sessions":[
        {"name":"vault-guide","state":"attention","project":{"id":"p-7c1e","name":"Vault rewrite","slug":"vault","role":"guide","lead":false}},
        {"name":"vault-lead","state":"attention","project":{"id":"p-7c1e","name":"Vault rewrite","slug":"vault","role":"lead","lead":true}},
        {"name":"build","state":"attention","project":null}
    ]}"""

    private fun client(status: HttpStatusCode, body: String) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "t" },
        transport = mockTransport { respond(body, status, headersOf("Content-Type", listOf("application/json"))) },
    )

    @Test
    fun `a waiting project member is named by its project and role, and a failed read names the session`() = runTest {
        val projects = projectsOf(client(HttpStatusCode.OK, rows))
        assertEquals("Vault rewrite · guide needs you", WatchCycle.needsYouTitle("vault-guide", projects["vault-guide"]))
        assertEquals("Vault rewrite · lead needs you", WatchCycle.needsYouTitle("vault-lead", projects["vault-lead"]))
        assertEquals("a plain session keeps its name", "build needs you", WatchCycle.needsYouTitle("build", projects["build"]))
        assertEquals("only project sessions are tagged", setOf("vault-guide", "vault-lead"), projects.keys)
        // The notice must still go out, named less well: no client (the push
        // reconcile passes none) and a refused read both leave every name plain.
        assertTrue(projectsOf(null).isEmpty())
        assertTrue(projectsOf(client(HttpStatusCode.InternalServerError, """{"error":"busy"}""")).isEmpty())
    }

    // ------------------------------------------------------------ the wiring

    private fun watchNotifier(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "app/src/main/kotlin/com/silencelen/huginn/notify/WatchNotifier.kt")
        assertTrue("WatchNotifier.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("WatchNotifier.kt read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `the cycle's needs-you title is the rule's, fed from the rows`() {
        val src = watchNotifier()
        val loop = src.substringAfter("for (name in fresh) {", "").substringBefore("posted++", "")
        assertTrue("the needs-you post read as ${loop.length} chars — wrong slice", loop.length in 100..2_500)
        assertTrue("the title is not the shared rule's", loop.contains("WatchCycle.needsYouTitle(name, projects[name])"))
        assertFalse("every session is titled by its tmux name again", loop.contains("\"\$name needs you\""))
        assertTrue("the rows are never read for the project", src.contains("projectsOf(client)"))
    }
}
