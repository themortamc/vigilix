<div align="center">

# 🛡️ Vigilix
### *Herramienta de Ciberseguridad y Escudo de Privacidad para Android*

[![Estado de CI](https://github.com/themortamc/vigilix/actions/workflows/build.yml/badge.svg)](https://github.com/themortamc/vigilix/actions)
[![Licencia: MIT](https://img.shields.io/badge/Licencia-MIT-blue.svg)](LICENSE)
[![Plataforma: Android](https://img.shields.io/badge/Plataforma-Android_8.0%2B-green.svg)]()
[![Núcleo Rust](https://img.shields.io/badge/N%C3%BAcleo-Rust_JNI-orange.svg)]()

**Vigilix** es una suite de seguridad móvil diseñada para Android con un enfoque estricto en la privacidad (*privacy-first, local-first, dual-mode*). Combina un núcleo nativo en **Rust** para operaciones criptográficas y cálculo de hashes de alta velocidad, con una interfaz moderna y adaptativa en **Kotlin**.

</div>

---

## 🌟 Funcionalidades Principales (v1.0)

- **🔄 Arquitectura Dual Mode**: Detección inteligente del dispositivo para operar en `RootMode` (control total y análisis de bajo nivel) o `NonRootMode` (máxima protección mediante `VpnService`).
- **🔐 Bóveda de Contraseñas Militar**: Cifrado seguro utilizando `Argon2id` (64 MiB, t=3, p=1) + `ChaCha20-Poly1305` (AEAD). Admite desbloqueo biométrico y **Duress PIN** (creación de una bóveda señuelo ante situaciones de coacción).
- **🦠 Escáner Antimalware SHA-256**: Modos de escaneo Rápido y Completo contra bases de firmas de malware locales sin enviar archivos a servidores externos.
- **🌐 Monitor de Red Local (VpnService)**: Detección en tiempo real de tráfico saliente hacia servidores C2 e IPs maliciosas sin necesidad de permisos root.
- **📦 Analizador de APKs Estático**: Lectura y análisis de manifiestos de aplicaciones, certificados, permisos peligrosos y cálculo automático de puntuación de riesgo (*Risk Score 0-100*).
- **📞 Detección Colaborativa de Estafas**: Base de datos local contra números de spam, SMS sospechosos y URLs de phishing alimentada por la comunidad.
- **💎 Suscripción y Funciones Pro**: Integración con Google Play Billing v6 para desbloqueo de sincronización en la nube vía WebDAV (Nextcloud/ownCloud), temas adicionales y exportación avanzada.
- **🧙 Asistente de Configuración (Setup Wizard)**: Flujo de bienvenida en 3 pasos adaptado a tres perfiles de usuario: Básico (un solo botón), Avanzado y Experto.

---

## 🏗️ Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│ Capa UI (Kotlin, ViewBinding, Material Design 3)            │
│  MainActivity (3 pestañas) + SetupWizard + Ajustes          │
├─────────────────────────────────────────────────────────────┤
│ Gestores y Lógica de Negocio (Kotlin)                       │
│  ExperienceManager | ModeDetector | FeatureGate             │
│  VaultStore | ScanEngine | HistoryManager | PermissionMonitor│
├─────────────────────────────────────────────────────────────┤
│ SecurityBridge (Objeto Kotlin — Frontera Única JNI)         │
├─────────────────────────────────────────────────────────────┤
│ rust/ — vigilix_core (cdylib, JNI)                          │
│  Criptografía: Argon2id / ChaCha20-Poly1305 / Zeroizing     │
│  Bóveda: Manejo de sesión + separación de AAD (vgx1:/vgv1:) │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Tecnologías Empleadas

| Componente | Tecnología |
| :--- | :--- |
| **Interfaz y Lógica Android** | Kotlin, ViewBinding, Jetpack Lifecycle, Coroutines, Room DB |
| **Motor Criptográfico** | Rust 2021 (`cdylib`, `jni`, `argon2`, `chacha20poly1305`, `zeroize`) |
| **Seguridad de Claves** | AndroidX Biometric, Android Keystore System (`KeystoreCrypto`) |
| **Conectividad y Facturación** | OkHttp 4, Google Play Billing v6, Firebase Remote Config |
| **Integración Continua** | GitHub Actions (Suite de pruebas en Rust 21/21 + Compilación de APK) |

---

## 🚀 Compilación y Desarrollo

### Requisitos
- **Rust** (canal stable) con targets de compilación cruzada para Android (`aarch64-linux-android`, `x86_64-linux-android`) y la herramienta `cargo-ndk`.
- **Android Studio / SDK** con herramientas de compilación para API 34 y NDK versión `25.2.9519653`.
- **JDK 17**.

### Comandos de Verificación

```bash
# 1. Ejecutar las pruebas unitarias del núcleo en Rust
cd rust
cargo test --release --locked

# 2. Compilar el APK de depuración
./gradlew assembleDebug
```

---

## 🔒 Compromisos de Seguridad

1. **Sin Secretos en el Repositorio**: Los certificados de firma de producción se proporcionan exclusivamente a través de variables de entorno de CI.
2. **Protección de Memoria**: Las claves y datos sensibles en Rust se liberan de memoria usando `Zeroizing<T>`, y en Kotlin los arrays de bytes se limpian de inmediato con `fill(0)`.
3. **Frontera JNI Hermética**: Cada función nativa expuesta está envuelta en `catch_unwind` para impedir que los *panics* crucen al entorno de Android.
4. **Consentimiento Explícito**: Ninguna información personal o archivo sale del dispositivo sin la autorización clara y visible del usuario.

---

## 📄 Licencia

Este proyecto está distribuido bajo la licencia **MIT**. Para más detalles, consulta el archivo [`LICENSE`](LICENSE).
