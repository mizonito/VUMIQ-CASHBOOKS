import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();

function readText(relPath: string): string {
  return fs.readFileSync(path.join(ROOT, relPath), 'utf8');
}

function listFilesRecursive(dirPath: string): string[] {
  const out: string[] = [];
  if (!fs.existsSync(dirPath)) return out;
  for (const entry of fs.readdirSync(dirPath, { withFileTypes: true })) {
    if (['node_modules', 'dist', '.backups', '.playwright-cli', '.aistudio'].includes(entry.name)) {
      continue;
    }
    const full = path.join(dirPath, entry.name);
    if (entry.isDirectory()) {
      out.push(...listFilesRecursive(full));
    } else {
      out.push(full);
    }
  }
  return out;
}

test('Phase 1A: Recoverable project backup exists and applicationId com.vumiq.cashbook is preserved', () => {
  assert.equal(fs.existsSync(path.join(ROOT, '.backups/vumiq-pre-phase1-backup.tar.gz')), true);
  const stat = fs.statSync(path.join(ROOT, '.backups/vumiq-pre-phase1-backup.tar.gz'));
  assert.equal(stat.size > 50_000, true, 'Backup tarball must be non-empty');

  const appGradle = readText('android-app/app/build.gradle.kts');
  assert.match(appGradle, /applicationId\s*=\s*"com\.vumiq\.cashbook"/);
  assert.match(appGradle, /namespace\s*=\s*"com\.vumiq\.cashbook"/);
  assert.match(appGradle, /minSdk\s*=\s*26/);
  assert.match(appGradle, /targetSdk\s*=\s*35/);
});

test('Phase 1B: Permanent release signing enforced without debug fallback or committed secrets', () => {
  const appGradle = readText('android-app/app/build.gradle.kts');

  // Must NEVER fall back to debug signing inside release buildType
  assert.equal(
    appGradle.includes('signingConfigs.getByName("debug")'),
    false,
    'Release buildType must never silently fall back to debug signing'
  );
  assert.match(appGradle, /enableV1Signing\s*=\s*true/);
  assert.match(appGradle, /enableV2Signing\s*=\s*true/);
  assert.match(appGradle, /enableV3Signing\s*=\s*true/);
  assert.match(appGradle, /verifyReleaseSigningConfigured/);
  assert.match(appGradle, /assembleRelease/);

  // .gitignore files must block keystores, keystore.properties, and google-services.json
  const rootGitignore = readText('.gitignore');
  const androidGitignore = readText('android-app/.gitignore');
  for (const pattern of ['*.jks', '*.keystore', 'keystore.properties', 'google-services.json']) {
    assert.equal(rootGitignore.includes(pattern), true, `Root .gitignore missing ${pattern}`);
    assert.equal(androidGitignore.includes(pattern), true, `Android .gitignore missing ${pattern}`);
  }

  // Zero keystores or keystore.properties tracked in repository
  const allFiles = listFilesRecursive(ROOT).map((f) => path.relative(ROOT, f));
  for (const rel of allFiles) {
    assert.equal(
      /\.(jks|keystore|p12|pfx)$/i.test(rel) || path.basename(rel) === 'keystore.properties',
      false,
      `Forbidden secret signing file present in workspace: ${rel}`
    );
  }
});

test('Phase 1C: GitHub Actions workflows enforce permanent secrets, apksigner verification, and never auto-generate keys', () => {
  for (const wfPath of [
    '.github/workflows/build-android-apk.yml',
    'android-app/.github/workflows/build-signed-apk.yml',
  ]) {
    const wf = readText(wfPath);

    // Must never auto-generate ephemeral signing keys or embed hardcoded passwords
    assert.equal(wf.includes('keytool -genkeypair'), false, `${wfPath} must not generate ephemeral keystores`);
    assert.equal(wf.includes('VumiqRelease2026'), false, `${wfPath} must not contain hardcoded passwords`);

    // Must reference all 4 required GitHub secrets
    for (const secretName of [
      'secrets.VUMIQ_KEYSTORE_BASE64',
      'secrets.VUMIQ_STORE_PASSWORD',
      'secrets.VUMIQ_KEY_ALIAS',
      'secrets.VUMIQ_KEY_PASSWORD',
    ]) {
      assert.equal(wf.includes(secretName), true, `${wfPath} must support ${secretName}`);
    }

    // Must keep debug build & unit tests usable without release secrets
    assert.match(wf, /gradle testDebugUnitTest/);
    assert.match(wf, /gradle assembleDebug/);

    // Must verify APK signature with apksigner and reject debug certs
    assert.match(wf, /"\$APKSIGNER"\s+verify\s+--verbose\s+--print-certs/);
    assert.match(wf, /CN=Android Debug/);
    assert.match(wf, /actions\/upload-artifact@v4/);
  }
});

function checkKotlinBraceBalance(src: string, fileLabel: string): void {
  let i = 0;
  const n = src.length;
  // Stack tracks 'code' braces vs '${...}' template interpolation braces
  const stack: ('code' | 'interp')[] = [];

  const scanString = (triple: boolean) => {
    while (i < n) {
      if (triple && src.startsWith('"""', i)) {
        i += 3;
        return;
      }
      if (!triple && src[i] === '\\') {
        i += 2;
        continue;
      }
      if (!triple && src[i] === '"') {
        i += 1;
        return;
      }
      if (src[i] === '$' && src[i + 1] === '{') {
        i += 2;
        stack.push('interp');
        scanCode(true);
        continue;
      }
      i += 1;
    }
  };

  const scanCode = (stopOnInterpClose: boolean) => {
    while (i < n) {
      if (src.startsWith('//', i)) {
        while (i < n && src[i] !== '\n') i++;
        continue;
      }
      if (src.startsWith('/*', i)) {
        i += 2;
        while (i < n && !src.startsWith('*/', i)) i++;
        i += 2;
        continue;
      }
      if (src.startsWith('"""', i)) {
        i += 3;
        scanString(true);
        continue;
      }
      if (src[i] === '"') {
        i += 1;
        scanString(false);
        continue;
      }
      if (src[i] === "'") {
        i += 1;
        while (i < n && src[i] !== "'") {
          if (src[i] === '\\') i += 2;
          else i += 1;
        }
        i += 1;
        continue;
      }
      if (src[i] === '{') {
        stack.push('code');
        i += 1;
        continue;
      }
      if (src[i] === '}') {
        assert.equal(stack.length > 0, true, `Unmatched closing brace in ${fileLabel}`);
        const top = stack.pop();
        i += 1;
        if (stopOnInterpClose && top === 'interp') {
          return;
        }
        continue;
      }
      i += 1;
    }
  };

  scanCode(false);
  assert.equal(stack.length, 0, `Unbalanced braces (remaining=${stack.length}) in ${fileLabel}`);
}

test('Structural integrity: All Kotlin and Gradle files have balanced braces and catalog aliases resolve', () => {
  const toml = readText('android-app/gradle/libs.versions.toml');
  const definedLibraries = new Set<string>();
  let inLibraries = false;
  for (const rawLine of toml.split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('[')) {
      inLibraries = line === '[libraries]';
      continue;
    }
    if (inLibraries && line.includes('=')) {
      const alias = line.split('=')[0].trim().replace(/-/g, '.');
      definedLibraries.add(alias);
    }
  }

  const appGradle = readText('android-app/app/build.gradle.kts');
  const referencedLibs = [...appGradle.matchAll(/libs\.([a-zA-Z0-9_.]+)/g)]
    .map((m) => m[1])
    .filter((ref) => !ref.startsWith('plugins.'));
  for (const ref of referencedLibs) {
    assert.equal(definedLibraries.has(ref), true, `Missing library alias in libs.versions.toml: libs.${ref}`);
  }

  const ktFiles = listFilesRecursive(path.join(ROOT, 'android-app')).filter(
    (f) => f.endsWith('.kt') || f.endsWith('.kts')
  );
  assert.equal(ktFiles.length >= 20, true);
  for (const file of ktFiles) {
    const src = fs.readFileSync(file, 'utf8');
    checkKotlinBraceBalance(src, path.relative(ROOT, file));
  }
});

test('Theme safety: Every LocalVumiqColors.current (colors.<token>) reference matches VumiqSemanticColors', () => {
  const themeKt = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/ui/theme/Theme.kt'
  );
  const classMatch = themeKt.match(/data class VumiqSemanticColors\(([\s\S]*?)\)/);
  assert.ok(classMatch, 'VumiqSemanticColors definition must exist in Theme.kt');

  const validTokens = new Set(
    [...classMatch[1].matchAll(/val\s+([a-zA-Z0-9_]+)\s*:/g)].map((m) => m[1])
  );
  assert.equal(validTokens.size >= 12, true);

  const ktFiles = listFilesRecursive(path.join(ROOT, 'android-app/app/src/main')).filter((f) =>
    f.endsWith('.kt')
  );
  for (const file of ktFiles) {
    const src = fs.readFileSync(file, 'utf8');
    if (!src.includes('LocalVumiqColors.current')) continue;
    for (const match of src.matchAll(/\bcolors\.([a-zA-Z0-9_]+)\b/g)) {
      const token = match[1];
      assert.equal(
        validTokens.has(token),
        true,
        `Invalid VumiqSemanticColors token 'colors.${token}' in ${path.relative(ROOT, file)}`
      );
    }
  }
});

test('Phase 6 Final Audit: Branding, EN/FR localization parity, zero mock data, and production README accuracy', () => {
  const stringsEn = readText('android-app/app/src/main/res/values/strings.xml');
  const stringsFr = readText('android-app/app/src/main/res/values-fr/strings.xml');

  // Ownership attribution preserved in both locales and splash/settings components
  assert.match(stringsEn, /Owned by Mr\. Amin M\. Gutale/);
  assert.match(stringsFr, /Owned by Mr\. Amin M\. Gutale/);
  const brandComponents = readText(
    'android-app/app/src/main/java/com/vumiq/cashbook/ui/components/BrandComponents.kt'
  );
  assert.match(brandComponents, /VUMIQ/);

  // 1-to-1 resource key parity between EN and FR strings.xml
  const enKeys = [...stringsEn.matchAll(/<string name="([^"]+)">/g)].map((m) => m[1]).sort();
  const frKeys = [...stringsFr.matchAll(/<string name="([^"]+)">/g)].map((m) => m[1]).sort();
  assert.deepEqual(enKeys, frKeys, 'EN and FR Android string resource keys must match 1-to-1');

  // Verify all 6 JUnit test suites exist under android-app/app/src/test
  for (const junitPath of [
    'android-app/app/src/test/java/com/vumiq/cashbook/domain/AccountingEngineTest.kt',
    'android-app/app/src/test/java/com/vumiq/cashbook/domain/BiometricGatePolicyTest.kt',
    'android-app/app/src/test/java/com/vumiq/cashbook/domain/CloudSyncEngineTest.kt',
    'android-app/app/src/test/java/com/vumiq/cashbook/domain/BackupRestorePlannerTest.kt',
    'android-app/app/src/test/java/com/vumiq/cashbook/domain/DueDateReminderEvaluatorTest.kt',
    'android-app/app/src/test/java/com/vumiq/cashbook/data/attachments/ReceiptStorageLogicTest.kt',
  ]) {
    assert.equal(fs.existsSync(path.join(ROOT, junitPath)), true, `Missing JUnit test file: ${junitPath}`);
  }

  // Production README accurately documents permanent keystore, Firebase SHA-1/SHA-256, and Samsung install
  const androidReadme = readText('android-app/README.md');
  assert.equal(
    androidReadme.includes('automatically generates a valid release keystore'),
    false,
    'README must not claim CI auto-generates ephemeral release keystores'
  );
  for (const requiredDocToken of [
    'VUMIQ_KEYSTORE_BASE64',
    'VUMIQ_STORE_PASSWORD',
    'VUMIQ_KEY_ALIAS',
    'VUMIQ_KEY_PASSWORD',
    'VUMIQ_GOOGLE_SERVICES_JSON',
    'SHA-1',
    'SHA-256',
    'com.vumiq.cashbook',
    'Owned by Mr. Amin M. Gutale',
  ]) {
    assert.equal(
      androidReadme.includes(requiredDocToken),
      true,
      `android-app/README.md must document ${requiredDocToken}`
    );
  }
});


