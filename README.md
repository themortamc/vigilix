# Vigilix

A mobile cybersecurity tool for Android — file scanner, password vault, app permission inspector, and forensic utilities.

Built with **Kotlin (Android)** and a **Rust** core (JNI) for crypto and hashing.

## About

Vigilix is a privacy-first security toolkit. It scans installed apps and files for known malware signatures, stores passwords in an encrypted vault, and provides utilities for inspecting app permissions and freezing background-activity.

> **Note:** This is a personal project. Detection is by exact hash match — it cannot detect unknown or variant malware. Always verify findings with multiple sources before taking action.

## Features

- **File scanner** — SHA-256 scan of storage and installed apps against a local malware-signature database (Fast / Full modes)
- **Password vault** — Argon2id + ChaCha20-Poly1305 encrypted vault with master key, biometric unlock, and auto-fill support
- **App inspector** — list installed apps with granted permissions, restrict background activity (requires root)
- **Text encryptor** — standalone encrypt/decrypt with the same crypto core
- **VirusTotal integration** — query hashes via your own API key (hash only, never the file)
- **Dark/light theme** — Material Design 3

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin (Android), Rust (crypto core via JNI) |
| Build | Gradle 8.2.2, Android Gradle Plugin 8.2.2 |
| UI | Material Components 1.11.0, AndroidX |
| Crypto | Argon2id, ChaCha20-Poly1305, AndroidX Biometric |
| CI | GitHub Actions (APK build + Rust tests) |

## Project Structure

```
app/
  build.gradle.kts
  proguard-rules.pro
  src/main/          # Kotlin sources, resources, manifest
rust/
  Cargo.toml         # JNI core: encryption, hashing, vault, signature DB
build.gradle.kts    # Root build config
settings.gradle.kts
gradle.properties
SECURITY_AUDIT.md   # Full security audit of the codebase
.github/workflows/  # CI: APK build + Rust unit tests
```

## Getting Started

### Prerequisites
- Android Studio (AGP 8.2.2, Kotlin 1.9.22)
- Rust toolchain with `cargo-ndk` for building the JNI library
- Android NDK

### Build

```bash
# From project root
./gradlew assembleDebug      # build APK
# Rust core (run tests)
cd rust && cargo test --locked
```

The CI workflow (`.github/workflows/`) builds the APK and runs the Rust test suite on every push.

## Security Notes

The full security review is in [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md). Key points:

- Crypto uses per-message random salt and nonce; format is versioned (`vgx1:` / `vgv1:`)
- No secrets or keystores are committed to git
- Vault data is `FLAG_SECURE` (no screenshots) while open
- Clipboard entries from the vault auto-expire after 45 seconds
- Release signing is optional — configure `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` env vars in `build.gradle.kts` for signed releases

## License

See `LICENSE` (MIT).
