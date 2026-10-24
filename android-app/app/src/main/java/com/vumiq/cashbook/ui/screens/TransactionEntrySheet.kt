package com.vumiq.cashbook.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.data.attachments.ReceiptStorageManager
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.CategoryKind
import com.vumiq.cashbook.domain.ExchangeRateOutcome
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ParsedAmountOutcome
import com.vumiq.cashbook.domain.PartyStatus
import com.vumiq.cashbook.domain.TransactionDraftInput
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntrySheet(
    state: CashbookUiState,
    initialType: TransactionType,
    preselectedPartyId: String = "",
    onLaunchSystemSheet: () -> Unit = {},
    onDismiss: () -> Unit,
    onSubmit: (
        idempotencyKey: String,
        draft: TransactionDraftInput,
        onError: (String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR
    val activeAccounts = state.accounts.filter { it.status == AccountStatus.ACTIVE }
    val activeParties = state.parties.filter { it.status == PartyStatus.ACTIVE }

    val idempotencyKey = remember {
        "idem_${UUID.randomUUID().toString().replace("-", "").take(20)}"
    }

    var selectedType by remember { mutableStateOf(initialType) }
    var selectedAccountId by remember {
        mutableStateOf(
            activeAccounts.firstOrNull { it.currency == state.selectedCurrency }?.id
                ?: activeAccounts.firstOrNull()?.id.orEmpty()
        )
    }
    var toAccountId by remember {
        mutableStateOf(
            activeAccounts.firstOrNull { it.id != selectedAccountId }?.id.orEmpty()
        )
    }
    val sourceAccount = activeAccounts.firstOrNull { it.id == selectedAccountId }
    val destAccount = activeAccounts.firstOrNull { it.id == toAccountId }
    val effectiveCurrency = sourceAccount?.currency ?: state.selectedCurrency

    var amountInput by remember { mutableStateOf("") }
    var descriptionInput by remember { mutableStateOf("") }
    var dateInput by remember { mutableStateOf(LocalDate.now().toString()) }
    var timeInput by remember {
        mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
    }
    var selectedCategoryId by remember { mutableStateOf("") }
    var selectedPartyId by remember { mutableStateOf(preselectedPartyId) }
    var fxRateInput by remember { mutableStateOf("1") }
    var transferFeeInput by remember { mutableStateOf("0") }
    var referenceInput by remember { mutableStateOf("") }
    var notesInput by remember { mutableStateOf("") }
    var stagedReceiptUri by remember { mutableStateOf<Uri?>(null) }
    var stagedReceiptName by remember { mutableStateOf("") }
    var pendingCameraCapture by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var receiptNotice by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val stagedThumbnail by produceState<Bitmap?>(initialValue = null, key1 = stagedReceiptUri) {
        val uri = stagedReceiptUri
        value = if (uri == null) {
            null
        } else {
            ReceiptStorageManager.loadUriPreviewBitmap(context, uri, maxDimensionPx = 420)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        val pending = pendingCameraCapture
        pendingCameraCapture = null
        if (captured && pending != null && pending.first.exists() && pending.first.length() > 0L) {
            stagedReceiptUri = pending.second
            stagedReceiptName = "camera_receipt_${LocalDate.now()}.jpg"
            receiptNotice = null
        } else {
            pending?.first?.let { runCatching { it.delete() } }
        }
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            stagedReceiptUri = uri
            stagedReceiptName = ReceiptStorageManager.querySourceDisplayName(context, uri)
                ?: "gallery_receipt_${LocalDate.now()}.jpg"
            receiptNotice = null
        }
    }

    val requiresCashAccount = selectedType !in setOf(
        TransactionType.CREDIT_SALE,
        TransactionType.CREDIT_PURCHASE
    )
    val relevantCategoryKind = if (selectedType in setOf(
            TransactionType.INCOME,
            TransactionType.CREDIT_SALE,
            TransactionType.REFUND_INCOME
        )
    ) {
        CategoryKind.INCOME
    } else {
        CategoryKind.EXPENSE
    }
    val matchingCategories = state.categories.filter {
        !it.archived && it.kind == relevantCategoryKind
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isFr) "Enregistrer une opération" else "Record Financial Entry",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )

            // Transaction Type Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransactionType.entries.forEach { tType ->
                    FilterChip(
                        selected = selectedType == tType,
                        onClick = {
                            selectedType = tType
                            errorMessage = null
                        },
                        label = { Text(if (isFr) tType.labelFr else tType.labelEn) }
                    )
                }
            }

            // Source Account Selector
            if (requiresCashAccount) {
                Text(
                    text = if (isFr) "Compte de paiement" else "Account",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeAccounts.forEach { acc ->
                        FilterChip(
                            selected = selectedAccountId == acc.id,
                            onClick = {
                                selectedAccountId = acc.id
                                if (toAccountId == acc.id) {
                                    toAccountId = activeAccounts.firstOrNull { it.id != acc.id }?.id.orEmpty()
                                }
                                errorMessage = null
                            },
                            label = { Text("${acc.name} (${acc.currency.code})") }
                        )
                    }
                }
            }

            // Transfer Destination Account & Cross-Currency FX Rate
            if (selectedType == TransactionType.TRANSFER) {
                Text(
                    text = if (isFr) "Compte destinataire" else "Destination Account",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeAccounts.filter { it.id != selectedAccountId }.forEach { acc ->
                        FilterChip(
                            selected = toAccountId == acc.id,
                            onClick = {
                                toAccountId = acc.id
                                errorMessage = null
                            },
                            label = { Text("${acc.name} (${acc.currency.code})") }
                        )
                    }
                }

                if (destAccount != null && destAccount.currency != effectiveCurrency) {
                    OutlinedTextField(
                        value = fxRateInput,
                        onValueChange = { fxRateInput = it; errorMessage = null },
                        label = {
                            Text("FX Rate (1 ${effectiveCurrency.code} = ? ${destAccount.currency.code})")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = transferFeeInput,
                    onValueChange = { transferFeeInput = it; errorMessage = null },
                    label = {
                        Text("${if (isFr) "Frais de transfert" else "Transfer fee"} (${effectiveCurrency.code})")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Exact Amount Input
            OutlinedTextField(
                value = amountInput,
                onValueChange = {
                    amountInput = it
                    errorMessage = null
                },
                label = {
                    Text("${if (isFr) "Montant" else "Amount"} (${effectiveCurrency.code})")
                },
                placeholder = {
                    Text(if (effectiveCurrency.decimals == 0) "25000" else "125.50")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Description
            OutlinedTextField(
                value = descriptionInput,
                onValueChange = {
                    descriptionInput = it
                    errorMessage = null
                },
                label = { Text(if (isFr) "Description / Libellé" else "Description") },
                placeholder = {
                    Text(if (isFr) "Ex: Vente comptoir marchandises" else "e.g. Daily retail counter sales")
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Category Selector (when applicable)
            if (selectedType !in setOf(
                    TransactionType.TRANSFER,
                    TransactionType.OPENING_BALANCE,
                    TransactionType.RECEIVABLE_PAYMENT,
                    TransactionType.PAYABLE_PAYMENT
                ) && matchingCategories.isNotEmpty()
            ) {
                Text(
                    text = if (isFr) "Catégorie" else "Category",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    matchingCategories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = {
                                selectedCategoryId = if (selectedCategoryId == cat.id) "" else cat.id
                            },
                            label = { Text(cat.name) }
                        )
                    }
                }
            }

            // Party Selector (required for credit/settlement, optional for cash sales/expenses)
            if (activeParties.isNotEmpty()) {
                Text(
                    text = if (isFr) "Client / Fournisseur" else "Customer / Supplier Party",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeParties.forEach { party ->
                        FilterChip(
                            selected = selectedPartyId == party.id,
                            onClick = {
                                selectedPartyId = if (selectedPartyId == party.id) "" else party.id
                                errorMessage = null
                            },
                            label = { Text(party.name) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it; errorMessage = null },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = referenceInput,
                    onValueChange = { referenceInput = it },
                    label = { Text(if (isFr) "Réf / Reçu" else "Ref #") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = notesInput,
                onValueChange = { notesInput = it },
                label = { Text(if (isFr) "Notes complémentaires" else "Optional notes") },
                modifier = Modifier.fillMaxWidth()
            )

            // Optional Receipt Photo Attachment (Camera or Gallery)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colors.subtleSurface,
                border = BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isFr) "Photo du reçu (optionnel)" else "Receipt Photo (Optional)",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary
                    )

                    if (stagedReceiptUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bmp = stagedThumbnail
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = if (isFr) "Aperçu du reçu" else "Receipt preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stagedReceiptName.ifBlank {
                                            if (isFr) "Reçu sélectionné" else "Receipt selected"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isFr) {
                                            "Sera compressé dans le stockage privé"
                                        } else {
                                            "Compressed into private app storage on save"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    stagedReceiptUri = null
                                    stagedReceiptName = ""
                                    receiptNotice = null
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = colors.expenseText
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (isFr) "Retirer" else "Remove",
                                    color = colors.expenseText
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val pair = ReceiptStorageManager.createTemporaryCameraCaptureUri(context)
                                    pendingCameraCapture = pair
                                    onLaunchSystemSheet()
                                    takePictureLauncher.launch(pair.second)
                                }.onFailure { err ->
                                    pendingCameraCapture = null
                                    receiptNotice = if (isFr) {
                                        "Appareil photo indisponible : ${err.localizedMessage ?: "utilisez la galerie"}"
                                    } else {
                                        "Camera unavailable: ${err.localizedMessage ?: "use Gallery instead"}"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isFr) "Caméra" else "Camera")
                        }

                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    onLaunchSystemSheet()
                                    pickGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }.onFailure { err ->
                                    receiptNotice = err.localizedMessage ?: "Gallery picker unavailable."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isFr) "Galerie" else "Gallery")
                        }
                    }

                    if (!receiptNotice.isNullOrBlank()) {
                        Text(
                            text = receiptNotice.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warningText
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.expenseText
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pendingCameraCapture?.first?.let { runCatching { it.delete() } }
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Annuler" else "Cancel")
                }

                Button(
                    onClick = {
                        val parsedAmt = MoneyMath.parseAmountToMinor(amountInput, effectiveCurrency)
                        if (parsedAmt is ParsedAmountOutcome.Failure) {
                            errorMessage = parsedAmt.message
                            return@Button
                        }
                        val amountMinor = (parsedAmt as ParsedAmountOutcome.Success).minorUnits

                        var feeMinor = 0L
                        var convertedMinor = 0L
                        var rateScaled = 0L
                        var toCurrencyCode = ""

                        if (selectedType == TransactionType.TRANSFER) {
                            val feeParsed = MoneyMath.parseAmountToMinor(
                                rawInput = transferFeeInput.ifBlank { "0" },
                                currency = effectiveCurrency,
                                allowZero = true
                            )
                            if (feeParsed is ParsedAmountOutcome.Failure) {
                                errorMessage = feeParsed.message
                                return@Button
                            }
                            feeMinor = (feeParsed as ParsedAmountOutcome.Success).minorUnits

                            val dst = destAccount
                            if (dst == null) {
                                errorMessage = if (isFr) {
                                    "Sélectionnez un second compte distinct pour un transfert."
                                } else {
                                    "Create and select a second account to record a transfer."
                                }
                                return@Button
                            }
                            toCurrencyCode = dst.currency.code
                            if (dst.currency != effectiveCurrency) {
                                when (val fxRes = MoneyMath.parseExchangeRateScaled(fxRateInput)) {
                                    is ExchangeRateOutcome.Failure -> {
                                        errorMessage = fxRes.message
                                        return@Button
                                    }
                                    is ExchangeRateOutcome.Success -> {
                                        rateScaled = fxRes.rateScaled
                                        convertedMinor = MoneyMath.convertMinorWithScaledRate(
                                            sourceMinor = amountMinor,
                                            sourceCurrency = effectiveCurrency,
                                            targetCurrency = dst.currency,
                                            exchangeRateScaled = rateScaled
                                        )
                                    }
                                }
                            }
                        }

                        val draft = TransactionDraftInput(
                            type = selectedType,
                            amountMinor = amountMinor,
                            currency = effectiveCurrency,
                            description = descriptionInput,
                            date = dateInput,
                            time = timeInput,
                            accountId = if (requiresCashAccount) selectedAccountId else "",
                            toAccountId = if (selectedType == TransactionType.TRANSFER) toAccountId else "",
                            categoryId = selectedCategoryId,
                            partyId = selectedPartyId,
                            convertedAmountMinor = convertedMinor,
                            toCurrency = toCurrencyCode,
                            exchangeRateScaled = rateScaled,
                            feeMinor = feeMinor,
                            referenceNumber = referenceInput,
                            notes = notesInput,
                            attachmentSourceUri = stagedReceiptUri?.toString().orEmpty(),
                            attachmentDisplayName = stagedReceiptName
                        )

                        onSubmit(idempotencyKey, draft) { err ->
                            errorMessage = err
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Enregistrer" else "Save Entry")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
