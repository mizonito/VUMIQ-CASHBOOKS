package com.vumiq.cashbook.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vumiq.cashbook.R
import com.vumiq.cashbook.data.preferences.DueReminderPreferencesSnapshot
import com.vumiq.cashbook.data.preferences.SettingsRepository
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.BiometricAvailability
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.MoneyMath
import com.vumiq.cashbook.domain.ParsedAmountOutcome
import com.vumiq.cashbook.domain.RelockTimeoutOption
import com.vumiq.cashbook.domain.ThemePreference
import com.vumiq.cashbook.reminders.DueDateReminderWorker
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.components.OwnershipFooterBlock
import com.vumiq.cashbook.ui.components.VumiqSurfaceCard
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.TabularMoneyStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    state: CashbookUiState,
    settingsRepository: SettingsRepository = run {
        val ctx = LocalContext.current
        remember(ctx) { SettingsRepository(ctx) }
    },
    onSwitchBusiness: (String) -> Unit = {},
    onCreateNewBusiness: (
        businessName: String,
        currency: CurrencyCode,
        accountName: String,
        accountType: AccountType,
        openingBalanceMinor: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddAccount: (
        name: String,
        accountType: AccountType,
        currency: CurrencyCode,
        openingBalanceMinor: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _, _ -> },
    onChangeTheme: (ThemePreference) -> Unit = {},
    onChangeLocale: (LocaleCode) -> Unit = {},
    onToggleBiometricUnlock: (Boolean) -> Unit = {},
    onChangeRelockTimeout: (RelockTimeoutOption) -> Unit = {},
    onLockLedgerNow: () -> Unit = {},
    onLaunchSystemSheet: () -> Unit = {},
    onSignInWithGoogle: () -> Unit = {},
    onSignOutFromGoogle: () -> Unit = {},
    onSyncCloudNow: () -> Unit = {},
    onExportJsonBackup: () -> Unit = {},
    onRestoreJsonBackup: () -> Unit = {},
    onToggleDueReminders: (Boolean) -> Unit = {},
    onChangeDueLeadDays: (Int) -> Unit = {},
    onChangeDuePreferredHour: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR

    // Directly consume 'biometric_unlock_enabled' from SettingsRepository (DataStore) using collectAsState
    val isBiometricUnlockEnabled by settingsRepository.biometricUnlockEnabledFlow.collectAsState(
        initial = state.biometricGate.isBiometricUnlockEnabled
    )
    val relockTimeoutSeconds by settingsRepository.relockTimeoutSecondsFlow.collectAsState(
        initial = state.biometricGate.relockTimeout.seconds
    )
    val dueReminderPrefs by settingsRepository.dueReminderPreferencesFlow.collectAsState(
        initial = DueReminderPreferencesSnapshot()
    )
    val selectedRelockTimeout = RelockTimeoutOption.fromSeconds(relockTimeoutSeconds)
    var notificationPermissionDeniedNote by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            notificationPermissionDeniedNote = false
            onToggleDueReminders(true)
        } else {
            notificationPermissionDeniedNote = true
            onToggleDueReminders(false)
        }
    }

    val requestToggleDueReminders: (Boolean) -> Unit = { wantEnabled ->
        if (!wantEnabled) {
            notificationPermissionDeniedNote = false
            onToggleDueReminders(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !DueDateReminderWorker.hasNotificationPermission(context)
        ) {
            onLaunchSystemSheet()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionDeniedNote = false
            onToggleDueReminders(true)
        }
    }

    val updateBiometricUnlock: (Boolean) -> Unit = { enabled ->
        coroutineScope.launch {
            settingsRepository.setBiometricUnlockEnabled(enabled)
            onToggleBiometricUnlock(enabled)
        }
    }

    var showAddAccountSheet by remember { mutableStateOf(false) }
    var showNewBizSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Security & Biometric Unlock (Observed from SettingsRepository DataStore & checked at app boot)
        item {
            val bioGate = state.biometricGate

            val statusText = when (bioGate.availability) {
                BiometricAvailability.AVAILABLE -> if (isBiometricUnlockEnabled) {
                    stringResource(R.string.settings_biometric_status_enabled)
                } else {
                    stringResource(R.string.settings_biometric_status_disabled)
                }
                BiometricAvailability.NOT_ENROLLED ->
                    stringResource(R.string.settings_biometric_status_not_enrolled)
                BiometricAvailability.NO_HARDWARE,
                BiometricAvailability.UNAVAILABLE ->
                    stringResource(R.string.settings_biometric_status_unavailable)
            }

            val biometricUnlockLabel = stringResource(R.string.settings_biometric_unlock_title)

            VumiqSurfaceCard {
                Text(
                    text = stringResource(R.string.settings_security_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { updateBiometricUnlock(!isBiometricUnlockEnabled) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fingerprint,
                            contentDescription = null,
                            tint = colors.incomeText,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = biometricUnlockLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                    }
                    Switch(
                        checked = isBiometricUnlockEnabled,
                        onCheckedChange = { enabled -> updateBiometricUnlock(enabled) },
                        modifier = Modifier.semantics {
                            contentDescription = biometricUnlockLabel
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )

                if (bioGate.availability == BiometricAvailability.NOT_ENROLLED) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val enrollIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                                    putExtra(
                                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                        BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL
                                    )
                                }
                            } else {
                                Intent(Settings.ACTION_SECURITY_SETTINGS)
                            }
                            onLaunchSystemSheet()
                            runCatching { context.startActivity(enrollIntent) }
                                .onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }
                                }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_biometric_enroll_cta))
                    }
                }

                if (isBiometricUnlockEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = colors.hairlineBorder
                    )

                    Text(
                        text = stringResource(R.string.settings_autolock_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RelockTimeoutOption.entries.forEach { option ->
                            FilterChip(
                                selected = selectedRelockTimeout == option,
                                onClick = {
                                    coroutineScope.launch {
                                        settingsRepository.setRelockTimeoutSeconds(option.seconds)
                                        onChangeRelockTimeout(option)
                                    }
                                },
                                label = { Text(if (isFr) option.labelFr else option.labelEn) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onLockLedgerNow,
                        enabled = bioGate.availability == BiometricAvailability.AVAILABLE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_lock_now_cta))
                    }
                }
            }
        }

        // Customer & Supplier Payment Due-Date Reminders (Phase 4)
        item {
            VumiqSurfaceCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { requestToggleDueReminders(!dueReminderPrefs.enabled) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            tint = colors.incomeText,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isFr) "Rappels d'échéance clients & fournisseurs" else "Payment Due-Date Reminders",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isFr) {
                                    "Alertes hors ligne pour créances clients et dettes fournisseurs non réglées."
                                } else {
                                    "Offline notifications for unsettled customer receivables and supplier payables."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Switch(
                        checked = dueReminderPrefs.enabled,
                        onCheckedChange = requestToggleDueReminders
                    )
                }

                if (notificationPermissionDeniedNote) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isFr) {
                            "Autorisation de notification Android refusée. Activez les notifications pour VUMIQ Cashbook dans les paramètres du téléphone."
                        } else {
                            "Android notification permission was denied. Enable notifications for VUMIQ Cashbook in Phone Settings to receive due alerts."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.warningText
                    )
                }

                if (dueReminderPrefs.enabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = colors.hairlineBorder
                    )
                    Text(
                        text = if (isFr) "Préavis avant échéance" else "Advance Notice Timing",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to (if (isFr) "Jour J uniquement" else "Due day only"),
                            1 to (if (isFr) "1 jour avant" else "1 day before"),
                            3 to (if (isFr) "3 jours avant" else "3 days before")
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = dueReminderPrefs.leadDays == days,
                                onClick = { onChangeDueLeadDays(days) },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isFr) "Heure préférée (fuseau local)" else "Preferred Local Check Time",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(8 to "08:00", 9 to "09:00", 18 to "18:00").forEach { (hour, label) ->
                            FilterChip(
                                selected = dueReminderPrefs.preferredHour == hour,
                                onClick = { onChangeDuePreferredHour(hour) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // Active Business & Multi-Business Switcher
        item {
            VumiqSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFr) "Entreprises / Livres" else "Businesses",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    OutlinedButton(
                        onClick = { showNewBizSheet = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isFr) "Nouvelle" else "New Book")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.businesses.forEach { biz ->
                        FilterChip(
                            selected = biz.id == state.activeBusiness?.id,
                            onClick = { onSwitchBusiness(biz.id) },
                            label = { Text("${biz.name} (${biz.defaultCurrency.code})") }
                        )
                    }
                }
            }
        }

        // Financial Accounts Management
        item {
            VumiqSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFr) "Comptes de trésorerie" else "Cash & Bank Accounts",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    OutlinedButton(
                        onClick = { showAddAccountSheet = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isFr) "Ajouter" else "Add Account")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                state.accounts.forEachIndexed { idx, acc ->
                    if (idx > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = colors.hairlineBorder)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${acc.name} · ${acc.currency.code}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            MoneyMath.formatMinorAmount(
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

        // Appearance & Language
        item {
            VumiqSurfaceCard {
                Text(
                    text = if (isFr) "Apparence & Langue" else "Appearance & Language",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemePreference.SYSTEM to (if (isFr) "Système" else "System"),
                        ThemePreference.LIGHT to (if (isFr) "Clair" else "Light"),
                        ThemePreference.DARK to (if (isFr) "Sombre" else "Dark")
                    ).forEach { (pref, label) ->
                        FilterChip(
                            selected = state.themePreference == pref,
                            onClick = { onChangeTheme(pref) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.locale == LocaleCode.EN,
                        onClick = { onChangeLocale(LocaleCode.EN) },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = state.locale == LocaleCode.FR,
                        onClick = { onChangeLocale(LocaleCode.FR) },
                        label = { Text("Français") }
                    )
                }
            }
        }

        // Google Account & Two-Way Cloud Firestore Synchronization
        item {
            val cloudAcc = state.cloudAccount
            val telemetry = state.cloudSyncTelemetry
            val formattedLastSync = remember(telemetry.lastSuccessfulSyncEpochMs) {
                if (telemetry.lastSuccessfulSyncEpochMs <= 0L) {
                    null
                } else {
                    runCatching {
                        Instant.ofEpochMilli(telemetry.lastSuccessfulSyncEpochMs)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    }.getOrNull()
                }
            }

            VumiqSurfaceCard {
                Text(
                    text = stringResource(R.string.settings_cloud_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))

                when {
                    !state.isFirebaseConfigured -> {
                        Text(
                            text = stringResource(R.string.settings_cloud_unconfigured_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }

                    !cloudAcc.isSignedIn -> {
                        Text(
                            text = stringResource(R.string.settings_cloud_signed_out_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                        if (!cloudAcc.authError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = cloudAcc.authError,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.expenseText
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onSignInWithGoogle,
                            enabled = !state.isAuthenticatingCloud,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.isAuthenticatingCloud) {
                                    stringResource(R.string.settings_cloud_signing_in)
                                } else {
                                    stringResource(R.string.settings_cloud_sign_in_cta)
                                }
                            )
                        }
                    }

                    else -> {
                        val accountHeadline = listOf(
                            cloudAcc.displayName,
                            cloudAcc.email
                        ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { cloudAcc.uid }
                        val verificationBadge = if (cloudAcc.isEmailVerified) {
                            if (isFr) "Email vérifié" else "Verified email"
                        } else {
                            if (isFr) "Email non vérifié" else "Unverified email"
                        }
                        Text(
                            text = "$accountHeadline ($verificationBadge)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val syncMeta = buildList {
                            add(
                                if (isFr) {
                                    "${state.unsyncedCount} écriture(s) locale(s) en attente"
                                } else {
                                    "${state.unsyncedCount} local record(s) pending sync"
                                }
                            )
                            if (formattedLastSync != null) {
                                add(
                                    if (isFr) {
                                        "Dernière synchro : $formattedLastSync"
                                    } else {
                                        "Last synced: $formattedLastSync"
                                    }
                                )
                            }
                        }.joinToString(" · ")
                        Text(
                            text = syncMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )

                        if (telemetry.lastSyncStatus == "error" && telemetry.lastSyncError.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = telemetry.lastSyncError,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.expenseText
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onSyncCloudNow,
                                enabled = !state.isSyncing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (state.isSyncing) {
                                        if (isFr) "Synchro..." else "Syncing..."
                                    } else {
                                        if (isFr) "Synchroniser" else "Sync Now"
                                    }
                                )
                            }

                            OutlinedButton(
                                onClick = onSignOutFromGoogle,
                                enabled = !state.isSyncing,
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_cloud_sign_out_cta))
                            }
                        }
                    }
                }
            }
        }

        // Offline Local Backup & Recovery
        item {
            VumiqSurfaceCard {
                Text(
                    text = if (isFr) "Sauvegarde locale & Restauration JSON" else "Offline Backup & JSON Recovery",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isFr) {
                        "Base SQLite hors ligne active (${state.allTransactions.size} écriture(s) protégées). Exportez ou restaurez vos sauvegardes JSON compatibles Web et Android."
                    } else {
                        "Offline Room SQLite storage active (${state.allTransactions.size} durable record(s)). Export or restore Web-compatible JSON backups anytime."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportJsonBackup,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isFr) "Sauvegarder" else "Export JSON")
                    }

                    OutlinedButton(
                        onClick = onRestoreJsonBackup,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isFr) "Restaurer" else "Restore JSON")
                    }
                }
            }
        }

        // Immutable Audit Trail Preview
        if (state.auditEvents.isNotEmpty()) {
            item {
                VumiqSurfaceCard {
                    Text(
                        text = if (isFr) "Journal d'audit récent" else "Immutable Audit Trail",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    state.auditEvents.take(5).forEachIndexed { idx, evt ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = colors.hairlineBorder
                            )
                        }
                        Text(
                            text = evt.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "${evt.actorName} · ${evt.createdAt.take(16).replace('T', ' ')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        // Permanent Ownership Attribution
        item {
            OwnershipFooterBlock()
        }
    }

    if (showAddAccountSheet) {
        var accName by remember { mutableStateOf("") }
        var accType by remember { mutableStateOf(AccountType.BANK) }
        var accCurrency by remember { mutableStateOf(state.selectedCurrency) }
        var openingText by remember { mutableStateOf("0") }
        var errMsg by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { showAddAccountSheet = false },
            containerColor = colors.cardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isFr) "Ajouter un compte" else "New Cash / Bank Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = accName,
                    onValueChange = { accName = it; errMsg = null },
                    label = { Text(if (isFr) "Nom du compte" else "Account name (e.g. M-Pesa, Rawbank)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AccountType.entries.forEach { t ->
                        FilterChip(
                            selected = accType == t,
                            onClick = { accType = t },
                            label = { Text(if (isFr) t.labelFr else t.labelEn) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CurrencyCode.entries.forEach { c ->
                        FilterChip(
                            selected = accCurrency == c,
                            onClick = { accCurrency = c },
                            label = { Text(c.code) }
                        )
                    }
                }
                OutlinedTextField(
                    value = openingText,
                    onValueChange = { openingText = it; errMsg = null },
                    label = { Text("Opening balance (${accCurrency.code})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errMsg != null) {
                    Text(errMsg.orEmpty(), color = colors.expenseText)
                }
                Button(
                    onClick = {
                        when (val p = MoneyMath.parseAmountToMinor(
                            openingText.ifBlank { "0" },
                            accCurrency,
                            allowZero = true
                        )) {
                            is ParsedAmountOutcome.Failure -> errMsg = p.message
                            is ParsedAmountOutcome.Success -> {
                                onAddAccount(
                                    accName,
                                    accType,
                                    accCurrency,
                                    p.minorUnits,
                                    { showAddAccountSheet = false },
                                    { err -> errMsg = err }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Créer le compte" else "Save Account")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    if (showNewBizSheet) {
        var bizName by remember { mutableStateOf("") }
        var bizCur by remember { mutableStateOf(CurrencyCode.USD) }
        var accName by remember { mutableStateOf("Main Cash Drawer") }
        var errMsg by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { showNewBizSheet = false },
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
                    text = if (isFr) "Nouvelle entreprise" else "Create Separate Business Book",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = bizName,
                    onValueChange = { bizName = it; errMsg = null },
                    label = { Text(if (isFr) "Nom de l'entreprise" else "Business name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CurrencyCode.entries.forEach { c ->
                        FilterChip(
                            selected = bizCur == c,
                            onClick = { bizCur = c },
                            label = { Text(c.code) }
                        )
                    }
                }
                OutlinedTextField(
                    value = accName,
                    onValueChange = { accName = it },
                    label = { Text(if (isFr) "Premier compte" else "Initial cash account") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errMsg != null) {
                    Text(errMsg.orEmpty(), color = colors.expenseText)
                }
                Button(
                    onClick = {
                        onCreateNewBusiness(
                            bizName,
                            bizCur,
                            accName,
                            AccountType.CASH,
                            0L,
                            { showNewBizSheet = false },
                            { err -> errMsg = err }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isFr) "Créer" else "Create Business")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}
