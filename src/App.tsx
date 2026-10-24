/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { Component, ErrorInfo, ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { onAuthStateChanged, User } from 'firebase/auth';
import {
  LayoutDashboard,
  ReceiptText,
  BarChart3,
  Users,
  Settings,
  Plus,
  Sun,
  Moon,
  Monitor,
  WifiOff,
  CheckCircle2,
  AlertTriangle,
  X,
} from 'lucide-react';
import {
  Account,
  AuditEvent,
  Business,
  BusinessMember,
  Category,
  CurrencyCode,
  DateFilterPreset,
  DateRange,
  LocaleCode,
  MemberRole,
  NavDestination,
  Party,
  ThemePreference,
  Transaction,
  TransactionType,
} from './types/cashbook';
import { resolveDateRangePreset, ROLE_PERMISSIONS } from './engine/accounting';
import { translate } from './i18n/translations';
import {
  auth,
  signInWithGooglePopup,
  signOutFirebase,
  validateFirestoreConnectionOnBoot,
} from './services/firebase';
import {
  addCollaboratorMember,
  commitTransactionEntry,
  createBusinessWithInitialAccount,
  createFinancialAccount,
  deleteTransactionEntry,
  getSavedSessionMode,
  GUEST_ACTOR_UID,
  listUserBusinesses,
  loadLocalPreferences,
  restoreVerifiedBackup,
  saveCategoryItem,
  savePartyItem,
  setSavedSessionMode,
  subscribeToBusinessWorkspace,
  syncCloudUserProfile,
  updateUserPreferences,
  voidTransactionEntry,
  wipeBusinessOrAccountData,
} from './services/repository';
import { BrandMark } from './components/BrandMark';
import { SplashScreen } from './components/SplashScreen';
import { OnboardingAuthView } from './components/OnboardingAuthView';
import { DashboardView } from './components/DashboardView';
import { TransactionsView } from './components/TransactionsView';
import { ReportsView } from './components/ReportsView';
import { PartiesView } from './components/PartiesView';
import { SettingsView } from './components/SettingsView';
import { TransactionModal } from './components/TransactionModal';
import { TransactionDetailDrawer } from './components/TransactionDetailDrawer';

function getLocalIsoDate(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  message: string;
  operationContext: string;
}

class CashbookErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, message: '', operationContext: '' };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    let message = error.message || 'An unexpected application error occurred.';
    let operationContext = '';
    try {
      const parsed = JSON.parse(error.message);
      if (parsed && typeof parsed === 'object' && 'error' in parsed) {
        message = String(parsed.error);
        if (parsed.operationType || parsed.path) {
          operationContext = `${parsed.operationType || 'operation'} · ${parsed.path || ''}`;
        }
      }
    } catch {
      // Plain error message
    }
    return { hasError: true, message, operationContext };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('VUMIQ Cashbook Runtime Boundary:', error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div className="min-h-screen flex items-center justify-center bg-[#F6F8F5] dark:bg-[#101713] p-6 text-[#17231D] dark:text-[#ECF3EE]">
          <div className="max-w-md w-full rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] p-6 space-y-4">
            <div className="flex items-center gap-3 text-[#D94848] dark:text-[#F87171]">
              <AlertTriangle className="w-6 h-6 shrink-0" />
              <h1 className="text-lg font-semibold font-display">Workspace Protection Alert</h1>
            </div>
            <p className="text-sm text-[#58655D] dark:text-[#98A89E] leading-relaxed">
              VUMIQ Cashbook halted an unexpected operation to protect your financial records. No posted entries were silently modified.
            </p>
            <div className="rounded-lg bg-[#F6F8F5] dark:bg-[#101713] p-3 text-xs font-mono-num text-[#17231D] dark:text-[#ECF3EE] break-words">
              {this.state.message}
              {this.state.operationContext ? (
                <div className="mt-1 text-[#58655D] dark:text-[#98A89E]">{this.state.operationContext}</div>
              ) : null}
            </div>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="w-full min-h-[44px] rounded-lg bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-medium transition-colors"
            >
              Reload Cashbook Safely
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

interface ToastMessage {
  id: number;
  kind: 'success' | 'error';
  text: string;
}

const VALID_DESTINATIONS = new Set<NavDestination>([
  'home',
  'transactions',
  'reports',
  'parties',
  'settings',
]);

function CashbookShell() {
  const initialLocalPrefs = useMemo(() => loadLocalPreferences(), []);

  // Determine whether splash screen should run on fresh session launch
  const [showSplash, setShowSplash] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    const params = new URLSearchParams(window.location.search);
    if (params.get('autostart') === '1') {
      sessionStorage.setItem('vumiq_splash_done', '1');
      return false;
    }
    return sessionStorage.getItem('vumiq_splash_done') !== '1';
  });

  const [theme, setTheme] = useState<ThemePreference>(initialLocalPrefs.theme);
  const [locale, setLocale] = useState<LocaleCode>(initialLocalPrefs.locale);
  const [notificationsEnabled, setNotificationsEnabled] = useState<boolean>(
    initialLocalPrefs.notificationsEnabled
  );

  const [authReady, setAuthReady] = useState(false);
  const [cloudUser, setCloudUser] = useState<User | null>(null);
  const [sessionMode, setSessionMode] = useState<'guest' | 'cloud' | ''>(() => getSavedSessionMode());

  const [businesses, setBusinesses] = useState<Business[]>([]);
  const [activeBusinessId, setActiveBusinessId] = useState<string>(initialLocalPrefs.defaultBusinessId || '');
  const [creatingNewBusiness, setCreatingNewBusiness] = useState(false);
  const [workspaceLoading, setWorkspaceLoading] = useState(true);

  // Live workspace collections
  const [members, setMembers] = useState<BusinessMember[]>([]);
  const [currentUserRole, setCurrentUserRole] = useState<MemberRole>('owner');
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [hasPendingWrites, setHasPendingWrites] = useState(false);

  // Navigation & UI state
  const [navDest, setNavDest] = useState<NavDestination>('home');
  const [todayIso] = useState<string>(() => getLocalIsoDate());
  const [datePreset, setDatePreset] = useState<DateFilterPreset>('month');
  const [customDateRange, setCustomDateRange] = useState<DateRange>({ startDate: '', endDate: '' });
  const [selectedCurrency, setSelectedCurrency] = useState<CurrencyCode>('USD');

  // Modals & Drawers
  const [txModalOpen, setTxModalOpen] = useState(false);
  const [txModalDefaultType, setTxModalDefaultType] = useState<TransactionType>('income');
  const [txModalDefaultPartyId, setTxModalDefaultPartyId] = useState<string>('');
  const [editingTx, setEditingTx] = useState<Transaction | null>(null);
  const [duplicatingTx, setDuplicatingTx] = useState<Transaction | null>(null);
  const [inspectedTxId, setInspectedTxId] = useState<string | null>(null);
  const [externalAddPartyOpen, setExternalAddPartyOpen] = useState(false);

  const [isOnline, setIsOnline] = useState<boolean>(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );
  const [toast, setToast] = useState<ToastMessage | null>(null);

  const pushToast = useCallback((kind: 'success' | 'error', text: string) => {
    const id = Date.now();
    setToast({ id, kind, text });
    window.setTimeout(() => {
      setToast((curr) => (curr && curr.id === id ? null : curr));
    }, 4200);
  }, []);

  // Apply light / dark / system theme deliberately to document root
  useEffect(() => {
    const root = document.documentElement;
    const media = window.matchMedia('(prefers-color-scheme: dark)');

    const applyResolvedTheme = () => {
      const useDark = theme === 'dark' || (theme === 'system' && media.matches);
      root.classList.toggle('dark', useDark);
      root.style.colorScheme = useDark ? 'dark' : 'light';
    };

    applyResolvedTheme();
    media.addEventListener('change', applyResolvedTheme);
    return () => media.removeEventListener('change', applyResolvedTheme);
  }, [theme]);

  // Keep <html lang="..."> synchronized with locale
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  // Monitor network connectivity
  useEffect(() => {
    const onOnline = () => setIsOnline(true);
    const onOffline = () => setIsOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  // Browser / Android back navigation history management
  const navigateTo = useCallback(
    (dest: NavDestination) => {
      if (dest === navDest) return;
      setNavDest(dest);
      window.history.pushState({ vumiqDest: dest }, '');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },
    [navDest]
  );

  useEffect(() => {
    window.history.replaceState({ vumiqDest: 'home' }, '');
    const handlePopState = (event: PopStateEvent) => {
      if (txModalOpen) {
        setTxModalOpen(false);
        return;
      }
      if (inspectedTxId) {
        setInspectedTxId(null);
        return;
      }
      const stateDest = event.state?.vumiqDest as NavDestination | undefined;
      if (stateDest && VALID_DESTINATIONS.has(stateDest)) {
        setNavDest(stateDest);
      } else {
        setNavDest('home');
      }
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, [txModalOpen, inspectedTxId]);

  // Validate Firestore connectivity once on boot and attach auth listener
  useEffect(() => {
    void validateFirestoreConnectionOnBoot();

    const unsub = onAuthStateChanged(auth, async (user) => {
      setCloudUser(user);
      if (user) {
        setSessionMode('cloud');
        setSavedSessionMode('cloud');
        setWorkspaceLoading(true);
        try {
          const profile = await syncCloudUserProfile(user);
          setTheme(profile.theme);
          setLocale(profile.locale);
          setNotificationsEnabled(profile.notificationsEnabled);

          const bizList = await listUserBusinesses('cloud', user.uid);
          setBusinesses(bizList);
          if (bizList.length > 0) {
            const preferred =
              bizList.find((b) => b.id === profile.defaultBusinessId) || bizList[0];
            setActiveBusinessId(preferred.id);
            setSelectedCurrency(preferred.defaultCurrency);
          } else {
            setActiveBusinessId('');
          }
        } catch (err) {
          pushToast(
            'error',
            err instanceof Error ? err.message : 'Unable to load cloud profile.'
          );
        } finally {
          setWorkspaceLoading(false);
          setAuthReady(true);
        }
      } else {
        const saved = getSavedSessionMode();
        if (saved === 'guest') {
          setSessionMode('guest');
          const prefs = loadLocalPreferences();
          const bizList = await listUserBusinesses('guest', GUEST_ACTOR_UID);
          setBusinesses(bizList);
          if (bizList.length > 0) {
            const preferred =
              bizList.find((b) => b.id === prefs.defaultBusinessId) || bizList[0];
            setActiveBusinessId(preferred.id);
            setSelectedCurrency(preferred.defaultCurrency);
          } else {
            setActiveBusinessId('');
          }
        } else {
          setSessionMode('');
          setBusinesses([]);
          setActiveBusinessId('');
        }
        setWorkspaceLoading(false);
        setAuthReady(true);
      }
    });

    return () => unsub();
  }, [pushToast]);

  const activeBusiness = useMemo(
    () => businesses.find((b) => b.id === activeBusinessId) || businesses[0] || null,
    [businesses, activeBusinessId]
  );

  const actorUid = sessionMode === 'cloud' && cloudUser ? cloudUser.uid : GUEST_ACTOR_UID;
  const actorName =
    sessionMode === 'cloud' && cloudUser
      ? cloudUser.displayName || cloudUser.email?.split('@')[0] || 'Business Owner'
      : 'Local Business Owner';

  // Subscribe to active business workspace records
  useEffect(() => {
    if (!sessionMode || !activeBusiness) {
      setMembers([]);
      setAccounts([]);
      setCategories([]);
      setParties([]);
      setTransactions([]);
      setAuditEvents([]);
      return;
    }

    const unsub = subscribeToBusinessWorkspace(
      sessionMode,
      actorUid,
      activeBusiness,
      (bundle) => {
        setMembers(bundle.members);
        setCurrentUserRole(bundle.currentUserRole);
        setAccounts(bundle.accounts);
        setCategories(bundle.categories);
        setParties(bundle.parties);
        setTransactions(bundle.transactions);
        setAuditEvents(bundle.auditEvents);
        setHasPendingWrites(bundle.hasPendingWrites);
      },
      (err) => {
        pushToast('error', err.message || 'Live workspace synchronization error.');
      }
    );

    return () => unsub();
  }, [sessionMode, actorUid, activeBusiness, pushToast]);

  // Keep selectedCurrency valid when active business changes
  useEffect(() => {
    if (activeBusiness) {
      setSelectedCurrency(activeBusiness.defaultCurrency);
    }
  }, [activeBusiness?.id]);

  const effectiveDateRange = useMemo(
    () => resolveDateRangePreset(datePreset, todayIso, customDateRange),
    [datePreset, todayIso, customDateRange]
  );

  const inspectedTransaction = useMemo(
    () => (inspectedTxId ? transactions.find((t) => t.id === inspectedTxId) || null : null),
    [inspectedTxId, transactions]
  );

  const handleSplashComplete = useCallback(() => {
    sessionStorage.setItem('vumiq_splash_done', '1');
    setShowSplash(false);
  }, []);

  const handleChangeTheme = useCallback(
    async (nextTheme: ThemePreference) => {
      setTheme(nextTheme);
      if (sessionMode) {
        try {
          await updateUserPreferences(sessionMode, actorUid, { theme: nextTheme });
        } catch (err) {
          pushToast('error', err instanceof Error ? err.message : 'Could not save theme preference.');
        }
      }
    },
    [sessionMode, actorUid, pushToast]
  );

  const handleCycleTheme = useCallback(() => {
    const order: ThemePreference[] = ['light', 'dark', 'system'];
    const next = order[(order.indexOf(theme) + 1) % order.length];
    void handleChangeTheme(next);
  }, [theme, handleChangeTheme]);

  const handleChangeLocale = useCallback(
    async (nextLocale: LocaleCode) => {
      setLocale(nextLocale);
      if (sessionMode) {
        try {
          await updateUserPreferences(sessionMode, actorUid, { locale: nextLocale });
        } catch (err) {
          pushToast('error', err instanceof Error ? err.message : 'Could not save language preference.');
        }
      }
    },
    [sessionMode, actorUid, pushToast]
  );

  const handleToggleNotifications = useCallback(
    async (enabled: boolean) => {
      setNotificationsEnabled(enabled);
      if (sessionMode) {
        await updateUserPreferences(sessionMode, actorUid, { notificationsEnabled: enabled });
      }
    },
    [sessionMode, actorUid]
  );

  const handleSignInGoogle = useCallback(async () => {
    await signInWithGooglePopup();
  }, []);

  const handleSelectGuestMode = useCallback(async () => {
    setSavedSessionMode('guest');
    setSessionMode('guest');
    const bizList = await listUserBusinesses('guest', GUEST_ACTOR_UID);
    setBusinesses(bizList);
    if (bizList.length > 0) {
      setActiveBusinessId(bizList[0].id);
      setSelectedCurrency(bizList[0].defaultCurrency);
    }
  }, []);

  const handleCreateBusinessWorkspace = useCallback(
    async (data: {
      businessName: string;
      defaultCurrency: CurrencyCode;
      address: string;
      phone: string;
      initialAccountName: string;
      initialAccountType: Account['accountType'];
      openingBalanceMinor: number;
    }) => {
      const targetMode = sessionMode || 'guest';
      if (!sessionMode) {
        setSavedSessionMode('guest');
        setSessionMode('guest');
      }
      const created = await createBusinessWithInitialAccount({
        mode: targetMode,
        actorUid,
        actorName,
        businessName: data.businessName,
        defaultCurrency: data.defaultCurrency,
        address: data.address,
        phone: data.phone,
        initialAccountName: data.initialAccountName,
        initialAccountType: data.initialAccountType,
        openingBalanceMinor: data.openingBalanceMinor,
      });

      const updatedList = await listUserBusinesses(targetMode, actorUid);
      setBusinesses(updatedList);
      setActiveBusinessId(created.id);
      setSelectedCurrency(created.defaultCurrency);
      setCreatingNewBusiness(false);
      setNavDest('home');
      pushToast('success', `Workspace "${created.name}" is ready.`);
    },
    [sessionMode, actorUid, actorName, pushToast]
  );

  const handleSwitchBusiness = useCallback(
    async (businessId: string) => {
      const target = businesses.find((b) => b.id === businessId);
      if (!target) return;
      setActiveBusinessId(target.id);
      setSelectedCurrency(target.defaultCurrency);
      if (sessionMode) {
        await updateUserPreferences(sessionMode, actorUid, { defaultBusinessId: target.id });
      }
      pushToast('success', `Switched to "${target.name}".`);
    },
    [businesses, sessionMode, actorUid, pushToast]
  );

  const openNewTransactionModal = useCallback(
    (type: TransactionType = 'income', partyId = '') => {
      setEditingTx(null);
      setDuplicatingTx(null);
      setTxModalDefaultType(type);
      setTxModalDefaultPartyId(partyId);
      setTxModalOpen(true);
    },
    []
  );

  const handleEditTransaction = useCallback((tx: Transaction) => {
    setInspectedTxId(null);
    setDuplicatingTx(null);
    setEditingTx(tx);
    setTxModalDefaultType(tx.type);
    setTxModalDefaultPartyId(tx.partyId);
    setTxModalOpen(true);
  }, []);

  const handleDuplicateTransaction = useCallback((tx: Transaction) => {
    setInspectedTxId(null);
    setEditingTx(null);
    setDuplicatingTx(tx);
    setTxModalDefaultType(tx.type);
    setTxModalDefaultPartyId(tx.partyId);
    setTxModalOpen(true);
  }, []);

  const handleSubmitTransaction = useCallback(
    async (payload: {
      idempotencyKey: string;
      editingTransaction?: Transaction;
      type: TransactionType;
      amountMinor: number;
      currency: CurrencyCode;
      convertedAmountMinor: number;
      toCurrency: string;
      exchangeRateScaled: number;
      feeMinor: number;
      date: string;
      time: string;
      description: string;
      accountId: string;
      toAccountId: string;
      categoryId: string;
      partyId: string;
      referenceNumber: string;
      notes: string;
      attachmentName: string;
      attachmentMime: string;
      dueDate: string;
      editReason?: string;
    }) => {
      if (!sessionMode || !activeBusiness) return;
      await commitTransactionEntry({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        accounts,
        categories,
        parties,
        existingTransactions: transactions,
        ...payload,
      });
      setTxModalOpen(false);
      setEditingTx(null);
      setDuplicatingTx(null);
      pushToast(
        'success',
        payload.editingTransaction
          ? 'Transaction updated and logged in audit trail.'
          : 'Transaction recorded.'
      );
    },
    [
      sessionMode,
      activeBusiness,
      actorUid,
      actorName,
      currentUserRole,
      accounts,
      categories,
      parties,
      transactions,
      pushToast,
    ]
  );

  const handleVoidTransaction = useCallback(
    async (tx: Transaction, reason: string) => {
      if (!sessionMode || !activeBusiness) return;
      await voidTransactionEntry({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        transaction: tx,
        reason,
      });
      pushToast('success', 'Transaction voided and reversed in ledger.');
    },
    [sessionMode, activeBusiness, actorUid, actorName, currentUserRole, pushToast]
  );

  const handleDeleteTransaction = useCallback(
    async (tx: Transaction, reason: string) => {
      if (!sessionMode || !activeBusiness) return;
      await deleteTransactionEntry({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        transaction: tx,
        reason,
      });
      pushToast('success', 'Transaction permanently removed and logged.');
    },
    [sessionMode, activeBusiness, actorUid, actorName, currentUserRole, pushToast]
  );

  const handleSaveParty = useCallback(
    async (data: {
      existingParty?: Party;
      name: string;
      partyType: Party['partyType'];
      phone: string;
      address: string;
      notes: string;
      currency: CurrencyCode;
      openingReceivableMinor: number;
      openingPayableMinor: number;
      dueDate: string;
    }) => {
      if (!sessionMode || !activeBusiness) return;
      await savePartyItem({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        ...data,
      });
      pushToast('success', data.existingParty ? 'Party updated.' : 'Party saved.');
    },
    [sessionMode, activeBusiness, actorUid, actorName, currentUserRole, pushToast]
  );

  const handleCreateAccount = useCallback(
    async (data: {
      name: string;
      accountType: Account['accountType'];
      currency: CurrencyCode;
      openingBalanceMinor: number;
    }) => {
      if (!sessionMode || !activeBusiness) return;
      await createFinancialAccount({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        ...data,
      });
      pushToast('success', `Account "${data.name}" added.`);
    },
    [sessionMode, activeBusiness, actorUid, actorName, currentUserRole, pushToast]
  );

  const handleSaveCategory = useCallback(
    async (data: {
      existingCategory?: Category;
      name: string;
      kind: Category['kind'];
      color: string;
      icon: string;
      archived?: boolean;
    }) => {
      if (!sessionMode || !activeBusiness) return;
      await saveCategoryItem({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        business: activeBusiness,
        ...data,
      });
      pushToast('success', 'Category saved.');
    },
    [sessionMode, activeBusiness, actorUid, actorName, currentUserRole, pushToast]
  );

  const handleAddCollaborator = useCallback(
    async (data: {
      memberUid: string;
      displayName: string;
      targetRole: Exclude<MemberRole, 'owner'>;
    }) => {
      if (!sessionMode || !activeBusiness) return;
      await addCollaboratorMember({
        mode: sessionMode,
        actorUid,
        role: currentUserRole,
        business: activeBusiness,
        ...data,
      });
      pushToast('success', `Collaborator "${data.displayName}" added as ${data.targetRole}.`);
    },
    [sessionMode, activeBusiness, actorUid, currentUserRole, pushToast]
  );

  const handleRestoreBackup = useCallback(
    async (rawJson: string, strategy: 'merge' | 'replace') => {
      if (!sessionMode || !activeBusiness) {
        throw new Error('No active workspace selected.');
      }
      const res = await restoreVerifiedBackup({
        mode: sessionMode,
        actorUid,
        actorName,
        role: currentUserRole,
        targetBusiness: activeBusiness,
        existingAccounts: accounts,
        existingCategories: categories,
        existingParties: parties,
        existingTransactions: transactions,
        rawJson,
        strategy,
      });
      pushToast(
        'success',
        `Backup restored (${res.importedTransactions} imported, ${res.skippedDuplicates} duplicates skipped).`
      );
      return res;
    },
    [
      sessionMode,
      activeBusiness,
      actorUid,
      actorName,
      currentUserRole,
      accounts,
      categories,
      parties,
      transactions,
      pushToast,
    ]
  );

  const handleSignOut = useCallback(async () => {
    if (sessionMode === 'cloud') {
      await signOutFirebase();
    }
    setSavedSessionMode('');
    setSessionMode('');
    setBusinesses([]);
    setActiveBusinessId('');
    setCreatingNewBusiness(false);
    setNavDest('home');
  }, [sessionMode]);

  const handleWipeData = useCallback(async () => {
    if (!sessionMode) return;
    await wipeBusinessOrAccountData({
      mode: sessionMode,
      uid: actorUid,
      businesses,
    });
    if (sessionMode === 'cloud') {
      await signOutFirebase();
    }
    setSavedSessionMode('');
    setSessionMode('');
    setBusinesses([]);
    setActiveBusinessId('');
    setNavDest('home');
    pushToast('success', 'All workspace data has been permanently erased.');
  }, [sessionMode, actorUid, businesses, pushToast]);

  // Splash launch sequence
  if (showSplash) {
    return <SplashScreen locale={locale} onComplete={handleSplashComplete} />;
  }

  // Initial auth check spinner
  if (!authReady || workspaceLoading) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-[#F6F8F5] dark:bg-[#101713] text-[#17231D] dark:text-[#ECF3EE] gap-4">
        <BrandMark size="lg" showSubtitle subtitleText={translate(locale, 'brandSubtitle')} />
        <p className="text-sm text-[#58655D] dark:text-[#98A89E]">Loading secure ledger...</p>
      </div>
    );
  }

  // Onboarding / Auth / New Business Setup screen
  if (!sessionMode || !activeBusiness || creatingNewBusiness) {
    const showSetupForm = Boolean(sessionMode) && (!activeBusiness || creatingNewBusiness);
    return (
      <OnboardingAuthView
        locale={locale}
        onChangeLocale={(loc) => void handleChangeLocale(loc)}
        isAuthenticatedCloudUser={sessionMode === 'cloud' && Boolean(cloudUser)}
        cloudUserDisplayName={cloudUser?.displayName || cloudUser?.email || undefined}
        onSignInGoogle={handleSignInGoogle}
        onSelectGuestMode={() => void handleSelectGuestMode()}
        showBusinessSetupForm={showSetupForm}
        onCreateBusinessWorkspace={handleCreateBusinessWorkspace}
        onCancelGuestMode={
          creatingNewBusiness && businesses.length > 0
            ? () => setCreatingNewBusiness(false)
            : sessionMode === 'guest' && businesses.length === 0
              ? () => {
                  setSavedSessionMode('');
                  setSessionMode('');
                }
              : undefined
        }
      />
    );
  }

  const canRecord = ROLE_PERMISSIONS[currentUserRole].canRecordTransactions;

  const navItems: Array<{
    id: NavDestination;
    label: string;
    icon: React.FC<{ className?: string }>;
  }> = [
    { id: 'home', label: translate(locale, 'navHome'), icon: LayoutDashboard },
    { id: 'transactions', label: translate(locale, 'navTransactions'), icon: ReceiptText },
    { id: 'reports', label: translate(locale, 'navReports'), icon: BarChart3 },
    { id: 'parties', label: translate(locale, 'navParties'), icon: Users },
    { id: 'settings', label: translate(locale, 'navSettings'), icon: Settings },
  ];

  const syncStatusText = !isOnline
    ? translate(locale, 'syncOffline')
    : sessionMode === 'guest'
      ? translate(locale, 'syncLocalGuest')
      : hasPendingWrites
        ? translate(locale, 'syncPending')
        : translate(locale, 'syncCloudSynced');

  const currentNavLabel = navItems.find((n) => n.id === navDest)?.label || translate(locale, 'navHome');

  return (
    <div className="min-h-screen flex bg-[#F6F8F5] dark:bg-[#101713] text-[#17231D] dark:text-[#ECF3EE]">
      {/* Desktop Left Navigation Rail */}
      <aside
        aria-label="Primary Sidebar Navigation"
        className="hidden lg:flex lg:w-64 lg:shrink-0 lg:flex-col lg:justify-between border-r border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] sticky top-0 h-screen"
      >
        <div className="p-5 space-y-6">
          <div className="flex items-center justify-between">
            <BrandMark size="md" showSubtitle subtitleText={translate(locale, 'brandSubtitle')} />
          </div>

          {/* Active Business Selector */}
          <div className="space-y-1.5">
            <label
              htmlFor="sidebar-business-select"
              className="block text-[11px] uppercase tracking-wider font-medium text-[#58655D] dark:text-[#98A89E]"
            >
              Active Ledger
            </label>
            {businesses.length > 1 ? (
              <select
                id="sidebar-business-select"
                value={activeBusiness.id}
                onChange={(e) => void handleSwitchBusiness(e.target.value)}
                className="w-full min-h-[40px] rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] px-3 text-sm font-medium text-[#17231D] dark:text-[#ECF3EE] focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              >
                {businesses.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name} ({b.defaultCurrency})
                  </option>
                ))}
              </select>
            ) : (
              <div className="rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] px-3 py-2">
                <div className="text-sm font-semibold truncate">{activeBusiness.name}</div>
                <div className="text-xs text-[#58655D] dark:text-[#98A89E] font-mono-num">
                  {activeBusiness.defaultCurrency} · {currentUserRole}
                </div>
              </div>
            )}
          </div>

          {/* Navigation Links */}
          <nav aria-label="Main Destinations" className="space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              const active = navDest === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  aria-current={active ? 'page' : undefined}
                  onClick={() => navigateTo(item.id)}
                  className={`w-full min-h-[44px] flex items-center gap-3 px-3.5 rounded-lg text-sm font-medium transition-colors cursor-pointer ${
                    active
                      ? 'bg-[#126B45]/10 dark:bg-[#20A56B]/15 text-[#126B45] dark:text-[#34D399] font-semibold'
                      : 'text-[#58655D] dark:text-[#98A89E] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] hover:text-[#17231D] dark:hover:text-[#ECF3EE]'
                  }`}
                >
                  <Icon className="w-4 h-4 shrink-0" />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </nav>
        </div>

        {/* Sidebar Footer: Sync Status & Permanent Brand Credit */}
        <div className="p-5 border-t border-[#E2E8E3] dark:border-[#28362D] space-y-3">
          <div className="flex items-center gap-2 text-xs text-[#58655D] dark:text-[#98A89E]">
            {!isOnline ? <WifiOff className="w-3.5 h-3.5 text-[#C88719] shrink-0" /> : null}
            <span className="truncate">{syncStatusText}</span>
          </div>
          <div className="pt-2 border-t border-[#E2E8E3]/70 dark:border-[#28362D]/70">
            <div className="text-[10px] tracking-[0.14em] uppercase text-[#58655D] dark:text-[#98A89E]">
              {translate(locale, 'ownedAndCreatedBy')}
            </div>
            <div className="text-xs font-medium text-[#17231D] dark:text-[#ECF3EE] mt-0.5">
              {translate(locale, 'ownerName')}
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content Column */}
      <div className="flex-1 min-w-0 flex flex-col">
        {/* Desktop 3-Zone Top Bar */}
        <header className="hidden lg:flex h-16 shrink-0 items-center justify-between gap-4 px-8 border-b border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] sticky top-0 z-20">
          {/* Zone 1: View Heading & Business */}
          <div className="flex items-center gap-3 min-w-0">
            <h1 className="text-lg font-bold font-display whitespace-nowrap">{currentNavLabel}</h1>
            <span className="text-[#58655D] dark:text-[#98A89E]" aria-hidden="true">
              ·
            </span>
            <span className="text-sm text-[#58655D] dark:text-[#98A89E] truncate">
              {activeBusiness.name}
            </span>
          </div>

          {/* Zone 2: Unboxed Sync & Role Telemetry */}
          <div className="text-xs text-[#58655D] dark:text-[#98A89E] whitespace-nowrap">
            {syncStatusText} · <span className="capitalize">{currentUserRole}</span>
          </div>

          {/* Zone 3: Language, Theme & Primary Action */}
          <div className="flex items-center gap-2.5 shrink-0">
            <button
              type="button"
              onClick={() => void handleChangeLocale(locale === 'en' ? 'fr' : 'en')}
              aria-label={`Switch interface language (current ${locale.toUpperCase()})`}
              className="min-h-[38px] px-3 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-xs font-semibold uppercase tracking-wider text-[#17231D] dark:text-[#ECF3EE] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
            >
              {locale.toUpperCase()}
            </button>

            <button
              type="button"
              onClick={handleCycleTheme}
              aria-label={`Toggle theme (current: ${theme})`}
              title={`Theme: ${theme}`}
              className="min-h-[38px] min-w-[38px] inline-flex items-center justify-center rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-[#17231D] dark:text-[#ECF3EE] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
            >
              {theme === 'light' ? (
                <Sun className="w-4 h-4" />
              ) : theme === 'dark' ? (
                <Moon className="w-4 h-4" />
              ) : (
                <Monitor className="w-4 h-4" />
              )}
            </button>

            {canRecord ? (
              <button
                type="button"
                onClick={() => openNewTransactionModal('income')}
                className="min-h-[38px] px-4 inline-flex items-center gap-2 rounded-lg bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-semibold transition-colors whitespace-nowrap cursor-pointer"
              >
                <Plus className="w-4 h-4 shrink-0" />
                <span>{translate(locale, 'recordTransaction')}</span>
              </button>
            ) : null}
          </div>
        </header>

        {/* Compact Mobile Top Header (56px height) */}
        <header className="flex lg:hidden h-14 shrink-0 items-center justify-between gap-2 px-3.5 border-b border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] sticky top-0 z-20">
          <div className="flex items-center gap-1.5 min-w-0">
            <BrandMark size="sm" subtitleText="" />
            <span className="text-xs font-medium text-[#58655D] dark:text-[#98A89E] truncate max-w-[165px]">
              · {activeBusiness.name}
            </span>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={handleCycleTheme}
              aria-label={`Toggle theme (current: ${theme})`}
              className="min-h-[40px] min-w-[40px] inline-flex items-center justify-center rounded-lg text-[#58655D] dark:text-[#98A89E] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25]"
            >
              {theme === 'light' ? (
                <Sun className="w-4 h-4" />
              ) : theme === 'dark' ? (
                <Moon className="w-4 h-4" />
              ) : (
                <Monitor className="w-4 h-4" />
              )}
            </button>

            {canRecord ? (
              <button
                type="button"
                onClick={() => openNewTransactionModal('income')}
                className="min-h-[40px] px-3 inline-flex items-center gap-1.5 rounded-lg bg-[#126B45] hover:bg-[#084B32] text-white text-xs font-semibold whitespace-nowrap"
              >
                <Plus className="w-4 h-4 shrink-0" />
                <span>{translate(locale, 'addEntryShort')}</span>
              </button>
            ) : null}
          </div>
        </header>

        {/* Active View Content */}
        <main className="flex-1 w-full max-w-[1380px] mx-auto px-4 sm:px-6 lg:px-8 py-6 pb-24 lg:pb-10">
          {navDest === 'home' && (
            <DashboardView
              locale={locale}
              role={currentUserRole}
              accounts={accounts}
              categories={categories}
              parties={parties}
              transactions={transactions}
              defaultCurrency={activeBusiness.defaultCurrency}
              selectedCurrency={selectedCurrency}
              onSelectCurrency={setSelectedCurrency}
              datePreset={datePreset}
              customDateRange={customDateRange}
              effectiveDateRange={effectiveDateRange}
              onChangeDatePreset={setDatePreset}
              onChangeCustomDateRange={setCustomDateRange}
              onOpenNewTransaction={(type) => openNewTransactionModal(type)}
              onSelectTransaction={(tx) => setInspectedTxId(tx.id)}
              onNavigate={navigateTo}
              onOpenAddParty={() => {
                navigateTo('parties');
                setExternalAddPartyOpen(true);
              }}
              notificationsEnabled={notificationsEnabled}
              todayIso={todayIso}
            />
          )}

          {navDest === 'transactions' && (
            <TransactionsView
              locale={locale}
              role={currentUserRole}
              accounts={accounts}
              categories={categories}
              parties={parties}
              transactions={transactions}
              defaultCurrency={activeBusiness.defaultCurrency}
              onOpenNewTransaction={(type) => openNewTransactionModal(type || 'income')}
              onSelectTransaction={(tx) => setInspectedTxId(tx.id)}
            />
          )}

          {navDest === 'reports' && (
            <ReportsView
              locale={locale}
              role={currentUserRole}
              business={activeBusiness}
              accounts={accounts}
              categories={categories}
              parties={parties}
              transactions={transactions}
              todayIso={todayIso}
            />
          )}

          {navDest === 'parties' && (
            <PartiesView
              locale={locale}
              role={currentUserRole}
              parties={parties}
              transactions={transactions}
              defaultCurrency={activeBusiness.defaultCurrency}
              isAddModalOpenExternal={externalAddPartyOpen}
              onCloseExternalAddModal={() => setExternalAddPartyOpen(false)}
              onSaveParty={handleSaveParty}
              onRecordPartyWorkflow={(type, partyId) => openNewTransactionModal(type, partyId)}
            />
          )}

          {navDest === 'settings' && (
            <SettingsView
              mode={sessionMode}
              userEmail={cloudUser?.email || undefined}
              locale={locale}
              theme={theme}
              notificationsEnabled={notificationsEnabled}
              role={currentUserRole}
              businesses={businesses}
              activeBusiness={activeBusiness}
              members={members}
              accounts={accounts}
              categories={categories}
              parties={parties}
              transactions={transactions}
              auditEvents={auditEvents}
              onSwitchBusiness={(id) => void handleSwitchBusiness(id)}
              onStartNewBusiness={() => setCreatingNewBusiness(true)}
              onChangeTheme={(t) => void handleChangeTheme(t)}
              onChangeLocale={(l) => void handleChangeLocale(l)}
              onToggleNotifications={(en) => void handleToggleNotifications(en)}
              onCreateAccount={handleCreateAccount}
              onOpenTransfer={() => openNewTransactionModal('transfer')}
              onSaveCategory={handleSaveCategory}
              onAddCollaborator={handleAddCollaborator}
              onRestoreBackup={handleRestoreBackup}
              onSignInGoogleUpgrade={sessionMode === 'guest' ? handleSignInGoogle : undefined}
              onSignOut={handleSignOut}
              onWipeAccountData={handleWipeData}
            />
          )}
        </main>

        {/* Mobile Bottom Navigation Bar (64px height; combined sticky < 15% of 844px) */}
        <nav
          aria-label="Mobile Primary Navigation"
          className="flex lg:hidden fixed bottom-0 inset-x-0 h-16 z-20 items-center justify-around border-t border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] px-1"
        >
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = navDest === item.id;
            return (
              <button
                key={item.id}
                type="button"
                aria-current={active ? 'page' : undefined}
                onClick={() => navigateTo(item.id)}
                className={`flex-1 min-h-[48px] flex flex-col items-center justify-center gap-0.5 rounded-lg px-0.5 text-[10.5px] leading-tight font-medium transition-colors ${
                  active
                    ? 'text-[#126B45] dark:text-[#34D399] font-semibold'
                    : 'text-[#58655D] dark:text-[#98A89E]'
                }`}
              >
                <Icon className="w-4 h-4 shrink-0" />
                <span className="whitespace-nowrap">{item.label}</span>
              </button>
            );
          })}
        </nav>
      </div>

      {/* Transaction Entry / Edit Modal */}
      <TransactionModal
        isOpen={txModalOpen}
        locale={locale}
        defaultType={txModalDefaultType}
        defaultPartyId={txModalDefaultPartyId}
        editingTransaction={editingTx}
        duplicatingFrom={duplicatingTx}
        accounts={accounts}
        categories={categories}
        parties={parties}
        defaultCurrency={activeBusiness.defaultCurrency}
        todayIso={todayIso}
        onClose={() => {
          setTxModalOpen(false);
          setEditingTx(null);
          setDuplicatingTx(null);
        }}
        onSubmit={handleSubmitTransaction}
      />

      {/* Transaction Detail & Audit Trail Drawer */}
      <TransactionDetailDrawer
        transaction={inspectedTransaction}
        accounts={accounts}
        categories={categories}
        parties={parties}
        auditEvents={auditEvents}
        role={currentUserRole}
        locale={locale}
        onClose={() => setInspectedTxId(null)}
        onEdit={handleEditTransaction}
        onDuplicate={handleDuplicateTransaction}
        onVoid={handleVoidTransaction}
        onDelete={handleDeleteTransaction}
      />

      {/* Accessible Non-Blocking Toast Feedback */}
      {toast ? (
        <div
          role={toast.kind === 'error' ? 'alert' : 'status'}
          aria-live="polite"
          className={`fixed bottom-20 lg:bottom-6 right-4 lg:right-6 z-50 max-w-sm flex items-center gap-3 px-4 py-3 rounded-xl border shadow-lg text-sm font-medium ${
            toast.kind === 'error'
              ? 'bg-white dark:bg-[#19221C] border-[#D94848] text-[#D94848] dark:text-[#F87171]'
              : 'bg-white dark:bg-[#19221C] border-[#168653] text-[#17231D] dark:text-[#ECF3EE]'
          }`}
        >
          {toast.kind === 'error' ? (
            <AlertTriangle className="w-4 h-4 shrink-0 text-[#D94848]" />
          ) : (
            <CheckCircle2 className="w-4 h-4 shrink-0 text-[#168653] dark:text-[#34D399]" />
          )}
          <span className="flex-1 leading-snug">{toast.text}</span>
          <button
            type="button"
            aria-label="Dismiss notification"
            onClick={() => setToast(null)}
            className="p-1 rounded hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] text-[#58655D] dark:text-[#98A89E]"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      ) : null}
    </div>
  );
}

export default function App() {
  return (
    <CashbookErrorBoundary>
      <CashbookShell />
    </CashbookErrorBoundary>
  );
}
