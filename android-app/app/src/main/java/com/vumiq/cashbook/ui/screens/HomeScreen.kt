package com.vumiq.cashbook.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.DateFilterPreset
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.TabularMoneyStyle

@Composable
fun HomeScreen(
    state: CashbookUiState,
    onSelectCurrency: (CurrencyCode) -> Unit,
    onSelectDatePreset: (DateFilterPreset) -> Unit,
    onOpenQuickEntry: (TransactionType) -> Unit,
    onInspectTransaction: (CashbookTransaction) -> Unit,
    onNavigateToLedger: () -> Unit,
    onNavigateToParties: () -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR
    val summary = state.activeSummary
    val currency = state.selectedCurrency
    val activeAccounts = state.accounts.filter {
        it.status == AccountStatus.ACTIVE && it.currency == currency
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Currency & Period Filter Bar (Interactive Buttons)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.availableCurrencies.size > 1) {
                    state.availableCurrencies.forEach { cur ->
                        FilterChip(
                            selected = cur == currency,
                            onClick = { onSelectCurrency(cur) },
                            label = { Text(cur.code, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
                val presets = listOf(
                    DateFilterPreset.TODAY to (if (isFr) "Aujourd'hui" else "Today"),
                    DateFilterPreset.WEEK to (if (isFr) "Semaine" else "This Week"),
                    DateFilterPreset.MONTH to (if (isFr) "Mois" else "This Month"),
                    DateFilterPreset.YEAR to (if (isFr) "Année" else "This Year"),
                    DateFilterPreset.ALL to (if (isFr) "Tout" else "All Time")
                )
                presets.forEach { (preset, label) ->
                    FilterChip(
                        selected = state.datePreset == preset,
                        onClick = { onSelectDatePreset(preset) },
                        label = { Text(label) }
                    )
                }
            }
        }

        // Focal Anchor: Total Cash Balance & Period Income / Expense
        item {
            VumiqSurfaceCard(contentPadding = PaddingValues(20.dp)) {
                Text(
                    text = if (isFr) {
                        "Trésorerie disponible (${currency.code})"
                    } else {
                        "Current Cash Balance (${currency.code})"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = MoneyMath.formatMinorAmount(
                        summary?.currentCashBalanceMinor ?: 0L,
                        currency,
                        state.locale
                    ),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if ((summary?.currentCashBalanceMinor ?: 0L) < 0L) {
                        colors.expenseText
                    } else {
                        colors.textPrimary
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isFr) {
                        "${activeAccounts.size} compte(s) actif(s) · Flux net période: ${
                            MoneyMath.formatMinorAmount(
                                summary?.netCashMovementMinor ?: 0L,
                                currency,
                                state.locale,
                                showPlusSign = true
                            )
                        }"
                    } else {
                        "${activeAccounts.size} active account(s) · Net period cash: ${
                            MoneyMath.formatMinorAmount(
                                summary?.netCashMovementMinor ?: 0L,
                                currency,
                                state.locale,
                                showPlusSign = true
                            )
                        }"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = colors.hairlineBorder)
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFr) "Entrées (Revenus)" else "Period Income",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = MoneyMath.formatMinorAmount(
                                summary?.periodIncomeMinor ?: 0L,
                                currency,
                                state.locale
                            ),
                            style = TabularMoneyStyle.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize
                            ),
                            color = colors.incomeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFr) "Sorties (Dépenses)" else "Period Expenses",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = MoneyMath.formatMinorAmount(
                                summary?.periodExpenseMinor ?: 0L,
                                currency,
                                state.locale
                            ),
                            style = TabularMoneyStyle.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize
                            ),
                            color = colors.expenseText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Thumb-friendly primary Cash In / Cash Out / Transfer triggers (>= 48dp height)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onOpenQuickEntry(TransactionType.INCOME) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.incomeText,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFr) "Entrée" else "Cash In",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { onOpenQuickEntry(TransactionType.EXPENSE) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.expenseText,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFr) "Sortie" else "Cash Out",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { onOpenQuickEntry(TransactionType.TRANSFER) },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.hairlineBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = if (isFr) "Transfert" else "Transfer",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Receivables & Payables Strip
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToParties() },
                    shape = RoundedCornerShape(16.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (isFr) "À recevoir (Clients)" else "To Receive",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = MoneyMath.formatMinorAmount(
                                summary?.totalReceivablesMinor ?: 0L,
                                currency,
                                state.locale
                            ),
                            style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.incomeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToParties() },
                    shape = RoundedCornerShape(16.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (isFr) "À payer (Fournisseurs)" else "To Pay",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = MoneyMath.formatMinorAmount(
                                summary?.totalPayablesMinor ?: 0L,
                                currency,
                                state.locale
                            ),
                            style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.warningText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Accounts Breakdown
        item {
            VumiqSurfaceCard {
                Text(
                    text = if (isFr) "Comptes de trésorerie (${currency.code})" else "Cash & Bank Accounts (${currency.code})",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                activeAccounts.forEachIndexed { idx, acc ->
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = colors.hairlineBorder
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val icon = when (acc.accountType) {
                                AccountType.BANK -> Icons.Outlined.AccountBalance
                                AccountType.MOBILE_MONEY -> Icons.Outlined.Smartphone
                                else -> Icons.Outlined.Payments
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = acc.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = if (isFr) acc.accountType.labelFr else acc.accountType.labelEn,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Text(
                            text = MoneyMath.formatMinorAmount(
                                state.accountBalances[acc.id] ?: 0L,
                                acc.currency,
                                state.locale
                            ),
                            style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }

        // Recent Ledger Activity
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFr) "Écritures récentes" else "Recent Activity",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                if (state.allTransactions.isNotEmpty()) {
                    TextButton(onClick = onNavigateToLedger) {
                        Text(if (isFr) "Voir tout" else "View All")
                    }
                }
            }
        }

        val recent = state.allTransactions.filter { it.status.wireValue == "posted" }.take(6)
        if (recent.isEmpty()) {
            item {
                VumiqSurfaceCard {
                    Text(
                        text = if (isFr) "Aucune opération enregistrée" else "No transactions recorded yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFr) {
                            "Utilisez Entrée ou Sortie ci-dessus pour enregistrer votre première opération réelle."
                        } else {
                            "Tap Cash In or Cash Out above to record your first real transaction."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            items(recent, key = { it.id }) { tx ->
                TransactionRowItem(
                    tx = tx,
                    locale = state.locale,
                    onClick = { onInspectTransaction(tx) }
                )
            }
        }
    }
}
