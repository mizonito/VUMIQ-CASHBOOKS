package com.vumiq.cashbook.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vumiq.cashbook.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-aligns customer & supplier due-date reminder schedules across device reboots,
 * APK updates, manual clock adjustments, and travel time-zone changes.
 */
class BootAndTimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = SettingsRepository(appContext).getDueReminderPreferences()
                if (prefs.enabled) {
                    DueDateReminderWorker.syncSchedule(
                        context = appContext,
                        enabled = true,
                        preferredHour = prefs.preferredHour,
                        runImmediateCheck = true
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
