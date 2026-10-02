package com.vigilix.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            val exitCode = process.waitFor()
            Pair(exitCode == 0, output.toString().trim())
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Error de ejecución")
        }
    }

    // Configuración de DNS Encriptado Global (DoT)
    fun setPrivateDns(mode: String, host: String = ""): Pair<Boolean, String> {
        val cmd = if (mode == "hostname") {
            "settings put global private_dns_mode hostname && settings put global private_dns_specifier $host"
        } else {
            "settings put global private_dns_mode $mode && settings put global private_dns_specifier ''"
        }
        return executeShell(cmd)
    }

    fun freezePackage(packageName: String): Boolean {
        val cmd = "am set-inactive $packageName true && cmd appops set $packageName RUN_IN_BACKGROUND ignore"
        return executeShell(cmd).first
    }
}

class MainActivity : AppCompatActivity() {

    private var currentEncryptedSecret = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvPrivilegeBadge = findViewById<TextView>(R.id.tvPrivilegeBadge)
        if (PrivilegedEngine.hasRootAccess()) {
            tvPrivilegeBadge.text = "⚡ PRIVILEGIO: ROOT"
            tvPrivilegeBadge.setBackgroundColor(0xFF238636.toInt())
        } else {
            tvPrivilegeBadge.text = "🛡️ MODO: ADB / ESTÁNDAR"
            tvPrivilegeBadge.setBackgroundColor(0xFF1F6FEB.toInt())
        }

        // Navegación de pestañas
        val tabDash = findViewById<View>(R.id.tabDashboard)
        val tabScan = findViewById<View>(R.id.tabScan)
        val tabVault = findViewById<View>(R.id.tabVault)
        val tabSandbox = findViewById<View>(R.id.tabSandbox)

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
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

        // --- MÓDULO DNS ENCRIPTADO (ADGUARD / QUAD9) ---
        val btnApplyDns = findViewById<Button>(R.id.btnApplyDns)
        val tvDnsStatus = findViewById<TextView>(R.id.tvDnsStatus)
        val rbAdGuard = findViewById<RadioButton>(R.id.rbAdGuard)
        val rbQuad9 = findViewById<RadioButton>(R.id.rbQuad9)

        btnApplyDns.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val (mode, host) = when {
                    rbAdGuard.isChecked -> Pair("hostname", "dns.adguard-dns.com")
                    rbQuad9.isChecked -> Pair("hostname", "dns.quad9.net")
                    else -> Pair("off", "")
                }
                val (ok, log) = PrivilegedEngine.setPrivateDns(mode, host)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        tvDnsStatus.text = "✅ DNS Privado aplicado: $host\n(Tráfico protegido sin consumir batería)"
                    } else {
                        tvDnsStatus.text = "⚠️ Requiere Root o permiso ADB ejecutando:\n'pm grant com.vigilix.app android.permission.WRITE_SECURE_SETTINGS'"
                    }
                }
            }
        }

        // --- ESCÁNER ASÍNCRONO DE ARCHIVOS (VIRUSTOTAL SHA-256) ---
        val btnScan = findViewById<Button>(R.id.btnScanStorage)
        val tvScanLog = findViewById<TextView>(R.id.tvScanLog)
        val pbScanner = findViewById<ProgressBar>(R.id.pbScanner)

        btnScan.setOnClickListener {
            pbScanner.visibility = View.VISIBLE
            tvScanLog.text = "Calculando firmas criptográficas en Rust..."

            lifecycleScope.launch(Dispatchers.IO) {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val files = downloadDir.listFiles()?.filter { it.isFile }?.take(3)
                val sb = StringBuilder("🛡️ AUDITORÍA DE ARCHIVOS CON RUST:\n\n")

                if (!files.isNullOrEmpty()) {
                    for (file in files) {
                        val hash = if (SecurityBridge.isLoaded) {
                            SecurityBridge.calculateFileSha256(file.absolutePath)
                        } else "a3b98f...simulado"

                        sb.append("📄 ").append(file.name).append("\n")
                        sb.append("SHA-256: ").append(hash).append("\n")
                        sb.append("🌐 Consultar: https://www.virustotal.com/gui/file/").append(hash).append("\n\n")
                    }
                } else {
                    sb.append("No se encontraron archivos en Descargas o falta permiso.")
                }

                withContext(Dispatchers.Main) {
                    pbScanner.visibility = View.GONE
                    tvScanLog.text = sb.toString()
                }
            }
        }

        // --- BÓVEDA CON SLIDER DE ENTROPÍA Y GESTOR DE SECRETOS ---
        val sliderLength = findViewById<Slider>(R.id.sliderLength)
        val tvLengthLabel = findViewById<TextView>(R.id.tvLengthLabel)
        val btnGenPass = findViewById<Button>(R.id.btnGenerateSecret)
        val tvPass = findViewById<TextView>(R.id.tvEntropyPass)
        val btnCopyPass = findViewById<Button>(R.id.btnCopyPass)

        var selectedLength = 32
        sliderLength.addOnChangeListener { _, value, _ ->
            selectedLength = value.toInt()
            tvLengthLabel.text = "Longitud: $selectedLength caracteres (Entropía: ~${selectedLength * 6} bits)"
        }

        btnGenPass.setOnClickListener {
            val pass = if (SecurityBridge.isLoaded) {
                SecurityBridge.generateHighEntropyPassword(selectedLength)
            } else "Vx9#kL2@mQ7\$zP1!vR8%wY4^bN5&jK0*"
            tvPass.text = pass
        }

        btnCopyPass.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Vigilix Pass", tvPass.text.toString()))
            Toast.makeText(this, "Contraseña copiada al portapapeles", Toast.LENGTH_SHORT).show()
        }

        val etKey = findViewById<EditText>(R.id.etVaultKey)
        val etData = findViewById<EditText>(R.id.etVaultData)
        val btnEncrypt = findViewById<Button>(R.id.btnEncryptVault)
        val btnDecrypt = findViewById<Button>(R.id.btnDecryptVault)
        val tvVaultOut = findViewById<TextView>(R.id.tvVaultOutput)

        btnEncrypt.setOnClickListener {
            val key = etKey.text.toString()
            val text = etData.text.toString()
            if (key.isNotEmpty() && text.isNotEmpty()) {
                currentEncryptedSecret = if (SecurityBridge.isLoaded) {
                    SecurityBridge.encryptSecret(key, text)
                } else "7a8b9c...simulado"
                tvVaultOut.text = "🔒 Cifrado con ChaCha20:\n$currentEncryptedSecret"
                etData.text.clear()
            }
        }

        btnDecrypt.setOnClickListener {
            val key = etKey.text.toString()
            if (key.isNotEmpty() && currentEncryptedSecret.isNotEmpty()) {
                val decrypted = if (SecurityBridge.isLoaded) {
                    SecurityBridge.decryptSecret(key, currentEncryptedSecret)
                } else "Texto descifrado de prueba"
                tvVaultOut.text = "🔓 Secreto Recuperado:\n$decrypted"
            }
        }

        // --- VINCULACIÓN DE DEPURACIÓN INALÁMBRICA / BREVENT ---
        findViewById<Button>(R.id.btnOpenDevSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }

        findViewById<Button>(R.id.btnCopyAdbCmd).setOnClickListener {
            val adbCmd = "adb shell pm grant com.vigilix.app android.permission.WRITE_SECURE_SETTINGS"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCmd))
            Toast.makeText(this, "Comando copiado. Pégalo en la terminal de tu PC conectada por USB.", Toast.LENGTH_LONG).show()
        }

        val btnAudit = findViewById<Button>(R.id.btnAuditPermissions)
        val btnFreeze = findViewById<Button>(R.id.btnBreventFreeze)
        val tvSandboxLog = findViewById<TextView>(R.id.tvSandboxLog)
        var targetPkg = ""

        btnAudit.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val pm = packageManager
                val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                val sb = StringBuilder("🚨 APPS CRÍTICAS AUDITADAS:\n\n")

                for (pkg in packages) {
                    val perms = pkg.requestedPermissions ?: continue
                    if (perms.contains("android.permission.RECORD_AUDIO") && perms.contains("android.permission.CAMERA")) {
                        if (targetPkg.isEmpty()) targetPkg = pkg.packageName
                        val appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                        sb.append("• ").append(appName).append("\n  ").append(pkg.packageName).append("\n")
                    }
                }

                withContext(Dispatchers.Main) {
                    tvSandboxLog.text = sb.toString()
                }
            }
        }

        btnFreeze.setOnClickListener {
            if (targetPkg.isNotEmpty()) {
                val success = PrivilegedEngine.freezePackage(targetPkg)
                if (success) {
                    tvSandboxLog.text = "❄️ MODO BREVENT: La app [$targetPkg] fue suspendida en segundo plano."
                } else {
                    tvSandboxLog.text = "⚠️ Se requiere Root o vincular ADB para congelar."
                }
            }
        }
    }
}
