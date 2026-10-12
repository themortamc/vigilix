package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class HashDbTest {

    @Test
    fun eicarSha256Constant() {
        assertEquals(
            "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f",
            HashDb.EICAR_SHA256
        )
    }

    @Test
    fun maxEntriesConstant() {
        assertEquals(2_000_000, HashDb.MAX_ENTRIES)
    }

    @Test
    fun maxBytesConstant() {
        assertEquals(300L * 1024 * 1024, HashDb.MAX_BYTES)
    }

    @Test
    fun lookupReturnsEicarForTestFile() {
        val result = HashDb.lookupEicarOnly(HashDb.EICAR_SHA256)
        assertEquals("EICAR (archivo de prueba)", result)
        assertNull(HashDb.lookupEicarOnly("invalid"))
    }
}