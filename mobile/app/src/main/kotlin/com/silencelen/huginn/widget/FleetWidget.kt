package com.silencelen.huginn.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import com.silencelen.huginn.R
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.notify.Fleet
import com.silencelen.huginn.notify.FleetSnapshot
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.stateLabel
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

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
            GlanceTheme {
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
            .background(GlanceTheme.colors.widgetBackground)
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
        Text(
            "Huginn",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.defaultWeight())
        if (snapshot != null) {
            // Absolute, not "Nm ago": the widget redraws on observations, not on a
            // clock, so a relative age would sit there growing stale-wrong.
            Text(
                "as of " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.asOf)),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun CountsLine(snapshot: FleetSnapshot) {
    Row(GlanceModifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (snapshot.attention > 0) {
            Text(
                "${snapshot.attention} need you",
                style = TextStyle(
                    color = GlanceTheme.colors.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Dot(sepColor())
        }
        Text(
            "${snapshot.running} working",
            style = TextStyle(
                color = if (snapshot.running > 0) GlanceTheme.colors.primary
                else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
        Dot(sepColor())
        Text(
            "${snapshot.quiet} waiting",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
        if (snapshot.chatsRunning > 0) {
            Dot(sepColor())
            Text(
                "${snapshot.chatsRunning} chat" + (if (snapshot.chatsRunning == 1) "" else "s"),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
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

/** Same code the sessions list speaks: error = needs you, primary = working, muted = quiet. */
@Composable
private fun stateColor(state: String?): ColorProvider = when (state) {
    Fleet.ATTENTION -> GlanceTheme.colors.error
    Fleet.RUNNING -> GlanceTheme.colors.primary
    else -> GlanceTheme.colors.onSurfaceVariant
}

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
