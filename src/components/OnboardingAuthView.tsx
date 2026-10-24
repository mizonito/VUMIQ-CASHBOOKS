import React, { useState } from 'react';
import { ShieldCheck, HardDrive, Globe, ArrowRight, Building2, Wallet } from 'lucide-react';
import { Account, CurrencyCode, LocaleCode, SUPPORTED_CURRENCIES } from '../types/cashbook';
import { CURRENCY_DEFINITIONS, parseAmountToMinor } from '../engine/money';
import { translate } from '../i18n/translations';
import { BrandMark } from './BrandMark';

interface OnboardingAuthViewProps {
  locale: LocaleCode;
  onChangeLocale: (locale: LocaleCode) => void;
  isAuthenticatedCloudUser: boolean;
  cloudUserDisplayName?: string;
  onSignInGoogle: () => Promise<void>;
  onSelectGuestMode: () => void;
  showBusinessSetupForm: boolean;
  onCreateBusinessWorkspace: (data: {
    businessName: string;
    defaultCurrency: CurrencyCode;
    address: string;
    phone: string;
    initialAccountName: string;
    initialAccountType: Account['accountType'];
    openingBalanceMinor: number;
  }) => Promise<void>;
  onCancelGuestMode?: () => void;
}

export const OnboardingAuthView: React.FC<OnboardingAuthViewProps> = ({
  locale,
  onChangeLocale,
  isAuthenticatedCloudUser,
  cloudUserDisplayName,
  onSignInGoogle,
  onSelectGuestMode,
  showBusinessSetupForm,
  onCreateBusinessWorkspace,
  onCancelGuestMode,
}) => {
  const [guestConfirmedDisclosure, setGuestConfirmedDisclosure] = useState(false);
  const [businessName, setBusinessName] = useState('');
  const [defaultCurrency, setDefaultCurrency] = useState<CurrencyCode>('USD');
  const [address, setAddress] = useState('');
  const [phone, setPhone] = useState('');
  const [initialAccountName, setInitialAccountName] = useState('Cash on Hand');
  const [initialAccountType, setInitialAccountType] = useState<Account['accountType']>('cash');
  const [openingBalanceStr, setOpeningBalanceStr] = useState('0.00');
  const [errorMsg, setErrorMsg] = useState('');
  const [busy, setBusy] = useState(false);

  const handleGoogleClick = async () => {
    setErrorMsg('');
    setBusy(true);
    try {
      await onSignInGoogle();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Google Sign-In could not be completed.');
    } finally {
      setBusy(false);
    }
  };

  const handleSetupSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg('');

    if (!businessName.trim()) {
      setErrorMsg('Please enter your business or personal ledger name.');
      return;
    }
    if (!initialAccountName.trim()) {
      setErrorMsg('Please enter a name for your first cash or bank account.');
      return;
    }

    const parsedOpening = parseAmountToMinor(openingBalanceStr || '0', defaultCurrency, { allowZero: true });
    if (!parsedOpening.ok) {
      setErrorMsg(`Opening balance: ${parsedOpening.message}`);
      return;
    }

    setBusy(true);
    try {
      await onCreateBusinessWorkspace({
        businessName: businessName.trim(),
        defaultCurrency,
        address: address.trim(),
        phone: phone.trim(),
        initialAccountName: initialAccountName.trim(),
        initialAccountType,
        openingBalanceMinor: parsedOpening.minorUnits,
      });
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to create workspace.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F6F8F5] dark:bg-[#101713] text-[#17231D] dark:text-[#ECF3EE] flex flex-col">
      {/* Top Bar */}
      <header className="flex items-center justify-between gap-8 px-6 py-4 border-b border-[#E2E8E3] dark:border-[#28362D] bg-white dark:bg-[#19221C]">
        <BrandMark size="md" subtitleText={translate(locale, 'brandSubtitle')} />
        <div className="flex items-center gap-2 shrink-0">
          <Globe className="w-4 h-4 text-[#58655D] dark:text-[#98A89E]" aria-hidden="true" />
          <div className="inline-flex rounded-lg bg-[#EFF3EE] dark:bg-[#212D25] p-0.5">
            <button
              type="button"
              onClick={() => onChangeLocale('en')}
              className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors min-h-[32px] whitespace-nowrap ${
                locale === 'en'
                  ? 'bg-white dark:bg-[#19221C] text-[#17231D] dark:text-white shadow-xs'
                  : 'text-[#58655D] dark:text-[#98A89E]'
              }`}
            >
              EN
            </button>
            <button
              type="button"
              onClick={() => onChangeLocale('fr')}
              className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors min-h-[32px] whitespace-nowrap ${
                locale === 'fr'
                  ? 'bg-white dark:bg-[#19221C] text-[#17231D] dark:text-white shadow-xs'
                  : 'text-[#58655D] dark:text-[#98A89E]'
              }`}
            >
              FR
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 flex items-center justify-center px-4 py-10">
        <div className="w-full max-w-5xl grid grid-cols-1 lg:grid-cols-12 gap-8 items-stretch">
          {/* Left Editorial Trust Column */}
          <section className="lg:col-span-5 flex flex-col justify-between p-8 rounded-2xl bg-[#084B32] text-white">
            <div>
              <p className="text-xs font-medium tracking-wide text-[#9FE3C4]">
                VUMIQ Financial Ledger
              </p>
              <h1 className="mt-3 font-display text-2xl sm:text-3xl font-bold leading-tight text-balance">
                Exact daily bookkeeping built for shop owners and growing businesses.
              </h1>
              <p className="mt-4 text-sm leading-relaxed text-[#D3EAE0]">
                Track cash on hand, bank accounts, mobile money, customer receivables, supplier payables, and multi-currency reports with integer-precision accounting.
              </p>

              <div className="mt-8 space-y-4 border-t border-white/15 pt-6 text-sm">
                <div>
                  <p className="font-semibold text-white">Zero Approximate Math</p>
                  <p className="text-xs text-[#C5E2D5] mt-0.5">
                    Every currency is stored in exact integer minor units with strict precision checks.
                  </p>
                </div>
                <div>
                  <p className="font-semibold text-white">Multi-Currency Isolation</p>
                  <p className="text-xs text-[#C5E2D5] mt-0.5">
                    USD, CDF, EUR, KES, XOF, and more—never mixed into misleading raw totals.
                  </p>
                </div>
                <div>
                  <p className="font-semibold text-white">Complete Audit Trail</p>
                  <p className="text-xs text-[#C5E2D5] mt-0.5">
                    Balanced transfers, receivable settlements without double-counted income, and verified exports.
                  </p>
                </div>
              </div>
            </div>

            <div className="mt-8 pt-5 border-t border-white/15 text-xs text-[#9DC3B0]">
              <span>{translate(locale, 'ownedAndCreatedBy')}</span>
              <span className="mx-1.5" aria-hidden="true">·</span>
              <span className="font-semibold text-white">Mr. Amin M. Gutale</span>
            </div>
          </section>

          {/* Right Action / Setup Column */}
          <section className="lg:col-span-7 p-6 sm:p-8 rounded-2xl bg-white dark:bg-[#19221C] border border-[#E2E8E3] dark:border-[#28362D] flex flex-col justify-center">
            {errorMsg && (
              <div
                role="alert"
                className="mb-5 rounded-xl border border-[#D94848]/40 bg-[#D94848]/10 px-4 py-3 text-sm text-[#B42323] dark:text-[#FCA5A5]"
              >
                {errorMsg}
              </div>
            )}

            {!showBusinessSetupForm ? (
              <div>
                <h2 className="font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
                  Welcome to VUMIQ Cashbook
                </h2>
                <p className="mt-1.5 text-sm text-[#58655D] dark:text-[#98A89E]">
                  Sign in for encrypted cloud synchronization across devices, or use local Guest Mode on this device.
                </p>

                <div className="mt-6 space-y-4">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={handleGoogleClick}
                    className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white font-medium text-sm flex items-center justify-center gap-3 transition-colors cursor-pointer disabled:opacity-60"
                  >
                    <ShieldCheck className="w-5 h-5 shrink-0" aria-hidden="true" />
                    <span>{translate(locale, 'signInGoogle')}</span>
                  </button>

                  <div className="relative py-2 flex items-center">
                    <div className="flex-grow border-t border-[#E2E8E3] dark:border-[#28362D]" />
                    <span className="mx-3 text-xs text-[#58655D] dark:text-[#98A89E]">or</span>
                    <div className="flex-grow border-t border-[#E2E8E3] dark:border-[#28362D]" />
                  </div>

                  {/* Guest Mode Disclosure Card */}
                  <div className="rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] p-4">
                    <div className="flex items-start gap-3">
                      <HardDrive className="w-5 h-5 text-[#C88719] shrink-0 mt-0.5" aria-hidden="true" />
                      <div>
                        <h3 className="text-sm font-semibold text-[#17231D] dark:text-[#ECF3EE]">
                          {translate(locale, 'guestDisclosureTitle')}
                        </h3>
                        <p className="mt-1 text-xs leading-relaxed text-[#58655D] dark:text-[#98A89E]">
                          {translate(locale, 'guestDisclosureBody')}
                        </p>
                      </div>
                    </div>

                    <label className="mt-3.5 flex items-center gap-2.5 text-xs font-medium text-[#17231D] dark:text-[#ECF3EE] cursor-pointer min-h-[36px]">
                      <input
                        type="checkbox"
                        checked={guestConfirmedDisclosure}
                        onChange={(e) => setGuestConfirmedDisclosure(e.target.checked)}
                        className="w-4 h-4 accent-[#126B45] rounded"
                      />
                      <span>I understand that Guest Mode stores records only on this browser until exported.</span>
                    </label>

                    <button
                      type="button"
                      disabled={!guestConfirmedDisclosure || busy}
                      onClick={onSelectGuestMode}
                      className="mt-3 w-full min-h-[44px] px-4 py-2.5 rounded-xl border border-[#126B45] text-[#126B45] dark:border-[#34D399] dark:text-[#34D399] font-medium text-sm flex items-center justify-center gap-2 hover:bg-[#126B45]/5 transition-colors cursor-pointer disabled:opacity-45 disabled:cursor-not-allowed"
                    >
                      <span>{translate(locale, 'continueGuest')}</span>
                      <ArrowRight className="w-4 h-4" aria-hidden="true" />
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <form onSubmit={handleSetupSubmit} className="space-y-4">
                <div>
                  <p className="text-xs font-medium text-[#126B45] dark:text-[#34D399]">
                    {isAuthenticatedCloudUser
                      ? `Signed in as ${cloudUserDisplayName || 'Verified User'}`
                      : translate(locale, 'syncLocalGuest')}
                  </p>
                  <h2 className="mt-1 font-display text-xl sm:text-2xl font-bold text-[#17231D] dark:text-[#ECF3EE]">
                    {translate(locale, 'setupBusinessTitle')}
                  </h2>
                  <p className="mt-1 text-sm text-[#58655D] dark:text-[#98A89E]">
                    {translate(locale, 'setupBusinessSubtitle')}
                  </p>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="sm:col-span-2">
                    <label htmlFor="setup-biz-name" className="block text-xs font-medium mb-1.5">
                      <Building2 className="w-3.5 h-3.5 inline mr-1.5 opacity-70" aria-hidden="true" />
                      {translate(locale, 'businessNameLabel')} *
                    </label>
                    <input
                      id="setup-biz-name"
                      type="text"
                      required
                      maxLength={120}
                      value={businessName}
                      onChange={(e) => setBusinessName(e.target.value)}
                      placeholder={translate(locale, 'businessNamePlaceholder')}
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    />
                  </div>

                  <div>
                    <label htmlFor="setup-currency" className="block text-xs font-medium mb-1.5">
                      {translate(locale, 'defaultCurrencyLabel')} *
                    </label>
                    <select
                      id="setup-currency"
                      value={defaultCurrency}
                      onChange={(e) => {
                        const nextCur = e.target.value as CurrencyCode;
                        setDefaultCurrency(nextCur);
                        setOpeningBalanceStr(CURRENCY_DEFINITIONS[nextCur].decimals === 0 ? '0' : '0.00');
                      }}
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    >
                      {SUPPORTED_CURRENCIES.map((code) => (
                        <option key={code} value={code}>
                          {code} — {locale === 'fr' ? CURRENCY_DEFINITIONS[code].nameFr : CURRENCY_DEFINITIONS[code].nameEn}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label htmlFor="setup-phone" className="block text-xs font-medium mb-1.5">
                      Business Phone (Optional)
                    </label>
                    <input
                      id="setup-phone"
                      type="tel"
                      maxLength={40}
                      value={phone}
                      onChange={(e) => setPhone(e.target.value)}
                      placeholder="+243 81 000 0000"
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    />
                  </div>

                  <div className="sm:col-span-2">
                    <label htmlFor="setup-address" className="block text-xs font-medium mb-1.5">
                      Address / Branch Location (Optional)
                    </label>
                    <input
                      id="setup-address"
                      type="text"
                      maxLength={240}
                      value={address}
                      onChange={(e) => setAddress(e.target.value)}
                      placeholder="City, Street, Shop #"
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    />
                  </div>
                </div>

                <div className="pt-3 border-t border-[#E2E8E3] dark:border-[#28362D] grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div>
                    <label htmlFor="setup-acc-name" className="block text-xs font-medium mb-1.5">
                      <Wallet className="w-3.5 h-3.5 inline mr-1 opacity-70" aria-hidden="true" />
                      {translate(locale, 'initialAccountNameLabel')} *
                    </label>
                    <input
                      id="setup-acc-name"
                      type="text"
                      required
                      maxLength={80}
                      value={initialAccountName}
                      onChange={(e) => setInitialAccountName(e.target.value)}
                      placeholder={translate(locale, 'initialAccountPlaceholder')}
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    />
                  </div>

                  <div>
                    <label htmlFor="setup-acc-type" className="block text-xs font-medium mb-1.5">
                      Account Type
                    </label>
                    <select
                      id="setup-acc-type"
                      value={initialAccountType}
                      onChange={(e) => setInitialAccountType(e.target.value as Account['accountType'])}
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    >
                      <option value="cash">Cash on Hand</option>
                      <option value="bank">Bank Account</option>
                      <option value="mobile_money">Mobile Money</option>
                      <option value="other">Other Account</option>
                    </select>
                  </div>

                  <div>
                    <label htmlFor="setup-opening-bal" className="block text-xs font-medium mb-1.5">
                      {translate(locale, 'openingBalanceLabel')} ({defaultCurrency})
                    </label>
                    <input
                      id="setup-opening-bal"
                      type="text"
                      inputMode="decimal"
                      value={openingBalanceStr}
                      onChange={(e) => setOpeningBalanceStr(e.target.value)}
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] bg-[#F6F8F5] dark:bg-[#101713] text-sm font-mono-num focus:outline-none focus:ring-2 focus:ring-[#126B45]"
                    />
                  </div>
                </div>

                <div className="pt-2 flex items-center justify-end gap-3">
                  {onCancelGuestMode && !isAuthenticatedCloudUser && (
                    <button
                      type="button"
                      onClick={onCancelGuestMode}
                      className="min-h-[44px] px-4 py-2 rounded-xl border border-[#E2E8E3] dark:border-[#28362D] text-sm font-medium hover:bg-[#EFF3EE] dark:hover:bg-[#212D25] transition-colors"
                    >
                      Back
                    </button>
                  )}
                  <button
                    type="submit"
                    disabled={busy}
                    className="min-h-[48px] px-6 py-2.5 rounded-xl bg-[#126B45] hover:bg-[#084B32] text-white font-medium text-sm flex items-center gap-2 transition-colors cursor-pointer disabled:opacity-60"
                  >
                    <span>{busy ? 'Creating...' : translate(locale, 'createWorkspaceBtn')}</span>
                    <ArrowRight className="w-4 h-4" aria-hidden="true" />
                  </button>
                </div>
              </form>
            )}
          </section>
        </div>
      </main>
    </div>
  );
};
