# Memoria del proyecto — Vigilix

> Estado actual, decisiones tomadas y lo que el siguiente agente
> (humano o IA) debe saber antes de tocar una línea de código.
>
> **Última actualización:** 2026-10-10 (v0.8.0 — Reorganización total de arquitectura de información).

## Estado actual

- **Versión:** 0.8.0 (`versionCode=6`, `versionName="0.8.0"`).
- **CI:** `rust-tests` (21 tests) y `build-apk` en `.github/workflows/build.yml`.
- **Estructura:** `app/` (Kotlin Android), `rust/` (núcleo cdylib + tests).

## Reorganización IA v2.0 (2026-10-10)

1. **4 Pestañas Principales en BottomNav:**
   - 🏠 **Inicio**: Dashboard general con indicador de salud, estado de bóveda, último escaneo y controles del sistema.
   - 🛡️ **Escáner**: Escaneo de teléfono (Rápido/Completo), verificación de archivos sueltos, historial y firmas/VT.
   - 🔐 **Bóveda**: Bóveda de contraseñas limpia y enfocada en credenciales.
   - 📱 **Privacidad**: Inspector unificado de apps con filtros por riesgo (Cámara/Mic, Ubicación, SMS) y restricción root.
2. **Panel de Herramientas Modal (🔧):**
   - Acceso desde la barra superior a Cifrador de Texto (`vgx1:`) y Generador de Contraseñas de alta entropía.

## Comandos de verificación

```bash
# Rust core (21 tests)
cd rust && cargo test --release --locked
```
