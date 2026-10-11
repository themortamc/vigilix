package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class PermissionMonitorTest {

    @Test
    fun sensitivePermissionsListIsNotEmpty() {
        val sensitive = PermissionMonitor.SENSITIVE
        assertTrue(sensitive.isNotEmpty())
        assertTrue(sensitive.any { it.first.contains("LOCATION") })
        assertTrue(sensitive.any { it.first.contains("CAMERA") })
        assertTrue(sensitive.any { it.first.contains("RECORD_AUDIO") })
        assertTrue(sensitive.any { it.first.contains("READ_CONTACTS") })
        assertTrue(sensitive.any { it.first.contains("READ_SMS") })
    }

    @Test
    fun sensitivePermissionsHaveReadableNames() {
        val sensitive = PermissionMonitor.SENSITIVE
        val names = sensitive.map { it.second }
        assertTrue(names.contains("Ubicación"))
        assertTrue(names.contains("Micrófono"))
        assertTrue(names.contains("Cámara"))
        assertTrue(names.contains("Contactos"))
        assertTrue(names.contains("SMS"))
    }

    @Test
    fun sensitiveAppDataClass() {
        val app = PermissionMonitor.SensitiveApp(
            packageName = "com.example.app",
            label = "Example App",
            granted = listOf("Ubicación", "Cámara")
        )
        assertEquals("com.example.app", app.packageName)
        assertEquals("Example App", app.label)
        assertEquals(2, app.granted.size)
    }
}