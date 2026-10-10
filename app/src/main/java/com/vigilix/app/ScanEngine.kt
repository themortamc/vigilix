package com.vigilix.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

enum class ScanPhase { IDLE, STORAGE, APPS, VIRUSTOTAL, DONE, CANCELLED, FAILED }

/** Algo que merece atención. `packageName` != null cuando es una app instalada. */
data class Finding(
    val path: String,
    val displayName: String,
    val sha256: String,
    val severity: Status,
    val reason: String,
    val packageName: String? = null,
)

data class ScanState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val scannedFiles: Long = 0,
    val scannedBytes: Long = 0,
    val skippedFiles: Long = 0,
    val currentPath: String = "",
    val vtDone: Int = 0,
    val vtTotal: Int = 0,
    val vtUnknown: Int = 0,
    val findings: List<Finding> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val running: Boolean
        get() = phase == ScanPhase.STORAGE || phase == ScanPhase.APPS || phase == ScanPhase.VIRUSTOTAL
}

/**
 * Recorre el almacenamiento y las apps instaladas, calcula el SHA-256 de cada archivo y lo compara con
 * la base local. Opcionalmente consulta a VirusTotal por hash (con el límite del plan gratuito).
 *
 * Qué NO hace: no analiza el contenido ni el comportamiento. Un archivo desconocido no es un archivo seguro.
 */
object ScanEngine {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    private const val VT_MAX_PER_SCAN = 40
    private const val EMIT_EVERY_MS = 300L

    private val QUICK_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "apkm", "dex", "jar", "exe", "dll", "msi", "scr", "bat", "cmd", "ps1",
        "vbs", "js", "lnk", "sh", "elf", "com", "zip", "rar", "7z", "pdf", "doc", "docm", "xls", "xlsm",
        "rtf", "iso",
    )
    private val VT_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "apkm", "dex", "jar", "exe", "dll", "msi", "scr", "bat", "cmd", "ps1",
        "vbs", "js", "elf", "com",
    )

    private class Candidate(val sha256: String, val path: String, val name: String, val packageName: String?)

    /** Reinicia el estado (por ejemplo, para descartar un resultado viejo en pantalla). */
    fun reset() {
        if (!_state.value.running) _state.value = ScanState()
    }

    suspend fun run(context: Context, full: Boolean, useVirusTotal: Boolean) {
        val app = context.applicationContext
        val job = currentCoroutineContext()[Job]
        val findings = ArrayList<Finding>()
        val notes = ArrayList<String>()
        val candidates = LinkedHashMap<String, Candidate>()
        var state = ScanState(phase = ScanPhase.STORAGE)
        _state.value = state

        fun publish(update: (ScanState) -> ScanState) {
            state = update(state).copy(findings = findings.toList(), notes = notes.toList())
            _state.value = state
        }

        try {
            withContext(Dispatchers.IO) {
                var lastEmit = 0L
                fun onFile(path: String, size: Long, counted: Boolean) {
                    val now = System.currentTimeMillis()
                    state = state.copy(
                        scannedFiles = state.scannedFiles + if (counted) 1 else 0,
                        scannedBytes = state.scannedBytes + if (counted) size else 0,
                        currentPath = path,
                    )
                    if (now - lastEmit >= EMIT_EVERY_MS) {
                        lastEmit = now
                        _state.value = state.copy(findings = findings.toList(), notes = notes.toList())
                    }
                }

                scanStorage(app, full, job, findings, candidates, ::onFile) { skipped ->
                    state = state.copy(skippedFiles = state.skippedFiles + skipped)
                }
                publish { it.copy(phase = ScanPhase.APPS, currentPath = "") }
                scanInstalledApps(app, job, findings, candidates)
            }

            if (useVirusTotal) {
                scanVirusTotal(app, candidates, findings, notes) { done, total, unknown ->
                    publish { it.copy(phase = ScanPhase.VIRUSTOTAL, vtDone = done, vtTotal = total, vtUnknown = unknown) }
                }
            }
            publish { it.copy(phase = ScanPhase.DONE, currentPath = "") }
            Prefs.setLastScan(app, state.scannedFiles, findings.size)
            HistoryManager.addEntry(
                app,
                HistoryManager.Entry(
                    timestamp = System.currentTimeMillis(),
                    mode = if (full) "Completo" else "Rápido",
                    files = state.scannedFiles,
                    findings = findings.size,
                    vtUsed = state.vtDone,
                ),
            )
        } catch (e: CancellationException) {
            publish { it.copy(phase = ScanPhase.CANCELLED, currentPath = "") }
            throw e
        } catch (e: Exception) {
            notes.add(app.getString(R.string.scan_note_failed))
            publish { it.copy(phase = ScanPhase.FAILED, currentPath = "") }
        } finally {
            VtCache.flush(app)
        }
    }

    // ----- Almacenamiento compartido -----

    @Suppress("DEPRECATION")
    private fun scanStorage(
        context: Context,
        full: Boolean,
        job: Job?,
        findings: MutableList<Finding>,
        candidates: MutableMap<String, Candidate>,
        onFile: (path: String, size: Long, counted: Boolean) -> Unit,
        onSkipped: (Long) -> Unit,
    ) {
        val root = Environment.getExternalStorageDirectory()
        val androidDir = root.path + "/Android"

        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (job?.isActive == false) return FileVisitResult.TERMINATE
                // /Android/data y /Android/obb pertenecen a otras apps: Android no deja leerlos.
                val s = dir.toString()
                return if (s == "$androidDir/data" || s == "$androidDir/obb") {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (job?.isActive == false) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile || attrs.size() == 0L) return FileVisitResult.CONTINUE

                val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val isTestFile = name.lowercase(Locale.ROOT).contains("eicar")
                if (!full && ext !in QUICK_EXTENSIONS && !isTestFile) return FileVisitResult.CONTINUE

                val size = attrs.size()
                val path = file.toString()
                if (size > MAX_FILE_BYTES) {
                    onSkipped(1)
                    return FileVisitResult.CONTINUE
                }
                val hash = sha256(path)
                if (hash == null) {
                    onSkipped(1)
                    return FileVisitResult.CONTINUE
                }
                onFile(path, size, true)

                val known = HashDb.lookup(context, hash)
                if (known != null) {
                    findings.add(
                        Finding(path, name, hash, Status.BAD, context.getString(R.string.scan_reason_db, known)),
                    )
                } else if (ext in VT_EXTENSIONS || isTestFile) {
                    candidates.putIfAbsent(hash, Candidate(hash, path, name, null))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                onSkipped(1)
                return FileVisitResult.CONTINUE
            }
        }
        try {
            Files.walkFileTree(root.toPath(), emptySet(), Int.MAX_VALUE, visitor)
        } catch (e: IOException) {
            // Sin acceso al almacenamiento: se informa en la pantalla (permiso faltante).
        }
    }

    private fun sha256(path: String): String? =
        SecurityBridge.sha256OfFile(path) ?: if (!SecurityBridge.isLoaded) Hashing.sha256OfPath(path) else null

    // ----- Apps instaladas -----

    private fun scanInstalledApps(
        context: Context,
        job: Job?,
        findings: MutableList<Finding>,
        candidates: MutableMap<String, Candidate>,
    ) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }
        for (info in apps) {
            if (job?.isActive == false) return
            if (info.packageName == context.packageName) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue

            val label = info.loadLabel(pm).toString()
            val paths = ArrayList<String>()
            info.sourceDir?.let { paths.add(it) }
            info.splitSourceDirs?.let { paths.addAll(it) }
            for ((index, path) in paths.withIndex()) {
                val hash = sha256(path) ?: continue
                val known = HashDb.lookup(context, hash)
                if (known != null) {
                    findings.add(
                        Finding(path, label, hash, Status.BAD, context.getString(R.string.scan_reason_db, known), info.packageName),
                    )
                } else if (index == 0) {
                    // Solo el APK base va a VirusTotal, para cuidar el cupo gratuito.
                    candidates.putIfAbsent(hash, Candidate(hash, path, label, info.packageName))
                }
            }
        }
    }

    // ----- VirusTotal -----

    private suspend fun scanVirusTotal(
        context: Context,
        candidates: Map<String, Candidate>,
        findings: MutableList<Finding>,
        notes: MutableList<String>,
        progress: (done: Int, total: Int, unknown: Int) -> Unit,
    ) {
        val key = Prefs.vtKey(context)
        if (key.isNullOrBlank()) {
            notes.add(context.getString(R.string.scan_note_vt_nokey))
            return
        }
        // Primero las apps instaladas, después los archivos sueltos.
        val ordered = candidates.values.sortedBy { if (it.packageName != null) 0 else 1 }
        if (ordered.isEmpty()) return

        val remainingToday = (VirusTotalClient.DAILY_SAFE_LIMIT - Prefs.vtUsedToday(context)).coerceAtLeast(0)
        val limit = minOf(VT_MAX_PER_SCAN, remainingToday)
        val queue = ordered.take(limit)
        if (queue.size < ordered.size) {
            notes.add(context.getString(R.string.scan_note_vt_capped, queue.size, ordered.size))
        }

        var unknown = 0
        var lastRequestAt = 0L
        var networkErrors = 0
        progress(0, queue.size, 0)

        for ((index, candidate) in queue.withIndex()) {
            currentCoroutineContext().ensureActive()

            var report = VtCache.get(context, candidate.sha256)
            if (report == null) {
                waitForSlot(lastRequestAt)
                var result = withContext(Dispatchers.IO) { VirusTotalClient.lookup(key, candidate.sha256) }
                lastRequestAt = System.currentTimeMillis()
                Prefs.addVtUsage(context)

                if (result is VtResult.RateLimited) {
                    delay(60_000L)
                    result = withContext(Dispatchers.IO) { VirusTotalClient.lookup(key, candidate.sha256) }
                    lastRequestAt = System.currentTimeMillis()
                    Prefs.addVtUsage(context)
                }
                when (result) {
                    is VtResult.Report -> {
                        report = result
                        VtCache.put(context, candidate.sha256, result)
                    }
                    VtResult.NotFound -> unknown++
                    VtResult.RateLimited -> {
                        notes.add(context.getString(R.string.scan_note_vt_limit))
                        return
                    }
                    VtResult.InvalidKey -> {
                        notes.add(context.getString(R.string.scan_note_vt_badkey))
                        return
                    }
                    is VtResult.Error -> {
                        networkErrors++
                        if (networkErrors >= 3) {
                            notes.add(context.getString(R.string.scan_note_vt_network))
                            return
                        }
                    }
                }
            }

            if (report != null) {
                val finding = classify(context, candidate, report)
                if (finding != null) findings.add(finding)
            }
            progress(index + 1, queue.size, unknown)
        }
    }

    private suspend fun waitForSlot(lastRequestAt: Long) {
        while (true) {
            val wait = VirusTotalClient.MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait <= 0) return
            delay(minOf(wait, 1_000L))
            currentCoroutineContext().ensureActive()
        }
    }

    private fun classify(context: Context, c: Candidate, r: VtResult.Report): Finding? = when {
        r.malicious >= 5 -> Finding(
            c.path, c.name, c.sha256, Status.BAD,
            context.getString(R.string.scan_reason_vt_bad, r.malicious, r.total), c.packageName,
        )
        r.malicious >= 1 || r.suspicious >= 3 -> Finding(
            c.path, c.name, c.sha256, Status.WARN,
            context.getString(R.string.scan_reason_vt_warn, r.malicious + r.suspicious), c.packageName,
        )
        else -> null
    }
}
