import { initializeApp, getApps } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut as firebaseSignOut } from 'firebase/auth';
import {
  doc,
  getDocFromServer,
  getFirestore,
  initializeFirestore,
  memoryLocalCache,
  persistentLocalCache,
  persistentMultipleTabManager,
  setLogLevel,
  Firestore,
  FirestoreSettings,
} from 'firebase/firestore';
import firebaseConfig from '../../firebase-applet-config.json';

// Suppress @firebase/firestore internal console.error transport noise during normal offline/mobile handoffs.
// Application-level errors are still explicitly captured and reported via handleFirestoreError.
setLogLevel('silent');

const app = getApps().length ? getApps()[0] : initializeApp(firebaseConfig);

function isRunningInIframe(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.self !== window.top;
  } catch {
    return true;
  }
}

function createFirestoreInstance(): Firestore {
  const isBrowser = typeof window !== 'undefined';

  try {
    const settings: FirestoreSettings = {
      // Avoid IndexedDB / BroadcastChannel multi-tab lock contention inside cross-origin preview iframes,
      // while preserving full persistent multi-tab cache when installed or opened top-level.
      localCache:
        !isBrowser || isRunningInIframe()
          ? memoryLocalCache()
          : persistentLocalCache({
              tabManager: persistentMultipleTabManager(),
            }),
      // Use HTTP long-polling in browsers to prevent WebChannel [code=unavailable] drops behind proxies,
      // mobile carriers, firewalls, and embedded preview iframes.
      ...(isBrowser ? { experimentalForceLongPolling: true } : {}),
    };

    return initializeFirestore(app, settings, firebaseConfig.firestoreDatabaseId);
  } catch {
    return getFirestore(app, firebaseConfig.firestoreDatabaseId);
  }
}

export const db = createFirestoreInstance();
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();

export enum OperationType {
  CREATE = 'create',
  UPDATE = 'update',
  DELETE = 'delete',
  LIST = 'list',
  GET = 'get',
  WRITE = 'write',
}

export interface FirestoreErrorInfo {
  error: string;
  operationType: OperationType;
  path: string | null;
  authInfo: {
    userId?: string | null;
    email?: string | null;
    emailVerified?: boolean | null;
    isAnonymous?: boolean | null;
    tenantId?: string | null;
    providerInfo?: {
      providerId?: string | null;
      email?: string | null;
    }[];
  };
}

export function isTransientOfflineFirestoreError(error: unknown): boolean {
  if (!error) return false;
  const code = typeof error === 'object' && error !== null && 'code' in error ? String((error as { code?: unknown }).code) : '';
  const msg = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return (
    code === 'unavailable' ||
    msg.includes('the client is offline') ||
    msg.includes('could not reach cloud firestore backend') ||
    msg.includes('network error') ||
    (typeof navigator !== 'undefined' && !navigator.onLine)
  );
}

export function handleFirestoreError(error: unknown, operationType: OperationType, path: string | null): never {
  const errInfo: FirestoreErrorInfo = {
    error: error instanceof Error ? error.message : String(error),
    authInfo: {
      userId: auth.currentUser?.uid,
      email: auth.currentUser?.email,
      emailVerified: auth.currentUser?.emailVerified,
      isAnonymous: auth.currentUser?.isAnonymous,
      tenantId: auth.currentUser?.tenantId,
      providerInfo:
        auth.currentUser?.providerData?.map((provider) => ({
          providerId: provider.providerId,
          email: provider.email,
        })) || [],
    },
    operationType,
    path,
  };
  console.error('Firestore Error: ', JSON.stringify(errInfo));
  throw new Error(JSON.stringify(errInfo));
}

let connectionTested = false;

async function probeFirestoreServerOnce(): Promise<'reachable' | 'offline' | 'config_error'> {
  try {
    await getDocFromServer(doc(db, 'test', 'connection'));
    return 'reachable';
  } catch (error) {
    const code =
      typeof error === 'object' && error !== null && 'code' in error
        ? String((error as { code?: unknown }).code)
        : '';
    const msg = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();

    // permission-denied or unauthenticated from the server confirms live connectivity to the configured database
    if (code === 'permission-denied' || code === 'unauthenticated') {
      return 'reachable';
    }
    // Distinguish missing database vs missing document
    if (code === 'not-found') {
      if (msg.includes('database') && msg.includes('does not exist')) {
        return 'config_error';
      }
      return 'reachable';
    }
    if (isTransientOfflineFirestoreError(error)) {
      return 'offline';
    }
    if (
      code === 'invalid-argument' ||
      msg.includes('api key not valid') ||
      msg.includes('project_not_found')
    ) {
      return 'config_error';
    }
    return 'offline';
  }
}

export async function validateFirestoreConnectionOnBoot(): Promise<void> {
  if (connectionTested) return;
  connectionTested = true;

  const isMisconfiguredPlaceholder =
    !firebaseConfig.projectId ||
    firebaseConfig.projectId === 'remixed-project-id' ||
    !firebaseConfig.apiKey;

  if (isMisconfiguredPlaceholder) {
    console.error('Please check your Firebase configuration.');
    return;
  }

  const scheduleReconnectCheck = () => {
    if (typeof window !== 'undefined') {
      window.addEventListener(
        'online',
        () => {
          connectionTested = false;
          void validateFirestoreConnectionOnBoot();
        },
        { once: true }
      );
    }
  };

  // If the device is currently offline, defer server probe until connectivity returns
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    scheduleReconnectCheck();
    return;
  }

  const firstAttempt = await probeFirestoreServerOnce();
  if (firstAttempt === 'reachable') return;
  if (firstAttempt === 'config_error') {
    console.error('Please check your Firebase configuration.');
    return;
  }

  // Allow 2.5s for mobile/proxy handoff or long-polling negotiation before retrying
  await new Promise((resolve) => setTimeout(resolve, 2500));
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    scheduleReconnectCheck();
    return;
  }

  const secondAttempt = await probeFirestoreServerOnce();
  if (secondAttempt === 'config_error') {
    console.error('Please check your Firebase configuration.');
    return;
  }
  if (secondAttempt === 'offline') {
    // Device cannot currently reach Firestore backend; operate quietly in offline mode until online event fires
    scheduleReconnectCheck();
  }
}

export async function signInWithGooglePopup() {
  const credential = await signInWithPopup(auth, googleProvider);
  return credential.user;
}

export async function signOutFirebase() {
  await firebaseSignOut(auth);
}
