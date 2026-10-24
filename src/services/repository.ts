import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';
import {
  Account,
  AuditEvent,
  BackupArchive,
  BLUEPRINT_CONSTRAINTS,
  Business,
  BusinessMember,
  Category,
  CurrencyCode,
  LocaleCode,
  MemberRole,
  Party,
  ThemePreference,
  Transaction,
  TransactionType,
  UserProfile,
} from '../types/cashbook';
import {
  computeBackupChecksum,
  createDefaultCategories,
  ROLE_PERMISSIONS,
  validateBackupArchive,
  validateTransactionDraft,
} from '../engine/accounting';
import { formatMinorAmount } from '../engine/money';
import { auth, db, handleFirestoreError, isTransientOfflineFirestoreError, OperationType } from './firebase';

/**
 * Normalized Local Storage Table Keys (Guest / Offline-Only Mode)
 * Uses separate normalized entity collections with foreign keys rather than a single monolithic JSON blob.
 */
const LOCAL_TABLES = {
  MODE: 'vumiq_session_mode_v1', // 'guest' | 'cloud' | ''
  PREFS: 'vumiq_table_user_prefs_v1',
  BUSINESSES: 'vumiq_table_businesses_v1',
  MEMBERS: 'vumiq_table_members_v1',
  ACCOUNTS: 'vumiq_table_accounts_v1',
  CATEGORIES: 'vumiq_table_categories_v1',
  PARTIES: 'vumiq_table_parties_v1',
  TRANSACTIONS: 'vumiq_table_transactions_v1',
  AUDITS: 'vumiq_table_audits_v1',
} as const;

export const GUEST_ACTOR_UID = 'guest_local_owner';

function generateCleanId(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `${prefix}_${Date.now()}_${rand}`;
}

function toIsoTimestamp(val: unknown): string {
  if (!val) return new Date().toISOString();
  if (typeof val === 'string') return val;
  if (val instanceof Timestamp) return val.toDate().toISOString();
  if (typeof val === 'object' && val !== null && 'toDate' in val && typeof (val as { toDate: () => Date }).toDate === 'function') {
    return (val as { toDate: () => Date }).toDate().toISOString();
  }
  return new Date().toISOString();
}

function readLocalTable<T>(key: string): T[] {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as T[]) : [];
  } catch {
    return [];
  }
}

function writeLocalTable<T>(key: string, rows: T[]): void {
  localStorage.setItem(key, JSON.stringify(rows));
}

export function getSavedSessionMode(): 'guest' | 'cloud' | '' {
  try {
    const val = localStorage.getItem(LOCAL_TABLES.MODE);
    return val === 'guest' || val === 'cloud' ? val : '';
  } catch {
    return '';
  }
}

export function setSavedSessionMode(mode: 'guest' | 'cloud' | ''): void {
  try {
    if (!mode) localStorage.removeItem(LOCAL_TABLES.MODE);
    else localStorage.setItem(LOCAL_TABLES.MODE, mode);
  } catch {
    // ignore storage quota errors
  }
}

export function loadLocalPreferences(): UserProfile {
  try {
    const raw = localStorage.getItem(LOCAL_TABLES.PREFS);
    if (raw) {
      return JSON.parse(raw) as UserProfile;
    }
  } catch {
    // fallback default
  }
  const now = new Date().toISOString();
  return {
    uid: GUEST_ACTOR_UID,
    displayName: 'Local Business Owner',
    defaultBusinessId: '',
    theme: 'system',
    locale: 'en',
    notificationsEnabled: true,
    createdAt: now,
    updatedAt: now,
  };
}

export function saveLocalPreferences(prefs: Partial<UserProfile>): UserProfile {
  const current = loadLocalPreferences();
  const next: UserProfile = {
    ...current,
    ...prefs,
    updatedAt: new Date().toISOString(),
  };
  try {
    localStorage.setItem(LOCAL_TABLES.PREFS, JSON.stringify(next));
  } catch {
    // ignore
  }
  return next;
}

export interface WorkspaceBundle {
  businesses: Business[];
  activeBusiness: Business | null;
  members: BusinessMember[];
  currentUserRole: MemberRole;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  auditEvents: AuditEvent[];
  hasPendingWrites: boolean;
}

/**
 * Bootstraps or updates the authenticated user's public profile and isolated PII email document in Firestore.
 */
export async function syncCloudUserProfile(user: {
  uid: string;
  displayName: string | null;
  email: string | null;
}): Promise<UserProfile> {
  const userPath = `users/${user.uid}`;
  const privatePath = `users/${user.uid}/private/info`;
  const userRef = doc(db, 'users', user.uid);
  const privateRef = doc(db, 'users', user.uid, 'private', 'info');

  try {
    const snap = await getDoc(userRef);
    const localPrefs = loadLocalPreferences();
    if (!snap.exists()) {
      const displayName = (user.displayName || user.email?.split('@')[0] || 'VUMIQ User').slice(
        0,
        BLUEPRINT_CONSTRAINTS.USER_DISPLAY_NAME_MAX
      );
      await setDoc(userRef, {
        uid: user.uid,
        displayName,
        defaultBusinessId: '',
        theme: localPrefs.theme,
        locale: localPrefs.locale,
        notificationsEnabled: true,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
      if (user.email) {
        await setDoc(privateRef, {
          uid: user.uid,
          email: user.email.slice(0, BLUEPRINT_CONSTRAINTS.USER_EMAIL_MAX),
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
      const now = new Date().toISOString();
      return {
        uid: user.uid,
        displayName,
        defaultBusinessId: '',
        theme: localPrefs.theme,
        locale: localPrefs.locale,
        notificationsEnabled: true,
        createdAt: now,
        updatedAt: now,
      };
    }

    const data = snap.data();
    return {
      uid: String(data.uid),
      displayName: String(data.displayName || 'VUMIQ User'),
      defaultBusinessId: String(data.defaultBusinessId || ''),
      theme: (data.theme as ThemePreference) || 'system',
      locale: (data.locale as LocaleCode) || 'en',
      notificationsEnabled: Boolean(data.notificationsEnabled ?? true),
      createdAt: toIsoTimestamp(data.createdAt),
      updatedAt: toIsoTimestamp(data.updatedAt),
    };
  } catch (error) {
    if (isTransientOfflineFirestoreError(error)) {
      const localPrefs = loadLocalPreferences();
      const now = new Date().toISOString();
      return {
        uid: user.uid,
        displayName: user.displayName || user.email?.split('@')[0] || localPrefs.displayName || 'VUMIQ User',
        defaultBusinessId: localPrefs.defaultBusinessId,
        theme: localPrefs.theme,
        locale: localPrefs.locale,
        notificationsEnabled: localPrefs.notificationsEnabled,
        createdAt: now,
        updatedAt: now,
      };
    }
    handleFirestoreError(error, OperationType.WRITE, `${userPath} | ${privatePath}`);
  }
}

export async function updateUserPreferences(
  mode: 'guest' | 'cloud',
  uid: string,
  updates: Partial<Pick<UserProfile, 'displayName' | 'defaultBusinessId' | 'theme' | 'locale' | 'notificationsEnabled'>>
): Promise<void> {
  saveLocalPreferences(updates);
  if (mode === 'cloud' && auth.currentUser && auth.currentUser.uid === uid) {
    const path = `users/${uid}`;
    try {
      await updateDoc(doc(db, 'users', uid), {
        ...updates,
        updatedAt: serverTimestamp(),
      });
    } catch (error) {
      handleFirestoreError(error, OperationType.UPDATE, path);
    }
  }
}

/**
 * Fetches businesses accessible by the current user (guest or cloud).
 */
export async function listUserBusinesses(mode: 'guest' | 'cloud', uid: string): Promise<Business[]> {
  if (mode === 'guest') {
    return readLocalTable<Business>(LOCAL_TABLES.BUSINESSES).filter((b) => b.ownerId === uid);
  }

  const path = 'businesses';
  try {
    const q = query(collection(db, 'businesses'), where('ownerId', '==', uid));
    const snap = await getDocs(q);
    return snap.docs
      .map((d) => {
        const data = d.data();
        return {
          id: String(data.id),
          ownerId: String(data.ownerId),
          name: String(data.name),
          defaultCurrency: data.defaultCurrency as CurrencyCode,
          address: String(data.address || ''),
          phone: String(data.phone || ''),
          fiscalStartMonth: Number(data.fiscalStartMonth || 1),
          createdAt: toIsoTimestamp(data.createdAt),
          updatedAt: toIsoTimestamp(data.updatedAt),
        };
      })
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  } catch (error) {
    if (isTransientOfflineFirestoreError(error)) {
      return [];
    }
    handleFirestoreError(error, OperationType.LIST, path);
  }
}

/**
 * Subscribes to live workspace records for a specific business in either Guest or Cloud mode.
 */
export function subscribeToBusinessWorkspace(
  mode: 'guest' | 'cloud',
  uid: string,
  business: Business,
  onUpdate: (data: Omit<WorkspaceBundle, 'businesses'>) => void,
  onError: (err: Error) => void
): () => void {
  if (mode === 'guest') {
    const emitLocal = () => {
      const members = readLocalTable<BusinessMember>(LOCAL_TABLES.MEMBERS).filter((m) => m.businessId === business.id);
      const memberRecord = members.find((m) => m.memberUid === uid);
      const role: MemberRole = business.ownerId === uid ? 'owner' : memberRecord?.role || 'viewer';
      onUpdate({
        activeBusiness: business,
        members,
        currentUserRole: role,
        accounts: readLocalTable<Account>(LOCAL_TABLES.ACCOUNTS).filter((a) => a.businessId === business.id),
        categories: readLocalTable<Category>(LOCAL_TABLES.CATEGORIES).filter((c) => c.businessId === business.id),
        parties: readLocalTable<Party>(LOCAL_TABLES.PARTIES).filter((p) => p.businessId === business.id),
        transactions: readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS).filter((t) => t.businessId === business.id),
        auditEvents: readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS)
          .filter((e) => e.businessId === business.id)
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
        hasPendingWrites: false,
      });
    };
    emitLocal();
    const handler = () => emitLocal();
    window.addEventListener('vumiq-local-db-changed', handler);
    return () => window.removeEventListener('vumiq-local-db-changed', handler);
  }

  // Cloud Firestore real-time listeners with strict ownerId filter matching Pillar 8 rules
  let members: BusinessMember[] = [];
  let accounts: Account[] = [];
  let categories: Category[] = [];
  let parties: Party[] = [];
  let transactions: Transaction[] = [];
  let auditEvents: AuditEvent[] = [];
  let pendingFlags = { acc: false, tx: false };

  const emitCloud = () => {
    const myMember = members.find((m) => m.memberUid === uid);
    const currentUserRole: MemberRole = business.ownerId === uid ? 'owner' : myMember?.role || 'viewer';
    onUpdate({
      activeBusiness: business,
      members,
      currentUserRole,
      accounts,
      categories,
      parties,
      transactions,
      auditEvents,
      hasPendingWrites: pendingFlags.acc || pendingFlags.tx,
    });
  };

  const basePath = `businesses/${business.id}`;
  const unsubs = [
    onSnapshot(
      query(collection(db, basePath, 'members'), where('ownerId', '==', business.ownerId)),
      (snap) => {
        members = snap.docs.map((d) => {
          const x = d.data();
          return {
            memberUid: String(x.memberUid),
            businessId: String(x.businessId),
            ownerId: String(x.ownerId),
            role: x.role as MemberRole,
            displayName: String(x.displayName),
            createdAt: toIsoTimestamp(x.createdAt),
            updatedAt: toIsoTimestamp(x.updatedAt),
          };
        });
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/members`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
    onSnapshot(
      query(collection(db, basePath, 'accounts'), where('ownerId', '==', business.ownerId)),
      { includeMetadataChanges: true },
      (snap) => {
        pendingFlags.acc = snap.metadata.hasPendingWrites;
        accounts = snap.docs
          .map((d) => {
            const x = d.data();
            return {
              id: String(x.id),
              businessId: String(x.businessId),
              ownerId: String(x.ownerId),
              name: String(x.name),
              accountType: x.accountType as Account['accountType'],
              currency: x.currency as CurrencyCode,
              openingBalanceMinor: Number(x.openingBalanceMinor || 0),
              status: (x.status as Account['status']) || 'active',
              createdAt: toIsoTimestamp(x.createdAt),
              updatedAt: toIsoTimestamp(x.updatedAt),
            };
          })
          .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/accounts`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
    onSnapshot(
      query(collection(db, basePath, 'categories'), where('ownerId', '==', business.ownerId)),
      (snap) => {
        categories = snap.docs
          .map((d) => {
            const x = d.data();
            return {
              id: String(x.id),
              businessId: String(x.businessId),
              ownerId: String(x.ownerId),
              name: String(x.name),
              kind: x.kind as Category['kind'],
              color: String(x.color),
              icon: String(x.icon),
              archived: Boolean(x.archived),
              createdAt: toIsoTimestamp(x.createdAt),
              updatedAt: toIsoTimestamp(x.updatedAt),
            };
          })
          .sort((a, b) => a.name.localeCompare(b.name));
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/categories`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
    onSnapshot(
      query(collection(db, basePath, 'parties'), where('ownerId', '==', business.ownerId)),
      (snap) => {
        parties = snap.docs
          .map((d) => {
            const x = d.data();
            return {
              id: String(x.id),
              businessId: String(x.businessId),
              ownerId: String(x.ownerId),
              name: String(x.name),
              partyType: x.partyType as Party['partyType'],
              phone: String(x.phone || ''),
              address: String(x.address || ''),
              notes: String(x.notes || ''),
              currency: x.currency as CurrencyCode,
              openingReceivableMinor: Number(x.openingReceivableMinor || 0),
              openingPayableMinor: Number(x.openingPayableMinor || 0),
              dueDate: String(x.dueDate || ''),
              status: (x.status as Party['status']) || 'active',
              createdAt: toIsoTimestamp(x.createdAt),
              updatedAt: toIsoTimestamp(x.updatedAt),
            };
          })
          .sort((a, b) => a.name.localeCompare(b.name));
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/parties`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
    onSnapshot(
      query(collection(db, basePath, 'transactions'), where('ownerId', '==', business.ownerId)),
      { includeMetadataChanges: true },
      (snap) => {
        pendingFlags.tx = snap.metadata.hasPendingWrites;
        transactions = snap.docs.map((d) => {
          const x = d.data();
          return {
            id: String(x.id),
            businessId: String(x.businessId),
            ownerId: String(x.ownerId),
            idempotencyKey: String(x.idempotencyKey),
            type: x.type as TransactionType,
            amountMinor: Number(x.amountMinor),
            currency: x.currency as CurrencyCode,
            convertedAmountMinor: Number(x.convertedAmountMinor || 0),
            toCurrency: String(x.toCurrency || ''),
            exchangeRateScaled: Number(x.exchangeRateScaled || 0),
            feeMinor: Number(x.feeMinor || 0),
            date: String(x.date),
            time: String(x.time || '12:00'),
            description: String(x.description),
            accountId: String(x.accountId || ''),
            toAccountId: String(x.toAccountId || ''),
            categoryId: String(x.categoryId || ''),
            categoryLabelSnapshot: String(x.categoryLabelSnapshot || ''),
            partyId: String(x.partyId || ''),
            partyNameSnapshot: String(x.partyNameSnapshot || ''),
            referenceNumber: String(x.referenceNumber || ''),
            notes: String(x.notes || ''),
            attachmentName: String(x.attachmentName || ''),
            attachmentMime: String(x.attachmentMime || ''),
            dueDate: String(x.dueDate || ''),
            status: (x.status as Transaction['status']) || 'posted',
            voidReason: String(x.voidReason || ''),
            createdByUid: String(x.createdByUid),
            createdAt: toIsoTimestamp(x.createdAt),
            updatedAt: toIsoTimestamp(x.updatedAt),
          };
        });
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/transactions`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
    onSnapshot(
      query(collection(db, basePath, 'auditEvents'), where('ownerId', '==', business.ownerId)),
      (snap) => {
        auditEvents = snap.docs
          .map((d) => {
            const x = d.data();
            return {
              id: String(x.id),
              businessId: String(x.businessId),
              ownerId: String(x.ownerId),
              entityType: x.entityType as AuditEvent['entityType'],
              entityId: String(x.entityId),
              action: x.action as AuditEvent['action'],
              actorUid: String(x.actorUid),
              actorName: String(x.actorName),
              summary: String(x.summary),
              previousSummary: String(x.previousSummary || ''),
              newSummary: String(x.newSummary || ''),
              reason: String(x.reason || ''),
              createdAt: toIsoTimestamp(x.createdAt),
            };
          })
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        emitCloud();
      },
      (err) => {
        try {
          handleFirestoreError(err, OperationType.LIST, `${basePath}/auditEvents`);
        } catch (wrapped) {
          onError(wrapped as Error);
        }
      }
    ),
  ];

  return () => {
    for (const unsub of unsubs) unsub();
  };
}

function notifyLocalDbChanged(): void {
  window.dispatchEvent(new CustomEvent('vumiq-local-db-changed'));
}

/**
 * Creates a new Business + Owner Membership + Default Categories + Initial Account atomically.
 * Never fabricates sample transactions.
 */
export async function createBusinessWithInitialAccount(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  businessName: string;
  defaultCurrency: CurrencyCode;
  address?: string;
  phone?: string;
  initialAccountName: string;
  initialAccountType: Account['accountType'];
  openingBalanceMinor: number;
}): Promise<Business> {
  const name = params.businessName.trim().slice(0, BLUEPRINT_CONSTRAINTS.BUSINESS_NAME_MAX);
  const accName = params.initialAccountName.trim().slice(0, BLUEPRINT_CONSTRAINTS.ACCOUNT_NAME_MAX);
  if (!name) throw new Error('Business name is required.');
  if (!accName) throw new Error('Initial account name is required.');

  const nowIso = new Date().toISOString();
  const bizId = generateCleanId('biz');
  const accId = generateCleanId('acc');
  const evtBizId = generateCleanId('evt');
  const evtAccId = generateCleanId('evt');

  const biz: Business = {
    id: bizId,
    ownerId: params.actorUid,
    name,
    defaultCurrency: params.defaultCurrency,
    address: (params.address || '').trim().slice(0, BLUEPRINT_CONSTRAINTS.BUSINESS_ADDRESS_MAX),
    phone: (params.phone || '').trim().slice(0, BLUEPRINT_CONSTRAINTS.BUSINESS_PHONE_MAX),
    fiscalStartMonth: 1,
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  const ownerMember: BusinessMember = {
    memberUid: params.actorUid,
    businessId: bizId,
    ownerId: params.actorUid,
    role: 'owner',
    displayName: params.actorName.slice(0, BLUEPRINT_CONSTRAINTS.USER_DISPLAY_NAME_MAX),
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  const defaultCats = createDefaultCategories(bizId, params.actorUid, nowIso);

  const initialAcc: Account = {
    id: accId,
    businessId: bizId,
    ownerId: params.actorUid,
    name: accName,
    accountType: params.initialAccountType,
    currency: params.defaultCurrency,
    openingBalanceMinor: params.openingBalanceMinor,
    status: 'active',
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  if (params.mode === 'guest') {
    writeLocalTable(LOCAL_TABLES.BUSINESSES, [...readLocalTable<Business>(LOCAL_TABLES.BUSINESSES), biz]);
    writeLocalTable(LOCAL_TABLES.MEMBERS, [...readLocalTable<BusinessMember>(LOCAL_TABLES.MEMBERS), ownerMember]);
    writeLocalTable(LOCAL_TABLES.CATEGORIES, [...readLocalTable<Category>(LOCAL_TABLES.CATEGORIES), ...defaultCats]);
    writeLocalTable(LOCAL_TABLES.ACCOUNTS, [...readLocalTable<Account>(LOCAL_TABLES.ACCOUNTS), initialAcc]);
    writeLocalTable(LOCAL_TABLES.AUDITS, [
      ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
      {
        id: evtBizId,
        businessId: bizId,
        ownerId: params.actorUid,
        entityType: 'business',
        entityId: bizId,
        action: 'create',
        actorUid: params.actorUid,
        actorName: ownerMember.displayName,
        summary: `Created business "${biz.name}"`,
        previousSummary: '',
        newSummary: `${biz.name} (${biz.defaultCurrency})`,
        reason: '',
        createdAt: nowIso,
      },
      {
        id: evtAccId,
        businessId: bizId,
        ownerId: params.actorUid,
        entityType: 'account',
        entityId: accId,
        action: 'create',
        actorUid: params.actorUid,
        actorName: ownerMember.displayName,
        summary: `Created account "${initialAcc.name}"`,
        previousSummary: '',
        newSummary: `${initialAcc.name} — Opening ${formatMinorAmount(initialAcc.openingBalanceMinor, initialAcc.currency)}`,
        reason: '',
        createdAt: nowIso,
      },
    ]);
    saveLocalPreferences({ defaultBusinessId: bizId });
    notifyLocalDbChanged();
    return biz;
  }

  // Cloud Firestore: Batch 1 creates Business + Owner Membership + Audit Event (satisfies existsAfter gate)
  try {
    const batch1 = writeBatch(db);
    batch1.set(doc(db, 'businesses', bizId), {
      id: biz.id,
      ownerId: biz.ownerId,
      name: biz.name,
      defaultCurrency: biz.defaultCurrency,
      address: biz.address,
      phone: biz.phone,
      fiscalStartMonth: biz.fiscalStartMonth,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch1.set(doc(db, 'businesses', bizId, 'members', params.actorUid), {
      memberUid: ownerMember.memberUid,
      businessId: bizId,
      ownerId: biz.ownerId,
      role: 'owner',
      displayName: ownerMember.displayName,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch1.set(doc(db, 'businesses', bizId, 'auditEvents', evtBizId), {
      id: evtBizId,
      businessId: bizId,
      ownerId: biz.ownerId,
      entityType: 'business',
      entityId: bizId,
      action: 'create',
      actorUid: params.actorUid,
      actorName: ownerMember.displayName,
      summary: `Created business "${biz.name}"`,
      previousSummary: '',
      newSummary: `${biz.name} (${biz.defaultCurrency})`,
      reason: '',
      createdAt: serverTimestamp(),
    });
    await batch1.commit();

    // Batch 2 creates Default Categories + Initial Account + Account Audit Event
    const batch2 = writeBatch(db);
    for (const cat of defaultCats) {
      batch2.set(doc(db, 'businesses', bizId, 'categories', cat.id), {
        id: cat.id,
        businessId: cat.businessId,
        ownerId: cat.ownerId,
        name: cat.name,
        kind: cat.kind,
        color: cat.color,
        icon: cat.icon,
        archived: false,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    }
    batch2.set(doc(db, 'businesses', bizId, 'accounts', accId), {
      id: initialAcc.id,
      businessId: bizId,
      ownerId: biz.ownerId,
      name: initialAcc.name,
      accountType: initialAcc.accountType,
      currency: initialAcc.currency,
      openingBalanceMinor: initialAcc.openingBalanceMinor,
      status: 'active',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch2.set(doc(db, 'businesses', bizId, 'auditEvents', evtAccId), {
      id: evtAccId,
      businessId: bizId,
      ownerId: biz.ownerId,
      entityType: 'account',
      entityId: accId,
      action: 'create',
      actorUid: params.actorUid,
      actorName: ownerMember.displayName,
      summary: `Created account "${initialAcc.name}"`,
      previousSummary: '',
      newSummary: `${initialAcc.name} — Opening ${formatMinorAmount(initialAcc.openingBalanceMinor, initialAcc.currency)}`,
      reason: '',
      createdAt: serverTimestamp(),
    });
    await batch2.commit();

    await updateUserPreferences('cloud', params.actorUid, { defaultBusinessId: bizId });
    return biz;
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, `businesses/${bizId}`);
  }
}

export async function createFinancialAccount(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  name: string;
  accountType: Account['accountType'];
  currency: CurrencyCode;
  openingBalanceMinor: number;
}): Promise<Account> {
  if (!ROLE_PERMISSIONS[params.role].canManageAccounts) {
    throw new Error('Your role does not have permission to create or modify accounts.');
  }
  const nowIso = new Date().toISOString();
  const id = generateCleanId('acc');
  const evtId = generateCleanId('evt');
  const acc: Account = {
    id,
    businessId: params.business.id,
    ownerId: params.business.ownerId,
    name: params.name.trim().slice(0, BLUEPRINT_CONSTRAINTS.ACCOUNT_NAME_MAX),
    accountType: params.accountType,
    currency: params.currency,
    openingBalanceMinor: params.openingBalanceMinor,
    status: 'active',
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  if (params.mode === 'guest') {
    writeLocalTable(LOCAL_TABLES.ACCOUNTS, [...readLocalTable<Account>(LOCAL_TABLES.ACCOUNTS), acc]);
    writeLocalTable(LOCAL_TABLES.AUDITS, [
      ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
      {
        id: evtId,
        businessId: params.business.id,
        ownerId: params.business.ownerId,
        entityType: 'account',
        entityId: id,
        action: 'create',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Created account "${acc.name}"`,
        previousSummary: '',
        newSummary: `${acc.name} (${formatMinorAmount(acc.openingBalanceMinor, acc.currency)})`,
        reason: '',
        createdAt: nowIso,
      },
    ]);
    notifyLocalDbChanged();
    return acc;
  }

  const basePath = `businesses/${params.business.id}`;
  try {
    const batch = writeBatch(db);
    batch.set(doc(db, basePath, 'accounts', id), {
      id: acc.id,
      businessId: acc.businessId,
      ownerId: acc.ownerId,
      name: acc.name,
      accountType: acc.accountType,
      currency: acc.currency,
      openingBalanceMinor: acc.openingBalanceMinor,
      status: 'active',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch.set(doc(db, basePath, 'auditEvents', evtId), {
      id: evtId,
      businessId: acc.businessId,
      ownerId: acc.ownerId,
      entityType: 'account',
      entityId: id,
      action: 'create',
      actorUid: params.actorUid,
      actorName: params.actorName.slice(0, 100),
      summary: `Created account "${acc.name}"`,
      previousSummary: '',
      newSummary: `${acc.name} (${formatMinorAmount(acc.openingBalanceMinor, acc.currency)})`,
      reason: '',
      createdAt: serverTimestamp(),
    });
    await batch.commit();
    return acc;
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, `${basePath}/accounts/${id}`);
  }
}

export async function saveCategoryItem(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  existingCategory?: Category;
  name: string;
  kind: Category['kind'];
  color: string;
  icon: string;
  archived?: boolean;
}): Promise<void> {
  if (!ROLE_PERMISSIONS[params.role].canRecordTransactions) {
    throw new Error('Your role does not have permission to modify categories.');
  }
  const cleanName = params.name.trim().slice(0, BLUEPRINT_CONSTRAINTS.CATEGORY_NAME_MAX);
  if (!cleanName) throw new Error('Category name is required.');
  const cleanColor = BLUEPRINT_CONSTRAINTS.HEX_COLOR_PATTERN.test(params.color) ? params.color : '#168653';
  const cleanIcon = (params.icon || 'tag').slice(0, BLUEPRINT_CONSTRAINTS.CATEGORY_ICON_MAX);
  const nowIso = new Date().toISOString();

  if (params.existingCategory) {
    const prev = params.existingCategory;
    if (params.mode === 'guest') {
      const list = readLocalTable<Category>(LOCAL_TABLES.CATEGORIES).map((c) =>
        c.id === prev.id
          ? { ...c, name: cleanName, color: cleanColor, icon: cleanIcon, archived: Boolean(params.archived ?? c.archived), updatedAt: nowIso }
          : c
      );
      writeLocalTable(LOCAL_TABLES.CATEGORIES, list);
      notifyLocalDbChanged();
      return;
    }
    const path = `businesses/${params.business.id}/categories/${prev.id}`;
    try {
      await updateDoc(doc(db, 'businesses', params.business.id, 'categories', prev.id), {
        name: cleanName,
        color: cleanColor,
        icon: cleanIcon,
        archived: Boolean(params.archived ?? prev.archived),
        updatedAt: serverTimestamp(),
      });
      return;
    } catch (error) {
      handleFirestoreError(error, OperationType.UPDATE, path);
    }
  }

  const id = generateCleanId('cat');
  const cat: Category = {
    id,
    businessId: params.business.id,
    ownerId: params.business.ownerId,
    name: cleanName,
    kind: params.kind,
    color: cleanColor,
    icon: cleanIcon,
    archived: false,
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  if (params.mode === 'guest') {
    writeLocalTable(LOCAL_TABLES.CATEGORIES, [...readLocalTable<Category>(LOCAL_TABLES.CATEGORIES), cat]);
    notifyLocalDbChanged();
    return;
  }

  const path = `businesses/${params.business.id}/categories/${id}`;
  try {
    await setDoc(doc(db, 'businesses', params.business.id, 'categories', id), {
      id: cat.id,
      businessId: cat.businessId,
      ownerId: cat.ownerId,
      name: cat.name,
      kind: cat.kind,
      color: cat.color,
      icon: cat.icon,
      archived: false,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, path);
  }
}

export async function savePartyItem(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  existingParty?: Party;
  name: string;
  partyType: Party['partyType'];
  phone: string;
  address: string;
  notes: string;
  currency: CurrencyCode;
  openingReceivableMinor: number;
  openingPayableMinor: number;
  dueDate: string;
  status?: Party['status'];
}): Promise<Party> {
  if (!ROLE_PERMISSIONS[params.role].canManageParties) {
    throw new Error('Your role does not have permission to manage parties.');
  }
  const cleanName = params.name.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_NAME_MAX);
  if (!cleanName) throw new Error('Party name is required.');
  const nowIso = new Date().toISOString();

  if (params.existingParty) {
    const prev = params.existingParty;
    const updated: Party = {
      ...prev,
      name: cleanName,
      partyType: params.partyType,
      phone: params.phone.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_PHONE_MAX),
      address: params.address.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_ADDRESS_MAX),
      notes: params.notes.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_NOTES_MAX),
      currency: params.currency,
      openingReceivableMinor: params.openingReceivableMinor,
      openingPayableMinor: params.openingPayableMinor,
      dueDate: params.dueDate.slice(0, 10),
      status: params.status || prev.status,
      updatedAt: nowIso,
    };
    if (params.mode === 'guest') {
      const list = readLocalTable<Party>(LOCAL_TABLES.PARTIES).map((p) => (p.id === prev.id ? updated : p));
      writeLocalTable(LOCAL_TABLES.PARTIES, list);
      notifyLocalDbChanged();
      return updated;
    }
    const path = `businesses/${params.business.id}/parties/${prev.id}`;
    try {
      await updateDoc(doc(db, 'businesses', params.business.id, 'parties', prev.id), {
        name: updated.name,
        partyType: updated.partyType,
        phone: updated.phone,
        address: updated.address,
        notes: updated.notes,
        currency: updated.currency,
        openingReceivableMinor: updated.openingReceivableMinor,
        openingPayableMinor: updated.openingPayableMinor,
        dueDate: updated.dueDate,
        status: updated.status,
        updatedAt: serverTimestamp(),
      });
      return updated;
    } catch (error) {
      handleFirestoreError(error, OperationType.UPDATE, path);
    }
  }

  const id = generateCleanId('pty');
  const party: Party = {
    id,
    businessId: params.business.id,
    ownerId: params.business.ownerId,
    name: cleanName,
    partyType: params.partyType,
    phone: params.phone.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_PHONE_MAX),
    address: params.address.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_ADDRESS_MAX),
    notes: params.notes.trim().slice(0, BLUEPRINT_CONSTRAINTS.PARTY_NOTES_MAX),
    currency: params.currency,
    openingReceivableMinor: params.openingReceivableMinor,
    openingPayableMinor: params.openingPayableMinor,
    dueDate: params.dueDate.slice(0, 10),
    status: 'active',
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  if (params.mode === 'guest') {
    writeLocalTable(LOCAL_TABLES.PARTIES, [...readLocalTable<Party>(LOCAL_TABLES.PARTIES), party]);
    notifyLocalDbChanged();
    return party;
  }

  const path = `businesses/${params.business.id}/parties/${id}`;
  try {
    await setDoc(doc(db, 'businesses', params.business.id, 'parties', id), {
      id: party.id,
      businessId: party.businessId,
      ownerId: party.ownerId,
      name: party.name,
      partyType: party.partyType,
      phone: party.phone,
      address: party.address,
      notes: party.notes,
      currency: party.currency,
      openingReceivableMinor: party.openingReceivableMinor,
      openingPayableMinor: party.openingPayableMinor,
      dueDate: party.dueDate,
      status: 'active',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    return party;
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, path);
  }
}

export async function commitTransactionEntry(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  existingTransactions: Transaction[];
  editingTransaction?: Transaction;
  idempotencyKey: string;
  type: TransactionType;
  amountMinor: number;
  currency: CurrencyCode;
  convertedAmountMinor: number;
  toCurrency: string;
  exchangeRateScaled: number;
  feeMinor: number;
  date: string;
  time: string;
  description: string;
  accountId: string;
  toAccountId: string;
  categoryId: string;
  partyId: string;
  referenceNumber: string;
  notes: string;
  attachmentName: string;
  attachmentMime: string;
  dueDate: string;
  editReason?: string;
}): Promise<Transaction> {
  if (params.editingTransaction) {
    if (!ROLE_PERMISSIONS[params.role].canEditTransactions) {
      throw new Error('Your role does not have permission to edit transactions.');
    }
    if (params.editingTransaction.status === 'voided') {
      throw new Error('Voided transactions are locked and cannot be edited.');
    }
  } else {
    if (!ROLE_PERMISSIONS[params.role].canRecordTransactions) {
      throw new Error('Your role does not have permission to record transactions.');
    }
    // Idempotency protection
    const duplicate = params.existingTransactions.find((t) => t.idempotencyKey === params.idempotencyKey);
    if (duplicate) return duplicate;
  }

  const check = validateTransactionDraft(
    {
      type: params.type,
      amountMinor: params.amountMinor,
      currency: params.currency,
      description: params.description,
      date: params.date,
      time: params.time,
      accountId: params.accountId,
      toAccountId: params.toAccountId,
      partyId: params.partyId,
      convertedAmountMinor: params.convertedAmountMinor,
      toCurrency: params.toCurrency,
      exchangeRateScaled: params.exchangeRateScaled,
      feeMinor: params.feeMinor,
    },
    params.accounts,
    params.parties
  );
  if (!check.valid) throw new Error(check.message);

  const cat = params.categories.find((c) => c.id === params.categoryId);
  const pty = params.parties.find((p) => p.id === params.partyId);
  const nowIso = new Date().toISOString();

  if (params.editingTransaction) {
    const prev = params.editingTransaction;
    const updated: Transaction = {
      ...prev,
      type: params.type,
      amountMinor: params.amountMinor,
      currency: params.currency,
      convertedAmountMinor: params.convertedAmountMinor,
      toCurrency: params.toCurrency.slice(0, 3),
      exchangeRateScaled: params.exchangeRateScaled,
      feeMinor: params.feeMinor,
      date: params.date,
      time: params.time.slice(0, 8),
      description: params.description.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_DESCRIPTION_MAX),
      accountId: params.accountId,
      toAccountId: params.toAccountId,
      categoryId: params.categoryId,
      categoryLabelSnapshot: (cat?.name || prev.categoryLabelSnapshot || '').slice(0, BLUEPRINT_CONSTRAINTS.CATEGORY_NAME_MAX),
      partyId: params.partyId,
      partyNameSnapshot: (pty?.name || prev.partyNameSnapshot || '').slice(0, BLUEPRINT_CONSTRAINTS.PARTY_NAME_MAX),
      referenceNumber: params.referenceNumber.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_REFERENCE_MAX),
      notes: params.notes.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_NOTES_MAX),
      attachmentName: params.attachmentName.slice(0, BLUEPRINT_CONSTRAINTS.TX_ATTACHMENT_NAME_MAX),
      attachmentMime: params.attachmentMime.slice(0, BLUEPRINT_CONSTRAINTS.TX_ATTACHMENT_MIME_MAX),
      dueDate: params.dueDate.slice(0, 10),
      updatedAt: nowIso,
    };

    const evtId = generateCleanId('evt');
    const prevSummary = `${prev.type} ${formatMinorAmount(prev.amountMinor, prev.currency)} — ${prev.description} (${prev.date})`.slice(0, 500);
    const nextSummary = `${updated.type} ${formatMinorAmount(updated.amountMinor, updated.currency)} — ${updated.description} (${updated.date})`.slice(0, 500);

    if (params.mode === 'guest') {
      const txs = readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS).map((t) => (t.id === prev.id ? updated : t));
      writeLocalTable(LOCAL_TABLES.TRANSACTIONS, txs);
      writeLocalTable(LOCAL_TABLES.AUDITS, [
        ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
        {
          id: evtId,
          businessId: params.business.id,
          ownerId: params.business.ownerId,
          entityType: 'transaction',
          entityId: prev.id,
          action: 'update',
          actorUid: params.actorUid,
          actorName: params.actorName.slice(0, 100),
          summary: `Edited transaction "${updated.description}"`.slice(0, 300),
          previousSummary: prevSummary,
          newSummary: nextSummary,
          reason: (params.editReason || 'Updated transaction details').slice(0, 240),
          createdAt: nowIso,
        },
      ]);
      notifyLocalDbChanged();
      return updated;
    }

    const basePath = `businesses/${params.business.id}`;
    try {
      const batch = writeBatch(db);
      batch.update(doc(db, basePath, 'transactions', prev.id), {
        type: updated.type,
        amountMinor: updated.amountMinor,
        currency: updated.currency,
        convertedAmountMinor: updated.convertedAmountMinor,
        toCurrency: updated.toCurrency,
        exchangeRateScaled: updated.exchangeRateScaled,
        feeMinor: updated.feeMinor,
        date: updated.date,
        time: updated.time,
        description: updated.description,
        accountId: updated.accountId,
        toAccountId: updated.toAccountId,
        categoryId: updated.categoryId,
        categoryLabelSnapshot: updated.categoryLabelSnapshot,
        partyId: updated.partyId,
        partyNameSnapshot: updated.partyNameSnapshot,
        referenceNumber: updated.referenceNumber,
        notes: updated.notes,
        attachmentName: updated.attachmentName,
        attachmentMime: updated.attachmentMime,
        dueDate: updated.dueDate,
        updatedAt: serverTimestamp(),
      });
      batch.set(doc(db, basePath, 'auditEvents', evtId), {
        id: evtId,
        businessId: params.business.id,
        ownerId: params.business.ownerId,
        entityType: 'transaction',
        entityId: prev.id,
        action: 'update',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Edited transaction "${updated.description}"`.slice(0, 300),
        previousSummary: prevSummary,
        newSummary: nextSummary,
        reason: (params.editReason || 'Updated transaction details').slice(0, 240),
        createdAt: serverTimestamp(),
      });
      await batch.commit();
      return updated;
    } catch (error) {
      handleFirestoreError(error, OperationType.UPDATE, `${basePath}/transactions/${prev.id}`);
    }
  }

  // Create new transaction + mandatory atomic audit event (`create_${txId}`)
  const txId = generateCleanId('tx');
  const auditId = `create_${txId}`;
  const cleanIdem = BLUEPRINT_CONSTRAINTS.ID_PATTERN.test(params.idempotencyKey)
    ? params.idempotencyKey.slice(0, 128)
    : generateCleanId('idem');

  const tx: Transaction = {
    id: txId,
    businessId: params.business.id,
    ownerId: params.business.ownerId,
    idempotencyKey: cleanIdem,
    type: params.type,
    amountMinor: params.amountMinor,
    currency: params.currency,
    convertedAmountMinor: params.convertedAmountMinor,
    toCurrency: params.toCurrency.slice(0, 3),
    exchangeRateScaled: params.exchangeRateScaled,
    feeMinor: params.feeMinor,
    date: params.date,
    time: (params.time || '12:00').slice(0, 8),
    description: params.description.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_DESCRIPTION_MAX),
    accountId: params.accountId,
    toAccountId: params.toAccountId,
    categoryId: params.categoryId,
    categoryLabelSnapshot: (cat?.name || '').slice(0, BLUEPRINT_CONSTRAINTS.CATEGORY_NAME_MAX),
    partyId: params.partyId,
    partyNameSnapshot: (pty?.name || '').slice(0, BLUEPRINT_CONSTRAINTS.PARTY_NAME_MAX),
    referenceNumber: params.referenceNumber.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_REFERENCE_MAX),
    notes: params.notes.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_NOTES_MAX),
    attachmentName: params.attachmentName.slice(0, BLUEPRINT_CONSTRAINTS.TX_ATTACHMENT_NAME_MAX),
    attachmentMime: params.attachmentMime.slice(0, BLUEPRINT_CONSTRAINTS.TX_ATTACHMENT_MIME_MAX),
    dueDate: params.dueDate.slice(0, 10),
    status: 'posted',
    voidReason: '',
    createdByUid: params.actorUid,
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  const summaryStr = `${tx.type} ${formatMinorAmount(tx.amountMinor, tx.currency)} — ${tx.description} (${tx.date})`.slice(0, 300);

  if (params.mode === 'guest') {
    writeLocalTable(LOCAL_TABLES.TRANSACTIONS, [...readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS), tx]);
    writeLocalTable(LOCAL_TABLES.AUDITS, [
      ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
      {
        id: auditId,
        businessId: params.business.id,
        ownerId: params.business.ownerId,
        entityType: 'transaction',
        entityId: txId,
        action: 'create',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Recorded ${summaryStr}`.slice(0, 300),
        previousSummary: '',
        newSummary: summaryStr,
        reason: '',
        createdAt: nowIso,
      },
    ]);
    notifyLocalDbChanged();
    return tx;
  }

  const basePath = `businesses/${params.business.id}`;
  try {
    const batch = writeBatch(db);
    batch.set(doc(db, basePath, 'transactions', txId), {
      id: tx.id,
      businessId: tx.businessId,
      ownerId: tx.ownerId,
      idempotencyKey: tx.idempotencyKey,
      type: tx.type,
      amountMinor: tx.amountMinor,
      currency: tx.currency,
      convertedAmountMinor: tx.convertedAmountMinor,
      toCurrency: tx.toCurrency,
      exchangeRateScaled: tx.exchangeRateScaled,
      feeMinor: tx.feeMinor,
      date: tx.date,
      time: tx.time,
      description: tx.description,
      accountId: tx.accountId,
      toAccountId: tx.toAccountId,
      categoryId: tx.categoryId,
      categoryLabelSnapshot: tx.categoryLabelSnapshot,
      partyId: tx.partyId,
      partyNameSnapshot: tx.partyNameSnapshot,
      referenceNumber: tx.referenceNumber,
      notes: tx.notes,
      attachmentName: tx.attachmentName,
      attachmentMime: tx.attachmentMime,
      dueDate: tx.dueDate,
      status: 'posted',
      voidReason: '',
      createdByUid: tx.createdByUid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch.set(doc(db, basePath, 'auditEvents', auditId), {
      id: auditId,
      businessId: tx.businessId,
      ownerId: tx.ownerId,
      entityType: 'transaction',
      entityId: txId,
      action: 'create',
      actorUid: params.actorUid,
      actorName: params.actorName.slice(0, 100),
      summary: `Recorded ${summaryStr}`.slice(0, 300),
      previousSummary: '',
      newSummary: summaryStr,
      reason: '',
      createdAt: serverTimestamp(),
    });
    await batch.commit();
    return tx;
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, `${basePath}/transactions/${txId}`);
  }
}

export async function voidTransactionEntry(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  transaction: Transaction;
  reason: string;
}): Promise<void> {
  if (!ROLE_PERMISSIONS[params.role].canVoidTransactions) {
    throw new Error('Your role does not have permission to void transactions.');
  }
  const cleanReason = params.reason.trim().slice(0, BLUEPRINT_CONSTRAINTS.TX_VOID_REASON_MAX);
  if (!cleanReason) throw new Error('Please provide a reason for voiding this transaction.');
  const nowIso = new Date().toISOString();
  const evtId = generateCleanId('evt');

  if (params.mode === 'guest') {
    const list = readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS).map((t) =>
      t.id === params.transaction.id ? { ...t, status: 'voided' as const, voidReason: cleanReason, updatedAt: nowIso } : t
    );
    writeLocalTable(LOCAL_TABLES.TRANSACTIONS, list);
    writeLocalTable(LOCAL_TABLES.AUDITS, [
      ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
      {
        id: evtId,
        businessId: params.business.id,
        ownerId: params.business.ownerId,
        entityType: 'transaction',
        entityId: params.transaction.id,
        action: 'void',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Voided transaction "${params.transaction.description}"`.slice(0, 300),
        previousSummary: `posted — ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)}`,
        newSummary: `voided — ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)}`,
        reason: cleanReason,
        createdAt: nowIso,
      },
    ]);
    notifyLocalDbChanged();
    return;
  }

  const basePath = `businesses/${params.business.id}`;
  try {
    const batch = writeBatch(db);
    batch.update(doc(db, basePath, 'transactions', params.transaction.id), {
      status: 'voided',
      voidReason: cleanReason,
      updatedAt: serverTimestamp(),
    });
    batch.set(doc(db, basePath, 'auditEvents', evtId), {
      id: evtId,
      businessId: params.business.id,
      ownerId: params.business.ownerId,
      entityType: 'transaction',
      entityId: params.transaction.id,
      action: 'void',
      actorUid: params.actorUid,
      actorName: params.actorName.slice(0, 100),
      summary: `Voided transaction "${params.transaction.description}"`.slice(0, 300),
      previousSummary: `posted — ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)}`,
      newSummary: `voided — ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)}`,
      reason: cleanReason,
      createdAt: serverTimestamp(),
    });
    await batch.commit();
  } catch (error) {
    handleFirestoreError(error, OperationType.UPDATE, `${basePath}/transactions/${params.transaction.id}`);
  }
}

export async function deleteTransactionEntry(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  business: Business;
  transaction: Transaction;
  reason: string;
}): Promise<void> {
  if (!ROLE_PERMISSIONS[params.role].canDeleteTransactions) {
    throw new Error('Only Owners and Administrators are permitted to permanently delete transactions.');
  }
  const cleanReason = (params.reason.trim() || 'Deleted by administrator').slice(0, 240);
  const nowIso = new Date().toISOString();
  const evtId = generateCleanId('evt');

  if (params.mode === 'guest') {
    const list = readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS).filter((t) => t.id !== params.transaction.id);
    writeLocalTable(LOCAL_TABLES.TRANSACTIONS, list);
    writeLocalTable(LOCAL_TABLES.AUDITS, [
      ...readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS),
      {
        id: evtId,
        businessId: params.business.id,
        ownerId: params.business.ownerId,
        entityType: 'transaction',
        entityId: params.transaction.id,
        action: 'delete',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Deleted transaction "${params.transaction.description}"`.slice(0, 300),
        previousSummary: `${params.transaction.type} ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)} (${params.transaction.date})`,
        newSummary: 'deleted',
        reason: cleanReason,
        createdAt: nowIso,
      },
    ]);
    notifyLocalDbChanged();
    return;
  }

  const basePath = `businesses/${params.business.id}`;
  try {
    const batch = writeBatch(db);
    batch.delete(doc(db, basePath, 'transactions', params.transaction.id));
    batch.set(doc(db, basePath, 'auditEvents', evtId), {
      id: evtId,
      businessId: params.business.id,
      ownerId: params.business.ownerId,
      entityType: 'transaction',
      entityId: params.transaction.id,
      action: 'delete',
      actorUid: params.actorUid,
      actorName: params.actorName.slice(0, 100),
      summary: `Deleted transaction "${params.transaction.description}"`.slice(0, 300),
      previousSummary: `${params.transaction.type} ${formatMinorAmount(params.transaction.amountMinor, params.transaction.currency)} (${params.transaction.date})`,
      newSummary: 'deleted',
      reason: cleanReason,
      createdAt: serverTimestamp(),
    });
    await batch.commit();
  } catch (error) {
    handleFirestoreError(error, OperationType.DELETE, `${basePath}/transactions/${params.transaction.id}`);
  }
}

export async function addCollaboratorMember(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  role: MemberRole;
  business: Business;
  memberUid: string;
  displayName: string;
  targetRole: Exclude<MemberRole, 'owner'>;
}): Promise<void> {
  if (!ROLE_PERMISSIONS[params.role].canManageMembers) {
    throw new Error('Only Owners and Administrators can manage team roles.');
  }
  const cleanUid = params.memberUid.trim();
  if (!BLUEPRINT_CONSTRAINTS.ID_PATTERN.test(cleanUid) || cleanUid.length > 128) {
    throw new Error('Enter a valid collaborator UID (alphanumeric, hyphens, or underscores).');
  }
  const cleanName = params.displayName.trim().slice(0, 100);
  if (!cleanName) throw new Error('Collaborator display name is required.');
  const nowIso = new Date().toISOString();

  const mem: BusinessMember = {
    memberUid: cleanUid,
    businessId: params.business.id,
    ownerId: params.business.ownerId,
    role: params.targetRole,
    displayName: cleanName,
    createdAt: nowIso,
    updatedAt: nowIso,
  };

  if (params.mode === 'guest') {
    const existing = readLocalTable<BusinessMember>(LOCAL_TABLES.MEMBERS).filter(
      (m) => !(m.businessId === params.business.id && m.memberUid === cleanUid)
    );
    writeLocalTable(LOCAL_TABLES.MEMBERS, [...existing, mem]);
    notifyLocalDbChanged();
    return;
  }

  const path = `businesses/${params.business.id}/members/${cleanUid}`;
  try {
    await setDoc(doc(db, 'businesses', params.business.id, 'members', cleanUid), {
      memberUid: cleanUid,
      businessId: params.business.id,
      ownerId: params.business.ownerId,
      role: params.targetRole,
      displayName: cleanName,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (error) {
    handleFirestoreError(error, OperationType.CREATE, path);
  }
}

export function buildVerifiedBackupArchive(bundle: {
  business: Business;
  accounts: Account[];
  categories: Category[];
  parties: Party[];
  transactions: Transaction[];
  auditEvents: AuditEvent[];
}): BackupArchive {
  const totalPostedMinorSum = bundle.transactions
    .filter((t) => t.status === 'posted')
    .reduce((sum, t) => sum + t.amountMinor, 0);

  const checksum = computeBackupChecksum({
    businessId: bundle.business.id,
    accountCount: bundle.accounts.length,
    transactionCount: bundle.transactions.length,
    totalPostedMinorSum,
  });

  return {
    schemaVersion: 1,
    application: 'VUMIQ Cashbook',
    exportedAt: new Date().toISOString(),
    checksum,
    business: bundle.business,
    accounts: bundle.accounts,
    categories: bundle.categories,
    parties: bundle.parties,
    transactions: bundle.transactions,
    auditEvents: bundle.auditEvents,
  };
}

/**
 * Restores a verified backup archive into the active business with duplicate prevention (merge or replace).
 */
export async function restoreVerifiedBackup(params: {
  mode: 'guest' | 'cloud';
  actorUid: string;
  actorName: string;
  role: MemberRole;
  targetBusiness: Business;
  existingAccounts: Account[];
  existingCategories: Category[];
  existingParties: Party[];
  existingTransactions: Transaction[];
  rawJson: string;
  strategy: 'merge' | 'replace';
}): Promise<{ importedTransactions: number; skippedDuplicates: number }> {
  if (!ROLE_PERMISSIONS[params.role].canManageAccounts) {
    throw new Error('Only Owners and Administrators may restore backup archives.');
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(params.rawJson);
  } catch {
    throw new Error('Selected file is not valid JSON.');
  }

  const verified = validateBackupArchive(parsed);
  if (!verified.ok) {
    throw new Error(verified.message);
  }

  const archive = verified.archive;
  const bizId = params.targetBusiness.id;
  const ownerId = params.targetBusiness.ownerId;
  const nowIso = new Date().toISOString();

  if (params.mode === 'guest') {
    let accs = readLocalTable<Account>(LOCAL_TABLES.ACCOUNTS);
    let cats = readLocalTable<Category>(LOCAL_TABLES.CATEGORIES);
    let ptys = readLocalTable<Party>(LOCAL_TABLES.PARTIES);
    let txs = readLocalTable<Transaction>(LOCAL_TABLES.TRANSACTIONS);
    const audits = readLocalTable<AuditEvent>(LOCAL_TABLES.AUDITS);

    if (params.strategy === 'replace') {
      accs = accs.filter((a) => a.businessId !== bizId);
      cats = cats.filter((c) => c.businessId !== bizId);
      ptys = ptys.filter((p) => p.businessId !== bizId);
      txs = txs.filter((t) => t.businessId !== bizId);
    }

    const existingAccIds = new Set(accs.filter((a) => a.businessId === bizId).map((a) => a.id));
    for (const a of archive.accounts) {
      if (!existingAccIds.has(a.id)) {
        accs.push({ ...a, businessId: bizId, ownerId });
      }
    }

    const existingCatIds = new Set(cats.filter((c) => c.businessId === bizId).map((c) => c.id));
    for (const c of archive.categories) {
      if (!existingCatIds.has(c.id)) {
        cats.push({ ...c, businessId: bizId, ownerId });
      }
    }

    const existingPtyIds = new Set(ptys.filter((p) => p.businessId === bizId).map((p) => p.id));
    for (const p of archive.parties) {
      if (!existingPtyIds.has(p.id)) {
        ptys.push({ ...p, businessId: bizId, ownerId });
      }
    }

    const existingTxKeys = new Set(
      txs.filter((t) => t.businessId === bizId).flatMap((t) => [t.id, t.idempotencyKey])
    );

    let importedTransactions = 0;
    let skippedDuplicates = 0;

    for (const t of archive.transactions) {
      if (existingTxKeys.has(t.id) || existingTxKeys.has(t.idempotencyKey)) {
        skippedDuplicates += 1;
        continue;
      }
      txs.push({ ...t, businessId: bizId, ownerId, createdByUid: params.actorUid });
      existingTxKeys.add(t.id);
      existingTxKeys.add(t.idempotencyKey);
      importedTransactions += 1;
    }

    audits.push({
      id: generateCleanId('evt'),
      businessId: bizId,
      ownerId,
      entityType: 'business',
      entityId: bizId,
      action: 'restore',
      actorUid: params.actorUid,
      actorName: params.actorName.slice(0, 100),
      summary: `Restored backup (${params.strategy}): ${importedTransactions} transactions imported`,
      previousSummary: '',
      newSummary: `Imported ${importedTransactions}, skipped ${skippedDuplicates} duplicates`,
      reason: `Backup restore (${params.strategy})`,
      createdAt: nowIso,
    });

    writeLocalTable(LOCAL_TABLES.ACCOUNTS, accs);
    writeLocalTable(LOCAL_TABLES.CATEGORIES, cats);
    writeLocalTable(LOCAL_TABLES.PARTIES, ptys);
    writeLocalTable(LOCAL_TABLES.TRANSACTIONS, txs);
    writeLocalTable(LOCAL_TABLES.AUDITS, audits);
    notifyLocalDbChanged();
    return { importedTransactions, skippedDuplicates };
  }

  // Cloud mode restore
  const basePath = `businesses/${bizId}`;
  try {
    if (params.strategy === 'replace') {
      const delBatch = writeBatch(db);
      for (const t of params.existingTransactions) {
        delBatch.delete(doc(db, basePath, 'transactions', t.id));
      }
      await delBatch.commit();
    }

    const metaBatch = writeBatch(db);
    const existingAccIds = new Set(params.strategy === 'replace' ? [] : params.existingAccounts.map((a) => a.id));
    for (const a of archive.accounts) {
      if (!existingAccIds.has(a.id)) {
        metaBatch.set(doc(db, basePath, 'accounts', a.id), {
          id: a.id,
          businessId: bizId,
          ownerId,
          name: a.name.slice(0, 80),
          accountType: a.accountType,
          currency: a.currency,
          openingBalanceMinor: a.openingBalanceMinor,
          status: a.status,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
    }
    const existingCatIds = new Set(params.strategy === 'replace' ? [] : params.existingCategories.map((c) => c.id));
    for (const c of archive.categories) {
      if (!existingCatIds.has(c.id)) {
        metaBatch.set(doc(db, basePath, 'categories', c.id), {
          id: c.id,
          businessId: bizId,
          ownerId,
          name: c.name.slice(0, 64),
          kind: c.kind,
          color: c.color,
          icon: c.icon,
          archived: c.archived,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
    }
    const existingPtyIds = new Set(params.strategy === 'replace' ? [] : params.existingParties.map((p) => p.id));
    for (const p of archive.parties) {
      if (!existingPtyIds.has(p.id)) {
        metaBatch.set(doc(db, basePath, 'parties', p.id), {
          id: p.id,
          businessId: bizId,
          ownerId,
          name: p.name.slice(0, 100),
          partyType: p.partyType,
          phone: p.phone.slice(0, 40),
          address: p.address.slice(0, 240),
          notes: p.notes.slice(0, 500),
          currency: p.currency,
          openingReceivableMinor: p.openingReceivableMinor,
          openingPayableMinor: p.openingPayableMinor,
          dueDate: p.dueDate.slice(0, 10),
          status: p.status,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
    }
    await metaBatch.commit();

    const existingKeys = new Set(
      params.strategy === 'replace' ? [] : params.existingTransactions.flatMap((t) => [t.id, t.idempotencyKey])
    );

    let importedTransactions = 0;
    let skippedDuplicates = 0;
    let txBatch = writeBatch(db);
    let opsInBatch = 0;

    for (const t of archive.transactions) {
      if (existingKeys.has(t.id) || existingKeys.has(t.idempotencyKey)) {
        skippedDuplicates += 1;
        continue;
      }
      // Only posted transactions can be created directly via create gate; skip voided or import posted
      if (t.status !== 'posted') continue;

      const auditId = `create_${t.id}`;
      txBatch.set(doc(db, basePath, 'transactions', t.id), {
        id: t.id,
        businessId: bizId,
        ownerId,
        idempotencyKey: t.idempotencyKey,
        type: t.type,
        amountMinor: t.amountMinor,
        currency: t.currency,
        convertedAmountMinor: t.convertedAmountMinor || 0,
        toCurrency: t.toCurrency || '',
        exchangeRateScaled: t.exchangeRateScaled || 0,
        feeMinor: t.feeMinor || 0,
        date: t.date,
        time: t.time || '12:00',
        description: t.description.slice(0, 200),
        accountId: t.accountId || '',
        toAccountId: t.toAccountId || '',
        categoryId: t.categoryId || '',
        categoryLabelSnapshot: (t.categoryLabelSnapshot || '').slice(0, 64),
        partyId: t.partyId || '',
        partyNameSnapshot: (t.partyNameSnapshot || '').slice(0, 100),
        referenceNumber: (t.referenceNumber || '').slice(0, 64),
        notes: (t.notes || '').slice(0, 600),
        attachmentName: (t.attachmentName || '').slice(0, 120),
        attachmentMime: (t.attachmentMime || '').slice(0, 64),
        dueDate: (t.dueDate || '').slice(0, 10),
        status: 'posted',
        voidReason: '',
        createdByUid: params.actorUid,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
      txBatch.set(doc(db, basePath, 'auditEvents', auditId), {
        id: auditId,
        businessId: bizId,
        ownerId,
        entityType: 'transaction',
        entityId: t.id,
        action: 'restore',
        actorUid: params.actorUid,
        actorName: params.actorName.slice(0, 100),
        summary: `Restored transaction "${t.description}"`.slice(0, 300),
        previousSummary: '',
        newSummary: `${t.type} ${formatMinorAmount(t.amountMinor, t.currency)} (${t.date})`.slice(0, 500),
        reason: 'Backup restore',
        createdAt: serverTimestamp(),
      });
      opsInBatch += 2;
      importedTransactions += 1;
      existingKeys.add(t.id);
      existingKeys.add(t.idempotencyKey);

      if (opsInBatch >= 300) {
        await txBatch.commit();
        txBatch = writeBatch(db);
        opsInBatch = 0;
      }
    }

    if (opsInBatch > 0) {
      await txBatch.commit();
    }

    return { importedTransactions, skippedDuplicates };
  } catch (error) {
    handleFirestoreError(error, OperationType.WRITE, basePath);
  }
}

export async function wipeBusinessOrAccountData(params: {
  mode: 'guest' | 'cloud';
  uid: string;
  businesses: Business[];
}): Promise<void> {
  if (params.mode === 'guest') {
    for (const key of Object.values(LOCAL_TABLES)) {
      localStorage.removeItem(key);
    }
    notifyLocalDbChanged();
    return;
  }

  for (const biz of params.businesses) {
    if (biz.ownerId === params.uid) {
      const path = `businesses/${biz.id}`;
      try {
        await deleteDoc(doc(db, 'businesses', biz.id));
      } catch (error) {
        handleFirestoreError(error, OperationType.DELETE, path);
      }
    }
  }
  try {
    await deleteDoc(doc(db, 'users', params.uid, 'private', 'info'));
    await deleteDoc(doc(db, 'users', params.uid));
  } catch {
    // ignore if already absent
  }
}
