package com.vigilix.app

import android.net.Uri
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Una contraseña guardada: de dónde es (sitio y/o app), usuario y clave. */
data class Credential(
    val id: String,
    val title: String,
    val url: String,
    val packageName: String,
    val username: String,
    val password: String,
    val notes: String,
    val favorite: Boolean,
    val updatedAt: Long,
) {
    /** Dominio normalizado ("www.gmail.com" -> "gmail.com") o null si no hay sitio válido. */
    val host: String? get() = hostOf(url)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("url", url)
        .put("pkg", packageName)
        .put("user", username)
        .put("pass", password)
        .put("notes", notes)
        .put("fav", favorite)
        .put("ts", updatedAt)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Credential = Credential(
            id = o.optString("id").ifEmpty { newId() },
            title = o.optString("title"),
            url = o.optString("url"),
            packageName = o.optString("pkg"),
            username = o.optString("user"),
            password = o.optString("pass"),
            notes = o.optString("notes"),
            favorite = o.optBoolean("fav", false),
            updatedAt = o.optLong("ts", 0L),
        )

        private val HOST_REGEX = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

        fun hostOf(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val withScheme = if (text.contains("://")) text else "https://$text"
            val host = Uri.parse(withScheme).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
            return if (HOST_REGEX.matches(host)) host else null
        }

        /**
         * Coincidencia ESTRICTA de dominio: igual, o subdominio del guardado
         * (accounts.google.com sirve para google.com). Nunca por "contiene":
         * "google.com.evil.com" y "evilgoogle.com" NO coinciden.
         */
        fun domainMatches(entryHost: String, requestDomain: String): Boolean {
            val d = requestDomain.trim().lowercase(Locale.ROOT).removePrefix("www.")
            return d == entryHost || d.endsWith(".$entryHost")
        }
    }
}
