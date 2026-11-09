# ADR-015 — Alertas de stock mínimo

**Estado:** Accepted

**Relación con ADR-007/ADR-009 (SSE) y ADR-010 (frontend):** primer productor real de `stock-alert.triggered`/`stock-alert.resolved`, tipos que ADR-009 ya había reservado en el contrato pero explícitamente no implementó ("declararlos sin productor daría una falsa sensación de cobertura"). El canal, el broadcaster y el hook de suscripción en el cliente son exactamente los mismos que ya usa `transfers` — esta fase no les cambia nada.

## Contexto

RF-036 exige al menos una funcionalidad adicional de valor real más allá de los módulos obligatorios; `docs/PROJECT_BRIEF.md` ya la eligió de antemano: alertas de stock mínimo (RF-010). El modelo de datos (`StockAlert`, `docs/DOMAIN_MODEL.md` sección 2.9) y la regla de negocio central (BR-010) ya estaban definidos y aprobados desde la fase de diseño, antes de que existiera código de negocio — este ADR documenta las decisiones de implementación que faltaban: dónde se engancha la evaluación, cómo se evita duplicar alertas sin arriesgar la operación que las dispara, y qué expone el frontend.

## ¿Por qué esta funcionalidad y no otra más costosa?

La prueba pedía elegir una funcionalidad adicional "de valor real". Se consideraron tres alternativas de mayor alcance y se descartaron:

- **Reordenamiento automático / sugerencia de compra:** requeriría un modelo de demanda (¿cuánto pedir, a qué proveedor, con qué anticipación?) que no está definido en ningún requisito del enunciado — habría sido inventar una regla de negocio no pedida, exactamente lo que ADR-010 y el resto de este proyecto evitan sistemáticamente.
- **Notificación por correo/push con infraestructura de colas:** exige un proveedor SMTP o push real, credenciales, plantillas y manejo de fallos de entrega — infraestructura nueva sin justificación concreta (contra la regla explícita del proyecto de "no agregar infraestructura sin justificación"), para un requisito que la propia prueba permite resolver con notificación en interfaz.
- **Un motor de reglas configurable (umbrales por rol, por horario, por categoría de producto):** generalidad que nadie pidió; el umbral ya existe y ya es configurable por producto/sucursal (`Inventory.minimum_stock`, BR-010) — no hace falta una capa de configuración adicional para una funcionalidad cuyo valor es justamente "avisar cuando ya se definió un mínimo".

Frente a esas alternativas, alertas de stock mínimo tal como las define BR-010 tiene una ventaja decisiva: **reutiliza un dato que el sistema ya calcula** (`Inventory.quantity_on_hand` vs. `minimum_stock`, ya usado por el filtro `lowStock` de Inventario y por el panel de reabastecimiento del dashboard). No inventa un umbral nuevo, no requiere que el usuario configure nada que no configurara ya, y el valor operativo es inmediato y verificable: un operador o gerente ve, sin tener que ir a buscarlo, qué productos necesitan reabastecimiento **en el momento en que empiezan a necesitarlo**, no la próxima vez que alguien decida mirar el inventario completo.

## Decisión

### 1. Condición de disparo: cruzar el umbral, evaluado en cada cambio real de stock

Se dispara cuando `quantity_on_hand <= minimum_stock` **inmediatamente después** de cualquier operación que modifique el stock (venta, ajuste manual, recepción de compra, despacho/recepción de transferencia — los cinco puntos donde el código ya hace `UPDATE Inventory ... WHERE version = v` con reintento optimista, `docs/CRITICAL_FLOWS.md` sección 1.2). Se resuelve cuando, tras otro cambio, `quantity_on_hand > minimum_stock`. No se evalúa por un job periódico ni por lectura: solo cuando el stock realmente cambia, porque es el único momento en que la condición puede haber cambiado.

`UC-16` menciona explícitamente "retiro o venta"; la implementación evalúa las **cinco** operaciones que tocan `quantity_on_hand`, no solo esas dos — la alternativa (evaluar solo en venta/retiro) dejaría una compra o una transferencia capaces de dejar el stock en el mínimo sin que nadie se enterara, un vacío que ni la fuente ni BR-010 (que sí dice "cada operación") justifican.

### 2. Deduplicación: comprobar antes de insertar, con el índice único como respaldo de base de datos

Una alerta activa por `inventory_id` (una fila por producto/sucursal, nunca por evento). `StockAlertService.evaluate` comprueba `existsByInventoryIdAndStatus(id, ACTIVE)` antes de insertar; si ya existe, no hace nada — dos retiros sucesivos que dejan el stock por debajo del mínimo no crean una segunda alerta.

**Por qué no capturar la violación del índice único dentro de la misma transacción** (el patrón obvio y el que ya usa este mismo código para el primer `Inventory` de un producto/sucursal, `InventoryMovementService.findOrCreateInventory`): se verificó empíricamente contra PostgreSQL real que, tras un `INSERT` fallido por restricción de unicidad, **toda la transacción en curso queda abortada** — cualquier sentencia posterior, incluido el `COMMIT`, es rechazada hasta un `ROLLBACK`. Capturar la excepción en Java no evita esto: la base de datos ya decidió que esa transacción no puede continuar. Como la evaluación de la alerta corre **dentro** de la transacción de la venta/compra/transferencia que la disparó (BR-010 lo exige así, para que la alerta nunca sobreviva a una operación que termina en rollback), intentar y capturar una violación de unicidad ahí habría arriesgado la venta misma — precisamente lo que esta fase pide evitar.

En su lugar, el `exists`-check es la defensa primaria, y es suficiente en la práctica: cada operación que puede modificar el mismo `Inventory` ya serializa su escritura vía bloqueo optimista sobre `version` — dos operaciones sobre la misma fila nunca actualizan "a la vez" a nivel de base de datos, una siempre reintenta después de que la otra confirma. El índice único parcial de `stock_alert` (`docs/DOMAIN_MODEL.md`, 2.9) sigue existiendo como garantía de última línea a nivel de esquema, igual criterio que `users.email`/`branches.code`, pero no se diseñó ningún camino de código que dependa de capturarlo.

### 3. Alcance por sucursal: igual que Inventario, abierto

`GET /stock-alerts` es de lectura abierta a cualquier rol autenticado, cualquier sucursal — mismo criterio que `GET /inventory` (RF-003): una alerta es un derivado directo de un dato que ya es público dentro de la organización, así que acotarla por sucursal habría sido una restricción nueva sin respaldo en ningún requisito. `branchId` es un filtro opcional, no una autorización.

### 4. Estados: `ACTIVE`/`RESOLVED`, sin un tercer estado "atendida"

El dominio ya fijó esto (`docs/DOMAIN_MODEL.md`, 2.9, aprobado antes de esta fase): una transición, `ACTIVE → RESOLVED`, automática. Se consideró explícitamente agregar un estado "atendida/reconocida" (alguien la vio, aunque el stock siga bajo) y se descartó: convertiría la alerta en un objeto de flujo de trabajo con una acción de usuario que ningún requisito pide, y el propio dominio ya decidió que la resolución es automática, no una acción manual — agregar un estado manual intermedio habría sido cambiar un modelo de datos ya aprobado sin una necesidad concreta que lo justifique.

### 5. Destinatarios: cualquiera con acceso de lectura a la sucursal, sin lista de suscripción

UC-16 nombra a Operador de inventario y Gerente de sucursal como quienes "reciben"; como la lectura es abierta (punto 3), no se construyó un mecanismo de suscripción/destinatario por rol — todo el que puede ver el inventario de una sucursal puede ver sus alertas. Construir una lista de destinatarios explícita habría sido infraestructura adicional (una tabla de suscripciones, o un cálculo de "quién debe verla") para un requisito que la apertura de lectura ya resuelve sin ella.

### 6. Interfaz primero; sin correo

Center dedicado (`/inventario/alertas`) más un enlace desde el panel de reabastecimiento del dashboard, con actualización near-real-time vía el mismo canal SSE que `transfers` ya demostró. Se descartó el correo explícitamente: exigiría credenciales SMTP, una plantilla y manejo de fallos de entrega — infraestructura nueva para un requisito que la prueba misma permite resolver con notificación en interfaz ("prioriza notificación dentro de la interfaz; correo solo si no complica innecesariamente" — sí lo complica, así que no se implementa).

## Alternativas consideradas

- **Job periódico que recorra todo `Inventory` buscando cruces de umbral:** rechazada — recorrería toda la tabla repetidamente para detectar algo que solo puede cambiar en los cinco puntos donde el código ya escribe `quantity_on_hand`; evaluarlo ahí es más simple y más barato (docs/BUSINESS_RULES.md, criterio general de "SQL agregado, nunca cargar todo para recorrer en memoria").
- **Capturar la violación del índice único dentro de la transacción de negocio (`Propagation.NESTED`/savepoint):** rechazada por complejidad y riesgo de configuración no probada (`JpaTransactionManager.nestedTransactionAllowed` no está habilitado por defecto en Spring) frente a una alternativa (`exists`-check) que ya es suficiente dado el bloqueo optimista existente.
- **Tercer estado "atendida":** rechazada (punto 4) — cambiaría un modelo de datos ya aprobado sin necesidad concreta.
- **Notificación por correo:** rechazada (punto 6) — infraestructura nueva sin justificación suficiente para el alcance de esta prueba.

## Consecuencias positivas

- Cero infraestructura nueva: reutiliza el canal SSE, el broadcaster y el patrón de bloqueo optimista ya existentes; el único componente nuevo es una tabla y un servicio.
- La garantía "un fallo de alerta nunca revierte una venta/compra ya confirmada" quedó demostrada con una prueba dedicada (`StockAlertNotificationFailureTest`), no solo argumentada.
- El mismo mecanismo cubre las cinco operaciones que tocan stock, no solo venta/retiro como sugiere la redacción literal de UC-16 — sin faltantes silenciosos originados en una compra o una transferencia.

## Consecuencias negativas / trade-offs

- **Sin envío por correo ni push**, por diseño (punto 6) — si en el futuro se requiere alcanzar a alguien que no tiene la aplicación abierta, esto queda pendiente de una fase aparte, con su propia justificación de infraestructura.
- **Dos conexiones SSE por pestaña** cuando `/transferencias` y `/inventario/alertas` están montadas a la vez (cada una con su propio hook, `useTransferRealtime`/`useStockAlertRealtime`) — el mismo límite de escala que ADR-007/ADR-009 ya señalan para un canal en memoria de un solo proceso, y el mismo criterio que esos documentos fijan para cuándo reconsiderarlo (una suscripción a nivel de layout) — no se adelantó ese rediseño sin que una tercera pantalla lo necesitara de verdad.
- **La deduplicación depende de que todo cambio de stock pase por el bloqueo optimista existente** (punto 2): si en el futuro se agregara un camino de escritura a `Inventory` que no pasara por ese patrón, el `exists`-check dejaría de ser, por sí solo, una garantía completa contra duplicados — el índice único parcial seguiría evitando el duplicado real, pero al costo de abortar esa transacción (ver punto 2). No es un riesgo hoy: los cinco puntos de escritura existentes ya siguen el patrón.

## Hallazgo durante la verificación

Verificado empíricamente contra PostgreSQL real (no solo documentado): tras un `INSERT` que viola una restricción única dentro de una transacción, PostgreSQL aborta toda la transacción — cualquier sentencia posterior, incluido `COMMIT`, se rechaza hasta el `ROLLBACK`. Esto descartó capturar la violación del índice único dentro de la misma transacción de negocio (punto 2) antes de escribir una sola línea de ese código, evitando construir una solución que habría arriesgado ventas/compras reales ante una condición de carrera rara.

## Criterios para reconsiderarla

- Si se agrega un tercer camino de escritura a `Inventory.quantity_on_hand` que no pase por bloqueo optimista: revisar si el `exists`-check de deduplicación sigue siendo suficiente (punto 2).
- Si se necesita alcanzar a alguien sin la aplicación abierta (guardia nocturno, proveedor externo): retomar la opción de correo/push, con su propia justificación de infraestructura.
- Si una tercera pantalla necesita el canal SSE: evaluar entonces una suscripción a nivel de layout en vez de una conexión por pantalla (mismo criterio ya fijado en ADR-009).
