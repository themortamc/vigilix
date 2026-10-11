package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class HistoryManagerTest {

    @Test
    fun entryDataClass() {
        val entry = HistoryManager.Entry(
            timestamp = 1700000000000L,
            mode = "Completo",
            files = 1234,
            findings = 2,
            vtUsed = 10
        )
        assertEquals(1700000000000L, entry.timestamp)
        assertEquals("Completo", entry.mode)
        assertEquals(1234, entry.files)
        assertEquals(2, entry.findings)
        assertEquals(10, entry.vtUsed)
    }

    @Test
    fun toLineAndFromLineRoundtrip() {
        val entry = HistoryManager.Entry(
            timestamp = 1700000000000L,
            mode = "Rápido",
            files = 500,
            findings = 0,
            vtUsed = 0
        )
        val line = entry.toLine()
        assertEquals("1700000000000|Rápido|500|0|0", line)

        val parsed = HistoryManager.fromLine(line)
        assertNotNull(parsed)
        assertEquals(entry.timestamp, parsed!!.timestamp)
        assertEquals(entry.mode, parsed.mode)
        assertEquals(entry.files, parsed.files)
        assertEquals(entry.findings, parsed.findings)
        assertEquals(entry.vtUsed, parsed.vtUsed)
    }

    @Test
    fun fromLineReturnsNullOnInvalidFormat() {
        assertNull(HistoryManager.fromLine("invalid"))
        assertNull(HistoryManager.fromLine("1|2|3|4")) // falta un campo
        assertNull(HistoryManager.fromLine("abc|mode|10|0|0")) // timestamp no es long
    }

    @Test
    fun maxEntriesConstant() {
        assertEquals(10, HistoryManager.MAX_ENTRIES)
    }
}