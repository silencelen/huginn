package com.silencelen.huginn.desktop.diag

/**
 * The "Copy diagnostics" payload: everything worth pasting into a chat when
 * something looks wrong, and nothing that must not leave the machine.
 *
 * THE TOKEN IS EXCLUDED BY CONSTRUCTION. [Input] has no field it could travel in
 * — only `hasToken`, a boolean — so there is no call site that could put it there
 * by accident, and no future edit to this file that could start including it
 * without also changing the type. [RingLog.scrub] over the finished text is the
 * second line of defence, for the token that arrives inside somebody else's error
 * message ("401 for Bearer abc…").
 *
 * Pure: it takes facts and returns text. Gathering the facts is [collect], which
 * is where the runtime lives.
 */
object Diagnostics {

    /**
     * Everything the report can say. Note what is NOT here: the bearer token, the
     * settings file's contents, anything read off disk. Adding a field is adding a
     * thing the owner will paste into a chat window.
     */
    data class Input(
        val generatedAt: String,
        val appVersion: String,
        val packaged: Boolean,
        val platform: String,
        val jvm: String,
        val uptimeSec: Long,
        val heapUsedMb: Long,
        val heapMaxMb: Long,
        val baseUrl: String,
        val routePinned: Boolean,
        val hasToken: Boolean,
        val clientId: String,
        val watchConnected: Boolean,
        val lastWatchError: String?,
        val lastWatchErrorAt: String?,
        val appdVersion: String?,
        val notifyEnabled: Boolean,
        val present: Boolean,
        val visible: Boolean,
        val claiming: Boolean,
        /** The notification backend's name, or null when none is wired. */
        val notifier: String?,
        val updateStatus: String,
        val updateVersion: String?,
        val updateError: String?,
        val lastError: String?,
        val logPath: String?,
        val log: String,
    )

    fun build(input: Input): String {
        val lines = buildList {
            add("# Huginn Desktop diagnostics")
            add("generated       ${input.generatedAt}")
            add("")
            add("## App")
            add("version         ${input.appVersion}")
            add("packaged        ${input.packaged}")
            add("platform        ${input.platform}")
            add("jvm             ${input.jvm}")
            add("uptime          ${input.uptimeSec}s")
            add("heap            ${input.heapUsedMb}MB used / ${input.heapMaxMb}MB max")
            add("")
            add("## Connection")
            add("server          ${input.baseUrl}${if (input.routePinned) " (pinned)" else ""}")
            // The one honest thing to say about a secret in a shareable report.
            add("token           ${if (input.hasToken) "set" else "MISSING"}")
            add("client id       ${input.clientId}")
            add("watch stream    ${if (input.watchConnected) "connected" else "DISCONNECTED"}")
            add("last watch err  ${dash(input.lastWatchError)}${input.lastWatchErrorAt?.let { " at $it" } ?: ""}")
            add("appd version    ${input.appdVersion ?: "unknown"}")
            add("")
            add("## Notifications")
            add("enabled         ${input.notifyEnabled}")
            add("window visible  ${input.visible}")
            add("attended        ${input.present}")
            add("claiming route  ${input.claiming}")
            add("desktop notifier ${input.notifier ?: "NOT WIRED (Telegram and the phone remain the only routes)"}")
            add("")
            add("## Update")
            add("status          ${input.updateStatus}")
            add("version         ${dash(input.updateVersion)}")
            add("error           ${dash(input.updateError)}")
            add("")
            add("## Last error")
            add(dash(input.lastError))
            add("")
            add("## Log (${input.logPath ?: "file unavailable"})")
            add(input.log.ifBlank { "(empty)" })
        }
        // Scrubbed as a WHOLE, after assembly: a credential that arrived inside a
        // free-text error message is still a credential, and the fields it can hide
        // in are exactly the ones no schema constrains.
        return RingLog.scrub(lines.joinToString("\n"))
    }

    private fun dash(s: String?): String = if (s.isNullOrBlank()) "-" else s
}
