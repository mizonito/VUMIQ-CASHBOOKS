package com.vumiq.cashbook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.roundToLong

sealed interface DraftValidationOutcome {
    data object Valid : DraftValidationOutcome
    data class Invalid(val message: String) : DraftValidationOutcome
}

data class TransactionDraftInput(
    val type: TransactionType,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val description: String,
    val date: String,
    val time: String,
    val accountId: String = "",
    val toAccountId: String = "",
    val categoryId: String = "",
    val partyId: String = "",
    val convertedAmountMinor: Long = 0L,
    val toCurrency: String = "",
    val exchangeRateScaled: Long = 0L,
    val feeMinor: Long = 0L,
    val referenceNumber: String = "",
    val notes: String = "",
    val dueDate: String = "",
    val attachmentSourceUri: String = "",
    val attachmentDisplayName: String = ""
)

object AccountingEngine {

    fun createDefaultCategories(
        businessId: String,
        ownerId: String,
        nowIso: String
    ): List<Category> {
        data class Seed(
            val suffix: String,
            val name: String,
            val kind: CategoryKind,
            val color: String,
            val icon: String
        )

        val seeds = listOf(
            Seed("sales", "Sales", CategoryKind.INCOME, "#168653", "shopping-bag"),
            Seed("services", "Services", CategoryKind.INCOME, "#20A56B", "briefcase"),
            Seed("biz_income", "Business income", CategoryKind.INCOME, "#0E7490", "trending-up"),
            Seed("other_income", "Other income", CategoryKind.INCOME, "#4F46E5", "plus-circle"),
            Seed("stock", "Stock purchases", CategoryKind.EXPENSE, "#D94848", "package"),
            Seed("rent", "Rent", CategoryKind.EXPENSE, "#B45309", "building"),
            Seed("salaries", "Salaries", CategoryKind.EXPENSE, "#9333EA", "users"),
            Seed("transport", "Transport", CategoryKind.EXPENSE, "#C88719", "truck"),
            Seed("utilities", "Utilities", CategoryKind.EXPENSE, "#0284C7", "zap"),
            Seed("supplies", "Supplies", CategoryKind.EXPENSE, "#BE123C", "clipboard"),
            Seed("maintenance", "Maintenance", CategoryKind.EXPENSE, "#6D28D9", "wrench"),
            Seed("other_expense", "Other expenses", CategoryKind.EXPENSE, "#64748B", "minus-circle")
        )

        return seeds.map { item ->
            Category(
                id = "cat_${businessId}_${item.suffix}".take(120),
                businessId = businessId,
                ownerId = ownerId,
                name = item.name,
                kind = item.kind,
                color = item.color,
                icon = item.icon,
                archived = false,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        }
    }

    fun resolveDateRangePreset(
        preset: DateFilterPreset,
        referenceDateIso: String,
        customRange: DateRange? = null
    ): DateRange {
        if (preset == DateFilterPreset.ALL) return DateRange("", "")
        if (preset == DateFilterPreset.CUSTOM) return customRange ?: DateRange("", "")

        val refDate = LocalDate.parse(referenceDateIso)
        return when (preset) {
            DateFilterPreset.TODAY -> DateRange(referenceDateIso, referenceDateIso)
            DateFilterPreset.WEEK -> {
                val start = refDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = start.plusDays(6)
                DateRange(start.toString(), end.toString())
            }
            DateFilterPreset.MONTH -> {
                val start = refDate.withDayOfMonth(1)
                val end = refDate.withDayOfMonth(refDate.lengthOfMonth())
                DateRange(start.toString(), end.toString())
            }
            DateFilterPreset.YEAR -> {
                DateRange("${refDate.year}-01-01", "${refDate.year}-12-31")
            }
            else -> DateRange("", "")
        }
    }

    fun isDateWithinRange(dateStr: String, range: DateRange?): Boolean {
        if (range == null) return true
        if (range.startDate.isNotEmpty() && dateStr < range.startDate) return false
        if (range.endDate.isNotEmpty() && dateStr > range.endDate) return false
        return true
    }

    /**
     * Computes exact current minor-unit balance for every account:
     * Current Balance = Account.openingBalanceMinor + Posted Inflows - Posted Outflows
     */
    fun computeAccountBalances(
        accounts: List<Account>,
        transactions: List<CashbookTransaction>,
        upToDateInclusive: String? = null
    ): Map<String, Long> {
        val balances = LinkedHashMap<String, Long>(accounts.size)
        for (acc in accounts) {
            balances[acc.id] = acc.openingBalanceMinor
        }

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            if (!upToDateInclusive.isNullOrEmpty() && tx.date > upToDateInclusive) continue

            when (tx.type) {
                TransactionType.INCOME,
                TransactionType.OPENING_BALANCE,
                TransactionType.REFUND_EXPENSE,
                TransactionType.RECEIVABLE_PAYMENT -> {
                    val cur = balances[tx.accountId]
                    if (tx.accountId.isNotEmpty() && cur != null) {
                        balances[tx.accountId] = cur + tx.amountMinor
                    }
                }

                TransactionType.EXPENSE,
                TransactionType.REFUND_INCOME,
                TransactionType.PAYABLE_PAYMENT -> {
                    val cur = balances[tx.accountId]
                    if (tx.accountId.isNotEmpty() && cur != null) {
                        balances[tx.accountId] = cur - tx.amountMinor
                    }
                }

                TransactionType.TRANSFER -> {
                    val srcCur = balances[tx.accountId]
                    if (tx.accountId.isNotEmpty() && srcCur != null) {
                        balances[tx.accountId] = srcCur - (tx.amountMinor + tx.feeMinor)
                    }
                    val dstCur = balances[tx.toAccountId]
                    if (tx.toAccountId.isNotEmpty() && dstCur != null) {
                        val creditAmt = if (
                            tx.toCurrency.isNotEmpty() &&
                            tx.toCurrency != tx.currency.code &&
                            tx.convertedAmountMinor > 0L
                        ) {
                            tx.convertedAmountMinor
                        } else {
                            tx.amountMinor
                        }
                        balances[tx.toAccountId] = dstCur + creditAmt
                    }
                }

                TransactionType.CREDIT_SALE,
                TransactionType.CREDIT_PURCHASE -> {
                    // Non-cash invoice/credit events affect party subledgers & P&L, not cash accounts
                }
            }
        }

        return balances
    }

    /**
     * Computes exact receivables and payables per party and per currency without double-counting settlements.
     */
    fun computePartyBalances(
        parties: List<Party>,
        transactions: List<CashbookTransaction>
    ): Map<String, PartyComputedBalance> {
        class MutablePartyBucket(val party: Party) {
            val rec = LinkedHashMap<CurrencyCode, Long>()
            val pay = LinkedHashMap<CurrencyCode, Long>()
            var txCount = 0
        }

        val buckets = LinkedHashMap<String, MutablePartyBucket>(parties.size)
        for (party in parties) {
            val bucket = MutablePartyBucket(party)
            if (party.openingReceivableMinor > 0L) {
                bucket.rec[party.currency] = party.openingReceivableMinor
            }
            if (party.openingPayableMinor > 0L) {
                bucket.pay[party.currency] = party.openingPayableMinor
            }
            buckets[party.id] = bucket
        }

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED || tx.partyId.isEmpty()) continue
            val bucket = buckets[tx.partyId] ?: continue
            bucket.txCount += 1
            val cur = tx.currency

            when (tx.type) {
                TransactionType.CREDIT_SALE -> {
                    bucket.rec[cur] = (bucket.rec[cur] ?: 0L) + tx.amountMinor
                }
                TransactionType.RECEIVABLE_PAYMENT -> {
                    bucket.rec[cur] = max(0L, (bucket.rec[cur] ?: 0L) - tx.amountMinor)
                }
                TransactionType.CREDIT_PURCHASE -> {
                    bucket.pay[cur] = (bucket.pay[cur] ?: 0L) + tx.amountMinor
                }
                TransactionType.PAYABLE_PAYMENT -> {
                    bucket.pay[cur] = max(0L, (bucket.pay[cur] ?: 0L) - tx.amountMinor)
                }
                else -> Unit
            }
        }

        return buckets.mapValues { (_, b) ->
            PartyComputedBalance(
                party = b.party,
                receivableByCurrency = b.rec,
                payableByCurrency = b.pay,
                transactionCount = b.txCount
            )
        }
    }

    /**
     * Computes financial summaries strictly isolated by currency (NEVER sums USD + CDF).
     */
    fun computePeriodSummariesByCurrency(
        accounts: List<Account>,
        parties: List<Party>,
        transactions: List<CashbookTransaction>,
        dateRange: DateRange? = null,
        fallbackCurrency: CurrencyCode? = null
    ): Map<CurrencyCode, CurrencyPeriodSummary> {
        val accountBalances = computeAccountBalances(accounts, transactions)
        val partyBalances = computePartyBalances(parties, transactions)

        val activeCurrencies = LinkedHashSet<CurrencyCode>()
        if (fallbackCurrency != null) activeCurrencies.add(fallbackCurrency)
        for (acc in accounts) {
            if (acc.status == AccountStatus.ACTIVE) activeCurrencies.add(acc.currency)
        }
        for (party in parties) {
            if (party.openingReceivableMinor > 0L || party.openingPayableMinor > 0L) {
                activeCurrencies.add(party.currency)
            }
        }
        for (tx in transactions) {
            if (tx.status == TransactionStatus.POSTED) {
                activeCurrencies.add(tx.currency)
                CurrencyCode.fromCodeOrNull(tx.toCurrency)?.let { activeCurrencies.add(it) }
            }
        }

        val summaries = LinkedHashMap<CurrencyCode, CurrencyPeriodSummary>()

        for (cur in activeCurrencies) {
            var currentCashBalanceMinor = 0L
            var accountCount = 0
            for (acc in accounts) {
                if (acc.currency == cur && acc.status == AccountStatus.ACTIVE) {
                    currentCashBalanceMinor += accountBalances[acc.id] ?: 0L
                    accountCount += 1
                }
            }

            var totalReceivablesMinor = 0L
            var totalPayablesMinor = 0L
            for (pb in partyBalances.values) {
                if (pb.party.status == PartyStatus.ACTIVE) {
                    totalReceivablesMinor += pb.receivableByCurrency[cur] ?: 0L
                    totalPayablesMinor += pb.payableByCurrency[cur] ?: 0L
                }
            }

            var periodIncomeMinor = 0L
            var periodExpenseMinor = 0L
            var periodCashInflowMinor = 0L
            var periodCashOutflowMinor = 0L

            for (tx in transactions) {
                if (tx.status != TransactionStatus.POSTED) continue
                if (!isDateWithinRange(tx.date, dateRange)) continue

                if (tx.currency == cur) {
                    when (tx.type) {
                        TransactionType.INCOME -> {
                            periodIncomeMinor += tx.amountMinor
                            periodCashInflowMinor += tx.amountMinor
                        }
                        TransactionType.EXPENSE -> {
                            periodExpenseMinor += tx.amountMinor
                            periodCashOutflowMinor += tx.amountMinor
                        }
                        TransactionType.REFUND_INCOME -> {
                            periodIncomeMinor -= tx.amountMinor
                            periodCashOutflowMinor += tx.amountMinor
                        }
                        TransactionType.REFUND_EXPENSE -> {
                            periodExpenseMinor -= tx.amountMinor
                            periodCashInflowMinor += tx.amountMinor
                        }
                        TransactionType.CREDIT_SALE -> {
                            periodIncomeMinor += tx.amountMinor
                        }
                        TransactionType.RECEIVABLE_PAYMENT -> {
                            periodCashInflowMinor += tx.amountMinor
                        }
                        TransactionType.CREDIT_PURCHASE -> {
                            periodExpenseMinor += tx.amountMinor
                        }
                        TransactionType.PAYABLE_PAYMENT -> {
                            periodCashOutflowMinor += tx.amountMinor
                        }
                        TransactionType.OPENING_BALANCE -> {
                            // Capital baseline, never counted as earned income or period cash inflow
                        }
                        TransactionType.TRANSFER -> {
                            if (tx.feeMinor > 0L) {
                                periodExpenseMinor += tx.feeMinor
                                periodCashOutflowMinor += tx.feeMinor
                            }
                            if (tx.toCurrency.isNotEmpty() && tx.toCurrency != cur.code) {
                                periodCashOutflowMinor += tx.amountMinor
                            }
                        }
                    }
                } else if (
                    tx.type == TransactionType.TRANSFER &&
                    tx.toCurrency == cur.code &&
                    tx.convertedAmountMinor > 0L
                ) {
                    periodCashInflowMinor += tx.convertedAmountMinor
                }
            }

            summaries[cur] = CurrencyPeriodSummary(
                currency = cur,
                currentCashBalanceMinor = currentCashBalanceMinor,
                periodIncomeMinor = periodIncomeMinor,
                periodExpenseMinor = periodExpenseMinor,
                netIncomeMinor = periodIncomeMinor - periodExpenseMinor,
                periodCashInflowMinor = periodCashInflowMinor,
                periodCashOutflowMinor = periodCashOutflowMinor,
                netCashMovementMinor = periodCashInflowMinor - periodCashOutflowMinor,
                totalReceivablesMinor = totalReceivablesMinor,
                totalPayablesMinor = totalPayablesMinor,
                accountCount = accountCount
            )
        }

        return summaries
    }

    fun computeCategoryBreakdown(
        categories: List<Category>,
        transactions: List<CashbookTransaction>,
        currency: CurrencyCode,
        kind: CategoryKind,
        dateRange: DateRange? = null
    ): List<CategoryBreakdownItem> {
        val catMap = categories.associateBy { it.id }

        class Acc(val name: String, val color: String, var amountMinor: Long = 0L, var count: Int = 0)

        val totals = LinkedHashMap<String, Acc>()

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED || tx.currency != currency) continue
            if (!isDateWithinRange(tx.date, dateRange)) continue

            val contribution = if (kind == CategoryKind.INCOME) {
                when (tx.type) {
                    TransactionType.INCOME, TransactionType.CREDIT_SALE -> tx.amountMinor
                    TransactionType.REFUND_INCOME -> -tx.amountMinor
                    else -> 0L
                }
            } else {
                when (tx.type) {
                    TransactionType.EXPENSE, TransactionType.CREDIT_PURCHASE -> tx.amountMinor
                    TransactionType.REFUND_EXPENSE -> -tx.amountMinor
                    else -> 0L
                }
            }

            if (contribution == 0L) continue

            val cat = if (tx.categoryId.isNotEmpty()) catMap[tx.categoryId] else null
            val key = tx.categoryId.ifEmpty { "__uncategorized__" }
            val label = cat?.name ?: tx.categoryLabelSnapshot.ifEmpty { "Uncategorized" }
            val color = cat?.color ?: if (kind == CategoryKind.INCOME) "#168653" else "#D94848"

            val item = totals.getOrPut(key) { Acc(label, color) }
            item.amountMinor += contribution
            item.count += 1
        }

        val positive = totals.entries
            .filter { it.value.amountMinor > 0L }
            .sortedByDescending { it.value.amountMinor }

        val grandTotal = positive.sumOf { it.value.amountMinor }
        return positive.map { (catId, item) ->
            val pct = if (grandTotal > 0L) {
                ((item.amountMinor.toDouble() * 1000.0) / grandTotal.toDouble()).roundToLong() / 10.0
            } else {
                0.0
            }
            CategoryBreakdownItem(
                categoryId = catId,
                categoryName = item.name,
                color = item.color,
                amountMinor = item.amountMinor,
                percentage = pct,
                count = item.count
            )
        }
    }

    fun computeTimeSeriesData(
        transactions: List<CashbookTransaction>,
        currency: CurrencyCode,
        dateRange: DateRange? = null,
        monthlyGranularity: Boolean = false
    ): List<TimeSeriesPoint> {
        class Bucket(
            val key: String,
            var incomeMinor: Long = 0L,
            var expenseMinor: Long = 0L,
            var netCashMinor: Long = 0L
        )

        val buckets = LinkedHashMap<String, Bucket>()
        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED || tx.currency != currency) continue
            if (!isDateWithinRange(tx.date, dateRange)) continue

            val key = if (monthlyGranularity && tx.date.length >= 7) tx.date.substring(0, 7) else tx.date
            val b = buckets.getOrPut(key) { Bucket(key) }

            when (tx.type) {
                TransactionType.INCOME -> {
                    b.incomeMinor += tx.amountMinor
                    b.netCashMinor += tx.amountMinor
                }
                TransactionType.CREDIT_SALE -> b.incomeMinor += tx.amountMinor
                TransactionType.REFUND_INCOME -> {
                    b.incomeMinor -= tx.amountMinor
                    b.netCashMinor -= tx.amountMinor
                }
                TransactionType.EXPENSE -> {
                    b.expenseMinor += tx.amountMinor
                    b.netCashMinor -= tx.amountMinor
                }
                TransactionType.CREDIT_PURCHASE -> b.expenseMinor += tx.amountMinor
                TransactionType.REFUND_EXPENSE -> {
                    b.expenseMinor -= tx.amountMinor
                    b.netCashMinor += tx.amountMinor
                }
                TransactionType.RECEIVABLE_PAYMENT -> b.netCashMinor += tx.amountMinor
                TransactionType.PAYABLE_PAYMENT -> b.netCashMinor -= tx.amountMinor
                TransactionType.TRANSFER -> {
                    if (tx.feeMinor > 0L) {
                        b.expenseMinor += tx.feeMinor
                        b.netCashMinor -= tx.feeMinor
                    }
                }
                TransactionType.OPENING_BALANCE -> Unit
            }
        }

        return buckets.values
            .sortedBy { it.key }
            .map {
                TimeSeriesPoint(
                    bucketKey = it.key,
                    label = it.key,
                    incomeMinor = it.incomeMinor,
                    expenseMinor = it.expenseMinor,
                    netCashMinor = it.netCashMinor
                )
            }
    }

    fun filterAndSearchTransactions(
        transactions: List<CashbookTransaction>,
        accounts: List<Account>,
        categories: List<Category>,
        parties: List<Party>,
        query: TransactionFilterQuery
    ): List<CashbookTransaction> {
        val accMap = accounts.associateBy { it.id }
        val catMap = categories.associateBy { it.id }
        val partyMap = parties.associateBy { it.id }
        val searchNorm = normalizeSearchToken(query.search)
        val seenIds = HashSet<String>()
        val results = ArrayList<CashbookTransaction>()

        for (tx in transactions) {
            if (!seenIds.add(tx.id)) continue
            if (!query.includeVoided && tx.status == TransactionStatus.VOIDED) continue
            if (!isDateWithinRange(tx.date, query.dateRange)) continue

            if (query.currency != null && tx.currency != query.currency && tx.toCurrency != query.currency.code) {
                continue
            }

            if (query.specificType != null && tx.type != query.specificType) continue

            when (query.typeFilter) {
                TransactionTypeFilter.ALL -> Unit
                TransactionTypeFilter.INCOME_GROUP -> {
                    if (tx.type !in setOf(
                            TransactionType.INCOME,
                            TransactionType.CREDIT_SALE,
                            TransactionType.RECEIVABLE_PAYMENT,
                            TransactionType.REFUND_EXPENSE
                        )
                    ) continue
                }
                TransactionTypeFilter.EXPENSE_GROUP -> {
                    if (tx.type !in setOf(
                            TransactionType.EXPENSE,
                            TransactionType.CREDIT_PURCHASE,
                            TransactionType.PAYABLE_PAYMENT,
                            TransactionType.REFUND_INCOME
                        )
                    ) continue
                }
                TransactionTypeFilter.TRANSFER_ONLY -> {
                    if (tx.type != TransactionType.TRANSFER) continue
                }
            }

            if (query.categoryId.isNotEmpty() && tx.categoryId != query.categoryId) continue
            if (query.accountId.isNotEmpty() && tx.accountId != query.accountId && tx.toAccountId != query.accountId) {
                continue
            }
            if (query.partyId.isNotEmpty() && tx.partyId != query.partyId) continue

            if (searchNorm.isNotEmpty()) {
                val accName = accMap[tx.accountId]?.name.orEmpty()
                val toAccName = accMap[tx.toAccountId]?.name.orEmpty()
                val catName = catMap[tx.categoryId]?.name ?: tx.categoryLabelSnapshot
                val partyName = partyMap[tx.partyId]?.name ?: tx.partyNameSnapshot
                val haystack = normalizeSearchToken(
                    listOf(
                        tx.description,
                        tx.referenceNumber,
                        catName,
                        partyName,
                        accName,
                        toAccName,
                        tx.notes
                    ).joinToString(" ")
                )
                if (!haystack.contains(searchNorm)) continue
            }

            results.add(tx)
        }

        val comparator = when (query.sortBy) {
            TransactionSortMode.AMOUNT_DESC -> compareByDescending<CashbookTransaction> { it.amountMinor }
                .thenByDescending { it.date }
            TransactionSortMode.AMOUNT_ASC -> compareBy<CashbookTransaction> { it.amountMinor }
                .thenBy { it.date }
            TransactionSortMode.DATE_ASC -> compareBy<CashbookTransaction> { "${it.date}T${it.time}Z_${it.createdAt}_${it.id}" }
            TransactionSortMode.DATE_DESC -> compareByDescending<CashbookTransaction> { "${it.date}T${it.time}Z_${it.createdAt}_${it.id}" }
        }

        return results.sortedWith(comparator)
    }

    private fun normalizeSearchToken(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun validateTransactionDraft(
        draft: TransactionDraftInput,
        accounts: List<Account>,
        parties: List<Party>
    ): DraftValidationOutcome {
        if (draft.amountMinor <= 0L || draft.amountMinor > BlueprintConstraints.MAX_MINOR_UNITS) {
            return DraftValidationOutcome.Invalid("Transaction amount must be greater than zero.")
        }
        val trimmedDesc = draft.description.trim()
        if (trimmedDesc.isEmpty() || trimmedDesc.length > BlueprintConstraints.TX_DESCRIPTION_MAX) {
            return DraftValidationOutcome.Invalid("Description is required (1–200 characters).")
        }
        if (!BlueprintConstraints.DATE_REGEX.matches(draft.date)) {
            return DraftValidationOutcome.Invalid("Valid YYYY-MM-DD date is required.")
        }

        val requiresCashAccount = draft.type !in setOf(
            TransactionType.CREDIT_SALE,
            TransactionType.CREDIT_PURCHASE
        )
        if (requiresCashAccount) {
            val srcAcc = accounts.firstOrNull { it.id == draft.accountId && it.status == AccountStatus.ACTIVE }
                ?: return DraftValidationOutcome.Invalid("Select an active payment account.")
            if (srcAcc.currency != draft.currency) {
                return DraftValidationOutcome.Invalid(
                    "Transaction currency (${draft.currency.code}) must match source account currency (${srcAcc.currency.code})."
                )
            }
        }

        if (draft.type == TransactionType.TRANSFER) {
            if (draft.toAccountId.isEmpty() || draft.toAccountId == draft.accountId) {
                return DraftValidationOutcome.Invalid("Transfers require two distinct accounts.")
            }
            val dstAcc = accounts.firstOrNull { it.id == draft.toAccountId && it.status == AccountStatus.ACTIVE }
                ?: return DraftValidationOutcome.Invalid("Select an active destination account.")
            if (dstAcc.currency != draft.currency) {
                if (draft.convertedAmountMinor <= 0L || draft.exchangeRateScaled <= 0L) {
                    return DraftValidationOutcome.Invalid(
                        "Cross-currency transfers require an explicit positive exchange rate."
                    )
                }
            }
        }

        if (draft.type in setOf(
                TransactionType.CREDIT_SALE,
                TransactionType.RECEIVABLE_PAYMENT,
                TransactionType.CREDIT_PURCHASE,
                TransactionType.PAYABLE_PAYMENT
            )
        ) {
            val party = parties.firstOrNull { it.id == draft.partyId && it.status == PartyStatus.ACTIVE }
            if (party == null) {
                return DraftValidationOutcome.Invalid(
                    "Select a customer or supplier party for credit or settlement workflows."
                )
            }
        }

        return DraftValidationOutcome.Valid
    }

    /**
     * Exact FNV-1a 32-bit unsigned checksum identical byte-for-byte with VUMIQ Cashbook Web archive format.
     */
    fun computeBackupChecksum(
        businessId: String,
        accountCount: Int,
        transactionCount: Int,
        totalPostedMinorSum: Long
    ): String {
        val raw = "$businessId|acc:$accountCount|tx:$transactionCount|sum:$totalPostedMinorSum"
        var hash = 2166136261L.toInt()
        for (ch in raw) {
            hash = hash xor ch.code
            hash *= 16777619
        }
        val unsignedHex = (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        return "vumiq_v1_$unsignedHex"
    }
}
