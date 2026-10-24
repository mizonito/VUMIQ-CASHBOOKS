package com.vumiq.cashbook.ui

import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.vumiq.cashbook.data.CashbookRepository
import com.vumiq.cashbook.data.local.CashbookDatabase
import com.vumiq.cashbook.data.preferences.CloudSyncTelemetrySnapshot
import com.vumiq.cashbook.data.preferences.SettingsRepository
import com.vumiq.cashbook.data.preferences.UserPreferencesSnapshot
import com.vumiq.cashbook.data.preferences.UserPreferencesStore
import com.vumiq.cashbook.data.remote.CloudSyncWorker
import com.vumiq.cashbook.data.remote.FirestoreSyncManager
import com.vumiq.cashbook.domain.Account
import com.vumiq.cashbook.domain.AccountStatus
import com.vumiq.cashbook.domain.AccountType
import com.vumiq.cashbook.domain.AccountingEngine
import com.vumiq.cashbook.domain.AuditEvent
import com.vumiq.cashbook.domain.BackupInspectionPreview
import com.vumiq.cashbook.domain.BiometricAvailability
import com.vumiq.cashbook.domain.BiometricGatePolicy
import com.vumiq.cashbook.domain.BiometricGateSnapshot
import com.vumiq.cashbook.domain.Business
import com.vumiq.cashbook.domain.CashbookTransaction
import com.vumiq.cashbook.domain.Category
import com.vumiq.cashbook.domain.CategoryBreakdownItem
import com.vumiq.cashbook.domain.CategoryKind
import com.vumiq.cashbook.domain.CurrencyCode
import com.vumiq.cashbook.domain.CurrencyPeriodSummary
import com.vumiq.cashbook.domain.DateFilterPreset
import com.vumiq.cashbook.domain.DateRange
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.Party
import com.vumiq.cashbook.domain.PartyComputedBalance
import com.vumiq.cashbook.domain.PartyType
import com.vumiq.cashbook.domain.RelockTimeoutOption
import com.vumiq.cashbook.domain.RestoreMode
import com.vumiq.cashbook.domain.ThemePreference
import com.vumiq.cashbook.domain.TransactionDraftInput
import com.vumiq.cashbook.domain.TransactionFilterQuery
import com.vumiq.cashbook.domain.TransactionSortMode
import com.vumiq.cashbook.domain.TransactionType
import com.vumiq.cashbook.domain.TransactionTypeFilter
import com.vumiq.cashbook.reminders.DueDateReminderWorker
import com.vumiq.cashbook.security.BiometricAuthManager
import com.vumiq.cashbook.security.BiometricAuthOutcome
import com.vumiq.cashbook.security.CloudAccountSnapshot
import com.vumiq.cashbook.security.GoogleAuthManager
import com.vumiq.cashbook.security.GoogleSignInResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class AppTab {
    HOME,
    TRANSACTIONS,
    PARTIES,
    REPORTS,
    SETTINGS
}

data class ShareExportEvent(
    val uri: Uri,
    val mimeType: String,
    val title: String
)

private data class BusinessRecordsBundle(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val parties: List<Party> = emptyList(),
    val transactions: List<CashbookTransaction> = emptyList(),
    val auditEvents: List<AuditEvent> = emptyList()
)

private data class UiInteractionControls(
    val currentTab: AppTab = AppTab.HOME,
    val datePreset: DateFilterPreset = DateFilterPreset.MONTH,
    val customDateRange: DateRange = DateRange(),
    val searchQuery: String = "",
    val typeFilter: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val filterAccountId: String = "",
    val filterCategoryId: String = "",
    val includeVoided: Boolean = false,
    val sortMode: TransactionSortMode = TransactionSortMode.DATE_DESC,
    val isSyncing: Boolean = false,
    val pendingBackupPreview: BackupInspectionPreview? = null,
    val bannerMessage: String? = null
)

private data class BiometricRuntimeHolder(
    val bootCheckCompleted: Boolean = false,
    val snapshot: BiometricGateSnapshot = BiometricGateSnapshot(),
    val autoTriggerKey: Int = 1,
    val suppressNextBackgroundRelock: Boolean = false
)

private data class RuntimeSecurityAndCloudHolder(
    val bioRuntime: BiometricRuntimeHolder = BiometricRuntimeHolder(),
    val cloudAccount: CloudAccountSnapshot = CloudAccountSnapshot(),
    val syncTelemetry: CloudSyncTelemetrySnapshot = CloudSyncTelemetrySnapshot()
)

data class CashbookUiState(
    val isHydrated: Boolean = false,
    val isBootCheckComplete: Boolean = false,
    val showSplashOverlay: Boolean = true,
    val biometricGate: BiometricGateSnapshot = BiometricGateSnapshot(),
    val biometricAutoTriggerKey: Int = 1,
    val businesses: List<Business> = emptyList(),
    val activeBusiness: Business? = null,
    val accounts: List<Account> = emptyList(),
    val accountBalances: Map<String, Long> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val parties: List<Party> = emptyList(),
    val partyBalances: Map<String, PartyComputedBalance> = emptyMap(),
    val allTransactions: List<CashbookTransaction> = emptyList(),
    val filteredTransactions: List<CashbookTransaction> = emptyList(),
    val auditEvents: List<AuditEvent> = emptyList(),
    val availableCurrencies: List<CurrencyCode> = listOf(CurrencyCode.USD),
    val selectedCurrency: CurrencyCode = CurrencyCode.USD,
    val periodSummaries: Map<CurrencyCode, CurrencyPeriodSummary> = emptyMap(),
    val activeSummary: CurrencyPeriodSummary? = null,
    val expenseBreakdown: List<CategoryBreakdownItem> = emptyList(),
    val incomeBreakdown: List<CategoryBreakdownItem> = emptyList(),
    val activeDateRange: DateRange = DateRange(),
    val datePreset: DateFilterPreset = DateFilterPreset.MONTH,
    val searchQuery: String = "",
    val typeFilter: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val filterAccountId: String = "",
    val filterCategoryId: String = "",
    val includeVoided: Boolean = false,
    val sortMode: TransactionSortMode = TransactionSortMode.DATE_DESC,
    val currentTab: AppTab = AppTab.HOME,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val locale: LocaleCode = LocaleCode.EN,
    val displayName: String = "Business Owner",
    val isFirebaseConfigured: Boolean = false,
    val cloudAccount: CloudAccountSnapshot = CloudAccountSnapshot(),
    val cloudSyncTelemetry: CloudSyncTelemetrySnapshot = CloudSyncTelemetrySnapshot(),
    val unsyncedCount: Int = 0,
    val isSyncing: Boolean = false,
    val isAuthenticatingCloud: Boolean = false,
    val pendingBackupPreview: BackupInspectionPreview? = null,
    val bannerMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CashbookViewModel(application: Application) : AndroidViewModel(application) {

    private val database = CashbookDatabase.getInstance(application)
    val settingsRepository = SettingsRepository(application)
    private val preferencesStore = UserPreferencesStore(application)
    private val syncManager = FirestoreSyncManager(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val repository = CashbookRepository(
        context = application,
        dao = database.cashbookDao(),
        preferencesStore = preferencesStore,
        syncManager = syncManager,
        settingsRepository = settingsRepository
    )

    private val controlsFlow = MutableStateFlow(UiInteractionControls())
    private val splashVisibleFlow = MutableStateFlow(true)
    private val biometricRuntimeFlow = MutableStateFlow(BiometricRuntimeHolder())
    private val cloudAccountFlow = MutableStateFlow(googleAuthManager.currentAccountSnapshot())
    private val isAuthenticatingCloudFlow = MutableStateFlow(false)
    private var stagedBackupRawJson: String? = null

    private val _shareEvent = MutableSharedFlow<ShareExportEvent>()
    val shareEvent = _shareEvent.asSharedFlow()

    /**
     * Reactive DataStore state for 'biometric_unlock_enabled' from SettingsRepository,
     * observed directly by SettingsView.
     */
    val biometricUnlockEnabled: StateFlow<Boolean> = settingsRepository.biometricUnlockEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsRepository.DEFAULT_BIOMETRIC_UNLOCK_ENABLED
        )

    init {
        performAppBootSequence()
    }

    /**
     * App boot security sequence:
     * 1. Ensures local owner UID exists in DataStore.
     * 2. Reads 'biometric_unlock_enabled' & relock timeout from SettingsRepository (DataStore).
     * 3. Checks Android BiometricManager sensor & enrollment readiness.
     * 4. Evaluates cold-start BiometricGatePolicy before releasing the system splash screen.
     */
    private fun performAppBootSequence() {
        viewModelScope.launch {
            preferencesStore.getOrCreateLocalOwnerUid()
            val isBiometricEnabledAtBoot = settingsRepository.isBiometricUnlockEnabled()
            val relockTimeoutSeconds = settingsRepository.getRelockTimeoutSeconds()
            val availability = BiometricAuthManager.checkAvailability(getApplication())
            val coldStartGate = BiometricGatePolicy.evaluateColdStart(
                isBiometricUnlockEnabled = isBiometricEnabledAtBoot,
                availability = availability,
                relockTimeoutSeconds = relockTimeoutSeconds
            )
            biometricRuntimeFlow.value = BiometricRuntimeHolder(
                bootCheckCompleted = true,
                snapshot = coldStartGate,
                autoTriggerKey = 1
            )
            cloudAccountFlow.value = googleAuthManager.currentAccountSnapshot()
            if (cloudAccountFlow.value.isSignedIn) {
                CloudSyncWorker.enqueueWhenConnected(getApplication())
            }
            val duePrefs = settingsRepository.getDueReminderPreferences()
            if (duePrefs.enabled) {
                DueDateReminderWorker.syncSchedule(
                    context = getApplication(),
                    enabled = true,
                    preferredHour = duePrefs.preferredHour,
                    runImmediateCheck = true
                )
            }
            delay(850L)
            splashVisibleFlow.value = false
        }
    }

    private val activeBusinessFlow = combine(
        repository.observeBusinesses(),
        preferencesStore.preferencesFlow
    ) { businesses, prefs ->
        val selected = businesses.firstOrNull { it.id == prefs.activeBusinessId }
            ?: businesses.firstOrNull()
        Triple(businesses, selected, prefs)
    }

    private val recordsFlow = activeBusinessFlow.flatMapLatest { (_, activeBiz, _) ->
        if (activeBiz == null) {
            flowOf(BusinessRecordsBundle())
        } else {
            combine(
                repository.observeAccounts(activeBiz.id),
                repository.observeCategories(activeBiz.id),
                repository.observeParties(activeBiz.id),
                repository.observeTransactions(activeBiz.id),
                repository.observeAuditEvents(activeBiz.id)
            ) { accounts, categories, parties, transactions, audits ->
                BusinessRecordsBundle(
                    accounts = accounts,
                    categories = categories,
                    parties = parties,
                    transactions = transactions,
                    auditEvents = audits
                )
            }
        }
    }

    private val securitySettingsFlow = combine(
        settingsRepository.biometricUnlockEnabledFlow,
        settingsRepository.relockTimeoutSecondsFlow,
        biometricRuntimeFlow,
        cloudAccountFlow,
        settingsRepository.cloudSyncTelemetryFlow
    ) { isBiometricUnlockEnabled, relockTimeoutSeconds, bioRuntime, cloudAcc, syncTelemetry ->
        RuntimeSecurityAndCloudHolder(
            bioRuntime = bioRuntime.copy(
                snapshot = bioRuntime.snapshot.copy(
                    isBiometricUnlockEnabled = isBiometricUnlockEnabled,
                    relockTimeout = RelockTimeoutOption.fromSeconds(relockTimeoutSeconds)
                )
            ),
            cloudAccount = cloudAcc,
            syncTelemetry = syncTelemetry
        )
    }

    val uiState: StateFlow<CashbookUiState> = combine(
        activeBusinessFlow,
        recordsFlow,
        controlsFlow,
        splashVisibleFlow,
        combine(securitySettingsFlow, isAuthenticatingCloudFlow) { holder, authBusy -> holder to authBusy }
    ) { (businesses, activeBiz, prefs), bundle, controls, showSplash, (secHolder, authBusy) ->
        buildUiState(businesses, activeBiz, prefs, bundle, controls, showSplash, secHolder, authBusy)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CashbookUiState()
    )

    private fun buildUiState(
        businesses: List<Business>,
        activeBiz: Business?,
        prefs: UserPreferencesSnapshot,
        bundle: BusinessRecordsBundle,
        controls: UiInteractionControls,
        showSplash: Boolean,
        secHolder: RuntimeSecurityAndCloudHolder,
        authBusy: Boolean
    ): CashbookUiState {
        val bioRuntime = secHolder.bioRuntime
        val todayIso = LocalDate.now().toString()
        val resolvedRange = AccountingEngine.resolveDateRangePreset(
            preset = controls.datePreset,
            referenceDateIso = todayIso,
            customRange = controls.customDateRange
        )

        val fallbackCur = activeBiz?.defaultCurrency ?: CurrencyCode.USD
        val periodSummaries = AccountingEngine.computePeriodSummariesByCurrency(
            accounts = bundle.accounts,
            parties = bundle.parties,
            transactions = bundle.transactions,
            dateRange = resolvedRange,
            fallbackCurrency = fallbackCur
        )

        val availableCurrencies = periodSummaries.keys.toList().ifEmpty { listOf(fallbackCur) }
        val preferredCurrency = CurrencyCode.fromCodeOrNull(prefs.selectedCurrencyCode)
        val selectedCurrency = if (preferredCurrency != null && preferredCurrency in availableCurrencies) {
            preferredCurrency
        } else {
            fallbackCur
        }

        val activeSummary = periodSummaries[selectedCurrency] ?: CurrencyPeriodSummary(
            currency = selectedCurrency,
            currentCashBalanceMinor = 0L,
            periodIncomeMinor = 0L,
            periodExpenseMinor = 0L,
            netIncomeMinor = 0L,
            periodCashInflowMinor = 0L,
            periodCashOutflowMinor = 0L,
            netCashMovementMinor = 0L,
            totalReceivablesMinor = 0L,
            totalPayablesMinor = 0L,
            accountCount = bundle.accounts.count { it.currency == selectedCurrency && it.status == AccountStatus.ACTIVE }
        )

        val accountBalances = AccountingEngine.computeAccountBalances(bundle.accounts, bundle.transactions)
        val partyBalances = AccountingEngine.computePartyBalances(bundle.parties, bundle.transactions)

        val filteredTransactions = AccountingEngine.filterAndSearchTransactions(
            transactions = bundle.transactions,
            accounts = bundle.accounts,
            categories = bundle.categories,
            parties = bundle.parties,
            query = TransactionFilterQuery(
                search = controls.searchQuery,
                dateRange = resolvedRange,
                typeFilter = controls.typeFilter,
                categoryId = controls.filterCategoryId,
                accountId = controls.filterAccountId,
                includeVoided = controls.includeVoided,
                sortBy = controls.sortMode
            )
        )

        val expenseBreakdown = AccountingEngine.computeCategoryBreakdown(
            categories = bundle.categories,
            transactions = bundle.transactions,
            currency = selectedCurrency,
            kind = CategoryKind.EXPENSE,
            dateRange = resolvedRange
        )
        val incomeBreakdown = AccountingEngine.computeCategoryBreakdown(
            categories = bundle.categories,
            transactions = bundle.transactions,
            currency = selectedCurrency,
            kind = CategoryKind.INCOME,
            dateRange = resolvedRange
        )

        return CashbookUiState(
            isHydrated = bioRuntime.bootCheckCompleted,
            isBootCheckComplete = bioRuntime.bootCheckCompleted,
            showSplashOverlay = showSplash,
            biometricGate = bioRuntime.snapshot,
            biometricAutoTriggerKey = bioRuntime.autoTriggerKey,
            businesses = businesses,
            activeBusiness = activeBiz,
            accounts = bundle.accounts,
            accountBalances = accountBalances,
            categories = bundle.categories,
            parties = bundle.parties,
            partyBalances = partyBalances,
            allTransactions = bundle.transactions,
            filteredTransactions = filteredTransactions,
            auditEvents = bundle.auditEvents,
            availableCurrencies = availableCurrencies,
            selectedCurrency = selectedCurrency,
            periodSummaries = periodSummaries,
            activeSummary = activeSummary,
            expenseBreakdown = expenseBreakdown,
            incomeBreakdown = incomeBreakdown,
            activeDateRange = resolvedRange,
            datePreset = controls.datePreset,
            searchQuery = controls.searchQuery,
            typeFilter = controls.typeFilter,
            filterAccountId = controls.filterAccountId,
            filterCategoryId = controls.filterCategoryId,
            includeVoided = controls.includeVoided,
            sortMode = controls.sortMode,
            currentTab = controls.currentTab,
            themePreference = prefs.theme,
            locale = prefs.locale,
            displayName = prefs.displayName,
            isFirebaseConfigured = syncManager.isFirebaseConfigured(),
            cloudAccount = secHolder.cloudAccount,
            cloudSyncTelemetry = secHolder.syncTelemetry,
            unsyncedCount = bundle.transactions.count { it.pendingCloudSync },
            isSyncing = controls.isSyncing,
            isAuthenticatingCloud = authBusy,
            pendingBackupPreview = controls.pendingBackupPreview,
            bannerMessage = controls.bannerMessage
        )
    }

    fun onBiometricAuthOutcome(outcome: BiometricAuthOutcome) {
        biometricRuntimeFlow.update { current ->
            val updated = when (outcome) {
                BiometricAuthOutcome.Succeeded ->
                    BiometricGatePolicy.onAuthenticationSucceeded(current.snapshot)
                BiometricAuthOutcome.CancelledByUser ->
                    BiometricGatePolicy.onAuthenticationError(current.snapshot, null)
                is BiometricAuthOutcome.Error ->
                    BiometricGatePolicy.onAuthenticationError(current.snapshot, outcome.message)
            }
            current.copy(snapshot = updated)
        }
    }

    /**
     * Persists the 'biometric_unlock_enabled' boolean key via SettingsRepository (DataStore)
     * and synchronizes the active runtime gate state so the next app boot reflects it immediately.
     */
    fun setBiometricUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBiometricUnlockEnabled(enabled)
            val availability = BiometricAuthManager.checkAvailability(getApplication())
            biometricRuntimeFlow.update { current ->
                current.copy(
                    snapshot = BiometricGatePolicy.onBiometricPreferenceChanged(
                        current = current.snapshot,
                        enabled = enabled,
                        freshAvailability = availability
                    )
                )
            }
            val isFr = uiState.value.locale == LocaleCode.FR
            val feedback = when {
                !enabled -> if (isFr) {
                    "Déverrouillage biométrique désactivé."
                } else {
                    "Biometric Unlock disabled."
                }
                availability == BiometricAvailability.AVAILABLE -> if (isFr) {
                    "Déverrouillage biométrique activé pour le prochain démarrage."
                } else {
                    "Biometric Unlock saved and active for next app boot."
                }
                else -> if (isFr) {
                    "Préférence enregistrée · Configurez une empreinte ou un visage dans Android."
                } else {
                    "Preference saved · Enroll a fingerprint or face in Phone Settings to activate."
                }
            }
            postBanner(feedback)
        }
    }

    fun setRelockTimeout(option: RelockTimeoutOption) {
        viewModelScope.launch {
            settingsRepository.setRelockTimeoutSeconds(option.seconds)
            biometricRuntimeFlow.update { current ->
                current.copy(
                    snapshot = current.snapshot.copy(relockTimeout = option)
                )
            }
        }
    }

    fun lockLedgerNow() {
        val availability = BiometricAuthManager.checkAvailability(getApplication())
        if (availability != BiometricAvailability.AVAILABLE) {
            postBanner("Enroll a fingerprint, face, or screen lock in phone Settings first.")
            return
        }
        viewModelScope.launch {
            settingsRepository.setBiometricUnlockEnabled(true)
        }
        biometricRuntimeFlow.update { current ->
            current.copy(
                snapshot = BiometricGatePolicy.onLockManually(
                    current.snapshot.copy(
                        isBiometricUnlockEnabled = true,
                        availability = availability
                    )
                ),
                autoTriggerKey = current.autoTriggerKey + 1
            )
        }
    }

    fun markSystemSheetLaunched() {
        biometricRuntimeFlow.update { it.copy(suppressNextBackgroundRelock = true) }
    }

    fun onAppMovedToBackground() {
        biometricRuntimeFlow.update { current ->
            if (!current.bootCheckCompleted) return@update current
            val updated = BiometricGatePolicy.onAppBackgrounded(
                current = current.snapshot,
                nowEpochMs = System.currentTimeMillis(),
                suppressRelockForSystemSheet = current.suppressNextBackgroundRelock
            )
            current.copy(
                snapshot = updated,
                suppressNextBackgroundRelock = false
            )
        }
    }

    fun onAppMovedToForeground() {
        val freshAvailability = BiometricAuthManager.checkAvailability(getApplication())
        biometricRuntimeFlow.update { current ->
            if (!current.bootCheckCompleted) return@update current
            val wasAuthenticated = current.snapshot.isSessionAuthenticated
            val updated = BiometricGatePolicy.onAppForegrounded(
                current = current.snapshot,
                nowEpochMs = System.currentTimeMillis(),
                freshAvailability = freshAvailability
            )
            val retriggered = wasAuthenticated && updated.requiresUnlock
            current.copy(
                snapshot = updated,
                autoTriggerKey = if (retriggered) current.autoTriggerKey + 1 else current.autoTriggerKey
            )
        }
    }

    fun selectTab(tab: AppTab) {
        controlsFlow.update { it.copy(currentTab = tab) }
    }

    fun dismissBanner() {
        controlsFlow.update { it.copy(bannerMessage = null) }
    }

    private fun postBanner(msg: String) {
        controlsFlow.update { it.copy(bannerMessage = msg) }
    }

    fun setDatePreset(preset: DateFilterPreset) {
        controlsFlow.update { it.copy(datePreset = preset) }
    }

    fun setSearchQuery(query: String) {
        controlsFlow.update { it.copy(searchQuery = query) }
    }

    fun setTypeFilter(filter: TransactionTypeFilter) {
        controlsFlow.update { it.copy(typeFilter = filter) }
    }

    fun setAccountFilter(accountId: String) {
        controlsFlow.update { it.copy(filterAccountId = accountId) }
    }

    fun setIncludeVoided(include: Boolean) {
        controlsFlow.update { it.copy(includeVoided = include) }
    }

    fun selectCurrency(currency: CurrencyCode) {
        viewModelScope.launch {
            preferencesStore.setSelectedCurrencyCode(currency.code)
        }
    }

    fun switchBusiness(businessId: String) {
        viewModelScope.launch {
            preferencesStore.setActiveBusinessId(businessId)
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            preferencesStore.setTheme(theme)
        }
    }

    fun setLocale(locale: LocaleCode) {
        viewModelScope.launch {
            preferencesStore.setLocale(locale)
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(locale.wireValue)
            )
        }
    }

    fun createBusinessOnboarding(
        businessName: String,
        currency: CurrencyCode,
        initialAccountName: String,
        initialAccountType: AccountType,
        openingBalanceMinor: Long,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                repository.createBusinessWithPrimaryAccount(
                    businessName = businessName,
                    defaultCurrency = currency,
                    initialAccountName = initialAccountName,
                    initialAccountType = initialAccountType,
                    openingBalanceMinor = openingBalanceMinor
                )
            }.onSuccess { biz ->
                postBanner("Created ${biz.name} (${biz.defaultCurrency.code})")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Unable to create business.")
            }
        }
    }

    fun addAccount(
        name: String,
        accountType: AccountType,
        currency: CurrencyCode,
        openingBalanceMinor: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.createAccount(bizId, name, accountType, currency, openingBalanceMinor)
            }.onSuccess { acc ->
                postBanner("Account \"${acc.name}\" added.")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Could not create account.")
            }
        }
    }

    fun addCategory(
        name: String,
        kind: CategoryKind,
        color: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.createCategory(bizId, name, kind, color)
            }.onSuccess {
                postBanner("Category \"${it.name}\" saved.")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Could not add category.")
            }
        }
    }

    fun addParty(
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
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.createParty(
                    businessId = bizId,
                    name = name,
                    partyType = partyType,
                    currency = currency,
                    phone = phone,
                    address = address,
                    notes = notes,
                    openingReceivableMinor = openingReceivableMinor,
                    openingPayableMinor = openingPayableMinor,
                    dueDate = dueDate
                )
            }.onSuccess { party ->
                postBanner("${party.name} added to Parties.")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Could not save party.")
            }
        }
    }

    fun updatePartyDueDate(
        partyId: String,
        dueDate: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.updatePartyDueDate(bizId, partyId, dueDate)
            }.onSuccess { updated ->
                val isFr = uiState.value.locale == LocaleCode.FR
                val msg = if (updated.dueDate.isEmpty()) {
                    if (isFr) "Échéance effacée pour ${updated.name}." else "Cleared due date for ${updated.name}."
                } else {
                    if (isFr) "Échéance enregistrée (${updated.dueDate}) pour ${updated.name}." else "Due date saved (${updated.dueDate}) for ${updated.name}."
                }
                postBanner(msg)
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Invalid due date.")
            }
        }
    }

    fun setDueRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDueRemindersEnabled(enabled)
            val prefs = settingsRepository.getDueReminderPreferences()
            DueDateReminderWorker.syncSchedule(
                context = getApplication(),
                enabled = enabled,
                preferredHour = prefs.preferredHour,
                runImmediateCheck = true
            )
            val isFr = uiState.value.locale == LocaleCode.FR
            postBanner(
                if (enabled) {
                    if (isFr) "Rappels d'échéance activés." else "Payment due-date reminders enabled."
                } else {
                    if (isFr) "Rappels d'échéance désactivés." else "Payment due-date reminders disabled."
                }
            )
        }
    }

    fun setDueReminderLeadDays(leadDays: Int) {
        viewModelScope.launch {
            settingsRepository.setDueReminderLeadDays(leadDays)
            val prefs = settingsRepository.getDueReminderPreferences()
            if (prefs.enabled) {
                DueDateReminderWorker.enqueueImmediateEvaluation(getApplication())
            }
        }
    }

    fun setDueReminderPreferredHour(hour24: Int) {
        viewModelScope.launch {
            settingsRepository.setDueReminderPreferredHour(hour24)
            val prefs = settingsRepository.getDueReminderPreferences()
            if (prefs.enabled) {
                DueDateReminderWorker.syncSchedule(
                    context = getApplication(),
                    enabled = true,
                    preferredHour = hour24,
                    runImmediateCheck = false
                )
            }
        }
    }

    fun recordTransaction(
        idempotencyKey: String,
        draft: TransactionDraftInput,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.recordTransactionWithReceiptOutcome(bizId, idempotencyKey, draft)
            }.onSuccess { (tx, receiptWarning) ->
                val isFr = uiState.value.locale == LocaleCode.FR
                val message = if (receiptWarning.isNullOrBlank()) {
                    if (isFr) "Enregistré : ${tx.description}" else "Saved: ${tx.description}"
                } else {
                    if (isFr) {
                        "Écriture enregistrée (${tx.description}) · Reçu non joint : $receiptWarning"
                    } else {
                        "Saved: ${tx.description} (Receipt not attached: $receiptWarning)"
                    }
                }
                postBanner(message)
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Validation failed.")
            }
        }
    }

    fun attachOrReplaceReceipt(
        transactionId: String,
        sourceUri: Uri,
        preferredDisplayName: String? = null,
        onError: (String) -> Unit = {}
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.attachOrReplaceTransactionReceipt(
                    businessId = bizId,
                    transactionId = transactionId,
                    sourceUri = sourceUri,
                    preferredDisplayName = preferredDisplayName
                )
            }.onSuccess { updated ->
                val isFr = uiState.value.locale == LocaleCode.FR
                postBanner(
                    if (isFr) {
                        "Reçu enregistré pour « ${updated.description} »."
                    } else {
                        "Receipt attached to \"${updated.description}\"."
                    }
                )
            }.onFailure { err ->
                val msg = err.message ?: "Could not attach receipt photo."
                onError(msg)
                postBanner(msg)
            }
        }
    }

    fun removeReceipt(
        transactionId: String,
        onError: (String) -> Unit = {}
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.removeTransactionReceipt(bizId, transactionId)
            }.onSuccess { updated ->
                val isFr = uiState.value.locale == LocaleCode.FR
                postBanner(
                    if (isFr) {
                        "Reçu retiré de « ${updated.description} »."
                    } else {
                        "Receipt removed from \"${updated.description}\"."
                    }
                )
            }.onFailure { err ->
                val msg = err.message ?: "Could not remove receipt."
                onError(msg)
                postBanner(msg)
            }
        }
    }

    fun editTransaction(
        transactionId: String,
        amountMinor: Long,
        description: String,
        date: String,
        notes: String,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.editTransaction(bizId, transactionId, amountMinor, description, date, notes, reason)
            }.onSuccess {
                postBanner("Transaction updated with audit trail.")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Could not update transaction.")
            }
        }
    }

    fun voidTransaction(
        transactionId: String,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.voidTransaction(bizId, transactionId, reason)
            }.onSuccess {
                postBanner("Transaction voided and reversed in cash balances.")
                onSuccess()
            }.onFailure { err ->
                onError(err.message ?: "Could not void transaction.")
            }
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        if (isAuthenticatingCloudFlow.value) return
        markSystemSheetLaunched()
        viewModelScope.launch {
            isAuthenticatingCloudFlow.value = true
            when (val result = googleAuthManager.signInWithGoogle(activityContext)) {
                is GoogleSignInResult.Success -> {
                    cloudAccountFlow.value = result.snapshot
                    if (result.snapshot.displayName.isNotBlank()) {
                        preferencesStore.setDisplayName(result.snapshot.displayName)
                    }
                    isAuthenticatingCloudFlow.value = false
                    controlsFlow.update { it.copy(isSyncing = true) }
                    val syncRes = repository.syncWorkspaceWithCloud()
                    controlsFlow.update {
                        it.copy(
                            isSyncing = false,
                            bannerMessage = if (syncRes.ok) {
                                "Signed in as ${result.snapshot.email.ifBlank { result.snapshot.displayName }} · ${syncRes.message}"
                            } else {
                                syncRes.message
                            }
                        )
                    }
                }
                GoogleSignInResult.Cancelled -> {
                    isAuthenticatingCloudFlow.value = false
                    cloudAccountFlow.value = googleAuthManager.currentAccountSnapshot(authError = null)
                }
                is GoogleSignInResult.Error -> {
                    isAuthenticatingCloudFlow.value = false
                    cloudAccountFlow.value = googleAuthManager.currentAccountSnapshot(authError = result.message)
                    postBanner(result.message)
                }
            }
        }
    }

    fun signOutFromGoogle() {
        viewModelScope.launch {
            googleAuthManager.signOut()
            cloudAccountFlow.value = googleAuthManager.currentAccountSnapshot(authError = null)
            val isFr = uiState.value.locale == LocaleCode.FR
            postBanner(
                if (isFr) {
                    "Déconnecté du Cloud · Vos écritures locales restent protégées sur ce téléphone."
                } else {
                    "Signed out of Google Cloud · All local Room records remain safely stored on this phone."
                }
            )
        }
    }

    fun syncToCloudNow() {
        if (controlsFlow.value.isSyncing) return
        viewModelScope.launch {
            controlsFlow.update { it.copy(isSyncing = true) }
            val res = repository.syncWorkspaceWithCloud()
            controlsFlow.update { it.copy(isSyncing = false, bannerMessage = res.message) }
        }
    }

    fun exportPdfStatement() {
        val state = uiState.value
        val biz = state.activeBusiness ?: return
        val summary = state.activeSummary ?: return
        viewModelScope.launch {
            runCatching {
                repository.exportPdfReportSafe(state, biz, summary)
            }.onSuccess { uri ->
                markSystemSheetLaunched()
                _shareEvent.emit(
                    ShareExportEvent(
                        uri = uri,
                        mimeType = "application/pdf",
                        title = "${biz.name} Statement (PDF)"
                    )
                )
            }.onFailure { err ->
                postBanner("PDF export failed: ${err.message}")
            }
        }
    }

    private suspend fun CashbookRepository.exportPdfReportSafe(
        state: CashbookUiState,
        biz: Business,
        summary: CurrencyPeriodSummary
    ): Uri = exportPdfStatement(
        business = biz,
        currency = state.selectedCurrency,
        summary = summary,
        transactions = state.filteredTransactions,
        dateRange = state.activeDateRange,
        locale = state.locale
    )

    fun exportCsvRegister() {
        val state = uiState.value
        val biz = state.activeBusiness ?: return
        viewModelScope.launch {
            runCatching {
                repository.exportCsvReport(
                    business = biz,
                    transactions = state.filteredTransactions,
                    accounts = state.accounts,
                    locale = state.locale
                )
            }.onSuccess { uri ->
                markSystemSheetLaunched()
                _shareEvent.emit(
                    ShareExportEvent(
                        uri = uri,
                        mimeType = "text/csv",
                        title = "${biz.name} Ledger (CSV)"
                    )
                )
            }.onFailure { err ->
                postBanner("CSV export failed: ${err.message}")
            }
        }
    }

    fun exportJsonBackupArchive() {
        val bizId = uiState.value.activeBusiness?.id ?: return
        viewModelScope.launch {
            runCatching {
                repository.exportJsonBackup(bizId)
            }.onSuccess { uri ->
                markSystemSheetLaunched()
                _shareEvent.emit(
                    ShareExportEvent(
                        uri = uri,
                        mimeType = "application/json",
                        title = "VUMIQ Cashbook Backup (JSON)"
                    )
                )
            }.onFailure { err ->
                postBanner("Backup export failed: ${err.message}")
            }
        }
    }

    /**
     * Validates a candidate JSON backup archive BEFORE mutating Room, opening the
     * RestoreBackupDialog so the user can choose Safe Merge vs explicit confirmed Replace.
     */
    fun inspectBackupFromJsonContent(rawJson: String) {
        viewModelScope.launch {
            runCatching {
                repository.inspectBackupJson(rawJson)
            }.onSuccess { preview ->
                stagedBackupRawJson = rawJson
                controlsFlow.update { it.copy(pendingBackupPreview = preview) }
            }.onFailure { err ->
                stagedBackupRawJson = null
                controlsFlow.update { it.copy(pendingBackupPreview = null) }
                postBanner("Backup validation failed: ${err.message ?: "Invalid archive."}")
            }
        }
    }

    fun dismissPendingBackupRestore() {
        stagedBackupRawJson = null
        controlsFlow.update { it.copy(pendingBackupPreview = null) }
    }

    fun confirmPendingBackupRestore(mode: RestoreMode) {
        val rawJson = stagedBackupRawJson ?: return
        stagedBackupRawJson = null
        controlsFlow.update { it.copy(pendingBackupPreview = null) }
        viewModelScope.launch {
            runCatching {
                repository.executeRestoreBackup(rawJson, mode)
            }.onSuccess { summary ->
                val msg = if (summary.mode == RestoreMode.SAFE_MERGE) {
                    "Merged \"${summary.business.name}\": +${summary.insertedOrReplacedTransactions} new, ${summary.skippedDuplicateTransactions} duplicates skipped."
                } else {
                    "Replaced \"${summary.business.name}\" (${summary.insertedOrReplacedTransactions} entries; recovery snapshot saved)."
                }
                postBanner(msg)
            }.onFailure { err ->
                postBanner("Restore rejected: ${err.message}")
            }
        }
    }

    fun reportBackupFileReadError(message: String) {
        postBanner("Could not read backup file: $message")
    }
}
