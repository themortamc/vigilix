package com.vigilix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta datos de la app a formatos estándar (CSV, JSON).
 * Solo disponible para usuarios Pro.
 */
object ExportManager {

    enum class Format { CSV, JSON }

    data class ExportResult(
        val success: Boolean,
        val file: File?,
        val error: String? = null,
        val format: Format,
        val type: ExportType
    )

    enum class ExportType { HISTORY, VAULT, APK_REPORT }

    /**
     * Exporta el historial de escaneos.
     */
    suspend fun exportHistory(
        context: Context,
        format: Format = Format.CSV,
        onComplete: (ExportResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val entries = HistoryManager.recentEntries(context, HistoryManager.MAX_ENTRIES)
        if (entries.isEmpty()) {
            onComplete(ExportResult(false, null, "Historial vacío", format, ExportType.HISTORY))
            return@withContext
        }

        val file = createTempFile(context, "history_", format)
        val success = try {
            when (format) {
                Format.CSV -> writeHistoryCsv(file, entries)
                Format.JSON -> writeHistoryJson(file, entries)
            }
            true
        } catch (e: Exception) {
            false
        }

        onComplete(ExportResult(success, if (success) file else null, if (!success) "Error escribiendo archivo" else null, format, ExportType.HISTORY))
    }

    /**
     * Exporta la bóveda (requiere desbloqueo previo).
     * CSV: sin contraseñas. JSON: formato vgx1: cifrado.
     */
    suspend fun exportVault(
        context: Context,
        masterPassword: String,
        format: Format = Format.CSV,
        onComplete: (ExportResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!VaultStore.isUnlocked) {
            onComplete(ExportResult(false, null, "Bóveda bloqueada", format, ExportType.VAULT))
            return@withContext
        }

        val creds = VaultStore.all()
        if (creds.isEmpty()) {
            onComplete(ExportResult(false, null, "Bóveda vacía", format, ExportType.VAULT))
            return@withContext
        }

        val file = createTempFile(context, "vault_", format)
        val success = try {
            when (format) {
                Format.CSV -> writeVaultCsv(file, creds)
                Format.JSON -> writeVaultJson(file, creds, masterPassword)
            }
            true
        } catch (e: Exception) {
            false
        }

        onComplete(ExportResult(success, if (success) file else null, if (!success) "Error exportando bóveda" else null, format, ExportType.VAULT))
    }

    /**
     * Exporta reporte de análisis APK (usa el HTML/JSON ya generado por ApkAnalyzer).
     */
    suspend fun exportApkReport(
        context: Context,
        report: ApkAnalyzer.AnalysisResult,
        format: Format = Format.JSON,
        onComplete: (ExportResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val file = createTempFile(context, "apk_report_", format)
        val success = try {
            when (format) {
                Format.CSV -> writeApkReportCsv(file, report)
                Format.JSON -> writeApkReportJson(file, report)
            }
            true
        } catch (e: Exception) {
            false
        }

        onComplete(ExportResult(success, if (success) file else null, if (!success) "Error exportando reporte APK" else null, format, ExportType.APK_REPORT))
    }

    /**
     * Comparte un archivo vía Intent (compatible con FileProvider).
     */
    fun shareFile(context: Context, file: File, mimeType: String, subject: String = "Export Vigilix") {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir exportación"))
    }

    // ===== Private writers =====

    private fun writeHistoryCsv(file: File, entries: List<HistoryManager.Entry>) {
        FileOutputStream(file).use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                w.write("timestamp,mode,files,findings,vtUsed\n")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                for (e in entries) {
                    val date = sdf.format(Date(e.timestamp))
                    w.write("$date,${e.mode},${e.files},${e.findings},${e.vtUsed}\n")
                }
            }
        }
    }

    private fun writeHistoryJson(file: File, entries: List<HistoryManager.Entry>) {
        val json = org.json.JSONArray()
        for (e in entries) {
            json.put(org.json.JSONObject().apply {
                put("timestamp", e.timestamp)
                put("mode", e.mode)
                put("files", e.files)
                put("findings", e.findings)
                put("vtUsed", e.vtUsed)
            })
        }
        FileOutputStream(file).use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeVaultCsv(file: File, creds: List<Credential>) {
        FileOutputStream(file).use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                w.write("id,title,url,package,username,notes,favorite,updatedAt\n")
                for (c in creds) {
                    w.write("${escapeCsv(c.id)},${escapeCsv(c.title)},${escapeCsv(c.url)},${escapeCsv(c.packageName)},${escapeCsv(c.username)},${escapeCsv(c.notes)},${c.favorite},${c.updatedAt}\n")
                }
            }
        }
    }

    private fun writeVaultJson(file: File, creds: List<Credential>, masterPassword: String) {
        // Exportar como blob vgx1: cifrado para poder importar después
        val plaintext = VaultStore.serializeCreds(creds)
        val blob = SecurityBridge.encrypt(masterPassword, plaintext) ?: return
        val json = org.json.JSONObject().apply {
            put("format", "vgx1")
            put("version", 1)
            put("blob", blob)
        }
        FileOutputStream(file).use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeApkReportCsv(file: File, report: ApkAnalyzer.AnalysisResult) {
        FileOutputStream(file).use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                w.write("field,value\n")
                w.write("packageName,${report.packageName}\n")
                w.write("versionName,${report.versionName}\n")
                w.write("versionCode,${report.versionCode}\n")
                w.write("riskScore,${report.riskScore}\n")
                w.write("permissionsCount,${report.permissions.size}\n")
                w.write("dangerousPermissionsCount,${report.dangerousPermissions.size}\n")
                for (p in report.permissions) {
                    w.write("permission,${escapeCsv(p)}\n")
                }
            }
        }
    }

    private fun writeApkReportJson(file: File, report: ApkAnalyzer.AnalysisResult) {
        FileOutputStream(file).use { out ->
            out.write(report.exportJson().toByteArray(Charsets.UTF_8))
        }
    }

    private fun createTempFile(context: Context, prefix: String, format: Format): File {
        val ext = when (format) { Format.CSV -> ".csv" else -> ".json" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(context.cacheDir, "$prefix$timestamp$ext")
    }

    private fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }
}