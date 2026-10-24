package com.vumiq.cashbook.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vumiq.cashbook.BuildConfig
import com.vumiq.cashbook.data.local.CashbookDao
import com.vumiq.cashbook.data.local.toDomain
import com.vumiq.cashbook.data.local.toEntity
import com.vumiq.cashbook.data.preferences.UserPreferencesSnapshot
import com.vumiq.cashbook.domain.Account
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.AuditEvent
import com.vumiq.cashbook.domain.Business
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.Category
import com.vumiq.cashbook.domain.CategoryKind
import com.vumiq.cashbook.domain.CloudSyncEngine
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.Party
import com.vumiq.cashbook.domain.PartyStatus
import com.vumiq.cashbook.domain.PartyType
import com.vumiq.cashbook.domain.TransactionStatus
import com.vumiq.cashbook.domain.TransactionType
import kotlinx.coroutines.tasks.await
import java.time.Instant

data class CloudSyncResult(
    val ok: Boolean,
    val pushedCount: Int = 0,
    val pulledCount: Int = 0,
    val message: String,
    val shouldRetryLater: Boolean = false
)

/**
 * Genuine two-way Cloud Firestore synchronization manager connecting the native Android Room SQLite
 * ledger and the VUMIQ Cashbook Web workspace (`sixth-etching-101dl`, database
 * `ai-studio-vumiqcashbook-dd57b662-22cd-4575-bb13-5e888a1b3793`).
 *
 * Strictly complies with `firestore.rules`:
 * - Verified Google account gate (`email_verified == true`)
 * - Pillar 8 `.whereEqualTo("ownerId", uid)` on every `list` query
 * - Atomic `WriteBatch` for business + owner membership creation
 * - Atomic `WriteBatch` for posted transaction + `create_{txId}` audit event creation
 * - Action-scoped `docRef.update(...)` with exact allowlisted keys so `createdAt` and immutable
 *   identity fields are never overwritten
 * - Zero silent deletions; offline-first Room remains authoritative locally when offline
 */
class FirestoreSyncManager(private val context: Context) {

    fun isFirebaseConfigured(): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun currentCloudUser(): FirebaseUser? {
        if (!isFirebaseConfigured()) return null
        return runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
    }

    private fun firestoreOrNull(): FirebaseFirestore? {
        if (!isFirebaseConfigured()) return null
        return runCatching {
            val app = FirebaseApp.getInstance()
            FirebaseFirestore.getInstance(app, BuildConfig.FIRESTORE_DATABASE_ID)
        }.getOrNull()
    }

    suspend fun syncWorkspaceTwoWay(
        dao: CashbookDao,
        prefs: UserPreferencesSnapshot
    ): CloudSyncResult {
        if (!isFirebaseConfigured()) {
            return CloudSyncResult(
                ok = false,
                message = "Offline-first local mode active. Add google-services.json for com.vumiq.cashbook (project ${BuildConfig.FIREBASE_PROJECT_ID}) to enable two-way Cloud Sync."
            )
        }

        val user = currentCloudUser()
            ?: return CloudSyncResult(
                ok = false,
                message = "Sign in with Google in Settings to sync between Android and Web Cashbook."
            )

        if (!user.isEmailVerified) {
            return CloudSyncResult(
                ok = false,
                message = "Your Google account email (${user.email ?: user.uid}) is not verified. Cloud Firestore security rules require a verified email."
            )
        }

        val db = firestoreOrNull()
            ?: return CloudSyncResult(
                ok = false,
                message = "Cloud Firestore instance is currently unavailable.",
                shouldRetryLater = true
            )

        return try {
            val uid = user.uid
            val actorName = (user.displayName ?: prefs.displayName).trim().take(100).ifEmpty { "Owner" }
            val nowFallbackIso = Instant.now().toString()

            // 1. Ensure User Profile & Isolated PII document exist in Firestore
            syncUserProfileDocument(db, user, actorName, prefs)

            // 2. Adopt authenticated Cloud UID for any local businesses created offline before first sign-in
            val initialLocalBusinesses = dao.getBusinessesSnapshot().map { it.toDomain() }
            for (localBiz in initialLocalBusinesses) {
                if (CloudSyncEngine.isLocalPlaceholderOwnerId(localBiz.ownerId)) {
                    dao.adoptCloudOwnerForLocalBusiness(localBiz.id, uid)
                }
            }

            // Reload local businesses owned by this user
            val localBusinesses = dao.getBusinessesSnapshot()
                .map { it.toDomain() }
                .filter { it.ownerId == uid }
            val localBizMap = localBusinesses.associateBy { it.id }

            // 3. Pull remote businesses owned by this user (Pillar 8: whereEqualTo("ownerId", uid))
            val remoteBizSnap = db.collection("businesses")
                .whereEqualTo("ownerId", uid)
                .get()
                .await()
            val remoteBizMap = remoteBizSnap.documents.mapNotNull { doc ->
                parseBusinessDoc(doc, nowFallbackIso)
            }.associateBy { it.id }

            val allBusinessIds = LinkedHashSet<String>().apply {
                addAll(remoteBizMap.keys)
                addAll(localBizMap.keys)
            }

            var totalPushed = 0
            var totalPulled = 0

            for (bizId in allBusinessIds) {
                val localBiz = localBizMap[bizId]
                val remoteBiz = remoteBizMap[bizId]
                val bizRef = db.collection("businesses").document(bizId)

                var pulledBizForRoom: Business? = null

                when {
                    localBiz != null && remoteBiz == null -> {
                        createBusinessWithOwnerMembership(db, bizRef, localBiz, uid, actorName)
                        totalPushed++
                    }
                    localBiz == null && remoteBiz != null -> {
                        pulledBizForRoom = remoteBiz
                        totalPulled++
                    }
                    localBiz != null && remoteBiz != null -> {
                        val remoteMs = CloudSyncEngine.parseIsoEpochMillis(remoteBiz.updatedAt)
                        val localMs = CloudSyncEngine.parseIsoEpochMillis(localBiz.updatedAt)
                        if (remoteMs > localMs) {
                            pulledBizForRoom = remoteBiz
                        } else if (localMs > remoteMs && !sameBusinessMetadata(localBiz, remoteBiz)) {
                            updateBusinessMetadata(bizRef, localBiz)
                            totalPushed++
                        }
                    }
                }

                // Pull subcollections for this business (all enforcing ownerId == uid per Pillar 8)
                val remoteAccounts = bizRef.collection("accounts")
                    .whereEqualTo("ownerId", uid)
                    .get().await().documents
                    .mapNotNull { parseAccountDoc(it, bizId, uid, nowFallbackIso) }

                val remoteCategories = bizRef.collection("categories")
                    .whereEqualTo("ownerId", uid)
                    .get().await().documents
                    .mapNotNull { parseCategoryDoc(it, bizId, uid, nowFallbackIso) }

                val remoteParties = bizRef.collection("parties")
                    .whereEqualTo("ownerId", uid)
                    .get().await().documents
                    .mapNotNull { parsePartyDoc(it, bizId, uid, nowFallbackIso) }

                val remoteTransactions = bizRef.collection("transactions")
                    .whereEqualTo("ownerId", uid)
                    .get().await().documents
                    .mapNotNull { parseTransactionDoc(it, bizId, uid, nowFallbackIso) }

                val remoteAudits = bizRef.collection("auditEvents")
                    .whereEqualTo("ownerId", uid)
                    .get().await().documents
                    .mapNotNull { parseAuditDoc(it, bizId, uid, nowFallbackIso) }

                val localAccounts = dao.getAccountsSnapshot(bizId).map { it.toDomain() }
                val localCategories = dao.getCategoriesSnapshot(bizId).map { it.toDomain() }
                val localParties = dao.getPartiesSnapshot(bizId).map { it.toDomain() }
                val localTransactions = dao.getTransactionsSnapshot(bizId).map { it.toDomain() }
                val localAudits = dao.getAuditEventsSnapshot(bizId).map { it.toDomain() }
                val localAuditMap = localAudits.associateBy { it.id }
                val remoteAuditIds = remoteAudits.map { it.id }.toHashSet()

                val accPlan = CloudSyncEngine.planAccountsMerge(localAccounts, remoteAccounts)
                val catPlan = CloudSyncEngine.planCategoriesMerge(localCategories, remoteCategories)
                val partyPlan = CloudSyncEngine.planPartiesMerge(localParties, remoteParties)
                val txPlan = CloudSyncEngine.planTransactionsMerge(localTransactions, remoteTransactions)

                val auditsToPull = remoteAudits.filter { !localAuditMap.containsKey(it.id) }

                // Apply Cloud -> Local Room updates atomically first so local foreign references are complete
                dao.applyCloudPullBundle(
                    business = pulledBizForRoom?.toEntity(),
                    accounts = accPlan.toPullIntoLocal.map { it.toEntity() },
                    categories = catPlan.toPullIntoLocal.map { it.toEntity() },
                    parties = partyPlan.toPullIntoLocal.map { it.toEntity() },
                    transactions = txPlan.toUpsertInLocal.map { it.toEntity() },
                    auditEvents = auditsToPull.map { it.copy(pendingCloudSync = false).toEntity() },
                    syncedTxIds = txPlan.toMarkSyncedLocally
                )

                totalPulled += accPlan.toPullIntoLocal.size +
                    catPlan.toPullIntoLocal.size +
                    partyPlan.toPullIntoLocal.size +
                    txPlan.toUpsertInLocal.size

                // Push Local -> Cloud Accounts
                for (acc in accPlan.toPushCreateToCloud) {
                    createAccountDoc(bizRef.collection("accounts").document(acc.id), acc, bizId, uid)
                    totalPushed++
                }
                for (acc in accPlan.toPushUpdateToCloud) {
                    updateAccountDoc(bizRef.collection("accounts").document(acc.id), acc)
                    totalPushed++
                }

                // Push Local -> Cloud Categories
                for (cat in catPlan.toPushCreateToCloud) {
                    createCategoryDoc(bizRef.collection("categories").document(cat.id), cat, bizId, uid)
                    totalPushed++
                }
                for (cat in catPlan.toPushUpdateToCloud) {
                    updateCategoryDoc(bizRef.collection("categories").document(cat.id), cat)
                    totalPushed++
                }

                // Push Local -> Cloud Parties
                for (party in partyPlan.toPushCreateToCloud) {
                    createPartyDoc(bizRef.collection("parties").document(party.id), party, bizId, uid)
                    totalPushed++
                }
                for (party in partyPlan.toPushUpdateToCloud) {
                    updatePartyDoc(bizRef.collection("parties").document(party.id), party)
                    totalPushed++
                }

                // Push Local -> Cloud Transactions (strictly obeying firestore.rules)
                for (op in txPlan.cloudPushOperations) {
                    when (op) {
                        is CloudSyncEngine.TransactionPushOperation.CreatePosted -> {
                            createPostedTransactionBatch(
                                db = db,
                                bizRef = bizRef,
                                tx = op.tx,
                                localAudit = localAuditMap["create_${op.tx.id}"],
                                bizId = bizId,
                                uid = uid,
                                actorName = actorName
                            )
                            dao.markTransactionSynced(op.tx.id)
                            dao.markAuditEventSynced("create_${op.tx.id}")
                            remoteAuditIds += "create_${op.tx.id}"
                            totalPushed++
                        }

                        is CloudSyncEngine.TransactionPushOperation.CreatePostedThenVoid -> {
                            createPostedTransactionBatch(
                                db = db,
                                bizRef = bizRef,
                                tx = op.tx,
                                localAudit = localAuditMap["create_${op.tx.id}"],
                                bizId = bizId,
                                uid = uid,
                                actorName = actorName
                            )
                            voidTransactionDoc(
                                txRef = bizRef.collection("transactions").document(op.tx.id),
                                voidReason = op.tx.voidReason
                            )
                            dao.markTransactionSynced(op.tx.id)
                            dao.markAuditEventSynced("create_${op.tx.id}")
                            remoteAuditIds += "create_${op.tx.id}"
                            totalPushed++
                        }

                        is CloudSyncEngine.TransactionPushOperation.UpdatePostedDetails -> {
                            updatePostedTransactionDoc(
                                txRef = bizRef.collection("transactions").document(op.tx.id),
                                tx = op.tx
                            )
                            dao.markTransactionSynced(op.tx.id)
                            totalPushed++
                        }

                        is CloudSyncEngine.TransactionPushOperation.VoidExistingInCloud -> {
                            voidTransactionDoc(
                                txRef = bizRef.collection("transactions").document(op.tx.id),
                                voidReason = op.tx.voidReason
                            )
                            dao.markTransactionSynced(op.tx.id)
                            totalPushed++
                        }
                    }
                }

                // Push remaining unsynced append-only AuditEvents (edit/void/account/party events)
                val unsyncedAudits = dao.getUnsyncedAuditEvents(bizId).map { it.toDomain() }
                for (audit in unsyncedAudits) {
                    if (audit.id !in remoteAuditIds) {
                        createAuditDoc(
                            auditRef = bizRef.collection("auditEvents").document(audit.id),
                            audit = audit,
                            bizId = bizId,
                            uid = uid,
                            actorName = actorName
                        )
                        remoteAuditIds += audit.id
                    }
                    dao.markAuditEventSynced(audit.id)
                }
            }

            CloudSyncResult(
                ok = true,
                pushedCount = totalPushed,
                pulledCount = totalPulled,
                message = "Two-way Cloud Sync complete ($totalPushed pushed, $totalPulled pulled)."
            )
        } catch (e: FirebaseFirestoreException) {
            val transient = e.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            )
            CloudSyncResult(
                ok = false,
                message = "Cloud Sync paused (${e.code.name}): ${e.localizedMessage ?: "Records safely queued offline."}",
                shouldRetryLater = transient
            )
        } catch (e: Exception) {
            CloudSyncResult(
                ok = false,
                message = "Cloud Sync paused (records safely stored offline): ${e.localizedMessage ?: "Network unavailable"}",
                shouldRetryLater = true
            )
        }
    }

    private suspend fun syncUserProfileDocument(
        db: FirebaseFirestore,
        user: FirebaseUser,
        actorName: String,
        prefs: UserPreferencesSnapshot
    ) {
        val userRef = db.collection("users").document(user.uid)
        val snap = userRef.get().await()
        if (!snap.exists()) {
            userRef.set(
                mapOf(
                    "uid" to user.uid,
                    "displayName" to actorName.take(100).ifEmpty { "Owner" },
                    "defaultBusinessId" to prefs.activeBusinessId.take(128),
                    "theme" to prefs.theme.wireValue,
                    "locale" to prefs.locale.wireValue,
                    "notificationsEnabled" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            val email = user.email?.trim().orEmpty()
            if (email.length in 3..254) {
                val privateRef = userRef.collection("private").document("info")
                if (!privateRef.get().await().exists()) {
                    privateRef.set(
                        mapOf(
                            "uid" to user.uid,
                            "email" to email,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
        }
    }

    private suspend fun createBusinessWithOwnerMembership(
        db: FirebaseFirestore,
        bizRef: DocumentReference,
        business: Business,
        uid: String,
        actorName: String
    ) {
        val batch = db.batch()
        batch.set(
            bizRef,
            mapOf(
                "id" to business.id,
                "ownerId" to uid,
                "name" to business.name.trim().ifEmpty { "VUMIQ Business" }.take(120),
                "defaultCurrency" to business.defaultCurrency.code,
                "address" to business.address.take(240),
                "phone" to business.phone.take(40),
                "fiscalStartMonth" to business.fiscalStartMonth.coerceIn(1, 12),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val memberRef = bizRef.collection("members").document(uid)
        batch.set(
            memberRef,
            mapOf(
                "memberUid" to uid,
                "businessId" to business.id,
                "ownerId" to uid,
                "role" to "owner",
                "displayName" to actorName,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    private suspend fun updateBusinessMetadata(
        bizRef: DocumentReference,
        business: Business
    ) {
        bizRef.update(
            mapOf(
                "name" to business.name.trim().ifEmpty { "VUMIQ Business" }.take(120),
                "defaultCurrency" to business.defaultCurrency.code,
                "address" to business.address.take(240),
                "phone" to business.phone.take(40),
                "fiscalStartMonth" to business.fiscalStartMonth.coerceIn(1, 12),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private fun sameBusinessMetadata(a: Business, b: Business): Boolean =
        a.name == b.name &&
            a.defaultCurrency == b.defaultCurrency &&
            a.address == b.address &&
            a.phone == b.phone &&
            a.fiscalStartMonth == b.fiscalStartMonth

    private suspend fun createAccountDoc(
        accRef: DocumentReference,
        acc: Account,
        bizId: String,
        uid: String
    ) {
        accRef.set(
            mapOf(
                "id" to acc.id,
                "businessId" to bizId,
                "ownerId" to uid,
                "name" to acc.name.trim().ifEmpty { "Account" }.take(80),
                "accountType" to acc.accountType.wireValue,
                "currency" to acc.currency.code,
                "openingBalanceMinor" to acc.openingBalanceMinor.coerceAtLeast(0L),
                "status" to acc.status.wireValue,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun updateAccountDoc(accRef: DocumentReference, acc: Account) {
        accRef.update(
            mapOf(
                "name" to acc.name.trim().ifEmpty { "Account" }.take(80),
                "accountType" to acc.accountType.wireValue,
                "openingBalanceMinor" to acc.openingBalanceMinor.coerceAtLeast(0L),
                "status" to acc.status.wireValue,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun createCategoryDoc(
        catRef: DocumentReference,
        cat: Category,
        bizId: String,
        uid: String
    ) {
        val safeColor = if (cat.color.matches(Regex("^#[0-9a-fA-F]{6}$"))) cat.color else "#126B45"
        catRef.set(
            mapOf(
                "id" to cat.id,
                "businessId" to bizId,
                "ownerId" to uid,
                "name" to cat.name.trim().ifEmpty { "Category" }.take(64),
                "kind" to cat.kind.wireValue,
                "color" to safeColor,
                "icon" to cat.icon.trim().ifEmpty { "tag" }.take(32),
                "archived" to cat.archived,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun updateCategoryDoc(catRef: DocumentReference, cat: Category) {
        val safeColor = if (cat.color.matches(Regex("^#[0-9a-fA-F]{6}$"))) cat.color else "#126B45"
        catRef.update(
            mapOf(
                "name" to cat.name.trim().ifEmpty { "Category" }.take(64),
                "color" to safeColor,
                "icon" to cat.icon.trim().ifEmpty { "tag" }.take(32),
                "archived" to cat.archived,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun createPartyDoc(
        partyRef: DocumentReference,
        party: Party,
        bizId: String,
        uid: String
    ) {
        partyRef.set(
            mapOf(
                "id" to party.id,
                "businessId" to bizId,
                "ownerId" to uid,
                "name" to party.name.trim().ifEmpty { "Party" }.take(100),
                "partyType" to party.partyType.wireValue,
                "phone" to party.phone.take(40),
                "address" to party.address.take(240),
                "notes" to party.notes.take(500),
                "currency" to party.currency.code,
                "openingReceivableMinor" to party.openingReceivableMinor.coerceAtLeast(0L),
                "openingPayableMinor" to party.openingPayableMinor.coerceAtLeast(0L),
                "dueDate" to party.dueDate.take(10),
                "status" to party.status.wireValue,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun updatePartyDoc(partyRef: DocumentReference, party: Party) {
        partyRef.update(
            mapOf(
                "name" to party.name.trim().ifEmpty { "Party" }.take(100),
                "partyType" to party.partyType.wireValue,
                "phone" to party.phone.take(40),
                "address" to party.address.take(240),
                "notes" to party.notes.take(500),
                "currency" to party.currency.code,
                "openingReceivableMinor" to party.openingReceivableMinor.coerceAtLeast(0L),
                "openingPayableMinor" to party.openingPayableMinor.coerceAtLeast(0L),
                "dueDate" to party.dueDate.take(10),
                "status" to party.status.wireValue,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun createPostedTransactionBatch(
        db: FirebaseFirestore,
        bizRef: DocumentReference,
        tx: CashbookTransaction,
        localAudit: AuditEvent?,
        bizId: String,
        uid: String,
        actorName: String
    ) {
        val txRef = bizRef.collection("transactions").document(tx.id)
        val auditId = "create_${tx.id}"
        val auditRef = bizRef.collection("auditEvents").document(auditId)
        val safeTime = tx.time.trim().take(8).let { if (it.length >= 5) it else "12:00" }
        val safeIdem = tx.idempotencyKey
            .trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifEmpty { "idem_${tx.id}" }
            .take(128)

        val batch = db.batch()
        batch.set(
            txRef,
            mapOf(
                "id" to tx.id,
                "businessId" to bizId,
                "ownerId" to uid,
                "idempotencyKey" to safeIdem,
                "type" to tx.type.wireValue,
                "amountMinor" to tx.amountMinor,
                "currency" to tx.currency.code,
                "convertedAmountMinor" to tx.convertedAmountMinor.coerceAtLeast(0L),
                "toCurrency" to tx.toCurrency.take(3),
                "exchangeRateScaled" to tx.exchangeRateScaled.coerceAtLeast(0L),
                "feeMinor" to tx.feeMinor.coerceAtLeast(0L),
                "date" to tx.date.take(10),
                "time" to safeTime,
                "description" to tx.description.trim().ifEmpty { "Entry" }.take(200),
                "accountId" to tx.accountId.take(128),
                "toAccountId" to tx.toAccountId.take(128),
                "categoryId" to tx.categoryId.take(128),
                "categoryLabelSnapshot" to tx.categoryLabelSnapshot.take(64),
                "partyId" to tx.partyId.take(128),
                "partyNameSnapshot" to tx.partyNameSnapshot.take(100),
                "referenceNumber" to tx.referenceNumber.take(64),
                "notes" to tx.notes.take(600),
                "attachmentName" to tx.attachmentName.take(120),
                "attachmentMime" to tx.attachmentMime.take(64),
                "dueDate" to tx.dueDate.take(10),
                "status" to "posted",
                "voidReason" to "",
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            auditRef,
            mapOf(
                "id" to auditId,
                "businessId" to bizId,
                "ownerId" to uid,
                "entityType" to "transaction",
                "entityId" to tx.id,
                "action" to "create",
                "actorUid" to uid,
                "actorName" to actorName,
                "summary" to (localAudit?.summary ?: "Recorded ${tx.type.wireValue}: ${tx.description}")
                    .trim().ifEmpty { "Recorded transaction" }.take(300),
                "previousSummary" to "",
                "newSummary" to (localAudit?.newSummary ?: "${tx.currency.code} ${tx.amountMinor}").take(500),
                "reason" to "",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    private suspend fun updatePostedTransactionDoc(
        txRef: DocumentReference,
        tx: CashbookTransaction
    ) {
        val safeTime = tx.time.trim().take(8).let { if (it.length >= 5) it else "12:00" }
        txRef.update(
            mapOf(
                "type" to tx.type.wireValue,
                "amountMinor" to tx.amountMinor,
                "currency" to tx.currency.code,
                "convertedAmountMinor" to tx.convertedAmountMinor.coerceAtLeast(0L),
                "toCurrency" to tx.toCurrency.take(3),
                "exchangeRateScaled" to tx.exchangeRateScaled.coerceAtLeast(0L),
                "feeMinor" to tx.feeMinor.coerceAtLeast(0L),
                "date" to tx.date.take(10),
                "time" to safeTime,
                "description" to tx.description.trim().ifEmpty { "Entry" }.take(200),
                "accountId" to tx.accountId.take(128),
                "toAccountId" to tx.toAccountId.take(128),
                "categoryId" to tx.categoryId.take(128),
                "categoryLabelSnapshot" to tx.categoryLabelSnapshot.take(64),
                "partyId" to tx.partyId.take(128),
                "partyNameSnapshot" to tx.partyNameSnapshot.take(100),
                "referenceNumber" to tx.referenceNumber.take(64),
                "notes" to tx.notes.take(600),
                "attachmentName" to tx.attachmentName.take(120),
                "attachmentMime" to tx.attachmentMime.take(64),
                "dueDate" to tx.dueDate.take(10),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun voidTransactionDoc(
        txRef: DocumentReference,
        voidReason: String
    ) {
        txRef.update(
            mapOf(
                "status" to "voided",
                "voidReason" to voidReason.trim().ifEmpty { "Voided entry" }.take(240),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun createAuditDoc(
        auditRef: DocumentReference,
        audit: AuditEvent,
        bizId: String,
        uid: String,
        actorName: String
    ) {
        val validEntityType = if (audit.entityType in setOf("transaction", "account", "category", "party", "business")) {
            audit.entityType
        } else {
            "transaction"
        }
        val validAction = if (audit.action in setOf("create", "update", "void", "delete", "restore")) {
            audit.action
        } else {
            "update"
        }
        auditRef.set(
            mapOf(
                "id" to audit.id,
                "businessId" to bizId,
                "ownerId" to uid,
                "entityType" to validEntityType,
                "entityId" to audit.entityId.ifBlank { bizId }.take(128),
                "action" to validAction,
                "actorUid" to uid,
                "actorName" to actorName,
                "summary" to audit.summary.trim().ifEmpty { "Ledger update" }.take(300),
                "previousSummary" to audit.previousSummary.take(500),
                "newSummary" to audit.newSummary.take(500),
                "reason" to audit.reason.take(240),
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private fun readIsoTimestamp(doc: DocumentSnapshot, field: String, fallbackIso: String): String {
        val raw = doc.get(field) ?: return fallbackIso
        return when (raw) {
            is Timestamp -> Instant.ofEpochSecond(raw.seconds, raw.nanoseconds.toLong()).toString()
            is String -> raw.ifBlank { fallbackIso }
            else -> fallbackIso
        }
    }

    private fun parseBusinessDoc(doc: DocumentSnapshot, fallbackIso: String): Business? {
        val id = doc.getString("id") ?: doc.id
        val name = doc.getString("name") ?: return null
        return Business(
            id = id,
            ownerId = doc.getString("ownerId").orEmpty(),
            name = name,
            defaultCurrency = CurrencyCode.fromCodeOrDefault(doc.getString("defaultCurrency")),
            address = doc.getString("address").orEmpty(),
            phone = doc.getString("phone").orEmpty(),
            fiscalStartMonth = (doc.getLong("fiscalStartMonth") ?: 1L).toInt().coerceIn(1, 12),
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            updatedAt = readIsoTimestamp(doc, "updatedAt", fallbackIso)
        )
    }

    private fun parseAccountDoc(doc: DocumentSnapshot, bizId: String, uid: String, fallbackIso: String): Account? {
        val name = doc.getString("name") ?: return null
        return Account(
            id = doc.getString("id") ?: doc.id,
            businessId = doc.getString("businessId") ?: bizId,
            ownerId = doc.getString("ownerId") ?: uid,
            name = name,
            accountType = AccountType.fromWire(doc.getString("accountType")),
            currency = CurrencyCode.fromCodeOrDefault(doc.getString("currency")),
            openingBalanceMinor = doc.getLong("openingBalanceMinor") ?: 0L,
            status = AccountStatus.fromWire(doc.getString("status")),
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            updatedAt = readIsoTimestamp(doc, "updatedAt", fallbackIso)
        )
    }

    private fun parseCategoryDoc(doc: DocumentSnapshot, bizId: String, uid: String, fallbackIso: String): Category? {
        val name = doc.getString("name") ?: return null
        return Category(
            id = doc.getString("id") ?: doc.id,
            businessId = doc.getString("businessId") ?: bizId,
            ownerId = doc.getString("ownerId") ?: uid,
            name = name,
            kind = CategoryKind.fromWire(doc.getString("kind")),
            color = doc.getString("color") ?: "#126B45",
            icon = doc.getString("icon") ?: "tag",
            archived = doc.getBoolean("archived") ?: false,
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            updatedAt = readIsoTimestamp(doc, "updatedAt", fallbackIso)
        )
    }

    private fun parsePartyDoc(doc: DocumentSnapshot, bizId: String, uid: String, fallbackIso: String): Party? {
        val name = doc.getString("name") ?: return null
        return Party(
            id = doc.getString("id") ?: doc.id,
            businessId = doc.getString("businessId") ?: bizId,
            ownerId = doc.getString("ownerId") ?: uid,
            name = name,
            partyType = PartyType.fromWire(doc.getString("partyType")),
            phone = doc.getString("phone").orEmpty(),
            address = doc.getString("address").orEmpty(),
            notes = doc.getString("notes").orEmpty(),
            currency = CurrencyCode.fromCodeOrDefault(doc.getString("currency")),
            openingReceivableMinor = doc.getLong("openingReceivableMinor") ?: 0L,
            openingPayableMinor = doc.getLong("openingPayableMinor") ?: 0L,
            dueDate = doc.getString("dueDate").orEmpty(),
            status = PartyStatus.fromWire(doc.getString("status")),
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            updatedAt = readIsoTimestamp(doc, "updatedAt", fallbackIso)
        )
    }

    private fun parseTransactionDoc(
        doc: DocumentSnapshot,
        bizId: String,
        uid: String,
        fallbackIso: String
    ): CashbookTransaction? {
        val id = doc.getString("id") ?: doc.id
        val amountMinor = doc.getLong("amountMinor") ?: return null
        if (amountMinor <= 0L) return null
        return CashbookTransaction(
            id = id,
            businessId = doc.getString("businessId") ?: bizId,
            ownerId = doc.getString("ownerId") ?: uid,
            idempotencyKey = doc.getString("idempotencyKey") ?: "idem_$id",
            type = TransactionType.fromWire(doc.getString("type")),
            amountMinor = amountMinor,
            currency = CurrencyCode.fromCodeOrDefault(doc.getString("currency")),
            convertedAmountMinor = doc.getLong("convertedAmountMinor") ?: 0L,
            toCurrency = doc.getString("toCurrency").orEmpty(),
            exchangeRateScaled = doc.getLong("exchangeRateScaled") ?: 0L,
            feeMinor = doc.getLong("feeMinor") ?: 0L,
            date = doc.getString("date") ?: fallbackIso.take(10),
            time = doc.getString("time") ?: "12:00",
            description = doc.getString("description") ?: "Transaction",
            accountId = doc.getString("accountId").orEmpty(),
            toAccountId = doc.getString("toAccountId").orEmpty(),
            categoryId = doc.getString("categoryId").orEmpty(),
            categoryLabelSnapshot = doc.getString("categoryLabelSnapshot").orEmpty(),
            partyId = doc.getString("partyId").orEmpty(),
            partyNameSnapshot = doc.getString("partyNameSnapshot").orEmpty(),
            referenceNumber = doc.getString("referenceNumber").orEmpty(),
            notes = doc.getString("notes").orEmpty(),
            attachmentName = doc.getString("attachmentName").orEmpty(),
            attachmentMime = doc.getString("attachmentMime").orEmpty(),
            dueDate = doc.getString("dueDate").orEmpty(),
            status = TransactionStatus.fromWire(doc.getString("status")),
            voidReason = doc.getString("voidReason").orEmpty(),
            createdByUid = doc.getString("createdByUid") ?: uid,
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            updatedAt = readIsoTimestamp(doc, "updatedAt", fallbackIso),
            pendingCloudSync = false
        )
    }

    private fun parseAuditDoc(
        doc: DocumentSnapshot,
        bizId: String,
        uid: String,
        fallbackIso: String
    ): AuditEvent? {
        val summary = doc.getString("summary") ?: return null
        return AuditEvent(
            id = doc.getString("id") ?: doc.id,
            businessId = doc.getString("businessId") ?: bizId,
            ownerId = doc.getString("ownerId") ?: uid,
            entityType = doc.getString("entityType") ?: "transaction",
            entityId = doc.getString("entityId") ?: bizId,
            action = doc.getString("action") ?: "create",
            actorUid = doc.getString("actorUid") ?: uid,
            actorName = doc.getString("actorName") ?: "Owner",
            summary = summary,
            previousSummary = doc.getString("previousSummary").orEmpty(),
            newSummary = doc.getString("newSummary").orEmpty(),
            reason = doc.getString("reason").orEmpty(),
            createdAt = readIsoTimestamp(doc, "createdAt", fallbackIso),
            pendingCloudSync = false
        )
    }
}
