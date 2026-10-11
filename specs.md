# Especificación Técnica — Vigilix v1.0

> SDD (Spec-Driven Development): cada feature se implementa tras tener su sección aquí.
> Fuente de verdad sobre *cómo* funciona el sistema.

---

## 1. Visión General y Arquitectura

### 1.1 Identidad
**Vigilix** — Herramienta de ciberseguridad móvil Android: escáner malware (hash exacto), bóveda contraseñas (Argon2id+ChaCha20-Poly1305), inspector permisos, monitor red, detector estafas, análisis APK estático. **Privacy-first, local-first, dual-mode**.

### 1.2 Dual Mode Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ModeDetector                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ Root Check  │  │ Debug Flags │  │ Magisk/     │             │
│  │ (su, uid=0) │  │ (adb, debug)│  │ KernelSU    │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         └────────────────┼────────────────┘                    │
│                          ▼                                      │
│              ┌───────────────────────┐                          │
│              │   Mode: Root / NonRoot │                         │
│              └───────────┬───────────┘                          │
└──────────────────────────┼──────────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
┌────────────────┐ ┌────────────────┐ ┌────────────────┐
│   RootMode     │ │  NonRootMode   │ │  FeatureGate   │
│ (Full Access)  │ │ (Max Possible) │ │ (per feature)  │
└────────────────┘ └────────────────┘ └────────────────┘
```

### 1.3 Capabilities por Mode

| Feature | RootMode | NonRootMode | Pro Only |
|---------|----------|-------------|----------|
| Escaneo completo almacenamiento | ✅ | ✅ (MANAGE_EXTERNAL_STORAGE) | - |
| Escaneo apps sistema | ✅ | ❌ | - |
| Restringir apps background (am/cmd) | ✅ | ❌ | - |
| DNS privado sin ADB | ✅ | ❌ (requiere ADB once) | - |
| Monitor red (VpnService) | ✅ | ✅ | - |
| Análisis APK estático | ✅ | ✅ | - |
| Detección estafas colaborativa | ✅ | ✅ | - |
| Duress PIN / Bóveda falsa | ✅ | ✅ | - |
| Análisis comportamiento (sandbox) | ✅ | ❌ | Pro |
| Sync bóveda cloud (WebDAV) | ✅ | ✅ | Pro |
| Temas extra / Export CSV | ✅ | ✅ | Pro |

### 1.4 Capas

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Kotlin, ViewBinding, Coroutines, Material 3, Jetpack)  │
│  MainActivity (3 tabs) + SetupWizard + Settings + Dialogs   │
├─────────────────────────────────────────────────────────────┤
│ ViewModels / Managers (Kotlin)                              │
│  ExperienceManager | ModeDetector | FeatureGate             │
│  VaultStore | ScanEngine | HistoryManager | PermissionMonitor│
│  ThreatIntelManager | NetworkMonitor | ApkAnalyzer          │
│  SubscriptionManager | CloudSyncManager                     │
├─────────────────────────────────────────────────────────────┤
│ SecurityBridge (Kotlin object — único puente JNI)           │
│  - isLoaded: Boolean (modo degradado = null)                │
│  - guarded() { fallback } para todas las llamadas           │
├─────────────────────────────────────────────────────────────┤
│ rust/ — vigilix_core (cdylib, JNI)                          │
│  Crypto: Argon2id / ChaCha20-Poly1305 / Zeroizing           │
│  Vault: session + AAD separation (vgx1:/vgv1:)              │
│  HashDB: binary search, atomic write, max 2M entries        │
│  JNI: catch_unwind en 100% funciones, fallback null         │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Formatos On-Disk (Inmutables sin Migración)

| Prefijo | Contenido | AAD | Versión |
|---------|-----------|-----|---------|
| `vgx1:` | hex(salt[16] \| nonce[12] \| ciphertext \| tag[16]) | `vigilix/v1` | 1 |
| `vgv1:` | hex(salt[16] \| nonce[12] \| ciphertext \| tag[16]) | `vigilix/vault/v1` | 1 |

- Clave derivada: **Argon2id** (64 MiB, t=3, p=1) sobre `password + salt_por_operación`
- Cifrado: **ChaCha20-Poly1305** (AEAD)
- Clave derivada de la bóveda vive SOLO en Rust (sesión global `Mutex<Option<Session>>`)

---

## 3. Experience System (UX Universal)

### 3.1 ExperienceLevel

```kotlin
enum class ExperienceLevel {
    BASIC,      // "Modo simple" - 1 botón grande, texto grande, auto
    ADVANCED,   // "Usuario avanzado" - Dashboard completo, ajustes visibles
    EXPERT      // "Desarrollador/Experto" - Todo visible, logs, ADB, raw
}
```

### 3.2 Setup Wizard (3 pasos)

| Paso | Basic | Advanced | Expert |
|------|-------|----------|--------|
| 1. Nivel | ✅ Selección | ✅ Selección | ✅ Selección |
| 2. Permisos | Solo escenciales | + Biométrico + DNS | + Root + ADB + VpnService |
| 3. Features | Scan + Vault | + Privacy + History | + Network Monitor + Apk Analyzer + Duress + Cloud Sync |

### 3.3 UI Adaptativa

| Componente | Basic | Advanced | Expert |
|------------|-------|----------|--------|
| Home | 1 tarjeta grande "Escanear" | Dashboard completo | Dashboard + logs |
| Security | Solo "Escanear" | + Permisos + Historial | + Network Monitor + Apk Analyzer |
| Vault | Desbloquear + 1 botón | Lista completa | + Duress + Export + Cloud Sync |
| Settings | Solo "Tema" | + Avanzado visible | + Root + ADB + Logs + Beta |

---

## 4. Cloud Gratuita (Zero Cost)

### 4.1 Threat Intelligence en GitHub Pages

```
vigilix-threat-intel/
├── scam-numbers.json       # { "numbers": ["+34600...", ...], "updated": "2024-01-15" }
├── phishing-urls.json      # { "urls": ["http://...", ...], "updated": "..." }
├── malicious-apks.json     # { "hashes": ["sha256...", ...], "updated": "..." }
├── c2-ips.json             # { "ips": ["1.2.3.4", ...], "updated": "..." }
└── manifest.json           # { "version": 3, "files": [...], "updated": "..." }
```

- **Workflow diario**: `cron: '0 3 * * *'` → fetch fuentes → merge → push a `gh-pages`
- **CDN**: Cloudflare gratuito frente a GitHub Pages
- **Client**: Descarga silenciosa cada 24h (WiFi + cargando), fallback local DB

### 4.2 Firebase Remote Config (Free Tier)

| Parameter | Type | Default | Uso |
|-----------|------|---------|-----|
| `kill_switch` | boolean | false | Apagar app remotamente |
| `vt_enabled_default` | boolean | true | VT on/off por defecto |
| `scan_interval_hours` | number | 24 | Intervalo auto-scan |
| `vpn_interval_seconds` | number | 300 | Intervalo VpnService |
| `max_scan_file_mb` | number | 2000 | Límite archivo escaneo |
| `feature_duress_pin` | boolean | true | Toggle Duress PIN |
| `feature_apk_analyzer` | boolean | true | Toggle APK Analyzer |
| `feature_network_monitor` | boolean | true | Toggle Network Monitor |
| `pro_features_enabled` | boolean | false | Gate Pro features |

### 4.3 GitHub Pages Threat Intel Sync

- **Workflow diario**: Descarga fuentes → normaliza → merge → push a `gh-pages/threat-intel/`
- **Client**: `ThreatIntelManager` descarga cada 24h (solo WiFi + cargando), merge con local, firma SHA-256 verificación
- **Fallback**: DB local si falla descarga

---

## 5. Subscription ($2/mes)

### 5.1 Modelo

| Plan | Precio | Trial | Features |
|------|--------|-------|----------|
| Free | $0 | - | Core: Scan, Vault, Privacy, Network Monitor, Apk Analyzer, Scam Detection |
| Pro | $2/mes | 14 días | Cloud Sync (WebDAV), Temas extra, Export CSV/HTML, Prioridad soporte, Sin límites historial |

### 5.2 Google Play Billing v6

```kotlin
// Entitlement IDs
const val PRO_MONTHLY = "vigilix_pro_monthly"
const val PRO_YEARLY = "vigilix_pro_yearly"  // $20/año (17% descuento)
```

- **BillingClient** v6 + `BillingClientStateListener`
- `QueryProductDetailsAsync` → `QueryPurchasesAsync` → `acknowledgePurchase`
- **RevenueCat** como fallback (gratuito hasta $2.5k/mes revenue)
- `EntitlementManager` singleton: `isPro()`, `observeEntitlements()`

### 5.3 Pro Features Gate

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureGate @Inject constructor(
    private val entitlementManager: EntitlementManager,
    private val remoteConfig: FirebaseRemoteConfig
) {
    val isPro: Boolean get() = entitlementManager.isPro()
    
    fun isEnabled(feature: ProFeature): Boolean = when (feature) {
        ProFeature.CLOUD_SYNC -> isPro
        ProFeature.EXTRA_THEMES -> isPro
        ProFeature.EXPORT_CSV -> isPro
        ProFeature.UNLIMITED_HISTORY -> isPro
        ProFeature.PRIORITY_SUPPORT -> isPro
        ProFeature.BEHAVIOR_ANALYSIS -> isPro && remoteConfig.getBoolean("feature_behavior_analysis")
        else -> true
    }
}
```

---

## 6. Dual Mode Implementation

### 6.1 ModeDetector

```kotlin
object ModeDetector {
    enum class Mode { ROOT, NON_ROOT }
    
    @Suppress("UNUSED_PARAMETER")
    fun detect(context: Context): Mode = runBlocking {
        val root = PrivilegedEngine.checkRoot()
        val debug = isDebuggable(context)
        val magisk = detectMagisk()
        val kernelSu = detectKernelSu()
        
        if (root || magisk || kernelSu) Mode.ROOT else Mode.NON_ROOT
    }
    
    private suspend fun detectMagisk(): Boolean = withContext(Dispatchers.IO) {
        File("/sbin/.magisk").exists() || File("/data/adb/magisk").exists() ||
        try { Runtime.getRuntime().exec("magisk --version").waitFor() == 0 } catch (_) { false }
    }
    
    private suspend fun detectKernelSu(): Boolean = withContext(Dispatchers.IO) {
        File("/system/bin/su").exists() || File("/system/xbin/su").exists() ||
        try { Runtime.getRuntime().exec("which su").waitFor() == 0 } catch (_) { false }
    }
}
```

### 6.2 FeatureGate

```kotlin
sealed interface AppMode {
    data class Root(val hasMagisk: Boolean, val hasKernelSu: Boolean) : AppMode
    data class NonRoot(val hasDebug: Boolean, val hasAdbWireless: Boolean) : AppMode
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureGate @Inject constructor(
    private val mode: AppMode,
    private val entitlementManager: EntitlementManager,
    private val remoteConfig: FirebaseRemoteConfig
) {
    // Core (always available)
    val scan: Boolean = true
    val vault: Boolean = true
    val privacy: Boolean = true
    val networkMonitor: Boolean = remoteConfig.getBoolean("feature_network_monitor")
    val apkAnalyzer: Boolean = remoteConfig.getBoolean("feature_apk_analyzer")
    val scamDetection: Boolean = true
    
    // Root-only
    val restrictBackgroundApps: Boolean = mode is AppMode.Root
    val dnsWithoutAdb: Boolean = mode is AppMode.Root
    val systemAppScan: Boolean = mode is AppMode.Root
    val behaviorAnalysis: Boolean = (mode is AppMode.Root) && entitlementManager.isPro()
    
    // Pro-only
    val cloudSync: Boolean = entitlementManager.isPro()
    val extraThemes: Boolean = entitlementManager.isPro()
    val exportCsv: Boolean = entitlementManager.isPro()
    val unlimitedHistory: Boolean = entitlementManager.isPro()
    val behaviorAnalysisSandbox: Boolean = entitlementManager.isPro() && (mode is AppMode.Root)
}
```

---

## 7. Features P0 Implementation Specs

### 7.1 Detección Estafas Colaborativa

**Arquitectura:**
```
ScamDetectionManager
├── Local DB (Room): scam_numbers, phishing_urls, reported_by_user
├── ThreatIntelManager: sync desde GitHub Pages (daily)
├── UserReporter: UI para reportar número/URL → PR a GitHub Pages repo
└── CallerIdIntegration: (opcional) CallScreeningService para screening llamadas
```

**BD Local (Room):**
```kotlin
@Entity(tableName = "scam_reports")
data class ScamReport(
    @PrimaryKey val id: String,           // UUID
    val type: ScamType,                   // CALL / SMS / URL
    val indicator: String,                // phone number / URL
    val description: String?,             // User description
    @Embedded val location: GeoLocation?, // Optional coarse location
    val timestamp: Long,                  // System.currentTimeMillis()
    val reportedByUser: Boolean,          // true = user reported, false = threat intel
    val status: ReportStatus              // PENDING / VERIFIED / FALSE_POSITIVE
)

enum class ScamType { CALL, SMS, URL }
enum class ReportStatus { PENDING, VERIFIED, FALSE_POSITIVE }
```

**Sync GitHub Pages:**
- Usuario reporta → PR automático a `vigilix-community/scam-reports` (bot con PAT)
- Workflow valida → merge → deploy GitHub Pages
- App descarga `scam-numbers.json` cada 24h

### 7.2 Análisis APK Estático

```kotlin
class ApkAnalyzer {
    data class AnalysisResult(
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val permissions: List<String>,
        val dangerousPermissions: List<String>,
        val riskScore: Int,           // 0-100
        val riskFactors: List<RiskFactor>,
        val manifestXml: String,      // Pretty-printed XML
        val certificates: List<CertInfo>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>
    )
    
    sealed class RiskFactor(val weight: Int, val description: String) {
        object InstallPackages : RiskFactor(25, "REQUEST_INSTALL_PACKAGES")
        object DeletePackages : RiskFactor(25, "REQUEST_DELETE_PACKAGES")
        object SystemAlertWindow : RiskFactor(20, "SYSTEM_ALERT_WINDOW")
        object BindAccessibility : RiskFactor(20, "BIND_ACCESSIBILITY_SERVICE")
        object ReadSms : RiskFactor(15, "READ_SMS")
        object SendSms : RiskFactor(25, "SEND_SMS")
        object ReadContacts : RiskFactor(15, "READ_CONTACTS")
        object DeviceAdmin : RiskFactor(30, "BIND_DEVICE_ADMIN")
        object Overlay : RiskFactor(15, "SYSTEM_ALERT_WINDOW")
        object UnknownSources : RiskFactor(10, "INSTALL_UNKNOWN_APPS")
        data class Custom(val name: String, val weight: Int, val desc: String) : RiskFactor(weight, desc)
    }
    
    suspend fun analyze(apkPath: String): Result<AnalysisResult>
    suspend fun analyzeInstalled(packageName: String): Result<AnalysisResult>
    fun exportHtmlReport(result: AnalysisResult): String  // Para compartir
}
```

**Risk Score Algorithm:**
- Base: 0
- Por cada `RiskFactor`: sumar `weight`
- Combo multipliers: `INSTALL_PACKAGES + DELETE_PACKAGES` = ×1.5
- `SYSTEM_ALERT_WINDOW + BIND_ACCESSIBILITY` = ×1.5
- Cap at 100

### 7.3 Monitor Red (VpnService NonRoot)

```kotlin
class NetworkMonitorVpnService : VpnService() {
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateVpn(network) }
        override fun onLost(network: Network) { stopVpn() }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = Builder()
            .addAddress("10.0.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)
            .establish()
        // Packet parsing en hilo aparte (coroutine)
        return START_STICKY
    }
    
    private fun parsePackets(input: FileInputStream) {
        // Parse IP/TCP/UDP headers → extract: src/dst IP, port, protocol, bytes
        // Match contra C2 IPs (threat intel) + puertos sospechosos
        // Alert si: IP en threat-intel + puerto no estándar (443, 80, 53)
    }
}
```

**NonRoot Constraints:**
- `android.permission.INTERNET` + `BIND_VPN_SERVICE`
- Solo WiFi (configurable)
- Pausa en Battery Saver / Data Saver
- Intervalo configurable (default 5 min, configurable via Remote Config)

### 7.4 Duress PIN / Bóveda Falsa

```kotlin
// En VaultStore
fun unlockWithDuress(context: Context, duressPin: String): UnlockResult {
    if (!isDuressPin(duressPin)) return unlockWithPassword(context, duressPin)
    
    // Generar bóveda falsa determinística
    val fakeVault = generateFakeVault()
    val blob = SecurityBridge.vaultCreateBlob(duressPin, fakeVault) ?: return UnlockResult.WRONG_KEY
    writeBlob(context, blob)
    *session() = Some(Session { key = deriveDuressKey(duressPin), salt = randomSalt() })
    return UnlockResult.OK
}

private fun generateFakeVault(): String = JSONObject().put("v", 1).put("items", JSONArray().apply {
    // 3-5 entradas creíbles pero falsas
    put(generateFakeCredential("Gmail", "usuario@gmail.com", "********"))
    put(generateFakeCredential("Banco", "usuario@banco.com", "********"))
    put(generateFakeCredential("WiFi", "Casa", "********"))
}).toString()
```

---

## 8. Setup Wizard + UX Universal

### 8.1 SetupWizardActivity (3 Fragments)

```kotlin
class SetupWizardActivity : AppCompatActivity() {
    private val viewModel: SetupWizardViewModel by viewModels()
    
    // Step 1: ExperienceLevelSelectionFragment
    // Step 2: PermissionsFragment (dynamic per level)
    // Step 3: FeaturesToggleFragment (per level + Pro)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ViewPager2 + FragmentStateAdapter
        // Progress indicator 3 steps
        // Skip button (solo Expert)
    }
}
```

**ExperienceLevel Selection UI:**
```
┌─────────────────────────────────────┐
│  ¿Cómo quieres usar Vigilix?        │
├─────────────────────────────────────┤
│  🔰  Básico                          │
│     "Quiero protección simple,      │
│      un botón y listo"              │
│  ─────────────────────────────────  │
│  ⚡  Avanzado                         │
│     "Quiero control total,          │
│      ver permisos y ajustes"        │
│  ─────────────────────────────────  │
│  🛠  Experto                          │
│     "Quiero todo: logs, ADB,        │
│      root, análisis profundo"       │
└─────────────────────────────────────┘
```

### 8.2 Settings Fragment (Adaptativo)

```kotlin
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val level = ExperienceManager.getLevel()
        
        // Siempre visibles
        addPreferencesFromResource(R.xml.prefs_basic)
        
        if (level != ExperienceLevel.BASIC) {
            // Sección "Avanzado" (oculta en Basic)
            val advancedCategory = PreferenceCategory(this).apply { title = "Avanzado" }
            preferenceScreen.addPreference(advancedCategory)
            addPreferencesFromResource(R.xml.prefs_advanced, advancedCategory)
        }
        
        if (level == ExperienceLevel.EXPERT) {
            val expertCategory = PreferenceCategory(this).apply { title = "Experto" }
            preferenceScreen.addPreference(expertCategory)
            addPreferencesFromResource(R.xml.prefs_expert, expertCategory)
        }
    }
}
```

---

## 9. Testing Strategy

### 9.1 Unit Tests (JUnit4 + Robolectric)
- `VaultStoreTest`: create, unlock, lock, upsert, delete, import/export, duress
- `HashDbTest`: lookup, import, download, merge, limits
- `PermissionMonitorTest`: findSensitiveApps filtering
- `HistoryManagerTest`: add, recent, clear
- `ApkAnalyzerTest`: risk scoring, manifest parsing
- `ScamDetectionTest`: local DB, threat intel merge, user reports
- `SecurityBridgeTest`: degraded mode (isLoaded=false)
- `FeatureGateTest`: all mode/entitlement combinations

### 9.2 Instrumented Tests (Espresso)
- `SetupWizardTest`: 3 niveles completan wizard → UI correcta
- `VaultFlowTest`: create → unlock → add → lock → unlock biometric
- `ScanFlowTest`: quick → full → cancel → VT toggle
- `AutofillTest`: locked → unlock → fill → lock
- `RootModeTest`: mock root → features enabled
- `NonRootTest`: no root → features gated correctly
- `SubscriptionTest`: purchase → entitlement → Pro features unlock

### 9.3 CI Matrix
```yaml
# .github/workflows/test.yml
strategy:
  matrix:
    api: [26, 28, 30, 34]
    include:
      - api: 26
        emulator: "pixel_2_api_26"
      - api: 28
        emulator: "pixel_3_api_28"
      - api: 30
        emulator: "pixel_4_api_30"
      - api: 34
        emulator: "pixel_7_api_34"
```

---

## 10. CI/CD Pipeline

### 10.1 Build Workflow (`.github/workflows/build.yml`)
- `rust-tests`: cargo test --release --locked
- `build-apk`: matrix API 26/28/30/34 → assembleDebug
- `unit-tests`: `./gradlew testDebugUnitTest`
- `instrumented-tests`: matrix emulators → connectedAndroidTest
- `lint`: `./gradlew lintDebug`
- `proguard-check`: `assembleRelease` (continue-on-error)

### 10.2 Release Workflow (`.github/workflows/release.yml`)
- Trigger: tag `v*`
- Version bump: `versionName` + `versionCode`
- Changelog: auto-generado desde commits convencionales
- Signing: GitHub Secrets (KEYSTORE_FILE, PASSWORDS)
- Upload: Play Console Internal Track + GitHub Release + APK artifact

---

## 11. Versioning y Changelog

**Versioning:** SemVer + `versionCode` incremental
- `0.x.y` → Beta/Alpha
- `1.0.0` → Primera release estable

**Changelog:** `CHANGELOG.md` generado auto desde commits `feat:`, `fix:`, `docs:`, `refactor:`, `security:`

---

## 12. Archivos de Configuración Clave

| Archivo | Propósito |
|---------|-----------|
| `task.md` | Plan de trabajo vivo |
| `specs.md` | Esta especificación (fuente de verdad) |
| `features.md` | Roadmap con estados |
| `MEMORY.md` | Estado actual, decisiones, comandos |
| `constitution.md` | Invariantes de seguridad |
| `AGENTS.md` | Guía para agentes IA |
| `constitution.md` | Invariantes no negociables |
| `.github/workflows/*.yml` | CI/CD pipelines |

---

**Última actualización:** 2026-10-11 (v1.0 spec)
**Estado:** En implementación activa — Fase 2/3 (Setup Wizard + Dual Mode)