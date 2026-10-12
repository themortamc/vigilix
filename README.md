<div align="center">

# 🛡️ Vigilix
### *Mobile Cybersecurity & Privacy Shield for Android*

[![CI Status](https://github.com/themortamc/vigilix/actions/workflows/build.yml/badge.svg)](https://github.com/themortamc/vigilix/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)]()
[![Rust Core](https://img.shields.io/badge/Core-Rust_JNI-orange.svg)]()

**Vigilix** es una herramienta avanzada de ciberseguridad móvil diseñada para Android. Combina la potencia y seguridad de un núcleo nativo en **Rust** con una interfaz intuitiva en **Kotlin**, ofreciendo protección integral de grado militar (*privacy-first, local-first, dual-mode*).

</div>

---

## 🌟 Características Principales (v1.0)

- **🔄 Dual Mode Architecture**: Detección automática de entorno (`RootMode` con acceso completo o `NonRootMode` maximizado con `VpnService`).
- **🔐 Bóveda de Contraseñas de Alta Seguridad**: Cifrado extremo con `Argon2id` (64 MiB, t=3, p=1) + `ChaCha20-Poly1305` (AEAD). Soporte para desbloqueo biométrico y **Duress PIN** (bóveda falsa ante coacción).
- **🦠 Escáner Antimalware SHA-256**: Escaneo rápido y completo de almacenamiento y aplicaciones con verificación de base de firmas local.
- **🌐 Monitor de Red (VpnService)**: Detección en tiempo real de conexiones sospechosas y servidores C2 sin permisos root.
- **📦 Analizador de APKs Estático**: Inspección de manifiestos, permisos peligrosos, certificados y cálculo automático de *Risk Score (0-100)*.
- **📞 Detección de Estafas Colaborativa**: Base de datos local sincronizada con Threat Intelligence comunitaria (GitHub Pages) y reporte local de llamadas/SMS/URLs.
- **💎 Suscripción y Entitlements**: Integración con Google Play Billing v6 ($2/mes con 14 días de prueba) para funciones Pro (Cloud Sync WebDAV, temas extra, exportación).
- **🧙 Setup Wizard de 3 Niveles**: Experiencia adaptada para cualquier usuario (Básico / Avanzado / Experto).

---

## 🏗️ Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Kotlin, ViewBinding, Material 3, Coroutines)            │
│  MainActivity (3 tabs) + SetupWizard + Settings + Dialogs   │
├─────────────────────────────────────────────────────────────┤
│ ViewModels / Managers (Kotlin)                              │
│  ExperienceManager | ModeDetector | FeatureGate             │
│  VaultStore | ScanEngine | HistoryManager | PermissionMonitor│
├─────────────────────────────────────────────────────────────┤
│ SecurityBridge (Kotlin object — único puente JNI)           │
├─────────────────────────────────────────────────────────────┤
│ rust/ — vigilix_core (cdylib, JNI)                          │
│  Crypto: Argon2id / ChaCha20-Poly1305 / Zeroizing           │
│  Vault: session + AAD separation (vgx1:/vgv1:)              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Stack Tecnológico

| Capa | Tecnología |
| :--- | :--- |
| **Android UI & Architectura** | Kotlin, ViewBinding, Material 3, Lifecycle, Coroutines, Room DB |
| **Núcleo Criptográfico** | Rust 2021 edition (`cdylib`, `jni`, `argon2`, `chacha20poly1305`) |
| **Seguridad & Auth** | AndroidX Biometric, AndroidX Security Crypto (`KeystoreCrypto`) |
| **Redes & Cloud** | OkHttp 4, Google Play Billing v6, Firebase Remote Config |
| **CI/CD** | GitHub Actions (Pruebas unitarias Rust 21/21 + Gradle Build APK) |

---

## 🚀 Compilación y Desarrollo

### Requisitos Previos
- **Rust** (stable) con targets Android (`aarch64-linux-android`, `x86_64-linux-android`) y `cargo-ndk`.
- **Android Studio** (AGP 8.2.2+, Kotlin 1.9.20+, JDK 17, Android SDK API 34).

### Comandos Clave

```bash
# 1. Compilar y ejecutar pruebas del núcleo en Rust
cd rust
cargo test --release --locked

# 2. Compilar APK Debug en Android
./gradlew assembleDebug
```

---

## 🔒 Garantías de Seguridad

1. **Cero Secretos en el Repo**: El material de firmado se inyecta exclusivamente mediante variables de entorno (`KEYSTORE_FILE`, etc.).
2. **Memoria Ceroizada**: Las claves y contraseñas en Rust se protegen con `Zeroizing<T>` y en Kotlin se limpian con `fill(0)`.
3. **Frontera JNI Protegida**: Todo `extern "system"` en Rust está envuelto en `catch_unwind`. Ningún *panic* cruza a Kotlin.
4. **Consentimiento Explícito**: Ningún dato o archivo del usuario se envía a la red sin interacción y aprobación explícita en la UI.

---

## 📄 Licencia

Distribuido bajo la licencia **MIT**. Consulta [`LICENSE`](LICENSE) para más detalles.
