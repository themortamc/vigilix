package com.vigilix.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.AssetManager
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import kotlinx.coroutines.Dispatchers

/**
 * Analizador estático de APKs. Extrae información del manifest,
 * certificados, permisos y calcula un risk score 0-100.
 */
class ApkAnalyzer private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val pm: PackageManager = context.packageManager

    /** Analiza un APK por ruta de archivo. */
    suspend fun analyzeFile(apkPath: String): Result<AnalysisResult> = withContext(Dispatchers.IO) {
        try {
            val jarFile = JarFile(apkPath)
            val manifest = jarFile.getManifest()
            val entries = jarFile.entries()

            // Parse manifest
            val manifestParser = ManifestParser(manifest)
            val packageName = manifestParser.packageName
            val versionName = manifestParser.versionName
            val versionCode = manifestParser.versionCode
            val permissions = manifestParser.permissions
            val activities = manifestParser.activities
            val services = manifestParser.services
            val receivers = manifestParser.receivers
            val providers = manifestParser.providers

            // Certificates
            val certificates = extractCertificates(jarFile)

            // Risk analysis
            val riskFactors = analyzeRiskFactors(permissions, certificates)
            val riskScore = calculateRiskScore(riskFactors)

            Result.success(AnalysisResult(
                packageName = packageName ?: "unknown",
                versionName = versionName ?: "unknown",
                versionCode = versionCode ?: 0,
                permissions = permissions,
                dangerousPermissions = permissions.filter { isDangerous(it) },
                riskScore = riskScore,
                riskFactors = riskFactors,
                manifestXml = manifest?.mainAttributes?.toString() ?: "",
                certificates = certificates,
                activities = activities,
                services = services,
                receivers = receivers,
                providers = providers
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Analiza una app instalada por package name. */
    suspend fun analyzeInstalled(packageName: String): Result<AnalysisResult> = withContext(Dispatchers.IO) {
        try {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
            val manifestParser = ManifestParser(null) // Parse from PackageInfo
            val permissions = info.requestedPermissions?.toList() ?: emptyList()
            val signatures = info.signatures ?: arrayOf()
            val certificates = signatures.map { extractCertInfo(it) }

            val riskFactors = analyzeRiskFactors(permissions, certificates)
            val riskScore = calculateRiskScore(riskFactors)

            Result.success(AnalysisResult(
                packageName = packageName,
                versionName = info.versionName ?: "unknown",
                versionCode = info.versionCode,
                permissions = permissions,
                dangerousPermissions = permissions.filter { isDangerous(it) },
                riskScore = riskScore,
                riskFactors = riskFactors,
                manifestXml = "",
                certificates = certificates,
                activities = info.activities?.map { it.name } ?: emptyList(),
                services = info.services?.map { it.name } ?: emptyList(),
                receivers = info.receivers?.map { it.name } ?: emptyList(),
                providers = info.providers?.map { it.name } ?: emptyList()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Risk Analysis =====

    private fun analyzeRiskFactors(permissions: List<String>, certificates: List<CertInfo>): List<RiskFactor> {
        val factors = mutableListOf<RiskFactor>()

        // Permission-based risks
        val permSet = permissions.toSet()
        if ("android.permission.REQUEST_INSTALL_PACKAGES" in permSet) factors.add(RiskFactor.InstallPackages)
        if ("android.permission.REQUEST_DELETE_PACKAGES" in permSet) factors.add(RiskFactor.DeletePackages)
        if ("android.permission.SYSTEM_ALERT_WINDOW" in permSet) factors.add(RiskFactor.SystemAlertWindow)
        if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in permSet) factors.add(RiskFactor.BindAccessibility)
        if ("android.permission.READ_SMS" in permSet) factors.add(RiskFactor.ReadSms)
        if ("android.permission.SEND_SMS" in permSet) factors.add(RiskFactor.SendSms)
        if ("android.permission.READ_CONTACTS" in permSet) factors.add(RiskFactor.ReadContacts)
        if ("android.permission.BIND_DEVICE_ADMIN" in permSet) factors.add(RiskFactor.DeviceAdmin)
        if ("android.permission.INSTALL_UNKNOWN_APPS" in permSet) factors.add(RiskFactor.UnknownSources)

        // Certificate risks
        for (cert in certificates) {
            if (cert.isSelfSigned) factors.add(RiskFactor.Custom("Certificado autofirmado", 15, "El certificado está autofirmado"))
            if (cert.isExpired) factors.add(RiskFactor.Custom("Certificado expirado", 10, "El certificado ha expirado"))
            if (cert.weakAlgorithm) factors.add(RiskFactor.Custom("Algoritmo débil", 20, "Usa algoritmo de firma débil (MD5/SHA1)"))
        }

        // Combo multipliers handled in score calculation
        return factors
    }

    private fun calculateRiskScore(factors: List<RiskFactor>): Int {
        var score = 0
        for (factor in factors) {
            score += factor.weight
        }

        // Combo multipliers
        val hasInstall = factors.any { it == RiskFactor.InstallPackages }
        val hasDelete = factors.any { it == RiskFactor.DeletePackages }
        val hasOverlay = factors.any { it == RiskFactor.SystemAlertWindow }
        val hasAccessibility = factors.any { it == RiskFactor.BindAccessibility }

        if (hasInstall && hasDelete) score = (score * 1.5).toInt()
        if (hasOverlay && factors.any { it == RiskFactor.BindAccessibility }) score = (score * 1.5).toInt()

        return score.coerceAtMost(100)
    }

    // ===== Manifest Parsing =====

    private class ManifestParser(private val manifest: java.util.jar.Manifest?) {
        var packageName: String? = null
        var versionName: String? = null
        var versionCode: Int? = null
        var permissions = mutableListOf<String>()
        var activities = mutableListOf<String>()
        var services = mutableListOf<String>()
        var receivers = mutableListOf<String>()
        var providers = mutableListOf<String>()

        init {
            if (manifest != null) {
                val mainAttrs = manifest.mainAttributes
                packageName = mainAttrs.getValue("Package")
                versionName = mainAttrs.getValue("Version-Name")
                versionCode = mainAttrs.getValue("Version-Code")?.toIntOrNull()
            }
        }
    }

    private fun extractCertificates(jarFile: JarFile): List<CertInfo> {
        val certs = mutableListOf<CertInfo>()
        val entries = jarFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC")) {
                try {
                    val certs = jarFile.getInputStream(entry).use { input ->
                        CertificateFactory.getInstance("X.509").generateCertificates(input) as List<X509Certificate>
                    }
                    for (cert in certs) {
                        certs.add(extractCertInfo(cert))
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
        return certs.distinctBy { it.sha256 }
    }

    private fun extractCertInfo(cert: X509Certificate): CertInfo {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        return CertInfo(
            subjectDN = cert.subjectDN.name,
            issuerDN = cert.issuerDN.name,
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
            sha256 = sha256,
            isSelfSigned = cert.subjectDN.equals(cert.issuerDN),
            isExpired = cert.notAfter.before(java.util.Date()),
            weakAlgorithm = cert.sigAlgName.contains("MD5", ignoreCase = true) || cert.sigAlgName.contains("SHA1", ignoreCase = true),
            sigAlgName = cert.sigAlgName
        )
    }

    private fun extractCertInfo(signature: Signature): CertInfo {
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(ByteArrayInputStream(signature.toByteArray())) as X509Certificate
        return extractCertInfo(cert)
    }

    private fun isDangerous(permission: String): Boolean = when (permission) {
        "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.REQUEST_DELETE_PACKAGES",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN" -> true
        else -> false
    }

    companion object {
        @Volatile private var INSTANCE: ApkAnalyzer? = null

        fun getInstance(context: Context): ApkAnalyzer = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ApkAnalyzer(context.applicationContext).also { INSTANCE = it }
        }
    }
}

// ===== Data Classes =====

data class AnalysisResult(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val permissions: List<String>,
    val dangerousPermissions: List<String>,
    val riskScore: Int,
    val riskFactors: List<RiskFactor>,
    val manifestXml: String,
    val certificates: List<CertInfo>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>
) {
    fun exportHtmlReport(): String {
        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Análisis APK: $packageName</title>
<style>body{font-family:sans-serif;margin:20px}h1{color:#333}.risk{font-size:24px;font-weight:bold}.factor{margin:8px 0;padding:8px;background:#f5f5f5;border-radius:4px}.high{background:#ffe0e0}.medium{background:#fff3cd}.low{background:#d4edda}</style></head>
<body>
<h1>Análisis de APK: $packageName</h1>
<p><b>Versión:</b> $versionName (code $versionCode)</p>
<div class="risk">Risk Score: <b>$riskScore/100</b></div>
<h2>Factores de riesgo</h2>
${riskFactors.map { "<div class='factor ${when(it.weight){in 25..30->\"high\" in 15..20->\"medium\" else->\"low\"}}'><b>${it.description}</b> (${it.weight} pts)</div>".trimIndent() }.joinToString("")}
<h2>Permisos (${permissions.size})</h2>
<ul>${permissions.map { "<li>$it${if (it in dangerousPermissions) \" <b style='color:red'>(Peligroso)</b>\"" else "\""}</li>" }.joinToString("")}</ul>
<h2>Certificados (${certificates.size})</h2>
<ul>${certificates.map { "<li>${it.subjectDN} (${if (it.isSelfSigned) \"Autofirmado\" else it.issuerDN}) ${if (it.isExpired) \"EXPIRADO\" else \"Válido hasta \" + java.text.SimpleDateFormat(\"dd/MM/yyyy\").format(java.util.Date(it.notAfter))} ${if (it.weakAlgorithm) \"<b style='color:red'>(Algoritmo débil)</b>\" else \"\"}</li>" }.joinToString("")}</ul>
</body></html>
""".trimIndent()
    }

    fun exportJson(): String = kotlinx.serialization.json.Json.encodeToString(this)
}

sealed class RiskFactor(val weight: Int, val description: String) {
    object InstallPackages : RiskFactor(25, "REQUEST_INSTALL_PACKAGES - Puede instalar otras apps")
    object DeletePackages : RiskFactor(25, "REQUEST_DELETE_PACKAGES - Puede desinstalar apps")
    object SystemAlertWindow : RiskFactor(20, "SYSTEM_ALERT_WINDOW - Dibuja sobre otras apps")
    object BindAccessibility : RiskFactor(20, "BIND_ACCESSIBILITY_SERVICE - Accesibilidad (keylogger risk)")
    object ReadSms : RiskFactor(15, "READ_SMS - Lee SMS")
    object SendSms : RiskFactor(25, "SEND_SMS - Envía SMS (costo/estafa)")
    object ReadContacts : RiskFactor(15, "READ_CONTACTS - Lee contactos")
    object DeviceAdmin : RiskFactor(30, "BIND_DEVICE_ADMIN - Admin de dispositivo (control total)")
    object UnknownSources : RiskFactor(10, "INSTALL_UNKNOWN_APPS - Orígenes desconocidos")
    data class Custom(val name: String, val weight: Int, val desc: String) : RiskFactor(weight, "$name: $desc")
}

data class CertInfo(
    val subjectDN: String,
    val issuerDN: String,
    val notBefore: Long,
    val notAfter: Long,
    val sha256: String,
    val isSelfSigned: Boolean,
    val isExpired: Boolean,
    val weakAlgorithm: Boolean,
    val sigAlgName: String
)