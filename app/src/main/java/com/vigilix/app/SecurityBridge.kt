package com.vigilix.app

import android.os.ParcelFileDescriptor

/**
 * Puente JNI hacia libvigilix_core (Rust).
 *
 * Regla de diseño: si el motor nativo no está disponible o falla, SE DEVUELVE null (o false).
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
    private external fun sha256OfPath(path: String): String?

    private external fun vaultCreate(password: String, plaintext: String): String?
    private external fun vaultOpen(password: String, blob: String): String?
    private external fun vaultPeek(password: String, blob: String): String?
    private external fun vaultOpenWithKey(key: ByteArray, blob: String): String?
    private external fun vaultSave(plaintext: String): String?
    private external fun vaultLock()
    private external fun vaultIsUnlocked(): Boolean
    private external fun vaultExportKey(): ByteArray?

    private external fun hashDbMerge(dbPath: String, textPath: String, maxEntries: Int): Long
    private external fun hashDbContains(dbPath: String, hash: String): Boolean
    private external fun hashDbCount(dbPath: String): Long

    // ----- Herramientas sueltas -----

    /** Contraseña aleatoria (8..128 caracteres). */
    fun newPassword(length: Int): String? = guarded(null) { generateHighEntropyPassword(length) }

    /** Devuelve "vgx1:<hex>" o null si falló. */
    fun encrypt(masterKey: String, plaintext: String): String? =
        guarded(null) { encryptSecret(masterKey, plaintext) }

    /** Devuelve el texto original o null (clave incorrecta, datos dañados o motor no disponible). */
    fun decrypt(masterKey: String, payload: String): String? =
        guarded(null) { decryptSecret(masterKey, payload) }

    /** SHA-256 de un archivo por ruta (hex en minúsculas) o null. */
    fun sha256OfFile(path: String): String? = guarded(null) { sha256OfPath(path) }

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

    // ----- Bóveda de contraseñas (la clave derivada vive solo en Rust) -----

    /** Crea o re-cifra una bóveda con la clave maestra y deja la sesión abierta. Devuelve "vgv1:...". */
    fun vaultCreateBlob(password: String, plaintext: String): String? =
        guarded(null) { vaultCreate(password, plaintext) }

    /** Abre la bóveda con la clave maestra. Devuelve el contenido o null. */
    fun vaultOpenBlob(password: String, blob: String): String? =
        guarded(null) { vaultOpen(password, blob) }

    /** Descifra una bóveda ajena sin tocar la sesión abierta (para importar copias). */
    fun vaultPeekBlob(password: String, blob: String): String? =
        guarded(null) { vaultPeek(password, blob) }

    /** Abre la bóveda con la clave derivada protegida por la huella. */
    fun vaultOpenBlobWithKey(key: ByteArray, blob: String): String? =
        guarded(null) { vaultOpenWithKey(key, blob) }

    /** Vuelve a cifrar el contenido con la sesión abierta. null si está bloqueada. */
    fun vaultSaveBlob(plaintext: String): String? = guarded(null) { vaultSave(plaintext) }

    fun lockVault() {
        guarded(Unit) { vaultLock() }
    }

    fun isVaultUnlocked(): Boolean = guarded(false) { vaultIsUnlocked() }

    /** Clave derivada de la sesión, para envolverla con la huella. Hay que borrar el arreglo al terminar. */
    fun exportVaultKey(): ByteArray? = guarded(null) { vaultExportKey() }

    // ----- Base de firmas (hashes de malware conocido) -----

    /** Agrega a la base los SHA-256 que haya en un texto. Devuelve el total, o un número negativo si falló. */
    fun hashDbMergeText(dbPath: String, textPath: String, maxEntries: Int): Long =
        guarded(-1L) { hashDbMerge(dbPath, textPath, maxEntries) }

    fun hashDbHas(dbPath: String, sha256Hex: String): Boolean =
        guarded(false) { hashDbContains(dbPath, sha256Hex) }

    fun hashDbSize(dbPath: String): Long = guarded(0L) { hashDbCount(dbPath) }

    private inline fun <T> guarded(fallback: T, block: () -> T): T {
        if (!isLoaded) return fallback
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            fallback
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
