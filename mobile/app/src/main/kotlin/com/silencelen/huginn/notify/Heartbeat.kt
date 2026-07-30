package com.silencelen.huginn.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The alarm that fires while the phone is asleep.
 *
 * This is the piece that was missing, and it is worth being precise about why the
 * two mechanisms already in the app cannot do it:
 *
 *   * WorkManager is deferred by Doze. A periodic job does not run while the device
 *     is idle; it waits for a maintenance window or for the screen to come on. That
 *     is why the 15-minute poll here — and devstore's six-hourly one — appear to
 *     work perfectly and then deliver nothing overnight. The job is not lost, it is
 *     simply queued behind the thing being waited for.
 *   * A foreground service keeps the process alive, but its socket still depends on
 *     a radio that Doze is powering down, and the system may kill the service under
 *     memory pressure with nothing to bring it back.
 *
 * `setAndAllowWhileIdle` is the documented exception: it fires during Doze, granted
 * a brief window of network access, throttled to roughly once every nine minutes.
 * That makes it slower than the stream and completely independent of it — the useful
 * combination, since the stream is the fast path when awake and this is the floor
 * that holds when everything else has been suspended. It also revives the service,
 * so a kill during the night self-heals rather than lasting until the app is opened.
 *
 * Ten minutes rather than nine: asking for less than the throttle allows means the
 * system silently rounds it up, and a schedule that lies about its own period is
 * worse than one that admits it.
 */
object Heartbeat {

    /** The safety-net cadence used when push cannot be trusted. */
    val INTERVAL_MS = 10 * 60 * 1000L

    /**
     * The cadence used while push is demonstrably working.
     *
     * Measured on the owner's SM-F966U (2026-07-28): a high-priority FCM message
     * reached the phone in 17-86ms in EVERY state — app open, backgrounded,
     * process killed, screen off, full Doze, and Doze with the process killed —
     * and it did so with the app REMOVED from the battery allowlist (34ms). The
     * ten-minute alarm was insurance against a delivery path that turns out not
     * to need it, and 144 device wake-ups a day is a real cost for insurance.
     *
     * So the alarm stretches to hourly while push is proving itself, and stays
     * only as the thing that notices if push ever stops.
     */
    val RELAXED_INTERVAL_MS = 60 * 60 * 1000L

    /**
     * The cadence to use, from the only evidence that actually distinguishes a
     * working push path from a broken one.
     *
     * The previous rule — relax while a push arrived within the last two hours —
     * was wrong, and the overnight measurement said so plainly. A quiet night is
     * SILENT: no chats finish, no sessions ask anything, so no pushes arrive, so
     * after two hours the rule concluded push had failed and tightened to ten
     * minutes. The hours with the least to report were the hours it woke the phone
     * most, which is precisely backwards.
     *
     * The mistake was treating "no push arrived" as evidence of failure when it is
     * mostly evidence that nothing happened. Those two are indistinguishable from
     * the phone alone — but not from the host, which knows exactly how many pushes
     * it sent here. So the host reports its tally and this compares:
     *
     *   * host sent no more than arrived → nothing is being dropped → stay relaxed,
     *     however long the silence;
     *   * host sent more than arrived → a push went missing → tighten, immediately,
     *     without waiting out any trust window.
     *
     * A push in flight at the moment of a beat can show a deficit of one for a few
     * seconds. That resolves itself on the next beat, and erring toward checking
     * more often is the safe direction for a fallback.
     *
     * Relaxing also has to be EARNED: until a push has actually arrived once, the
     * path is unproven and the tight cadence is what makes a fresh install work at
     * all when FCM turns out to be unavailable.
     *
     * Pure, so the whole battery story is one testable function.
     */
    fun intervalFor(pushesSent: Long, pushesReceived: Long): Long =
        if (pushesReceived > 0L && pushesSent <= pushesReceived) RELAXED_INTERVAL_MS
        else INTERVAL_MS

    private const val REQUEST = 4713

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun arm(context: Context, delayMs: Long = INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                intent(context),
            )
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(intent(context)) }
    }

    /**
     * Whether this app sits on the battery-optimisation allowlist.
     *
     * Without it, Doze suspends the app's network access, and the difference is the
     * whole story: the alarm still fires but its request fails, so the symptom is
     * not "no alarm" but "an alarm that reaches nothing" — which looks identical
     * from the outside and is why this is surfaced in Settings rather than assumed.
     */
    fun isExemptFromDoze(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /**
     * The system dialogue asking for that allowlist entry. Play Store policy forbids
     * asking for this except for a handful of app types; this app is sideloaded from
     * devstore, so the only judge of whether it is warranted is the person who owns
     * both the phone and the host it is watching.
     */
    fun requestDozeExemption(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val settings = SettingsStore(app)

            // ARMED FIRST, before any work.
            //
            // The next beat used to be scheduled only in the `finally` below —
            // after a network tick bounded at 25 SECONDS, inside a broadcast
            // receiver that is guaranteed roughly ten. A process killed in that
            // window (Doze, memory pressure, the system reclaiming a background
            // app — the exact conditions this alarm exists for) left NO pending
            // alarm at all, and the heartbeat was simply over: silently, until
            // the app was next opened by hand. Scheduling before working means
            // the chain survives the tick dying in any manner whatsoever.
            //
            // Same PendingIntent and request code, so the refinement at the end
            // REPLACES this rather than stacking a second alarm.
            suspend fun armFromHealth() = Heartbeat.arm(app, Heartbeat.intervalFor(
                runCatching { settings.pushesSent.first() }.getOrDefault(0L),
                runCatching { settings.pushesReceived.first() }.getOrDefault(0L),
            ))
            runCatching { armFromHealth() }

            var rearm = true
            try {
                // Bounded: a broadcast receiver is not a place to wait on a network,
                // and the next alarm is only ten minutes away in any case.
                rearm = withTimeoutOrNull(25_000) { tick(app) } ?: true
            } catch (_: Exception) {
                // Deliberately swallowed. See the re-arm below.
            } finally {
                // Re-armed even after a failure, and this is the important part: a
                // one-shot alarm that only re-arms on success stops for good the
                // first time the tailnet is unreachable — which is a nightly event,
                // not an exceptional one. Only an explicit "the feature is off"
                // clears this flag.
                if (rearm) {
                    // At the cadence push health has earned, now that the tick has
                    // refreshed the tallies it is read from.
                    runCatching { armFromHealth() }
                } else {
                    // The feature is off: undo the defensive arm above, or it
                    // would keep waking the device for a watch nobody wants.
                    Heartbeat.cancel(app)
                }
                pending.finish()
            }
        }
    }

    /** @return whether to schedule the next beat. */
    private suspend fun tick(app: Context): Boolean {
        val settings = SettingsStore(app)
        settings.noteAlarm(System.currentTimeMillis())

        if (!settings.notifyEnabled.first()) return false
        val token = settings.token.first()
        if (token.isBlank()) return false

        // Revive the streaming watcher if it was killed while the phone slept. Doing
        // this first means even a failed check leaves the fast path restored.
        if (settings.watchEnabled.first()) WatchService.start(app)

        // And make good on what BootReceiver has always claimed the first beat does:
        // hand over the push token if the host does not have the current one. A no-op
        // on an ordinary beat.
        runCatching { HuginnMessagingService.ensureTokenRegistered(app) }

        val base = settings.baseUrl.first()
        val id = settings.clientId()
        val canNotify = SessionWatchWorker.canNotify(app)
        val client = HuginnClient({ base }, { token }, { id }, { canNotify })
        return try {
            // Zero wait: this is a look, not a vigil. The host records it as a
            // heartbeat, which is how "the alarm kept firing all night" becomes
            // something readable the next morning.
            val watch = client.watch(knownHash = null, waitMs = 0)
            settings.noteContact(System.currentTimeMillis())
            WatchCycle.apply(app, settings, watch, client)
            true
        } catch (e: Exception) {
            // Off the tailnet, host rebooting, radio not up yet: all ordinary at
            // 3am. Recorded so it can be seen, not treated as a reason to stop.
            settings.noteWatchError(e.message ?: "unreachable", System.currentTimeMillis())
            true
        }
    }
}
