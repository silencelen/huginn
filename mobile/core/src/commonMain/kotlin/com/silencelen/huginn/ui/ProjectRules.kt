package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ManifestSession
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectDeleted
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectRow
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
 *
 * ⚠⚠ AND THE COUNTS ARE THE DAEMON'S, NOT OURS. `alive`, `busy` and `waiting`
 * arrive already summed on a [ProjectRow] — they are a join across three
 * registries (the project store, tmux, and Claude Code's own pid-keyed rows)
 * that no client can perform. [rollupWords] renders them; it never recomputes
 * them from a member list it happens to be holding.
 */
object ProjectRules {

    // ------------------------------------------------------------- the caps

    const val MAX_NAME: Int = 60

    /** The whole first message the lead gets. The daemon's cap, to the character. */
    const val MAX_BRIEF: Int = 4_000

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

    // ------------------------------------------------------ the vocabularies

    /** What a project IS. The daemon's closed list, mirrored for the create sheet. */
    val KINDS: List<String> = listOf("software", "infra", "hardware", "docs", "research", "other")

    /**
     * Where a project is in its life.
     *
     *   drafting  the lead is up, no manifest yet (or the last one was discarded)
     *   proposed  a tagged manifest arrived; waiting for Spawn · Edit · Discard
     *   active    members exist
     *   paused    auto-resume is suspended for the cluster; the sessions stay
     *   archived  over — the owner filed it, or the lead is gone. Terminal.
     */
    val STATUSES: List<String> = listOf("drafting", "proposed", "active", "paused", "archived")

    /** The legal moves, and nothing else. `archived` is terminal on purpose. */
    val TRANSITIONS: Map<String, List<String>> = mapOf(
        "drafting" to listOf("proposed", "archived"),
        "proposed" to listOf("drafting", "active", "archived"),
        "active" to listOf("paused", "archived"),
        "paused" to listOf("active", "archived"),
        "archived" to emptyList(),
    )

    /**
     * The word, or NULL if this daemon has invented one.
     *
     * ⚠ UNKNOWN IS NULL AND NEVER A GUESS, on every vocabulary in this file. A
     * newer daemon's sixth kind must leave a chip undrawn rather than be shown as
     * `other`, which is a real member of the list and means something.
     */
    fun kindWord(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it in KINDS }

    fun statusWord(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it in STATUSES }

    /** Whether this move is one the daemon will take. An unknown target is never legal. */
    fun canTransition(from: String?, to: String?): Boolean {
        val f = statusWord(from) ?: return false
        val t = statusWord(to) ?: return false
        if (f == t) return true
        return t in (TRANSITIONS[f] ?: emptyList())
    }

    /** What a status reads as under a name. Null for a word this client does not know. */
    fun statusWords(raw: String?): String? = when (statusWord(raw)) {
        "drafting" -> "the lead is sizing it"
        "proposed" -> "waiting for your answer"
        "active" -> "running"
        "paused" -> "paused"
        "archived" -> "archived"
        else -> null
    }

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
     * The tmux and peer namespace a display name produces.
     *
     * ⚠ NO DOTS, EVER. tmux rewrites a `.` to `_` and still exits 0, so a slug
     * carrying one is a name that comes back different from the one asked for —
     * which is the whole class of bug this grammar exists to avoid. Lowercase for
     * the same family of reason: the daemon lowercases anyway.
     */
    fun slugFor(name: String): String =
        cleanName(name).lowercase()
            .replace(NOT_SLUG, "-")
            .trim('-')
            .take(24)
            .trimEnd('-')

    private val NOT_SLUG = Regex("""[^a-z0-9]+""")

    /** Slugs that already mean something else on this daemon. */
    val RESERVED_SLUGS: List<String> = listOf("login", "main", "huginn", "lead")

    /**
     * Why the slug this name produces cannot be used, or null.
     *
     * Checked on its own terms rather than folded into [nameProblem], because two
     * different display names can land on one slug — and the slug is the
     * namespace every member is about to be named in.
     */
    fun slugProblem(slug: String, takenSlugs: List<String> = emptyList()): String? {
        if (slug.isEmpty()) return "that name does not produce a usable slug — use some letters or digits"
        if (!SLUG_RE.matches(slug)) return "a slug is lowercase letters, digits and dashes, 1-24 characters"
        if (slug in RESERVED_SLUGS) return "\"$slug\" is reserved"
        if (takenSlugs.any { it.lowercase() == slug.lowercase() }) return "there is already a project with that slug"
        return null
    }

    private val SLUG_RE = Regex("""^[a-z0-9][a-z0-9-]{0,23}$""")

    /** Why this working directory cannot be used, or null. */
    fun cwdProblem(raw: String): String? {
        val cwd = raw.trim()
        if (cwd.isEmpty()) return null // blank = the daemon's own WORKDIR
        if (!cwd.startsWith("/")) return "a project directory must be an absolute path"
        return null
    }

    /**
     * Why this brief cannot be sent, or null.
     *
     * ⚠ A BRIEF IS NOT OPTIONAL AND IT IS NOT A DESCRIPTION. It is typed straight
     * into the lead's composer as the whole first message it ever gets, and a
     * project created without one is a session sitting there with nothing to size.
     */
    fun briefProblem(raw: String): String? {
        val brief = raw.trim()
        if (brief.isEmpty()) return "a project needs a brief — it is the whole first message the lead gets"
        if (brief.length > MAX_BRIEF) return "a brief is at most $MAX_BRIEF characters"
        return null
    }

    /** Why this is not a kind, or null. The daemon refuses a create without one. */
    fun kindProblem(raw: String?): String? =
        if (kindWord(raw) == null) "kind is one of ${KINDS.joinToString(", ")}" else null

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

    /**
     * Whether this member may be dropped from the project.
     *
     * ⚠ NEVER THE LEAD, and the daemon says why in one line: *"the lead is the
     * project — delete the project instead"*. Dropping it would leave a record
     * whose brief, manifest and peer namespace all belong to a session that is
     * no longer in it. The daemon answers 409; this is what stops the verb being
     * offered, which is the difference between a control and a trap.
     */
    fun canDrop(member: ProjectMemberState): Boolean = !member.lead && member.role != LEAD_ROLE

    /**
     * The live sessions a project could ADOPT: everything running that is not
     * already one of its members.
     *
     * ⚠ IT CANNOT KNOW ABOUT OTHER PROJECTS, and must not pretend to. A session
     * belonging to a different cluster looks exactly like a free one from here —
     * the daemon holds that join and answers 409 NAMING the other project, which
     * is the whole fix and is worth far more than a row quietly missing from a
     * picker. So this filters only what this client can actually see.
     */
    fun adoptable(sessions: List<String>, members: List<ProjectMemberState>): List<String> {
        val taken = members.map { it.name }.toSet()
        return sessions.filter { it.isNotBlank() && it !in taken }
    }

    /**
     * What a completed delete SAYS, in one line.
     *
     * ⚠ THE REFUSALS ARE THE HALF THAT MATTERS, and they were not said at all.
     * A graceful delete types a wrap-up phrase into each member and lets the
     * settle timer close it — but a member sitting on a permission or
     * folder-trust dialog cannot be typed at, so the daemon skips it and removes
     * the record anyway. Those sessions are then RUNNING WITH NO PROJECT BEHIND
     * THEM, and a reader told only "project removed" has no reason to go looking
     * for them. The daemon names each one; this puts the names on the screen.
     *
     * Pure, in `:core`, because both shells report the same delete and a sentence
     * kept in two apps is a sentence fixed in one.
     */
    fun deletedWords(done: ProjectDeleted): String {
        val bits = mutableListOf("Project removed")
        if (done.ended.isNotEmpty()) bits += "ended ${done.ended.size}"
        if (done.refused.isNotEmpty()) {
            val names = done.refused.joinToString(", ") { it.name.ifBlank { it.claudeName } }
            val what = if (done.refused.size == 1) "1 session was" else "${done.refused.size} sessions were"
            // ⚠ "NOT WOUND DOWN", not "failed". Nothing broke: those sessions are
            // alive and working, and the word has to leave a reader expecting to
            // find them rather than expecting wreckage.
            bits += "$what not wound down: $names"
        }
        return bits.joinToString(" · ")
    }

    /** The two composers, mirrored — the only two forms a project session's name takes. */
    fun tmuxNameFor(slug: String, role: String): String = "$slug-$role"

    fun claudeNameFor(slug: String, role: String): String = "$slug/$role"

    // ------------------------------------------------------------ the marks

    /**
     * The colour key for one member, IN THE SESSION VOCABULARY — `running`,
     * `attention`, `idle` — or null when nothing is known.
     *
     * ⚠ THE SAME WORDS AS A SESSION ROW ON PURPOSE. A member IS a session, and a
     * cluster where the dot means one thing on the project screen and another on
     * the sessions list is a cluster nobody can read at a glance.
     *
     * ⚠ TWO VOCABULARIES, READ IN ONE ORDER. `needsYou` is the daemon's own
     * promotion and wins outright; then the NATIVE registry's `status`
     * (`busy`/`idle`/`waiting`), which is the pid-keyed truth; then the title
     * hook's `state`, which is what every other session row here already draws.
     *
     * An ENDED member has no mark at all, and neither has one whose tmux session
     * and process are both gone: they are not idle, they are not there.
     *
     * ⚠ UNKNOWN IS NULL, NEVER A GUESS. A newer daemon inventing a word must
     * leave the row unmarked rather than have it picked up by an `else` branch
     * and drawn as idle.
     */
    fun stateWord(member: ProjectMemberState): String? {
        if (ended(member)) return null
        if (gone(member)) return null
        if (member.needsYou) return "attention"
        when (member.status) {
            "busy" -> return "running"
            "waiting" -> return "attention"
            "idle" -> return "idle"
        }
        return when (member.state) {
            "running" -> "running"
            "attention" -> "attention"
            "idle" -> "idle"
            else -> null
        }
    }

    /** True once this member's session has ended. The row stays; the mark goes. */
    fun ended(member: ProjectMemberState): Boolean = member.endedAt != null

    /** Neither a tmux session nor a live process: nothing to say a state about. */
    fun gone(member: ProjectMemberState): Boolean = !member.present && !member.alive

    /** True when this member is the reason the owner is being asked to look. */
    fun needsYou(member: ProjectMemberState): Boolean = stateWord(member) == "attention"

    /**
     * What one member's state is, in words, for the row under the name.
     *
     * [ProjectMemberState.waitingFor] is appended when the registry said what it
     * is waiting for, because "needs you" and "needs you — input needed" are a
     * different amount of help.
     */
    fun memberWords(member: ProjectMemberState): String = when {
        ended(member) -> "ended"
        gone(member) -> "not running"
        else -> when (stateWord(member)) {
            "attention" -> {
                val why = member.waitingFor?.trim()?.takeIf { it.isNotEmpty() }
                if (why == null) "needs you" else "needs you — $why"
            }
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
     * ⚠ AND THE LEAD IS ALWAYS FIRST unless a member is asking for something. It
     * is the session the owner talks to, and burying it among twelve members it
     * spawned is how a cluster loses its front door.
     *
     * Role breaks every tie, so the order is total and the rows do not swap
     * places between five-second polls.
     */
    fun <T : ProjectMemberState> ordered(members: List<T>): List<T> =
        members.sortedWith(compareBy({ rank(it) }, { it.role }, { it.name }))

    private fun rank(m: ProjectMemberState): Int = when {
        needsYou(m) -> 0
        ended(m) -> 6
        gone(m) -> 5
        m.lead -> 1
        stateWord(m) == "running" -> 2
        stateWord(m) == "idle" -> 3
        else -> 4
    }

    // ----------------------------------------------------------- the rollup

    /**
     * The counts a project row is summarised from, AS THE DAEMON COUNTED THEM.
     *
     * ⚠ THE LEAD IS NOT IN ANY OF THESE. `lib/projects.js` filters it out of
     * every count before it emits the row, because the lead is always there and
     * counting it would make an idle cluster read as one session busy.
     */
    data class Rollup(
        /** Members on the record — what was created, alive or not. */
        val members: Int,
        /** Members whose `claude` process is answering. */
        val alive: Int,
        /** Members the native registry calls `busy`. */
        val busy: Int,
        /** Members that need a person: native `waiting`, or a promoted `attention`. */
        val waiting: Int,
        /**
         * Whether a lead has registered.
         *
         * ⚠ IT IS NOT A MEMBER and it is not counted as one — see the counts
         * above — but it IS a row in the MEMBERS list, which is why the header
         * has to know about it. See [rollupWords].
         */
        val lead: Boolean = false,
    )

    fun rollup(row: ProjectRow): Rollup =
        Rollup(
            members = row.memberCount,
            alive = row.alive,
            busy = row.busy,
            waiting = row.waiting,
            lead = row.lead != null,
        )

    /**
     * The same counts off a live member list, for a surface holding rows rather
     * than a [ProjectRow] — the dashboard's own members, say.
     *
     * The lead is dropped here too, so the two paths cannot disagree.
     */
    fun rollup(members: List<ProjectMemberState>): Rollup {
        val rows = members.filterNot { it.lead }
        return Rollup(
            members = rows.size,
            alive = rows.count { it.alive },
            busy = rows.count { stateWord(it) == "running" },
            waiting = rows.count { needsYou(it) },
            lead = members.any { it.lead },
        )
    }

    /**
     * The one line under a project's name: "2 of 3 working · 1 needs you".
     *
     * ⚠ THE DENOMINATOR IS THE LIVE MEMBERS, NOT EVERY ROW EVER SPAWNED. "3 of
     * 12 working" on a cluster where nine have finished reads as a project in
     * trouble. The ones that are no longer running get their own clause, at the
     * end, where a count is information rather than an accusation.
     *
     * Asserted as literals in ProjectRulesTest: this sentence is read more often
     * than any other string in the feature, and it is built from counts that are
     * easy to get subtly wrong.
     */
    fun rollupWords(r: Rollup): String {
        // ⚠⚠ "NO MEMBERS YET" OVER A MEMBERS LIST THAT SHOWS THE LEAD (P-33/D-20).
        // The counts deliberately drop the lead — an idle cluster whose lead is
        // thinking must not read as one session busy — but the MEMBERS list below
        // the header does not, so the header was contradicting the list directly
        // underneath it on every project between "created" and "spawned", which
        // is the whole window in which somebody is watching it.
        //
        // The count is unchanged; only the sentence over zero of them is, and it
        // is the one state where the lead is the entire cluster.
        if (r.members == 0) return if (r.lead) LEAD_ONLY else "no members yet"
        val parts = mutableListOf<String>()
        parts += if (r.alive > 0) "${r.busy} of ${r.alive} working" else "none running"
        if (r.waiting > 0) parts += "${r.waiting} need${if (r.waiting == 1) "s" else ""} you"
        val down = (r.members - r.alive).coerceAtLeast(0)
        if (down > 0 && r.alive > 0) parts += "$down not running"
        return parts.joinToString(" · ")
    }

    /** A project whose lead has registered and whose cluster has not been spawned. */
    const val LEAD_ONLY: String = "just the lead so far"

    fun rollupWords(row: ProjectRow): String = rollupWords(rollup(row))

    fun rollupWords(members: List<ProjectMemberState>): String = rollupWords(rollup(members))

    /** The lead's own line, when there is one. Null when no lead has registered. */
    fun leadWords(row: ProjectRow): String? =
        row.lead?.claudeName?.takeIf { it.isNotBlank() }?.let { "led by $it" }

    /**
     * True while this project is still a going concern.
     *
     * `archived` is the one terminal status, and it is reached two ways — the
     * owner filed it, or the lead process disappeared and the daemon filed it
     * with an [ProjectRow.endedReason]. Both produce the same row for a reader.
     */
    fun live(row: ProjectRow): Boolean = statusWord(row.status) != "archived"

    fun live(project: Project): Boolean = statusWord(project.status) != "archived"

    /**
     * The projects in list order: live ones first, then by name.
     *
     * The [ScratchpadRules.ordered] decision rather than [ArchiveRules.ordered]'s,
     * and for that rule's reason: this is a PLACE. A list that re-sorts itself by
     * activity moves the row under the finger, and the row you tapped is not the
     * row that is there a second later.
     */
    fun orderedProjects(projects: List<ProjectRow>): List<ProjectRow> =
        projects.sortedWith(
            compareBy<ProjectRow> { if (live(it)) 0 else 1 }
                .thenBy { cleanName(it.name).lowercase() }
                .thenBy { it.id },
        )

    /** What a project row leads with: its name, falling back to its slug or a short id. */
    fun label(row: ProjectRow): String =
        cleanName(row.name).takeIf { it.isNotEmpty() }
            ?: row.slug.takeIf { it.isNotBlank() }
            ?: row.id.take(8)

    fun label(project: Project): String =
        cleanName(project.name).takeIf { it.isNotEmpty() }
            ?: project.slug.takeIf { it.isNotBlank() }
            ?: project.id.take(8)

    // --------------------------------------------------------- the manifest

    /** The length a summary is clipped to before it stops being one line. */
    const val SUMMARY_MAX: Int = 140

    /**
     * The manifest in one line, for a card header or a notification.
     *
     * The daemon already caps its own summary at 90 characters and refuses a
     * block without one, so this is a clip rather than a rescue — and there is
     * deliberately no fallback to the scope paragraph, because the slot is a line
     * and a paragraph in it is a card that ate the rest of the card.
     */
    fun manifestSummary(manifest: ProjectManifest?): String? =
        manifest?.summary?.let { normalized(it) }?.takeIf { it.isNotEmpty() }?.let { clip(it) }

    /** The same line off a row, which carries the summary and not the manifest. */
    fun manifestSummary(row: ProjectRow): String? =
        row.manifestSummary?.let { normalized(it) }?.takeIf { it.isNotEmpty() }?.let { clip(it) }

    private fun clip(s: String): String =
        if (s.length <= SUMMARY_MAX) s else s.take(SUMMARY_MAX - 1).trimEnd() + "…"

    /** What the proposal asks for, in one line: "3 sessions: docs, repo, fw". */
    fun manifestWords(manifest: ProjectManifest?): String {
        val sessions = manifest?.sessions.orEmpty()
        if (sessions.isEmpty()) return "no sessions in this proposal"
        val roles = sessions.joinToString(", ") { it.role }
        return if (sessions.size == 1) "1 session: $roles" else "${sessions.size} sessions: $roles"
    }

    /**
     * One proposed session's settings, in the order a person reads them.
     *
     * ⚠ NULL MEANS "THE HOST'S OWN", NOT "UNKNOWN". The daemon turns a model,
     * effort or mode word it does not recognise into null rather than losing the
     * whole proposal over it, so an empty cell here is the default and is said as
     * one.
     */
    fun sessionWords(session: ManifestSession): String {
        val bits = listOfNotNull(
            session.model,
            session.effort?.let { "$it effort" },
            session.mode?.let { "$it mode" },
        )
        return if (bits.isEmpty()) "the host's own defaults" else bits.joinToString(" · ")
    }

    /** Where a proposed session will run, when it is not the project's own directory. */
    fun sessionCwd(session: ManifestSession): String? =
        session.cwd?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What is wrong with this proposal, or null.
     *
     * ⚠ AN UNTAGGED BLOCK IS THE SILENT FAILURE THIS FEATURE HAS, and it is also
     * the injection signal. The lead wrote what it believes is a proposal and the
     * daemon ignored it because the fence carried no tag — which is either the
     * lead forgetting its own contract, or something the lead READ trying to get
     * twelve sessions spawned with prompts a stranger wrote. Said on the card,
     * because the fix is something only a person can do.
     */
    fun manifestCaution(manifest: ProjectManifest?): String? = untaggedCaution(manifest?.untaggedSeen == true)

    fun manifestCaution(row: ProjectRow): String? = untaggedCaution(row.untaggedSeen)

    private fun untaggedCaution(seen: Boolean): String? =
        if (seen) {
            "The lead wrote a proposal without its tag, so it was not read. Ask it for a new block."
        } else {
            null
        }

    /**
     * True when there is a proposal worth showing Spawn · Edit · Discard on.
     *
     * ⚠ THE STATUS IS THE GATE, NOT THE PRESENCE OF A MANIFEST. A manifest stays
     * on the record after a Discard so an editor can reopen it, and it stays
     * after a Spawn as the record of what was made — offering Spawn on either
     * would be offering to create a cluster that is already running.
     */
    fun hasProposal(project: Project): Boolean =
        statusWord(project.status) == "proposed" && project.manifest?.sessions.orEmpty().isNotEmpty()

    fun hasProposal(row: ProjectRow): Boolean =
        statusWord(row.status) == "proposed" && row.manifestRev > 0

    /**
     * True when this proposal has already been carried out at this rev — the
     * daemon stamps `spawnedRev` when it spawns, so a card redrawn from a stale
     * notification can say so instead of offering Spawn twice.
     */
    fun alreadySpawned(manifest: ProjectManifest?): Boolean =
        manifest != null && manifest.rev > 0 && manifest.spawnedRev >= manifest.rev

    /**
     * The same question off a ROW, which is all a list has.
     *
     * ⚠ `GET /v1/projects` CARRIES NO MANIFEST. A tree that had to fetch every
     * project to find out whether a proposal had already been carried out would
     * make one call per row to draw one list, which is why the daemon puts
     * `spawnedRev` beside `manifestRev` on the row. Same rule, same order of
     * tests, so the two overloads cannot answer differently about one project.
     */
    fun alreadySpawned(row: ProjectRow): Boolean =
        row.manifestRev > 0 && row.spawnedRev >= row.manifestRev

    // ------------------------------------------------------------ the spawn

    /**
     * What a spawn did, in one line.
     *
     * ⚠⚠ PARTIAL IS THE NORMAL OUTCOME, IT ARRIVES ON A 200, AND IT MUST READ AS
     * PARTIAL. Spawning is a loop over tmux; the second of three roles failing
     * does not un-spawn the first, and a headline that said "failed" would send
     * somebody looking for sessions that are sitting there working. The failures
     * are named separately by [spawnFailures] so each one carries the daemon's
     * own sentence.
     */
    fun spawnWords(result: SpawnResult): String {
        val started = result.spawned.size
        val failed = result.failed.size
        val all = started + failed
        if (all == 0) return "nothing came back"
        return when {
            failed == 0 && started == 1 -> "1 member started"
            failed == 0 -> "$started members started"
            started == 0 && all == 1 -> "the member did not start"
            started == 0 -> "none of the $all started"
            else -> "$started of $all started · $failed failed"
        }
    }

    /**
     * One line per role that did not start, carrying the DAEMON'S OWN reason.
     *
     * Verbatim: "duplicate session: half-mid" and "persona could not be written"
     * are different problems with different fixes, and a client's summary of
     * either helps nobody.
     */
    fun spawnFailures(result: SpawnResult): List<String> =
        result.failed.map { f ->
            val who = f.role.takeIf { it.isNotBlank() } ?: "a member"
            val why = normalized(f.reason).takeIf { it.isNotEmpty() }
            if (why == null) who else "$who — $why"
        }

    /** The peer names a spawn actually created, for the line that tells the lead. */
    fun spawnedNames(result: SpawnResult): List<String> =
        result.spawned.map { it.claudeName.takeIf { n -> n.isNotBlank() } ?: it.name }
}
