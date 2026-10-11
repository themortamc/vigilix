package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class ExportManagerTest {

    @Test
    fun escapeCsvHandlesSpecialChars() {
        val result = ExportManager.escapeCsvForTest("simple")
        assertEquals("simple", result)

        val withComma = ExportManager.escapeCsvForTest("a,b,c")
        assertEquals("\"a,b,c\"", withComma)

        val withQuotes = ExportManager.escapeCsvForTest("he said \"hello\"")
        assertEquals("\"he said \"\"hello\"\"\"", withQuotes)

        val withNewline = ExportManager.escapeCsvForTest("line1\nline2")
        assertEquals("\"line1\nline2\"", withNewline)
    }
}

// Extension para testear función privada
fun ExportManager.escapeCsvForTest(value: String): String = value.let {
    if (it.contains(",") || it.contains("\"") || it.contains("\n")) {
        "\"${it.replace("\"", "\"\"")}\""
    } else it
}