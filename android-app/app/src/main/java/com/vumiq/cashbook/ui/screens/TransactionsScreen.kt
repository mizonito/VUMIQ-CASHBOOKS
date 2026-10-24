package com.vumiq.cashbook.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vumiq.cashbook.data.attachments.ReceiptStorageManager
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ParsedAmountOutcome
import com.vumiq.cashbook.domain.TransactionStatus
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.domain.TransactionTypeFilter
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.TabularMoneyStyle
import java.io.File
import java.time.LocalDate

@Composable
fun TransactionRowItem(
    tx: CashbookTransaction,
    locale: LocaleCode,
    onClick: () -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = locale == LocaleCode.FR
    val isVoided = tx.status == TransactionStatus.VOIDED

    val isPositiveFlow = tx.type in setOf(
        TransactionType.INCOME,
        TransactionType.OPENING_BALANCE,
        TransactionType.REFUND_EXPENSE,
        TransactionType.RECEIVABLE_PAYMENT,
        TransactionType.CREDIT_SALE
    )

    val amountColor = when {
        isVoided -> colors.textSecondary
        tx.type == TransactionType.TRANSFER -> colors.textPrimary
        isPositiveFlow -> colors.incomeText
        else -> colors.expenseText
    }

    val signPrefix = when {
        isVoided || tx.type == TransactionType.TRANSFER -> ""
        isPositiveFlow -> "+"
        else -> "-"
    }

    // Clean unboxed metadata separated by middle dots (Zero-Pill Discipline)
    val hasReceipt = tx.receiptLocalPath.isNotBlank() || tx.attachmentName.isNotBlank()
    val metaParts = buildList {
        add(tx.date)
        add(if (isFr) tx.type.labelFr else tx.type.labelEn)
        if (tx.categoryLabelSnapshot.isNotBlank()) add(tx.categoryLabelSnapshot)
        if (tx.partyNameSnapshot.isNotBlank()) add(tx.partyNameSnapshot)
        if (hasReceipt) add(if (isFr) "Reçu" else "Receipt")
        if (isVoided) add(if (isFr) "ANNULÉE" else "VOIDED")
    }.joinToString(" · ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = colors.cardSurface,
        border = BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = tx.description,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (isVoided) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (isVoided) colors.textSecondary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = metaParts,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "$signPrefix${MoneyMath.formatMinorAmount(tx.amountMinor, tx.currency, locale)}",
                style = TabularMoneyStyle.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (isVoided) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = amountColor
            )
        }
    }
}

@Composable
fun TransactionsScreen(
    state: CashbookUiState,
    onSearchChange: (String) -> Unit,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onAccountFilterChange: (String) -> Unit,
    onToggleIncludeVoided: (Boolean) -> Unit,
    onOpenNewEntry: (TransactionType) -> Unit,
    onInspectTransaction: (CashbookTransaction) -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = {
                    Text(
                        if (isFr) {
                            "Rechercher description, référence, tiers..."
                        } else {
                            "Search description, reference, party..."
                        }
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    TransactionTypeFilter.ALL to (if (isFr) "Toutes" else "All"),
                    TransactionTypeFilter.INCOME_GROUP to (if (isFr) "Entrées" else "Inflows"),
                    TransactionTypeFilter.EXPENSE_GROUP to (if (isFr) "Sorties" else "Outflows"),
                    TransactionTypeFilter.TRANSFER_ONLY to (if (isFr) "Transferts" else "Transfers")
                )
                filters.forEach { (flt, label) ->
                    FilterChip(
                        selected = state.typeFilter == flt,
                        onClick = { onTypeFilterChange(flt) },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = state.includeVoided,
                    onClick = { onToggleIncludeVoided(!state.includeVoided) },
                    label = { Text(if (isFr) "Inclure annulées" else "Show Voided") }
                )
            }
        }

        if (state.accounts.size > 1) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filterAccountId.isEmpty(),
                        onClick = { onAccountFilterChange("") },
                        label = { Text(if (isFr) "Tous les comptes" else "All Accounts") }
                    )
                    state.accounts.forEach { acc ->
                        FilterChip(
                            selected = state.filterAccountId == acc.id,
                            onClick = { onAccountFilterChange(acc.id) },
                            label = { Text("${acc.name} (${acc.currency.code})") }
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFr) {
                        "${state.filteredTransactions.size} écriture(s)"
                    } else {
                        "${state.filteredTransactions.size} record(s)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary
                )
                Button(
                    onClick = { onOpenNewEntry(TransactionType.INCOME) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isFr) "Nouvelle écriture" else "New Entry")
                }
            }
        }

        if (state.filteredTransactions.isEmpty()) {
            item {
                VumiqSurfaceCard {
                    Text(
                        text = if (isFr) "Aucun résultat dans ce filtre" else "No matching records",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFr) {
                            "Modifiez vos filtres ou enregistrez une nouvelle opération."
                        } else {
                            "Adjust your filters or tap New Entry to record a transaction."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            items(state.filteredTransactions, key = { it.id }) { tx ->
                TransactionRowItem(
                    tx = tx,
                    locale = state.locale,
                    onClick = { onInspectTransaction(tx) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    tx: CashbookTransaction,
    locale: LocaleCode,
    onLaunchSystemSheet: () -> Unit = {},
    onAttachOrReplaceReceipt: (Uri, String?) -> Unit = { _, _ -> },
    onRemoveReceipt: () -> Unit = {},
    onDismiss: () -> Unit,
    onSaveEdit: (
        amountMinor: Long,
        description: String,
        date: String,
        notes: String,
        reason: String,
        onError: (String) -> Unit
    ) -> Unit,
    onConfirmVoid: (reason: String, onError: (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val colors = LocalVumiqColors.current
    val isFr = locale == LocaleCode.FR
    var mode by remember { mutableStateOf("view") } // "view" | "edit" | "void"
    var editAmount by remember(tx.id) {
        mutableStateOf(MoneyMath.minorToInputString(tx.amountMinor, tx.currency))
    }
    var editDesc by remember(tx.id) { mutableStateOf(tx.description) }
    var editDate by remember(tx.id) { mutableStateOf(tx.date) }
    var editNotes by remember(tx.id) { mutableStateOf(tx.notes) }
    var actionReason by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingDetailCameraCapture by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var showFullReceiptDialog by remember { mutableStateOf(false) }

    val receiptBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = tx.receiptLocalPath,
        key2 = tx.updatedAt
    ) {
        value = if (tx.receiptLocalPath.isBlank()) {
            null
        } else {
            ReceiptStorageManager.loadPreviewBitmap(tx.receiptLocalPath, maxDimensionPx = 1280)
        }
    }

    val detailCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        val pending = pendingDetailCameraCapture
        pendingDetailCameraCapture = null
        if (captured && pending != null && pending.first.exists() && pending.first.length() > 0L) {
            onAttachOrReplaceReceipt(pending.second, "camera_receipt_${LocalDate.now()}.jpg")
        } else {
            pending?.first?.let { runCatching { it.delete() } }
        }
    }

    val detailGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val sourceName = ReceiptStorageManager.querySourceDisplayName(context, uri)
            onAttachOrReplaceReceipt(uri, sourceName)
        }
    }

    if (showFullReceiptDialog && receiptBitmap != null) {
        Dialog(onDismissRequest = { showFullReceiptDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.cardSurface,
                border = BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = tx.attachmentName.ifBlank {
                            if (isFr) "Photo du reçu" else "Receipt Photo"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Image(
                        bitmap = receiptBitmap!!.asImageBitmap(),
                        contentDescription = if (isFr) "Photo du reçu" else "Receipt photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 460.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.subtleSurface)
                    )
                    OutlinedButton(
                        onClick = { showFullReceiptDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isFr) "Fermer" else "Close")
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isFr) tx.type.labelFr else tx.type.labelEn,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary
            )
            Text(
                text = MoneyMath.formatMinorAmount(tx.amountMinor, tx.currency, locale),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary
            )

            HorizontalDivider(color = colors.hairlineBorder)

            when (mode) {
                "view" -> {
                    DetailLine(if (isFr) "Description" else "Description", tx.description)
                    DetailLine(if (isFr) "Date & Heure" else "Date & Time", "${tx.date} · ${tx.time}")
                    if (tx.categoryLabelSnapshot.isNotBlank()) {
                        DetailLine(if (isFr) "Catégorie" else "Category", tx.categoryLabelSnapshot)
                    }
                    if (tx.partyNameSnapshot.isNotBlank()) {
                        DetailLine(if (isFr) "Tiers" else "Party", tx.partyNameSnapshot)
                    }
                    if (tx.referenceNumber.isNotBlank()) {
                        DetailLine(if (isFr) "Référence" else "Reference", tx.referenceNumber)
                    }
                    if (tx.notes.isNotBlank()) {
                        DetailLine(if (isFr) "Notes" else "Notes", tx.notes)
                    }
                    DetailLine(
                        if (isFr) "Statut" else "Status",
                        if (tx.status == TransactionStatus.VOIDED) {
                            "VOIDED — ${tx.voidReason}"
                        } else {
                            "POSTED"
                        }
                    )

                    // Receipt Attachment Viewer & Controls (Phase 5)
                    val hasLocalReceipt = receiptBitmap != null
                    val hasRemoteReceiptMeta = !hasLocalReceipt && tx.attachmentName.isNotBlank()
                    if (hasLocalReceipt || hasRemoteReceiptMeta || tx.status == TransactionStatus.POSTED) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.subtleSurface,
                            border = BorderStroke(1.dp, colors.hairlineBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isFr) "Pièce justificative / Reçu" else "Receipt Attachment",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.textPrimary
                                    )
                                    if ((hasLocalReceipt || hasRemoteReceiptMeta) && tx.status == TransactionStatus.POSTED) {
                                        TextButton(onClick = onRemoveReceipt) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                tint = colors.expenseText
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                if (isFr) "Supprimer" else "Remove",
                                                color = colors.expenseText
                                            )
                                        }
                                    }
                                }

                                if (hasLocalReceipt) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(168.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { showFullReceiptDialog = true }
                                    ) {
                                        Image(
                                            bitmap = receiptBitmap!!.asImageBitmap(),
                                            contentDescription = if (isFr) "Aperçu du reçu" else "Receipt preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Text(
                                        text = "${tx.attachmentName.ifBlank { "receipt.jpg" }} · ${
                                            if (isFr) "Touchez l'image pour agrandir" else "Tap photo to inspect full size"
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                } else if (hasRemoteReceiptMeta) {
                                    Text(
                                        text = if (isFr) {
                                            "Reçu référencé : ${tx.attachmentName} (stocké sur l'appareil d'origine)"
                                        } else {
                                            "Receipt recorded: ${tx.attachmentName} (photo stored on originating device)"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                } else {
                                    Text(
                                        text = if (isFr) {
                                            "Aucun reçu joint. Prenez une photo ou choisissez depuis la galerie."
                                        } else {
                                            "No receipt attached. Capture a photo or choose from Gallery."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }

                                if (tx.status == TransactionStatus.POSTED) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    val pair = ReceiptStorageManager.createTemporaryCameraCaptureUri(context)
                                                    pendingDetailCameraCapture = pair
                                                    onLaunchSystemSheet()
                                                    detailCameraLauncher.launch(pair.second)
                                                }.onFailure { err ->
                                                    pendingDetailCameraCapture = null
                                                    errorMsg = err.localizedMessage ?: "Camera unavailable"
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                if (hasLocalReceipt) {
                                                    if (isFr) "Reprendre" else "Retake"
                                                } else {
                                                    if (isFr) "Caméra" else "Camera"
                                                }
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    onLaunchSystemSheet()
                                                    detailGalleryLauncher.launch(
                                                        PickVisualMediaRequest(
                                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                                        )
                                                    )
                                                }.onFailure { err ->
                                                    errorMsg = err.localizedMessage ?: "Gallery unavailable"
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                if (hasLocalReceipt) {
                                                    if (isFr) "Remplacer" else "Replace"
                                                } else {
                                                    if (isFr) "Galerie" else "Gallery"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (errorMsg != null) {
                        Text(errorMsg.orEmpty(), color = colors.expenseText)
                    }

                    if (tx.status == TransactionStatus.POSTED) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    errorMsg = null
                                    mode = "edit"
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isFr) "Modifier" else "Edit")
                            }

                            OutlinedButton(
                                onClick = {
                                    errorMsg = null
                                    mode = "void"
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, colors.expenseText)
                            ) {
                                Icon(
                                    Icons.Outlined.Block,
                                    contentDescription = null,
                                    tint = colors.expenseText
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isFr) "Annuler" else "Void",
                                    color = colors.expenseText
                                )
                            }
                        }
                    }
                }

                "edit" -> {
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { editAmount = it; errorMsg = null },
                        label = { Text("${if (isFr) "Montant" else "Amount"} (${tx.currency.code})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it; errorMsg = null },
                        label = { Text(if (isFr) "Description" else "Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDate,
                        onValueChange = { editDate = it; errorMsg = null },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        label = { Text(if (isFr) "Notes" else "Notes") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = actionReason,
                        onValueChange = { actionReason = it },
                        label = { Text(if (isFr) "Motif de modification (Audit)" else "Reason for edit (Audit log)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg != null) {
                        Text(errorMsg.orEmpty(), color = colors.expenseText)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { mode = "view" },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(if (isFr) "Retour" else "Back")
                        }
                        Button(
                            onClick = {
                                when (val parsed = MoneyMath.parseAmountToMinor(editAmount, tx.currency)) {
                                    is ParsedAmountOutcome.Failure -> errorMsg = parsed.message
                                    is ParsedAmountOutcome.Success -> {
                                        onSaveEdit(
                                            parsed.minorUnits,
                                            editDesc,
                                            editDate,
                                            editNotes,
                                            actionReason
                                        ) { err -> errorMsg = err }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(if (isFr) "Enregistrer" else "Save Changes")
                        }
                    }
                }

                "void" -> {
                    Text(
                        text = if (isFr) {
                            "L'annulation conserve la trace d'audit mais retire immédiatement ce montant des soldes et rapports."
                        } else {
                            "Voiding preserves the immutable audit trail while reversing this entry's impact on cash balances and reports."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    OutlinedTextField(
                        value = actionReason,
                        onValueChange = { actionReason = it; errorMsg = null },
                        label = { Text(if (isFr) "Motif obligatoire d'annulation" else "Required void reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg != null) {
                        Text(errorMsg.orEmpty(), color = colors.expenseText)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { mode = "view" },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(if (isFr) "Retour" else "Back")
                        }
                        Button(
                            onClick = {
                                onConfirmVoid(actionReason) { err -> errorMsg = err }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.expenseText),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(if (isFr) "Confirmer l'annulation" else "Void Entry")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val colors = LocalVumiqColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary
        )
    }
}
