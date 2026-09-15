package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Alerts
import com.silencelen.huginn.data.ClientsInfo
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.PushStatus
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.ui.HeadroomSettingsSection
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.agoWordsMs
import kotlin.math.roundToInt

/**
 * The seven smaller drawers, and the two pieces of furniture every drawer is
 * built from.
 *
 * Each page takes only what it needs — that is the point of the exercise. The
 * screen these replace was one 911-line composable behind a 41-parameter
 * signature, and the reason nobody could see it was getting crowded is that
 * there was nowhere for a new setting to be crowded OUT of.
 *
 * ⚠ NO INLINE `titleMedium` HEADERS. The nine the old screen drew are deleted;
 * the drawer's own title is the heading, and [SettingsGroup] is a small label
 * for the two named groups inside *Notifications* — "From huginn" and "From this
 * phone" — which are the only place a second level was ever earning its keep.
 */

/** The one small caption a page is allowed. Never a heading. */
@Composable
fun SettingsGroup(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 14.dp, bottom = 2.dp, start = 8.dp),
    )
}

/** The muted meta line. The same weight as the summaries, so it reads as one. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------- usage

/**
 * The arbiter's whole form, plus the one row that says where the NUMBERS are.
 *
 * The form itself is `:ui`'s and is dropped in verbatim — both clients offer the
 * same fields and refuse the same values, and the host validates them again and
 * answers in its own words. What this page owns is where it sits and that
 * account switching is rendered HERE and nowhere else.
 */
@Composable
fun UsagePage(
    settings: HeadroomSettings?,
    models: List<ModelChoice>,
    busy: Boolean,
    note: String?,
    onSave: (HeadroomSettings) -> Unit,
    onOpenStatus: () -> Unit,
    highlight: String?,
) {
    SettingsNavRow(
        id = "usage.plan",
        title = "Plan usage",
        summary = "The live percentages, the windows and when they reset.",
        trailingText = "Status",
        onOpen = onOpenStatus,
        highlighted = SettingsRowStyle.isHighlighted("usage.plan", highlight),
    )
    Column(Modifier.padding(top = 8.dp)) {
        HeadroomSettingsSection(
            settings = settings,
            models = models,
            onSave = onSave,
            busy = busy,
            note = note,
        )
    }
}

// ------------------------------------------------------------------- chats

/**
 * What huginn types for you: the composer templates, and the wrap-up phrase.
 *
 * NEW ON THE PHONE. The templates are host-owned and this client has consumed
 * them since 3.0.1 without being able to change them — `w2-surface.md:583`
 * promised the editor and it was never built. The editor is the desktop's,
 * moved to `:ui` unchanged, so both clients now edit one set of words.
 *
 * Soft end is READ-ONLY on purpose: the phrase and the auto-end flag are the
 * host's, there is no route to write them, and a field whose Save can only 404
 * is worse than a fact.
 */
@Composable
fun ChatsPage(
    quickActions: QuickActions?,
    busy: Boolean,
    note: String?,
    onSaveQuickActions: (QuickActions) -> Unit,
    softEndPhrase: String?,
    softEndAuto: Boolean,
    highlight: String?,
) {
    if (quickActions != null) {
        SettingsReadOnlyRow(
            id = "chats.quick-actions",
            title = "Quick actions",
            summary = "What Explain, Execute and Ask in a new chat put in the composer for selected text.",
            highlighted = SettingsRowStyle.isHighlighted("chats.quick-actions", highlight),
        )
        Column(Modifier.padding(start = 8.dp)) {
            QuickActionsEditor(
                actions = quickActions,
                busy = busy,
                note = note,
                onSave = onSaveQuickActions,
            )
        }
    }
    SettingsReadOnlyRow(
        id = "chats.soft-end",
        title = "Soft end",
        value = if (softEndAuto) "ends itself" else "stays open",
        summary = softEndPhrase?.takeIf { it.isNotBlank() }
            ?: "The phrase huginn types to wind a session down. Held on the host.",
        highlighted = SettingsRowStyle.isHighlighted("chats.soft-end", highlight),
        modifier = Modifier.padding(top = 10.dp),
    )
}

// ------------------------------------------------------------------ notify

/**
 * Two mechanisms, named as two groups, because they were two switches one word
 * apart with nothing saying they were different things.
 *
 * "From huginn" is the host messaging you over Telegram — it works with this app
 * closed, which is most of the time, so it is listed first. "From this phone" is
 * this client's own notifications, which need Android's permission, an exemption
 * from Doze, and either a push or a background check to carry them.
 */
@Composable
fun NotifyPage(
    alerts: Alerts?,
    onAlertsEnabled: (Boolean) -> Unit,
    onAlertsMode: (String) -> Unit,
    notifyEnabled: Boolean,
    onNotifyEnabled: (Boolean) -> Unit,
    watchEnabled: Boolean,
    onWatchEnabled: (Boolean) -> Unit,
    health: HuginnViewModel.DeliveryHealth,
    push: PushStatus?,
    clients: ClientsInfo?,
    nowMs: Long,
    onRequestDozeExemption: () -> Unit,
    onRefreshDelivery: () -> Unit,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    highlight: String?,
) {
    alerts?.let { al ->
        SettingsGroup("From huginn")
        SettingsToggleRow(
            id = "notify.host-alerts",
            title = "Message me when a session needs me",
            summary = if (al.channel == "telegram")
                "Sent by huginn over Telegram, so it reaches you with this app closed and costs " +
                    "no battery. Also tells you when a long chat finishes." +
                    (if (al.delivered > 0) " ${al.delivered} sent so far." else "")
            else "No delivery channel is configured on huginn.",
            checked = al.enabled,
            onCheckedChange = onAlertsEnabled,
            enabled = al.channel != "none",
            highlighted = SettingsRowStyle.isHighlighted("notify.host-alerts", highlight),
        )
        if (al.enabled) {
            // Fallback is the default and the recommendation: both channels firing
            // for one event teaches you to dismiss without reading, and then the
            // one that mattered is gone too.
            SettingsToggleRow(
                id = "notify.host-alerts-mode",
                title = "Only when the app is out of contact",
                summary = when {
                    al.mode == "always" ->
                        "Off, so every alert arrives twice — once in the app and once on Telegram."
                    al.appOnline ->
                        "On. huginn can see this phone checking in, so it is staying quiet and " +
                            "letting the app notify you."
                    else ->
                        "On. huginn has not heard from this phone recently, so Telegram is " +
                            "carrying alerts."
                },
                checked = al.mode != "always",
                onCheckedChange = { onAlertsMode(if (it) "fallback" else "always") },
                highlighted = SettingsRowStyle.isHighlighted("notify.host-alerts-mode", highlight),
            )
        }
    }

    SettingsGroup("From this phone")
    SettingsToggleRow(
        id = "notify.device-watch",
        title = "Tell me when a session needs me",
        summary = "Checks huginn about every 10 minutes, including while the phone is asleep, " +
            "and notifies when a session starts waiting for an answer.",
        checked = notifyEnabled,
        onCheckedChange = onNotifyEnabled,
        highlighted = SettingsRowStyle.isHighlighted("notify.device-watch", highlight),
    )
    if (!notifyEnabled) return

    // The SAME catalog id as the row above it: the catalog holds one item for
    // both controls (inventory 19 and 20) because they are one setting with a
    // coarse and a fine step, and a shell must not draw an id the catalog does
    // not own. The highlight stays on the parent so an arrival marks one row.
    SettingsToggleRow(
        id = "notify.device-watch",
        title = "Watch continuously",
        summary = if (watchEnabled)
            "Alerts arrive within seconds while the phone is awake. Android requires a quiet " +
                "ongoing notification, and it shows what huginn is doing."
        else
            "Off, so alerts wait for the 10-minute check rather than arriving at once. " +
                "Nothing is missed either way.",
        checked = watchEnabled,
        onCheckedChange = onWatchEnabled,
    )

    // The part that decides whether any of the above works while the phone
    // sleeps, and the part that used to be invisible: without the allowlist entry
    // Android suspends this app's network during Doze, so the check still fires
    // and reaches nothing — a failure indistinguishable from no check at all.
    if (!health.dozeExempt) {
        SettingsActionRow(
            id = "notify.background",
            title = "Allow background use",
            summary = "Android is allowed to put this app to sleep. When it does, the checks still " +
                "run but cannot reach huginn — this is the usual reason notifications stop " +
                "arriving overnight.",
            actionLabel = "Allow",
            onAction = onRequestDozeExemption,
            highlighted = SettingsRowStyle.isHighlighted("notify.background", highlight),
        )
    } else {
        SettingsReadOnlyRow(
            id = "notify.background",
            title = "Background use",
            value = "allowed",
            summary = "Exempt from battery optimisation, so checks keep working while the phone is asleep.",
            highlighted = SettingsRowStyle.isHighlighted("notify.background", highlight),
        )
    }

    if (!notificationsAllowed) {
        SettingsReadOnlyRow(
            id = "notify.permission",
            title = "Android permission",
            value = "blocked",
            summary = "Android is blocking notifications for this app, so none will arrive.",
            highlighted = SettingsRowStyle.isHighlighted("notify.permission", highlight),
        )
        Row(
            Modifier.padding(start = 8.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onRequestNotifications) { Text("Allow") }
            OutlinedButton(onClick = onOpenSystemNotificationSettings) { Text("System settings") }
        }
    } else {
        SettingsReadOnlyRow(
            id = "notify.permission",
            title = "Android permission",
            value = "allowed",
            highlighted = SettingsRowStyle.isHighlighted("notify.permission", highlight),
        )
    }

    // Push before the counts, because when it is working the numbers stop
    // mattering much: FCM reaches a sleeping phone in seconds where the alarm
    // below takes up to ten minutes.
    push?.let { ps ->
        val registered = ps.devices.isNotEmpty()
        SettingsReadOnlyRow(
            id = "notify.delivery",
            title = "Delivery",
            value = when {
                ps.configured && registered -> "push"
                ps.configured -> "not registered"
                else -> "check only"
            },
            summary = when {
                ps.configured && registered ->
                    "huginn sends straight to this phone through Google, which arrives in seconds " +
                        "even while it is asleep."
                ps.configured ->
                    "huginn can push, but this phone has not registered yet. It registers itself " +
                        "on start — check the token in Host & sign-in."
                else ->
                    "Push is not set up on huginn, so alerts arrive on the 10-minute check or by " +
                        "Telegram instead."
            },
            highlighted = SettingsRowStyle.isHighlighted("notify.delivery", highlight),
        )
    }
    Column(Modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        push?.let { ps ->
            if (ps.pushed > 0) SettingsNote("${ps.pushed} delivered so far")
            SettingsNote(
                when {
                    health.pushesReceived == 0L ->
                        "No push has arrived here yet, so the backup check runs every 10 minutes " +
                            "until one proves it can."
                    health.pushesMissing > 0L ->
                        "${health.pushesMissing} push(es) huginn sent never arrived, so the backup " +
                            "check has tightened to every 10 minutes."
                    else ->
                        "${health.pushesReceived} of ${health.pushesSent} pushes arrived — nothing " +
                            "dropped, so the backup check only runs hourly."
                },
            )
        }
        // Two witnesses. The app's own record can only be written while the app is
        // alive, so it cannot testify about the hours that matter; huginn's was
        // taken by a machine that never slept.
        SettingsNote("This app last reached huginn ${witnessWords(health.lastContactAt, nowMs)}")
        SettingsNote("Background check last ran ${witnessWords(health.lastAlarmAt, nowMs)}")
        clients?.clients?.firstOrNull()?.let { c ->
            SettingsNote(
                "huginn last heard from this phone " +
                    (agoWordsMs(nowMs - c.ageSeconds * 1000L, nowMs).ifBlank { "just now" }) +
                    (c.kind?.let { " ($it)" } ?: "") + " · ${c.checkIns} check-ins",
            )
        }
        if (health.lastError.isNotBlank()) {
            SettingsNote("Last failure ${witnessWords(health.lastErrorAt, nowMs)}: ${health.lastError}")
        }
        OutlinedButton(onClick = onRefreshDelivery, modifier = Modifier.padding(top = 6.dp)) {
            Text("Refresh")
        }
    }
}

/** "4 minutes ago", or "never" for a zero — a 0 stamp means it has not happened. */
internal fun witnessWords(atMs: Long, nowMs: Long): String =
    if (atMs <= 0L) "never" else agoWordsMs(atMs, nowMs).ifBlank { "just now" }

// ----------------------------------------------------------------- devices

/**
 * One row, and that is the finding.
 *
 * Everything else the desktop keeps here — enrolment, scope, the work folder,
 * the claude path, local-AI serving — is a MACHINE's decision about itself, and
 * this phone is not one of those machines. What it has is the way in.
 */
@Composable
fun DevicesPage(deviceCount: Int, servingCount: Int, onOpenFleet: () -> Unit, highlight: String?) {
    SettingsNavRow(
        id = "devices.fleet",
        title = "Machines",
        summary = "Your PC, a server, a build box. A machine offers ITSELF and decides what it " +
            "will allow — this phone can see them, start work on one, and withdraw an enrolment.",
        trailingText = when {
            deviceCount == 0 && servingCount == 0 -> "none"
            servingCount > 0 -> "$deviceCount · $servingCount serving"
            else -> "$deviceCount"
        },
        onOpen = onOpenFleet,
        highlighted = SettingsRowStyle.isHighlighted("devices.fleet", highlight),
    )
}

// ----------------------------------------------------------------- privacy

/**
 * The two irreversible local things, and the sentence nobody can find when they
 * want it.
 *
 * Sign out is NOT here — it is beside the account it signs out of, in *Host &
 * sign-in*, which is the same-verb-one-control rule.
 */
@Composable
fun PrivacyPage(
    appLock: Boolean,
    appLockAvailable: Boolean,
    onAppLock: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    highlight: String?,
) {
    SettingsToggleRow(
        id = "privacy.app-lock",
        title = "Lock the app",
        summary = if (!appLockAvailable)
            "Needs a screen lock on this phone first — there is nothing to unlock with."
        else
            "Ask for fingerprint, face or the device PIN when opening the app after it has been " +
                "away for a minute, and keep huginn out of the recents preview. This app is a " +
                "hand on huginn; an unlocked phone passed to someone should not include it.",
        checked = appLock,
        onCheckedChange = onAppLock,
        enabled = appLockAvailable,
        highlighted = SettingsRowStyle.isHighlighted("privacy.app-lock", highlight),
    )
    // Proof on demand. The lock's normal trigger is time away, which makes "is it
    // even on?" unanswerable by looking — the first version's silent failure sat
    // unnoticed behind exactly that.
    if (appLock && appLockAvailable) {
        SettingsActionRow(
            id = "privacy.lock-now",
            title = "Lock now",
            summary = "Locks huginn immediately instead of waiting for the next time it is opened.",
            actionLabel = "Lock",
            onAction = onLockNow,
            highlighted = SettingsRowStyle.isHighlighted("privacy.lock-now", highlight),
        )
    }
    SettingsReadOnlyRow(
        id = "privacy.token",
        title = "What the token is",
        summary = "The bearer is kept in this app's own storage, which no other app can read. " +
            "Anyone holding it can do everything this app can do — read every transcript, type " +
            "into every session, start work on every enrolled machine. It is not a password for " +
            "an account; it is the key to the host.",
        highlighted = SettingsRowStyle.isHighlighted("privacy.token", highlight),
    )
}

// -------------------------------------------------------------- appearance

/**
 * Nearly empty, and that is the finding rather than a gap to fill.
 *
 * Terminal text size finally gets a home: it has been editable only by pinching
 * the live pane, which is a gesture nobody discovers and cannot be used at all
 * on a session you are not looking at. NO THEME PICKER — the theme is hardcoded
 * in both shells and a picker is a feature, not a reorganisation.
 */
@Composable
fun AppearancePage(fontScale: Float, onFontScale: (Float) -> Unit, highlight: String?) {
    var live by remember(fontScale) { mutableFloatStateOf(fontScale) }
    SettingsReadOnlyRow(
        id = "appearance.terminal-text-size",
        title = "Terminal text size",
        value = "${live.roundToInt()} sp",
        summary = "How big the session pane's text is. Pinching the pane changes the same setting.",
        highlighted = SettingsRowStyle.isHighlighted("appearance.terminal-text-size", highlight),
    )
    Row(
        Modifier.widthIn(max = SettingsRowStyle.ROW_MAX_WIDTH).fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Slider(
            value = live,
            // The same bounds the pinch gesture is clamped to, so the two cannot
            // disagree about what is legal.
            valueRange = 5.5f..22f,
            onValueChange = { live = it },
            onValueChangeFinished = { onFontScale(live) },
            modifier = Modifier.weight(1f),
        )
        Text(
            "${live.roundToInt()} sp",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------- about

@Composable
fun AboutPage(appVersion: String, appdVersion: String?, repo: String, highlight: String?) {
    SettingsReadOnlyRow(
        id = "about.version",
        title = "Version",
        value = appVersion.ifBlank { "—" },
        // The app and the daemon version INDEPENDENTLY — "app 3.1.0 beside appd
        // 3.0.5" is a correct, ordinary state, and a screen showing only one
        // number reads as the other's to anyone who has not internalised that.
        summary = "This app.",
        highlighted = SettingsRowStyle.isHighlighted("about.version", highlight),
    )
    SettingsReadOnlyRow(
        id = "about.host-version",
        title = "Host version",
        value = appdVersion?.takeIf { it.isNotBlank() } ?: "not answering",
        summary = "The huginn daemon this app is talking to. It versions separately from the app.",
        highlighted = SettingsRowStyle.isHighlighted("about.host-version", highlight),
    )
    SettingsReadOnlyRow(
        id = "about.repo",
        title = "Source",
        value = "github.com/$repo",
        summary = "Where this app's source and its releases live.",
        highlighted = SettingsRowStyle.isHighlighted("about.repo", highlight),
    )
    SettingsReadOnlyRow(
        id = "about.what-this-is",
        title = "What huginn is",
        summary = "A front end for Claude Code sessions running on your own machine. Chats are " +
            "headless turns in huginn's working directory; sessions are the real tmux sessions, " +
            "so one you open here is the same one your laptop attaches to.",
        highlighted = SettingsRowStyle.isHighlighted("about.what-this-is", highlight),
    )
}
