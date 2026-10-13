package com.silencelen.huginn.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.color.ColorProviders
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.silencelen.huginn.MainActivity
import com.silencelen.huginn.ui.TimeFormat
import com.silencelen.huginn.ui.TimeWords
import com.silencelen.huginn.R
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.notify.Fleet
import com.silencelen.huginn.notify.FleetSnapshot
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.stateLabel
import com.silencelen.huginn.ui.theme.DarkHuginnColors
import com.silencelen.huginn.ui.theme.HuginnDarkColorScheme
import com.silencelen.huginn.ui.theme.HuginnLightColorScheme
import com.silencelen.huginn.ui.theme.LightHuginnColors
import com.silencelen.huginn.ui.theme.StateKind
import com.silencelen.huginn.ui.theme.stateKindOf
import com.silencelen.huginn.ui.theme.stateWord
import androidx.glance.material3.ColorProviders
import kotlinx.coroutines.flow.first

/**
 * The fleet on the launcher: which sessions need you, which are working, and a
 * quick way to start a chat — without opening the app.
 *
 * The widget never fetches for itself on render. It draws the [FleetSnapshot]
 * that the LAST observation recorded, because every way an observation arrives
 * (push, stream, alarm, 15-minute worker) already funnels through
 * [com.silencelen.huginn.notify.WatchNotifier.apply] — the widget rides that,
 * shows when the fleet was last seen, and offers a refresh that runs a real
 * observation through the same funnel. A widget with its own fetch loop would
 * be a fourth watcher to keep honest.
 */
class FleetWidget : GlanceAppWidget() {

    /**
     * Exact rather than responsive: the row budget is computed from the real
     * height, so resizing the widget taller shows more sessions instead of
     * snapping between two canned layouts.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read once per update, not collected: every writer of the snapshot pokes
        // updateAll, so a live subscription would only double the renders.
        val settings = SettingsStore(context)
        val signedIn = settings.token.first().isNotBlank()
        val snapshot = Fleet.decode(settings.fleetSnapshot.first())
        provideContent {
            GlanceTheme(colors = HuginnGlanceColors) {
                FleetContent(signedIn, snapshot)
            }
        }
    }

    companion object {
        const val EXTRA_NEW_CHAT = "new_chat"

        /**
         * Redraws every placed widget. `runCatching` because this is called from
         * the notification path — a widget-host hiccup must never cost an alert.
         * A no-op when no widget is placed.
         */
        suspend fun update(context: Context) {
            runCatching { FleetWidget().updateAll(context) }
        }
    }
}

/** Receives the system's widget broadcasts; placement and the periodic tick also refetch. */
class FleetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FleetWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // The system's update (placement, and the 30-minute backstop tick) is the
        // widget's own pulse — it must not depend on notifications being enabled,
        // so it runs a fetch of its own through the worker.
        FleetRefreshWorker.enqueue(context)
    }
}

/** The refresh arrow: one real observation, through the same funnel as every other. */
class RefreshFleetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        FleetRefreshWorker.enqueue(context)
    }
}

// ---------------------------------------------------------------------------
// Rendering. Glance composables, so nothing here is shared with :ui — but the
// vocabulary is the app's: the same dot colours and the same state words the
// sessions list uses, via stateLabel.
// ---------------------------------------------------------------------------

private fun openApp(context: Context) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

private fun openSession(context: Context, name: String) = openApp(context).apply {
    putExtra(SessionWatchWorker.EXTRA_SESSION, name)
}

private fun newChat(context: Context) = openApp(context).apply {
    putExtra(FleetWidget.EXTRA_NEW_CHAT, true)
}

@Composable
private fun FleetContent(signedIn: Boolean, snapshot: FleetSnapshot?) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // `background`, NOT `widgetBackground`: Glance's M3 bridge fills
            // widgetBackground from secondaryContainer, a role Theme.kt leaves to
            // Material's baseline violet (see PaletteTest). The ground is the
            // app's own.
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Header(snapshot)
        when {
            !signedIn -> Empty("Open Huginn to sign in", actionStartActivity(openApp(context)))
            snapshot == null -> Empty("No data yet — tap to check", actionRunCallback<RefreshFleetAction>())
            snapshot.sessions.isEmpty() -> Empty("No sessions on huginn", actionStartActivity(openApp(context)))
            else -> {
                CountsLine(snapshot)
                Spacer(GlanceModifier.height(6.dp))
                SessionRows(snapshot)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        ActionsRow(enabled = signedIn)
    }
}

@Composable
private fun Header(snapshot: FleetSnapshot?) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(openApp(context))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The wordmark is typed, lowercase, in the mono (BRAND.md section 5).
        // RemoteViews cannot load the bundled Plex, so the launcher's mono
        // stands in, at the heaviest weight Glance offers short of bold.
        Text(
            "huginn",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Spacer(GlanceModifier.defaultWeight())
        if (snapshot != null) {
            // ⚠ DAY-QUALIFIED. A bare short time-of-day with no date made a
            // snapshot 26 hours (or seven days) old render a string identical to
            // a two-hour-old one — while CountsLine went on asserting its stale
            // "N need you", because FleetRefreshWorker redraws WITHOUT updating
            // the snapshot when the watch call fails. TimeWords.stampMs is this
            // product's stamp: relative only inside the last six hours, the
            // calendar past that, "" for a stamp that is not one.
            Text(
                TimeWords.stampMs(snapshot.asOf, System.currentTimeMillis(), widgetTimeFormat(context)),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun CountsLine(snapshot: FleetSnapshot) {
    // A snapshot old enough to be about yesterday is not news any more. The
    // counts stay — they are the last truth this widget saw — but they stop
    // being said in the colours that mean "right now".
    val stale = fleetIsStale(snapshot.asOf, System.currentTimeMillis())
    Row(GlanceModifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (snapshot.attention > 0) {
            Text(
                "${snapshot.attention} ${stateWord(StateKind.NEEDS_YOU)}",
                style = TextStyle(
                    color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceStateColors.needsYou,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Dot(sepColor())
        }
        Text(
            "${snapshot.running} ${stateWord(StateKind.WORKING)}",
            style = TextStyle(
                color = if (snapshot.running > 0 && !stale) GlanceStateColors.working
                else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
        Dot(sepColor())
        Text(
            "${snapshot.quiet} ${stateWord(StateKind.WAITING)}",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
        if (snapshot.chatsRunning > 0) {
            Dot(sepColor())
            Text(
                "${snapshot.chatsRunning} chat" + (if (snapshot.chatsRunning == 1) "" else "s"),
                style = TextStyle(
                    color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

@Composable
private fun sepColor(): ColorProvider = GlanceTheme.colors.outline

/** The " · " between counts, drawn instead of typed so it never wraps apart. */
@Composable
private fun Dot(color: ColorProvider) {
    Text(" · ", style = TextStyle(color = color, fontSize = 12.sp))
}

@Composable
private fun SessionRows(snapshot: FleetSnapshot) {
    // The row budget comes from the widget's real height: header + counts +
    // actions + padding is ~110dp of overhead, each row ~24dp. Whatever is left
    // is list; a 2-cell widget gets a couple of rows, a 4-cell one gets six.
    val height = LocalSize.current.height
    val rows = (((height - 110.dp) / 24.dp).toInt()).coerceIn(0, 6)
    if (rows == 0) return
    val shown = snapshot.sessions.take(rows)
    val context = LocalContext.current
    Column {
        for (s in shown) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable(actionStartActivity(openSession(context, s.name))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(stateColor(s.state)),
                ) {}
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    s.name,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    stateLabel(s.state),
                    style = TextStyle(
                        color = stateColor(s.state),
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
        if (snapshot.sessions.size > shown.size) {
            Text(
                "+${snapshot.sessions.size - shown.size} more",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

/**
 * The app's scheme, handed to Glance: the SAME `ColorScheme` values Theme.kt
 * gives MaterialTheme (brand PR 5), day and night, so the widget cannot drift
 * from the app the way a hand-kept copy would.
 */
// Lazy: Glance's material bridge reads android.graphics.Color when it builds the
// providers, and a JVM unit test that touches this file's pure functions must not
// trip that at class load.
internal val HuginnGlanceColors: ColorProviders by lazy {
    ColorProviders(light = HuginnLightColorScheme, dark = HuginnDarkColorScheme)
}

/**
 * The state ladder for Glance. Glance cannot read the app's composition locals
 * (`LocalHuginnColors`, `stateColor`), so this mirrors theme/StateColors.kt with
 * day/night providers built from the same token objects ([LightHuginnColors] /
 * [DarkHuginnColors], tokens.json): the same seven words, the same colours, and
 * needs-you the lit gold, never the error red.
 */
internal object GlanceStateColors {
    private fun dayNight(day: Color, night: Color): ColorProvider =
        androidx.glance.color.ColorProvider(day = day, night = night)

    val working: ColorProvider = dayNight(LightHuginnColors.stateWorking, DarkHuginnColors.stateWorking)
    val needsYou: ColorProvider = dayNight(LightHuginnColors.stateAttention, DarkHuginnColors.stateAttention)
    val waiting: ColorProvider = dayNight(LightHuginnColors.stateIdle, DarkHuginnColors.stateIdle)
    val shell: ColorProvider = dayNight(HuginnLightColorScheme.outline, HuginnDarkColorScheme.outline)
    val ok: ColorProvider = dayNight(LightHuginnColors.stateSuccess, DarkHuginnColors.stateSuccess)
    val error: ColorProvider = dayNight(LightHuginnColors.stateError, DarkHuginnColors.stateError)
    val info: ColorProvider = dayNight(LightHuginnColors.stateInfo, DarkHuginnColors.stateInfo)

    fun of(kind: StateKind): ColorProvider = when (kind) {
        StateKind.WORKING -> working
        StateKind.NEEDS_YOU -> needsYou
        StateKind.WAITING -> waiting
        StateKind.SHELL -> shell
        StateKind.OK -> ok
        StateKind.ERROR -> error
        StateKind.INFO -> info
    }

    /** The daemon's wire word (`running` / `attention` / `idle` …), mapped once by [stateKindOf]. */
    fun forWire(state: String?): ColorProvider = of(stateKindOf(state))
}

private fun stateColor(state: String?): ColorProvider = GlanceStateColors.forWire(state)

@Composable
private fun Empty(message: String, onTap: androidx.glance.action.Action) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 14.dp).clickable(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

@Composable
private fun ActionsRow(enabled: Boolean) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (enabled) {
            Text(
                "New chat",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(14.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(actionStartActivity(newChat(context))),
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Image(
            provider = ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = "Refresh",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier
                .size(30.dp)
                .padding(6.dp)
                .clickable(actionRunCallback<RefreshFleetAction>()),
        )
    }
}

/**
 * Past this, the widget's counts stop being coloured as if they were current.
 *
 * Half a day is deliberately generous: this widget is fed by four observation
 * paths and a 30-minute tick, so anything this old means none of them have run
 * — off the tailnet, the phone asleep, or the daemon down — and the counts on
 * screen are a memory rather than a report.
 */
internal const val FLEET_STALE_MS: Long = 12 * 60 * 60 * 1000L

internal fun fleetIsStale(asOfMs: Long, nowMs: Long): Boolean =
    asOfMs > 0L && nowMs - asOfMs >= FLEET_STALE_MS

/** The viewer's own clock convention, for [TimeWords]. */
internal fun widgetTimeFormat(context: Context): TimeFormat = TimeFormat(
    tzOffsetSec = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
    hour24 = android.text.format.DateFormat.is24HourFormat(context),
)
