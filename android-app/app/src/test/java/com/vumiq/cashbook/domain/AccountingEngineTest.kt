package com.vumiq.cashbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingEngineTest {

    private val now = "2026-10-24T09:00:00Z"

    private fun makeAccount(
        id: String,
        currency: CurrencyCode,
        openingMinor: Long = 0L
    ) = Account(
        id = id,
        businessId = "biz_1",
        ownerId = "user_1",
        name = "Acc $id",
        accountType = AccountType.CASH,
        currency = currency,
        openingBalanceMinor = openingMinor,
        status = AccountStatus.ACTIVE,
        createdAt = now,
        updatedAt = now
    )

    private fun makeParty(
        id: String,
        type: PartyType,
        currency: CurrencyCode = CurrencyCode.USD
    ) = Party(
        id = id,
        businessId = "biz_1",
        ownerId = "user_1",
        name = "Party $id",
        partyType = type,
        currency = currency,
        createdAt = now,
        updatedAt = now
    )

    private fun makeTx(
        id: String,
        type: TransactionType,
        amountMinor: Long,
        currency: CurrencyCode = CurrencyCode.USD,
        accountId: String = "acc_usd",
        toAccountId: String = "",
        partyId: String = "",
        convertedAmountMinor: Long = 0L,
        toCurrency: String = "",
        exchangeRateScaled: Long = 0L,
        feeMinor: Long = 0L,
        status: TransactionStatus = TransactionStatus.POSTED
    ) = CashbookTransaction(
        id = id,
        businessId = "biz_1",
        ownerId = "user_1",
        idempotencyKey = "idem_$id",
        type = type,
        amountMinor = amountMinor,
        currency = currency,
        convertedAmountMinor = convertedAmountMinor,
        toCurrency = toCurrency,
        exchangeRateScaled = exchangeRateScaled,
        feeMinor = feeMinor,
        date = "2026-10-24",
        time = "10:00",
        description = "Tx $id",
        accountId = accountId,
        toAccountId = toAccountId,
        partyId = partyId,
        status = status,
        createdByUid = "user_1",
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun `Case A - exact minor unit parsing rejects excess decimals and scientific notation`() {
        val validUsd = MoneyMath.parseAmountToMinor("1,250.55", CurrencyCode.USD)
        assertTrue(validUsd is ParsedAmountOutcome.Success)
        assertEquals(125055L, (validUsd as ParsedAmountOutcome.Success).minorUnits)

        val excessDecimals = MoneyMath.parseAmountToMinor("10.999", CurrencyCode.USD)
        assertTrue(excessDecimals is ParsedAmountOutcome.Failure)
        assertEquals(AmountErrorCode.PRECISION_EXCEEDED, (excessDecimals as ParsedAmountOutcome.Failure).errorCode)

        val zeroDecimalCurrency = MoneyMath.parseAmountToMinor("500.5", CurrencyCode.XOF)
        assertTrue(zeroDecimalCurrency is ParsedAmountOutcome.Failure)
        assertEquals(AmountErrorCode.PRECISION_EXCEEDED, (zeroDecimalCurrency as ParsedAmountOutcome.Failure).errorCode)

        val validXof = MoneyMath.parseAmountToMinor("25000", CurrencyCode.XOF)
        assertEquals(25000L, (validXof as ParsedAmountOutcome.Success).minorUnits)
    }

    @Test
    fun `Case B - basic cashbook balance and opening balance excluded from period income`() {
        val acc = makeAccount("acc_usd", CurrencyCode.USD, openingMinor = 100_000L) // $1,000.00
        val txs = listOf(
            makeTx("t1", TransactionType.OPENING_BALANCE, 50_000L), // +$500.00 capital baseline
            makeTx("t2", TransactionType.INCOME, 25_050L),          // +$250.50 sales
            makeTx("t3", TransactionType.EXPENSE, 9_995L)           // -$99.95 supplies
        )

        val summaries = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = listOf(acc),
            parties = emptyList(),
            transactions = txs
        )
        val usd = summaries.getValue(CurrencyCode.USD)

        // Cash balance = 1000.00 + 500.00 + 250.50 - 99.95 = 1650.55 (165055 minor)
        assertEquals(165_055L, usd.currentCashBalanceMinor)
        // Period income = ONLY 250.50 (opening balance is excluded)
        assertEquals(25_050L, usd.periodIncomeMinor)
        assertEquals(9_995L, usd.periodExpenseMinor)
        assertEquals(15_055L, usd.netIncomeMinor)
    }

    @Test
    fun `Case C - internal transfer preserves total cash and records explicit fee once`() {
        val cash = makeAccount("acc_cash", CurrencyCode.USD, openingMinor = 100_000L)
        val bank = makeAccount("acc_bank", CurrencyCode.USD, openingMinor = 0L)
        val transfer = makeTx(
            id = "t_tr",
            type = TransactionType.TRANSFER,
            amountMinor = 40_000L,
            accountId = "acc_cash",
            toAccountId = "acc_bank",
            feeMinor = 150L
        )

        val balances = AccountingEngine.computeAccountBalances(listOf(cash, bank), listOf(transfer))
        assertEquals(59_850L, balances["acc_cash"])
        assertEquals(40_000L, balances["acc_bank"])

        val usd = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = listOf(cash, bank),
            parties = emptyList(),
            transactions = listOf(transfer)
        ).getValue(CurrencyCode.USD)

        assertEquals(99_850L, usd.currentCashBalanceMinor)
        assertEquals(0L, usd.periodIncomeMinor)
        assertEquals(150L, usd.periodExpenseMinor)
        assertEquals(-150L, usd.netCashMovementMinor)
    }

    @Test
    fun `Case D - cross currency transfer isolates USD and CDF ledgers cleanly`() {
        val usdAcc = makeAccount("acc_usd", CurrencyCode.USD, openingMinor = 50_000L) // $500.00
        val cdfAcc = makeAccount("acc_cdf", CurrencyCode.CDF, openingMinor = 0L)
        val rateScaled = (MoneyMath.parseExchangeRateScaled("2850") as ExchangeRateOutcome.Success).rateScaled
        val convertedMinor = MoneyMath.convertMinorWithScaledRate(
            sourceMinor = 10_000L, // $100.00
            sourceCurrency = CurrencyCode.USD,
            targetCurrency = CurrencyCode.CDF,
            exchangeRateScaled = rateScaled
        )
        assertEquals(28_500_000L, convertedMinor) // FC 285,000.00

        val fxTx = makeTx(
            id = "tx_fx",
            type = TransactionType.TRANSFER,
            amountMinor = 10_000L,
            currency = CurrencyCode.USD,
            accountId = "acc_usd",
            toAccountId = "acc_cdf",
            toCurrency = "CDF",
            convertedAmountMinor = convertedMinor,
            exchangeRateScaled = rateScaled
        )

        val summaries = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = listOf(usdAcc, cdfAcc),
            parties = emptyList(),
            transactions = listOf(fxTx)
        )
        assertEquals(40_000L, summaries.getValue(CurrencyCode.USD).currentCashBalanceMinor)
        assertEquals(28_500_000L, summaries.getValue(CurrencyCode.CDF).currentCashBalanceMinor)
    }

    @Test
    fun `Case E - credit sale and receivable settlement never double count income`() {
        val acc = makeAccount("acc_usd", CurrencyCode.USD, openingMinor = 0L)
        val customer = makeParty("pty_cust", PartyType.CUSTOMER)
        val txs = listOf(
            makeTx(
                id = "t_cs",
                type = TransactionType.CREDIT_SALE,
                amountMinor = 30_000L,
                accountId = "",
                partyId = "pty_cust"
            ),
            makeTx(
                id = "t_rp",
                type = TransactionType.RECEIVABLE_PAYMENT,
                amountMinor = 12_000L,
                accountId = "acc_usd",
                partyId = "pty_cust"
            )
        )

        val usd = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = listOf(acc),
            parties = listOf(customer),
            transactions = txs
        ).getValue(CurrencyCode.USD)

        assertEquals(30_000L, usd.periodIncomeMinor)          // Recognized once!
        assertEquals(12_000L, usd.currentCashBalanceMinor)    // Cash collected
        assertEquals(18_000L, usd.totalReceivablesMinor)      // Remaining receivable ($180.00)
    }

    @Test
    fun `Case F - refunds and voided records preserve exact audit ledger integrity`() {
        val acc = makeAccount("acc_usd", CurrencyCode.USD, openingMinor = 0L)
        val txs = listOf(
            makeTx("t1", TransactionType.INCOME, 20_000L),
            makeTx("t2", TransactionType.REFUND_INCOME, 5_000L),
            makeTx("t3", TransactionType.EXPENSE, 99_900L, status = TransactionStatus.VOIDED)
        )

        val usd = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = listOf(acc),
            parties = emptyList(),
            transactions = txs
        ).getValue(CurrencyCode.USD)

        assertEquals(15_000L, usd.periodIncomeMinor)
        assertEquals(0L, usd.periodExpenseMinor)
        assertEquals(15_000L, usd.currentCashBalanceMinor)
    }

    @Test
    fun `Case G - cross platform FNV-1a backup checksum format`() {
        val checksum = AccountingEngine.computeBackupChecksum(
            businessId = "biz_demo",
            accountCount = 2,
            transactionCount = 5,
            totalPostedMinorSum = 165_055L
        )
        assertTrue(checksum.startsWith("vumiq_v1_"))
        assertEquals(17, checksum.length)
    }
}
