package com.vumiq.cashbook.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vumiq.cashbook.R
import com.vumiq.cashbook.domain.BiometricAvailability

sealed interface BiometricAuthOutcome {
    data object Succeeded : BiometricAuthOutcome
    data object CancelledByUser : BiometricAuthOutcome
    data class Error(val errorCode: Int, val message: String) : BiometricAuthOutcome
}

/**
 * Native Android BiometricPrompt coordinator supporting Samsung Ultrasonic Fingerprint,
 * Face Unlock (Class 2 / Class 3), and Device PIN/Pattern fallback across Android 8.0–15.
 */
object BiometricAuthManager {

    private fun preferredAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }

    fun checkAvailability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        val primaryStatus = manager.canAuthenticate(preferredAuthenticators())
        if (primaryStatus == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricAvailability.AVAILABLE
        }

        // Fallback check for biometric-only sensors if device credential bridge is restricted
        val biometricOnlyStatus = manager.canAuthenticate(BIOMETRIC_WEAK)
        return when (biometricOnlyStatus) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        onOutcome: (BiometricAuthOutcome) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val manager = BiometricManager.from(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onOutcome(BiometricAuthOutcome.Succeeded)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val isUserCancel = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (isUserCancel) {
                    onOutcome(BiometricAuthOutcome.CancelledByUser)
                } else {
                    onOutcome(BiometricAuthOutcome.Error(errorCode, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // System BiometricPrompt automatically stays open and prompts retry on mismatched touch/face
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val combinedAuth = preferredAuthenticators()
        val canUseDeviceCredential =
            manager.canAuthenticate(combinedAuth) == BiometricManager.BIOMETRIC_SUCCESS

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            .setDescription(activity.getString(R.string.biometric_prompt_description))
            .setConfirmationRequired(false) // Enables instant one-tap / glance unlock without extra Confirm tap

        if (canUseDeviceCredential) {
            promptInfoBuilder.setAllowedAuthenticators(combinedAuth)
        } else {
            promptInfoBuilder
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setNegativeButtonText(activity.getString(R.string.biometric_negative_cancel))
        }

        runCatching {
            prompt.authenticate(promptInfoBuilder.build())
        }.onFailure { err ->
            onOutcome(
                BiometricAuthOutcome.Error(
                    errorCode = -1,
                    message = err.localizedMessage ?: "Biometric sensor unavailable."
                )
            )
        }
    }
}
