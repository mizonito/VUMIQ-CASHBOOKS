package com.vumiq.cashbook.domain

enum class BiometricAvailability {
    AVAILABLE,
    NOT_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE
}

enum class RelockTimeoutOption(
    val seconds: Int,
    val labelEn: String,
    val labelFr: String
) {
    IMMEDIATE(0, "Immediately", "Immédiat"),
    ONE_MINUTE(60, "After 1 min", "Après 1 min"),
    FIVE_MINUTES(300, "After 5 min", "Après 5 min");

    companion object {
        fun fromSeconds(seconds: Int): RelockTimeoutOption =
            entries.firstOrNull { it.seconds == seconds } ?: ONE_MINUTE
    }
}

data class BiometricGateSnapshot(
    val isBiometricUnlockEnabled: Boolean = true,
    val availability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    val isSessionAuthenticated: Boolean = false,
    val relockTimeout: RelockTimeoutOption = RelockTimeoutOption.ONE_MINUTE,
    val lastBackgroundedEpochMs: Long? = null,
    val lastAuthError: String? = null
) {
    /**
     * Determines whether the app boot / resume gate requires BiometricPrompt before revealing the ledger.
     * Safely bypasses the gate when Biometric Unlock is disabled in DataStore or when biometric/credential
     * hardware is unavailable or not enrolled on the phone.
     */
    val requiresUnlock: Boolean
        get() = isBiometricUnlockEnabled &&
            availability == BiometricAvailability.AVAILABLE &&
            !isSessionAuthenticated
}

object BiometricGatePolicy {

    /**
     * Evaluates initial app boot lock state from the persisted DataStore 'Biometric Unlock' preference
     * and current device sensor availability.
     */
    fun evaluateColdStart(
        isBiometricUnlockEnabled: Boolean,
        availability: BiometricAvailability,
        relockTimeoutSeconds: Int
    ): BiometricGateSnapshot {
        val timeout = RelockTimeoutOption.fromSeconds(relockTimeoutSeconds)
        val autoAuthenticated = !isBiometricUnlockEnabled || availability != BiometricAvailability.AVAILABLE
        return BiometricGateSnapshot(
            isBiometricUnlockEnabled = isBiometricUnlockEnabled,
            availability = availability,
            isSessionAuthenticated = autoAuthenticated,
            relockTimeout = timeout,
            lastBackgroundedEpochMs = null,
            lastAuthError = null
        )
    }

    /**
     * Updates runtime gate state when the user toggles 'Biometric Unlock' in SettingsView.
     * Turning the toggle ON while already inside Settings keeps the current active session authenticated
     * and enforces BiometricPrompt on the next app boot or background timeout.
     * Turning the toggle OFF immediately clears any lock requirement and background timer.
     */
    fun onBiometricPreferenceChanged(
        current: BiometricGateSnapshot,
        enabled: Boolean,
        freshAvailability: BiometricAvailability
    ): BiometricGateSnapshot = current.copy(
        isBiometricUnlockEnabled = enabled,
        availability = freshAvailability,
        isSessionAuthenticated = true,
        lastBackgroundedEpochMs = if (enabled) current.lastBackgroundedEpochMs else null,
        lastAuthError = null
    )

    fun onAuthenticationSucceeded(current: BiometricGateSnapshot): BiometricGateSnapshot =
        current.copy(
            isSessionAuthenticated = true,
            lastBackgroundedEpochMs = null,
            lastAuthError = null
        )

    fun onAuthenticationError(
        current: BiometricGateSnapshot,
        message: String?
    ): BiometricGateSnapshot = current.copy(
        isSessionAuthenticated = false,
        lastAuthError = message?.takeIf { it.isNotBlank() }
    )

    fun onLockManually(current: BiometricGateSnapshot): BiometricGateSnapshot {
        if (current.availability != BiometricAvailability.AVAILABLE) return current
        return current.copy(
            isBiometricUnlockEnabled = true,
            isSessionAuthenticated = false,
            lastAuthError = null
        )
    }

    fun onAppBackgrounded(
        current: BiometricGateSnapshot,
        nowEpochMs: Long,
        suppressRelockForSystemSheet: Boolean = false
    ): BiometricGateSnapshot {
        if (suppressRelockForSystemSheet || !current.isBiometricUnlockEnabled) {
            return current.copy(lastBackgroundedEpochMs = null)
        }
        return current.copy(lastBackgroundedEpochMs = nowEpochMs)
    }

    fun onAppForegrounded(
        current: BiometricGateSnapshot,
        nowEpochMs: Long,
        freshAvailability: BiometricAvailability
    ): BiometricGateSnapshot {
        if (!current.isBiometricUnlockEnabled || freshAvailability != BiometricAvailability.AVAILABLE) {
            return current.copy(
                availability = freshAvailability,
                isSessionAuthenticated = true,
                lastBackgroundedEpochMs = null,
                lastAuthError = null
            )
        }

        val bgAt = current.lastBackgroundedEpochMs
        if (bgAt == null) {
            return current.copy(availability = freshAvailability)
        }

        val elapsedMs = (nowEpochMs - bgAt).coerceAtLeast(0L)
        val thresholdMs = current.relockTimeout.seconds * 1_000L
        val shouldRelock = elapsedMs >= thresholdMs

        return current.copy(
            availability = freshAvailability,
            isSessionAuthenticated = if (shouldRelock) false else current.isSessionAuthenticated,
            lastBackgroundedEpochMs = null,
            lastAuthError = if (shouldRelock) null else current.lastAuthError
        )
    }
}
