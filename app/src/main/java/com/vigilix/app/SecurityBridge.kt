package com.vigilix.app

import android.os.ParcelFileDescriptor

/**
 * Puente JNI hacia libvigilix_core (Rust).
 *
 * Regla de diseño: si el motor nativo no está disponible o falla, SE DEVUELVE null.
 * Nunca se inventan contraseñas, hashes ni textos cifrados de "relleno".
 * La interfaz decide cómo mostrar el error al usuario.
 */
object SecurityBridge {

    /** `true` si la biblioteca nativa se cargó correctamente. */
    val isLoaded: Boolean = try {
        System.loadLibrary("vigilix_core")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    } catch (e: SecurityException) {
        false
    }

    // Los nombres de estas funciones están fijados por la capa JNI de Rust:
    // Java_com_vigilix_app_SecurityBridge_<nombre>. No renombrar sin cambiar rust/src/lib.rs.
    private external fun generateHighEntropyPassword(length: Int): String?
    private external fun encryptSecret(masterKey: String, plaintext: String): String?
    private external fun decryptSecret(masterKey: String, payload: String): String?
    private external fun sha256OfFd(fd: Int): String?

    /** Contraseña aleatoria (8..128 caracteres). */
    fun newPassword(length: Int): String? = guarded { generateHighEntropyPassword(length) }

    /** Devuelve "vgx1:<hex>" o null si falló. */
    fun encrypt(masterKey: String, plaintext: String): String? =
        guarded { encryptSecret(masterKey, plaintext) }

    /** Devuelve el texto original o null (clave incorrecta, datos dañados o motor no disponible). */
    fun decrypt(masterKey: String, payload: String): String? =
        guarded { decryptSecret(masterKey, payload) }

    /**
     * SHA-256 de un descriptor de archivo. El descriptor debe venir de `detachFd()`:
     * esta función (y Rust) pasan a ser dueños de él y lo cierran.
     */
    fun sha256OfDescriptor(fd: Int): String? {
        if (!isLoaded) {
            closeQuietly(fd)
            return null
        }
        return try {
            sha256OfFd(fd)
        } catch (e: UnsatisfiedLinkError) {
            // El código nativo no llegó a ejecutarse, así que el descriptor sigue abierto.
            closeQuietly(fd)
            null
        }
    }

    private inline fun guarded(block: () -> String?): String? {
        if (!isLoaded) return null
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    private fun closeQuietly(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: Exception) {
            // Nada más que hacer.
        }
    }
}
