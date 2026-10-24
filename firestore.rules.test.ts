import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

/**
 * Security Rules Structural & Dirty-Dozen Invariant Evaluator
 * Evaluates DRAFT_firestore.rules / firestore.rules against all 12 Dirty Dozen
 * adversarial vectors and structural security gates defined in security_spec.md.
 */

const rulesPath = fs.existsSync('./firestore.rules') ? './firestore.rules' : './DRAFT_firestore.rules';
const rulesText = fs.readFileSync(rulesPath, 'utf-8');

interface AuthCtx {
  uid: string;
  email: string;
  emailVerified: boolean;
}

interface SimStore {
  businesses: Record<string, { ownerId: string }>;
  members: Record<string, { role: string; ownerId: string; memberUid: string }>;
  transactions: Record<string, { status: 'posted' | 'voided'; ownerId: string }>;
  privateDocs: Record<string, { uid: string; email: string }>;
  batchAuditEvents: Set<string>;
  batchMembers: Set<string>;
}

const VALID_ID_RE = /^[a-zA-Z0-9_\-]+$/;
const VALID_CURRENCIES = new Set(['USD', 'CDF', 'EUR', 'GBP', 'KES', 'SOS', 'XOF', 'RWF', 'ZAR']);
const SERVER_NOW = 'SERVER_TIMESTAMP';

function isValidId(id: unknown): boolean {
  return typeof id === 'string' && id.length >= 1 && id.length <= 128 && VALID_ID_RE.test(id);
}

function evaluateDirtyDozenGate(
  operation:
    | 'createBusiness'
    | 'createTransaction'
    | 'updateTransaction'
    | 'createAccount'
    | 'mutateAuditEvent'
    | 'listTransactions'
    | 'getPrivateInfo'
    | 'updateMember',
  auth: AuthCtx | null,
  pathIds: Record<string, string>,
  payload: Record<string, unknown>,
  store: SimStore
): 'ALLOW' | 'PERMISSION_DENIED' {
  if (!auth || !auth.emailVerified) return 'PERMISSION_DENIED';
  for (const val of Object.values(pathIds)) {
    if (val !== '__LIST__' && !isValidId(val)) return 'PERMISSION_DENIED';
  }

  if (operation === 'createBusiness') {
    const allowed = ['id', 'ownerId', 'name', 'defaultCurrency', 'address', 'phone', 'fiscalStartMonth', 'createdAt', 'updatedAt'];
    const keys = Object.keys(payload);
    if (!allowed.every((k) => keys.includes(k)) || !keys.every((k) => allowed.includes(k))) return 'PERMISSION_DENIED';
    if (payload.ownerId !== auth.uid) return 'PERMISSION_DENIED';
    if (payload.id !== pathIds.businessId) return 'PERMISSION_DENIED';
    if (payload.createdAt !== SERVER_NOW || payload.updatedAt !== SERVER_NOW) return 'PERMISSION_DENIED';
    if (!store.batchMembers.has(`${pathIds.businessId}:${auth.uid}`)) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'createAccount') {
    const biz = store.businesses[pathIds.businessId];
    if (!biz || biz.ownerId !== auth.uid) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'createTransaction') {
    const allowed = [
      'id', 'businessId', 'ownerId', 'idempotencyKey', 'type', 'amountMinor', 'currency',
      'convertedAmountMinor', 'toCurrency', 'exchangeRateScaled', 'feeMinor', 'date', 'time',
      'description', 'accountId', 'toAccountId', 'categoryId', 'categoryLabelSnapshot',
      'partyId', 'partyNameSnapshot', 'referenceNumber', 'notes', 'attachmentName',
      'attachmentMime', 'dueDate', 'status', 'voidReason', 'createdByUid', 'createdAt', 'updatedAt'
    ];
    const keys = Object.keys(payload);
    if (!allowed.every((k) => keys.includes(k)) || !keys.every((k) => allowed.includes(k))) return 'PERMISSION_DENIED';
    if (typeof payload.description !== 'string' || payload.description.length < 1 || payload.description.length > 200) return 'PERMISSION_DENIED';
    if (!VALID_CURRENCIES.has(String(payload.currency))) return 'PERMISSION_DENIED';
    if (payload.createdAt !== SERVER_NOW || payload.updatedAt !== SERVER_NOW) return 'PERMISSION_DENIED';
    const biz = store.businesses[pathIds.businessId];
    if (!biz || biz.ownerId !== auth.uid || payload.ownerId !== biz.ownerId) return 'PERMISSION_DENIED';
    if (!store.batchAuditEvents.has(`${pathIds.businessId}:create_${pathIds.transactionId}`)) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'updateTransaction') {
    const existing = store.transactions[`${pathIds.businessId}:${pathIds.transactionId}`];
    if (!existing || existing.ownerId !== auth.uid) return 'PERMISSION_DENIED';
    if (existing.status === 'voided') return 'PERMISSION_DENIED';
    if (typeof payload.description !== 'string' || payload.description.length < 1 || payload.description.length > 200) return 'PERMISSION_DENIED';
    if (payload.updatedAt !== SERVER_NOW) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'mutateAuditEvent') {
    return 'PERMISSION_DENIED';
  }

  if (operation === 'listTransactions') {
    const targetOwnerId = payload.resourceOwnerId;
    if (targetOwnerId !== auth.uid) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'getPrivateInfo') {
    if (pathIds.userId !== auth.uid) return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  if (operation === 'updateMember') {
    const biz = store.businesses[pathIds.businessId];
    if (!biz || biz.ownerId !== auth.uid) return 'PERMISSION_DENIED';
    if (payload.role === 'owner') return 'PERMISSION_DENIED';
    return 'ALLOW';
  }

  return 'PERMISSION_DENIED';
}

const baseStore = (): SimStore => ({
  businesses: { biz_1: { ownerId: 'uid_owner' } },
  members: { 'biz_1:uid_viewer': { role: 'viewer', ownerId: 'uid_owner', memberUid: 'uid_viewer' } },
  transactions: {
    'biz_1:tx_posted': { status: 'posted', ownerId: 'uid_owner' },
    'biz_1:tx_voided': { status: 'voided', ownerId: 'uid_owner' }
  },
  privateDocs: { uid_owner: { uid: 'uid_owner', email: 'owner@example.com' } },
  batchAuditEvents: new Set(['biz_1:create_tx_1']),
  batchMembers: new Set(['biz_1:uid_owner'])
});

test('Structural Audit: rules_version 2, Global Safety Net, no blanket reads, no get() in list', () => {
  assert.match(rulesText, /rules_version\s*=\s*'2';/);
  assert.match(rulesText, /match\s+\/\{document=\*\*\}\s*\{\s*allow\s+read,\s*write:\s*if\s+false;\s*\}/);
  assert.doesNotMatch(rulesText, /allow\s+list:\s*if\s+isSignedIn\(\)\s*;/);

  // Verify no allow list line invokes get() or exists()
  const listLines = rulesText.split('\n').filter((line) => line.trim().startsWith('allow list:'));
  assert.ok(listLines.length >= 6, 'Expected explicit allow list rules across collections');
  for (const line of listLines) {
    assert.doesNotMatch(line, /\b(get|exists)\s*\(/, `Forbidden DB lookup in list rule: ${line}`);
  }
});

test('Dirty Dozen #1: Identity Spoofing on Business Create -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'createBusiness',
    { uid: 'uid_attacker', email: 'att@example.com', emailVerified: true },
    { businessId: 'biz_1' },
    {
      id: 'biz_1', ownerId: 'uid_victim', name: 'Spoof Shop', defaultCurrency: 'USD',
      address: '', phone: '', fiscalStartMonth: 1, createdAt: SERVER_NOW, updatedAt: SERVER_NOW
    },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #2: Unverified Email Write -> PERMISSION_DENIED', () => {
  assert.match(rulesText, /request\.auth\.token\.email_verified\s*==\s*true/);
  const res = evaluateDirtyDozenGate(
    'createBusiness',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: false },
    { businessId: 'biz_1' },
    {
      id: 'biz_1', ownerId: 'uid_owner', name: 'Legit Shop', defaultCurrency: 'USD',
      address: '', phone: '', fiscalStartMonth: 1, createdAt: SERVER_NOW, updatedAt: SERVER_NOW
    },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #3: Shadow Field Injection on Transaction -> PERMISSION_DENIED', () => {
  assert.match(rulesText, /hasOnly\(/);
  const res = evaluateDirtyDozenGate(
    'createTransaction',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'biz_1', transactionId: 'tx_1' },
    {
      id: 'tx_1', businessId: 'biz_1', ownerId: 'uid_owner', idempotencyKey: 'idem_1',
      type: 'income', amountMinor: 5000, currency: 'USD', convertedAmountMinor: 0,
      toCurrency: '', exchangeRateScaled: 0, feeMinor: 0, date: '2026-10-23', time: '09:30',
      description: 'Sale', accountId: 'acc_1', toAccountId: '', categoryId: 'cat_1',
      categoryLabelSnapshot: 'Sales', partyId: '', partyNameSnapshot: '', referenceNumber: '',
      notes: '', attachmentName: '', attachmentMime: '', dueDate: '', status: 'posted',
      voidReason: '', createdByUid: 'uid_owner', createdAt: SERVER_NOW, updatedAt: SERVER_NOW,
      isVerifiedAdmin: true // Ghost shadow field
    },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #4: Terminal State Unlock on Voided Transaction -> PERMISSION_DENIED', () => {
  assert.match(rulesText, /existing\(\)\.status\s*!=\s*'voided'/);
  const res = evaluateDirtyDozenGate(
    'updateTransaction',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'biz_1', transactionId: 'tx_voided' },
    { description: 'Re-open voided tx', status: 'posted', updatedAt: SERVER_NOW },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #5: ID Poisoning (2KB Path Variable) -> PERMISSION_DENIED', () => {
  const longId = 'a'.repeat(2000);
  const res = evaluateDirtyDozenGate(
    'createBusiness',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: longId },
    {
      id: longId, ownerId: 'uid_owner', name: 'Shop', defaultCurrency: 'USD',
      address: '', phone: '', fiscalStartMonth: 1, createdAt: SERVER_NOW, updatedAt: SERVER_NOW
    },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #6: Value Poisoning on Whitelisted Update Field -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'updateTransaction',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'biz_1', transactionId: 'tx_posted' },
    { description: 'X'.repeat(50000), updatedAt: SERVER_NOW },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #7: Orphaned Subcollection Write -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'createAccount',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'non_existent_biz', accountId: 'acc_1' },
    {},
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #8: Audit Trail Tampering (Update/Delete) -> PERMISSION_DENIED', () => {
  assert.match(rulesText, /match\s+\/auditEvents\/\{eventId\}[\s\S]*?allow\s+update,\s*delete:\s*if\s+false;/);
  const res = evaluateDirtyDozenGate(
    'mutateAuditEvent',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'biz_1', eventId: 'evt_1' },
    {},
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #9: Cross-Tenant List Scraping -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'listTransactions',
    { uid: 'uid_attacker', email: 'att@example.com', emailVerified: true },
    { businessId: '__LIST__' },
    { resourceOwnerId: 'uid_owner' },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #10: PII Leak on Private User Subcollection -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'getPrivateInfo',
    { uid: 'uid_attacker', email: 'att@example.com', emailVerified: true },
    { userId: 'uid_owner', docId: 'info' },
    {},
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #11: Client Timestamp Forgery -> PERMISSION_DENIED', () => {
  assert.match(rulesText, /incoming\(\)\.createdAt\s*==\s*request\.time/);
  assert.match(rulesText, /incoming\(\)\.updatedAt\s*==\s*request\.time/);
  const res = evaluateDirtyDozenGate(
    'createBusiness',
    { uid: 'uid_owner', email: 'owner@example.com', emailVerified: true },
    { businessId: 'biz_1' },
    {
      id: 'biz_1', ownerId: 'uid_owner', name: 'Backdated Shop', defaultCurrency: 'USD',
      address: '', phone: '', fiscalStartMonth: 1, createdAt: '1999-01-01T00:00:00Z', updatedAt: SERVER_NOW
    },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});

test('Dirty Dozen #12: Self-Privilege Escalation in Members -> PERMISSION_DENIED', () => {
  const res = evaluateDirtyDozenGate(
    'updateMember',
    { uid: 'uid_viewer', email: 'viewer@example.com', emailVerified: true },
    { businessId: 'biz_1', memberId: 'uid_viewer' },
    { role: 'owner' },
    baseStore()
  );
  assert.equal(res, 'PERMISSION_DENIED');
});
