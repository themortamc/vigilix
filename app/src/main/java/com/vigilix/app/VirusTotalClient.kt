package com.vigilix.app

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface VtResult {
    data class Report(val malicious: Int, val suspicious: Int, val harmless: Int, val undetected: Int) : VtResult {
        val total: Int get() = malicious + suspicious + harmless + undetected
    }

    /** VirusTotal nunca vio ese archivo. No significa que sea seguro ni peligroso. */
    object NotFound : VtResult
    object RateLimited : VtResult
    object InvalidKey : VtResult
    data class Error(val message: String) : VtResult
}

/**
 * Consulta por HASH a la API pública y gratuita de VirusTotal (4 por minuto, 500 por día).
 * Nunca se sube el archivo: solo viaja su SHA-256. Cada usuario usa SU propia API key.
 *
 * Aviso de VirusTotal: la API pública no puede usarse en productos o servicios comerciales.
 */
object VirusTotalClient {
    const val MIN_INTERVAL_MS = 15_500L
    const val DAILY_SAFE_LIMIT = 480
    private const val MAX_BODY_BYTES = 3 * 1024 * 1024
    private val HASH_REGEX = Regex("^[0-9a-f]{64}$")

    /** Bloqueante: llamar desde un hilo de fondo. */
    fun lookup(apiKey: String, sha256: String): VtResult {
        if (!HASH_REGEX.matches(sha256)) return VtResult.Error("hash inválido")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://www.virustotal.com/api/v3/files/$sha256").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("x-apikey", apiKey)
            connection.setRequestProperty("accept", "application/json")
            when (val code = connection.responseCode) {
                200 -> parse(readLimited(connection))
                404 -> VtResult.NotFound
                429 -> VtResult.RateLimited
                401, 403 -> VtResult.InvalidKey
                else -> VtResult.Error("HTTP $code")
            }
        } catch (e: IOException) {
            VtResult.Error("sin conexión")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(connection: HttpURLConnection): String {
        connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw IOException("respuesta demasiado grande")
            }
            return out.toString("UTF-8")
        }
    }

    private fun parse(body: String): VtResult = try {
        val stats = JSONObject(body)
            .getJSONObject("data")
            .getJSONObject("attributes")
            .getJSONObject("last_analysis_stats")
        VtResult.Report(
            malicious = stats.optInt("malicious"),
            suspicious = stats.optInt("suspicious"),
            harmless = stats.optInt("harmless"),
            undetected = stats.optInt("undetected"),
        )
    } catch (e: JSONException) {
        VtResult.Error("respuesta inesperada")
    }
}

/** Guarda los resultados ya consultados para no gastar el cupo gratuito en archivos repetidos. */
object VtCache {
    private const val FILE_NAME = "vt_cache.json"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val MAX_ENTRIES = 5000

    private var loaded = false
    private val entries = LinkedHashMap<String, Pair<Long, VtResult.Report>>()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(context)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                entries[key] = Pair(
                    o.getLong("t"),
                    VtResult.Report(o.getInt("m"), o.getInt("s"), o.getInt("h"), o.getInt("u")),
                )
            }
        } catch (e: IOException) {
            entries.clear()
        } catch (e: JSONException) {
            entries.clear()
        }
    }

    @Synchronized
    fun get(context: Context, sha256: String): VtResult.Report? {
        ensureLoaded(context)
        val hit = entries[sha256] ?: return null
        return if (System.currentTimeMillis() - hit.first <= MAX_AGE_MS) hit.second else null
    }

    @Synchronized
    fun put(context: Context, sha256: String, report: VtResult.Report) {
        ensureLoaded(context)
        entries.remove(sha256)
        entries[sha256] = Pair(System.currentTimeMillis(), report)
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    fun flush(context: Context) {
        ensureLoaded(context)
        try {
            val root = JSONObject()
            for ((hash, value) in entries) {
                val r = value.second
                root.put(
                    hash,
                    JSONObject().put("t", value.first).put("m", r.malicious).put("s", r.suspicious)
                        .put("h", r.harmless).put("u", r.undetected),
                )
            }
            file(context).writeText(root.toString())
        } catch (e: IOException) {
            // El caché es opcional.
        }
    }
}
