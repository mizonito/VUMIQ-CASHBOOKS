import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAmountToMinor, formatMinorAmount, convertMinorWithScaledRate } from './money';
import {
  computePeriodSummariesByCurrency,
  createLedgerEngine,
  filterAndSearchTransactions,
  resolveDateRangePreset,
  computeBackupChecksum,
  validateBackupArchive,
} from './accounting';

test('Monetary Validation: rejects zero, negative, NaN, scientific notation, and excess decimal precision without rounding', () => {
  assert.equal(parseAmountToMinor('0', 'USD').ok, false);
  assert.equal(parseAmountToMinor('-10.00', 'USD').ok, false);
  assert.equal(parseAmountToMinor('1e5', 'USD').ok, false);
  assert.equal(parseAmountToMinor('abc', 'USD').ok, false);

  // Must NEVER silently round beyond currency precision
  const excessUsd = parseAmountToMinor('19.999', 'USD');
  assert.equal(excessUsd.ok, false);
  if (!excessUsd.ok) assert.equal(excessUsd.errorCode, 'PRECISION_EXCEEDED');

  const excessXof = parseAmountToMinor('500.5', 'XOF');
  assert.equal(excessXof.ok, false);

  // Classic IEEE-754 trap: 0.10 + 0.20 = 0.30 exact in integer minor units
  const a = parseAmountToMinor('0.10', 'USD');
  const b = parseAmountToMinor('0.20', 'USD');
  assert.ok(a.ok && b.ok);
  assert.equal(a.minorUnits + b.minorUnits, 30);
  assert.equal(formatMinorAmount(a.minorUnits + b.minorUnits, 'USD', 'en'), 'USD 0.30');
});

test('Case A: Basic income and expense (Opening 100.00 + Income 50.00 - Expense 20.00 = Closing 130.00, Net +30.00)', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Shop Owner',
    name: 'Kivu General Store',
    defaultCurrency: 'USD',
  });

  const cashAcc = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Shop Owner',
    businessId: biz.id,
    name: 'Cash on Hand',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 10000, // 100.00
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Shop Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_a_inc',
    type: 'income',
    amountMinor: 5000, // 50.00
    currency: 'USD',
    date: '2026-10-23',
    description: 'Daily counter sales',
    accountId: cashAcc.id,
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Shop Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_a_exp',
    type: 'expense',
    amountMinor: 2000, // 20.00
    currency: 'USD',
    date: '2026-10-23',
    description: 'Packaging bags',
    accountId: cashAcc.id,
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summary = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions, {
    startDate: '2026-10-01',
    endDate: '2026-10-31',
  }).USD;

  assert.equal(snap.accountBalances[cashAcc.id], 13000); // 130.00
  assert.equal(summary.currentCashBalanceMinor, 13000); // 130.00
  assert.equal(summary.periodIncomeMinor, 5000); // 50.00
  assert.equal(summary.periodExpenseMinor, 2000); // 20.00
  assert.equal(summary.netCashMovementMinor, 3000); // 30.00
});

test('Case B: Account transfer (Cash 200.00, Bank 500.00, Transfer 75.00 -> Cash 125, Bank 575, Combined 700, Income/Expense 0)', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Transfer Test Co',
    defaultCurrency: 'USD',
  });

  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash Drawer',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 20000, // 200.00
  });

  const bank = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Business Bank',
    accountType: 'bank',
    currency: 'USD',
    openingBalanceMinor: 50000, // 500.00
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_b_trf',
    type: 'transfer',
    amountMinor: 7500, // 75.00
    currency: 'USD',
    date: '2026-10-23',
    description: 'Bank deposit from cash drawer',
    accountId: cash.id,
    toAccountId: bank.id,
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summary = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions).USD;

  assert.equal(snap.accountBalances[cash.id], 12500); // 125.00
  assert.equal(snap.accountBalances[bank.id], 57500); // 575.00
  assert.equal(summary.currentCashBalanceMinor, 70000); // 700.00 combined
  assert.equal(summary.periodIncomeMinor, 0);
  assert.equal(summary.periodExpenseMinor, 0);
});

test('Case C: Editing a transaction updates balance once and preserves audit trail', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Edit Audit Co',
    defaultCurrency: 'USD',
  });
  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 10000, // 100.00
  });

  const { transaction: tx } = await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_c_1',
    type: 'expense',
    amountMinor: 3000, // 30.00
    currency: 'USD',
    date: '2026-10-23',
    description: 'Courier fee',
    accountId: cash.id,
  });

  await engine.editTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    transactionId: tx.id,
    amountMinor: 2500, // edited to 25.00
    description: 'Courier fee (corrected receipt)',
    date: '2026-10-23',
    reason: 'Receipt showed 25.00',
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summary = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions).USD;

  assert.equal(snap.accountBalances[cash.id], 7500); // 100 - 25 = 75.00
  assert.equal(summary.periodExpenseMinor, 2500); // 25.00 exactly once
  const editAudit = snap.auditEvents.find((e) => e.entityId === tx.id && e.action === 'update');
  assert.ok(editAudit, 'Audit trail must record the update');
  assert.match(editAudit.previousSummary, /USD 30\.00/);
  assert.match(editAudit.newSummary, /USD 25\.00/);
});

test('Case D: Voiding / reversing a transaction restores balance, locks terminal state, and preserves audit record', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Void Policy Co',
    defaultCurrency: 'USD',
  });
  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 10000,
  });

  const { transaction: tx } = await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_d_1',
    type: 'expense',
    amountMinor: 4000,
    currency: 'USD',
    date: '2026-10-23',
    description: 'Erroneous entry',
    accountId: cash.id,
  });

  await engine.voidTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    transactionId: tx.id,
    reason: 'Entered on wrong account',
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summary = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions).USD;

  assert.equal(snap.accountBalances[cash.id], 10000); // Restored to 100.00
  assert.equal(summary.periodExpenseMinor, 0);
  assert.ok(snap.auditEvents.some((e) => e.entityId === tx.id && e.action === 'void'));

  // Terminal state lock: attempting to edit a voided transaction must throw
  await assert.rejects(
    () =>
      engine.editTransaction({
        actorUid: 'user_1',
        actorName: 'Owner',
        businessId: biz.id,
        transactionId: tx.id,
        amountMinor: 1000,
        description: 'Tamper voided',
        date: '2026-10-23',
      }),
    /TERMINAL_STATE/
  );
});

test('Case E: Multiple currencies (USD and CDF) are strictly isolated and never combined into raw totals', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Kinshasa Dual Currency Trader',
    defaultCurrency: 'USD',
  });

  const usdAcc = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'USD Safe',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 10000, // $100.00
  });

  const cdfAcc = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'CDF Till',
    accountType: 'cash',
    currency: 'CDF',
    openingBalanceMinor: 25000000, // 250,000.00 FC
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_e_usd',
    type: 'income',
    amountMinor: 5000, // $50.00
    currency: 'USD',
    date: '2026-10-23',
    description: 'Wholesale USD invoice',
    accountId: usdAcc.id,
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_e_cdf',
    type: 'income',
    amountMinor: 10000000, // 100,000.00 FC
    currency: 'CDF',
    date: '2026-10-23',
    description: 'Retail CDF sales',
    accountId: cdfAcc.id,
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summaries = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions);

  assert.equal(summaries.USD.currentCashBalanceMinor, 15000); // $150.00
  assert.equal(summaries.CDF.currentCashBalanceMinor, 35000000); // 350,000.00 FC
  assert.equal(summaries.USD.periodIncomeMinor, 5000);
  assert.equal(summaries.CDF.periodIncomeMinor, 10000000);
  assert.notEqual(summaries.USD.currentCashBalanceMinor, summaries.USD.currentCashBalanceMinor + summaries.CDF.currentCashBalanceMinor);

  // Explicit cross-currency conversion helper test: $10.00 at 2,850.00 CDF/USD = 28,500.00 CDF
  const converted = convertMinorWithScaledRate(1000, 'USD', 'CDF', 2850_000000);
  assert.equal(converted, 2850000);
});

test('Case F: Receivables — Credit sale 100.00 + Payment 40.00 -> Outstanding 60.00, Income counted once (100.00)', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Wholesale Distributor',
    defaultCurrency: 'USD',
  });

  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 0,
  });

  const customer = await engine.createParty({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Café Lumumba',
    partyType: 'customer',
    currency: 'USD',
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_f_sale',
    type: 'credit_sale',
    amountMinor: 10000, // 100.00 credit sale
    currency: 'USD',
    date: '2026-10-20',
    description: 'Coffee beans invoice #101',
    partyId: customer.id,
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'idem_f_pay',
    type: 'receivable_payment',
    amountMinor: 4000, // 40.00 partial payment
    currency: 'USD',
    date: '2026-10-23',
    description: 'Partial settlement for invoice #101',
    accountId: cash.id,
    partyId: customer.id,
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const summary = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions).USD;

  assert.equal(snap.partyBalances[customer.id].receivableByCurrency.USD, 6000); // 60.00 remaining
  assert.equal(summary.totalReceivablesMinor, 6000); // 60.00
  assert.equal(summary.periodIncomeMinor, 10000); // 100.00 (NOT 140.00!)
  assert.equal(summary.currentCashBalanceMinor, 4000); // 40.00 actual cash received
});

test('Case G: Duplicate submission with same idempotency key creates exactly one financial transaction', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Idempotent Shop',
    defaultCurrency: 'USD',
  });
  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 0,
  });

  const req = {
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'client_req_998877',
    type: 'income' as const,
    amountMinor: 8500,
    currency: 'USD' as const,
    date: '2026-10-23',
    description: 'Order #44',
    accountId: cash.id,
  };

  const first = await engine.recordTransaction(req);
  const second = await engine.recordTransaction(req);

  assert.equal(first.deduplicated, false);
  assert.equal(second.deduplicated, true);
  assert.equal(first.transaction.id, second.transaction.id);

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  assert.equal(snap.transactions.length, 1);
  assert.equal(snap.accountBalances[cash.id], 8500);
});

test('Case H: Concurrent operations — simultaneous updates and transfers maintain consistent combined balances', async () => {
  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Concurrent Ledger Co',
    defaultCurrency: 'USD',
  });

  const accA = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Account A',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 100000, // 1,000.00
  });
  const accB = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Account B',
    accountType: 'bank',
    currency: 'USD',
    openingBalanceMinor: 100000, // 1,000.00
  });

  // Fire 10 concurrent transfers back and forth + 5 concurrent incomes + 1 invalid one-sided transfer
  const ops = [
    ...Array.from({ length: 5 }, (_, i) =>
      engine.recordTransaction({
        actorUid: 'user_1',
        actorName: 'Owner',
        businessId: biz.id,
        idempotencyKey: `conc_trf_ab_${i}`,
        type: 'transfer',
        amountMinor: 3000, // 30.00 * 5 = 150.00 from A -> B
        currency: 'USD',
        date: '2026-10-23',
        description: `Transfer AB ${i}`,
        accountId: accA.id,
        toAccountId: accB.id,
      })
    ),
    ...Array.from({ length: 5 }, (_, i) =>
      engine.recordTransaction({
        actorUid: 'user_1',
        actorName: 'Owner',
        businessId: biz.id,
        idempotencyKey: `conc_trf_ba_${i}`,
        type: 'transfer',
        amountMinor: 1000, // 10.00 * 5 = 50.00 from B -> A
        currency: 'USD',
        date: '2026-10-23',
        description: `Transfer BA ${i}`,
        accountId: accB.id,
        toAccountId: accA.id,
      })
    ),
    ...Array.from({ length: 5 }, (_, i) =>
      engine.recordTransaction({
        actorUid: 'user_1',
        actorName: 'Owner',
        businessId: biz.id,
        idempotencyKey: `conc_inc_${i}`,
        type: 'income',
        amountMinor: 2000, // 20.00 * 5 = 100.00 into A
        currency: 'USD',
        date: '2026-10-23',
        description: `Sale ${i}`,
        accountId: accA.id,
      })
    ),
    // Invalid one-sided transfer must reject cleanly without partial commit
    engine
      .recordTransaction({
        actorUid: 'user_1',
        actorName: 'Owner',
        businessId: biz.id,
        idempotencyKey: 'conc_invalid_onesided',
        type: 'transfer',
        amountMinor: 9999,
        currency: 'USD',
        date: '2026-10-23',
        description: 'Broken transfer',
        accountId: accA.id,
        toAccountId: '',
      })
      .catch(() => null),
  ];

  await Promise.all(ops);

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  // A: 1000 - 150 + 50 + 100 = 1000.00 (100000)
  // B: 1000 + 150 - 50 = 1100.00 (110000)
  assert.equal(snap.accountBalances[accA.id], 100000);
  assert.equal(snap.accountBalances[accB.id], 110000);
  assert.equal(snap.accountBalances[accA.id] + snap.accountBalances[accB.id], 210000);
});

test('Case I: Date and report boundaries — month-end, year-end, leap day (2028-02-29), and custom ranges', async () => {
  const leapMonthRange = resolveDateRangePreset('month', '2028-02-15');
  assert.equal(leapMonthRange.startDate, '2028-02-01');
  assert.equal(leapMonthRange.endDate, '2028-02-29');

  const nonLeapMonthRange = resolveDateRangePreset('month', '2026-02-15');
  assert.equal(nonLeapMonthRange.endDate, '2026-02-28');

  const engine = createLedgerEngine();
  const biz = await engine.createBusiness({
    actorUid: 'user_1',
    actorName: 'Owner',
    name: 'Boundary Co',
    defaultCurrency: 'USD',
  });
  const cash = await engine.createAccount({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    name: 'Cash',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 0,
  });

  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'tx_dec31',
    type: 'income',
    amountMinor: 1100,
    currency: 'USD',
    date: '2026-12-31',
    description: 'Year-end sale',
    accountId: cash.id,
  });
  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'tx_jan01',
    type: 'income',
    amountMinor: 2200,
    currency: 'USD',
    date: '2027-01-01',
    description: 'New-year sale',
    accountId: cash.id,
  });
  await engine.recordTransaction({
    actorUid: 'user_1',
    actorName: 'Owner',
    businessId: biz.id,
    idempotencyKey: 'tx_leap29',
    type: 'income',
    amountMinor: 3300,
    currency: 'USD',
    date: '2028-02-29',
    description: 'Leap day sale',
    accountId: cash.id,
  });

  const snap = await engine.getWorkspaceSnapshot('user_1', biz.id);
  const y2026 = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions, resolveDateRangePreset('year', '2026-06-01')).USD;
  const y2027 = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions, resolveDateRangePreset('year', '2027-06-01')).USD;
  const feb2028 = computePeriodSummariesByCurrency(snap.accounts, snap.parties, snap.transactions, leapMonthRange).USD;

  assert.equal(y2026.periodIncomeMinor, 1100);
  assert.equal(y2027.periodIncomeMinor, 2200);
  assert.equal(feb2028.periodIncomeMinor, 3300);

  // Search & filter deduplication and case/whitespace insensitivity check
  const searched = filterAndSearchTransactions(snap.transactions, snap.accounts, snap.categories, snap.parties, {
    search: '  LEAP   day  ',
  });
  assert.equal(searched.length, 1);
  assert.equal(searched[0].date, '2028-02-29');
});

test('Case J: Data isolation & RBAC — separate users/businesses cannot read or mutate each other without explicit role', async () => {
  const engine = createLedgerEngine();
  const bizAlice = await engine.createBusiness({
    actorUid: 'uid_alice',
    actorName: 'Alice',
    name: 'Alice Bakery',
    defaultCurrency: 'USD',
  });
  const bizBob = await engine.createBusiness({
    actorUid: 'uid_bob',
    actorName: 'Bob',
    name: 'Bob Hardware',
    defaultCurrency: 'USD',
  });

  // Bob cannot read or record in Alice's business
  await assert.rejects(() => engine.getWorkspaceSnapshot('uid_bob', bizAlice.id), /FORBIDDEN/);
  await assert.rejects(
    () =>
      engine.createAccount({
        actorUid: 'uid_bob',
        actorName: 'Bob',
        businessId: bizAlice.id,
        name: 'Rogue Account',
        accountType: 'cash',
        currency: 'USD',
        openingBalanceMinor: 0,
      }),
    /FORBIDDEN/
  );

  // Alice invites Eve as Read-only viewer -> Eve can read, but CANNOT record or delete
  await engine.addMember({
    actorUid: 'uid_alice',
    businessId: bizAlice.id,
    memberUid: 'uid_eve_viewer',
    displayName: 'Eve Auditor',
    role: 'viewer',
  });

  const viewerSnap = await engine.getWorkspaceSnapshot('uid_eve_viewer', bizAlice.id);
  assert.equal(viewerSnap.business.name, 'Alice Bakery');

  await assert.rejects(
    () =>
      engine.createAccount({
        actorUid: 'uid_eve_viewer',
        actorName: 'Eve Auditor',
        businessId: bizAlice.id,
        name: 'Unauthorized Account',
        accountType: 'cash',
        currency: 'USD',
        openingBalanceMinor: 0,
      }),
    /FORBIDDEN/
  );

  // Alice cannot read Bob's business either
  await assert.rejects(() => engine.getWorkspaceSnapshot('uid_alice', bizBob.id), /FORBIDDEN/);

  // Verify backup checksum validation
  const checksum = computeBackupChecksum({
    businessId: bizAlice.id,
    accountCount: viewerSnap.accounts.length,
    transactionCount: viewerSnap.transactions.length,
    totalPostedMinorSum: 0,
  });
  const verified = validateBackupArchive({
    schemaVersion: 1,
    application: 'VUMIQ Cashbook',
    exportedAt: new Date().toISOString(),
    checksum,
    business: viewerSnap.business,
    accounts: viewerSnap.accounts,
    categories: viewerSnap.categories,
    parties: viewerSnap.parties,
    transactions: viewerSnap.transactions,
    auditEvents: viewerSnap.auditEvents,
  });
  assert.equal(verified.ok, true);
});
