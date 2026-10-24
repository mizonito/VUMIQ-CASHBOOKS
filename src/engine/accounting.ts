import {
  Account,
  AuditEvent,
  BackupArchive,
  Business,
  BusinessMember,
  Category,
  CurrencyCode,
  CurrencyPeriodSummary,
  DateFilterPreset,
  DateRange,
  MemberRole,
  Party,
  PartyComputedBalance,
  SUPPORTED_CURRENCIES,
  Transaction,
  TransactionFilterQuery,
  TransactionType,
} from '../types/cashbook';
import { formatMinorAmount } from './money';

/**
 * RBAC Permission Matrix enforced consistently across server rules and engine mutations.
 */
export const ROLE_PERMISSIONS: Record<
  MemberRole,
  {
    canRead: boolean;
    canRecordTransactions: boolean;
    canEditTransactions: boolean;
    canVoidTransactions: boolean;
    canDeleteTransactions: boolean;
    canManageAccounts: boolean;
    canManageParties: boolean;
    canExportData: boolean;
    canManageMembers: boolean;
  }
> = {
  owner: {
    canRead: true,
    canRecordTransactions: true,
    canEditTransactions: true,
    canVoidTransactions: true,
    canDeleteTransactions: true,
    canManageAccounts: true,
    canManageParties: true,
    canExportData: true,
    canManageMembers: true,
  },
  admin: {
    canRead: true,
    canRecordTransactions: true,
    canEditTransactions: true,
    canVoidTransactions: true,
    canDeleteTransactions: true,
    canManageAccounts: true,
    canManageParties: true,
    canExportData: true,
    canManageMembers: true,
  },
  accountant: {
    canRead: true,
    canRecordTransactions: true,
    canEditTransactions: true,
    canVoidTransactions: true,
    canDeleteTransactions: false,
    canManageAccounts: false,
    canManageParties: true,
    canExportData: true,
    canManageMembers: false,
  },
  manager: {
    canRead: true,
    canRecordTransactions: true,
    canEditTransactions: true,
    canVoidTransactions: true,
    canDeleteTransactions: false,
    canManageAccounts: false,
    canManageParties: true,
    canExportData: true,
    canManageMembers: false,
  },
  viewer: {
    canRead: true,
    canRecordTransactions: false,
    canEditTransactions: false,
    canVoidTransactions: false,
    canDeleteTransactions: false,
    canManageAccounts: false,
    canManageParties: false,
    canExportData: false,
    canManageMembers: false,
  },
};

export function createDefaultCategories(businessId: string, ownerId: string, nowIso: string): Category[] {
  const defaults: Array<{ idSuffix: string; name: string; kind: 'income' | 'expense'; color: string; icon: string }> = [
    { idSuffix: 'sales', name: 'Sales', kind: 'income', color: '#168653', icon: 'shopping-bag' },
    { idSuffix: 'services', name: 'Services', kind: 'income', color: '#20A56B', icon: 'briefcase' },
    { idSuffix: 'biz_income', name: 'Business income', kind: 'income', color: '#0E7490', icon: 'trending-up' },
    { idSuffix: 'other_income', name: 'Other income', kind: 'income', color: '#4F46E5', icon: 'plus-circle' },
    { idSuffix: 'stock', name: 'Stock purchases', kind: 'expense', color: '#D94848', icon: 'package' },
    { idSuffix: 'rent', name: 'Rent', kind: 'expense', color: '#B45309', icon: 'building' },
    { idSuffix: 'salaries', name: 'Salaries', kind: 'expense', color: '#9333EA', icon: 'users' },
    { idSuffix: 'transport', name: 'Transport', kind: 'expense', color: '#C88719', icon: 'truck' },
    { idSuffix: 'utilities', name: 'Utilities', kind: 'expense', color: '#0284C7', icon: 'zap' },
    { idSuffix: 'supplies', name: 'Supplies', kind: 'expense', color: '#BE123C', icon: 'clipboard' },
    { idSuffix: 'maintenance', name: 'Maintenance', kind: 'expense', color: '#6D28D9', icon: 'wrench' },
    { idSuffix: 'other_expense', name: 'Other expenses', kind: 'expense', color: '#64748B', icon: 'minus-circle' },
  ];

  return defaults.map((item) => ({
    id: `cat_${businessId}_${item.idSuffix}`.slice(0, 120),
    businessId,
    ownerId,
    name: item.name,
    kind: item.kind,
    color: item.color,
    icon: item.icon,
    archived: false,
    createdAt: nowIso,
    updatedAt: nowIso,
  }));
}

/**
 * Resolves standard date presets against a reference local YYYY-MM-DD date.
 */
export function resolveDateRangePreset(preset: DateFilterPreset, referenceDateIso: string, customRange?: DateRange): DateRange {
  if (preset === 'all') {
    return { startDate: '', endDate: '' };
  }
  if (preset === 'custom') {
    return {
      startDate: customRange?.startDate || '',
      endDate: customRange?.endDate || '',
    };
  }

  const [yStr, mStr, dStr] = referenceDateIso.split('-');
  const year = Number(yStr);
  const month = Number(mStr); // 1..12
  const day = Number(dStr);

  if (preset === 'today') {
    return { startDate: referenceDateIso, endDate: referenceDateIso };
  }

  if (preset === 'week') {
    // Monday-start week in UTC calendar space so timezones never shift date boundaries
    const refUtc = new Date(Date.UTC(year, month - 1, day));
    const dow = refUtc.getUTCDay(); // 0=Sun, 1=Mon...
    const diffToMon = dow === 0 ? -6 : 1 - dow;
    const startUtc = new Date(Date.UTC(year, month - 1, day + diffToMon));
    const endUtc = new Date(Date.UTC(startUtc.getUTCFullYear(), startUtc.getUTCMonth(), startUtc.getUTCDate() + 6));
    return {
      startDate: startUtc.toISOString().slice(0, 10),
      endDate: endUtc.toISOString().slice(0, 10),
    };
  }

  if (preset === 'month') {
    const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
    const mm = String(month).padStart(2, '0');
    return {
      startDate: `${year}-${mm}-01`,
      endDate: `${year}-${mm}-${String(lastDay).padStart(2, '0')}`,
    };
  }

  // 'year'
  return {
    startDate: `${year}-01-01`,
    endDate: `${year}-12-31`,
  };
}

export function isDateWithinRange(dateStr: string, range?: DateRange): boolean {
  if (!range) return true;
  if (range.startDate && dateStr < range.startDate) return false;
  if (range.endDate && dateStr > range.endDate) return false;
  return true;
}

/**
 * Calculates exact current balance for every financial account using integer minor units:
 * Current Balance = Account.openingBalanceMinor + Posted Inflows - Posted Outflows
 */
export function computeAccountBalances(
  accounts: Account[],
  transactions: Transaction[],
  upToDateInclusive?: string
): Record<string, number> {
  const balances: Record<string, number> = {};
  for (const acc of accounts) {
    balances[acc.id] = acc.openingBalanceMinor;
  }

  for (const tx of transactions) {
    if (tx.status !== 'posted') continue;
    if (upToDateInclusive && tx.date > upToDateInclusive) continue;

    switch (tx.type) {
      case 'income':
      case 'opening_balance':
      case 'refund_expense':
      case 'receivable_payment': {
        if (tx.accountId && tx.accountId in balances) {
          balances[tx.accountId] += tx.amountMinor;
        }
        break;
      }
      case 'expense':
      case 'refund_income':
      case 'payable_payment': {
        if (tx.accountId && tx.accountId in balances) {
          balances[tx.accountId] -= tx.amountMinor;
        }
        break;
      }
      case 'transfer': {
        if (tx.accountId && tx.accountId in balances) {
          balances[tx.accountId] -= tx.amountMinor + (tx.feeMinor || 0);
        }
        if (tx.toAccountId && tx.toAccountId in balances) {
          const creditAmt =
            tx.toCurrency && tx.toCurrency !== tx.currency && tx.convertedAmountMinor > 0
              ? tx.convertedAmountMinor
              : tx.amountMinor;
          balances[tx.toAccountId] += creditAmt;
        }
        break;
      }
      case 'credit_sale':
      case 'credit_purchase': {
        // Non-cash invoice/credit events affect party subledgers & income/expense recognition, not cash accounts
        break;
      }
    }
  }

  return balances;
}

/**
 * Calculates exact outstanding receivables and payables per party and per currency.
 * - Credit Sale increases Receivable; Receivable Payment reduces Receivable without double-counting Income.
 * - Credit Purchase increases Payable; Payable Payment reduces Payable without double-counting Expense.
 */
export function computePartyBalances(parties: Party[], transactions: Transaction[]): Record<string, PartyComputedBalance> {
  const map: Record<string, PartyComputedBalance> = {};

  for (const party of parties) {
    const rec: Partial<Record<CurrencyCode, number>> = {};
    const pay: Partial<Record<CurrencyCode, number>> = {};
    if (party.openingReceivableMinor > 0) {
      rec[party.currency] = party.openingReceivableMinor;
    }
    if (party.openingPayableMinor > 0) {
      pay[party.currency] = party.openingPayableMinor;
    }
    map[party.id] = {
      party,
      receivableByCurrency: rec,
      payableByCurrency: pay,
      transactionCount: 0,
    };
  }

  for (const tx of transactions) {
    if (tx.status !== 'posted' || !tx.partyId || !map[tx.partyId]) continue;
    const entry = map[tx.partyId];
    entry.transactionCount += 1;
    const cur = tx.currency;

    if (tx.type === 'credit_sale') {
      entry.receivableByCurrency[cur] = (entry.receivableByCurrency[cur] || 0) + tx.amountMinor;
    } else if (tx.type === 'receivable_payment') {
      const next = (entry.receivableByCurrency[cur] || 0) - tx.amountMinor;
      entry.receivableByCurrency[cur] = Math.max(0, next);
    } else if (tx.type === 'credit_purchase') {
      entry.payableByCurrency[cur] = (entry.payableByCurrency[cur] || 0) + tx.amountMinor;
    } else if (tx.type === 'payable_payment') {
      const next = (entry.payableByCurrency[cur] || 0) - tx.amountMinor;
      entry.payableByCurrency[cur] = Math.max(0, next);
    }
  }

  return map;
}

/**
 * Computes financial summaries strictly isolated by currency (NEVER adds USD + CDF together).
 * Distinguishes:
 * - Current Cash Balance (all-time posted balance of active accounts in that currency)
 * - Selected Period Income & Expenses (excluding transfers, opening balances, and debt settlements!)
 * - Selected Period Net Cash Movement (actual cash inflows minus outflows in the selected period, excluding internal same-currency transfers)
 * - Total Receivables (Money to Receive) & Total Payables (Money to Pay)
 */
export function computePeriodSummariesByCurrency(
  accounts: Account[],
  parties: Party[],
  transactions: Transaction[],
  dateRange?: DateRange,
  fallbackCurrency?: CurrencyCode
): Record<CurrencyCode, CurrencyPeriodSummary> {
  const accountBalances = computeAccountBalances(accounts, transactions);
  const partyBalances = computePartyBalances(parties, transactions);

  const activeCurrencies = new Set<CurrencyCode>();
  if (fallbackCurrency) activeCurrencies.add(fallbackCurrency);
  for (const acc of accounts) {
    if (acc.status === 'active') activeCurrencies.add(acc.currency);
  }
  for (const party of parties) {
    if (party.openingReceivableMinor > 0 || party.openingPayableMinor > 0) {
      activeCurrencies.add(party.currency);
    }
  }
  for (const tx of transactions) {
    if (tx.status === 'posted') {
      activeCurrencies.add(tx.currency);
      if (tx.toCurrency && (SUPPORTED_CURRENCIES as readonly string[]).includes(tx.toCurrency)) {
        activeCurrencies.add(tx.toCurrency as CurrencyCode);
      }
    }
  }

  const summaries = {} as Record<CurrencyCode, CurrencyPeriodSummary>;

  for (const cur of activeCurrencies) {
    let currentCashBalanceMinor = 0;
    let accountCount = 0;
    for (const acc of accounts) {
      if (acc.currency === cur && acc.status === 'active') {
        currentCashBalanceMinor += accountBalances[acc.id] || 0;
        accountCount += 1;
      }
    }

    let totalReceivablesMinor = 0;
    let totalPayablesMinor = 0;
    for (const pb of Object.values(partyBalances)) {
      if (pb.party.status === 'active') {
        totalReceivablesMinor += pb.receivableByCurrency[cur] || 0;
        totalPayablesMinor += pb.payableByCurrency[cur] || 0;
      }
    }

    let periodIncomeMinor = 0;
    let periodExpenseMinor = 0;
    let periodCashInflowMinor = 0;
    let periodCashOutflowMinor = 0;

    for (const tx of transactions) {
      if (tx.status !== 'posted') continue;
      if (!isDateWithinRange(tx.date, dateRange)) continue;

      if (tx.currency === cur) {
        switch (tx.type) {
          case 'income':
            periodIncomeMinor += tx.amountMinor;
            periodCashInflowMinor += tx.amountMinor;
            break;
          case 'expense':
            periodExpenseMinor += tx.amountMinor;
            periodCashOutflowMinor += tx.amountMinor;
            break;
          case 'refund_income':
            // Reverses prior income without inflating expenses
            periodIncomeMinor -= tx.amountMinor;
            periodCashOutflowMinor += tx.amountMinor;
            break;
          case 'refund_expense':
            // Reverses prior expense without inflating income
            periodExpenseMinor -= tx.amountMinor;
            periodCashInflowMinor += tx.amountMinor;
            break;
          case 'credit_sale':
            // Recognizes earned income once; cash arrives later via receivable_payment
            periodIncomeMinor += tx.amountMinor;
            break;
          case 'receivable_payment':
            // Collects cash against receivable; NEVER counts sale as income a second time
            periodCashInflowMinor += tx.amountMinor;
            break;
          case 'credit_purchase':
            // Recognizes expense once; cash leaves later via payable_payment
            periodExpenseMinor += tx.amountMinor;
            break;
          case 'payable_payment':
            // Pays cash against payable; NEVER counts purchase as expense a second time
            periodCashOutflowMinor += tx.amountMinor;
            break;
          case 'opening_balance':
            // Opening balance is capital baseline, NOT newly earned income or period cash inflow
            break;
          case 'transfer':
            // Same-currency transfer between business accounts has ZERO net cash movement and ZERO income/expense,
            // apart from any explicitly recorded feeMinor.
            if (tx.feeMinor > 0) {
              periodExpenseMinor += tx.feeMinor;
              periodCashOutflowMinor += tx.feeMinor;
            }
            if (tx.toCurrency && tx.toCurrency !== cur) {
              // Cross-currency conversion outflow in source currency
              periodCashOutflowMinor += tx.amountMinor;
            }
            break;
        }
      } else if (tx.type === 'transfer' && tx.toCurrency === cur && tx.convertedAmountMinor > 0) {
        // Cross-currency conversion inflow in destination currency
        periodCashInflowMinor += tx.convertedAmountMinor;
      }
    }

    summaries[cur] = {
      currency: cur,
      currentCashBalanceMinor,
      periodIncomeMinor,
      periodExpenseMinor,
      netIncomeMinor: periodIncomeMinor - periodExpenseMinor,
      periodCashInflowMinor,
      periodCashOutflowMinor,
      netCashMovementMinor: periodCashInflowMinor - periodCashOutflowMinor,
      totalReceivablesMinor,
      totalPayablesMinor,
      accountCount,
    };
  }

  return summaries;
}

export interface TimeSeriesPoint {
  bucketKey: string; // YYYY-MM-DD or YYYY-MM
  label: string;
  incomeMinor: number;
  expenseMinor: number;
  netCashMinor: number;
}

/**
 * Generates real time-series chart points from posted records in a single currency.
 */
export function computeTimeSeriesData(
  transactions: Transaction[],
  currency: CurrencyCode,
  dateRange?: DateRange,
  granularity: 'day' | 'month' = 'day'
): TimeSeriesPoint[] {
  const buckets = new Map<string, TimeSeriesPoint>();

  for (const tx of transactions) {
    if (tx.status !== 'posted' || tx.currency !== currency) continue;
    if (!isDateWithinRange(tx.date, dateRange)) continue;

    const key = granularity === 'month' ? tx.date.slice(0, 7) : tx.date;
    if (!buckets.has(key)) {
      buckets.set(key, {
        bucketKey: key,
        label: key,
        incomeMinor: 0,
        expenseMinor: 0,
        netCashMinor: 0,
      });
    }
    const b = buckets.get(key)!;

    if (tx.type === 'income') {
      b.incomeMinor += tx.amountMinor;
      b.netCashMinor += tx.amountMinor;
    } else if (tx.type === 'credit_sale') {
      b.incomeMinor += tx.amountMinor;
    } else if (tx.type === 'refund_income') {
      b.incomeMinor -= tx.amountMinor;
      b.netCashMinor -= tx.amountMinor;
    } else if (tx.type === 'expense') {
      b.expenseMinor += tx.amountMinor;
      b.netCashMinor -= tx.amountMinor;
    } else if (tx.type === 'credit_purchase') {
      b.expenseMinor += tx.amountMinor;
    } else if (tx.type === 'refund_expense') {
      b.expenseMinor -= tx.amountMinor;
      b.netCashMinor += tx.amountMinor;
    } else if (tx.type === 'receivable_payment') {
      b.netCashMinor += tx.amountMinor;
    } else if (tx.type === 'payable_payment') {
      b.netCashMinor -= tx.amountMinor;
    } else if (tx.type === 'transfer' && tx.feeMinor > 0) {
      b.expenseMinor += tx.feeMinor;
      b.netCashMinor -= tx.feeMinor;
    }
  }

  return Array.from(buckets.values()).sort((a, b) => a.bucketKey.localeCompare(b.bucketKey));
}

export interface CategoryBreakdownItem {
  categoryId: string;
  categoryName: string;
  color: string;
  amountMinor: number;
  percentage: number;
  count: number;
}

export function computeCategoryBreakdown(
  categories: Category[],
  transactions: Transaction[],
  currency: CurrencyCode,
  kind: 'income' | 'expense',
  dateRange?: DateRange
): CategoryBreakdownItem[] {
  const catMap = new Map(categories.map((c) => [c.id, c]));
  const totals = new Map<string, { name: string; color: string; amountMinor: number; count: number }>();

  for (const tx of transactions) {
    if (tx.status !== 'posted' || tx.currency !== currency) continue;
    if (!isDateWithinRange(tx.date, dateRange)) continue;

    let signedContribution = 0;
    if (kind === 'income') {
      if (tx.type === 'income' || tx.type === 'credit_sale') signedContribution = tx.amountMinor;
      else if (tx.type === 'refund_income') signedContribution = -tx.amountMinor;
    } else {
      if (tx.type === 'expense' || tx.type === 'credit_purchase') signedContribution = tx.amountMinor;
      else if (tx.type === 'refund_expense') signedContribution = -tx.amountMinor;
    }

    if (signedContribution === 0) continue;

    const cat = tx.categoryId ? catMap.get(tx.categoryId) : undefined;
    const key = tx.categoryId || '__uncategorized__';
    // Preserve historical category label even if category was renamed or archived
    const label = cat?.name || tx.categoryLabelSnapshot || 'Uncategorized';
    const color = cat?.color || (kind === 'income' ? '#168653' : '#D94848');

    const prev = totals.get(key) || { name: label, color, amountMinor: 0, count: 0 };
    prev.amountMinor += signedContribution;
    prev.count += 1;
    totals.set(key, prev);
  }

  const positiveItems = Array.from(totals.entries())
    .filter(([, val]) => val.amountMinor > 0)
    .sort((a, b) => b[1].amountMinor - a[1].amountMinor);

  const grandTotal = positiveItems.reduce((sum, [, item]) => sum + item.amountMinor, 0);

  return positiveItems.map(([categoryId, item]) => ({
    categoryId,
    categoryName: item.name,
    color: item.color,
    amountMinor: item.amountMinor,
    percentage: grandTotal > 0 ? Math.round((item.amountMinor * 1000) / grandTotal) / 10 : 0,
    count: item.count,
  }));
}

export interface BalanceHistoryPoint {
  date: string;
  balanceMinor: number;
}

export function computeCashBalanceHistory(
  accounts: Account[],
  transactions: Transaction[],
  currency: CurrencyCode,
  dateRange?: DateRange
): BalanceHistoryPoint[] {
  const relevantAccounts = accounts.filter((a) => a.currency === currency && a.status === 'active');
  if (relevantAccounts.length === 0) return [];

  const dates = new Set<string>();
  for (const tx of transactions) {
    if (tx.status !== 'posted') continue;
    if (tx.currency === currency || tx.toCurrency === currency) {
      if (isDateWithinRange(tx.date, dateRange)) {
        dates.add(tx.date);
      }
    }
  }

  const sortedDates = Array.from(dates).sort();
  return sortedDates.map((d) => {
    const accBalances = computeAccountBalances(relevantAccounts, transactions, d);
    const total = relevantAccounts.reduce((s, a) => s + (accBalances[a.id] || 0), 0);
    return { date: d, balanceMinor: total };
  });
}

/**
 * Normalizes whitespace and case for fast, reliable search without duplicate results.
 */
function normalizeSearchToken(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, ' ');
}

export function filterAndSearchTransactions(
  transactions: Transaction[],
  accounts: Account[],
  categories: Category[],
  parties: Party[],
  query: TransactionFilterQuery
): Transaction[] {
  const accMap = new Map(accounts.map((a) => [a.id, a]));
  const catMap = new Map(categories.map((c) => [c.id, c]));
  const partyMap = new Map(parties.map((p) => [p.id, p]));

  const searchNorm = query.search ? normalizeSearchToken(query.search) : '';
  const seenIds = new Set<string>();
  const results: Transaction[] = [];

  for (const tx of transactions) {
    if (seenIds.has(tx.id)) continue;
    if (!query.includeVoided && tx.status === 'voided') continue;
    if (!isDateWithinRange(tx.date, query.dateRange)) continue;

    if (query.currency && query.currency !== 'all') {
      if (tx.currency !== query.currency && tx.toCurrency !== query.currency) continue;
    }

    if (query.typeFilter && query.typeFilter !== 'all') {
      if (query.typeFilter === 'income_group') {
        if (!['income', 'credit_sale', 'receivable_payment', 'refund_expense'].includes(tx.type)) continue;
      } else if (query.typeFilter === 'expense_group') {
        if (!['expense', 'credit_purchase', 'payable_payment', 'refund_income'].includes(tx.type)) continue;
      } else if (tx.type !== query.typeFilter) {
        continue;
      }
    }

    if (query.categoryId && tx.categoryId !== query.categoryId) continue;
    if (query.accountId && tx.accountId !== query.accountId && tx.toAccountId !== query.accountId) continue;
    if (query.partyId && tx.partyId !== query.partyId) continue;

    if (query.minAmountMinor != null && tx.amountMinor < query.minAmountMinor) continue;
    if (query.maxAmountMinor != null && tx.amountMinor > query.maxAmountMinor) continue;

    if (searchNorm) {
      const accName = accMap.get(tx.accountId)?.name || '';
      const toAccName = tx.toAccountId ? accMap.get(tx.toAccountId)?.name || '' : '';
      const catName = catMap.get(tx.categoryId)?.name || tx.categoryLabelSnapshot || '';
      const partyName = partyMap.get(tx.partyId)?.name || tx.partyNameSnapshot || '';
      const haystack = normalizeSearchToken(
        [tx.description, tx.referenceNumber, catName, partyName, accName, toAccName, tx.notes].join(' ')
      );
      if (!haystack.includes(searchNorm)) continue;
    }

    seenIds.add(tx.id);
    results.push(tx);
  }

  const sortMode = query.sortBy || 'date_desc';
  results.sort((a, b) => {
    if (sortMode === 'amount_desc') return b.amountMinor - a.amountMinor || b.date.localeCompare(a.date);
    if (sortMode === 'amount_asc') return a.amountMinor - b.amountMinor || a.date.localeCompare(b.date);
    const keyA = `${a.date}T${a.time}Z_${a.createdAt}_${a.id}`;
    const keyB = `${b.date}T${b.time}Z_${b.createdAt}_${b.id}`;
    return sortMode === 'date_asc' ? keyA.localeCompare(keyB) : keyB.localeCompare(keyA);
  });

  return results;
}

/**
 * Validates transaction business rules before committing to storage.
 */
export function validateTransactionDraft(
  draft: {
    type: TransactionType;
    amountMinor: number;
    currency: CurrencyCode;
    description: string;
    date: string;
    time: string;
    accountId: string;
    toAccountId: string;
    partyId: string;
    convertedAmountMinor?: number;
    toCurrency?: string;
    exchangeRateScaled?: number;
    feeMinor?: number;
  },
  accounts: Account[],
  parties: Party[]
): { valid: true } | { valid: false; message: string } {
  if (!Number.isSafeInteger(draft.amountMinor) || draft.amountMinor <= 0) {
    return { valid: false, message: 'Transaction amount must be greater than zero.' };
  }
  if (!draft.description.trim() || draft.description.trim().length > 200) {
    return { valid: false, message: 'Description is required (1–200 characters).' };
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.date)) {
    return { valid: false, message: 'Valid YYYY-MM-DD date is required.' };
  }

  const requiresCashAccount = !['credit_sale', 'credit_purchase'].includes(draft.type);
  if (requiresCashAccount) {
    const srcAcc = accounts.find((a) => a.id === draft.accountId && a.status === 'active');
    if (!srcAcc) {
      return { valid: false, message: 'Select an active payment account.' };
    }
    if (srcAcc.currency !== draft.currency) {
      return { valid: false, message: `Transaction currency (${draft.currency}) must match source account currency (${srcAcc.currency}).` };
    }
  }

  if (draft.type === 'transfer') {
    if (!draft.toAccountId || draft.toAccountId === draft.accountId) {
      return { valid: false, message: 'Transfers require two distinct accounts.' };
    }
    const dstAcc = accounts.find((a) => a.id === draft.toAccountId && a.status === 'active');
    if (!dstAcc) {
      return { valid: false, message: 'Select an active destination account.' };
    }
    if (dstAcc.currency !== draft.currency) {
      if (!draft.convertedAmountMinor || draft.convertedAmountMinor <= 0 || !draft.exchangeRateScaled || draft.exchangeRateScaled <= 0) {
        return { valid: false, message: 'Cross-currency transfers require an explicit positive exchange rate.' };
      }
    }
  }

  if (['credit_sale', 'receivable_payment', 'credit_purchase', 'payable_payment'].includes(draft.type)) {
    const party = parties.find((p) => p.id === draft.partyId && p.status === 'active');
    if (!party) {
      return { valid: false, message: 'Select a customer or supplier party for credit or settlement workflows.' };
    }
  }

  return { valid: true };
}

/**
 * Computes deterministic backup checksum to verify archive integrity before restore.
 */
export function computeBackupChecksum(data: {
  businessId: string;
  accountCount: number;
  transactionCount: number;
  totalPostedMinorSum: number;
}): string {
  const raw = `${data.businessId}|acc:${data.accountCount}|tx:${data.transactionCount}|sum:${data.totalPostedMinorSum}`;
  let hash = 2166136261;
  for (let i = 0; i < raw.length; i++) {
    hash ^= raw.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return `vumiq_v1_${(hash >>> 0).toString(16).padStart(8, '0')}`;
}

export function validateBackupArchive(payload: unknown): { ok: true; archive: BackupArchive } | { ok: false; message: string } {
  if (!payload || typeof payload !== 'object') {
    return { ok: false, message: 'Backup file is not a valid JSON object.' };
  }
  const obj = payload as Partial<BackupArchive>;
  if (obj.application !== 'VUMIQ Cashbook' || obj.schemaVersion !== 1) {
    return { ok: false, message: 'Unsupported backup archive format or version.' };
  }
  if (!obj.business || !Array.isArray(obj.accounts) || !Array.isArray(obj.categories) || !Array.isArray(obj.parties) || !Array.isArray(obj.transactions)) {
    return { ok: false, message: 'Backup archive is missing required financial tables.' };
  }
  const totalPostedMinorSum = obj.transactions
    .filter((t) => t.status === 'posted')
    .reduce((sum, t) => sum + (Number.isSafeInteger(t.amountMinor) ? t.amountMinor : 0), 0);

  const expectedChecksum = computeBackupChecksum({
    businessId: obj.business.id,
    accountCount: obj.accounts.length,
    transactionCount: obj.transactions.length,
    totalPostedMinorSum,
  });

  if (obj.checksum && obj.checksum !== expectedChecksum) {
    return { ok: false, message: 'Backup checksum mismatch — archive may be corrupted or modified.' };
  }

  return { ok: true, archive: obj as BackupArchive };
}

/**
 * Pure, Deterministic Multi-Tenant Ledger Engine with Serialized Atomic Queue,
 * Idempotency Protection, RBAC Authorization, and Full Audit Trail.
 * Used both by Guest/Local storage and automated Accounting Validation Tests (Cases A–J).
 */
export function createLedgerEngine() {
  const businesses = new Map<string, Business>();
  const memberships = new Map<string, BusinessMember[]>(); // businessId -> members
  const accounts = new Map<string, Account[]>();
  const categories = new Map<string, Category[]>();
  const parties = new Map<string, Party[]>();
  const transactions = new Map<string, Transaction[]>();
  const auditEvents = new Map<string, AuditEvent[]>();
  const idempotencyIndex = new Map<string, Transaction>(); // `${businessId}:${idempotencyKey}` -> Transaction

  let mutexChain: Promise<unknown> = Promise.resolve();
  let seq = 1;

  function nextId(prefix: string): string {
    seq += 1;
    return `${prefix}_${Date.now()}_${seq}`;
  }

  function runAtomic<T>(fn: () => T): Promise<T> {
    const next = mutexChain.then(() => fn());
    mutexChain = next.then(
      () => undefined,
      () => undefined
    );
    return next;
  }

  function resolveActorRole(actorUid: string, businessId: string): MemberRole | null {
    const biz = businesses.get(businessId);
    if (!biz) return null;
    if (biz.ownerId === actorUid) return 'owner';
    const mem = (memberships.get(businessId) || []).find((m) => m.memberUid === actorUid);
    return mem ? mem.role : null;
  }

  function assertPermission(
    actorUid: string,
    businessId: string,
    perm: keyof (typeof ROLE_PERMISSIONS)['owner']
  ): Business {
    const biz = businesses.get(businessId);
    if (!biz) throw new Error('NOT_FOUND: Business does not exist.');
    const role = resolveActorRole(actorUid, businessId);
    if (!role || !ROLE_PERMISSIONS[role][perm]) {
      throw new Error('FORBIDDEN: Unauthorized access to business financial records.');
    }
    return biz;
  }

  return {
    async createBusiness(params: {
      actorUid: string;
      actorName: string;
      name: string;
      defaultCurrency: CurrencyCode;
      address?: string;
      phone?: string;
    }): Promise<Business> {
      return runAtomic(() => {
        const now = new Date().toISOString();
        const id = nextId('biz');
        const biz: Business = {
          id,
          ownerId: params.actorUid,
          name: params.name.trim(),
          defaultCurrency: params.defaultCurrency,
          address: params.address?.trim() || '',
          phone: params.phone?.trim() || '',
          fiscalStartMonth: 1,
          createdAt: now,
          updatedAt: now,
        };
        businesses.set(id, biz);
        memberships.set(id, [
          {
            memberUid: params.actorUid,
            businessId: id,
            ownerId: params.actorUid,
            role: 'owner',
            displayName: params.actorName,
            createdAt: now,
            updatedAt: now,
          },
        ]);
        accounts.set(id, []);
        categories.set(id, createDefaultCategories(id, params.actorUid, now));
        parties.set(id, []);
        transactions.set(id, []);
        auditEvents.set(id, [
          {
            id: nextId('evt'),
            businessId: id,
            ownerId: params.actorUid,
            entityType: 'business',
            entityId: id,
            action: 'create',
            actorUid: params.actorUid,
            actorName: params.actorName,
            summary: `Created business "${biz.name}" (${biz.defaultCurrency})`,
            previousSummary: '',
            newSummary: `${biz.name} (${biz.defaultCurrency})`,
            reason: '',
            createdAt: now,
          },
        ]);
        return biz;
      });
    },

    async addMember(params: {
      actorUid: string;
      businessId: string;
      memberUid: string;
      displayName: string;
      role: Exclude<MemberRole, 'owner'>;
    }): Promise<BusinessMember> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canManageMembers');
        const now = new Date().toISOString();
        const member: BusinessMember = {
          memberUid: params.memberUid,
          businessId: params.businessId,
          ownerId: biz.ownerId,
          role: params.role,
          displayName: params.displayName,
          createdAt: now,
          updatedAt: now,
        };
        const list = memberships.get(params.businessId) || [];
        const filtered = list.filter((m) => m.memberUid !== params.memberUid);
        filtered.push(member);
        memberships.set(params.businessId, filtered);
        return member;
      });
    },

    async createAccount(params: {
      actorUid: string;
      actorName: string;
      businessId: string;
      name: string;
      accountType: Account['accountType'];
      currency: CurrencyCode;
      openingBalanceMinor: number;
    }): Promise<Account> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canManageAccounts');
        if (!Number.isSafeInteger(params.openingBalanceMinor) || params.openingBalanceMinor < 0) {
          throw new Error('VALIDATION: Opening balance must be a non-negative integer minor amount.');
        }
        const now = new Date().toISOString();
        const acc: Account = {
          id: nextId('acc'),
          businessId: params.businessId,
          ownerId: biz.ownerId,
          name: params.name.trim(),
          accountType: params.accountType,
          currency: params.currency,
          openingBalanceMinor: params.openingBalanceMinor,
          status: 'active',
          createdAt: now,
          updatedAt: now,
        };
        accounts.get(params.businessId)!.push(acc);
        auditEvents.get(params.businessId)!.push({
          id: nextId('evt'),
          businessId: params.businessId,
          ownerId: biz.ownerId,
          entityType: 'account',
          entityId: acc.id,
          action: 'create',
          actorUid: params.actorUid,
          actorName: params.actorName,
          summary: `Created account "${acc.name}"`,
          previousSummary: '',
          newSummary: `${acc.name} (${formatMinorAmount(acc.openingBalanceMinor, acc.currency)})`,
          reason: '',
          createdAt: now,
        });
        return acc;
      });
    },

    async createParty(params: {
      actorUid: string;
      actorName: string;
      businessId: string;
      name: string;
      partyType: Party['partyType'];
      currency: CurrencyCode;
      phone?: string;
      address?: string;
      notes?: string;
      openingReceivableMinor?: number;
      openingPayableMinor?: number;
      dueDate?: string;
    }): Promise<Party> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canManageParties');
        const now = new Date().toISOString();
        const party: Party = {
          id: nextId('pty'),
          businessId: params.businessId,
          ownerId: biz.ownerId,
          name: params.name.trim(),
          partyType: params.partyType,
          phone: params.phone?.trim() || '',
          address: params.address?.trim() || '',
          notes: params.notes?.trim() || '',
          currency: params.currency,
          openingReceivableMinor: params.openingReceivableMinor || 0,
          openingPayableMinor: params.openingPayableMinor || 0,
          dueDate: params.dueDate || '',
          status: 'active',
          createdAt: now,
          updatedAt: now,
        };
        parties.get(params.businessId)!.push(party);
        return party;
      });
    },

    async recordTransaction(params: {
      actorUid: string;
      actorName: string;
      businessId: string;
      idempotencyKey: string;
      type: TransactionType;
      amountMinor: number;
      currency: CurrencyCode;
      date: string;
      time?: string;
      description: string;
      accountId?: string;
      toAccountId?: string;
      categoryId?: string;
      partyId?: string;
      convertedAmountMinor?: number;
      toCurrency?: string;
      exchangeRateScaled?: number;
      feeMinor?: number;
      referenceNumber?: string;
      notes?: string;
      dueDate?: string;
    }): Promise<{ transaction: Transaction; deduplicated: boolean }> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canRecordTransactions');
        const idemFullKey = `${params.businessId}:${params.idempotencyKey}`;
        const existingTx = idempotencyIndex.get(idemFullKey);
        if (existingTx) {
          return { transaction: existingTx, deduplicated: true };
        }

        const bizAccounts = accounts.get(params.businessId)!;
        const bizParties = parties.get(params.businessId)!;
        const bizCategories = categories.get(params.businessId)!;

        const draft = {
          type: params.type,
          amountMinor: params.amountMinor,
          currency: params.currency,
          description: params.description.trim(),
          date: params.date,
          time: params.time || '12:00',
          accountId: params.accountId || '',
          toAccountId: params.toAccountId || '',
          partyId: params.partyId || '',
          convertedAmountMinor: params.convertedAmountMinor || 0,
          toCurrency: params.toCurrency || '',
          exchangeRateScaled: params.exchangeRateScaled || 0,
          feeMinor: params.feeMinor || 0,
        };

        const check = validateTransactionDraft(draft, bizAccounts, bizParties);
        if (!check.valid) {
          throw new Error(`VALIDATION: ${check.message}`);
        }

        const cat = bizCategories.find((c) => c.id === params.categoryId);
        const pty = bizParties.find((p) => p.id === params.partyId);
        const now = new Date().toISOString();
        const txId = nextId('tx');

        const tx: Transaction = {
          id: txId,
          businessId: params.businessId,
          ownerId: biz.ownerId,
          idempotencyKey: params.idempotencyKey,
          type: draft.type,
          amountMinor: draft.amountMinor,
          currency: draft.currency,
          convertedAmountMinor: draft.convertedAmountMinor,
          toCurrency: draft.toCurrency,
          exchangeRateScaled: draft.exchangeRateScaled,
          feeMinor: draft.feeMinor,
          date: draft.date,
          time: draft.time,
          description: draft.description,
          accountId: draft.accountId,
          toAccountId: draft.toAccountId,
          categoryId: params.categoryId || '',
          categoryLabelSnapshot: cat?.name || '',
          partyId: draft.partyId,
          partyNameSnapshot: pty?.name || '',
          referenceNumber: params.referenceNumber?.trim() || '',
          notes: params.notes?.trim() || '',
          attachmentName: '',
          attachmentMime: '',
          dueDate: params.dueDate || '',
          status: 'posted',
          voidReason: '',
          createdByUid: params.actorUid,
          createdAt: now,
          updatedAt: now,
        };

        transactions.get(params.businessId)!.push(tx);
        idempotencyIndex.set(idemFullKey, tx);
        auditEvents.get(params.businessId)!.push({
          id: `create_${txId}`,
          businessId: params.businessId,
          ownerId: biz.ownerId,
          entityType: 'transaction',
          entityId: txId,
          action: 'create',
          actorUid: params.actorUid,
          actorName: params.actorName,
          summary: `Recorded ${tx.type}: ${tx.description}`,
          previousSummary: '',
          newSummary: `${tx.type} ${formatMinorAmount(tx.amountMinor, tx.currency)} (${tx.date})`,
          reason: '',
          createdAt: now,
        });

        return { transaction: tx, deduplicated: false };
      });
    },

    async editTransaction(params: {
      actorUid: string;
      actorName: string;
      businessId: string;
      transactionId: string;
      amountMinor: number;
      description: string;
      date: string;
      reason?: string;
    }): Promise<Transaction> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canEditTransactions');
        const list = transactions.get(params.businessId)!;
        const idx = list.findIndex((t) => t.id === params.transactionId);
        if (idx === -1) throw new Error('NOT_FOUND: Transaction not found.');

        const prev = list[idx];
        if (prev.status === 'voided') {
          throw new Error('TERMINAL_STATE: Voided transactions cannot be modified.');
        }

        const check = validateTransactionDraft(
          {
            type: prev.type,
            amountMinor: params.amountMinor,
            currency: prev.currency,
            description: params.description,
            date: params.date,
            time: prev.time,
            accountId: prev.accountId,
            toAccountId: prev.toAccountId,
            partyId: prev.partyId,
            convertedAmountMinor: prev.convertedAmountMinor,
            toCurrency: prev.toCurrency,
            exchangeRateScaled: prev.exchangeRateScaled,
            feeMinor: prev.feeMinor,
          },
          accounts.get(params.businessId)!,
          parties.get(params.businessId)!
        );
        if (!check.valid) throw new Error(`VALIDATION: ${check.message}`);

        const now = new Date().toISOString();
        const updated: Transaction = {
          ...prev,
          amountMinor: params.amountMinor,
          description: params.description.trim(),
          date: params.date,
          updatedAt: now,
        };
        list[idx] = updated;
        idempotencyIndex.set(`${params.businessId}:${updated.idempotencyKey}`, updated);

        auditEvents.get(params.businessId)!.push({
          id: nextId('evt'),
          businessId: params.businessId,
          ownerId: biz.ownerId,
          entityType: 'transaction',
          entityId: updated.id,
          action: 'update',
          actorUid: params.actorUid,
          actorName: params.actorName,
          summary: `Edited transaction "${updated.description}"`,
          previousSummary: `${prev.description} — ${formatMinorAmount(prev.amountMinor, prev.currency)} (${prev.date})`,
          newSummary: `${updated.description} — ${formatMinorAmount(updated.amountMinor, updated.currency)} (${updated.date})`,
          reason: params.reason || 'Updated record',
          createdAt: now,
        });

        return updated;
      });
    },

    async voidTransaction(params: {
      actorUid: string;
      actorName: string;
      businessId: string;
      transactionId: string;
      reason: string;
    }): Promise<Transaction> {
      return runAtomic(() => {
        const biz = assertPermission(params.actorUid, params.businessId, 'canVoidTransactions');
        if (!params.reason.trim()) throw new Error('VALIDATION: Void reason is required.');

        const list = transactions.get(params.businessId)!;
        const idx = list.findIndex((t) => t.id === params.transactionId);
        if (idx === -1) throw new Error('NOT_FOUND: Transaction not found.');

        const prev = list[idx];
        if (prev.status === 'voided') {
          throw new Error('TERMINAL_STATE: Transaction is already voided.');
        }

        const now = new Date().toISOString();
        const voided: Transaction = {
          ...prev,
          status: 'voided',
          voidReason: params.reason.trim(),
          updatedAt: now,
        };
        list[idx] = voided;

        auditEvents.get(params.businessId)!.push({
          id: nextId('evt'),
          businessId: params.businessId,
          ownerId: biz.ownerId,
          entityType: 'transaction',
          entityId: voided.id,
          action: 'void',
          actorUid: params.actorUid,
          actorName: params.actorName,
          summary: `Voided transaction "${voided.description}"`,
          previousSummary: `posted — ${formatMinorAmount(prev.amountMinor, prev.currency)}`,
          newSummary: `voided — ${formatMinorAmount(prev.amountMinor, prev.currency)}`,
          reason: voided.voidReason,
          createdAt: now,
        });

        return voided;
      });
    },

    async getWorkspaceSnapshot(actorUid: string, businessId: string) {
      return runAtomic(() => {
        const biz = assertPermission(actorUid, businessId, 'canRead');
        const accs = [...accounts.get(businessId)!];
        const cats = [...categories.get(businessId)!];
        const ptys = [...parties.get(businessId)!];
        const txs = [...transactions.get(businessId)!];
        const audits = [...auditEvents.get(businessId)!];

        return {
          business: biz,
          accounts: accs,
          categories: cats,
          parties: ptys,
          transactions: txs,
          auditEvents: audits,
          accountBalances: computeAccountBalances(accs, txs),
          partyBalances: computePartyBalances(ptys, txs),
        };
      });
    },
  };
}
