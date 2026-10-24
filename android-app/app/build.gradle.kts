import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vumiq.cashbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vumiq.cashbook"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("vumiqVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("vumiqVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Provisioned Firebase project, named Firestore database, and Web OAuth client ID from VUMIQ Cashbook workspace
        val webClientId = (System.getenv("VUMIQ_WEB_CLIENT_ID")
            ?: (project.findProperty("vumiqWebClientId") as String?))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "17133092640-8kjv3sdllqfvphpisf18v3f752rioiji.apps.googleusercontent.com"
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"sixth-etching-101dl\"")
        buildConfigField("String", "FIRESTORE_DATABASE_ID", "\"ai-studio-vumiqcashbook-dd57b662-22cd-4575-bb13-5e888a1b3793\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
    }

    val resolvedKeystorePath = (System.getenv("SIGNING_KEYSTORE_PATH")
        ?: keystoreProps.getProperty("storeFile"))?.trim().orEmpty()
    val resolvedStorePassword = (System.getenv("SIGNING_STORE_PASSWORD")
        ?: keystoreProps.getProperty("storePassword")).orEmpty()
    val resolvedKeyAlias = (System.getenv("SIGNING_KEY_ALIAS")
        ?: keystoreProps.getProperty("keyAlias"))?.trim().orEmpty()
    val resolvedKeyPassword = (System.getenv("SIGNING_KEY_PASSWORD")
        ?: keystoreProps.getProperty("keyPassword")).orEmpty()
    val resolvedKeystoreFile = resolvedKeystorePath.takeIf { it.isNotEmpty() }?.let { path ->
        val candidate = file(path)
        if (candidate.exists()) candidate else rootProject.file(path)
    }
    val hasCompleteReleaseSigning = resolvedKeystoreFile != null &&
        resolvedKeystoreFile.exists() &&
        resolvedKeystoreFile.length() > 0L &&
        resolvedStorePassword.isNotEmpty() &&
        resolvedKeyAlias.isNotEmpty() &&
        resolvedKeyPassword.isNotEmpty()

    signingConfigs {
        create("release") {
            if (hasCompleteReleaseSigning && resolvedKeystoreFile != null) {
                storeFile = resolvedKeystoreFile
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyReleaseSigningConfigured by tasks.registering {
    group = "verification"
    description = "Fails release builds clearly if the permanent VUMIQ release keystore or credentials are missing."
    doLast {
        val ksPath = (System.getenv("SIGNING_KEYSTORE_PATH")
            ?: keystoreProps.getProperty("storeFile"))?.trim().orEmpty()
        val storePass = (System.getenv("SIGNING_STORE_PASSWORD")
            ?: keystoreProps.getProperty("storePassword")).orEmpty()
        val aliasName = (System.getenv("SIGNING_KEY_ALIAS")
            ?: keystoreProps.getProperty("keyAlias"))?.trim().orEmpty()
        val keyPass = (System.getenv("SIGNING_KEY_PASSWORD")
            ?: keystoreProps.getProperty("keyPassword")).orEmpty()
        val ksFile = ksPath.takeIf { it.isNotEmpty() }?.let { p ->
            val f = file(p)
            if (f.exists()) f else rootProject.file(p)
        }

        val missing = mutableListOf<String>()
        if (ksFile == null || !ksFile.exists() || ksFile.length() == 0L) {
            missing += "SIGNING_KEYSTORE_PATH / storeFile (existing non-empty .jks keystore)"
        }
        if (storePass.isEmpty()) missing += "SIGNING_STORE_PASSWORD / storePassword"
        if (aliasName.isEmpty()) missing += "SIGNING_KEY_ALIAS / keyAlias"
        if (keyPass.isEmpty()) missing += "SIGNING_KEY_PASSWORD / keyPassword"

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Permanent release signing is required for VUMIQ Cashbook release builds. " +
                    "Missing or invalid configuration: ${missing.joinToString(", ")}. " +
                    "Configure GitHub Actions secrets (VUMIQ_KEYSTORE_BASE64, VUMIQ_STORE_PASSWORD, " +
                    "VUMIQ_KEY_ALIAS, VUMIQ_KEY_PASSWORD) or local android-app/keystore.properties. " +
                    "Debug builds (./gradlew assembleDebug) remain usable without release credentials."
            )
        }
    }
}

tasks.configureEach {
    if (name in setOf("assembleRelease", "packageRelease", "bundleRelease")) {
        dependsOn(verifyReleaseSigningConfigured)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
