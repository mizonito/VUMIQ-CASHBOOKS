package com.vumiq.cashbook.domain

enum class CurrencyCode(
    val code: String,
    val nameEn: String,
    val nameFr: String,
    val symbol: String,
    val decimals: Int,
    val minorFactor: Long
) {
    USD("USD", "US Dollar", "Dollar américain", "$", 2, 100L),
    CDF("CDF", "Congolese Franc", "Franc congolais", "FC", 2, 100L),
    EUR("EUR", "Euro", "Euro", "€", 2, 100L),
    GBP("GBP", "British Pound", "Livre sterling", "£", 2, 100L),
    KES("KES", "Kenyan Shilling", "Shilling kényan", "KSh", 2, 100L),
    SOS("SOS", "Somali Shilling", "Shilling somalien", "Sh.So.", 2, 100L),
    XOF("XOF", "West African CFA Franc", "Franc CFA (BCEAO)", "CFA", 0, 1L),
    RWF("RWF", "Rwandan Franc", "Franc rwandais", "FRw", 0, 1L),
    ZAR("ZAR", "South African Rand", "Rand sud-africain", "R", 2, 100L);

    companion object {
        fun fromCodeOrNull(raw: String?): CurrencyCode? {
            if (raw.isNullOrBlank()) return null
            val upper = raw.trim().uppercase()
            return entries.firstOrNull { it.code == upper }
        }

        fun fromCodeOrDefault(raw: String?, fallback: CurrencyCode = USD): CurrencyCode =
            fromCodeOrNull(raw) ?: fallback
    }
}

enum class AccountType(val wireValue: String, val labelEn: String, val labelFr: String) {
    CASH("cash", "Cash Drawer", "Caisse espèces"),
    BANK("bank", "Bank Account", "Compte bancaire"),
    MOBILE_MONEY("mobile_money", "Mobile Money", "Mobile Money"),
    OTHER("other", "Other Account", "Autre compte");

    companion object {
        fun fromWire(raw: String?): AccountType =
            entries.firstOrNull { it.wireValue == raw } ?: CASH
    }
}

enum class AccountStatus(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(raw: String?): AccountStatus =
            entries.firstOrNull { it.wireValue == raw } ?: ACTIVE
    }
}

enum class CategoryKind(val wireValue: String) {
    INCOME("income"),
    EXPENSE("expense");

    companion object {
        fun fromWire(raw: String?): CategoryKind =
            entries.firstOrNull { it.wireValue == raw } ?: EXPENSE
    }
}

enum class PartyType(val wireValue: String, val labelEn: String, val labelFr: String) {
    CUSTOMER("customer", "Customer", "Client"),
    SUPPLIER("supplier", "Supplier", "Fournisseur"),
    OTHER("other", "Other Contact", "Autre contact");

    companion object {
        fun fromWire(raw: String?): PartyType =
            entries.firstOrNull { it.wireValue == raw } ?: CUSTOMER
    }
}

enum class PartyStatus(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(raw: String?): PartyStatus =
            entries.firstOrNull { it.wireValue == raw } ?: ACTIVE
    }
}

enum class MemberRole(val wireValue: String) {
    OWNER("owner"),
    ADMIN("admin"),
    ACCOUNTANT("accountant"),
    MANAGER("manager"),
    VIEWER("viewer");

    companion object {
        fun fromWire(raw: String?): MemberRole =
            entries.firstOrNull { it.wireValue == raw } ?: OWNER
    }
}

enum class TransactionType(val wireValue: String, val labelEn: String, val labelFr: String) {
    INCOME("income", "Cash In (Income)", "Entrée (Revenu)"),
    EXPENSE("expense", "Cash Out (Expense)", "Sortie (Dépense)"),
    TRANSFER("transfer", "Account Transfer", "Transfert de compte"),
    OPENING_BALANCE("opening_balance", "Opening Balance", "Solde d'ouverture"),
    REFUND_INCOME("refund_income", "Sales Refund", "Remboursement client"),
    REFUND_EXPENSE("refund_expense", "Supplier Refund", "Remboursement fournisseur"),
    CREDIT_SALE("credit_sale", "Credit Sale (Receivable)", "Vente à crédit (Créance)"),
    RECEIVABLE_PAYMENT("receivable_payment", "Customer Payment Received", "Encaissement client"),
    CREDIT_PURCHASE("credit_purchase", "Credit Purchase (Payable)", "Achat à crédit (Dette)"),
    PAYABLE_PAYMENT("payable_payment", "Supplier Payment Sent", "Règlement fournisseur");

    companion object {
        fun fromWire(raw: String?): TransactionType =
            entries.firstOrNull { it.wireValue == raw } ?: EXPENSE
    }
}

enum class TransactionStatus(val wireValue: String) {
    POSTED("posted"),
    VOIDED("voided");

    companion object {
        fun fromWire(raw: String?): TransactionStatus =
            entries.firstOrNull { it.wireValue == raw } ?: POSTED
    }
}

enum class ThemePreference(val wireValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromWire(raw: String?): ThemePreference =
            entries.firstOrNull { it.wireValue == raw } ?: SYSTEM
    }
}

enum class LocaleCode(val wireValue: String) {
    EN("en"),
    FR("fr");

    companion object {
        fun fromWire(raw: String?): LocaleCode =
            entries.firstOrNull { it.wireValue == raw } ?: EN
    }
}

enum class DateFilterPreset {
    TODAY,
    WEEK,
    MONTH,
    YEAR,
    ALL,
    CUSTOM
}

enum class TransactionTypeFilter {
    ALL,
    INCOME_GROUP,
    EXPENSE_GROUP,
    TRANSFER_ONLY
}

enum class TransactionSortMode {
    DATE_DESC,
    DATE_ASC,
    AMOUNT_DESC,
    AMOUNT_ASC
}

object BlueprintConstraints {
    const val ID_MAX_LENGTH = 128
    val ID_REGEX = Regex("^[a-zA-Z0-9_\\-]+$")
    val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val HEX_COLOR_REGEX = Regex("^#[0-9a-fA-F]{6}$")
    const val BUSINESS_NAME_MAX = 120
    const val BUSINESS_ADDRESS_MAX = 240
    const val BUSINESS_PHONE_MAX = 40
    const val ACCOUNT_NAME_MAX = 80
    const val CATEGORY_NAME_MAX = 64
    const val PARTY_NAME_MAX = 100
    const val PARTY_PHONE_MAX = 40
    const val PARTY_ADDRESS_MAX = 240
    const val PARTY_NOTES_MAX = 500
    const val TX_DESCRIPTION_MAX = 200
    const val TX_REFERENCE_MAX = 64
    const val TX_NOTES_MAX = 600
    const val TX_ATTACHMENT_NAME_MAX = 120
    const val TX_ATTACHMENT_MIME_MAX = 64
    const val TX_VOID_REASON_MAX = 240
    const val AUDIT_SUMMARY_MAX = 300
    const val AUDIT_STATE_MAX = 500
    const val MAX_MINOR_UNITS = 99_999_999_999_999L
}

data class Business(
    val id: String,
    val ownerId: String,
    val name: String,
    val defaultCurrency: CurrencyCode,
    val address: String = "",
    val phone: String = "",
    val fiscalStartMonth: Int = 1,
    val createdAt: String,
    val updatedAt: String
)

data class Account(
    val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val accountType: AccountType,
    val currency: CurrencyCode,
    val openingBalanceMinor: Long,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val createdAt: String,
    val updatedAt: String
)

data class Category(
    val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val kind: CategoryKind,
    val color: String,
    val icon: String,
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

data class Party(
    val id: String,
    val businessId: String,
    val ownerId: String,
    val name: String,
    val partyType: PartyType,
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val currency: CurrencyCode,
    val openingReceivableMinor: Long = 0L,
    val openingPayableMinor: Long = 0L,
    val dueDate: String = "",
    val status: PartyStatus = PartyStatus.ACTIVE,
    val createdAt: String,
    val updatedAt: String
)

data class CashbookTransaction(
    val id: String,
    val businessId: String,
    val ownerId: String,
    val idempotencyKey: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val convertedAmountMinor: Long = 0L,
    val toCurrency: String = "",
    val exchangeRateScaled: Long = 0L,
    val feeMinor: Long = 0L,
    val date: String,
    val time: String,
    val description: String,
    val accountId: String = "",
    val toAccountId: String = "",
    val categoryId: String = "",
    val categoryLabelSnapshot: String = "",
    val partyId: String = "",
    val partyNameSnapshot: String = "",
    val referenceNumber: String = "",
    val notes: String = "",
    val attachmentName: String = "",
    val attachmentMime: String = "",
    val receiptLocalPath: String = "",
    val dueDate: String = "",
    val status: TransactionStatus = TransactionStatus.POSTED,
    val voidReason: String = "",
    val createdByUid: String,
    val createdAt: String,
    val updatedAt: String,
    val pendingCloudSync: Boolean = true
)

data class AuditEvent(
    val id: String,
    val businessId: String,
    val ownerId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val actorUid: String,
    val actorName: String,
    val summary: String,
    val previousSummary: String = "",
    val newSummary: String = "",
    val reason: String = "",
    val createdAt: String,
    val pendingCloudSync: Boolean = true
)

data class DateRange(
    val startDate: String = "",
    val endDate: String = ""
)

data class CurrencyPeriodSummary(
    val currency: CurrencyCode,
    val currentCashBalanceMinor: Long,
    val periodIncomeMinor: Long,
    val periodExpenseMinor: Long,
    val netIncomeMinor: Long,
    val periodCashInflowMinor: Long,
    val periodCashOutflowMinor: Long,
    val netCashMovementMinor: Long,
    val totalReceivablesMinor: Long,
    val totalPayablesMinor: Long,
    val accountCount: Int
)

data class PartyComputedBalance(
    val party: Party,
    val receivableByCurrency: Map<CurrencyCode, Long>,
    val payableByCurrency: Map<CurrencyCode, Long>,
    val transactionCount: Int
)

data class CategoryBreakdownItem(
    val categoryId: String,
    val categoryName: String,
    val color: String,
    val amountMinor: Long,
    val percentage: Double,
    val count: Int
)

data class TimeSeriesPoint(
    val bucketKey: String,
    val label: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netCashMinor: Long
)

data class TransactionFilterQuery(
    val search: String = "",
    val dateRange: DateRange? = null,
    val typeFilter: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val specificType: TransactionType? = null,
    val categoryId: String = "",
    val accountId: String = "",
    val partyId: String = "",
    val currency: CurrencyCode? = null,
    val includeVoided: Boolean = false,
    val sortBy: TransactionSortMode = TransactionSortMode.DATE_DESC
)

data class BackupArchive(
    val schemaVersion: Int = 1,
    val application: String = "VUMIQ Cashbook",
    val exportedAt: String,
    val checksum: String,
    val business: Business,
    val accounts: List<Account>,
    val categories: List<Category>,
    val parties: List<Party>,
    val transactions: List<CashbookTransaction>,
    val auditEvents: List<AuditEvent>
)
