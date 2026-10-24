import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { computeAccountBalances, computePartyBalances } from './accounting';
import type { Account, CurrencyCode, Party, Transaction } from '../types/cashbook';

const ROOT = process.cwd();

function readText(relPath: string): string {
  return fs.readFileSync(path.join(ROOT, relPath), 'utf8');
}

type ReminderStage = 'DUE_SOON' | 'DUE_TODAY' | 'OVERDUE';
type ReminderDirection = 'RECEIVABLE' | 'PAYABLE';

interface DueDateReminderCandidate {
  businessId: string;
  businessName: string;
  partyId: string;
  partyName: string;
  partyType: Party['partyType'];
  direction: ReminderDirection;
  currency: CurrencyCode;
  outstandingMinor: number;
  dueDateIso: string;
  daysUntilDue: number;
  stage: ReminderStage;
  dedupKey: string;
  notificationId: number;
}

const DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;

function parseIsoDayUtc(iso: string): number | null {
  const clean = iso.trim();
  if (!DATE_REGEX.test(clean)) return null;
  const [y, m, d] = clean.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (
    dt.getUTCFullYear() !== y ||
    dt.getUTCMonth() !== m - 1 ||
    dt.getUTCDate() !== d
  ) {
    return null;
  }
  return Math.floor(dt.getTime() / 86_400_000);
}

function notificationIdForParty(partyId: string, direction: ReminderDirection): number {
  const s = `vumiq_due_${partyId}_${direction}`;
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return Math.max(10_000, h & 0x7fffffff);
}

/**
 * Mirrors DueDateReminderEvaluator.classifyPartyDueStatus in Kotlin.
 */
function classifyPartyDueStatus(
  dueDateIso: string,
  hasPositiveBalance: boolean,
  todayIso: string,
  leadDays = 3
): { stage: ReminderStage; daysUntilDue: number } | null {
  if (!hasPositiveBalance) return null;
  const todayEpoch = parseIsoDayUtc(todayIso);
  const dueEpoch = parseIsoDayUtc(dueDateIso);
  if (todayEpoch === null || dueEpoch === null) return null;
  const daysUntil = dueEpoch - todayEpoch;
  const safeLead = Math.min(30, Math.max(0, leadDays));
  if (daysUntil < 0) return { stage: 'OVERDUE', daysUntilDue: daysUntil };
  if (daysUntil === 0) return { stage: 'DUE_TODAY', daysUntilDue: 0 };
  if (daysUntil <= safeLead) return { stage: 'DUE_SOON', daysUntilDue: daysUntil };
  return null;
}

/**
 * Mirrors DueDateReminderEvaluator.evaluateBusinessParties in Kotlin.
 */
function evaluateBusinessParties(
  business: { id: string; name: string },
  parties: Party[],
  transactions: Transaction[],
  todayIso: string,
  leadDays: number,
  alreadySentDedupKeys: Set<string>
): { candidates: DueDateReminderCandidate[]; stillActiveNotificationIds: Set<number> } {
  const todayEpoch = parseIsoDayUtc(todayIso);
  if (todayEpoch === null) {
    return { candidates: [], stillActiveNotificationIds: new Set() };
  }
  const safeLead = Math.min(30, Math.max(0, leadDays));
  const balances = computePartyBalances(parties, transactions);

  const candidates: DueDateReminderCandidate[] = [];
  const stillActiveNotificationIds = new Set<number>();

  for (const party of parties) {
    if (party.status !== 'active') continue;
    const cleanDue = (party.dueDate ?? '').trim();
    const dueEpoch = parseIsoDayUtc(cleanDue);
    if (dueEpoch === null) continue;
    const daysUntil = dueEpoch - todayEpoch;
    if (daysUntil > safeLead) continue;

    const stage: ReminderStage =
      daysUntil < 0 ? 'OVERDUE' : daysUntil === 0 ? 'DUE_TODAY' : 'DUE_SOON';

    const computed = balances[party.id];
    const recMinor = computed?.receivableByCurrency?.[party.currency] ?? 0;
    const payMinor = computed?.payableByCurrency?.[party.currency] ?? 0;

    if (recMinor > 0) {
      const notifId = notificationIdForParty(party.id, 'RECEIVABLE');
      stillActiveNotificationIds.add(notifId);
      const dedupKey = `${party.id}|${cleanDue}|${stage}|RECEIVABLE|${recMinor}|${todayIso}`;
      if (!alreadySentDedupKeys.has(dedupKey)) {
        candidates.push({
          businessId: business.id,
          businessName: business.name,
          partyId: party.id,
          partyName: party.name,
          partyType: party.partyType,
          direction: 'RECEIVABLE',
          currency: party.currency,
          outstandingMinor: recMinor,
          dueDateIso: cleanDue,
          daysUntilDue: daysUntil,
          stage,
          dedupKey,
          notificationId: notifId,
        });
      }
    }

    if (payMinor > 0) {
      const notifId = notificationIdForParty(party.id, 'PAYABLE');
      stillActiveNotificationIds.add(notifId);
      const dedupKey = `${party.id}|${cleanDue}|${stage}|PAYABLE|${payMinor}|${todayIso}`;
      if (!alreadySentDedupKeys.has(dedupKey)) {
        candidates.push({
          businessId: business.id,
          businessName: business.name,
          partyId: party.id,
          partyName: party.name,
          partyType: party.partyType,
          direction: 'PAYABLE',
          currency: party.currency,
          outstandingMinor: payMinor,
          dueDateIso: cleanDue,
          daysUntilDue: daysUntil,
          stage,
          dedupKey,
          notificationId: notifId,
        });
      }
    }
  }

  return { candidates, stillActiveNotificationIds };
}

function pruneDedupKeysForToday(existingKeys: Set<string>, todayIso: string): Set<string> {
  const out = new Set<string>();
  for (const key of existingKeys) {
    if (key.endsWith(`|${todayIso}`)) out.add(key);
  }
  return out;
}

/**
 * Mirrors DueDateReminderWorker.computeInitialDelayMinutes in Kotlin.
 */
function computeInitialDelayMinutes(
  preferredHour: number,
  nowHour: number,
  nowMinute: number
): number {
  const safeHour = Math.min(23, Math.max(0, preferredHour));
  const nowTotalMinutes = nowHour * 60 + nowMinute;
  let targetTotalMinutes = safeHour * 60;
  if (targetTotalMinutes <= nowTotalMinutes) {
    targetTotalMinutes += 24 * 60;
  }
  return Math.max(1, targetTotalMinutes - nowTotalMinutes);
}

test('Phase 4A: classifyPartyDueStatus handles OVERDUE, DUE_TODAY, DUE_SOON, future, invalid, and settled states', () => {
  assert.deepEqual(classifyPartyDueStatus('2026-10-20', true, '2026-10-24', 3), {
    stage: 'OVERDUE',
    daysUntilDue: -4,
  });
  assert.deepEqual(classifyPartyDueStatus('2026-10-24', true, '2026-10-24', 3), {
    stage: 'DUE_TODAY',
    daysUntilDue: 0,
  });
  assert.deepEqual(classifyPartyDueStatus('2026-10-27', true, '2026-10-24', 3), {
    stage: 'DUE_SOON',
    daysUntilDue: 3,
  });
  // Beyond lead window -> null
  assert.equal(classifyPartyDueStatus('2026-10-28', true, '2026-10-24', 3), null);
  // Zero / settled balance -> null even when overdue
  assert.equal(classifyPartyDueStatus('2026-10-10', false, '2026-10-24', 3), null);
  // Undated or malformed date -> null
  assert.equal(classifyPartyDueStatus('', true, '2026-10-24', 3), null);
  assert.equal(classifyPartyDueStatus('2026-02-30', true, '2026-10-24', 3), null);
});

test('Phase 4B: Read-only evaluation preserves all cash & party balances and cancels settled/archived notifications', () => {
  const accounts: Account[] = [
    {
      id: 'acc_cash',
      businessId: 'biz_1',
      ownerId: 'uid_1',
      name: 'Main Safe',
      accountType: 'cash',
      currency: 'USD',
      openingBalanceMinor: 500_000,
      status: 'active',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    },
  ];

  const parties: Party[] = [
    {
      id: 'pty_cust_overdue',
      businessId: 'biz_1',
      ownerId: 'uid_1',
      name: 'Hargeisa Wholesale Co.',
      phone: '+252615000111',
      address: '',
      partyType: 'customer',
      currency: 'USD',
      openingReceivableMinor: 45_000, // $450.00 receivable
      openingPayableMinor: 0,
      dueDate: '2026-10-22', // 2 days overdue on 2026-10-24
      notes: '',
      status: 'active',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    },
    {
      id: 'pty_supp_due_soon',
      businessId: 'biz_1',
      ownerId: 'uid_1',
      name: 'Mogadishu Port Logistics',
      phone: '+252615000222',
      address: '',
      partyType: 'supplier',
      currency: 'USD',
      openingReceivableMinor: 0,
      openingPayableMinor: 120_000, // $1,200.00 payable
      dueDate: '2026-10-26', // due in 2 days
      notes: '',
      status: 'active',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    },
    {
      id: 'pty_archived',
      businessId: 'biz_1',
      ownerId: 'uid_1',
      name: 'Archived Client',
      phone: '',
      address: '',
      partyType: 'customer',
      currency: 'USD',
      openingReceivableMinor: 30_000,
      openingPayableMinor: 0,
      dueDate: '2026-10-20',
      notes: '',
      status: 'archived',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    },
    {
      id: 'pty_undated',
      businessId: 'biz_1',
      ownerId: 'uid_1',
      name: 'Undated Customer',
      phone: '',
      address: '',
      partyType: 'customer',
      currency: 'USD',
      openingReceivableMinor: 15_000,
      openingPayableMinor: 0,
      dueDate: '',
      notes: '',
      status: 'active',
      createdAt: '2026-10-01T08:00:00Z',
      updatedAt: '2026-10-01T08:00:00Z',
    },
  ];

  const initialTransactions: Transaction[] = [];
  const cashBefore = JSON.stringify(computeAccountBalances(accounts, initialTransactions));
  const partyBefore = JSON.stringify(computePartyBalances(parties, initialTransactions));

  const run1 = evaluateBusinessParties(
    { id: 'biz_1', name: 'VUMIQ Trading' },
    parties,
    initialTransactions,
    '2026-10-24',
    3,
    new Set()
  );

  // Read-only guarantee: exact ledger balances untouched
  assert.equal(JSON.stringify(computeAccountBalances(accounts, initialTransactions)), cashBefore);
  assert.equal(JSON.stringify(computePartyBalances(parties, initialTransactions)), partyBefore);

  assert.equal(run1.candidates.length, 2);
  assert.equal(run1.candidates[0].partyId, 'pty_cust_overdue');
  assert.equal(run1.candidates[0].direction, 'RECEIVABLE');
  assert.equal(run1.candidates[0].stage, 'OVERDUE');
  assert.equal(run1.candidates[0].outstandingMinor, 45_000);

  assert.equal(run1.candidates[1].partyId, 'pty_supp_due_soon');
  assert.equal(run1.candidates[1].direction, 'PAYABLE');
  assert.equal(run1.candidates[1].stage, 'DUE_SOON');
  assert.equal(run1.candidates[1].outstandingMinor, 120_000);

  // Duplicate suppression on same day (e.g. after reboot or timezone change)
  const sentKeys = new Set(run1.candidates.map((c) => c.dedupKey));
  const run2SameDay = evaluateBusinessParties(
    { id: 'biz_1', name: 'VUMIQ Trading' },
    parties,
    initialTransactions,
    '2026-10-24',
    3,
    sentKeys
  );
  assert.equal(run2SameDay.candidates.length, 0, 'Must not spam duplicate notifications on same day');
  assert.equal(
    run2SameDay.stillActiveNotificationIds.size,
    2,
    'Active tray notifications remain valid while balances are still unpaid'
  );

  // Settle customer receivable in full ($450.00 receivable_payment)
  const settlementTx: Transaction = {
    id: 'tx_settle_cust',
    businessId: 'biz_1',
    ownerId: 'uid_1',
    type: 'receivable_payment',
    status: 'posted',
    currency: 'USD',
    amountMinor: 45_000,
    convertedAmountMinor: 0,
    toCurrency: '',
    exchangeRateScaled: 0,
    feeMinor: 0,
    accountId: 'acc_cash',
    toAccountId: '',
    categoryId: '',
    partyId: 'pty_cust_overdue',
    description: 'Full invoice settlement',
    referenceNumber: 'RCP-101',
    notes: '',
    attachmentName: '',
    attachmentMimeType: '',
    date: '2026-10-24',
    time: '11:00',
    dueDate: '',
    voidReason: '',
    voidedAt: '',
    voidedBy: '',
    idempotencyKey: 'idem_settle_101',
    createdBy: 'uid_1',
    updatedBy: 'uid_1',
    createdAt: '2026-10-24T11:00:00Z',
    updatedAt: '2026-10-24T11:00:00Z',
  };

  const runAfterSettlement = evaluateBusinessParties(
    { id: 'biz_1', name: 'VUMIQ Trading' },
    parties,
    [settlementTx],
    '2026-10-24',
    3,
    sentKeys
  );

  const custNotifId = notificationIdForParty('pty_cust_overdue', 'RECEIVABLE');
  const suppNotifId = notificationIdForParty('pty_supp_due_soon', 'PAYABLE');
  assert.equal(
    runAfterSettlement.stillActiveNotificationIds.has(custNotifId),
    false,
    'Settled customer notification ID must be dropped so DueDateReminderWorker cancels it immediately'
  );
  assert.equal(runAfterSettlement.stillActiveNotificationIds.has(suppNotifId), true);
});

test('Phase 4C: pruneDedupKeysForToday removes stale days and computeInitialDelayMinutes handles local clock wrap', () => {
  const keys = new Set([
    'pty_1|2026-10-22|OVERDUE|RECEIVABLE|45000|2026-10-23',
    'pty_1|2026-10-22|OVERDUE|RECEIVABLE|45000|2026-10-24',
  ]);
  const pruned = pruneDedupKeysForToday(keys, '2026-10-24');
  assert.equal(pruned.size, 1);
  assert.equal(pruned.has('pty_1|2026-10-22|OVERDUE|RECEIVABLE|45000|2026-10-24'), true);

  // 07:30 local -> 09:00 preferred = 90 minutes
  assert.equal(computeInitialDelayMinutes(9, 7, 30), 90);
  // 09:00 exact -> schedules for tomorrow (1440 minutes)
  assert.equal(computeInitialDelayMinutes(9, 9, 0), 1440);
  // 14:15 local -> 09:00 tomorrow = 1125 minutes
  assert.equal(computeInitialDelayMinutes(9, 14, 15), 1125);
});

test('Phase 4D: Android Manifest, BroadcastReceiver, WorkManager, and UI due-date reminder files are properly wired', () => {
  const manifest = readText('android-app/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android\.permission\.POST_NOTIFICATIONS/);
  assert.match(manifest, /android\.permission\.RECEIVE_BOOT_COMPLETED/);
  assert.match(manifest, /android:name="\.reminders\.BootAndTimeChangeReceiver"/);
  assert.match(manifest, /android\.intent\.action\.BOOT_COMPLETED/);
  assert.match(manifest, /android\.intent\.action\.MY_PACKAGE_REPLACED/);
  assert.match(manifest, /android\.intent\.action\.TIME_SET/);
  assert.match(manifest, /android\.intent\.action\.TIMEZONE_CHANGED/);

  const evaluatorKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/domain/DueDateReminderEvaluator.kt'
  );
  assert.match(evaluatorKt, /AccountingEngine\.computePartyBalances/);
  assert.match(evaluatorKt, /pruneDedupKeysForToday/);

  const workerKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/reminders/DueDateReminderWorker.kt'
  );
  assert.match(workerKt, /Manifest\.permission\.POST_NOTIFICATIONS/);
  assert.match(workerKt, /reminderPrefs\.activeNotificationIds - allStillActiveIds/);
  assert.match(workerKt, /ZonedDateTime\.now\(\)/);

  const partiesKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/ui/screens/PartiesScreen.kt'
  );
  assert.match(partiesKt, /DueDateReminderEvaluator\.classifyPartyDueStatus/);
  assert.match(partiesKt, /onUpdatePartyDueDate/);

  const settingsKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/ui/screens/SettingsView.kt'
  );
  assert.match(settingsKt, /Manifest\.permission\.POST_NOTIFICATIONS/);
  assert.match(settingsKt, /onToggleDueReminders/);
});
