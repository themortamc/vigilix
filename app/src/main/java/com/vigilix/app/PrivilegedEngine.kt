package com.vigilix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class ShellResult(val ok: Boolean, val output: String)

/**
 * Operaciones que necesitan root. No expone un shell genérico: cada función arma un
 * comando fijo y, si lleva un nombre de paquete, lo valida antes. Todo corre fuera
 * del hilo principal y con un tiempo máximo (el proceso se cierra si se cuelga).
 */
object PrivilegedEngine {
    // El primer `su` puede mostrar el aviso de Magisk y la persona tarda en aceptarlo.
    private const val ROOT_CHECK_TIMEOUT_MS = 30_000L
    private const val COMMAND_TIMEOUT_MS = 10_000L

    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackage(name: String): Boolean = name.length <= 255 && PACKAGE_REGEX.matches(name)

    /** Pide acceso root (puede mostrar el aviso del gestor de root). Llamar solo por acción del usuario. */
    suspend fun checkRoot(): Boolean {
        val result = run("id", ROOT_CHECK_TIMEOUT_MS)
        return result.ok && result.output.contains("uid=0")
    }

    suspend fun setPrivateDns(provider: DnsProvider): ShellResult {
        val host = provider.host
        val command = if (host != null) {
            // `host` viene de las constantes de DnsProvider, nunca de texto escrito por el usuario.
            "settings put global private_dns_specifier $host && " +
                "settings put global private_dns_mode ${provider.mode}"
        } else {
            "settings put global private_dns_mode ${provider.mode}"
        }
        return run(command, COMMAND_TIMEOUT_MS)
    }

    /** Limita la actividad en segundo plano de la app. No borra datos ni quita permisos. */
    suspend fun restrictBackground(pkg: String): ShellResult {
        if (!isValidPackage(pkg)) return ShellResult(false, "invalid package")
        return run(
            "am set-inactive $pkg true && cmd appops set $pkg RUN_IN_BACKGROUND ignore",
            COMMAND_TIMEOUT_MS,
        )
    }

    /** Revierte [restrictBackground]. */
    suspend fun allowBackground(pkg: String): ShellResult {
        if (!isValidPackage(pkg)) return ShellResult(false, "invalid package")
        return run(
            "cmd appops set $pkg RUN_IN_BACKGROUND allow && am set-inactive $pkg false",
            COMMAND_TIMEOUT_MS,
        )
    }

    private suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@withContext ShellResult(false, "su no disponible")
        }
        // Vigilante: si el proceso no termina a tiempo, se destruye y la lectura se corta.
        val watchdog = launch {
            delay(timeoutMs)
            process.destroy()
        }
        try {
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            ShellResult(code == 0, text.trim())
        } catch (e: IOException) {
            ShellResult(false, "")
        } finally {
            watchdog.cancel()
            process.destroy()
        }
    }
}
