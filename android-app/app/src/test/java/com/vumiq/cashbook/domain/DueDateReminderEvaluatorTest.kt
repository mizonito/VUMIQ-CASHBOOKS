package com.vumiq.cashbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DueDateReminderEvaluatorTest {

    private val biz = Business(
        id = "biz_1",
        ownerId = "uid_1",
        name = "VUMIQ Wholesale",
        defaultCurrency = CurrencyCode.USD,
        createdAt = "2026-10-01T08:00:00Z",
        updatedAt = "2026-10-01T08:00:00Z"
    )

    private fun sampleParty(
        id: String,
        name: String,
        type: PartyType,
        dueDate: String,
        openingRec: Long = 0L,
        openingPay: Long = 0L,
        status: PartyStatus = PartyStatus.ACTIVE
    ) = Party(
        id = id,
        businessId = biz.id,
        ownerId = biz.ownerId,
        name = name,
        partyType = type,
        currency = CurrencyCode.USD,
        openingReceivableMinor = openingRec,
        openingPayableMinor = openingPay,
        dueDate = dueDate,
        status = status,
        createdAt = "2026-10-01T08:00:00Z",
        updatedAt = "2026-10-01T08:00:00Z"
    )

    @Test
    fun emitsRemindersForOverdueDueTodayAndDueSoonButSkipsSettledAndFuture() {
        val customerOverdue = sampleParty("p_overdue", "Amina Retail", PartyType.CUSTOMER, "2026-10-20", openingRec = 15_000L)
        val supplierToday = sampleParty("p_today", "Baraka Flour", PartyType.SUPPLIER, "2026-10-24", openingPay = 40_000L)
        val customerSoon = sampleParty("p_soon", "Dalmar Market", PartyType.CUSTOMER, "2026-10-25", openingRec = 8_000L)
        val settledCustomer = sampleParty("p_settled", "Settled Client", PartyType.CUSTOMER, "2026-10-20", openingRec = 10_000L)
        val farFuture = sampleParty("p_future", "Next Month Supplier", PartyType.SUPPLIER, "2026-11-15", openingPay = 25_000L)

        // Settled payment transaction reduces `p_settled` receivable to 0 without deleting anything
        val settleTx = CashbookTransaction(
            id = "tx_settle_1",
            businessId = biz.id,
            ownerId = biz.ownerId,
            idempotencyKey = "idem_settle_1",
            type = TransactionType.RECEIVABLE_PAYMENT,
            amountMinor = 10_000L,
            currency = CurrencyCode.USD,
            date = "2026-10-21",
            time = "11:00",
            description = "Full settlement",
            accountId = "acc_1",
            partyId = "p_settled",
            status = TransactionStatus.POSTED,
            createdByUid = biz.ownerId,
            createdAt = "2026-10-21T11:00:00Z",
            updatedAt = "2026-10-21T11:00:00Z"
        )

        val (candidates, activeIds) = DueDateReminderEvaluator.evaluateBusinessParties(
            business = biz,
            parties = listOf(customerOverdue, supplierToday, customerSoon, settledCustomer, farFuture),
            transactions = listOf(settleTx),
            todayIso = "2026-10-24",
            leadDays = 1,
            alreadySentDedupKeys = emptySet()
        )

        assertEquals(3, candidates.size)
        assertEquals(3, activeIds.size)
        assertEquals(ReminderStage.OVERDUE, candidates.first { it.partyId == "p_overdue" }.stage)
        assertEquals(ReminderStage.DUE_TODAY, candidates.first { it.partyId == "p_today" }.stage)
        assertEquals(ReminderStage.DUE_SOON, candidates.first { it.partyId == "p_soon" }.stage)

        // Second evaluation on the same day with recorded dedupKeys emits ZERO duplicate notifications
        val sentKeys = candidates.map { it.dedupKey }.toSet()
        val (secondRunCandidates, secondActiveIds) = DueDateReminderEvaluator.evaluateBusinessParties(
            business = biz,
            parties = listOf(customerOverdue, supplierToday, customerSoon, settledCustomer, farFuture),
            transactions = listOf(settleTx),
            todayIso = "2026-10-24",
            leadDays = 1,
            alreadySentDedupKeys = sentKeys
        )
        assertTrue(secondRunCandidates.isEmpty())
        assertEquals(3, secondActiveIds.size)
    }
}
