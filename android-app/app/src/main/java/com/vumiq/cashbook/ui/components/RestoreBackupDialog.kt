package com.vumiq.cashbook.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.domain.BackupInspectionPreview
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.RestoreMode
import com.vumiq.cashbook.ui.theme.LocalVumiqColors

/**
 * Validated Backup Restore Inspection & Mode Confirmation Dialog (Phase 3).
 *
 * Enforces safe data recovery:
 * - Displays verified checksum, business name, currency, and incoming record counts.
 * - Defaults to non-destructive `RestoreMode.SAFE_MERGE` when the business already exists locally.
 * - Requires explicit warning acknowledgment before enabling `RestoreMode.REPLACE_BUSINESS`.
 */
@Composable
fun RestoreBackupDialog(
    preview: BackupInspectionPreview,
    locale: LocaleCode,
    onConfirmRestore: (RestoreMode) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = locale == LocaleCode.FR
    var confirmReplaceChecked by remember(preview.businessId, preview.checksum) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isFr) "Restaurer l'archive JSON vérifiée" else "Restore Verified JSON Backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${preview.businessName} · ${preview.currency.code} · Checksum ${preview.checksum}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )

                Text(
                    text = if (isFr) {
                        "${preview.incomingTransactionCount} écritures · ${preview.incomingAccountCount} comptes · ${preview.incomingPartyCount} tiers"
                    } else {
                        "${preview.incomingTransactionCount} transactions · ${preview.incomingAccountCount} accounts · ${preview.incomingPartyCount} parties"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )

                if (preview.targetBusinessExistsLocally) {
                    HorizontalDivider(color = colors.hairlineBorder)

                    Text(
                        text = if (isFr) {
                            "Fusion sécurisée (recommandé) : conserve vos ${preview.existingLocalTransactionCount} écritures existantes, ajoute ${preview.newTransactionsInMerge} nouvelles écritures et ignore ${preview.duplicateTransactionsSkippedInMerge} doublons."
                        } else {
                            "Safe Merge (Recommended): Keeps all ${preview.existingLocalTransactionCount} existing local entries, adds ${preview.newTransactionsInMerge} new entries, and safely skips ${preview.duplicateTransactionsSkippedInMerge} duplicates."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary
                    )

                    Surface(
                        color = colors.subtleSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.hairlineBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isFr) {
                                    "Attention — Remplacement complet"
                                } else {
                                    "Warning — Full Book Replacement"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.expenseText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFr) {
                                    "Remplacer ce livre écrasera les ${preview.existingLocalTransactionCount} écritures locales actuelles de « ${preview.businessName} ». Une copie de secours locale sera enregistrée automatiquement avant le remplacement."
                                } else {
                                    "Replacing this book will overwrite all ${preview.existingLocalTransactionCount} current local entries in \"${preview.businessName}\". An automatic recovery snapshot is saved locally before replacing."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { confirmReplaceChecked = !confirmReplaceChecked }
                            ) {
                                Checkbox(
                                    checked = confirmReplaceChecked,
                                    onCheckedChange = { confirmReplaceChecked = it }
                                )
                                Text(
                                    text = if (isFr) {
                                        "Je comprends et souhaite remplacer ce livre"
                                    } else {
                                        "I understand and want to replace existing book records"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary
                                )
                            }
                            if (confirmReplaceChecked) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { onConfirmRestore(RestoreMode.REPLACE_BUSINESS) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isFr) "Remplacer tout le livre" else "Replace Existing Book",
                                        color = colors.expenseText
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (isFr) {
                            "L'intégrité de l'archive a été vérifiée. Aucune donnée locale existante ne sera écrasée."
                        } else {
                            "Archive checksum and ledger invariants passed verification. No existing local book will be overwritten."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirmRestore(RestoreMode.SAFE_MERGE) }) {
                Text(
                    text = when {
                        preview.targetBusinessExistsLocally && isFr -> "Fusion sécurisée"
                        preview.targetBusinessExistsLocally -> "Safe Merge"
                        isFr -> "Importer le livre"
                        else -> "Import Ledger Book"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isFr) "Annuler" else "Cancel")
            }
        }
    )
}
