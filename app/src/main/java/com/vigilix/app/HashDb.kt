package com.vigilix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class DbError { ENGINE, FILE, TOO_MANY, URL, HTTP, NETWORK, TOO_BIG }

sealed interface DbResult {
    data class Ok(val total: Long, val added: Long) : DbResult
    data class Failed(val error: DbError, val detail: String? = null) : DbResult
}

/**
 * Base local de hashes SHA-256 de malware conocido. Se arma a partir de listas de texto, CSV o ZIP
 * (cualquier formato donde aparezcan hashes de 64 caracteres) y se consulta sin internet.
 *
 * Limitación inherente: solo detecta archivos IDÉNTICOS a muestras ya conocidas.
 */
object HashDb {
    /** SHA-256 del archivo de prueba EICAR, que todos los antivirus detectan a propósito. */
    const val EICAR_SHA256 = "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"

    private const val MAX_ENTRIES = 2_000_000
    private const val MAX_BYTES = 300L * 1024 * 1024

    private class TooBig : IOException()

    fun file(context: Context) = File(context.applicationContext.filesDir, "malware_hashes.vxdb")

    fun size(context: Context): Long = SecurityBridge.hashDbSize(file(context).path)

    /** Nombre de la coincidencia si el hash es conocido, o null. */
    fun lookup(context: Context, sha256: String): String? {
        if (sha256 == EICAR_SHA256) return "EICAR (archivo de prueba)"
        return if (SecurityBridge.hashDbHas(file(context).path, sha256)) "base de firmas local" else null
    }

    /** Suma los hashes de un archivo elegido por el usuario (txt, csv o zip). */
    suspend fun importFrom(context: Context, input: InputStream): DbResult = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "hashes_in.txt")
        try {
            copyToText(input, tmp)
            merge(context, tmp)
        } catch (e: TooBig) {
            DbResult.Failed(DbError.TOO_BIG)
        } catch (e: IOException) {
            DbResult.Failed(DbError.FILE)
        } finally {
            tmp.delete()
        }
    }

    /** Descarga una lista (solo HTTPS) y la suma a la base. Se usa únicamente cuando el usuario lo pide. */
    suspend fun downloadAndMerge(context: Context, url: String): DbResult = withContext(Dispatchers.IO) {
        val parsed = try {
            URL(url.trim())
        } catch (e: IOException) {
            return@withContext DbResult.Failed(DbError.URL)
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return@withContext DbResult.Failed(DbError.URL)
        }
        val tmp = File(context.cacheDir, "hashes_dl.txt")
        var connection: HttpURLConnection? = null
        try {
            connection = parsed.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Vigilix")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext DbResult.Failed(DbError.HTTP, code.toString())
            }
            connection.inputStream.use { copyToText(it, tmp) }
            merge(context, tmp)
        } catch (e: TooBig) {
            DbResult.Failed(DbError.TOO_BIG)
        } catch (e: IOException) {
            DbResult.Failed(DbError.NETWORK)
        } finally {
            connection?.disconnect()
            tmp.delete()
        }
    }

    private fun merge(context: Context, textFile: File): DbResult {
        if (!SecurityBridge.isLoaded) return DbResult.Failed(DbError.ENGINE)
        val db = file(context)
        val before = SecurityBridge.hashDbSize(db.path)
        val total = SecurityBridge.hashDbMergeText(db.path, textFile.path, MAX_ENTRIES)
        return when {
            total == -2L -> DbResult.Failed(DbError.TOO_MANY)
            total < 0L -> DbResult.Failed(DbError.FILE)
            else -> {
                Prefs.setDbUpdatedAt(context, System.currentTimeMillis())
                DbResult.Ok(total, total - before)
            }
        }
    }

    /** Copia a un texto plano; si el contenido es un ZIP, concatena sus archivos. Con tope de tamaño. */
    private fun copyToText(source: InputStream, target: File) {
        val input = BufferedInputStream(source)
        input.mark(4)
        val magic = ByteArray(4)
        val read = input.read(magic)
        input.reset()
        val isZip = read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2].toInt() == 3 && magic[3].toInt() == 4

        FileOutputStream(target).use { out ->
            var written = 0L
            if (isZip) {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        written = copyLimited(zip, out, written)
                        out.write('\n'.code)
                    }
                }
            } else {
                copyLimited(input, out, written)
            }
        }
    }

    private fun copyLimited(from: InputStream, to: OutputStream, alreadyWritten: Long): Long {
        var total = alreadyWritten
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw TooBig()
            to.write(buffer, 0, n)
        }
        return total
    }
}
