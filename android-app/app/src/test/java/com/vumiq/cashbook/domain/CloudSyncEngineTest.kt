package com.vumiq.cashbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncEngineTest {

    private fun sampleTx(
        id: String = "tx_1",
        idem: String = "idem_1",
        amountMinor: Long = 10_000L,
        description: String = "Counter sale",
        status: TransactionStatus = TransactionStatus.POSTED,
        voidReason: String = "",
        updatedAt: String = "2026-10-24T10:00:00Z",
        pendingSync: Boolean = false
    ) = CashbookTransaction(
        id = id,
        businessId = "biz_1",
        ownerId = "uid_1",
        idempotencyKey = idem,
        type = TransactionType.INCOME,
        amountMinor = amountMinor,
        currency = CurrencyCode.USD,
        date = "2026-10-24",
        time = "10:00",
        description = description,
        accountId = "acc_1",
        status = status,
        voidReason = voidReason,
        createdByUid = "uid_1",
        createdAt = "2026-10-24T09:00:00Z",
        updatedAt = updatedAt,
        pendingCloudSync = pendingSync
    )

    @Test
    fun olderCloudRecordNeverOverwritesNewerLocalEdit() {
        val localNewer = sampleTx(
            amountMinor = 25_000L,
            description = "Updated amount locally",
            updatedAt = "2026-10-24T12:00:00Z",
            pendingSync = true
        )
        val remoteOlder = sampleTx(
            amountMinor = 10_000L,
            description = "Stale cloud copy",
            updatedAt = "2026-10-24T10:00:00Z"
        )

        val plan = CloudSyncEngine.planTransactionsMerge(listOf(localNewer), listOf(remoteOlder))
        assertTrue(plan.toUpsertInLocal.isEmpty())
        assertEquals(1, plan.cloudPushOperations.size)
        assertTrue(plan.cloudPushOperations.single() is CloudSyncEngine.TransactionPushOperation.UpdatePostedDetails)
    }

    @Test
    fun newerCloudRecordPullsIntoLocalSafely() {
        val localOlder = sampleTx(
            amountMinor = 10_000L,
            updatedAt = "2026-10-24T10:00:00Z",
            pendingSync = false
        )
        val remoteNewer = sampleTx(
            amountMinor = 18_500L,
            description = "Edited from Web Cashbook",
            updatedAt = "2026-10-24T11:30:00.250Z"
        )

        val plan = CloudSyncEngine.planTransactionsMerge(listOf(localOlder), listOf(remoteNewer))
        assertEquals(1, plan.toUpsertInLocal.size)
        assertEquals(18_500L, plan.toUpsertInLocal.single().amountMinor)
        assertTrue(plan.cloudPushOperations.isEmpty())
    }

    @Test
    fun terminalVoidedStateAlwaysWinsAndNeverRevertsToPosted() {
        val localVoidedOffline = sampleTx(
            status = TransactionStatus.VOIDED,
            voidReason = "Duplicate entry",
            updatedAt = "2026-10-24T10:05:00Z",
            pendingSync = true
        )
        val remotePosted = sampleTx(
            status = TransactionStatus.POSTED,
            updatedAt = "2026-10-24T11:00:00Z"
        )

        val plan1 = CloudSyncEngine.planTransactionsMerge(listOf(localVoidedOffline), listOf(remotePosted))
        assertTrue(plan1.toUpsertInLocal.isEmpty())
        assertTrue(plan1.cloudPushOperations.single() is CloudSyncEngine.TransactionPushOperation.VoidExistingInCloud)

        // Reverse: remote is VOIDED while local had a newer POSTED edit -> remote terminal VOIDED still wins
        val localEdited = sampleTx(
            status = TransactionStatus.POSTED,
            amountMinor = 99_000L,
            updatedAt = "2026-10-24T14:00:00Z",
            pendingSync = true
        )
        val remoteVoided = sampleTx(
            status = TransactionStatus.VOIDED,
            voidReason = "Voided on web",
            updatedAt = "2026-10-24T12:00:00Z"
        )
        val plan2 = CloudSyncEngine.planTransactionsMerge(listOf(localEdited), listOf(remoteVoided))
        assertEquals(TransactionStatus.VOIDED, plan2.toUpsertInLocal.single().status)
        assertTrue(plan2.cloudPushOperations.isEmpty())
    }

    @Test
    fun offlineCreatedAndVoidedTransactionUsesTwoStepCreatePostedThenVoid() {
        val offlineVoided = sampleTx(
            id = "tx_offline_void",
            status = TransactionStatus.VOIDED,
            voidReason = "Entered wrong currency",
            pendingSync = true
        )
        val plan = CloudSyncEngine.planTransactionsMerge(listOf(offlineVoided), emptyList())
        assertTrue(plan.cloudPushOperations.single() is CloudSyncEngine.TransactionPushOperation.CreatePostedThenVoid)
    }

    @Test
    fun idempotencyKeyPreventsDuplicateInsertionAcrossDevices() {
        val local = sampleTx(id = "tx_android_1", idem = "shared_idem_abc", updatedAt = "2026-10-24T10:00:00Z")
        val remote = sampleTx(id = "tx_web_1", idem = "shared_idem_abc", updatedAt = "2026-10-24T10:00:00Z")

        val plan = CloudSyncEngine.planTransactionsMerge(listOf(local), listOf(remote))
        assertTrue(plan.cloudPushOperations.isEmpty())
        assertTrue(plan.toUpsertInLocal.isEmpty())
    }
}
