# Actores y Casos de Uso

**Sistema de Inventario Multi-Sucursal**

**Base de este documento:** `docs/PROJECT_BRIEF.md` (especificación de requisitos aprobada) y `docs/REQUIREMENTS_TRACEABILITY.md` (matriz de trazabilidad). Todo caso de uso referencia el/los requisito(s) funcional(es) (RF-xxx) que implementa.

**Fecha:** 2026-08-26.

**Convención de etiquetas:** igual que en `PROJECT_BRIEF.md` — **[Origen]** (exigido por el documento fuente), **[Decisión]** (elección del proyecto) y **[Supuesto]** (interpretación propia ante una ambigüedad, pendiente de confirmación cuando así se indica). Los permisos de aprobación de transferencia y de tratamiento de faltantes se apoyan en los supuestos 3 y 4 de `PROJECT_BRIEF.md` sección 7 — **pendientes de confirmación**.

No se diseñan endpoints, contratos de API ni modelo de datos en este documento.

---

## 1. Actores

### 1.1 Administrador general

- **Objetivo:** garantizar la correcta configuración y operación global del sistema a nivel de toda la organización.
- **Responsabilidades [Origen]:** gestionar usuarios, gestionar sucursales, gestionar configuración general, tener visibilidad global del sistema.
- **Alcance por sucursal:** todas las sucursales (global), sin restricción.
- **Permisos:**
  - Lectura: total, sobre cualquier sucursal, módulo y reporte.
  - Escritura: usuarios, sucursales, configuración general; también puede iniciar solicitudes de transferencia (RF-022) en representación de una sucursal destino.
  - **Nota (verificada contra código, 2026-08-29):** el rol técnico `ADMIN` tiene además escritura directa sobre prácticamente toda acción operativa del día a día — ingresos/retiros de inventario, órdenes de compra y su recepción, ventas, despacho y recepción de transferencias — porque cada endpoint de escritura del backend incluye `ADMIN` en su `@PreAuthorize` junto al rol operativo correspondiente. No es una excepción por módulo: es un patrón consistente en todos los controllers. Ver matriz corregida en sección 2.
  - Aprobación: autoridad global de override sobre cualquier flujo de aprobación **[Supuesto]** — el documento fuente no se lo asigna explícitamente, pero se deriva de su "visibilidad total del sistema"; pendiente de confirmación si debe ejercerla en la práctica o delegarse siempre en el Gerente. A nivel técnico el override ya está implementado.
- **Casos de uso principales:** UC-01, UC-04, UC-07, UC-08 (override), UC-13, UC-14, UC-15, UC-16.

### 1.2 Gerente de sucursal

- **Objetivo:** asegurar la correcta operación de su sucursal y la coordinación confiable con el resto de la red.
- **Responsabilidades [Origen]:** supervisar operaciones de su sucursal, consultar reportes, supervisar inventario, participar en la aprobación y gestión de transferencias.
- **Alcance por sucursal:** escritura y supervisión sobre su propia sucursal; lectura sobre cualquier otra sucursal de la red (RF-003).
- **Permisos:**
  - Lectura: total sobre su sucursal; inventario y catálogo de cualquier otra sucursal; reportes y dashboard — **de cualquier sucursal, no solo la propia** (verificado en código: `DashboardService`/`LogisticsComplianceService` no acotan a `MANAGER`, a diferencia de `OPERATOR`) — y comparativos entre sucursales (RF-035).
  - Escritura: aprobación/gestión de transferencias que involucren su sucursal; puede iniciar solicitudes de transferencia como sucursal destino (RF-022); **además, verificado en código (BR-047/BR-053), puede ejecutar directamente la creación y recepción de órdenes de compra, el registro de ventas/devoluciones, y el despacho/recepción de transferencias** — no está limitado a aprobar, también puede operar; también puede crear/editar proveedores y clasificar rutas logísticas.
  - Aprobación: aprueba/gestiona solicitudes de transferencia de su sucursal (RF-023 como origen) **[Supuesto, pendiente de confirmación]**; define el tratamiento de faltantes en recepción parcial (RF-026) **[Supuesto, pendiente de confirmación]**. Ambas ya implementadas en código como decisión técnica, independientemente de si la pregunta de negocio sigue abierta.
- **Casos de uso principales:** UC-01, UC-04, UC-05, UC-06, UC-07, UC-08, UC-09, UC-10, UC-11, UC-12, UC-13, UC-16.

### 1.3 Operador de inventario

- **Objetivo:** ejecutar el día a día operativo de inventario, compras, ventas y transferencias de su sucursal.
- **Responsabilidades [Origen]:** registrar ingresos, registrar retiros, registrar compras, registrar ventas, solicitar transferencias, ejecutar operaciones de inventario autorizadas.
- **Alcance por sucursal:** escritura únicamente sobre la sucursal a la que pertenece; lectura sobre inventario de cualquier otra sucursal (RF-003), sin capacidad de escritura remota.
- **Permisos:**
  - Lectura: inventario y catálogo de su sucursal y de cualquier otra sucursal; su propio historial de movimientos, compras y ventas; catálogo de proveedores; dashboard y reporte de cumplimiento logístico — **acotados a su propia sucursal únicamente** (a diferencia de Gerente/Administrador, que ven cualquiera; RF-035).
  - Escritura: ingresos/retiros de inventario (incluye configurar el stock mínimo de un producto, verificado en código), órdenes de compra y su recepción, ventas y sus devoluciones, solicitud de transferencia, preparación/despacho cuando su sucursal es origen, confirmación de recepción cuando su sucursal es destino, unidades alternativas de un producto. **No** puede crear/editar proveedores (solo lectura, BR-058) ni clasificar rutas logísticas.
  - Aprobación: ninguna — no aprueba solicitudes de transferencia ni define tratamiento de faltantes.
- **Casos de uso principales:** UC-01, UC-02, UC-03, UC-04, UC-05, UC-06, UC-07, UC-09, UC-10, UC-11, UC-12, UC-13, UC-16.

### 1.4 Sistema externo (opcional — integración futura)

- **Objetivo [Origen]:** permitir, en el futuro, que un ERP o sistema de punto de venta externo se integre con el inventario multi-sucursal vía API.
- **Responsabilidades:** ninguna implementada en el alcance actual. El documento fuente lo marca explícitamente como actor opcional (RF-040).
- **Alcance por sucursal:** no aplica — **fuera de alcance de implementación en esta entrega** (ver `PROJECT_BRIEF.md`, sección 2 "Fuera de alcance explícito").
- **Permisos:** no definidos todavía. Se documenta únicamente para no romper, a futuro, la posibilidad de exponerle la API REST ya existente para otros actores (consulta de inventario, registro de venta/compra) bajo un mecanismo de autenticación de servicio a servicio.
- **Casos de uso principales:** ninguno implementado. Candidatos futuros, si se decide construir la integración: UC-01 (consulta de inventario), UC-06 (registro de venta), UC-04/UC-05 (ciclo de compra) — condicionado a decisión explícita posterior.

---

## 2. Matriz Actor × Acción

**Actualizada el 2026-08-29 contra el código real** (no contra la implementación planeada): se auditaron los `@PreAuthorize` de cada Controller y las restricciones de sucursal de cada Service (`AuthorizationService`, y los métodos propios de `DashboardService`/`LogisticsComplianceService`). La versión anterior de esta matriz describía el diseño previsto en la fase de casos de uso (2026-08-26); varias reglas de negocio posteriores (BR-047, BR-049, BR-052, BR-053, BR-056, BR-058 — ver `docs/BUSINESS_RULES.md` y `docs/STATUS.md`) ampliaron o modificaron esos permisos durante la implementación sin que esta matriz se actualizara. Las diferencias respecto a la versión anterior están marcadas con †.

Convenciones: **R** lectura · **W** escritura/ejecución · **A** aprobación · **—** sin acceso.

| Acción | Administrador general | Gerente de sucursal | Operador de inventario | Sistema externo |
|---|---|---|---|---|
| Gestionar usuarios (incl. correo, BR-058) | W | — | — | — |
| Gestionar sucursales | W | — | — | — |
| Consultar inventario de su propia sucursal | R | R | R | — |
| Consultar inventario de otra sucursal | R | R | R | — |
| Registrar ingreso de inventario (ajuste manual) | W † | — | W | — |
| Registrar retiro de inventario (ajuste manual) | W † | — | W | — |
| Crear orden de compra | W † | W † | W | — |
| Confirmar recepción de compra | W † | W † | W | — |
| Registrar venta | W † | W † | W | — |
| Aplicar descuento / lista de precios en venta | W † | W † | W | — |
| Generar devolución de venta (BR-052) | W | W | W (su sucursal) | — |
| Solicitar transferencia | W | W | W | — |
| Aprobar/gestionar solicitud de transferencia (como origen) | A¹ | A | — | — |
| Preparar y confirmar cantidad a despachar | W † | W † | W | — |
| Registrar despacho (transportista, fecha estimada) | W † | W † | W | — |
| Confirmar recepción completa | W † | W † | W | — |
| Confirmar recepción parcial (registrar faltante) | W † | W † | W | — |
| Definir tratamiento de faltante (reenvío/ajuste/reclamación) | A¹ | A | — | — |
| Consultar logística (tiempos, rutas, estado) | R | R | R | — |
| Clasificar / reclasificar rutas logísticas | W | W | — | — |
| Consultar dashboard de su sucursal | R | R (**cualquier sucursal**, no solo la suya) † | R (solo la suya) | — |
| Consultar dashboard comparativo entre sucursales | R | R | — | — |
| Consultar reportes de cumplimiento logístico | R (cualquier sucursal) | R (**cualquier sucursal**) † | R (solo la suya) | — |
| Exportar reportes (movimientos/ventas/transferencias/cumplimiento) — BR-056 | R (cualquier sucursal) | R (cualquier sucursal) | R (solo la suya) | — |
| Consultar proveedores | R | R | R | — |
| Crear / editar / activar / desactivar proveedor (BR-058) | W | W | — | — |
| Eliminar proveedor (BR-058) | W | — | — | — |
| Editar producto existente (nombre, precio, stock mínimo — BR-057/059) | W † | — | W | — |
| Configurar stock mínimo de un producto (valor de siembra, no por sucursal) † | W | — | W | — |
| Gestionar unidades de medida (catálogo global) | W | — | — | — |
| Gestionar unidades alternativas de un producto | W | — | W | — |
| Gestionar listas de precios / fijar precio | W | — | — | — |
| Recibir alertas de stock mínimo | R | R | R | — |

¹ Autoridad de override global; en operación normal se espera que la ejerza el Gerente de sucursal — **[Supuesto, pendiente de confirmación]** en cuanto a si así debe ser por diseño de negocio (ver sección 1.1 y 1.2); a nivel técnico el override ya está implementado (`hasAnyRole('MANAGER','ADMIN')` en ambos endpoints).

**Correcciones de fondo respecto a la matriz anterior (con evidencia de código):**

- **"Configurar umbral de stock mínimo" estaba invertida.** La matriz anterior decía Operador=— y Gerente=W (su sucursal). El código real (`ProductController.java`, `hasAnyRole('OPERATOR','ADMIN')`) da W a Operador y ningún acceso de escritura a Gerente. Además, ya no es un valor por sucursal: es `Product.minimumStock`, un valor de siembra a nivel de producto (no existe endpoint para editarlo por sucursal individual) — la fila se dividió en dos para reflejar esto sin ambigüedad.
- **Administrador general tiene W en prácticamente toda acción operativa**, no solo en configuración global: el patrón `hasAnyRole(..., 'ADMIN')` es universal en el backend — no hay un solo endpoint de escritura operativa que excluya a ADMIN. La matriz anterior lo describía como "—" en ingreso/retiro/compras/ventas/transferencias.
- **Gerente de sucursal tiene W (no solo A)** en compras, ventas y en despacho/recepción de transferencias (BR-047, BR-053) — puede ejecutar la operación directamente, no solo aprobarla.
- **Gerente de sucursal no está acotado a su propia sucursal** al consultar el dashboard o el reporte de cumplimiento logístico de una sucursal específica — puede ver el de cualquiera, igual que Administrador. Solo Operador queda limitado a la suya.
- **Gestión de proveedores no estaba documentada** en la matriz anterior. Pasó por dos fases (BR-049 la abrió a cualquier rol; BR-058 la redujo) — el estado final ya no es "abierta a cualquier rol": lectura sí, pero escritura es Gerente+Administrador (Operador solo lee), y eliminar es exclusivo de Administrador.

Esta matriz es la base para el diseño de RBAC (roles ADMIN, MANAGER, OPERATOR ya definidos en `DECISIONS.md` TD-008). A diferencia de la versión anterior, esta ya no describe una intención de diseño: describe el comportamiento verificado del código en la fecha indicada arriba — si el código cambia, esta tabla debe volver a auditarse, no asumirse vigente.

---

## 3. Casos de uso principales

### UC-01 — Consultar inventario (local y remoto)

- **Actores:** Administrador general, Gerente de sucursal, Operador de inventario.
- **Requisitos relacionados:** RF-003, RF-006.
- **Precondiciones:** el usuario está autenticado y tiene un rol válido.
- **Flujo principal:**
  1. El usuario selecciona la sucursal a consultar (la propia, por defecto, u otra de la red).
  2. El sistema valida que el usuario tenga permiso de lectura sobre esa sucursal (todas las sucursales son de lectura pública dentro de la organización, según RF-003).
  3. El sistema muestra el catálogo de productos y el stock disponible de la sucursal seleccionada.
- **Flujos alternos y errores:**
  - 2a. La sucursal indicada no existe → el sistema informa error y no muestra datos.
  - 3a. La sucursal no tiene productos registrados → el sistema muestra catálogo vacío, no un error.
- **Postcondiciones:** el usuario visualiza el inventario solicitado; no se modifica ningún dato.

### UC-02 — Registrar ingreso de inventario

- **Actor:** Operador de inventario (de la sucursal afectada).
- **Requisitos relacionados:** RF-007, RF-009.
- **Precondiciones:** el usuario pertenece a la sucursal donde se registra el ingreso; el producto existe en el catálogo.
- **Flujo principal:**
  1. El operador indica producto, cantidad, motivo (compra, devolución o ajuste) y fecha.
  2. El sistema registra el movimiento con fecha, responsable, motivo y cantidad (RF-009).
  3. El sistema incrementa el stock del producto en esa sucursal.
- **Flujos alternos y errores:**
  - 1a. El producto no existe en el catálogo → el sistema rechaza el registro y solicita crear el producto primero (UC no cubierto aquí, ver `PROJECT_BRIEF.md` 3.2).
  - 1b. La cantidad es cero o negativa → el sistema rechaza el registro.
- **Postcondiciones:** el stock queda incrementado; existe un movimiento auditable asociado.

### UC-03 — Registrar retiro de inventario

- **Actor:** Operador de inventario (de la sucursal afectada).
- **Requisitos relacionados:** RF-008, RF-009.
- **Precondiciones:** el usuario pertenece a la sucursal donde se registra el retiro; el producto tiene stock registrado.
- **Flujo principal:**
  1. El operador indica producto, cantidad, motivo (venta, merma o ajuste) y fecha.
  2. El sistema valida que el stock disponible sea suficiente.
  3. El sistema registra el movimiento (RF-009) y decrementa el stock.
- **Flujos alternos y errores:**
  - 2a. El stock disponible es insuficiente y el motivo no es un ajuste explícitamente autorizado → el sistema rechaza el retiro.
- **Postcondiciones:** el stock queda decrementado; existe un movimiento auditable asociado.

### UC-04 — Crear orden de compra

- **Actor:** Operador de inventario.
- **Requisitos relacionados:** RF-012, RF-013.
- **Precondiciones:** el proveedor y los productos a comprar existen o pueden registrarse en el momento.
- **Flujo principal:**
  1. El operador selecciona proveedor y agrega líneas de producto con cantidad, precio unitario y descuento.
  2. El operador indica el plazo/condición de pago.
  3. El sistema registra la orden de compra en estado inicial (creada/pendiente de recepción).
- **Flujos alternos y errores:**
  - 1a. No se indica al menos un producto → el sistema rechaza la creación.
- **Postcondiciones:** existe una orden de compra consultable en `PROJECT_BRIEF.md`/histórico por proveedor y producto (RF-015).

### UC-05 — Recibir compra (confirmar recepción)

- **Actor:** Operador de inventario.
- **Requisitos relacionados:** RF-014, RF-016.
- **Precondiciones:** existe una orden de compra en estado pendiente de recepción.
- **Flujo principal:**
  1. El operador confirma la recepción total o parcial de la mercancía de una orden de compra.
  2. El sistema genera automáticamente el ingreso de inventario correspondiente (UC-02) por sucursal receptora.
  3. El sistema recalcula el costo promedio ponderado del producto afectado (RF-016).
  4. El sistema actualiza el estado de la orden de compra.
- **Flujos alternos y errores:**
  - 1a. La orden de compra ya fue recibida en su totalidad → el sistema rechaza una nueva confirmación sobre la misma orden (evita recepción duplicada, riesgo identificado en `PROJECT_BRIEF.md` sección 9).
- **Postcondiciones:** el inventario y el costo promedio quedan actualizados; la orden de compra refleja su nuevo estado.

### UC-06 — Registrar venta

- **Actor:** Operador de inventario.
- **Requisitos relacionados:** RF-017, RF-018, RF-019, RF-020, RF-021.
- **Precondiciones:** el producto existe y pertenece al catálogo de la sucursal del operador.
- **Flujo principal:**
  1. El operador agrega producto(s), cantidad y selecciona lista de precios/descuento aplicable.
  2. El sistema valida disponibilidad de stock (RF-019).
  3. El operador confirma la venta.
  4. El sistema registra la venta asociada a sucursal, fecha y responsable (RF-018), genera el comprobante (RF-021) y dispara el retiro de inventario (UC-03).
- **Flujos alternos y errores:**
  - 2a. El stock disponible es insuficiente → el sistema rechaza la confirmación antes de afectar el inventario (RF-019).
- **Postcondiciones:** la venta queda registrada y es consultable; el stock queda decrementado.

### UC-07 — Solicitar transferencia

- **Actores:** Operador de inventario, Gerente de sucursal o Administrador general (como sucursal destino).
- **Requisitos relacionados:** RF-022.
- **Precondiciones:** existe al menos una sucursal origen distinta con el producto en catálogo.
- **Flujo principal:**
  1. El usuario indica producto, cantidad y sucursal origen.
  2. El sistema registra la solicitud de transferencia en estado "solicitada".
  3. La sucursal origen queda notificada (near-real-time, RNF-001).
- **Flujos alternos y errores:**
  - 1a. La sucursal origen indicada no tiene el producto en catálogo → el sistema advierte pero permite registrar la solicitud (la disponibilidad se valida en la preparación, UC-08).
- **Postcondiciones:** existe una solicitud de transferencia pendiente de revisión por la sucursal origen.

### UC-08 — Aprobar / gestionar solicitud de transferencia

- **Actor:** Gerente de sucursal (origen) **[Supuesto, pendiente de confirmación]**; Administrador general como override.
- **Requisitos relacionados:** RF-023 (revisión de disponibilidad como parte de la gestión de la solicitud).
- **Precondiciones:** existe una solicitud de transferencia en estado "solicitada" dirigida a la sucursal del actor.
- **Flujo principal:**
  1. El Gerente revisa la solicitud y la disponibilidad de stock en su sucursal.
  2. El Gerente aprueba la solicitud, ajustando la cantidad si es necesario.
  3. El sistema pasa la solicitud a estado "en preparación" y la deja disponible para despacho (UC-09).
- **Flujos alternos y errores:**
  - 2a. El Gerente rechaza la solicitud (sin stock disponible o por otro motivo) → el sistema marca la solicitud como rechazada y notifica a quien la originó.
- **Postcondiciones:** la solicitud queda aprobada (con cantidad confirmada o ajustada) o rechazada.

### UC-09 — Preparar y despachar transferencia

- **Actor:** Operador de inventario de la sucursal origen.
- **Requisitos relacionados:** RF-023, RF-024.
- **Precondiciones:** la solicitud de transferencia está aprobada (UC-08).
- **Flujo principal:**
  1. El operador prepara físicamente la cantidad confirmada.
  2. El operador registra el despacho: fecha estimada de llegada y transportista.
  3. El sistema cambia el estado de la transferencia a "en tránsito" y registra el retiro de inventario en la sucursal origen (UC-03).
- **Flujos alternos y errores:**
  - 1a. La cantidad disponible al momento de preparar es menor a la aprobada (venta u otro movimiento consumió el stock reservado) → el sistema exige ajustar la cantidad antes de despachar.
- **Postcondiciones:** la transferencia queda en tránsito; el stock de origen queda decrementado.

### UC-10 — Recibir transferencia completa

- **Actor:** Operador de inventario de la sucursal destino.
- **Requisitos relacionados:** RF-025.
- **Precondiciones:** la transferencia está en estado "en tránsito".
- **Flujo principal:**
  1. El operador confirma que la cantidad recibida coincide con la despachada.
  2. El sistema registra el ingreso de inventario en la sucursal destino (UC-02) y cierra la transferencia como "recibida".
- **Flujos alternos y errores:**
  - 1a. La transferencia ya fue marcada como recibida previamente → el sistema rechaza una nueva confirmación (evita recepción duplicada).
- **Postcondiciones:** el stock de destino queda incrementado; la transferencia queda cerrada con tiempos reales de entrega registrados (alimenta UC-12).

### UC-11 — Recibir transferencia parcial y gestionar faltante

- **Actor:** Operador de inventario de la sucursal destino (registra la recepción); Gerente de sucursal (define el tratamiento) **[Supuesto, pendiente de confirmación]**.
- **Requisitos relacionados:** RF-026.
- **Precondiciones:** la transferencia está en estado "en tránsito".
- **Flujo principal:**
  1. El operador registra la cantidad efectivamente recibida, menor a la despachada.
  2. El sistema calcula el faltante, registra el ingreso parcial correspondiente (UC-02, por la cantidad recibida) y genera una alerta.
  3. El Gerente define el tratamiento del faltante: reenvío, ajuste o reclamación.
  4. El sistema registra el tratamiento elegido y actualiza el estado de la transferencia a "recibida con faltante" (o el estado que corresponda tras el tratamiento).
- **Flujos alternos y errores:**
  - 3a. El tratamiento "reenvío" genera una nueva solicitud de transferencia por la cantidad faltante (vínculo con UC-07).
  - 3b. El tratamiento "ajuste" cierra el faltante como pérdida aceptada, sin generar nueva transferencia.
  - 3c. El tratamiento "reclamación" deja el faltante abierto hasta resolución externa al sistema (registro informativo).
- **Postcondiciones:** el stock de destino refleja solo lo efectivamente recibido; el faltante queda trazado con su tratamiento.

### UC-12 — Consultar logística

- **Actores:** Administrador general, Gerente de sucursal, Operador de inventario.
- **Requisitos relacionados:** RF-027, RF-028, RF-029, RF-030.
- **Precondiciones:** el usuario está autenticado.
- **Flujo principal:**
  1. El usuario consulta el estado de las transferencias en curso (en preparación, en tránsito, recibido, con faltantes).
  2. El usuario consulta tiempos estimados vs. reales y la clasificación de rutas.
  3. El usuario genera un reporte de cumplimiento logístico filtrado por sucursal o ruta.
- **Flujos alternos y errores:**
  - 3a. No hay transferencias en el rango/filtro solicitado → el sistema muestra el reporte vacío, no un error.
- **Postcondiciones:** ninguna modificación de datos; el usuario obtiene visibilidad del estado logístico.

### UC-13 — Consultar dashboard y reportes

- **Actores:** Administrador general, Gerente de sucursal (dashboard completo); Operador de inventario (dashboard de su sucursal, sin comparativa entre sucursales, RF-035).
- **Requisitos relacionados:** RF-031, RF-032, RF-033, RF-034, RF-035.
- **Precondiciones:** el usuario está autenticado.
- **Flujo principal:**
  1. El usuario accede al dashboard de su sucursal (o global, si es Administrador).
  2. El sistema muestra ventas del mes vs. anteriores, rotación de inventario, transferencias activas, indicadores de reabastecimiento.
  3. Si el rol lo permite, el usuario accede a la comparativa entre sucursales.
- **Flujos alternos y errores:**
  - 3a. El Operador intenta acceder a la comparativa entre sucursales → el sistema deniega el acceso (RF-035).
- **Postcondiciones:** ninguna modificación de datos.

### UC-14 — Administrar usuarios

- **Actor:** Administrador general.
- **Requisitos relacionados:** RF-037.
- **Precondiciones:** el usuario autenticado tiene rol Administrador general.
- **Flujo principal:**
  1. El administrador crea, consulta, edita o desactiva un usuario, asignándole rol y sucursal (si aplica).
  2. El sistema aplica el cambio de forma inmediata a los permisos del usuario afectado.
- **Flujos alternos y errores:**
  - 1a. Se intenta crear un usuario con un rol inexistente → el sistema rechaza la operación.
- **Postcondiciones:** el usuario queda creado/actualizado/desactivado con el rol y alcance correspondientes.

### UC-15 — Administrar sucursales

- **Actor:** Administrador general.
- **Requisitos relacionados:** RF-037.
- **Precondiciones:** el usuario autenticado tiene rol Administrador general.
- **Flujo principal:**
  1. El administrador crea, consulta o edita los datos de una sucursal (nombre, ubicación, estado activo/inactivo).
- **Flujos alternos y errores:**
  - 1a. Se intenta desactivar una sucursal con inventario o transferencias activas → el sistema advierte antes de confirmar (evita pérdida de trazabilidad).
- **Postcondiciones:** la sucursal queda creada/actualizada; disponible o no para nuevas operaciones según su estado.

### UC-16 — Recibir y gestionar alertas de stock mínimo (funcionalidad adicional)

- **Actores:** Operador de inventario, Gerente de sucursal (reciben); Administrador general/Gerente (configuran el umbral).
- **Requisitos relacionados:** RF-010, RF-036.
- **Precondiciones:** el producto tiene un stock mínimo configurado para la sucursal.
- **Flujo principal:**
  1. Un movimiento de retiro o venta deja el stock de un producto en o por debajo de su mínimo.
  2. El sistema genera una alerta visible para los usuarios autorizados de esa sucursal.
  3. El usuario consulta la alerta desde el dashboard (UC-13) o una vista dedicada de alertas.
- **Flujos alternos y errores:**
  - 1a. Un ingreso posterior vuelve a dejar el stock por encima del mínimo → la alerta se marca como resuelta automáticamente.
- **Postcondiciones:** la alerta queda registrada, visible y trazable (con fecha de generación y, si aplica, de resolución).

---

## 4. Historias de usuario (funcionalidades clave)

Formato: `Como <rol>, quiero <acción>, para <beneficio>`, con criterios de aceptación en Given/When/Then. Las tres primeras historias provienen directamente de los ejemplos del documento fuente (sección 6.3 del PDF); el resto se derivan de los casos de uso anteriores para cubrir la lista de funcionalidades clave solicitada.

### HU-01 — Registrar ingreso con costo promedio actualizado [Origen]

**Como** operador de inventario, **quiero** registrar el ingreso de productos con su precio de compra, **para** mantener el costo promedio del inventario actualizado.

- **Given** un producto con stock 100 unidades a un costo promedio de $10, **When** se confirma la recepción de una compra de 50 unidades a $16, **Then** el sistema recalcula el costo promedio ponderado a $12 ((100×10 + 50×16) / 150) y el stock queda en 150 unidades.
- **Given** una orden de compra ya marcada como recibida en su totalidad, **When** se intenta confirmar su recepción nuevamente, **Then** el sistema rechaza la operación e informa que la orden ya fue recibida.

### HU-02 — Dashboard comparativo de ventas [Origen]

**Como** gerente de sucursal, **quiero** ver en un dashboard la comparativa de ventas entre el mes actual y los meses anteriores, **para** identificar tendencias y tomar decisiones de compra anticipadas.

- **Given** ventas registradas en los últimos 4 meses para mi sucursal, **When** accedo al dashboard, **Then** veo el volumen de ventas del mes actual junto al de los 3 meses anteriores (ver `PROJECT_BRIEF.md` supuesto 6).
- **Given** que soy Operador de inventario, **When** intento acceder a la comparativa entre sucursales del dashboard, **Then** el sistema deniega el acceso a esa sección específica.

### HU-03 — Solicitar transferencia con urgencia [Origen]

**Como** operador de inventario, **quiero** solicitar la transferencia de un producto desde otra sucursal con indicación de urgencia, **para** que la sucursal origen pueda priorizar el despacho según disponibilidad.

- **Given** que necesito 20 unidades de un producto que mi sucursal no tiene, **When** solicito la transferencia marcándola como urgente, **Then** la solicitud queda registrada y visible para la sucursal origen con la indicación de urgencia.
- **Given** una solicitud marcada como urgente, **When** el Gerente de la sucursal origen revisa sus solicitudes pendientes, **Then** puede identificar cuáles son urgentes para priorizarlas.

### HU-04 — Validar stock antes de confirmar venta

**Como** operador de inventario, **quiero** que el sistema valide el stock antes de confirmar una venta, **para** evitar vender producto que no existe en mi sucursal.

- **Given** un producto con 5 unidades disponibles, **When** intento registrar una venta de 10 unidades, **Then** el sistema rechaza la confirmación e informa el stock disponible, sin afectar el inventario.
- **Given** un producto con 5 unidades disponibles, **When** registro una venta de 5 unidades, **Then** la venta se confirma y el stock queda en 0.

### HU-05 — Aprobar solicitud de transferencia

**Como** gerente de sucursal, **quiero** revisar y aprobar (o rechazar) las solicitudes de transferencia dirigidas a mi sucursal, **para** controlar la salida de inventario de mi sucursal.

- **Given** una solicitud de transferencia pendiente por 30 unidades y solo 20 disponibles en mi sucursal, **When** apruebo la solicitud, **Then** puedo ajustar la cantidad a 20 antes de confirmarla.
- **Given** una solicitud de transferencia sin stock disponible, **When** la rechazo, **Then** el sistema notifica el rechazo a quien la originó y no se genera ningún movimiento de inventario.

### HU-06 — Confirmar recepción parcial y tratar el faltante

**Como** operador de inventario de la sucursal destino, **quiero** registrar la cantidad realmente recibida en una transferencia, **para** que el faltante quede trazado y gestionado.

- **Given** una transferencia en tránsito por 50 unidades, **When** registro la recepción de solo 45 unidades, **Then** el sistema registra un faltante de 5 unidades, genera una alerta, e incrementa el stock de destino solo en 45.
- **Given** un faltante registrado, **When** el Gerente define el tratamiento como "reenvío", **Then** el sistema deja disponible la creación de una nueva solicitud de transferencia por las 5 unidades faltantes.

### HU-07 — Alerta de stock mínimo

**Como** operador de inventario, **quiero** recibir una alerta cuando un producto llegue a su stock mínimo, **para** poder solicitar reabastecimiento a tiempo.

- **Given** un producto con stock mínimo configurado en 10 unidades y stock actual de 12, **When** se registra un retiro de 3 unidades, **Then** el sistema genera una alerta visible porque el stock (9) quedó por debajo del mínimo.
- **Given** una alerta activa por stock bajo mínimo, **When** se registra un ingreso que sube el stock por encima del mínimo, **Then** la alerta se marca como resuelta.

### HU-08 — Consultar inventario de otra sucursal

**Como** gerente de sucursal, **quiero** consultar el inventario de otra sucursal de la red, **para** evaluar si puedo solicitarle una transferencia antes de generar la solicitud.

- **Given** que estoy autenticado como Gerente de la sucursal A, **When** consulto el inventario de la sucursal B, **Then** veo el catálogo y stock disponible de la sucursal B en modo solo lectura.

### HU-09 — Administrar usuarios y sucursales

**Como** administrador general, **quiero** crear usuarios y asignarles rol y sucursal, **para** que cada persona tenga el nivel de acceso correspondiente a su función.

- **Given** que soy Administrador general, **When** creo un usuario con rol "Operador de inventario" asociado a la sucursal A, **Then** ese usuario solo puede escribir sobre la sucursal A y leer sobre el resto, según la matriz Actor × Acción.
- **Given** un usuario con rol distinto de Administrador general, **When** intenta acceder a la gestión de usuarios o sucursales, **Then** el sistema deniega el acceso.

---

**Documentos relacionados:** `docs/PROJECT_BRIEF.md` (requisitos y supuestos), `docs/REQUIREMENTS_TRACEABILITY.md` (detalle de cada RF), `docs/DECISIONS.md` (roles RBAC ya aprobados: ADMIN, MANAGER, OPERATOR).
