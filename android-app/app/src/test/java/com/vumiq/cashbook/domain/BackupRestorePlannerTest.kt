package com.vumiq.cashbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestorePlannerTest {

    private fun disposableBusiness(id: String = "biz_disp_1") = Business(
        id = id,
        ownerId = "uid_local",
        name = "Gutale Trading Disposable Book",
        defaultCurrency = CurrencyCode.USD,
        createdAt = "2026-10-01T08:00:00Z",
        updatedAt = "2026-10-20T08:00:00Z"
    )

    private fun disposableAccount() = Account(
        id = "acc_cash_1",
        businessId = "biz_disp_1",
        ownerId = "uid_local",
        name = "Main Till",
        accountType = AccountType.CASH,
        currency = CurrencyCode.USD,
        openingBalanceMinor = 50_000L,
        createdAt = "2026-10-01T08:00:00Z",
        updatedAt = "2026-10-01T08:00:00Z"
    )

    private fun disposableTx(
        id: String,
        idem: String,
        amountMinor: Long,
        description: String = "Sale entry",
        status: TransactionStatus = TransactionStatus.POSTED,
        updatedAt: String = "2026-10-20T10:00:00Z"
    ) = CashbookTransaction(
        id = id,
        businessId = "biz_disp_1",
        ownerId = "uid_local",
        idempotencyKey = idem,
        type = TransactionType.INCOME,
        amountMinor = amountMinor,
        currency = CurrencyCode.USD,
        date = "2026-10-20",
        time = "10:00",
        description = description,
        accountId = "acc_cash_1",
        status = status,
        voidReason = if (status == TransactionStatus.VOIDED) "Cancelled" else "",
        createdByUid = "uid_local",
        createdAt = "2026-10-20T09:00:00Z",
        updatedAt = updatedAt
    )

    @Test
    fun safeMergeSkipsDuplicatesByIdAndIdempotencyAndPreservesExistingLocalRecords() {
        val localBiz = disposableBusiness()
        val localAcc = disposableAccount()
        val localTx1 = disposableTx("tx_1", "idem_1", 10_000L, updatedAt = "2026-10-22T12:00:00Z")
        val localTxOnly = disposableTx("tx_local_only", "idem_local_only", 35_000L)

        // Incoming archive has:
        // - tx_1 with an OLDER timestamp (must be skipped, never overwrite newer localTx1)
        // - tx_dup_idem with a different ID but SAME idempotencyKey "idem_local_only" (must be skipped)
        // - tx_new_3 genuinely new record (must be inserted)
        val incomingArchive = BackupRestorePlanner.validateAndSanitizeArchive(
            BackupArchive(
                exportedAt = "2026-10-23T08:00:00Z",
                checksum = "deadbeef",
                business = localBiz,
                accounts = listOf(localAcc),
                categories = emptyList(),
                parties = emptyList(),
                transactions = listOf(
                    disposableTx("tx_1", "idem_1", 5_000L, updatedAt = "2026-10-19T08:00:00Z"),
                    disposableTx("tx_dup_idem", "idem_local_only", 35_000L, updatedAt = "2026-10-19T08:00:00Z"),
                    disposableTx("tx_new_3", "idem_new_3", 12_500L, description = "New imported invoice")
                ),
                auditEvents = emptyList()
            )
        )

        val plan = BackupRestorePlanner.planSafeMerge(
            incoming = incomingArchive,
            localBusiness = localBiz,
            localAccounts = listOf(localAcc),
            localCategories = emptyList(),
            localParties = emptyList(),
            localTransactions = listOf(localTx1, localTxOnly),
            localAudits = emptyList()
        )

        assertEquals(1, plan.newTransactionsInserted)
        assertEquals(0, plan.existingTransactionsUpdated)
        assertEquals(2, plan.duplicateTransactionsSkipped)
        assertEquals("tx_new_3", plan.transactionsToUpsert.single().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateRejectsZeroOrNegativeTransactionAmounts() {
        BackupRestorePlanner.validateAndSanitizeArchive(
            BackupArchive(
                exportedAt = "2026-10-23T08:00:00Z",
                checksum = "00000000",
                business = disposableBusiness(),
                accounts = listOf(disposableAccount()),
                categories = emptyList(),
                parties = emptyList(),
                transactions = listOf(disposableTx("tx_bad", "idem_bad", 0L)),
                auditEvents = emptyList()
            )
        )
    }

    @Test
    fun intraArchiveDuplicateTransactionsAreSanitizedBeforeImport() {
        val archive = BackupRestorePlanner.validateAndSanitizeArchive(
            BackupArchive(
                exportedAt = "2026-10-23T08:00:00Z",
                checksum = "12345678",
                business = disposableBusiness(),
                accounts = listOf(disposableAccount()),
                categories = emptyList(),
                parties = emptyList(),
                transactions = listOf(
                    disposableTx("tx_a", "idem_same", 9_000L),
                    disposableTx("tx_b", "idem_same", 9_000L)
                ),
                auditEvents = emptyList()
            )
        )
        assertEquals(1, archive.transactions.size)
    }
}
