import { BLUEPRINT_CONSTRAINTS, CurrencyCode, LocaleCode, SUPPORTED_CURRENCIES } from '../types/cashbook';

export interface CurrencyDefinition {
  code: CurrencyCode;
  nameEn: string;
  nameFr: string;
  symbol: string;
  decimals: 0 | 2;
  minorFactor: 1 | 100;
}

export const CURRENCY_DEFINITIONS: Record<CurrencyCode, CurrencyDefinition> = {
  USD: { code: 'USD', nameEn: 'US Dollar', nameFr: 'Dollar américain', symbol: '$', decimals: 2, minorFactor: 100 },
  CDF: { code: 'CDF', nameEn: 'Congolese Franc', nameFr: 'Franc congolais', symbol: 'FC', decimals: 2, minorFactor: 100 },
  EUR: { code: 'EUR', nameEn: 'Euro', nameFr: 'Euro', symbol: '€', decimals: 2, minorFactor: 100 },
  GBP: { code: 'GBP', nameEn: 'British Pound', nameFr: 'Livre sterling', symbol: '£', decimals: 2, minorFactor: 100 },
  KES: { code: 'KES', nameEn: 'Kenyan Shilling', nameFr: 'Shilling kényan', symbol: 'KSh', decimals: 2, minorFactor: 100 },
  SOS: { code: 'SOS', nameEn: 'Somali Shilling', nameFr: 'Shilling somalien', symbol: 'Sh.So.', decimals: 2, minorFactor: 100 },
  XOF: { code: 'XOF', nameEn: 'West African CFA Franc', nameFr: 'Franc CFA (BCEAO)', symbol: 'CFA', decimals: 0, minorFactor: 1 },
  RWF: { code: 'RWF', nameEn: 'Rwandan Franc', nameFr: 'Franc rwandais', symbol: 'FRw', decimals: 0, minorFactor: 1 },
  ZAR: { code: 'ZAR', nameEn: 'South African Rand', nameFr: 'Rand sud-africain', symbol: 'R', decimals: 2, minorFactor: 100 },
};

export function isValidCurrencyCode(code: string): code is CurrencyCode {
  return (SUPPORTED_CURRENCIES as readonly string[]).includes(code);
}

export interface ParsedAmountResult {
  ok: true;
  minorUnits: number;
  normalizedInput: string;
}

export interface ParsedAmountError {
  ok: false;
  errorCode:
    | 'EMPTY'
    | 'INVALID_CURRENCY'
    | 'MALFORMED'
    | 'NON_POSITIVE'
    | 'NEGATIVE'
    | 'PRECISION_EXCEEDED'
    | 'OVERFLOW';
  message: string;
}

/**
 * Parses a user-entered string amount into exact integer minor units without binary floating-point arithmetic.
 * Never silently rounds amounts beyond the currency's permitted decimal precision.
 */
export function parseAmountToMinor(
  rawInput: string | number,
  currency: CurrencyCode,
  options: { allowZero?: boolean } = {}
): ParsedAmountResult | ParsedAmountError {
  if (!isValidCurrencyCode(currency)) {
    return { ok: false, errorCode: 'INVALID_CURRENCY', message: 'Unsupported currency code.' };
  }

  if (typeof rawInput === 'number') {
    if (!Number.isFinite(rawInput) || Number.isNaN(rawInput)) {
      return { ok: false, errorCode: 'MALFORMED', message: 'Invalid numeric amount.' };
    }
    rawInput = String(rawInput);
  }

  const trimmed = rawInput.trim().replace(/\s+/g, '');
  if (!trimmed) {
    return { ok: false, errorCode: 'EMPTY', message: 'Amount is required.' };
  }

  // Reject scientific notation, multiple separators, or invalid characters
  if (/[eE]/.test(trimmed)) {
    return { ok: false, errorCode: 'MALFORMED', message: 'Scientific notation is not permitted for monetary amounts.' };
  }

  // Normalize comma decimal separator ONLY if there is no dot and single comma at end
  let normalized = trimmed;
  if (normalized.includes(',') && !normalized.includes('.')) {
    const parts = normalized.split(',');
    if (parts.length === 2 && parts[1].length <= 2) {
      normalized = `${parts[0]}.${parts[1]}`;
    } else {
      normalized = normalized.replace(/,/g, '');
    }
  } else {
    normalized = normalized.replace(/,/g, '');
  }

  if (normalized.startsWith('-')) {
    return {
      ok: false,
      errorCode: 'NEGATIVE',
      message: 'Negative amounts are not permitted.',
    };
  }

  if (normalized.startsWith('+')) {
    normalized = normalized.slice(1);
  }

  if (!/^\d+(\.\d+)?$/.test(normalized)) {
    return { ok: false, errorCode: 'MALFORMED', message: 'Enter a valid numeric amount (e.g. 125.50).' };
  }

  const def = CURRENCY_DEFINITIONS[currency];
  const [wholeRaw, fracRaw = ''] = normalized.split('.');

  if (fracRaw.length > def.decimals) {
    return {
      ok: false,
      errorCode: 'PRECISION_EXCEEDED',
      message:
        def.decimals === 0
          ? `${currency} does not allow fractional decimal places.`
          : `${currency} supports at most ${def.decimals} decimal places.`,
    };
  }

  const wholeClean = wholeRaw.replace(/^0+(?=\d)/, '');
  if (wholeClean.length > 12) {
    return { ok: false, errorCode: 'OVERFLOW', message: 'Amount exceeds maximum safe ledger limit.' };
  }

  const paddedFrac = fracRaw.padEnd(def.decimals, '0');
  const combinedDigits = `${wholeClean}${paddedFrac}`.replace(/^0+(?=\d)/, '');
  const minorBig = BigInt(combinedDigits || '0');

  if (minorBig > BigInt(BLUEPRINT_CONSTRAINTS.MAX_MINOR_UNITS)) {
    return { ok: false, errorCode: 'OVERFLOW', message: 'Amount exceeds maximum safe ledger limit.' };
  }

  const minorUnits = Number(minorBig);
  if (!Number.isSafeInteger(minorUnits)) {
    return { ok: false, errorCode: 'OVERFLOW', message: 'Amount exceeds safe integer precision.' };
  }

  if (!options.allowZero && minorUnits <= 0) {
    return { ok: false, errorCode: 'NON_POSITIVE', message: 'Amount must be greater than zero.' };
  }

  const normalizedInput =
    def.decimals === 0 ? wholeClean : `${wholeClean}.${paddedFrac}`;

  return {
    ok: true,
    minorUnits,
    normalizedInput,
  };
}

/**
 * Formats integer minor units back into an exact editable decimal string (no thousands separators).
 */
export function minorToInputString(minorUnits: number, currency: CurrencyCode): string {
  const def = CURRENCY_DEFINITIONS[currency];
  const sign = minorUnits < 0 ? '-' : '';
  const abs = Math.abs(Math.trunc(minorUnits));
  if (def.decimals === 0) {
    return `${sign}${abs}`;
  }
  const whole = Math.floor(abs / def.minorFactor);
  const frac = String(abs % def.minorFactor).padStart(def.decimals, '0');
  return `${sign}${whole}.${frac}`;
}

/**
 * Formats integer minor units as an unambiguous, locale-aware currency string using exact integer decomposition.
 */
export function formatMinorAmount(
  minorUnits: number,
  currency: CurrencyCode,
  locale: LocaleCode = 'en',
  options: { showPlusSign?: boolean; compactCode?: boolean } = {}
): string {
  const def = CURRENCY_DEFINITIONS[currency];
  const isNegative = minorUnits < 0;
  const abs = Math.abs(Math.trunc(minorUnits));

  const whole = def.decimals === 0 ? abs : Math.floor(abs / def.minorFactor);
  const frac = def.decimals === 0 ? '' : String(abs % def.minorFactor).padStart(def.decimals, '0');

  const groupSep = locale === 'fr' ? '\u202F' : ',';
  const decSep = locale === 'fr' ? ',' : '.';

  const wholeFormatted = String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, groupSep);
  const numberPart = def.decimals === 0 ? wholeFormatted : `${wholeFormatted}${decSep}${frac}`;

  const signPrefix = isNegative ? '-' : options.showPlusSign && minorUnits > 0 ? '+' : '';

  if (locale === 'fr') {
    return `${signPrefix}${numberPart} ${currency}`;
  }
  return `${signPrefix}${currency} ${numberPart}`;
}

/**
 * Parses an exchange rate string into a scaled integer (rate * 1,000,000) without floating-point drift.
 */
export function parseExchangeRateScaled(rawRate: string): { ok: true; rateScaled: number } | { ok: false; message: string } {
  const cleaned = rawRate.trim().replace(',', '.');
  if (!/^\d+(\.\d{1,6})?$/.test(cleaned)) {
    return { ok: false, message: 'Enter a positive exchange rate with up to 6 decimal places.' };
  }
  const [whole, frac = ''] = cleaned.split('.');
  const scaledBig = BigInt(`${whole}${frac.padEnd(6, '0')}`);
  if (scaledBig <= 0n || scaledBig > 999_999_999_999_999n) {
    return { ok: false, message: 'Exchange rate is out of bounds.' };
  }
  return { ok: true, rateScaled: Number(scaledBig) };
}

/**
 * Converts source integer minor units to destination integer minor units using an explicit scaled rate.
 * Uses half-up integer rounding in BigInt space only at the final minor-unit boundary.
 */
export function convertMinorWithScaledRate(
  sourceMinor: number,
  sourceCurrency: CurrencyCode,
  targetCurrency: CurrencyCode,
  exchangeRateScaled: number
): number {
  if (sourceCurrency === targetCurrency) return sourceMinor;
  const srcDef = CURRENCY_DEFINITIONS[sourceCurrency];
  const tgtDef = CURRENCY_DEFINITIONS[targetCurrency];

  // Value in target minor = sourceMinor * (tgtMinorFactor / srcMinorFactor) * (exchangeRateScaled / 1_000_000)
  const numerator = BigInt(sourceMinor) * BigInt(tgtDef.minorFactor) * BigInt(exchangeRateScaled);
  const denominator = BigInt(srcDef.minorFactor) * 1_000_000n;
  const quotient = (numerator + denominator / 2n) / denominator;
  return Number(quotient);
}
