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
- **Nunca declares terminado sin evidencia.** "Debería compilar" no
  vale. Hay que correr los comandos de la sección 2 y mostrar su
  salida real (ver sección 6).
- **Cero secretos en el repositorio.** Si algo no debe ser público,
  no va al repo. El firmado de release es solo por variables de
  entorno.
- **Pequeño y verificable.** Un commit = una cosa. Mensajes
  (`feat:`, `fix:`, `docs:`).

## 2. Comandos de verificación

Ejecutar desde la raíz del repositorio. Cada comando debe terminar
con código de salida 0.

```bash
# Núcleo Rust (obligatorio para cambios en rust/)
(cd rust && cargo test --release --locked)

# Lint de Rust (sin warnings)
(cd rust && cargo clippy --release --locked -- -D warnings)

# APK Android
./gradlew assembleDebug --console=plain

# El artifact debe existir tras compilar
ls app/build/outputs/apk/debug/*.apk
```

Nota: los comandos con `cd` van entre paréntesis para no cambiar el
directorio de las líneas siguientes.

> En este repo hay un plugin de OpenCode (`.opencode/plugin/verify.ts`)
> que corre `cargo test`, `assembleDebug` y el chequeo del APK cuando
> terminas. Si alguno falla, recibirás el error: arréglalo y no
> termines hasta que pase. Aun así, corre los comandos tú mismo antes
> de declarar terminado.

## 2.1. **OBLIGATORIO: Push y verificación en GitHub Actions**

**ANTES DE DECLARAR TERMINADO:**
- Hacer `git push` a la rama correspondiente
- Verificar que **GitHub Actions** (`build.yml`) pase en CI (rust-tests + build-apk matrix + unit-tests)
- **NUNCA** asumir que la compilación local garantiza la CI — diferencias de entorno, caché, y matrix de APIs pueden fallar en CI
- Si CI falla: arreglar, volver a pushear, y repetir hasta verde

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
- No ocultar errores de comandos (`2>/dev/null`, `|| true`,
  `|| echo ...`). Si un comando falla, se ve y se arregla.
- No saltarse un chequeo porque "no está disponible". Si no puedes
  correr un comando (falta SDK, JDK, red), dilo explícitamente en
  tu respuesta final; no asumas que pasa.

## 6. Verificación (antes de declarar terminado)

Pega en tu respuesta final la salida real (o sus últimas líneas) de
cada comando de la sección 2. **Un ítem sin salida pegada no cuenta
como hecho.** Si un comando falla, arregla y vuelve a correrlo; no
declares terminado hasta que todo pase.

- [ ] `cargo test --release --locked` en `rust/` → todo verde
- [ ] `cargo clippy --release --locked -- -D warnings` en `rust/` → sin warnings
- [ ] `./gradlew assembleDebug` → compila sin errores
- [ ] `ls app/build/outputs/apk/debug/*.apk` → el APK existe
- [ ] La feature tiene sección en `specs.md`
- [ ] `features.md` actualizado (si aplica)
- [ ] `MEMORY.md` actualizado con decisiones relevantes
- [ ] Commit con mensaje descriptivo