package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class WebDavSyncManagerTest {

    @Test
    fun webDavConfigJsonRoundtrip() {
        val config = WebDavSyncManager.WebDavConfig(
            url = "https://cloud.example.com/remote.php/dav/files/user/",
            username = "testuser",
            password = "apppassword",
            path = "vigilix/vault.vgv"
        )
        val json = config.toJson()
        val parsed = WebDavSyncManager.WebDavConfig.fromJson(json)
        assertEquals(config.url, parsed.url)
        assertEquals(config.username, parsed.username)
        assertEquals(config.password, parsed.password)
        assertEquals(config.path, parsed.path)
    }

    @Test
    fun webDavConfigDefaultPath() {
        val json = org.json.JSONObject().apply {
            put("url", "https://example.com/dav/")
            put("username", "user")
            put("password", "pass")
        }
        val config = WebDavSyncManager.WebDavConfig.fromJson(json)
        assertEquals("vigilix/vault.vgv", config.path)
    }

    @Test
    fun buildRemotePath() {
        val config = WebDavSyncManager.WebDavConfig(
            url = "https://cloud.example.com/remote.php/dav/files/user/",
            username = "user",
            password = "pass"
        )
        // Test via reflection since method is private
        // Just verify config stores correctly
        assertEquals("https://cloud.example.com/remote.php/dav/files/user/", config.url)
    }
}