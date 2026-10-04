package com.vigilix.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.ItemFileBinding
import com.vigilix.app.databinding.ItemFindingBinding
import com.vigilix.app.databinding.ScreenScannerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** Pestaña "Escáner": escaneo del teléfono, base de firmas, VirusTotal y verificación de archivos sueltos. */
class ScannerScreen(private val activity: AppCompatActivity, private val v: ScreenScannerBinding) {

    private var shownFindings = -1
    private var shownPhase = ScanPhase.IDLE

    private val pickFiles = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) hashFiles(uris)
    }
    private val importDb = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDbFrom(uri)
    }
    private val requestStorage = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (granted) startScan()
    }
    private val requestNotifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun setup() {
        v.calloutScanInfo.show(Status.INFO, activity.getString(R.string.scan_info_title), activity.getString(R.string.scan_info_body))
        v.calloutApk.hide()

        v.toggleScanMode.check(if (Prefs.scanFull(activity)) R.id.btnScanFull else R.id.btnScanQuick)
        v.toggleScanMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                Prefs.setScanFull(activity, checkedId == R.id.btnScanFull)
                updateModeHint()
            }
        }
        v.switchVt.isChecked = Prefs.vtEnabled(activity)
        v.switchVt.setOnCheckedChangeListener { _, checked ->
            Prefs.setVtEnabled(activity, checked)
            updateVtHint()
        }

        v.btnScanStart.setOnClickListener { startScan() }
        v.btnScanCancel.setOnClickListener { ScanService.stop(activity) }
        v.btnGrantAccess.setOnClickListener { requestStorageAccess() }

        v.btnDbUpdate.setOnClickListener { updateDb() }
        v.btnDbImport.setOnClickListener { importDb.launch(arrayOf("*/*")) }
        v.btnDbSource.setOnClickListener { chooseDbSource() }

        v.btnVtKey.setOnClickListener { editVtKey() }
        v.btnVtGetKey.setOnClickListener { activity.openUrl("https://www.virustotal.com/gui/my-apikey") }

        v.btnPickFiles.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        v.calloutScanResult.hide()
        v.calloutDbResult.hide()
        v.calloutScanPerm.hide()

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScanEngine.state.onEach { render(it) }.launchIn(this)
            }
        }
        refresh()
    }

    /** Se llama al mostrar la pestaña o al volver a la app (el usuario pudo cambiar permisos). */
    fun refresh() {
        updateModeHint()
        updateVtHint()
        updatePermission()
        updateDbInfo()
    }

    // ---------------------------------------------------------------------------------------------
    // Escaneo del teléfono
    // ---------------------------------------------------------------------------------------------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun updatePermission() {
        val ok = hasStorageAccess()
        v.btnGrantAccess.isVisible = !ok
        if (ok) {
            v.calloutScanPerm.hide()
        } else {
            v.calloutScanPerm.show(
                Status.WARN,
                activity.getString(R.string.scan_perm_title),
                activity.getString(R.string.scan_perm_body),
            )
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.parse("package:${activity.packageName}")
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
            } catch (e: ActivityNotFoundException) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: ActivityNotFoundException) {
                    activity.toast(R.string.error_open_settings)
                }
            }
        } else {
            requestStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun updateModeHint() {
        v.tvScanModeHint.setText(if (Prefs.scanFull(activity)) R.string.scan_mode_full_hint else R.string.scan_mode_quick_hint)
    }

    private fun updateVtHint() {
        val hasKey = !Prefs.vtKey(activity).isNullOrBlank()
        v.tvVtHint.text = activity.getString(if (hasKey) R.string.scan_vt_hint_ready else R.string.scan_vt_hint_nokey)
        v.tvVtStatus.text = if (hasKey) {
            activity.getString(R.string.vt_status_ready, Prefs.vtUsedToday(activity), VirusTotalClient.DAILY_SAFE_LIMIT)
        } else {
            activity.getString(R.string.vt_status_nokey)
        }
        v.btnVtKey.setText(if (hasKey) R.string.vt_change_key else R.string.vt_set_key)
    }

    private fun startScan() {
        if (ScanEngine.state.value.running) return
        if (!hasStorageAccess()) {
            updatePermission()
            requestStorageAccess()
            return
        }
        val wantsVt = v.switchVt.isChecked
        if (wantsVt && Prefs.vtKey(activity).isNullOrBlank()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.scan_vt_missing_title)
                .setMessage(R.string.scan_vt_missing_body)
                .setPositiveButton(R.string.vt_set_key) { _, _ -> editVtKey() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Sin este permiso el escaneo igual funciona, pero no se ve la notificación de progreso.
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        shownFindings = -1
        v.findingsContainer.removeAllViews()
        v.calloutScanResult.hide()
        ScanService.start(activity, Prefs.scanFull(activity), wantsVt)
    }

    private fun render(state: ScanState) {
        val running = state.running
        v.btnScanStart.isVisible = !running
        v.btnScanCancel.isVisible = running
        v.progressScan.isVisible = running
        v.tvScanStatus.isVisible = state.phase != ScanPhase.IDLE
        v.tvScanPath.isVisible = running && state.currentPath.isNotEmpty()
        v.tvScanPath.text = state.currentPath
        v.btnPickFiles.isEnabled = true

        v.tvScanStatus.text = when (state.phase) {
            ScanPhase.STORAGE -> activity.getString(
                R.string.scan_status_files, state.scannedFiles, Formatter.formatShortFileSize(activity, state.scannedBytes),
            )
            ScanPhase.APPS -> activity.getString(R.string.scan_status_apps)
            ScanPhase.VIRUSTOTAL -> activity.getString(R.string.scan_status_vt, state.vtDone, state.vtTotal)
            else -> activity.getString(
                R.string.scan_status_files, state.scannedFiles, Formatter.formatShortFileSize(activity, state.scannedBytes),
            )
        }

        if (state.findings.size != shownFindings || state.phase != shownPhase) {
            shownFindings = state.findings.size
            renderFindings(state.findings)
        }
        if (state.phase != shownPhase) {
            shownPhase = state.phase
            renderResult(state)
        } else if (!running && state.phase != ScanPhase.IDLE) {
            renderResult(state)
        }
    }

    private fun renderResult(state: ScanState) {
        val notes = state.notes.joinToString("\n")
        when (state.phase) {
            ScanPhase.DONE -> {
                val skipped = if (state.skippedFiles > 0) activity.getString(R.string.scan_skipped, state.skippedFiles) else ""
                val unknown = if (state.vtUnknown > 0) activity.getString(R.string.scan_vt_unknown, state.vtUnknown) else ""
                val extra = listOf(skipped, unknown, notes).filter { it.isNotEmpty() }.joinToString("\n")
                if (state.findings.isEmpty()) {
                    v.calloutScanResult.show(
                        Status.OK,
                        activity.getString(R.string.scan_result_clean_title),
                        activity.getString(R.string.scan_result_clean_body, state.scannedFiles) + if (extra.isNotEmpty()) "\n$extra" else "",
                    )
                } else {
                    val worst = if (state.findings.any { it.severity == Status.BAD }) Status.BAD else Status.WARN
                    v.calloutScanResult.show(
                        worst,
                        activity.getString(R.string.scan_result_found_title, state.findings.size),
                        activity.getString(R.string.scan_result_found_body) + if (extra.isNotEmpty()) "\n$extra" else "",
                    )
                }
            }
            ScanPhase.CANCELLED -> v.calloutScanResult.show(Status.INFO, activity.getString(R.string.scan_result_cancelled), notes)
            ScanPhase.FAILED -> v.calloutScanResult.show(Status.BAD, activity.getString(R.string.scan_note_failed), notes)
            else -> if (!state.running) v.calloutScanResult.hide()
        }
    }

    private fun renderFindings(findings: List<Finding>) {
        v.findingsContainer.removeAllViews()
        for (finding in findings.sortedByDescending { it.severity == Status.BAD }) {
            val row = ItemFindingBinding.inflate(activity.layoutInflater, v.findingsContainer, false)
            row.ivFinding.setStatus(finding.severity)
            row.tvFindingName.text = finding.displayName
            row.tvFindingReason.text = finding.reason
            row.tvFindingPath.text = finding.path
            row.tvFindingHash.text = finding.sha256
            row.btnFindingCopy.setOnClickListener { activity.copyToClipboard(finding.sha256, sensitive = false) }
            row.btnFindingVt.setOnClickListener { activity.openUrl("https://www.virustotal.com/gui/file/${finding.sha256}") }
            val pkg = finding.packageName
            if (pkg != null) {
                row.btnFindingAction.setText(R.string.finding_app_settings)
                row.btnFindingAction.setOnClickListener { activity.openAppSettings(pkg) }
            } else {
                row.btnFindingAction.setText(R.string.finding_delete)
                row.btnFindingAction.setOnClickListener { confirmDelete(finding, row.root) }
            }
            v.findingsContainer.addView(row.root)
        }
    }

    private fun confirmDelete(finding: Finding, row: View) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.finding_delete_title)
            .setMessage(activity.getString(R.string.finding_delete_body, finding.displayName))
            .setPositiveButton(R.string.finding_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { File(finding.path).delete() }
                    if (deleted) {
                        v.findingsContainer.removeView(row)
                        activity.toast(R.string.finding_deleted)
                    } else {
                        activity.toast(R.string.finding_delete_failed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // Base de firmas
    // ---------------------------------------------------------------------------------------------

    private fun updateDbInfo() {
        val count = HashDb.size(activity)
        val updated = Prefs.dbUpdatedAt(activity)
        val whenText = if (updated == 0L) {
            activity.getString(R.string.db_never)
        } else {
            DateFormat.getMediumDateFormat(activity).format(Date(updated))
        }
        v.tvDbInfo.text = activity.getString(R.string.db_info, count, whenText)
        v.btnDbUpdate.setText(if (Prefs.dbUrl(activity).isNullOrBlank()) R.string.db_update_setup else R.string.db_update)
    }

    private fun updateDb() {
        val url = Prefs.dbUrl(activity)
        if (url.isNullOrBlank()) {
            chooseDbSource()
            return
        }
        runDbJob { HashDb.downloadAndMerge(activity, url) }
    }

    private fun importDbFrom(uri: Uri) {
        runDbJob {
            val input = try {
                activity.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (input == null) DbResult.Failed(DbError.FILE) else input.use { HashDb.importFrom(activity, it) }
        }
    }

    private fun runDbJob(job: suspend () -> DbResult) {
        v.btnDbUpdate.isEnabled = false
        v.btnDbImport.isEnabled = false
        v.calloutDbResult.show(Status.INFO, activity.getString(R.string.db_working))
        activity.lifecycleScope.launch {
            val result = job()
            v.btnDbUpdate.isEnabled = true
            v.btnDbImport.isEnabled = true
            when (result) {
                is DbResult.Ok -> v.calloutDbResult.show(
                    Status.OK,
                    activity.getString(R.string.db_done_title),
                    activity.getString(R.string.db_done_body, result.added, result.total),
                )
                is DbResult.Failed -> v.calloutDbResult.show(
                    Status.BAD,
                    activity.getString(R.string.db_fail_title),
                    activity.getString(dbErrorText(result.error), result.detail ?: ""),
                )
            }
            updateDbInfo()
        }
    }

    private fun dbErrorText(error: DbError): Int = when (error) {
        DbError.ENGINE -> R.string.db_err_engine
        DbError.FILE -> R.string.db_err_file
        DbError.TOO_MANY -> R.string.db_err_too_many
        DbError.URL -> R.string.db_err_url
        DbError.HTTP -> R.string.db_err_http
        DbError.NETWORK -> R.string.db_err_network
        DbError.TOO_BIG -> R.string.db_err_too_big
    }

    private fun chooseDbSource() {
        val items = arrayOf(
            activity.getString(R.string.db_source_bazaar),
            activity.getString(R.string.db_source_custom),
            activity.getString(R.string.db_source_get_key),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.db_source_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> activity.askText(R.string.db_bazaar_key_title, R.string.db_bazaar_key_hint, secret = true) { key ->
                        if (Regex("^[A-Za-z0-9_-]{8,128}$").matches(key)) {
                            Prefs.setDbUrl(activity, "https://mb-api.abuse.ch/v2/files/exports/$key/recent.csv")
                            updateDbInfo()
                            activity.toast(R.string.db_source_saved)
                        } else {
                            activity.toast(R.string.db_bazaar_key_invalid)
                        }
                    }
                    1 -> activity.askText(R.string.db_custom_title, R.string.db_custom_hint, secret = false, message = R.string.db_custom_body) { url ->
                        if (url.startsWith("https://", ignoreCase = true)) {
                            Prefs.setDbUrl(activity, url)
                            updateDbInfo()
                            activity.toast(R.string.db_source_saved)
                        } else {
                            activity.toast(R.string.db_err_url_short)
                        }
                    }
                    else -> activity.openUrl("https://auth.abuse.ch/")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // VirusTotal
    // ---------------------------------------------------------------------------------------------

    private fun editVtKey() {
        activity.askText(R.string.vt_key_title, R.string.vt_key_hint, secret = true, message = R.string.vt_key_body) { key ->
            Prefs.setVtKey(activity, key)
            updateVtHint()
            activity.toast(if (key.isBlank()) R.string.vt_key_removed else R.string.vt_key_saved)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verificar archivos sueltos (selector del sistema, sin permisos de almacenamiento)
    // ---------------------------------------------------------------------------------------------

    private class FileResult(val name: String, val size: Long, val hash: String?, val viaRust: Boolean, val known: String?)

    private fun hashFiles(selected: List<Uri>) {
        val uris = selected.take(MAX_FILES)
        v.filesContainer.removeAllViews()
        v.calloutApk.hide()
        v.btnPickFiles.isEnabled = false
        v.tvFilesStatus.text = activity.getString(R.string.files_working, uris.size)

        activity.lifecycleScope.launch {
            var hasApk = false
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) { describeAndHash(uri) }
                addFileRow(result)
                if (result.name.endsWith(".apk", ignoreCase = true)) hasApk = true
            }
            v.tvFilesStatus.text = if (selected.size > uris.size) {
                activity.getString(R.string.files_done_truncated, uris.size, selected.size)
            } else {
                activity.getString(R.string.files_done, uris.size)
            }
            v.btnPickFiles.isEnabled = true
            if (hasApk) {
                v.calloutApk.show(Status.WARN, activity.getString(R.string.files_apk_title), activity.getString(R.string.files_apk_body))
            }
        }
    }

    /** Corre en un hilo de fondo. Usa Rust si está disponible y, si no, un SHA-256 real de Java. */
    private fun describeAndHash(uri: Uri): FileResult {
        var name = activity.getString(R.string.files_unnamed)
        var size = -1L
        try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            activity.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Sin nombre ni tamaño: igual se puede calcular la huella.
        }

        var hash: String? = null
        if (SecurityBridge.isLoaded) {
            hash = try {
                activity.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                    SecurityBridge.sha256OfDescriptor(pfd.detachFd())
                }
            } catch (e: Exception) {
                null
            }
        }
        val viaRust = hash != null
        if (hash == null) {
            hash = try {
                activity.contentResolver.openInputStream(uri)?.use { Hashing.sha256(it) }
            } catch (e: Exception) {
                null
            }
        }
        val known = hash?.let { HashDb.lookup(activity, it) }
        return FileResult(name, size, hash, viaRust, known)
    }

    private fun addFileRow(result: FileResult) {
        val row = ItemFileBinding.inflate(activity.layoutInflater, v.filesContainer, false)
        row.tvFileName.text = result.name
        val hash = result.hash
        if (hash == null) {
            row.tvFileMeta.text = activity.getString(R.string.files_error_meta)
            row.tvFileHash.text = activity.getString(R.string.files_error_hash)
            row.btnFileCopy.isEnabled = false
            row.btnFileVt.isEnabled = false
        } else {
            val size = if (result.size >= 0) {
                Formatter.formatShortFileSize(activity, result.size)
            } else {
                activity.getString(R.string.files_size_unknown)
            }
            val engine = activity.getString(if (result.viaRust) R.string.files_engine_rust else R.string.files_engine_java)
            val verdict = if (result.known != null) {
                activity.getString(R.string.files_known_bad, result.known)
            } else {
                activity.getString(R.string.files_not_in_db)
            }
            row.tvFileMeta.text = activity.getString(R.string.files_meta, size, engine) + "\n" + verdict
            row.tvFileHash.text = hash
            row.btnFileCopy.setOnClickListener { activity.copyToClipboard(hash, sensitive = false) }
            row.btnFileVt.setOnClickListener { activity.openUrl("https://www.virustotal.com/gui/file/$hash") }
        }
        v.filesContainer.addView(row.root)
    }

    private companion object {
        const val MAX_FILES = 10
    }
}
