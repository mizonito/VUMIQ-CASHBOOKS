package com.vumiq.cashbook.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vumiq.cashbook.domain.BiometricAvailability
import com.vumiq.cashbook.domain.BiometricGatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `reads default biometric_unlock_enabled state and persists toggle writes to DataStore`() = runBlocking {
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val backingFile = tempFolder.newFile("vumiq_settings_test.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { backingFile }
            )
            val repository = SettingsRepository(dataStore)

            // Default state is enabled
            assertEquals(true, repository.biometricUnlockEnabledFlow.first())
            assertTrue(repository.isBiometricUnlockEnabled())

            // Simulate user toggling 'Biometric Unlock' OFF in SettingsView
            repository.setBiometricUnlockEnabled(false)
            assertEquals(false, repository.biometricUnlockEnabledFlow.first())
            assertFalse(repository.isBiometricUnlockEnabled())

            // Simulate next app boot security check reading from SettingsRepository
            val nextBootWhenDisabled = BiometricGatePolicy.evaluateColdStart(
                isBiometricUnlockEnabled = repository.isBiometricUnlockEnabled(),
                availability = BiometricAvailability.AVAILABLE,
                relockTimeoutSeconds = repository.getRelockTimeoutSeconds()
            )
            assertFalse(nextBootWhenDisabled.requiresUnlock)
            assertTrue(nextBootWhenDisabled.isSessionAuthenticated)

            // Simulate user toggling 'Biometric Unlock' back ON in SettingsView
            repository.setBiometricUnlockEnabled(true)
            assertEquals(true, repository.biometricUnlockEnabledFlow.first())
            assertTrue(repository.isBiometricUnlockEnabled())

            // Next app boot security check immediately enforces BiometricPrompt
            val nextBootWhenEnabled = BiometricGatePolicy.evaluateColdStart(
                isBiometricUnlockEnabled = repository.isBiometricUnlockEnabled(),
                availability = BiometricAvailability.AVAILABLE,
                relockTimeoutSeconds = repository.getRelockTimeoutSeconds()
            )
            assertTrue(nextBootWhenEnabled.requiresUnlock)
            assertFalse(nextBootWhenEnabled.isSessionAuthenticated)
        } finally {
            storeScope.cancel()
        }
    }
}
