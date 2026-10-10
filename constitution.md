# Constitution de Vigilix

> Documento normativo de máximo nivel. Todo agente de IA, revisor o
> colaborador humano que toque este repositorio DEBE leer este archivo antes
> de escribir una sola línea de código. En caso de conflicto entre
> instrucciones, el orden de precedencia es:
>
> 1. Constitución (este archivo)
> 2. `MEMORY.md` — estado actual y decisiones tomadas
> 3. `specs.md` — especificación técnica
> 4. `features.md` — roadmap de funcionalidades
> 5. `AGENTS.md` — instrucciones operativas para agentes de IA

---

## 1. Identidad del proyecto

Vigilix es una **herramienta de ciberseguridad móvil para Android**:
escáner de archivos, bóveda de contraseñas, inspector de permisos y
utilidades forenses. El propósito es **proteger a la persona que lo usa**
de estafas telefónicas, phishing y archivos maliciosos.

- El usuario es una persona no experta. La claridad de los mensajes de
  error importa tanto como la corrección del código.
- Detección por coincidencia exacta de hash. **Nunca** afirmar que
  Vigilix "detecta malware" sin matizar: solo conoce lo que está en su
  base de firmas local.

## 2. Invariantes de seguridad (no negociables)

Estas reglas tienen prioridad sobre cualquier otra. Un cambio que las
viola se rechaza aunque el código compile y los tests pasen.

1. **Cero secretos en el repositorio.** Ningún keystore, contraseña, API
   key o material de firma se commitea. El firmado de release se
   configura SOLO por variables de entorno
   (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
2. **La criptografía vive en Rust.** Kotlin no toca bytes en claro de la
   bóveda ni claves derivadas. El puente JNI (`SecurityBridge`) es la
   única frontera.
3. **Nada cruza la frontera JNI sin protección:**
   - Todo `extern "system"` en Rust está envuelto en
     `catch_unwind` + `AssertUnwindSafe`. Ningún panic llega a Kotlin.
   - Ante cualquier fallo, las funciones JNI devuelven `null` / `false` /
     `-1` (según el tipo). **Nunca** se inventa un valor de relleno
     (hash falso, contraseña inventada, texto cifrado simulado).
4. **Memoria ceroizada.** Toda clave, contraseña o plaintext en Rust se
   guarda en `Zeroizing<T>`. En Kotlin, los arreglos de bytes que
   contienen claves se borran con `fill(0)` apenas se usan.
5. **Salts y nonces aleatorios por operación**, generados con `OsRng`.
   Nunca reutilizar salt ni nonce. Los formatos son versionados
   (`vgx1:`, `vgv1:`) y el AAD distingue texto suelto de bóveda.
6. **El vault usa `FLAG_SECURE`** mientras está abierto (sin
   screenshots/recents). La entrada al portapapeles desde la bóveda
   expira a los 45 segundos.
7. **Nunca subir datos del usuario a red sin consentimiento explícito.**
   La integración con VirusTotal envía **solo el hash**, nunca el
   archivo. La actualización de firmas es opcional y visible en la UI.
8. **Permisos mínimistas.** Cada `uses-permission` en el manifiesto debe
   justificar por qué existe. Al añadir uno, actualizar también el
   comentario en el manifiesto.

## 3. Estilo de código

### Rust (núcleo `rust/`)
- Edition 2021. Perfil release con `opt-level = 3`, `lto = true`,
  `codegen-units = 1`, `strip = true`, `overflow-checks = true`.
- La lógica pura (sin JNI) se separa de la capa JNI para ser testeable:
  `encrypt_text`, `vault_create`, `hashdb_contains`… son funciones
  públicas simples. La capa `#[no_mangle]` solo traduce y protege.
- Nombres de funciones JNI fijos por convención
  `Java_com_vigilix_app_SecurityBridge_<nombre>`. Renombrar exige
  cambiar ambos lados en el mismo commit.
- Tests en `rust/src/lib.rs` bajo `#[cfg(test)] mod tests`. El módulo
  global de sesión del vault se protege con
  `VAULT_TEST_LOCK` para ejecutar esas tests de a una.

### Kotlin (app Android)
- Idioma principal. `minSdk 26`, `targetSdk 34`, JVM 17.
- View Binding activado. Sin view models ni arquitectura reactiva
  pesada: el patrón vigente es actividad + helpers `object`
  (estado simple, fácil de seguir).
- Los recursos (strings, colores) están **solo** en
  `res/values/strings.xml` y `res/values/colors.xml` (con variantes
  `-night`). No hardcodear texto en layouts ni en código.
- Coroutines para trabajo asíncrono (`kotlinx-coroutines-android`).
  Los escaneos largos corren en `ScanService` (foreground service).
- `SecurityBridge` es el único punto de contacto con nativo. Si falla
  la librería (`isLoaded == false`), la app sigue funcionando en modo
  degradado devolviendo `null`, y la UI muestra "motor no disponible".

### Nombres y estructura
- Kotlin: `PascalCase` para clases/objects, `camelCase` para funciones.
  Uno object/funcionalidad por archivo, en `com.vigilix.app`.
- Rust: `snake_case` para funciones, `SCREAMING_SNAKE` para constantes.
  Un único `lib.rs` mientras el código quepa; si supera ~2000 líneas,
  extraer módulos (`crypto.rs`, `vault.rs`, `hashdb.rs`) manteniendo
  los `pub` de la API.

## 4. Proceso de cambios

1. **Leer** este archivo, `MEMORY.md` y `specs.md` antes de tocar código.
2. **Pequeño y verificable.** Commits con mensaje descriptivo
   (`feat:`, `fix:`, `docs:`). Un commit = una cosa.
3. **Test primero** para cualquier cambio en la capa cripto/hashdb
   (Rust). La capa UI puede ir detrás, pero sin romper la compilación.
4. **Verificar antes de declarar terminado** (ver `AGENTS.md` §
   "Verificación"). Ningún cambio se considera completo sin:
   - `cargo test --release` (en `rust/`) → todo verde
   - `./gradlew assembleDebug` → compila
   - Sin warnings nuevos de `cargo clippy` si está disponible
5. **No romper la CI** (`.github/workflows/build.yml`): el job
   `rust-tests` debe quedar verde; `build-apk` también.

## 5. Lo que NO se hace

- No se añaden dependencias sin justificar en el PR/commit. La app
  mantiene dependencias al mínimo.
- No se cambia el formato de `vgx1:`/`vgv1:` sin un plan de
  migración documentado en `specs.md` y un bump de versión del
  formato.
- No se elimina `Cargo.lock` ni se edita a mano.
- No se sube a `main` con warnings de lint nuevos.
- No se documenta en el README algo que no está en el código.
- No se agregan features del roadmap de `features.md` sin antes
  escribir su sección en `specs.md` (SDD: spec antes que código).

## 6. Propiedad y licencia

- Proyecto personal, **licencia MIT** (ver `LICENSE`).
- Publico en GitHub (`github.com/themortamc/vigilix`). Todo lo que
  entre al repo debe ser apto para ser público: sin datos locales,
  sin referencias a infraestructura privada, sin secretos.
