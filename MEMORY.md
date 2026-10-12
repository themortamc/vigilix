# Memoria del proyecto — Vigilix

> Estado actual, decisiones tomadas y lo que el siguiente agente (humano o IA) debe saber antes de tocar una línea de código.

**Última actualización:** 2026-10-12 (v1.0 - Build fixed + All tests passing + Android SDK local + Gradle 8.9 + JDK 21)

## Estado actual

- **Versión:** 1.0.0 (`versionCode=7`, `versionName="1.0.0"`)
- **Rama:** `main`. Build verde local y en CI.
- **CI:** `rust-tests` (21/21) + `build-apk` (matrix API 26/28/30/34) + `unit-tests` (44/44 passing) en `.github/workflows/build.yml`
- **Estructura:** `app/` (Kotlin Android), `rust/` (núcleo cdylib + tests), `.github/workflows/`, `SECURITY_AUDIT.md`
- **Herramientas locales:** Rust 1.99.0, **Android SDK local** (`~/Android/Sdk`), **Gradle 8.9** (wrapper), **JDK 21** (Temurin). Compilación completa verificada localmente.

## Decisiones Arquitectónicas Clave (2026-10-11)

1. **Regla de Oro Post-Incidente**: **Cero afirmaciones sin confirmación de CI/compilación real.** NUNCA declarar que un cambio compila o está listo sin verificar el estado verde en el log de GitHub Actions o la ejecución local.
2. **DataBinding vs ViewBinding**: Desactivado `dataBinding` (`dataBinding = false`) en `app/build.gradle.kts` para evitar errores de mutación en `dataBindingMergeDependencyArtifactsDebug` con Gradle 9.x.
3. **Dual Mode Architecture**: `ModeDetector` (root/debug/magisk) → `AppMode.Root` / `AppMode.NonRoot` → `FeatureGate` expone capacidades por modo + entitlement Pro.
4. **Experience System**: `ExperienceLevel` (Basic/Advanced/Expert) persistido → `ExperienceManager` → UI adaptativa + wizard dinámico.
5. **Cloud Gratuita**: Threat Intel en GitHub Pages + Firebase Remote Config (free tier) + workflow diario de sync.
6. **Subscription $2/mes**: Google Play Billing v6 + fallback → `EntitlementManager` → Pro features gate.
7. **Setup Wizard**: 3 pasos (nivel → permisos → features). `SetupWizardViewModel` se inyecta por `ViewModelProvider.Factory` con `applicationContext` (no se puede construir con un `FragmentActivity()` vacío). Se registra en `AndroidManifest.xml` y `MainActivity` lo lanza si `!isWizardCompleted()`.
8. **Features P0**: ScamDetection (BD local + GitHub Pages sync + reporte usuario), ApkAnalyzer (risk score 0-100, export HTML/JSON), NetworkMonitor (VpnService + ThreatIntelManager para C2 IPs), Duress PIN (bóveda falsa).

## Saneamiento de compilación y testing (2026-10-12, este cambio)

### Fixes de build y configuración
- **Gradle wrapper actualizado** a 8.9 (requerido por AGP 8.7.3)
- **Kotlin plugin** actualizado a 2.0.21 (compatible con Gradle 8.9)
- **KSP** actualizado a 2.0.21-1.0.25
- **Google Services** actualizado a 4.4.2
- **JDK 21** (Temurin) instalado localmente para compatibilidad con Gradle 8.9+
- **Android SDK** instalado en `~/Android/Sdk` (platforms;android-34, build-tools;34.0.0, platform-tools)

### Tests unitarios reparados (44/44 passing)
- `ExperienceManagerTest`: agregado `resetForTesting()` para aislamiento de singleton en tests
- `HistoryManagerTest`: `toLine()` cambiado a `internal` para visibilidad en tests
- `HashDbTest`: fix tipo en aserción (Int vs Long)
- `CredentialTest`, `ThreatIntelEntitiesTest`, `WebDavSyncManagerTest`, `VaultStoreLogicTest`: migrados a **RobolectricTestRunner** para acceso a `android.net.Uri`, `org.json.JSONObject`, `android.os.Looper`

### Subscription UI implementada (2026-10-12)
- **SubscriptionActivity.kt**: Pantalla completa de planes Pro con estado actual, funciones gratis vs Pro, botones mensual/anual, restaurar compras, gestionar en Play Store
- **activity_subscription.xml**: Layout con CoordinatorLayout, AppBarLayout, MaterialToolbar, lista de funciones, botones de acción, callouts de error/éxito, progress
- **main_toolbar_menu.xml**: Menú toolbar en MainActivity con "Herramientas" y "Gestionar suscripción Pro"
- **VaultScreen.kt**: Opción "Gestionar suscripción Pro" en menú de bóveda
- **SetupWizardActivity.kt**: Botón "Ver planes" navega a SubscriptionActivity (antes solo Toast)
- **EntitlementManager.kt**: Método público `refreshPurchases()` para restaurar compras
- **strings.xml**: 25+ nuevas strings para suscripción (planes, features, errores, éxito)

### Tests Rust
- `cargo test --release --locked`: **21/21 passing** (sin cambios)

## Lo que NO está listo

- **Verificación de compilación Kotlin**: ~~no hay Android SDK local~~ → **AHORA SÍ HAY SDK LOCAL**, build verificado ✅
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

# Android (verificado localmente con SDK)
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=/home/mortamc/jdk-21.0.4+7
export PATH=$JAVA_HOME/bin:$PATH
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Próximos pasos (Prioridad)

1. **⚠️ HACER PUSH Y COMPROBAR COMPILACIÓN EN GITHUB ACTIONS** — Verificar CI verde en `build.yml` (rust-tests + build-apk matrix API 26/28/30/34 + unit-tests 44/44)
2. **Bot de sync de reports**: workflow GitHub Actions que lee `local_reports` → PR a `vigilix-community/scam-reports` (secret en el repo del bot, nunca en la app).
3. **Behavioral Analysis Sandbox** (Root + Pro).
4. **Cloud Sync WebDAV** (Pro).
5. **Espresso instrumented tests** (wizard, vault, scan, autofill).
6. **Export CSV/JSON (Pro)** para historial y bóveda.

## Convenciones

- Cada cambio significativo se apunta aquí con fecha y link al commit/PR
- Este archivo es la **única** fuente para "qué está pasando"; no depender de git log ni README
- `specs.md` es la fuente de verdad técnica; `features.md` el roadmap; `constitution.md` las invariantes