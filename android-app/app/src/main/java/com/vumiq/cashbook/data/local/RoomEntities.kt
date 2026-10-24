package com.vumiq.cashbook.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vumiq.cashbook.domain.Account
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.AuditEvent
import com.vumiq.cashbook.domain.Business
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.Category
import com.vumiq.cashbook.domain.CategoryKind
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.Party
import com.vumiq.cashbook.domain.PartyStatus
import com.vumiq.cashbook.domain.PartyType
import com.vumiq.cashbook.domain.TransactionStatus
import com.vumiq.cashbook.domain.TransactionType

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val defaultCurrency: String,
    val address: String,
    val phone: String,
    val fiscalStartMonth: Int,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["businessId"])]
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val accountType: String,
    val currency: String,
    val openingBalanceMinor: Long,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["businessId"])]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val kind: String,
    val color: String,
    val icon: String,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "parties",
    indices = [Index(value = ["businessId"])]
)
data class PartyEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val partyType: String,
    val phone: String,
    val address: String,
    val notes: String,
    val currency: String,
    val openingReceivableMinor: Long,
    val openingPayableMinor: Long,
    val dueDate: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["businessId"]),
        Index(value = ["businessId", "idempotencyKey"], unique = true)
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val idempotencyKey: String,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val convertedAmountMinor: Long,
    val toCurrency: String,
    val exchangeRateScaled: Long,
    val feeMinor: Long,
    val date: String,
    val time: String,
    val description: String,
    val accountId: String,
    val toAccountId: String,
    val categoryId: String,
    val categoryLabelSnapshot: String,
    val partyId: String,
    val partyNameSnapshot: String,
    val referenceNumber: String,
    val notes: String,
    val attachmentName: String,
    val attachmentMime: String,
    @ColumnInfo(defaultValue = "")
    val receiptLocalPath: String = "",
    val dueDate: String,
    val status: String,
    val voidReason: String,
    val createdByUid: String,
    val createdAt: String,
    val updatedAt: String,
    val pendingCloudSync: Boolean
)

@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["businessId"])]
)
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val actorUid: String,
    val actorName: String,
    val summary: String,
    val previousSummary: String,
    val newSummary: String,
    val reason: String,
    val createdAt: String,
    val pendingCloudSync: Boolean
)

fun BusinessEntity.toDomain() = Business(
    id = id,
    ownerId = ownerId,
    name = name,
    defaultCurrency = CurrencyCode.fromCodeOrDefault(defaultCurrency),
    address = address,
    phone = phone,
    fiscalStartMonth = fiscalStartMonth,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Business.toEntity() = BusinessEntity(
    id = id,
    ownerId = ownerId,
    name = name,
    defaultCurrency = defaultCurrency.code,
    address = address,
    phone = phone,
    fiscalStartMonth = fiscalStartMonth,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun AccountEntity.toDomain() = Account(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    accountType = AccountType.fromWire(accountType),
    currency = CurrencyCode.fromCodeOrDefault(currency),
    openingBalanceMinor = openingBalanceMinor,
    status = AccountStatus.fromWire(status),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Account.toEntity() = AccountEntity(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    accountType = accountType.wireValue,
    currency = currency.code,
    openingBalanceMinor = openingBalanceMinor,
    status = status.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CategoryEntity.toDomain() = Category(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    kind = CategoryKind.fromWire(kind),
    color = color,
    icon = icon,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Category.toEntity() = CategoryEntity(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    kind = kind.wireValue,
    color = color,
    icon = icon,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun PartyEntity.toDomain() = Party(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    partyType = PartyType.fromWire(partyType),
    phone = phone,
    address = address,
    notes = notes,
    currency = CurrencyCode.fromCodeOrDefault(currency),
    openingReceivableMinor = openingReceivableMinor,
    openingPayableMinor = openingPayableMinor,
    dueDate = dueDate,
    status = PartyStatus.fromWire(status),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Party.toEntity() = PartyEntity(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    name = name,
    partyType = partyType.wireValue,
    phone = phone,
    address = address,
    notes = notes,
    currency = currency.code,
    openingReceivableMinor = openingReceivableMinor,
    openingPayableMinor = openingPayableMinor,
    dueDate = dueDate,
    status = status.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TransactionEntity.toDomain() = CashbookTransaction(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    idempotencyKey = idempotencyKey,
    type = TransactionType.fromWire(type),
    amountMinor = amountMinor,
    currency = CurrencyCode.fromCodeOrDefault(currency),
    convertedAmountMinor = convertedAmountMinor,
    toCurrency = toCurrency,
    exchangeRateScaled = exchangeRateScaled,
    feeMinor = feeMinor,
    date = date,
    time = time,
    description = description,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    categoryLabelSnapshot = categoryLabelSnapshot,
    partyId = partyId,
    partyNameSnapshot = partyNameSnapshot,
    referenceNumber = referenceNumber,
    notes = notes,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    receiptLocalPath = receiptLocalPath,
    dueDate = dueDate,
    status = TransactionStatus.fromWire(status),
    voidReason = voidReason,
    createdByUid = createdByUid,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pendingCloudSync = pendingCloudSync
)

fun CashbookTransaction.toEntity() = TransactionEntity(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    idempotencyKey = idempotencyKey,
    type = type.wireValue,
    amountMinor = amountMinor,
    currency = currency.code,
    convertedAmountMinor = convertedAmountMinor,
    toCurrency = toCurrency,
    exchangeRateScaled = exchangeRateScaled,
    feeMinor = feeMinor,
    date = date,
    time = time,
    description = description,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    categoryLabelSnapshot = categoryLabelSnapshot,
    partyId = partyId,
    partyNameSnapshot = partyNameSnapshot,
    referenceNumber = referenceNumber,
    notes = notes,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    receiptLocalPath = receiptLocalPath,
    dueDate = dueDate,
    status = status.wireValue,
    voidReason = voidReason,
    createdByUid = createdByUid,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pendingCloudSync = pendingCloudSync
)

fun AuditEventEntity.toDomain() = AuditEvent(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    entityType = entityType,
    entityId = entityId,
    action = action,
    actorUid = actorUid,
    actorName = actorName,
    summary = summary,
    previousSummary = previousSummary,
    newSummary = newSummary,
    reason = reason,
    createdAt = createdAt,
    pendingCloudSync = pendingCloudSync
)

fun AuditEvent.toEntity() = AuditEventEntity(
    id = id,
    businessId = businessId,
    ownerId = ownerId,
    entityType = entityType,
    entityId = entityId,
    action = action,
    actorUid = actorUid,
    actorName = actorName,
    summary = summary,
    previousSummary = previousSummary,
    newSummary = newSummary,
    reason = reason,
    createdAt = createdAt,
    pendingCloudSync = pendingCloudSync
)
