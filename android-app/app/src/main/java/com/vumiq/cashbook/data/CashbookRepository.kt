package com.vumiq.cashbook.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.vumiq.cashbook.data.attachments.ReceiptStorageManager
import com.vumiq.cashbook.data.attachments.StoredReceiptOutcome
import com.vumiq.cashbook.data.local.CashbookDao
import com.vumiq.cashbook.data.local.CashbookDatabase
import com.vumiq.cashbook.data.local.toDomain
import com.vumiq.cashbook.data.local.toEntity
import com.vumiq.cashbook.data.preferences.SettingsRepository
import com.vumiq.cashbook.data.preferences.UserPreferencesStore
import com.vumiq.cashbook.data.remote.CloudSyncResult
import com.vumiq.cashbook.data.remote.CloudSyncWorker
import com.vumiq.cashbook.data.remote.FirestoreSyncManager
import com.vumiq.cashbook.domain.Account
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.AccountingEngine
import com.vumiq.cashbook.domain.AuditEvent
import com.vumiq.cashbook.domain.BackupArchive
import com.vumiq.cashbook.domain.BackupInspectionPreview
import com.vumiq.cashbook.domain.BackupRestorePlanner
import com.vumiq.cashbook.domain.BlueprintConstraints
import com.vumiq.cashbook.domain.Business
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.Category
import com.vumiq.cashbook.domain.CategoryKind
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.CurrencyPeriodSummary
import com.vumiq.cashbook.domain.DateRange
import com.vumiq.cashbook.domain.DraftValidationOutcome
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.Party
import com.vumiq.cashbook.domain.PartyStatus
import com.vumiq.cashbook.domain.PartyType
import com.vumiq.cashbook.domain.RestoreExecutionSummary
import com.vumiq.cashbook.domain.RestoreMode
import com.vumiq.cashbook.domain.TransactionDraftInput
import com.vumiq.cashbook.domain.TransactionStatus
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.reminders.DueDateReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

class CashbookRepository(
    private val context: Context,
    private val dao: CashbookDao,
    val preferencesStore: UserPreferencesStore,
    val syncManager: FirestoreSyncManager,
    private val settingsRepository: SettingsRepository = SettingsRepository(context)
) {
    private val writeMutex = Mutex()

    fun observeBusinesses(): Flow<List<Business>> =
        dao.observeBusinesses().map { list -> list.map { it.toDomain() } }

    fun observeAccounts(businessId: String): Flow<List<Account>> =
        dao.observeAccounts(businessId).map { list -> list.map { it.toDomain() } }

    fun observeCategories(businessId: String): Flow<List<Category>> =
        dao.observeCategories(businessId).map { list -> list.map { it.toDomain() } }

    fun observeParties(businessId: String): Flow<List<Party>> =
        dao.observeParties(businessId).map { list -> list.map { it.toDomain() } }

    fun observeTransactions(businessId: String): Flow<List<CashbookTransaction>> =
        dao.observeTransactions(businessId).map { list -> list.map { it.toDomain() } }

    fun observeAuditEvents(businessId: String): Flow<List<AuditEvent>> =
        dao.observeAuditEvents(businessId).map { list -> list.map { it.toDomain() } }

    private fun newId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun nowIso(): String = Instant.now().toString()

    /**
     * Creates a brand-new business with 1 primary cash account and standard categories.
     * Strictly zero fabricated or mock financial transactions are inserted.
     */
    suspend fun createBusinessWithPrimaryAccount(
        businessName: String,
        defaultCurrency: CurrencyCode,
        initialAccountName: String,
        initialAccountType: AccountType,
        openingBalanceMinor: Long,
        address: String = "",
        phone: String = ""
    ): Business = writeMutex.withLock {
        val cleanBizName = businessName.trim()
        require(cleanBizName.isNotEmpty() && cleanBizName.length <= 120) {
            "Business name is required (1–120 characters)."
        }
        val cleanAccName = initialAccountName.trim().ifEmpty { "Main Cash Drawer" }.take(80)
        require(openingBalanceMinor >= 0L) {
            "Opening balance cannot be negative."
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val ownerUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val actorName = prefs.displayName
        val now = nowIso()
        val bizId = newId("biz")
        val accId = newId("acc")

        val business = Business(
            id = bizId,
            ownerId = ownerUid,
            name = cleanBizName,
            defaultCurrency = defaultCurrency,
            address = address.trim().take(240),
            phone = phone.trim().take(40),
            fiscalStartMonth = 1,
            createdAt = now,
            updatedAt = now
        )

        val primaryAccount = Account(
            id = accId,
            businessId = bizId,
            ownerId = ownerUid,
            name = cleanAccName,
            accountType = initialAccountType,
            currency = defaultCurrency,
            openingBalanceMinor = openingBalanceMinor,
            status = AccountStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )

        val defaultCategories = AccountingEngine.createDefaultCategories(bizId, ownerUid, now)
        val audits = listOf(
            AuditEvent(
                id = newId("evt"),
                businessId = bizId,
                ownerId = ownerUid,
                entityType = "business",
                entityId = bizId,
                action = "create",
                actorUid = ownerUid,
                actorName = actorName,
                summary = "Created business \"${business.name}\" (${defaultCurrency.code})",
                newSummary = "${business.name} (${defaultCurrency.code})",
                createdAt = now
            ),
            AuditEvent(
                id = newId("evt"),
                businessId = bizId,
                ownerId = ownerUid,
                entityType = "account",
                entityId = accId,
                action = "create",
                actorUid = ownerUid,
                actorName = actorName,
                summary = "Created account \"${primaryAccount.name}\"",
                newSummary = "${primaryAccount.name} (${MoneyMath.formatMinorAmount(openingBalanceMinor, defaultCurrency)})",
                createdAt = now
            )
        )

        dao.insertBusinessBundle(
            business = business.toEntity(),
            initialAccount = primaryAccount.toEntity(),
            defaultCategories = defaultCategories.map { it.toEntity() },
            auditEvents = audits.map { it.toEntity() }
        )

        preferencesStore.setActiveBusinessId(bizId)
        preferencesStore.setSelectedCurrencyCode(defaultCurrency.code)
        CloudSyncWorker.enqueueWhenConnected(context)
        business
    }

    suspend fun createAccount(
        businessId: String,
        name: String,
        accountType: AccountType,
        currency: CurrencyCode,
        openingBalanceMinor: Long
    ): Account = writeMutex.withLock {
        val biz = dao.getBusinessById(businessId)?.toDomain()
            ?: error("Active business not found.")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) {
            "Account name is required (1–80 characters)."
        }
        require(openingBalanceMinor >= 0L) {
            "Opening balance cannot be negative."
        }
        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()
        val acc = Account(
            id = newId("acc"),
            businessId = businessId,
            ownerId = biz.ownerId,
            name = cleanName,
            accountType = accountType,
            currency = currency,
            openingBalanceMinor = openingBalanceMinor,
            status = AccountStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertAccount(acc.toEntity())
        dao.upsertAuditEvent(
            AuditEvent(
                id = newId("evt"),
                businessId = businessId,
                ownerId = biz.ownerId,
                entityType = "account",
                entityId = acc.id,
                action = "create",
                actorUid = actorUid,
                actorName = prefs.displayName,
                summary = "Created account \"${acc.name}\"",
                newSummary = "${acc.name} (${MoneyMath.formatMinorAmount(openingBalanceMinor, currency)})",
                createdAt = now
            ).toEntity()
        )
        CloudSyncWorker.enqueueWhenConnected(context)
        acc
    }

    suspend fun createCategory(
        businessId: String,
        name: String,
        kind: CategoryKind,
        color: String
    ): Category = writeMutex.withLock {
        val biz = dao.getBusinessById(businessId)?.toDomain()
            ?: error("Active business not found.")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 64) {
            "Category name is required (1–64 characters)."
        }
        val safeColor = if (color.matches(Regex("^#[0-9a-fA-F]{6}$"))) color else "#126B45"
        val now = nowIso()
        val category = Category(
            id = newId("cat"),
            businessId = businessId,
            ownerId = biz.ownerId,
            name = cleanName,
            kind = kind,
            color = safeColor,
            icon = if (kind == CategoryKind.INCOME) "trending-up" else "tag",
            archived = false,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertCategory(category.toEntity())
        CloudSyncWorker.enqueueWhenConnected(context)
        category
    }

    suspend fun createParty(
        businessId: String,
        name: String,
        partyType: PartyType,
        currency: CurrencyCode,
        phone: String = "",
        address: String = "",
        notes: String = "",
        openingReceivableMinor: Long = 0L,
        openingPayableMinor: Long = 0L,
        dueDate: String = ""
    ): Party = writeMutex.withLock {
        val biz = dao.getBusinessById(businessId)?.toDomain()
            ?: error("Active business not found.")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 100) {
            "Customer or supplier name is required (1–100 characters)."
        }
        val cleanDueDate = dueDate.trim()
        require(cleanDueDate.isEmpty() || BlueprintConstraints.DATE_REGEX.matches(cleanDueDate)) {
            "Due date must use YYYY-MM-DD format (e.g. 2026-11-15) or be left blank."
        }
        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()
        val party = Party(
            id = newId("pty"),
            businessId = businessId,
            ownerId = biz.ownerId,
            name = cleanName,
            partyType = partyType,
            phone = phone.trim().take(40),
            address = address.trim().take(240),
            notes = notes.trim().take(500),
            currency = currency,
            openingReceivableMinor = openingReceivableMinor,
            openingPayableMinor = openingPayableMinor,
            dueDate = cleanDueDate,
            status = PartyStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertParty(party.toEntity())
        dao.upsertAuditEvent(
            AuditEvent(
                id = newId("evt"),
                businessId = businessId,
                ownerId = biz.ownerId,
                entityType = "party",
                entityId = party.id,
                action = "create",
                actorUid = actorUid,
                actorName = prefs.displayName,
                summary = "Added ${partyType.wireValue} \"${party.name}\"",
                newSummary = "${party.name} (${currency.code})",
                createdAt = now
            ).toEntity()
        )
        CloudSyncWorker.enqueueWhenConnected(context)
        DueDateReminderWorker.enqueueImmediateEvaluation(context)
        party
    }

    suspend fun updatePartyDueDate(
        businessId: String,
        partyId: String,
        dueDate: String
    ): Party = writeMutex.withLock {
        val cleanDue = dueDate.trim()
        require(cleanDue.isEmpty() || BlueprintConstraints.DATE_REGEX.matches(cleanDue)) {
            "Due date must use YYYY-MM-DD format (e.g. 2026-11-15) or be left blank."
        }
        val existing = dao.getPartyById(partyId)?.toDomain()
            ?: error("Customer or supplier not found.")
        require(existing.businessId == businessId) {
            "Party does not belong to the active business."
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val updated = existing.copy(
            dueDate = cleanDue,
            updatedAt = now
        )
        dao.upsertParty(updated.toEntity())
        dao.upsertAuditEvent(
            AuditEvent(
                id = newId("evt"),
                businessId = businessId,
                ownerId = existing.ownerId,
                entityType = "party",
                entityId = existing.id,
                action = "update",
                actorUid = actorUid,
                actorName = prefs.displayName,
                summary = if (cleanDue.isEmpty()) {
                    "Cleared payment due date for \"${existing.name}\""
                } else {
                    "Set payment due date for \"${existing.name}\" to $cleanDue"
                }.take(300),
                previousSummary = existing.dueDate.ifEmpty { "none" },
                newSummary = cleanDue.ifEmpty { "none" },
                createdAt = now
            ).toEntity()
        )
        CloudSyncWorker.enqueueWhenConnected(context)
        DueDateReminderWorker.enqueueImmediateEvaluation(context)
        updated
    }

    suspend fun recordTransaction(
        businessId: String,
        idempotencyKey: String,
        draft: TransactionDraftInput
    ): CashbookTransaction =
        recordTransactionWithReceiptOutcome(businessId, idempotencyKey, draft).first

    /**
     * Records a financial transaction with optional receipt image attachment.
     *
     * Strict Failure Isolation (Phase 5):
     * Financial validation runs first. Once the financial entry is valid, any error while copying
     * or compressing the optional receipt photo NEVER aborts or rolls back the financial transaction;
     * instead, the ledger record is saved intact and a non-fatal warning message is returned.
     */
    suspend fun recordTransactionWithReceiptOutcome(
        businessId: String,
        idempotencyKey: String,
        draft: TransactionDraftInput
    ): Pair<CashbookTransaction, String?> = writeMutex.withLock {
        val existing = dao.getTransactionByIdempotency(businessId, idempotencyKey)
        if (existing != null) {
            return@withLock existing.toDomain() to null
        }

        val biz = dao.getBusinessById(businessId)?.toDomain()
            ?: error("Active business not found.")
        val accounts = dao.getAccountsSnapshot(businessId).map { it.toDomain() }
        val parties = dao.getPartiesSnapshot(businessId).map { it.toDomain() }
        val categories = dao.getCategoriesSnapshot(businessId).map { it.toDomain() }

        when (val validation = AccountingEngine.validateTransactionDraft(draft, accounts, parties)) {
            is DraftValidationOutcome.Invalid -> throw IllegalArgumentException(validation.message)
            DraftValidationOutcome.Valid -> Unit
        }

        var storedReceiptPath = ""
        var storedAttachmentName = ""
        var storedAttachmentMime = ""
        var nonFatalReceiptWarning: String? = null

        val rawReceiptUri = draft.attachmentSourceUri.trim()
        if (rawReceiptUri.isNotEmpty()) {
            val outcome = runCatching {
                ReceiptStorageManager.importAndCompressReceipt(
                    context = context,
                    sourceUri = Uri.parse(rawReceiptUri),
                    preferredDisplayName = draft.attachmentDisplayName
                )
            }.getOrElse { err ->
                StoredReceiptOutcome.Failure(err.localizedMessage ?: "Receipt image could not be stored.")
            }
            when (outcome) {
                is StoredReceiptOutcome.Success -> {
                    storedReceiptPath = outcome.localPath
                    storedAttachmentName = outcome.attachmentName
                    storedAttachmentMime = outcome.attachmentMime
                }
                is StoredReceiptOutcome.Failure -> {
                    nonFatalReceiptWarning = outcome.message
                }
            }
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val cat = categories.firstOrNull { it.id == draft.categoryId }
        val party = parties.firstOrNull { it.id == draft.partyId }
        val now = nowIso()
        val txId = newId("tx")

        val tx = CashbookTransaction(
            id = txId,
            businessId = businessId,
            ownerId = biz.ownerId,
            idempotencyKey = idempotencyKey,
            type = draft.type,
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            convertedAmountMinor = draft.convertedAmountMinor,
            toCurrency = draft.toCurrency,
            exchangeRateScaled = draft.exchangeRateScaled,
            feeMinor = draft.feeMinor,
            date = draft.date,
            time = draft.time,
            description = draft.description.trim(),
            accountId = draft.accountId,
            toAccountId = draft.toAccountId,
            categoryId = draft.categoryId,
            categoryLabelSnapshot = cat?.name.orEmpty(),
            partyId = draft.partyId,
            partyNameSnapshot = party?.name.orEmpty(),
            referenceNumber = draft.referenceNumber.trim().take(64),
            notes = draft.notes.trim().take(600),
            attachmentName = storedAttachmentName,
            attachmentMime = storedAttachmentMime,
            receiptLocalPath = storedReceiptPath,
            dueDate = draft.dueDate.trim().take(10),
            status = TransactionStatus.POSTED,
            voidReason = "",
            createdByUid = actorUid,
            createdAt = now,
            updatedAt = now,
            pendingCloudSync = true
        )

        val audit = AuditEvent(
            id = "create_$txId",
            businessId = businessId,
            ownerId = biz.ownerId,
            entityType = "transaction",
            entityId = txId,
            action = "create",
            actorUid = actorUid,
            actorName = prefs.displayName,
            summary = "Recorded ${tx.type.wireValue}: ${tx.description}".take(300),
            previousSummary = "",
            newSummary = "${tx.type.wireValue} ${MoneyMath.formatMinorAmount(tx.amountMinor, tx.currency)} (${tx.date})".take(500),
            reason = "",
            createdAt = now,
            pendingCloudSync = true
        )

        try {
            dao.insertTransactionWithAudit(tx.toEntity(), audit.toEntity())
        } catch (dbErr: Throwable) {
            if (storedReceiptPath.isNotEmpty()) {
                val activePaths = runCatching { dao.getAllNonEmptyReceiptPaths().toSet() }.getOrDefault(emptySet())
                ReceiptStorageManager.deleteManagedReceiptIfOrphaned(
                    filesDir = context.filesDir,
                    candidateOldPath = storedReceiptPath,
                    remainingReferencedPaths = activePaths
                )
            }
            throw dbErr
        }
        CloudSyncWorker.enqueueWhenConnected(context)
        DueDateReminderWorker.enqueueImmediateEvaluation(context)
        tx to nonFatalReceiptWarning
    }

    suspend fun attachOrReplaceTransactionReceipt(
        businessId: String,
        transactionId: String,
        sourceUri: Uri,
        preferredDisplayName: String? = null
    ): CashbookTransaction = writeMutex.withLock {
        val prev = dao.getTransactionById(transactionId)?.toDomain()
            ?: error("Transaction not found.")
        require(prev.businessId == businessId) {
            "Transaction does not belong to the active business."
        }
        require(prev.status != TransactionStatus.VOIDED) {
            "Voided transactions are permanently locked and cannot be modified."
        }

        val outcome = ReceiptStorageManager.importAndCompressReceipt(
            context = context,
            sourceUri = sourceUri,
            preferredDisplayName = preferredDisplayName
        )
        val stored = when (outcome) {
            is StoredReceiptOutcome.Success -> outcome
            is StoredReceiptOutcome.Failure -> throw IllegalArgumentException(outcome.message)
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val updated = prev.copy(
            attachmentName = stored.attachmentName,
            attachmentMime = stored.attachmentMime,
            receiptLocalPath = stored.localPath,
            updatedAt = now,
            pendingCloudSync = true
        )

        val audit = AuditEvent(
            id = newId("evt"),
            businessId = businessId,
            ownerId = prev.ownerId,
            entityType = "transaction",
            entityId = prev.id,
            action = "update",
            actorUid = actorUid,
            actorName = prefs.displayName,
            summary = if (prev.receiptLocalPath.isNotEmpty() || prev.attachmentName.isNotEmpty()) {
                "Replaced receipt attachment on \"${prev.description}\""
            } else {
                "Attached receipt to \"${prev.description}\""
            }.take(300),
            previousSummary = prev.attachmentName.ifEmpty { "none" },
            newSummary = stored.attachmentName.take(500),
            reason = "Receipt attachment update",
            createdAt = now
        )

        dao.insertTransactionWithAudit(updated.toEntity(), audit.toEntity())

        if (prev.receiptLocalPath.isNotEmpty() && prev.receiptLocalPath != stored.localPath) {
            val activePaths = dao.getAllNonEmptyReceiptPaths().toSet()
            ReceiptStorageManager.deleteManagedReceiptIfOrphaned(
                filesDir = context.filesDir,
                candidateOldPath = prev.receiptLocalPath,
                remainingReferencedPaths = activePaths
            )
        }

        CloudSyncWorker.enqueueWhenConnected(context)
        updated
    }

    suspend fun removeTransactionReceipt(
        businessId: String,
        transactionId: String
    ): CashbookTransaction = writeMutex.withLock {
        val prev = dao.getTransactionById(transactionId)?.toDomain()
            ?: error("Transaction not found.")
        require(prev.businessId == businessId) {
            "Transaction does not belong to the active business."
        }
        require(prev.status != TransactionStatus.VOIDED) {
            "Voided transactions are permanently locked and cannot be modified."
        }
        if (prev.receiptLocalPath.isEmpty() && prev.attachmentName.isEmpty()) {
            return@withLock prev
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val updated = prev.copy(
            attachmentName = "",
            attachmentMime = "",
            receiptLocalPath = "",
            updatedAt = now,
            pendingCloudSync = true
        )

        val audit = AuditEvent(
            id = newId("evt"),
            businessId = businessId,
            ownerId = prev.ownerId,
            entityType = "transaction",
            entityId = prev.id,
            action = "update",
            actorUid = actorUid,
            actorName = prefs.displayName,
            summary = "Removed receipt attachment from \"${prev.description}\"".take(300),
            previousSummary = prev.attachmentName.ifEmpty { "receipt" },
            newSummary = "none",
            reason = "Removed receipt photo",
            createdAt = now
        )

        dao.insertTransactionWithAudit(updated.toEntity(), audit.toEntity())

        if (prev.receiptLocalPath.isNotEmpty()) {
            val activePaths = dao.getAllNonEmptyReceiptPaths().toSet()
            ReceiptStorageManager.deleteManagedReceiptIfOrphaned(
                filesDir = context.filesDir,
                candidateOldPath = prev.receiptLocalPath,
                remainingReferencedPaths = activePaths
            )
        }

        CloudSyncWorker.enqueueWhenConnected(context)
        updated
    }

    suspend fun editTransaction(
        businessId: String,
        transactionId: String,
        amountMinor: Long,
        description: String,
        date: String,
        notes: String,
        reason: String
    ): CashbookTransaction = writeMutex.withLock {
        val prev = dao.getTransactionById(transactionId)?.toDomain()
            ?: error("Transaction not found.")
        require(prev.status != TransactionStatus.VOIDED) {
            "Voided transactions are permanently locked and cannot be edited."
        }

        val accounts = dao.getAccountsSnapshot(businessId).map { it.toDomain() }
        val parties = dao.getPartiesSnapshot(businessId).map { it.toDomain() }
        val draft = TransactionDraftInput(
            type = prev.type,
            amountMinor = amountMinor,
            currency = prev.currency,
            description = description.trim(),
            date = date,
            time = prev.time,
            accountId = prev.accountId,
            toAccountId = prev.toAccountId,
            categoryId = prev.categoryId,
            partyId = prev.partyId,
            convertedAmountMinor = prev.convertedAmountMinor,
            toCurrency = prev.toCurrency,
            exchangeRateScaled = prev.exchangeRateScaled,
            feeMinor = prev.feeMinor,
            referenceNumber = prev.referenceNumber,
            notes = notes.trim(),
            dueDate = prev.dueDate
        )

        when (val validation = AccountingEngine.validateTransactionDraft(draft, accounts, parties)) {
            is DraftValidationOutcome.Invalid -> throw IllegalArgumentException(validation.message)
            DraftValidationOutcome.Valid -> Unit
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val updated = prev.copy(
            amountMinor = amountMinor,
            description = description.trim(),
            date = date,
            notes = notes.trim().take(600),
            updatedAt = now,
            pendingCloudSync = true
        )

        val audit = AuditEvent(
            id = newId("evt"),
            businessId = businessId,
            ownerId = prev.ownerId,
            entityType = "transaction",
            entityId = prev.id,
            action = "update",
            actorUid = actorUid,
            actorName = prefs.displayName,
            summary = "Edited transaction \"${updated.description}\"".take(300),
            previousSummary = "${prev.description} — ${MoneyMath.formatMinorAmount(prev.amountMinor, prev.currency)} (${prev.date})".take(500),
            newSummary = "${updated.description} — ${MoneyMath.formatMinorAmount(updated.amountMinor, updated.currency)} (${updated.date})".take(500),
            reason = reason.trim().ifEmpty { "Updated entry" }.take(240),
            createdAt = now
        )

        dao.insertTransactionWithAudit(updated.toEntity(), audit.toEntity())
        CloudSyncWorker.enqueueWhenConnected(context)
        DueDateReminderWorker.enqueueImmediateEvaluation(context)
        updated
    }

    suspend fun voidTransaction(
        businessId: String,
        transactionId: String,
        reason: String
    ): CashbookTransaction = writeMutex.withLock {
        val cleanReason = reason.trim()
        require(cleanReason.isNotEmpty() && cleanReason.length <= 240) {
            "Enter a brief reason before voiding a financial record."
        }
        val prev = dao.getTransactionById(transactionId)?.toDomain()
            ?: error("Transaction not found.")
        require(prev.status != TransactionStatus.VOIDED) {
            "Transaction is already voided."
        }

        val prefs = preferencesStore.preferencesFlow.first()
        val actorUid = syncManager.currentCloudUser()?.uid
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val voided = prev.copy(
            status = TransactionStatus.VOIDED,
            voidReason = cleanReason,
            updatedAt = now,
            pendingCloudSync = true
        )

        val audit = AuditEvent(
            id = newId("evt"),
            businessId = businessId,
            ownerId = prev.ownerId,
            entityType = "transaction",
            entityId = prev.id,
            action = "void",
            actorUid = actorUid,
            actorName = prefs.displayName,
            summary = "Voided transaction \"${prev.description}\"".take(300),
            previousSummary = "posted — ${MoneyMath.formatMinorAmount(prev.amountMinor, prev.currency)}",
            newSummary = "voided — $cleanReason".take(500),
            reason = cleanReason,
            createdAt = now
        )

        dao.insertTransactionWithAudit(voided.toEntity(), audit.toEntity())
        CloudSyncWorker.enqueueWhenConnected(context)
        DueDateReminderWorker.enqueueImmediateEvaluation(context)
        voided
    }

    /**
     * Executes full two-way Cloud Firestore <-> Room synchronization across all user-owned books,
     * updating telemetry in SettingsRepository and scheduling durable WorkManager retries if offline.
     */
    suspend fun syncWorkspaceWithCloud(): CloudSyncResult = writeMutex.withLock {
        val prefs = preferencesStore.preferencesFlow.first()
        val outcome = syncManager.syncWorkspaceTwoWay(
            dao = dao,
            prefs = prefs
        )
        if (outcome.ok) {
            settingsRepository.recordCloudSyncSuccess()
            val allBusinesses = dao.getBusinessesSnapshot()
            if (allBusinesses.isNotEmpty() && allBusinesses.none { it.id == prefs.activeBusinessId }) {
                val primary = allBusinesses.first()
                preferencesStore.setActiveBusinessId(primary.id)
                preferencesStore.setSelectedCurrencyCode(primary.defaultCurrency)
            }
        } else {
            settingsRepository.recordCloudSyncFailure(outcome.message)
            if (outcome.shouldRetryLater) {
                CloudSyncWorker.enqueueWhenConnected(context)
            }
        }
        outcome
    }

    suspend fun syncActiveBusinessToCloud(businessId: String): CloudSyncResult =
        syncWorkspaceWithCloud()

    private fun shareableExportFile(fileName: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, fileName)
    }

    private fun fileToShareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Generates a clean RFC-4180 UTF-8 CSV register and returns a FileProvider Uri ready for Android Share Sheet.
     */
    suspend fun exportCsvReport(
        business: Business,
        transactions: List<CashbookTransaction>,
        accounts: List<Account>,
        locale: LocaleCode
    ): Uri = withContext(Dispatchers.IO) {
        val accMap = accounts.associateBy { it.id }
        val file = shareableExportFile("vumiq_${business.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_ledger.csv")
        val sb = StringBuilder()
        sb.appendLine("Date,Time,Type,Description,Category,Party,Account,Currency,Amount,Status,Reference,Notes")

        fun esc(v: String): String = "\"${v.replace("\"", "\"\"")}\""

        for (tx in transactions) {
            val accLabel = if (tx.type == TransactionType.TRANSFER) {
                "${accMap[tx.accountId]?.name.orEmpty()} -> ${accMap[tx.toAccountId]?.name.orEmpty()}"
            } else {
                accMap[tx.accountId]?.name.orEmpty()
            }
            sb.appendLine(
                listOf(
                    esc(tx.date),
                    esc(tx.time),
                    esc(tx.type.wireValue),
                    esc(tx.description),
                    esc(tx.categoryLabelSnapshot),
                    esc(tx.partyNameSnapshot),
                    esc(accLabel),
                    esc(tx.currency.code),
                    esc(MoneyMath.minorToInputString(tx.amountMinor, tx.currency)),
                    esc(tx.status.wireValue),
                    esc(tx.referenceNumber),
                    esc(tx.notes)
                ).joinToString(",")
            )
        }

        file.writeText(sb.toString(), Charsets.UTF_8)
        fileToShareUri(file)
    }

    /**
     * Generates a native A4 PDF financial statement using Android's built-in PdfDocument engine.
     */
    suspend fun exportPdfStatement(
        business: Business,
        currency: CurrencyCode,
        summary: CurrencyPeriodSummary,
        transactions: List<CashbookTransaction>,
        dateRange: DateRange,
        locale: LocaleCode
    ): Uri = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNumber = 1

        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdf.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#126B45")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 18f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#171A1C")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 13f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#171A1C")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 9.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5C646A")
            textSize = 8.5f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DCE2E5")
            strokeWidth = 1f
        }

        fun drawFooter(c: Canvas, pg: Int) {
            c.drawLine(36f, 800f, 559f, 800f, linePaint)
            c.drawText("VUMIQ Cashbook · Owned by Mr. Amin M. Gutale", 36f, 816f, mutedPaint)
            c.drawText("Page $pg", 520f, 816f, mutedPaint)
        }

        // Header
        canvas.drawText("VUMIQ Cashbook", 36f, 46f, brandPaint)
        canvas.drawText(business.name, 36f, 66f, titlePaint)
        val periodLabel = if (dateRange.startDate.isEmpty() && dateRange.endDate.isEmpty()) {
            "All Recorded Dates"
        } else {
            "${dateRange.startDate.ifEmpty { "Start" }} to ${dateRange.endDate.ifEmpty { "Present" }}"
        }
        canvas.drawText("Currency: ${currency.code}  ·  Period: $periodLabel", 36f, 82f, mutedPaint)
        canvas.drawLine(36f, 94f, 559f, 94f, linePaint)

        // Summary KPI Row
        canvas.drawText(
            "Cash Balance: ${MoneyMath.formatMinorAmount(summary.currentCashBalanceMinor, currency, locale)}",
            36f,
            114f,
            titlePaint
        )
        canvas.drawText(
            "Period Income: ${MoneyMath.formatMinorAmount(summary.periodIncomeMinor, currency, locale)}   |   " +
                "Period Expenses: ${MoneyMath.formatMinorAmount(summary.periodExpenseMinor, currency, locale)}   |   " +
                "Net Income: ${MoneyMath.formatMinorAmount(summary.netIncomeMinor, currency, locale)}",
            36f,
            132f,
            bodyPaint
        )
        canvas.drawText(
            "Receivables: ${MoneyMath.formatMinorAmount(summary.totalReceivablesMinor, currency, locale)}   |   " +
                "Payables: ${MoneyMath.formatMinorAmount(summary.totalPayablesMinor, currency, locale)}",
            36f,
            148f,
            mutedPaint
        )
        canvas.drawLine(36f, 160f, 559f, 160f, linePaint)

        // Table Header
        var y = 178f
        canvas.drawText("DATE", 36f, y, mutedPaint)
        canvas.drawText("TYPE", 105f, y, mutedPaint)
        canvas.drawText("DESCRIPTION", 195f, y, mutedPaint)
        canvas.drawText("AMOUNT (${currency.code})", 445f, y, mutedPaint)
        y += 8f
        canvas.drawLine(36f, y, 559f, y, linePaint)
        y += 16f

        val currencyTxs = transactions.filter {
            it.status == TransactionStatus.POSTED &&
                (it.currency == currency || it.toCurrency == currency.code)
        }

        for (tx in currencyTxs) {
            if (y > 775f) {
                drawFooter(canvas, pageNumber)
                pdf.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdf.startPage(pageInfo)
                canvas = page.canvas
                y = 48f
            }
            canvas.drawText(tx.date, 36f, y, bodyPaint)
            canvas.drawText(tx.type.wireValue.take(14), 105f, y, bodyPaint)
            canvas.drawText(tx.description.take(40), 195f, y, bodyPaint)
            canvas.drawText(
                MoneyMath.formatMinorAmount(tx.amountMinor, tx.currency, locale),
                445f,
                y,
                bodyPaint
            )
            y += 18f
        }

        drawFooter(canvas, pageNumber)
        pdf.finishPage(page)

        val file = shareableExportFile("vumiq_${business.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${currency.code.lowercase()}_statement.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()

        fileToShareUri(file)
    }

    /**
     * Exports a cross-platform JSON Backup Archive compatible 1:1 with VUMIQ Cashbook Web.
     * Flushes SQLite WAL frames before reading so exported state is 100% consistent.
     */
    suspend fun exportJsonBackup(businessId: String): Uri = withContext(Dispatchers.IO) {
        CashbookDatabase.getInstance(context).checkpointWalSafely()
        val jsonText = buildBusinessBackupJsonString(businessId)
        val file = shareableExportFile("vumiq_backup_${businessId}.json")
        file.writeText(jsonText, Charsets.UTF_8)
        fileToShareUri(file)
    }

    private suspend fun buildBusinessBackupJsonString(businessId: String): String {
        val biz = dao.getBusinessById(businessId)?.toDomain()
            ?: error("Active business not found.")
        val accounts = dao.getAccountsSnapshot(businessId).map { it.toDomain() }
        val categories = dao.getCategoriesSnapshot(businessId).map { it.toDomain() }
        val parties = dao.getPartiesSnapshot(businessId).map { it.toDomain() }
        val transactions = dao.getTransactionsSnapshot(businessId).map { it.toDomain() }
        val audits = dao.getAuditEventsSnapshot(businessId).map { it.toDomain() }

        val totalPostedMinorSum = transactions
            .filter { it.status == TransactionStatus.POSTED }
            .sumOf { it.amountMinor }

        val checksum = AccountingEngine.computeBackupChecksum(
            businessId = biz.id,
            accountCount = accounts.size,
            transactionCount = transactions.size,
            totalPostedMinorSum = totalPostedMinorSum
        )

        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("application", "VUMIQ Cashbook")
            put("exportedAt", nowIso())
            put("checksum", checksum)
            put("business", JSONObject().apply {
                put("id", biz.id)
                put("ownerId", biz.ownerId)
                put("name", biz.name)
                put("defaultCurrency", biz.defaultCurrency.code)
                put("address", biz.address)
                put("phone", biz.phone)
                put("fiscalStartMonth", biz.fiscalStartMonth)
                put("createdAt", biz.createdAt)
                put("updatedAt", biz.updatedAt)
            })
            put("accounts", JSONArray().apply {
                accounts.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id)
                        put("businessId", a.businessId)
                        put("ownerId", a.ownerId)
                        put("name", a.name)
                        put("accountType", a.accountType.wireValue)
                        put("currency", a.currency.code)
                        put("openingBalanceMinor", a.openingBalanceMinor)
                        put("status", a.status.wireValue)
                        put("createdAt", a.createdAt)
                        put("updatedAt", a.updatedAt)
                    })
                }
            })
            put("categories", JSONArray().apply {
                categories.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("businessId", c.businessId)
                        put("ownerId", c.ownerId)
                        put("name", c.name)
                        put("kind", c.kind.wireValue)
                        put("color", c.color)
                        put("icon", c.icon)
                        put("archived", c.archived)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                    })
                }
            })
            put("parties", JSONArray().apply {
                parties.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("businessId", p.businessId)
                        put("ownerId", p.ownerId)
                        put("name", p.name)
                        put("partyType", p.partyType.wireValue)
                        put("phone", p.phone)
                        put("address", p.address)
                        put("notes", p.notes)
                        put("currency", p.currency.code)
                        put("openingReceivableMinor", p.openingReceivableMinor)
                        put("openingPayableMinor", p.openingPayableMinor)
                        put("dueDate", p.dueDate)
                        put("status", p.status.wireValue)
                        put("createdAt", p.createdAt)
                        put("updatedAt", p.updatedAt)
                    })
                }
            })
            put("transactions", JSONArray().apply {
                transactions.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("businessId", t.businessId)
                        put("ownerId", t.ownerId)
                        put("idempotencyKey", t.idempotencyKey)
                        put("type", t.type.wireValue)
                        put("amountMinor", t.amountMinor)
                        put("currency", t.currency.code)
                        put("convertedAmountMinor", t.convertedAmountMinor)
                        put("toCurrency", t.toCurrency)
                        put("exchangeRateScaled", t.exchangeRateScaled)
                        put("feeMinor", t.feeMinor)
                        put("date", t.date)
                        put("time", t.time)
                        put("description", t.description)
                        put("accountId", t.accountId)
                        put("toAccountId", t.toAccountId)
                        put("categoryId", t.categoryId)
                        put("categoryLabelSnapshot", t.categoryLabelSnapshot)
                        put("partyId", t.partyId)
                        put("partyNameSnapshot", t.partyNameSnapshot)
                        put("referenceNumber", t.referenceNumber)
                        put("notes", t.notes)
                        put("attachmentName", t.attachmentName)
                        put("attachmentMime", t.attachmentMime)
                        put("dueDate", t.dueDate)
                        put("status", t.status.wireValue)
                        put("voidReason", t.voidReason)
                        put("createdByUid", t.createdByUid)
                        put("createdAt", t.createdAt)
                        put("updatedAt", t.updatedAt)
                    })
                }
            })
            put("auditEvents", JSONArray().apply {
                audits.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("businessId", e.businessId)
                        put("ownerId", e.ownerId)
                        put("entityType", e.entityType)
                        put("entityId", e.entityId)
                        put("action", e.action)
                        put("actorUid", e.actorUid)
                        put("actorName", e.actorName)
                        put("summary", e.summary)
                        put("previousSummary", e.previousSummary)
                        put("newSummary", e.newSummary)
                        put("reason", e.reason)
                        put("createdAt", e.createdAt)
                    })
                }
            })
        }

        return root.toString(2)
    }

    /**
     * Automatically writes a local recovery snapshot into `files/recovery_snapshots/` before
     * any user-confirmed `REPLACE_BUSINESS` operation, keeping the 5 most recent snapshots.
     */
    private suspend fun writeLocalRecoverySnapshot(businessId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val existingBiz = dao.getBusinessById(businessId) ?: return@runCatching null
            val json = buildBusinessBackupJsonString(existingBiz.id)
            val recoveryDir = File(context.filesDir, "recovery_snapshots").apply { mkdirs() }
            val fileName = "pre_replace_${existingBiz.id}_${System.currentTimeMillis()}.json"
            File(recoveryDir, fileName).writeText(json, Charsets.UTF_8)
            val snapshots = recoveryDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            snapshots.drop(5).forEach { old -> runCatching { old.delete() } }
            fileName
        }.getOrNull()
    }

    /**
     * Strictly validates a candidate JSON backup archive BEFORE mutating any local Room rows
     * and computes an inspection preview (showing new vs duplicate counts for Safe Merge vs Replace).
     */
    suspend fun inspectBackupJson(rawJson: String): BackupInspectionPreview = withContext(Dispatchers.IO) {
        val archive = parseAndValidateBackupJson(rawJson)
        val bizId = archive.business.id
        val localBiz = dao.getBusinessById(bizId)?.toDomain()
        val localAccounts = if (localBiz != null) dao.getAccountsSnapshot(bizId).map { it.toDomain() } else emptyList()
        val localCategories = if (localBiz != null) dao.getCategoriesSnapshot(bizId).map { it.toDomain() } else emptyList()
        val localParties = if (localBiz != null) dao.getPartiesSnapshot(bizId).map { it.toDomain() } else emptyList()
        val localTransactions = if (localBiz != null) dao.getTransactionsSnapshot(bizId).map { it.toDomain() } else emptyList()
        val localAudits = if (localBiz != null) dao.getAuditEventsSnapshot(bizId).map { it.toDomain() } else emptyList()

        BackupRestorePlanner.buildInspectionPreview(
            sanitized = archive,
            localBusiness = localBiz,
            localAccounts = localAccounts,
            localCategories = localCategories,
            localParties = localParties,
            localTransactions = localTransactions,
            localAudits = localAudits
        )
    }

    /**
     * Executes a validated backup import in either:
     * - `RestoreMode.SAFE_MERGE` (default): Non-destructively merges records, skipping duplicates
     *   by ID and idempotencyKey and never deleting or regressing newer local entries.
     * - `RestoreMode.REPLACE_BUSINESS`: Writes an automatic recovery snapshot first, then atomically
     *   replaces the target business book after explicit user confirmation.
     */
    suspend fun executeRestoreBackup(
        rawJson: String,
        mode: RestoreMode
    ): RestoreExecutionSummary = writeMutex.withLock {
        val archive = withContext(Dispatchers.Default) { parseAndValidateBackupJson(rawJson) }
        CashbookDatabase.getInstance(context).checkpointWalSafely()

        val prefs = preferencesStore.preferencesFlow.first()
        val activeOwnerUid = syncManager.currentCloudUser()?.uid
            ?: dao.getBusinessById(archive.business.id)?.ownerId
            ?: preferencesStore.getOrCreateLocalOwnerUid()
        val now = nowIso()

        val bizId = archive.business.id
        val existingLocalBiz = dao.getBusinessById(bizId)?.toDomain()

        val summary = if (mode == RestoreMode.SAFE_MERGE || existingLocalBiz == null && mode == RestoreMode.SAFE_MERGE) {
            val localAccounts = dao.getAccountsSnapshot(bizId).map { it.toDomain() }
            val localCategories = dao.getCategoriesSnapshot(bizId).map { it.toDomain() }
            val localParties = dao.getPartiesSnapshot(bizId).map { it.toDomain() }
            val localTransactions = dao.getTransactionsSnapshot(bizId).map { it.toDomain() }
            val localAudits = dao.getAuditEventsSnapshot(bizId).map { it.toDomain() }

            val plan = BackupRestorePlanner.planSafeMerge(
                incoming = archive,
                localBusiness = existingLocalBiz,
                localAccounts = localAccounts,
                localCategories = localCategories,
                localParties = localParties,
                localTransactions = localTransactions,
                localAudits = localAudits
            )

            val bizForUpsert = plan.businessToUpsert?.copy(ownerId = activeOwnerUid)
            val restoreAudit = AuditEvent(
                id = newId("evt"),
                businessId = bizId,
                ownerId = activeOwnerUid,
                entityType = "business",
                entityId = bizId,
                action = "restore",
                actorUid = activeOwnerUid,
                actorName = prefs.displayName,
                summary = "Merged backup for \"${archive.business.name}\" (+${plan.newTransactionsInserted} new, ${plan.duplicateTransactionsSkipped} duplicates skipped)".take(300),
                newSummary = "Safe Merge (${archive.checksum})",
                createdAt = now
            )

            dao.mergeBusinessArchive(
                business = bizForUpsert?.toEntity(),
                accounts = plan.accountsToUpsert.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                categories = plan.categoriesToUpsert.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                parties = plan.partiesToUpsert.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                transactions = plan.transactionsToUpsert.map {
                    it.copy(ownerId = activeOwnerUid, createdByUid = activeOwnerUid).toEntity()
                },
                auditEvents = (plan.auditEventsToUpsert + restoreAudit).map {
                    it.copy(ownerId = activeOwnerUid, actorUid = activeOwnerUid).toEntity()
                }
            )

            val resultingBiz = dao.getBusinessById(bizId)?.toDomain() ?: archive.business.copy(ownerId = activeOwnerUid)
            RestoreExecutionSummary(
                business = resultingBiz,
                mode = RestoreMode.SAFE_MERGE,
                insertedOrReplacedTransactions = plan.newTransactionsInserted,
                updatedTransactions = plan.existingTransactionsUpdated,
                skippedDuplicateTransactions = plan.duplicateTransactionsSkipped
            )
        } else {
            val recoveryFileName = if (existingLocalBiz != null) {
                writeLocalRecoverySnapshot(bizId)
            } else {
                null
            }
            val normalizedBiz = archive.business.copy(ownerId = activeOwnerUid)
            val restoreAudit = AuditEvent(
                id = newId("evt"),
                businessId = bizId,
                ownerId = activeOwnerUid,
                entityType = "business",
                entityId = bizId,
                action = "restore",
                actorUid = activeOwnerUid,
                actorName = prefs.displayName,
                summary = "Full replace restore of \"${normalizedBiz.name}\" (${archive.transactions.size} entries)".take(300),
                newSummary = "Full Replace (${archive.checksum})",
                createdAt = now
            )

            dao.restoreBusinessArchive(
                business = normalizedBiz.toEntity(),
                accounts = archive.accounts.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                categories = archive.categories.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                parties = archive.parties.map { it.copy(ownerId = activeOwnerUid).toEntity() },
                transactions = archive.transactions.map {
                    it.copy(ownerId = activeOwnerUid, createdByUid = activeOwnerUid, pendingCloudSync = true).toEntity()
                },
                auditEvents = (archive.auditEvents + restoreAudit).map {
                    it.copy(ownerId = activeOwnerUid, actorUid = activeOwnerUid, pendingCloudSync = true).toEntity()
                }
            )
            val remainingPaths = dao.getAllNonEmptyReceiptPaths().toSet()
            ReceiptStorageManager.sweepUnreferencedReceipts(context.filesDir, remainingPaths)

            RestoreExecutionSummary(
                business = normalizedBiz,
                mode = RestoreMode.REPLACE_BUSINESS,
                insertedOrReplacedTransactions = archive.transactions.size,
                updatedTransactions = 0,
                skippedDuplicateTransactions = 0,
                recoverySnapshotFileName = recoveryFileName
            )
        }

        CashbookDatabase.getInstance(context).checkpointWalSafely()
        preferencesStore.setActiveBusinessId(summary.business.id)
        preferencesStore.setSelectedCurrencyCode(summary.business.defaultCurrency.code)
        CloudSyncWorker.enqueueWhenConnected(context)
        summary
    }

    /**
     * Safe default entry point: delegates to non-destructive `RestoreMode.SAFE_MERGE`
     * so callers can never accidentally overwrite existing financial records.
     */
    suspend fun restoreJsonBackup(rawJson: String): Business =
        executeRestoreBackup(rawJson, RestoreMode.SAFE_MERGE).business

    private fun parseAndValidateBackupJson(rawJson: String): BackupArchive {
        val trimmed = rawJson.trim()
        require(trimmed.isNotEmpty()) {
            "Selected backup file is empty."
        }
        require(trimmed.length <= 15 * 1024 * 1024) {
            "Backup file exceeds the 15 MB safety limit."
        }
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("Selected file is not a valid JSON backup archive.")
        }
        val appName = root.optString("application")
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(appName == "VUMIQ Cashbook" && schemaVersion == 1) {
            "Unsupported backup archive format or version."
        }

        val bizJson = root.getJSONObject("business")
        val biz = Business(
            id = bizJson.getString("id"),
            ownerId = bizJson.optString("ownerId", "local_restored"),
            name = bizJson.getString("name"),
            defaultCurrency = CurrencyCode.fromCodeOrDefault(bizJson.optString("defaultCurrency", "USD")),
            address = bizJson.optString("address", ""),
            phone = bizJson.optString("phone", ""),
            fiscalStartMonth = bizJson.optInt("fiscalStartMonth", 1),
            createdAt = bizJson.optString("createdAt", nowIso()),
            updatedAt = bizJson.optString("updatedAt", nowIso())
        )

        val accountsArr = root.getJSONArray("accounts")
        val accounts = (0 until accountsArr.length()).map { i ->
            val o = accountsArr.getJSONObject(i)
            Account(
                id = o.getString("id"),
                businessId = biz.id,
                ownerId = o.optString("ownerId", biz.ownerId),
                name = o.getString("name"),
                accountType = AccountType.fromWire(o.optString("accountType", "cash")),
                currency = CurrencyCode.fromCodeOrDefault(o.optString("currency", biz.defaultCurrency.code)),
                openingBalanceMinor = o.optLong("openingBalanceMinor", 0L),
                status = AccountStatus.fromWire(o.optString("status", "active")),
                createdAt = o.optString("createdAt", nowIso()),
                updatedAt = o.optString("updatedAt", nowIso())
            )
        }

        val categoriesArr = root.getJSONArray("categories")
        val categories = (0 until categoriesArr.length()).map { i ->
            val o = categoriesArr.getJSONObject(i)
            Category(
                id = o.getString("id"),
                businessId = biz.id,
                ownerId = o.optString("ownerId", biz.ownerId),
                name = o.getString("name"),
                kind = CategoryKind.fromWire(o.optString("kind", "expense")),
                color = o.optString("color", "#126B45"),
                icon = o.optString("icon", "tag"),
                archived = o.optBoolean("archived", false),
                createdAt = o.optString("createdAt", nowIso()),
                updatedAt = o.optString("updatedAt", nowIso())
            )
        }

        val partiesArr = root.getJSONArray("parties")
        val parties = (0 until partiesArr.length()).map { i ->
            val o = partiesArr.getJSONObject(i)
            Party(
                id = o.getString("id"),
                businessId = biz.id,
                ownerId = o.optString("ownerId", biz.ownerId),
                name = o.getString("name"),
                partyType = PartyType.fromWire(o.optString("partyType", "customer")),
                phone = o.optString("phone", ""),
                address = o.optString("address", ""),
                notes = o.optString("notes", ""),
                currency = CurrencyCode.fromCodeOrDefault(o.optString("currency", biz.defaultCurrency.code)),
                openingReceivableMinor = o.optLong("openingReceivableMinor", 0L),
                openingPayableMinor = o.optLong("openingPayableMinor", 0L),
                dueDate = o.optString("dueDate", ""),
                status = PartyStatus.fromWire(o.optString("status", "active")),
                createdAt = o.optString("createdAt", nowIso()),
                updatedAt = o.optString("updatedAt", nowIso())
            )
        }

        val txArr = root.getJSONArray("transactions")
        val transactions = (0 until txArr.length()).map { i ->
            val o = txArr.getJSONObject(i)
            CashbookTransaction(
                id = o.getString("id"),
                businessId = biz.id,
                ownerId = o.optString("ownerId", biz.ownerId),
                idempotencyKey = o.optString("idempotencyKey", "idem_${o.getString("id")}"),
                type = TransactionType.fromWire(o.getString("type")),
                amountMinor = o.getLong("amountMinor"),
                currency = CurrencyCode.fromCodeOrDefault(o.getString("currency")),
                convertedAmountMinor = o.optLong("convertedAmountMinor", 0L),
                toCurrency = o.optString("toCurrency", ""),
                exchangeRateScaled = o.optLong("exchangeRateScaled", 0L),
                feeMinor = o.optLong("feeMinor", 0L),
                date = o.getString("date"),
                time = o.optString("time", "12:00"),
                description = o.getString("description"),
                accountId = o.optString("accountId", ""),
                toAccountId = o.optString("toAccountId", ""),
                categoryId = o.optString("categoryId", ""),
                categoryLabelSnapshot = o.optString("categoryLabelSnapshot", ""),
                partyId = o.optString("partyId", ""),
                partyNameSnapshot = o.optString("partyNameSnapshot", ""),
                referenceNumber = o.optString("referenceNumber", ""),
                notes = o.optString("notes", ""),
                attachmentName = o.optString("attachmentName", ""),
                attachmentMime = o.optString("attachmentMime", ""),
                dueDate = o.optString("dueDate", ""),
                status = TransactionStatus.fromWire(o.optString("status", "posted")),
                voidReason = o.optString("voidReason", ""),
                createdByUid = o.optString("createdByUid", biz.ownerId),
                createdAt = o.optString("createdAt", nowIso()),
                updatedAt = o.optString("updatedAt", nowIso()),
                pendingCloudSync = true
            )
        }

        val auditArr = root.optJSONArray("auditEvents") ?: JSONArray()
        val audits = (0 until auditArr.length()).map { i ->
            val o = auditArr.getJSONObject(i)
            AuditEvent(
                id = o.getString("id"),
                businessId = biz.id,
                ownerId = o.optString("ownerId", biz.ownerId),
                entityType = o.optString("entityType", "business"),
                entityId = o.optString("entityId", biz.id),
                action = o.optString("action", "restore"),
                actorUid = o.optString("actorUid", biz.ownerId),
                actorName = o.optString("actorName", "Owner"),
                summary = o.optString("summary", "Restored record"),
                previousSummary = o.optString("previousSummary", ""),
                newSummary = o.optString("newSummary", ""),
                reason = o.optString("reason", ""),
                createdAt = o.optString("createdAt", nowIso())
            )
        }

        val totalPostedMinorSum = transactions
            .filter { it.status == TransactionStatus.POSTED }
            .sumOf { it.amountMinor }

        val expectedChecksum = AccountingEngine.computeBackupChecksum(
            businessId = biz.id,
            accountCount = accounts.size,
            transactionCount = transactions.size,
            totalPostedMinorSum = totalPostedMinorSum
        )
        val providedChecksum = root.optString("checksum", "")
        require(providedChecksum.isEmpty() || providedChecksum == expectedChecksum) {
            "Backup checksum mismatch — archive may be corrupted or modified."
        }

        val rawArchive = BackupArchive(
            exportedAt = root.optString("exportedAt", nowIso()),
            checksum = expectedChecksum,
            business = biz,
            accounts = accounts,
            categories = categories,
            parties = parties,
            transactions = transactions,
            auditEvents = audits
        )
        return BackupRestorePlanner.validateAndSanitizeArchive(rawArchive)
    }
}
