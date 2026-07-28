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
     * How recently a push must have arrived to keep trusting it. Longer than any
     * plausible quiet spell in a working setup, so an idle night does not tighten
     * the alarm — but short enough that a genuinely dead FCM path is noticed
     * within a couple of hours rather than a day.
     */
    val PUSH_TRUST_MS = 2 * 60 * 60 * 1000L

    /**
     * The cadence to use, given when a push last arrived. Pure, so the policy is
     * testable without a device: this is the whole battery story in one function.
     */
    fun intervalFor(lastPushAtMs: Long, nowMs: Long): Long =
        if (lastPushAtMs > 0L && nowMs - lastPushAtMs < PUSH_TRUST_MS) RELAXED_INTERVAL_MS
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
                    // Re-armed at the cadence push health earns: hourly while FCM
                    // is delivering, ten-minutely when it has gone quiet.
                    val settings = SettingsStore(app)
                    val last = runCatching { settings.lastPushAt.first() }.getOrDefault(0L)
                    Heartbeat.arm(app, Heartbeat.intervalFor(last, System.currentTimeMillis()))
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
