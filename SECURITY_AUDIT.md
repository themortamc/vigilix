# Auditoría de seguridad de Vigilix (0.4.0 → 0.5.0)

Repositorio revisado: `themortamc/vigilix` (8 commits; el ZIP subido era idéntico al repositorio).
Alcance: código Kotlin, núcleo Rust (JNI), manifest, Gradle, workflow de CI e historial de git.

## 0. Qué se verificó y qué NO

| Qué | Estado |
|---|---|
| Lectura completa del código, manifest, Gradle y CI | Hecho |
| Historial de git buscando claves, keystores o secretos | Hecho: no hay |
| Sintaxis de todo el Kotlin (kotlinc 1.9.22) | Hecho: sin errores de sintaxis. Los tipos NO se pudieron comprobar (no hay SDK de Android en el entorno) |
| Validez de todos los XML y referencias entre recursos (`R.id`, `@string`, `@drawable`, etc.) | Hecho con xmllint y un script: sin faltantes |
| SHA de las acciones de GitHub fijadas y existencia de cargo-ndk 4.1.2 | Verificado contra los repositorios originales |
| **Compilar la app Android y el núcleo Rust** | **NO se pudo** (no hay Android SDK ni Rust en el entorno) |
| **Ejecutar las pruebas de Rust y probar la app en un teléfono** | **NO se pudo** |
| CVE de las dependencias (Material 1.11.0, AppCompat 1.6.1, coroutines 1.7.3, AGP 8.2.2, crates de Rust) | **NO verificado**. Están desactualizadas; falta correr una herramienta de auditoría |

Conclusión práctica: la compilación y la prueba real las hace tu GitHub Actions y tu teléfono. Si algo falla al compilar, lo más probable es que sea un error de tipos o de recursos y no de sintaxis.

## 1. Resumen de hallazgos

| ID | Severidad | Hallazgo | Estado en 0.5.0 |
|---|---|---|---|
| V-01 | Crítica | Resultados falsos cuando no carga el motor nativo (contraseña fija, cifrado y hash "simulados") | Corregido |
| V-02 | Alta | Salt fijo en el cifrado de Rust, igual para todos los usuarios y mensajes | Corregido |
| V-03 | Alta | "Congelar" actuaba sobre la primera app con cámara y micrófono, sin elegir ni confirmar ni deshacer | Corregido |
| V-04 | Alta | Información falsa en pantalla ("100% Protegido", hilos de escaneo, ADB habilitando congelar) | Corregido |
| V-05 | Media | `scanThreat` por coincidencia de subcadena (`mercadopago.com.ar.evil.com` salía SEGURO) | Eliminado |
| V-06 | Media | Root pedido en el hilo principal al abrir la app | Corregido |
| V-07 | Media | Comandos de shell armados con texto interpolado | Corregido |
| V-08 | Media | Rust: `unwrap/expect` en JNI, errores devueltos como texto, sin borrado de memoria | Corregido |
| V-09 | Media | Manifest: `allowBackup=true`, permisos innecesarios, ícono de sistema | Corregido |
| V-10 | Media | Datos sensibles sin protección en pantalla y portapapeles | Corregido |
| V-11 | Media | El escáner nunca funcionó (nunca pedía el permiso de almacenamiento) | Reemplazado |
| V-12 | Media | Release sin minificar; el CI entrega APK de debug con clave distinta en cada ejecución | Parcial (ver 4) |
| V-13 | Media | CI: acciones por tag, sin `permissions`, cargo-ndk sin versión, sin pruebas | Corregido (ver 4) |
| V-14 | Baja | Etiqueta de entropía incorrecta ("6 bits/carácter") | Corregido |
| V-15 | Baja | Dependencias antiguas | Pendiente |

## 2. Detalle

### V-01 Resultados falsos si falla el motor nativo (Crítica)
**Antes:** si `libvigilix_core` no cargaba, la app mostraba una contraseña fija escrita en el código como si fuera "generada", un texto cifrado "simulado" y un hash "simulado". Peor: la bóveda borraba el texto original después de "cifrar", así que se perdía el dato. Pasaba en la práctica porque `abiFilters` incluía `x86_64` pero el CI solo compilaba `arm64-v8a`.
**Ahora:** no existe ningún valor de relleno. Si el motor falla, la app lo dice con claridad y no borra nada de lo que escribiste. El hash de archivos usa `MessageDigest` real de Java como respaldo. El CI compila `arm64-v8a` y `x86_64`, que es lo que declara Gradle.

### V-02 Salt estático (Alta)
**Antes:** la clave se derivaba con Argon2 usando siempre el mismo salt (`VIGILIX_SALT_SECURE_2026`). Con el mismo texto y clave el resultado era siempre igual, y un atacante podía precalcular una tabla para todos los usuarios. Se usaba `Argon2::default()` sin guardar los parámetros.
**Ahora:** Argon2id con salt aleatorio de 16 bytes por mensaje, 64 MiB de memoria, 3 pasadas, 1 hilo. ChaCha20-Poly1305 con nonce aleatorio de 96 bits y datos autenticados `vigilix/v1`. Formato versionado: `vgx1:` + hex(salt ‖ nonce ‖ cifrado ‖ etiqueta). Cualquier modificación del texto hace fallar el descifrado.

### V-03 Congelado de apps (Alta)
**Antes:** el botón actuaba sobre la primera app que *pedía* cámara y micrófono en el manifest, sin elegir, sin confirmar, sin deshacer y en el hilo principal. La auditoría contaba permisos pedidos, no concedidos. Además, ADB con `WRITE_SECURE_SETTINGS` no habilita `am`/`appops`: solo root puede congelar, pero el texto decía lo contrario.
**Ahora:** la pestaña Apps lista las apps instaladas por vos con ambos permisos **concedidos**. Al tocar una, "Abrir ajustes de la app" está disponible para todos. La restricción en segundo plano necesita root, pide confirmación, es reversible ("Quitar restricción") y corre fuera del hilo principal. El nombre de paquete se valida con una expresión regular antes de armar el comando. Se llama "restringir" y no "congelar" porque eso es lo que hace: no borra datos ni quita permisos.

### V-04 Información falsa (Alta)
**Antes:** "100% Protegido" y "Hilos de escaneo en segundo plano" estaban escritos a mano; la insignia "ADB / ESTÁNDAR" aparecía siempre que el teléfono no tenía root.
**Ahora:** el Inicio muestra controles reales y un resumen "N de M en orden": motor cargado, bloqueo de pantalla, antigüedad del parche de seguridad y estado real del DNS privado (leído de los ajustes). El permiso opcional de DNS se muestra aparte y no cuenta en el resumen. La insignia superior dice "Modo estándar", "Root activo" o "Sin root" según lo que se comprobó.

### V-05 `scanThreat` (Media)
Comparaba por subcadena y nunca se llamaba desde la interfaz. Se eliminó en vez de arreglarlo: una lista fija de dominios no protege de forma real y da falsa seguridad.

### V-06 Root en el hilo principal (Media)
**Antes:** `hasRootAccess()` ejecutaba `su` en `onCreate`: riesgo de ANR y aviso de Magisk al abrir la app.
**Ahora:** el root se pide solo cuando tocás una acción que lo necesita, en un hilo de fondo, con un vigilante que destruye el proceso si se cuelga (30 s la primera vez porque la persona tiene que aceptar el aviso; 10 s para el resto).

### V-07 Shell con interpolación (Media)
Hoy los valores eran constantes, pero el patrón era peligroso. Ahora no hay shell genérico: el DNS usa un `enum` con valores fijos (`DnsProvider`), el paquete se valida con `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` y, si la app tiene `WRITE_SECURE_SETTINGS`, el DNS se cambia con `Settings.Global.putString` sin pasar por un shell. Después de cambiar se vuelve a leer el ajuste real: no se confía solo en el código de salida del comando.

### V-08 Núcleo Rust (Media)
Cada función JNI usa `catch_unwind` (un panic ya no tira la app) y devuelve `null` ante cualquier error, en lugar de textos como `ERROR_KDF` que la interfaz podía mostrar como resultado. Claves, textos y contraseñas se guardan en `Zeroizing` y se borran de la memoria al soltarse. El generador queda limitado a 8–128 caracteres y garantiza mayúscula, minúscula, número y símbolo. Perfil release con LTO, `strip` y `overflow-checks`.
**Límite que sigue existiendo:** la clave y el texto pasan por `String` de Kotlin/Java, que no se pueden borrar de la memoria a mano.

### V-09 Manifest (Media)
Se quitaron `INTERNET` (la app no usa la red; VirusTotal se abre en el navegador), `READ_EXTERNAL_STORAGE` y `MANAGE_EXTERNAL_STORAGE`. `allowBackup=false` con reglas explícitas de extracción de datos. Ícono propio adaptativo en lugar del de sistema. Se mantienen `QUERY_ALL_PACKAGES` (necesario para listar apps; Google Play lo restringe y pide justificarlo) y `WRITE_SECURE_SETTINGS` (opcional, solo se concede por ADB).

### V-10 Datos sensibles (Media)
`FLAG_SECURE` solo mientras se ve la Bóveda (bloquea capturas y vista previa en "recientes"). Lo copiado desde la Bóveda se marca como sensible y se borra del portapapeles a los 45 segundos (solo si lo copiado sigue siendo lo nuestro). Los campos de clave y texto usan `textNoSuggestions`, `importantForAutofill=no` y `saveEnabled=false`. Si la app pasa a segundo plano, la Bóveda se borra de la pantalla a los 30 segundos.
**Límite:** el borrado del portapapeles depende de que el proceso siga vivo; si Android lo mata antes, no se ejecuta.

### V-11 Escáner (Media)
Nunca funcionó: no pedía el acceso total al almacenamiento en tiempo de ejecución. Se reemplazó por **Verificar archivo**: se eligen archivos con el selector del sistema (sin permisos), se calcula el SHA-256 y se ofrece compararlo en VirusTotal (solo se envía la huella, nunca el archivo). La pantalla aclara que **no detecta virus**: sirve para comparar. Si se elige un `.apk`, avisa que no lo instales si la huella no coincide.

### V-14 Entropía (Baja)
El juego de caracteres tiene 88 símbolos, es decir log2(88) ≈ 6,46 bits por carácter, no 6. La pantalla ahora calcula el valor según la longitud elegida.

## 3. Lo que ya estaba bien
- `gen_range` de `rand` no tiene sesgo de módulo; el generador usa el CSPRNG del sistema.
- Nonce aleatorio de 96 bits en cada cifrado.
- A VirusTotal solo viaja el hash, no el archivo.
- No hay claves, keystores ni secretos en el historial de git.
- La actividad exportada es la del lanzador, como corresponde.

## 4. Pendientes y recomendaciones

1. **Firma de release (V-12).** El CI entrega un APK de debug: es depurable y su clave cambia en cada ejecución, así que no se puede actualizar sobre una instalación anterior. Recomendado: generar un keystore propio, guardarlo en *GitHub Secrets* (archivo en base64, contraseña y alias), configurar `signingConfigs.release` leyendo variables de entorno y publicar el APK de release. No se agregó nada de esto para no meter secretos ni configuración que no pude probar. El CI ya intenta `assembleRelease` (con R8) como verificación que no bloquea.
2. **`rust/Cargo.lock`.** No pude generarlo (no hay Cargo en el entorno). Ejecutá `cargo generate-lockfile` dentro de `rust/` y subilo: el CI lo detecta solo y pasa a usar `--locked`. Mientras no exista, avisa con una advertencia.
3. **Gradle wrapper.** El CI lo genera en cada ejecución. Lo ideal es subir `gradlew` y `gradle/wrapper/` al repositorio y validar el wrapper.
4. **Dependencias (V-15).** Activá Dependabot (`package-ecosystem: gradle`, `cargo` y `github-actions`) y corré `cargo audit` o `cargo deny`. No cambié versiones porque no puedo compilar ni probar aquí.
5. **Bóveda persistente (idea a futuro).** Hoy no guarda nada, por diseño. Si algún día guarda datos, que la clave de la base esté protegida con Android Keystore y no solo con una clave maestra.
6. **Pruebas.** Las pruebas unitarias de Rust están incluidas (ida y vuelta con Unicode, salt distinto por mensaje, clave incorrecta, texto modificado, entradas inválidas, clases de caracteres y un SHA-256 conocido) pero **no se ejecutaron**. Corren en un job aparte del CI: si fallan, se ve en rojo pero el APK se sigue generando.

## 5. Compatibilidad: leer antes de actualizar
- **Los textos cifrados con la 0.4.0 no se pueden descifrar con la 0.5.0** (formato y derivación de clave nuevos). La Bóveda 0.4.0 no guardaba nada, pero si copiaste algún texto cifrado antes, quedó inutilizable.
- Las funciones JNI cambiaron (`generateHighEntropyPassword(length)`, `encryptSecret`, `decryptSecret`, `sha256OfFd`; `scanThreat` ya no existe). Kotlin y Rust tienen que actualizarse juntos, y así viene en este paquete.
- Cambió la interfaz completa: cuatro pestañas (Inicio, Archivos, Bóveda, Apps), tema claro y oscuro estilo Obsidian.

## 6. Lista de prueba en el teléfono
1. **Inicio:** "Motor de cifrado" en verde. Cambiá el DNS con la opción manual y volvé a abrir la app: el estado debe reflejar el ajuste real.
2. **Archivos:** elegí un archivo y comparalo con `sha256sum` en una PC; tiene que coincidir. Debe decir "motor Rust".
3. **Bóveda:** generá una contraseña, copiala y verificá que se borra del portapapeles a los 45 s. Probá una captura de pantalla (debe estar bloqueada). Cifrá un texto, descifralo con la misma clave y probá con una clave incorrecta (debe dar error sin borrar lo escrito).
4. **Apps:** comparar la lista con Ajustes > Privacidad > Administrador de permisos.
5. **CI:** revisar que los dos jobs terminen y que aparezca el artefacto `Vigilix-Debug-APK`.
