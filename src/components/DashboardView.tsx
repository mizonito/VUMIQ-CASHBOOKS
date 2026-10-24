import React, { useMemo } from 'react';
import {
  ArrowDownLeft,
  ArrowUpRight,
  ArrowLeftRight,
  UserPlus,
  BarChart3,
  Plus,
  Bell,
} from 'lucide-react';
import {
  Account,
  Category,
  CurrencyCode,
  DateFilterPreset,
  DateRange,
  LocaleCode,
  MemberRole,
  NavDestination,
  Party,
  Transaction,
  TransactionType,
} from '../types/cashbook';
import {
  computeCashBalanceHistory,
  computeCategoryBreakdown,
  computePeriodSummariesByCurrency,
  computeTimeSeriesData,
  filterAndSearchTransactions,
  ROLE_PERMISSIONS,
} from '../engine/accounting';
import { formatMinorAmount } from '../engine/money';
import { formatLocaleDate, getTransactionTypeLabel, translate } from '../i18n/translations';

interface DashboardViewProps {
  locale: LocaleCode;
  role: MemberRole;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  defaultCurrency: CurrencyCode;
  selectedCurrency: CurrencyCode;
  onSelectCurrency: (currency: CurrencyCode) => void;
  datePreset: DateFilterPreset;
  customDateRange: DateRange;
  effectiveDateRange: DateRange;
  onChangeDatePreset: (preset: DateFilterPreset) => void;
  onChangeCustomDateRange: (range: DateRange) => void;
  onOpenNewTransaction: (type: TransactionType) => void;
  onSelectTransaction: (tx: Transaction) => void;
  onNavigate: (dest: NavDestination) => void;
  onOpenAddParty: () => void;
  notificationsEnabled: boolean;
  todayIso: string;
}

export const DashboardView: React.FC<DashboardViewProps> = ({
  locale,
  role,
  accounts,
  categories,
  parties,
  transactions,
  defaultCurrency,
  selectedCurrency,
  onSelectCurrency,
  datePreset,
  customDateRange,
  effectiveDateRange,
  onChangeDatePreset,
  onChangeCustomDateRange,
  onOpenNewTransaction,
  onSelectTransaction,
  onNavigate,
  onOpenAddParty,
  notificationsEnabled,
  todayIso,
}) => {
  const canRecord = ROLE_PERMISSIONS[role].canRecordTransactions;

  const summariesByCurrency = useMemo(
    () =>
      computePeriodSummariesByCurrency(
        accounts,
        parties,
        transactions,
        effectiveDateRange,
        defaultCurrency
      ),
    [accounts, parties, transactions, effectiveDateRange, defaultCurrency]
  );

  const availableCurrencies = useMemo(
    () => Object.keys(summariesByCurrency) as CurrencyCode[],
    [summariesByCurrency]
  );

  const activeCurrency = summariesByCurrency[selectedCurrency]
    ? selectedCurrency
    : availableCurrencies[0] || defaultCurrency;

  const currentSummary = summariesByCurrency[activeCurrency] || {
    currency: activeCurrency,
    currentCashBalanceMinor: 0,
    periodIncomeMinor: 0,
    periodExpenseMinor: 0,
    netIncomeMinor: 0,
    periodCashInflowMinor: 0,
    periodCashOutflowMinor: 0,
    netCashMovementMinor: 0,
    totalReceivablesMinor: 0,
    totalPayablesMinor: 0,
    accountCount: 0,
  };

  const granularity = datePreset === 'year' || datePreset === 'all' ? 'month' : 'day';

  const timeSeries = useMemo(
    () => computeTimeSeriesData(transactions, activeCurrency, effectiveDateRange, granularity),
    [transactions, activeCurrency, effectiveDateRange, granularity]
  );

  const expenseCategories = useMemo(
    () => computeCategoryBreakdown(categories, transactions, activeCurrency, 'expense', effectiveDateRange),
    [categories, transactions, activeCurrency, effectiveDateRange]
  );

  const balanceHistory = useMemo(
    () => computeCashBalanceHistory(accounts, transactions, activeCurrency, effectiveDateRange),
    [accounts, transactions, activeCurrency, effectiveDateRange]
  );

  const recentTransactions = useMemo(
    () =>
      filterAndSearchTransactions(transactions, accounts, categories, parties, {
        currency: activeCurrency,
        includeVoided: false,
        sortBy: 'date_desc',
      }).slice(0, 8),
    [transactions, accounts, categories, parties, activeCurrency]
  );

  // Payment due-date reminders (Receivables / Payables approaching or overdue)
  const dueReminders = useMemo(() => {
    if (!notificationsEnabled) return [];
    return transactions
      .filter(
        (t) =>
          t.status === 'posted' &&
          t.dueDate &&
          ['credit_sale', 'credit_purchase'].includes(t.type) &&
          t.dueDate <= todayIso
      )
      .slice(0, 3);
  }, [transactions, notificationsEnabled, todayIso]);

  const accMap = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts]);
  const catMap = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories]);
  const partyMap = useMemo(() => new Map(parties.map((p) => [p.id, p])), [parties]);

  const postedTxCount = transactions.filter((t) => t.status === 'posted').length;

  const dateFilterButtons: Array<{ id: DateFilterPreset; label: string }> = [
    { id: 'today', label: translate(locale, 'filterToday') },
    { id: 'week', label: translate(locale, 'filterWeek') },
    { id: 'month', label: translate(locale, 'filterMonth') },
    { id: 'year', label: translate(locale, 'filterYear') },
    { id: 'all', label: translate(locale, 'filterAll') },
    { id: 'custom', label: translate(locale, 'filterCustom') },
  ];

  // SVG Bar Chart scaling
  const maxBarMinor = Math.max(
    1,
    ...timeSeries.map((pt) => Math.max(pt.incomeMinor, pt.expenseMinor))
  );

  return (
    <div className="space-y-8">
      {/* Top Toolbar: Period Selector + Multi-Currency Selector */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-1 p-1 rounded-xl bg-[#EFF3EE] dark:bg-[#212D25] w-fit">
          {dateFilterButtons.map((btn) => (
            <button
              key={btn.id}
              type="button"
              onClick={() => onChangeDatePreset(btn.id)}
              className={`min-h-[36px] px-3 py-1.5 text-xs font-medium rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                datePreset === btn.id
                  ? 'bg-white dark:bg-[#19221C] text-[#17231D] dark:text-white shadow-xs'
                  : 'text-[#58655D] dark:text-[#98A89E] hover:text-[#17231D] dark:hover:text-white'
              }`}
            >
              {btn.label}
            </button>
          ))}
        </div>

        {/* Currency Switcher (strictly prevents combining raw multi-currency totals) */}
        {availableCurrencies.length > 1 && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-[#58655D] dark:text-[#98A89E]">Ledger Currency:</span>
            <div className="inline-flex items-center gap-1 p-1 rounded-xl bg-[#EFF3EE] dark:bg-[#212D25]">
              {availableCurrencies.map((cur) => (
                <button
                  key={cur}
                  type="button"
                  onClick={() => onSelectCurrency(cur)}
                  className={`min-h-[34px] px-3 py-1 text-xs font-semibold font-mono-num rounded-lg transition-colors cursor-pointer ${
                    activeCurrency === cur
                      ? 'bg-[#126B45] text-white'
                      : 'text-[#58655D] dark:text-[#98A89E] hover:text-[#17231D] dark:hover:text-white'
                  }`}
                >
                  {cur}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Custom Date Range Inputs */}
      {datePreset === 'custom' && (
        <div className="flex flex-wrap items-end gap-4 p-4 rounded-xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D]">
          <div>
            <label htmlFor="dash-custom-start" className="block text-xs font-medium mb-1">
              Start Date
            </label>
            <input
              id="dash-custom-start"
              type="date"
              value={customDateRange.startDate}
              onChange={(e) =>
                onChangeCustomDateRange({ ...customDateRange, startDate: e.target.value })
              }
              className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
            />
          </div>
          <div>
            <label htmlFor="dash-custom-end" className="block text-xs font-medium mb-1">
              End Date
            </label>
            <input
              id="dash-custom-end"
              type="date"
              value={customDateRange.endDate}
              onChange={(e) =>
                onChangeCustomDateRange({ ...customDateRange, endDate: e.target.value })
              }
              className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
            />
          </div>
        </div>
      )}

      {/* Due Payment Reminders Banner */}
      {dueReminders.length > 0 && (
        <div className="rounded-xl border border-[#C88719]/40 bg-[#C88719]/10 px-4 py-3 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 text-sm">
            <Bell className="w-4 h-4 text-[#C88719] shrink-0" aria-hidden="true" />
            <span>
              <strong>{dueReminders.length}</strong> credit/payable reminder(s) have reached or passed their due date.
            </span>
          </div>
          <button
            type="button"
            onClick={() => onNavigate('parties')}
            className="text-xs font-semibold text-[#126B45] dark:text-[#34D399] hover:underline whitespace-nowrap cursor-pointer"
          >
            Review Parties
          </button>
        </div>
      )}

      {/* Primary Focal Anchor: Current Cash Balance + Period Breakdown */}
      <section aria-label="Financial Summary" className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-stretch">
        {/* Current Cash Balance Hero Card (Clearly distinguished from selected period movement) */}
        <div className="lg:col-span-5 p-6 rounded-2xl bg-[#084B32] text-white flex flex-col justify-between">
          <div>
            <p className="text-xs font-medium text-[#A6D9C0]">
              {translate(locale, 'currentCashBalance')} ({activeCurrency})
            </p>
            <p
              data-testid="dashboard-cash-balance"
              className="mt-2 text-3xl sm:text-4xl font-bold font-mono-num tracking-tight"
            >
              {formatMinorAmount(currentSummary.currentCashBalanceMinor, activeCurrency, locale)}
            </p>
            <p className="mt-1.5 text-xs text-[#BCE2CF]">
              {translate(locale, 'allTimePostedNote')} · {currentSummary.accountCount} account(s)
            </p>
          </div>

          <div className="mt-6 pt-4 border-t border-white/15 grid grid-cols-2 gap-4">
            <div>
              <p className="text-xs text-[#A6D9C0]">{translate(locale, 'moneyToReceive')}</p>
              <p
                data-testid="dashboard-receivables"
                className="mt-0.5 text-base font-semibold font-mono-num text-white"
              >
                {formatMinorAmount(currentSummary.totalReceivablesMinor, activeCurrency, locale)}
              </p>
            </div>
            <div>
              <p className="text-xs text-[#A6D9C0]">{translate(locale, 'moneyToPay')}</p>
              <p
                data-testid="dashboard-payables"
                className="mt-0.5 text-base font-semibold font-mono-num text-white"
              >
                {formatMinorAmount(currentSummary.totalPayablesMinor, activeCurrency, locale)}
              </p>
            </div>
          </div>
        </div>

        {/* Selected Period Metrics Grid */}
        <div className="lg:col-span-7 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 flex flex-col justify-between">
          <div className="flex items-center justify-between gap-2 pb-4 border-b border-[#E2E8E3] dark:border-[#28362D]">
            <h2 className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE]">
              Selected Period Performance ({activeCurrency})
            </h2>
            <span className="text-xs text-[#58655D] dark:text-[#98A89E] font-mono-num">
              {effectiveDateRange.startDate && effectiveDateRange.endDate
                ? `${effectiveDateRange.startDate} → ${effectiveDateRange.endDate}`
                : translate(locale, 'filterAll')}
            </span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 pt-5">
            <div>
              <div className="flex items-center gap-1.5 text-xs text-[#58655D] dark:text-[#98A89E]">
                <ArrowDownLeft className="w-3.5 h-3.5 text-[#168653] dark:text-[#34D399]" aria-hidden="true" />
                <span>{translate(locale, 'periodIncome')}</span>
              </div>
              <p
                data-testid="dashboard-period-income"
                className="mt-1.5 text-xl font-bold font-mono-num text-[#168653] dark:text-[#34D399]"
              >
                {formatMinorAmount(currentSummary.periodIncomeMinor, activeCurrency, locale, { showPlusSign: true })}
              </p>
            </div>

            <div>
              <div className="flex items-center gap-1.5 text-xs text-[#58655D] dark:text-[#98A89E]">
                <ArrowUpRight className="w-3.5 h-3.5 text-[#D94848] dark:text-[#F87171]" aria-hidden="true" />
                <span>{translate(locale, 'periodExpenses')}</span>
              </div>
              <p
                data-testid="dashboard-period-expenses"
                className="mt-1.5 text-xl font-bold font-mono-num text-[#D94848] dark:text-[#F87171]"
              >
                {formatMinorAmount(currentSummary.periodExpenseMinor, activeCurrency, locale)}
              </p>
            </div>

            <div>
              <div className="flex items-center gap-1.5 text-xs text-[#58655D] dark:text-[#98A89E]">
                <ArrowLeftRight className="w-3.5 h-3.5 text-[#126B45] dark:text-[#34D399]" aria-hidden="true" />
                <span>{translate(locale, 'netCashMovement')}</span>
              </div>
              <p
                data-testid="dashboard-net-cash"
                className={`mt-1.5 text-xl font-bold font-mono-num ${
                  currentSummary.netCashMovementMinor >= 0
                    ? 'text-[#168653] dark:text-[#34D399]'
                    : 'text-[#D94848] dark:text-[#F87171]'
                }`}
              >
                {formatMinorAmount(currentSummary.netCashMovementMinor, activeCurrency, locale, { showPlusSign: true })}
              </p>
            </div>
          </div>

          {/* Quick Actions Bar */}
          <div className="mt-6 pt-4 border-t border-[#E2E8E3] dark:border-[#28362D] flex flex-wrap items-center gap-2.5">
            {canRecord && (
              <>
                <button
                  type="button"
                  onClick={() => onOpenNewTransaction('income')}
                  className="min-h-[40px] px-3.5 py-2 rounded-xl bg-[#168653] hover:bg-[#126B45] text-white text-xs font-medium flex items-center gap-1.5 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>{translate(locale, 'actionAddIncome')}</span>
                </button>
                <button
                  type="button"
                  onClick={() => onOpenNewTransaction('expense')}
                  className="min-h-[40px] px-3.5 py-2 rounded-xl bg-[#D94848] hover:bg-[#B93838] text-white text-xs font-medium flex items-center gap-1.5 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>{translate(locale, 'actionAddExpense')}</span>
                </button>
                <button
                  type="button"
                  onClick={() => onOpenNewTransaction('transfer')}
                  className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] hover:border-[#126B45] text-xs font-medium flex items-center gap-1.5 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <ArrowLeftRight className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>{translate(locale, 'actionTransfer')}</span>
                </button>
                <button
                  type="button"
                  onClick={onOpenAddParty}
                  className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] hover:border-[#126B45] text-xs font-medium flex items-center gap-1.5 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <UserPlus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>{translate(locale, 'actionAddParty')}</span>
                </button>
              </>
            )}
            <button
              type="button"
              onClick={() => onNavigate('reports')}
              className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] hover:border-[#126B45] text-xs font-medium flex items-center gap-1.5 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              <BarChart3 className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'actionViewReports')}</span>
            </button>
          </div>
        </div>
      </section>

      {/* Onboarding Empty State when Business has 0 Transactions */}
      {postedTxCount === 0 ? (
        <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-8 text-center max-w-3xl mx-auto">
          <h3 className="font-display text-lg font-bold text-[#17231D] dark:text-[#ECF3EE]">
            {translate(locale, 'onboardingEmptyTitle')}
          </h3>
          <p className="mt-2 text-sm text-[#58655D] dark:text-[#98A89E] max-w-xl mx-auto">
            {translate(locale, 'onboardingEmptyBody')}
          </p>
          {canRecord && (
            <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
              <button
                type="button"
                onClick={() => onOpenNewTransaction('income')}
                className="min-h-[44px] px-5 py-2.5 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-medium cursor-pointer"
              >
                Record First Income
              </button>
              <button
                type="button"
                onClick={() => onOpenNewTransaction('expense')}
                className="min-h-[44px] px-5 py-2.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-sm font-medium hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] cursor-pointer"
              >
                Record First Expense
              </button>
            </div>
          )}
        </section>
      ) : (
        <>
          {/* Real Data Visualizations */}
          <section aria-label="Financial Charts" className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-stretch">
            {/* Income vs Expenses Bar Chart */}
            <div className="lg:col-span-7 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6 flex flex-col justify-between">
              <div className="flex items-center justify-between gap-4 mb-4">
                <h3 className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE]">
                  {translate(locale, 'chartIncomeVsExpense')} ({activeCurrency})
                </h3>
                <div className="flex items-center gap-3 text-xs text-[#58655D] dark:text-[#98A89E]">
                  <span className="inline-flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-xs bg-[#168653] dark:bg-[#34D399]" aria-hidden="true" />
                    <span>{translate(locale, 'txType_income')}</span>
                  </span>
                  <span aria-hidden="true">·</span>
                  <span className="inline-flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-xs bg-[#D94848] dark:bg-[#F87171]" aria-hidden="true" />
                    <span>{translate(locale, 'txType_expense')}</span>
                  </span>
                </div>
              </div>

              {timeSeries.length === 0 ? (
                <p className="py-12 text-center text-xs text-[#58655D] dark:text-[#98A89E]">
                  {translate(locale, 'insufficientChartData')}
                </p>
              ) : (
                <div className="space-y-3">
                  <div className="h-44 flex items-end gap-3 pt-4 border-b border-[#E2E8E3] dark:border-[#28362D] overflow-x-auto">
                    {timeSeries.slice(-12).map((pt) => {
                      const incPct = Math.max(pt.incomeMinor > 0 ? 6 : 0, Math.round((pt.incomeMinor / maxBarMinor) * 100));
                      const expPct = Math.max(pt.expenseMinor > 0 ? 6 : 0, Math.round((pt.expenseMinor / maxBarMinor) * 100));
                      return (
                        <div key={pt.bucketKey} className="flex-1 min-w-[44px] flex flex-col items-center gap-1.5 h-full justify-end">
                          <div className="w-full flex items-end justify-center gap-1 h-32">
                            <div
                              title={`Income: ${formatMinorAmount(pt.incomeMinor, activeCurrency, locale)}`}
                              style={{ height: `${incPct}%` }}
                              className="w-3 sm:w-4 rounded-t bg-[#168653] dark:bg-[#34D399] transition-all"
                            />
                            <div
                              title={`Expense: ${formatMinorAmount(pt.expenseMinor, activeCurrency, locale)}`}
                              style={{ height: `${expPct}%` }}
                              className="w-3 sm:w-4 rounded-t bg-[#D94848] dark:bg-[#F87171] transition-all"
                            />
                          </div>
                          <span className="text-[10px] font-mono-num text-[#58655D] dark:text-[#98A89E] truncate max-w-[56px]">
                            {pt.label.slice(5)}
                          </span>
                        </div>
                      );
                    })}
                  </div>

                  {/* Cash Balance History Summary Line */}
                  {balanceHistory.length > 0 && (
                    <div className="pt-2 flex items-center justify-between text-xs text-[#58655D] dark:text-[#98A89E]">
                      <span>{translate(locale, 'chartCashBalanceTrend')}</span>
                      <span className="font-mono-num">
                        {formatMinorAmount(balanceHistory[0].balanceMinor, activeCurrency, locale)} →{' '}
                        <strong className="text-[#17231D] dark:text-[#ECF3EE]">
                          {formatMinorAmount(balanceHistory[balanceHistory.length - 1].balanceMinor, activeCurrency, locale)}
                        </strong>
                      </span>
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* Expense Categories Breakdown */}
            <div className="lg:col-span-5 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-6">
              <h3 className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE] mb-4">
                {translate(locale, 'chartExpenseCategories')} ({activeCurrency})
              </h3>

              {expenseCategories.length === 0 ? (
                <p className="py-12 text-center text-xs text-[#58655D] dark:text-[#98A89E]">
                  No expenses recorded in this period.
                </p>
              ) : (
                <div className="space-y-3.5">
                  {expenseCategories.slice(0, 6).map((item) => (
                    <div key={item.categoryId}>
                      <div className="flex items-center justify-between text-xs mb-1">
                        <span className="font-medium text-[#17231D] dark:text-[#ECF3EE] truncate">
                          {item.categoryName}
                        </span>
                        <span className="font-mono-num text-[#58655D] dark:text-[#98A89E]">
                          {formatMinorAmount(item.amountMinor, activeCurrency, locale)} · {item.percentage}%
                        </span>
                      </div>
                      <div className="h-2 w-full rounded-full bg-[#EFF3EE] dark:bg-[#212D25] overflow-hidden">
                        <div
                          style={{ width: `${Math.max(3, item.percentage)}%`, backgroundColor: item.color }}
                          className="h-full rounded-full"
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>

          {/* Recent Transactions List (Unboxed clean metadata, zero static pill badges) */}
          <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b border-[#E2E8E3] dark:border-[#28362D]">
              <h3 className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE]">
                {translate(locale, 'recentTransactions')} ({activeCurrency})
              </h3>
              <button
                type="button"
                onClick={() => onNavigate('transactions')}
                className="text-xs font-medium text-[#126B45] dark:text-[#34D399] hover:underline cursor-pointer"
              >
                {translate(locale, 'viewAll')}
              </button>
            </div>

            {recentTransactions.length === 0 ? (
              <p className="px-6 py-8 text-sm text-[#58655D] dark:text-[#98A89E]">
                No recent {activeCurrency} transactions.
              </p>
            ) : (
              <div className="divide-y divide-[#E2E8E3] dark:divide-[#28362D]">
                {recentTransactions.map((tx) => {
                  const isInflow = ['income', 'credit_sale', 'receivable_payment', 'refund_expense', 'opening_balance'].includes(tx.type);
                  const isOutflow = ['expense', 'credit_purchase', 'payable_payment', 'refund_income'].includes(tx.type);
                  const accName = accMap.get(tx.accountId)?.name || 'Subledger';
                  const catName = catMap.get(tx.categoryId)?.name || tx.categoryLabelSnapshot || getTransactionTypeLabel(locale, tx.type);
                  const partyName = partyMap.get(tx.partyId)?.name || tx.partyNameSnapshot;

                  return (
                    <button
                      key={tx.id}
                      type="button"
                      onClick={() => onSelectTransaction(tx)}
                      className="w-full min-h-[60px] px-6 py-3.5 flex items-center justify-between gap-4 text-left hover:bg-[#F6F8F5] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
                    >
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-[#17231D] dark:text-[#ECF3EE] truncate">
                          {tx.description}
                        </p>
                        <p className="mt-0.5 text-xs text-[#58655D] dark:text-[#98A89E] truncate">
                          <span>{catName}</span>
                          <span className="mx-1.5" aria-hidden="true">·</span>
                          <span>{accName}</span>
                          {partyName && (
                            <>
                              <span className="mx-1.5" aria-hidden="true">·</span>
                              <span>{partyName}</span>
                            </>
                          )}
                          <span className="mx-1.5" aria-hidden="true">·</span>
                          <span className="font-mono-num">{formatLocaleDate(tx.date, locale)}</span>
                        </p>
                      </div>

                      <div className="text-right shrink-0">
                        <p
                          className={`text-sm font-semibold font-mono-num ${
                            isInflow
                              ? 'text-[#168653] dark:text-[#34D399]'
                              : isOutflow
                              ? 'text-[#D94848] dark:text-[#F87171]'
                              : 'text-[#17231D] dark:text-[#ECF3EE]'
                          }`}
                        >
                          {isOutflow ? '-' : isInflow ? '+' : ''}
                          {formatMinorAmount(tx.amountMinor, tx.currency, locale)}
                        </p>
                        <p className="text-[11px] text-[#58655D] dark:text-[#98A89E]">
                          {getTransactionTypeLabel(locale, tx.type)}
                        </p>
                      </div>
                    </button>
                  );
                })}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
};
