package com.vumiq.cashbook.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Single process-wide Jetpack DataStore instance shared across settings and user preferences
 * to prevent IllegalStateException from multiple active DataStore delegates on the same file.
 */
internal val Context.vumiqPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vumiq_preferences"
)

data class CloudSyncTelemetrySnapshot(
    val lastSuccessfulSyncEpochMs: Long = 0L,
    val lastSyncStatus: String = "idle", // "idle" | "ok" | "error"
    val lastSyncError: String = ""
)

data class DueReminderPreferencesSnapshot(
    val enabled: Boolean = false,
    val leadDays: Int = 1,
    val preferredHour: Int = 9,
    val sentDedupKeys: Set<String> = emptySet(),
    val activeNotificationIds: Set<Int> = emptySet()
)

/**
 * DataStore-backed repository dedicated to reading and writing security, cloud sync,
 * and reminder preferences, specifically the 'biometric_unlock_enabled' boolean key checked during app boot.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.vumiqPreferencesDataStore)

    companion object {
        val BIOMETRIC_UNLOCK_ENABLED_KEY = booleanPreferencesKey("biometric_unlock_enabled")
        private val LEGACY_BIOMETRIC_LOCK_KEY = booleanPreferencesKey("biometric_lock_enabled")
        val RELOCK_TIMEOUT_SECONDS_KEY = intPreferencesKey("relock_timeout_seconds")

        val LAST_SYNC_SUCCESS_EPOCH_KEY = longPreferencesKey("cloud_last_sync_success_epoch_ms")
        val LAST_SYNC_STATUS_KEY = stringPreferencesKey("cloud_last_sync_status")
        val LAST_SYNC_ERROR_KEY = stringPreferencesKey("cloud_last_sync_error")

        val DUE_REMINDERS_ENABLED_KEY = booleanPreferencesKey("due_date_reminders_enabled")
        val DUE_REMINDER_LEAD_DAYS_KEY = intPreferencesKey("due_date_reminder_lead_days")
        val DUE_REMINDER_HOUR_KEY = intPreferencesKey("due_date_reminder_hour")
        val DUE_SENT_DEDUP_KEYS = stringSetPreferencesKey("due_date_sent_dedup_keys")
        val DUE_ACTIVE_NOTIFICATION_IDS_KEY = stringSetPreferencesKey("due_date_active_notification_ids")

        const val DEFAULT_BIOMETRIC_UNLOCK_ENABLED = true
        const val DEFAULT_RELOCK_TIMEOUT_SECONDS = 60
        const val DEFAULT_DUE_REMINDERS_ENABLED = false
        const val DEFAULT_DUE_REMINDER_LEAD_DAYS = 1
        const val DEFAULT_DUE_REMINDER_HOUR = 9
    }

    private val safePreferencesFlow: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    /**
     * Reactive stream of the persisted 'biometric_unlock_enabled' boolean preference.
     * Observed by SettingsView and CashbookViewModel.
     */
    val biometricUnlockEnabledFlow: Flow<Boolean> = safePreferencesFlow
        .map { prefs ->
            prefs[BIOMETRIC_UNLOCK_ENABLED_KEY]
                ?: prefs[LEGACY_BIOMETRIC_LOCK_KEY]
                ?: DEFAULT_BIOMETRIC_UNLOCK_ENABLED
        }
        .distinctUntilChanged()

    /**
     * Reactive stream of the background auto-relock timeout in seconds.
     */
    val relockTimeoutSecondsFlow: Flow<Int> = safePreferencesFlow
        .map { prefs ->
            prefs[RELOCK_TIMEOUT_SECONDS_KEY] ?: DEFAULT_RELOCK_TIMEOUT_SECONDS
        }
        .distinctUntilChanged()

    /**
     * Reactive stream of Cloud Firestore synchronization telemetry (last success timestamp & errors).
     */
    val cloudSyncTelemetryFlow: Flow<CloudSyncTelemetrySnapshot> = safePreferencesFlow
        .map { prefs ->
            CloudSyncTelemetrySnapshot(
                lastSuccessfulSyncEpochMs = prefs[LAST_SYNC_SUCCESS_EPOCH_KEY] ?: 0L,
                lastSyncStatus = prefs[LAST_SYNC_STATUS_KEY] ?: "idle",
                lastSyncError = prefs[LAST_SYNC_ERROR_KEY].orEmpty()
            )
        }
        .distinctUntilChanged()

    val dueReminderPreferencesFlow: Flow<DueReminderPreferencesSnapshot> = safePreferencesFlow
        .map { prefs ->
            DueReminderPreferencesSnapshot(
                enabled = prefs[DUE_REMINDERS_ENABLED_KEY] ?: DEFAULT_DUE_REMINDERS_ENABLED,
                leadDays = (prefs[DUE_REMINDER_LEAD_DAYS_KEY] ?: DEFAULT_DUE_REMINDER_LEAD_DAYS).coerceIn(0, 30),
                preferredHour = (prefs[DUE_REMINDER_HOUR_KEY] ?: DEFAULT_DUE_REMINDER_HOUR).coerceIn(0, 23),
                sentDedupKeys = prefs[DUE_SENT_DEDUP_KEYS].orEmpty(),
                activeNotificationIds = prefs[DUE_ACTIVE_NOTIFICATION_IDS_KEY]
                    .orEmpty()
                    .mapNotNull { it.toIntOrNull() }
                    .toSet()
            )
        }
        .distinctUntilChanged()

    /**
     * Reads the persisted 'biometric_unlock_enabled' key during the app boot security check.
     */
    suspend fun isBiometricUnlockEnabled(): Boolean =
        biometricUnlockEnabledFlow.first()

    /**
     * Writes the 'biometric_unlock_enabled' boolean key atomically to Jetpack DataStore.
     * Uses NonCancellable so even if the user closes the app immediately after toggling in
     * SettingsView, the preference flush completes and takes effect on the next app boot.
     */
    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[BIOMETRIC_UNLOCK_ENABLED_KEY] = enabled
                prefs.remove(LEGACY_BIOMETRIC_LOCK_KEY)
            }
        }
    }

    suspend fun getRelockTimeoutSeconds(): Int =
        relockTimeoutSecondsFlow.first()

    suspend fun setRelockTimeoutSeconds(seconds: Int) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[RELOCK_TIMEOUT_SECONDS_KEY] = seconds
            }
        }
    }

    suspend fun recordCloudSyncSuccess(nowEpochMs: Long = System.currentTimeMillis()) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[LAST_SYNC_SUCCESS_EPOCH_KEY] = nowEpochMs
                prefs[LAST_SYNC_STATUS_KEY] = "ok"
                prefs[LAST_SYNC_ERROR_KEY] = ""
            }
        }
    }

    suspend fun recordCloudSyncFailure(errorMessage: String) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[LAST_SYNC_STATUS_KEY] = "error"
                prefs[LAST_SYNC_ERROR_KEY] = errorMessage.take(300)
            }
        }
    }

    suspend fun getDueReminderPreferences(): DueReminderPreferencesSnapshot =
        dueReminderPreferencesFlow.first()

    suspend fun setDueRemindersEnabled(enabled: Boolean) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[DUE_REMINDERS_ENABLED_KEY] = enabled
                if (!enabled) {
                    prefs[DUE_ACTIVE_NOTIFICATION_IDS_KEY] = emptySet()
                }
            }
        }
    }

    suspend fun setDueReminderLeadDays(leadDays: Int) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[DUE_REMINDER_LEAD_DAYS_KEY] = leadDays.coerceIn(0, 30)
            }
        }
    }

    suspend fun setDueReminderPreferredHour(hour24: Int) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[DUE_REMINDER_HOUR_KEY] = hour24.coerceIn(0, 23)
            }
        }
    }

    suspend fun updateDueReminderDeliveryState(
        updatedSentKeys: Set<String>,
        activeNotificationIds: Set<Int>
    ) {
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                prefs[DUE_SENT_DEDUP_KEYS] = updatedSentKeys
                prefs[DUE_ACTIVE_NOTIFICATION_IDS_KEY] = activeNotificationIds.map { it.toString() }.toSet()
            }
        }
    }
}
