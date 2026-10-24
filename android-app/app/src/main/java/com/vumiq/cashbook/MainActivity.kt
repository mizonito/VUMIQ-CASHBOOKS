package com.vumiq.cashbook

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.security.BiometricAuthManager
import com.vumiq.cashbook.ui.AppTab
import com.vumiq.cashbook.ui.CashbookUiState
import com.vumiq.cashbook.ui.CashbookViewModel
import com.vumiq.cashbook.ui.components.RestoreBackupDialog
import com.vumiq.cashbook.ui.components.VumiqBrandEmblem
import com.vumiq.cashbook.ui.components.VumiqLaunchSplashOverlay
import com.vumiq.cashbook.ui.screens.BiometricLockScreen
import com.vumiq.cashbook.ui.screens.HomeScreen
import com.vumiq.cashbook.ui.screens.OnboardingScreen
import com.vumiq.cashbook.ui.screens.PartiesScreen
import com.vumiq.cashbook.ui.screens.ReportsScreen
import com.vumiq.cashbook.ui.screens.SettingsView
import com.vumiq.cashbook.ui.screens.TransactionDetailSheet
import com.vumiq.cashbook.ui.screens.TransactionEntrySheet
import com.vumiq.cashbook.ui.screens.TransactionsScreen
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.VumiqCashbookTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: CashbookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold system splash until Jetpack DataStore 'Biometric Unlock' preference is checked at boot
        splashScreen.setKeepOnScreenCondition {
            !viewModel.uiState.value.isBootCheckComplete
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current

            // Observe app background/foreground transitions to enforce auto-relock policy
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onAppMovedToBackground()
                        Lifecycle.Event.ON_START -> viewModel.onAppMovedToForeground()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Mask task-switcher thumbnails while locked
            LaunchedEffect(state.biometricGate.requiresUnlock) {
                if (state.biometricGate.requiresUnlock) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val restoreBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("Unable to open selected file stream.")
                    }.onSuccess { jsonContent ->
                        viewModel.inspectBackupFromJsonContent(jsonContent)
                    }.onFailure { err ->
                        viewModel.reportBackupFileReadError(err.localizedMessage ?: "Invalid file")
                    }
                }
            }

            val launchBackupPicker = {
                viewModel.markSystemSheetLaunched()
                restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            }

            LaunchedEffect(viewModel) {
                viewModel.shareEvent.collect { evt ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = evt.mimeType
                        putExtra(Intent.EXTRA_STREAM, evt.uri)
                        putExtra(Intent.EXTRA_SUBJECT, evt.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, evt.title))
                }
            }

            VumiqCashbookTheme(themePreference = state.themePreference) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isBootCheckComplete) {
                        when {
                            // Boot Sequence Step 2: BiometricPrompt (gated by DataStore 'Biometric Unlock' preference)
                            // Triggered immediately once the VUMIQ launch splash finishes when enabled.
                            !state.showSplashOverlay && state.biometricGate.requiresUnlock -> {
                                BiometricLockScreen(
                                    autoPromptTriggerKey = state.biometricAutoTriggerKey,
                                    errorMessage = state.biometricGate.lastAuthError,
                                    onRequestBiometricPrompt = {
                                        BiometricAuthManager.authenticate(
                                            activity = this@MainActivity,
                                            onOutcome = viewModel::onBiometricAuthOutcome
                                        )
                                    }
                                )
                            }

                            // Hold rendering financial surfaces while splash is still visible and locked
                            state.showSplashOverlay && state.biometricGate.requiresUnlock -> Unit

                            // Startup Sequence Step 3: Unlocked -> Onboarding or Ledger
                            state.activeBusiness == null -> {
                                val onboardingSnackbar = remember { SnackbarHostState() }
                                LaunchedEffect(state.bannerMessage) {
                                    val msg = state.bannerMessage
                                    if (!msg.isNullOrBlank()) {
                                        onboardingSnackbar.showSnackbar(msg)
                                        viewModel.dismissBanner()
                                    }
                                }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    OnboardingScreen(
                                        locale = state.locale,
                                        isFirebaseConfigured = state.isFirebaseConfigured,
                                        isSyncingCloud = state.isSyncing || state.isAuthenticatingCloud,
                                        onCreateBusiness = { bizName, cur, accName, accType, openingMinor, onErr ->
                                            viewModel.createBusinessOnboarding(
                                                businessName = bizName,
                                                currency = cur,
                                                initialAccountName = accName,
                                                initialAccountType = accType,
                                                openingBalanceMinor = openingMinor,
                                                onError = onErr
                                            )
                                        },
                                        onImportExistingBackup = launchBackupPicker,
                                        onSignInAndPullCloud = {
                                            viewModel.signInWithGoogle(this@MainActivity)
                                        }
                                    )
                                    SnackbarHost(
                                        hostState = onboardingSnackbar,
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                    )
                                }
                            }

                            else -> {
                                VumiqMainShell(
                                    state = state,
                                    viewModel = viewModel,
                                    onPickBackupJson = launchBackupPicker,
                                    onSignInWithGoogle = { viewModel.signInWithGoogle(this@MainActivity) }
                                )
                            }
                        }
                    }

                    val backupPreview = state.pendingBackupPreview
                    if (backupPreview != null && !state.biometricGate.requiresUnlock) {
                        RestoreBackupDialog(
                            preview = backupPreview,
                            locale = state.locale,
                            onConfirmRestore = viewModel::confirmPendingBackupRestore,
                            onDismiss = viewModel::dismissPendingBackupRestore
                        )
                    }

                    // Startup Sequence Step 1: VUMIQ Branded Splash ("Owned by Mr. Amin M. Gutale")
                    VumiqLaunchSplashOverlay(visible = state.showSplashOverlay)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VumiqMainShell(
    state: CashbookUiState,
    viewModel: CashbookViewModel,
    onPickBackupJson: () -> Unit,
    onSignInWithGoogle: () -> Unit
) {
    val colors = LocalVumiqColors.current
    val isFr = state.locale == LocaleCode.FR
    val snackbarHostState = remember { SnackbarHostState() }

    var entrySheetRequest by remember { mutableStateOf<Pair<TransactionType, String>?>(null) }
    var inspectedTransactionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.bannerMessage) {
        val msg = state.bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Clean 3-zone mobile Top App Bar: Brand/Business title + single primary Record CTA
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.canvas
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VumiqBrandEmblem(size = 28.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = state.activeBusiness?.name ?: "VUMIQ Cashbook",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { entrySheetRequest = TransactionType.INCOME to "" },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isFr) "Saisir" else "Record")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = colors.cardSurface) {
                NavigationBarItem(
                    selected = state.currentTab == AppTab.HOME,
                    onClick = { viewModel.selectTab(AppTab.HOME) },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = state.currentTab == AppTab.TRANSACTIONS,
                    onClick = { viewModel.selectTab(AppTab.TRANSACTIONS) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_transactions), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = state.currentTab == AppTab.PARTIES,
                    onClick = { viewModel.selectTab(AppTab.PARTIES) },
                    icon = { Icon(Icons.Outlined.PeopleOutline, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_parties), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = state.currentTab == AppTab.REPORTS,
                    onClick = { viewModel.selectTab(AppTab.REPORTS) },
                    icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_reports), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = state.currentTab == AppTab.SETTINGS,
                    onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings), maxLines = 1) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state.currentTab) {
                AppTab.HOME -> HomeScreen(
                    state = state,
                    onSelectCurrency = viewModel::selectCurrency,
                    onSelectDatePreset = viewModel::setDatePreset,
                    onOpenQuickEntry = { type -> entrySheetRequest = type to "" },
                    onInspectTransaction = { tx -> inspectedTransactionId = tx.id },
                    onNavigateToLedger = { viewModel.selectTab(AppTab.TRANSACTIONS) },
                    onNavigateToParties = { viewModel.selectTab(AppTab.PARTIES) }
                )

                AppTab.TRANSACTIONS -> TransactionsScreen(
                    state = state,
                    onSearchChange = viewModel::setSearchQuery,
                    onTypeFilterChange = viewModel::setTypeFilter,
                    onAccountFilterChange = viewModel::setAccountFilter,
                    onToggleIncludeVoided = viewModel::setIncludeVoided,
                    onOpenNewEntry = { type -> entrySheetRequest = type to "" },
                    onInspectTransaction = { tx -> inspectedTransactionId = tx.id }
                )

                AppTab.PARTIES -> PartiesScreen(
                    state = state,
                    onAddParty = viewModel::addParty,
                    onQuickSettleParty = { partyId, settleType ->
                        entrySheetRequest = settleType to partyId
                    },
                    onUpdatePartyDueDate = viewModel::updatePartyDueDate
                )

                AppTab.REPORTS -> ReportsScreen(
                    state = state,
                    onSelectDatePreset = viewModel::setDatePreset,
                    onSelectCurrency = viewModel::selectCurrency,
                    onExportPdf = viewModel::exportPdfStatement,
                    onExportCsv = viewModel::exportCsvRegister
                )

                AppTab.SETTINGS -> SettingsView(
                    state = state,
                    settingsRepository = viewModel.settingsRepository,
                    onSwitchBusiness = viewModel::switchBusiness,
                    onCreateNewBusiness = { name, cur, accName, accType, openMinor, onOk, onErr ->
                        viewModel.createBusinessOnboarding(
                            businessName = name,
                            currency = cur,
                            initialAccountName = accName,
                            initialAccountType = accType,
                            openingBalanceMinor = openMinor,
                            onSuccess = onOk,
                            onError = onErr
                        )
                    },
                    onAddAccount = viewModel::addAccount,
                    onChangeTheme = viewModel::setThemePreference,
                    onChangeLocale = viewModel::setLocale,
                    onToggleBiometricUnlock = viewModel::setBiometricUnlockEnabled,
                    onChangeRelockTimeout = viewModel::setRelockTimeout,
                    onLockLedgerNow = viewModel::lockLedgerNow,
                    onLaunchSystemSheet = viewModel::markSystemSheetLaunched,
                    onSignInWithGoogle = onSignInWithGoogle,
                    onSignOutFromGoogle = viewModel::signOutFromGoogle,
                    onSyncCloudNow = viewModel::syncToCloudNow,
                    onExportJsonBackup = viewModel::exportJsonBackupArchive,
                    onRestoreJsonBackup = onPickBackupJson,
                    onToggleDueReminders = viewModel::setDueRemindersEnabled,
                    onChangeDueLeadDays = viewModel::setDueReminderLeadDays,
                    onChangeDuePreferredHour = viewModel::setDueReminderPreferredHour
                )
            }
        }
    }

    val activeEntryReq = entrySheetRequest
    if (activeEntryReq != null) {
        TransactionEntrySheet(
            state = state,
            initialType = activeEntryReq.first,
            preselectedPartyId = activeEntryReq.second,
            onLaunchSystemSheet = viewModel::markSystemSheetLaunched,
            onDismiss = { entrySheetRequest = null },
            onSubmit = { idemKey, draft, onErr ->
                viewModel.recordTransaction(
                    idempotencyKey = idemKey,
                    draft = draft,
                    onSuccess = { entrySheetRequest = null },
                    onError = onErr
                )
            }
        )
    }

    val activeInspected = inspectedTransactionId?.let { id ->
        state.allTransactions.firstOrNull { it.id == id }
    }
    if (activeInspected != null) {
        TransactionDetailSheet(
            tx = activeInspected,
            locale = state.locale,
            onLaunchSystemSheet = viewModel::markSystemSheetLaunched,
            onAttachOrReplaceReceipt = { uri, displayName ->
                viewModel.attachOrReplaceReceipt(
                    transactionId = activeInspected.id,
                    sourceUri = uri,
                    preferredDisplayName = displayName
                )
            },
            onRemoveReceipt = {
                viewModel.removeReceipt(transactionId = activeInspected.id)
            },
            onDismiss = { inspectedTransactionId = null },
            onSaveEdit = { amt, desc, dt, notes, reason, onErr ->
                viewModel.editTransaction(
                    transactionId = activeInspected.id,
                    amountMinor = amt,
                    description = desc,
                    date = dt,
                    notes = notes,
                    reason = reason,
                    onSuccess = { inspectedTransactionId = null },
                    onError = onErr
                )
            },
            onConfirmVoid = { reason, onErr ->
                viewModel.voidTransaction(
                    transactionId = activeInspected.id,
                    reason = reason,
                    onSuccess = { inspectedTransactionId = null },
                    onError = onErr
                )
            }
        )
    }
}
