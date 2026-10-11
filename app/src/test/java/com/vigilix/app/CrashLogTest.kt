package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class CrashLogTest {

    @Test
    fun crashReportDataClass() {
        val report = CrashLog.CrashReport(
            id = "test-123",
            timestamp = 1700000000000L,
            thread = "main",
            exceptionClass = "java.lang.NullPointerException",
            message = "Test message",
            stackTrace = "at Test.test(Test.java:1)",
            deviceInfo = "model=Pixel; api=34",
            userAction = "button_click"
        )
        assertEquals("test-123", report.id)
        assertEquals(1700000000000L, report.timestamp)
        assertEquals("main", report.thread)
        assertEquals("java.lang.NullPointerException", report.exceptionClass)
        assertEquals("Test message", report.message)
        assertEquals("at Test.test(Test.java:1)", report.stackTrace)
        assertEquals("model=Pixel; api=34", report.deviceInfo)
        assertEquals("button_click", report.userAction)
    }

    @Test
    fun crashReportWithoutUserAction() {
        val report = CrashLog.CrashReport(
            id = "test-123",
            timestamp = 1700000000000L,
            thread = "main",
            exceptionClass = "java.lang.NullPointerException",
            message = "Test message",
            stackTrace = "at Test.test(Test.java:1)",
            deviceInfo = "model=Pixel; api=34",
            userAction = null
        )
        assertNull(report.userAction)
    }
}