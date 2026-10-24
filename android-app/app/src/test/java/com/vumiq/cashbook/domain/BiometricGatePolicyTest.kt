package com.vumiq.cashbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricGatePolicyTest {

    @Test
    fun `boot check requires BiometricPrompt when DataStore preference is enabled and sensor is ready`() {
        val state = BiometricGatePolicy.evaluateColdStart(
            isBiometricUnlockEnabled = true,
            availability = BiometricAvailability.AVAILABLE,
            relockTimeoutSeconds = 60
        )
        assertTrue(state.isBiometricUnlockEnabled)
        assertTrue(state.requiresUnlock)
        assertFalse(state.isSessionAuthenticated)
    }

    @Test
    fun `boot check skips BiometricPrompt when Biometric Unlock preference is disabled in DataStore`() {
        val state = BiometricGatePolicy.evaluateColdStart(
            isBiometricUnlockEnabled = false,
            availability = BiometricAvailability.AVAILABLE,
            relockTimeoutSeconds = 60
        )
        assertFalse(state.isBiometricUnlockEnabled)
        assertFalse(state.requiresUnlock)
        assertTrue(state.isSessionAuthenticated)
    }

    @Test
    fun `boot check safely bypasses lock when no biometrics or screen lock enrolled`() {
        val state = BiometricGatePolicy.evaluateColdStart(
            isBiometricUnlockEnabled = true,
            availability = BiometricAvailability.NOT_ENROLLED,
            relockTimeoutSeconds = 60
        )
        assertFalse(state.requiresUnlock)
        assertTrue(state.isSessionAuthenticated)
    }

    @Test
    fun `toggling Biometric Unlock in SettingsView updates preference cleanly without locking active session`() {
        val disabledAtBoot = BiometricGatePolicy.evaluateColdStart(
            isBiometricUnlockEnabled = false,
            availability = BiometricAvailability.AVAILABLE,
            relockTimeoutSeconds = 60
        )
        assertFalse(disabledAtBoot.requiresUnlock)

        // User enables 'Biometric Unlock' in SettingsView -> active session stays open, next boot/background locks
        val enabledInSettings = BiometricGatePolicy.onBiometricPreferenceChanged(
            current = disabledAtBoot,
            enabled = true,
            freshAvailability = BiometricAvailability.AVAILABLE
        )
        assertTrue(enabledInSettings.isBiometricUnlockEnabled)
        assertTrue(enabledInSettings.isSessionAuthenticated)
        assertFalse(enabledInSettings.requiresUnlock)

        // User later disables 'Biometric Unlock' -> background timestamp and lock requirement clear immediately
        val bgState = BiometricGatePolicy.onAppBackgrounded(enabledInSettings, nowEpochMs = 10_000L)
        val disabledAgain = BiometricGatePolicy.onBiometricPreferenceChanged(
            current = bgState,
            enabled = false,
            freshAvailability = BiometricAvailability.AVAILABLE
        )
        assertFalse(disabledAgain.isBiometricUnlockEnabled)
        assertNull(disabledAgain.lastBackgroundedEpochMs)
        assertFalse(disabledAgain.requiresUnlock)
    }

    @Test
    fun `authentication success unlocks session and clears prior errors`() {
        val initial = BiometricGatePolicy.evaluateColdStart(
            isBiometricUnlockEnabled = true,
            availability = BiometricAvailability.AVAILABLE,
            relockTimeoutSeconds = 60
        )
        val failed = BiometricGatePolicy.onAuthenticationError(initial, "Sensor dirty")
        assertTrue(failed.requiresUnlock)
        assertEquals("Sensor dirty", failed.lastAuthError)

        val unlocked = BiometricGatePolicy.onAuthenticationSucceeded(failed)
        assertFalse(unlocked.requiresUnlock)
        assertTrue(unlocked.isSessionAuthenticated)
        assertNull(unlocked.lastAuthError)
    }

    @Test
    fun `background grace period preserves session for quick share and relocks after timeout`() {
        val unlocked = BiometricGatePolicy.onAuthenticationSucceeded(
            BiometricGatePolicy.evaluateColdStart(
                isBiometricUnlockEnabled = true,
                availability = BiometricAvailability.AVAILABLE,
                relockTimeoutSeconds = 60
            )
        )

        val bgState = BiometricGatePolicy.onAppBackgrounded(unlocked, nowEpochMs = 1_000L)

        // Returning after 15s (< 60s timeout) keeps ledger unlocked
        val quickReturn = BiometricGatePolicy.onAppForegrounded(
            current = bgState,
            nowEpochMs = 16_000L,
            freshAvailability = BiometricAvailability.AVAILABLE
        )
        assertFalse(quickReturn.requiresUnlock)

        // Returning after 65s (>= 60s timeout) requires Fingerprint/Face unlock again
        val bgAgain = BiometricGatePolicy.onAppBackgrounded(quickReturn, nowEpochMs = 20_000L)
        val expiredReturn = BiometricGatePolicy.onAppForegrounded(
            current = bgAgain,
            nowEpochMs = 85_000L,
            freshAvailability = BiometricAvailability.AVAILABLE
        )
        assertTrue(expiredReturn.requiresUnlock)
    }

    @Test
    fun `system share sheet or document picker suppression prevents spurious relock`() {
        val unlocked = BiometricGatePolicy.onAuthenticationSucceeded(
            BiometricGatePolicy.evaluateColdStart(
                isBiometricUnlockEnabled = true,
                availability = BiometricAvailability.AVAILABLE,
                relockTimeoutSeconds = 0 // Immediate relock configured
            )
        )

        val suppressedBg = BiometricGatePolicy.onAppBackgrounded(
            current = unlocked,
            nowEpochMs = 5_000L,
            suppressRelockForSystemSheet = true
        )
        val resumed = BiometricGatePolicy.onAppForegrounded(
            current = suppressedBg,
            nowEpochMs = 30_000L,
            freshAvailability = BiometricAvailability.AVAILABLE
        )
        assertFalse(resumed.requiresUnlock)
    }
}
