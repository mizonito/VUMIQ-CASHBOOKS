/**
 * Core Domain Types & Schema Validation Bounds for VUMIQ Cashbook
 * Synchronized verbatim with firebase-blueprint.json constraints.
 */

export const SUPPORTED_CURRENCIES = [
  'USD',
  'CDF',
  'EUR',
  'GBP',
  'KES',
  'SOS',
  'XOF',
  'RWF',
  'ZAR',
] as const;

export type CurrencyCode = (typeof SUPPORTED_CURRENCIES)[number];

export type AccountType = 'cash' | 'bank' | 'mobile_money' | 'other';
export type AccountStatus = 'active' | 'archived';

export type CategoryKind = 'income' | 'expense';

export type PartyType = 'customer' | 'supplier' | 'other';
export type PartyStatus = 'active' | 'archived';

export type MemberRole = 'owner' | 'admin' | 'accountant' | 'manager' | 'viewer';

export type TransactionType =
  | 'income'
  | 'expense'
  | 'transfer'
  | 'opening_balance'
  | 'refund_income'
  | 'refund_expense'
  | 'credit_sale'
  | 'receivable_payment'
  | 'credit_purchase'
  | 'payable_payment';

export type TransactionStatus = 'posted' | 'voided';

export type ThemePreference = 'light' | 'dark' | 'system';
export type LocaleCode = 'en' | 'fr';

export type NavDestination = 'home' | 'transactions' | 'reports' | 'parties' | 'settings';

export type DateFilterPreset = 'today' | 'week' | 'month' | 'year' | 'all' | 'custom';

export type ReportType =
  | 'income_expense_summary'
  | 'cashbook'
  | 'account_statement'
  | 'daily'
  | 'weekly'
  | 'monthly'
  | 'annual'
  | 'category_breakdown'
  | 'party_statement'
  | 'receivables'
  | 'payables'
  | 'transaction_register';

/**
 * Verbatim validation constants matching firebase-blueprint.json and firestore.rules
 */
export const BLUEPRINT_CONSTRAINTS = {
  ID_MAX_LENGTH: 128,
  ID_PATTERN: /^[a-zA-Z0-9_\-]+$/,
  DATE_PATTERN: /^\d{4}-\d{2}-\d{2}$/,
  HEX_COLOR_PATTERN: /^#[0-9a-fA-F]{6}$/,
  USER_DISPLAY_NAME_MAX: 100,
  USER_EMAIL_MAX: 254,
  BUSINESS_NAME_MAX: 120,
  BUSINESS_ADDRESS_MAX: 240,
  BUSINESS_PHONE_MAX: 40,
  ACCOUNT_NAME_MAX: 80,
  CATEGORY_NAME_MAX: 64,
  CATEGORY_ICON_MAX: 32,
  PARTY_NAME_MAX: 100,
  PARTY_PHONE_MAX: 40,
  PARTY_ADDRESS_MAX: 240,
  PARTY_NOTES_MAX: 500,
  TX_DESCRIPTION_MAX: 200,
  TX_REFERENCE_MAX: 64,
  TX_NOTES_MAX: 600,
  TX_ATTACHMENT_NAME_MAX: 120,
  TX_ATTACHMENT_MIME_MAX: 64,
  TX_VOID_REASON_MAX: 240,
  AUDIT_SUMMARY_MAX: 300,
  AUDIT_STATE_MAX: 500,
  MAX_MINOR_UNITS: 99_999_999_999_999,
} as const;

export interface UserProfile {
  uid: string;
  displayName: string;
  defaultBusinessId: string;
  theme: ThemePreference;
  locale: LocaleCode;
  notificationsEnabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Business {
  id: string;
  ownerId: string;
  name: string;
  defaultCurrency: CurrencyCode;
  address: string;
  phone: string;
  fiscalStartMonth: number; // 1..12
  createdAt: string;
  updatedAt: string;
}

export interface BusinessMember {
  memberUid: string;
  businessId: string;
  ownerId: string;
  role: MemberRole;
  displayName: string;
  createdAt: string;
  updatedAt: string;
}

export interface Account {
  id: string;
  businessId: string;
  ownerId: string;
  name: string;
  accountType: AccountType;
  currency: CurrencyCode;
  openingBalanceMinor: number;
  status: AccountStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Category {
  id: string;
  businessId: string;
  ownerId: string;
  name: string;
  kind: CategoryKind;
  color: string;
  icon: string;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Party {
  id: string;
  businessId: string;
  ownerId: string;
  name: string;
  partyType: PartyType;
  phone: string;
  address: string;
  notes: string;
  currency: CurrencyCode;
  openingReceivableMinor: number;
  openingPayableMinor: number;
  dueDate: string; // YYYY-MM-DD or ''
  status: PartyStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Transaction {
  id: string;
  businessId: string;
  ownerId: string;
  idempotencyKey: string;
  type: TransactionType;
  amountMinor: number;
  currency: CurrencyCode;
  convertedAmountMinor: number; // > 0 when cross-currency transfer
  toCurrency: string; // CurrencyCode or ''
  exchangeRateScaled: number; // rate * 1_000_000
  feeMinor: number; // explicit transfer fee on source account
  date: string; // YYYY-MM-DD
  time: string; // HH:MM
  description: string;
  accountId: string; // empty only for non-cash credit_sale / credit_purchase
  toAccountId: string; // non-empty for transfer
  categoryId: string;
  categoryLabelSnapshot: string;
  partyId: string;
  partyNameSnapshot: string;
  referenceNumber: string;
  notes: string;
  attachmentName: string;
  attachmentMime: string;
  dueDate: string; // YYYY-MM-DD or ''
  status: TransactionStatus;
  voidReason: string;
  createdByUid: string;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEvent {
  id: string;
  businessId: string;
  ownerId: string;
  entityType: 'transaction' | 'account' | 'category' | 'party' | 'business';
  entityId: string;
  action: 'create' | 'update' | 'void' | 'delete' | 'restore';
  actorUid: string;
  actorName: string;
  summary: string;
  previousSummary: string;
  newSummary: string;
  reason: string;
  createdAt: string;
}

export interface DateRange {
  startDate: string; // YYYY-MM-DD inclusive, or ''
  endDate: string; // YYYY-MM-DD inclusive, or ''
}

export interface CurrencyPeriodSummary {
  currency: CurrencyCode;
  currentCashBalanceMinor: number;
  periodIncomeMinor: number;
  periodExpenseMinor: number;
  netIncomeMinor: number; // periodIncomeMinor - periodExpenseMinor
  periodCashInflowMinor: number;
  periodCashOutflowMinor: number;
  netCashMovementMinor: number; // periodCashInflowMinor - periodCashOutflowMinor
  totalReceivablesMinor: number; // Money to receive
  totalPayablesMinor: number; // Money to pay
  accountCount: number;
}

export interface PartyComputedBalance {
  party: Party;
  receivableByCurrency: Partial<Record<CurrencyCode, number>>;
  payableByCurrency: Partial<Record<CurrencyCode, number>>;
  transactionCount: number;
}

export interface TransactionFilterQuery {
  search?: string;
  dateRange?: DateRange;
  typeFilter?: 'all' | 'income_group' | 'expense_group' | TransactionType;
  categoryId?: string;
  accountId?: string;
  partyId?: string;
  currency?: CurrencyCode | 'all';
  minAmountMinor?: number | null;
  maxAmountMinor?: number | null;
  includeVoided?: boolean;
  sortBy?: 'date_desc' | 'date_asc' | 'amount_desc' | 'amount_asc';
}

export interface BackupArchive {
  schemaVersion: 1;
  application: 'VUMIQ Cashbook';
  exportedAt: string;
  checksum: string;
  business: Business;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  auditEvents: AuditEvent[];
}
