package com.bitchat.android.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OS-standard Biometric / Device PIN App Lock Manager using framework `BiometricPrompt`
 * and `KeyguardManager` (zero external dependencies).
 */
object AppLockManager {
    private const val PREFS_NAME = "nemix_security_prefs"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"

    private var prefs: SharedPreferences? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val enabled = sp.getBoolean(KEY_APP_LOCK_ENABLED, false)
        _isEnabled.value = enabled
        if (enabled && isDeviceSecure(context)) {
            _isLocked.value = true
        }
    }

    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        if (prefs == null) init(context)
        prefs?.edit()?.putBoolean(KEY_APP_LOCK_ENABLED, enabled)?.apply()
        _isEnabled.value = enabled
        if (!enabled) {
            _isLocked.value = false
        }
    }

    fun onAppBackgrounded(context: Context) {
        if (_isEnabled.value && isDeviceSecure(context)) {
            _isLocked.value = true
        }
    }

    fun markUnlocked() {
        _isLocked.value = false
    }

    /**
     * Triggers the OS biometric / PIN authentication prompt.
     * For API < 28 or fallback, returns a `KeyguardManager` confirm-credential Intent via [onLaunchCredentialIntent].
     */
    fun authenticate(
        activity: Activity,
        onLaunchCredentialIntent: (Intent) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isDeviceSecure(activity)) {
            markUnlocked()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle("nemix のロック解除")
                    .setSubtitle("生体認証または画面ロックで認証してください")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()

                prompt.authenticate(
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            markUnlocked()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            onError(errString?.toString() ?: "認証がキャンセルされました")
                        }
                    }
                )
                return
            } catch (_: Exception) {
                // Fall through to KeyguardManager credential intent
            }
        }

        @Suppress("DEPRECATION")
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        @Suppress("DEPRECATION")
        val intent = km?.createConfirmDeviceCredentialIntent(
            "nemix のロック解除",
            "端末のPIN・パターン・パスワードでロックを解除してください"
        )
        if (intent != null) {
            onLaunchCredentialIntent(intent)
        } else {
            markUnlocked()
        }
    }
}

