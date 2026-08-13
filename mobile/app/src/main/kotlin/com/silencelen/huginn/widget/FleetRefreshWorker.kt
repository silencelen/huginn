package com.silencelen.huginn.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.notify.WatchNotifier
import kotlinx.coroutines.flow.first

/**
 * One observation, on the widget's behalf: the refresh arrow, widget placement,
 * and the system's periodic tick land here.
 *
 * It runs the SAME cycle as every other observer when it can — a widget refresh
 * that noticed a session newly waiting should produce the same notification the
 * alarm would have, and the shared baselines are what keep that from ever
 * doubling. Only when notifications are switched off does it record the fleet
 * without announcing anything: the widget stays honest, the silence stays
 * chosen.
 */
class FleetRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(context)
        val token = settings.token.first()
        if (token.isBlank()) {
            // Signed out: nothing to fetch, but the widget should say so.
            FleetWidget.update(context)
            return Result.success()
        }
        val base = settings.baseUrl.first()
        val id = settings.clientId()
        val canNotify = SessionWatchWorker.canNotify(context)
        val client = HuginnClient({ base }, { token }, { id }, { canNotify })
        val watch = try {
            client.watch(knownHash = null, waitMs = 0)
        } catch (e: Exception) {
            // Off the tailnet, asleep, daemon restarting: ordinary. The redraw
            // still runs so the "as of" time keeps telling the truth about how
            // old what is on screen actually is.
            settings.noteWatchError(e.message ?: "unreachable", System.currentTimeMillis())
            FleetWidget.update(context)
            return Result.success()
        }
        settings.noteContact(System.currentTimeMillis())
        if (settings.notifyEnabled.first()) {
            WatchNotifier.apply(context, settings, watch, client)
        } else {
            WatchNotifier.recordFleet(context, settings, watch)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "fleet-refresh"

        /** KEEP, so a burst of taps costs one fetch rather than a queue of them. */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<FleetRefreshWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }
    }
}
