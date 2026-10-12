package com.vigilix.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gestor de Threat Intelligence. Descarga y mantiene BD local de:
 * - Números de estafa (scam-numbers.json)
 * - URLs de phishing (phishing-urls.json)
 * - Hashes de APKs maliciosos (malicious-apks.json)
 * - IPs de C2 (c2-ips.json)
 * Fuente: GitHub Pages (vigilix-threat-intel repo, branch gh-pages)
 */
class ThreatIntelManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://vigilix.github.io/vigilix-threat-intel/"
    private val filesDir = File(context.filesDir, "threat_intel")
    private val _lastSync = MutableLiveData<Long>()
    val lastSync: LiveData<Long> = _lastSync

    init {
        filesDir.mkdirs()
        loadLastSync()
    }

    /** Descarga y actualiza toda la threat intel (llamar en WiFi + cargando). */
    suspend fun sync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        val manifest = fetchManifest() ?: return@withContext Result.failure(IOException("Manifest fetch failed"))
        var totalAdded = 0L

        for (file in manifest.files) {
            val result = downloadAndSave(file.name, file.sha256)
            if (result.isSuccess) totalAdded += result.getOrNull() ?: 0L
        }

        val now = System.currentTimeMillis()
        saveLastSync(now)
        _lastSync.postValue(now)
        Result.success(SyncResult(manifest.version, totalAdded))
    }

    /** Verifica si un número es scam conocido. */
    fun isScamNumber(number: String): Boolean {
        val normalized = normalizePhone(number)
        return scamNumbers.contains(normalized)
    }

    /** Verifica si una URL es phishing conocida. */
    fun isPhishingUrl(url: String): Boolean {
        val normalized = normalizeUrl(url)
        return phishingUrls.any { normalized.contains(it) }
    }

    /** Verifica si un hash APK es malicioso conocido. */
    fun isMaliciousApk(hash: String): Boolean = maliciousApks.contains(hash.lowercase())

    /** Verifica si una IP es C2 conocida. */
    fun isC2Ip(ip: String): Boolean = c2Ips.contains(ip)

    /** Reporta un número/URL por el usuario → crea PR en GitHub (via bot). */
    suspend fun reportScam(type: ScamType, indicator: String, description: String?): Result<Unit> = withContext(Dispatchers.IO) {
        // TODO: Implementar envío a GitHub via API (bot con PAT)
        // Por ahora guardar localmente
        val report = ScamReport(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            indicator = indicator,
            description = description,
            timestamp = System.currentTimeMillis(),
            reportedByUser = true,
            status = ReportStatus.PENDING
        )
        saveLocalReport(report)
        Result.success(Unit)
    }

    // ===== Private implementation =====

    data class SyncResult(val version: Int, val totalAdded: Long)
    private data class Manifest(val version: Int, val files: List<ManifestFile>, val updated: String)
    private data class ManifestFile(val name: String, val sha256: String)
    private var scamNumbers = mutableSetOf<String>()
    private var phishingUrls = mutableSetOf<String>()
    private var maliciousApks = mutableSetOf<String>()
    private var c2Ips = mutableSetOf<String>()

    private fun loadLastSync() {
        val file = File(filesDir, "last_sync.txt")
        if (file.exists()) {
            _lastSync.value = file.readText().toLongOrNull()
        }
    }

    private fun saveLastSync(time: Long) {
        File(filesDir, "last_sync.txt").writeText(time.toString())
    }

    private fun fetchManifest(): Manifest? {
        return try {
            val request = Request.Builder().url("$baseUrl/manifest.json").build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) null
                else {
                    val json = JSONObject(response.body?.string())
                    val filesArray = json.getJSONArray("files")
                    val files = mutableListOf<ManifestFile>()
                    for (i in 0 until filesArray.length()) {
                        val obj = filesArray.getJSONObject(i)
                        files.add(ManifestFile(obj.getString("name"), obj.getString("sha256")))
                    }
                    Manifest(
                        version = json.getInt("version"),
                        files = files,
                        updated = json.getString("updated")
                    )
                }
            } finally {
                response.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAndSave(fileName: String, expectedSha256: String): Result<Long> {
        return try {
            val request = Request.Builder().url("$baseUrl/threat-intel/$fileName").build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) Result.failure(IOException("HTTP ${response.code}"))
                else {
                    val body = response.body?.bytes() ?: Result.failure(IOException("Empty body"))
                    
                    // Verify SHA-256
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
                    val actualSha256 = body?.let { bytes ->
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    } ?: ""
                    if (actualSha256 != expectedSha256) {
                        Result.failure(SecurityException("SHA-256 mismatch for $fileName"))
                    } else {
                        val file = File(filesDir, fileName)
                        FileOutputStream(file).use { it.write(body) }
                        
                        // Parse and add to sets
                        val added = parseAndAdd(file)
                        Result.success(added.toLong())
                    }
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAndAdd(file: File): Int {
        return when (file.name) {
            "scam-numbers.json" -> parseJsonArray(file).map { normalizePhone(it) }.filter { it.isNotBlank() }.also { scamNumbers.addAll(it) }.size
            "phishing-urls.json" -> parseJsonArray(file).map { normalizeUrl(it) }.filter { it.isNotBlank() }.also { phishingUrls.addAll(it) }.size
            "malicious-apks.json" -> parseJsonArray(file).map { it.lowercase() }.also { maliciousApks.addAll(it) }.size
            "c2-ips.json" -> parseJsonArray(file).also { c2Ips.addAll(it) }.size
            else -> 0
        }
    }

    private fun parseJsonArray(file: File): List<String> {
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("data")
            val result = mutableListOf<String>()
            for (i in 0 until array.length()) {
                result.add(array.getString(i))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizePhone(number: String): String = number.replace(Regex("[^0-9+]"), "")
    private fun normalizeUrl(url: String): String = Uri.parse(url).host?.lowercase() ?: url.lowercase()

    private fun saveLocalReport(report: ScamReport) {
        val file = File(filesDir, "local_reports.json")
        val reports = mutableListOf<ScamReport>()
        if (file.exists()) {
            try {
                val json = JSONObject(file.readText())
                val array = json.getJSONArray("reports")
                for (i in 0 until array.length()) {
                    reports.add(ScamReport.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) { /* ignore */ }
        }
        reports.add(0, ScamReport(
            id = java.util.UUID.randomUUID().toString(),
            type = report.type,
            indicator = report.indicator,
            description = report.description,
            timestamp = System.currentTimeMillis(),
            reportedByUser = true,
            status = ReportStatus.PENDING
        ))
        val json = JSONObject().put("reports", JSONArray(reports.map { it.toJson() }))
        file.writeText(json.toString())
    }

    companion object {
        @Volatile private var INSTANCE: ThreatIntelManager? = null

        fun getInstance(context: Context): ThreatIntelManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ThreatIntelManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}

// Fin de ThreatIntelManager