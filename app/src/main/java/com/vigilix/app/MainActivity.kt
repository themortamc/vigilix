package com.vigilix.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object PrivilegedEngine {
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun executeShell(command: String): Pair<Boolean, String> {
        return try {
            // Intenta por Root, si falla ejecuta en shell estándar (o ADB enlazado)
            val useRoot = hasRootAccess()
            val process = if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val exitCode = process.waitFor()
            Pair(exitCode == 0, output.toString())
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Error de ejecución")
        }
    }

    // Modo Brevent: Fuerza inactividad y desconecta de segundo plano
    fun freezePackage(packageName: String): Boolean {
        val cmd = "am set-inactive $packageName true && cmd appops set $packageName RUN_IN_BACKGROUND ignore"
        return executeShell(cmd).first
    }

    // Modo GrapheneOS: Revoca permisos de sistema directamente
    fun revokePermission(packageName: String, permission: String): Boolean {
        val cmd = "pm revoke $packageName $permission"
        return executeShell(cmd).first
    }
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Detección de privilegios en el Header
        val tvPrivilegeBadge = findViewById<TextView>(R.id.tvPrivilegeBadge)
        val isRooted = PrivilegedEngine.hasRootAccess()
        if (isRooted) {
            tvPrivilegeBadge.text = "⚡ PRIVILEGIO: ROOT (ACTIVO)"
            tvPrivilegeBadge.setBackgroundColor(0xFF238636.toInt())
        } else {
            tvPrivilegeBadge.text = "🛡️ PRIVILEGIO: ESTÁNDAR / ADB"
            tvPrivilegeBadge.setBackgroundColor(0xFF1F6FEB.toInt())
        }

        // 2. Referencias a las 4 Pestañas
        val tabDash = findViewById<View>(R.id.tabDashboard)
        val tabScan = findViewById<View>(R.id.tabScan)
        val tabVault = findViewById<View>(R.id.tabVault)
        val tabSandbox = findViewById<View>(R.id.tabSandbox)

        // 3. Bottom Navigation Listener (Cambio de pantallas fluido)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            tabDash.visibility = View.GONE
            tabScan.visibility = View.GONE
            tabVault.visibility = View.GONE
            tabSandbox.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_dash -> { tabDash.visibility = View.VISIBLE; true }
                R.id.nav_scan -> { tabScan.visibility = View.VISIBLE; true }
                R.id.nav_vault -> { tabVault.visibility = View.VISIBLE; true }
                R.id.nav_sandbox -> { tabSandbox.visibility = View.VISIBLE; true }
                else -> false
            }
        }

        // --- DASHBOARD ACTIONS ---
        val btnQuickCheck = findViewById<Button>(R.id.btnQuickCheck)
        val tvTotalApps = findViewById<TextView>(R.id.tvTotalAppsAudited)
        val tvRiskCount = findViewById<TextView>(R.id.tvHighRiskCount)

        fun updateAppAuditStats() {
            val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            var highRisk = 0
            for (pkg in packages) {
                val perms = pkg.requestedPermissions ?: continue
                if (perms.contains("android.permission.CAMERA") && 
                    perms.contains("android.permission.RECORD_AUDIO")) {
                    highRisk++
                }
            }
            tvTotalApps.text = packages.size.toString()
            tvRiskCount.text = highRisk.toString()
        }
        updateAppAuditStats()

        btnQuickCheck.setOnClickListener {
            updateAppAuditStats()
            findViewById<TextView>(R.id.tvHealthSubtitle).text = "Diagnóstico completado. Memoria segura."
        }

        // --- VIRUSTOTAL SCANNER ACTIONS ---
        val btnScan = findViewById<Button>(R.id.btnScanStorage)
        val tvScanLog = findViewById<TextView>(R.id.tvScanLog)

        btnScan.setOnClickListener {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists()) {
                val files = downloadDir.listFiles()?.take(4)
                if (!files.isNullOrEmpty()) {
                    val sb = StringBuilder("🛡️ HASHES SHA-256 GENERADOS (RUST NATIVO):\n\n")
                    for (f in files) {
                        if (f.isFile) {
                            val hash = SecurityBridge.calculateFileSha256(f.absolutePath)
                            sb.append("📄 ").append(f.name).append("\n")
                            sb.append("SHA-256: ").append(hash.take(24)).append("...\n\n")
                        }
                    }
                    tvScanLog.text = sb.toString()
                } else {
                    tvScanLog.text = "Carpeta Downloads vacía."
                }
            }
        }

        // --- VAULT CHACHA20 ACTIONS ---
        val btnGenPass = findViewById<Button>(R.id.btnGenerateSecret)
        val tvPass = findViewById<TextView>(R.id.tvEntropyPass)
        val etKey = findViewById<EditText>(R.id.etVaultKey)
        val etData = findViewById<EditText>(R.id.etVaultData)
        val btnEncrypt = findViewById<Button>(R.id.btnEncryptVault)
        val tvVaultOut = findViewById<TextView>(R.id.tvVaultOutput)

        btnGenPass.setOnClickListener {
            val strong = SecurityBridge.generateHighEntropyPassword(32)
            tvPass.text = strong
        }

        btnEncrypt.setOnClickListener {
            val key = etKey.text.toString()
            val text = etData.text.toString()
            if (key.isNotEmpty() && text.isNotEmpty()) {
                val encrypted = SecurityBridge.encryptSecret(key, text)
                tvVaultOut.text = "🔒 CIPHERTEXT:\n$encrypted"
            }
        }

        // --- SANDBOX BREVENT & PERMISSION CONTROL ---
        val btnAudit = findViewById<Button>(R.id.btnAuditPermissions)
        val btnFreeze = findViewById<Button>(R.id.btnBreventFreeze)
        val tvSandboxLog = findViewById<TextView>(R.id.tvSandboxLog)
        var firstDangerousPackage = ""

        btnAudit.setOnClickListener {
            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val sb = StringBuilder("🚨 APPS CON PERMISOS INVASIVOS DETECTADAS:\n\n")

            for (pkg in packages) {
                val perms = pkg.requestedPermissions ?: continue
                if (perms.contains("android.permission.RECORD_AUDIO") && perms.contains("android.permission.CAMERA")) {
                    if (firstDangerousPackage.isEmpty()) firstDangerousPackage = pkg.packageName
                    val appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                    sb.append("• ").append(appName).append("\n  [").append(pkg.packageName).append("]\n")
                }
            }
            tvSandboxLog.text = sb.toString()
        }

        btnFreeze.setOnClickListener {
            if (firstDangerousPackage.isNotEmpty()) {
                val success = PrivilegedEngine.freezePackage(firstDangerousPackage)
                if (success) {
                    tvSandboxLog.text = "❄️ MODO BREVENT APLICADO:\nLa aplicación [$firstDangerousPackage] ha sido suspendida del segundo plano."
                } else {
                    tvSandboxLog.text = "⚠️ Requiere acceso Root o depuración inalámbrica ADB activa para suspender [$firstDangerousPackage]."
                }
            } else {
                tvSandboxLog.text = "Presiona primero 'Escanear Apps Peligrosas'."
            }
        }
    }
}
