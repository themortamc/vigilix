package com.vigilix.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gestor de detección de estafas colaborativa.
 * BD local (Room) + sync con GitHub Pages + reporte usuario.
 *
 * El reporte del usuario se guarda localmente (Room + backup en archivos).
 * El envío al repo comunitario lo hace un workflow bot con sus propios secrets;
 * la app NUNCA sube datos del usuario a red sin consentimiento visible.
 */
class ScamDetectionManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val db = ThreatIntelDatabase.getInstance(context)
    private val threatIntel = ThreatIntelManager.getInstance(context)

    private val _reportStatus = MutableLiveData<ReportStatus>()
    val reportStatus: LiveData<ReportStatus> = _reportStatus

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

    /** Reporta un número/URL por el usuario. Guarda localmente para el siguiente sync. */
    suspend fun reportScam(type: ScamType, indicator: String, description: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = when (type) {
            ScamType.CALL, ScamType.SMS -> normalizePhone(indicator)
            ScamType.URL -> normalizeUrl(indicator)
        }

        val report = ScamReport(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            indicator = normalized,
            description = description,
            timestamp = System.currentTimeMillis(),
            reportedByUser = true,
            status = ReportStatus.PENDING
        )

        // Guardar en BD local (Room) + backup en archivos (ThreatIntelManager)
        db.scamReportDao().insert(report)
        val backupResult = threatIntel.reportScam(type, normalized, description)
        _reportStatus.postValue(ReportStatus.PENDING)

        backupResult.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    /** Descarga la threat intel comunitaria desde GitHub Pages. */
    suspend fun syncThreatIntel(): Result<Unit> {
        return threatIntel.sync().fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    /** Reportes pendientes de sincronizar (para el workflow bot). */
    suspend fun pendingReports(limit: Int = 50): List<ScamReport> =
        withContext(Dispatchers.IO) { db.scamReportDao().getByStatus(ReportStatus.PENDING.name).take(limit) }

    private fun normalizePhone(number: String): String = number.replace(Regex("[^0-9+]"), "")
    private fun normalizeUrl(url: String): String = Uri.parse(url).host?.lowercase() ?: url.lowercase()

    companion object {
        @Volatile private var INSTANCE: ScamDetectionManager? = null

        fun getInstance(context: Context): ScamDetectionManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScamDetectionManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}
