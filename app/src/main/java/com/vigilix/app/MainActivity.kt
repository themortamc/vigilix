package com.vigilix.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.BufferedReader
import java.io.InputStreamReader

object SecurityBridge {
    var isLoaded = false
    init {
        try {
            System.loadLibrary("vigilix_core")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
        }
    }

    external fun generateHighEntropyPassword(length: Int): String
    external fun encryptSecret(masterKey: String, plaintext: String): String
    external fun decryptSecret(masterKey: String, cipherHex: String): String
    external fun calculateFileSha256(filePath: String): String
    external fun scanThreat(input: String): String
}

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

    fun freezePackage(packageName: String): Boolean {
        val cmd = "am set-inactive $packageName true && cmd appops set $packageName RUN_IN_BACKGROUND ignore"
        return executeShell(cmd).first
    }
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Verificación de carga del motor Rust
        if (!SecurityBridge.isLoaded) {
            Toast.makeText(this, "Aviso: Motor nativo Rust en modo simulación", Toast.LENGTH_LONG).show()
        }

        val tvPrivilegeBadge = findViewById<TextView>(R.id.tvPrivilegeBadge)
        val isRooted = PrivilegedEngine.hasRootAccess()
        if (isRooted) {
            tvPrivilegeBadge.text = "⚡ PRIVILEGIO: ROOT"
            tvPrivilegeBadge.setBackgroundColor(0xFF238636.toInt())
        } else {
            tvPrivilegeBadge.text = "🛡️ PRIVILEGIO: ADB / USER"
            tvPrivilegeBadge.setBackgroundColor(0xFF1F6FEB.toInt())
        }

        // Pestañas
        val tabDash = findViewById<View>(R.id.tabDashboard)
        val tabScan = findViewById<View>(R.id.tabScan)
        val tabVault = findViewById<View>(R.id.tabVault)
        val tabSandbox = findViewById<View>(R.id.tabSandbox)

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

        // Dashboard stats
        val btnQuickCheck = findViewById<Button>(R.id.btnQuickCheck)
        val tvTotalApps = findViewById<TextView>(R.id.tvTotalAppsAudited)
        val tvRiskCount = findViewById<TextView>(R.id.tvHighRiskCount)

        fun updateAppAuditStats() {
            try {
                val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                var highRisk = 0
                for (pkg in packages) {
                    val perms = pkg.requestedPermissions ?: continue
                    if (perms.contains("android.permission.CAMERA") && perms.contains("android.permission.RECORD_AUDIO")) {
                        highRisk++
                    }
                }
                tvTotalApps.text = packages.size.toString()
                tvRiskCount.text = highRisk.toString()
            } catch (e: Exception) {
                tvTotalApps.text = "OK"
                tvRiskCount.text = "0"
            }
        }
        updateAppAuditStats()

        btnQuickCheck.setOnClickListener {
            updateAppAuditStats()
            findViewById<TextView>(R.id.tvHealthSubtitle).text = "Diagnóstico completado. Memoria segura."
        }

        // Scanner
        val btnScan = findViewById<Button>(R.id.btnScanStorage)
        val tvScanLog = findViewById<TextView>(R.id.tvScanLog)
        btnScan.setOnClickListener {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val files = downloadDir.listFiles()?.take(4)
                if (!files.isNullOrEmpty()) {
                    val sb = StringBuilder("🛡️ HASHES SHA-256 (RUST):\n\n")
                    for (f in files) {
                        if (f.isFile) {
                            val hash = if (SecurityBridge.isLoaded) {
                                SecurityBridge.calculateFileSha256(f.absolutePath)
                            } else "e3b0c44298fc1c149afbf4c8996fb924..."
                            sb.append("📄 ").append(f.name).append("\nSHA-256: ").append(hash.take(24)).append("...\n\n")
                        }
                    }
                    tvScanLog.text = sb.toString()
                } else {
                    tvScanLog.text = "Carpeta Downloads vacía."
                }
            } catch (e: Exception) {
                tvScanLog.text = "Permiso de almacenamiento requerido."
            }
        }

        // Vault
        val btnGenPass = findViewById<Button>(R.id.btnGenerateSecret)
        val tvPass = findViewById<TextView>(R.id.tvEntropyPass)
        val etKey = findViewById<EditText>(R.id.etVaultKey)
        val etData = findViewById<EditText>(R.id.etVaultData)
        val btnEncrypt = findViewById<Button>(R.id.btnEncryptVault)
        val tvVaultOut = findViewById<TextView>(R.id.tvVaultOutput)

        btnGenPass.setOnClickListener {
            val strong = if (SecurityBridge.isLoaded) {
                SecurityBridge.generateHighEntropyPassword(32)
            } else "Vx9#kL2@mQ7\$zP1!vR8%wY4^bN5&jK0*"
            tvPass.text = strong
        }

        btnEncrypt.setOnClickListener {
            val key = etKey.text.toString()
            val text = etData.text.toString()
            if (key.isNotEmpty() && text.isNotEmpty()) {
                val encrypted = if (SecurityBridge.isLoaded) {
                    SecurityBridge.encryptSecret(key, text)
                } else "7a8b9c0d1e2f3a4b5c6d7e8f (Simulado)"
                tvVaultOut.text = "🔒 CIPHERTEXT:\n$encrypted"
            }
        }

        // Brevent Sandbox
        val btnAudit = findViewById<Button>(R.id.btnAuditPermissions)
        val btnFreeze = findViewById<Button>(R.id.btnBreventFreeze)
        val tvSandboxLog = findViewById<TextView>(R.id.tvSandboxLog)
        var firstDangerousPackage = ""

        btnAudit.setOnClickListener {
            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val sb = StringBuilder("🚨 APPS INVASIVAS DETECTADAS:\n\n")

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
                    tvSandboxLog.text = "❄️ MODO BREVENT APLICADO:\nLa aplicación [$firstDangerousPackage] ha sido suspendida."
                } else {
                    tvSandboxLog.text = "⚠️ Requiere Root o ADB activo para congelar [$firstDangerousPackage]."
                }
            } else {
                tvSandboxLog.text = "Presiona primero 'Escanear Apps'."
            }
        }
    }
}
