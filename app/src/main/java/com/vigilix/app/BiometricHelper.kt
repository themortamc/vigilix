package com.vigilix.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.GeneralSecurityException

/**
 * Desbloqueo con huella. La clave derivada de la bóveda se guarda cifrada con una clave del Keystore
 * que EXIGE la huella en cada uso. Si cambian las huellas del teléfono, esa clave se invalida y
 * vuelve a pedirse la clave maestra (comportamiento esperado, no un error).
 */
object BiometricHelper {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun promptInfo(title: String, negative: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

    /** Activa la huella: pide confirmarla y guarda la clave de la sesión abierta protegida por ella. */
    fun enable(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val key = SecurityBridge.exportVaultKey()
        if (key == null) {
            onResult(false)
            return
        }
        val cipher = try {
            KeystoreCrypto.newBiometricEncryptCipher()
        } catch (e: Exception) {
            // GeneralSecurityException, ProviderException, IllegalStateException (sin huellas registradas)...
            key.fill(0)
            onResult(false)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    try {
                        if (c == null) {
                            onResult(false)
                        } else {
                            val sealed = c.doFinal(key)
                            Prefs.saveBiometric(activity, c.iv, sealed)
                            onResult(true)
                        }
                    } catch (e: GeneralSecurityException) {
                        onResult(false)
                    } finally {
                        key.fill(0)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    key.fill(0)
                    KeystoreCrypto.deleteBiometricKey()
                    onResult(false)
                }
            },
        )
        prompt.authenticate(
            promptInfo(activity.getString(R.string.bio_enable_title), activity.getString(R.string.cancel)),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    fun disable(context: Context) {
        Prefs.clearBiometric(context)
        KeystoreCrypto.deleteBiometricKey()
    }

    /** Pide la huella y abre la bóveda. `onResult(true)` si quedó abierta. */
    fun unlock(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val stored = Prefs.loadBiometric(activity)
        val cipher = stored?.let { KeystoreCrypto.biometricDecryptCipher(it.first) }
        if (stored == null || cipher == null) {
            // La clave del Keystore ya no sirve (cambiaron las huellas): se desactiva y se pide la clave maestra.
            disable(activity)
            onResult(false)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    var key: ByteArray? = null
                    try {
                        key = c?.doFinal(stored.second)
                        onResult(key != null && VaultStore.unlockWithKey(activity, key) == UnlockResult.OK)
                    } catch (e: GeneralSecurityException) {
                        onResult(false)
                    } finally {
                        key?.fill(0)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }
            },
        )
        prompt.authenticate(
            promptInfo(activity.getString(R.string.bio_unlock_title), activity.getString(R.string.bio_use_master)),
            BiometricPrompt.CryptoObject(cipher),
        )
    }
}
