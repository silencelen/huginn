package com.silencelen.huginn

import com.silencelen.huginn.data.ManifestSession
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectLead
import com.silencelen.huginn.data.ProjectDeleted
import com.silencelen.huginn.data.ProjectEndRefusal
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectMember
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.SpawnFailure
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

    /** A live member row, as `joinMembers` produces one. */
    private fun live(
        role: String,
        status: String? = "idle",
        state: String? = "idle",
        needsYou: Boolean = false,
        waitingFor: String? = null,
        alive: Boolean = true,
        present: Boolean = true,
        lead: Boolean = false,
        ended: Boolean = false,
        pending: Int = 0,
    ) = ProjectLive(
        role = role,
        name = "statusflap-$role",
        claudeName = "statusflap/$role",
        sessionId = "sid-$role",
        present = present,
        alive = alive,
        status = status,
        waitingFor = waitingFor,
        needsYou = needsYou,
        state = state,
        pendingSends = pending,
        endedAt = if (ended) nowSec - 300 else null,
        spawnedAt = nowSec - 3_600,
        lead = lead,
        checkedAt = nowSec,
    )

    private fun row(
        members: Int = 0,
        alive: Int = 0,
        busy: Int = 0,
        waiting: Int = 0,
        status: String = "active",
        name: String = "Status page flap",
        id: String = "p1",
        slug: String = "statusflap",
        manifestRev: Int = 0,
        manifestSummary: String? = null,
        untagged: Boolean = false,
        endedReason: String? = null,
    ) = ProjectRow(
        id = id, name = name, slug = slug, kind = "software", status = status,
        cwd = "/root/netplan/status-page",
        memberCount = members, alive = alive, busy = busy, waiting = waiting,
        lead = ProjectLead(name = "$slug-lead", claudeName = "$slug/lead", present = true),
        manifestRev = manifestRev, manifestSummary = manifestSummary, untaggedSeen = untagged,
        endedReason = endedReason, createdAt = nowSec - 86_400, updatedAt = nowSec - 100, rev = 3,
    )

    // ------------------------------------------------------- the vocabularies

    @Test
    fun `the kinds and statuses are the daemon's closed lists, spelled out`() {
        assertEquals(
            listOf("software", "infra", "hardware", "docs", "research", "other"),
            ProjectRules.KINDS,
        )
        assertEquals(
            listOf("drafting", "proposed", "active", "paused", "archived"),
            ProjectRules.STATUSES,
        )
    }

    @Test
    fun `a word from a newer daemon is NULL, never coerced into a real one`() {
        // ⚠ `other` IS A REAL KIND AND MEANS SOMETHING. Folding an unknown word
        // into it would draw a chip that claims the lead said something it did
        // not — unlike an app's kind, where `other` IS the daemon's fallback.
        assertNull(ProjectRules.kindWord("quantum"))
        assertNull(ProjectRules.kindWord(null))
        assertEquals("hardware", ProjectRules.kindWord("Hardware"))
        assertNull(ProjectRules.statusWord("compacting"))
        assertEquals("proposed", ProjectRules.statusWord("proposed"))
        assertNull(ProjectRules.statusWords("compacting"))
        assertEquals("waiting for your answer", ProjectRules.statusWords("proposed"))
    }

    @Test
    fun `the transitions are the ones the daemon names, and archived is terminal`() {
        assertTrue(ProjectRules.canTransition("drafting", "proposed"))
        assertTrue(ProjectRules.canTransition("proposed", "drafting"), "Discard")
        assertTrue(ProjectRules.canTransition("proposed", "active"), "Spawn")
        assertTrue(ProjectRules.canTransition("active", "paused"))
        assertTrue(ProjectRules.canTransition("paused", "active"))
        assertFalse(ProjectRules.canTransition("archived", "active"))
        // ⚠ An ACTIVE project is never re-proposed: the members are already
        // running, and a lead recapping its plan must not spawn a second cluster.
        assertFalse(ProjectRules.canTransition("active", "proposed"))
        assertFalse(ProjectRules.canTransition("active", "nonsense"))
    }

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

    @Test
    fun `a slug survives tmux, and the display name it came from does not have to`() {
        assertEquals("lora-sensor-stick", ProjectRules.slugFor("LoRa Sensor Stick"))
        // ⚠ NO DOTS EVER. tmux rewrites '.' to '_' and exits 0, so a name with one
        // is a name that comes back different from what was asked for.
        assertEquals("v2-1-board", ProjectRules.slugFor("v2.1 board"))
        assertEquals("", ProjectRules.slugFor("..."), "a name with nothing usable in it produces no slug")
        assertTrue(ProjectRules.slugProblem("")!!.contains("usable slug"))
        assertTrue(ProjectRules.slugProblem("login")!!.contains("reserved"))
        assertEquals(
            "there is already a project with that slug",
            ProjectRules.slugProblem("lora", listOf("LORA")),
        )
        assertNull(ProjectRules.slugProblem("lora-stick", listOf("other")))
        assertEquals(
            "a slug is lowercase letters, digits and dashes, 1-24 characters",
            ProjectRules.slugProblem("Not_A_Slug"),
        )
    }

    @Test
    fun `the two namespaces are composed in one place each`() {
        assertEquals("lora-stick-docs", ProjectRules.tmuxNameFor("lora-stick", "docs"))
        assertEquals("lora-stick/docs", ProjectRules.claudeNameFor("lora-stick", "docs"))
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

    @Test
    fun `a brief is required, because it IS the lead's first message`() {
        // ⚠ THE DAEMON REFUSES A CREATE WITHOUT ONE. A project made with no brief
        // is a session sitting there with nothing to size.
        assertEquals(
            "a project needs a brief — it is the whole first message the lead gets",
            ProjectRules.briefProblem("   "),
        )
        assertNull(ProjectRules.briefProblem("Find out why the status page flaps."))
        assertEquals(4_000, ProjectRules.MAX_BRIEF)
        assertEquals("a brief is at most 4000 characters", ProjectRules.briefProblem("x".repeat(4_001)))
    }

    @Test
    fun `a kind is required and comes off the closed list`() {
        assertNull(ProjectRules.kindProblem("infra"))
        assertEquals(
            "kind is one of software, infra, hardware, docs, research, other",
            ProjectRules.kindProblem("quantum"),
        )
        assertEquals(
            "kind is one of software, infra, hardware, docs, research, other",
            ProjectRules.kindProblem(null),
        )
    }

    // ------------------------------------------------------------- the marks

    @Test
    fun `a member's mark is a SESSION word, so both screens draw the same dot`() {
        assertEquals("running", ProjectRules.stateWord(live("db", status = "busy")))
        assertEquals("idle", ProjectRules.stateWord(live("docs", status = "idle")))
        assertEquals("attention", ProjectRules.stateWord(live("web", status = "waiting")))
    }

    @Test
    fun `the NATIVE status wins over the title hook's state`() {
        // Two vocabularies meet on a member row. `status` is Claude Code's own
        // pid-keyed registry; `state` is the hook. When they disagree the registry
        // is the one that watched the process.
        val m = live("db", status = "busy", state = "idle")
        assertEquals("running", ProjectRules.stateWord(m))
        assertEquals("working", ProjectRules.memberWords(m))
    }

    @Test
    fun `needsYou beats whatever either registry last recorded`() {
        // The daemon already promotes a live permission dialog to `attention` on a
        // session row; a member that says needsYou while its status is stale must
        // not be drawn as quietly working.
        val m = live("web", status = "busy", state = "running", needsYou = true)
        assertEquals("attention", ProjectRules.stateWord(m))
        assertTrue(ProjectRules.needsYou(m))
        assertEquals("needs you", ProjectRules.memberWords(m))
    }

    @Test
    fun `waiting says what it is waiting FOR, because that is a different amount of help`() {
        val m = live("web", status = "waiting", needsYou = true, waitingFor = "input needed")
        assertEquals("needs you — input needed", ProjectRules.memberWords(m))
    }

    @Test
    fun `an unknown status word is NULL, and the hook is what is left to draw with`() {
        // ⚠ A newer daemon inventing a fifth word must not be picked up by an
        // `else` branch and drawn as idle. Nothing in the native row is usable, so
        // the title hook's own word is what remains.
        val m = live("probe", status = "compacting-or-whatever-comes-next", state = "idle")
        assertEquals("idle", ProjectRules.stateWord(m))
        val blind = live("probe", status = "compacting-or-whatever-comes-next", state = "teleporting")
        assertNull(ProjectRules.stateWord(blind))
        assertEquals("no state yet", ProjectRules.memberWords(blind))
        assertNull(ProjectRules.stateWord(live("probe", status = null, state = null)))
    }

    @Test
    fun `an ended member has no mark at all, and neither has one that is simply gone`() {
        // Not idle — gone. A grey dot beside a live grey dot says the wrong thing.
        val over = live("docs", status = "idle", ended = true)
        assertTrue(ProjectRules.ended(over))
        assertNull(ProjectRules.stateWord(over))
        assertEquals("ended", ProjectRules.memberWords(over))

        // ⚠ AND `alive:false` WITH NO TMUX SESSION IS NOT `idle`. joinMembers
        // reports exactly this for a member whose pane has disappeared.
        val missing = live("api", status = null, state = null, alive = false, present = false)
        assertTrue(ProjectRules.gone(missing))
        assertNull(ProjectRules.stateWord(missing))
        assertEquals("not running", ProjectRules.memberWords(missing))
    }

    // ------------------------------------------------------------ the order

    @Test
    fun `the one asking for something is at the top, and the lead is right under it`() {
        // ⚠ THE WHOLE POINT OF THE SCREEN. Twelve rows in role order put the
        // session sitting on a permission dialog wherever the alphabet leaves it —
        // and bury the lead, which is the session the owner talks to.
        val rows = listOf(
            live("apply", status = "idle"),
            live("docs", status = "idle", ended = true),
            live("build", status = "busy"),
            live("zzz", status = "waiting", needsYou = true),
            live("probe", status = "teleporting", state = "teleporting"),
            live("lead", status = "idle", lead = true),
        )
        assertEquals(
            listOf("zzz", "lead", "build", "apply", "probe", "docs"),
            ProjectRules.ordered(rows).map { it.role },
        )
    }

    @Test
    fun `a lead that needs you is still first, because the ask outranks the office`() {
        val rows = listOf(
            live("db", status = "busy"),
            live("lead", status = "waiting", needsYou = true, lead = true),
        )
        assertEquals(listOf("lead", "db"), ProjectRules.ordered(rows).map { it.role })
    }

    // ----------------------------------------------------------- the rollup

    @Test
    fun `the rollup renders the DAEMON'S counts and never recomputes them`() {
        // ⚠ `alive`, `busy` and `waiting` are a join across three registries no
        // client can perform, and they exclude the lead. Rendering them is the
        // client's whole job here.
        assertEquals("3 of 5 working · 1 needs you", ProjectRules.rollupWords(row(members = 5, alive = 5, busy = 3, waiting = 1)))
        val r = ProjectRules.rollup(row(members = 5, alive = 5, busy = 3, waiting = 1))
        assertEquals(5, r.members)
        assertEquals(3, r.busy)
    }

    @Test
    fun `the denominator is the LIVE members, and the rest get their own clause`() {
        // "3 of 12 working" on a cluster where nine have finished reads as a
        // project in trouble. The ones that are no longer running go at the end.
        assertEquals(
            "1 of 3 working · 1 needs you · 1 not running",
            ProjectRules.rollupWords(row(members = 4, alive = 3, busy = 1, waiting = 1)),
        )
        assertEquals("no members yet", ProjectRules.rollupWords(row(members = 0)))
        // A cluster whose members are all gone says so rather than "0 of 0".
        assertEquals("none running", ProjectRules.rollupWords(row(members = 3, alive = 0)))
    }

    @Test
    fun `two waiting members need you, one needs you`() {
        assertEquals(
            "0 of 2 working · 2 need you",
            ProjectRules.rollupWords(row(members = 2, alive = 2, busy = 0, waiting = 2)),
        )
    }

    @Test
    fun `the same rollup off a live list drops the lead, so the two paths agree`() {
        val rows = listOf(
            live("lead", status = "busy", lead = true),
            live("db", status = "busy"),
            live("web", status = "waiting", needsYou = true),
            live("api", status = "idle"),
        )
        // The lead is busy and is NOT counted — three members, one working.
        assertEquals("1 of 3 working · 1 needs you", ProjectRules.rollupWords(rows))
        assertEquals(3, ProjectRules.rollup(rows).members)
    }

    // --------------------------------------------------------- the manifest

    @Test
    fun `the summary is one line, clipped, and there is no fallback to the scope`() {
        assertEquals(
            "Two sessions: docs writes the README, repo runs the checks.",
            ProjectRules.manifestSummary(
                ProjectManifest(summary = " Two sessions: docs writes the README,\n repo runs the checks. "),
            ),
        )
        // ⚠ THE SCOPE IS A PARAGRAPH AND THIS SLOT IS A LINE. A card that put the
        // scope here would eat the rest of the card.
        assertNull(ProjectRules.manifestSummary(ProjectManifest(scope = "a long paragraph about the cluster")))
        assertNull(ProjectRules.manifestSummary(null))
    }

    @Test
    fun `a long summary is clipped rather than allowed to become a paragraph`() {
        val long = "x".repeat(400)
        val out = ProjectRules.manifestSummary(ProjectManifest(summary = long))!!
        assertEquals(ProjectRules.SUMMARY_MAX, out.length)
        assertTrue(out.endsWith("…"), out.takeLast(5))
    }

    @Test
    fun `the proposal says how many and which roles`() {
        val m = ProjectManifest(
            rev = 1,
            summary = "three sessions",
            sessions = listOf(
                ManifestSession(role = "docs", firstPrompt = "write it"),
                ManifestSession(role = "repo", firstPrompt = "check it"),
                ManifestSession(role = "fw", firstPrompt = "flash it"),
            ),
        )
        assertEquals("3 sessions: docs, repo, fw", ProjectRules.manifestWords(m))
        assertEquals(
            "1 session: docs",
            ProjectRules.manifestWords(m.copy(sessions = m.sessions.take(1))),
        )
        assertEquals("no sessions in this proposal", ProjectRules.manifestWords(ProjectManifest()))
    }

    @Test
    fun `a proposed session's settings read in order, and null means the host's own`() {
        // ⚠ The daemon turns a model, effort or mode word it does not recognise
        // into null rather than losing the whole proposal over it — so an empty
        // cell is the DEFAULT and is said as one.
        assertEquals(
            "opus · high effort · act mode",
            ProjectRules.sessionWords(
                ManifestSession(role = "db", firstPrompt = "go", model = "opus", effort = "high", mode = "act"),
            ),
        )
        assertEquals(
            "the host's own defaults",
            ProjectRules.sessionWords(ManifestSession(role = "web", firstPrompt = "go")),
        )
        assertNull(ProjectRules.sessionCwd(ManifestSession(role = "web", firstPrompt = "go")))
        assertEquals(
            "/root/netplan/status-page/db",
            ProjectRules.sessionCwd(
                ManifestSession(role = "db", firstPrompt = "go", cwd = "/root/netplan/status-page/db"),
            ),
        )
    }

    @Test
    fun `an untagged proposal is the silent failure, and it is said`() {
        // ⚠ The lead believes it proposed something; the daemon ignored the block
        // because its fence carried no tag; to the owner, nothing happened. It is
        // also the injection signal, which is why it is never swallowed.
        val w = ProjectRules.manifestCaution(ProjectManifest(untaggedSeen = true))
        assertTrue(w != null && w.contains("without its tag"), "said: $w")
        assertNull(ProjectRules.manifestCaution(ProjectManifest(summary = "fine")))
        assertNull(ProjectRules.manifestCaution(null as ProjectManifest?))
        // And the row carries the same flag, because the tree sees rows.
        assertTrue(ProjectRules.manifestCaution(row(untagged = true)) != null)
        assertNull(ProjectRules.manifestCaution(row()))
    }

    @Test
    fun `a card is offered on a PROPOSED project and on nothing else`() {
        // ⚠ THE STATUS IS THE GATE. The manifest stays on the record after a
        // Discard so an editor can reopen it, and after a Spawn as the record of
        // what was made — offering Spawn on either would be offering to create a
        // cluster that is already running.
        val m = ProjectManifest(rev = 2, summary = "two sessions", sessions = listOf(ManifestSession("docs", "go")))
        val base = Project(id = "p1", name = "Status page flap", status = "proposed", manifest = m)
        assertTrue(ProjectRules.hasProposal(base))
        assertFalse(ProjectRules.hasProposal(base.copy(status = "active")))
        assertFalse(ProjectRules.hasProposal(base.copy(status = "drafting")))
        assertFalse(ProjectRules.hasProposal(base.copy(manifest = ProjectManifest(rev = 3))), "no sessions in it")
        assertTrue(ProjectRules.hasProposal(row(status = "proposed", manifestRev = 2)))
        assertFalse(ProjectRules.hasProposal(row(status = "proposed", manifestRev = 0)))
    }

    @Test
    fun `a rev that has already been spawned says so before the button is pressed`() {
        assertTrue(ProjectRules.alreadySpawned(ProjectManifest(rev = 2, spawnedRev = 2)))
        assertFalse(ProjectRules.alreadySpawned(ProjectManifest(rev = 3, spawnedRev = 2)))
        assertFalse(ProjectRules.alreadySpawned(ProjectManifest(rev = 0, spawnedRev = 0)))
        assertFalse(ProjectRules.alreadySpawned(null))
    }

    @Test
    fun `a ROW answers the same question, without fetching the project`() {
        // ⚠ THE LIST HAS NO MANIFEST ON IT. `GET /v1/projects` carries rows, and a
        // tree that had to GET every project to find out whether a proposal was
        // already carried out would make one call per row to draw one list. The
        // daemon puts `spawnedRev` beside `manifestRev` on the row for exactly
        // this, and the two overloads must not be able to disagree.
        assertTrue(ProjectRules.alreadySpawned(ProjectRow(manifestRev = 2, spawnedRev = 2)))
        assertFalse(ProjectRules.alreadySpawned(ProjectRow(manifestRev = 3, spawnedRev = 2)))
        // A rev of 0 is "no proposal has ever arrived", which is not a spawn.
        assertFalse(ProjectRules.alreadySpawned(ProjectRow(manifestRev = 0, spawnedRev = 0)))
        // A daemon older than this field sends no `spawnedRev` at all; the default
        // must read as "not spawned" rather than as "already done", because the
        // wrong way round hides Spawn on a proposal nobody has answered.
        assertFalse(ProjectRules.alreadySpawned(ProjectRow(manifestRev = 1)))
    }

    // ------------------------------------------------------------ the spawn

    @Test
    fun `a partial spawn reads as partial, not as a failure`() {
        // ⚠ Spawning is a loop over tmux: the third role failing does not un-spawn
        // the first two, the answer is still HTTP 200, and a headline saying
        // "failed" would send somebody looking for sessions that are working.
        val r = SpawnResult(
            ok = false,
            spawned = listOf(
                ProjectMember(role = "db", name = "statusflap-db", claudeName = "statusflap/db"),
                ProjectMember(role = "web", name = "statusflap-web", claudeName = "statusflap/web"),
            ),
            failed = listOf(SpawnFailure(role = "docs", reason = "duplicate session: statusflap-docs")),
        )
        assertEquals("2 of 3 started · 1 failed", ProjectRules.spawnWords(r))
        assertEquals(
            listOf("docs — duplicate session: statusflap-docs"),
            ProjectRules.spawnFailures(r),
            "the daemon's own sentence, verbatim: the fix is in it",
        )
        assertEquals(listOf("statusflap/db", "statusflap/web"), ProjectRules.spawnedNames(r))
    }

    @Test
    fun `the whole-success and whole-failure headlines say which they are`() {
        assertEquals(
            "1 member started",
            ProjectRules.spawnWords(SpawnResult(ok = true, spawned = listOf(ProjectMember(role = "a")))),
        )
        assertEquals(
            "2 members started",
            ProjectRules.spawnWords(
                SpawnResult(ok = true, spawned = listOf(ProjectMember(role = "a"), ProjectMember(role = "b"))),
            ),
        )
        assertEquals(
            "the member did not start",
            ProjectRules.spawnWords(SpawnResult(failed = listOf(SpawnFailure("a", "no")))),
        )
        assertEquals(
            "none of the 2 started",
            ProjectRules.spawnWords(
                SpawnResult(failed = listOf(SpawnFailure("a", "no"), SpawnFailure("b", "no"))),
            ),
        )
        assertEquals("nothing came back", ProjectRules.spawnWords(SpawnResult()))
    }

    // ---------------------------------------------------------- the project

    @Test
    fun `the list holds still, so the row you tapped is the row that is there`() {
        // The ScratchpadRules decision, not the archive's: this is a PLACE. Live
        // first, then by name, id last so two that read the same never swap.
        val a = row(id = "a", name = "Zephyr", slug = "zephyr")
        val b = row(id = "b", name = "alpha", slug = "alpha")
        val c = row(id = "c", name = "Midway", slug = "midway", status = "archived")
        assertEquals(listOf("b", "a", "c"), ProjectRules.orderedProjects(listOf(a, c, b)).map { it.id })
    }

    @Test
    fun `an archived project is not live, whatever else is on the row`() {
        assertFalse(ProjectRules.live(row(status = "archived")))
        assertTrue(ProjectRules.live(row(status = "paused")))
        // A status this client has never heard of is not archived either — the one
        // terminal word is spelled out and nothing else is guessed into it.
        assertTrue(ProjectRules.live(row(status = "hibernating")))
    }

    @Test
    fun `a project with no name still has to be distinguishable from the one above`() {
        assertEquals("statusflap", ProjectRules.label(row(name = "  ")))
        assertEquals("Status page flap", ProjectRules.label(row(name = " Status page\tflap ")))
        assertEquals("0123abcd", ProjectRules.label(Project(id = "0123abcd-0000-4000", name = "  ")))
    }

    @Test
    fun `the lead is named by its PEER name, which is the one somebody would type`() {
        assertEquals("led by statusflap/lead", ProjectRules.leadWords(row()))
        assertNull(ProjectRules.leadWords(row().copy(lead = null)))
        assertNull(ProjectRules.leadWords(row().copy(lead = ProjectLead())))
    }

    // ------------------------------------------- membership, edited by hand

    /**
     * ⚠ THE LEAD CANNOT BE DROPPED. The daemon answers 409 — *"the lead is the
     * project — delete the project instead"* — and this is what stops the verb
     * being OFFERED, which is the difference between a control and a trap. A
     * project whose lead had been dropped would keep a brief, a manifest and a
     * peer namespace all belonging to a session no longer in it.
     */
    @Test
    fun `the lead is not droppable, and everyone else is`() {
        assertFalse(ProjectRules.canDrop(live("lead", lead = true)))
        assertTrue(ProjectRules.canDrop(live("firmware")))
        // Belt and braces: the role word alone disqualifies it, in case a daemon
        // ever sends the lead's row without the flag.
        assertFalse(ProjectRules.canDrop(live("lead", lead = false)))
    }

    /**
     * What "Add member" may offer: everything running that this project does not
     * already hold.
     *
     * ⚠ IT CANNOT KNOW ABOUT OTHER PROJECTS AND MUST NOT PRETEND TO. A session in
     * a different cluster looks free from here; the daemon holds that join and
     * answers 409 NAMING the other project, which is the entire fix — and far
     * better than a row quietly missing from a picker with no explanation.
     */
    @Test
    fun `adoptable is every live session this project does not already hold`() {
        val members = listOf(live("lead", lead = true), live("firmware"))
        val running = listOf("statusflap-lead", "statusflap-firmware", "scratch", "jtyper")
        assertEquals(listOf("scratch", "jtyper"), ProjectRules.adoptable(running, members))
    }

    @Test
    fun `a session in another project is still offered, because only the daemon knows`() {
        val members = listOf(live("lead", lead = true))
        assertEquals(
            listOf("someone-elses-firmware"),
            ProjectRules.adoptable(listOf("statusflap-lead", "someone-elses-firmware"), members),
        )
    }

    @Test
    fun `a blank session name is never offered`() {
        assertEquals(listOf("scratch"), ProjectRules.adoptable(listOf("", "   ", "scratch"), emptyList()))
    }

    /**
     * The role grammar the adopt form pre-checks with is the daemon's, spelled
     * out as a literal — `lib/projects.js roleProblem`, in another language.
     */
    @Test
    fun `the adopt form refuses what the daemon would refuse`() {
        val taken = listOf("firmware")
        assertNull(ProjectRules.roleProblem("pcb", taken))
        assertEquals("a member needs a role", ProjectRules.roleProblem("  ", taken))
        assertEquals("\"lead\" is the lead session's own role", ProjectRules.roleProblem("lead", taken))
        assertEquals("there is already a member with that role", ProjectRules.roleProblem("firmware", taken))
        // The grammar is `^[a-z0-9][a-z0-9-]{0,15}$` — a dot is the interesting
        // one: tmux rewrites it to '_' AND STILL EXITS 0, so the session that
        // comes back is not the one that was asked for.
        assertTrue(ProjectRules.roleProblem("fw.old", taken)!!.isNotBlank())
        assertTrue(ProjectRules.roleProblem("Firmware", taken)!!.isNotBlank())
        assertTrue(ProjectRules.roleProblem("a".repeat(17), taken)!!.isNotBlank())
    }

    /** The twelve-member cap, checked before the trip rather than after it. */
    @Test
    fun `the cap is checked before the request`() {
        assertNull(ProjectRules.capProblem(11, 1))
        assertEquals("a project holds at most 12 members", ProjectRules.capProblem(12, 1))
    }

    /**
     * ⚠⚠ "PROJECT REMOVED" ALONE IS A LIE BY OMISSION when a graceful delete
     * could not reach every member. Those sessions are ALIVE, with no project
     * behind them, and nothing else on the host will ever mention them again —
     * the record they belonged to has gone.
     */
    @Test
    fun `a delete says what it could not wind down, by name`() {
        val done = ProjectDeleted(
            ok = true,
            ended = listOf("lora-pcb", "lora-lead"),
            mode = "graceful",
            refused = listOf(
                ProjectEndRefusal("lora-firmware", "lora/firmware", "a dialog is open on the screen"),
                ProjectEndRefusal("lora-test", "lora/test", "a question is waiting on the screen"),
            ),
        )
        assertEquals(
            "Project removed · ended 2 · 2 sessions were not wound down: lora-firmware, lora-test",
            ProjectRules.deletedWords(done),
        )
    }

    /** One is singular, and the word has to agree or the line reads as a bug. */
    @Test
    fun `one refusal is one session`() {
        val done = ProjectDeleted(
            ok = true,
            ended = emptyList(),
            mode = "graceful",
            refused = listOf(ProjectEndRefusal("lora-firmware", "lora/firmware", "a dialog is open")),
        )
        assertEquals(
            "Project removed · 1 session was not wound down: lora-firmware",
            ProjectRules.deletedWords(done),
        )
    }

    /**
     * ⚠ "NOT WOUND DOWN", NEVER "FAILED". Nothing broke — those sessions are
     * working — and the word has to leave a reader expecting to find them rather
     * than expecting wreckage.
     */
    @Test
    fun `the words do not read as a breakage`() {
        val line = ProjectRules.deletedWords(
            ProjectDeleted(refused = listOf(ProjectEndRefusal("a", "p/a", "busy"))),
        ).lowercase()
        for (word in listOf("failed", "error", "crashed", "lost")) {
            assertFalse(word in line, "'$word' would send somebody looking for wreckage: $line")
        }
    }

    /** The ordinary delete is unchanged: no refusals, no clause. */
    @Test
    fun `a clean delete says only what it did`() {
        assertEquals("Project removed", ProjectRules.deletedWords(ProjectDeleted(ok = true)))
        assertEquals(
            "Project removed · ended 3",
            ProjectRules.deletedWords(ProjectDeleted(ok = true, ended = listOf("a", "b", "c"))),
        )
    }

    /** A daemon too old to send the key looks exactly like nothing refused. */
    @Test
    fun `an older daemon's answer reads as a clean delete`() {
        assertEquals(
            "Project removed · ended 1",
            ProjectRules.deletedWords(ProjectDeleted(ok = true, ended = listOf("a"), mode = "now")),
        )
    }
}
