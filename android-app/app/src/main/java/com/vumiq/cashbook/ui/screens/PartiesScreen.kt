package com.vumiq.cashbook.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.DueDateReminderEvaluator
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ParsedAmountOutcome
import com.vumiq.cashbook.domain.Party
import com.vumiq.cashbook.domain.PartyStatus
import com.vumiq.cashbook.domain.PartyType
import com.vumiq.cashbook.domain.ReminderStage
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.TabularMoneyStyle
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartiesScreen(
    state: CashbookUiState,
    onAddParty: (
        name: String,
        partyType: PartyType,
        currency: CurrencyCode,
        phone: String,
        address: String,
        notes: String,
        openingReceivableMinor: Long,
        openingPayableMinor: Long,
        dueDate: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onQuickSettleParty: (partyId: String, type: TransactionType) -> Unit,
    onUpdatePartyDueDate: (
        partyId: String,
        dueDate: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _ -> }
) {
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR
    val todayIso = remember { LocalDate.now().toString() }
    var showAddPartySheet by remember { mutableStateOf(false) }
    var editingDueDateParty by remember { mutableStateOf<Party?>(null) }
    var roleFilter by remember { mutableStateOf<PartyType?>(null) }

    val activeParties = state.parties.filter {
        it.status == PartyStatus.ACTIVE && (roleFilter == null || it.partyType == roleFilter)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = roleFilter == null,
                        onClick = { roleFilter = null },
                        label = { Text(if (isFr) "Tous" else "All") }
                    )
                    FilterChip(
                        selected = roleFilter == PartyType.CUSTOMER,
                        onClick = { roleFilter = PartyType.CUSTOMER },
                        label = { Text(if (isFr) "Clients" else "Customers") }
                    )
                    FilterChip(
                        selected = roleFilter == PartyType.SUPPLIER,
                        onClick = { roleFilter = PartyType.SUPPLIER },
                        label = { Text(if (isFr) "Fournisseurs" else "Suppliers") }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { showAddPartySheet = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isFr) "Ajouter" else "Add Party")
                }
            }
        }

        if (activeParties.isEmpty()) {
            item {
                VumiqSurfaceCard {
                    Text(
                        text = if (isFr) "Aucun client ou fournisseur enregistré" else "No customers or suppliers yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFr) {
                            "Ajoutez vos clients et fournisseurs pour suivre les ventes à crédit, dettes et encaissements."
                        } else {
                            "Add customers and suppliers to track receivables, payables, and partial settlements accurately."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            items(activeParties, key = { it.id }) { party ->
                val computed = state.partyBalances[party.id]
                val recMinor = computed?.receivableByCurrency?.get(party.currency) ?: 0L
                val payMinor = computed?.payableByCurrency?.get(party.currency) ?: 0L
                val hasBalance = recMinor > 0L || payMinor > 0L
                val dueClassification = DueDateReminderEvaluator.classifyPartyDueStatus(
                    dueDateIso = party.dueDate,
                    hasPositiveBalance = hasBalance,
                    todayIso = todayIso
                )

                VumiqSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = party.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            val meta = buildList {
                                add(if (isFr) party.partyType.labelFr else party.partyType.labelEn)
                                if (party.phone.isNotBlank()) add(party.phone)
                                add("${computed?.transactionCount ?: 0} tx")
                            }.joinToString(" · ")
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            if (party.dueDate.isNotBlank()) {
                                val dueLabel = when (dueClassification?.first) {
                                    ReminderStage.OVERDUE -> if (isFr) "En retard · Échéance ${party.dueDate}" else "Overdue · Due ${party.dueDate}"
                                    ReminderStage.DUE_TODAY -> if (isFr) "Échéance aujourd'hui · ${party.dueDate}" else "Due today · ${party.dueDate}"
                                    ReminderStage.DUE_SOON -> if (isFr) "Échéance proche · ${party.dueDate}" else "Due soon · ${party.dueDate}"
                                    null -> if (isFr) "Échéance ${party.dueDate}" else "Due ${party.dueDate}"
                                }
                                val dueColor = when (dueClassification?.first) {
                                    ReminderStage.OVERDUE -> colors.expenseText
                                    ReminderStage.DUE_TODAY, ReminderStage.DUE_SOON -> colors.warningText
                                    null -> colors.textSecondary
                                }
                                Text(
                                    text = dueLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dueColor
                                )
                            }
                        }

                        TextButton(onClick = { editingDueDateParty = party }) {
                            Text(
                                text = if (party.dueDate.isBlank()) {
                                    if (isFr) "+ Échéance" else "+ Due Date"
                                } else {
                                    if (isFr) "Échéance" else "Edit Due"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = colors.hairlineBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isFr) "Créance à recevoir" else "To Receive",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = MoneyMath.formatMinorAmount(recMinor, party.currency, state.locale),
                                style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
                                color = if (recMinor > 0L) colors.incomeText else colors.textPrimary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (isFr) "Dette à payer" else "To Pay",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = MoneyMath.formatMinorAmount(payMinor, party.currency, state.locale),
                                style = TabularMoneyStyle.copy(fontWeight = FontWeight.SemiBold),
                                color = if (payMinor > 0L) colors.warningText else colors.textPrimary
                            )
                        }
                    }

                    if (recMinor > 0L || payMinor > 0L) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (recMinor > 0L) {
                                OutlinedButton(
                                    onClick = {
                                        onQuickSettleParty(party.id, TransactionType.RECEIVABLE_PAYMENT)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Text(if (isFr) "Encaisser client" else "Receive Payment")
                                }
                            }
                            if (payMinor > 0L) {
                                OutlinedButton(
                                    onClick = {
                                        onQuickSettleParty(party.id, TransactionType.PAYABLE_PAYMENT)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Text(if (isFr) "Régler fournisseur" else "Pay Supplier")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddPartySheet) {
        var name by remember { mutableStateOf("") }
        var pType by remember { mutableStateOf(PartyType.CUSTOMER) }
        var currency by remember { mutableStateOf(state.selectedCurrency) }
        var phone by remember { mutableStateOf("") }
        var openingRec by remember { mutableStateOf("0") }
        var openingPay by remember { mutableStateOf("0") }
        var dueDateText by remember { mutableStateOf("") }
        var errText by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { showAddPartySheet = false },
            containerColor = colors.cardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isFr) "Nouveau client ou fournisseur" else "Add Customer or Supplier",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartyType.entries.forEach { t ->
                        FilterChip(
                            selected = pType == t,
                            onClick = { pType = t },
                            label = { Text(if (isFr) t.labelFr else t.labelEn) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errText = null },
                    label = { Text(if (isFr) "Nom complet / Société" else "Full name or business") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(if (isFr) "Téléphone" else "Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = openingRec,
                        onValueChange = { openingRec = it; errText = null },
                        label = { Text("Opening Receivable (${currency.code})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = openingPay,
                        onValueChange = { openingPay = it; errText = null },
                        label = { Text("Opening Payable (${currency.code})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = dueDateText,
                    onValueChange = { dueDateText = it; errText = null },
                    label = {
                        Text(
                            if (isFr) {
                                "Date d'échéance optionnelle (AAAA-MM-JJ)"
                            } else {
                                "Optional payment due date (YYYY-MM-DD)"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(7L to "+7d", 14L to "+14d", 30L to "+30d").forEach { (days, label) ->
                        val presetDate = LocalDate.now().plusDays(days).toString()
                        FilterChip(
                            selected = dueDateText == presetDate,
                            onClick = { dueDateText = presetDate; errText = null },
                            label = { Text(label) }
                        )
                    }
                    if (dueDateText.isNotBlank()) {
                        FilterChip(
                            selected = false,
                            onClick = { dueDateText = ""; errText = null },
                            label = { Text(if (isFr) "Effacer" else "Clear") }
                        )
                    }
                }

                if (errText != null) {
                    Text(errText.orEmpty(), color = colors.expenseText)
                }

                Button(
                    onClick = {
                        val recRes = MoneyMath.parseAmountToMinor(
                            openingRec.ifBlank { "0" },
                            currency,
                            allowZero = true
                        )
                        val payRes = MoneyMath.parseAmountToMinor(
                            openingPay.ifBlank { "0" },
                            currency,
                            allowZero = true
                        )
                        if (recRes is ParsedAmountOutcome.Failure) {
                            errText = recRes.message
                            return@Button
                        }
                        if (payRes is ParsedAmountOutcome.Failure) {
                            errText = payRes.message
                            return@Button
                        }
                        onAddParty(
                            name,
                            pType,
                            currency,
                            phone,
                            "",
                            "",
                            (recRes as ParsedAmountOutcome.Success).minorUnits,
                            (payRes as ParsedAmountOutcome.Success).minorUnits,
                            dueDateText.trim(),
                            { showAddPartySheet = false },
                            { err -> errText = err }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Enregistrer le tiers" else "Save Party")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    val dueTarget = editingDueDateParty
    if (dueTarget != null) {
        var dueInput by remember(dueTarget.id) { mutableStateOf(dueTarget.dueDate) }
        var dueError by remember(dueTarget.id) { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { editingDueDateParty = null },
            containerColor = colors.cardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isFr) "Échéance — ${dueTarget.name}" else "Payment Due Date — ${dueTarget.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = if (isFr) {
                        "Définissez une date d'échéance optionnelle pour les rappels de créances/dettes. Ne modifie aucun solde."
                    } else {
                        "Set an optional due date for receivable/payable reminders. Never alters recorded balances."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                OutlinedTextField(
                    value = dueInput,
                    onValueChange = { dueInput = it; dueError = null },
                    label = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0L to (if (isFr) "Aujourd'hui" else "Today"), 7L to "+7d", 14L to "+14d", 30L to "+30d").forEach { (days, label) ->
                        val targetDate = LocalDate.now().plusDays(days).toString()
                        FilterChip(
                            selected = dueInput == targetDate,
                            onClick = { dueInput = targetDate; dueError = null },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = dueInput.isEmpty(),
                        onClick = { dueInput = ""; dueError = null },
                        label = { Text(if (isFr) "Aucune" else "Clear") }
                    )
                }
                if (dueError != null) {
                    Text(dueError.orEmpty(), color = colors.expenseText)
                }
                Button(
                    onClick = {
                        onUpdatePartyDueDate(
                            dueTarget.id,
                            dueInput.trim(),
                            { editingDueDateParty = null },
                            { err -> dueError = err }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Enregistrer l'échéance" else "Save Due Date")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}
