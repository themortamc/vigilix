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
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.ActivityMainBinding
import com.vigilix.app.databinding.ItemAppBinding
import com.vigilix.app.databinding.ItemCheckBinding
import com.vigilix.app.databinding.ItemSensitiveAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pantalla principal con cinco secciones: Inicio (Dashboard), Escáner, Bóveda, Apps y Seguridad.
 */
class MainActivity : AppCompatActivity() {

    private enum class Tab(val menuId: Int) {
        HOME(R.id.nav_home),
        FILES(R.id.nav_files),
        VAULT(R.id.nav_vault),
        APPS(R.id.nav_apps),
        SECURITY(R.id.nav_security),
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        tab = savedInstanceState?.getInt(STATE_TAB)?.let { Tab.values().getOrNull(it) } ?: Tab.HOME

        setupHome()
        setupApps()
        setupSecurity()
        scanner = ScannerScreen(this, b.screenScanner).also { it.setup() }
        vault = VaultScreen(this, b.screenVault).also { it.setup() }

        b.bottomNav.selectedItemId = tab.menuId
        b.bottomNav.setOnItemSelectedListener { item ->
            val target = Tab.values().firstOrNull { it.menuId == item.itemId }
            if (target != null) {
                showTab(target)
                if (target == Tab.HOME) refreshHome()
                if (target == Tab.APPS && !appsLoaded) loadApps()
                if (target == Tab.FILES) scanner.refresh()
                if (target == Tab.VAULT) vault.refresh()
                if (target == Tab.SECURITY) refreshSecurity()
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
            Tab.APPS -> if (appsLoaded) loadApps()
            Tab.FILES -> scanner.refresh()
            Tab.VAULT -> vault.refresh()
            Tab.SECURITY -> refreshSecurity()
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
        b.scrollApps.isVisible = target == Tab.APPS
        b.screenSecurity.scrollSecurity.isVisible = target == Tab.SECURITY

        if (target == Tab.VAULT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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

        b.btnVaultCard.setOnClickListener { showTab(Tab.VAULT); b.bottomNav.selectedItemId = R.id.nav_vault }
        b.btnScanCard.setOnClickListener { showTab(Tab.FILES); b.bottomNav.selectedItemId = R.id.nav_files }
        b.btnQuickGenerator.setOnClickListener { showTab(Tab.VAULT); b.bottomNav.selectedItemId = R.id.nav_vault }
        b.btnQuickEncrypt.setOnClickListener { showTab(Tab.VAULT); b.bottomNav.selectedItemId = R.id.nav_vault }
        b.btnQuickFileCheck.setOnClickListener { showTab(Tab.FILES); b.bottomNav.selectedItemId = R.id.nav_files }

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
    // APPS
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
            if (!isGranted(info, android.Manifest.permission.CAMERA)) continue
            if (!isGranted(info, android.Manifest.permission.RECORD_AUDIO)) continue
            result.add(AppEntry(info.packageName, appInfo.loadLabel(pm).toString(), appInfo.loadIcon(pm)))
        }
        result.sortBy { it.label.lowercase(Locale.getDefault()) }
        return result
    }

    private fun isGranted(info: PackageInfo, permission: String): Boolean {
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

    // ---------------------------------------------------------------------------------------------
    // SEGURIDAD
    // ---------------------------------------------------------------------------------------------

    private fun setupSecurity() {
        val sec = b.screenSecurity
        sec.btnRefreshPerms.setOnClickListener { loadSensitiveApps() }
        sec.btnLockApp.setOnClickListener { runLockApp(restrict = true) }
        sec.btnUnlockApp.setOnClickListener { runLockApp(restrict = false) }
        sec.btnClearHistory.setOnClickListener { clearHistory() }
    }

    private fun refreshSecurity() {
        loadSensitiveApps()
        refreshHistory()
    }

    private fun loadSensitiveApps() {
        val sec = b.screenSecurity
        sec.progressPerms.isVisible = true
        sec.tvPermsEmpty.isVisible = false
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { PermissionMonitor.findSensitiveApps(applicationContext) }
            sec.progressPerms.isVisible = false
            renderSensitiveApps(apps)
        }
    }

    private fun renderSensitiveApps(apps: List<PermissionMonitor.SensitiveApp>) {
        val sec = b.screenSecurity
        sec.permsContainer.removeAllViews()
        sec.tvPermsEmpty.isVisible = apps.isEmpty()
        if (apps.isEmpty()) {
            sec.tvPermsEmpty.setText(R.string.sec_perms_empty)
            return
        }
        for (app in apps) {
            val row = ItemSensitiveAppBinding.inflate(layoutInflater, sec.permsContainer, false)
            row.tvAppLabel.text = app.label
            row.tvAppPerms.text = app.granted.joinToString(" · ")
            row.root.setOnClickListener { openAppSettings(app.packageName) }
            sec.permsContainer.addView(row.root)
        }
    }

    private fun runLockApp(restrict: Boolean) {
        val sec = b.screenSecurity
        val pkg = sec.etLockApp.text?.toString().orEmpty().trim()
        if (!PrivilegedEngine.isValidPackage(pkg)) {
            sec.tvLockStatus.setText(R.string.sec_lock_invalid)
            return
        }
        lifecycleScope.launch {
            if (!ensureRoot()) {
                sec.tvLockStatus.setText(R.string.app_needs_root)
                return@launch
            }
            sec.tvLockStatus.setText(R.string.sec_lock_working)
            val result = if (restrict) PrivilegedEngine.restrictBackground(pkg) else PrivilegedEngine.allowBackground(pkg)
            sec.tvLockStatus.setText(
                if (result.ok) {
                    if (restrict) R.string.sec_lock_done else R.string.sec_unlock_done
                } else {
                    R.string.sec_lock_failed
                },
            )
        }
    }

    private fun refreshHistory() {
        val sec = b.screenSecurity
        val entries = HistoryManager.recentEntries(this)
        sec.historyList.removeAllViews()
        sec.btnClearHistory.isVisible = entries.isNotEmpty()

        for (entry in entries.reversed()) {
            val row = ItemCheckBinding.inflate(layoutInflater, sec.historyList, false)
            row.ivCheck.setStatus(if (entry.findings == 0) Status.OK else Status.WARN)
            row.tvCheckTitle.text = getString(R.string.sec_hist_title, entry.mode, timeAgo(entry.timestamp))
            row.tvCheckDetail.text = getString(R.string.sec_hist_detail, entry.files, entry.findings, entry.vtUsed)
            sec.historyList.addView(row.root)
        }
    }

    private fun clearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sec_history_clear_title)
            .setMessage(R.string.sec_history_clear_body)
            .setPositiveButton(R.string.sec_history_clear) { _, _ ->
                HistoryManager.clear(this)
                refreshHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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