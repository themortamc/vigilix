package com.vigilix.app

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.ActivityMainBinding
import com.vigilix.app.databinding.DialogToolsBinding
import com.vigilix.app.databinding.ItemAppBinding
import com.vigilix.app.databinding.ItemCheckBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pantalla principal de Vigilix (IA v2.0):
 * 4 Pestañas:
 * 1. 🏠 Inicio (Dashboard, Salud, Accesos Rápidos, DNS)
 * 2. 🛡️ Escáner (Escaneo de Teléfono, Archivo Suelto, Historial, VT/DB)
 * 3. 🔐 Bóveda (Gestor de Contraseñas, Autofill, Backup)
 * 4. 📱 Privacidad (Inspector de Permisos por Riesgo, Restricción Root)
 * + 🔧 Panel Modal de Herramientas Cripto desde la barra superior.
 */
class MainActivity : AppCompatActivity() {

    private enum class Tab(val menuId: Int) {
        HOME(R.id.nav_home),
        FILES(R.id.nav_files),
        VAULT(R.id.nav_vault),
        PRIVACY(R.id.nav_privacy),
    }

    private class Check(
        val status: Status,
        val title: String,
        val detail: String,
        val counted: Boolean = true,
    )

    private lateinit var b: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wipeScreenTask = Runnable { vault.wipeScreen() }

    private lateinit var scanner: ScannerScreen
    private lateinit var vault: VaultScreen

    private var tab = Tab.HOME
    private var rootState: Boolean? = null
    private var privacyAppsLoaded = false
    private var privacyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        tab = savedInstanceState?.getInt(STATE_TAB)?.let { Tab.values().getOrNull(it) } ?: Tab.HOME

        setupHeader()
        setupHome()
        setupPrivacy()
        scanner = ScannerScreen(this, b.screenScanner).also { it.setup() }
        vault = VaultScreen(this, b.screenVault).also { it.setup() }

        b.bottomNav.selectedItemId = tab.menuId
        b.bottomNav.setOnItemSelectedListener { item ->
            val target = Tab.values().firstOrNull { it.menuId == item.itemId }
            if (target != null) {
                showTab(target)
                if (target == Tab.HOME) refreshHome()
                if (target == Tab.FILES) scanner.refresh()
                if (target == Tab.VAULT) vault.refresh()
                if (target == Tab.PRIVACY) loadPrivacyApps()
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
        mainHandler.removeCallbacks(wipeScreenTask)
    }

    override fun onResume() {
        super.onResume()
        when (tab) {
            Tab.HOME -> refreshHome()
            Tab.FILES -> scanner.refresh()
            Tab.VAULT -> vault.refresh()
            Tab.PRIVACY -> if (privacyAppsLoaded) loadPrivacyApps()
        }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.postDelayed(wipeScreenTask, VAULT_WIPE_DELAY_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(wipeScreenTask)
        if (isFinishing) vault.wipeScreen()
        super.onDestroy()
    }

    private fun showTab(target: Tab) {
        tab = target
        b.scrollHome.isVisible = target == Tab.HOME
        b.scrollFiles.isVisible = target == Tab.FILES
        b.scrollVault.isVisible = target == Tab.VAULT
        b.screenPrivacy.scrollPrivacy.isVisible = target == Tab.PRIVACY

        if (target == Tab.VAULT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setupHeader() {
        b.btnTopTools.setOnClickListener { showToolsDialog() }
    }

    // ---------------------------------------------------------------------------------------------
    // DASHBOARD / INICIO
    // ---------------------------------------------------------------------------------------------

    private fun setupHome() {
        b.tvAdbCommand.text = DnsController.adbCommand(this)
        b.btnCopyAdb.setOnClickListener { copyToClipboard(DnsController.adbCommand(this), sensitive = false) }
        b.btnDnsAdguard.setOnClickListener { onDnsClicked(DnsProvider.ADGUARD) }
        b.btnDnsQuad9.setOnClickListener { onDnsClicked(DnsProvider.QUAD9) }
        b.btnDnsAuto.setOnClickListener { onDnsClicked(DnsProvider.AUTOMATIC) }

        // Botones de acceso rápido
        b.btnQuickScan.setOnClickListener { showTab(Tab.FILES); b.bottomNav.selectedItemId = R.id.nav_files }
        b.btnQuickVault.setOnClickListener { showTab(Tab.VAULT); b.bottomNav.selectedItemId = R.id.nav_vault }
        b.btnQuickDns.setOnClickListener {
            b.scrollHome.smoothScrollTo(0, b.panelDns.top)
        }
        b.btnQuickTools.setOnClickListener { showToolsDialog() }

        b.btnVaultCard.setOnClickListener { showTab(Tab.VAULT); b.bottomNav.selectedItemId = R.id.nav_vault }
        b.btnScanCard.setOnClickListener { showTab(Tab.FILES); b.bottomNav.selectedItemId = R.id.nav_files }

        updateAccessChip()
    }

    private fun refreshHome() {
        refreshVaultCard()
        refreshScanCard()
        refreshChecks()
    }

    private fun refreshVaultCard() {
        val exists = VaultStore.exists(this)
        val unlocked = SecurityBridge.isLoaded && VaultStore.isUnlocked
        b.tvVaultCard.text = when {
            !SecurityBridge.isLoaded -> getString(R.string.dash_vault_engine_off)
            !exists -> getString(R.string.dash_vault_empty)
            unlocked -> getString(R.string.dash_vault_open_count, VaultStore.count())
            else -> getString(R.string.dash_vault_locked)
        }
        b.btnVaultCard.text = if (unlocked) getString(R.string.dash_vault_go) else getString(R.string.dash_vault_unlock)
    }

    private fun refreshScanCard() {
        val lastAt = Prefs.lastScanAt(this)
        b.tvScanCard.text = if (lastAt == 0L) {
            getString(R.string.dash_scan_never)
        } else {
            val files = Prefs.lastScanFiles(this)
            val findings = Prefs.lastScanFindings(this)
            val ago = timeAgo(lastAt)
            getString(R.string.dash_scan_last, ago, files, findings)
        }
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
    // PRIVACIDAD & INSPECTOR DE APPS
    // ---------------------------------------------------------------------------------------------

    private fun setupPrivacy() {
        val p = b.screenPrivacy
        p.chipGroupFilters.setOnCheckedStateChangeListener { _, _ -> loadPrivacyApps() }
        p.btnRestrictApp.setOnClickListener { runRestrictApp(restrict = true) }
        p.btnUnrestrictApp.setOnClickListener { runRestrictApp(restrict = false) }
    }

    private fun loadPrivacyApps() {
        privacyJob?.cancel()
        val p = b.screenPrivacy
        p.tvPrivacyEmpty.isVisible = false
        p.progressPrivacy.isVisible = p.privacyAppsContainer.childCount == 0

        privacyJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { PermissionMonitor.findSensitiveApps(applicationContext) }
            privacyAppsLoaded = true
            p.progressPrivacy.isVisible = false
            renderPrivacyApps(apps)
        }
    }

    private fun renderPrivacyApps(apps: List<PermissionMonitor.SensitiveApp>) {
        val p = b.screenPrivacy
        p.privacyAppsContainer.removeAllViews()

        // Filtrar según chip seleccionado
        val filtered = when (p.chipGroupFilters.checkedChipId) {
            R.id.chipFilterCameraMic -> apps.filter { it.granted.contains("Cámara") || it.granted.contains("Micrófono") }
            R.id.chipFilterLocation -> apps.filter { it.granted.contains("Ubicación") }
            R.id.chipFilterSmsContacts -> apps.filter { it.granted.contains("SMS") || it.granted.contains("Contactos") }
            else -> apps
        }

        p.tvPrivacyEmpty.isVisible = filtered.isEmpty()
        if (filtered.isEmpty()) return

        for (app in filtered) {
            val row = ItemAppBinding.inflate(layoutInflater, p.privacyAppsContainer, false)
            val icon = try {
                packageManager.getApplicationIcon(app.packageName)
            } catch (e: Exception) {
                ContextCompat.getDrawable(this, R.drawable.ic_shield)
            }
            row.ivAppIcon.setImageDrawable(icon)
            row.tvAppName.text = app.label
            row.tvAppPackage.text = app.granted.joinToString(" · ")
            row.root.setOnClickListener { showPrivacyAppDialog(app) }
            p.privacyAppsContainer.addView(row.root)
        }
    }

    private fun showPrivacyAppDialog(app: PermissionMonitor.SensitiveApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setMessage("${app.packageName}\n\nPermisos concedidos: ${app.granted.joinToString(", ")}")
            .setPositiveButton(R.string.privacy_app_settings) { _, _ -> openAppSettings(app.packageName) }
            .setNeutralButton(R.string.privacy_app_more_root) { _, _ ->
                lifecycleScope.launch {
                    if (!ensureRoot()) {
                        toast(R.string.privacy_app_needs_root)
                        return@launch
                    }
                    b.screenPrivacy.etRestrictApp.setText(app.packageName)
                    b.screenPrivacy.scrollPrivacy.smoothScrollTo(0, b.screenPrivacy.tilRestrictApp.top)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun runRestrictApp(restrict: Boolean) {
        val p = b.screenPrivacy
        val pkg = p.etRestrictApp.text?.toString().orEmpty().trim()
        if (!PrivilegedEngine.isValidPackage(pkg)) {
            p.tvRestrictStatus.setText(R.string.privacy_restrict_invalid)
            return
        }
        lifecycleScope.launch {
            if (!ensureRoot()) {
                p.tvRestrictStatus.setText(R.string.privacy_app_needs_root)
                return@launch
            }
            p.tvRestrictStatus.setText(R.string.privacy_restrict_working)
            val result = if (restrict) PrivilegedEngine.restrictBackground(pkg) else PrivilegedEngine.allowBackground(pkg)
            p.tvRestrictStatus.setText(
                if (result.ok) {
                    if (restrict) R.string.privacy_restrict_done else R.string.privacy_unrestrict_done
                } else {
                    R.string.privacy_restrict_failed
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MODAL DE HERRAMIENTAS CRIPTO (🔧)
    // ---------------------------------------------------------------------------------------------

    private var toolEncrypt = true

    private fun showToolsDialog() {
        val d = DialogToolsBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tools_title)
            .setView(d.root)
            .setNegativeButton(R.string.tools_close, null)
            .create()

        // Setup Generador
        fun passwordLength() = 12 + d.sbToolLength.progress * 4
        fun updateLengthLabel() {
            d.tvToolLengthLabel.text = getString(R.string.gen_length, passwordLength())
        }
        updateLengthLabel()
        d.sbToolLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLengthLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        d.btnToolGenerate.setOnClickListener {
            val length = passwordLength()
            val pass = SecurityBridge.newPassword(length)
            if (pass != null) {
                d.tvToolPassword.text = pass
                d.tvToolPassword.isVisible = true
                val bits = (length * ln(88.0) / ln(2.0)).roundToInt()
                d.tvToolPasswordMeta.text = getString(R.string.gen_meta, bits)
                d.tvToolPasswordMeta.isVisible = true
            }
        }
        d.btnToolCopyPassword.setOnClickListener {
            val pass = d.tvToolPassword.text.toString()
            if (pass.isNotEmpty()) copyToClipboard(pass, sensitive = true)
        }

        // Setup Cifrador
        d.toggleToolCryptMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toolEncrypt = checkedId == R.id.btnToolModeEncrypt
                d.tilToolData.hint = getString(if (toolEncrypt) R.string.data_hint_encrypt else R.string.data_hint_decrypt)
                d.btnToolRunCrypt.setText(if (toolEncrypt) R.string.run_encrypt else R.string.run_decrypt)
            }
        }

        d.btnToolRunCrypt.setOnClickListener {
            val key = d.etToolKey.text?.toString().orEmpty()
            val data = d.etToolData.text?.toString().orEmpty()
            d.tilToolKey.error = null
            d.tilToolData.error = null

            if (key.isEmpty()) {
                d.tilToolKey.error = getString(R.string.key_empty)
                return@setOnClickListener
            }
            if (data.isBlank()) {
                d.tilToolData.error = getString(R.string.data_empty)
                return@setOnClickListener
            }

            d.btnToolRunCrypt.isEnabled = false
            d.btnToolRunCrypt.setText(R.string.working)

            lifecycleScope.launch {
                val out = withContext(Dispatchers.Default) {
                    if (toolEncrypt) SecurityBridge.encrypt(key, data) else SecurityBridge.decrypt(key, data)
                }
                d.btnToolRunCrypt.isEnabled = true
                d.btnToolRunCrypt.setText(if (toolEncrypt) R.string.run_encrypt else R.string.run_decrypt)

                if (out != null) {
                    d.tvToolCryptResult.text = out
                    d.tvToolCryptResult.isVisible = true
                    d.btnToolCopyResult.isVisible = true
                } else {
                    toast(if (toolEncrypt) R.string.error_encrypt_title else R.string.error_decrypt_title)
                }
            }
        }

        d.btnToolCopyResult.setOnClickListener {
            val res = d.tvToolCryptResult.text.toString()
            if (res.isNotEmpty()) copyToClipboard(res, sensitive = !toolEncrypt)
        }

        dialog.show()
    }

    private fun timeAgo(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L
        return when {
            minutes < 1 -> "hace unos segundos"
            minutes < 60 -> "hace $minutes min"
            hours < 24 -> "hace $hours h"
            else -> "hace $days d"
        }
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val VAULT_WIPE_DELAY_MS = 30_000L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}