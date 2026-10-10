# Memoria del proyecto — Vigilix

> Estado actual, decisiones tomadas y lo que el siguiente agente
> (humano o IA) debe saber antes de tocar una línea de código.
>
> **Última actualización:** 2026-10-10 (v0.7.0 — Dashboard, pestaña Seguridad e Historial).

## Estado actual

- **Versión:** 0.7.0 (`versionCode=5`, `versionName="0.7.0"`).
- **CI:** `rust-tests` (21 tests) y `build-apk` en `.github/workflows/build.yml`.
- **Estructura:** `app/` (Kotlin Android), `rust/` (núcleo cdylib + tests),
  `.github/workflows/build.yml`, `SECURITY_AUDIT.md`.
- **Ubicación:** `/home/mortamc/Downloads/vigilix/`.

## Cambios realizados (v0.7.0)

1. **Dashboard principal (Obsidian UI):** salud del dispositivo con porcentaje real, tarjeta de bóveda con recuento de contraseñas, tarjeta de último escaneo con tiempo relativo, y accesos rápidos.
2. **Pestaña Seguridad:** detector de permisos sensibles en tiempo real (`PermissionMonitor`), bloqueo/desbloqueo de apps en segundo plano con root (`PrivilegedEngine`), y historial de los últimos 10 escaneos (`HistoryManager`).
3. **Persistencia del escaneo:** `ScanEngine` guarda fecha, modo, archivos analizados y hallazgos en `Prefs` y `HistoryManager` al terminar.
4. **Harness e Invariantes intactos:** 21 tests de Rust pasando verde. Sin secretos, sin dependencias externas pesadas, sin datos del usuario saliendo del teléfono.

## Comandos de verificación

```bash
# Rust core (21 tests)
cd rust && cargo test --release --locked
```
