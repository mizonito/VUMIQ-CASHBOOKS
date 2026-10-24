import React, { useMemo, useState } from 'react';
import { Search, RotateCcw, Plus } from 'lucide-react';
import {
  Account,
  Category,
  CurrencyCode,
  LocaleCode,
  MemberRole,
  Party,
  SUPPORTED_CURRENCIES,
  Transaction,
  TransactionFilterQuery,
  TransactionType,
} from '../types/cashbook';
import { filterAndSearchTransactions, ROLE_PERMISSIONS } from '../engine/accounting';
import { formatMinorAmount, parseAmountToMinor } from '../engine/money';
import { formatLocaleDate, getTransactionTypeLabel, translate } from '../i18n/translations';

interface TransactionsViewProps {
  locale: LocaleCode;
  role: MemberRole;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  defaultCurrency: CurrencyCode;
  onOpenNewTransaction: (type?: TransactionType) => void;
  onSelectTransaction: (tx: Transaction) => void;
}

const PAGE_STEP = 25;

export const TransactionsView: React.FC<TransactionsViewProps> = ({
  locale,
  role,
  accounts,
  categories,
  parties,
  transactions,
  defaultCurrency,
  onOpenNewTransaction,
  onSelectTransaction,
}) => {
  const [search, setSearch] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [typeFilter, setTypeFilter] = useState<NonNullable<TransactionFilterQuery['typeFilter']>>('all');
  const [categoryId, setCategoryId] = useState('');
  const [accountId, setAccountId] = useState('');
  const [partyId, setPartyId] = useState('');
  const [currencyFilter, setCurrencyFilter] = useState<CurrencyCode | 'all'>('all');
  const [minAmountStr, setMinAmountStr] = useState('');
  const [maxAmountStr, setMaxAmountStr] = useState('');
  const [includeVoided, setIncludeVoided] = useState(false);
  const [sortBy, setSortBy] = useState<NonNullable<TransactionFilterQuery['sortBy']>>('date_desc');
  const [visibleLimit, setVisibleLimit] = useState(PAGE_STEP);

  const refCur: CurrencyCode = currencyFilter === 'all' ? defaultCurrency : currencyFilter;

  const minMinor = useMemo(() => {
    if (!minAmountStr.trim()) return null;
    const p = parseAmountToMinor(minAmountStr, refCur, { allowZero: true });
    return p.ok ? p.minorUnits : null;
  }, [minAmountStr, refCur]);

  const maxMinor = useMemo(() => {
    if (!maxAmountStr.trim()) return null;
    const p = parseAmountToMinor(maxAmountStr, refCur, { allowZero: true });
    return p.ok ? p.minorUnits : null;
  }, [maxAmountStr, refCur]);

  const filtered = useMemo(
    () =>
      filterAndSearchTransactions(transactions, accounts, categories, parties, {
        search,
        dateRange: { startDate, endDate },
        typeFilter,
        categoryId,
        accountId,
        partyId,
        currency: currencyFilter,
        minAmountMinor: minMinor,
        maxAmountMinor: maxMinor,
        includeVoided,
        sortBy,
      }),
    [
      transactions,
      accounts,
      categories,
      parties,
      search,
      startDate,
      endDate,
      typeFilter,
      categoryId,
      accountId,
      partyId,
      currencyFilter,
      minMinor,
      maxMinor,
      includeVoided,
      sortBy,
    ]
  );

  const visibleRows = filtered.slice(0, visibleLimit);

  const accMap = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts]);
  const catMap = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories]);
  const partyMap = useMemo(() => new Map(parties.map((p) => [p.id, p])), [parties]);

  const hasActiveFilters = Boolean(
    search ||
      startDate ||
      endDate ||
      typeFilter !== 'all' ||
      categoryId ||
      accountId ||
      partyId ||
      currencyFilter !== 'all' ||
      minAmountStr ||
      maxAmountStr ||
      includeVoided
  );

  const resetAllFilters = () => {
    setSearch('');
    setStartDate('');
    setEndDate('');
    setTypeFilter('all');
    setCategoryId('');
    setAccountId('');
    setPartyId('');
    setCurrencyFilter('all');
    setMinAmountStr('');
    setMaxAmountStr('');
    setIncludeVoided(false);
    setSortBy('date_desc');
    setVisibleLimit(PAGE_STEP);
  };

  return (
    <div className="space-y-6">
      {/* Header Row */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
            {translate(locale, 'navTransactions')}
          </h1>
          <p className="text-xs text-[#58655D] dark:text-[#98A89E] mt-0.5">
            Showing {filtered.length} matching ledger entries
          </p>
        </div>

        {ROLE_PERMISSIONS[role].canRecordTransactions && (
          <button
            type="button"
            onClick={() => onOpenNewTransaction('income')}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-medium flex items-center gap-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Plus className="w-4 h-4" aria-hidden="true" />
            <span>{translate(locale, 'recordTransaction')}</span>
          </button>
        )}
      </div>

      {/* Search & Multi-Filter Surface */}
      <section
        aria-label="Search and Filters"
        className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-5 space-y-4"
      >
        <div className="relative">
          <Search
            className="w-4 h-4 text-[#58655D] dark:text-[#98A89E] absolute left-3.5 top-1/2 -translate-y-1/2"
            aria-hidden="true"
          />
          <input
            type="search"
            aria-label="Search transactions"
            placeholder={translate(locale, 'searchPlaceholder')}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setVisibleLimit(PAGE_STEP);
            }}
            className="w-full min-h-[44px] pl-10 pr-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
          />
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
          <div>
            <label htmlFor="flt-type" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Entry Type
            </label>
            <select
              id="flt-type"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value as any)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-medium"
            >
              <option value="all">All Types</option>
              <option value="income_group">All Inflows / Income</option>
              <option value="expense_group">All Outflows / Expenses</option>
              <option value="transfer">Transfers Only</option>
              <option value="credit_sale">Credit Sales (Receivables)</option>
              <option value="receivable_payment">Receivable Settlements</option>
              <option value="credit_purchase">Credit Purchases (Payables)</option>
              <option value="payable_payment">Payable Settlements</option>
            </select>
          </div>

          <div>
            <label htmlFor="flt-account" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Account
            </label>
            <select
              id="flt-account"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-medium"
            >
              <option value="">All Accounts</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.currency})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="flt-category" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Category
            </label>
            <select
              id="flt-category"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-medium"
            >
              <option value="">All Categories</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="flt-party" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Party
            </label>
            <select
              id="flt-party"
              value={partyId}
              onChange={(e) => setPartyId(e.target.value)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-medium"
            >
              <option value="">All Parties</option>
              {parties.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="flt-start" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              From Date
            </label>
            <input
              id="flt-start"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
            />
          </div>

          <div>
            <label htmlFor="flt-end" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              To Date
            </label>
            <input
              id="flt-end"
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
            />
          </div>

          <div>
            <label htmlFor="flt-currency" className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Currency & Sort
            </label>
            <div className="flex gap-2">
              <select
                id="flt-currency"
                value={currencyFilter}
                onChange={(e) => setCurrencyFilter(e.target.value as any)}
                className="w-1/2 min-h-[40px] px-2 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
              >
                <option value="all">All CCY</option>
                {SUPPORTED_CURRENCIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <select
                aria-label="Sort order"
                value={sortBy}
                onChange={(e) => setSortBy(e.target.value as any)}
                className="w-1/2 min-h-[40px] px-2 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs"
              >
                <option value="date_desc">Newest</option>
                <option value="date_asc">Oldest</option>
                <option value="amount_desc">Largest</option>
                <option value="amount_asc">Smallest</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-xs text-[#58655D] dark:text-[#98A89E] mb-1">
              Amount Range ({refCur})
            </label>
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="decimal"
                placeholder="Min"
                value={minAmountStr}
                onChange={(e) => setMinAmountStr(e.target.value)}
                className="w-1/2 min-h-[40px] px-2.5 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
              />
              <input
                type="text"
                inputMode="decimal"
                placeholder="Max"
                value={maxAmountStr}
                onChange={(e) => setMaxAmountStr(e.target.value)}
                className="w-1/2 min-h-[40px] px-2.5 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-xs font-mono-num"
              />
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-[#E2E8E3] dark:border-[#28362D]">
          <label className="inline-flex items-center gap-2 text-xs font-medium cursor-pointer min-h-[36px]">
            <input
              type="checkbox"
              checked={includeVoided}
              onChange={(e) => setIncludeVoided(e.target.checked)}
              className="w-4 h-4 accent-[#126B45]"
            />
            <span>Include voided / reversed entries</span>
          </label>

          {hasActiveFilters && (
            <button
              type="button"
              onClick={resetAllFilters}
              className="min-h-[36px] px-3 py-1.5 rounded-lg text-xs font-medium text-[#126B45] dark:text-[#34D399] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] flex items-center gap-1.5 cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'resetFilters')}</span>
            </button>
          )}
        </div>
      </section>

      {/* Ledger Table / Rows */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] overflow-hidden">
        {visibleRows.length === 0 ? (
          <div className="p-10 text-center space-y-3">
            <p className="text-sm text-[#58655D] dark:text-[#98A89E]">
              {translate(locale, 'noMatchingTransactions')}
            </p>
            {hasActiveFilters && (
              <button
                type="button"
                onClick={resetAllFilters}
                className="min-h-[40px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium cursor-pointer"
              >
                {translate(locale, 'resetFilters')}
              </button>
            )}
          </div>
        ) : (
          <div className="divide-y divide-[#E2E8E3] dark:divide-[#28362D]">
            {visibleRows.map((tx) => {
              const isVoided = tx.status === 'voided';
              const isInflow = ['income', 'credit_sale', 'receivable_payment', 'refund_expense', 'opening_balance'].includes(tx.type);
              const isOutflow = ['expense', 'credit_purchase', 'payable_payment', 'refund_income'].includes(tx.type);
              const accName =
                tx.type === 'transfer'
                  ? `${accMap.get(tx.accountId)?.name || 'Source'} → ${accMap.get(tx.toAccountId)?.name || 'Dest'}`
                  : accMap.get(tx.accountId)?.name || 'Subledger';
              const catName = catMap.get(tx.categoryId)?.name || tx.categoryLabelSnapshot || getTransactionTypeLabel(locale, tx.type);
              const partyName = partyMap.get(tx.partyId)?.name || tx.partyNameSnapshot;

              return (
                <button
                  key={tx.id}
                  type="button"
                  onClick={() => onSelectTransaction(tx)}
                  className="w-full min-h-[64px] px-6 py-3.5 flex items-center justify-between gap-4 text-left hover:bg-[#F6F8F5] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
                >
                  <div className="min-w-0">
                    <p
                      className={`text-sm font-medium truncate ${
                        isVoided
                          ? 'line-through text-[#58655D] dark:text-[#98A89E]'
                          : 'text-[#17231D] dark:text-[#ECF3EE]'
                      }`}
                    >
                      {tx.description}
                    </p>
                    <p className="mt-0.5 text-xs text-[#58655D] dark:text-[#98A89E] truncate">
                      <span className="font-mono-num">{formatLocaleDate(tx.date, locale)}</span>
                      <span className="mx-1.5" aria-hidden="true">·</span>
                      <span>{catName}</span>
                      <span className="mx-1.5" aria-hidden="true">·</span>
                      <span>{accName}</span>
                      {partyName && (
                        <>
                          <span className="mx-1.5" aria-hidden="true">·</span>
                          <span>{partyName}</span>
                        </>
                      )}
                      {tx.referenceNumber && (
                        <>
                          <span className="mx-1.5" aria-hidden="true">·</span>
                          <span className="font-mono-num">Ref: {tx.referenceNumber}</span>
                        </>
                      )}
                      {isVoided && (
                        <>
                          <span className="mx-1.5" aria-hidden="true">·</span>
                          <span className="text-[#D94848] dark:text-[#F87171]">Voided</span>
                        </>
                      )}
                    </p>
                  </div>

                  <div className="text-right shrink-0">
                    <p
                      className={`text-sm font-semibold font-mono-num ${
                        isVoided
                          ? 'line-through text-[#58655D]'
                          : isInflow
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

        {filtered.length > visibleLimit && (
          <div className="p-4 border-t border-[#E2E8E3] dark:border-[#28362D] text-center">
            <button
              type="button"
              onClick={() => setVisibleLimit((prev) => prev + PAGE_STEP)}
              className="min-h-[40px] px-5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] cursor-pointer"
            >
              Load More ({filtered.length - visibleLimit} remaining)
            </button>
          </div>
        )}
      </section>
    </div>
  );
};
