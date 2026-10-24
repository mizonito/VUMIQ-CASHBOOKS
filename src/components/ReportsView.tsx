import React, { useMemo, useState } from 'react';
import { FileDown, FileSpreadsheet, FileText } from 'lucide-react';
import {
  Account,
  Business,
  Category,
  CurrencyCode,
  LocaleCode,
  MemberRole,
  Party,
  ReportType,
  Transaction,
} from '../types/cashbook';
import {
  computeAccountBalances,
  computeCategoryBreakdown,
  computePartyBalances,
  computePeriodSummariesByCurrency,
  computeTimeSeriesData,
  filterAndSearchTransactions,
  isDateWithinRange,
  resolveDateRangePreset,
  ROLE_PERMISSIONS,
} from '../engine/accounting';
import { formatMinorAmount } from '../engine/money';
import { getTransactionTypeLabel, translate } from '../i18n/translations';
import {
  exportReportToCsv,
  exportReportToPdf,
  exportReportToXlsx,
  ReconciledReportDocument,
} from '../utils/exporters';

interface ReportsViewProps {
  locale: LocaleCode;
  role: MemberRole;
  business: Business;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  todayIso: string;
}

const REPORT_CATALOG: Array<{ id: ReportType; title: string; basis: string }> = [
  { id: 'income_expense_summary', title: 'Income & Expense Summary', basis: 'Recognized Income & Expenses (excludes transfers and debt settlements)' },
  { id: 'cashbook', title: 'Cashbook Report', basis: 'Posted Cash Inflows, Outflows & Net Cash Movement' },
  { id: 'account_statement', title: 'Account Statement', basis: 'Running Account Ledger Balance (Opening + Inflows - Outflows)' },
  { id: 'daily', title: 'Daily Report', basis: 'Daily Income, Expense & Net Cash Movement' },
  { id: 'weekly', title: 'Weekly Report', basis: '7-Day Period Cash & Income Summary' },
  { id: 'monthly', title: 'Monthly Report', basis: 'Monthly Aggregated Financial Performance' },
  { id: 'annual', title: 'Annual Report', basis: 'Calendar / Fiscal Year Summary by Month' },
  { id: 'category_breakdown', title: 'Category Breakdown', basis: 'Recognized Totals & Share by Category' },
  { id: 'party_statement', title: 'Party Statement', basis: 'Counterparty Credit Invoices, Settlements & Remaining Balance' },
  { id: 'receivables', title: 'Receivables Report', basis: 'Outstanding Customer Receivables (Money to Receive)' },
  { id: 'payables', title: 'Payables Report', basis: 'Outstanding Supplier Payables (Money to Pay)' },
  { id: 'transaction_register', title: 'Transaction Register', basis: 'Complete Chronological Audit Register' },
];

export const ReportsView: React.FC<ReportsViewProps> = ({
  locale,
  role,
  business,
  accounts,
  categories,
  parties,
  transactions,
  todayIso,
}) => {
  const [reportType, setReportType] = useState<ReportType>('income_expense_summary');
  const [currency, setCurrency] = useState<CurrencyCode>(business.defaultCurrency);
  const [startDate, setStartDate] = useState(() => resolveDateRangePreset('month', todayIso).startDate);
  const [endDate, setEndDate] = useState(() => resolveDateRangePreset('month', todayIso).endDate);
  const [selectedAccountId, setSelectedAccountId] = useState<string>(accounts[0]?.id || '');
  const [selectedPartyId, setSelectedPartyId] = useState<string>(parties[0]?.id || '');

  const canExport = ROLE_PERMISSIONS[role].canExportData;

  const availableCurrencies = useMemo(() => {
    const set = new Set<CurrencyCode>([business.defaultCurrency]);
    accounts.forEach((a) => set.add(a.currency));
    transactions.forEach((t) => set.add(t.currency));
    return Array.from(set);
  }, [business.defaultCurrency, accounts, transactions]);

  const effectiveRange = useMemo(() => {
    if (reportType === 'daily') return { startDate: endDate || todayIso, endDate: endDate || todayIso };
    if (reportType === 'weekly') return resolveDateRangePreset('week', endDate || todayIso);
    if (reportType === 'annual') return resolveDateRangePreset('year', endDate || todayIso);
    return { startDate, endDate };
  }, [reportType, startDate, endDate, todayIso]);

  const reportDoc: ReconciledReportDocument = useMemo(() => {
    const meta = REPORT_CATALOG.find((r) => r.id === reportType)!;
    const periodLabel =
      effectiveRange.startDate && effectiveRange.endDate
        ? `${effectiveRange.startDate} to ${effectiveRange.endDate}`
        : 'All Dates';

    const summaries = computePeriodSummariesByCurrency(
      accounts,
      parties,
      transactions,
      effectiveRange,
      currency
    );
    const curSum = summaries[currency] || {
      currency,
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

    const accMap = new Map(accounts.map((a) => [a.id, a]));
    const catMap = new Map(categories.map((c) => [c.id, c]));
    const partyMap = new Map(parties.map((p) => [p.id, p]));

    // 1. Category Breakdown
    if (reportType === 'category_breakdown') {
      const incCats = computeCategoryBreakdown(categories, transactions, currency, 'income', effectiveRange);
      const expCats = computeCategoryBreakdown(categories, transactions, currency, 'expense', effectiveRange);
      const rows: string[][] = [
        ...incCats.map((c) => ['Income', c.categoryName, String(c.count), `${c.percentage}%`, formatMinorAmount(c.amountMinor, currency, locale)]),
        ...expCats.map((c) => ['Expense', c.categoryName, String(c.count), `${c.percentage}%`, formatMinorAmount(c.amountMinor, currency, locale)]),
      ];
      return {
        reportTitle: meta.title,
        businessName: business.name,
        currency,
        periodLabel,
        filterDescription: `Currency: ${currency}`,
        totalsBasisLabel: meta.basis,
        summaryMetrics: [
          { label: 'Total Recognized Income', value: formatMinorAmount(curSum.periodIncomeMinor, currency, locale) },
          { label: 'Total Recognized Expenses', value: formatMinorAmount(curSum.periodExpenseMinor, currency, locale) },
          { label: 'Net Operating Result', value: formatMinorAmount(curSum.netIncomeMinor, currency, locale) },
        ],
        columns: ['Direction', 'Category', 'Entries', 'Share', 'Amount'],
        rows,
        footerTotalsRow: ['TOTAL NET', '', '', '', formatMinorAmount(curSum.netIncomeMinor, currency, locale)],
      };
    }

    // 2. Receivables or Payables Report
    if (reportType === 'receivables' || reportType === 'payables') {
      const partyBals = computePartyBalances(parties, transactions);
      const isRec = reportType === 'receivables';
      const rows: string[][] = [];
      let totalOutstanding = 0;

      for (const pb of Object.values(partyBals)) {
        if (pb.party.status !== 'active') continue;
        const amt = isRec ? pb.receivableByCurrency[currency] || 0 : pb.payableByCurrency[currency] || 0;
        if (amt > 0) {
          totalOutstanding += amt;
          rows.push([
            pb.party.name,
            pb.party.partyType,
            pb.party.phone || '—',
            pb.party.dueDate || '—',
            formatMinorAmount(amt, currency, locale),
          ]);
        }
      }

      return {
        reportTitle: meta.title,
        businessName: business.name,
        currency,
        periodLabel: `As of ${todayIso}`,
        filterDescription: `Active parties in ${currency}`,
        totalsBasisLabel: meta.basis,
        summaryMetrics: [
          {
            label: isRec ? 'Total Outstanding Receivables' : 'Total Outstanding Payables',
            value: formatMinorAmount(totalOutstanding, currency, locale),
          },
          { label: 'Parties with Balance', value: String(rows.length) },
        ],
        columns: ['Party Name', 'Type', 'Phone', 'Due Date', 'Outstanding Balance'],
        rows,
        footerTotalsRow: ['TOTAL OUTSTANDING', '', '', '', formatMinorAmount(totalOutstanding, currency, locale)],
      };
    }

    // 3. Account Statement (with running balance)
    if (reportType === 'account_statement') {
      const targetAcc = accounts.find((a) => a.id === selectedAccountId) || accounts[0];
      if (!targetAcc) {
        return {
          reportTitle: meta.title,
          businessName: business.name,
          currency,
          periodLabel,
          filterDescription: 'No account available',
          totalsBasisLabel: meta.basis,
          summaryMetrics: [],
          columns: ['Date', 'Description', 'Type', 'Inflow', 'Outflow', 'Running Balance'],
          rows: [],
        };
      }

      const accCur = targetAcc.currency;
      const sortedAll = filterAndSearchTransactions(transactions, accounts, categories, parties, {
        accountId: targetAcc.id,
        includeVoided: false,
        sortBy: 'date_asc',
      });

      let running = targetAcc.openingBalanceMinor;
      let openingAtPeriodStart = targetAcc.openingBalanceMinor;
      let periodIn = 0;
      let periodOut = 0;
      const rows: string[][] = [];

      for (const tx of sortedAll) {
        let delta = 0;
        if (['income', 'opening_balance', 'refund_expense', 'receivable_payment'].includes(tx.type) && tx.accountId === targetAcc.id) {
          delta = tx.amountMinor;
        } else if (['expense', 'refund_income', 'payable_payment'].includes(tx.type) && tx.accountId === targetAcc.id) {
          delta = -tx.amountMinor;
        } else if (tx.type === 'transfer') {
          if (tx.accountId === targetAcc.id) {
            delta = -(tx.amountMinor + (tx.feeMinor || 0));
          } else if (tx.toAccountId === targetAcc.id) {
            delta =
              tx.toCurrency && tx.toCurrency !== tx.currency && tx.convertedAmountMinor > 0
                ? tx.convertedAmountMinor
                : tx.amountMinor;
          }
        }

        if (effectiveRange.startDate && tx.date < effectiveRange.startDate) {
          openingAtPeriodStart += delta;
          running += delta;
          continue;
        }
        if (effectiveRange.endDate && tx.date > effectiveRange.endDate) {
          continue;
        }

        running += delta;
        if (delta > 0) periodIn += delta;
        if (delta < 0) periodOut += Math.abs(delta);

        rows.push([
          tx.date,
          tx.description,
          getTransactionTypeLabel(locale, tx.type),
          delta > 0 ? formatMinorAmount(delta, accCur, locale) : '—',
          delta < 0 ? formatMinorAmount(Math.abs(delta), accCur, locale) : '—',
          formatMinorAmount(running, accCur, locale),
        ]);
      }

      return {
        reportTitle: `${meta.title} — ${targetAcc.name}`,
        businessName: business.name,
        currency: accCur,
        periodLabel,
        filterDescription: `Account: ${targetAcc.name} (${accCur})`,
        totalsBasisLabel: meta.basis,
        summaryMetrics: [
          { label: 'Opening Balance at Period Start', value: formatMinorAmount(openingAtPeriodStart, accCur, locale) },
          { label: 'Period Inflows', value: formatMinorAmount(periodIn, accCur, locale) },
          { label: 'Period Outflows', value: formatMinorAmount(periodOut, accCur, locale) },
          { label: 'Closing Account Balance', value: formatMinorAmount(running, accCur, locale) },
        ],
        columns: ['Date', 'Description', 'Type', 'Inflow', 'Outflow', 'Running Balance'],
        rows,
        footerTotalsRow: [
          'CLOSING TOTALS',
          '',
          '',
          formatMinorAmount(periodIn, accCur, locale),
          formatMinorAmount(periodOut, accCur, locale),
          formatMinorAmount(running, accCur, locale),
        ],
      };
    }

    // 4. Party Statement
    if (reportType === 'party_statement') {
      const targetParty = parties.find((p) => p.id === selectedPartyId) || parties[0];
      const partyBals = computePartyBalances(parties, transactions);
      if (!targetParty) {
        return {
          reportTitle: meta.title,
          businessName: business.name,
          currency,
          periodLabel,
          filterDescription: 'No party selected',
          totalsBasisLabel: meta.basis,
          summaryMetrics: [],
          columns: ['Date', 'Description', 'Workflow', 'Amount'],
          rows: [],
        };
      }
      const pTxs = filterAndSearchTransactions(transactions, accounts, categories, parties, {
        partyId: targetParty.id,
        currency,
        dateRange: effectiveRange,
        sortBy: 'date_asc',
      });
      const pb = partyBals[targetParty.id];
      const remRec = pb?.receivableByCurrency[currency] || 0;
      const remPay = pb?.payableByCurrency[currency] || 0;

      return {
        reportTitle: `${meta.title} — ${targetParty.name}`,
        businessName: business.name,
        currency,
        periodLabel,
        filterDescription: `Party: ${targetParty.name} (${targetParty.partyType})`,
        totalsBasisLabel: meta.basis,
        summaryMetrics: [
          { label: 'Remaining Receivable (They Owe You)', value: formatMinorAmount(remRec, currency, locale) },
          { label: 'Remaining Payable (You Owe Them)', value: formatMinorAmount(remPay, currency, locale) },
        ],
        columns: ['Date', 'Description', 'Workflow', 'Reference', 'Amount'],
        rows: pTxs.map((t) => [
          t.date,
          t.description,
          getTransactionTypeLabel(locale, t.type),
          t.referenceNumber || '—',
          formatMinorAmount(t.amountMinor, t.currency, locale),
        ]),
      };
    }

    // 5. Annual / Monthly Time-Bucketed Report
    if (reportType === 'annual' || reportType === 'monthly') {
      const gran = reportType === 'annual' ? 'month' : 'day';
      const buckets = computeTimeSeriesData(transactions, currency, effectiveRange, gran);
      return {
        reportTitle: meta.title,
        businessName: business.name,
        currency,
        periodLabel,
        filterDescription: `Currency: ${currency} | Granularity: ${gran}`,
        totalsBasisLabel: meta.basis,
        summaryMetrics: [
          { label: 'Period Income', value: formatMinorAmount(curSum.periodIncomeMinor, currency, locale) },
          { label: 'Period Expenses', value: formatMinorAmount(curSum.periodExpenseMinor, currency, locale) },
          { label: 'Net Cash Movement', value: formatMinorAmount(curSum.netCashMovementMinor, currency, locale) },
        ],
        columns: [gran === 'month' ? 'Month' : 'Date', 'Income', 'Expenses', 'Net Cash Movement'],
        rows: buckets.map((b) => [
          b.label,
          formatMinorAmount(b.incomeMinor, currency, locale),
          formatMinorAmount(b.expenseMinor, currency, locale),
          formatMinorAmount(b.netCashMinor, currency, locale, { showPlusSign: true }),
        ]),
        footerTotalsRow: [
          'PERIOD TOTAL',
          formatMinorAmount(curSum.periodIncomeMinor, currency, locale),
          formatMinorAmount(curSum.periodExpenseMinor, currency, locale),
          formatMinorAmount(curSum.netCashMovementMinor, currency, locale, { showPlusSign: true }),
        ],
      };
    }

    // 6. Income & Expense Summary / Cashbook / Daily / Weekly / Transaction Register
    const periodTxs = transactions
      .filter((t) => t.status === 'posted' && t.currency === currency && isDateWithinRange(t.date, effectiveRange))
      .sort((a, b) => a.date.localeCompare(b.date) || a.time.localeCompare(b.time));

    const curAccounts = accounts.filter((a) => a.currency === currency && a.status === 'active');
    const currentBalances = computeAccountBalances(curAccounts, transactions);
    const totalClosingCash = curAccounts.reduce((s, a) => s + (currentBalances[a.id] || 0), 0);

    return {
      reportTitle: meta.title,
      businessName: business.name,
      currency,
      periodLabel,
      filterDescription: `Currency: ${currency}`,
      totalsBasisLabel: meta.basis,
      summaryMetrics: [
        { label: 'Current Cash Balance (All Active Accounts)', value: formatMinorAmount(totalClosingCash, currency, locale) },
        { label: 'Selected Period Income', value: formatMinorAmount(curSum.periodIncomeMinor, currency, locale) },
        { label: 'Selected Period Expenses', value: formatMinorAmount(curSum.periodExpenseMinor, currency, locale) },
        { label: 'Selected Period Net Cash Movement', value: formatMinorAmount(curSum.netCashMovementMinor, currency, locale, { showPlusSign: true }) },
      ],
      columns: ['Date', 'Description', 'Type', 'Category', 'Account', 'Party', 'Amount'],
      rows: periodTxs.map((tx) => [
        tx.date,
        tx.description,
        getTransactionTypeLabel(locale, tx.type),
        catMap.get(tx.categoryId)?.name || tx.categoryLabelSnapshot || '—',
        accMap.get(tx.accountId)?.name || 'Subledger',
        partyMap.get(tx.partyId)?.name || tx.partyNameSnapshot || '—',
        formatMinorAmount(tx.amountMinor, tx.currency, locale),
      ]),
      footerTotalsRow: [
        `RECORDS: ${periodTxs.length}`,
        `Income: ${formatMinorAmount(curSum.periodIncomeMinor, currency, locale)}`,
        `Expenses: ${formatMinorAmount(curSum.periodExpenseMinor, currency, locale)}`,
        '',
        '',
        'Net Cash:',
        formatMinorAmount(curSum.netCashMovementMinor, currency, locale, { showPlusSign: true }),
      ],
    };
  }, [reportType, effectiveRange, accounts, parties, transactions, categories, currency, business.name, locale, selectedAccountId, selectedPartyId, todayIso]);

  return (
    <div className="space-y-6">
      {/* Header + Export Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
            {translate(locale, 'navReports')}
          </h1>
          <p className="text-xs text-[#58655D] dark:text-[#98A89E] mt-0.5">
            Reconciled financial statements with exact minor-unit totals
          </p>
        </div>

        {canExport && (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => exportReportToPdf(reportDoc)}
              className="min-h-[40px] px-3.5 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-xs font-medium flex items-center gap-1.5 cursor-pointer whitespace-nowrap"
            >
              <FileText className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'exportPdf')}</span>
            </button>
            <button
              type="button"
              onClick={() => exportReportToCsv(reportDoc)}
              className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] hover:border-[#126B45] text-xs font-medium flex items-center gap-1.5 cursor-pointer whitespace-nowrap"
            >
              <FileDown className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'exportCsv')}</span>
            </button>
            <button
              type="button"
              onClick={() => exportReportToXlsx(reportDoc)}
              className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] hover:border-[#126B45] text-xs font-medium flex items-center gap-1.5 cursor-pointer whitespace-nowrap"
            >
              <FileSpreadsheet className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{translate(locale, 'exportXlsx')}</span>
            </button>
          </div>
        )}
      </div>

      {/* Report Configuration Controls */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-5 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div>
          <label htmlFor="rep-type" className="block text-xs font-medium mb-1">
            Report Type
          </label>
          <select
            id="rep-type"
            value={reportType}
            onChange={(e) => setReportType(e.target.value as ReportType)}
            className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-medium"
          >
            {REPORT_CATALOG.map((r) => (
              <option key={r.id} value={r.id}>
                {r.title}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="rep-currency" className="block text-xs font-medium mb-1">
            Currency
          </label>
          <select
            id="rep-currency"
            value={currency}
            onChange={(e) => setCurrency(e.target.value as CurrencyCode)}
            disabled={reportType === 'account_statement'}
            className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num disabled:opacity-60"
          >
            {availableCurrencies.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        {reportType === 'account_statement' ? (
          <div className="sm:col-span-2">
            <label htmlFor="rep-acc" className="block text-xs font-medium mb-1">
              Target Financial Account
            </label>
            <select
              id="rep-acc"
              value={selectedAccountId}
              onChange={(e) => setSelectedAccountId(e.target.value)}
              className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
            >
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.currency})
                </option>
              ))}
            </select>
          </div>
        ) : reportType === 'party_statement' ? (
          <div className="sm:col-span-2">
            <label htmlFor="rep-party" className="block text-xs font-medium mb-1">
              Counterparty (Customer / Supplier)
            </label>
            <select
              id="rep-party"
              value={selectedPartyId}
              onChange={(e) => setSelectedPartyId(e.target.value)}
              className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
            >
              {parties.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.partyType})
                </option>
              ))}
            </select>
          </div>
        ) : (
          <>
            <div>
              <label htmlFor="rep-start" className="block text-xs font-medium mb-1">
                Start Date
              </label>
              <input
                id="rep-start"
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
              />
            </div>
            <div>
              <label htmlFor="rep-end" className="block text-xs font-medium mb-1">
                End / Reference Date
              </label>
              <input
                id="rep-end"
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="w-full min-h-[42px] px-3 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
              />
            </div>
          </>
        )}
      </section>

      {/* Reconciled Statement Sheet */}
      <section className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] overflow-hidden">
        <div className="px-6 py-5 border-b border-[#E2E8E3] dark:border-[#28362D] space-y-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="font-display text-lg font-bold text-[#17231D] dark:text-[#ECF3EE]">
              {reportDoc.reportTitle}
            </h2>
            <span className="text-xs font-mono-num text-[#58655D] dark:text-[#98A89E]">
              Period: {reportDoc.periodLabel} · Currency: {reportDoc.currency}
            </span>
          </div>
          <p className="text-xs text-[#58655D] dark:text-[#98A89E]">
            Accounting Basis: {reportDoc.totalsBasisLabel}
          </p>

          {reportDoc.summaryMetrics.length > 0 && (
            <div className="pt-3 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 border-t border-[#E2E8E3] dark:border-[#28362D]">
              {reportDoc.summaryMetrics.map((m) => (
                <div key={m.label}>
                  <p className="text-xs text-[#58655D] dark:text-[#98A89E]">{m.label}</p>
                  <p className="mt-1 text-base font-bold font-mono-num text-[#17231D] dark:text-[#ECF3EE]">
                    {m.value}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-sm">
            <thead>
              <tr className="border-b border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713]">
                {reportDoc.columns.map((col, idx) => (
                  <th
                    key={col}
                    className={`px-5 py-3 text-xs font-semibold text-[#58655D] dark:text-[#98A89E] whitespace-nowrap ${
                      idx === reportDoc.columns.length - 1 ? 'text-right' : 'text-left'
                    }`}
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E2E8E3] dark:divide-[#28362D]">
              {reportDoc.rows.length === 0 ? (
                <tr>
                  <td
                    colSpan={reportDoc.columns.length}
                    className="px-5 py-10 text-center text-xs text-[#58655D] dark:text-[#98A89E]"
                  >
                    No posted financial records in this period for {reportDoc.currency}.
                  </td>
                </tr>
              ) : (
                reportDoc.rows.map((row, rIdx) => (
                  <tr key={rIdx} className="hover:bg-[#F6F8F5]/70 dark:hover:bg-[#212D25]/60">
                    {row.map((cell, cIdx) => (
                      <td
                        key={cIdx}
                        className={`px-5 py-3 text-xs ${
                          cIdx === 0 || cIdx >= row.length - 2 ? 'font-mono-num' : ''
                        } ${cIdx === row.length - 1 ? 'text-right font-semibold' : 'text-left'}`}
                      >
                        {cell}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
            {reportDoc.footerTotalsRow && reportDoc.rows.length > 0 && (
              <tfoot>
                <tr className="border-t-2 border-[#126B45]/30 bg-[#EFF3EE] dark:bg-[#212D25] font-bold">
                  {reportDoc.footerTotalsRow.map((cell, idx) => (
                    <td
                      key={idx}
                      className={`px-5 py-3 text-xs font-mono-num ${
                        idx === reportDoc.footerTotalsRow!.length - 1 ? 'text-right' : 'text-left'
                      }`}
                    >
                      {cell}
                    </td>
                  ))}
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      </section>
    </div>
  );
};
