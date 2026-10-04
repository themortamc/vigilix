# Auditoría de seguridad de Vigilix (0.4.0 → 0.6.0)

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

1. **Firma de release (V-12).** El CI entrega un APK de debug: es depurable y su clave cambia en cada ejecución, así que no se puede actualizar sobre una instalación anterior. Recomendado: generar un keystore propio, guardarlo en *GitHub Secrets* (archivo en base64, contraseña y alias), configurar `signingConfigs.release` leyendo variables de entorno y publicar el APK de release. Desde la 0.5.1 `build.gradle.kts` ya soporta firma opcional: si están definidas `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`, `assembleRelease` firma con ese keystore; si no, genera un APK sin firmar en vez de fallar. Falta cargar el keystore en GitHub Secrets y agregar el paso en el workflow. El CI ya intenta `assembleRelease` (con R8) como verificación que no bloquea.
2. **`rust/Cargo.lock`.** Hecho (0.5.1): ya está en el repositorio y el CI usa `--locked`. Actualizalo con `cargo update` cuando cambies dependencias y volvé a subirlo.
3. **Gradle wrapper.** El CI lo genera en cada ejecución. Lo ideal es subir `gradlew` y `gradle/wrapper/` al repositorio y validar el wrapper.
4. **Dependencias (V-15).** Activá Dependabot (`package-ecosystem: gradle`, `cargo` y `github-actions`) y corré `cargo audit` o `cargo deny`. No cambié versiones porque no puedo compilar ni probar aquí.
5. **Bóveda persistente.** Hecho en la 0.6.0 (ver sección 8).
6. **Pruebas.** Las pruebas unitarias de Rust están incluidas (ida y vuelta con Unicode, salt distinto por mensaje, clave incorrecta, texto modificado, entradas inválidas, clases de caracteres y un SHA-256 conocido) y desde la 0.6.0 **sí se ejecutaron** (21 pruebas, todas pasan en un entorno de Linux con Rust 1.85). Corren en un job aparte del CI: si fallan, se ve en rojo pero el APK se sigue generando.

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

## 7. Cambios de la 0.5.1 (corrección de compilación)
- `activity_main.xml` y `values/colors.xml` se habían reemplazado por versiones reducidas que ya no tenían los IDs ni los colores que usa el Kotlin (`scrollFiles`, `scrollVault`, `scrollApps`, `tvAdbCommand`, etc.): por eso fallaba `compileDebugKotlin`. Se restauraron las versiones completas y se conservaron tus retoques de la barra superior (escudo de 28 dp con color de acento, título de 20 sp, sin divisor).
- Con los colores reducidos, `vx_text`, `vx_border`, `vx_accent` y otros solo existían en `values-night`: el APK habría compilado igual pero se caía al abrirlo en modo claro.
- `isGranted()` usaba `ContextCompat.hasPermission`, que no existe, y además comprobaría los permisos de Vigilix en vez de los de la app revisada. Se volvió a comparar `requestedPermissionsFlags` (arreglo paralelo a `requestedPermissions`, mismo índice).
- La firma de release ya no rompe `assembleRelease` cuando faltan las variables de entorno.
- Se eliminó `color/bottom_nav_color_selector.xml` (restos del diseño anterior, sin uso).

## 8. Versión 0.6.0: escáner completo, contraseñas guardadas y autocompletado

### 8.1 Qué se agregó
- **Escáner del teléfono** (pestaña Escáner): recorre el almacenamiento y las apps instaladas, calcula el SHA-256 de cada archivo con el motor Rust y lo compara con una lista local de malware conocido. Modo *Rápido* (ejecutables, instaladores, scripts, comprimidos, PDF y documentos de Office) o *Completo* (todo hasta 2 GB por archivo). Corre como servicio en primer plano, con notificación y botón de cancelar.
- **Lista local de firmas:** archivo binario de hashes ordenados, con búsqueda binaria directa sobre el archivo (sin cargarla en memoria; máximo 2.000.000 de firmas). Se alimenta de listas en texto, CSV o ZIP: cualquier formato donde aparezcan SHA-256 de 64 caracteres. Se actualiza desde internet (solo HTTPS, solo cuando la persona lo pide) o importando un archivo, sin red. Incluye el hash del archivo de prueba EICAR para poder verificar que todo funciona.
- **VirusTotal gratis:** consulta por hash con la API pública y la clave del propio usuario (se guarda cifrada con el Keystore). Respeta el límite gratuito (4 por minuto, 500 por día): una consulta cada 15,5 s, tope de 40 por escaneo y 480 por día, con caché de 7 días. Primero las apps instaladas, después los ejecutables sueltos.
- **Bóveda de contraseñas:** sitio o app de origen, usuario, contraseña, notas y favoritos. Búsqueda, copia rápida de usuario y de clave desde la lista (la clave se marca como sensible y se borra del portapapeles a los 45 s), abrir el sitio o la app, y generador integrado.
- **Autocompletado del sistema** (Android 8+): sirve en apps y en navegadores que lo soporten (Chrome pide activarlo en Ajustes > Contraseñas y autocompletado > Servicio de autocompletado).
- **Desbloqueo configurable:** solo clave maestra, o clave maestra + huella. Bloqueo automático (al salir, 1, 5 o 15 minutos) y bloqueo al apagar la pantalla.
- **Copias cifradas:** exportar la bóveda a un archivo (ya cifrado, se puede guardar en cualquier carpeta, incluida una sincronizada) e importar una copia con su propia clave maestra.
- Las herramientas de la 0.5 (generador y cifrado de texto suelto) se mantienen debajo de la bóveda.

### 8.2 Diseño de seguridad de la bóveda
- Formato `vgv1:` + hex(salt ‖ nonce ‖ cifrado ‖ etiqueta). Argon2id (64 MiB, 3 pasadas) → ChaCha20-Poly1305. AAD propio (`vigilix/vault/v1`): un texto cifrado suelto (`vgx1:`) no se puede hacer pasar por una bóveda.
- La clave derivada vive **solo en la sesión de Rust** y se borra (`Zeroizing`) al bloquear. Cada guardado usa un nonce nuevo con el mismo salt. Escritura atómica: archivo temporal, `fsync` y renombrado.
- **Huella:** la clave derivada se envuelve con una clave del Android Keystore (AES-GCM de 256 bits) que exige biometría fuerte **en cada uso** y se invalida si cambian las huellas registradas. Si se invalida, vuelve a pedirse la clave maestra. Cambiar la clave maestra desactiva la huella.
- **Autocompletado:** la coincidencia es estricta: dominio igual o subdominio del guardado (`accounts.google.com` sirve para `google.com`; `google.com.evil.com` y `evilgoogle.com` no), o el mismo nombre de paquete de la app vinculada. Con la bóveda bloqueada exige huella o clave maestra antes de mostrar nada. Nunca se autocompleta dentro de la propia Vigilix.
- Pantalla protegida contra capturas (`FLAG_SECURE`) en la pestaña Contraseñas y en la pantalla de desbloqueo del autocompletado. Sin copias de seguridad automáticas de Android (`allowBackup=false`).
- 5 intentos fallidos seguidos activan una espera creciente en la pantalla. No es un límite persistente: el costo de Argon2id es la defensa real contra adivinar la clave.

### 8.3 Límites que siguen existiendo (importante)
- **Detección por hash exacto:** solo encuentra archivos idénticos a muestras ya conocidas. No detecta variantes nuevas, malware desconocido ni comportamiento sospechoso. "No encontré nada" no significa "es seguro". La pantalla lo aclara.
- **Alcance en Android:** sin root, Android no deja leer `/Android/data`, `/Android/obb` ni los datos privados de otras apps. Sí se leen los APK instalados (sus códigos son legibles) y el almacenamiento compartido.
- **Cobertura de la lista:** la fuente preconfigurada es MalwareBazaar (abuse.ch), que exige una Auth-Key gratuita y publica sobre todo muestras de las últimas horas, mayormente de Windows. Para el teléfono sirve más VirusTotal. La dirección de descarga (`mb-api.abuse.ch/v2/files/exports/<clave>/recent.csv`) la tomé del ejemplo de la documentación de abuse.ch y **no la pude probar con una clave real**: si responde error, usá "Otra dirección" o importá el archivo a mano.
- **VirusTotal:** el plan público es para uso personal y no comercial, y cada usuario tiene que usar su propia clave. Si la app llega a monetizarse, hay que pasar a un plan comercial.
- **Memoria:** mientras la bóveda está abierta, las contraseñas están en memoria como `String` de Java, que no se pueden borrar a mano (el recolector las libera después). La lista descifrada se descarta al bloquear.
- **Autocompletado:** el servicio ve la estructura de la pantalla donde la persona toca un campo (es inherente a cualquier gestor de contraseñas) pero no guarda ni registra nada. No hay todavía "guardar contraseña nueva al iniciar sesión" ni sugerencias dentro del teclado (Gboard). El dominio verificado solo lo informan los navegadores compatibles; en el resto se usa la app vinculada.
- **Cambio de clave maestra:** el archivo anterior se reemplaza, pero no se sobrescribe de forma segura (memoria flash).
- **Servicio en primer plano:** si Android mata la app a la mitad, el escaneo se corta y no se retoma.

### 8.4 Distribución en todos lados
| Canal | Qué hay que resolver |
|---|---|
| APK en GitHub | Firma de release propia (sección 4, punto 1). Es el canal sin restricciones. |
| Google Play | `QUERY_ALL_PACKAGES` y `MANAGE_EXTERNAL_STORAGE` requieren declaración y aprobación (el acceso a todos los archivos suele aprobarse para antivirus, pero no está garantizado); la función de root y `WRITE_SECURE_SETTINGS` pueden generar objeciones. Conviene una variante (`flavor`) "tienda" sin root ni DNS por permisos especiales. No verifiqué las políticas vigentes de Play hoy. Hace falta política de privacidad pública. |
| F-Droid | Solo usa AndroidX y Material (software libre), pero VirusTotal es un servicio no libre: se marca como característica no deseada (*NonFreeNet*). Hay que escribir la receta de compilación (Gradle + Rust con cargo-ndk). |

Recomendación: dejar los tres canales para después de probar la 0.6 en un teléfono, y hacer primero la firma de release y la variante "tienda".

### 8.5 Cambios de compatibilidad
- **`minSdk` sube de 24 a 26** (Android 8.0): el servicio de autocompletado del sistema existe desde ahí. Deja afuera Android 7.x y menos (alrededor del 1 % de los equipos).
- Nuevos permisos: `INTERNET` (solo al actualizar la lista o consultar VirusTotal, siempre a pedido), `MANAGE_EXTERNAL_STORAGE` (y `READ_EXTERNAL_STORAGE` hasta Android 10), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` y `USE_BIOMETRIC`.
- Nueva dependencia: `androidx.biometric:biometric:1.1.0`.
- Nuevas funciones JNI: `sha256OfPath`, `vaultCreate`, `vaultOpen`, `vaultPeek`, `vaultOpenWithKey`, `vaultSave`, `vaultLock`, `vaultIsUnlocked`, `vaultExportKey`, `hashDbMerge`, `hashDbContains`, `hashDbCount`. Kotlin y Rust se actualizan juntos.
- Cambian los nombres de las pestañas: Inicio, **Escáner**, **Contraseñas**, Apps.

### 8.6 Qué se verificó en esta versión
| Qué | Estado |
|---|---|
| Pruebas de Rust (bóveda, base de firmas, hash por ruta, EICAR de punta a punta) | **Ejecutadas: 21 de 21 pasan** |
| Todo el Kotlin contra el SDK de Android (API 34), con componentes AndroidX/Material simulados | **Sin errores de tipos** (esto ya encontró y corrigió un error real de código) |
| Recursos: IDs, strings, colores claro/oscuro, estilos, drawables, clases del manifest | Cruzados con un script: sin faltantes |
| Compilación real con Gradle, R8 e instalación en un teléfono | **NO se pudo.** Lo hace tu CI. Riesgo restante: diferencias entre las firmas reales de AndroidX/Material y las simuladas, vinculación de recursos y reglas de R8 |
| Descarga real de la lista de abuse.ch y consulta real a VirusTotal | **NO probadas** (necesitan tus claves) |

### 8.7 Lista de prueba en el teléfono (0.6)
1. **Escáner con EICAR:** creá un archivo de texto llamado `eicar.com` con esta única línea y sin espacios extra: `X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*`. Dale el permiso de archivos, tocá *Escanear ahora* en modo Rápido: tiene que aparecer como coincidencia con malware conocido (EICAR). Es inofensivo; es un archivo de prueba que todos los antivirus detectan. Si tu antivirus lo borra, es buena señal.
2. **Lista de firmas:** importá un `.txt` con un hash cualquiera en una línea y mirá que el total suba. Probá una Auth-Key real de abuse.ch.
3. **VirusTotal:** cargá tu clave, activá la opción y escaneá. Tiene que consultar de a una cada ~15 s y mostrar el avance. Probá también con una clave inválida (debe avisar y cortar).
4. **Bóveda:** creá la bóveda, agregá una entrada con sitio y usuario, bloqueala, reabrila con la clave maestra. Activá la huella, bloqueá y desbloqueá con huella. Cambiá una huella registrada del teléfono: tiene que volver a pedir la clave maestra.
5. **Copia cifrada:** exportá, cambiá la clave maestra, importá la copia con su clave original.
6. **Autocompletado:** activalo desde Ajustes de la bóveda, abrí el sitio guardado en Chrome y tocá el campo de usuario. Con la bóveda bloqueada tiene que pedir huella o clave. Probá también un sitio parecido (por ejemplo `tusitio.com.otro.com`): **no** debe ofrecer nada.
7. **Captura de pantalla** en la pestaña Contraseñas: tiene que estar bloqueada.
