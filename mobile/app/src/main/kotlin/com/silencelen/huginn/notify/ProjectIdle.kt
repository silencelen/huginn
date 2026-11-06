package com.silencelen.huginn.notify

/**
 * A PROJECT has gone quiet, decided without a Context.
 *
 * The daemon pushes `{kind: "project_idle", subject: <project id>, title:
 * "<project name> is idle", text: "Worked 42m; lead, guide, vault ran.",
 * options: [], payload: {projectId}}` once per working burst of a project: when
 * every session in it has been quiet for five minutes after a burst that worked
 * at least five (owner decision, 2026-11-06). It replaces the per-member
 * `session_finished`, which a project sent every time one of its members paused
 * between messages to another — a shade full of "finished" for work that was
 * still going on.
 *
 * ## What it may carry, and what it never does
 *
 * - "Finished" ([PostArgs.isResult]), the channel a finished session rides: it
 *   is news you asked for, not a question.
 * - A tap opens THAT project's page ([PostArgs.project]), where each member's
 *   own state is shown — never one member's session.
 * - NO buttons and no reply box: there is nothing to answer. The wire's
 *   `options` is empty, and is read for nothing here anyway.
 * - Withheld while the reader has that project's page open ([shows]).
 * - Its own slot ([keyFor]). Not `project:<id>`, where a proposal's Spawn and
 *   Discard wait: an idle notice landing there would take away a choice the
 *   reader was never shown. Not a member's either, whose "needs you" stays up.
 *   A later burst replaces the earlier notice: one per project. It comes down
 *   when the project's page is opened (read is dismissed, MainActivity).
 * - Nothing in the app derives it from an observation. Only the daemon knows
 *   when a burst began, and the watch digest carries no project.
 */
object ProjectIdle {

    /** The push kind (appd, r4). */
    const val KIND: String = "project_idle"

    /**
     * The slot. A tmux session name cannot contain `:`, so no session's slot can
     * collide with it, and the `project:` prefix is the proposal's.
     */
    fun keyFor(projectId: String): String = "project-idle:$projectId"

    data class Notice(
        val key: String,
        val title: String,
        val text: String,
        val projectId: String,
    )

    /**
     * Exactly how a [Notice] is handed to [SessionWatchWorker.post]. The nulls
     * and empties are doors held shut, asserted by test: no `session` (the tap
     * opens the project, not a member), no `replyChat` (no free text), no
     * `answers`/`fingerprint` and no `projectActions` (no buttons), and
     * `isResult` true (Finished, never the blocking channel).
     */
    data class PostArgs(
        val title: String,
        val text: String,
        val key: String,
        val project: String,
        val session: String? = null,
        val replyChat: String? = null,
        val answers: List<SessionWatchWorker.Companion.AnswerOption> = emptyList(),
        val fingerprint: String? = null,
        val projectActions: List<ProjectNotices.Action> = emptyList(),
        val isResult: Boolean = true,
    )

    /**
     * One push turned into a [Notice]; null for any other kind, and for one that
     * names no project — a tap that could open nothing. The project is read the
     * way [ProjectNotices.fromPush] reads it, `payload.projectId` before
     * `subject`. The daemon's own sentence is kept: it knows the project's name,
     * how long the burst worked and which roles ran.
     */
    fun fromPush(
        kind: String,
        title: String,
        text: String,
        subject: String?,
        /** The daemon's `payload` field, a JSON object as a string. */
        payload: String? = null,
    ): Notice? {
        if (kind != KIND) return null
        val id = ProjectNotices.parsePayload(payload)["projectId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return Notice(
            key = keyFor(id),
            title = title.ifBlank { "A project is idle" },
            text = text.ifBlank { "Open the project to see what its sessions did." },
            projectId = id,
        )
    }

    fun postArgs(n: Notice): PostArgs = PostArgs(title = n.title, text = n.text, key = n.key, project = n.projectId)

    /** Posted unless that project's page is on screen in front of the reader. */
    fun shows(n: Notice, onScreen: (String) -> Boolean = { Foreground.showsProject(it) }): Boolean = !onScreen(n.projectId)
}
