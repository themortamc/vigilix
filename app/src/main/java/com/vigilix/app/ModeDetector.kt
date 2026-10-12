package com.vigilix.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detecta el modo de ejecución: RootMode (acceso root completo) o NonRootMode (máximo sin root).
 */
sealed interface AppMode {
    data class Root(
        val hasMagisk: Boolean,
        val hasKernelSu: Boolean,
        val hasDebug: Boolean
    ) : AppMode

    data class NonRoot(
        val hasDebug: Boolean,
        val hasAdbWireless: Boolean
    ) : AppMode
}

/**
 * Detecta el modo de ejecución al inicio de la app.
 */
object ModeDetector {

    private val MAGISK_PATHS = arrayOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/system/bin/magisk",
        "/system/xbin/magisk"
    )

    private val KERNEL_SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/vendor/bin/su"
    )

    /**
     * Detecta el modo de forma asíncrona (algunas cheques requieren I/O).
     */
    suspend fun detect(context: Context): AppMode = withContext(Dispatchers.IO) {
        val rootCheck = PrivilegedEngine.checkRoot()
        val magisk = detectMagisk()
        val kernelSu = detectKernelSu()
        val debug = isDebuggable(context)
        val adbWireless = hasAdbWireless(context)

        if (rootCheck || magisk || kernelSu) {
            AppMode.Root(
                hasMagisk = magisk,
                hasKernelSu = kernelSu,
                hasDebug = debug
            )
        } else {
            AppMode.NonRoot(
                hasDebug = debug,
                hasAdbWireless = adbWireless
            )
        }
    }

    private suspend fun detectMagisk(): Boolean = withContext(Dispatchers.IO) {
        MAGISK_PATHS.any { File(it).exists() } ||
        try {
            Runtime.getRuntime().exec("magisk --version").waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun detectKernelSu(): Boolean = withContext(Dispatchers.IO) {
        KERNEL_SU_PATHS.any { File(it).exists() } ||
        try {
            Runtime.getRuntime().exec("which su").waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private suspend fun hasAdbWireless(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = Runtime.getRuntime().exec("getprop service.adb.tcp.port").inputStream.bufferedReader().readText().trim()
            output.isNotBlank() && output != "0"
        } catch (_: Exception) {
            false
        }
    }
}