package com.vumiq.cashbook.domain

import java.math.BigInteger
import kotlin.math.abs

enum class AmountErrorCode {
    EMPTY,
    MALFORMED,
    NON_POSITIVE,
    NEGATIVE,
    PRECISION_EXCEEDED,
    OVERFLOW
}

sealed interface ParsedAmountOutcome {
    data class Success(val minorUnits: Long, val normalizedInput: String) : ParsedAmountOutcome
    data class Failure(val errorCode: AmountErrorCode, val message: String) : ParsedAmountOutcome
}

sealed interface ExchangeRateOutcome {
    data class Success(val rateScaled: Long) : ExchangeRateOutcome
    data class Failure(val message: String) : ExchangeRateOutcome
}

object MoneyMath {

    private val SCIENTIFIC_REGEX = Regex("[eE]")
    private val NUMERIC_REGEX = Regex("^\\d+(\\.\\d+)?$")
    private val FX_RATE_REGEX = Regex("^\\d+(\\.\\d{1,6})?$")
    private val MAX_MINOR_BIG = BigInteger.valueOf(BlueprintConstraints.MAX_MINOR_UNITS)
    private val MILLION_BIG = BigInteger.valueOf(1_000_000L)
    private val MAX_FX_SCALED_BIG = BigInteger.valueOf(999_999_999_999_999L)

    /**
     * Parses a user-entered monetary string into exact integer minor units without binary floating-point math.
     * Never silently truncates or rounds decimals exceeding the currency's permitted precision.
     */
    fun parseAmountToMinor(
        rawInput: String,
        currency: CurrencyCode,
        allowZero: Boolean = false
    ): ParsedAmountOutcome {
        val trimmed = rawInput.trim().replace(Regex("\\s+"), "")
        if (trimmed.isEmpty()) {
            return ParsedAmountOutcome.Failure(AmountErrorCode.EMPTY, "Amount is required.")
        }

        if (SCIENTIFIC_REGEX.containsMatchIn(trimmed)) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.MALFORMED,
                "Scientific notation is not permitted for monetary amounts."
            )
        }

        var normalized = if (trimmed.contains(',') && !trimmed.contains('.')) {
            val parts = trimmed.split(',')
            if (parts.size == 2 && parts[1].length <= 2) {
                "${parts[0]}.${parts[1]}"
            } else {
                trimmed.replace(",", "")
            }
        } else {
            trimmed.replace(",", "")
        }

        if (normalized.startsWith('-')) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.NEGATIVE,
                "Negative amounts are not permitted."
            )
        }

        if (normalized.startsWith('+')) {
            normalized = normalized.substring(1)
        }

        if (!NUMERIC_REGEX.matches(normalized)) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.MALFORMED,
                "Enter a valid numeric amount (e.g. 125.50)."
            )
        }

        val dotIdx = normalized.indexOf('.')
        val wholeRaw = if (dotIdx >= 0) normalized.substring(0, dotIdx) else normalized
        val fracRaw = if (dotIdx >= 0) normalized.substring(dotIdx + 1) else ""

        if (fracRaw.length > currency.decimals) {
            val msg = if (currency.decimals == 0) {
                "${currency.code} does not allow fractional decimal places."
            } else {
                "${currency.code} supports at most ${currency.decimals} decimal places."
            }
            return ParsedAmountOutcome.Failure(AmountErrorCode.PRECISION_EXCEEDED, msg)
        }

        val wholeClean = wholeRaw.trimStart('0').ifEmpty { "0" }
        if (wholeClean.length > 12) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.OVERFLOW,
                "Amount exceeds maximum safe ledger limit."
            )
        }

        val paddedFrac = fracRaw.padEnd(currency.decimals, '0')
        val combinedDigits = (wholeClean + paddedFrac).trimStart('0').ifEmpty { "0" }
        val minorBig = BigInteger(combinedDigits)

        if (minorBig > MAX_MINOR_BIG) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.OVERFLOW,
                "Amount exceeds maximum safe ledger limit."
            )
        }

        val minorUnits = minorBig.toLong()
        if (!allowZero && minorUnits <= 0L) {
            return ParsedAmountOutcome.Failure(
                AmountErrorCode.NON_POSITIVE,
                "Amount must be greater than zero."
            )
        }

        val normalizedInput = if (currency.decimals == 0) {
            wholeClean
        } else {
            "$wholeClean.$paddedFrac"
        }

        return ParsedAmountOutcome.Success(minorUnits, normalizedInput)
    }

    /**
     * Converts integer minor units back into an exact editable decimal string (no thousands separators).
     */
    fun minorToInputString(minorUnits: Long, currency: CurrencyCode): String {
        val sign = if (minorUnits < 0L) "-" else ""
        val absVal = abs(minorUnits)
        if (currency.decimals == 0) {
            return "$sign$absVal"
        }
        val whole = absVal / currency.minorFactor
        val frac = (absVal % currency.minorFactor).toString().padStart(currency.decimals, '0')
        return "$sign$whole.$frac"
    }

    /**
     * Formats integer minor units as an unambiguous, locale-aware currency string using exact integer math.
     */
    fun formatMinorAmount(
        minorUnits: Long,
        currency: CurrencyCode,
        locale: LocaleCode = LocaleCode.EN,
        showPlusSign: Boolean = false
    ): String {
        val isNegative = minorUnits < 0L
        val absVal = abs(minorUnits)
        val whole = if (currency.decimals == 0) absVal else absVal / currency.minorFactor
        val frac = if (currency.decimals == 0) {
            ""
        } else {
            (absVal % currency.minorFactor).toString().padStart(currency.decimals, '0')
        }

        val groupSep = if (locale == LocaleCode.FR) "\u202F" else ","
        val decSep = if (locale == LocaleCode.FR) "," else "."

        val wholeFormatted = formatGroupingDigits(whole.toString(), groupSep)
        val numberPart = if (currency.decimals == 0) {
            wholeFormatted
        } else {
            "$wholeFormatted$decSep$frac"
        }

        val signPrefix = when {
            isNegative -> "-"
            showPlusSign && minorUnits > 0L -> "+"
            else -> ""
        }

        return if (locale == LocaleCode.FR) {
            "$signPrefix$numberPart ${currency.code}"
        } else {
            "$signPrefix${currency.code} $numberPart"
        }
    }

    private fun formatGroupingDigits(digits: String, separator: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        var count = 0
        for (i in digits.length - 1 downTo 0) {
            sb.append(digits[i])
            count++
            if (count == 3 && i > 0) {
                sb.append(separator.reversed())
                count = 0
            }
        }
        return sb.reverse().toString()
    }

    /**
     * Parses an exchange rate string into a scaled integer (rate * 1,000,000) without floating-point drift.
     */
    fun parseExchangeRateScaled(rawRate: String): ExchangeRateOutcome {
        val cleaned = rawRate.trim().replace(',', '.')
        if (!FX_RATE_REGEX.matches(cleaned)) {
            return ExchangeRateOutcome.Failure(
                "Enter a positive exchange rate with up to 6 decimal places."
            )
        }
        val parts = cleaned.split('.')
        val whole = parts[0]
        val frac = if (parts.size > 1) parts[1] else ""
        val scaledBig = BigInteger(whole + frac.padEnd(6, '0'))
        if (scaledBig <= BigInteger.ZERO || scaledBig > MAX_FX_SCALED_BIG) {
            return ExchangeRateOutcome.Failure("Exchange rate is out of bounds.")
        }
        return ExchangeRateOutcome.Success(scaledBig.toLong())
    }

    /**
     * Converts source integer minor units to target integer minor units using an explicit scaled rate.
     * Performs half-up integer rounding in BigInteger space only at the final minor-unit boundary.
     */
    fun convertMinorWithScaledRate(
        sourceMinor: Long,
        sourceCurrency: CurrencyCode,
        targetCurrency: CurrencyCode,
        exchangeRateScaled: Long
    ): Long {
        if (sourceCurrency == targetCurrency) return sourceMinor
        val numerator = BigInteger.valueOf(sourceMinor)
            .multiply(BigInteger.valueOf(targetCurrency.minorFactor))
            .multiply(BigInteger.valueOf(exchangeRateScaled))
        val denominator = BigInteger.valueOf(sourceCurrency.minorFactor).multiply(MILLION_BIG)
        val halfDenominator = denominator.divide(BigInteger.TWO)
        return numerator.add(halfDenominator).divide(denominator).toLong()
    }
}
