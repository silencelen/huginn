package com.silencelen.huginn

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectLead
import com.silencelen.huginn.data.ProjectList
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SpawnResult
import com.silencelen.huginn.ui.DashboardCursor
import com.silencelen.huginn.ui.ProjectRules
import com.silencelen.huginn.ui.appEntries
import com.silencelen.huginn.ui.groupSessions
import com.silencelen.huginn.ui.projectEntries
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone shell's own project rules — the ones that cannot live in `:core`
 * because they are about this client's screens, and cannot be asserted on the
 * view model because there is none to build: `HuginnViewModel` is an
 * `AndroidViewModel` and this host has no device. Everything here is therefore
 * reached the way `reattachPlan` already is, as a pure function the shell calls.
 */
class ProjectsPhoneTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun session(name: String) = Session(name = name)

    private fun live(role: String, name: String, lead: Boolean = false) =
        ProjectLive(role = role, name = name, claudeName = "x/$role", lead = lead, present = true, alive = true)

    // ------------------------------------------------------------- grouping

    /**
     * ⚠⚠ THE JOIN KEY IS THE TMUX NAME AND IT COMES OFF `live[]`.
     *
     * Every session on this host is addressed by its tmux name (`<slug>-<role>`);
     * a project's PEER name is `<slug>/<role>` and a slash is not a tmux name
     * character, so a grouping that matched peer names would group nothing. And a
     * grouping that matched the slug PREFIX would be worse than nothing: a
     * session somebody called `statusflap-notes` by hand would silently join a
     * cluster it is not in, under a heading claiming the daemon put it there.
     */
    @Test
    fun `a project's members group under its heading, and the rest come after`() {
        val p = ProjectRow(
            id = "p1", name = "Status page flap", slug = "statusflap", status = "active",
            memberCount = 2, alive = 2, lead = ProjectLead(name = "statusflap-lead"),
        )
        val members = mapOf("p1" to listOf(live("lead", "statusflap-lead", lead = true), live("db", "statusflap-db")))
        val sessions = listOf(
            session("jtyper"),
            session("statusflap-db"),
            session("statusflap-lead"),
            // The trap: the slug is a prefix of this name and it is NOT a member.
            session("statusflap-notes"),
        )

        val groups = groupSessions(listOf(p), members, sessions)

        assertEquals(2, groups.size)
        assertEquals("p1", groups[0].project?.id)
        assertEquals(listOf("statusflap-db", "statusflap-lead"), groups[0].sessions.map { it.name })
        assertNull("the unaffiliated block is last and has no project", groups[1].project)
        assertEquals(listOf("jtyper", "statusflap-notes"), groups[1].sessions.map { it.name })
    }

    /**
     * A project whose detail has not been fetched still gathers its LEAD, because
     * the list row carries that one name. Anything more would be a guess.
     */
    @Test
    fun `an unfetched project still claims the lead it is certain to own`() {
        val p = ProjectRow(id = "p1", name = "LoRa", slug = "lora", status = "drafting",
            lead = ProjectLead(name = "lora-lead"))
        val groups = groupSessions(listOf(p), emptyMap(), listOf(session("lora-lead"), session("jtyper")))
        assertEquals(listOf("lora-lead"), groups[0].sessions.map { it.name })
        assertEquals(listOf("jtyper"), groups[1].sessions.map { it.name })
    }

    /** A heading over nothing reads as a bug, so a project with no session here draws none. */
    @Test
    fun `a project with no live session gets no heading`() {
        val p = ProjectRow(id = "p1", name = "Auvik lab", slug = "auvik", status = "archived")
        val groups = groupSessions(listOf(p), emptyMap(), listOf(session("jtyper")))
        assertEquals(1, groups.size)
        assertNull(groups[0].project)
    }

    /** No projects at all is the list exactly as it has always been drawn. */
    @Test
    fun `no projects means one ungrouped block`() {
        val groups = groupSessions(emptyList(), emptyMap(), listOf(session("a"), session("b")))
        assertEquals(1, groups.size)
        assertNull(groups[0].project)
        assertEquals(2, groups[0].sessions.size)
    }

    // ------------------------------------------------------ the feature probe

    /**
     * FEATURE ABSENT HIDES EVERY WAY IN AT ONCE.
     *
     * Three doors lead to Projects and three to Apps, and each of them ends in
     * a route an older daemon answers 404 to. Three `== true` checks written
     * beside three call sites is exactly how one gets left behind, so the answer
     * is computed once. `null` — the probe has not spoken — shows nothing either:
     * a door that may not exist is not a door to offer.
     */
    @Test
    fun `a daemon without projects or apps offers no entry anywhere`() {
        val off = projectEntries(false)
        assertFalse(off.sessionsIcon); assertFalse(off.settingsRow); assertFalse(off.grouping)
        assertFalse("nothing at all", off.any)

        val unknown = projectEntries(null)
        assertFalse("the probe has not answered — show nothing", unknown.any)

        val on = projectEntries(true)
        assertTrue(on.sessionsIcon && on.settingsRow && on.grouping)

        assertEquals(appEntries(false), appEntries(null))
        assertFalse(
            appEntries(false).statusCard || appEntries(false).fullPage || appEntries(false).settingsRow,
        )
        assertTrue(
            appEntries(true).statusCard && appEntries(true).fullPage && appEntries(true).settingsRow,
        )
    }

    /** The client turns the 404 into a null, which is what the probe reads. */
    @Test
    fun `an older daemon answers 404 and the probe reads absent, not empty`() = runTest {
        val client = HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            engine = MockEngine { respond("not found", HttpStatusCode.NotFound) },
        )
        assertNull("absent is null, never an empty list", client.projects())
        assertNull("and only after BOTH names have 404ed", client.apps())
    }

    // -------------------------------------------------------- the poll gate

    /**
     * ⚠⚠ A DASHBOARD TICK IS NOT FREE AT EITHER END.
     *
     * The daemon sums up to twelve session overviews to answer it; the client
     * then redraws a table of twelve rows of numbers. `generatedAt` is the
     * daemon's own "this is when I answered", so an answer carrying the stamp
     * already on screen is the same answer — and publishing it recomposes the
     * whole screen to draw identical figures, every five seconds, for as long as
     * anybody reads it.
     */
    @Test
    fun `the dashboard poll skips an answer whose generatedAt has not moved`() {
        val cursor = DashboardCursor()
        val first = ProjectDashboard(generatedAt = 1_789_460_000)
        assertTrue("the first answer is always new", cursor.accept(first))
        assertFalse("the same stamp is the same answer", cursor.accept(ProjectDashboard(generatedAt = 1_789_460_000)))
        assertFalse(cursor.accept(ProjectDashboard(generatedAt = 1_789_460_000)))
        assertTrue("a moved stamp is work", cursor.accept(ProjectDashboard(generatedAt = 1_789_460_005)))
        // Nothing to publish is not the same as nothing new.
        assertFalse(cursor.accept(null))
    }

    /**
     * ⚠ ZERO IS NOT A STAMP. A daemon that did not fill the field leaves 0, and
     * reading that as "unchanged" would freeze the screen on its first answer for
     * the rest of the session.
     */
    @Test
    fun `an unstamped dashboard always lands`() {
        val cursor = DashboardCursor()
        assertTrue(cursor.accept(ProjectDashboard(generatedAt = 0)))
        assertTrue(cursor.accept(ProjectDashboard(generatedAt = 0)))
    }

    /** A different project starts again: its stamps are not this one's. */
    @Test
    fun `resetting forgets the stamp`() {
        val cursor = DashboardCursor()
        assertTrue(cursor.accept(ProjectDashboard(generatedAt = 7)))
        assertFalse(cursor.accept(ProjectDashboard(generatedAt = 7)))
        cursor.reset()
        assertTrue(cursor.accept(ProjectDashboard(generatedAt = 7)))
    }

    // ------------------------------------------------------- the refusals

    /**
     * THE THREE REFUSALS, VERBATIM, WITH EVERYTHING TYPED STILL IN THE SHEET.
     *
     * Each of these is a 409 that is an ANSWER rather than a throw, and the whole
     * value of each is the fix it names — "open it once with `claude` there", the
     * slug that is taken, the usage window that is red. Thrown, they arrive on a
     * failure path as a red line with no project attached and the form cleared;
     * answered, they land under the field with the brief still in it.
     */
    @Test
    fun `a refused create comes back as a sentence, not an exception`() = runTest {
        val untrusted = "Claude Code has not been trusted in /srv/thing — open it once with " +
            "`claude` there and accept the folder-trust question, then create the project"
        val client = HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            engine = MockEngine {
                respond(
                    """{"error":${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(untrusted))}}""",
                    HttpStatusCode.Conflict,
                    headersOf("Content-Type", listOf("application/json")),
                )
            },
        )
        val made = client.createProject("Thing", "software", "do the thing", "/srv/thing")
        assertFalse(made.ok)
        assertNull("nothing was created", made.project)
        assertEquals("shown exactly as the host wrote it", untrusted, made.refusal)
    }

    @Test
    fun `a taken slug is answered in the sheet too`() = runTest {
        val taken = "another project already uses the name lora-stick"
        val client = HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            engine = MockEngine {
                respond(
                    """{"error":"$taken"}""",
                    HttpStatusCode.Conflict,
                    headersOf("Content-Type", listOf("application/json")),
                )
            },
        )
        assertEquals(taken, client.createProject("LoRa sensor stick", "hardware", "b", null).refusal)
    }

    /**
     * The STOP sentinel, which is a state of the house rather than a fault in the
     * proposal — and reads that way only in the daemon's own words. It comes back
     * from SPAWN, so it lands on the manifest card rather than the create sheet.
     */
    @Test
    fun `the headroom STOP sentinel refuses a spawn in its own words`() = runTest {
        val stop = "there is no room on this account right now (weekly usage 92%) — " +
            "the proposal is kept and can be spawned when the window resets"
        val client = HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            engine = MockEngine {
                respond(
                    """{"error":"$stop"}""",
                    HttpStatusCode.Conflict,
                    headersOf("Content-Type", listOf("application/json")),
                )
            },
        )
        val outcome = client.spawnProject("p1", 2)
        assertFalse(outcome.ok)
        assertEquals(stop, outcome.refusal)
        assertTrue("nothing was created", outcome.spawned.isEmpty())
    }

    // ----------------------------------------------------------- the spawn

    /**
     * ⚠⚠ `ok:false` ON AN HTTP 200 IS THE ORDINARY CASE.
     *
     * Spawning is a loop over tmux: the second of three roles failing does not
     * un-spawn the first, so the daemon carries on, creates the rest, and answers
     * with both lists inside a 200. A shell that read the status code as the
     * verdict would report a working two-thirds cluster as a failure; one that
     * collapsed this to a boolean would lose WHICH role to retry — which is the
     * only actionable thing in the answer.
     */
    @Test
    fun `a partial spawn names the role that failed and keeps the ones that came up`() {
        val result = json.decodeFromString(SpawnResult.serializer(), fixture("spawn-result.json"))
        assertFalse("the normal partial case", result.ok)
        assertEquals(listOf("db", "web"), result.spawned.map { it.role })

        val words = ProjectRules.spawnWords(result)
        assertTrue("says how many of how many: $words", words.contains("2"))

        val failures = ProjectRules.spawnFailures(result)
        assertEquals(1, failures.size)
        assertTrue("names the role: ${failures[0]}", failures[0].contains("probe"))
        // The daemon's own sentence about why, verbatim — "duplicate session" and
        // "persona could not be written" are different problems with different
        // fixes, and a client's summary of either helps nobody.
        assertTrue("keeps the reason: ${failures[0]}", failures[0].contains("duplicate session"))
    }

    // --------------------------------------------------- the fixtures decode

    /** The shell's own reading of the two list routes, from the daemon's fixtures. */
    @Test
    fun `the tree and the apps card decode from the daemon's own fixtures`() {
        val list = json.decodeFromString(ProjectList.serializer(), fixture("projects.json"))
        assertEquals(3, list.projects.size)
        // LIVE FIRST, THEN BY NAME, and archived last — a place rather than a
        // feed. A tree that re-sorted by activity would move the row under the
        // finger, and the row you tapped is not the row that is there a second
        // later. So "LoRa sensor stick" leads "Status page flap" on its name, not
        // on being busier, and the archived lab is at the bottom whatever it is
        // called.
        val ordered = ProjectRules.orderedProjects(list.projects)
        assertEquals(listOf("LoRa sensor stick", "Status page flap", "Auvik lab"), ordered.map { it.name })
        assertEquals("archived", ordered.last().status)

        val apps = json.decodeFromString(AppList.serializer(), fixture("apps.json"))
        assertEquals(4, apps.apps.size)
        // ⚠ THE THIRD STATE. `up:null` is "no verdict yet" and the daemon keeps
        // probe state in memory only, so every restart puts every row back to it.
        // Folding it to false would draw an outage on a host where nothing is wrong.
        val unknown: App = apps.apps.single { it.id == "btc15m" }
        assertNull(unknown.up)
        assertNull("and the same for the device verdict", unknown.reachable.ok)
        // ⚠ AND THE FIX IS PER ROW NOW (decision 55). One row carries lines; the
        // others carry none, which is what makes the disclosure worth opening.
        val needsRetrofit = apps.apps.single { it.reachable.ok == false }
        assertEquals("jtyper", needsRetrofit.id)
        assertEquals(6, needsRetrofit.reachable.fix.size)
        assertTrue(
            "the `#` lines say which machine the next ones run on",
            needsRetrofit.reachable.fix.first().startsWith("# on huginn"),
        )
        assertFalse("the transition is still outstanding on this host", apps.retrofitApplied)
    }
}
