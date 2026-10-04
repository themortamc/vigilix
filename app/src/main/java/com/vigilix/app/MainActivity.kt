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

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var b: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wipeScreenTask = Runnable { vault.wipeScreen() }

    private lateinit var scanner: ScannerScreen
    private lateinit var vault: VaultScreen

    private var tab = Tab.HOME
    private var rootState: Boolean? = null
    private var appsLoaded = false
    private var appsJob: Job? = null

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        tab = savedInstanceState?.getInt(STATE_TAB)?.let { Tab.values().getOrNull(it) } ?: Tab.HOME

        setupHome()
        setupApps()
        scanner = ScannerScreen(this, b.screenScanner).also { it.setup() }
        vault = VaultScreen(this, b.screenVault).also { it.setup() }

        b.bottomNav.selectedItemId = tab.menuId
        b.bottomNav.setOnItemSelectedListener { item ->
            val target = Tab.values().firstOrNull { it.menuId == item.itemId }
            if (target != null) {
                showTab(target)
                if (target == Tab.HOME) refreshChecks()
                if (target == Tab.APPS && !appsLoaded) loadApps()
                if (target == Tab.FILES) scanner.refresh()
                if (target == Tab.VAULT) vault.refresh()
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
        // La persona pudo cambiar ajustes o permisos en otra pantalla.
        when (tab) {
            Tab.HOME -> refreshChecks()
            Tab.APPS -> if (appsLoaded) loadApps()
            Tab.FILES -> scanner.refresh()
            Tab.VAULT -> vault.refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        // Si la app queda en segundo plano, los datos sensibles se borran de la pantalla tras un rato.
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
        // requestedPermissions y requestedPermissionsFlags son arreglos paralelos (mismo índice).
        // Se mira el permiso de la app AJENA: checkSelfPermission() solo sirve para la propia.
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

    private companion object {
        const val STATE_TAB = "tab"
        const val VAULT_WIPE_DELAY_MS = 30_000L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
