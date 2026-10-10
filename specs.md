# Especificación técnica — Vigilix

> SDD (Spec-Driven Development): cada feature que se implementa DEBE
> tener su sección aquí antes de escribir código. Este documento es la
> fuente de verdad sobre *cómo* funciona el sistema hoy y cómo debe
> comportarse cada pieza.

**Versiones de formato on-disk (no romper sin migración documentada):**

| Prefijo | Contenido | AAD |
| ------- | --------- | --- |
| `vgx1:` | hex(salt[16] \| nonce[12] \| ciphertext \| tag[16]) | `vigilix/v1` |
| `vgv1:` | hex(salt[16] \| nonce[12] \| ciphertext \| tag[16]) | `vigilix/vault/v1` |

---

## 1. Arquitectura general

```
┌───────────────────────────────────────────────────────────┐
│  UI (Kotlin, View Binding, coroutines)                    │
│  MainActivity: Dashboard · Escáner · Bóveda · Apps · Sec  │
├───────────────────────────────────────────────────────────┤
│  Helpers (Kotlin objects)                                 │
│  VaultStore · ScanEngine · HashDb · DnsController         │
│  PermissionMonitor · HistoryManager · KeystoreCrypto      │
│  BiometricHelper · AutofillSupport · PrivilegedEngine     │
│  VirusTotalClient · ClipboardHelper                       │
├───────────────────────────────────────────────────────────┤
│  SecurityBridge (Kotlin object — único puente JNI)        │
│  - isLoaded: bool  (false → modo degradado, devuelve null)  │
│  - Cada fun envuelta en guarded() { UnsatisfiedLinkError }│
├───────────────────────────────────────────────────────────┤
│  rust/ — vigilix_core (cdylib, JNI)                       │
│  Lógica pura (testeable):                                 │
│    encrypt_text / decrypt_text                            │
│    generate_password (8..128, OsRng, 4 clases)            │
│    sha256_of_file / sha256_of_path                        │
│    vault_* (create/open/peek/open_with_key/save/lock/…)   │
│    hashdb_* (merge_text/count/contains — búsqueda binaria) │
│  Capa JNI: #[no_mangle] extern "system" + catch_unwind    │
└───────────────────────────────────────────────────────────┘
```

## 2. Dashboard (Pestaña Inicio)

- **Salud del dispositivo:** calcula un porcentaje basado en controles reales (motor nativo, bloqueo de pantalla, parche de seguridad, DNS privado). No usa números inventados.
- **Tarjeta de Bóveda:** muestra estado real (abierta con recuento / bloqueada / vacía / sin motor) y botón de acceso rápido.
- **Tarjeta de Último Escaneo:** lee `Prefs.lastScanAt` para mostrar tiempo transcurrido, archivos procesados y hallazgos.
- **Accesos rápidos:** generador de contraseñas, cifrador de texto y verificación de archivo suelto.

## 3. Pestaña Seguridad

- **Detector de permisos en tiempo real (`PermissionMonitor`):**
  Audita apps de terceros instaladas y enumera cuáles tienen concedidos permisos sensibles (Ubicación, Cámara, Micrófono, Contactos, SMS, Registro de llamadas, Dibujar sobre otras apps). Sin falsos positivos (compara permisos concedidos reales).
- **Bloqueo de apps (`PrivilegedEngine`):**
  Restringe actividad en segundo plano mediante `am set-inactive <pkg> true` y `appops set <pkg> RUN_IN_BACKGROUND ignore`. Requiere root; se revierte cuando la persona lo decide.
- **Historial de escaneos (`HistoryManager`):**
  Guarda en `SharedPreferences` de forma estructurada los últimos 10 escaneos (`timestamp`, `mode`, `files`, `findings`, `vtUsed`). Permite borrado completo.

## 4. Escáner, Bóveda, Apps, VirusTotal, DNS

*(Sin cambios respecto a v0.6.0; se mantienen todas las invariantes de seguridad de `constitution.md`).*
