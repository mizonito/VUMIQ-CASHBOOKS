package com.vumiq.cashbook.domain

import java.time.Instant

/**
 * Pure, deterministic two-way Cloud Firestore <-> Room synchronization planner.
 *
 * Enforces VUMIQ Cashbook's non-negotiable financial invariants:
 * 1. Never silently deletes financial records (union merge; missing on either side is synced across).
 * 2. Prevents duplicates via both primary `id` and `(businessId, idempotencyKey)` matching.
 * 3. Enforces terminal `VOIDED` status precedence: once voided on Web or Android, a transaction
 *    never reverts to `POSTED` (matching `firestore.rules` terminal state locking).
 * 4. Prevents older records from silently overwriting newer records using epoch-millisecond
 *    ISO-8601 `updatedAt` comparison.
 * 5. Preserves local-only fields (such as offline receipt photo paths) when pulling remote updates.
 */
object CloudSyncEngine {

    data class EntityMergePlan<T>(
        val toPullIntoLocal: List<T>,
        val toPushCreateToCloud: List<T>,
        val toPushUpdateToCloud: List<T>
    )

    sealed interface TransactionPushOperation {
        /** Transaction does not exist in Firestore and is currently POSTED -> atomic batch create + create_{id} audit. */
        data class CreatePosted(val tx: CashbookTransaction) : TransactionPushOperation

        /**
         * Transaction was created offline AND voided offline before ever reaching Firestore.
         * Firestore rules require `status == 'posted'` on `create`, followed by a terminal `voided` `update`.
         */
        data class CreatePostedThenVoid(val tx: CashbookTransaction) : TransactionPushOperation

        /** Transaction exists in Firestore as POSTED and local has a newer POSTED edit. */
        data class UpdatePostedDetails(val tx: CashbookTransaction) : TransactionPushOperation

        /** Transaction exists in Firestore as POSTED and local has transitioned it to terminal VOIDED. */
        data class VoidExistingInCloud(val tx: CashbookTransaction) : TransactionPushOperation
    }

    data class TransactionMergePlan(
        val toUpsertInLocal: List<CashbookTransaction>,
        val toMarkSyncedLocally: List<String>,
        val cloudPushOperations: List<TransactionPushOperation>
    )

    fun parseIsoEpochMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching { Instant.parse(iso.trim()).toEpochMilli() }.getOrDefault(0L)
    }

    fun isLocalPlaceholderOwnerId(ownerId: String): Boolean {
        val trimmed = ownerId.trim()
        return trimmed.isEmpty() ||
            trimmed.startsWith("local_") ||
            trimmed == "guest_local_owner"
    }

    fun planAccountsMerge(
        localAccounts: List<Account>,
        remoteAccounts: List<Account>
    ): EntityMergePlan<Account> {
        val localMap = localAccounts.associateBy { it.id }
        val remoteMap = remoteAccounts.associateBy { it.id }
        val toPull = mutableListOf<Account>()
        val toCreate = mutableListOf<Account>()
        val toUpdate = mutableListOf<Account>()

        for ((id, remote) in remoteMap) {
            val local = localMap[id]
            if (local == null) {
                toPull += remote
            } else {
                val remoteMs = parseIsoEpochMillis(remote.updatedAt)
                val localMs = parseIsoEpochMillis(local.updatedAt)
                when {
                    remoteMs > localMs -> toPull += remote
                    localMs > remoteMs && local.currency == remote.currency && !sameAccountFields(local, remote) -> {
                        toUpdate += local
                    }
                }
            }
        }

        for ((id, local) in localMap) {
            if (!remoteMap.containsKey(id)) {
                toCreate += local
            }
        }

        return EntityMergePlan(toPull, toCreate, toUpdate)
    }

    private fun sameAccountFields(a: Account, b: Account): Boolean =
        a.name == b.name &&
            a.accountType == b.accountType &&
            a.openingBalanceMinor == b.openingBalanceMinor &&
            a.status == b.status

    fun planCategoriesMerge(
        localCategories: List<Category>,
        remoteCategories: List<Category>
    ): EntityMergePlan<Category> {
        val localMap = localCategories.associateBy { it.id }
        val remoteMap = remoteCategories.associateBy { it.id }
        val toPull = mutableListOf<Category>()
        val toCreate = mutableListOf<Category>()
        val toUpdate = mutableListOf<Category>()

        for ((id, remote) in remoteMap) {
            val local = localMap[id]
            if (local == null) {
                toPull += remote
            } else {
                val remoteMs = parseIsoEpochMillis(remote.updatedAt)
                val localMs = parseIsoEpochMillis(local.updatedAt)
                when {
                    remoteMs > localMs -> toPull += remote
                    localMs > remoteMs && local.kind == remote.kind && !sameCategoryFields(local, remote) -> {
                        toUpdate += local
                    }
                }
            }
        }

        for ((id, local) in localMap) {
            if (!remoteMap.containsKey(id)) {
                toCreate += local
            }
        }

        return EntityMergePlan(toPull, toCreate, toUpdate)
    }

    private fun sameCategoryFields(a: Category, b: Category): Boolean =
        a.name == b.name &&
            a.color == b.color &&
            a.icon == b.icon &&
            a.archived == b.archived

    fun planPartiesMerge(
        localParties: List<Party>,
        remoteParties: List<Party>
    ): EntityMergePlan<Party> {
        val localMap = localParties.associateBy { it.id }
        val remoteMap = remoteParties.associateBy { it.id }
        val toPull = mutableListOf<Party>()
        val toCreate = mutableListOf<Party>()
        val toUpdate = mutableListOf<Party>()

        for ((id, remote) in remoteMap) {
            val local = localMap[id]
            if (local == null) {
                toPull += remote
            } else {
                val remoteMs = parseIsoEpochMillis(remote.updatedAt)
                val localMs = parseIsoEpochMillis(local.updatedAt)
                when {
                    remoteMs > localMs -> toPull += remote
                    localMs > remoteMs && !samePartyFields(local, remote) -> toUpdate += local
                }
            }
        }

        for ((id, local) in localMap) {
            if (!remoteMap.containsKey(id)) {
                toCreate += local
            }
        }

        return EntityMergePlan(toPull, toCreate, toUpdate)
    }

    private fun samePartyFields(a: Party, b: Party): Boolean =
        a.name == b.name &&
            a.partyType == b.partyType &&
            a.phone == b.phone &&
            a.address == b.address &&
            a.notes == b.notes &&
            a.currency == b.currency &&
            a.openingReceivableMinor == b.openingReceivableMinor &&
            a.openingPayableMinor == b.openingPayableMinor &&
            a.dueDate == b.dueDate &&
            a.status == b.status

    /**
     * Plans two-way transaction synchronization with idempotency deduplication,
     * terminal VOIDED precedence, and timestamp-based LWW protection.
     */
    fun planTransactionsMerge(
        localTransactions: List<CashbookTransaction>,
        remoteTransactions: List<CashbookTransaction>
    ): TransactionMergePlan {
        val localById = localTransactions.associateBy { it.id }
        val localByIdempotency = localTransactions
            .filter { it.idempotencyKey.isNotBlank() }
            .associateBy { "${it.businessId}::${it.idempotencyKey}" }

        val matchedLocalIds = HashSet<String>()
        val toUpsertInLocal = mutableListOf<CashbookTransaction>()
        val toMarkSyncedLocally = mutableListOf<String>()
        val pushOps = mutableListOf<TransactionPushOperation>()

        for (remote in remoteTransactions) {
            val idemComposite = "${remote.businessId}::${remote.idempotencyKey}"
            val local = localById[remote.id]
                ?: if (remote.idempotencyKey.isNotBlank()) localByIdempotency[idemComposite] else null

            if (local == null) {
                toUpsertInLocal += remote.copy(pendingCloudSync = false)
                continue
            }

            matchedLocalIds += local.id

            // Rule 1: Terminal VOIDED status is irreversible and always wins
            if (remote.status == TransactionStatus.VOIDED) {
                if (local.status != TransactionStatus.VOIDED || local.pendingCloudSync || local.voidReason != remote.voidReason) {
                    toUpsertInLocal += mergePreservingLocalAttachment(local, remote).copy(
                        id = local.id,
                        status = TransactionStatus.VOIDED,
                        voidReason = remote.voidReason.ifBlank { local.voidReason },
                        pendingCloudSync = false
                    )
                }
                continue
            }

            if (local.status == TransactionStatus.VOIDED) {
                // Local voided a record that is still POSTED in Firestore -> push terminal void transition
                if (local.id == remote.id) {
                    pushOps += TransactionPushOperation.VoidExistingInCloud(local)
                } else {
                    // Canonicalize to remote's ID if matched by idempotencyKey? Keep remote ID for Firestore update
                    pushOps += TransactionPushOperation.VoidExistingInCloud(local.copy(id = remote.id))
                }
                continue
            }

            // Rule 2: Both are POSTED -> compare updatedAt epoch milliseconds
            val remoteMs = parseIsoEpochMillis(remote.updatedAt)
            val localMs = parseIsoEpochMillis(local.updatedAt)

            when {
                remoteMs > localMs -> {
                    // Cloud has a newer edit -> pull into Room without losing local attachment path
                    toUpsertInLocal += mergePreservingLocalAttachment(local, remote).copy(
                        id = local.id,
                        pendingCloudSync = false
                    )
                }
                local.pendingCloudSync && localMs >= remoteMs -> {
                    // Local has an unsynced edit that is newer than (or equal to) cloud -> push update
                    if (!samePostedTransactionFields(local, remote)) {
                        pushOps += TransactionPushOperation.UpdatePostedDetails(
                            if (local.id == remote.id) local else local.copy(id = remote.id)
                        )
                    } else {
                        toMarkSyncedLocally += local.id
                    }
                }
                local.pendingCloudSync -> {
                    // Local pendingCloudSync was true, but remote is already strictly newer (handled above)
                    toMarkSyncedLocally += local.id
                }
            }
        }

        // Remaining local transactions that do NOT exist in Cloud (neither by id nor idempotencyKey)
        for (local in localTransactions) {
            if (local.id in matchedLocalIds) continue
            if (local.status == TransactionStatus.VOIDED) {
                pushOps += TransactionPushOperation.CreatePostedThenVoid(local)
            } else {
                pushOps += TransactionPushOperation.CreatePosted(local)
            }
        }

        return TransactionMergePlan(
            toUpsertInLocal = toUpsertInLocal,
            toMarkSyncedLocally = toMarkSyncedLocally,
            cloudPushOperations = pushOps
        )
    }

    private fun mergePreservingLocalAttachment(
        local: CashbookTransaction,
        remote: CashbookTransaction
    ): CashbookTransaction = remote.copy(
        attachmentName = remote.attachmentName.ifBlank { local.attachmentName },
        attachmentMime = remote.attachmentMime.ifBlank { local.attachmentMime },
        receiptLocalPath = local.receiptLocalPath
    )

    private fun samePostedTransactionFields(
        a: CashbookTransaction,
        b: CashbookTransaction
    ): Boolean =
        a.type == b.type &&
            a.amountMinor == b.amountMinor &&
            a.currency == b.currency &&
            a.convertedAmountMinor == b.convertedAmountMinor &&
            a.toCurrency == b.toCurrency &&
            a.exchangeRateScaled == b.exchangeRateScaled &&
            a.feeMinor == b.feeMinor &&
            a.date == b.date &&
            a.time == b.time &&
            a.description == b.description &&
            a.accountId == b.accountId &&
            a.toAccountId == b.toAccountId &&
            a.categoryId == b.categoryId &&
            a.categoryLabelSnapshot == b.categoryLabelSnapshot &&
            a.partyId == b.partyId &&
            a.partyNameSnapshot == b.partyNameSnapshot &&
            a.referenceNumber == b.referenceNumber &&
            a.notes == b.notes &&
            a.attachmentName == b.attachmentName &&
            a.attachmentMime == b.attachmentMime &&
            a.dueDate == b.dueDate
}
