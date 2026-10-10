# AGENTS — Vigilix

> Instrucciones operativas para cualquier agente de IA (Claude Code,
> Codex, opencode, u otro) que trabaje en este repositorio.
>
> **Antes de escribir una línea de código, lee en este orden:**
> 1. `constitution.md` (invariantes de seguridad, no negociables)
> 2. `MEMORY.md` (estado actual y decisiones)
> 3. `specs.md` (cómo funciona cada pieza)
> 4. `features.md` (roadmap)
> 5. Este archivo (`AGENTS.md`)

---

## 1. Reglas de oro

- **Spec-driven development (SDD).** Antes de escribir código, la
  feature debe tener su sección en `specs.md`. Si no la hay, escribe
  la sección allí primero. No hay excepciones.
- **Tests primero** para cualquier cambio en `rust/`. La capa UI
  puede ir detrás, pero sin romper la compilación.
- **No romper la CI.** Verificar antes de declarar terminado.
- **Cero secretos en el repositorio.** Si algo no debe ser público,
  no va al repo. El firmado de release es solo por variables de
  entorno.
- **Pequeño y verificable.** Un commit = una cosa. Mensajes
  (`feat:`, `fix:`, `docs:`).

## 2. Comandos de verificación

```bash
# Núcleo Rust (obligatorio para cambios en rust/)
cd rust && cargo test --release --locked
# APK Android
./gradlew assembleDebug
# Lint rápido (si está disponible)
cargo clippy --release 2>/dev/null || echo "clippy no disponible"
```

## 3. Flujo de trabajo típico

1. **Leer** `constitution.md`, `MEMORY.md`, `specs.md`.
2. **Si es nueva feature:** escribir su sección en `specs.md` y
   marcarla `idea` → `spec` en `features.md`.
3. **Test fallido primero** (TDD) para la capa Rust.
4. **Implementar** el mínimo para pasar el test.
5. **Verificar** con los comandos de la sección 2.
6. **Documentar** en `MEMORY.md` cualquier decisión no trivial.
7. **Commit** con mensaje descriptivo. No subir sin verificación.

## 4. Invariantes que no se rompen

- Formatos `vgx1:` / `vgv1:` son fijos. Cambiarlos exige migración
  documentada.
- La capa JNI en Rust siempre envuelta en `catch_unwind`. Ningún
  panic cruza la frontera.
- `SecurityBridge` es el único puente Kotlin↔Rust.
- Ante fallo, las funciones JNI devuelven `null`/`false`/`-1`. Nunca
  valores de relleno.
- Claves, contraseñas y plaintext en Rust se guardan en `Zeroizing<T>`.
- En Kotlin, los arreglos de bytes con claves se borran con `fill(0)`.
- Permisos del manifiesto: cada uno debe tener un comentario
  justificativo.
- Nada se sube a red sin consentimiento explícito visible en la UI.

## 5. Errores comunes al evitar

- No hardcodear texto en layouts o código: todo va en `strings.xml`.
- No mezclar mayúsculas/minúsculas en recursos (colores, estilos) con
  nombres inconsistentes.
- No cambiar el nombre de una función JNI sin cambiar ambos lados
  en el mismo commit.
- No eliminar `Cargo.lock` ni editarlo a mano.
- No asumir que el motor nativo está disponible: la UI debe
  manejar `isLoaded == false` (modo degradado).
- No silenciar fallos con "se asume que está bien"; usar `null` y
  dejar que la UI muestre el error.

## 6. Verificación (antes de declarar terminado)

- [ ] `cargo test --release --locked` en `rust/` → todo verde
- [ ] `./gradlew assembleDebug` → compila sin errores
- [ ] Sin warnings nuevos de `cargo clippy` (si está disponible)
- [ ] La feature tiene sección en `specs.md`
- [ ] `features.md` actualizado (si aplica)
- [ ] `MEMORY.md` actualizado con decisiones relevantes
- [ ] Commit con mensaje descriptivo
