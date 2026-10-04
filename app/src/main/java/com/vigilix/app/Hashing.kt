package com.vigilix.app

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 real de Java: respaldo cuando el motor Rust no está disponible. Nunca devuelve valores inventados. */
object Hashing {
    fun sha256(input: InputStream): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    }

    fun sha256OfPath(path: String): String? = try {
        FileInputStream(File(path)).use { sha256(it) }
    } catch (e: IOException) {
        null
    }
}
