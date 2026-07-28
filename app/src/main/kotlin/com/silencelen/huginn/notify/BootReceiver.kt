package com.silencelen.huginn.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores background watching after the two events that silently destroy it: a
 * reboot, and an update of this app.
 *
 * Both matter, and the second is the one that bites during development. A reboot
 * clears pending alarms and stops services; installing a new version of the app
 * ALSO cancels its alarms, so shipping a release would quietly end the heartbeat
 * until the app was next opened — with the settings screen still cheerfully
 * reporting that background delivery was on. Nothing about the symptom would point
 * at the update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(app)
                if (settings.token.first().isBlank()) return@launch
                if (settings.notifyEnabled.first()) Heartbeat.arm(app, delayMs = 60_000)
                if (settings.watchEnabled.first()) WatchService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
