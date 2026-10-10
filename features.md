# Roadmap de funcionalidades — Vigilix

> SDD: ninguna feature se implementa sin su sección en `specs.md`.
> Estado de cada item: `idea` → `spec` → `en-curso` → `listo` /
> `descartado`. Al moverla, actualizar el estado aquí y en `MEMORY.md`.

## Hechas (v0.7.0)

- [x] **Dashboard principal** (estado de salud, accesos directos, tarjeta de bóveda y último escaneo)
- [x] **Pestaña Seguridad** (detector de permisos sensibles en tiempo real, bloqueo de apps con root, historial de escaneos)
- [x] **Historial de escaneos persistente** (`HistoryManager`: últimos 10 escaneos con fecha, modo, archivos, hallazgos y consultas a VT)
- [x] **Detector de permisos en tiempo real** (`PermissionMonitor`: apps de terceros con ubicación, cámara, micro, contactos, SMS, llamadas)
- [x] Escáner de archivos por SHA-256 (Fast / Full) contra base de firmas local
- [x] Bóveda de contraseñas (Argon2id + ChaCha20-Poly1305) con cambio de clave
- [x] Desbloqueo biométrico de la bóveda (Android Keystore envuelve la clave)
- [x] Autocompletado del sistema (AutofillService + pantalla de desbloqueo)
- [x] Inspector de apps y permisos (cámara y micro concedidos)
- [x] Cifrador de texto standalone (`vgx1:`)
- [x] VirusTotal por hash (API key del usuario)
- [x] Generador de contraseñas de alta entropía
- [x] DNS privado AdGuard (con wizard de ADB para `WRITE_SECURE_SETTINGS`)
- [x] Congelar apps en segundo plano (root, opcional)
- [x] Tema claro/oscuro Obsidian
- [x] CI: tests Rust + build APK (GitHub Actions)
- [x] Auditoría de seguridad documentada (`SECURITY_AUDIT.md`)

## En curso

*(nada)*

## Pendientes — corto plazo

- [ ] **Testing Android** (`idea`)
  Cobertura unitaria para `VaultStore`, `HashDb`, `SecurityBridge` (modo degradado), `HistoryManager` y `PermissionMonitor` con JUnit4.
- [ ] **Export/import de bóveda en UI** (`idea`)
  Ya soportado en el core con `vaultPeek`. Diálogo con confirmación.
- [ ] **Base de firmas: descargador con firma** (`idea`)
  Descargar lista de hashes de URL fija y verificar contra SHA-256.

## Pendientes — mediano plazo

- [ ] **Modo detección colaborativa de estafas** (`idea`)
- [ ] **Informes de escaneo exportables** (`idea`)
- [ ] **i18n** (`idea`)
