package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.data.Alerts
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.settings.SettingsCategory
import com.silencelen.huginn.settings.SettingsProbe
import com.silencelen.huginn.notify.DeliveryCopy
import com.silencelen.huginn.notify.PushTally
import com.silencelen.huginn.ui.agoWordsMs
import kotlin.math.roundToInt

/**
 * The phone's Settings, decided without Compose.
 *
 * Three things live here and nothing else: what the catalog is allowed to show
 * ([phoneProbe]), the one live line under each category on the home screen
 * ([phoneSummary]), and the text Copy diagnostics puts on the clipboard
 * ([diagnosticsBundle]). All three are the parts that can be WRONG rather than
 * merely ugly — a probe that shows a row the daemon cannot serve, a summary that
 * claims two saved logins against a host with one, a diagnostics bundle carrying
 * the bearer token into somebody's chat window — so all three are pure functions
 * a test can hold to account.
 *
 * ⚠ THE BUNDLE NEVER CARRIES THE TOKEN. Not elided, not truncated, not
 * "last four": absent, and [SettingsPhoneTest] asserts it against a token string
 * planted in every field it could leak through. The bundle exists to be pasted
 * into a chat with somebody else, and a secret that travels in a support report
 * is a secret that has been published.
 */

/**
 * Everything the three rules read, gathered once by the shell.
 *
 * A single record rather than eleven parameters apiece, and deliberately made of
 * wire types and primitives: it can be built in a test in four lines, which is
 * what makes the summaries assertable at all. The old screen's 41-parameter
 * signature is the thing this redesign deleted; a settings layer that grew its
 * own would have learned nothing.
 */
data class PhoneSettingsFacts(
    // --------------------------------------------------------------- host
    val baseUrl: String = "",
    /**
     * The ACTIVE PIN'S NAME — the owner's word for this path, not a label this
     * app chose. Empty when nothing is pinned, which the summaries read as "no
     * route" rather than inventing one.
     */
    val routeName: String = "",
    val connected: Boolean? = null,
    val accountEmail: String? = null,
    val savedAccounts: Int = 0,
    // -------------------------------------------------------------- usage
    /** Null against a daemon older than 3.0 — the whole usage tier goes with it. */
    val headroomSettings: HeadroomSettings? = null,
    /** The worst window, as the ordinary status poll already carries it. */
    val worst: StatusHeadroom? = null,
    // -------------------------------------------------------------- chats
    /** Null against a daemon that owns no templates (pre-3.0.1). */
    val quickActions: QuickActions? = null,
    // ------------------------------------------------------------- notify
    /** Null when `/v1/alerts` did not answer: huginn cannot reach you at all. */
    val alerts: Alerts? = null,
    val notifyEnabled: Boolean = false,
    val watchEnabled: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val dozeExempt: Boolean = false,
    val pushConfigured: Boolean = false,
    val pushRegistered: Boolean = false,
    val pushesSent: Long = 0,
    val pushesReceived: Long = 0,
    /** What the background alarm settled on, in milliseconds. */
    val heartbeatIntervalMs: Long = 0,
    // ------------------------------------------------------------ devices
    val deviceCount: Int = 0,
    val servingCount: Int = 0,
    // ------------------------------------------------------------ privacy
    val appLock: Boolean = false,
    val appLockAvailable: Boolean = false,
    // --------------------------------------------------------- appearance
    val fontScale: Float = 0f,
    // ------------------------------------------------- updates and about
    val appVersion: String = "",
    val appdVersion: String? = null,
    /** The updater's state in one word — "up to date", "3.1.1 available", "—". */
    val updateWord: String = "",
    val updateRepo: String = "",
    // ---------------------------------------------------------- witnesses
    val lastContactAt: Long = 0,
    val lastAlarmAt: Long = 0,
    val lastError: String = "",
    val lastErrorAt: Long = 0,
    /** How long ago the HOST last heard from this phone, in seconds. Null: never. */
    val hostSawUsSeconds: Long? = null,
    /** Which mechanism the host saw: `stream` | `heartbeat` | `poll`. */
    val hostSawUsKind: String? = null,
    /** The daemon's scratchpad answer, carried through untouched. */
    val padsAvailable: Boolean? = null,
)

/**
 * What this host and this phone can actually offer, right now.
 *
 * The defaults in [SettingsProbe] are the pessimistic answers, so everything
 * here is a deliberate "yes" with a reason:
 *
 * - `enrolable` is unconditional. The fleet destination exists on this client
 *   whatever the host says, and its empty state is where the instructions for
 *   enrolling a machine are written — hiding the category when no machine is
 *   enrolled would hide the only page that says how to enrol one.
 * - `diagnostics` is unconditional, and is the owner's answer to Q3: the phone
 *   had no diagnostics at all, and "why did nothing arrive last night" is more a
 *   phone question than a desktop one.
 * - `selfUpdate` is unconditional. This APK updates itself from the public
 *   GitHub releases; unlike the desktop there is no store build to compile it
 *   out. It is still never a SETTING — there is one channel and no picker.
 * - `localServe` is false: serving local models is a machine's own decision and
 *   this phone is not a candidate.
 */
fun phoneProbe(f: PhoneSettingsFacts): SettingsProbe = SettingsProbe(
    headroom = f.headroomSettings != null,
    quickActions = f.quickActions != null,
    alerts = f.alerts != null,
    padsAvailable = f.padsAvailable,
    localServe = false,
    appLockAvailable = f.appLockAvailable,
    enrolable = true,
    savedAccounts = f.savedAccounts,
    diagnostics = true,
    selfUpdate = true,
)

/**
 * The one line under a category on the home screen.
 *
 * It says what the setting is SET TO, not what the category is for — the blurb
 * already says that, and a second sentence saying the same thing is how nine
 * rows become an essay. Null draws nothing, which is the honest answer while the
 * facts are still landing.
 */
fun phoneSummary(category: SettingsCategory, f: PhoneSettingsFacts): String? = when (category.id) {
    "host" -> hostSummary(f)
    "usage" -> usageSummary(f)
    "chats" -> f.quickActions?.let { "${quickActionCount(it)} quick actions" }
    "notify" -> notifySummary(f)
    "devices" -> devicesSummary(f)
    "privacy" -> if (!f.appLockAvailable) "no screen lock on this phone"
        else if (f.appLock) "lock on" else "lock off"
    "appearance" -> f.fontScale.takeIf { it > 0f }?.let { "terminal text ${it.roundToInt()} sp" }
    "updates" -> listOfNotNull(f.appVersion.takeIf { it.isNotBlank() }, f.updateWord.takeIf { it.isNotBlank() })
        .joinToString(" · ").takeIf { it.isNotBlank() }
    "about" -> listOfNotNull(
        f.appVersion.takeIf { it.isNotBlank() },
        f.appdVersion?.takeIf { it.isNotBlank() }?.let { "appd $it" },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
    else -> null
}

/**
 * "jacob@… · 2 saved logins".
 *
 * NOT CONNECTED WINS. When the host is unreachable the account line is a memory
 * of the last time it answered, and leading with a stale identity is how a
 * connection problem reads as an account problem for ten minutes.
 */
private fun hostSummary(f: PhoneSettingsFacts): String? {
    val where = routeWord(f)
    // ⚠ NULL WHILE THE FACTS ARE STILL LANDING. A fresh launch has no address for
    // a frame or two, and "no route yet" flashing on a phone that has three
    // pinned would be a lie told at exactly the moment somebody is worried.
    // "No route" is only said once a failed connection has proved it.
    if (f.connected == false) return "not connected · ${where ?: "no route yet"}"
    val who = f.accountEmail?.takeIf { it.isNotBlank() } ?: return where
    val logins = when (f.savedAccounts) {
        0 -> null
        1 -> "1 saved login"
        else -> "${f.savedAccounts} saved logins"
    }
    return listOfNotNull(who, logins).joinToString(" · ")
}

/**
 * "Fable 37% · ladder set".
 *
 * The percentage is the WORST window anywhere rather than the live account's,
 * because that is the number that decides when huginn acts — and it is already
 * on the status poll, so this line costs no request.
 */
private fun usageSummary(f: PhoneSettingsFacts): String? {
    val s = f.headroomSettings ?: return null
    val pct = f.worst?.worstPercent?.let { p ->
        val label = f.worst.worstLabel?.takeIf { it.isNotBlank() }
        listOfNotNull(label, "${p.roundToInt()}%").joinToString(" ")
    }
    val ladder = s.ladder.takeIf { it.size > 1 }?.let { "ladder set" }
    return listOfNotNull(pct, ladder).joinToString(" · ").takeIf { it.isNotBlank() }
}

/** How many of the four templates the host actually holds words for. */
internal fun quickActionCount(q: QuickActions): Int =
    listOf(q.explain, q.execute, q.askInNewChat, q.quote).count { it.isNotBlank() }

/**
 * "On · push · Telegram fallback".
 *
 * Three facts in the order they fail in: whether this phone is listening at all,
 * how fast it hears, and whether huginn has a way to reach you when it is not.
 */
private fun notifySummary(f: PhoneSettingsFacts): String {
    val bits = ArrayList<String>(3)
    bits += if (!f.notifyEnabled) "Off" else if (!f.notificationsAllowed) "blocked by Android" else "On"
    if (f.notifyEnabled && f.notificationsAllowed) {
        if (f.pushConfigured && f.pushRegistered) bits += "push"
        else if (f.watchEnabled) bits += "watching"
    }
    f.alerts?.let { a ->
        if (a.enabled && a.channel != "none") {
            bits += "${a.channel.replaceFirstChar { it.uppercase() }} ${if (a.mode == "always") "always" else "fallback"}"
        }
    }
    return bits.joinToString(" · ")
}

/** "3 machines · 1 serving". */
private fun devicesSummary(f: PhoneSettingsFacts): String {
    if (f.deviceCount == 0 && f.servingCount == 0) return "no machines enrolled"
    val machines = if (f.deviceCount == 1) "1 machine" else "${f.deviceCount} machines"
    return listOfNotNull(machines, f.servingCount.takeIf { it > 0 }?.let { "$it serving" })
        .joinToString(" · ")
}

/**
 * What to call the route in a one-line summary: the owner's name for it, the
 * address when there is no name, and NULL when nothing is pinned at all.
 */
private fun routeWord(f: PhoneSettingsFacts): String? =
    f.routeName.takeIf { it.isNotBlank() } ?: f.baseUrl.takeIf { it.isNotBlank() }?.let { hostOf(it) }

/**
 * The host part of a base URL — `100.97.198.90:8787` — and never more.
 *
 * A base URL cannot carry a bearer in this app (the token is a separate setting
 * and a separate header), but a person can paste anything into that field, so
 * the bundle takes the authority and nothing else rather than trusting what the
 * field happens to hold.
 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.trim().substringAfter("://").substringBefore('/').substringAfter('@')
        .ifBlank { "—" }

/**
 * The delivery facts as text somebody can paste.
 *
 * Written as lines of `label: value` rather than prose because it is read by
 * whoever is helping, not by the owner — and the questions it answers are
 * "which build, over which route" and "did the pushes huginn says it sent
 * arrive". It is generated at the moment the button is pressed: a bundle
 * assembled from a cached snapshot would describe a phone that no longer exists.
 *
 * @param nowMs the clock, injected so the witness lines are assertable.
 */
fun diagnosticsBundle(f: PhoneSettingsFacts, nowMs: Long): String {
    val lines = ArrayList<String>(16)
    lines += "huginn diagnostics"
    lines += "app: ${f.appVersion.ifBlank { "?" }}${f.updateWord.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}"
    lines += "appd: ${f.appdVersion?.takeIf { it.isNotBlank() } ?: "not answering"}"
    // The route by the OWNER'S NAME first: which path this phone is on is the
    // fact that explains a dead connection, and the address is the detail under
    // it. A pin nobody named still has a name — its own address.
    lines += "route: ${routeWord(f) ?: "none pinned"} · ${hostOf(f.baseUrl)}"
    lines += "connected: " + when (f.connected) {
        true -> "yes"
        false -> "no"
        null -> "not tested"
    }
    lines += "signed in: ${f.accountEmail?.takeIf { it.isNotBlank() } ?: "nobody"} · ${f.savedAccounts} saved"
    lines += "notifications: " + buildString {
        append(if (f.notifyEnabled) "on" else "off")
        append(if (f.notificationsAllowed) ", allowed by Android" else ", BLOCKED by Android")
        append(if (f.dozeExempt) ", exempt from doze" else ", NOT exempt from doze")
        if (f.watchEnabled) append(", watching continuously")
    }
    lines += "push: " + when {
        !f.pushConfigured -> "not configured on huginn"
        !f.pushRegistered -> "configured, this phone not registered"
        else -> "on"
    }
    // ARRIVED, clamped: this bundle is pasted into chats and issues, and "received
    // 1274" against "sent 916" sends whoever reads it looking for a bug that is
    // two counters from two host epochs. PushTally is the same clamp the page uses.
    lines += "pushes: huginn sent ${f.pushesSent}, this phone received " +
        "${PushTally.arrived(f.pushesReceived, f.pushesSent)}"
    lines += "background check: every ${minutes(f.heartbeatIntervalMs)}"
    lines += "app last reached huginn: ${witness(f.lastContactAt, nowMs)}"
    lines += "background check last ran: ${witness(f.lastAlarmAt, nowMs)}"
    lines += "huginn last heard from this phone: " + (
        f.hostSawUsSeconds?.let { s ->
            agoWordsMs(nowMs - s * 1000L, nowMs).ifBlank { "just now" } +
                (f.hostSawUsKind?.let { " ($it)" } ?: "")
        } ?: "never"
        )
    if (f.lastError.isNotBlank()) {
        // Scrubbed for the same reason the token is absent: this text is pasted
        // somewhere else, and the raw transport message carries the daemon's LAN
        // address. See the file header, and DeliveryCopy.
        lines += "last failure: ${witness(f.lastErrorAt, nowMs)} — ${DeliveryCopy.trouble(f.lastError)}"
    }
    lines += "alerts from huginn: " + (
        f.alerts?.let { a ->
            "${if (a.enabled) "on" else "off"} · ${a.channel} · ${a.mode} · ${a.delivered} sent"
        } ?: "not answered"
        )
    lines += "machines: ${f.deviceCount} enrolled, ${f.servingCount} serving"
    // No token line, and no field that could carry one. See the file header.
    return lines.joinToString("\n")
}

private fun witness(atMs: Long, nowMs: Long): String =
    if (atMs <= 0L) "never" else agoWordsMs(atMs, nowMs).ifBlank { "just now" }

private fun minutes(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 60_000L -> "${ms / 1000} s"
    ms % 3_600_000L == 0L -> "${ms / 3_600_000} h"
    else -> "${ms / 60_000} min"
}
