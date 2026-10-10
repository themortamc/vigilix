# Roadmap de funcionalidades — Vigilix

> SDD: ninguna feature se implementa sin su sección en `specs.md`.
> Estado de cada item: `idea` → `spec` → `en-curso` → `listo` /
> `descartado`. Al moverla, actualizar el estado aquí y en `MEMORY.md`.

## Hechas (v0.8.0)

- [x] **Rediseño total de Arquitectura de Información (IA v2.0)**
  - 4 pestañas unificadas: Inicio, Escáner, Bóveda, Privacidad.
  - Integración sin solapamientos entre permiso de apps y auditoría de privacidad.
  - Panel modal de Herramientas Cripto accesible desde la barra superior (🔧).
- [x] **Dashboard estilo Obsidian** (indicador de salud, accesos rápidos de 1-tap, estado de bóveda y escaneo).
- [x] **Privacidad e Inspector Unificado** (filtro por Cámara/Mic, Ubicación, SMS/Contactos, y restricción root en segundo plano).
- [x] **Historial de escaneos integrado** (`HistoryManager`: últimos 10 escaneos).
- [x] Escáner de archivos por SHA-256 (Fast / Full) contra base de firmas local.
- [x] Bóveda de contraseñas (Argon2id + ChaCha20-Poly1305) con cambio de clave.
- [x] Desbloqueo biométrico de la bóveda (Android Keystore envuelve la clave).
- [x] Autocompletado del sistema (AutofillService + pantalla de desbloqueo).
- [x] VirusTotal por hash (API key del usuario).
- [x] Generador de contraseñas de alta entropía.
- [x] DNS privado AdGuard (con wizard de ADB para `WRITE_SECURE_SETTINGS`).
- [x] CI: tests Rust + build APK (GitHub Actions).

## Pendientes — corto plazo

- [ ] **Testing Android** (`idea`)
  Cobertura unitaria para `VaultStore`, `HashDb`, `SecurityBridge` (modo degradado), `HistoryManager` y `PermissionMonitor` con JUnit4.
- [ ] **Export/import de bóveda en UI** (`idea`)
  Ya soportado en el core con `vaultPeek`. Diálogo con confirmación.
