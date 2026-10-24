package com.vumiq.cashbook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.domain.CategoryBreakdownItem
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.DateFilterPreset
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.TabularMoneyStyle

@Composable
fun ReportsScreen(
    state: CashbookUiState,
    onSelectDatePreset: (DateFilterPreset) -> Unit,
    onSelectCurrency: (CurrencyCode) -> Unit,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR
    val cur = state.selectedCurrency
    val summary = state.activeSummary

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.availableCurrencies.forEach { c ->
                    FilterChip(
                        selected = c == cur,
                        onClick = { onSelectCurrency(c) },
                        label = { Text(c.code) }
                    )
                }
                listOf(
                    DateFilterPreset.TODAY to (if (isFr) "Aujourd'hui" else "Today"),
                    DateFilterPreset.WEEK to (if (isFr) "Semaine" else "Week"),
                    DateFilterPreset.MONTH to (if (isFr) "Mois" else "Month"),
                    DateFilterPreset.YEAR to (if (isFr) "Année" else "Year"),
                    DateFilterPreset.ALL to (if (isFr) "Tout" else "All Time")
                ).forEach { (preset, label) ->
                    FilterChip(
                        selected = state.datePreset == preset,
                        onClick = { onSelectDatePreset(preset) },
                        label = { Text(label) }
                    )
                }
            }
        }

        // Export Actions (Native Android PDF & CSV Share)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isFr) "Exporter PDF" else "Share PDF")
                }

                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isFr) "Exporter CSV" else "Share CSV")
                }
            }
        }

        // Financial Statement Summary Card
        item {
            VumiqSurfaceCard {
                Text(
                    text = if (isFr) "Synthèse financière (${cur.code})" else "Financial Summary (${cur.code})",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                SummaryMetricLine(
                    label = if (isFr) "Revenus gagnés (Période)" else "Earned Income (Period)",
                    amountText = MoneyMath.formatMinorAmount(summary?.periodIncomeMinor ?: 0L, cur, state.locale),
                    valueColor = colors.incomeText
                )
                SummaryMetricLine(
                    label = if (isFr) "Dépenses engagées (Période)" else "Incurred Expenses (Period)",
                    amountText = MoneyMath.formatMinorAmount(summary?.periodExpenseMinor ?: 0L, cur, state.locale),
                    valueColor = colors.expenseText
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = colors.hairlineBorder)
                SummaryMetricLine(
                    label = if (isFr) "Résultat net" else "Net Profit / Loss",
                    amountText = MoneyMath.formatMinorAmount(
                        summary?.netIncomeMinor ?: 0L,
                        cur,
                        state.locale,
                        showPlusSign = true
                    ),
                    valueColor = if ((summary?.netIncomeMinor ?: 0L) >= 0L) colors.incomeText else colors.expenseText
                )
                SummaryMetricLine(
                    label = if (isFr) "Flux net de trésorerie" else "Net Cash Movement",
                    amountText = MoneyMath.formatMinorAmount(
                        summary?.netCashMovementMinor ?: 0L,
                        cur,
                        state.locale,
                        showPlusSign = true
                    ),
                    valueColor = colors.textPrimary
                )
            }
        }

        // Real Data-Driven Category Breakdown (Expenses)
        item {
            CategoryBreakdownSection(
                title = if (isFr) "Répartition des dépenses" else "Expenses by Category",
                emptyLabel = if (isFr) "Aucune dépense sur cette période." else "No expenses recorded in this period.",
                items = state.expenseBreakdown,
                currency = cur,
                locale = state.locale,
                isIncome = false
            )
        }

        // Real Data-Driven Category Breakdown (Income)
        item {
            CategoryBreakdownSection(
                title = if (isFr) "Répartition des revenus" else "Income by Category",
                emptyLabel = if (isFr) "Aucun revenu sur cette période." else "No income recorded in this period.",
                items = state.incomeBreakdown,
                currency = cur,
                locale = state.locale,
                isIncome = true
            )
        }
    }
}

@Composable
private fun SummaryMetricLine(
    label: String,
    amountText: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val colors = LocalVumiqColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(
            amountText,
            style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

@Composable
private fun CategoryBreakdownSection(
    title: String,
    emptyLabel: String,
    items: List<CategoryBreakdownItem>,
    currency: CurrencyCode,
    locale: LocaleCode,
    isIncome: Boolean
) {
    val colors = LocalVumiqColors.current
    VumiqSurfaceCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Spacer(modifier = Modifier.height(10.dp))
        if (items.isEmpty()) {
            Text(emptyLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${item.categoryName} · ${item.percentage}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = MoneyMath.formatMinorAmount(item.amountMinor, currency, locale),
                        style = TabularMoneyStyle.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.subtleSurface)
                ) {
                    val fraction = (item.percentage.toFloat() / 100f).coerceIn(0.03f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isIncome) colors.incomeText else colors.expenseText)
                    )
                }
            }
        }
    }
}
