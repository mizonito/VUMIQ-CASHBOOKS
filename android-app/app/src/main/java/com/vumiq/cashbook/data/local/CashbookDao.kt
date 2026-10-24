package com.vumiq.cashbook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CashbookDao {

    @Query("SELECT * FROM businesses ORDER BY createdAt ASC")
    fun observeBusinesses(): Flow<List<BusinessEntity>>

    @Query("SELECT * FROM businesses WHERE id = :businessId LIMIT 1")
    suspend fun getBusinessById(businessId: String): BusinessEntity?

    @Query("SELECT * FROM accounts WHERE businessId = :businessId ORDER BY createdAt ASC")
    fun observeAccounts(businessId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE businessId = :businessId ORDER BY createdAt ASC")
    suspend fun getAccountsSnapshot(businessId: String): List<AccountEntity>

    @Query("SELECT * FROM categories WHERE businessId = :businessId ORDER BY kind ASC, name ASC")
    fun observeCategories(businessId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE businessId = :businessId ORDER BY kind ASC, name ASC")
    suspend fun getCategoriesSnapshot(businessId: String): List<CategoryEntity>

    @Query("SELECT * FROM parties WHERE businessId = :businessId ORDER BY name ASC")
    fun observeParties(businessId: String): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE businessId = :businessId ORDER BY name ASC")
    suspend fun getPartiesSnapshot(businessId: String): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE id = :partyId LIMIT 1")
    suspend fun getPartyById(partyId: String): PartyEntity?

    @Query("SELECT * FROM transactions WHERE businessId = :businessId ORDER BY date DESC, time DESC, createdAt DESC")
    fun observeTransactions(businessId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE businessId = :businessId ORDER BY date DESC, time DESC, createdAt DESC")
    suspend fun getTransactionsSnapshot(businessId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :txId LIMIT 1")
    suspend fun getTransactionById(txId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE businessId = :businessId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getTransactionByIdempotency(businessId: String, idempotencyKey: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE businessId = :businessId AND pendingCloudSync = 1")
    suspend fun getUnsyncedTransactions(businessId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET pendingCloudSync = 0 WHERE id = :txId")
    suspend fun markTransactionSynced(txId: String)

    @Query("SELECT receiptLocalPath FROM transactions WHERE receiptLocalPath != ''")
    suspend fun getAllNonEmptyReceiptPaths(): List<String>

    @Query("SELECT * FROM audit_events WHERE businessId = :businessId ORDER BY createdAt DESC LIMIT 250")
    fun observeAuditEvents(businessId: String): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE businessId = :businessId ORDER BY createdAt ASC")
    suspend fun getAuditEventsSnapshot(businessId: String): List<AuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBusiness(business: BusinessEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParty(party: PartyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParties(parties: List<PartyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(tx: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(txs: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAuditEvent(audit: AuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAuditEvents(audits: List<AuditEventEntity>)

    @Query("DELETE FROM accounts WHERE businessId = :businessId")
    suspend fun deleteAccountsForBusiness(businessId: String)

    @Query("DELETE FROM categories WHERE businessId = :businessId")
    suspend fun deleteCategoriesForBusiness(businessId: String)

    @Query("DELETE FROM parties WHERE businessId = :businessId")
    suspend fun deletePartiesForBusiness(businessId: String)

    @Query("DELETE FROM transactions WHERE businessId = :businessId")
    suspend fun deleteTransactionsForBusiness(businessId: String)

    @Query("DELETE FROM audit_events WHERE businessId = :businessId")
    suspend fun deleteAuditEventsForBusiness(businessId: String)

    @Query("SELECT * FROM businesses ORDER BY createdAt ASC")
    suspend fun getBusinessesSnapshot(): List<BusinessEntity>

    @Query("SELECT * FROM audit_events WHERE businessId = :businessId AND pendingCloudSync = 1 ORDER BY createdAt ASC")
    suspend fun getUnsyncedAuditEvents(businessId: String): List<AuditEventEntity>

    @Query("UPDATE audit_events SET pendingCloudSync = 0 WHERE id = :auditId")
    suspend fun markAuditEventSynced(auditId: String)

    @Query("UPDATE businesses SET ownerId = :cloudOwnerUid WHERE id = :businessId")
    suspend fun updateBusinessOwnerId(businessId: String, cloudOwnerUid: String)

    @Query("UPDATE accounts SET ownerId = :cloudOwnerUid WHERE businessId = :businessId")
    suspend fun updateAccountsOwnerId(businessId: String, cloudOwnerUid: String)

    @Query("UPDATE categories SET ownerId = :cloudOwnerUid WHERE businessId = :businessId")
    suspend fun updateCategoriesOwnerId(businessId: String, cloudOwnerUid: String)

    @Query("UPDATE parties SET ownerId = :cloudOwnerUid WHERE businessId = :businessId")
    suspend fun updatePartiesOwnerId(businessId: String, cloudOwnerUid: String)

    @Query("UPDATE transactions SET ownerId = :cloudOwnerUid, createdByUid = :cloudOwnerUid WHERE businessId = :businessId")
    suspend fun updateTransactionsOwnerId(businessId: String, cloudOwnerUid: String)

    @Query("UPDATE audit_events SET ownerId = :cloudOwnerUid, actorUid = :cloudOwnerUid WHERE businessId = :businessId")
    suspend fun updateAuditEventsOwnerId(businessId: String, cloudOwnerUid: String)

    @Transaction
    suspend fun adoptCloudOwnerForLocalBusiness(
        businessId: String,
        cloudOwnerUid: String
    ) {
        updateBusinessOwnerId(businessId, cloudOwnerUid)
        updateAccountsOwnerId(businessId, cloudOwnerUid)
        updateCategoriesOwnerId(businessId, cloudOwnerUid)
        updatePartiesOwnerId(businessId, cloudOwnerUid)
        updateTransactionsOwnerId(businessId, cloudOwnerUid)
        updateAuditEventsOwnerId(businessId, cloudOwnerUid)
    }

    @Transaction
    suspend fun applyCloudPullBundle(
        business: BusinessEntity?,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        parties: List<PartyEntity>,
        transactions: List<TransactionEntity>,
        auditEvents: List<AuditEventEntity>,
        syncedTxIds: List<String>
    ) {
        if (business != null) upsertBusiness(business)
        if (accounts.isNotEmpty()) upsertAccounts(accounts)
        if (categories.isNotEmpty()) upsertCategories(categories)
        if (parties.isNotEmpty()) upsertParties(parties)
        if (transactions.isNotEmpty()) upsertTransactions(transactions)
        if (auditEvents.isNotEmpty()) upsertAuditEvents(auditEvents)
        for (txId in syncedTxIds) {
            markTransactionSynced(txId)
        }
    }

    @Transaction
    suspend fun insertBusinessBundle(
        business: BusinessEntity,
        initialAccount: AccountEntity,
        defaultCategories: List<CategoryEntity>,
        auditEvents: List<AuditEventEntity>
    ) {
        upsertBusiness(business)
        upsertAccount(initialAccount)
        upsertCategories(defaultCategories)
        upsertAuditEvents(auditEvents)
    }

    @Transaction
    suspend fun insertTransactionWithAudit(
        tx: TransactionEntity,
        audit: AuditEventEntity
    ) {
        upsertTransaction(tx)
        upsertAuditEvent(audit)
    }

    @Transaction
    suspend fun mergeBusinessArchive(
        business: BusinessEntity?,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        parties: List<PartyEntity>,
        transactions: List<TransactionEntity>,
        auditEvents: List<AuditEventEntity>
    ) {
        if (business != null) upsertBusiness(business)
        if (accounts.isNotEmpty()) upsertAccounts(accounts)
        if (categories.isNotEmpty()) upsertCategories(categories)
        if (parties.isNotEmpty()) upsertParties(parties)
        if (transactions.isNotEmpty()) upsertTransactions(transactions)
        if (auditEvents.isNotEmpty()) upsertAuditEvents(auditEvents)
    }

    @Transaction
    suspend fun restoreBusinessArchive(
        business: BusinessEntity,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        parties: List<PartyEntity>,
        transactions: List<TransactionEntity>,
        auditEvents: List<AuditEventEntity>
    ) {
        deleteTransactionsForBusiness(business.id)
        deletePartiesForBusiness(business.id)
        deleteCategoriesForBusiness(business.id)
        deleteAccountsForBusiness(business.id)
        deleteAuditEventsForBusiness(business.id)

        upsertBusiness(business)
        upsertAccounts(accounts)
        upsertCategories(categories)
        upsertParties(parties)
        upsertTransactions(transactions)
        upsertAuditEvents(auditEvents)
    }
}
