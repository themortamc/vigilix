<div align="center">

# 🛡️ Vigilix
### *Mobile Cybersecurity & Privacy Shield for Android*

[![CI Status](https://github.com/themortamc/vigilix/actions/workflows/build.yml/badge.svg)](https://github.com/themortamc/vigilix/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)]()
[![Rust Core](https://img.shields.io/badge/Core-Rust_JNI-orange.svg)]()

**Vigilix** is an advanced mobile cybersecurity tool built for Android. It combines the speed and memory safety of a **Rust** native core with a modern, adaptive **Kotlin** UI — privacy-first, local-first, dual-mode.

</div>

---

## 🌟 Key Features (v1.0)

- **🔄 Dual Mode Architecture** — Automatic device detection: `RootMode` (full control, low-level scans) or `NonRootMode` (maximum protection via `VpnService`).
- **🔐 Military-Grade Password Vault** — Encrypted with `Argon2id` (64 MiB, t=3, p=1) + `ChaCha20-Poly1305` (AEAD). Biometric unlock and **Duress PIN** (a believable decoy vault under coercion).
- **🦠 SHA-256 Malware Scanner** — Fast & Full storage/app scans against local signature databases. Files never leave your device.
- **🌐 Local Network Monitor (`VpnService`)** — Real-time detection of outgoing connections to C2 servers and malicious IPs, no root required.
- **📦 Static APK Analyzer** — Manifest inspection, dangerous permissions, certificate checks, and an automatic **Risk Score (0–100)**.
- **📞 Collaborative Scam Detection** — Local database of spam numbers, suspicious SMS, and phishing URLs, backed by community threat intelligence.
- **💎 Pro Subscription** — Google Play Billing v6 ($2/month, 14-day trial) unlocking WebDAV cloud sync (Nextcloud/ownCloud), extra themes, and advanced exports.
- **🧙 3-Level Setup Wizard** — Tailored onboarding for Basic (one big button), Advanced, and Expert users.

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ UI Layer (Kotlin, ViewBinding, Material Design 3)           │
│  MainActivity (3 tabs) + SetupWizard + Settings             │
├─────────────────────────────────────────────────────────────┤
│ Business Logic / Managers (Kotlin)                          │
│  ExperienceManager | ModeDetector | FeatureGate             │
│  VaultStore | ScanEngine | HistoryManager | PermissionMonitor│
├─────────────────────────────────────────────────────────────┤
│ SecurityBridge (Kotlin object — single JNI boundary)        │
├─────────────────────────────────────────────────────────────┤
│ rust/ — vigilix_core (cdylib, JNI)                          │
│  Crypto: Argon2id / ChaCha20-Poly1305 / Zeroizing           │
│  Vault: session + AAD separation (vgx1:/vgv1:)              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

| Component | Technology |
| :--- | :--- |
| **Android UI & Logic** | Kotlin, ViewBinding, Jetpack Lifecycle, Coroutines, Room DB |
| **Crypto Engine** | Rust 2021 (`cdylib`, `jni`, `argon2`, `chacha20poly1305`, `zeroize`) |
| **Key Security** | AndroidX Biometric, Android Keystore (`KeystoreCrypto`) |
| **Networking & Billing** | OkHttp 4, Google Play Billing v6, Firebase Remote Config |
| **CI/CD** | GitHub Actions (Rust test suite 21/21 + APK build) |

---

## 🚀 Build & Development

### Requirements
- **Rust** (stable channel) with Android cross-compilation targets (`aarch64-linux-android`, `x86_64-linux-android`) and `cargo-ndk`.
- **Android Studio / SDK** with build-tools for API 34 and NDK `25.2.9519653`.
- **JDK 17**.

### Verification Commands

```bash
# 1. Run the Rust core unit tests
cd rust
cargo test --release --locked

# 2. Build the debug APK
./gradlew assembleDebug
```

---

## 🔒 Security Guarantees

1. **Zero Secrets in the Repo** — Production signing material is provided exclusively through CI environment variables.
2. **Memory Zeroization** — Keys and sensitive data in Rust are wiped with `Zeroizing<T>`; Kotlin byte arrays are cleared with `fill(0)` right after use.
3. **Hermetic JNI Boundary** — Every exposed native function is wrapped in `catch_unwind` so panics never cross into the Android runtime.
4. **Explicit Consent** — No personal information or file ever leaves the device without clear, visible user authorization.

---

## 📄 License

Distributed under the **MIT** license. See [`LICENSE`](LICENSE) for details.
