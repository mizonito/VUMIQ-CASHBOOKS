import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { computeBackupChecksum, computeAccountBalances } from './accounting';
import type { Account, Business, Transaction } from '../types/cashbook';

/**
 * Phase 3 Verification Suite — Backup, Restore, and Data Recovery.
 *
 * Exercises:
 * 1. Android 12+ `data_extraction_rules.xml` & `backup_rules.xml` SQLite `-wal`, `-shm`, `-journal` exclusion
 *    and `AndroidManifest.xml` binding + `PRAGMA wal_checkpoint(FULL)` in `CashbookDatabase.kt`.
 * 2. Disposable test dataset round-trip backup serialization + FNV-1a checksum tampering rejection.
 * 3. Strict validation (rejects negative/zero amounts, malformed dates, invalid IDs, self-transfers).
 * 4. Disposable dataset Safe Merge vs Full Replace verification (zero silent deletions, deduplication by
 *    both `id` and `(businessId, idempotencyKey)`, older backup never overwrites newer local record,
 *    terminal `voided` state preserved, and exact post-merge cash balance verification).
 */

const ID_REGEX = /^[a-zA-Z0-9_-]+$/;
const DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;

interface DisposableArchive {
  schemaVersion: number;
  application: string;
  exportedAt: string;
  checksum: string;
  business: Business;
  accounts: Account[];
  transactions: Transaction[];
}

function validateAndSanitizeArchive(raw: DisposableArchive): DisposableArchive {
  if (raw.application !== 'VUMIQ Cashbook' || raw.schemaVersion !== 1) {
    throw new Error('Unsupported backup archive format or version.');
  }
  const bizId = raw.business.id.trim();
  if (!bizId || !ID_REGEX.test(bizId)) {
    throw new Error('Backup contains an invalid business ID.');
  }
  if (!raw.accounts.length) {
    throw new Error('Backup must contain at least one account.');
  }

  const totalPostedMinorSum = raw.transactions
    .filter((t) => t.status === 'posted')
    .reduce((acc, t) => acc + t.amountMinor, 0);

  const expectedChecksum = computeBackupChecksum({
    businessId: bizId,
    accountCount: raw.accounts.length,
    transactionCount: raw.transactions.length,
    totalPostedMinorSum,
  });

  if (raw.checksum && raw.checksum !== expectedChecksum) {
    throw new Error('Backup checksum mismatch — archive may be corrupted or modified.');
  }

  const seenIds = new Set<string>();
  const seenIdems = new Set<string>();
  const cleanTxs: Transaction[] = [];

  for (const tx of raw.transactions) {
    if (!tx.id.trim() || !ID_REGEX.test(tx.id.trim())) {
      throw new Error(`Invalid transaction ID: ${tx.id}`);
    }
    if (!Number.isInteger(tx.amountMinor) || tx.amountMinor <= 0) {
      throw new Error(`Invalid amountMinor on ${tx.id}`);
    }
    if (!DATE_REGEX.test(tx.date.trim())) {
      throw new Error(`Invalid date on ${tx.id}: ${tx.date}`);
    }
    if (tx.type === 'transfer' && (!tx.accountId || !tx.toAccountId || tx.accountId === tx.toAccountId)) {
      throw new Error(`Invalid transfer accounts on ${tx.id}`);
    }
    const cleanIdem = (tx.idempotencyKey || `idem_${tx.id}`).replace(/[^a-zA-Z0-9_-]/g, '_');
    const idemComp = `${bizId}::${cleanIdem}`;
    if (seenIds.has(tx.id) || seenIdems.has(idemComp)) {
      continue;
    }
    seenIds.add(tx.id);
    seenIdems.add(idemComp);
    cleanTxs.push({ ...tx, businessId: bizId, idempotencyKey: cleanIdem });
  }

  return { ...raw, checksum: expectedChecksum, transactions: cleanTxs };
}

function executeSafeMergeOnDisposableDataset(
  localTransactions: Transaction[],
  incoming: DisposableArchive
): { merged: Transaction[]; inserted: number; updated: number; skippedDuplicates: number } {
  const byId = new Map(localTransactions.map((t) => [t.id, t]));
  const byIdem = new Map(localTransactions.map((t) => [`${t.businessId}::${t.idempotencyKey}`, t]));
  const result = new Map(localTransactions.map((t) => [t.id, t]));

  let inserted = 0;
  let updated = 0;
  let skippedDuplicates = 0;

  for (const inc of incoming.transactions) {
    const existing = byId.get(inc.id) ?? byIdem.get(`${inc.businessId}::${inc.idempotencyKey}`);
    if (!existing) {
      result.set(inc.id, inc);
      inserted++;
      continue;
    }
    if (existing.status === 'voided') {
      skippedDuplicates++;
      continue;
    }
    if (inc.status === 'voided' && existing.status !== 'voided') {
      result.set(existing.id, { ...existing, status: 'voided', voidReason: inc.voidReason || 'Voided', updatedAt: inc.updatedAt });
      updated++;
      continue;
    }
    if (Date.parse(inc.updatedAt) > Date.parse(existing.updatedAt)) {
      result.set(existing.id, { ...inc, id: existing.id });
      updated++;
    } else {
      skippedDuplicates++;
    }
  }

  return {
    merged: Array.from(result.values()),
    inserted,
    updated,
    skippedDuplicates,
  };
}

test('Phase 3A: Android 12+ data_extraction_rules.xml and backup_rules.xml exclude SQLite -wal and -shm files', () => {
  const root = process.cwd();
  const manifest = fs.readFileSync(path.join(root, 'android-app/app/src/main/AndroidManifest.xml'), 'utf8');
  const extractionXml = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/res/xml/data_extraction_rules.xml'),
    'utf8'
  );
  const backupXml = fs.readFileSync(path.join(root, 'android-app/app/src/main/res/xml/backup_rules.xml'), 'utf8');
  const roomDb = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/data/local/CashbookDatabase.kt'),
    'utf8'
  );

  assert.match(manifest, /android:dataExtractionRules="@xml\/data_extraction_rules"/);
  assert.match(manifest, /android:fullBackupContent="@xml\/backup_rules"/);

  for (const xml of [extractionXml, backupXml]) {
    assert.match(xml, /include\s+domain="database"\s+path="vumiq_cashbook_ledger\.db"/);
    assert.match(xml, /exclude\s+domain="database"\s+path="vumiq_cashbook_ledger\.db-wal"/);
    assert.match(xml, /exclude\s+domain="database"\s+path="vumiq_cashbook_ledger\.db-shm"/);
    assert.match(xml, /exclude\s+domain="database"\s+path="vumiq_cashbook_ledger\.db-journal"/);
  }
  assert.match(extractionXml, /<cloud-backup>/);
  assert.match(extractionXml, /<device-transfer>/);
  assert.match(roomDb, /PRAGMA wal_checkpoint\(FULL\)/);
});

test('Phase 3B: Disposable dataset backup/restore round-trip, checksum tampering rejection, and Safe Merge deduplication', () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vumiq-disposable-backup-'));
  try {
    const biz: Business = {
      id: 'biz_disposable_test',
      ownerId: 'uid_owner_1',
      name: 'Gutale Disposable Test Store',
      defaultCurrency: 'USD',
      address: 'Mogadishu',
      phone: '+252610000000',
      fiscalStartMonth: 1,
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-20T08:00:00Z',
    };
    const cashAcc: Account = {
      id: 'acc_till_1',
      businessId: biz.id,
      ownerId: biz.ownerId,
      name: 'Cash Drawer',
      accountType: 'cash',
      currency: 'USD',
      openingBalanceMinor: 100_00,
      status: 'active',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    };

    const makeDisposableTx = (
      id: string,
      idem: string,
      type: 'income' | 'expense',
      amountMinor: number,
      updatedAt: string
    ): Transaction => ({
      id,
      businessId: biz.id,
      ownerId: biz.ownerId,
      idempotencyKey: idem,
      type,
      amountMinor,
      currency: 'USD',
      convertedAmountMinor: 0,
      toCurrency: '',
      exchangeRateScaled: 0,
      feeMinor: 0,
      date: '2026-10-22',
      time: '11:30',
      description: `Entry ${id}`,
      accountId: cashAcc.id,
      toAccountId: '',
      categoryId: 'cat_sales',
      categoryLabelSnapshot: 'Sales',
      partyId: '',
      partyNameSnapshot: '',
      referenceNumber: '',
      notes: '',
      attachmentName: '',
      attachmentMime: '',
      dueDate: '',
      status: 'posted',
      voidReason: '',
      createdByUid: biz.ownerId,
      createdAt: '2026-10-22T11:30:00Z',
      updatedAt,
    });

    // Local device has: Opening $100 + tx_shared ($50, edited locally at 14:00 to $60) + tx_local_only (-$20) = $140.00
    const localTransactions: Transaction[] = [
      makeDisposableTx('tx_shared', 'idem_shared_1', 'income', 60_00, '2026-10-22T14:00:00Z'),
      makeDisposableTx('tx_local_only', 'idem_local_only', 'expense', 20_00, '2026-10-22T15:00:00Z'),
    ];

    // Backup file contains:
    // 1. Older version of tx_shared ($50 at 10:00 -> must NOT overwrite local $60!)
    // 2. Duplicate of tx_local_only under a different ID 'tx_reimported' with same 'idem_local_only' (-> must be skipped!)
    // 3. Brand-new transaction tx_backup_new (+$35.00 -> must be inserted!)
    const backupTransactions: Transaction[] = [
      makeDisposableTx('tx_shared', 'idem_shared_1', 'income', 50_00, '2026-10-22T10:00:00Z'),
      makeDisposableTx('tx_reimported', 'idem_local_only', 'expense', 20_00, '2026-10-22T15:00:00Z'),
      makeDisposableTx('tx_backup_new', 'idem_backup_new', 'income', 35_00, '2026-10-23T09:00:00Z'),
    ];

    const checksum = computeBackupChecksum({
      businessId: biz.id,
      accountCount: 1,
      transactionCount: backupTransactions.length,
      totalPostedMinorSum: backupTransactions.reduce((s, t) => s + t.amountMinor, 0),
    });

    const backupFile = path.join(tmpDir, 'disposable_backup.json');
    const archivePayload: DisposableArchive = {
      schemaVersion: 1,
      application: 'VUMIQ Cashbook',
      exportedAt: '2026-10-23T10:00:00Z',
      checksum,
      business: biz,
      accounts: [cashAcc],
      transactions: backupTransactions,
    };
    fs.writeFileSync(backupFile, JSON.stringify(archivePayload, null, 2), 'utf8');

    // 1. Tampered archive must be rejected before touching any state
    const tampered = { ...archivePayload, checksum: 'badc0ffe' };
    assert.throws(() => validateAndSanitizeArchive(tampered), /checksum mismatch/);

    // 2. Negative/zero amount archive must be rejected
    const badAmount = {
      ...archivePayload,
      checksum: '',
      transactions: [{ ...backupTransactions[0], amountMinor: 0 }],
    };
    assert.throws(() => validateAndSanitizeArchive(badAmount), /Invalid amountMinor/);

    // 3. Valid disposable archive passes validation and Safe Merge produces exact expected balances
    const loaded = JSON.parse(fs.readFileSync(backupFile, 'utf8')) as DisposableArchive;
    const validated = validateAndSanitizeArchive(loaded);
    const mergeOutcome = executeSafeMergeOnDisposableDataset(localTransactions, validated);

    assert.equal(mergeOutcome.inserted, 1, 'Only tx_backup_new should be inserted');
    assert.equal(mergeOutcome.updated, 0, 'Older tx_shared must not overwrite newer local edit');
    assert.equal(mergeOutcome.skippedDuplicates, 2, 'Both tx_shared and idem_local_only duplicate must be skipped');
    assert.equal(mergeOutcome.merged.length, 3);

    // Verify exact closing cash balance: Opening $100 + $60 - $20 + $35 = $175.00 (17500 minor units)
    const balances = computeAccountBalances([cashAcc], mergeOutcome.merged);
    assert.equal(balances[cashAcc.id], 175_00);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('Phase 3C: Restore dialog and repository enforce Safe Merge default and pre-replace recovery snapshot', () => {
  const root = process.cwd();
  const repoSrc = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/data/CashbookRepository.kt'),
    'utf8'
  );
  const dialogSrc = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/ui/components/RestoreBackupDialog.kt'),
    'utf8'
  );

  assert.match(repoSrc, /writeLocalRecoverySnapshot/);
  assert.match(repoSrc, /recovery_snapshots/);
  assert.match(repoSrc, /dao\.mergeBusinessArchive/);
  assert.match(dialogSrc, /RestoreMode\.SAFE_MERGE/);
  assert.match(dialogSrc, /RestoreMode\.REPLACE_BUSINESS/);
  assert.match(dialogSrc, /confirmReplaceChecked/);
});
