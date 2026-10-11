# Memoria del proyecto — Vigilix

> Estado actual, decisiones tomadas y lo que el siguiente agente (humano o IA) debe saber antes de tocar una línea de código.

**Última actualización:** 2026-10-11 (v1.0 - Dual Mode + Cloud Gratuita + Subscription + UX Universal)

## Estado actual

- **Versión:** 1.0.0 (`versionCode=7`, `versionName="1.0.0"`)
- **Rama:** `main`. El trabajo de saneamiento de compilación está pendiente de commit (ver `git status`).
- **CI:** `rust-tests` (21/21 locales) + `build-apk` (matrix API 26/28/30/34) en `.github/workflows/build.yml`
- **Estructura:** `app/` (Kotlin Android), `rust/` (núcleo cdylib + tests), `.github/workflows/`, `SECURITY_AUDIT.md`
- **Herramientas locales:** Rust 1.99.0. **Sin Android SDK local**: la compilación Kotlin se verifica en CI; local solo se corre `cargo test`.

## Decisiones Arquitectónicas Clave (2026-10-11)

1. **Dual Mode Architecture**: `ModeDetector` (root/debug/magisk) → `AppMode.Root` / `AppMode.NonRoot` → `FeatureGate` expone capacidades por modo + entitlement Pro.
2. **Experience System**: `ExperienceLevel` (Basic/Advanced/Expert) persistido → `ExperienceManager` → UI adaptativa + wizard dinámico.
3. **Cloud Gratuita**: Threat Intel en GitHub Pages + Firebase Remote Config (free tier) + workflow diario de sync.
4. **Subscription $2/mes**: Google Play Billing v6 + fallback → `EntitlementManager` → Pro features gate.
5. **Setup Wizard**: 3 pasos (nivel → permisos → features). `SetupWizardViewModel` se inyecta por `ViewModelProvider.Factory` con `applicationContext` (no se puede construir con un `FragmentActivity()` vacío). Se registra en `AndroidManifest.xml` y `MainActivity` lo lanza si `!isWizardCompleted()`.
6. **Features P0**: ScamDetection (BD local + GitHub Pages sync + reporte usuario), ApkAnalyzer (risk score 0-100, export HTML/JSON), NetworkMonitor (VpnService + ThreatIntelManager para C2 IPs), Duress PIN (bóveda falsa).

## Saneamiento de compilación (2026-10-11, este cambio)

Errores que impedían que la app compilara, corregidos:

- **strings.xml**: faltaban `back`, `next`, `finish`, `skip` (usados por `activity_setup_wizard.xml`).
- **ThreatIntelDatabase.kt**: `@TypeConverters` sin argumentos → eliminado (la anotación va en la clase DB).
- **FeatureGate.kt**: `@Inject` sin framework DI → constructor normal. `getInstance` resuelve el modo con `runBlocking` (solo primera vez).
- **EntitlementManager.kt**: conflicto de declaración `isPro` (property LiveData + fun `isPro()`) → `isProLiveData` + `val isPro: Boolean`. `withContext` en funciones no-suspend → callbacks asíncronos de BillingClient.
- **ThreatIntelDao.kt**: `upsertReport` con `@Query SELECT` sin parámetros de match → eliminado (se usa `insert` con `OnConflictStrategy.REPLACE`).
- **ThreatIntelEntities.kt / ThreatIntelManager.kt**: `ScamReport`, `ScamType`, `ReportStatus` definidos DOS veces → única definición en `ThreatIntelEntities.kt`. `SyncResult` era `private` siendo retorno público de `sync()` → ahora público.
- **VaultStore.kt**: `generateFakeVault()` con llaves desbalanceadas (`apply {` sin cierre) → reescrito.
- **NetworkMonitorVpnService.kt**: `processAlerts()` inexistente → eliminado. `Builder()` ambiguo → `VpnService.Builder()`. `isSuspiciousIp` era stub → ahora consulta `ThreatIntelManager.isC2Ip`.
- **ScamDetectionManager.kt**: campos duplicados (`client`, `githubApi`), referencia a `githubApi` eliminado, `createGitHubPr` usaba `System.getenv("GITHUB_PAT")` (siempre null en Android; además violaría la Constitución: cero secretos) → reescrito: el reporte se guarda en Room + backup JSON local; el push al repo comunitario lo hace un workflow bot con sus propios secrets.
- **ModeDetector.kt**: faltaba `import java.io.File`.
- **ApkAnalyzer.kt**: import duplicado de `Dispatchers`; `exportJson` usaba `kotlinx.serialization` (dependencia que se quitó del build) → `org.json` estándar.
- **RemoteConfigManager.kt**: faltaban imports de `LiveData`/`MutableLiveData`; import fantasma de `ktx.andAwait`.
- **build.gradle.kts**: dependencias duplicadas de coroutines; agregado `testImplementation` (JUnit4, Robolectric, androidx.test).

### Tests agregados (Kotlin unit, Robolectric/JUnit4)

- `ExperienceManagerTest` (nivel por defecto, setLevel, wizard completed/reset)
- `PrivilegedEngineTest` (validación de paquetes, inyección de shell rechazada)
- `AppModeTest` (discriminación Root/NonRoot, campos de datos)
- `ThreatIntelEntitiesTest` (serialización/deserialización de `ScamReport`, entidades)
- `ApkAnalyzerRiskScoreTest` (pesos de risk factors, multiplicadores de combo)

## Lo que NO está listo

- **Verificación de compilación Kotlin**: no hay Android SDK local; hay que correr `./gradlew assembleDebug testDebugUnitTest` en CI antes de declarar v1.0 estable.
- Behavioral Analysis Sandbox (Root + Pro) - en diseño
- Cloud Sync WebDAV (Pro) - pendiente
- Temas extra Material You (Pro) - pendiente
- Export CSV/HTML/JSON (Pro) - pendiente (HTML de APK sí existe)
- F-Droid build reproducible - pendiente
- KMP / iOS prep - no iniciado
- Espresso instrumented tests - pendiente
- Bot de reports (workflow GitHub con PAT) - pendiente; los reportes quedan PENDING en local

## Comandos rápidos

```bash
# Rust core (obligatorio, se corre local)
cd rust && cargo test --release --locked

# Android (verificar en CI o con SDK local)
./gradlew testDebugUnitTest connectedDebugAndroidTest
./gradlew assembleDebug
```

## Próximos pasos (Prioridad)

1. **CI verde completo**: correr `testDebugUnitTest` + `assembleDebug` y corregir lo que salga del compilador real.
2. **Bot de sync de reports**: workflow GitHub Actions que lee `local_reports` → PR a `vigilix-community/scam-reports` (secret en el repo del bot, nunca en la app).
3. **Behavioral Analysis Sandbox** (Root + Pro).
4. **Cloud Sync WebDAV** (Pro).
5. **Espresso instrumented tests** (wizard, vault, scan, autofill).
6. **Export CSV/JSON (Pro)** para historial y bóveda.

## Convenciones

- Cada cambio significativo se apunta aquí con fecha y link al commit/PR
- Este archivo es la **única** fuente para "qué está pasando"; no depender de git log ni README
- `specs.md` es la fuente de verdad técnica; `features.md` el roadmap; `constitution.md` las invariantes
