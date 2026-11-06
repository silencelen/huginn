package com.silencelen.huginn

import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.ProjectIdle
import com.silencelen.huginn.notify.ProjectNotices
import com.silencelen.huginn.notify.SessionWatchWorker
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `project_idle`: every session of a project has been quiet for five minutes
 * after a burst that worked (owner decision, 2026-11-06). One notice for the
 * whole project instead of a `session_finished` per member.
 *
 * Unrouted, the push fell through to the generic post: the one slot every
 * unknown kind shares, filed under Needs you, and a tap that opened nothing in
 * particular. What it must be instead: a result (Finished, the channel a
 * finished session rides), no buttons and no reply box, a tap that opens THAT
 * project's page, nothing while that page is on screen, and a slot of its own
 * that a proposal's buttons do not share.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ProjectIdleTest {

    @After
    fun clearForeground() {
        Foreground.resumed = false
        Foreground.chat = null
        Foreground.session = null
        Foreground.project = null
    }

    /** The push as the daemon sends it (R4 contract): subject and payload both name the project. */
    private fun push(
        kind: String = ProjectIdle.KIND,
        title: String = "Vault rewrite is idle",
        text: String = "Worked 42m; lead, guide, vault ran.",
        subject: String? = "p-7c1e",
        payload: String? = """{"projectId":"p-7c1e"}""",
    ) = ProjectIdle.fromPush(kind, title, text, subject, payload)

    /** The push that MUST make a notice: unrouted, it made none and fell to the generic post. */
    private fun notice(
        title: String = "Vault rewrite is idle",
        text: String = "Worked 42m; lead, guide, vault ran.",
        subject: String? = "p-7c1e",
        payload: String? = """{"projectId":"p-7c1e"}""",
    ): ProjectIdle.Notice {
        val n = push(title = title, text = text, subject = subject, payload = payload)
        assertNotNull("project_idle makes no notice of its own", n)
        return n!!
    }

    @Test
    fun `a project going quiet is one result that opens that project`() {
        val n = notice()
        assertEquals("p-7c1e", n.projectId)
        assertEquals("the daemon's own words are kept", "Vault rewrite is idle", n.title)
        assertEquals("Worked 42m; lead, guide, vault ran.", n.text)
        val a = ProjectIdle.postArgs(n)
        assertEquals("the tap opens THAT project's page", "p-7c1e", a.project)
        assertNull("the tap opens the project, not one member", a.session)
        assertTrue("news, like a finished session", a.isResult)
        assertEquals(
            "Finished, never the blocking channel",
            SessionWatchWorker.CHANNEL_CHATS,
            SessionWatchWorker.channelFor(a.replyChat, a.isResult, proposal = a.projectActions.isNotEmpty()),
        )
    }

    /**
     * The doors held shut. There is nothing to answer: a reply box would be free
     * text to Claude from the shade, and a button would act on a project that
     * asked nothing.
     */
    @Test
    fun `no buttons and no reply box`() {
        val a = ProjectIdle.postArgs(notice())
        assertNull("free text must not be reachable from the shade", a.replyChat)
        assertTrue("no pane answer", a.answers.isEmpty())
        assertNull(a.fingerprint)
        assertTrue("no project verb either", a.projectActions.isEmpty())
    }

    @Test
    fun `its slot is its own - not the proposal's, not a session's, not the generic one`() {
        val key = ProjectIdle.postArgs(notice()).key
        assertEquals(ProjectIdle.keyFor("p-7c1e"), key)
        // A proposal's Spawn and Discard live under project:<id>; an idle notice
        // landing there would take them off the shade unseen.
        assertNotEquals(ProjectNotices.keyFor("p-7c1e"), key)
        assertNotEquals(
            SessionWatchWorker.notificationIdFor(ProjectNotices.keyFor("p-7c1e")),
            SessionWatchWorker.notificationIdFor(key),
        )
        // Unrouted, it took the one id every unknown kind shares.
        assertNotEquals(SessionWatchWorker.notificationIdFor(null), SessionWatchWorker.notificationIdFor(key))
        // A tmux session name cannot hold ':', so no member's slot can hash here.
        assertTrue(key.contains(':'))
    }

    @Test
    fun `nothing is posted while that project's page is on screen`() {
        Foreground.resumed = true
        Foreground.project = "p-7c1e"
        assertTrue("an open project page is not known to be on screen", Foreground.showsProject("p-7c1e"))
        val n = notice()
        assertFalse("the page is in front of the reader already", ProjectIdle.shows(n))
        // Another project's page, or one of its members open: post.
        Foreground.project = "p-other"
        assertTrue(ProjectIdle.shows(n))
        Foreground.project = null
        Foreground.session = "vault-guide"
        assertTrue("a member's session is not the project's page", ProjectIdle.shows(n))
        // The page left open with the phone pocketed: post.
        Foreground.session = null
        Foreground.project = "p-7c1e"
        Foreground.resumed = false
        assertTrue("open but nobody looking", ProjectIdle.shows(n))
    }

    @Test
    fun `the project comes from the payload, then the subject - and other kinds are left alone`() {
        assertEquals("p-7c1e", notice(subject = null).projectId)
        assertEquals("p-7c1e", notice(payload = null).projectId)
        assertEquals("the field the daemon fills on purpose wins", "p-7c1e", notice(subject = "p-other").projectId)
        assertNull("a tap that could open nothing", push(subject = null, payload = null))
        assertNull(push(subject = " ", payload = """{"projectId":" "}"""))
        assertNull(push(kind = "session_finished"))
        assertNull(push(kind = ProjectNotices.KIND_CHANGE))
        assertNull(push(kind = "chat_finished"))
        // A silent push still says something.
        val bare = notice(title = "", text = "")
        assertTrue(bare.title.isNotBlank())
        assertTrue(bare.text.isNotBlank())
    }

    // ------------------------------------------------------------ the wiring

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `the messaging service routes the kind and keeps the generic post off it`() {
        val text = source("notify/HuginnMessagingService.kt")
        assertTrue("project_idle is not routed", text.contains("ProjectIdle.fromPush("))
        assertTrue("it is posted while its project's page is on screen", text.contains("ProjectIdle.shows("))
        val post = text.substringAfter("ProjectIdle.postArgs(idle)", "").substringBefore("val redundant", "")
        assertTrue("the idle post read as ${post.length} chars — wrong slice", post.length in 50..1_200)
        assertTrue("its tap does not open the project", post.contains("project = a.project"))
        assertTrue("it is not filed as a result", post.contains("isResult = a.isResult"))
        assertTrue("it is not posted in its own slot", post.contains("key = a.key"))
        assertTrue(
            "the generic post draws it as well, under Needs you",
            Regex("""val redundant = [^\n]*idle != null""").containsMatchIn(text),
        )
    }

    @Test
    fun `the open project page is published, and opening it takes the idle notice down`() {
        val main = source("MainActivity.kt")
        assertTrue(
            "the project page is never published as on screen",
            main.contains("Foreground.project = (dest as? Dest.Project)?.id"),
        )
        assertTrue(
            "a page left behind keeps suppressing its project's notices",
            Regex("""onDispose \{[^}\n]*Foreground\.project = null""").containsMatchIn(main),
        )
        assertTrue("read = dismissed leaves the idle notice up", main.contains("ProjectIdle.keyFor(d.id)"))
    }
}
