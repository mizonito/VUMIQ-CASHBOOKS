import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Pure executable mirror of Android `CloudSyncEngine.kt` + `firestore.rules` schema contract
 * verification for Phase 2 (Firebase Auth + Genuine Two-Way Cloud Synchronization).
 */

interface SyncTx {
  id: string;
  businessId: string;
  idempotencyKey: string;
  amountMinor: number;
  description: string;
  status: 'posted' | 'voided';
  voidReason: string;
  attachmentName: string;
  attachmentMime: string;
  updatedAt: string;
  pendingCloudSync: boolean;
}

type PushOp =
  | { kind: 'CreatePosted'; tx: SyncTx }
  | { kind: 'CreatePostedThenVoid'; tx: SyncTx }
  | { kind: 'UpdatePostedDetails'; tx: SyncTx }
  | { kind: 'VoidExistingInCloud'; tx: SyncTx };

function parseIsoEpochMillis(iso: string): number {
  if (!iso.trim()) return 0;
  const ms = Date.parse(iso.trim());
  return Number.isNaN(ms) ? 0 : ms;
}

function samePostedFields(a: SyncTx, b: SyncTx): boolean {
  return (
    a.amountMinor === b.amountMinor &&
    a.description === b.description &&
    a.attachmentName === b.attachmentName &&
    a.attachmentMime === b.attachmentMime
  );
}

function planTransactionsMerge(localTransactions: SyncTx[], remoteTransactions: SyncTx[]) {
  const localById = new Map(localTransactions.map((t) => [t.id, t]));
  const localByIdem = new Map(
    localTransactions
      .filter((t) => t.idempotencyKey.trim().length > 0)
      .map((t) => [`${t.businessId}::${t.idempotencyKey}`, t])
  );

  const matchedLocalIds = new Set<string>();
  const toUpsertInLocal: SyncTx[] = [];
  const toMarkSyncedLocally: string[] = [];
  const cloudPushOperations: PushOp[] = [];

  for (const remote of remoteTransactions) {
    const idemKey = `${remote.businessId}::${remote.idempotencyKey}`;
    const local =
      localById.get(remote.id) ??
      (remote.idempotencyKey.trim() ? localByIdem.get(idemKey) : undefined);

    if (!local) {
      toUpsertInLocal.push({ ...remote, pendingCloudSync: false });
      continue;
    }

    matchedLocalIds.add(local.id);

    if (remote.status === 'voided') {
      if (local.status !== 'voided' || local.pendingCloudSync || local.voidReason !== remote.voidReason) {
        toUpsertInLocal.push({
          ...remote,
          id: local.id,
          attachmentName: remote.attachmentName || local.attachmentName,
          attachmentMime: remote.attachmentMime || local.attachmentMime,
          status: 'voided',
          voidReason: remote.voidReason || local.voidReason,
          pendingCloudSync: false,
        });
      }
      continue;
    }

    if (local.status === 'voided') {
      cloudPushOperations.push({
        kind: 'VoidExistingInCloud',
        tx: local.id === remote.id ? local : { ...local, id: remote.id },
      });
      continue;
    }

    const remoteMs = parseIsoEpochMillis(remote.updatedAt);
    const localMs = parseIsoEpochMillis(local.updatedAt);

    if (remoteMs > localMs) {
      toUpsertInLocal.push({
        ...remote,
        id: local.id,
        attachmentName: remote.attachmentName || local.attachmentName,
        attachmentMime: remote.attachmentMime || local.attachmentMime,
        pendingCloudSync: false,
      });
    } else if (local.pendingCloudSync && localMs >= remoteMs) {
      if (!samePostedFields(local, remote)) {
        cloudPushOperations.push({
          kind: 'UpdatePostedDetails',
          tx: local.id === remote.id ? local : { ...local, id: remote.id },
        });
      } else {
        toMarkSyncedLocally.push(local.id);
      }
    } else if (local.pendingCloudSync) {
      toMarkSyncedLocally.push(local.id);
    }
  }

  for (const local of localTransactions) {
    if (matchedLocalIds.has(local.id)) continue;
    if (local.status === 'voided') {
      cloudPushOperations.push({ kind: 'CreatePostedThenVoid', tx: local });
    } else {
      cloudPushOperations.push({ kind: 'CreatePosted', tx: local });
    }
  }

  return { toUpsertInLocal, toMarkSyncedLocally, cloudPushOperations };
}

function makeTx(overrides: Partial<SyncTx> = {}): SyncTx {
  return {
    id: 'tx_1',
    businessId: 'biz_1',
    idempotencyKey: 'idem_1',
    amountMinor: 15000,
    description: 'Wholesale flour bag',
    status: 'posted',
    voidReason: '',
    attachmentName: '',
    attachmentMime: '',
    updatedAt: '2026-10-24T10:00:00Z',
    pendingCloudSync: false,
    ...overrides,
  };
}

test('Phase 2 Sync: Older remote record never overwrites newer offline local edit', () => {
  const local = makeTx({
    amountMinor: 28500,
    description: 'Updated offline on Samsung phone',
    updatedAt: '2026-10-24T13:15:00Z',
    pendingCloudSync: true,
  });
  const remote = makeTx({
    amountMinor: 15000,
    description: 'Older web version',
    updatedAt: '2026-10-24T10:00:00Z',
  });

  const plan = planTransactionsMerge([local], [remote]);
  assert.equal(plan.toUpsertInLocal.length, 0);
  assert.equal(plan.cloudPushOperations.length, 1);
  assert.equal(plan.cloudPushOperations[0].kind, 'UpdatePostedDetails');
  assert.equal(plan.cloudPushOperations[0].tx.amountMinor, 28500);
});

test('Phase 2 Sync: Newer Web Cashbook record pulls cleanly into Android Room without losing local attachment metadata', () => {
  const local = makeTx({
    amountMinor: 15000,
    attachmentName: 'receipt_oct24.jpg',
    attachmentMime: 'image/jpeg',
    updatedAt: '2026-10-24T10:00:00Z',
  });
  const remote = makeTx({
    amountMinor: 19200,
    description: 'Adjusted from Web Cashbook',
    attachmentName: '',
    attachmentMime: '',
    updatedAt: '2026-10-24T12:45:00.500Z',
  });

  const plan = planTransactionsMerge([local], [remote]);
  assert.equal(plan.cloudPushOperations.length, 0);
  assert.equal(plan.toUpsertInLocal.length, 1);
  assert.equal(plan.toUpsertInLocal[0].amountMinor, 19200);
  assert.equal(plan.toUpsertInLocal[0].attachmentName, 'receipt_oct24.jpg');
});

test('Phase 2 Sync: Terminal VOIDED status wins in both directions and never reverts to POSTED', () => {
  const localVoid = makeTx({
    status: 'voided',
    voidReason: 'Customer returned item',
    updatedAt: '2026-10-24T10:05:00Z',
    pendingCloudSync: true,
  });
  const remotePosted = makeTx({
    status: 'posted',
    updatedAt: '2026-10-24T11:30:00Z',
  });
  const plan1 = planTransactionsMerge([localVoid], [remotePosted]);
  assert.equal(plan1.toUpsertInLocal.length, 0);
  assert.equal(plan1.cloudPushOperations.length, 1);
  assert.equal(plan1.cloudPushOperations[0].kind, 'VoidExistingInCloud');

  const localEditedLater = makeTx({
    status: 'posted',
    amountMinor: 42000,
    updatedAt: '2026-10-24T15:00:00Z',
    pendingCloudSync: true,
  });
  const remoteVoidedEarlier = makeTx({
    status: 'voided',
    voidReason: 'Voided by owner on web',
    updatedAt: '2026-10-24T12:00:00Z',
  });
  const plan2 = planTransactionsMerge([localEditedLater], [remoteVoidedEarlier]);
  assert.equal(plan2.cloudPushOperations.length, 0);
  assert.equal(plan2.toUpsertInLocal.length, 1);
  assert.equal(plan2.toUpsertInLocal[0].status, 'voided');
});

test('Phase 2 Sync: Offline-created and offline-voided entry uses two-step CreatePostedThenVoid to satisfy firestore.rules', () => {
  const offlineVoid = makeTx({
    id: 'tx_offline_1',
    status: 'voided',
    voidReason: 'Entered twice offline',
    pendingCloudSync: true,
  });
  const plan = planTransactionsMerge([offlineVoid], []);
  assert.equal(plan.cloudPushOperations.length, 1);
  assert.equal(plan.cloudPushOperations[0].kind, 'CreatePostedThenVoid');
});

test('Phase 2 Sync: Idempotency key deduplicates records across Web and Android without deleting anything', () => {
  const localOnly = makeTx({ id: 'tx_local_only', idempotencyKey: 'idem_local_only', pendingCloudSync: true });
  const sharedLocal = makeTx({ id: 'tx_android_dup', idempotencyKey: 'idem_shared_99' });
  const sharedRemote = makeTx({ id: 'tx_web_dup', idempotencyKey: 'idem_shared_99' });
  const remoteOnly = makeTx({ id: 'tx_remote_only', idempotencyKey: 'idem_remote_only' });

  const plan = planTransactionsMerge([localOnly, sharedLocal], [sharedRemote, remoteOnly]);
  assert.equal(plan.toUpsertInLocal.length, 1);
  assert.equal(plan.toUpsertInLocal[0].id, 'tx_remote_only');
  assert.equal(plan.cloudPushOperations.length, 1);
  assert.equal(plan.cloudPushOperations[0].tx.id, 'tx_local_only');
});

test('Phase 2 Architecture: Android Credential Manager, Firebase config, and Firestore rules contract match', () => {
  const root = process.cwd();
  const appGradle = fs.readFileSync(path.join(root, 'android-app/app/build.gradle.kts'), 'utf8');
  const firebaseConfig = JSON.parse(fs.readFileSync(path.join(root, 'firebase-applet-config.json'), 'utf8'));
  const authMgr = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/security/GoogleAuthManager.kt'),
    'utf8'
  );
  const syncMgr = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/data/remote/FirestoreSyncManager.kt'),
    'utf8'
  );
  const syncWorker = fs.readFileSync(
    path.join(root, 'android-app/app/src/main/java/com/vumiq/cashbook/data/remote/CloudSyncWorker.kt'),
    'utf8'
  );

  // Android BuildConfig targets the exact same Firebase project & named database as Web Cashbook
  assert.equal(appGradle.includes(firebaseConfig.projectId), true, 'Android FIREBASE_PROJECT_ID must match Web');
  assert.equal(
    appGradle.includes(firebaseConfig.firestoreDatabaseId),
    true,
    'Android FIRESTORE_DATABASE_ID must match Web'
  );

  // Native Google Sign-In uses Jetpack Credential Manager + GoogleIdTokenCredential
  assert.match(authMgr, /CredentialManager\.create/);
  assert.match(authMgr, /GetGoogleIdOption\.Builder/);
  assert.match(authMgr, /GoogleIdTokenCredential\.createFrom/);
  assert.match(authMgr, /GoogleAuthProvider\.getCredential/);
  assert.match(authMgr, /isEmailVerified/);
  assert.match(authMgr, /ClearCredentialStateRequest/);

  // FirestoreSyncManager enforces Pillar 8 ownerId filter on all 6 collection/subcollection list queries
  const ownerFilterMatches = [...syncMgr.matchAll(/\.whereEqualTo\("ownerId",\s*uid\)/g)];
  assert.equal(
    ownerFilterMatches.length >= 6,
    true,
    `Expected >= 6 ownerId list filters for businesses/accounts/categories/parties/transactions/auditEvents, found ${ownerFilterMatches.length}`
  );

  // Never calls .delete() on Firestore documents
  assert.equal(syncMgr.includes('.delete()'), false, 'FirestoreSyncManager must never silently delete financial records');

  // Durable offline queue via WorkManager with NetworkType.CONNECTED + exponential backoff
  assert.match(syncWorker, /NetworkType\.CONNECTED/);
  assert.match(syncWorker, /BackoffPolicy\.EXPONENTIAL/);
});
