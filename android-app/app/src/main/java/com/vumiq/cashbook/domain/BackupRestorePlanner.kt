package com.vumiq.cashbook.domain

/**
 * Pure, deterministic Backup Validation, Deduplication, and Safe Merge / Replace Planner
 * for VUMIQ Cashbook (Phase 3 — Backup, Restore, and Data Recovery).
 *
 * Guarantees:
 * 1. Validates archive schema, IDs, dates (`YYYY-MM-DD`), positive integer amounts, and non-negative
 *    opening balances before touching Room SQLite.
 * 2. Deduplicates intra-archive duplicates by primary `id` and `(businessId, idempotencyKey)` so
 *    edited or merged external JSON files cannot insert duplicate transactions.
 * 3. `RestoreMode.SAFE_MERGE` never deletes any existing local financial record, skips duplicates
 *    by `id` and `(businessId, idempotencyKey)`, preserves terminal `VOIDED` status, and prevents
 *    older backup entries from overwriting newer local edits.
 * 4. `RestoreMode.REPLACE_BUSINESS` is only executed when explicitly selected by the user after
 *    viewing the local record count warning (and after writing a local recovery snapshot).
 */
enum class RestoreMode {
    SAFE_MERGE,
    REPLACE_BUSINESS
}

data class BackupInspectionPreview(
    val businessId: String,
    val businessName: String,
    val currency: CurrencyCode,
    val exportedAt: String,
    val checksum: String,
    val incomingAccountCount: Int,
    val incomingCategoryCount: Int,
    val incomingPartyCount: Int,
    val incomingTransactionCount: Int,
    val targetBusinessExistsLocally: Boolean,
    val existingLocalTransactionCount: Int,
    val newTransactionsInMerge: Int,
    val updatedTransactionsInMerge: Int,
    val duplicateTransactionsSkippedInMerge: Int
)

data class SafeMergeExecutionPlan(
    val businessToUpsert: Business?,
    val accountsToUpsert: List<Account>,
    val categoriesToUpsert: List<Category>,
    val partiesToUpsert: List<Party>,
    val transactionsToUpsert: List<CashbookTransaction>,
    val auditEventsToUpsert: List<AuditEvent>,
    val newTransactionsInserted: Int,
    val existingTransactionsUpdated: Int,
    val duplicateTransactionsSkipped: Int
)

data class RestoreExecutionSummary(
    val business: Business,
    val mode: RestoreMode,
    val insertedOrReplacedTransactions: Int,
    val updatedTransactions: Int,
    val skippedDuplicateTransactions: Int,
    val recoverySnapshotFileName: String? = null
)

object BackupRestorePlanner {

    fun validateAndSanitizeArchive(raw: BackupArchive): BackupArchive {
        require(raw.application == "VUMIQ Cashbook" && raw.schemaVersion == 1) {
            "Unsupported backup archive header (${raw.application} v${raw.schemaVersion})."
        }

        val bizId = raw.business.id.trim()
        val bizName = raw.business.name.trim()
        require(bizId.isNotEmpty() && bizId.length <= BlueprintConstraints.ID_MAX_LENGTH && BlueprintConstraints.ID_REGEX.matches(bizId)) {
            "Backup contains an invalid business ID."
        }
        require(bizName.isNotEmpty() && bizName.length <= BlueprintConstraints.BUSINESS_NAME_MAX) {
            "Backup contains an empty or oversized business name."
        }
        require(raw.accounts.isNotEmpty()) {
            "Backup must contain at least one cash, bank, or mobile money account."
        }

        val sanitizedAccounts = LinkedHashMap<String, Account>()
        for (acc in raw.accounts) {
            val id = acc.id.trim()
            require(id.isNotEmpty() && id.length <= BlueprintConstraints.ID_MAX_LENGTH && BlueprintConstraints.ID_REGEX.matches(id)) {
                "Backup contains an account with an invalid ID."
            }
            require(acc.name.trim().isNotEmpty()) {
                "Account \"$id\" has an empty name."
            }
            require(acc.openingBalanceMinor in 0L..BlueprintConstraints.MAX_MINOR_UNITS) {
                "Account \"${acc.name}\" has an invalid opening balance."
            }
            if (!sanitizedAccounts.containsKey(id)) {
                sanitizedAccounts[id] = acc.copy(id = id, businessId = bizId, name = acc.name.trim())
            }
        }

        val sanitizedCategories = LinkedHashMap<String, Category>()
        for (cat in raw.categories) {
            val id = cat.id.trim()
            if (id.isEmpty() || !BlueprintConstraints.ID_REGEX.matches(id)) continue
            val cleanName = cat.name.trim()
            if (cleanName.isEmpty()) continue
            if (!sanitizedCategories.containsKey(id)) {
                val safeColor = if (BlueprintConstraints.HEX_COLOR_REGEX.matches(cat.color)) cat.color else "#126B45"
                sanitizedCategories[id] = cat.copy(id = id, businessId = bizId, name = cleanName, color = safeColor)
            }
        }

        val sanitizedParties = LinkedHashMap<String, Party>()
        for (pty in raw.parties) {
            val id = pty.id.trim()
            require(id.isNotEmpty() && id.length <= BlueprintConstraints.ID_MAX_LENGTH && BlueprintConstraints.ID_REGEX.matches(id)) {
                "Backup contains a customer/supplier record with an invalid ID."
            }
            require(pty.name.trim().isNotEmpty()) {
                "Party \"$id\" has an empty name."
            }
            require(
                pty.openingReceivableMinor in 0L..BlueprintConstraints.MAX_MINOR_UNITS &&
                    pty.openingPayableMinor in 0L..BlueprintConstraints.MAX_MINOR_UNITS
            ) {
                "Party \"${pty.name}\" has a negative or out-of-range opening balance."
            }
            val cleanDue = pty.dueDate.trim()
            require(cleanDue.isEmpty() || BlueprintConstraints.DATE_REGEX.matches(cleanDue)) {
                "Party \"${pty.name}\" has an invalid due date ($cleanDue). Expected YYYY-MM-DD."
            }
            if (!sanitizedParties.containsKey(id)) {
                sanitizedParties[id] = pty.copy(
                    id = id,
                    businessId = bizId,
                    name = pty.name.trim(),
                    dueDate = cleanDue
                )
            }
        }

        // Deduplicate intra-archive transactions by BOTH `id` AND `(businessId, idempotencyKey)`
        val seenTxIds = HashSet<String>()
        val seenIdemKeys = HashSet<String>()
        val sanitizedTransactions = mutableListOf<CashbookTransaction>()

        for (tx in raw.transactions) {
            val id = tx.id.trim()
            require(id.isNotEmpty() && id.length <= BlueprintConstraints.ID_MAX_LENGTH && BlueprintConstraints.ID_REGEX.matches(id)) {
                "Backup contains a transaction with an invalid ID."
            }
            require(tx.amountMinor in 1L..BlueprintConstraints.MAX_MINOR_UNITS) {
                "Transaction \"$id\" must have a positive integer minor-unit amount."
            }
            require(tx.convertedAmountMinor in 0L..BlueprintConstraints.MAX_MINOR_UNITS && tx.feeMinor in 0L..BlueprintConstraints.MAX_MINOR_UNITS) {
                "Transaction \"$id\" contains negative fee or converted amount."
            }
            val cleanDate = tx.date.trim()
            require(BlueprintConstraints.DATE_REGEX.matches(cleanDate)) {
                "Transaction \"$id\" has an invalid date ($cleanDate). Expected YYYY-MM-DD."
            }
            val cleanDesc = tx.description.trim()
            require(cleanDesc.isNotEmpty()) {
                "Transaction \"$id\" is missing a description."
            }
            if (tx.type == TransactionType.TRANSFER) {
                require(tx.accountId.isNotBlank() && tx.toAccountId.isNotBlank() && tx.accountId != tx.toAccountId) {
                    "Transfer transaction \"$id\" must have distinct source and destination accounts."
                }
            }

            val cleanIdem = tx.idempotencyKey
                .trim()
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifEmpty { "idem_$id" }
                .take(BlueprintConstraints.ID_MAX_LENGTH)
            val idemComposite = "$bizId::$cleanIdem"

            if (id in seenTxIds || idemComposite in seenIdemKeys) {
                // Skip intra-file duplicate safely
                continue
            }
            seenTxIds += id
            seenIdemKeys += idemComposite

            sanitizedTransactions += tx.copy(
                id = id,
                businessId = bizId,
                idempotencyKey = cleanIdem,
                date = cleanDate,
                description = cleanDesc
            )
        }

        val sanitizedAudits = LinkedHashMap<String, AuditEvent>()
        for (evt in raw.auditEvents) {
            val id = evt.id.trim()
            if (id.isEmpty() || !BlueprintConstraints.ID_REGEX.matches(id)) continue
            if (!sanitizedAudits.containsKey(id)) {
                sanitizedAudits[id] = evt.copy(id = id, businessId = bizId)
            }
        }

        return raw.copy(
            business = raw.business.copy(id = bizId, name = bizName),
            accounts = sanitizedAccounts.values.toList(),
            categories = sanitizedCategories.values.toList(),
            parties = sanitizedParties.values.toList(),
            transactions = sanitizedTransactions,
            auditEvents = sanitizedAudits.values.toList()
        )
    }

    fun planSafeMerge(
        incoming: BackupArchive,
        localBusiness: Business?,
        localAccounts: List<Account>,
        localCategories: List<Category>,
        localParties: List<Party>,
        localTransactions: List<CashbookTransaction>,
        localAudits: List<AuditEvent>
    ): SafeMergeExecutionPlan {
        val bizToUpsert = when {
            localBusiness == null -> incoming.business
            CloudSyncEngine.parseIsoEpochMillis(incoming.business.updatedAt) >
                CloudSyncEngine.parseIsoEpochMillis(localBusiness.updatedAt) -> {
                incoming.business.copy(ownerId = localBusiness.ownerId)
            }
            else -> null
        }

        val effectiveOwnerId = localBusiness?.ownerId ?: incoming.business.ownerId

        val localAccMap = localAccounts.associateBy { it.id }
        val accountsToUpsert = incoming.accounts.mapNotNull { inc ->
            val existing = localAccMap[inc.id]
            when {
                existing == null -> inc.copy(ownerId = effectiveOwnerId)
                CloudSyncEngine.parseIsoEpochMillis(inc.updatedAt) >
                    CloudSyncEngine.parseIsoEpochMillis(existing.updatedAt) -> inc.copy(ownerId = existing.ownerId)
                else -> null
            }
        }

        val localCatMap = localCategories.associateBy { it.id }
        val categoriesToUpsert = incoming.categories.mapNotNull { inc ->
            val existing = localCatMap[inc.id]
            when {
                existing == null -> inc.copy(ownerId = effectiveOwnerId)
                CloudSyncEngine.parseIsoEpochMillis(inc.updatedAt) >
                    CloudSyncEngine.parseIsoEpochMillis(existing.updatedAt) -> inc.copy(ownerId = existing.ownerId)
                else -> null
            }
        }

        val localPartyMap = localParties.associateBy { it.id }
        val partiesToUpsert = incoming.parties.mapNotNull { inc ->
            val existing = localPartyMap[inc.id]
            when {
                existing == null -> inc.copy(ownerId = effectiveOwnerId)
                CloudSyncEngine.parseIsoEpochMillis(inc.updatedAt) >
                    CloudSyncEngine.parseIsoEpochMillis(existing.updatedAt) -> inc.copy(ownerId = existing.ownerId)
                else -> null
            }
        }

        val localTxById = localTransactions.associateBy { it.id }
        val localTxByIdem = localTransactions
            .filter { it.idempotencyKey.isNotBlank() }
            .associateBy { "${it.businessId}::${it.idempotencyKey}" }

        val txToUpsert = mutableListOf<CashbookTransaction>()
        var newInserted = 0
        var existingUpdated = 0
        var duplicatesSkipped = 0

        for (inc in incoming.transactions) {
            val idemKey = "${inc.businessId}::${inc.idempotencyKey}"
            val existing = localTxById[inc.id]
                ?: if (inc.idempotencyKey.isNotBlank()) localTxByIdem[idemKey] else null

            if (existing == null) {
                txToUpsert += inc.copy(
                    ownerId = effectiveOwnerId,
                    createdByUid = effectiveOwnerId,
                    pendingCloudSync = true
                )
                newInserted++
                continue
            }

            // Never revert a local terminal VOIDED transaction back to POSTED
            if (existing.status == TransactionStatus.VOIDED) {
                duplicatesSkipped++
                continue
            }

            // If incoming voided a record that is still POSTED locally, apply terminal VOIDED
            if (inc.status == TransactionStatus.VOIDED && existing.status != TransactionStatus.VOIDED) {
                txToUpsert += existing.copy(
                    status = TransactionStatus.VOIDED,
                    voidReason = inc.voidReason.ifBlank { "Voided in restored backup" },
                    updatedAt = inc.updatedAt,
                    pendingCloudSync = true
                )
                existingUpdated++
                continue
            }

            val incMs = CloudSyncEngine.parseIsoEpochMillis(inc.updatedAt)
            val locMs = CloudSyncEngine.parseIsoEpochMillis(existing.updatedAt)
            if (incMs > locMs) {
                txToUpsert += inc.copy(
                    id = existing.id,
                    ownerId = existing.ownerId,
                    createdByUid = existing.createdByUid,
                    attachmentName = inc.attachmentName.ifBlank { existing.attachmentName },
                    attachmentMime = inc.attachmentMime.ifBlank { existing.attachmentMime },
                    receiptLocalPath = existing.receiptLocalPath,
                    pendingCloudSync = true
                )
                existingUpdated++
            } else {
                duplicatesSkipped++
            }
        }

        val localAuditIds = localAudits.map { it.id }.toHashSet()
        val auditsToUpsert = incoming.auditEvents
            .filter { it.id !in localAuditIds }
            .map { it.copy(ownerId = effectiveOwnerId, actorUid = effectiveOwnerId, pendingCloudSync = true) }

        return SafeMergeExecutionPlan(
            businessToUpsert = bizToUpsert,
            accountsToUpsert = accountsToUpsert,
            categoriesToUpsert = categoriesToUpsert,
            partiesToUpsert = partiesToUpsert,
            transactionsToUpsert = txToUpsert,
            auditEventsToUpsert = auditsToUpsert,
            newTransactionsInserted = newInserted,
            existingTransactionsUpdated = existingUpdated,
            duplicateTransactionsSkipped = duplicatesSkipped
        )
    }

    fun buildInspectionPreview(
        sanitized: BackupArchive,
        localBusiness: Business?,
        localAccounts: List<Account>,
        localCategories: List<Category>,
        localParties: List<Party>,
        localTransactions: List<CashbookTransaction>,
        localAudits: List<AuditEvent>
    ): BackupInspectionPreview {
        val mergePlan = planSafeMerge(
            incoming = sanitized,
            localBusiness = localBusiness,
            localAccounts = localAccounts,
            localCategories = localCategories,
            localParties = localParties,
            localTransactions = localTransactions,
            localAudits = localAudits
        )
        return BackupInspectionPreview(
            businessId = sanitized.business.id,
            businessName = sanitized.business.name,
            currency = sanitized.business.defaultCurrency,
            exportedAt = sanitized.exportedAt,
            checksum = sanitized.checksum,
            incomingAccountCount = sanitized.accounts.size,
            incomingCategoryCount = sanitized.categories.size,
            incomingPartyCount = sanitized.parties.size,
            incomingTransactionCount = sanitized.transactions.size,
            targetBusinessExistsLocally = localBusiness != null,
            existingLocalTransactionCount = localTransactions.size,
            newTransactionsInMerge = mergePlan.newTransactionsInserted,
            updatedTransactionsInMerge = mergePlan.existingTransactionsUpdated,
            duplicateTransactionsSkippedInMerge = mergePlan.duplicateTransactionsSkipped
        )
    }
}
