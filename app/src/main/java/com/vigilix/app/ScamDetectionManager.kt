package com.vigilix.app

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gestor de detección de estafas colaborativa.
 * BD local (Room) + sync con GitHub Pages + reporte usuario.
 */
class ScamDetectionManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val db = ThreatIntelDatabase.getInstance(context)
    private val threatIntel = ThreatIntelManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _reportStatus = MutableLiveData<ReportStatus>()
    val reportStatus: LiveData<ReportStatus> = _reportStatus

    private val githubRepo = "vigilix-community/scam-reports"
    private val githubApi = "https://api.github.com/repos/$githubRepo"

    /** Verifica si un número es scam conocido (local + threat intel). */
    fun isScamNumber(number: String): Boolean {
        val normalized = normalizePhone(number)
        return threatIntel.isScamNumber(normalized) || db.scamNumberDao().exists(normalized)
    }

    /** Verifica si una URL es phishing conocida. */
    fun isPhishingUrl(url: String): Boolean {
        val normalized = normalizeUrl(url)
        return threatIntel.isPhishingUrl(normalized) || db.phishingUrlDao().exists(normalized)
    }

    /** Reporta un número/URL por el usuario. Crea PR en GitHub. */
    suspend fun reportScam(type: ScamType, indicator: String, description: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = when (type) {
            ScamType.CALL, ScamType.SMS -> normalizePhone(indicator)
            ScamType.URL -> normalizeUrl(indicator)
        }

        // Guardar reporte local
        val report = ScamReport(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            indicator = normalized,
            description = description,
            timestamp = System.currentTimeMillis(),
            reportedByUser = true,
            status = ReportStatus.PENDING
        )
        db.scamReportDao().insert(report)

        // Intentar crear PR en GitHub (best effort)
        try {
            createGitHubPr(type, normalized, description)
        } catch (e: Exception) {
            // Log pero no fallar - el reporte local se guardó
        }

        _reportStatus.postValue(ReportStatus.PENDING)
        Result.success(Unit)
    }

    /** Sync con threat intel (GitHub Pages). */
    suspend fun syncThreatIntel(): Result<ThreatIntelManager.SyncResult> {
        return threatIntel.sync()
    }

    // DAO accessors
    private val db by lazy { ThreatIntelDatabase.getInstance(context) }

    private fun normalizePhone(number: String): String = number.replace(Regex("[^0-9+]"), "")
    private fun normalizeUrl(url: String): String = Uri.parse(url).host?.lowercase() ?: url.lowercase()

    /** Crea PR en GitHub via API (requiere PAT en secrets). */
    private suspend fun createGitHubPr(type: ScamType, indicator: String, description: String?): Result<Unit> {
        val token = System.getenv("GITHUB_PAT") ?: return Result.failure(Exception("No PAT"))
        val githubPat = token

        val branch = "scam-report-${System.currentTimeMillis()}"
        val filePath = "data/${type.name.toLowerCase()}/${System.currentTimeMillis()}.json"
        val content = JSONObject().apply {
            put("type", type.name)
            put("indicator", indicator)
            put("description", description ?: "")
            put("timestamp", System.currentTimeMillis())
            put("reported_by", "user")
        }.toString()

        // 1. Create branch
        val createBranchBody = JSONObject().apply {
            put("ref", "refs/heads/$branch")
            put("sha", getMainBranchSha())
        }.toString()

        val createBranchReq = Request.Builder()
            .url("$githubApi/git/refs")
            .header("Authorization", "token $githubPat")
            .header("Accept", "application/vnd.github.v3+json")
            .post(toRequestBody(createBranchBody))
            .build()

        client.newCall(createBranchReq).execute().use { response ->
            if (!response.isSuccessful) return Result.failure(Exception("Create branch failed: ${response.code}"))
        }

        // 2. Create file
        val encodedContent = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val createFileBody = JSONObject().apply {
            put("message", "Add scam report: $indicator")
            put("content", encodedContent)
            put("branch", branch)
        }.toString()

        val createFileReq = Request.Builder()
            .url("$githubApi/contents/$filePath")
            .header("Authorization", "token $githubPat")
            .header("Accept", "application/vnd.github.v3+json")
            .put(toRequestBody(createFileBody))
            .build()

        client.newCall(createFileReq).execute().use { response ->
            if (!response.isSuccessful) return Result.failure(Exception("Create file failed: ${response.code}"))
        }

        // 3. Create PR
        val prBody = JSONObject().apply {
            put("title", "Add scam report: $indicator")
            put("head", branch)
            put("base", "main")
            put("body", "Auto-generated scam report from user.\n\nType: ${type.name}\nIndicator: $indicator\nDescription: ${description ?: "N/A"}")
        }.toString()

        val prReq = Request.Builder()
            .url("$githubApi/pulls")
            .header("Authorization", "token $githubPat")
            .header("Accept", "application/vnd.github.v3+json")
            .post(toRequestBody(prBody))
            .build()

        client.newCall(prReq).execute().use { response ->
            if (!response.isSuccessful) return Result.failure(Exception("Create PR failed: ${response.code}"))
            Result.success(Unit)
        }
    }

    private fun getMainBranchSha(): String = "main" // Simplified - in production, fetch from API

    private val githubApi = "https://api.github.com/repos/vigilix-community/scam-reports"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        @Volatile private var INSTANCE: ScamDetectionManager? = null

        fun getInstance(context: Context): ScamDetectionManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScamDetectionManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}