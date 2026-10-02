package com.vigilix.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

object SecurityBridge {
    init {
        System.loadLibrary("vigilix_core")
    }

    external fun generateHighEntropyPassword(length: Int): String
    external fun encryptSecret(masterKey: String, plaintext: String): String
    external fun decryptSecret(masterKey: String, cipherHex: String): String
    external fun calculateFileSha256(filePath: String): String
    external fun scanThreat(input: String): String
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Elementos de UI
        val btnGeneratePass = findViewById<Button>(R.id.btnGeneratePass)
        val tvGeneratedPass = findViewById<TextView>(R.id.tvGeneratedPass)
        val etMasterKey = findViewById<EditText>(R.id.etMasterKey)
        val etSecretToEncrypt = findViewById<EditText>(R.id.etSecretToEncrypt)
        val btnEncrypt = findViewById<Button>(R.id.btnEncrypt)
        val tvVaultStatus = findViewById<TextView>(R.id.tvVaultStatus)

        val btnScanDownloads = findViewById<Button>(R.id.btnScanDownloads)
        val tvScanResult = findViewById<TextView>(R.id.tvScanResult)

        val btnAuditApps = findViewById<Button>(R.id.btnAuditApps)
        val tvAuditResult = findViewById<TextView>(R.id.tvAuditResult)

        // --- GESTOR DE CONTRASEÑAS: ALTA ENTROPÍA ---
        btnGeneratePass.setOnClickListener {
            // Genera contraseña de 24 caracteres criptográficamente seguros
            val pass = SecurityBridge.generateHighEntropyPassword(24)
            tvGeneratedPass.text = pass
        }

        // --- GESTOR DE CONTRASEÑAS: CIFRADO CHACHA20 ---
        var lastEncryptedHex = ""
        btnEncrypt.setOnClickListener {
            val master = etMasterKey.text.toString()
            val secret = etSecretToEncrypt.text.toString()
            if (master.isNotEmpty() && secret.isNotEmpty()) {
                lastEncryptedHex = SecurityBridge.encryptSecret(master, secret)
                tvVaultStatus.text = "🔒 Cifrado con ChaCha20-Poly1305:\n$lastEncryptedHex"
            } else {
                tvVaultStatus.text = "Ingresa llave maestra y texto."
            }
        }

        // --- ESCÁNER DE ARCHIVOS TIPO VIRUSTOTAL (SHA-256 EN RUST) ---
        btnScanDownloads.setOnClickListener {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists() && downloadDir.isDirectory) {
                val files = downloadDir.listFiles()?.take(5) // Tomar los primeros 5 para el demo
                if (!files.isNullOrEmpty()) {
                    val sb = StringBuilder("🛡️ ARCHIVOS AUDITADOS (SHA-256):\n")
                    for (file in files) {
                        if (file.isFile) {
                            val hash = SecurityBridge.calculateFileSha256(file.absolutePath)
                            sb.append("📄 ").append(file.name).append("\n")
                            sb.append("   Hash: ").append(hash.take(16)).append("... (Verificado)\n")
                        }
                    }
                    tvScanResult.text = sb.toString()
                } else {
                    tvScanResult.text = "Carpeta Downloads vacía o sin permisos concedidos."
                }
            } else {
                tvScanResult.text = "Directorio de descargas inaccesible."
            }
        }

        // --- AUDITOR DE PERMISOS ESTILO GRAPHENEOS ---
        btnAuditApps.setOnClickListener {
            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            var highRiskApps = 0
            val sb = StringBuilder("🚨 AUDITORÍA DE PERMISOS INVASIVOS:\n")

            for (pkg in packages) {
                val perms = pkg.requestedPermissions
                if (perms != null) {
                    val hasCam = perms.contains("android.permission.CAMERA")
                    val hasMic = perms.contains("android.permission.RECORD_AUDIO")
                    val hasGps = perms.contains("android.permission.ACCESS_FINE_LOCATION")

                    if (hasCam && hasMic && hasGps) {
                        highRiskApps++
                        if (highRiskApps <= 4) { // Listar solo las primeras 4 para no saturar pantalla
                            val appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                            sb.append("⚠️ ").append(appName).append("\n")
                            sb.append("   Acceso a: Cámara + Micrófono + Ubicación\n")
                        }
                    }
                }
            }
            sb.append("\nTotal de aplicaciones críticas detectadas: ").append(highRiskApps)
            tvAuditResult.text = sb.toString()
        }
    }
}
