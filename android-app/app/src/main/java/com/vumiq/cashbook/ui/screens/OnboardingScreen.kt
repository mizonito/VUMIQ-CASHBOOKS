package com.vumiq.cashbook.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ParsedAmountOutcome
import com.vumiq.cashbook.ui.components.OwnershipFooterBlock
import com.vumiq.cashbook.ui.components.VumiqBrandEmblem
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors

@Composable
fun OnboardingScreen(
    locale: LocaleCode,
    isFirebaseConfigured: Boolean = false,
    isSyncingCloud: Boolean = false,
    onCreateBusiness: (
        businessName: String,
        currency: CurrencyCode,
        accountName: String,
        accountType: AccountType,
        openingBalanceMinor: Long,
        onError: (String) -> Unit
    ) -> Unit,
    onImportExistingBackup: () -> Unit,
    onSignInAndPullCloud: () -> Unit = {}
) {
    val colors = LocalVumiqColors.current
    val isFr = locale == LocaleCode.FR

    var businessName by rememberSaveable { mutableStateOf("") }
    var selectedCurrency by rememberSaveable { mutableStateOf(CurrencyCode.USD) }
    var accountName by rememberSaveable {
        mutableStateOf(if (isFr) "Caisse principale" else "Main Cash Drawer")
    }
    var selectedAccountType by rememberSaveable { mutableStateOf(AccountType.CASH) }
    var openingBalanceText by rememberSaveable { mutableStateOf("0") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VumiqBrandEmblem(size = 44.dp)
            Column {
                Text(
                    text = "VUMIQ Cashbook",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = if (isFr) {
                        "Comptabilité hors ligne fiable et exacte"
                    } else {
                        "Offline-first precision bookkeeping"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        }

        VumiqSurfaceCard {
            Text(
                text = if (isFr) "Créer votre premier livre de caisse" else "Set up your business cashbook",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isFr) {
                    "Toutes les écritures sont enregistrées localement sur votre téléphone sans aucune donnée fictive."
                } else {
                    "All transactions are stored durably on your phone with zero fake records."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = businessName,
                onValueChange = {
                    businessName = it
                    errorMessage = null
                },
                label = { Text(if (isFr) "Nom de l'entreprise ou boutique" else "Business or shop name") },
                placeholder = { Text(if (isFr) "Ex: Établissement Gutale" else "e.g. Gutale Trading Co.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isFr) "Devise principale" else "Primary currency",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CurrencyCode.entries.forEach { cur ->
                    FilterChip(
                        selected = selectedCurrency == cur,
                        onClick = { selectedCurrency = cur },
                        label = { Text("${cur.code} (${cur.symbol})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = accountName,
                onValueChange = {
                    accountName = it
                    errorMessage = null
                },
                label = { Text(if (isFr) "Premier compte de trésorerie" else "Primary cash / bank account") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccountType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedAccountType == type,
                        onClick = { selectedAccountType = type },
                        label = { Text(if (isFr) type.labelFr else type.labelEn) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = openingBalanceText,
                onValueChange = {
                    openingBalanceText = it
                    errorMessage = null
                },
                label = {
                    Text(
                        if (isFr) {
                            "Solde d'ouverture (${selectedCurrency.code})"
                        } else {
                            "Opening cash balance (${selectedCurrency.code})"
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.expenseText
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    if (businessName.isBlank()) {
                        errorMessage = if (isFr) {
                            "Veuillez saisir le nom de votre entreprise."
                        } else {
                            "Enter your business or shop name."
                        }
                        return@Button
                    }
                    val parsedOpening = MoneyMath.parseAmountToMinor(
                        rawInput = openingBalanceText.ifBlank { "0" },
                        currency = selectedCurrency,
                        allowZero = true
                    )
                    when (parsedOpening) {
                        is ParsedAmountOutcome.Failure -> {
                            errorMessage = parsedOpening.message
                        }
                        is ParsedAmountOutcome.Success -> {
                            onCreateBusiness(
                                businessName,
                                selectedCurrency,
                                accountName,
                                selectedAccountType,
                                parsedOpening.minorUnits
                            ) { err -> errorMessage = err }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isFr) "Ouvrir mon livre de caisse" else "Launch Cashbook",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        OutlinedButton(
            onClick = onImportExistingBackup,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Outlined.FileUpload, contentDescription = null)
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                text = if (isFr) {
                    "Restaurer une sauvegarde JSON (Web ou Android)"
                } else {
                    "Restore JSON Backup (from Web or Android)"
                }
            )
        }

        if (isFirebaseConfigured) {
            OutlinedButton(
                onClick = onSignInAndPullCloud,
                enabled = !isSyncingCloud,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = when {
                        isSyncingCloud -> if (isFr) "Connexion et synchronisation..." else "Signing in & syncing..."
                        else -> if (isFr) {
                            "Connexion Google & Synchroniser le Web Cashbook"
                        } else {
                            "Sign in with Google & Sync Web Cashbook"
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        OwnershipFooterBlock()
    }
}
