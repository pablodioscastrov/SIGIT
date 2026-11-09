# Reglas de Negocio

**Sistema de Inventario Multi-Sucursal**

**Base de este documento:** `docs/DOMAIN_MODEL.md` (modelo de dominio aprobado), `docs/PROJECT_BRIEF.md`, `docs/USE_CASES.md`, `docs/ARCHITECTURE.md` (secciones 6 y 7, ubicación de reglas y estrategia transaccional).

**Fecha:** 2026-08-26 (actualizado el mismo día tras el análisis de flujos críticos). Este documento reemplaza y expande la versión anterior de `BUSINESS_RULES.md` — las reglas BR-001 a BR-011 conservan el mismo identificador y significado que ya referencian otros documentos (`PROJECT_BRIEF.md`, `REQUIREMENTS_TRACEABILITY.md`, `DOMAIN_MODEL.md`); se agregan BR-012 en adelante para el resto de la cobertura pedida. Se agrega BR-023 y se corrigen BR-008 y BR-017 tras el análisis detallado en `docs/CRITICAL_FLOWS.md`, que contiene el pseudocódigo y los diagramas de actividad que hacen operativa cada regla — consúltese ese documento para el detalle paso a paso.

**Formato de cada regla:** ID, descripción, entidades afectadas, validación (qué se verifica y en qué capa), error esperado (código HTTP + slug de error) y pruebas necesarias. No se diseñan endpoints ni rutas concretas — el código de error es una convención de contrato, no una especificación de API.

No se escribe código en este documento.

---

## Convención de errores a nivel de API

Antes del catálogo, se fija la convención que cada regla usa en su campo "error esperado", para no repetirla 22 veces:

| Código HTTP | Cuándo aplica | Ejemplo |
|---|---|---|
| **400 Bad Request** | El payload es estructuralmente inválido: falta un campo obligatorio, tipo de dato incorrecto, formato inválido. Se detecta en la capa de entrada (DTO/Bean Validation), antes de llegar a cualquier regla de negocio. | Falta `productId` en el body de un movimiento. |
| **401 Unauthorized** | No hay JWT válido o está expirado. Transversal a todas las reglas; no se repite por regla porque no es una regla de negocio sino un requisito de acceso (RNF-003). | Token ausente o expirado. |
| **403 Forbidden** | El usuario está autenticado pero su rol o su alcance por sucursal no lo autoriza para la acción solicitada. | Un Operador intenta aprobar una transferencia. |
| **404 Not Found** | El recurso referenciado por identificador no existe. | `productId` inexistente. |
| **409 Conflict** | El **estado actual** del recurso impide la operación: transición de estado inválida, conflicto de versión por concurrencia, operación ya aplicada (idempotencia), documento ya cerrado. | Confirmar recepción de una orden de compra ya `RECEIVED`. |
| **422 Unprocessable Entity** | El payload tiene forma válida, pero viola una regla de negocio semántica sobre datos que sí existen y están en un estado válido. | Vender más unidades de las que hay en stock. |

Regla general para distinguir 409 de 422: **409 es "no en este momento/estado"**, **422 es "esto no es válido aunque el estado sea correcto"**. Ambos son errores de negocio (nunca del cliente HTTP en sí), por eso ninguno de los dos es 400.

---

## Catálogo de reglas

### BR-001 — Trazabilidad completa de movimientos de inventario **[Origen]**

- **Descripción:** todo ingreso o retiro de inventario debe registrarse con fecha, responsable, motivo y cantidad, sin excepción.
- **Entidades afectadas:** `InventoryMovement`, `Inventory`, `User`.
- **Validación:** la capa de aplicación de `inventory` no expone ninguna operación que modifique `Inventory.quantity_on_hand` sin crear, en la misma transacción, la fila de `InventoryMovement` correspondiente con `responsible_user_id`, `reason`, `occurred_at` y `quantity` poblados (`NOT NULL` a nivel de columna como respaldo).
- **Error esperado:** 400 si falta `motivo` o `cantidad` en la solicitud; no aplica un código de negocio porque esta regla se garantiza estructuralmente, no como una validación rechazable en tiempo de ejecución con datos completos.
- **Pruebas necesarias:** insertar un movimiento sin `responsible_user_id`/`reason`/`quantity` debe fallar a nivel de columna (`NOT NULL`); toda prueba de integración de RF-007/RF-008 debe verificar que exista exactamente un `InventoryMovement` nuevo por cada cambio de `Inventory.quantity_on_hand`.

### BR-002 — Validación de stock antes de confirmar una venta **[Origen]**

- **Descripción:** una venta no puede confirmarse si la cantidad solicitada de cualquiera de sus líneas excede el stock disponible de esa sucursal.
- **Entidades afectadas:** `Sale`, `SaleItem`, `Inventory`.
- **Validación:** en la capa de aplicación de `sales`, dentro de la misma transacción que confirma la venta, se compara `SaleItem.quantity` (convertida a unidad base) contra `Inventory.quantity_on_hand` de esa sucursal/producto, leído con bloqueo optimista (`version`).
- **Error esperado:** 422 `STOCK_INSUFICIENTE`.
- **Pruebas necesarias:** venta con cantidad exactamente igual al stock disponible se confirma y deja stock en cero; venta con cantidad mayor al stock disponible se rechaza sin generar ningún `InventoryMovement`; dos ventas concurrentes que en conjunto exceden el stock disponible — solo una se confirma (ver BR-022, concurrencia).

### BR-003 — Confirmar recepción de compra actualiza el inventario **[Origen]**

- **Descripción:** al confirmar la recepción (total o parcial) de una orden de compra, el inventario de la sucursal receptora se incrementa automáticamente en la cantidad recibida.
- **Entidades afectadas:** `PurchaseOrder`, `PurchaseOrderItem`, `InventoryMovement`, `Inventory`.
- **Validación:** el servicio de `purchases` invoca al servicio de `inventory` para generar un `InventoryMovement` (`direction = INGRESO`, `reason = COMPRA`) por la cantidad recibida, dentro de la misma transacción en que se actualiza `PurchaseOrderItem.quantity_received` y, si corresponde, `PurchaseOrder.status`.
- **Error esperado:** 422 `CANTIDAD_RECEPCION_EXCEDE_ORDENADO` si la cantidad a recibir excede `quantity_ordered - quantity_received` restante; 409 `ORDEN_YA_RECIBIDA` si la orden ya está en estado `RECEIVED` (ver BR-017, idempotencia).
- **Pruebas necesarias:** recepción total actualiza stock y cierra la orden; recepción parcial dos veces sucesivas (por el resto pendiente) actualiza el stock correctamente cada vez sin exceder lo ordenado; intentar recibir más de lo pendiente se rechaza.

### BR-004 — Cálculo de costo promedio ponderado **[Origen]**

- **Descripción:** cada recepción de compra con un precio unitario distinto al costo promedio actual del producto en esa sucursal recalcula el costo promedio ponderado.
- **Entidades afectadas:** `PurchaseOrderItem`, `Inventory`.
- **Fórmula:** `nuevo_costo = (stock_actual × costo_actual + cantidad_recibida × precio_unitario_recibido) / (stock_actual + cantidad_recibida)`.
- **Ajuste al modelo de dominio (aprobado y aplicado):** `docs/DOMAIN_MODEL.md` no incluía originalmente una columna para almacenar el costo promedio vigente; se aprobó agregar `Inventory.average_unit_cost` (numérico, `CHECK (average_unit_cost >= 0)`), actualizado exclusivamente por el flujo de recepción de compra (ver `docs/DOMAIN_MODEL.md`, sección 2.7 y 3.2).
- **Validación:** el recálculo ocurre en la misma transacción que BR-003, usando el `Inventory.quantity_on_hand` **previo** a aplicar el ingreso (no el posterior) como base de la ponderación.
- **Error esperado:** no es una regla rechazable por el usuario (no hay un caso de "costo promedio inválido" que el cliente pueda provocar con datos correctos); un resultado con precisión decimal se redondea según la política de redondeo — **pendiente de definir** (ver `docs/DOMAIN_MODEL.md`, decisiones pendientes de `docs/STATUS.md`).
- **Pruebas necesarias:** caso numérico documentado (100 u. a $10, ingreso de 50 u. a $16 → costo $12, ver `USE_CASES.md` HU-01); caso con stock inicial en cero (el costo promedio pasa a ser directamente el precio de la primera recepción); caso con dos recepciones sucesivas a precios distintos.

### BR-005 — Validación de disponibilidad antes de aprobar/preparar el envío de una transferencia **[Origen]**

- **Descripción:** la sucursal origen debe validar que tiene disponibilidad suficiente antes de aprobar o confirmar la cantidad a enviar de una solicitud de transferencia.
- **Entidades afectadas:** `Transfer`, `TransferItem`, `Inventory`.
- **Validación:** al pasar de `REQUESTED` a `APPROVED`, el servicio de `transfers` compara `TransferItem.quantity_requested` (o la cantidad ajustada) contra `Inventory.quantity_on_hand` de la sucursal origen.
- **Error esperado:** 422 `STOCK_INSUFICIENTE_PARA_TRANSFERENCIA` si se intenta aprobar una cantidad mayor a la disponible; no impide rechazar la solicitud (`REJECTED`) ni aprobarla con una cantidad ajustada menor.
- **Pruebas necesarias:** aprobar con cantidad ajustada igual al stock disponible; intentar aprobar la cantidad íntegra solicitada cuando excede el stock disponible se rechaza, mostrando el ajuste posible.

### BR-006 — Recepción completa actualiza el inventario destino **[Origen]**

- **Descripción:** al confirmar que la cantidad recibida coincide con la despachada, el inventario de la sucursal destino se incrementa automáticamente y la transferencia pasa a `RECEIVED_COMPLETE`.
- **Entidades afectadas:** `Transfer`, `TransferItem`, `InventoryMovement`, `Inventory`.
- **Validación:** el servicio de `transfers` genera un `InventoryMovement` (`direction = INGRESO`, `reason = TRANSFERENCIA_ENTRADA`) por `quantity_shipped` completo, y cambia `Transfer.status`, dentro de una única transacción.
- **Error esperado:** 409 `TRANSFERENCIA_ESTADO_INVALIDO` si la transferencia no está en `IN_TRANSIT`.
- **Pruebas necesarias:** recepción completa incrementa el stock destino exactamente en `quantity_shipped`; intentar confirmar recepción sobre una transferencia en estado `REQUESTED` o `RECEIVED_COMPLETE` se rechaza.

### BR-007 — Soporte de recepción parcial **[Origen]**

- **Descripción:** el sistema debe permitir confirmar una recepción con cantidad menor a la despachada, sin bloquear el flujo ni forzar una recepción completa ficticia.
- **Entidades afectadas:** `Transfer`, `TransferItem`.
- **Validación:** el servicio de `transfers` acepta `quantity_received < quantity_shipped` como un caso válido, no como un error, y deriva el estado `RECEIVED_PARTIAL` (ver BR-020, máquina de estados).
- **Error esperado:** no aplica error — es el camino feliz de un caso legítimo, distinto de BR-014 (recepción que excede lo enviado, que sí es un error).
- **Pruebas necesarias:** recepción con cantidad menor a la despachada se acepta y deriva a `RECEIVED_PARTIAL`, no a un error.

### BR-008 — La recepción parcial debe registrar el faltante **[Origen]**

- **Descripción:** toda recepción parcial calcula y persiste la diferencia entre lo despachado y lo recibido.
- **Entidades afectadas:** `TransferItem`.
- **Validación:** al confirmar la recepción, el servicio calcula `quantity_missing = quantity_shipped - quantity_received` y lo persiste junto con `quantity_received` en la misma operación; también dispara una notificación de discrepancia abierta (RF-026). **Corrección respecto a la versión anterior de esta regla:** esta notificación **no** reutiliza `StockAlert` (esa entidad es específica para stock mínimo, BR-010) — es un evento distinto cuya condición de "abierta" se consulta directamente como `TransferItem.quantity_missing > 0 AND discrepancy_treatment IS NULL`, sin necesitar una tabla propia (ver `docs/CRITICAL_FLOWS.md`, flujo E+F1 — recepción de transferencia unificada en `TransferService.receive`).
- **Error esperado:** no aplica error propio; ver BR-014 para el caso en que `quantity_received > quantity_shipped`.
- **Pruebas necesarias:** recepción de 45 de 50 unidades registra `quantity_missing = 5`; recepción completa dentro del mismo flujo deja `quantity_missing` en `NULL` o `0`, no ambiguo.

### BR-009 — Tratamiento del faltante: reenvío, ajuste o reclamación **[Origen]**

- **Descripción:** todo faltante registrado (BR-008) debe poder resolverse mediante uno de tres tratamientos: reenvío, ajuste o reclamación.
- **Entidades afectadas:** `TransferItem`, `Transfer` (si el tratamiento es reenvío, se crea una nueva `Transfer` referenciada en `follow_up_transfer_id`).
- **Validación:** el servicio de `transfers` solo acepta el tratamiento sobre un `TransferItem` en estado `RECEIVED_PARTIAL` con `quantity_missing > 0` y sin tratamiento previo (`discrepancy_treatment IS NULL`); un tratamiento ya definido no se puede sobrescribir (ver BR-021, no se elimina/edita historial).
- **Error esperado:** 409 `FALTANTE_YA_TRATADO` si se intenta definir un tratamiento sobre una línea que ya lo tiene; 422 `TRATAMIENTO_INVALIDO` si el valor no es uno de los tres permitidos.
- **Pruebas necesarias:** definir tratamiento `REENVIO` crea una nueva `Transfer` vinculada; intentar tratar dos veces la misma línea se rechaza; intentar tratar una línea sin faltante (`quantity_missing` nulo) se rechaza.

### BR-010 — Control de stock mínimo y generación de alerta **[Origen]**

- **Descripción:** cuando el stock de un producto en una sucursal alcanza o cae por debajo de su `minimum_stock`, se genera una alerta; cuando vuelve a superarlo, la alerta activa se resuelve.
- **Entidades afectadas:** `Inventory`, `StockAlert`.
- **Validación:** cada operación que modifica `Inventory.quantity_on_hand` (venta, retiro, ajuste, recepción de transferencia) evalúa, en la misma transacción, si el nuevo valor cruza el umbral `minimum_stock` en cualquier dirección, y crea o resuelve la `StockAlert` correspondiente. El índice único parcial `UNIQUE (inventory_id) WHERE status = 'ACTIVE'` (`docs/DOMAIN_MODEL.md`, 2.9) evita alertas activas duplicadas incluso ante una condición de carrera.
- **Error esperado:** no aplica error — es un efecto colateral automático, no una operación que el usuario invoque directamente y pueda rechazarse.
- **Pruebas necesarias:** un retiro que deja el stock justo en el mínimo genera la alerta; un ingreso posterior que sube el stock por encima del mínimo resuelve la alerta activa; dos retiros concurrentes que cruzan el umbral casi simultáneamente generan una única alerta activa, no dos.
- **Implementado (funcionalidad adicional elegida, RF-036) — ver `docs/adr/ADR-015-alertas-de-stock-minimo.md` para el diseño completo.** Dos precisiones sobre la validación anterior:
  1. La deduplicación no depende de capturar una violación del índice único **dentro** de la misma transacción que la venta/compra/transferencia: PostgreSQL aborta toda la transacción en curso ante cualquier statement fallido (verificado empíricamente), así que un `INSERT` que chocara contra el índice único dejaría inservible la transacción de negocio que lo rodea. En su lugar, `StockAlertService.evaluate` comprueba primero si ya existe una alerta activa (`existsByInventoryIdAndStatus`) antes de insertar — mismo patrón que `existsByEmail`/`existsByCode` en `users`/`branches`. Esa comprobación es suficiente en la práctica porque todo llamador ya serializa sus escrituras sobre el mismo `Inventory` vía bloqueo optimista por `version`; el índice único parcial queda como respaldo de última línea a nivel de base de datos, no como mecanismo de recuperación activo.
  2. **Un fallo al evaluar o persistir la alerta nunca revierte la operación de inventario que la disparó** (venta, compra, transferencia, ajuste ya confirmados): `StockAlertService.evaluate` captura cualquier excepción inesperada y solo la registra en el log, sin propagarla — verificado con un doble de prueba que fuerza el fallo (`StockAlertNotificationFailureTest`). El envío de la señal SSE (`stock-alert.triggered`/`resolved`) hereda la misma garantía por construcción: se emite después del commit (ADR-007), así que para cuando pudiera fallar el envío la operación ya quedó confirmada.

### BR-011 — Soporte de múltiples unidades de medida con conversión **[Origen]**

- **Descripción:** un producto puede registrarse, comprarse o venderse en una unidad de medida distinta a su unidad base, siempre que exista una conversión definida.
- **Entidades afectadas:** `Product`, `ProductUnit`, `UnitOfMeasure`, `InventoryMovement`, `PurchaseOrderItem`, `SaleItem`, `TransferItem`.
- **Validación:** toda operación que registre una cantidad en una unidad no base primero verifica que exista un `ProductUnit` para ese `(product_id, unit_of_measure_id)`, y convierte la cantidad a la unidad base usando `conversion_factor_to_base` antes de aplicarla a `Inventory.quantity_on_hand`.
- **Error esperado:** 422 `UNIDAD_NO_SOPORTADA` si se intenta registrar una cantidad en una unidad sin conversión definida para ese producto.
- **Pruebas necesarias:** registrar un ingreso en una unidad no base convierte correctamente antes de sumar al agregado; intentar usar una unidad no definida para el producto se rechaza.

### BR-012 — Cantidades siempre positivas en operaciones de entrada/salida

- **Descripción:** ninguna cantidad registrada en un movimiento de inventario, línea de compra, línea de venta o línea de transferencia puede ser cero o negativa.
- **Entidades afectadas:** `InventoryMovement.quantity`, `PurchaseOrderItem.quantity_ordered`, `SaleItem.quantity`, `TransferItem.quantity_requested/quantity_approved/quantity_shipped/quantity_received`.
- **Validación:** verificación en la capa de entrada (forma: es numérico y mayor que cero) y reforzada con `CHECK (quantity > 0)` en cada tabla correspondiente (`docs/DOMAIN_MODEL.md`, sección 6).
- **Error esperado:** 400 si el campo no es numérico o falta; 422 `CANTIDAD_INVALIDA` si es numérico pero ≤ 0 (es una regla de negocio, no un error de forma, porque "cero" es un valor sintácticamente válido).
- **Pruebas necesarias:** cantidad `0` y cantidad negativa se rechazan en cada uno de los cuatro puntos de entrada listados; cantidad `null` se rechaza como 400, no como 422.

### BR-013 — Una transferencia no puede despachar más de lo aprobado ni de lo disponible al momento del despacho

- **Descripción:** al registrar el despacho (RF-024), la cantidad a enviar no puede exceder `quantity_approved`, y tampoco puede exceder el stock realmente disponible en ese momento (que pudo cambiar desde la aprobación por otra venta o movimiento).
- **Entidades afectadas:** `TransferItem`, `Inventory`.
- **Validación:** el servicio de `transfers`, dentro de la transacción de despacho, valida `quantity_shipped <= quantity_approved` y, adicionalmente, vuelve a comprobar `Inventory.quantity_on_hand >= quantity_shipped` con lectura bloqueada por `version` — no confía en la validación hecha en el momento de la aprobación (BR-005), porque el tiempo transcurrido entre aprobar y despachar puede haber consumido el stock reservado (ver `docs/USE_CASES.md`, UC-09, flujo alterno 1a).
- **Error esperado:** 422 `CANTIDAD_DESPACHO_EXCEDE_APROBADO` si excede lo aprobado; 422 `STOCK_INSUFICIENTE` si excede el stock real disponible al despachar.
- **Pruebas necesarias:** despacho con cantidad igual a la aprobada y con stock suficiente se acepta; despacho con cantidad mayor a la aprobada se rechaza; escenario donde una venta consume el stock entre la aprobación y el despacho hace que el despacho se rechace pese a estar aprobado.

### BR-014 — La recepción no puede superar lo efectivamente enviado, salvo corrección explícita

- **Descripción:** `TransferItem.quantity_received` nunca puede ser mayor que `TransferItem.quantity_shipped` mediante el flujo normal de recepción.
- **Entidades afectadas:** `TransferItem`.
- **Validación:** verificación en el servicio de `transfers` antes de persistir la recepción, reforzada con `CHECK (quantity_received IS NULL OR quantity_received <= quantity_shipped)` en base de datos. No existe, en el alcance actual, un "flujo explícito de corrección" que permita recibir de más — si se necesitara (p. ej. el destino cuenta físicamente más de lo que el origen declaró haber despachado), quedaría como un ajuste manual de inventario en la sucursal destino, registrado aparte con su propio motivo (`AJUSTE_INGRESO`), nunca sobrescribiendo `quantity_received` de la transferencia.
- **Error esperado:** 422 `RECEPCION_EXCEDE_ENVIADO`.
- **Pruebas necesarias:** recepción con cantidad mayor a la despachada se rechaza; verificar que la corrección vía ajuste manual (fuera de esta regla) no modifica los campos de la transferencia original.

### BR-015 — Consistencia entre el stock materializado y el ledger de movimientos

- **Descripción:** `Inventory.quantity_on_hand` debe ser, en todo momento, igual a la suma de ingresos menos retiros registrados en `InventoryMovement` para ese producto/sucursal.
- **Entidades afectadas:** `Inventory`, `InventoryMovement`.
- **Validación:** estructural, no una validación que el usuario pueda disparar — se garantiza porque el único camino de escritura a `Inventory.quantity_on_hand` es el servicio de `inventory`, que siempre inserta el `InventoryMovement` correspondiente en la misma transacción (ver ADR-008 y `docs/DOMAIN_MODEL.md`, decisión 3.2). Se recomienda una comprobación periódica (job de auditoría, fuera del alcance de esta prueba) que recalcule la suma de movimientos y la compare contra el agregado, para detectar una eventual divergencia por error de programación.
- **Error esperado:** no aplica un error de API — una divergencia sería un defecto interno, no una respuesta a una solicitud del cliente.
- **Pruebas necesarias:** prueba de integración que, tras una secuencia de ingresos y retiros mixtos (compra, venta, transferencia, ajuste), recalcula la suma de `InventoryMovement` y verifica que coincide exactamente con `Inventory.quantity_on_hand`.

### BR-016 — La recepción de compra actualiza inventario y costo promedio de forma atómica

- **Descripción:** la actualización de `Inventory.quantity_on_hand`, la creación del `InventoryMovement` y el recálculo de `Inventory.average_unit_cost` (BR-004) ocurren como una única unidad atómica; no puede persistirse el cambio de stock sin el recálculo de costo, ni viceversa.
- **Entidades afectadas:** `PurchaseOrderItem`, `InventoryMovement`, `Inventory`.
- **Validación:** todo el bloque se ejecuta dentro de un único `@Transactional` en el servicio de `purchases`/`inventory` (`docs/ARCHITECTURE.md`, sección 7); si cualquier paso falla (p. ej. el `CHECK (quantity_on_hand >= 0)` improbable aquí, o una violación de unicidad), toda la transacción revierte — no queda un stock actualizado con un costo promedio desactualizado, ni viceversa.
- **Error esperado:** 500 si ocurre un fallo interno a mitad de la operación (no es un error de negocio del cliente, es una garantía de atomicidad); ningún estado parcial debe ser observable externamente.
- **Pruebas necesarias:** simular un fallo forzado entre la actualización de stock y el recálculo de costo (prueba de integración con un punto de fallo inyectado) y verificar que ninguno de los dos cambios queda aplicado tras el rollback.

### BR-017 — Idempotencia de operaciones críticas ante reintento accidental

- **Descripción:** un reintento accidental (doble clic, reintento de red, doble entrega de un mensaje) de una operación crítica no debe aplicar el efecto dos veces.
- **Entidades afectadas:** `PurchaseOrder`/`PurchaseOrderItem`, `Sale`, `InventoryMovement`, `Transfer`/`TransferItem`.
- **Corrección respecto a la versión anterior de esta regla:** el análisis detallado de `docs/CRITICAL_FLOWS.md` (sección 1.1) mostró que **no todas** las operaciones críticas se protegen solo con el estado del recurso — depende de si la operación es una transición de un solo uso o una creación/evento repetible:
  - **Transiciones de un solo uso (guarda de estado, sin clave de idempotencia):** aprobar/rechazar transferencia, despachar, confirmar recepción completa/parcial, cerrar tras tratamiento, confirmar una venta. Se protegen con `UPDATE ... WHERE status = <esperado>`; si la fila ya cambió, `0` filas afectadas → 409.
  - **Creación o evento repetible (requiere `idempotency_key` provista por el cliente):** registrar una venta (`Sale.client_reference_id`), confirmar una recepción de compra (`InventoryMovement.idempotency_key` — una orden `PARTIALLY_RECEIVED` admite legítimamente una siguiente recepción, por lo que el estado por sí solo no distingue un reintento de la siguiente operación real) y un ajuste manual de inventario. Ver columnas pendientes de aprobación en la sección final de este documento.
  - En ambos casos, la comprobación y la escritura ocurren en la misma transacción, con el mismo bloqueo (`version`) usado para concurrencia (BR-022), de modo que dos solicitudes casi simultáneas no pasen ambas la comprobación antes de que la primera confirme su cambio.
- **Error esperado:** 409 `OPERACION_YA_APLICADA` para las transiciones de un solo uso (o el slug específico de estado, p. ej. `ORDEN_YA_RECIBIDA`, `TRANSFERENCIA_ESTADO_INVALIDO`); para las operaciones de categoría "creación repetible", un reintento con la misma `idempotency_key` **no es un error** — se responde con el mismo resultado ya creado.
- **Pruebas necesarias:** enviar la misma confirmación dos veces en sucesión rápida (simulando un doble clic) debe aplicar el efecto una sola vez; para las operaciones de creación repetible, reenviar la misma `idempotency_key` debe devolver el resultado original sin duplicar el efecto, y una `idempotency_key` distinta en una recepción parcial legítima subsiguiente sí debe aplicarse. Ver `docs/CRITICAL_FLOWS.md`, escenario 3.3, para el detalle completo por operación.

### BR-018 — Autorización por rol y por alcance de sucursal

- **Descripción:** toda acción de escritura está limitada por el rol del usuario autenticado y, cuando corresponde, por su sucursal asignada (`docs/USE_CASES.md`, matriz Actor×Acción).
- **Entidades afectadas:** transversal — `User.role_code`, `User.branch_id` frente al `branch_id` del recurso sobre el que se opera.
- **Validación:** dos comprobaciones independientes, ambas necesarias: (1) el rol del usuario tiene permitida la acción (RBAC declarativo, `docs/adr/ADR-005-jwt-rbac.md`); (2) si la acción es de escritura sobre un recurso con `branch_id` (venta, movimiento, orden de compra, línea de transferencia como origen), el `branch_id` del usuario coincide con el del recurso — excepto `ADMIN`, con alcance global. La lectura de inventario/catálogo de otra sucursal está permitida a todos los roles (RF-003) y no aplica esta segunda comprobación.
- **Error esperado:** 403 `ROL_NO_AUTORIZADO` si el rol no tiene el permiso; 403 `SUCURSAL_NO_AUTORIZADA` si el rol lo permite en general pero el usuario no pertenece a la sucursal del recurso.
- **Pruebas necesarias:** un Operador de la sucursal A no puede registrar una venta en la sucursal B; un Gerente no puede gestionar usuarios/sucursales; un Administrador puede operar sobre cualquier sucursal.

### BR-019 — Las listas de precios y descuentos no producen totales inválidos

- **Descripción:** el precio unitario aplicado y el descuento de una línea de venta o de compra no pueden producir un total negativo ni un descuento fuera de un rango válido.
- **Entidades afectadas:** `SaleItem`, `PurchaseOrderItem`, `Price`.
- **Validación:** `unit_price > 0`; `discount_percentage` entre `0` y `100` inclusive (o el equivalente si se modela como monto: `discount_amount >= 0` y `discount_amount <= unit_price × quantity`); `line_total = (unit_price × quantity) - descuento`, y se verifica `line_total >= 0` antes de persistir. El precio usado en una venta debe provenir de un `Price` vigente (`valid_to IS NULL`) de una `PriceList` activa — no se acepta un precio arbitrario tecleado libremente fuera de las listas configuradas, salvo que el diseño de UI decida lo contrario en una fase posterior (fuera del alcance de esta regla).
- **Conversión de unidad aplicada al precio (instrucción explícita, corrige un vacío de diseño anterior):** `Price.unit_price` está fijado en la unidad base del producto (RF-011) — no existe ningún campo que asocie un precio a una unidad concreta. Si una línea de venta usa una unidad alternativa (`SaleItem.unit_of_measure_id` distinto de `Product.base_unit_of_measure_id`), `unit_price` se escala por el mismo `conversion_factor_to_base` que ya se aplica a la cantidad antes de calcular `line_total` — sin este ajuste, vender en una unidad que agrupa varias unidades base (p. ej. una caja de 12) cobraría el precio de una sola unidad por toda la caja. Es la misma idea que BR-004 ya aplica al costo promedio ponderado en `purchases` (`PurchaseReceiptService.applyInventoryReceipt`), aplicada aquí al precio de venta en vez de al costo de compra. `SaleItem.unit_price` persiste el precio ya escalado (el efectivamente cobrado por la unidad vendida), no el precio de lista original. No aplica a `PurchaseOrderItem.unit_price`: ese precio no viene de una lista, lo teclea quien registra la compra directamente para la unidad que eligió en la línea, así que ya es "precio por unidad seleccionada" sin necesitar ningún escalado adicional.
- **Error esperado:** 422 `DESCUENTO_FUERA_DE_RANGO`; 422 `TOTAL_INVALIDO` si el cálculo resulta negativo.
- **Pruebas necesarias:** descuento del 100% dejando el total en cero se acepta (es el límite válido); descuento mayor al 100% o un monto de descuento mayor al subtotal de la línea se rechaza; venta usando un precio de una lista inactiva o de una versión de precio ya cerrada (`valid_to` no nulo) se rechaza.

### BR-020 — Los cambios de estado de una transferencia siguen la máquina de estados válida

- **Descripción:** `Transfer.status` solo puede transicionar según el diagrama definido en `docs/DOMAIN_MODEL.md`, sección 4: `REQUESTED → APPROVED|REJECTED`, `APPROVED → IN_TRANSIT`, `IN_TRANSIT → RECEIVED_COMPLETE|RECEIVED_PARTIAL`, `RECEIVED_PARTIAL → CLOSED`.
- **Entidades afectadas:** `Transfer`.
- **Validación:** cada operación que cambia `status` (aprobar, rechazar, despachar, recibir, cerrar) verifica explícitamente el estado actual antes de aplicar la transición; ninguna transición fuera de esta lista es aceptada, incluida cualquier intento de "retroceder" un estado.
- **Error esperado:** 409 `TRANSICION_INVALIDA`.
- **Pruebas necesarias:** cada transición válida se prueba individualmente; se prueban explícitamente transiciones inválidas representativas (p. ej. `REQUESTED → IN_TRANSIT` directo, `RECEIVED_COMPLETE → APPROVED`, `CLOSED → cualquier estado`) y todas deben rechazarse con 409.

### BR-021 — No se elimina historial auditable

- **Descripción:** ningún registro de `InventoryMovement`, `Sale`/`SaleItem`, `PurchaseOrder`/`PurchaseOrderItem`, `Transfer`/`TransferItem` ni versión de `Price` puede eliminarse ni editarse retroactivamente en sus campos históricos, una vez creado (`docs/DOMAIN_MODEL.md`, sección 3.9).
- **Entidades afectadas:** todas las listadas arriba, más `Branch`, `Product`, `Supplier`, `User` (baja lógica, nunca eliminación física si tienen historial). **Excepción posterior, ver BR-046/BR-049:** `Branch`, `User` y `Supplier` sí exponen un `DELETE` real — la regla que se mantiene intacta es "nunca sin historial asociado", no "nunca ningún `DELETE`"; `Product` sigue sin uno.
- **Validación:** no se expone ninguna operación de `DELETE` para estas entidades en la capa de aplicación (salvo `Branch`/`User`/`Supplier`, ver excepción arriba); a nivel de base de datos, se recomienda revocar los privilegios `DELETE`/`UPDATE` sobre las tablas de historial puro (`InventoryMovement`) al rol de aplicación, y usar `ON DELETE RESTRICT` en las claves foráneas hacia `Branch`/`Product`/`Supplier`/`User` para que ni siquiera un error de programación pueda eliminarlos si tienen historial asociado.
- **Error esperado:** 404 si se intenta acceder a una operación de eliminación inexistente en la API (no se expone la ruta); 409 `ENTIDAD_CON_HISTORIAL` si se intenta desactivar/eliminar una `Branch`/`Product`/`Supplier`/`User` que tiene historial y el diseño decide validarlo explícitamente en vez de dejar que la sola falta de un endpoint de borrado lo prevenga.
- **Pruebas necesarias:** intentar eliminar físicamente un producto con movimientos asociados falla por restricción de clave foránea; ninguna prueba de la suite debe requerir editar un `InventoryMovement`, `SaleItem` o `Price` ya creado.

### BR-022 — Concurrencia sobre el mismo stock: bloqueo optimista y reintento

- **Descripción:** dos operaciones concurrentes que afectan el mismo `Inventory` (misma combinación producto/sucursal) no deben poder aplicar ambas su efecto sobre una lectura obsoleta del stock.
- **Entidades afectadas:** `Inventory` (columna `version`).
- **Validación:** toda escritura sobre `Inventory.quantity_on_hand` (y, por el mismo motivo, sobre `PurchaseOrderItem.quantity_received`, ver ajustes pendientes) lee la fila junto con su `version` actual y, al escribir, verifica que la versión no haya cambiado (bloqueo optimista, `docs/ARCHITECTURE.md`, sección 7); si cambió, la transacción falla con conflicto de versión y el servicio reintenta automáticamente la operación completa (releyendo el stock actualizado). **Mecanismo definido en `docs/CRITICAL_FLOWS.md` (sección 1.2):** máximo 3 intentos, con un backoff aleatorio corto entre reintentos, antes de propagar el error al cliente.
- **Error esperado:** si se agotan los reintentos automáticos, 409 `CONFLICTO_CONCURRENCIA`. El cliente no debería ver esto en el uso normal — es la salida de emergencia cuando la contención es tan alta que ni el reintento automático la resuelve.
- **Pruebas necesarias:** prueba de concurrencia con N hilos escribiendo sobre el mismo `Inventory` simultáneamente verifica que el resultado final es exactamente la suma/resta esperada, sin pérdidas de actualización ("lost update"); prueba que fuerza el agotamiento de reintentos y verifica el código 409.

### BR-023 — El ajuste manual de inventario es una operación de excepción, no un sustituto de compra/venta/transferencia

- **Descripción:** un ajuste manual (`AJUSTE_INGRESO`/`AJUSTE_RETIRO`) existe únicamente para corregir discrepancias entre el stock del sistema y el conteo físico real (mermas no capturadas, error de captura, hallazgos de inventario físico); nunca debe usarse como sustituto de un flujo de negocio ya modelado.
- **Entidades afectadas:** `InventoryMovement`, `Inventory`.
- **Validación:** `notes` (motivo) es obligatorio y no puede quedar vacío — a diferencia de otros movimientos, aquí no basta el valor del `ENUM reason`, porque un ajuste necesita una justificación legible por auditoría; el movimiento no puede tener poblada ninguna FK documental (`purchase_order_item_id`, `sale_item_id`, `transfer_item_id`), ya que un ajuste no cuelga de un documento comercial; se aplican además BR-012 (cantidad positiva) y la prevención general de stock negativo.
- **Error esperado:** 400 `NOTES_REQUERIDO` si falta el motivo; 422 `CANTIDAD_INVALIDA`; 422 `STOCK_INSUFICIENTE` si el ajuste de retiro dejaría el stock en negativo.
- **Pruebas necesarias:** ajuste sin `notes` se rechaza; ajuste de retiro mayor al stock disponible se rechaza igual que cualquier otro retiro; ver `docs/CRITICAL_FLOWS.md`, flujo G, para el detalle de idempotencia (requiere `idempotency_key`, es una operación de creación repetible).

### BR-024 — Consistencia rol/sucursal al crear o editar un usuario **[Origen: RF-037 a RF-039; DOMAIN_MODEL.md, sección 2.3]**

- **Descripción:** un usuario `ADMIN` no debe tener sucursal asignada (alcance global); un usuario `MANAGER`/`OPERATOR` siempre debe tener una sucursal asignada, que además debe existir y estar activa. Regla añadida al implementar UC-14 — no estaba enumerada explícitamente en versiones anteriores de este catálogo, aunque ya se derivaba del `CHECK` de `docs/DOMAIN_MODEL.md`.
- **Entidades afectadas:** `User`, `Branch`.
- **Validación:** `users.UserService` valida esto en la capa de aplicación antes de escribir (defensa en profundidad), replicando el `CHECK (role_code = 'ADMIN' OR branch_id IS NOT NULL)` de `V3__create_users_table.sql`, que actúa como última línea de defensa en base de datos.
- **Error esperado:** 422 `ADMIN_SIN_SUCURSAL` si `role=ADMIN` y se envía `branchId`; 422 `SUCURSAL_REQUERIDA` si `role≠ADMIN` y no se envía `branchId`; 404 `SUCURSAL_NO_ENCONTRADA` si el `branchId` no existe; 422 `SUCURSAL_INACTIVA` si la sucursal existe pero está desactivada.
- **Pruebas necesarias:** las cuatro combinaciones inválidas anteriores se rechazan con su código específico; crear/editar un `MANAGER`/`OPERATOR` con una sucursal activa existente se acepta; crear un `ADMIN` sin sucursal se acepta.

### BR-025 — No desactivar una sucursal con usuarios activos asignados **[Origen: UC-15, flujo alterno 1a]**

- **Descripción:** no se puede desactivar una sucursal mientras tenga usuarios activos (`MANAGER`/`OPERATOR`) asignados — dejaría esos usuarios apuntando a una sucursal inactiva, una inconsistencia análoga a la que UC-15 pide evitar para inventario/transferencias (módulos todavía no implementados; esta es la instancia concreta de la regla que sí se puede aplicar hoy).
- **Entidades afectadas:** `Branch`, `User`.
- **Validación:** `branches.BranchService.deactivate` comprueba `existsByBranchIdAndActiveTrue` antes de desactivar. No impide desactivar una sucursal sin usuarios activos (ya reasignados o desactivados primero).
- **Error esperado:** 409 `SUCURSAL_CON_USUARIOS_ACTIVOS`.
- **Pruebas necesarias:** desactivar una sucursal con al menos un usuario activo asignado se rechaza; desactivarla después de reasignar/desactivar a todos sus usuarios se acepta.

### BR-026 — La unidad base de un producto es inmutable **[Decisión]**

- **Descripción:** el factor de conversión de la unidad base de un producto (siempre `1`, por definición) no se puede modificar mediante el endpoint de edición de unidades alternativas. Regla añadida al implementar el módulo `products` para satisfacer el requisito de conversiones deterministas de BR-011 — no estaba enumerada explícitamente en versiones anteriores de este catálogo.
- **Entidades afectadas:** `ProductUnit`.
- **Validación:** `products.ProductUnitService.updateConversionFactor` rechaza la operación si el `ProductUnit` objetivo tiene `is_base_unit = true`, antes de aplicar el cambio.
- **Error esperado:** 422 `UNIDAD_BASE_INMUTABLE`.
- **Pruebas necesarias:** intentar editar el factor de conversión de la unidad base de un producto se rechaza con este código; editar el factor de una unidad alternativa (no base) se acepta.

### BR-027 — El motivo de un ajuste manual debe ser compatible con su dirección **[Decisión]**

- **Descripción:** un ajuste manual (BR-023) con `direction=INGRESO` solo admite `reason=DEVOLUCION` o `AJUSTE_INGRESO`; con `direction=RETIRO` solo admite `MERMA` o `AJUSTE_RETIRO`. `COMPRA`, `VENTA`, `TRANSFERENCIA_SALIDA`/`TRANSFERENCIA_ENTRADA` existen en el catálogo de `docs/DOMAIN_MODEL.md` (sección 2.8) pero ningún flujo de esta fase los produce — quedan reservados para cuando se implementen `purchases`/`sales`/`transfers`. Si no se envía `reason`, se asume `AJUSTE_INGRESO`/`AJUSTE_RETIRO` según la dirección. Regla añadida al implementar el módulo `inventory` — no estaba enumerada explícitamente en versiones anteriores de este catálogo.
- **Entidades afectadas:** `InventoryMovement`.
- **Validación:** `inventory.InventoryMovementService.resolveReason` verifica la pertenencia del motivo recibido al conjunto permitido para la dirección declarada, antes de crear el movimiento.
- **Error esperado:** 422 `MOTIVO_INCOMPATIBLE_CON_DIRECCION`.
- **Pruebas necesarias:** un ajuste `INGRESO` con `reason=MERMA` (u otro motivo de salida) se rechaza; un ajuste `INGRESO` con `reason=DEVOLUCION` se acepta; sin `reason`, el motivo por defecto corresponde a la dirección enviada.

### BR-028 — El precio de recepción es distinto del precio pactado en la orden **[Decisión]**

- **Descripción:** `PurchaseOrderItem.unit_price` (fijado al crear la orden) es la condición comercial pactada — inmutable, se usa para el `line_total` de la orden. El `unitPrice` que se envía en `POST /purchase-orders/{id}/receipts` (docs/openapi.yaml, `PurchaseReceiptRequest`) es el costo efectivamente recibido en esa recepción — puede diferir del pactado (cambios de precio entre orden y entrega, ajuste de factura) — y se usa exclusivamente para recalcular `Inventory.average_unit_cost` (BR-004). Ninguno sobrescribe al otro. Decisión tomada al implementar el módulo `purchases` para reconciliar una aparente tensión entre `docs/DOMAIN_MODEL.md` (sección 2.7, que sugiere que el costo histórico vive únicamente en `PurchaseOrderItem.unit_price`) y la firma explícita de `recibirCompra(..., precioUnitario, ...)` en `docs/CRITICAL_FLOWS.md` (flujo B) más el campo `unitPrice` del propio `PurchaseReceiptRequest` — se prioriza el pseudocódigo y el contrato ya aprobados, por ser más detallados y posteriores.
- **Entidades afectadas:** `PurchaseOrderItem`, `Inventory`.
- **Validación:** `PurchaseReceiptService.applyInventoryReceipt` usa el `unitPrice` de la solicitud de recepción, nunca el de `PurchaseOrderItem`, para la fórmula de costo promedio ponderado.
- **Error esperado:** no aplica — es una decisión de diseño, no una regla rechazable.
- **Pruebas necesarias:** recibir a un precio distinto del pactado en la orden actualiza `average_unit_cost` según el precio de recepción, sin alterar `PurchaseOrderItem.unit_price` ni su `lineTotal` original.

### BR-029 — Clave de idempotencia derivada por línea en la recepción de compra **[Decisión]**

- **Descripción:** `POST /purchase-orders/{id}/receipts` recibe un único encabezado `Idempotency-Key` por solicitud, pero puede traer varias líneas (`docs/openapi.yaml`, `PurchaseReceiptRequest.items[]`) y `InventoryMovement.idempotency_key` es única por movimiento (uno por línea). Se deriva una clave por línea como `<Idempotency-Key>:<purchaseOrderItemId>`. Esto permite reintentar la solicitud completa (cada línea ya aplicada se detecta y no se reaplica, devolviendo el resultado original) sin que el mismo header choque con otra recepción legítima que use una línea distinta de la misma orden.
- **Entidades afectadas:** `InventoryMovement`.
- **Validación:** `PurchaseReceiptService.receive` calcula la clave derivada por cada línea antes de comprobar `InventoryMovementRepository.findByIdempotencyKey`; esta comprobación ocurre **antes** que la de `PurchaseOrder.status` (BR-017/BR-023 generalizado a este flujo) — un reintento legítimo debe replicar su resultado incluso si la propia recepción original ya dejó la orden en `RECEIVED`.
- **Error esperado:** no aplica un error propio — un reintento con la misma clave no es un error (BR-017).
- **Pruebas necesarias:** reenviar la misma solicitud completa (mismo `Idempotency-Key`, mismas líneas) no duplica el incremento de stock ni el `quantity_received`; reenviar la misma clave después de que la orden ya quedó `RECEIVED` por ese mismo envío devuelve 200 con el resultado original, no 409; una clave distinta sí aplica una recepción legítima subsiguiente.

### BR-030 — Resolución de la lista de precios cuando la venta no especifica una **[Decisión]**

- **Descripción:** `POST /sales` acepta `priceListId` opcional (docs/openapi.yaml, `SaleCreateRequest`). Si se omite, se resuelve: primero una `PriceList` activa propia de la sucursal (`branch_id` = sucursal de la venta); si no existe, la lista global activa (`branch_id IS NULL`). Ningún documento aprobado detalla este algoritmo de resolución — es la interpretación más directa de "`branch_id` nulo = lista global" (docs/DOMAIN_MODEL.md, sección 2.13), priorizando lo específico de la sucursal sobre lo global.
- **Entidades afectadas:** `PriceList`, `Sale`.
- **Validación:** `SaleService.resolvePriceList` — si se especifica `priceListId`, se usa directamente (validando que exista y esté activa); si no, aplica el orden de prioridad descrito.
- **Error esperado:** 404 `LISTA_PRECIOS_NO_ENCONTRADA` si se especifica un id inexistente; 409 `LISTA_PRECIOS_INACTIVA` si existe pero está inactiva; 422 `LISTA_PRECIOS_NO_ENCONTRADA` si no se especifica ninguna y no hay lista activa aplicable (ni de sucursal ni global) — 422 en vez de 404 porque no falta un recurso referenciado por id, sino que el estado de las listas de precios existentes no permite completar la operación.
- **Pruebas necesarias:** venta sin `priceListId` con una lista de sucursal activa usa esa lista; sin lista de sucursal, cae a la global; sin ninguna lista activa, se rechaza.

### BR-031 — El estado `VOIDED` de una venta no se implementa todavía **[Origen: docs/DOMAIN_MODEL.md, decisión de aprobación 9, sin resolver]**

- **Descripción:** `docs/DOMAIN_MODEL.md` lista `Sale.status` como `CONFIRMED`/`VOIDED`, pero deja explícitamente pendiente de aprobación si la anulación de ventas se modela como ese estado o como un ajuste de inventario aparte. Al implementar el módulo `sales`, `SaleStatus` (Java) solo define `CONFIRMED` — no se presenta como resuelta una decisión que sigue abierta. `POST /sales/{id}/void` no se implementa (docs/API_DESIGN.md, sección 7.8, ya lo marca condicionado a esa aprobación).
- **Entidades afectadas:** `Sale`.
- **Validación:** no aplica — es una omisión deliberada, no una regla que el sistema imponga en tiempo de ejecución.
- **Error esperado:** no aplica.
- **Pruebas necesarias:** ninguna prueba de la suite depende de `VOIDED` ni de un endpoint de anulación.

### BR-032 — Clave de idempotencia de la solicitud de transferencia **[Decisión]**

- **Descripción:** la solicitud de transferencia (flujo C1) es una operación de creación repetible (categoría 2, `docs/CRITICAL_FLOWS.md` sección 1.1), por lo que necesita una clave de idempotencia — pero `docs/DOMAIN_MODEL.md` (sección 2.18) no listaba ninguna columna para ella. Se agrega `Transfer.client_reference_id` (texto, `UNIQUE`, nullable), exactamente el mismo patrón ya aprobado y aplicado en `Sale.client_reference_id` (decisión pendiente #5, resuelta en la fase de `sales`). Nulo para las transferencias que el propio sistema genera —las de reenvío del flujo F2—, que no provienen de una solicitud HTTP susceptible de doble clic.
- **Entidades afectadas:** `Transfer`.
- **Validación:** `TransferService.request` consulta la clave antes de crear nada; si ya existe, devuelve la transferencia original sin crear una segunda.
- **Error esperado:** 400 `IDEMPOTENCY_KEY_REQUERIDO` si falta el encabezado; un reintento con la misma clave **no** es un error (BR-017).
- **Pruebas necesarias:** doble envío con la misma clave no crea dos solicitudes; sin encabezado se rechaza.

### BR-033 — La cantidad aprobada no puede exceder la solicitada **[Decisión]**

- **Descripción:** al aprobar (flujo C2) la sucursal origen puede **ajustar la cantidad hacia abajo** (BR-005 lo contempla explícitamente: "aprobarla con una cantidad ajustada menor"), pero nunca hacia arriba — aprobar más de lo que el destino pidió no es un ajuste, es una transferencia distinta. Ni `docs/DOMAIN_MODEL.md` ni BR-005 lo decían de forma explícita; se formaliza aquí.
- **Entidades afectadas:** `TransferItem`.
- **Validación:** `TransferService.approve` compara contra `quantity_requested`, reforzado por `CHECK (quantity_approved IS NULL OR (quantity_approved > 0 AND quantity_approved <= quantity_requested))`. La cadena completa queda acotada en base de datos: `received ≤ shipped ≤ approved ≤ requested`.
- **Error esperado:** 422 `CANTIDAD_APROBADA_EXCEDE_SOLICITADO`.
- **Pruebas necesarias:** aprobar más de lo solicitado se rechaza; aprobar una cantidad menor se acepta y es la que limita el despacho.

### BR-034 — Aprobación y despacho cubren todas las líneas; la recepción admite subconjuntos **[Decisión]**

- **Descripción:** una transferencia tiene un **único tramo de envío** (`docs/DOMAIN_MODEL.md` 2.17; `docs/API_DESIGN.md` 7.9), así que el despacho no es parcial por línea: la solicitud debe incluir todas. Lo mismo se aplica a la aprobación, por una razón derivada: una línea sin `quantity_approved` no podría despacharse nunca (el despacho exige `shipped ≤ approved`), dejando la transferencia en un callejón sin salida. La **recepción**, en cambio, sí admite un subconjunto de líneas por llamada — el conteo físico puede hacerse línea por línea en momentos distintos (escenario 3.5), y el estado de la transferencia solo avanza cuando todas quedan atendidas.
- **Entidades afectadas:** `Transfer`, `TransferItem`.
- **Validación:** `TransferService.approve`/`dispatch` comparan el conjunto de líneas recibido contra el de la transferencia; `receive` no lo hace y recalcula el estado al final.
- **Error esperado:** 422 `APROBACION_INCOMPLETA`; 422 `DESPACHO_INCOMPLETO`; 404 `LINEA_TRANSFERENCIA_NO_ENCONTRADA` si se referencia una línea de otra transferencia; 422 `LINEA_DUPLICADA_EN_SOLICITUD` si la misma línea aparece dos veces.
- **Pruebas necesarias:** aprobar o despachar omitiendo una línea de una transferencia de dos se rechaza; recibir una sola línea de dos deja la transferencia en tránsito y la segunda recepción la cierra.

### BR-035 — Faltante nulo en recepción completa; recepción en cero no genera movimiento **[Decisión]**

- **Descripción:** resuelve la ambigüedad que BR-008 dejaba abierta ("`quantity_missing` en `NULL` o `0`, no ambiguo"): se persiste **`NULL` cuando la recepción fue completa** y la diferencia solo cuando realmente falta algo, tal como muestra el ejemplo 9.6 de `docs/API_DESIGN.md`. Así, "tiene faltante" es exactamente `quantity_missing IS NOT NULL`. Complemento: una línea recibida en **cero** (no llegó nada) es una recepción parcial válida (flujo E+F1) pero **no genera `InventoryMovement`** — un movimiento de cantidad cero violaría `CHECK (quantity > 0)` y no representa ningún hecho de stock.
- **Entidades afectadas:** `TransferItem`, `InventoryMovement`, `Inventory`.
- **Validación:** `TransferService.receive` guarda `null` si `received == shipped`, y solo toca inventario/ledger si `received > 0`.
- **Error esperado:** no aplica — ambos son caminos válidos, no rechazos.
- **Pruebas necesarias:** recepción completa deja `quantityMissing` nulo; recepción de 0 de N deja `quantityMissing = N`, sin fila de inventario ni movimiento en el destino.

### BR-036 — La ruta de una transferencia se deriva del par de sucursales, no de un campo tecleado **[Decisión]**

- **Descripción:** `docs/DOMAIN_MODEL.md` (2.18) prevé `Transfer.route_id`, y la tabla `route` tiene `UNIQUE (origin_branch_id, destination_branch_id)` (2.17). Eso significa que el par de sucursales **ya identifica** la ruta: guardar además `route_id` es una denormalización. Se resuelve así: `route_id` existe (el modelo aprobado lo pide) pero **se asigna solo**, resolviendo el par al crear la transferencia — nunca llega en el payload —, y el reporte de cumplimiento agrupa y filtra **por el par de sucursales**, no por `route_id`. Consecuencia deseada: una transferencia creada antes de que su ruta fuera clasificada tiene `route_id` nulo pero **igual aparece** en el reporte de esa ruta, porque el par no puede desincronizarse.
- **Entidades afectadas:** `Transfer`, `Route`.
- **Validación:** `TransferService.resolveRouteId` consulta `RouteService.findByBranchPair` al crear la transferencia (y su reenvío); `LogisticsComplianceService` traduce el filtro `routeId` a su par origen-destino antes de consultar.
- **Error esperado:** no aplica — que un par no tenga ruta clasificada no es un error, deja `route_id` nulo.
- **Pruebas necesarias:** una transferencia creada tras clasificar la ruta recibe su `routeId` sin enviarlo en el payload; una creada antes sigue contando en el reporte de esa ruta.

### BR-037 — Una ruta se clasifica con una sola etiqueta y su par origen-destino es inmutable **[Decisión]**

- **Descripción:** `docs/DOMAIN_MODEL.md` (2.17) dejaba abierto si `classification` es una etiqueta o un conjunto ("o combinable como conjunto si se requiere más de una etiqueta"). Se opta por **una sola** etiqueta (`PRIORITY`/`COST`/`TIME`): RF-028 pide clasificar "por al menos uno de los tres criterios", y un conjunto exigiría una tabla puente que ningún requisito justifica hoy. Además, el par origen-destino es la identidad de negocio de la ruta y por tanto **no es editable**: `PATCH /routes/{id}` solo cambia la clasificación (`docs/API_DESIGN.md` 7.9: "actualiza clasificación"); cambiar el par no sería reclasificar esta ruta sino crear otra distinta.
- **Entidades afectadas:** `Route`.
- **Validación:** `UpdateRouteRequest` solo expone `classification`; `CHECK` en base de datos restringe los tres valores y `UNIQUE (origin, destination)` impide duplicar el par.
- **Error esperado:** 409 `RUTA_YA_EXISTE` al clasificar dos veces el mismo par; 422 `ORIGEN_IGUAL_DESTINO`.
- **Pruebas necesarias:** reclasificar cambia solo la clasificación; duplicar el par se rechaza; origen igual a destino se rechaza.

### BR-038 — El cumplimiento logístico se calcula, no se almacena **[Decisión]**

- **Descripción:** ninguna métrica de cumplimiento se persiste. Todo se deriva, en tiempo de consulta, de datos que el propio flujo de transferencias ya escribió: `dispatched_at`, `received_at`, `estimated_arrival_date` y `status`. No existe una columna "entregado a tiempo" ni un contador materializado que pudiera contradecir los hechos. Precisiones que esto obliga a fijar: (a) una transferencia **sin despacho** no entra al reporte —no tiene tiempo de entrega que medir— y por tanto no cuenta ni como cumplida ni como incumplida; (b) una entrega despachada **sin fecha estimada** se cuenta aparte (`notEvaluable`) en vez de asumirse puntual, que inflaría el indicador; (c) el porcentaje de cumplimiento se calcula solo sobre las entregas evaluables y es **nulo**, no 100%, cuando no hay ninguna; (d) "recibida con faltante" y "recibida tarde" son indicadores independientes: una entrega puede ser puntual e incompleta a la vez.
- **Entidades afectadas:** ninguna nueva — `Transfer` (solo lectura), `Route` (solo lectura).
- **Validación:** `LogisticsComplianceService` es de solo lectura; `reports` es hoja del grafo de dependencias (`docs/ARCHITECTURE.md`, sección 4) y lee `transfers` y `logistics` a través de sus servicios, nunca de sus repositorios.
- **Error esperado:** un rango sin datos devuelve un reporte vacío con 200, no un 404 (UC-12, flujo alterno 3a).
- **Pruebas necesarias:** estimado vs. real con una entrega puntual y una tardía; transferencia sin despacho excluida; recepción parcial contada como entregada y como faltante; despacho sin fecha estimada no contado como puntual; rango vacío devuelve 200.


### BR-039 — Ventas del mes actual vs. anteriores: ventana y agregación **[Decisión]**

- **Descripción:** RF-031 pide "volumen de ventas del mes en curso vs. meses anteriores" sin fijar cuántos meses atrás mostrar. Se adopta el supuesto ya registrado en `docs/PROJECT_BRIEF.md` (sección 3.8): **mes actual + 3 meses anteriores** (parámetro `months`, por defecto 3 — total de baldes = `months + 1`), tal como ya lo documenta `docs/openapi.yaml` para `GET /dashboard/sales-summary`. Cada balde es un mes calendario UTC; el balde 0 es el mes en curso.
- **Entidades afectadas:** ninguna nueva — lectura de `Sale` (`status = CONFIRMED` únicamente).
- **Validación:** por balde, `totalSales = SUM(Sale.total)` y `salesCount = COUNT(Sale.id)`, agregados en una sola consulta SQL agrupada por rango de fecha (comparación de fronteras calculadas en el servicio, no una función de truncamiento específica de un motor — portable entre PostgreSQL y H2). Un balde sin ventas aparece igual, con `totalSales = 0`, nunca se omite. La variación porcentual mostrada (mes actual vs. el inmediatamente anterior) es aritmética simple sobre los dos totales ya agregados, y es `null` —no `Infinity` ni `0%`— cuando el mes anterior no tuvo ventas. `branchId` es obligatorio: este endpoint reporta **una** sucursal a la vez — comparar entre sucursales es el propósito de BR-043, no de este.
- **Error esperado:** 400 `SUCURSAL_REQUERIDA` si falta `branchId`; 403 `SUCURSAL_NO_AUTORIZADA` si un `OPERATOR` pide una sucursal ajena — `MANAGER`/`ADMIN` pueden consultar cualquiera, mismo criterio ya aplicado en `reports/logistics-compliance` (`docs/API_DESIGN.md`, sección 6).
- **Pruebas necesarias:** dataset con ventas en 2 de los 4 meses de la ventana — los otros 2 aparecen en 0; variación porcentual correcta cuando ambos meses tienen ventas; variación `null` cuando el mes anterior no tuvo ninguna; `branchId` ausente rechazado; sucursal ajena rechazada para `OPERATOR`.

### BR-040 — Rotación de inventario y productos de alta/baja demanda **[Decisión]**

- **Descripción:** RF-032 pide "rotación de inventario" sin definir la fórmula ni el dato de que dispone el sistema para calcularla. El sistema **no** conserva una serie histórica de saldos de inventario — solo `Inventory.quantity_on_hand` materializado al momento de la consulta. La fórmula clásica de rotación (unidades vendidas ÷ inventario **promedio** del período) exigiría snapshots periódicos que el modelo aprobado no guarda; inventar esa precisión sería falsear el dato. Se adopta una definición simplificada y explícita: **rotación = unidades vendidas en la ventana (BR-039) ÷ stock actual (`quantityOnHand`)**. Si el stock actual es 0, la rotación **no es calculable** y se devuelve `null` (nunca una división por cero ni un valor infinito).
- **Entidades afectadas:** ninguna nueva — lectura de `SaleItem`+`Sale` (unidades vendidas) e `Inventory` (stock actual).
- **Validación:** las unidades vendidas por producto se agregan en SQL (`SUM(SaleItem.quantity) GROUP BY productId`, uniendo `SaleItem` con `Sale` por `saleId` — mismo patrón de unión sin asociación JPA ya usado en `InventoryRepository.search` con `Product`), limitando el resultado a los `limit` productos de mayor y de menor venta (`ORDER BY` + `LIMIT` en la base de datos, nunca cargando todo el catálogo para ordenar en memoria). "Alta demanda" = los `limit` productos con más unidades vendidas en la ventana; "baja demanda" = los `limit` con menos, **incluyendo productos con 0 ventas** en la ventana (es la señal más directa de baja demanda, no se excluye). Solo se consideran productos con una fila de `Inventory` en la sucursal consultada — no todo el catálogo global.
- **Error esperado:** igual que BR-039 (`branchId` obligatorio y con el mismo control de acceso).
- **Pruebas necesarias:** producto con ventas altas aparece en "alta demanda"; producto sin ninguna venta en la ventana aparece en "baja demanda" con rotación `null` si además tiene stock 0, o con rotación `0` si tiene stock > 0; división por stock 0 nunca lanza error ni deja `Infinity`; `limit` respeta el máximo configurado.

### BR-041 — Transferencias activas y su impacto en inventario **[Decisión]**

- **Descripción:** RF-033 pide "estado de transferencias activas y su impacto en inventario". "Activa" se define como **cualquier estado no terminal** de la máquina de estados de BR-020: `REQUESTED`, `APPROVED`, `IN_TRANSIT`, `RECEIVED_PARTIAL` (con al menos un faltante sin tratar). `REJECTED`, `RECEIVED_COMPLETE` y `CLOSED` quedan fuera. El "impacto en inventario" distingue explícitamente lo **ya ocurrido** de lo **proyectado**, para no presentar una cosa como la otra.
- **Entidades afectadas:** ninguna nueva — lectura de `Transfer`+`TransferItem`, acotada a las transferencias donde la sucursal consultada es origen o destino.
- **Validación:** por línea de una transferencia activa, una de dos — nunca ambas, porque el despacho de una línea es un único evento que no se repite (BR-034): si **no se ha despachado** (`quantityShipped IS NULL`), toda la cantidad comprometida es efecto proyectado: `unitsPendingDispatch += (quantityApproved ?? quantityRequested)`; si **ya se despachó**, lo no despachado de esa línea no vuelve a estar "pendiente" —no hay un segundo envío que lo complete— y lo que cuenta es lo real: `unitsInTransit += quantityShipped − (quantityReceived ?? 0)`, el stock que ya salió del origen y todavía no llegó al destino. Ambos son ≥ 0 por construcción (BR-013, BR-014).
- **Error esperado:** igual que BR-039 (`branchId` obligatorio y con el mismo control de acceso).
- **Pruebas necesarias:** una línea `IN_TRANSIT` reporta `unitsInTransit` igual a lo despachado y aún no recibido, con `unitsPendingDispatch = 0` para esa línea aunque se haya despachado menos de lo aprobado; una línea `REQUESTED`/`APPROVED` sin despachar reporta `unitsPendingDispatch` igual a lo comprometido y `unitsInTransit = 0`; una transferencia cerrada o rechazada no aparece en absoluto.

### BR-042 — Indicadores de reabastecimiento **[Decisión]**

- **Descripción:** RF-034 pide destacar "productos próximos a agotarse". Se reutiliza exactamente el mismo umbral ya aprobado en BR-010 (`quantity_on_hand <= minimum_stock`) — no se define un segundo umbral distinto para el dashboard, que discreparía del que ya usa el filtro `lowStock` de `GET /inventory`.
- **Entidades afectadas:** ninguna nueva — lectura de `Inventory`.
- **Validación:** el conteo total de productos bajo el umbral se agrega con `COUNT` en SQL; el listado de los más urgentes se ordena por `(quantity_on_hand - minimum_stock)` ascendente (más negativo = más urgente) con desempate por `quantity_on_hand` ascendente, limitado a `limit` filas mediante `LIMIT`/`Pageable` — nunca cargando todo el inventario de la sucursal para ordenar en memoria.
- **Error esperado:** igual que BR-039 (`branchId` obligatorio y con el mismo control de acceso).
- **Pruebas necesarias:** un producto exactamente en el mínimo aparece incluido; el orden de urgencia coloca primero al que está más por debajo de su mínimo; una sucursal sin productos bajo el umbral devuelve conteo 0 y lista vacía, no un error.

### BR-043 — Comparativa entre sucursales: solo perfiles administrativos **[Decisión]**

- **Descripción:** RF-035 exige que la comparativa entre sucursales sea visible **solo** para perfiles administrativos. Se adopta el mismo corte ya aprobado para `reports/logistics-compliance` (`docs/API_DESIGN.md`, sección 6): `MANAGER` y `ADMIN` pueden compararlas todas; `OPERATOR` no accede en absoluto al endpoint (403, no una respuesta vacía ni una versión reducida).
- **Entidades afectadas:** ninguna nueva.
- **Validación:** por cada sucursal activa se calculan, con las mismas consultas agregadas de BR-039/BR-041/BR-042 (una vez por sucursal, nunca cargando todas las sucursales en una sola consulta sin agrupar): ventas del mes en curso, conteo de transferencias activas que la involucran y conteo de productos bajo el umbral de reabastecimiento. No es un promedio ni un ranking adicional — son las mismas cifras que cada sucursal ya expone individualmente, solo yuxtapuestas para comparar.
- **Error esperado:** 403 `ROL_NO_AUTORIZADO` para `OPERATOR`.
- **Pruebas necesarias:** `OPERATOR` recibe 403; `MANAGER`/`ADMIN` reciben una fila por cada sucursal activa; una sucursal sin ventas ni transferencias aparece con ceros, no se omite.

---

### BR-044 — Desactivar un usuario exige motivo, visible mientras siga desactivado **[Decisión]**

- **Descripción:** UC-14 pide que desactivar a un usuario registre por qué, y que ese motivo se muestre mientras la cuenta siga inactiva. `POST /users/{id}/deactivate` pasa a exigir `{ reason }` (`@NotBlank`, máx. 500 caracteres) en vez de no aceptar cuerpo. El motivo se limpia al reactivar (`activate()`): describe la desactivación que se está cerrando, no tiene sentido que sobreviva a ella.
- **Entidades afectadas:** `users.deactivation_reason` (columna nueva, migración V26, nula para usuarios activos o nunca desactivados).
- **Validación:** `reason` en blanco o ausente → 400 `VALIDATION_ERROR`, igual que cualquier otro campo obligatorio de la API.
- **Pruebas necesarias:** desactivar sin motivo se rechaza; desactivar con motivo lo persiste y lo expone en `UserResponse.deactivationReason`; reactivar lo limpia.

### BR-045 — Un ADMIN no puede desactivarse ni eliminarse a sí mismo **[Decisión]**

- **Descripción:** sin esta regla, un ADMIN podría quitarse a sí mismo el único acceso administrativo disponible (si es el único `ADMIN` activo) sin que ningún otro rol pueda revertirlo. `UserService.deactivate`/`UserService.delete` comparan el id objetivo contra `AuthenticatedUser.userId()` (el principal ya resuelto desde el JWT, sin volver a consultar la base) antes de cualquier otra validación.
- **Entidades afectadas:** ninguna nueva.
- **Error esperado:** 422 `NO_AUTOGESTION` cuando el id objetivo coincide con el del usuario autenticado.
- **Pruebas necesarias:** un ADMIN intentando desactivarse o eliminarse a sí mismo recibe 422; sobre cualquier otro usuario, ambas operaciones proceden con normalidad.

### BR-046 — Eliminar sucursal o usuario es real y no reversible, solo sin datos asociados **[Decisión]**

- **Descripción:** a diferencia de activar/desactivar (reversible, UC-14/UC-15), `DELETE /branches/{id}` y `DELETE /users/{id}` borran la fila. Ambos módulos ya son punto de referencia desde `inventory`, `purchases`, `sales`, `transfers`, `logistics`/`price_list` (sucursal) y `inventory_movement`/`purchase_order`/`sale`/`transfer` (usuario) con FK `ON DELETE RESTRICT` (Flyway) — la base de datos ya impide huérfanos aunque la aplicación no comprobara nada.
  - Para sucursales, `BranchService.delete` además comprueba explícitamente `users.branch_id` (cualquier usuario asignado, activo o no) **antes** de intentar el `DELETE`, porque `users` ya es una dependencia legítima de `branches` (igual que en `deactivate`) y así el caso más común queda cubierto con una consulta directa, sin esperar a la excepción de la base de datos.
  - El resto de referencias posibles (inventario, compras, ventas, transferencias, rutas de una sucursal; movimientos, compras, ventas, transferencias de un usuario) **no** se comprueban con una consulta explícita: hacerlo obligaría a `branches`/`users` a depender de módulos que hoy dependen de ellos, invirtiendo el grafo de dependencias unidireccional (`docs/ARCHITECTURE.md`, sección 4). Se confía en la FK `ON DELETE RESTRICT` ya declarada: PostgreSQL rechaza el `DELETE` y el servicio traduce la `DataIntegrityViolationException` resultante a un conflicto legible.
- **Entidades afectadas:** ninguna nueva.
- **Error esperado:** 409 `SUCURSAL_CON_DATOS_ASOCIADOS` / `USUARIO_CON_DATOS_ASOCIADOS` cuando hay cualquier dato asociado; 204 sin contenido cuando la eliminación procede.
- **Validación:** la vía de la FK **solo se ejerce contra PostgreSQL real** — Hibernate no genera esas restricciones en el esquema de pruebas H2 (`ddl-auto=create-drop`) porque el modelo no usa asociaciones JPA (`docs/DECISIONS.md`), así que esa parte se verificó en vivo contra Docker Compose, no por la suite automatizada, igual que `FlywayMigrationIntegrationTest`.
- **Pruebas necesarias:** eliminar sin datos asociados devuelve 204 y el recurso deja de existir; eliminar una sucursal con usuarios asignados devuelve 409 (H2); eliminar sobre un id inexistente devuelve 404; el caso de un usuario con historial de movimientos/compras/ventas/transferencias, y el de una sucursal con inventario/compras/ventas/transferencias/rutas pero sin usuarios, se verificaron en vivo contra PostgreSQL real (ver nota de validación).

### BR-047 — El Gerente de sucursal tiene las mismas capacidades operativas que el Administrador sobre compras y transferencias de su sucursal **[Decisión]**

- **Descripción:** por instrucción explícita, `MANAGER` deja de ser un rol de solo consulta sobre `purchases`/`transfers` y pasa a poder despachar y recibir transferencias, y crear/cancelar/recibir compras — las mismas cuatro acciones ya disponibles para `OPERATOR`/`ADMIN` — siempre acotado a su propia sucursal (BR-018 no cambia: `ADMIN` sigue siendo el único rol sin esa restricción). No se le concede ninguna capacidad adicional a `OPERATOR` porque ya las tenía todas.
- **Entidades afectadas:** ninguna nueva; cambia únicamente el conjunto de roles aceptado en `@PreAuthorize` de `TransferController.dispatch`/`receive` y de los tres endpoints de `PurchaseOrderController` (`create`, `cancel`, `receive`/recepciones).
- **Validación:** el alcance por sucursal lo sigue resolviendo `AuthorizationService.requireBranchAccess`/`requireAnyBranchAccess`, agnóstico al rol — no hizo falta tocarlo para que la ampliación respetara la restricción de sucursal propia.
- **Error esperado:** sin cambios — 403 `ROL_NO_AUTORIZADO` sigue aplicando a cualquier rol fuera de la lista ampliada; 403 `SUCURSAL_NO_AUTORIZADA` sigue aplicando si un `MANAGER`/`OPERATOR` intenta operar fuera de su propia sucursal.
- **Pruebas necesarias:** un `MANAGER` puede despachar y recibir una transferencia de su propia sucursal, crear una orden de compra, cancelarla y recibirla; sigue sin poder aprobar/rechazar una transferencia (esa capacidad no cambió) ni operar sobre una sucursal ajena.

### BR-048 — El stock mínimo de un producto es un valor de siembra para el inventario, no un campo de stock en `Product` **[Decisión]**

- **Descripción:** por instrucción explícita ("se debe solicitar su stock mínimo, el cual se usará para el inventario y las alertas"), la creación de un producto ahora exige `minimumStock` (`BigDecimal`, `>= 0`). Esto **no** reintroduce una cantidad de stock dentro de `Product` — `Product` sigue sin ningún campo de cantidad viva, preservando la separación documentada originalmente ("no implementes stock dentro de Product"): `minimumStock` es un **valor por defecto** que se copia a `Inventory.minimum_stock` únicamente la primera vez que una sucursal registra movimiento de ese producto (`findOrCreateInventory`, ya existente en `InventoryMovementService`/`PurchaseReceiptService`/`TransferService`). No es retroactivo: no actualiza ninguna fila de `Inventory` ya creada. ~~Ni se puede editar después de creado el producto (mismo patrón de inmutabilidad que `sku`, BR-026).~~ **Revertido por BR-059** (por instrucción explícita): sí se puede editar después de creado, pero se mantiene no retroactivo.
- **Entidades afectadas:** `product.minimum_stock` (columna nueva, `NUMERIC(19,6) NOT NULL DEFAULT 0`, `CHECK >= 0`, migración V29); `Inventory` gana un tercer constructor que recibe el valor de siembra (el existente de 2 argumentos delega en él con `ZERO`, para no romper ningún sitio que ya creaba `Inventory` directamente).
- **Validación:** `@NotNull @DecimalMin("0")` en `CreateProductRequest.minimumStock`.
- **Error esperado:** 400 `VALIDATION_ERROR` si falta o es negativo.
- **Pruebas necesarias:** crear producto sin `minimumStock` se rechaza; crear con valor negativo se rechaza; la primera `Inventory` creada para ese producto en cualquier sucursal nace con ese `minimumStock` (verificado registrando un ajuste de inventario sobre un producto recién creado y leyendo el `minimumStock` de la fila de `Inventory` resultante); productos existentes antes de esta fase quedan con `minimum_stock = 0` (valor por defecto de la migración), sin afectar ninguna `Inventory` ya materializada.

### BR-049 — Proveedores: CRUD completo, incluida la eliminación real, abierto a cualquier rol autenticado **[Decisión]**

- **Descripción:** por instrucción explícita, `suppliers` deja de seguir la convención de `products`/`branches` (lectura abierta, escritura reservada a un subconjunto de roles) y pasa a ser el único catálogo del sistema donde **ningún** rol tiene más capacidades que otro: `ADMIN`, `MANAGER` y `OPERATOR` pueden crear, leer, editar, activar/desactivar y eliminar proveedores por igual. No hay tampoco restricción por sucursal porque `Supplier` no pertenece a ninguna (a diferencia de, por ejemplo, `Inventory`).
- **Entidades afectadas:** ninguna nueva; cambia el conjunto de roles aceptado (se elimina toda restricción) en los cinco endpoints de escritura de `SupplierController`, y se añade `DELETE /suppliers/{id}` — amplía la excepción de BR-021 ya abierta para `Branch`/`User` (BR-046) a `Supplier`: eliminación real, pero rechazada si tiene historial asociado.
- **Validación:** `SupplierService.delete` no comprueba ninguna dependencia propia con una consulta directa (a diferencia de `BranchService.delete` con `users`, que sí es una dependencia legítima del mismo módulo) — se apoya únicamente en la FK `ON DELETE RESTRICT` que `purchase_order.supplier_id` ya declaraba desde la fase de `purchases`, para no invertir el grafo de dependencias haciendo que `suppliers` conozca `purchases` (`docs/ARCHITECTURE.md`, sección 4).
- **Error esperado:** 409 `PROVEEDOR_CON_DATOS_ASOCIADOS` si tiene alguna orden de compra asociada; 404 sobre un proveedor inexistente; 204 sin contenido cuando la eliminación procede.
- **Pruebas necesarias:** los tres roles pueden crear, editar, activar/desactivar y eliminar un proveedor; eliminar uno sin órdenes de compra devuelve 204; eliminar sobre un id inexistente devuelve 404. El caso "eliminar bloqueado por una orden de compra asociada" depende íntegramente de la FK real de PostgreSQL — Hibernate no la genera en el esquema de pruebas H2 porque el modelo no usa asociaciones JPA — y se verifica en vivo contra Docker Compose, igual que el caso análogo de BR-046.

---

### BR-050 — El nombre de una unidad de medida es editable por ADMIN; el código permanece inmutable **[Decisión]**

- **Descripción:** por instrucción explícita, `units-of-measure` deja de ser un catálogo de solo alta (`GET`/`POST` únicamente, como fijaba el contrato original) y admite editar `name` vía `PATCH /units-of-measure/{id}`, exclusivo de `ADMIN` — misma restricción ya aplicada a la creación. `code` sigue siendo la clave de negocio y no se acepta en este cuerpo, mismo criterio que `Product.sku`/`Branch.code`/`Supplier.taxId`: renombrar la unidad no reescribe ningún dato histórico (`ProductUnit`, `InventoryMovement`, etc. referencian la unidad por `id`, nunca por `code`).
- **Entidades afectadas:** ninguna nueva; `UnitOfMeasure` gana un mutador `updateDetails(name)`.
- **Error esperado:** 404 `UNIDAD_DE_MEDIDA_NO_ENCONTRADA` sobre un id inexistente; 400 `VALIDATION_ERROR` si `name` falta o excede 100 caracteres; 403 `ROL_NO_AUTORIZADO` para cualquier rol distinto de `ADMIN`.
- **Pruebas necesarias:** un ADMIN edita el nombre y el código no cambia; `OPERATOR`/`MANAGER` reciben 403; editar un id inexistente devuelve 404.

### BR-051 — El alta de un producto fija su primer precio de venta en la lista de precios global por defecto **[Decisión]**

- **Descripción:** por instrucción explícita ("cuando se cree un producto se debe solicitar su precio de venta... ese precio es el que se debe utilizar cuando se genere una orden de venta"), `POST /products` ahora exige `unitPrice` (`> 0`). No se agrega ninguna columna de precio a `Product` — preservaría la separación ya establecida entre catálogo y precio (`PriceList`/`Price`, BR-019, docs/DOMAIN_MODEL.md sección 2.14) — en su lugar, `ProductService.create` fija ese valor como el primer `Price` vigente del producto en la **lista de precios global por defecto** (`branchId IS NULL`, la primera activa que exista; se crea una llamada "Lista General" solo si ninguna existe todavía), reutilizando el mismo mecanismo de versionado ya usado por `PriceListService.setPrice` (cerrar el vigente e insertar uno nuevo — aquí no hay vigente que cerrar, por tratarse de un producto recién creado).
  - Esta es exactamente la misma lista que `SaleService.resolvePriceList` ya usa como último recurso cuando una venta no especifica `priceListId` (BR-030): una venta nueva resuelve este precio automáticamente sin ninguna configuración adicional de listas de precios, y el total de la línea se sigue calculando automáticamente a partir de él (mecanismo ya existente en `SaleService`/`SaleLineRow`, sin cambios).
  - La tabla de productos expone este precio resuelto como `salePrice` en `ProductResponse` (`null` si el producto no tiene ningún precio vigente en esa lista — p. ej., productos creados antes de esta fase).
- **Entidades afectadas:** ninguna nueva; usa `PriceList`/`Price` ya existentes.
- **Error esperado:** 400 `VALIDATION_ERROR` si `unitPrice` falta, es cero o negativo.
- **Pruebas necesarias:** crear producto sin `unitPrice` se rechaza; con `unitPrice` cero o negativo se rechaza; el producto recién creado expone ese precio como `salePrice` en `GET /products/{id}`; la lista de precios global por defecto existe (se crea si hacía falta) y contiene ese precio como vigente.

### BR-052 — Una venta confirmada admite devolución total o parcial, línea por línea **[Decisión]**

- **Descripción:** por instrucción explícita ("permitir la devolución de ventas... se revierte el producto al inventario y este aumenta"), `POST /sales/{id}/returns` repone al inventario la cantidad devuelta de una o varias líneas de una venta ya confirmada, incrementando `Inventory.quantity_on_hand` al costo promedio ya vigente (una devolución no recalcula costo, a diferencia de una recepción de compra). Mismo patrón que la recepción de compra (`PurchaseReceiptService`): un `POST` puede traer varias líneas, cada línea valida y aplica de forma independiente, y la idempotencia (categoría 2) se resuelve por línea derivando `InventoryMovement.idempotency_key` como `<Idempotency-Key>:<saleItemId>`.
  - `SaleItem` gana `quantityReturned` (acumulado) y `version` (bloqueo optimista con reintento, igual patrón que `PurchaseOrderItem.version`) — `pending()` = `quantity - quantityReturned` es cuánto queda disponible para devolver.
  - El movimiento generado usa `MovementReason.DEVOLUCION` (ya existía en el enum para el ajuste manual, BR-023) enlazado a la `SaleItem` de origen vía `sale_item_id` — la misma FK documental que ya usa `VENTA`, en sentido inverso.
  - **Alcance explícito, deliberadamente acotado**: no se pidió una nota de crédito ni una reversión monetaria — `Sale.subtotal`/`discountTotal`/`total` y `Sale.status` **no cambian**; el comprobante original permanece intacto como registro histórico de lo vendido (BR-021), preservando también la decisión ya pendiente de aprobación sobre `SaleStatus.VOIDED` (no se resuelve aquí, ni se necesita para lo pedido). La trazabilidad de la devolución vive en `SaleItem.quantityReturned`/`pending` y en el propio `InventoryMovement`.
  - `SaleItemResponse` expone ahora `id` (necesario para que el cliente referencie qué línea devolver — antes no hacía falta, ninguna acción de cliente necesitaba referenciarla) además de `quantityReturned`/`pending`.
- **Entidades afectadas:** `SaleItem` (columnas nuevas, migración V30); reutiliza `PriceList`/`Price`/`Inventory`/`InventoryMovement` ya existentes.
- **Errores esperados:** 404 `VENTA_NO_ENCONTRADA`/`LINEA_VENTA_NO_ENCONTRADA`; 422 `CANTIDAD_INVALIDA` (cantidad ≤ 0); 422 `CANTIDAD_DEVOLUCION_EXCEDE_VENDIDO` (excede lo pendiente); 400 `IDEMPOTENCY_KEY_REQUERIDO`; 403 `SUCURSAL_NO_AUTORIZADA` (mismo alcance de sucursal que el resto de `sales`); 409 `CONFLICTO_CONCURRENCIA`.
- **Pruebas necesarias:** devolución parcial incrementa el stock exacto y dejó `pending` correcto; exceder lo vendido se rechaza; cantidad no positiva se rechaza; falta el header se rechaza; un reintento con la misma clave no duplica el efecto; el movimiento generado tiene `reason=DEVOLUCION`, `direction=INGRESO` y enlaza a la línea correcta; el comprobante original (total/estado) no cambia; alcance de sucursal igual que el resto de `sales`.

### BR-053 — OPERATOR y MANAGER pueden generar y gestionar ventas, igual que ADMIN **[Decisión]**

- **Descripción:** por instrucción explícita, `POST /sales` y `POST /sales/{id}/returns` pasan de `OPERATOR + ADMIN` a `OPERATOR + MANAGER + ADMIN` — misma ampliación ya aplicada a `purchases`/`transfers` en BR-047, y mismo alcance de sucursal sin cambios (`AuthorizationService.requireBranchAccess`, agnóstico al rol).
- **Entidades afectadas:** ninguna; solo el conjunto de roles en `@PreAuthorize` de `SaleController`.
- **Pruebas necesarias:** MANAGER puede crear una venta y puede registrar una devolución sobre una venta de su propia sucursal.

### BR-054 — Toda venta expone el nombre de quien la generó **[Decisión]**

- **Descripción:** por instrucción explícita ("cada venta debe tener un responsable... debe haber una columna que muestre quien generó la venta"), `SaleResponse` expone `soldByUserName` junto al ya existente `soldByUserId`. Se resuelve en `SaleService` (no en el cliente) porque `GET /users` es `ADMIN`-only (UC-14): un `OPERATOR`/`MANAGER` no podría resolver ese nombre por su cuenta aunque tuviera el id. El listado (`GET /sales`) resuelve los nombres en lote (una consulta por página, no una por fila) para no introducir N+1.
- **Entidades afectadas:** ninguna; `soldByUserId` ya existía desde la fase original de `sales`.
- **Pruebas necesarias:** el nombre aparece en la respuesta de creación, en el detalle y en el listado.

### BR-055 — Una cuenta desactivada recibe un mensaje de login distinto al de credenciales inválidas **[Decisión]**

- **Descripción:** por instrucción explícita, `POST /auth/login` distingue ahora una cuenta que existe pero está desactivada (401 `CUENTA_DESACTIVADA`, "Tu cuenta está desactivada. Contacta a un administrador o gerente.") del resto de fallos de login (401 `CREDENCIALES_INVALIDAS`, email inexistente o contraseña incorrecta). `AuthController` distingue capturando `DisabledException` (que `DaoAuthenticationProvider` lanza al comprobar `UserDetails.isEnabled()`, **antes** de verificar la contraseña) de forma separada del resto de `AuthenticationException`.
  - **Nota de seguridad, trade-off aceptado explícitamente:** hasta esta fase, los tres motivos de rechazo (email inexistente, contraseña incorrecta, cuenta inactiva) devolvían el mismo código/mensaje genérico a propósito, para no confirmarle a un atacante si un correo está registrado (enumeración de usuarios) — ver el javadoc original de `InvalidCredentialsException`. Distinguir la cuenta desactivada reintroduce ese riesgo acotado: confirma que el correo existe (y que está desactivado) incluso con una contraseña incorrecta, porque la comprobación de `isEnabled()` ocurre antes que la de la contraseña. Se acepta porque el beneficio pedido —que la persona sepa que debe contactar a un administrador en vez de reintentar una contraseña que nunca funcionará— pesa más que el riesgo de enumeración en el contexto de este sistema (uso interno, no público).
- **Entidades afectadas:** ninguna; usa `User.active` ya existente.
- **Pruebas necesarias:** una cuenta desactivada recibe `CUENTA_DESACTIVADA` con la contraseña correcta; también con la contraseña incorrecta (confirma el orden de comprobación); un email inexistente o una contraseña incorrecta sobre una cuenta activa siguen devolviendo `CREDENCIALES_INVALIDAS`.

### BR-056 — Reportes exportables (movimientos, ventas, transferencias, cumplimiento logístico) **[Decisión]**

- **Descripción:** cuatro reportes exportables en Excel, uno por cada `GET /api/v1/reports/.../export`: `inventory-movements` (movimientos por rango de fechas y sucursal), `sales` (ventas por rango y sucursal), `transfers` (transferencias por rango y estado) y `logistics-compliance` (cumplimiento por sucursal y por ruta, sobre el reporte ya existente). Implementados solo después de confirmar que los cuatro módulos fuente estaban estables (274/274 pruebas de backend en verde antes de tocar nada) — condición explícita de esta fase.

- **Decisión de formato — Excel (.xlsx) únicamente, no PDF:**
  - Se eligió **Excel vía Apache POI** (`poi-ooxml` 5.2.5, Apache-2.0) y **no PDF**, por tiempo disponible (instrucción explícita: "Elige PDF y/o Excel según el tiempo disponible"): los cuatro reportes son fundamentalmente tabulares (filas de movimientos/ventas/transferencias/métricas), el caso de uso natural de una hoja de cálculo — quien recibe el archivo típicamente quiere filtrar, ordenar o pegarlo en otro análisis, algo que PDF no ofrece. Generar un PDF real y bien maquetado (tablas con saltos de página, encabezados repetidos) exige una librería adicional de peso comparable (iText, con licencia AGPL/comercial que no encaja con una app interna sin ese análisis legal, u OpenPDF/Flying Saucer, con más piezas moviéndose) — un segundo esfuerzo de la misma magnitud que el de Excel, no justificable en el tiempo de esta fase.
  - **La capa de consulta/DTO se separó explícitamente de la generación de formato** (instrucción explícita): cada reporte construye un `ReportSheet` neutral (título, metadatos, columnas tipadas, filas) en `com.inventario.multisucursal.reports`, y solo `ExcelReportWriter` sabe convertirlo a bytes `.xlsx`. Añadir PDF en una fase futura sería un `PdfReportWriter` nuevo consumiendo el mismo `ReportSheet`, sin tocar ninguna consulta ni volver a resolver ninguna regla de autorización.

- **RBAC y alcance de sucursal (instrucción explícita: "los filtros deben respetar RBAC y alcance de sucursal"):** cada reporte reutiliza el servicio ya existente y ya probado del módulo dueño del dato — `InventoryMovementService`, `SaleService`, `TransferService` ganan un método `listForExport(...)` nuevo; `LogisticsComplianceService.report(...)` se reutiliza sin cambios. Ninguno de los cuatro endpoints tiene `@PreAuthorize`: los tres roles pueden exportar, y el alcance de sucursal se resuelve con `AuthorizationService.resolveBranchFilter(...)` (método nuevo, genérico): si se pide una sucursal explícita, exige pertenecer a ella (403 `SUCURSAL_NO_AUTORIZADA` si no); si no se pide ninguna, usa la propia sucursal del usuario (`ADMIN` sin restricción) — nunca se sustituye en silencio una sucursal pedida por otra, para que el archivo nunca contenga datos que el usuario no pidió explícitamente.

- **Sin cargas ilimitadas (instrucción explícita: "no cargues cantidades ilimitadas sin controles"), `ReportRangeValidator` (`common.reports`, compartido por los cuatro):**
  - `dateFrom`/`dateTo` son **obligatorios** en los cuatro — a diferencia de los listados paginados de la UI (`GET /inventory-movements`, `GET /sales`, `GET /transfers`), que sí completan un rango por defecto amplio porque una página no carga todo de una vez. Faltar cualquiera de los dos → 422 `RANGO_FECHAS_REQUERIDO`. `dateFrom` posterior a `dateTo` → 422 `RANGO_FECHAS_INVALIDO`.
  - Tope de **5000 filas** por reporte (`MAX_EXPORT_ROWS`, constante local a cada servicio — no vive en `reports` para no invertir el grafo de dependencias): la consulta se acota con `Pageable` y, si el total de resultados excede el tope, se rechaza con 422 `REPORTE_DEMASIADO_GRANDE` en vez de truncar en silencio — un archivo truncado sin avisar parecería completo y llevaría a una conclusión equivocada.
  - `logistics-compliance/export` exige el rango explícito aunque el endpoint interactivo (`GET /reports/logistics-compliance`) no lo exige — `LogisticsComplianceService.report` en sí no acota cuántas transferencias agrega para calcular el resumen, así que la exportación añade el control encima sin tocar el endpoint ya estable.

- **Contenido consistente con la UI/API (instrucción explícita):** cada fila del Excel resuelve los mismos nombres legibles que la pantalla ya muestra (sucursal, producto con SKU, responsable) en vez de IDs crudos — mismo criterio ya establecido en BR-054 para el responsable de una venta. Los montos se formatean con separador de miles y dos decimales, **sin símbolo de moneda** (la aplicación no usa ninguno en ninguna pantalla; inventar uno solo en el reporte rompería la consistencia). Las columnas de fecha/hora llevan literalmente "(UTC)" en el encabezado y se escriben como `LocalDateTime` (no vía `java.util.Date`), para que Excel muestre exactamente los mismos dígitos que persiste el backend sin que la zona horaria del servidor los desplace en silencio.

- **Entidades afectadas:** ninguna nueva; se reutilizan enteramente `InventoryMovement`, `Sale`, `Transfer`, `LogisticsComplianceResponse`. Sin migración.

- **Pruebas:** rango válido con datos esperados en las celdas; rango válido sin resultados (archivo bien formado con un aviso, no un error); falta el rango (422); rango invertido (422); tope de filas excedido (422, probado como prueba unitaria pura de `ReportRangeValidator`, sin sembrar 5000 filas reales); permisos — un operador no puede exportar la sucursal de otro (403) y, sin filtro, solo ve la propia; archivo no corrupto — cada prueba vuelve a abrir el `.xlsx` devuelto con Apache POI (`WorkbookFactory.create`) y falla si no puede. 20 pruebas nuevas de backend (`ReportExportApiTest`: 13; `ReportRangeValidatorTest`: 7) — 294 pruebas de backend en total (2 se omiten con gracia sin Docker-en-Docker).

### BR-057 — El precio de venta de un producto es editable después de creado **[Decisión]**

- **Descripción:** por instrucción explícita, `PATCH /products/{id}` ahora exige también `unitPrice` (`> 0`) y lo fija como el nuevo precio vigente del producto en la lista de precios global por defecto — cerrando el precio anterior e insertando uno nuevo, exactamente el mismo versionado que `PriceListService.setPrice` (BR-019) y que el propio alta del producto (BR-051). A diferencia del SKU y la unidad base (inmutables, BR-026), el precio sí cambia: `ProductService` extrae la lógica de fijar precio en la lista por defecto a un método compartido (`setDefaultListPrice`), reutilizado por `create` y por `update`.
  - Editar el precio **no** afecta ninguna venta ya confirmada: `SaleItem.unitPrice` copia el precio vigente al momento de confirmar y nunca se recalcula después (BR-021) — el nuevo precio solo aplica a ventas futuras.
- **Entidades afectadas:** ninguna nueva; reutiliza `PriceList`/`Price` ya existentes.
- **Error esperado:** 400 `VALIDATION_ERROR` si `unitPrice` falta, es cero o negativo.
- **Pruebas necesarias:** editar nombre/descripción/precio a la vez; el precio anterior queda cerrado (`validTo` no nulo) al consultar el historial de la lista de precios, no sobrescrito; precio cero o negativo se rechaza.
- **Bug encontrado y corregido en verificación manual contra Postgres real:** `setDefaultListPrice` cerraba el precio anterior (`current.close(); priceRepository.save(current)`) y luego insertaba el nuevo `Price` en la misma transacción. Hibernate agrupa los `INSERT` antes que los `UPDATE` dentro de un mismo flush, así que el `INSERT` del nuevo precio (con `validTo` nulo) llegaba a la base **antes** que el `UPDATE` que cerraba el anterior, violando el índice único parcial `uq_price_list_product_current` (`price_list_id, product_id` WHERE `valid_to IS NULL`, `V17__create_price_table.sql`) — error 500 `CONFLICTO_DATOS` al editar el precio de un producto que ya tenía uno. Los 301 tests de backend no lo detectaron porque corren contra H2 en memoria, no Postgres. Corregido forzando el flush del cierre (`priceRepository.saveAndFlush(current)`) antes de insertar el nuevo precio, en `ProductService.setDefaultListPrice`.

### BR-058 — Permisos ajustados en proveedores y edición completa de usuario **[Decisión]**

- **Proveedores, por instrucción explícita — reduce el alcance de BR-049:** `OPERATOR` pasa a solo lectura (pierde crear/editar/activar/desactivar/eliminar); crear, editar, activar y desactivar quedan en `MANAGER`+`ADMIN`; eliminar (real, no reversible) queda exclusivo de `ADMIN` — ni siquiera `MANAGER` puede eliminar. Sin restricción por sucursal en ningún caso (el proveedor no pertenece a ninguna, como ya establecía BR-049).
  - Errores esperados: 403 `ROL_NO_AUTORIZADO` para `OPERATOR` en cualquier escritura; 403 `ROL_NO_AUTORIZADO` para `MANAGER` en `DELETE`.
- **Usuarios — el correo pasa a ser editable, por instrucción explícita:** `PATCH /users/{id}` ahora exige también `email` (antes solo aceptaba nombre/rol/sucursal, deliberadamente sin correo ni contraseña). La contraseña sigue sin poder editarse aquí — ese flujo queda fuera de este alcance, igual que antes. Unicidad de correo validada excluyendo al propio usuario (`existsByEmailAndIdNot`), para que conservar el correo actual al editar otro campo no se trate como un duplicado.
  - Frontend: `UsersPage` no tenía ningún botón "Editar" hasta ahora (el `PATCH` de nombre/rol/sucursal existía en el backend desde antes, pero nunca se expuso en la interfaz) — `UserFormDialog` pasa a ser un diálogo dual de alta/edición, igual patrón que `ProductFormDialog`/`BranchFormDialog`. Editar la propia cuenta sí se permite (a diferencia de desactivar/eliminar, `NO_AUTOGESTION`): no es una acción destructiva.
  - Error esperado: 409 `EMAIL_YA_EXISTE` si el nuevo correo ya pertenece a otro usuario.
- **Pruebas necesarias:** `OPERATOR` no puede crear/editar/activar/desactivar/eliminar un proveedor; `MANAGER` puede todo lo anterior excepto eliminar; `ADMIN` puede eliminar; editar el correo de un usuario y que persista; correo duplicado se rechaza; conservar el correo propio al editar otro campo no se rechaza.

### BR-059 — El stock mínimo de un producto es editable después de creado **[Decisión]**

- **Descripción:** por instrucción explícita, revierte la inmutabilidad de `minimumStock` establecida en BR-048 ("mismo patrón de inmutabilidad que sku"). `PATCH /products/{id}` ahora exige también `minimumStock` (`>= 0`), que `ProductService.update` aplica directamente sobre `Product.minimumStock` vía `updateDetails`. Sigue siendo **solo el valor de siembra**: no reintroduce una cantidad de stock en `Product` ni toca ninguna fila de `Inventory` ya materializada — `findOrCreateInventory` (`InventoryMovementService`/`PurchaseReceiptService`/`TransferService`) solo lo copia la primera vez que una sucursal registra movimiento de ese producto, exactamente igual que antes de esta regla. El único efecto de editarlo es cambiar qué valor recibirán las sucursales que **todavía no** tengan `Inventory` para este producto.
- **Entidades afectadas:** ninguna nueva; reutiliza `Product.minimum_stock` (V29).
- **Error esperado:** 400 `VALIDATION_ERROR` si `minimumStock` falta o es negativo.
- **Pruebas necesarias:** editar el stock mínimo junto con nombre/descripción/precio persiste el nuevo valor; editar el stock mínimo de un producto que ya tiene `Inventory` en alguna sucursal no modifica esa fila (solo afecta sucursales nuevas); stock mínimo negativo se rechaza al editar.

## Ajustes pendientes al modelo de dominio (para aprobar antes de migrar)

1. ~~`Inventory.average_unit_cost`~~ — **resuelto:** aprobado y aplicado en `docs/DOMAIN_MODEL.md` (secciones 2.7, 3.2 y 6).
2. **Política de redondeo del costo promedio ponderado (BR-004):** aún no definida (ya señalada como pendiente en `docs/DOMAIN_MODEL.md`/`docs/STATUS.md`); se requiere decidir precisión decimal antes de implementar el cálculo.
3. ~~Mecanismo exacto de reintento de BR-022~~ — **resuelto:** definido en `docs/CRITICAL_FLOWS.md` (sección 1.2) como 3 intentos con backoff aleatorio corto.
4. ~~`PurchaseOrderItem.version`~~ — **resuelto:** aplicado al implementar el módulo `purchases` (migración V14). Bloqueo optimista manual (mismo patrón que `Inventory.version`, no `@Version` de JPA) sobre `quantity_received`, necesario porque es un agregado que puede incrementarse en varias recepciones parciales concurrentes sobre la misma línea (docs/CRITICAL_FLOWS.md, flujo B).
5. ~~`Sale.client_reference_id`~~ — **resuelto:** aplicado al implementar el módulo `sales` (migración V18). A diferencia de `InventoryMovement.idempotency_key` en la recepción de compra (BR-029, derivada por línea), aquí una sola clave por sucursal/solicitud basta: la venta completa (cabecera + todas sus líneas) se verifica de una sola vez, antes de procesar ninguna línea (docs/CRITICAL_FLOWS.md, flujo A).
6. ~~`InventoryMovement.idempotency_key`~~ — **parcialmente resuelto:** aplicado al implementar el módulo `purchases` (migración V15) para la recepción de compra (flujo B) — clave derivada `<Idempotency-Key del header>:<purchaseOrderItemId>`, ver BR-029. **Sigue pendiente** para el ajuste manual de inventario (flujo G, BR-023): la fase de `inventory` implementó ese endpoint sin idempotencia real (documentado en `docs/STATUS.md` como limitación conocida de esa fase) y no se retrofactoriza aquí — no era parte del alcance pedido para `purchases`.

7. ~~`Transfer.route_id`~~ — **resuelto:** aplicado al implementar el módulo `logistics` (migraciones V24–V25), junto con la tabla `route` de la que depende. Se asigna automáticamente desde el par origen-destino, nunca desde el payload (BR-036).

## Reglas ya señaladas como pendientes en documentos previos (no se resuelven aquí)

- Rol exacto que aprueba una transferencia y que decide el tratamiento del faltante (BR-005, BR-009): sigue como supuesto pendiente de confirmación (`docs/PROJECT_BRIEF.md`, `docs/USE_CASES.md`, `docs/DOMAIN_MODEL.md`). La implementación de `transfers` aplicó el supuesto ya registrado —aprobar/rechazar es del **Gerente de la sucursal origen**; el tratamiento del faltante, de un **Gerente de origen o destino**— sin darlo por confirmado.
- Si `Sale` admite anulación (`VOIDED`) o toda corrección se maneja como ajuste aparte (`docs/DOMAIN_MODEL.md`, decisión de aprobación 9) — afecta si BR-021 debe contemplar una excepción explícita para ese estado.

---

**Documentos relacionados:** `docs/DOMAIN_MODEL.md` (modelo de datos que estas reglas protegen), `docs/ARCHITECTURE.md` (capas, transacciones, concurrencia), `docs/adr/ADR-005-jwt-rbac.md` (BR-018), `docs/adr/ADR-008-trazabilidad-inventory-movement.md` (BR-001, BR-015).
