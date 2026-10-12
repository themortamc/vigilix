package com.vigilix.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultStoreLogicTest {

    @Test
    fun isDuressPinDetectsPrefix() {
        assertTrue(VaultStore.isDuressPin("DURESS_1234"))
        assertTrue(VaultStore.isDuressPin("DURESS_mypin"))
        assertFalse(VaultStore.isDuressPin("1234"))
        assertFalse(VaultStore.isDuressPin("DURESS"))
        assertFalse(VaultStore.isDuressPin("DURESSS_1234"))
    }

    @Test
    fun generateDuressPinHasCorrectFormat() {
        val realPin = "123456"
        val duressPin = VaultStore.generateDuressPin(realPin)
        assertTrue(duressPin.startsWith("DURESS_"))
        assertTrue(duressPin.endsWith(realPin))
    }

    @Test
    fun generateFakeVaultReturnsValidJson() {
        val fakeVault = VaultStore.generateFakeVault()
        val json = JSONObject(fakeVault)
        assertTrue(json.has("v"))
        assertTrue(json.has("items"))
        assertEquals(1, json.getInt("v"))
        assertTrue(json.getJSONArray("items").length() > 0)
    }

    @Test
    fun serializeAndParseRoundtrip() {
        val creds = listOf(
            Credential(
                id = Credential.newId(),
                title = "Test",
                url = "https://example.com",
                packageName = "com.test.app",
                username = "user123",
                password = "secret",
                notes = "notes",
                favorite = true,
                updatedAt = System.currentTimeMillis()
            )
        )
        val serialized = VaultStore.serializeCreds(creds)
        val parsed = VaultStore.parse(serialized)
        assertEquals(1, parsed.size)
        assertEquals("Test", parsed[0].title)
        assertEquals("user123", parsed[0].username)
        assertEquals("secret", parsed[0].password)
        assertTrue(parsed[0].favorite)
    }

    @Test
    fun parseThrowsOnInvalidJson() {
        assertThrows(org.json.JSONException::class.java) {
            VaultStore.parse("{ invalid json }")
        }
    }
}