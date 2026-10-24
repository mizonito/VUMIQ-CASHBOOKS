package com.vumiq.cashbook.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vumiq.cashbook.data.local.CashbookDatabase
import com.vumiq.cashbook.data.preferences.SettingsRepository
import com.vumiq.cashbook.data.preferences.UserPreferencesStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Durable WorkManager background synchronization worker.
 *
 * Queues offline Room mutations with a `NetworkType.CONNECTED` constraint and exponential backoff
 * so recorded transactions, edits, voids, accounts, and parties automatically sync as soon as
 * network connectivity returns—without ever losing or duplicating financial records.
 */
class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val syncManager = FirestoreSyncManager(applicationContext)
        if (!syncManager.isFirebaseConfigured() || syncManager.currentCloudUser() == null) {
            return Result.success()
        }

        val db = CashbookDatabase.getInstance(applicationContext)
        val prefsStore = UserPreferencesStore(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val prefs = prefsStore.preferencesFlow.first()

        val outcome = syncManager.syncWorkspaceTwoWay(
            dao = db.cashbookDao(),
            prefs = prefs
        )

        return if (outcome.ok) {
            settingsRepository.recordCloudSyncSuccess()
            // Ensure active business ID is selected if businesses were pulled onto a fresh device
            if (prefs.activeBusinessId.isBlank()) {
                val firstBiz = db.cashbookDao().getBusinessesSnapshot().firstOrNull()
                if (firstBiz != null) {
                    prefsStore.setActiveBusinessId(firstBiz.id)
                    prefsStore.setSelectedCurrencyCode(firstBiz.defaultCurrency)
                }
            }
            Result.success()
        } else {
            settingsRepository.recordCloudSyncFailure(outcome.message)
            if (outcome.shouldRetryLater && runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "vumiq_cloud_sync_queue"

        fun enqueueWhenConnected(context: Context) {
            val syncManager = FirestoreSyncManager(context.applicationContext)
            if (!syncManager.isFirebaseConfigured() || syncManager.currentCloudUser() == null) {
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
