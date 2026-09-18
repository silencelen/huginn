package com.silencelen.huginn

import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectMember
import com.silencelen.huginn.data.ProjectPeer
import com.silencelen.huginn.data.SpawnMemberResult
import com.silencelen.huginn.data.SpawnResult
import com.silencelen.huginn.ui.ProjectRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a project row says, and who the screen puts first.
 *
 * ⚠ THE GRAMMARS ARE ASSERTED AS LITERALS, spelled out rather than built from
 * anything the implementation also uses — the [ScratchpadRulesTest] precedent,
 * for its reason: the authority is `lib/projects.js`, in another language, and a
 * test that round-tripped through a shared helper would pass happily while the
 * two sides had stopped agreeing about what a role may be called.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectRulesTest {

    private val nowSec = 1_789_460_000L

    private fun member(
        role: String,
        state: String? = "idle",
        needsYou: Boolean? = false,
        ended: Boolean = false,
        pending: Int? = 0,
    ) = ProjectMember(
        name = "statusflap/$role",
        role = role,
        sessionId = "0123abcd-0000-4000-8000-0000000000$role".take(36),
        spawnedAt = nowSec - 3_600,
        state = state,
        needsYou = needsYou,
        pendingSends = pending,
        lastActivityTs = nowSec - 600,
        endedAt = if (ended) nowSec - 300 else null,
    )

    // ------------------------------------------------------------- the names

    @Test
    fun `a project needs a name that is actually a name`() {
        assertEquals("a project needs a name", ProjectRules.nameProblem(""))
        assertEquals("a project needs a name", ProjectRules.nameProblem("   "))
        assertNull(ProjectRules.nameProblem("LoRa sensor stick"))
    }

    @Test
    fun `a double quote is refused rather than quietly removed`() {
        // The daemon's persona QUOTES the project's name at the lead session. A
        // name carrying one closes the quote early and leaves its tail reading as
        // instructions — the same reason a page name refuses one.
        assertEquals(
            "a project name cannot contain a double quote",
            ProjectRules.nameProblem("""Ship "v2""""),
        )
    }

    @Test
    fun `a name is one line and measured after normalisation`() {
        assertEquals("LoRa sensor stick", ProjectRules.cleanName("LoRa\nsensor   stick"))
        // 60 characters of name padded with tabs is a 60-character name. Measuring
        // the raw string refuses something the daemon would have taken, and there
        // is no way to argue with an editor that says no.
        val sixty = "x".repeat(60)
        assertNull(ProjectRules.nameProblem("\t$sixty \n"))
        assertEquals(
            "a project name is at most 60 characters",
            ProjectRules.nameProblem("x".repeat(61)),
        )
    }

    @Test
    fun `names collide case-insensitively, because that is how a list reads`() {
        assertEquals(
            "there is already a project with that name",
            ProjectRules.nameProblem("lora Sensor Stick", listOf("LoRa sensor stick")),
        )
    }

    // -------------------------------------------------------------- the role

    @Test
    fun `a role is lowercase, dashed, and never contains a dot`() {
        // ⚠ THE DOT IS THE ONE THAT BITES. A role becomes half of a tmux session
        // name, and tmux rewrites a '.' to '_' AND STILL EXITS 0 — so a member
        // called "web.api" comes back as a session nobody asked for.
        assertNull(ProjectRules.roleProblem("docs"))
        assertNull(ProjectRules.roleProblem("web-api"))
        assertNull(ProjectRules.roleProblem("r2"))
        val grammar = "a role is lowercase letters, digits and dashes, up to 16 characters"
        assertEquals(grammar, ProjectRules.roleProblem("web.api"))
        assertEquals(grammar, ProjectRules.roleProblem("web_api"))
        assertEquals(grammar, ProjectRules.roleProblem("Docs"))
        assertEquals(grammar, ProjectRules.roleProblem("-docs"))
        assertEquals(grammar, ProjectRules.roleProblem("x".repeat(17)))
        assertNull(ProjectRules.roleProblem("x".repeat(16)), "sixteen is legal; seventeen is not")
    }

    @Test
    fun `lead is the lead session's own role and nobody else's`() {
        assertEquals("\"lead\" is the lead session's own role", ProjectRules.roleProblem("lead"))
        assertEquals(
            "there is already a member with that role",
            ProjectRules.roleProblem("docs", listOf("docs", "repo")),
        )
    }

    @Test
    fun `the member cap is checked before the round trip`() {
        assertNull(ProjectRules.capProblem(existing = 10, adding = 2))
        assertEquals(
            "a project holds at most 12 members",
            ProjectRules.capProblem(existing = 10, adding = 3),
        )
        assertEquals(12, ProjectRules.MAX_MEMBERS)
    }

    @Test
    fun `a directory must be absolute, and a blank one is the host's own`() {
        assertNull(ProjectRules.cwdProblem(""), "blank means the daemon's WORKDIR")
        assertNull(ProjectRules.cwdProblem("/root/netplan"))
        assertEquals(
            "a project directory must be an absolute path",
            ProjectRules.cwdProblem("netplan/dev"),
        )
    }

    // ------------------------------------------------------------- the marks

    @Test
    fun `a member's mark is a SESSION word, so both screens draw the same dot`() {
        assertEquals("running", ProjectRules.stateWord(member("db", state = "running")))
        assertEquals("idle", ProjectRules.stateWord(member("docs", state = "idle")))
        assertEquals("attention", ProjectRules.stateWord(member("web", state = "attention")))
    }

    @Test
    fun `needsYou beats whatever the state file last recorded`() {
        // The daemon already promotes a live permission dialog to `attention` on a
        // session row; a member that says needsYou while its state is stale must
        // not be drawn as quietly working.
        val m = member("web", state = "running", needsYou = true)
        assertEquals("attention", ProjectRules.stateWord(m))
        assertTrue(ProjectRules.needsYou(m))
        assertEquals("needs you", ProjectRules.memberWords(m))
    }

    @Test
    fun `an unknown state word is NULL, never guessed into the else branch`() {
        // ⚠ A newer daemon inventing a fifth word must leave the row unmarked. An
        // `else -> idle` would draw a session that is doing something unknown as a
        // session that is doing nothing, which is the wrong way round.
        val m = member("probe", state = "teleporting", needsYou = null)
        assertNull(ProjectRules.stateWord(m))
        assertEquals("no state yet", ProjectRules.memberWords(m))
        assertNull(ProjectRules.stateWord(member("probe", state = null, needsYou = null)))
    }

    @Test
    fun `waiting is the registry's word for needs-you, and is mapped`() {
        // The native session registry started emitting `waiting` beside busy/idle,
        // with `waitingFor: "input needed"`. It means exactly attention.
        assertEquals("attention", ProjectRules.stateWord(member("web", state = "waiting", needsYou = null)))
        assertEquals("running", ProjectRules.stateWord(member("db", state = "busy", needsYou = null)))
    }

    @Test
    fun `an ended member has no mark at all`() {
        // Not idle — gone. A grey dot beside a live grey dot says the wrong thing.
        val m = member("docs", state = "idle", ended = true)
        assertTrue(ProjectRules.ended(m))
        assertNull(ProjectRules.stateWord(m))
        assertEquals("ended", ProjectRules.memberWords(m))
    }

    // ------------------------------------------------------------ the order

    @Test
    fun `the one asking for something is at the top`() {
        // ⚠ THE WHOLE POINT OF THE SCREEN. Twelve rows in role order put the
        // session sitting on a permission dialog wherever the alphabet leaves it.
        val rows = listOf(
            member("apply", state = "idle"),
            member("docs", state = "idle", ended = true),
            member("build", state = "running"),
            member("zzz", state = "attention"),
            member("probe", state = "teleporting", needsYou = null),
        )
        assertEquals(
            listOf("zzz", "build", "apply", "probe", "docs"),
            ProjectRules.ordered(rows).map { it.role },
        )
    }

    // ----------------------------------------------------------- the rollup

    @Test
    fun `the rollup is the sentence the row is read for`() {
        val rows = listOf(
            member("db", state = "running"),
            member("etl", state = "running"),
            member("cdn", state = "running"),
            member("web", state = "attention"),
            member("api", state = "idle"),
        )
        assertEquals("3 of 5 working · 1 needs you", ProjectRules.rollupWords(rows))
    }

    @Test
    fun `the denominator is the LIVE members, and the ended ones get their own clause`() {
        // "3 of 12 working" on a cluster where seven finished hours ago reads as a
        // project in trouble. The ended count is information, at the end.
        val rows = listOf(
            member("db", state = "running"),
            member("web", state = "attention"),
            member("probe", state = "teleporting", needsYou = null),
            member("docs", state = "idle", ended = true),
        )
        assertEquals("1 of 3 working · 1 needs you · 1 ended", ProjectRules.rollupWords(rows))
        val r = ProjectRules.rollup(rows)
        assertEquals(3, r.live)
        assertEquals(1, r.ended)
    }

    @Test
    fun `two waiting members need you, one needs you`() {
        assertEquals(
            "0 of 2 working · 2 need you",
            ProjectRules.rollupWords(listOf(member("a", state = "attention"), member("b", state = "attention"))),
        )
        assertEquals("no members yet", ProjectRules.rollupWords(emptyList()))
    }

    // --------------------------------------------------------- the manifest

    @Test
    fun `the summary is one line, the daemon's when it has one`() {
        assertEquals(
            "Two sessions: docs writes the README, repo runs the checks.",
            ProjectRules.manifestSummary(
                ProjectManifest(summary = " Two sessions: docs writes the README,\n repo runs the checks. "),
            ),
        )
        // No summary: the first non-blank line of the body, never the whole body.
        assertEquals(
            "scope: rebuild the flap detector",
            ProjectRules.manifestSummary(
                ProjectManifest(text = "\n\nscope: rebuild the flap detector\nsessions:\n  docs\n  repo"),
            ),
        )
        assertNull(ProjectRules.manifestSummary(null))
        assertNull(ProjectRules.manifestSummary(ProjectManifest()))
    }

    @Test
    fun `a long summary is clipped rather than allowed to become a paragraph`() {
        val long = "x".repeat(400)
        val out = ProjectRules.manifestSummary(ProjectManifest(summary = long))!!
        assertEquals(ProjectRules.SUMMARY_MAX, out.length)
        assertTrue(out.endsWith("…"), out.takeLast(5))
    }

    @Test
    fun `an untagged proposal is the silent failure, and it is said`() {
        // ⚠ The lead believes it proposed something; the daemon ignored the block
        // because its fence carried no tag; to the owner, nothing happened.
        val w = ProjectRules.manifestCaution(ProjectManifest(untaggedSeen = true))
        assertTrue(w != null && w.contains("without its tag"), "said: $w")
        assertNull(ProjectRules.manifestCaution(ProjectManifest(summary = "fine")))
        assertNull(ProjectRules.manifestCaution(null))
    }

    @Test
    fun `a proposal is only worth a card when it has something in it`() {
        val base = Project(id = "p1", name = "Status page flap")
        assertFalse(ProjectRules.hasProposal(base))
        assertFalse(ProjectRules.hasProposal(base.copy(manifest = ProjectManifest(rev = 3))))
        assertTrue(ProjectRules.hasProposal(base.copy(manifest = ProjectManifest(summary = "two sessions"))))
        // An ended project's proposal is history, not a question.
        assertFalse(
            ProjectRules.hasProposal(
                base.copy(endedAt = nowSec, manifest = ProjectManifest(summary = "two sessions")),
            ),
        )
    }

    // ------------------------------------------------------------ the spawn

    @Test
    fun `a partial spawn reads as partial, not as a failure`() {
        // ⚠ Spawning is a loop over tmux: the fourth member failing does not
        // un-spawn the first three, and a headline saying "failed" would send
        // somebody looking for three sessions that are sitting there working.
        val r = SpawnResult(
            listOf(
                SpawnMemberResult("statusflap/db", ok = true),
                SpawnMemberResult("statusflap/web", ok = true),
                SpawnMemberResult(
                    "statusflap/docs",
                    ok = false,
                    error = "a session called statusflap-docs already exists",
                ),
            ),
        )
        assertEquals("2 of 3 started · 1 failed", ProjectRules.spawnWords(r))
        assertEquals(
            listOf("statusflap/docs — a session called statusflap-docs already exists"),
            ProjectRules.spawnFailures(r),
            "the daemon's own sentence, verbatim: the fix is in it",
        )
    }

    @Test
    fun `the whole-success and whole-failure headlines say which they are`() {
        assertEquals(
            "1 member started",
            ProjectRules.spawnWords(SpawnResult(listOf(SpawnMemberResult("a/b", ok = true)))),
        )
        assertEquals(
            "2 members started",
            ProjectRules.spawnWords(
                SpawnResult(listOf(SpawnMemberResult("a/b", ok = true), SpawnMemberResult("a/c", ok = true))),
            ),
        )
        assertEquals(
            "none of the 2 started",
            ProjectRules.spawnWords(
                SpawnResult(listOf(SpawnMemberResult("a/b"), SpawnMemberResult("a/c"))),
            ),
        )
        assertEquals("nothing came back", ProjectRules.spawnWords(SpawnResult()))
    }

    // ---------------------------------------------------------- the project

    @Test
    fun `the list holds still, so the row you tapped is the row that is there`() {
        // The ScratchpadRules decision, not the archive's: this is a PLACE. Live
        // first, then by name, id last so two that read the same never swap.
        val a = Project(id = "a", name = "Zephyr")
        val b = Project(id = "b", name = "alpha")
        val c = Project(id = "c", name = "Midway", endedAt = nowSec)
        assertEquals(listOf("b", "a", "c"), ProjectRules.orderedProjects(listOf(a, c, b)).map { it.id })
    }

    @Test
    fun `a project with no name still has to be distinguishable from the one above`() {
        assertEquals("0123abcd", ProjectRules.label(Project(id = "0123abcd-0000-4000", name = "  ")))
        assertEquals("Status page flap", ProjectRules.label(Project(id = "x", name = " Status page\tflap ")))
    }

    @Test
    fun `the lead is named when there is one`() {
        assertEquals(
            "led by statusflap/lead",
            ProjectRules.leadWords(Project(id = "x", lead = ProjectPeer("statusflap/lead"))),
        )
        assertNull(ProjectRules.leadWords(Project(id = "x")))
        assertNull(ProjectRules.leadWords(Project(id = "x", lead = ProjectPeer(""))))
    }
}
