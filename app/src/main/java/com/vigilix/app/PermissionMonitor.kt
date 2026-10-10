package com.vigilix.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Detector de permisos sensibles en TIEMPO REAL: lista las apps de terceros
 * que tienen concedidos permisos de riesgo (ubicación, micrófono, cámara,
 * contactos, SMS, llamadas). No pide permisos ni hace cambios: solo informa
 * para que la persona decida.
 */
object PermissionMonitor {

    data class SensitiveApp(
        val packageName: String,
        val label: String,
        val granted: List<String>,
    )

    /** Permisos sensibles: (permiso, nombre legible). */
    private val SENSITIVE = listOf(
        "android.permission.ACCESS_FINE_LOCATION" to "Ubicación",
        "android.permission.ACCESS_COARSE_LOCATION" to "Ubicación",
        "android.permission.RECORD_AUDIO" to "Micrófono",
        "android.permission.CAMERA" to "Cámara",
        "android.permission.READ_CONTACTS" to "Contactos",
        "android.permission.READ_SMS" to "SMS",
        "android.permission.READ_CALL_LOG" to "Registro de llamadas",
        "android.permission.SYSTEM_ALERT_WINDOW" to "Dibujar sobre otras apps",
    )

    /**
     * Apps de terceros (no de sistema, no Vigilix) que tienen al menos un
     * permiso sensible concedido. Ordenadas por cantidad de permisos
     * sensibles y luego por nombre.
     */
    @Suppress("DEPRECATION")
    fun findSensitiveApps(context: Context): List<SensitiveApp> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        val self = context.packageName
        val result = ArrayList<SensitiveApp>()
        for (info in packages) {
            val appInfo = info.applicationInfo ?: continue
            if (info.packageName == self) continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue
            val granted = grantedSensitive(info)
            if (granted.isEmpty()) continue
            result.add(SensitiveApp(info.packageName, appInfo.loadLabel(pm).toString(), granted))
        }
        result.sortWith(compareByDescending<SensitiveApp> { it.granted.size }.thenBy { it.label.lowercase() })
        return result
    }

    private fun grantedSensitive(info: PackageInfo): List<String> {
        val requested = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: return emptyList()
        val found = ArrayList<String>()
        for ((permission, name) in SENSITIVE) {
            val index = requested.indexOf(permission)
            val granted = index >= 0 && index < flags.size &&
                (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (granted && name !in found) found.add(name)
        }
        return found
    }
}