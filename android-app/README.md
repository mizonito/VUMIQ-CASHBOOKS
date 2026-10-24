# VUMIQ Cashbook — Native Android App (Kotlin + Jetpack Compose)

**Owned by Mr. Amin M. Gutale**

This `/android-app` directory contains the production-ready native Android source project for **VUMIQ Cashbook** (`applicationId = "com.vumiq.cashbook"`), targeting Android 8.0 through Android 15 (`minSdk = 26`, `targetSdk = 35`) including **Samsung Galaxy** phones (One UI 6 / One UI 7).

---

## Production Architecture & Capabilities

1. **Native Kotlin + Jetpack Compose (Material 3) & Biometric Security**:
   - Thumb-zone bottom navigation (`Home`, `Ledger`, `Parties`, `Reports`, `Settings`), edge-to-edge system bars, English & French (`EN` / `FR`) localization, and Light / Dark / System themes.
   - **Startup BiometricPrompt (`androidx.biometric` + Jetpack DataStore)**: Checks `biometric_unlock_enabled` at boot before dismissing the splash screen, prompts for **Fingerprint** or **Face Unlock** (`setConfirmationRequired(false)` with Device PIN/Pattern fallback), applies `FLAG_SECURE` to mask task-switcher previews while locked, and enforces configurable background auto-relock (`Immediately`, `After 1 min`, `After 5 min`).
   - Adaptive launcher icon (`@mipmap/ic_launcher` & `@mipmap/ic_launcher_round`) with deep emerald `#126B45` background, VUMIQ double-chevron emblem, and One UI monochrome icon layer.
   - Branded launch splash screen displaying **VUMIQ**, **Cashbook**, and **“Owned by Mr. Amin M. Gutale”**.

2. **Offline-First Room SQLite v2 Storage (`vumiq_cashbook_ledger.db`)**:
   - Write-Ahead Logging (`JournalMode.WRITE_AHEAD_LOGGING`) and explicit non-destructive Room migrations (`MIGRATION_1_2`) guarantee zero lost financial records across app restarts and future APK updates.
   - Zero mock or fabricated transactions.

3. **100% Exact-Math Accounting Engine (`MoneyMath.kt` & `AccountingEngine.kt`)**:
   - Exact integer minor units (`Long`) and `BigInteger` scaled exchange-rate math across 9 currencies (`USD, CDF, EUR, GBP, KES, SOS, XOF, RWF, ZAR`).
   - Strict currency isolation (never sums `USD + CDF`), non-income opening balances, internal & cross-currency transfers, credit sales/purchases, receivables/payables settlement without double-counting, refunds, and immutable audit-logged edits/voids.

4. **Two-Way Cloud Sync (`FirestoreSyncManager` + Android Credential Manager)**:
   - Native Google Sign-In via Android `CredentialManager` + Firebase Auth (`email_verified == true` enforced).
   - Genuine two-way synchronization with VUMIQ Cashbook Web on Firebase project `sixth-etching-101dl` (named Firestore database `ai-studio-vumiqcashbook-dd57b662-22cd-4575-bb13-5e888a1b3793`), using deterministic LWW timestamps, `(businessId, idempotencyKey)` deduplication, terminal `VOIDED` dominance, durable offline sync queue (`CloudSyncWorker`), and zero silent deletions.
   - Operates 100% normally offline when `google-services.json` is not configured.

5. **Safe Backup, Restore & Data Recovery**:
   - Android 12+ `data_extraction_rules.xml` and legacy `backup_rules.xml` exclude temporary SQLite `-wal`, `-shm`, and `-journal` files while preserving `vumiq_cashbook_ledger.db`, `datastore/`, `receipts/`, and `recovery_snapshots/`.
   - Runs `PRAGMA wal_checkpoint(FULL)` prior to every JSON export and restore.
   - **Validated JSON Restore Preview**: Verifies `vumiq_v1_*` FNV-1a checksums, defaults to **Safe Merge (Recommended)** with 3-way duplicate prevention, and automatically writes a timestamped pre-replace recovery snapshot (`files/recovery_snapshots/`) before any explicit **Replace Business** action.
   - Native Android `PdfDocument` A4 statements and RFC-4180 CSV exports shared via `FileProvider` (Quick Share, WhatsApp, Email).

6. **Customer & Supplier Due-Date Reminders & Private Receipt Attachments**:
   - Daily `WorkManager` evaluation (`DueDateReminderWorker` + `BootAndTimeChangeReceiver`) notifies you of overdue, due-today, and upcoming customer receivables or supplier payables without ever mutating ledger balances.
   - **Camera & Gallery Receipt Photos (`ReceiptStorageManager`)**: Captures or imports receipt images, normalizes EXIF rotation, downscales to compact JPEG (`<= 1600px`, quality `82`) inside app-private `files/receipts/`, isolates attachment errors so a photo failure never aborts a financial transaction, and cleans up orphaned files when replaced or removed.

---

## Step 1 — Create & Back Up Your ONE Permanent Release Keystore

Android requires every future update of `com.vumiq.cashbook` to be signed with the **exact same release certificate**. Release builds intentionally fail fast if signing credentials are missing—ephemeral auto-generated keys are permanently disabled so future APK upgrades never collide on your phone.

Run this command **once** on your computer (requires JDK 17+ `keytool`) in a private folder **outside** the Git repository:

```bash
keytool -genkeypair -v \
  -keystore vumiq-release.jks \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10950 \
  -alias vumiq \
  -dname "CN=Amin M. Gutale, OU=VUMIQ Cashbook, O=VUMIQ, C=US"
```

1. Choose a strong keystore password and key password when prompted.
2. **CRITICAL BACKUP STEP**: Immediately save copies of `vumiq-release.jks`, your keystore password, alias (`vumiq`), and key password in an encrypted password manager (e.g., Bitwarden / 1Password) and an offline backup drive. **Never commit `vumiq-release.jks` to Git.**
3. Encode `vumiq-release.jks` as a single-line Base64 string for GitHub Actions:
   - **Linux**:
     ```bash
     base64 -w 0 vumiq-release.jks > vumiq-keystore.b64.txt
     ```
   - **macOS**:
     ```bash
     base64 -i vumiq-release.jks | tr -d '\n' > vumiq-keystore.b64.txt
     ```
   - **Windows (PowerShell)**:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("vumiq-release.jks")) | Set-Content -NoNewline vumiq-keystore.b64.txt
     ```
   *(Delete `vumiq-keystore.b64.txt` after pasting its contents into GitHub Secrets in Step 2).*

---

## Step 2 — Push to GitHub & Configure Repository Secrets

1. Click **Export to GitHub** in Google AI Studio (or push this repository to your private GitHub repo).
2. In your GitHub repository, open **Settings -> Secrets and variables -> Actions -> New repository secret** and add these **4 required signing secrets**:

| Secret Name | Value |
| :--- | :--- |
| `VUMIQ_KEYSTORE_BASE64` | Single-line Base64 text from `vumiq-keystore.b64.txt` |
| `VUMIQ_STORE_PASSWORD` | Your permanent keystore password |
| `VUMIQ_KEY_ALIAS` | `vumiq` (or the alias you chose in Step 1) |
| `VUMIQ_KEY_PASSWORD` | Your key password |

Optional Cloud Sync secrets (see Step 3):

| Optional Secret Name | Value |
| :--- | :--- |
| `VUMIQ_GOOGLE_SERVICES_JSON` | Full JSON contents of `google-services.json` from Firebase Console |
| `VUMIQ_WEB_CLIENT_ID` | Optional override for Web OAuth 2.0 Client ID (defaults to workspace client) |

---

## Step 3 — (Optional) Register `com.vumiq.cashbook` in Firebase for Two-Way Cloud Sync

> **Note**: If you skip this step, VUMIQ Cashbook Android builds cleanly and runs **100% offline** with Room SQLite + JSON Backup/Restore. Complete this step whenever you want live two-way cloud synchronization with the Web Cashbook.

1. Extract the **SHA-1** and **SHA-256** certificate fingerprints from your permanent release keystore:
   ```bash
   keytool -list -v -keystore vumiq-release.jks -alias vumiq
   ```
   Copy both the `SHA1:` and `SHA256:` lines under `Certificate fingerprints`.
2. Open [Firebase Console — Project `sixth-etching-101dl`](https://console.firebase.google.com/project/sixth-etching-101dl/settings/general).
3. Under **Your apps**, click **Add app -> Android**:
   - **Android package name**: `com.vumiq.cashbook` *(must match exactly)*
   - **App nickname**: `VUMIQ Cashbook Android`
   - **Debug/Release signing certificate SHA-1**: Paste your release `SHA-1` fingerprint.
4. After registering, open the Android app settings in Firebase Console and click **Add fingerprint** to also add your release **`SHA-256`** fingerprint (required by Android Credential Manager / Google Identity Services).
5. In Firebase Console -> **Authentication -> Sign-in method**, confirm the **Google** provider is **Enabled** and has a support email configured.
6. Download **`google-services.json`**:
   - **For GitHub Actions builds**: Copy the entire contents of `google-services.json` into the `VUMIQ_GOOGLE_SERVICES_JSON` GitHub Actions secret (never commit `google-services.json` to Git).
   - **For local Android Studio builds**: Place `google-services.json` at `android-app/app/google-services.json` (already gitignored).

---

## Step 4 — Build, Download & Install the Signed APK on Your Samsung Galaxy Phone

1. Open your GitHub repository -> **Actions** tab -> select **Build Signed VUMIQ Cashbook Android APK**.
2. Click **Run workflow**:
   - Keep `version_code = 1` and `version_name = 1.0.0` for your first release (increment `version_code` to `2`, `3`, ... on future updates).
3. The pipeline executes two jobs automatically:
   - **Job 1 (`verify-debug-and-unit-tests`)**: Runs all JUnit 4 unit tests (`gradle testDebugUnitTest`) and verifies `gradle assembleDebug` without exposing secrets.
   - **Job 2 (`build-signed-release-apk`)**: Decodes your permanent keystore in runner temp memory, runs R8-optimized `gradle assembleRelease`, cryptographically verifies V1/V2/V3 signatures via Android SDK `apksigner verify --verbose --print-certs`, rejects debug certificates, generates a `.sha256` checksum, uploads the artifact, and scrubs temporary keystore files (`if: always()`).
4. Once green, scroll to **Artifacts** at the bottom of the workflow summary and download **`VUMIQ-Cashbook-Signed-Android-APK`** onto your Samsung phone.
5. Open **Samsung My Files** -> **Downloads**, extract the `.zip`, and tap **`vumiq-cashbook-v1.0.0-signed.apk`**:
   - If prompted on first install, allow **Install unknown apps** for **My Files**.
   - **Samsung One UI 6.1 / One UI 7 Auto Blocker tip**: If Samsung **Auto Blocker** blocks sideloaded enterprise APKs, open **Phone Settings -> Security and privacy -> Auto Blocker**, toggle it off temporarily while installing `vumiq-cashbook-v1.0.0-signed.apk`, and re-enable Auto Blocker right after installation.

---

## Step 5 — Installing Future Updates Safely (Zero Data Loss)

When releasing a new version in the future:
1. Keep the **exact same `VUMIQ_KEYSTORE_BASE64`** secret in GitHub Actions.
2. Trigger **Run workflow** with an incremented integer `version_code` (for example `2`) and updated `version_name` (for example `1.1.0`).
3. Download and tap the new signed `.apk` on your Samsung phone -> tap **Update**.
   - Android verifies the matching permanent certificate and upgrades VUMIQ Cashbook **in place**, preserving 100% of your Room SQLite ledger (`vumiq_cashbook_ledger.db`), biometric/reminder preferences (`datastore/`), and attached receipt photos (`files/receipts/`).

---

## Optional: Local Android Studio Build & Verification

1. Open **Android Studio Ladybug (2024.2.1+)** -> **Open** -> select `/android-app`.
2. Run the local JUnit 4 test suite and debug build (no keystore required):
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. To build a signed release APK locally, create a gitignored `android-app/keystore.properties` file:
   ```properties
   storeFile=/absolute/path/to/vumiq-release.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=vumiq
   keyPassword=YOUR_KEY_PASSWORD
   ```
   Then run:
   ```bash
   ./gradlew assembleRelease
   ```
   Output APK: `android-app/app/build/outputs/apk/release/app-release.apk`.
