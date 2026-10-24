import React, { useMemo, useState } from 'react';
import {
  Wallet,
  Tags,
  Building2,
  Download,
  Upload,
  ShieldAlert,
  Users,
  Plus,
  ArrowLeftRight,
} from 'lucide-react';
import {
  Account,
  AuditEvent,
  Business,
  BusinessMember,
  Category,
  CurrencyCode,
  LocaleCode,
  MemberRole,
  Party,
  SUPPORTED_CURRENCIES,
  ThemePreference,
  Transaction,
} from '../types/cashbook';
import { computeAccountBalances, ROLE_PERMISSIONS } from '../engine/accounting';
import { formatMinorAmount, parseAmountToMinor } from '../engine/money';
import { translate } from '../i18n/translations';
import { buildVerifiedBackupArchive } from '../services/repository';
import { downloadBackupJsonFile } from '../utils/exporters';

interface SettingsViewProps {
  mode: 'guest' | 'cloud';
  userEmail?: string;
  locale: LocaleCode;
  theme: ThemePreference;
  notificationsEnabled: boolean;
  role: MemberRole;
  businesses: Business[];
  activeBusiness: Business;
  members: BusinessMember[];
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  auditEvents: AuditEvent[];
  onSwitchBusiness: (businessId: string) => void;
  onStartNewBusiness: () => void;
  onChangeTheme: (theme: ThemePreference) => void;
  onChangeLocale: (locale: LocaleCode) => void;
  onToggleNotifications: (enabled: boolean) => void;
  onCreateAccount: (data: {
    name: string;
    accountType: Account['accountType'];
    currency: CurrencyCode;
    openingBalanceMinor: number;
  }) => Promise<void>;
  onOpenTransfer: () => void;
  onSaveCategory: (data: {
    existingCategory?: Category;
    name: string;
    kind: Category['kind'];
    color: string;
    icon: string;
    archived?: boolean;
  }) => Promise<void>;
  onAddCollaborator: (data: {
    memberUid: string;
    displayName: string;
    targetRole: Exclude<MemberRole, 'owner'>;
  }) => Promise<void>;
  onRestoreBackup: (rawJson: string, strategy: 'merge' | 'replace') => Promise<{ importedTransactions: number; skippedDuplicates: number }>;
  onSignInGoogleUpgrade?: () => Promise<void>;
  onSignOut: () => Promise<void>;
  onWipeAccountData: () => Promise<void>;
}

const CATEGORY_SWATCHES = ['#168653', '#20A56B', '#0284C7', '#4F46E5', '#9333EA', '#D94848', '#C88719', '#64748B'];

export const SettingsView: React.FC<SettingsViewProps> = ({
  mode,
  userEmail,
  locale,
  theme,
  notificationsEnabled,
  role,
  businesses,
  activeBusiness,
  members,
  accounts,
  categories,
  parties,
  transactions,
  auditEvents,
  onSwitchBusiness,
  onStartNewBusiness,
  onChangeTheme,
  onChangeLocale,
  onToggleNotifications,
  onCreateAccount,
  onOpenTransfer,
  onSaveCategory,
  onAddCollaborator,
  onRestoreBackup,
  onSignInGoogleUpgrade,
  onSignOut,
  onWipeAccountData,
}) => {
  const perms = ROLE_PERMISSIONS[role];
  const accountBalances = useMemo(() => computeAccountBalances(accounts, transactions), [accounts, transactions]);

  // New Account state
  const [accName, setAccName] = useState('');
  const [accType, setAccType] = useState<Account['accountType']>('bank');
  const [accCurrency, setAccCurrency] = useState<CurrencyCode>(activeBusiness.defaultCurrency);
  const [accOpening, setAccOpening] = useState('0.00');

  // Category state
  const [catName, setCatName] = useState('');
  const [catKind, setCatKind] = useState<Category['kind']>('expense');
  const [catColor, setCatColor] = useState('#168653');

  // Collaborator state
  const [collabUid, setCollabUid] = useState('');
  const [collabName, setCollabName] = useState('');
  const [collabRole, setCollabRole] = useState<Exclude<MemberRole, 'owner'>>('accountant');

  // Backup / Restore state
  const [restoreStrategy, setRestoreStrategy] = useState<'merge' | 'replace'>('merge');
  const [pendingRestoreJson, setPendingRestoreJson] = useState<string>('');
  const [pendingRestoreFileName, setPendingRestoreFileName] = useState<string>('');
  const [confirmWipe, setConfirmWipe] = useState(false);

  const [feedback, setFeedback] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    setFeedback(null);
    const parsed = parseAmountToMinor(accOpening || '0', accCurrency, { allowZero: true });
    if (!parsed.ok) {
      setFeedback({ type: 'err', text: parsed.message });
      return;
    }
    try {
      await onCreateAccount({
        name: accName.trim(),
        accountType: accType,
        currency: accCurrency,
        openingBalanceMinor: parsed.minorUnits,
      });
      setAccName('');
      setAccOpening('0.00');
      setFeedback({ type: 'ok', text: 'Financial account created.' });
    } catch (err) {
      setFeedback({ type: 'err', text: err instanceof Error ? err.message : 'Could not create account.' });
    }
  };

  const handleAddCategory = async (e: React.FormEvent) => {
    e.preventDefault();
    setFeedback(null);
    try {
      await onSaveCategory({
        name: catName.trim(),
        kind: catKind,
        color: catColor,
        icon: 'tag',
      });
      setCatName('');
      setFeedback({ type: 'ok', text: 'Category added.' });
    } catch (err) {
      setFeedback({ type: 'err', text: err instanceof Error ? err.message : 'Could not save category.' });
    }
  };

  const handleAddMember = async (e: React.FormEvent) => {
    e.preventDefault();
    setFeedback(null);
    try {
      await onAddCollaborator({
        memberUid: collabUid,
        displayName: collabName,
        targetRole: collabRole,
      });
      setCollabUid('');
      setCollabName('');
      setFeedback({ type: 'ok', text: 'Team role assigned.' });
    } catch (err) {
      setFeedback({ type: 'err', text: err instanceof Error ? err.message : 'Could not assign role.' });
    }
  };

  const handleDownloadBackup = () => {
    const archive = buildVerifiedBackupArchive({
      business: activeBusiness,
      accounts,
      categories,
      parties,
      transactions,
      auditEvents,
    });
    downloadBackupJsonFile(archive);
    setFeedback({
      type: 'ok',
      text: `Verified backup created (${archive.transactions.length} transactions, checksum ${archive.checksum}).`,
    });
  };

  const handleRestoreFilePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      setPendingRestoreJson(String(reader.result || ''));
      setPendingRestoreFileName(file.name);
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const executeRestore = async () => {
    if (!pendingRestoreJson) return;
    setFeedback(null);
    try {
      const res = await onRestoreBackup(pendingRestoreJson, restoreStrategy);
      setPendingRestoreJson('');
      setPendingRestoreFileName('');
      setFeedback({
        type: 'ok',
        text: `Restore complete: ${res.importedTransactions} transactions imported, ${res.skippedDuplicates} duplicates prevented.`,
      });
    } catch (err) {
      setFeedback({ type: 'err', text: err instanceof Error ? err.message : 'Backup validation failed.' });
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
          {translate(locale, 'navSettings')}
        </h1>
        <p className="text-xs text-[#58655D] dark:text-[#98A89E] mt-0.5">
          Manage cash accounts, categories, isolated businesses, team permissions, themes, and verified backups
        </p>
      </div>

      {feedback && (
        <div
          role="status"
          className={`rounded-xl px-4 py-3 text-sm border ${
            feedback.type === 'ok'
              ? 'border-[#168653]/40 bg-[#168653]/10 text-[#126B45] dark:text-[#34D399]'
              : 'border-[#D94848]/40 bg-[#D94848]/10 text-[#D94848] dark:text-[#F87171]'
          }`}
        >
          {feedback.text}
        </div>
      )}

      {/* 1. Financial Accounts & Transfers */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <Wallet className="w-5 h-5 text-[#126B45] dark:text-[#34D399]" aria-hidden="true" />
            <h2 className="font-display text-base font-bold">Cash, Bank & Mobile Money Accounts</h2>
          </div>
          {perms.canRecordTransactions && accounts.length >= 2 && (
            <button
              type="button"
              onClick={onOpenTransfer}
              className="min-h-[38px] px-3.5 py-1.5 rounded-xl border border-[#126B45] text-[#126B45] dark:text-[#34D399] text-xs font-medium flex items-center gap-1.5 cursor-pointer"
            >
              <ArrowLeftRight className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'actionTransfer')}</span>
            </button>
          )}
        </div>

        <div className="divide-y divide-[#E2E8E3] dark:divide-[#28362D] border-t border-b border-[#E2E8E3] dark:border-[#28362D]">
          {accounts.map((acc) => (
            <div key={acc.id} className="py-3.5 flex items-center justify-between gap-4">
              <div>
                <p className="text-sm font-semibold">{acc.name}</p>
                <p className="text-xs text-[#58655D] dark:text-[#98A89E] capitalize">
                  {acc.accountType.replace('_', ' ')} · {acc.currency} · Opening{' '}
                  <span className="font-mono-num">{formatMinorAmount(acc.openingBalanceMinor, acc.currency, locale)}</span>
                </p>
              </div>
              <div className="text-right">
                <p className="text-sm font-bold font-mono-num">
                  {formatMinorAmount(accountBalances[acc.id] || 0, acc.currency, locale)}
                </p>
                <p className="text-[11px] text-[#58655D] dark:text-[#98A89E]">Current Balance</p>
              </div>
            </div>
          ))}
        </div>

        {perms.canManageAccounts && (
          <form onSubmit={handleAddAccount} className="grid grid-cols-1 sm:grid-cols-5 gap-3 items-end">
            <div className="sm:col-span-2">
              <label htmlFor="new-acc-name" className="block text-xs font-medium mb-1">
                New Account Name
              </label>
              <input
                id="new-acc-name"
                type="text"
                required
                maxLength={80}
                placeholder="e.g. Rawbank USD / M-Pesa"
                value={accName}
                onChange={(e) => setAccName(e.target.value)}
                className="w-full min-h-[40px] px-3 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              />
            </div>
            <div>
              <label htmlFor="new-acc-type" className="block text-xs font-medium mb-1">
                Type
              </label>
              <select
                id="new-acc-type"
                value={accType}
                onChange={(e) => setAccType(e.target.value as Account['accountType'])}
                className="w-full min-h-[40px] px-2.5 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              >
                <option value="cash">Cash</option>
                <option value="bank">Bank</option>
                <option value="mobile_money">Mobile Money</option>
                <option value="other">Other</option>
              </select>
            </div>
            <div>
              <label htmlFor="new-acc-ccy" className="block text-xs font-medium mb-1">
                Currency & Opening
              </label>
              <div className="flex gap-1.5">
                <select
                  id="new-acc-ccy"
                  value={accCurrency}
                  onChange={(e) => setAccCurrency(e.target.value as CurrencyCode)}
                  className="w-20 min-h-[40px] px-1.5 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
                >
                  {SUPPORTED_CURRENCIES.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
                <input
                  type="text"
                  inputMode="decimal"
                  aria-label="New account opening balance"
                  value={accOpening}
                  onChange={(e) => setAccOpening(e.target.value)}
                  className="w-full min-h-[40px] px-2.5 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
                />
              </div>
            </div>
            <button
              type="submit"
              className="min-h-[40px] px-4 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-xs font-medium flex items-center justify-center gap-1.5 cursor-pointer"
            >
              <Plus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Add Account</span>
            </button>
          </form>
        )}
      </section>

      {/* 2. Categories Manager */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 space-y-5">
        <div className="flex items-center gap-2.5">
          <Tags className="w-5 h-5 text-[#126B45] dark:text-[#34D399]" aria-hidden="true" />
          <h2 className="font-display text-base font-bold">Income & Expense Categories</h2>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2.5">
          {categories.map((cat) => (
            <div
              key={cat.id}
              className="px-3.5 py-2.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] flex items-center justify-between gap-2 text-xs"
            >
              <div className="min-w-0">
                <p className={`font-medium truncate ${cat.archived ? 'line-through opacity-60' : ''}`}>
                  {cat.name}
                </p>
                <p className="text-[11px] text-[#58655D] dark:text-[#98A89E] capitalize">
                  {cat.kind} · {cat.color}
                </p>
              </div>
              {perms.canRecordTransactions && (
                <button
                  type="button"
                  onClick={() =>
                    onSaveCategory({
                      existingCategory: cat,
                      name: cat.name,
                      kind: cat.kind,
                      color: cat.color,
                      icon: cat.icon,
                      archived: !cat.archived,
                    })
                  }
                  className="text-xs font-medium text-[#58655D] hover:text-[#17231D] dark:hover:text-white cursor-pointer"
                >
                  {cat.archived ? 'Restore' : 'Archive'}
                </button>
              )}
            </div>
          ))}
        </div>

        {perms.canRecordTransactions && (
          <form onSubmit={handleAddCategory} className="flex flex-wrap items-end gap-3 pt-2">
            <div className="flex-1 min-w-[180px]">
              <label htmlFor="new-cat-name" className="block text-xs font-medium mb-1">
                New Category Name
              </label>
              <input
                id="new-cat-name"
                type="text"
                required
                maxLength={64}
                placeholder="e.g. Delivery Fuel / Catering"
                value={catName}
                onChange={(e) => setCatName(e.target.value)}
                className="w-full min-h-[40px] px-3 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              />
            </div>
            <div>
              <label htmlFor="new-cat-kind" className="block text-xs font-medium mb-1">
                Kind
              </label>
              <select
                id="new-cat-kind"
                value={catKind}
                onChange={(e) => setCatKind(e.target.value as Category['kind'])}
                className="min-h-[40px] px-3 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              >
                <option value="expense">Expense</option>
                <option value="income">Income</option>
              </select>
            </div>
            <div>
              <span className="block text-xs font-medium mb-1">Color</span>
              <div className="flex items-center gap-1.5 min-h-[40px]">
                {CATEGORY_SWATCHES.map((hex) => (
                  <button
                    key={hex}
                    type="button"
                    aria-label={`Select color ${hex}`}
                    onClick={() => setCatColor(hex)}
                    style={{ backgroundColor: hex }}
                    className={`w-6 h-6 rounded-md cursor-pointer ${
                      catColor === hex ? 'ring-2 ring-offset-2 ring-[#17231D] dark:ring-white' : ''
                    }`}
                  />
                ))}
              </div>
            </div>
            <button
              type="submit"
              className="min-h-[40px] px-4 py-2 rounded-xl bg-[#126B45] text-white text-xs font-medium cursor-pointer"
            >
              Add Category
            </button>
          </form>
        )}
      </section>

      {/* 3. Multi-Business Isolation & RBAC Roles */}
      <section className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Building2 className="w-5 h-5 text-[#126B45] dark:text-[#34D399]" aria-hidden="true" />
              <h2 className="font-display text-base font-bold">Businesses ({businesses.length})</h2>
            </div>
            <button
              type="button"
              onClick={onStartNewBusiness}
              className="min-h-[36px] px-3 py-1.5 rounded-lg border border-[#126B45] text-[#126B45] dark:text-[#34D399] text-xs font-medium cursor-pointer"
            >
              + New Business
            </button>
          </div>

          <p className="text-xs text-[#58655D] dark:text-[#98A89E]">
            Each business maintains strictly isolated accounts, categories, parties, and financial ledgers.
          </p>

          <div className="space-y-2">
            {businesses.map((b) => (
              <button
                key={b.id}
                type="button"
                onClick={() => onSwitchBusiness(b.id)}
                className={`w-full min-h-[48px] px-4 py-2.5 rounded-xl border text-left flex items-center justify-between text-xs cursor-pointer ${
                  b.id === activeBusiness.id
                    ? 'border-[#126B45] bg-[#126B45]/8 font-semibold'
                    : 'border-[#E2E8E3] dark:border-[#28362D]'
                }`}
              >
                <span>{b.name}</span>
                <span className="font-mono-num">{b.defaultCurrency}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Team Roles & Permissions */}
        <div className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 space-y-4">
          <div className="flex items-center gap-2">
            <Users className="w-5 h-5 text-[#126B45] dark:text-[#34D399]" aria-hidden="true" />
            <h2 className="font-display text-base font-bold">Access Control & Team Roles</h2>
          </div>

          <p className="text-xs text-[#58655D] dark:text-[#98A89E]">
            Active role: <strong className="capitalize text-[#17231D] dark:text-[#ECF3EE]">{role}</strong> · Enforced in server rules & ledger engine.
          </p>

          <div className="divide-y divide-[#E2E8E3] dark:divide-[#28362D] text-xs">
            {members.map((m) => (
              <div key={m.memberUid} className="py-2 flex items-center justify-between">
                <span>{m.displayName}</span>
                <span className="capitalize text-[#58655D] dark:text-[#98A89E]">{m.role}</span>
              </div>
            ))}
          </div>

          {perms.canManageMembers && (
            <form onSubmit={handleAddMember} className="grid grid-cols-1 sm:grid-cols-3 gap-2 pt-2">
              <input
                type="text"
                required
                placeholder="User UID"
                value={collabUid}
                onChange={(e) => setCollabUid(e.target.value)}
                className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
              />
              <input
                type="text"
                required
                placeholder="Display Name"
                value={collabName}
                onChange={(e) => setCollabName(e.target.value)}
                className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              />
              <div className="flex gap-1.5">
                <select
                  aria-label="Collaborator role"
                  value={collabRole}
                  onChange={(e) => setCollabRole(e.target.value as any)}
                  className="flex-1 min-h-[38px] px-2 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
                >
                  <option value="admin">Admin</option>
                  <option value="accountant">Accountant</option>
                  <option value="manager">Manager</option>
                  <option value="viewer">Viewer</option>
                </select>
                <button
                  type="submit"
                  className="min-h-[38px] px-3 py-1.5 rounded-lg bg-[#126B45] text-white text-xs font-medium cursor-pointer"
                >
                  Assign
                </button>
              </div>
            </form>
          )}
        </div>
      </section>

      {/* 4. Theme, Localization & Reminders */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 grid grid-cols-1 md:grid-cols-3 gap-6">
        <div>
          <p className="text-xs font-semibold mb-2">{translate(locale, 'themeLabel')}</p>
          <div className="inline-flex rounded-xl bg-[#EFF3EE] dark:bg-[#212D25] p-1">
            {(
              [
                { id: 'light', label: translate(locale, 'themeLight') },
                { id: 'dark', label: translate(locale, 'themeDark') },
                { id: 'system', label: translate(locale, 'themeSystem') },
              ] as const
            ).map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => onChangeTheme(t.id)}
                className={`min-h-[36px] px-3 py-1.5 text-xs font-medium rounded-lg cursor-pointer whitespace-nowrap ${
                  theme === t.id
                    ? 'bg-white dark:bg-[#19221C] text-[#17231D] dark:text-white shadow-xs'
                    : 'text-[#58655D] dark:text-[#98A89E]'
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <p className="text-xs font-semibold mb-2">{translate(locale, 'languageLabel')}</p>
          <div className="inline-flex rounded-xl bg-[#EFF3EE] dark:bg-[#212D25] p-1">
            <button
              type="button"
              onClick={() => onChangeLocale('en')}
              className={`min-h-[36px] px-3.5 py-1.5 text-xs font-medium rounded-lg cursor-pointer ${
                locale === 'en' ? 'bg-white dark:bg-[#19221C] shadow-xs' : 'text-[#58655D] dark:text-[#98A89E]'
              }`}
            >
              English
            </button>
            <button
              type="button"
              onClick={() => onChangeLocale('fr')}
              className={`min-h-[36px] px-3.5 py-1.5 text-xs font-medium rounded-lg cursor-pointer ${
                locale === 'fr' ? 'bg-white dark:bg-[#19221C] shadow-xs' : 'text-[#58655D] dark:text-[#98A89E]'
              }`}
            >
              Français
            </button>
          </div>
        </div>

        <div>
          <p className="text-xs font-semibold mb-2">Due-Date Reminders</p>
          <label className="inline-flex items-center gap-2.5 text-xs cursor-pointer min-h-[36px]">
            <input
              type="checkbox"
              checked={notificationsEnabled}
              onChange={(e) => onToggleNotifications(e.target.checked)}
              className="w-4 h-4 accent-[#126B45]"
            />
            <span>Alert on due receivables & payables</span>
          </label>
        </div>
      </section>

      {/* 5. Verified Backup, Restore & Account Portability */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 space-y-5">
        <h2 className="font-display text-base font-bold">Backup, Restore & Account Security</h2>
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={handleDownloadBackup}
            className="min-h-[42px] px-4 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-xs font-medium flex items-center gap-2 cursor-pointer"
          >
            <Download className="w-4 h-4" aria-hidden="true" />
            <span>{translate(locale, 'downloadBackup')}</span>
          </button>

          {perms.canManageAccounts && (
            <label className="min-h-[42px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium inline-flex items-center gap-2 cursor-pointer hover:border-[#126B45]">
              <Upload className="w-4 h-4" aria-hidden="true" />
              <span>{translate(locale, 'restoreBackup')}</span>
              <input type="file" accept="application/json" onChange={handleRestoreFilePick} className="sr-only" />
            </label>
          )}

          {mode === 'guest' && onSignInGoogleUpgrade && (
            <button
              type="button"
              onClick={onSignInGoogleUpgrade}
              className="min-h-[42px] px-4 py-2 rounded-xl border border-[#126B45] text-[#126B45] dark:text-[#34D399] text-xs font-medium cursor-pointer"
            >
              Sign in with Google for Cloud Sync
            </button>
          )}

          <button
            type="button"
            onClick={onSignOut}
            className="min-h-[42px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium cursor-pointer"
          >
            {translate(locale, 'signOut')} {userEmail ? `(${userEmail})` : ''}
          </button>
        </div>

        {/* Pending Restore Confirmation */}
        {pendingRestoreJson && (
          <div className="rounded-xl border border-[#C88719]/50 bg-[#C88719]/10 p-4 space-y-3">
            <p className="text-xs font-semibold">
              Confirm Backup Import: <span className="font-mono-num">{pendingRestoreFileName}</span>
            </p>
            <div className="flex flex-wrap items-center gap-4 text-xs">
              <label className="inline-flex items-center gap-1.5 cursor-pointer">
                <input
                  type="radio"
                  name="restore-strategy"
                  checked={restoreStrategy === 'merge'}
                  onChange={() => setRestoreStrategy('merge')}
                />
                <span>Merge (keep current records, skip duplicate IDs/keys)</span>
              </label>
              <label className="inline-flex items-center gap-1.5 cursor-pointer">
                <input
                  type="radio"
                  name="restore-strategy"
                  checked={restoreStrategy === 'replace'}
                  onChange={() => setRestoreStrategy('replace')}
                />
                <span>Replace active business transactions</span>
              </label>
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={executeRestore}
                className="min-h-[38px] px-4 py-1.5 rounded-lg bg-[#126B45] text-white text-xs font-medium cursor-pointer"
              >
                Verify Checksum & Import
              </button>
              <button
                type="button"
                onClick={() => setPendingRestoreJson('')}
                className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-xs cursor-pointer"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {/* Destructive Account Data Deletion */}
        <div className="pt-4 border-t border-[#E2E8E3] dark:border-[#28362D] flex flex-wrap items-center justify-between gap-3">
          <div className="text-xs text-[#58655D] dark:text-[#98A89E]">
            Export a JSON backup before deleting your account or local workspace data.
          </div>
          {!confirmWipe ? (
            <button
              type="button"
              onClick={() => setConfirmWipe(true)}
              className="min-h-[38px] px-3.5 py-1.5 rounded-lg border border-[#D94848]/50 text-[#D94848] text-xs font-medium flex items-center gap-1.5 cursor-pointer"
            >
              <ShieldAlert className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Delete Account & Financial Data</span>
            </button>
          ) : (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={onWipeAccountData}
                className="min-h-[38px] px-3.5 py-1.5 rounded-lg bg-[#D94848] text-white text-xs font-medium cursor-pointer"
              >
                Confirm Permanent Wipe
              </button>
              <button
                type="button"
                onClick={() => setConfirmWipe(false)}
                className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-xs cursor-pointer"
              >
                Cancel
              </button>
            </div>
          )}
        </div>
      </section>
    </div>
  );
};
