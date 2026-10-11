package com.vigilix.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scam_reports")
data class ScamReport(
    @PrimaryKey val id: String,
    val type: ScamType,
    val indicator: String,
    val description: String?,
    val timestamp: Long,
    val reportedByUser: Boolean,
    val status: ReportStatus
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("indicator", indicator)
        put("description", description ?: "")
        put("timestamp", timestamp)
        put("reportedByUser", reportedByUser)
        put("status", status.name)
    }

    companion object {
        fun fromJson(json: JSONObject): ScamReport = ScamReport(
            id = json.getString("id"),
            type = ScamType.valueOf(json.getString("type")),
            indicator = json.getString("indicator"),
            description = json.optString("description", null),
            timestamp = json.getLong("timestamp"),
            reportedByUser = json.getBoolean("reportedByUser"),
            status = ReportStatus.valueOf(json.getString("status"))
        )
    }
}

@Entity(tableName = "scam_numbers")
data class ScamNumber(
    @PrimaryKey val number: String
)

@Entity(tableName = "phishing_urls")
data class PhishingUrl(
    @PrimaryKey val url: String
)

@Entity(tableName = "malicious_apks")
data class MaliciousApk(
    @PrimaryKey val hash: String
)

@Entity(tableName = "c2_ips")
data class C2Ip(
    @PrimaryKey val ip: String
)