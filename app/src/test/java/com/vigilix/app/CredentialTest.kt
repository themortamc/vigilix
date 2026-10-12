package com.vigilix.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialTest {

    @Test
    fun newIdGeneratesUuid() {
        val id1 = Credential.newId()
        val id2 = Credential.newId()
        assertNotNull(id1)
        assertNotNull(id2)
        assertNotEquals(id1, id2)
    }

    @Test
    fun hostOfExtractsDomain() {
        assertEquals("gmail.com", Credential.hostOf("https://www.gmail.com"))
        assertEquals("gmail.com", Credential.hostOf("gmail.com"))
        assertEquals("accounts.google.com", Credential.hostOf("https://accounts.google.com/path"))
        assertEquals("example.com", Credential.hostOf("http://example.com:8080"))
    }

    @Test
    fun hostOfReturnsNullForInvalid() {
        assertNull(Credential.hostOf(""))
        assertNull(Credential.hostOf("   "))
        assertNull(Credential.hostOf("not a url"))
    }

    @Test
    fun domainMatchesExact() {
        assertTrue(Credential.domainMatches("gmail.com", "gmail.com"))
        assertTrue(Credential.domainMatches("gmail.com", "www.gmail.com"))
    }

    @Test
    fun domainMatchesSubdomain() {
        assertTrue(Credential.domainMatches("gmail.com", "mail.gmail.com"))
        assertTrue(Credential.domainMatches("google.com", "accounts.google.com"))
    }

    @Test
    fun domainDoesNotMatchUnrelated() {
        assertFalse(Credential.domainMatches("gmail.com", "evil.com"))
        assertFalse(Credential.domainMatches("gmail.com", "gmail.com.evil.com"))
        assertFalse(Credential.domainMatches("gmail.com", "evilgmail.com"))
    }

    @Test
    fun credentialJsonRoundtrip() {
        val cred = Credential(
            id = Credential.newId(),
            title = "Test",
            url = "https://example.com",
            packageName = "com.test",
            username = "user",
            password = "pass",
            notes = "notes",
            favorite = true,
            updatedAt = 1700000000000L
        )
        val json = cred.toJson()
        val parsed = Credential.fromJson(json)
        assertEquals(cred.title, parsed.title)
        assertEquals(cred.url, parsed.url)
        assertEquals(cred.username, parsed.username)
        assertEquals(cred.password, parsed.password)
        assertEquals(cred.favorite, parsed.favorite)
        assertEquals(cred.updatedAt, parsed.updatedAt)
    }
}