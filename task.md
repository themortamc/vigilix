# Task: Rediseño radical de interfaz de Vigilix

## Objetivo
Rediseñar por completo la interfaz de usuario de Vigilix para que sea **fácil, cómoda y sencilla de usar**, eliminando el exceso de pestañas, la mala agrupación de funciones, la densidad de información inconsistente y el exceso de toques para llegar a las funciones principales.

## Alcance
- **Dentro:** Rediseño de navegación, pantallas principales, flujo de interacción, jerarquía visual, componentes Material 3.
- **Fuera:** Cambios en la lógica de negocio (Rust core, VaultStore, ScanEngine),新增 features, testing.

## Criterios de Aceptación
1. La app tiene **máximo 3 pestañas** en la navegación inferior (o una sola pantalla con secciones expandibles).
2. Cualquier función crítica está alcanzable en **máximo 2 toques** desde la pantalla principal.
3. La densidad de información es **equilibrada**: sin elementos apretados, sin pantallas vacías.
4. La interfaz sigue las directrices de **Material 3** (botones, snackbars, diálogos, tipografía, colores de rol).
5. Los objetivos táctiles cumplen **48×48 dp mínimo**.
6. La app es **compatible con la mayoría de teléfonos** (minSdk 26, pantallas de 5" a 7").
7. El tema **oscuro es de primera clase** (no un invertido rápido).
8. La compilación en **GitHub Actions sigue en verde**.

## Fases
1. **Descubrimiento y brief de diseño** (entrevista + research de apps de referencia).
2. **Definición de arquitectura de navegación** (estructura de pestañas, flujo de pantallas).
3. **Diseño de componentes y tokens** (colores, tipografía, espaciado, formas).
4. **Implementación de nuevas pantallas** (XML + Kotlin).
5. **Revisión y ajuste fino** (heurísticas de usabilidad, contraste, targets táctiles).
6. **Verificación final** (compilación en CI, tests de Rust).

## Riesgos y Mitigaciones
- **Riesgo:** Cambios extensos pueden romper la compilación. **Mitigación:** Commit por fases, verificar con CI tras cada una.
- **Riesgo:** El redesign puede alterar la funcionalidad existente. **Mitigación:** Mantener la lógica de negocio intacta; solo cambiar UI.
- **Riesgo:** La nueva navegación puede no ser intuitiva para usuarios existentes. **Mitigación:** Usar patrones de navegación estándar de Android (Bottom Navigation, Navigation Rail).

## Verificación
- [ ] `cargo test --release --locked` en `rust/` → todo verde.
- [ ] `./gradlew assembleDebug` → compila sin errores.
- [ ] La app tiene ≤3 pestañas en navegación inferior.
- [ ] Las pantallas usan componentes Material 3 (no controles inventados).
- [ ] Los targets táctiles cumplen 48×48 dp.
- [ ] Dark theme está diseñado, no invertido.
- [ ] Commit con mensaje descriptivo.