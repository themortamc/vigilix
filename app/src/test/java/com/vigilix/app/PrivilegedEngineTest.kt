package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class PrivilegedEngineTest {

    @Test
    fun testPackageValidation() {
        assertTrue(PrivilegedEngine.isValidPackage("com.vigilix.app"))
        assertTrue(PrivilegedEngine.isValidPackage("org.example.test_app"))
        assertFalse(PrivilegedEngine.isValidPackage("123invalid"))
        assertFalse(PrivilegedEngine.isValidPackage("com.example.app; rm -rf /"))
        assertFalse(PrivilegedEngine.isValidPackage("pkg with spaces"))
    }
}
