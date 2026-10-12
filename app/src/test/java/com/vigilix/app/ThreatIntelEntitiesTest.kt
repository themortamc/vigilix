package com.vigilix.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreatIntelEntitiesTest {

    @Test
    fun scamReportSerialization() {
        val report = ScamReport(
            id = "test-123",
            type = ScamType.CALL,
            indicator = "+5491112345678",
            description = "Intento de estafa bancaria",
            timestamp = 1700000000000L,
            reportedByUser = true,
            status = ReportStatus.PENDING
        )

        val json = report.toJson()
        assertEquals("test-123", json.getString("id"))
        assertEquals("CALL", json.getString("type"))
        assertEquals("+5491112345678", json.getString("indicator"))
        assertTrue(json.getBoolean("reportedByUser"))
        assertEquals("PENDING", json.getString("status"))

        val deserialized = ScamReport.fromJson(json)
        assertEquals(report.id, deserialized.id)
        assertEquals(report.type, deserialized.type)
        assertEquals(report.indicator, deserialized.indicator)
        assertEquals(report.description, deserialized.description)
        assertEquals(report.timestamp, deserialized.timestamp)
        assertEquals(report.reportedByUser, deserialized.reportedByUser)
        assertEquals(report.status, deserialized.status)
    }

    @Test
    fun entityInstancesCreatedCorrectly() {
        val num = ScamNumber("+5491199998888")
        assertEquals("+5491199998888", num.number)

        val url = PhishingUrl("https://fake-login-bank.com")
        assertEquals("https://fake-login-bank.com", url.url)

        val apk = MaliciousApk("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertEquals(64, apk.hash.length)

        val c2 = C2Ip("192.168.1.100")
        assertEquals("192.168.1.100", c2.ip)
    }
}
