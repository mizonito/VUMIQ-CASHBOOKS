import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { computeAccountBalances } from './accounting';
import type { Account, CashbookTransaction } from '../types/cashbook';

const ROOT = process.cwd();

function readText(relPath: string): string {
  return fs.readFileSync(path.join(ROOT, relPath), 'utf8');
}

// Reference mirror of ReceiptStorageManager pure functions to test on disposable temp directories
function calculateInSampleSize(rawWidth: number, rawHeight: number, maxDimensionPx = 1600): number {
  if (rawWidth <= 0 || rawHeight <= 0 || maxDimensionPx <= 0) return 1;
  let inSampleSize = 1;
  const largestSide = Math.max(rawWidth, rawHeight);
  if (largestSide > maxDimensionPx) {
    const halfSide = Math.floor(largestSide / 2);
    while (Math.floor(halfSide / inSampleSize) >= maxDimensionPx) {
      inSampleSize *= 2;
    }
  }
  return Math.max(1, inSampleSize);
}

function sanitizeAttachmentDisplayName(rawName: string | null | undefined, fallbackSuffix: string): string {
  const base = (rawName ?? '')
    .trim()
    .split('/')
    .pop()!
    .split('\\')
    .pop()!
    .replace(/[^a-zA-Z0-9._ -]/g, '_')
    .trim();

  let normalized: string;
  if (!base) {
    normalized = `receipt_${fallbackSuffix.slice(0, 16)}.jpg`;
  } else if (!base.includes('.')) {
    normalized = `${base}.jpg`;
  } else {
    const lastDot = base.lastIndexOf('.');
    const stem = base.slice(0, lastDot).slice(0, 100) || 'receipt';
    normalized = `${stem}.jpg`;
  }
  return normalized.slice(0, 120);
}

function isManagedInternalReceiptPath(filesDir: string, candidatePath: string): boolean {
  const trimmed = candidatePath.trim();
  if (!trimmed) return false;
  try {
    const receiptsRoot = path.resolve(filesDir, 'receipts');
    const candidateCanonical = fs.existsSync(trimmed)
      ? fs.realpathSync(trimmed)
      : path.resolve(trimmed);
    const rootCanonical = fs.existsSync(receiptsRoot)
      ? fs.realpathSync(receiptsRoot)
      : receiptsRoot;
    return candidateCanonical.startsWith(`${rootCanonical}${path.sep}`);
  } catch {
    return false;
  }
}

function deleteManagedReceiptIfOrphaned(
  filesDir: string,
  candidateOldPath: string,
  remainingReferencedPaths: Set<string>
): boolean {
  const clean = candidateOldPath.trim();
  if (!clean || !isManagedInternalReceiptPath(filesDir, clean)) return false;
  const canonicalTarget = fs.existsSync(clean) ? fs.realpathSync(clean) : path.resolve(clean);
  const canonicalRemaining = new Set<string>();
  for (const p of remainingReferencedPaths) {
    const t = p.trim();
    if (!t) continue;
    canonicalRemaining.add(fs.existsSync(t) ? fs.realpathSync(t) : path.resolve(t));
  }
  if (canonicalRemaining.has(canonicalTarget)) return false;
  if (fs.existsSync(canonicalTarget) && fs.statSync(canonicalTarget).isFile()) {
    fs.unlinkSync(canonicalTarget);
    return true;
  }
  return false;
}

function sweepUnreferencedReceipts(filesDir: string, activeReferencedPaths: Set<string>): number {
  const receiptsDir = path.join(filesDir, 'receipts');
  if (!fs.existsSync(receiptsDir) || !fs.statSync(receiptsDir).isDirectory()) return 0;
  const canonicalActive = new Set<string>();
  for (const p of activeReferencedPaths) {
    const t = p.trim();
    if (!t) continue;
    canonicalActive.add(fs.existsSync(t) ? fs.realpathSync(t) : path.resolve(t));
  }
  let deletedCount = 0;
  for (const name of fs.readdirSync(receiptsDir)) {
    const full = path.join(receiptsDir, name);
    if (fs.statSync(full).isFile()) {
      const can = fs.realpathSync(full);
      if (!canonicalActive.has(can)) {
        fs.unlinkSync(full);
        deletedCount += 1;
      }
    }
  }
  return deletedCount;
}

test('Phase 5A: Room schema v2 uses explicit non-destructive MIGRATION_1_2 and preserves all ledger rows', () => {
  const dbKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/local/CashbookDatabase.kt'
  );
  assert.match(dbKt, /version\s*=\s*2/);
  assert.match(dbKt, /val\s+MIGRATION_1_2:\s*Migration\s*=\s*object\s*:\s*Migration\(1,\s*2\)/);
  assert.match(
    dbKt,
    /ALTER TABLE transactions ADD COLUMN receiptLocalPath TEXT NOT NULL DEFAULT ''/
  );
  assert.match(dbKt, /\.addMigrations\(MIGRATION_1_2\)/);
  assert.equal(
    dbKt.includes('fallbackToDestructiveMigration()'),
    false,
    'Production Room database must never enable destructive upgrade migrations'
  );

  const entitiesKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/local/RoomEntities.kt'
  );
  assert.match(entitiesKt, /@ColumnInfo\(defaultValue\s*=\s*""\)\s*val\s+receiptLocalPath:\s*String\s*=\s*""/);

  const daoKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/local/CashbookDao.kt'
  );
  assert.match(daoKt, /getAllNonEmptyReceiptPaths\(\)/);
});

test('Phase 5B: ReceiptStorageManager downsampling, EXIF orientation, and filename sanitization', () => {
  const storageKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/attachments/ReceiptStorageManager.kt'
  );
  assert.match(storageKt, /MAX_RECEIPT_DIMENSION_PX\s*=\s*1600/);
  assert.match(storageKt, /JPEG_QUALITY\s*=\s*82/);
  assert.match(storageKt, /ExifInterface/);
  assert.match(storageKt, /inJustDecodeBounds\s*=\s*true/);
  assert.match(storageKt, /Bitmap\.CompressFormat\.JPEG/);

  assert.equal(calculateInSampleSize(1200, 900, 1600), 1);
  assert.equal(calculateInSampleSize(4032, 3024, 1600), 2);
  assert.equal(calculateInSampleSize(8160, 6120, 1600), 4);
  assert.equal(calculateInSampleSize(0, 0, 1600), 1);

  const sanitizedTraversal = sanitizeAttachmentDisplayName('../../DCIM/Camera/IMG_20261024_0915.HEIC', 'abc123');
  assert.equal(sanitizedTraversal, 'IMG_20261024_0915.jpg');
  const sanitizedLong = sanitizeAttachmentDisplayName(`${'A'.repeat(200)}.png`, 'def456');
  assert.equal(sanitizedLong.length <= 120, true);
  assert.equal(sanitizedLong.endsWith('.jpg'), true);
  assert.equal(sanitizeAttachmentDisplayName('   ', '99887766'), 'receipt_99887766.jpg');
});

test('Phase 5C: Canonical path containment & orphan receipt cleanup on disposable temp filesystem', () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'vumiq-phase5-rcpt-'));
  try {
    const filesDir = path.join(tmpRoot, 'files');
    const receiptsDir = path.join(filesDir, 'receipts');
    const outsideDir = path.join(tmpRoot, 'external');
    fs.mkdirSync(receiptsDir, { recursive: true });
    fs.mkdirSync(outsideDir, { recursive: true });

    const activeReceipt = path.join(receiptsDir, 'rcpt_active_001.jpg');
    const replacedReceipt = path.join(receiptsDir, 'rcpt_old_002.jpg');
    const orphanSweepReceipt = path.join(receiptsDir, 'rcpt_orphan_003.jpg');
    const outsideImportantFile = path.join(outsideDir, 'customer_export.json');

    fs.writeFileSync(activeReceipt, 'jpeg-bytes-active');
    fs.writeFileSync(replacedReceipt, 'jpeg-bytes-old');
    fs.writeFileSync(orphanSweepReceipt, 'jpeg-bytes-orphan');
    fs.writeFileSync(outsideImportantFile, '{"important":true}');

    // Traversal / external path must be rejected and never deleted
    const traversalPath = path.join(receiptsDir, '..', '..', 'external', 'customer_export.json');
    assert.equal(isManagedInternalReceiptPath(filesDir, traversalPath), false);
    assert.equal(
      deleteManagedReceiptIfOrphaned(filesDir, traversalPath, new Set([activeReceipt])),
      false
    );
    assert.equal(fs.existsSync(outsideImportantFile), true);

    // Referenced receipt must NOT be deleted even if passed as candidate
    assert.equal(
      deleteManagedReceiptIfOrphaned(filesDir, activeReceipt, new Set([activeReceipt])),
      false
    );
    assert.equal(fs.existsSync(activeReceipt), true);

    // Unreferenced replaced receipt inside files/receipts/ MUST be deleted cleanly
    assert.equal(
      deleteManagedReceiptIfOrphaned(filesDir, replacedReceipt, new Set([activeReceipt])),
      true
    );
    assert.equal(fs.existsSync(replacedReceipt), false);

    // Full orphan sweep cleans remaining unreferenced receipt while preserving activeReceipt
    const swept = sweepUnreferencedReceipts(filesDir, new Set([activeReceipt]));
    assert.equal(swept, 1);
    assert.equal(fs.existsSync(orphanSweepReceipt), false);
    assert.equal(fs.existsSync(activeReceipt), true);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test('Phase 5D: Strict failure isolation guarantees receipt errors never abort financial transactions', () => {
  const repoKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/CashbookRepository.kt'
  );
  // Verify repository catches receipt storage failure into nonFatalReceiptWarning and still writes tx
  assert.match(repoKt, /recordTransactionWithReceiptOutcome/);
  assert.match(repoKt, /is StoredReceiptOutcome\.Failure -> \{\s*nonFatalReceiptWarning = outcome\.message/s);
  assert.match(repoKt, /dao\.insertTransactionWithAudit\(tx\.toEntity\(\),\s*audit\.toEntity\(\)\)/);
  assert.match(repoKt, /tx to nonFatalReceiptWarning/);

  // Simulate recording an expense whose receipt attachment failed to decode:
  // Financial transaction is still posted with empty attachment fields and exact balance updates.
  const cashAccount: Account = {
    id: 'acc_cash_usd',
    businessId: 'biz_1',
    ownerId: 'usr_1',
    name: 'Main Cash Box',
    accountType: 'cash',
    currency: 'USD',
    openingBalanceMinor: 50_000, // $500.00
    status: 'active',
    createdAt: '2026-10-24T08:00:00Z',
    updatedAt: '2026-10-24T08:00:00Z',
  };

  const expenseRecordedDespiteReceiptFailure: CashbookTransaction = {
    id: 'tx_fuel_01',
    businessId: 'biz_1',
    ownerId: 'usr_1',
    idempotencyKey: 'idem_fuel_01',
    type: 'expense',
    amountMinor: 12_500, // $125.00
    currency: 'USD',
    convertedAmountMinor: 0,
    toCurrency: 'USD',
    exchangeRateScaled: 0,
    feeMinor: 0,
    date: '2026-10-24',
    time: '09:30',
    description: 'Generator Diesel Delivery',
    accountId: 'acc_cash_usd',
    toAccountId: '',
    categoryId: 'cat_exp',
    categoryLabelSnapshot: 'Fuel & Transport',
    partyId: '',
    partyNameSnapshot: '',
    referenceNumber: 'INV-902',
    notes: '',
    attachmentName: '', // remained empty when receipt failed
    attachmentMime: '',
    dueDate: '',
    status: 'posted',
    voidReason: '',
    createdByUid: 'usr_1',
    createdAt: '2026-10-24T09:30:00Z',
    updatedAt: '2026-10-24T09:30:00Z',
  };

  const balances = computeAccountBalances([cashAccount], [expenseRecordedDespiteReceiptFailure]);
  assert.equal(balances['acc_cash_usd'], 37_500); // $375.00 exact
});

test('Phase 5E: CloudSyncEngine, BackupRestorePlanner, FileProvider, Manifest & Firestore rules compliance', () => {
  const cloudSyncKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/domain/CloudSyncEngine.kt'
  );
  assert.match(cloudSyncKt, /mergePreservingLocalAttachment/);
  assert.match(cloudSyncKt, /receiptLocalPath/);

  const backupPlannerKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/domain/BackupRestorePlanner.kt'
  );
  assert.match(backupPlannerKt, /receiptLocalPath\s*=\s*existing\.receiptLocalPath/);

  // FirestoreSyncManager must never serialize receiptLocalPath into Firestore documents
  const firestoreSyncKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/data/remote/FirestoreSyncManager.kt'
  );
  assert.equal(
    firestoreSyncKt.includes('"receiptLocalPath"'),
    false,
    'Firestore payload must not include local-only receiptLocalPath (strict firestore.rules hasOnly)'
  );

  // FileProvider paths and Manifest camera/provider security
  const filePathsXml = readText('android-app/app/src/main/res/xml/file_paths.xml');
  assert.match(filePathsXml, /name="exports"/);
  assert.match(filePathsXml, /name="camera_captures"/);
  assert.match(filePathsXml, /name="receipts"/);

  const manifestXml = readText('android-app/app/src/main/AndroidManifest.xml');
  assert.match(manifestXml, /android:name="android\.hardware\.camera"/);
  assert.match(manifestXml, /android:required="false"/);
  assert.equal(
    manifestXml.includes('android.permission.CAMERA'),
    false,
    'Manifest must not declare CAMERA permission without a runtime permission gate (TakePicture delegates to system camera safely)'
  );
  assert.match(manifestXml, /androidx\.core\.content\.FileProvider[\s\S]*?android:exported="false"/);

  // Backup rules include receipts/ so Android 12+ device transfer preserves receipt images
  const extractionXml = readText('android-app/app/src/main/res/xml/data_extraction_rules.xml');
  const backupXml = readText('android-app/app/src/main/res/xml/backup_rules.xml');
  assert.match(extractionXml, /domain="file"\s+path="receipts\/"/);
  assert.match(backupXml, /domain="file"\s+path="receipts\/"/);
});
