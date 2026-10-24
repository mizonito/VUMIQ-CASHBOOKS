package com.vumiq.cashbook.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure, read-only Customer Receivable & Supplier Payable Due-Date Reminder Evaluator (Phase 4).
 *
 * Non-negotiable guarantees:
 * 1. Read-only: Never modifies cash balances, party balances, or ledger transactions.
 * 2. Skips settled (`outstandingMinor <= 0`), archived (`status != ACTIVE`), or undated parties.
 * 3. Computes deterministic deduplication keys (`partyId|dueDate|stage|direction|amountMinor|todayIso`)
 *    so repeated WorkManager runs, time-zone shifts, or device reboots never emit duplicate alerts
 *    for the same item on the same calendar day.
 */
enum class ReminderStage {
    DUE_SOON,
    DUE_TODAY,
    OVERDUE
}

enum class ReminderDirection {
    RECEIVABLE,
    PAYABLE
}

data class DueDateReminderCandidate(
    val businessId: String,
    val businessName: String,
    val partyId: String,
    val partyName: String,
    val partyType: PartyType,
    val direction: ReminderDirection,
    val currency: CurrencyCode,
    val outstandingMinor: Long,
    val dueDateIso: String,
    val daysUntilDue: Long,
    val stage: ReminderStage,
    val dedupKey: String,
    val notificationId: Int
)

object DueDateReminderEvaluator {

    fun notificationIdForParty(partyId: String, direction: ReminderDirection): Int {
        val raw = ("vumiq_due_${partyId}_${direction.name}").hashCode()
        return (raw and 0x7FFFFFFF).coerceAtLeast(10_000)
    }

    /**
     * Evaluates inline due-date status for UI display on `PartiesScreen` without mutating any data.
     */
    fun classifyPartyDueStatus(
        dueDateIso: String,
        hasPositiveBalance: Boolean,
        todayIso: String,
        leadDays: Int = 3
    ): Pair<ReminderStage, Long>? {
        if (!hasPositiveBalance) return null
        val cleanDue = dueDateIso.trim()
        if (!BlueprintConstraints.DATE_REGEX.matches(cleanDue)) return null
        val today = runCatching { LocalDate.parse(todayIso.trim()) }.getOrNull() ?: return null
        val due = runCatching { LocalDate.parse(cleanDue) }.getOrNull() ?: return null
        val daysUntil = ChronoUnit.DAYS.between(today, due)
        val safeLead = leadDays.coerceIn(0, 30)
        return when {
            daysUntil < 0L -> ReminderStage.OVERDUE to daysUntil
            daysUntil == 0L -> ReminderStage.DUE_TODAY to 0L
            daysUntil <= safeLead.toLong() -> ReminderStage.DUE_SOON to daysUntil
            else -> null
        }
    }

    /**
     * Evaluates all active parties in a business book and returns candidate due-date notifications
     * that have NOT already been delivered for `todayIso`.
     */
    fun evaluateBusinessParties(
        business: Business,
        parties: List<Party>,
        transactions: List<CashbookTransaction>,
        todayIso: String,
        leadDays: Int,
        alreadySentDedupKeys: Set<String>
    ): Pair<List<DueDateReminderCandidate>, Set<Int>> {
        val today = runCatching { LocalDate.parse(todayIso.trim()) }.getOrNull()
            ?: return emptyList<DueDateReminderCandidate>() to emptySet()
        val safeLead = leadDays.coerceIn(0, 30).toLong()
        val balances = AccountingEngine.computePartyBalances(parties, transactions)

        val candidatesToNotify = mutableListOf<DueDateReminderCandidate>()
        val stillActiveNotificationIds = HashSet<Int>()

        for (party in parties) {
            if (party.status != PartyStatus.ACTIVE) continue
            val cleanDue = party.dueDate.trim()
            if (!BlueprintConstraints.DATE_REGEX.matches(cleanDue)) continue
            val due = runCatching { LocalDate.parse(cleanDue) }.getOrNull() ?: continue
            val daysUntil = ChronoUnit.DAYS.between(today, due)
            if (daysUntil > safeLead) continue

            val stage = when {
                daysUntil < 0L -> ReminderStage.OVERDUE
                daysUntil == 0L -> ReminderStage.DUE_TODAY
                else -> ReminderStage.DUE_SOON
            }

            val computed = balances[party.id]
            val recMinor = computed?.receivableByCurrency?.get(party.currency) ?: 0L
            val payMinor = computed?.payableByCurrency?.get(party.currency) ?: 0L

            if (recMinor > 0L) {
                val notifId = notificationIdForParty(party.id, ReminderDirection.RECEIVABLE)
                stillActiveNotificationIds += notifId
                val dedupKey = "${party.id}|$cleanDue|${stage.name}|RECEIVABLE|$recMinor|$todayIso"
                if (dedupKey !in alreadySentDedupKeys) {
                    candidatesToNotify += DueDateReminderCandidate(
                        businessId = business.id,
                        businessName = business.name,
                        partyId = party.id,
                        partyName = party.name,
                        partyType = party.partyType,
                        direction = ReminderDirection.RECEIVABLE,
                        currency = party.currency,
                        outstandingMinor = recMinor,
                        dueDateIso = cleanDue,
                        daysUntilDue = daysUntil,
                        stage = stage,
                        dedupKey = dedupKey,
                        notificationId = notifId
                    )
                }
            }

            if (payMinor > 0L) {
                val notifId = notificationIdForParty(party.id, ReminderDirection.PAYABLE)
                stillActiveNotificationIds += notifId
                val dedupKey = "${party.id}|$cleanDue|${stage.name}|PAYABLE|$payMinor|$todayIso"
                if (dedupKey !in alreadySentDedupKeys) {
                    candidatesToNotify += DueDateReminderCandidate(
                        businessId = business.id,
                        businessName = business.name,
                        partyId = party.id,
                        partyName = party.name,
                        partyType = party.partyType,
                        direction = ReminderDirection.PAYABLE,
                        currency = party.currency,
                        outstandingMinor = payMinor,
                        dueDateIso = cleanDue,
                        daysUntilDue = daysUntil,
                        stage = stage,
                        dedupKey = dedupKey,
                        notificationId = notifId
                    )
                }
            }
        }

        return candidatesToNotify to stillActiveNotificationIds
    }

    /**
     * Prunes stale deduplication keys from earlier days while retaining today's sent keys
     * so DataStore stays tiny over years of use.
     */
    fun pruneDedupKeysForToday(existingKeys: Set<String>, todayIso: String): Set<String> =
        existingKeys.filterTo(LinkedHashSet()) { it.endsWith("|$todayIso") }
}
