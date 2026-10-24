package com.vumiq.cashbook.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vumiq.cashbook.MainActivity
import com.vumiq.cashbook.R
import com.vumiq.cashbook.data.local.CashbookDatabase
import com.vumiq.cashbook.data.local.toDomain
import com.vumiq.cashbook.data.preferences.SettingsRepository
import com.vumiq.cashbook.data.preferences.UserPreferencesStore
import com.vumiq.cashbook.domain.DueDateReminderCandidate
import com.vumiq.cashbook.domain.DueDateReminderEvaluator
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ReminderDirection
import com.vumiq.cashbook.domain.ReminderStage
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Reliable WorkManager Payment Due-Date Reminder Worker (Phase 4).
 *
 * - Evaluates Customer Receivables and Supplier Payables strictly read-only.
 * - Automatically cancels notifications as soon as a party balance is settled (`0`) or disabled.
 * - Respects Android 13+ (`TIRAMISU`) `POST_NOTIFICATIONS` runtime permission.
 * - Uses deterministic daily deduplication keys so reboots, time-zone shifts, or manual re-checks
 *   never spam duplicate notifications.
 */
class DueDateReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settingsRepository = SettingsRepository(applicationContext)
        val reminderPrefs = settingsRepository.getDueReminderPreferences()

        if (!reminderPrefs.enabled || !hasNotificationPermission(applicationContext)) {
            cancelNotificationsById(applicationContext, reminderPrefs.activeNotificationIds)
            if (!reminderPrefs.enabled && reminderPrefs.activeNotificationIds.isNotEmpty()) {
                settingsRepository.updateDueReminderDeliveryState(
                    updatedSentKeys = reminderPrefs.sentDedupKeys,
                    activeNotificationIds = emptySet()
                )
            }
            return Result.success()
        }

        ensureNotificationChannel(applicationContext)

        val dao = CashbookDatabase.getInstance(applicationContext).cashbookDao()
        val userPrefs = UserPreferencesStore(applicationContext).preferencesFlow.first()
        val locale = userPrefs.locale
        val todayIso = LocalDate.now().toString()
        val prunedKeys = DueDateReminderEvaluator.pruneDedupKeysForToday(
            existingKeys = reminderPrefs.sentDedupKeys,
            todayIso = todayIso
        ).toMutableSet()

        val businesses = dao.getBusinessesSnapshot().map { it.toDomain() }
        val allStillActiveIds = HashSet<Int>()
        val candidatesToDeliver = mutableListOf<DueDateReminderCandidate>()

        for (biz in businesses) {
            val parties = dao.getPartiesSnapshot(biz.id).map { it.toDomain() }
            val transactions = dao.getTransactionsSnapshot(biz.id).map { it.toDomain() }
            val (candidates, stillActiveIds) = DueDateReminderEvaluator.evaluateBusinessParties(
                business = biz,
                parties = parties,
                transactions = transactions,
                todayIso = todayIso,
                leadDays = reminderPrefs.leadDays,
                alreadySentDedupKeys = prunedKeys
            )
            allStillActiveIds += stillActiveIds
            candidatesToDeliver += candidates
        }

        // Cancel any previously displayed notification whose party is now settled or undated
        val staleIds = reminderPrefs.activeNotificationIds - allStillActiveIds
        cancelNotificationsById(applicationContext, staleIds)

        // Post new candidate notifications (max 8 per run to avoid notification tray flooding)
        for (candidate in candidatesToDeliver.take(8)) {
            if (postCandidateNotification(applicationContext, candidate, locale)) {
                prunedKeys += candidate.dedupKey
            }
        }

        settingsRepository.updateDueReminderDeliveryState(
            updatedSentKeys = prunedKeys,
            activeNotificationIds = allStillActiveIds
        )

        return Result.success()
    }

    private fun postCandidateNotification(
        context: Context,
        candidate: DueDateReminderCandidate,
        locale: LocaleCode
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        val isFr = locale == LocaleCode.FR
        val formattedAmount = MoneyMath.formatMinorAmount(candidate.outstandingMinor, candidate.currency, locale)

        val title = when (candidate.stage) {
            ReminderStage.OVERDUE -> if (isFr) {
                "Échéance dépassée · ${candidate.partyName}"
            } else {
                "Payment Overdue · ${candidate.partyName}"
            }
            ReminderStage.DUE_TODAY -> if (isFr) {
                "Échéance aujourd'hui · ${candidate.partyName}"
            } else {
                "Payment Due Today · ${candidate.partyName}"
            }
            ReminderStage.DUE_SOON -> if (isFr) {
                "Échéance proche (${candidate.dueDateIso}) · ${candidate.partyName}"
            } else {
                "Upcoming Due Date (${candidate.dueDateIso}) · ${candidate.partyName}"
            }
        }

        val body = when (candidate.direction) {
            ReminderDirection.RECEIVABLE -> if (isFr) {
                "Créance client à encaisser : $formattedAmount (${candidate.businessName} · échéance ${candidate.dueDateIso})"
            } else {
                "Customer receivable to collect: $formattedAmount (${candidate.businessName} · due ${candidate.dueDateIso})"
            }
            ReminderDirection.PAYABLE -> if (isFr) {
                "Dette fournisseur à régler : $formattedAmount (${candidate.businessName} · échéance ${candidate.dueDateIso})"
            } else {
                "Supplier payable due: $formattedAmount (${candidate.businessName} · due ${candidate.dueDateIso})"
            }
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            candidate.notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(candidate.notificationId, notification)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val CHANNEL_ID = "vumiq_due_date_reminders"
        private const val PERIODIC_WORK_NAME = "vumiq_due_date_periodic_worker"
        private const val IMMEDIATE_WORK_NAME = "vumiq_due_date_immediate_eval"

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Customer & Supplier Payment Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Optional reminders for upcoming and overdue customer receivables and supplier payables."
                }
                manager.createNotificationChannel(channel)
            }
        }

        fun cancelNotificationsById(context: Context, ids: Set<Int>) {
            if (ids.isEmpty()) return
            val nm = NotificationManagerCompat.from(context)
            ids.forEach { id -> runCatching { nm.cancel(id) } }
        }

        /**
         * Computes initial delay in minutes from now until the next occurrence of `preferredHour`
         * in the device's current local time zone (`ZonedDateTime.now()`).
         */
        fun computeInitialDelayMinutes(preferredHour: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            val safeHour = preferredHour.coerceIn(0, 23)
            var nextRun = now.withHour(safeHour).withMinute(0).withSecond(0).withNano(0)
            if (!nextRun.isAfter(now)) {
                nextRun = nextRun.plusDays(1)
            }
            return Duration.between(now, nextRun).toMinutes().coerceAtLeast(1L)
        }

        fun syncSchedule(
            context: Context,
            enabled: Boolean,
            preferredHour: Int,
            runImmediateCheck: Boolean = true
        ) {
            val appCtx = context.applicationContext
            val wm = runCatching { WorkManager.getInstance(appCtx) }.getOrNull() ?: return
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_WORK_NAME)
                enqueueImmediateEvaluation(appCtx)
                return
            }

            ensureNotificationChannel(appCtx)
            val delayMinutes = computeInitialDelayMinutes(preferredHour)
            val periodicRequest = PeriodicWorkRequestBuilder<DueDateReminderWorker>(12L, TimeUnit.HOURS)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )

            if (runImmediateCheck) {
                enqueueImmediateEvaluation(appCtx)
            }
        }

        fun enqueueImmediateEvaluation(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                val request = OneTimeWorkRequestBuilder<DueDateReminderWorker>().build()
                WorkManager.getInstance(appCtx).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
