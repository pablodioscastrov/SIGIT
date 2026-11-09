# Estrategia de Pruebas

**Rol:** líder de QA del proyecto.
**Alcance:** todos los módulos obligatorios de `docs/PROJECT_BRIEF.md`, sobre el estado descrito en `docs/STATUS.md` al iniciar esta fase (304 pruebas de backend, 173 de frontend, 0 E2E automatizadas).
**Fecha:** 2026-08-30. Actualizado el mismo día tras implementar las dos brechas identificadas en §4/§5: 3 pruebas de concurrencia contra PostgreSQL real (307 pruebas de backend en total) y 4 flujos E2E con Playwright.

**Principio rector:** cada prueba debe poder responder "¿qué fallo de negocio real detecta?" — no se persigue porcentaje de cobertura como objetivo aislado (ver `docs/PROJECT_BRIEF.md` sección 1). El historial de bugs reales de `docs/STATUS.md` confirma que casi ningún bug crítico encontrado en este proyecto fue detectado por la suite automática existente (H2), sino por verificación en vivo contra PostgreSQL/Docker/navegador real — la brecha entre "pasa en H2" y "funciona en Postgres real" es el riesgo dominante de este proyecto, más que la falta de pruebas en sí.

## 1. Clasificación

| Nivel | Qué cubre en este proyecto | Herramienta | Estado |
|---|---|---|---|
| **1. Unitarias** | Reglas puras sin I/O: validadores (`ReportRangeValidator`), comportamiento de entidades (`AuditableEntity`), formato de errores (`GlobalExceptionHandler`) | JUnit 5 + AssertJ | Implementado |
| **2. Integración** | Repositorios, migraciones Flyway, transacciones (rollback), bloqueo optimista/concurrencia, seguridad (filtro JWT, RBAC) | JUnit 5 + Spring Boot Test; H2 (`MODE=PostgreSQL`) por defecto, Testcontainers + PostgreSQL real solo en `FlywayMigrationIntegrationTest` | Implementado, con brecha — ver §4 |
| **3. API** | Contratos REST, validación de payload, códigos de error (400/401/403/404/409/422), envolvente uniforme, idempotencia | JUnit 5 + `TestRestTemplate`, contra H2 | Implementado, muy completo |
| **4. Frontend** | Componentes y flujos críticos de cada pantalla, permisos por rol en la UI, manejo de error del backend sin ocultarlo, SSE | Vitest + Testing Library, `fetch` simulado | Implementado, muy completo |
| **5. E2E** | Flujos de alto valor de punta a punta (navegador real → API real → PostgreSQL real, vía Docker Compose) | — | **No existe.** Ver §5 |

## 2. Reglas de la estrategia

1. Prioriza pruebas que detecten fallos de **negocio** (stock negativo, doble aplicación de un movimiento, faltante mal calculado, permiso cruzado entre sucursales) sobre pruebas que solo verifican forma de la respuesta.
2. No se persigue % de cobertura como meta; se persigue que cada regla de negocio documentada en `docs/BUSINESS_RULES.md` tenga al menos una prueba que la exprese como caso de fallo, no solo de éxito.
3. Datos deterministas: sin `Math.random()`/timestamps `now()` sin control en aserciones — el proyecto ya sigue esta práctica (fixtures fijos, relojes controlados, IDs de idempotencia explícitos).
4. Todo bug crítico descubierto (en cualquier fase, por cualquier medio) deja una prueba de regresión con nombre trazable a la causa — el proyecto ya sigue esta práctica de forma consistente (ver ejemplos en `docs/STATUS.md`: `TransferService.findActiveForDashboard` doble conteo, `ProductService.setDefaultListPrice` orden de flush de Hibernate, etc.). Se mantiene como regla explícita hacia adelante.
5. Una prueba de integración que solo puede fallar en PostgreSQL real (constraints, `ON DELETE RESTRICT`, orden de `INSERT`/`UPDATE` de Hibernate, semántica de bloqueo) no demuestra nada corriendo contra H2 — debe correr contra Postgres real/contenedorizado o quedar documentada explícitamente como "verificada solo en vivo, no en CI" (patrón ya usado y declarado en `docs/STATUS.md` para varios bugs).

## 3. Matriz Riesgo × Prueba

Leyenda de cobertura: ✅ cubierto · ⚠️ cubierto parcialmente / con brecha señalada · ❌ no existe.

| Área | Riesgo principal | Unit | Integración | API | Frontend | E2E |
|---|---|---|---|---|---|---|
| **Autenticación / autorización** | Acceso o escritura cruzando sucursal o rol sin autorización (RNF-003) | — | ✅ `AuthenticationFlowTest` (login, cuenta desactivada, JWT inválido/expirado/manipulado) | ✅ negativo 401/403 por rol en **cada** módulo con restricción (`operatorCannotX`, `managerCannotX`, `nonAdminCannotX` — ~25 casos distintos) | ✅ `auth.test.tsx`, `routing.test.tsx` (guardas de ruta, 403 sin cerrar sesión) | ❌ (implícito en A–D) |
| **Inventario** | Stock negativo, retiro concurrente, ajuste sin trazabilidad | — | ✅ `InventoryConcurrencyTest` (H2, rápida) + `InventoryConcurrencyPostgresTest` (Postgres real, §4) · `InventoryAdjustmentRollbackTest` (H2) | ✅ `InventoryAdjustmentApiTest` (conversión de unidad, motivo obligatorio, permisos, 404/422) | ✅ `inventory.test.tsx` (ajuste, filtros, movimientos, exportación) | Flujo A/B |
| **Recepción de compra** | Doble recepción, costo promedio mal calculado | — | ✅ `PurchaseReceiptRollbackTest` (H2) | ✅ `PurchaseOrderApiTest` + `PurchaseReceiptApiTest` (idempotencia real, costo ponderado con/sin stock previo, recepción parcial→completa) | ✅ `purchases.test.tsx` | ✅ Flujo A |
| **Venta** | Venta concurrente sobre el mismo stock, venta sin validar stock | — | ✅ `SaleConcurrencyTest` (H2) + `SaleConcurrencyPostgresTest` (Postgres real, §4) · `SaleRollbackTest` (H2) | ✅ `SaleApiTest` (incl. devolución, escalado de precio por unidad, idempotencia) | ✅ `sales.test.tsx` | ✅ Flujo B |
| **Transferencia completa/parcial** | Faltante mal calculado, doble despacho/recepción, venta vs. despacho compitiendo por el mismo stock | — | ✅ `TransferConcurrencyTest` (H2) + `TransferConcurrencyPostgresTest` (Postgres real, §4) · `TransferRollbackTest` (H2) | ✅ `TransferApiTest` (máquina de estados completa, 30+ casos: doble aprobación/despacho/recepción, tratamiento de faltante, reenvío) | ✅ `transfers.test.tsx` (incl. SSE, conflicto 409) | ✅ Flujo C/D |
| **Logística** | Reporte de cumplimiento con cifras inconsistentes con los hechos | — | — | ✅ `RouteApiTest`, `LogisticsComplianceApiTest`, export en `ReportExportApiTest` | ✅ `logistics.test.tsx` | — (cubierta indirectamente por C/D) |
| **Alertas** | Alerta duplicada, alerta no disparada/no resuelta, fallo de alerta revirtiendo la operación de negocio | — | ✅ `StockAlertNotificationFailureTest` (fallo forzado en evaluación de alerta no revierte el ajuste — H2, pero el mecanismo probado es independiente del motor) | ✅ `StockAlertApiTest` (cruce exacto de umbral, deduplicación, resolución, alcance por sucursal) | ✅ `stockAlerts.test.tsx` (incl. SSE) | — |
| **Dashboard** | Cifra agregada incorrecta, fuga de comparativa entre sucursales a `OPERATOR` | — | — | ✅ `DashboardApiTest` (17 casos: límites de mes exactos, `null` cuando no aplica, RBAC de la comparativa) | ✅ `dashboard.test.tsx` | — |

## 4. Brecha — concurrencia/transacciones sobre H2, no PostgreSQL real (implementado parcialmente)

Las pruebas más críticas del proyecto para RNF-006 (`InventoryConcurrencyTest`, `SaleConcurrencyTest`, `TransferConcurrencyTest`) verificaban el **mecanismo** (bloqueo optimista con reintento) solo contra H2 en modo compatibilidad PostgreSQL. H2 no replica el mismo comportamiento de bloqueo a nivel de fila ni el mismo comportamiento de aborto de transacción que Postgres — el propio `docs/STATUS.md` documenta un caso real (`ADR-015`, deduplicación de alertas) donde el comportamiento de Postgres ante una violación de índice único **difiere del que asumiría H2**, y se descubrió únicamente probando contra Postgres real.

**Estado: implementado.** Se agregaron tres variantes contra PostgreSQL real vía Testcontainers, mismo escenario y mismas aserciones que sus pares en H2, sin tocarlos:

- `InventoryConcurrencyPostgresTest` (`backend/.../inventory/`)
- `SaleConcurrencyPostgresTest` (`backend/.../sales/`)
- `TransferConcurrencyPostgresTest` (`backend/.../transfers/`)

Mismo patrón que `FlywayMigrationIntegrationTest` (`@Testcontainers(disabledWithoutDocker = true)`): se omiten con gracia si no hay Docker disponible, sin depender de Flyway completo (arrancan con el contexto por defecto, que sí corre las migraciones reales — a diferencia del perfil `test`, que las desactiva). Verificado: `mvn test` completo, 307 pruebas, 0 fallos, 5 omitidas (las 2 preexistentes + estas 3, todas por Docker no accesible en el entorno de este agente — ver nota abajo). Las pruebas de rollback (`*RollbackTest`) se dejaron en H2 sin cambios: el aborto de una transacción ante una excepción a mitad de camino es semántica ACID estándar, no un comportamiento específico de motor como sí lo es el bloqueo de fila bajo concurrencia real.

> **Nota de verificación:** en la sesión donde se implementó esto, el agente no pudo ejecutar estas tres pruebas *en verde* contra un contenedor real — el socket de Docker expuesto a ese proceso Java respondía con una introspección vacía (`docker info` incompleto) que Testcontainers no reconoce como un daemon válido, aunque el propio Docker Compose del proyecto sí corría normalmente en la misma máquina vía la CLI. Es la misma limitación que ya afecta a `FlywayMigrationIntegrationTest` en ese entorno (de ahí el "2 se omiten con gracia sin Docker" ya documentado en fases anteriores de `docs/STATUS.md`). Recomendado: correr `mvn test -Dtest=InventoryConcurrencyPostgresTest,SaleConcurrencyPostgresTest,TransferConcurrencyPostgresTest` una vez en un entorno con Docker sin restringir para confirmar que pasan en verde, no solo que compilan y se omiten con gracia.

## 5. E2E mínimos recomendados (implementado)

No existía ningún framework de E2E en el repositorio. Se agregó Playwright en un workspace propio (`e2e/`, `package.json`/`playwright.config.ts` separados de `frontend/` — no toca su build ni sus dependencias) con los cuatro flujos mínimos, cada uno verificado en verde contra el stack real (`docker compose up`, navegador Chromium real, PostgreSQL real):

- **A.** Login → registrar compra → verificar inventario (`e2e/tests/A-purchase-to-inventory.spec.ts`).
- **B.** Login → venta → verificar decremento y movimiento (`e2e/tests/B-sale-decrements-inventory.spec.ts`).
- **C.** Solicitar transferencia → despachar → recibir → verificar ambos inventarios (`e2e/tests/C-transfer-full-cycle.spec.ts`).
- **D.** Recepción parcial → faltante visible (`e2e/tests/D-transfer-partial-shortage.spec.ts`).

Cada uno ya tenía su lógica de negocio probada exhaustivamente a nivel de API (tabla §3) — el valor añadido de estos E2E es distinto: prueban la integración real navegador→API→Postgres→Docker Compose que ningún nivel anterior cubría (RT-003), y habrían detectado en su momento el bug real de CORS que solo apareció con navegador real (`docs/STATUS.md`, fase de Compras/Ventas). Detalle de diseño e instrucciones de ejecución en `e2e/README.md`.

## 6. Concurrencia y confiabilidad — 8 escenarios dirigidos (implementado)

Pase específico de confiabilidad/concurrencia sobre las operaciones críticas, con hilos reales (`ExecutorService` + `CountDownLatch` ready/start, nunca HTTP secuencial) donde el escenario lo exige. Formato por escenario: estado inicial · hilos · barrera · resultado permitido · invariantes · evidencia en `InventoryMovement`.

| # | Escenario | Estado inicial | Resultado permitido | Invariante | Prueba |
|---|---|---|---|---|---|
| 1 | Dos ventas simultáneas, último stock | Stock = 10, ambas piden 6 | Una 200, una `STOCK_INSUFICIENTE` | Stock final = 4, nunca negativo | `SaleConcurrencyTest` + `SaleConcurrencyPostgresTest` (ya existían) |
| 2 | Venta vs. despacho de transferencia, mismo producto/sucursal | Stock = 10, cada uno pide 6 | Una gana, la otra `STOCK_INSUFICIENTE`/`CONFLICTO_CONCURRENCIA` | Stock final = 4, nunca doble aplicado | `TransferConcurrencyTest` + `TransferConcurrencyPostgresTest` (ya existían) |
| 3 | Dos confirmaciones de recepción de la misma compra | Orden CREATED, pendiente = 10, cada una recibe 6 | Una 200 (recibe 6), una `CANTIDAD_RECEPCION_EXCEDE_ORDENADO` | `quantityReceived` final = 6, nunca 12 | `PurchaseReceiptConcurrencyTest` (nueva) |
| 4 | Dos despachos de la misma transferencia | Transferencia APPROVED, aprobado = 6 | Uno 200, uno `TRANSICION_INVALIDA` | Stock origen −6 una sola vez | `TransferDispatchConcurrencyTest` + Postgres (nuevas) |
| 5 | Dos recepciones de la misma transferencia | Transferencia IN_TRANSIT, despachado = 6 | Una 200, una `RECEPCION_YA_REGISTRADA` | Stock destino +6 una sola vez; transferencia cierra en `RECEIVED_COMPLETE` | `TransferReceiveConcurrencyTest` + Postgres (nuevas) |
| 6 | Reintento HTTP tras timeout aparente (mismo `Idempotency-Key`, carrera real) | Stock = 10 | Una 200 (persiste), una choca contra la restricción única (`CONFLICTO_DATOS`) | Efecto aplicado exactamente una vez; el perdedor nunca toca inventario | `IdempotencyKeyRaceTest` (venta + solicitud de transferencia) + variante Postgres (nuevas) |
| 7 | Excepción intencional a mitad de operación → rollback | Cualquier operación crítica, fallo forzado al insertar el `InventoryMovement` (último paso) | La operación completa revierte | Ningún efecto parcial: stock, estado y cantidades vuelven al valor previo | `InventoryAdjustmentRollbackTest`, `PurchaseReceiptRollbackTest`, `SaleRollbackTest`, `TransferRollbackTest` (despacho, ya existían) + `TransferReceiveRollbackTest` (recepción, nueva — brecha real: no existía el equivalente para recibir) |
| 8 | Deadlock/lock timeout razonable | Dos productos, 20 c/u; dos ventas concurrentes con los mismos dos productos en orden opuesto | Ambas se confirman, sin colgarse | Sin deadlock; stock final correcto para ambos productos | `SaleOppositeOrderConcurrencyTest` (nueva) — **encontró un deadlock real, ver abajo** |

### Bug real encontrado (escenario 8) y corregido

**Síntoma:** `SaleOppositeOrderConcurrencyTest` falló de forma intermitente contra H2 con `CannotAcquireLockException` / `"Deadlock detected"` (SQLState 40001) en la tabla `INVENTORY`.

**Causa raíz:** `SaleService.confirmSale`, `PurchaseReceiptService.receive`, `TransferService.dispatch` y `TransferService.receive` recorrían las líneas de la operación en el orden en que llegaron en el payload (o, en `dispatch`, en el orden en que las devolvía `findByTransferId`) — nunca ordenadas por producto. El `UPDATE ... WHERE id = ? AND version = ?` de `InventoryRepository.applyQuantity`/`applyReceipt` es optimista **a nivel de aplicación** (compara una columna `version`), pero la sentencia `UPDATE` en sí sigue tomando el lock de fila estándar de cualquier motor SQL, retenido hasta el commit de la transacción — el "optimista" no evita ese lock, solo evita tener que mantenerlo desde una lectura previa. Dos transacciones que tocan los mismos dos productos en orden opuesto (p. ej. venta 1 = [A, B], venta 2 = [B, A]) pueden, por tanto, terminar cada una reteniendo el lock que la otra necesita — el patrón clásico de interbloqueo, exactamente el que el propio código asumía imposible por ser "bloqueo optimista" (supuesto que resultó incorrecto: optimista y "sin locks retenidos" no son lo mismo). El bucle de reintento (`MAX_RETRIES = 3`) solo contempla "0 filas afectadas" (conflicto de versión, benigno); no atrapa una `CannotAcquireLockException`, así que el deadlock se propagaba sin control hasta `GlobalExceptionHandler`, que no tiene un `@ExceptionHandler` para ese tipo — cae en el genérico `Exception.class` y responde 500 `ERROR_INTERNO`, disfrazando una condición transitoria y perfectamente recuperable de un bug real del servidor.

**Corrección mínima aplicada:** ordenar las líneas por `productId` (proxy estable de qué fila de `Inventory` se toca, sin una consulta adicional en los tres casos donde el id ya está disponible; con una resolución previa en lote, sin N+1, en `PurchaseReceiptService`, donde el identificador de línea es `purchaseOrderItemId`) **antes** del bucle que aplica los movimientos de inventario, en los cuatro métodos. Es la técnica estándar de prevención de interbloqueo (orden global consistente de adquisición de locks) — no un reintento adicional ni un cambio de semántica de negocio. Archivos: [SaleService.java](../backend/src/main/java/com/inventario/multisucursal/sales/SaleService.java), [PurchaseReceiptService.java](../backend/src/main/java/com/inventario/multisucursal/purchases/PurchaseReceiptService.java), [TransferService.java](../backend/src/main/java/com/inventario/multisucursal/transfers/TransferService.java) (`dispatch` y `receive`). Verificado: `SaleOppositeOrderConcurrencyTest` en verde en 10 corridas consecutivas tras el fix (antes fallaba con el deadlock); suite completa de backend, 317 pruebas, 0 fallos.

No se corrigió atrapando la excepción y reintentando la operación completa a ciegas — eso habría ocultado el síntoma sin resolver la causa (el orden inconsistente seguiría produciendo interbloqueos bajo más concurrencia o más líneas por operación); la corrección elimina la posibilidad estructural.

## 7. No accionable ahora

- Prueba dedicada de "sin token → 401" por cada endpoint individual de cada módulo: ya está demostrado una vez de forma genérica (`AuthenticationFlowTest.protectedEndpointWithoutTokenReturns401`) contra el mismo filtro de seguridad que aplica a todos los endpoints por igual; replicarlo por endpoint no detectaría un fallo de negocio adicional, solo inflaría el conteo de pruebas.
