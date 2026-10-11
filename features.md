# Roadmap de funcionalidades — Vigilix

> SDD: ninguna feature se implementa sin su sección en `specs.md`.
> Estado de cada item: `idea` → `spec` → `en-curso` → `listo` / `descartado`.

## Hechas (v1.0.0)

- [x] **Arquitectura Dual Mode** (RootMode / NonRootMode con FeatureGate)
- [x] **Setup Wizard UX Universal** (Basic/Advanced/Expert + ajustes togglables)
- [x] **Cloud Gratuita** (GitHub Pages threat intel, Firebase Remote Config, sync workflow)
- [x] **Subscription $2/mes** (Google Play Billing + RevenueCat fallback)
- [x] **Escáner archivos SHA-256** (Fast/Full) contra base firmas local
- [x] **Bóveda contraseñas** (Argon2id + ChaCha20-Poly1305, cambio clave, biometría)
- [x] **Autocompletado sistema** (AutofillService + pantalla desbloqueo)
- [x] **Inspector permisos** (Cámara, Mic, Ubicación, Contactos, SMS, Llamadas)
- [x] **Monitor red VpnService** (NonRoot + Root, alertas C2/IPs sospechosas)
- [x] **Análisis APK estático** (Manifest parser, risk score 0-100, export HTML)
- [x] **Detección estafas colaborativa** (BD local + sync GitHub Pages + reporte usuario)
- [x] **Duress PIN / Bóveda falsa** (PIN alternativo → vault vacía creíble)
- [x] **Generador contraseñas alta entropía** (8-128 chars, 4 clases)
- [x] **DNS privado AdGuard/Quad9** (wizard ADB para WRITE_SECURE_SETTINGS)
- [x] **Cifrador texto standalone** (formato vgx1:)
- [x] **VirusTotal por hash** (API key usuario, cache 7 días, rate limit)
- [x] **Tema claro/oscuro Material 3** (adaptativo)
- [x] **CI/CD**: Rust tests + Android tests matrix API 26/28/30/34
- [x] **Subscription $2/mes** (Play Billing v6 + RevenueCat fallback)
- [x] **Duress PIN / Bóveda falsa**
- [x] **Testing Android** (JUnit4/Robolectric + Espresso)

## En curso

- [ ] **Behavioral Analysis Sandbox** (Root + Pro) - Análisis dinámico APK en perfil restringido
- [ ] **Cloud Sync WebDAV** (Pro) - Sync bóveda a Nextcloud/ownCloud
- [ ] **Temas extra Material You** (Pro) - 8 temas extra + custom color picker
- [ ] **Export CSV/HTML/JSON** (Pro) - Historial escaneos, bóveda, reportes APK
- [ ] **F-Droid build reproducible** - Metadata + build determinista

## Pendientes — Corto plazo

- [ ] **Widget 1×1 "Escanear ahora"** + Quick Settings Tile
- [ ] **Onboarding coach marks** (tooltips contextuales first-run)
- [ ] **Accesibilidad TalkBack** completa en todos los flujos
- [ ] **Crash reporting local cifrado** (opt-in, zero PII)
- [ ] **Feature flags remotas completas** (kill switch, feature toggles)

## Pendientes — Mediano plazo

- [ ] **YARA-lite on-device** (reglas compiladas a bytecode)
- [ ] **Análisis comportamiento sandbox** (Root + Pro, perfil restringido)
- [ ] **Passkeys / WebAuthn** - Bóveda como almacén credenciales
- [ ] **Kotlin Multiplatform prep** - expect/actual para core
- [ ] **iOS port** (via KMP + Compose Multiplatform)

## Descartadas / No se harán

- Backend propio de pago (cloud features = free tier only)
- Telemetría obligatoria / tracking usuarios
- Venta de datos / publicidad
- Features que requieran root obligatorio (siempre fallback non-root)
- IA/ML on-device pesada (battery drain, complejidad)

---

## Plantilla para proponer feature

```markdown
### <nombre> (`idea`)
- **Problema:** qué dolor resuelve (1-2 líneas)
- **Afecta a:** archivos/capas (UI, Rust, CI…)
- **Restricciones:** qué invariantes de la constitución tocan
- **Criterio de "listo":** observable, medible
- **Primero:** escribir su sección en specs.md
```