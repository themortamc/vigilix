package com.vigilix.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado con claves del Android Keystore (no salen del chip de seguridad del teléfono).
 *
 * - Clave "plain": protege datos chicos de la app (API key de VirusTotal, URL de la lista).
 * - Clave "bio": exige huella en cada uso. Envuelve la clave derivada de la bóveda.
 */
object KeystoreCrypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PLAIN = "vigilix_plain_v1"
    private const val ALIAS_BIO = "vigilix_bio_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate(alias: String, requireBiometric: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (requireBiometric) {
            builder.setUserAuthenticationRequired(true)
            builder.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Autenticación en cada uso, solo con biometría fuerte.
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun existingOrNew(alias: String): SecretKey {
        val ks = keyStore()
        val existing = ks.getKey(alias, null) as? SecretKey
        return existing ?: generate(alias, requireBiometric = false)
    }

    // ----- Datos chicos de la app -----

    /** Devuelve "iv:texto" en Base64. */
    fun encryptString(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingOrNew(ALIAS_PLAIN))
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decryptString(box: String): String? {
        return try {
            val parts = box.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = keyStore().getKey(ALIAS_PLAIN, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ----- Huella -----

    /** Cipher listo para CIFRAR, con una clave nueva que exige huella. Se pasa a BiometricPrompt. */
    fun newBiometricEncryptCipher(): Cipher {
        deleteBiometricKey()
        val key = generate(ALIAS_BIO, requireBiometric = true)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Cipher listo para DESCIFRAR, o null si la clave ya no existe o quedó invalidada (cambió la huella). */
    fun biometricDecryptCipher(iv: ByteArray): Cipher? {
        return try {
            val key = keyStore().getKey(ALIAS_BIO, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    fun deleteBiometricKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS_BIO)) ks.deleteEntry(ALIAS_BIO)
        } catch (e: GeneralSecurityException) {
            // Si no se puede borrar, la clave queda inutilizable sin la huella igualmente.
        }
    }
}
