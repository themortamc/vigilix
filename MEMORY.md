# Memoria del proyecto — Vigilix

> Estado actual, decisiones tomadas y lo que el siguiente agente (humano o IA) debe saber antes de tocar una línea de código.

**Última actualización:** 2026-10-11 (v1.0 - Implementación completa Dual Mode + Cloud Gratuita + Subscription + UX Universal)

## Estado actual

- **Versión:** 1.0.0 (`versionCode=7`, `versionName="1.0.0"`)
- **Último commit:** `922c55d fix(kotlin): fix imports and IDs for 3-tab interface` (rama `main`)
- **CI verde:** `rust-tests` (21/21) + `build-apk` (matrix API 26/28/30/34) en `.github/workflows/build.yml`
- **Estructura:** `app/` (Kotlin Android), `rust/` (núcleo cdylib + tests), `.github/workflows/`, `SECURITY_AUDIT.md`
- **Herramientas locales:** Rust 1.99.0, Gradle 9.8.0, Java 25. **Sin Android SDK local** (compilación verificada en CI)

## Decisiones Arquitectónicas Clave (2026-10-11)

1. **Dual Mode Architecture**: `ModeDetector` detecta root/debug/magisk → `AppMode.Root` / `AppMode.NonRoot` → `FeatureGate` expone capacidades por modo + entitlement Pro
2. **Experience System**: `ExperienceLevel` (Basic/Advanced/Expert) persistido → `ExperienceManager` → UI adaptativa + Settings fragment dinámico
3. **Cloud Gratuita**: Threat Intel en GitHub Pages (`gh-pages/threat-intel/`) + Firebase Remote Config (free tier) + Workflow diario sync
4. **Subscription $2/mes**: Google Play Billing v6 + RevenueCat fallback → `EntitlementManager` → Pro features gate
5. **Setup Wizard**: 3 pasos (ExperienceLevel → Permisos → Features) → persiste `ExperienceLevel` + configura permisos dinámicos
6. **Dual Mode Features**:
   - RootMode: restrictBackground, DNS sin ADB, system app scan, behavioral sandbox (Pro)
   - NonRootMode: VpnService network monitor, full scan storage, APK analyzer, scam detection
5. **Cloud Gratuita**: Threat Intel en GitHub Pages (workflow diario) + Firebase Remote Config (feature flags, kill switch)
6. **Subscription**: Play Billing v6 ($2/mes, trial 14d) + RevenueCat fallback → `EntitlementManager`
7. **Setup Wizard**: 3 pasos (ExperienceLevel → Permisos dinámicos → Features toggles) → persiste nivel
8. **Features P0 implementadas**: ScamDetection (local + GitHub Pages sync + user reports), ApkAnalyzer (risk score 0-100), NetworkMonitor (VpnService), Duress PIN (fake vault determinística)
7. **Testing**: JUnit4/Robolectric unit + Espresso instrumented + CI matrix API 26/28/30/34

## Lo que NO está listo

- Behavioral Analysis Sandbox (Root + Pro) - en diseño
- Cloud Sync WebDAV (Pro) - pendiente
- Temas extra Material You (Pro) - pendiente
- Export CSV/HTML/JSON (Pro) - pendiente
- F-Droid build reproducible - pendiente
- KMP / iOS prep - no iniciado

## Comandos rápidos

```bash
# Rust core
cd rust && cargo test --release --locked

# Android (verificar en CI)
./gradlew testDebugUnitTest connectedDebugAndroidTest

# Lint
./gradlew lintDebug

# Build APK
./gradlew assembleDebug
```

## Próximos pasos (Prioridad)

1. **Behavioral Analysis Sandbox** (Root + Pro) - Análisis dinámico APK en perfil restringido
2. **Cloud Sync WebDAV** (Pro) - Sync bóveda a Nextcloud/ownCloud
3. **Temas extra Material You** (Pro) - 8 temas + custom color picker
4. **Export CSV/HTML/JSON** (Pro) - Historial, bóveda, reportes APK
5. **F-Droid build reproducible** - Metadata + build determinista
6. **KMP / iOS prep** - expect/actual para core

## Convenciones

- Cada cambio significativo se apunta aquí con fecha y link al commit/PR
- Este archivo es la **única** fuente para "qué está pasando"; no depender de git log ni README
- `specs.md` es la fuente de verdad técnica; `features.md` el roadmap; `constitution.md` las invariantes