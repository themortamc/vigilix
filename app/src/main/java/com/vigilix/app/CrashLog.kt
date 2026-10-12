package com.vigilix.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Registro local de crashes/ANRs cifrado (vgx1:).
 * Opt-in: solo se activa si el usuario lo habilita en Settings (Advanced/Expert).
 * Cero PII: no URLs, IPs, credenciales ni datos de usuario.
 */
object CrashLog {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_REPORTS = 50
    private const val PREF_ENABLED = "crash_log_enabled"
    private const val PREFS_FILE = "vx_prefs"

    private val handlerRef = AtomicReference<Thread.UncaughtExceptionHandler?>(null)

    data class CrashReport(
        val id: String,
        val timestamp: Long,
        val thread: String,
        val exceptionClass: String,
        val message: String,
        val stackTrace: String,      // Cifrado vgx1:
        val deviceInfo: String,
        val userAction: String? = null
    )

    /** Inicializa el handler global. Llamar en Application.onCreate() si está habilitado. */
    fun init(context: Context) {
        if (!isEnabled(context)) return
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            log(context, throwable, thread.name)
            prev?.uncaughtException(thread, throwable)
        }
        handlerRef.set(handler)
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /** Deshabilita el handler. */
    fun disable(context: Context) {
        val h = handlerRef.getAndSet(null)
        if (h != null) {
            Thread.setDefaultUncaughtExceptionHandler(Thread.getDefaultUncaughtExceptionHandler())
        }
        setEnabled(context, false)
    }

    /** Registra un throwable manualmente (ej. catch block). */
    fun log(context: Context, throwable: Throwable, threadName: String = Thread.currentThread().name) {
        if (!isEnabled(context)) return
        val dir = getDir(context)
        dir.mkdirs()

        val stackTrace = StringBuilder()
        val writer = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(writer))
        stackTrace.append(writer.toString())

        // Cifrar el stack trace con clave derivada del timestamp (no persistente, solo ofuscación local)
        val encryptedTrace = SecurityBridge.encrypt(
            "crashlog_${System.currentTimeMillis()}",
            stackTrace.toString()
        ) ?: stackTrace.toString()

        val report = CrashReport(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            thread = threadName,
            exceptionClass = throwable::class.java.name,
            message = throwable.message ?: "null",
            stackTrace = encryptedTrace,
            deviceInfo = buildDeviceInfo(context),
            userAction = null
        )

        saveReport(context, dir, report)
        rotateOldReports(dir)
    }

    /** Obtiene todos los reports (desencriptados para mostrar al usuario). */
    fun getReports(context: Context): List<CrashReport> {
        val dir = getDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseReport(it) } ?: emptyList()
    }

    /** Elimina todos los reports. */
    fun clear(context: Context) {
        val dir = getDir(context)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Habilita/deshabilita el crash logging. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) init(context) else disable(context)
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ENABLED, false)
    }

    // ===== Private =====

    private fun getDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun buildDeviceInfo(context: Context): String {
        return "model=${Build.MODEL}; api=${Build.VERSION.SDK_INT}; app=${context.packageName}; brand=${Build.BRAND}"
    }

    private fun saveReport(context: Context, dir: File, report: CrashReport) {
        val file = File(dir, "${report.id}.json")
        val json = org.json.JSONObject().apply {
            put("id", report.id)
            put("timestamp", report.timestamp)
            put("thread", report.thread)
            put("exceptionClass", report.exceptionClass)
            put("message", report.message)
            put("stackTrace", report.stackTrace)
            put("deviceInfo", report.deviceInfo)
            put("userAction", report.userAction ?: "")
        }
        try {
            FileOutputStream(file).use { out ->
                out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun rotateOldReports(dir: File) {
        val files = dir.listFiles()?.filter { it.extension == "json" }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_REPORTS) {
            val toDelete = files.size - MAX_REPORTS
            for (i in 0 until toDelete) {
                files[i].delete()
            }
        }
    }

    private fun parseReport(file: File): CrashReport? {
        return try {
            val json = org.json.JSONObject(file.readText())
            val encryptedTrace = json.getString("stackTrace")
            val decrypted = SecurityBridge.decrypt("crashlog_${json.getLong("timestamp")}", encryptedTrace)
                ?: encryptedTrace
            CrashReport(
                id = json.getString("id"),
                timestamp = json.getLong("timestamp"),
                thread = json.getString("thread"),
                exceptionClass = json.getString("exceptionClass"),
                message = json.getString("message"),
                stackTrace = decrypted,
                deviceInfo = json.getString("deviceInfo"),
                userAction = json.optString("userAction", "").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }
}