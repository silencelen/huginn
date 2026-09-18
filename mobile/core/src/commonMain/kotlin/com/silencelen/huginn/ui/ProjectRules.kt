package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectMember
import com.silencelen.huginn.data.SpawnResult

/**
 * What a project row says, what order its members are in, and which of them the
 * owner is being asked about.
 *
 * In `:core` for the reason `docs/ADDING-A-FEATURE.md` gives and [ArchiveRules]
 * repeats: both shells draw these rows, and a rollup written twice is a sentence
 * the two apps will eventually disagree about in front of the same cluster.
 *
 * ⚠ THE DAEMON DECIDES, THIS EXPLAINS. Every grammar here is a MIRROR of
 * `lib/projects.js` — validating in the sheet turns a round trip into an inline
 * note, and the server still refuses what it refuses. Where the two disagree the
 * daemon is right and this is a bug, which is why the grammars are asserted as
 * literals rather than derived from anything (the [ScratchpadRules] precedent:
 * the writer is in another language, so a shared helper would let both sides
 * drift together and stay green).
 */
object ProjectRules {

    // ------------------------------------------------------------- the caps

    const val MAX_NAME: Int = 60

    /**
     * The most members one project may hold.
     *
     * Twelve concurrent sessions on one account is already the outer edge of what
     * a Max plan carries, which is why the daemon ALSO refuses a spawn while the
     * headroom arbiter's STOP sentinel is armed. This cap is the cheap half of
     * that pair: it stops a manifest asking for thirty before anything is typed.
     */
    const val MAX_MEMBERS: Int = 12

    /** The lead's role name. Reserved: nothing else may claim it. */
    const val LEAD_ROLE: String = "lead"

    // ------------------------------------------------------------ the names

    /** A project name as the daemon will store it: one line, no controls, trimmed. */
    fun cleanName(raw: String): String = normalized(raw).take(MAX_NAME)

    private fun normalized(raw: String): String =
        raw.map { if (it.code < 0x20 || it.code in 0x7f..0x9f) ' ' else it }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * Why this project cannot be called that, or null.
     *
     * The double quote is refused rather than stripped for the same reason a page
     * name refuses one: the daemon's persona QUOTES the project's name at the
     * lead ("You are the LEAD session of the Huginn project \"…\""), so a name
     * carrying one would close the quote early and leave its tail reading as
     * instructions.
     */
    fun nameProblem(raw: String, taken: List<String> = emptyList()): String? {
        val name = cleanName(raw)
        if (name.isEmpty()) return "a project needs a name"
        if ('"' in name) return "a project name cannot contain a double quote"
        // Measured after normalisation, the way the daemon measures it — refusing
        // a name the server would have taken is the worst way to be wrong here,
        // because there is no way to argue with it.
        if (normalized(raw).length > MAX_NAME) return "a project name is at most $MAX_NAME characters"
        val lower = name.lowercase()
        if (taken.any { cleanName(it).lowercase() == lower }) return "there is already a project with that name"
        return null
    }

    /**
     * Why this working directory cannot be used, or null.
     *
     * ⚠ TRUST IS NOT CHECKED HERE AND CANNOT BE. Claude Code refuses to start in
     * a directory it has not been trusted in, that trust lives in the CLI's own
     * state on the host, and the daemon answers the create with a 409 saying so.
     * That refusal is shown verbatim — see [com.silencelen.huginn.data.ProjectCreated].
     * All this rules out is a shape that could never work.
     */
    fun cwdProblem(raw: String): String? {
        val cwd = raw.trim()
        if (cwd.isEmpty()) return null              // blank = the daemon's own WORKDIR
        if (!cwd.startsWith("/")) return "a project directory must be an absolute path"
        return null
    }

    /**
     * Why this role name cannot be used, or null.
     *
     * The grammar is `^[a-z0-9][a-z0-9-]{0,15}$` — lowercase, no dot and no
     * underscore. Not arbitrary: the role becomes half of a tmux session name
     * (`<slug>-<role>`), and tmux rewrites a `.` to `_` AND STILL EXITS 0, so a
     * name carrying one comes back as a different session than the one asked for.
     */
    fun roleProblem(raw: String, taken: List<String> = emptyList()): String? {
        val role = raw.trim()
        if (role.isEmpty()) return "a member needs a role"
        if (role == LEAD_ROLE) return "\"lead\" is the lead session's own role"
        if (!ROLE_RE.matches(role)) {
            return "a role is lowercase letters, digits and dashes, up to 16 characters"
        }
        if (taken.any { it.trim() == role }) return "there is already a member with that role"
        return null
    }

    private val ROLE_RE = Regex("""^[a-z0-9][a-z0-9-]{0,15}$""")

    /** Why these members cannot be added, or null. The cap, checked before the trip. */
    fun capProblem(existing: Int, adding: Int): String? =
        if (existing + adding > MAX_MEMBERS) {
            "a project holds at most $MAX_MEMBERS members"
        } else {
            null
        }

    // ------------------------------------------------------------ the marks

    /**
     * The colour key for one member, IN THE SESSION VOCABULARY — `running`,
     * `attention`, `idle` — or null when nothing is known.
     *
     * ⚠ THE SAME WORDS AS A SESSION ROW ON PURPOSE. A member IS a session, and a
     * cluster where the dot means one thing on the project screen and another on
     * the sessions list is a cluster nobody can read at a glance. Both shells
     * already own a dot for these three words; this is what feeds it.
     *
     * An ENDED member has no mark at all: it is not idle, it is gone, and a grey
     * dot beside a live grey dot says the wrong thing.
     *
     * ⚠ UNKNOWN IS NULL, NEVER A GUESS. A newer daemon inventing a word must
     * leave the row unmarked rather than have it picked up by the `else` branch
     * and drawn as idle — the one exception is `waiting`, which the native
     * registry started emitting beside `busy`/`idle` and which means precisely
     * "input needed".
     */
    fun stateWord(member: ProjectMember): String? {
        if (ended(member)) return null
        if (member.needsYou == true) return "attention"
        return when (member.state) {
            "running", "busy" -> "running"
            "attention", "waiting" -> "attention"
            "idle" -> "idle"
            else -> null
        }
    }

    /** True once this member's session has ended. The row stays; the mark goes. */
    fun ended(member: ProjectMember): Boolean = member.endedAt != null

    /** True when this member is the reason the owner is being asked to look. */
    fun needsYou(member: ProjectMember): Boolean = stateWord(member) == "attention"

    /** What one member's state is, in words, for the row under the name. */
    fun memberWords(member: ProjectMember): String = when {
        ended(member) -> "ended"
        else -> when (stateWord(member)) {
            "attention" -> "needs you"
            "running" -> "working"
            "idle" -> "idle"
            else -> "no state yet"
        }
    }

    /**
     * The members in the order a person reads them: the ones asking for something
     * first, then the ones working, then the quiet ones, then the ended ones.
     *
     * ⚠ NEEDS-YOU FIRST IS THE WHOLE POINT OF THE SCREEN. A twelve-row table
     * sorted by role puts the one session sitting on a permission dialog wherever
     * the alphabet happens to leave it. Sorted HERE rather than trusted from the
     * daemon, so the two shells cannot disagree about which row is at the top.
     *
     * Role breaks every tie, so the order is total and the rows do not swap
     * places between five-second polls.
     */
    fun ordered(members: List<ProjectMember>): List<ProjectMember> =
        members.sortedWith(compareBy({ rank(it) }, { it.role }, { it.name }))

    private fun rank(m: ProjectMember): Int = when {
        ended(m) -> 4
        needsYou(m) -> 0
        stateWord(m) == "running" -> 1
        stateWord(m) == "idle" -> 2
        else -> 3
    }

    // ----------------------------------------------------------- the rollup

    /** The counts a project row is summarised from. Live excludes ended members. */
    data class Rollup(
        val live: Int,
        val working: Int,
        val needsYou: Int,
        val idle: Int,
        val ended: Int,
    )

    fun rollup(members: List<ProjectMember>): Rollup {
        val live = members.filterNot { ended(it) }
        return Rollup(
            live = live.size,
            working = live.count { stateWord(it) == "running" },
            needsYou = live.count { needsYou(it) },
            idle = live.count { stateWord(it) == "idle" },
            ended = members.count { ended(it) },
        )
    }

    /**
     * The one line under a project's name: "3 of 5 working · 1 needs you".
     *
     * ⚠ THE DENOMINATOR IS THE LIVE MEMBERS, NOT EVERY ROW EVER SPAWNED. "3 of
     * 12 working" on a cluster where seven finished hours ago reads as a project
     * in trouble. Ended members get their own clause, at the end, where a count
     * is information rather than an accusation.
     *
     * Asserted as literals in ProjectRulesTest: this sentence is read more often
     * than any other string in the feature, and it is built from counts that are
     * easy to get subtly wrong.
     */
    fun rollupWords(members: List<ProjectMember>): String {
        val r = rollup(members)
        if (r.live == 0 && r.ended == 0) return "no members yet"
        val parts = mutableListOf<String>()
        if (r.live > 0) parts += "${r.working} of ${r.live} working"
        if (r.needsYou > 0) parts += "${r.needsYou} need${if (r.needsYou == 1) "s" else ""} you"
        if (r.ended > 0) parts += "${r.ended} ended"
        return parts.joinToString(" · ")
    }

    /** The lead's own line, when there is one. Null when no lead has registered. */
    fun leadWords(project: Project): String? =
        project.lead?.name?.takeIf { it.isNotBlank() }?.let { "led by $it" }

    /** True while this project is still a going concern. */
    fun live(project: Project): Boolean = project.endedAt == null

    /**
     * The projects in list order: live ones first, then by name.
     *
     * The [ScratchpadRules.ordered] decision rather than [ArchiveRules.ordered]'s,
     * and for that rule's reason: this is a PLACE. A list that re-sorts itself by
     * activity moves the row under the finger, and the row you tapped is not the
     * row that is there a second later.
     */
    fun orderedProjects(projects: List<Project>): List<Project> =
        projects.sortedWith(
            compareBy<Project> { if (live(it)) 0 else 1 }
                .thenBy { cleanName(it.name).lowercase() }
                .thenBy { it.id },
        )

    /** What a project row leads with: its name, falling back to a short id. */
    fun label(project: Project): String =
        cleanName(project.name).takeIf { it.isNotEmpty() } ?: project.id.take(8)

    // --------------------------------------------------------- the manifest

    /** The length a summary is clipped to before it stops being one line. */
    const val SUMMARY_MAX: Int = 140

    /**
     * The manifest in one line, for a card header or a notification.
     *
     * Takes the daemon's own [ProjectManifest.summary] when there is one and
     * falls back to the first non-blank line of the body — never to the whole
     * body, because the body is a paragraph and this slot is a line.
     */
    fun manifestSummary(manifest: ProjectManifest?): String? {
        if (manifest == null) return null
        val summary = manifest.summary?.let { oneLine(it) }?.takeIf { it.isNotEmpty() }
        if (summary != null) return clip(summary)
        val first = manifest.text.orEmpty().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return clip(oneLine(first))
    }

    private fun oneLine(raw: String): String = normalized(raw)

    private fun clip(s: String): String =
        if (s.length <= SUMMARY_MAX) s else s.take(SUMMARY_MAX - 1).trimEnd() + "…"

    /**
     * What is wrong with this proposal, or null.
     *
     * ⚠ AN UNTAGGED BLOCK IS THE SILENT FAILURE THIS FEATURE HAS. The lead wrote
     * what it believes is a proposal, the daemon ignored it because the fence
     * carried no tag, and to the owner nothing at all happened. Said on the card,
     * because the fix — ask the lead for a new block — is something only a person
     * can do.
     */
    fun manifestCaution(manifest: ProjectManifest?): String? =
        if (manifest?.untaggedSeen == true) {
            "The lead wrote a proposal without its tag, so it was not read. Ask it for a new block."
        } else {
            null
        }

    /** True when there is a proposal worth showing Spawn · Edit · Discard on. */
    fun hasProposal(project: Project): Boolean =
        project.manifest != null && live(project) &&
            (!project.manifest!!.summary.isNullOrBlank() || !project.manifest!!.text.isNullOrBlank())

    // ------------------------------------------------------------ the spawn

    /**
     * What a spawn did, in one line.
     *
     * ⚠ PARTIAL IS THE NORMAL OUTCOME AND MUST READ AS ONE. Spawning is a loop
     * over tmux; the fourth member failing does not un-spawn the first three, and
     * a headline that said "failed" would send somebody looking for three
     * sessions that are sitting there working. The failures are named separately
     * by [spawnFailures] so each one carries the daemon's own sentence.
     */
    fun spawnWords(result: SpawnResult): String {
        val all = result.results
        if (all.isEmpty()) return "nothing came back"
        val ok = all.count { it.ok }
        return when {
            ok == all.size && ok == 1 -> "1 member started"
            ok == all.size -> "$ok members started"
            ok == 0 && all.size == 1 -> "the member did not start"
            ok == 0 -> "none of the ${all.size} started"
            else -> "$ok of ${all.size} started · ${all.size - ok} failed"
        }
    }

    /**
     * One line per member that did not start, carrying the DAEMON'S OWN reason.
     *
     * Verbatim: "a session called lora-stick-docs already exists" and "the
     * directory is not trusted" are different problems with different fixes, and
     * a client's summary of either helps nobody.
     */
    fun spawnFailures(result: SpawnResult): List<String> =
        result.results.filterNot { it.ok }.map { r ->
            val who = r.name.takeIf { it.isNotBlank() } ?: "a member"
            val why = r.error?.let { oneLine(it) }?.takeIf { it.isNotEmpty() }
            if (why == null) who else "$who — $why"
        }
}
