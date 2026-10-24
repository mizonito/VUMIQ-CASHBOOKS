import React, { useState } from 'react';
import { X, Edit3, Copy, RotateCcw, Trash2, History } from 'lucide-react';
import { Account, AuditEvent, Category, LocaleCode, MemberRole, Party, Transaction } from '../types/cashbook';
import { ROLE_PERMISSIONS } from '../engine/accounting';
import { formatMinorAmount } from '../engine/money';
import { formatLocaleDate, getTransactionTypeLabel, translate } from '../i18n/translations';

interface TransactionDetailDrawerProps {
  transaction: Transaction | null;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  auditEvents: AuditEvent[];
  role: MemberRole;
  locale: LocaleCode;
  onClose: () => void;
  onEdit: (tx: Transaction) => void;
  onDuplicate: (tx: Transaction) => void;
  onVoid: (tx: Transaction, reason: string) => Promise<void>;
  onDelete: (tx: Transaction, reason: string) => Promise<void>;
}

export const TransactionDetailDrawer: React.FC<TransactionDetailDrawerProps> = ({
  transaction,
  accounts,
  categories,
  parties,
  auditEvents,
  role,
  locale,
  onClose,
  onEdit,
  onDuplicate,
  onVoid,
  onDelete,
}) => {
  const [confirmMode, setConfirmMode] = useState<'none' | 'void' | 'delete'>('none');
  const [reason, setReason] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [busy, setBusy] = useState(false);

  if (!transaction) return null;

  const perms = ROLE_PERMISSIONS[role];
  const srcAcc = accounts.find((a) => a.id === transaction.accountId);
  const dstAcc = accounts.find((a) => a.id === transaction.toAccountId);
  const cat = categories.find((c) => c.id === transaction.categoryId);
  const party = parties.find((p) => p.id === transaction.partyId);

  const txAudits = auditEvents.filter((e) => e.entityId === transaction.id);
  const isVoided = transaction.status === 'voided';

  const handleConfirmAction = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!reason.trim()) {
      setErrorMsg('Please enter an explicit reason for the audit trail.');
      return;
    }
    setBusy(true);
    setErrorMsg('');
    try {
      if (confirmMode === 'void') {
        await onVoid(transaction, reason.trim());
      } else if (confirmMode === 'delete') {
        await onDelete(transaction, reason.trim());
      }
      setConfirmMode('none');
      setReason('');
      onClose();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Operation failed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="tx-detail-title"
      className="fixed inset-0 z-50 flex justify-end bg-black/50 backdrop-blur-xs"
    >
      <div className="w-full max-w-lg h-full bg-white dark:bg-[#19221C] border-l border-[#E2E8E3] dark:border-[#28362D] flex flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#E2E8E3] dark:border-[#28362D]">
          <div>
            <p className="text-xs text-[#58655D] dark:text-[#98A89E]">
              {getTransactionTypeLabel(locale, transaction.type)} · {isVoided ? 'Voided' : 'Posted'}
            </p>
            <h2 id="tx-detail-title" className="font-display text-lg font-bold text-[#17231D] dark:text-[#ECF3EE] truncate max-w-xs">
              {transaction.description}
            </h2>
          </div>
          <button
            type="button"
            aria-label="Close transaction details"
            onClick={onClose}
            className="min-h-[40px] min-w-[40px] rounded-lg flex items-center justify-center text-[#58655D] hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5 space-y-6">
          {/* Primary Amount */}
          <div className="pb-5 border-b border-[#E2E8E3] dark:border-[#28362D]">
            <p className="text-xs text-[#58655D] dark:text-[#98A89E]">Recorded Amount</p>
            <p
              className={`mt-1 text-3xl font-bold font-mono-num ${
                isVoided
                  ? 'line-through text-[#58655D]'
                  : ['income', 'credit_sale', 'receivable_payment', 'refund_expense'].includes(transaction.type)
                  ? 'text-[#168653] dark:text-[#34D399]'
                  : ['expense', 'credit_purchase', 'payable_payment', 'refund_income'].includes(transaction.type)
                  ? 'text-[#D94848] dark:text-[#F87171]'
                  : 'text-[#17231D] dark:text-[#ECF3EE]'
              }`}
            >
              {formatMinorAmount(transaction.amountMinor, transaction.currency, locale)}
            </p>
            {transaction.type === 'transfer' &&
              transaction.toCurrency &&
              transaction.toCurrency !== transaction.currency &&
              transaction.convertedAmountMinor > 0 && (
                <p className="mt-1 text-xs font-mono-num text-[#58655D] dark:text-[#98A89E]">
                  Converted credit: {formatMinorAmount(transaction.convertedAmountMinor, transaction.toCurrency as any, locale)} (Rate:{' '}
                  {(transaction.exchangeRateScaled / 1_000_000).toFixed(4)})
                </p>
              )}
            {isVoided && transaction.voidReason && (
              <p className="mt-2 text-xs text-[#D94848] dark:text-[#F87171]">
                Void reason: {transaction.voidReason}
              </p>
            )}
          </div>

          {/* Metadata Table */}
          <dl className="grid grid-cols-2 gap-y-3.5 gap-x-4 text-sm">
            <div>
              <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Date & Time</dt>
              <dd className="font-medium font-mono-num mt-0.5">
                {formatLocaleDate(transaction.date, locale)} · {transaction.time}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Account</dt>
              <dd className="font-medium mt-0.5">
                {transaction.type === 'transfer'
                  ? `${srcAcc?.name || 'Source'} → ${dstAcc?.name || 'Destination'}`
                  : srcAcc?.name || 'Non-cash subledger'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Category</dt>
              <dd className="font-medium mt-0.5">
                {cat?.name || transaction.categoryLabelSnapshot || '—'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Party</dt>
              <dd className="font-medium mt-0.5">
                {party?.name || transaction.partyNameSnapshot || '—'}
              </dd>
            </div>
            {transaction.referenceNumber && (
              <div>
                <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Reference #</dt>
                <dd className="font-medium font-mono-num mt-0.5">{transaction.referenceNumber}</dd>
              </div>
            )}
            {transaction.dueDate && (
              <div>
                <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Due Date</dt>
                <dd className="font-medium font-mono-num mt-0.5">{formatLocaleDate(transaction.dueDate, locale)}</dd>
              </div>
            )}
            {transaction.attachmentName && (
              <div className="col-span-2">
                <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Receipt Attachment</dt>
                <dd className="font-medium mt-0.5">{transaction.attachmentName}</dd>
              </div>
            )}
            {transaction.notes && (
              <div className="col-span-2">
                <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Notes</dt>
                <dd className="mt-0.5 text-[#17231D] dark:text-[#ECF3EE] whitespace-pre-wrap">{transaction.notes}</dd>
              </div>
            )}
            <div className="col-span-2">
              <dt className="text-xs text-[#58655D] dark:text-[#98A89E]">Transaction ID</dt>
              <dd className="mt-0.5 text-xs font-mono-num text-[#58655D] dark:text-[#98A89E]">{transaction.id}</dd>
            </div>
          </dl>

          {/* Destructive Confirmation Box */}
          {confirmMode !== 'none' && (
            <form
              onSubmit={handleConfirmAction}
              className="rounded-xl border border-[#D94848]/50 bg-[#D94848]/8 p-4 space-y-3"
            >
              <h3 className="text-sm font-semibold text-[#D94848] dark:text-[#F87171]">
                {confirmMode === 'void'
                  ? 'Confirm Void / Reversal'
                  : 'Confirm Permanent Deletion'}
              </h3>
              <p className="text-xs text-[#58655D] dark:text-[#98A89E]">
                {confirmMode === 'void'
                  ? 'Voiding reverses this entry’s effect on all balances and reports while keeping the record locked in the register.'
                  : 'Deleting removes this record from the active register and writes a permanent audit event.'}
              </p>
              {errorMsg && <p className="text-xs text-[#D94848] font-medium">{errorMsg}</p>}
              <input
                type="text"
                required
                maxLength={240}
                placeholder="Enter required reason for audit log..."
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                className="w-full min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#101713] text-sm"
              />
              <div className="flex items-center justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setConfirmMode('none');
                    setErrorMsg('');
                  }}
                  className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium cursor-pointer"
                >
                  {translate(locale, 'cancel')}
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="min-h-[38px] px-4 py-1.5 rounded-lg bg-[#D94848] text-white text-xs font-medium cursor-pointer disabled:opacity-60"
                >
                  {busy ? 'Processing...' : translate(locale, 'confirm')}
                </button>
              </div>
            </form>
          )}

          {/* Audit Trail */}
          <div className="pt-4 border-t border-[#E2E8E3] dark:border-[#28362D]">
            <div className="flex items-center gap-2 mb-3">
              <History className="w-4 h-4 text-[#58655D] dark:text-[#98A89E]" aria-hidden="true" />
              <h3 className="text-xs font-semibold tracking-wide text-[#58655D] dark:text-[#98A89E]">
                {translate(locale, 'auditTrail')} ({txAudits.length})
              </h3>
            </div>
            <div className="space-y-3">
              {txAudits.map((evt) => (
                <div key={evt.id} className="text-xs border-b border-[#E2E8E3]/70 dark:border-[#28362D] pb-2.5 last:border-0">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-semibold text-[#17231D] dark:text-[#ECF3EE] capitalize">{evt.action}</span>
                    <span className="font-mono-num text-[#58655D] dark:text-[#98A89E]">
                      {evt.createdAt.slice(0, 16).replace('T', ' ')}
                    </span>
                  </div>
                  <p className="text-[#58655D] dark:text-[#98A89E] mt-0.5">
                    Actor: {evt.actorName}
                  </p>
                  {evt.previousSummary && (
                    <p className="text-[#58655D] dark:text-[#98A89E] mt-0.5">Before: {evt.previousSummary}</p>
                  )}
                  {evt.newSummary && (
                    <p className="text-[#17231D] dark:text-[#ECF3EE] mt-0.5">After: {evt.newSummary}</p>
                  )}
                  {evt.reason && <p className="italic text-[#58655D] dark:text-[#98A89E] mt-0.5">Reason: {evt.reason}</p>}
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Action Footer */}
        <div className="px-5 py-3.5 border-t border-[#E2E8E3] dark:border-[#28362D] flex flex-wrap items-center justify-between gap-2 bg-[#F6F8F5] dark:bg-[#101713]">
          <div className="flex items-center gap-2">
            {perms.canEditTransactions && !isVoided && (
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onEdit(transaction);
                }}
                className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] text-xs font-medium flex items-center gap-1.5 hover:border-[#126B45] cursor-pointer"
              >
                <Edit3 className="w-3.5 h-3.5" aria-hidden="true" />
                <span>{translate(locale, 'edit')}</span>
              </button>
            )}
            {perms.canRecordTransactions && (
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onDuplicate(transaction);
                }}
                className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] text-xs font-medium flex items-center gap-1.5 hover:border-[#126B45] cursor-pointer"
              >
                <Copy className="w-3.5 h-3.5" aria-hidden="true" />
                <span>{translate(locale, 'duplicate')}</span>
              </button>
            )}
          </div>

          <div className="flex items-center gap-2">
            {perms.canVoidTransactions && !isVoided && (
              <button
                type="button"
                onClick={() => setConfirmMode('void')}
                className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#C88719]/50 text-[#C88719] dark:text-[#FBBF24] text-xs font-medium flex items-center gap-1.5 cursor-pointer"
              >
                <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
                <span>{translate(locale, 'voidReverse')}</span>
              </button>
            )}
            {perms.canDeleteTransactions && (
              <button
                type="button"
                onClick={() => setConfirmMode('delete')}
                className="min-h-[40px] px-3 py-1.5 rounded-lg border border-[#D94848]/50 text-[#D94848] dark:text-[#F87171] text-xs font-medium flex items-center gap-1.5 cursor-pointer"
              >
                <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />
                <span>{translate(locale, 'deleteRecord')}</span>
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
