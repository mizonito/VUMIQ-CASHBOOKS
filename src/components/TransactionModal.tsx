import React, { useEffect, useMemo, useState } from 'react';
import { X, ArrowLeftRight, Paperclip } from 'lucide-react';
import {
  Account,
  Category,
  CurrencyCode,
  LocaleCode,
  Party,
  Transaction,
  TransactionType,
} from '../types/cashbook';
import {
  convertMinorWithScaledRate,
  formatMinorAmount,
  minorToInputString,
  parseAmountToMinor,
  parseExchangeRateScaled,
} from '../engine/money';
import { getTransactionTypeLabel, translate } from '../i18n/translations';

interface TransactionModalProps {
  isOpen: boolean;
  locale: LocaleCode;
  defaultType?: TransactionType;
  defaultPartyId?: string;
  editingTransaction?: Transaction | null;
  duplicatingFrom?: Transaction | null;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  defaultCurrency: CurrencyCode;
  todayIso: string;
  onClose: () => void;
  onSubmit: (payload: {
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
  }) => Promise<void>;
}

const ALLOWED_RECEIPT_MIMES = new Set(['image/jpeg', 'image/png', 'image/webp', 'application/pdf']);

export const TransactionModal: React.FC<TransactionModalProps> = ({
  isOpen,
  locale,
  defaultType = 'income',
  defaultPartyId = '',
  editingTransaction = null,
  duplicatingFrom = null,
  accounts,
  categories,
  parties,
  defaultCurrency,
  todayIso,
  onClose,
  onSubmit,
}) => {
  const activeAccounts = useMemo(() => accounts.filter((a) => a.status === 'active'), [accounts]);
  const activeParties = useMemo(() => parties.filter((p) => p.status === 'active'), [parties]);

  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [type, setType] = useState<TransactionType>(defaultType);
  const [accountId, setAccountId] = useState('');
  const [toAccountId, setToAccountId] = useState('');
  const [currency, setCurrency] = useState<CurrencyCode>(defaultCurrency);
  const [amountInput, setAmountInput] = useState('');
  const [exchangeRateInput, setExchangeRateInput] = useState('1.00');
  const [feeInput, setFeeInput] = useState('0.00');
  const [date, setDate] = useState(todayIso);
  const [time, setTime] = useState('12:00');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [partyId, setPartyId] = useState(defaultPartyId);
  const [referenceNumber, setReferenceNumber] = useState('');
  const [notes, setNotes] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [attachmentName, setAttachmentName] = useState('');
  const [attachmentMime, setAttachmentMime] = useState('');
  const [editReason, setEditReason] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setErrorMsg('');
    setSubmitting(false);

    const seed = editingTransaction || duplicatingFrom;
    setIdempotencyKey(`idem_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`);

    if (seed) {
      setType(seed.type);
      setAccountId(seed.accountId);
      setToAccountId(seed.toAccountId);
      setCurrency(seed.currency);
      setAmountInput(minorToInputString(seed.amountMinor, seed.currency));
      setExchangeRateInput(
        seed.exchangeRateScaled > 0 ? String(seed.exchangeRateScaled / 1_000_000) : '1.00'
      );
      setFeeInput(seed.feeMinor > 0 ? minorToInputString(seed.feeMinor, seed.currency) : '0.00');
      setDate(editingTransaction ? seed.date : todayIso);
      setTime(seed.time || '12:00');
      setDescription(duplicatingFrom ? `${seed.description} (Copy)` : seed.description);
      setCategoryId(seed.categoryId);
      setPartyId(seed.partyId);
      setReferenceNumber(editingTransaction ? seed.referenceNumber : '');
      setNotes(seed.notes);
      setDueDate(seed.dueDate);
      setAttachmentName(seed.attachmentName);
      setAttachmentMime(seed.attachmentMime);
      setEditReason('');
      return;
    }

    const firstAcc = activeAccounts[0];
    const secondAcc = activeAccounts[1] || activeAccounts[0];
    setType(defaultType);
    setAccountId(firstAcc?.id || '');
    setToAccountId(secondAcc && secondAcc.id !== firstAcc?.id ? secondAcc.id : '');
    setCurrency(firstAcc?.currency || defaultCurrency);
    setAmountInput('');
    setExchangeRateInput('1.00');
    setFeeInput('0.00');
    setDate(todayIso);
    const now = new Date();
    setTime(`${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`);
    const targetKind = ['income', 'credit_sale', 'refund_income'].includes(defaultType)
      ? 'income'
      : ['expense', 'credit_purchase', 'refund_expense'].includes(defaultType)
      ? 'expense'
      : null;
    const defaultCat = targetKind
      ? categories.find((c) => !c.archived && c.kind === targetKind)
      : undefined;

    setDescription('');
    setCategoryId(defaultCat?.id || '');
    setPartyId(defaultPartyId || '');
    setReferenceNumber('');
    setNotes('');
    setDueDate('');
    setAttachmentName('');
    setAttachmentMime('');
    setEditReason('');
  }, [isOpen, editingTransaction, duplicatingFrom, defaultType, defaultPartyId, activeAccounts, categories, defaultCurrency, todayIso]);

  if (!isOpen) return null;

  const requiresCashAccount = !['credit_sale', 'credit_purchase'].includes(type);
  const requiresParty = ['credit_sale', 'receivable_payment', 'credit_purchase', 'payable_payment'].includes(type);
  const isTransfer = type === 'transfer';

  const sourceAcc = activeAccounts.find((a) => a.id === accountId);
  const destAcc = activeAccounts.find((a) => a.id === toAccountId);
  const activeCurrency: CurrencyCode = requiresCashAccount && sourceAcc ? sourceAcc.currency : currency;
  const isCrossCurrencyTransfer = Boolean(isTransfer && sourceAcc && destAcc && sourceAcc.currency !== destAcc.currency);

  const relevantCategoryKind: 'income' | 'expense' | null = ['income', 'credit_sale', 'refund_income'].includes(type)
    ? 'income'
    : ['expense', 'credit_purchase', 'refund_expense'].includes(type)
    ? 'expense'
    : null;

  const filteredCategories = categories.filter(
    (c) => !c.archived && (!relevantCategoryKind || c.kind === relevantCategoryKind)
  );

  // Live converted preview for cross-currency transfers
  let convertedPreviewStr = '';
  if (isCrossCurrencyTransfer && destAcc) {
    const parsedAmt = parseAmountToMinor(amountInput, activeCurrency);
    const parsedRate = parseExchangeRateScaled(exchangeRateInput);
    if (parsedAmt.ok && parsedRate.ok) {
      const convMinor = convertMinorWithScaledRate(
        parsedAmt.minorUnits,
        activeCurrency,
        destAcc.currency,
        parsedRate.rateScaled
      );
      convertedPreviewStr = formatMinorAmount(convMinor, destAcc.currency, locale);
    }
  }

  const handleFileAttachment = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!ALLOWED_RECEIPT_MIMES.has(file.type)) {
      setErrorMsg('Attachment must be a JPG, PNG, WebP image or PDF receipt.');
      e.target.value = '';
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setErrorMsg('Receipt file must be 5 MB or smaller.');
      e.target.value = '';
      return;
    }
    setErrorMsg('');
    setAttachmentName(file.name.slice(0, 120));
    setAttachmentMime(file.type.slice(0, 64));
  };

  const handleFormSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg('');

    const parsedAmt = parseAmountToMinor(amountInput, activeCurrency, { allowZero: false });
    if (!parsedAmt.ok) {
      setErrorMsg(parsedAmt.message);
      return;
    }

    if (!description.trim()) {
      setErrorMsg('Description is required.');
      return;
    }

    if (requiresCashAccount && !accountId) {
      setErrorMsg('Please select a payment account.');
      return;
    }

    if (isTransfer) {
      if (!toAccountId || toAccountId === accountId) {
        setErrorMsg('Select a different destination account for the transfer.');
        return;
      }
    }

    if (requiresParty && !partyId) {
      setErrorMsg('Please select a customer or supplier party for this credit/settlement entry.');
      return;
    }

    let feeMinor = 0;
    if (isTransfer && feeInput.trim()) {
      const parsedFee = parseAmountToMinor(feeInput, activeCurrency, { allowZero: true });
      if (!parsedFee.ok) {
        setErrorMsg(`Transfer fee: ${parsedFee.message}`);
        return;
      }
      feeMinor = parsedFee.minorUnits;
    }

    let convertedAmountMinor = 0;
    let toCurrency = '';
    let exchangeRateScaled = 0;

    if (isTransfer && destAcc) {
      toCurrency = destAcc.currency;
      if (isCrossCurrencyTransfer) {
        const parsedRate = parseExchangeRateScaled(exchangeRateInput);
        if (!parsedRate.ok) {
          setErrorMsg(parsedRate.message);
          return;
        }
        exchangeRateScaled = parsedRate.rateScaled;
        convertedAmountMinor = convertMinorWithScaledRate(
          parsedAmt.minorUnits,
          activeCurrency,
          destAcc.currency,
          exchangeRateScaled
        );
        if (convertedAmountMinor <= 0) {
          setErrorMsg('Converted destination amount must be greater than zero.');
          return;
        }
      } else {
        convertedAmountMinor = parsedAmt.minorUnits;
      }
    }

    setSubmitting(true);
    try {
      await onSubmit({
        idempotencyKey,
        editingTransaction: editingTransaction || undefined,
        type,
        amountMinor: parsedAmt.minorUnits,
        currency: activeCurrency,
        convertedAmountMinor,
        toCurrency,
        exchangeRateScaled,
        feeMinor,
        date,
        time,
        description: description.trim(),
        accountId: requiresCashAccount ? accountId : '',
        toAccountId: isTransfer ? toAccountId : '',
        categoryId: isTransfer || type === 'opening_balance' ? '' : categoryId,
        partyId,
        referenceNumber: referenceNumber.trim(),
        notes: notes.trim(),
        attachmentName,
        attachmentMime,
        dueDate: requiresParty ? dueDate : '',
        editReason: editingTransaction ? editReason.trim() || 'Edited transaction' : undefined,
      });
      onClose();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Unable to save transaction.');
    } finally {
      setSubmitting(false);
    }
  };

  const quickTypes: Array<{ value: TransactionType; label: string }> = [
    { value: 'income', label: getTransactionTypeLabel(locale, 'income') },
    { value: 'expense', label: getTransactionTypeLabel(locale, 'expense') },
    { value: 'transfer', label: getTransactionTypeLabel(locale, 'transfer') },
    { value: 'credit_sale', label: getTransactionTypeLabel(locale, 'credit_sale') },
    { value: 'receivable_payment', label: getTransactionTypeLabel(locale, 'receivable_payment') },
    { value: 'credit_purchase', label: getTransactionTypeLabel(locale, 'credit_purchase') },
    { value: 'payable_payment', label: getTransactionTypeLabel(locale, 'payable_payment') },
    { value: 'opening_balance', label: getTransactionTypeLabel(locale, 'opening_balance') },
    { value: 'refund_income', label: getTransactionTypeLabel(locale, 'refund_income') },
    { value: 'refund_expense', label: getTransactionTypeLabel(locale, 'refund_expense') },
  ];

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="tx-modal-title"
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/55 backdrop-blur-xs p-0 sm:p-4"
    >
      <div className="w-full max-w-2xl max-h-[92vh] flex flex-col rounded-t-3xl sm:rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] shadow-xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#E2E8E3] dark:border-[#28362D]">
          <h2 id="tx-modal-title" className="font-display text-lg font-bold text-[#17231D] dark:text-[#ECF3EE]">
            {editingTransaction ? 'Edit Financial Entry' : translate(locale, 'recordTransaction')}
          </h2>
          <button
            type="button"
            aria-label="Close transaction dialog"
            onClick={onClose}
            className="min-h-[40px] min-w-[40px] rounded-lg flex items-center justify-center text-[#58655D] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Scrollable Form */}
        <form onSubmit={handleFormSubmit} className="overflow-y-auto p-5 space-y-4">
          {errorMsg && (
            <div
              role="alert"
              className="rounded-xl border border-[#D94848]/40 bg-[#D94848]/10 px-4 py-3 text-sm text-[#B42323] dark:text-[#FCA5A5]"
            >
              {errorMsg}
            </div>
          )}

          {/* Transaction Type Selector */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label htmlFor="tx-type" className="block text-xs font-medium mb-1.5">
                Transaction Type *
              </label>
              <select
                id="tx-type"
                value={type}
                onChange={(e) => {
                  const nextType = e.target.value as TransactionType;
                  setType(nextType);
                  const nextKind = ['income', 'credit_sale', 'refund_income'].includes(nextType)
                    ? 'income'
                    : ['expense', 'credit_purchase', 'refund_expense'].includes(nextType)
                    ? 'expense'
                    : null;
                  if (!nextKind) {
                    setCategoryId('');
                  } else {
                    const firstMatch = categories.find((c) => !c.archived && c.kind === nextKind);
                    setCategoryId(firstMatch?.id || '');
                  }
                }}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-medium focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              >
                {quickTypes.map((qt) => (
                  <option key={qt.value} value={qt.value}>
                    {qt.label}
                  </option>
                ))}
              </select>
            </div>

            {/* Amount */}
            <div>
              <label htmlFor="tx-amount" className="block text-xs font-medium mb-1.5">
                Amount ({activeCurrency}) *
              </label>
              <input
                id="tx-amount"
                type="text"
                inputMode="decimal"
                required
                placeholder="0.00"
                value={amountInput}
                onChange={(e) => setAmountInput(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-base font-semibold font-mono-num focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              />
            </div>
          </div>

          {/* Non-cash currency selector when recording Credit Sale / Credit Purchase */}
          {!requiresCashAccount && (
            <div>
              <label htmlFor="tx-noncash-currency" className="block text-xs font-medium mb-1.5">
                Invoice / Credit Currency *
              </label>
              <select
                id="tx-noncash-currency"
                value={currency}
                onChange={(e) => setCurrency(e.target.value as CurrencyCode)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              >
                {Array.from(new Set([defaultCurrency, ...activeAccounts.map((a) => a.currency)])).map((cur) => (
                  <option key={cur} value={cur}>
                    {cur}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Account(s) */}
          {requiresCashAccount && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label htmlFor="tx-account" className="block text-xs font-medium mb-1.5">
                  {isTransfer ? 'From Account (Source) *' : 'Payment Account *'}
                </label>
                <select
                  id="tx-account"
                  value={accountId}
                  onChange={(e) => {
                    const nextAccId = e.target.value;
                    setAccountId(nextAccId);
                    const found = activeAccounts.find((a) => a.id === nextAccId);
                    if (found) setCurrency(found.currency);
                  }}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                >
                  <option value="">Select account...</option>
                  {activeAccounts.map((acc) => (
                    <option key={acc.id} value={acc.id}>
                      {acc.name} ({acc.currency})
                    </option>
                  ))}
                </select>
              </div>

              {isTransfer && (
                <div>
                  <label htmlFor="tx-to-account" className="block text-xs font-medium mb-1.5">
                    To Account (Destination) *
                  </label>
                  <select
                    id="tx-to-account"
                    value={toAccountId}
                    onChange={(e) => setToAccountId(e.target.value)}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                  >
                    <option value="">Select destination...</option>
                    {activeAccounts
                      .filter((a) => a.id !== accountId)
                      .map((acc) => (
                        <option key={acc.id} value={acc.id}>
                          {acc.name} ({acc.currency})
                        </option>
                      ))}
                  </select>
                </div>
              )}
            </div>
          )}

          {/* Cross-currency rate & transfer fee */}
          {isTransfer && (
            <div className="rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] p-3.5 space-y-3">
              <div className="flex items-center gap-2 text-xs font-medium text-[#58655D] dark:text-[#98A89E]">
                <ArrowLeftRight className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Balanced double-entry transfer (not counted as income or expense)</span>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {isCrossCurrencyTransfer && destAcc && (
                  <div>
                    <label htmlFor="tx-fx-rate" className="block text-xs font-medium mb-1">
                      Exchange Rate (1 {activeCurrency} = ? {destAcc.currency}) *
                    </label>
                    <input
                      id="tx-fx-rate"
                      type="text"
                      inputMode="decimal"
                      value={exchangeRateInput}
                      onChange={(e) => setExchangeRateInput(e.target.value)}
                      className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] text-sm font-mono-num"
                    />
                    {convertedPreviewStr && (
                      <p className="mt-1 text-xs text-[#168653] dark:text-[#34D399] font-mono-num">
                        Destination credit: {convertedPreviewStr}
                      </p>
                    )}
                  </div>
                )}
                <div>
                  <label htmlFor="tx-fee" className="block text-xs font-medium mb-1">
                    Explicit Transfer Fee ({activeCurrency})
                  </label>
                  <input
                    id="tx-fee"
                    type="text"
                    inputMode="decimal"
                    value={feeInput}
                    onChange={(e) => setFeeInput(e.target.value)}
                    className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] text-sm font-mono-num"
                  />
                </div>
              </div>
            </div>
          )}

          {/* Description */}
          <div>
            <label htmlFor="tx-description" className="block text-xs font-medium mb-1.5">
              Description *
            </label>
            <input
              id="tx-description"
              type="text"
              required
              maxLength={200}
              placeholder="e.g. Daily retail counter sales, Shop rent October, Supplier settlement"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
            />
          </div>

          {/* Date, Time, Category, Party */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label htmlFor="tx-date" className="block text-xs font-medium mb-1.5">
                Date *
              </label>
              <input
                id="tx-date"
                type="date"
                required
                value={date}
                onChange={(e) => setDate(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              />
            </div>

            <div>
              <label htmlFor="tx-time" className="block text-xs font-medium mb-1.5">
                Time
              </label>
              <input
                id="tx-time"
                type="time"
                value={time}
                onChange={(e) => setTime(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              />
            </div>

            {!isTransfer && type !== 'opening_balance' && (
              <div>
                <label htmlFor="tx-category" className="block text-xs font-medium mb-1.5">
                  Category
                </label>
                <select
                  id="tx-category"
                  value={categoryId}
                  onChange={(e) => setCategoryId(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                >
                  <option value="">Uncategorized</option>
                  {filteredCategories.map((cat) => (
                    <option key={cat.id} value={cat.id}>
                      {cat.name}
                    </option>
                  ))}
                </select>
              </div>
            )}

            <div>
              <label htmlFor="tx-party" className="block text-xs font-medium mb-1.5">
                Customer / Supplier Party {requiresParty ? '*' : '(Optional)'}
              </label>
              <select
                id="tx-party"
                required={requiresParty}
                value={partyId}
                onChange={(e) => setPartyId(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
              >
                <option value="">None</option>
                {activeParties.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.partyType})
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Reference, Due Date, Notes, Receipt */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label htmlFor="tx-ref" className="block text-xs font-medium mb-1.5">
                Reference / Invoice #
              </label>
              <input
                id="tx-ref"
                type="text"
                maxLength={64}
                placeholder="INV-2026-001"
                value={referenceNumber}
                onChange={(e) => setReferenceNumber(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
              />
            </div>

            {requiresParty && (
              <div>
                <label htmlFor="tx-due-date" className="block text-xs font-medium mb-1.5">
                  Payment Due Date (Reminder)
                </label>
                <input
                  id="tx-due-date"
                  type="date"
                  value={dueDate}
                  onChange={(e) => setDueDate(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
                />
              </div>
            )}
          </div>

          <div>
            <label htmlFor="tx-notes" className="block text-xs font-medium mb-1.5">
              Notes
            </label>
            <textarea
              id="tx-notes"
              rows={2}
              maxLength={600}
              placeholder="Optional supporting remarks..."
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              className="w-full px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
            />
          </div>

          {/* Receipt File Validation */}
          <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
            <label className="inline-flex items-center gap-2 text-xs font-medium text-[#126B45] dark:text-[#34D399] cursor-pointer min-h-[36px]">
              <Paperclip className="w-4 h-4" aria-hidden="true" />
              <span>{attachmentName ? `Attached: ${attachmentName}` : 'Attach Receipt (Image/PDF <= 5MB)'}</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                onChange={handleFileAttachment}
                className="sr-only"
              />
            </label>
            {attachmentName && (
              <button
                type="button"
                onClick={() => {
                  setAttachmentName('');
                  setAttachmentMime('');
                }}
                className="text-xs text-[#D94848] hover:underline"
              >
                Remove receipt
              </button>
            )}
          </div>

          {editingTransaction && (
            <div>
              <label htmlFor="tx-edit-reason" className="block text-xs font-medium mb-1.5">
                Audit Reason for Edit *
              </label>
              <input
                id="tx-edit-reason"
                type="text"
                required
                maxLength={240}
                placeholder="Explain why this record is being updated..."
                value={editReason}
                onChange={(e) => setEditReason(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
              />
            </div>
          )}

          {/* Footer Actions */}
          <div className="pt-3 border-t border-[#E2E8E3] dark:border-[#28362D] flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="min-h-[44px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-sm font-medium hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] transition-colors cursor-pointer"
            >
              {translate(locale, 'cancel')}
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="min-h-[44px] px-6 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-medium transition-colors cursor-pointer disabled:opacity-60"
            >
              {submitting ? 'Saving...' : translate(locale, 'saveEntry')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
