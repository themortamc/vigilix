package com.vigilix.app

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.ActivityMainBinding
import com.vigilix.app.databinding.ItemAppBinding
import com.vigilix.app.databinding.ItemCheckBinding
import com.vigilix.app.databinding.ItemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pantalla única con cuatro secciones: Inicio, Archivos, Bóveda y Apps.
 * Toda la lógica de seguridad vive en SecurityBridge (Rust), DnsController y PrivilegedEngine.
 */
class MainActivity : AppCompatActivity() {

    private enum class Tab(val menuId: Int) {
        HOME(R.id.nav_home),
        FILES(R.id.nav_files),
        VAULT(R.id.nav_vault),
        APPS(R.id.nav_apps),
    }

    private class Check(
        val status: Status,
        val title: String,
        val detail: String,
        val counted: Boolean = true,
    )

    private class FileResult(val name: String, val size: Long, val hash: String?, val viaRust: Boolean)

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var b: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wipeVaultTask = Runnable { wipeVault() }

    private var tab = Tab.HOME
    private var rootState: Boolean? = null
    private var vaultEncrypt = true
    private var appsLoaded = false
    private var appsJob: Job? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) hashFiles(uris)
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        tab = savedInstanceState?.getInt(STATE_TAB)?.let { Tab.values().getOrNull(it) } ?: Tab.HOME

        setupHome()
        setupFiles()
        setupVault()
        setupApps()

        b.bottomNav.selectedItemId = tab.menuId
        b.bottomNav.setOnItemSelectedListener { item ->
            val target = Tab.values().firstOrNull { it.menuId == item.itemId }
            if (target != null) {
                showTab(target)
                if (target == Tab.HOME) refreshChecks()
                if (target == Tab.APPS && !appsLoaded) loadApps()
            }
            target != null
        }
        showTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab.ordinal)
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(wipeVaultTask)
    }

    override fun onResume() {
        super.onResume()
        // La persona pudo cambiar ajustes o permisos en otra pantalla.
        when (tab) {
            Tab.HOME -> refreshChecks()
            Tab.APPS -> if (appsLoaded) loadApps()
            else -> Unit
        }
    }

    override fun onStop() {
        super.onStop()
        // Si la app queda en segundo plano, los datos sensibles se borran de la pantalla tras un rato.
        mainHandler.postDelayed(wipeVaultTask, VAULT_WIPE_DELAY_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(wipeVaultTask)
        if (isFinishing) wipeVault()
        super.onDestroy()
    }

    private fun showTab(target: Tab) {
        tab = target
        b.scrollHome.isVisible = target == Tab.HOME
        b.scrollFiles.isVisible = target == Tab.FILES
        b.scrollVault.isVisible = target == Tab.VAULT
        b.scrollApps.isVisible = target == Tab.APPS

        // Capturas de pantalla y vista previa de "recientes" bloqueadas solo en la bóveda.
        if (target == Tab.VAULT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // INICIO: controles reales del dispositivo + DNS privado
    // ---------------------------------------------------------------------------------------------

    private fun setupHome() {
        b.tvAdbCommand.text = DnsController.adbCommand(this)
        b.btnCopyAdb.setOnClickListener { copyToClipboard(DnsController.adbCommand(this), sensitive = false) }
        b.btnDnsAdguard.setOnClickListener { onDnsClicked(DnsProvider.ADGUARD) }
        b.btnDnsQuad9.setOnClickListener { onDnsClicked(DnsProvider.QUAD9) }
        b.btnDnsAuto.setOnClickListener { onDnsClicked(DnsProvider.AUTOMATIC) }
        updateAccessChip()
    }

    private fun refreshChecks() {
        val checks = listOf(engineCheck(), lockCheck(), patchCheck(), dnsCheck(), writePermissionCheck())

        b.checksContainer.removeAllViews()
        for (check in checks) {
            val row = ItemCheckBinding.inflate(layoutInflater, b.checksContainer, false)
            row.ivCheck.setStatus(check.status)
            row.tvCheckTitle.text = check.title
            row.tvCheckDetail.text = check.detail
            b.checksContainer.addView(row.root)
        }

        // Resumen honesto: cuántos controles reales están en orden (no un porcentaje inventado).
        val counted = checks.filter { it.counted && it.status != Status.INFO }
        val okCount = counted.count { it.status == Status.OK }
        val worst = when {
            counted.any { it.status == Status.BAD } -> Status.BAD
            counted.any { it.status == Status.WARN } -> Status.WARN
            else -> Status.OK
        }
        val bodyRes = when (worst) {
            Status.OK -> R.string.summary_body_ok
            Status.WARN -> R.string.summary_body_warn
            else -> R.string.summary_body_bad
        }
        b.calloutSummary.show(
            worst,
            getString(R.string.summary_title, okCount, counted.size),
            getString(bodyRes),
        )
        refreshDnsPanel()
    }

    private fun engineCheck(): Check {
        val title = getString(R.string.check_engine_title)
        return if (SecurityBridge.isLoaded) {
            Check(Status.OK, title, getString(R.string.check_engine_ok))
        } else {
            Check(Status.BAD, title, getString(R.string.check_engine_bad))
        }
    }

    private fun lockCheck(): Check {
        val title = getString(R.string.check_lock_title)
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return if (keyguard.isDeviceSecure) {
            Check(Status.OK, title, getString(R.string.check_lock_ok))
        } else {
            Check(Status.WARN, title, getString(R.string.check_lock_bad))
        }
    }

    private fun patchCheck(): Check {
        val title = getString(R.string.check_patch_title)
        val raw = Build.VERSION.SECURITY_PATCH
        val date = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
        } catch (e: ParseException) {
            null
        }
        if (date == null) return Check(Status.INFO, title, getString(R.string.check_patch_unknown))

        val days = ((System.currentTimeMillis() - date.time) / MILLIS_PER_DAY).toInt()
        val status = when {
            days <= 120 -> Status.OK
            days <= 365 -> Status.WARN
            else -> Status.BAD
        }
        return Check(status, title, getString(R.string.check_patch_detail, raw, days))
    }

    private fun dnsCheck(): Check {
        val title = getString(R.string.check_dns_title)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Check(Status.INFO, title, getString(R.string.dns_state_old))
        }
        val state = DnsController.read(this)
        return when {
            state.isCustomHost -> Check(Status.OK, title, getString(R.string.dns_state_host, state.host))
            state.mode == "off" -> Check(Status.WARN, title, getString(R.string.dns_state_off))
            else -> Check(Status.WARN, title, getString(R.string.dns_state_auto))
        }
    }

    private fun writePermissionCheck(): Check {
        val title = getString(R.string.check_perm_title)
        return if (DnsController.hasWritePermission(this)) {
            Check(Status.OK, title, getString(R.string.check_perm_ok), counted = false)
        } else {
            Check(Status.INFO, title, getString(R.string.check_perm_missing), counted = false)
        }
    }

    private fun refreshDnsPanel() {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        listOf(b.btnDnsAdguard, b.btnDnsQuad9, b.btnDnsAuto).forEach { it.isEnabled = supported }
        b.tvDnsState.text = if (!supported) {
            getString(R.string.dns_state_old)
        } else {
            val state = DnsController.read(this)
            when {
                state.isCustomHost -> getString(R.string.dns_now, getString(R.string.dns_state_host, state.host))
                state.mode == "off" -> getString(R.string.dns_now, getString(R.string.dns_state_off))
                else -> getString(R.string.dns_now, getString(R.string.dns_state_auto))
            }
        }
        b.panelAdb.isVisible = supported && !DnsController.hasWritePermission(this)
    }

    private fun updateAccessChip() {
        b.tvAccess.setText(
            when (rootState) {
                true -> R.string.access_root
                false -> R.string.access_no_root
                null -> R.string.access_standard
            },
        )
    }

    /** Pide root solo cuando la persona eligió una acción que lo necesita. */
    private suspend fun ensureRoot(): Boolean {
        if (rootState == true) return true
        toast(R.string.root_asking)
        val granted = PrivilegedEngine.checkRoot()
        rootState = granted
        updateAccessChip()
        return granted
    }

    private fun onDnsClicked(provider: DnsProvider) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            b.calloutDnsResult.show(Status.INFO, getString(R.string.dns_old_title), getString(R.string.dns_old_body))
            return
        }
        lifecycleScope.launch {
            if (DnsController.hasWritePermission(this@MainActivity)) {
                val applied = withContext(Dispatchers.IO) {
                    DnsController.applyWithSettings(applicationContext, provider)
                }
                if (applied) {
                    showDnsDone(provider)
                    return@launch
                }
            }
            askDnsMethod(provider)
        }
    }

    private fun providerLabel(provider: DnsProvider): String =
        provider.host ?: getString(R.string.dns_auto_label)

    private fun askDnsMethod(provider: DnsProvider) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dns_ask_title)
            .setMessage(getString(R.string.dns_ask_body, providerLabel(provider)))
            .setPositiveButton(R.string.dns_ask_root) { _, _ -> applyDnsWithRoot(provider) }
            .setNeutralButton(R.string.dns_ask_manual) { _, _ -> showManualDns(provider) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDnsWithRoot(provider: DnsProvider) {
        lifecycleScope.launch {
            b.calloutDnsResult.show(Status.INFO, getString(R.string.dns_working))
            if (!ensureRoot()) {
                b.calloutDnsResult.show(
                    Status.BAD,
                    getString(R.string.dns_noroot_title),
                    getString(R.string.dns_noroot_body),
                )
                return@launch
            }
            val result = PrivilegedEngine.setPrivateDns(provider)
            // Se vuelve a leer el ajuste real: no se confía solo en el código de salida del comando.
            if (result.ok && DnsController.isApplied(this@MainActivity, provider)) {
                showDnsDone(provider)
            } else {
                b.calloutDnsResult.show(
                    Status.BAD,
                    getString(R.string.dns_fail_title),
                    getString(R.string.dns_fail_body),
                )
                refreshChecks()
            }
        }
    }

    private fun showManualDns(provider: DnsProvider) {
        provider.host?.let { ClipboardHelper.copy(this, it, sensitive = false) }
        b.calloutDnsResult.show(
            Status.INFO,
            getString(R.string.dns_manual_title),
            getString(R.string.dns_manual_body, providerLabel(provider)),
        )
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_open_settings)
        }
    }

    private fun showDnsDone(provider: DnsProvider) {
        b.calloutDnsResult.show(
            Status.OK,
            getString(R.string.dns_done_title),
            getString(R.string.dns_done_body, providerLabel(provider)),
        )
        refreshChecks()
    }

    // ---------------------------------------------------------------------------------------------
    // ARCHIVOS: huella SHA-256 (se lee con el selector del sistema, sin permisos de almacenamiento)
    // ---------------------------------------------------------------------------------------------

    private fun setupFiles() {
        b.calloutFilesInfo.show(
            Status.INFO,
            getString(R.string.files_info_title),
            getString(R.string.files_info_body),
        )
        b.btnPickFiles.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
    }

    private fun hashFiles(selected: List<Uri>) {
        val uris = selected.take(MAX_FILES)
        b.filesContainer.removeAllViews()
        b.calloutApk.hide()
        b.btnPickFiles.isEnabled = false
        b.tvFilesStatus.text = getString(R.string.files_working, uris.size)

        lifecycleScope.launch {
            var hasApk = false
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) { describeAndHash(uri) }
                addFileRow(result)
                if (result.name.endsWith(".apk", ignoreCase = true)) hasApk = true
            }
            b.tvFilesStatus.text = if (selected.size > uris.size) {
                getString(R.string.files_done_truncated, uris.size, selected.size)
            } else {
                getString(R.string.files_done, uris.size)
            }
            b.btnPickFiles.isEnabled = true
            if (hasApk) {
                b.calloutApk.show(
                    Status.WARN,
                    getString(R.string.files_apk_title),
                    getString(R.string.files_apk_body),
                )
            }
        }
    }

    /** Corre en un hilo de fondo. Usa Rust si está disponible y, si no, un SHA-256 real de Java. */
    private fun describeAndHash(uri: Uri): FileResult {
        var name = getString(R.string.files_unnamed)
        var size = -1L
        try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
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
                contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                    // detachFd cede la propiedad del fd a Rust, que lo cierra al terminar.
                    // El ParcelFileDescriptor original NO se cierra con `use`: Rust es dueño del fd.
                    SecurityBridge.sha256OfDescriptor(pfd.detachFd())
                }
            } catch (e: Exception) {
                null
            }
        }
        val viaRust = hash != null
        if (hash == null) hash = javaSha256(uri)
        return FileResult(name, size, hash, viaRust)
    }

    private fun javaSha256(uri: Uri): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun addFileRow(result: FileResult) {
        val row = ItemFileBinding.inflate(layoutInflater, b.filesContainer, false)
        row.tvFileName.text = result.name
        val hash = result.hash
        if (hash == null) {
            row.tvFileMeta.text = getString(R.string.files_error_meta)
            row.tvFileHash.text = getString(R.string.files_error_hash)
            row.btnFileCopy.isEnabled = false
            row.btnFileVt.isEnabled = false
        } else {
            val size = if (result.size >= 0) {
                Formatter.formatShortFileSize(this, result.size)
            } else {
                getString(R.string.files_size_unknown)
            }
            val engine = getString(if (result.viaRust) R.string.files_engine_rust else R.string.files_engine_java)
            row.tvFileMeta.text = getString(R.string.files_meta, size, engine)
            row.tvFileHash.text = hash
            row.btnFileCopy.setOnClickListener { copyToClipboard(hash, sensitive = false) }
            row.btnFileVt.setOnClickListener { openUrl("https://www.virustotal.com/gui/file/$hash") }
        }
        b.filesContainer.addView(row.root)
    }

    // ---------------------------------------------------------------------------------------------
    // BÓVEDA: generador de contraseñas y cifrado de texto (todo en el teléfono)
    // ---------------------------------------------------------------------------------------------

    private fun setupVault() {
        b.calloutVaultInfo.show(
            Status.INFO,
            getString(R.string.vault_info_title),
            getString(R.string.vault_info_body),
        )

        updateLengthLabel()
        b.sbLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLengthLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        b.btnGenerate.setOnClickListener { generatePassword() }
        b.btnCopyPassword.setOnClickListener {
            val password = b.tvPassword.text.toString()
            if (password.isEmpty()) {
                toast(R.string.gen_copy_empty)
            } else {
                copyToClipboard(password, sensitive = true)
            }
        }

        b.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setVaultMode(encrypt = checkedId == R.id.btnModeEncrypt)
        }
        b.btnRun.setOnClickListener { runVault() }
        b.btnClearVault.setOnClickListener { wipeVault() }
        b.btnCopyResult.setOnClickListener {
            val text = b.tvVaultResult.text.toString()
            if (text.isNotEmpty()) copyToClipboard(text, sensitive = !vaultEncrypt)
        }
    }

    private fun passwordLength(): Int = 12 + b.sbLength.progress * 4

    private fun updateLengthLabel() {
        b.tvLengthLabel.text = getString(R.string.gen_length, passwordLength())
    }

    private fun generatePassword() {
        val length = passwordLength()
        val password = SecurityBridge.newPassword(length)
        if (password == null) {
            showVaultError(R.string.error_engine_title, R.string.error_engine_body)
            return
        }
        b.calloutVaultError.hide()
        // El juego de caracteres tiene 88 símbolos: log2(88) ≈ 6,46 bits por carácter.
        val bits = (length * ln(CHARSET_SIZE) / ln(2.0)).roundToInt()
        b.tvPassword.text = password
        b.tvPassword.isVisible = true
        b.tvPasswordMeta.text = getString(R.string.gen_meta, bits)
        b.tvPasswordMeta.isVisible = true
    }

    private fun setVaultMode(encrypt: Boolean) {
        vaultEncrypt = encrypt
        b.tilData.hint = getString(if (encrypt) R.string.data_hint_encrypt else R.string.data_hint_decrypt)
        b.btnRun.setText(if (encrypt) R.string.run_encrypt else R.string.run_decrypt)
        // Se limpia para no dejar texto en claro en el campo de texto cifrado (ni al revés).
        b.etData.text?.clear()
        b.tilData.error = null
        b.tilKey.error = null
        b.calloutVaultError.hide()
        b.panelVaultResult.isVisible = false
        b.tvVaultResult.text = ""
    }

    private fun runVault() {
        val encrypt = vaultEncrypt
        val key = b.etKey.text?.toString().orEmpty()
        val data = b.etData.text?.toString().orEmpty()

        b.calloutVaultError.hide()
        b.panelVaultResult.isVisible = false
        b.tilKey.error = null
        b.tilData.error = null

        if (!SecurityBridge.isLoaded) {
            showVaultError(R.string.error_engine_title, R.string.error_engine_body)
            return
        }
        if (key.isEmpty()) {
            b.tilKey.error = getString(R.string.key_empty)
            return
        }
        if (encrypt && key.length < MIN_KEY_LENGTH) {
            b.tilKey.error = getString(R.string.key_too_short, MIN_KEY_LENGTH)
            return
        }
        if (data.isBlank()) {
            b.tilData.error = getString(R.string.data_empty)
            return
        }

        b.btnRun.isEnabled = false
        b.btnRun.setText(R.string.working)
        lifecycleScope.launch {
            // Argon2id usa 64 MiB y puede tardar un momento: nunca en el hilo principal.
            val output = withContext(Dispatchers.Default) {
                if (encrypt) SecurityBridge.encrypt(key, data) else SecurityBridge.decrypt(key, data)
            }
            b.btnRun.isEnabled = true
            b.btnRun.setText(if (encrypt) R.string.run_encrypt else R.string.run_decrypt)

            if (output == null) {
                // Los campos NO se borran: la persona no pierde lo que escribió.
                if (encrypt) {
                    showVaultError(R.string.error_encrypt_title, R.string.error_encrypt_body)
                } else {
                    showVaultError(R.string.error_decrypt_title, R.string.error_decrypt_body)
                }
            } else {
                b.tvVaultResultLabel.setText(if (encrypt) R.string.result_encrypted else R.string.result_decrypted)
                b.tvVaultResult.text = output
                b.panelVaultResult.isVisible = true
            }
        }
    }

    private fun showVaultError(@StringRes title: Int, @StringRes body: Int) {
        b.calloutVaultError.show(Status.BAD, getString(title), getString(body))
    }

    /** Borra de la pantalla todo lo sensible de la bóveda. */
    private fun wipeVault() {
        if (!::b.isInitialized) return
        b.etKey.text?.clear()
        b.etData.text?.clear()
        b.tilKey.error = null
        b.tilData.error = null
        b.tvPassword.text = ""
        b.tvPassword.isVisible = false
        b.tvPasswordMeta.isVisible = false
        b.tvVaultResult.text = ""
        b.panelVaultResult.isVisible = false
        b.calloutVaultError.hide()
    }

    // ---------------------------------------------------------------------------------------------
    // APPS: quién tiene cámara y micrófono realmente concedidos
    // ---------------------------------------------------------------------------------------------

    private fun setupApps() {
        b.calloutAppsInfo.show(
            Status.INFO,
            getString(R.string.apps_info_title),
            getString(R.string.apps_info_body),
        )
        b.btnRefreshApps.setOnClickListener { loadApps() }
    }

    private fun loadApps() {
        appsJob?.cancel()
        b.tvAppsEmpty.isVisible = false
        b.appsProgress.isVisible = b.appsContainer.childCount == 0
        appsJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryApps() }
            appsLoaded = true
            b.appsProgress.isVisible = false
            renderApps(apps)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

        val result = ArrayList<AppEntry>()
        for (info in packages) {
            val appInfo = info.applicationInfo ?: continue
            if (info.packageName == packageName) continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue
            // Se cuentan permisos CONCEDIDOS, no solo pedidos en el manifiesto.
            if (!isGranted(info, android.Manifest.permission.CAMERA)) continue
            if (!isGranted(info, android.Manifest.permission.RECORD_AUDIO)) continue
            result.add(AppEntry(info.packageName, appInfo.loadLabel(pm).toString(), appInfo.loadIcon(pm)))
        }
        result.sortBy { it.label.lowercase(Locale.getDefault()) }
        return result
    }

    private fun isGranted(info: PackageInfo, permission: String): Boolean {
        // En API 33+ la forma correcta es hasPermission(); los índices de
        // requestedPermissions y requestedPermissionsFlags no siempre coinciden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.hasPermission(this, permission)
        }
        val requested = info.requestedPermissions ?: return false
        val flags = info.requestedPermissionsFlags ?: return false
        val index = requested.indexOf(permission)
        return index >= 0 && index < flags.size &&
            (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun renderApps(apps: List<AppEntry>) {
        b.appsContainer.removeAllViews()
        b.panelApps.isVisible = apps.isNotEmpty()
        b.tvAppsEmpty.isVisible = apps.isEmpty()
        if (apps.isEmpty()) {
            b.tvAppsEmpty.text = getString(R.string.apps_empty)
            return
        }
        for (app in apps) {
            val row = ItemAppBinding.inflate(layoutInflater, b.appsContainer, false)
            row.ivAppIcon.setImageDrawable(app.icon)
            row.tvAppName.text = app.label
            row.tvAppPackage.text = app.pkg
            row.root.setOnClickListener { showAppDialog(app) }
            b.appsContainer.addView(row.root)
        }
    }

    private fun showAppDialog(app: AppEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setIcon(app.icon)
            .setMessage(getString(R.string.app_dialog_body, app.pkg))
            .setPositiveButton(R.string.app_open_settings) { _, _ -> openAppSettings(app.pkg) }
            .setNeutralButton(R.string.app_more_root) { _, _ -> onRootOptions(app) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun onRootOptions(app: AppEntry) {
        lifecycleScope.launch {
            if (!ensureRoot()) {
                toast(R.string.app_needs_root)
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.app_restrict_title, app.label))
                .setMessage(R.string.app_restrict_body)
                .setPositiveButton(R.string.app_restrict) { _, _ -> runBackgroundRule(app, restrict = true) }
                .setNeutralButton(R.string.app_unrestrict) { _, _ -> runBackgroundRule(app, restrict = false) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun runBackgroundRule(app: AppEntry, restrict: Boolean) {
        lifecycleScope.launch {
            val result = if (restrict) {
                PrivilegedEngine.restrictBackground(app.pkg)
            } else {
                PrivilegedEngine.allowBackground(app.pkg)
            }
            toast(
                when {
                    !result.ok -> R.string.app_rule_failed
                    restrict -> R.string.app_restricted
                    else -> R.string.app_unrestricted
                },
            )
        }
    }

    private fun openAppSettings(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_open_settings)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------------------

    private fun copyToClipboard(text: String, sensitive: Boolean) {
        ClipboardHelper.copy(this, text, sensitive)
        if (sensitive) {
            toast(R.string.copied_sensitive, ClipboardHelper.CLEAR_AFTER_SECONDS)
        } else {
            toast(R.string.copied)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_no_browser)
        }
    }

    private fun toast(@StringRes id: Int, vararg args: Any) {
        Toast.makeText(this, getString(id, *args), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val MAX_FILES = 10
        const val MIN_KEY_LENGTH = 8
        const val VAULT_WIPE_DELAY_MS = 30_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val CHARSET_SIZE = 88.0
    }
}
