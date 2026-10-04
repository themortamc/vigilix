package com.vigilix.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/** Ajustes de la app. Nada de esto es la bóveda: las claves y URLs con credenciales se guardan cifradas. */
object Prefs {
    private const val FILE = "vx_prefs"

    private const val K_AUTOLOCK = "autolock_seconds"
    private const val K_SCREEN_OFF = "lock_on_screen_off"
    private const val K_BIO_IV = "bio_iv"
    private const val K_BIO_CT = "bio_ct"
    private const val K_VT_ENABLED = "vt_enabled"
    private const val K_VT_KEY = "vt_key_box"
    private const val K_VT_DAY = "vt_day"
    private const val K_VT_COUNT = "vt_count"
    private const val K_DB_URL = "db_url_box"
    private const val K_DB_UPDATED = "db_updated_at"
    private const val K_SCAN_FULL = "scan_full"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ----- Bóveda -----

    /** Segundos en segundo plano antes de bloquear la bóveda. 0 = al salir de la app. */
    fun autoLockSeconds(context: Context): Int = sp(context).getInt(K_AUTOLOCK, 60)

    fun setAutoLockSeconds(context: Context, seconds: Int) {
        sp(context).edit().putInt(K_AUTOLOCK, seconds).apply()
    }

    fun lockOnScreenOff(context: Context): Boolean = sp(context).getBoolean(K_SCREEN_OFF, true)

    fun setLockOnScreenOff(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(K_SCREEN_OFF, value).apply()
    }

    /** La huella está activada si hay una clave guardada protegida por el Keystore. */
    fun biometricEnabled(context: Context): Boolean {
        val s = sp(context)
        return s.contains(K_BIO_IV) && s.contains(K_BIO_CT)
    }

    fun saveBiometric(context: Context, iv: ByteArray, ciphertext: ByteArray) {
        sp(context).edit()
            .putString(K_BIO_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(K_BIO_CT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun loadBiometric(context: Context): Pair<ByteArray, ByteArray>? {
        val s = sp(context)
        val iv = s.getString(K_BIO_IV, null) ?: return null
        val ct = s.getString(K_BIO_CT, null) ?: return null
        return try {
            Pair(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(ct, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun clearBiometric(context: Context) {
        sp(context).edit().remove(K_BIO_IV).remove(K_BIO_CT).apply()
    }

    // ----- Escáner -----

    fun scanFull(context: Context): Boolean = sp(context).getBoolean(K_SCAN_FULL, false)

    fun setScanFull(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(K_SCAN_FULL, value).apply()
    }

    fun vtEnabled(context: Context): Boolean = sp(context).getBoolean(K_VT_ENABLED, false)

    fun setVtEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(K_VT_ENABLED, value).apply()
    }

    fun vtKey(context: Context): String? =
        sp(context).getString(K_VT_KEY, null)?.let { KeystoreCrypto.decryptString(it) }

    fun setVtKey(context: Context, key: String?) {
        val editor = sp(context).edit()
        if (key.isNullOrBlank()) editor.remove(K_VT_KEY) else editor.putString(K_VT_KEY, KeystoreCrypto.encryptString(key.trim()))
        editor.apply()
    }

    /** Consultas a VirusTotal hechas hoy (el plan gratis permite 500 por día). */
    fun vtUsedToday(context: Context): Int {
        val s = sp(context)
        return if (s.getLong(K_VT_DAY, -1L) == today()) s.getInt(K_VT_COUNT, 0) else 0
    }

    fun addVtUsage(context: Context, amount: Int = 1) {
        val s = sp(context)
        val used = vtUsedToday(context) + amount
        s.edit().putLong(K_VT_DAY, today()).putInt(K_VT_COUNT, used).apply()
    }

    /** URL de la lista de firmas (puede incluir una clave de acceso, por eso se guarda cifrada). */
    fun dbUrl(context: Context): String? =
        sp(context).getString(K_DB_URL, null)?.let { KeystoreCrypto.decryptString(it) }

    fun setDbUrl(context: Context, url: String?) {
        val editor = sp(context).edit()
        if (url.isNullOrBlank()) editor.remove(K_DB_URL) else editor.putString(K_DB_URL, KeystoreCrypto.encryptString(url.trim()))
        editor.apply()
    }

    fun dbUpdatedAt(context: Context): Long = sp(context).getLong(K_DB_UPDATED, 0L)

    fun setDbUpdatedAt(context: Context, millis: Long) {
        sp(context).edit().putLong(K_DB_UPDATED, millis).apply()
    }

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L
}
