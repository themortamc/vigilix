package com.vigilix.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Proveedores de DNS privado (DNS sobre TLS) que ofrece la app. Valores fijos, sin texto libre. */
enum class DnsProvider(val host: String?, val mode: String) {
    ADGUARD("dns.adguard-dns.com", "hostname"),
    QUAD9("dns.quad9.net", "hostname"),

    /** Valor por defecto de Android: cifra solo si la red lo permite. */
    AUTOMATIC(null, "opportunistic"),
}

data class DnsState(val mode: String?, val host: String?) {
    val isCustomHost: Boolean get() = mode == "hostname" && !host.isNullOrBlank()
}

/** Lectura y escritura del DNS privado (requiere Android 9 / API 28 o superior). */
object DnsController {
    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"

    /** Estado real del teléfono (se puede leer sin permisos especiales). */
    fun read(context: Context): DnsState {
        val resolver = context.contentResolver
        return DnsState(
            Settings.Global.getString(resolver, KEY_MODE),
            Settings.Global.getString(resolver, KEY_SPECIFIER),
        )
    }

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Comando que el usuario ejecuta una sola vez desde una PC para darle el permiso a la app. */
    fun adbCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** `true` si el estado actual del teléfono coincide con el proveedor pedido. */
    fun isApplied(context: Context, provider: DnsProvider): Boolean {
        val state = read(context)
        return state.mode == provider.mode && (provider.host == null || state.host == provider.host)
    }

    /** Escribe el ajuste sin shell. Devuelve `true` solo si el cambio quedó verificado. */
    fun applyWithSettings(context: Context, provider: DnsProvider): Boolean {
        return try {
            val resolver = context.contentResolver
            val host = provider.host
            if (host != null) {
                Settings.Global.putString(resolver, KEY_SPECIFIER, host)
            }
            Settings.Global.putString(resolver, KEY_MODE, provider.mode)
            isApplied(context, provider)
        } catch (e: SecurityException) {
            false
        }
    }
}
