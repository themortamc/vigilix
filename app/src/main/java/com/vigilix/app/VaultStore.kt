package com.vigilix.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

enum class UnlockResult { OK, WRONG_KEY, ENGINE_UNAVAILABLE, NO_VAULT, CORRUPT }

/**
 * Bóveda de contraseñas. El archivo `vault.vgv` está cifrado por Rust (Argon2id + ChaCha20-Poly1305);
 * la clave derivada vive solo en la sesión de Rust. Aquí se guarda la lista descifrada en memoria
 * mientras la bóveda está desbloqueada, y se borra al bloquear.
 */
object VaultStore {
    private const val FILE_NAME = "vault.vgv"
    private const val FORMAT_VERSION = 1

    private val items = ArrayList<Credential>()
    private val handler = Handler(Looper.getMainLooper())
    private var lockTask: Runnable? = null
    private var visibleActivities = 0

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    val isUnlocked: Boolean get() = SecurityBridge.isVaultUnlocked()

    // ----- Crear / abrir / bloquear -----

    fun create(context: Context, masterPassword: String): Boolean {
        if (!SecurityBridge.isLoaded) return false
        synchronized(items) { items.clear() }
        val blob = SecurityBridge.vaultCreateBlob(masterPassword, serialize()) ?: return false
        if (!writeBlob(context, blob)) {
            SecurityBridge.lockVault()
            return false
        }
        return true
    }

    fun unlockWithPassword(context: Context, masterPassword: String): UnlockResult {
        if (!SecurityBridge.isLoaded) return UnlockResult.ENGINE_UNAVAILABLE
        val blob = readBlob(context) ?: return UnlockResult.NO_VAULT
        val json = SecurityBridge.vaultOpenBlob(masterPassword, blob) ?: return UnlockResult.WRONG_KEY
        return finishUnlock(json)
    }

    fun unlockWithKey(context: Context, key: ByteArray): UnlockResult {
        if (!SecurityBridge.isLoaded) return UnlockResult.ENGINE_UNAVAILABLE
        val blob = readBlob(context) ?: return UnlockResult.NO_VAULT
        val json = SecurityBridge.vaultOpenBlobWithKey(key, blob) ?: return UnlockResult.WRONG_KEY
        return finishUnlock(json)
    }

    private fun finishUnlock(json: String): UnlockResult {
        return try {
            val parsed = parse(json)
            synchronized(items) {
                items.clear()
                items.addAll(parsed)
            }
            cancelLockTimer()
            UnlockResult.OK
        } catch (e: JSONException) {
            SecurityBridge.lockVault()
            UnlockResult.CORRUPT
        }
    }

    fun lock() {
        SecurityBridge.lockVault()
        synchronized(items) { items.clear() }
        cancelLockTimer()
    }

    // ----- Consultas -----

    fun all(): List<Credential> = synchronized(items) {
        items.sortedWith(
            compareByDescending<Credential> { it.favorite }
                .thenBy { it.title.lowercase(Locale.getDefault()) },
        )
    }

    fun search(query: String): List<Credential> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return all()
        return all().filter {
            it.title.lowercase(Locale.getDefault()).contains(q) ||
                it.username.lowercase(Locale.getDefault()).contains(q) ||
                it.url.lowercase(Locale.getDefault()).contains(q)
        }
    }

    fun count(): Int = synchronized(items) { items.size }

    /**
     * Entradas que sirven para un sitio o app. El sitio se compara por dominio exacto o
     * subdominio; la app, por nombre de paquete exacto. Nunca por texto parcial.
     */
    fun matchesFor(domain: String?, packageName: String?): List<Credential> {
        return all().filter { c ->
            val host = c.host
            val byDomain = !domain.isNullOrBlank() && host != null && Credential.domainMatches(host, domain)
            val byPackage = !packageName.isNullOrBlank() && c.packageName.isNotEmpty() && c.packageName == packageName
            byDomain || byPackage
        }
    }

    // ----- Cambios -----

    fun upsert(context: Context, credential: Credential): Boolean = mutate(context) {
        val index = items.indexOfFirst { it.id == credential.id }
        if (index >= 0) items[index] = credential else items.add(credential)
    }

    fun delete(context: Context, id: String): Boolean = mutate(context) {
        items.removeAll { it.id == id }
    }

    private inline fun mutate(context: Context, change: () -> Unit): Boolean {
        if (!isUnlocked) return false
        val backup = synchronized(items) { ArrayList(items) }
        synchronized(items) { change() }
        val blob = SecurityBridge.vaultSaveBlob(serialize())
        if (blob != null && writeBlob(context, blob)) return true
        // No se pudo guardar: se vuelve al estado anterior para no mostrar algo que no está en disco.
        synchronized(items) {
            items.clear()
            items.addAll(backup)
        }
        return false
    }

    /** Cambia la clave maestra. Verifica la actual y deja la bóveda abierta con la nueva. */
    fun changeMasterPassword(context: Context, current: String, new: String): Boolean {
        val blob = readBlob(context) ?: return false
        if (SecurityBridge.vaultPeekBlob(current, blob) == null) return false
        val newBlob = SecurityBridge.vaultCreateBlob(new, serialize()) ?: return false
        if (!writeBlob(context, newBlob)) return false
        // La clave derivada cambió: la protegida por la huella ya no sirve.
        Prefs.clearBiometric(context)
        KeystoreCrypto.deleteBiometricKey()
        return true
    }

    // ----- Copias -----

    /** Contenido cifrado del archivo, tal cual (se puede copiar a cualquier lado: no es legible sin la clave). */
    fun exportBlob(context: Context): String? = readBlob(context)

    /**
     * Suma a la bóveda abierta las entradas de una copia (con la clave maestra de esa copia).
     * Devuelve cuántas entradas se agregaron o actualizaron, o null si no se pudo leer la copia.
     */
    fun importBlob(context: Context, blob: String, copyPassword: String): Int? {
        if (!isUnlocked) return null
        val json = SecurityBridge.vaultPeekBlob(copyPassword, blob) ?: return null
        val incoming = try {
            parse(json)
        } catch (e: JSONException) {
            return null
        }
        var changed = 0
        val ok = mutate(context) {
            for (c in incoming) {
                val index = items.indexOfFirst { it.id == c.id }
                if (index < 0) {
                    items.add(c)
                    changed++
                } else if (c.updatedAt > items[index].updatedAt) {
                    items[index] = c
                    changed++
                }
            }
        }
        return if (ok) changed else null
    }

    // ----- Bloqueo automático (lo maneja VigilixApp según qué pantallas se ven) -----

    fun activityStarted() {
        visibleActivities++
        cancelLockTimer()
    }

    fun activityStopped(context: Context) {
        visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
        if (visibleActivities == 0) scheduleAutoLock(context)
    }

    /** Se llama después de usar la bóveda en segundo plano (por ejemplo, al autocompletar). */
    fun touch(context: Context) {
        if (visibleActivities == 0) scheduleAutoLock(context)
    }

    private fun scheduleAutoLock(context: Context) {
        cancelLockTimer()
        if (!isUnlocked) return
        val seconds = Prefs.autoLockSeconds(context)
        if (seconds <= 0) {
            lock()
            return
        }
        val task = Runnable { lock() }
        lockTask = task
        handler.postDelayed(task, seconds * 1000L)
    }

    private fun cancelLockTimer() {
        lockTask?.let { handler.removeCallbacks(it) }
        lockTask = null
    }

    // ----- Archivo y formato -----

    private fun serialize(): String {
        val array = JSONArray()
        synchronized(items) { items.forEach { array.put(it.toJson()) } }
        return JSONObject().put("v", FORMAT_VERSION).put("items", array).toString()
    }

    private fun parse(json: String): List<Credential> {
        val root = JSONObject(json)
        val array = root.optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).map { Credential.fromJson(array.getJSONObject(it)) }
    }

    private fun readBlob(context: Context): String? {
        val f = file(context)
        return try {
            if (f.exists()) f.readText() else null
        } catch (e: IOException) {
            null
        }
    }

    /** Escritura atómica: se escribe un temporal, se fuerza a disco y se renombra. */
    private fun writeBlob(context: Context, blob: String): Boolean {
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            tmp.renameTo(target)
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }
}
