# Task: Implementación completa Vigilix v1.0 - Dual Mode + Cloud Gratuito + Subscription + UX Universal

## Objetivo
Implementar **todas** las features solicitadas:
- **Dual Mode**: Root/Debug avanzado + Non-root máximo
- **Cloud features gratuitas** (GitHub Pages, Firebase free tier, GitHub Pages threat intel)
- **Subscription $2/mes** (Google Play Billing)
- **UX Universal**: Wizard setup (Basic/Advanced/Expert), features avanzadas togglables
- **Target**: Todos los usuarios (abuelos → devs)

## Alcance
**Dentro:** Todo lo definido en specs.md v1.0
**Fuera:** iOS port, backend propio de pago, features que requieran infraestructura de pago

## Criterios de Aceptación
1. ✅ App compila y tests Rust pasan (21/21)
2. ✅ Dual Mode: RootMode (full) / NonRootMode (max possible) detectado automáticamente
3. ✅ Setup Wizard: 3 niveles (Basic/Advanced/Expert) + ajustes togglables
4. ✅ Cloud features: Threat intel en GitHub Pages, Remote Config Firebase free, sync opcional
4. ✅ Subscription $2/mes implementada (Play Billing)
4. ✅ UX Universal: Wizard 3 niveles + ajustes avanzados togglables
4. ✅ Features P0: Estafas colaborativas, Análisis APK estático, Monitor red VpnService, Duress PIN
4. ✅ Tests Android (JUnit/Espresso) + Rust tests (21/21)
4. ✅ CI/CD verde en GitHub Actions

## Fases

### Fase 1: Documentación y Arquitectura (Día 1)
- [x] task.md actualizado
- [ ] specs.md v1.0 completo
- [ ] features.md actualizado
- [ ] MEMORY.md actualizado
- [ ] AGENTS.md actualizado

### Fase 2: Setup Wizard + UX Universal (Día 2-3)
- [ ] ExperienceLevel enum (Basic/Advanced/Expert)
- [ ] SetupWizardActivity (3 pantallas + persistencia)
- [ ] ExperienceManager (singleton, LiveData)
- [ ] UI adaptativa por nivel (ocultar/mostrar features)
- [ ] Settings fragment con toggles avanzados

### Fase 3: Dual Mode Architecture (Día 3-4)
- [ ] ModeDetector (root check + debug flags)
- [ ] RootMode / NonRootMode sealed interfaces
- [ ] FeatureGate (what's available per mode)
- [ ] VpnService para NonRoot network monitor
- [ ] RootMode: shell commands, ADB wireless, full scan

### Fase 4: Cloud Gratuita + Subscription (Día 4-5)
- [ ] Threat Intel en GitHub Pages (JSON estático + CDN)
- [ ] Firebase Remote Config (feature flags, kill switch)
- [ ] GitHub Pages threat intel sync (workflow diario)
- [ ] Google Play Billing v6 ($2/mes + trial 14 días)
- [ ] EntitlementManager (Pro features unlock)

### Fase 5: Setup Wizard + UX Universal (Día 5-6)
- [ ] SetupWizardActivity (3 pasos: nivel, permisos, features)
- [ ] ExperienceLevel: Basic (abuelo) / Advanced (usuario normal) / Expert (dev)
- [ ] UI adaptativa: Basic = 1 botón grande, Expert = todo visible
- [ ] Settings: sección "Avanzado" (oculta en Basic)

### Fase 6: Features P0 (Día 6-10)
- [ ] **Detección estafas colaborativa**: BD local + sync GitHub Pages + reporte usuario
- [ ] **Análisis APK estático**: AndroidManifest parser, risk score, YARA-lite
- [ ] **Monitor red VpnService**: NonRoot network monitor, alertas IP sospechosas
- [ ] **Duress PIN / Bóveda falsa**: PIN alternativo → vault vacía
- [ ] **Análisis APK estático**: Manifest parser, risk score, export HTML report

### Fase 7: Testing + CI/CD (Día 10-11)
- [ ] JUnit4 + Robolectric: VaultStore, HashDb, PermissionMonitor, HistoryManager
- [ ] Espresso: flujos críticos (vault unlock, scan, autofill, wizard)
- [ ] CI: matrix test (emulator API 26, 28, 30, 34)
- [ ] Release pipeline: version bump, changelog, Play Console upload

### Fase 8: Verificación Final (Día 11)
- [ ] Rust tests: 21/21 pass
- [ ] Android tests: unit + instrumented pass
- [ ] CI/CD: matrix API 26-34 pass
- [ ] Security audit: sin regresiones
- [ ] Performance: scan < 30s quick, < 5min full
- [ ] Battery: < 2%/day background

## Riesgos y Mitigaciones

| Riesgo | Probabilidad | Impacto | Mitigación |
|--------|--------------|---------|------------|
| Play Billing rejection | Media | Alto | Implementar RevenueCat como fallback, testing exhaustivo |
| VpnService battery drain | Alta | Media | Configurable interval, solo WiFi, pause on battery saver |
| GitHub Pages rate limit | Baja | Media | CDN + cache headers, fallback local DB |
| Play Billing rejection | Media | Alto | RevenueCat fallback, testing exhaustivo en test track |
| Root detection evasion | Baja | Media | Multiple checks (su, magisk, props), graceful degradation |
| VpnService battery drain | Alta | Media | Configurable interval, solo WiFi, pause on battery saver |

## Verificación Final
- [ ] `cargo test --release --locked` → 21/21 pass
- [ ] `./gradlew testDebugUnitTest connectedDebugAndroidTest` → all pass
- [ ] CI matrix (API 26, 28, 30, 34) → all green
- [ ] Security audit: sin regresiones vs SECURITY_AUDIT.md
- [ ] Performance: Quick scan < 30s, Full < 5min, Battery < 2%/day
- [ ] UX test: Abuelo completa wizard solo, Dev accede a todo en < 3 taps