package com.vigilix.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.util.Locale

/**
 * Historial de escaneos: guarda los últimos resultados para mostrarlos
 * sin necesidad de repetir el escaneo. Límite de 10 entradas.
 */
object HistoryManager {

    private const val PREFS = "vx_history"
    private const val KEY_COUNT = "history_count"
    private const val KEY_PREFIX = "entry_"
    private const val MAX_ENTRIES = 10

    data class Entry(
        val timestamp: Long,
        val mode: String,
        val files: Long,
        val findings: Int,
        val vtUsed: Int,
    )

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun addEntry(context: Context, entry: Entry) {
        val s = sp(context)
        val current = getCount(s)
        // Si hay 10, elimina el más viejo.
        if (current >= MAX_ENTRIES) {
            val oldest = s.getString(KEY_PREFIX + (current - MAX_ENTRIES), null)
            if (oldest != null) s.edit().remove(KEY_PREFIX + oldest).apply()
        }
        val id = "${entry.timestamp}_${System.nanoTime()}"
        s.edit()
            .putString(KEY_PREFIX + id, entry.toLine())
            .putInt(KEY_COUNT, if (current < MAX_ENTRIES) current + 1 else MAX_ENTRIES)
            .apply()
    }

    fun lastEntry(context: Context): Entry? {
        val s = sp(context)
        val count = getCount(s)
        if (count == 0) return null
        // El más reciente es el último que se escribió.
        val keys = s.all.keys.filter { it.startsWith(KEY_PREFIX) }.sortedBy { it.removePrefix(KEY_PREFIX) }
        val lastKey = keys.lastOrNull() ?: return null
        return fromLine(s.getString(lastKey, "") ?: return null)
    }

    fun recentEntries(context: Context, limit: Int = MAX_ENTRIES): List<Entry> {
        val s = sp(context)
        val keys = s.all.keys.filter { it.startsWith(KEY_PREFIX) }
            .sortedBy { it.removePrefix(KEY_PREFIX) }
            .takeLast(limit)
        return keys.mapNotNull { fromLine(s.getString(it, "") ?: return@mapNotNull null) }
    }

    fun clear(context: Context) {
        val s = sp(context)
        val keys = s.all.keys.filter { it.startsWith(KEY_PREFIX) }
        val edit = s.edit()
        keys.forEach { edit.remove(it) }
        edit.putInt(KEY_COUNT, 0).apply()
    }

    private fun getCount(s: android.content.SharedPreferences): Int =
        s.getInt(KEY_COUNT, 0)

    private fun Entry.toLine(): String =
        "$timestamp|$mode|$files|$findings|$vtUsed"

    private fun fromLine(line: String): Entry? {
        val parts = line.split("|")
        if (parts.size != 5) return null
        return Entry(
            timestamp = parts[0].toLongOrNull() ?: return null,
            mode = parts[1],
            files = parts[2].toLongOrNull() ?: 0,
            findings = parts[3].toIntOrNull() ?: 0,
            vtUsed = parts[4].toIntOrNull() ?: 0,
        )
    }
}