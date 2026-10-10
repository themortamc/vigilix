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

## 1. Arquitectura de Navegación e Información (IA v2.0)

Mantenemos 4 secciones principales unificadas en la navegación inferior más un panel modal de Herramientas Cripto:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  BARRA SUPERIOR: Logo Vigilix · Estado Root · Botón Herramientas (🔧)   │
├──────────────────────────────────────────────────────────────────────────┤
│  1. 🏠 INICIO (Dashboard)                                               │
│     - Indicador de Salud del Dispositivo (Porcentaje real y Estado)      │
│     - Accesos Rápidos (Escaneo 1-tap, Bóveda, DNS, Herramientas)         │
│     - Controles de Seguridad (Motor, Bloqueo, Parche, DNS)               │
│                                                                          │
│  2. 🛡️ ESCÁNER                                                           │
│     - Escaneo del Teléfono (Modos Rápido / Completo + VirusTotal)        │
│     - Verificador de Archivo Suelto (SHA-256 de archivos y APKs)        │
│     - Historial de Escaneos Recientes                                    │
│     - Gestión de Base de Firmas (.vxdb) y API Key de VirusTotal          │
│                                                                          │
│  3. 🔐 BÓVEDA                                                            │
│     - Desbloqueo Maestro / Biométrico (Android Keystore)                 │
│     - Lista de Credenciales (Búsqueda, copia rápida, accesos, autofill)  │
│     - Gestión de Copias Cifradas (Export/Import) y Ajustes Bóveda       │
│                                                                          │
│  4. 📱 PRIVACIDAD (Apps & Permisos)                                      │
│     - Filtro por riesgo: Cámara & Mic, Ubicación, SMS/Contactos          │
│     - Auditoría en tiempo real de permisos concedidos                    │
│     - Control de restricciones en segundo plano (vía Root)               │
└──────────────────────────────────────────────────────────────────────────┘
```

## 2. Dashboard (Pestaña Inicio)

- **Salud del dispositivo:** calcula un porcentaje basado en controles reales (motor nativo, bloqueo de pantalla, parche de seguridad, DNS privado).
- **Tarjetas de Estado:** estado de la bóveda, último escaneo e historial rápido.
- **Accesos rápidos:** disparadores de 1 toque para escaneo, bóveda, DNS y panel de herramientas.

## 3. Escáner de Malware (Pestaña Escáner)

- **Escaneo del sistema:** Modos Rápido y Completo con `ScanEngine`.
- **Análisis puntual:** Cálculo de hash SHA-256 de archivos o APKs mediante `SecurityBridge` (Rust).
- **Historial:** Persistencia de escaneos anteriores en `HistoryManager`.
- **Configuración:** Actualización de firmas `.vxdb` y VirusTotal.

## 4. Bóveda de Contraseñas (Pestaña Bóveda)

- Cifrado seguro con Argon2id y ChaCha20-Poly1305.
- Copia rápida de usuario y clave.
- Desbloqueo biométrico integrado con Android Keystore.
- Soporte para Autofill del sistema.

## 5. Privacidad y Permisos (Pestaña Privacidad)

- `PermissionMonitor`: Audita permisos concedidos en tiempo real por categoría (Cámara/Mic, Ubicación, SMS/Contactos).
- `PrivilegedEngine`: Restricción de ejecución en segundo plano para apps seleccionadas mediante root.

## 6. Panel de Herramientas Cripto

- Diálogo/Modal accesible desde cualquier parte de la app con el Generador de Contraseñas de Alta Entropía y Cifrador de Texto Standalone (`vgx1:`).
