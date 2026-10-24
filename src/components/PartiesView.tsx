import React, { useMemo, useState } from 'react';
import { Plus, Search, X, HandCoins } from 'lucide-react';
import {
  CurrencyCode,
  LocaleCode,
  MemberRole,
  Party,
  PartyType,
  SUPPORTED_CURRENCIES,
  Transaction,
  TransactionType,
} from '../types/cashbook';
import { computePartyBalances, ROLE_PERMISSIONS } from '../engine/accounting';
import { formatMinorAmount, minorToInputString, parseAmountToMinor } from '../engine/money';
import { formatLocaleDate, getTransactionTypeLabel, translate } from '../i18n/translations';

interface PartiesViewProps {
  locale: LocaleCode;
  role: MemberRole;
  parties: Party[];
  transactions: Transaction[];
  defaultCurrency: CurrencyCode;
  isAddModalOpenExternal?: boolean;
  onCloseExternalAddModal?: () => void;
  onSaveParty: (data: {
    existingParty?: Party;
    name: string;
    partyType: PartyType;
    phone: string;
    address: string;
    notes: string;
    currency: CurrencyCode;
    openingReceivableMinor: number;
    openingPayableMinor: number;
    dueDate: string;
  }) => Promise<void>;
  onRecordPartyWorkflow: (type: TransactionType, partyId: string) => void;
}

export const PartiesView: React.FC<PartiesViewProps> = ({
  locale,
  role,
  parties,
  transactions,
  defaultCurrency,
  isAddModalOpenExternal = false,
  onCloseExternalAddModal,
  onSaveParty,
  onRecordPartyWorkflow,
}) => {
  const canManage = ROLE_PERMISSIONS[role].canManageParties;
  const canRecord = ROLE_PERMISSIONS[role].canRecordTransactions;

  const [filterType, setFilterType] = useState<'all' | PartyType>('all');
  const [search, setSearch] = useState('');
  const [selectedPartyId, setSelectedPartyId] = useState<string>('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingParty, setEditingParty] = useState<Party | null>(null);

  // Form state
  const [name, setName] = useState('');
  const [partyType, setPartyType] = useState<PartyType>('customer');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [notes, setNotes] = useState('');
  const [currency, setCurrency] = useState<CurrencyCode>(defaultCurrency);
  const [openRecStr, setOpenRecStr] = useState('0.00');
  const [openPayStr, setOpenPayStr] = useState('0.00');
  const [dueDate, setDueDate] = useState('');
  const [formErr, setFormErr] = useState('');
  const [saving, setSaving] = useState(false);

  const isDialogShown = modalOpen || isAddModalOpenExternal;

  const openCreateModal = () => {
    setEditingParty(null);
    setName('');
    setPartyType('customer');
    setPhone('');
    setAddress('');
    setNotes('');
    setCurrency(defaultCurrency);
    setOpenRecStr('0.00');
    setOpenPayStr('0.00');
    setDueDate('');
    setFormErr('');
    setModalOpen(true);
  };

  const openEditModal = (party: Party) => {
    setEditingParty(party);
    setName(party.name);
    setPartyType(party.partyType);
    setPhone(party.phone);
    setAddress(party.address);
    setNotes(party.notes);
    setCurrency(party.currency);
    setOpenRecStr(minorToInputString(party.openingReceivableMinor, party.currency));
    setOpenPayStr(minorToInputString(party.openingPayableMinor, party.currency));
    setDueDate(party.dueDate);
    setFormErr('');
    setModalOpen(true);
  };

  const closeDialog = () => {
    setModalOpen(false);
    setEditingParty(null);
    onCloseExternalAddModal?.();
  };

  const partyBalances = useMemo(() => computePartyBalances(parties, transactions), [parties, transactions]);

  const filteredParties = useMemo(() => {
    const q = search.trim().toLowerCase();
    return parties
      .filter((p) => p.status === 'active')
      .filter((p) => (filterType === 'all' ? true : p.partyType === filterType))
      .filter((p) => (!q ? true : `${p.name} ${p.phone} ${p.address}`.toLowerCase().includes(q)));
  }, [parties, filterType, search]);

  const activeParty = parties.find((p) => p.id === selectedPartyId) || filteredParties[0] || null;
  const activePartyTx = useMemo(
    () =>
      activeParty
        ? transactions
            .filter((t) => t.status === 'posted' && t.partyId === activeParty.id)
            .sort((a, b) => b.date.localeCompare(a.date))
        : [],
    [activeParty, transactions]
  );

  const handlePartySubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormErr('');
    if (!name.trim()) {
      setFormErr('Party name is required.');
      return;
    }

    const recParsed = parseAmountToMinor(openRecStr || '0', currency, { allowZero: true });
    if (!recParsed.ok) {
      setFormErr(`Opening receivable: ${recParsed.message}`);
      return;
    }
    const payParsed = parseAmountToMinor(openPayStr || '0', currency, { allowZero: true });
    if (!payParsed.ok) {
      setFormErr(`Opening payable: ${payParsed.message}`);
      return;
    }

    setSaving(true);
    try {
      await onSaveParty({
        existingParty: editingParty || undefined,
        name: name.trim(),
        partyType,
        phone: phone.trim(),
        address: address.trim(),
        notes: notes.trim(),
        currency,
        openingReceivableMinor: recParsed.minorUnits,
        openingPayableMinor: payParsed.minorUnits,
        dueDate,
      });
      closeDialog();
    } catch (err) {
      setFormErr(err instanceof Error ? err.message : 'Unable to save party.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
            {translate(locale, 'navParties')}
          </h1>
          <p className="text-xs text-[#58655D] dark:text-[#98A89E] mt-0.5">
            Customers, suppliers, receivables, and payables subledger (settlements never double-count sales or expenses)
          </p>
        </div>

        {canManage && (
          <button
            type="button"
            onClick={openCreateModal}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white text-sm font-medium flex items-center gap-2 cursor-pointer whitespace-nowrap"
          >
            <Plus className="w-4 h-4" aria-hidden="true" />
            <span>{translate(locale, 'actionAddParty')}</span>
          </button>
        )}
      </div>

      {/* Filter & Search Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="inline-flex items-center gap-1 p-1 rounded-xl bg-[#EFF3EE] dark:bg-[#212D25] w-fit">
          {(
            [
              { id: 'all', label: 'All Parties' },
              { id: 'customer', label: 'Customers' },
              { id: 'supplier', label: 'Suppliers' },
              { id: 'other', label: 'Other' },
            ] as const
          ).map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setFilterType(tab.id)}
              className={`min-h-[36px] px-3 py-1.5 text-xs font-medium rounded-lg transition-colors cursor-pointer whitespace-nowrap ${
                filterType === tab.id
                  ? 'bg-white dark:bg-[#19221C] text-[#17231D] dark:text-white shadow-xs'
                  : 'text-[#58655D] dark:text-[#98A89E]'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className="relative w-full sm:w-72">
          <Search className="w-4 h-4 text-[#58655D] absolute left-3 top-1/2 -translate-y-1/2" aria-hidden="true" />
          <input
            type="search"
            aria-label="Search parties"
            placeholder="Search party name or phone..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full min-h-[40px] pl-9 pr-3 py-1.5 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C] text-xs"
          />
        </div>
      </div>

      {/* Master-Detail Grid */}
      {filteredParties.length === 0 ? (
        <div className="rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] p-10 text-center space-y-3">
          <p className="text-sm text-[#58655D] dark:text-[#98A89E]">
            No customers or suppliers found. Add a party to track credit sales, supplier bills, and settlements.
          </p>
          {canManage && (
            <button
              type="button"
              onClick={openCreateModal}
              className="min-h-[42px] px-4 py-2 rounded-xl bg-[#126B45] text-white text-xs font-medium cursor-pointer"
            >
              Add First Party
            </button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {/* Left Party List */}
          <div className="lg:col-span-5 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] divide-y divide-[#E2E8E3] dark:divide-[#28362D] overflow-hidden">
            {filteredParties.map((p) => {
              const bal = partyBalances[p.id];
              const recEntries = Object.entries(bal?.receivableByCurrency || {}).filter(([, v]) => (v || 0) > 0);
              const payEntries = Object.entries(bal?.payableByCurrency || {}).filter(([, v]) => (v || 0) > 0);
              const isSelected = activeParty?.id === p.id;

              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => setSelectedPartyId(p.id)}
                  className={`w-full min-h-[64px] px-5 py-3.5 text-left flex items-center justify-between gap-3 transition-colors cursor-pointer ${
                    isSelected ? 'bg-[#EFF3EE] dark:bg-[#212D25]' : 'hover:bg-[#F6F8F5] dark:hover:bg-[#212D25]/50'
                  }`}
                >
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE] truncate">
                      {p.name}
                    </p>
                    <p className="text-xs text-[#58655D] dark:text-[#98A89E] capitalize mt-0.5">
                      {p.partyType}
                      {p.phone ? ` · ${p.phone}` : ''}
                    </p>
                  </div>

                  <div className="text-right text-xs font-mono-num shrink-0">
                    {recEntries.length > 0 ? (
                      recEntries.map(([ccy, v]) => (
                        <p key={ccy} className="text-[#168653] dark:text-[#34D399] font-semibold">
                          To receive: {formatMinorAmount(v || 0, ccy as CurrencyCode, locale)}
                        </p>
                      ))
                    ) : payEntries.length > 0 ? (
                      payEntries.map(([ccy, v]) => (
                        <p key={ccy} className="text-[#D94848] dark:text-[#F87171] font-semibold">
                          To pay: {formatMinorAmount(v || 0, ccy as CurrencyCode, locale)}
                        </p>
                      ))
                    ) : (
                      <span className="text-[#58655D] dark:text-[#98A89E]">Settled</span>
                    )}
                  </div>
                </button>
              );
            })}
          </div>

          {/* Right Party Statement & Quick Settlement Panel */}
          {activeParty && (
            <div className="lg:col-span-7 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] overflow-hidden">
              <div className="p-6 border-b border-[#E2E8E3] dark:border-[#28362D] space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h2 className="font-display text-lg font-bold text-[#17231D] dark:text-[#ECF3EE]">
                      {activeParty.name}
                    </h2>
                    <p className="text-xs text-[#58655D] dark:text-[#98A89E] mt-0.5 capitalize">
                      {activeParty.partyType}
                      {activeParty.phone ? ` · ${activeParty.phone}` : ''}
                      {activeParty.address ? ` · ${activeParty.address}` : ''}
                      {activeParty.dueDate ? ` · Due ${formatLocaleDate(activeParty.dueDate, locale)}` : ''}
                    </p>
                  </div>

                  {canManage && (
                    <button
                      type="button"
                      onClick={() => openEditModal(activeParty)}
                      className="min-h-[36px] px-3 py-1.5 rounded-lg border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium cursor-pointer"
                    >
                      {translate(locale, 'edit')}
                    </button>
                  )}
                </div>

                {/* Balances */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
                  <div className="p-3.5 rounded-xl bg-[#F6F8F5] dark:bg-[#101713]">
                    <p className="text-xs text-[#58655D] dark:text-[#98A89E]">{translate(locale, 'moneyToReceive')}</p>
                    <p className="mt-1 text-lg font-bold font-mono-num text-[#168653] dark:text-[#34D399]">
                      {formatMinorAmount(
                        partyBalances[activeParty.id]?.receivableByCurrency[activeParty.currency] || 0,
                        activeParty.currency,
                        locale
                      )}
                    </p>
                  </div>
                  <div className="p-3.5 rounded-xl bg-[#F6F8F5] dark:bg-[#101713]">
                    <p className="text-xs text-[#58655D] dark:text-[#98A89E]">{translate(locale, 'moneyToPay')}</p>
                    <p className="mt-1 text-lg font-bold font-mono-num text-[#D94848] dark:text-[#F87171]">
                      {formatMinorAmount(
                        partyBalances[activeParty.id]?.payableByCurrency[activeParty.currency] || 0,
                        activeParty.currency,
                        locale
                      )}
                    </p>
                  </div>
                </div>

                {/* Credit & Settlement Workflow Buttons */}
                {canRecord && (
                  <div className="flex flex-wrap items-center gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => onRecordPartyWorkflow('credit_sale', activeParty.id)}
                      className="min-h-[38px] px-3 py-1.5 rounded-lg bg-[#168653] text-white text-xs font-medium cursor-pointer"
                    >
                      + Credit Sale (Receivable)
                    </button>
                    <button
                      type="button"
                      onClick={() => onRecordPartyWorkflow('receivable_payment', activeParty.id)}
                      className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#168653] text-[#168653] dark:text-[#34D399] text-xs font-medium flex items-center gap-1.5 cursor-pointer"
                    >
                      <HandCoins className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Receive Settlement</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => onRecordPartyWorkflow('credit_purchase', activeParty.id)}
                      className="min-h-[38px] px-3 py-1.5 rounded-lg bg-[#D94848] text-white text-xs font-medium cursor-pointer"
                    >
                      + Credit Purchase (Payable)
                    </button>
                    <button
                      type="button"
                      onClick={() => onRecordPartyWorkflow('payable_payment', activeParty.id)}
                      className="min-h-[38px] px-3 py-1.5 rounded-lg border border-[#D94848] text-[#D94848] dark:text-[#F87171] text-xs font-medium cursor-pointer"
                    >
                      Pay Supplier Debt
                    </button>
                  </div>
                )}
              </div>

              {/* Statement History */}
              <div className="divide-y divide-[#E2E8E3] dark:divide-[#28362D]">
                {activePartyTx.length === 0 ? (
                  <p className="p-6 text-xs text-[#58655D] dark:text-[#98A89E]">
                    No transactions recorded with {activeParty.name} yet.
                  </p>
                ) : (
                  activePartyTx.map((t) => (
                    <div key={t.id} className="px-6 py-3 flex items-center justify-between gap-4 text-xs">
                      <div>
                        <p className="font-medium text-[#17231D] dark:text-[#ECF3EE]">{t.description}</p>
                        <p className="text-[#58655D] dark:text-[#98A89E] mt-0.5">
                          {formatLocaleDate(t.date, locale)} · {getTransactionTypeLabel(locale, t.type)}
                        </p>
                      </div>
                      <span className="font-semibold font-mono-num">
                        {formatMinorAmount(t.amountMinor, t.currency, locale)}
                      </span>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Party Modal */}
      {isDialogShown && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="party-modal-title"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 backdrop-blur-xs p-4"
        >
          <div className="w-full max-w-lg rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] shadow-xl overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#E2E8E3] dark:border-[#28362D]">
              <h2 id="party-modal-title" className="font-display text-lg font-bold">
                {editingParty ? 'Edit Counterparty' : 'Add Customer or Supplier'}
              </h2>
              <button
                type="button"
                aria-label="Close party modal"
                onClick={closeDialog}
                className="min-h-[38px] min-w-[38px] rounded-lg flex items-center justify-center text-[#58655D] cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handlePartySubmit} className="p-5 space-y-4">
              {formErr && (
                <div className="rounded-xl bg-[#D94848]/10 border border-[#D94848]/40 px-4 py-2.5 text-xs text-[#D94848]">
                  {formErr}
                </div>
              )}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="sm:col-span-2">
                  <label htmlFor="pty-name" className="block text-xs font-medium mb-1">
                    Party Name *
                  </label>
                  <input
                    id="pty-name"
                    type="text"
                    required
                    maxLength={100}
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g. Café Lumumba / Metro Wholesale"
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
                  />
                </div>

                <div>
                  <label htmlFor="pty-type" className="block text-xs font-medium mb-1">
                    Party Type *
                  </label>
                  <select
                    id="pty-type"
                    value={partyType}
                    onChange={(e) => setPartyType(e.target.value as PartyType)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
                  >
                    <option value="customer">Customer</option>
                    <option value="supplier">Supplier</option>
                    <option value="other">Other Counterparty</option>
                  </select>
                </div>

                <div>
                  <label htmlFor="pty-ccy" className="block text-xs font-medium mb-1">
                    Subledger Currency *
                  </label>
                  <select
                    id="pty-ccy"
                    value={currency}
                    onChange={(e) => setCurrency(e.target.value as CurrencyCode)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
                  >
                    {SUPPORTED_CURRENCIES.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="pty-phone" className="block text-xs font-medium mb-1">
                    Phone Number
                  </label>
                  <input
                    id="pty-phone"
                    type="tel"
                    maxLength={40}
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
                  />
                </div>

                <div>
                  <label htmlFor="pty-due" className="block text-xs font-medium mb-1">
                    Follow-up Due Date
                  </label>
                  <input
                    id="pty-due"
                    type="date"
                    value={dueDate}
                    onChange={(e) => setDueDate(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
                  />
                </div>

                <div>
                  <label htmlFor="pty-open-rec" className="block text-xs font-medium mb-1">
                    Opening Receivable ({currency})
                  </label>
                  <input
                    id="pty-open-rec"
                    type="text"
                    inputMode="decimal"
                    value={openRecStr}
                    onChange={(e) => setOpenRecStr(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
                  />
                </div>

                <div>
                  <label htmlFor="pty-open-pay" className="block text-xs font-medium mb-1">
                    Opening Payable ({currency})
                  </label>
                  <input
                    id="pty-open-pay"
                    type="text"
                    inputMode="decimal"
                    value={openPayStr}
                    onChange={(e) => setOpenPayStr(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num"
                  />
                </div>

                <div className="sm:col-span-2">
                  <label htmlFor="pty-addr" className="block text-xs font-medium mb-1">
                    Address
                  </label>
                  <input
                    id="pty-addr"
                    type="text"
                    maxLength={240}
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
                  />
                </div>

                <div className="sm:col-span-2">
                  <label htmlFor="pty-notes" className="block text-xs font-medium mb-1">
                    Notes
                  </label>
                  <input
                    id="pty-notes"
                    type="text"
                    maxLength={500}
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm"
                  />
                </div>
              </div>

              <div className="pt-3 border-t border-[#E2E8E3] dark:border-[#28362D] flex justify-end gap-3">
                <button
                  type="button"
                  onClick={closeDialog}
                  className="min-h-[42px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-xs font-medium cursor-pointer"
                >
                  {translate(locale, 'cancel')}
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="min-h-[42px] px-5 py-2 rounded-xl bg-[#126B45] text-white text-xs font-medium cursor-pointer disabled:opacity-60"
                >
                  {saving ? 'Saving...' : 'Save Party'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
