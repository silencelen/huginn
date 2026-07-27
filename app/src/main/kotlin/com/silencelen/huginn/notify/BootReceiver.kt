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
 * Restarts continuous watching after a reboot, if it was on.
 *
 * Without this, "watch continuously" quietly stops meaning that the first time
 * the phone restarts — and the user would have no reason to suspect it, since the
 * setting still reads as enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(app)
                if (settings.watchEnabled.first() && settings.token.first().isNotBlank()) {
                    WatchService.start(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
