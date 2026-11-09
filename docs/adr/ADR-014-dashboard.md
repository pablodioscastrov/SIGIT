# ADR-014 — Dashboard de indicadores

**Estado:** Accepted

**Relación con ADR-010/011/012/013:** primera fase que toca backend y frontend a la vez. En el backend añade un módulo nuevo (`dashboard`) que se apoya en el mismo patrón de "hoja del grafo de dependencias" que `reports` (ADR de logística/cumplimiento) ya había establecido. En el frontend reutiliza sin cambios la arquitectura base de ADR-010 y el canal SSE que ADR-013 fue el primero en consumir.

## Contexto

La prueba pide seis indicadores mínimos (volumen de ventas del mes vs. anteriores, rotación de inventario, demanda alta/baja, transferencias activas y su impacto, reabastecimiento, comparativa entre sucursales) sin fijar su fórmula exacta. Antes de escribir código se definió cada uno matemáticamente en `docs/BUSINESS_RULES.md` (BR-039 a BR-043): fuente de datos, ventana temporal, filtros y tratamiento de sucursales — para no improvisar precisión donde el dato disponible no la sostiene.

## Decisión

### 1. Definición de cada KPI — simple y defendible, no inventada

- **Ventas del mes vs. anteriores (BR-039):** mes actual + `months` anteriores (por defecto 3, máximo 24), agregado en SQL por rango de fecha. Variación porcentual `null` — nunca `Infinity` ni un `0` engañoso — cuando el mes anterior no tuvo ventas.
- **Rotación de inventario (BR-040):** `unidades vendidas en la ventana ÷ stock actual`. Se documentó explícitamente como una aproximación: el sistema no guarda una serie histórica de inventario, así que la fórmula clásica de "promedio de inventario del período" es inviable con los datos disponibles. `null` cuando el stock actual es 0 (nunca una división por cero).
- **Demanda alta/baja (BR-040):** los `limit` productos con más/menos unidades vendidas en la ventana, anclado al catálogo de `Inventory` de la sucursal — no solo a los productos con ventas — para que un producto con cero ventas aparezca en "baja demanda" en vez de desaparecer.
- **Transferencias activas e impacto (BR-041):** cualquier estado no terminal (`REQUESTED`, `APPROVED`, `IN_TRANSIT`, `RECEIVED_PARTIAL`). El impacto se separa en real (`unitsInTransit`, ya descontado del origen) y proyectado (`unitsPendingDispatch`, comprometido pero aún no aplicado) — **cada línea contribuye a exactamente uno de los dos, nunca a ambos**, porque el despacho de una línea es un único evento que no se repite (BR-034): lo no despachado de una línea ya despachada no vuelve a estar "pendiente".
- **Reabastecimiento (BR-042):** reutiliza sin cambios el umbral `quantityOnHand <= minimumStock` ya aprobado para el filtro `lowStock` de Inventario (BR-010) — no se inventa un segundo criterio de "bajo stock" para el dashboard.
- **Comparativa entre sucursales (BR-043):** exclusiva de `MANAGER`/`ADMIN` (`OPERATOR` recibe 403); yuxtapone las mismas cifras que cada sucursal ya expone individualmente, sin una fórmula nueva.

### 2. Endpoints agregados específicos, no un mega-endpoint

Cinco endpoints (`GET /dashboard/sales-summary`, `.../inventory-rotation`, `.../active-transfers`, `.../replenishment`, `.../branch-comparison`), cada uno con su propio contrato y su propia forma de respuesta. Se descartó un único endpoint "todo el dashboard": cada panel tiene su propio ciclo de carga/error/vacío en el frontend, y un mega-endpoint habría acoplado esos ciclos entre sí (un panel lento o roto bloqueando a los demás) sin ninguna ventaja real, porque ninguno de los cinco cálculos comparte una consulta base.

### 3. Toda la agregación ocurre en SQL, dentro del módulo dueño del dato

`dashboard` es una hoja del grafo de dependencias (igual que `reports`): no tiene tablas propias, y cada suma/conteo/orden se resuelve en el módulo que ya es dueño de esos datos —

- `SaleRepository.aggregateForRange` / `SaleItemRepository.demandByProduct`: `SUM`/`COUNT`/`GROUP BY` en JPQL, incluyendo una unión sin asociación JPA entre `SaleItem` y `Sale` (mismo patrón de "theta join" que `InventoryRepository` ya usa entre `Inventory` y `Product` para su búsqueda) porque ambas entidades son deliberadamente planas y sin `@ManyToOne`.
- `InventoryRepository.countLowStock` / `findMostUrgentLowStock`: filtro y orden (`quantityOnHand - minimumStock ASC`) en SQL, con `Pageable` para el top-N — nunca se trae la tabla completa a Java para ordenar ahí.
- `TransferRepository.findActive` + `TransferItemRepository.findByTransferIdIn`: dos consultas (transferencias activas, luego sus líneas en un solo `IN`) para evitar N+1, con el cálculo de real/proyectado hecho en el bucle Java porque depende de una regla condicional por línea (punto 1) que no es una simple suma SQL.

`DashboardService` solo combina resultados ya acotados a una fila por mes, un puñado de productos o una sucursal a la vez — nunca una tabla completa cargada en memoria para agregar ahí.

### 4. Alcance de sucursal: mismo criterio que `reports`, no el genérico de escritura

`DashboardService.requireBranch` restringe solo a `OPERATOR` a su propia sucursal; `MANAGER` y `ADMIN` consultan cualquiera — el mismo "dashboard completo" que `LogisticsComplianceService.resolveBranchScope` ya tenía aprobado, deliberadamente distinto del `AuthorizationService.requireBranchAccess` genérico que sí limita a `MANAGER` en las pantallas de escritura. Reutilizar el genérico habría sido más simple de escribir, pero incorrecto: se detectó como bug real durante las pruebas (ver "Hallazgo").

### 5. Frontend: cuatro paneles independientes, sin librería de gráficos

`DashboardPage` monta `SalesTrendPanel`, `InventoryDemandPanel`, `ActiveTransfersPanel` y `ReplenishmentPanel`, cada uno con su propia consulta y su propio `AsyncBoundary` — el error de un panel (por ejemplo, un 500 en `active-transfers`) no oculta ni rompe los demás, que es justamente lo que las pruebas verifican. El gráfico de barras de ventas mensuales (`MiniBarChart`) es SVG dibujado a mano, sin librería externa, mismo criterio que ADR-010 fija para no agregar dependencias sin justificación concreta — cinco barras con una etiqueta y un valor no lo justifican.

### 6. El panel de transferencias activas reutiliza el canal SSE ya aprobado

`ActiveTransfersPanel` se registra bajo el mismo prefijo de clave de caché (`"transfers"`) que ya invalida `useTransferRealtime` (ADR-013) al recibir `transfer.status-changed`/`transfer.discrepancy-opened`. No se abrió una segunda conexión SSE ni se inventó un evento nuevo: el dashboard simplemente se beneficia de una señal que ya existía para otra pantalla.

## Alternativas consideradas

- **Un único endpoint `/dashboard` con todo el payload:** rechazada (punto 2) — acopla el ciclo de vida de cinco cálculos independientes sin necesidad.
- **Calcular la rotación como "promedio de inventario del período":** rechazada — el sistema no guarda snapshots históricos de inventario; forzar esa fórmula habría exigido datos que no existen o una precisión ficticia. Se documentó la aproximación en su lugar (BR-040).
- **Reutilizar `AuthorizationService.requireBranchAccess` para el alcance de sucursal:** rechazada tras encontrarla incorrecta en pruebas (punto 4 y "Hallazgo") — limita a `MANAGER`, contradiciendo el "dashboard completo" ya aprobado para `reports`.
- **Una librería de gráficos (Recharts, Chart.js, etc.) para el panel de ventas:** rechazada — un solo gráfico de barras simple no justifica una dependencia nueva (ADR-010).

## Consecuencias positivas

- Cada KPI queda documentado y testeado contra su definición exacta (`docs/BUSINESS_RULES.md` + `DashboardApiTest`), no contra una intuición de "lo que probablemente se pedía".
- El patrón "hoja del grafo + agregación en SQL en el módulo dueño" queda reforzado como plantilla para cualquier reporte futuro, con un segundo caso de theta-join documentado (`SaleItem`↔`Sale`) además del ya existente (`Inventory`↔`Product`).
- El aislamiento de errores por panel (punto 5) quedó verificado tanto en el navegador como en una prueba automatizada dedicada.

## Consecuencias negativas / trade-offs

- **La rotación de inventario es una aproximación conocida**, no la fórmula clásica de rotación contable; queda documentado en BR-040 y en el propio texto de la interfaz ("aproximación, no promedio de inventario del período") para que no se lea como más precisa de lo que es.
- **El impacto de transferencias activas recorre las líneas en Java, no en una sola consulta SQL agregada**, porque la regla de "real vs. proyectado" es condicional por línea (punto 1) — un trade-off aceptado porque el volumen (líneas de transferencias activas de una sucursal) es intrínsecamente pequeño, a diferencia de ventas o inventario.
- **Sin filtro de rango de fechas libre para ventas/demanda**, solo el parámetro `months` (cuántos meses atrás): se consideró suficiente para los indicadores pedidos y evita un selector de fechas que ningún KPI de este alcance necesita.

## Hallazgo durante la verificación en vivo

`DashboardApiTest` (dataset pequeño con resultado esperado conocido, `activeTransfersComputesInTransitAndPendingDispatchSeparately`) encontró un bug real antes de cualquier verificación manual: la primera versión de `TransferService.findActiveForDashboard` sumaba `unitsInTransit` (lo ya despachado) **y además** `unitsPendingDispatch` (aprobado menos despachado) sobre la misma línea parcialmente despachada, contando dos veces el mismo compromiso de stock. Se corrigió haciendo la rama mutuamente excluyente por línea (`quantityShipped == null` → proyectado; si no, real) y se actualizó BR-041 para reflejar la definición corregida. Es exactamente el tipo de error que "datasets pequeños con resultados esperados" —pedido explícitamente para esta fase— existe para atrapar: no era visible con datos de humo donde ninguna línea queda parcialmente despachada.

## Criterios para reconsiderarla

- Si se agregan snapshots históricos de inventario: reconsiderar la fórmula de rotación (BR-040) hacia el promedio de inventario del período, en vez de la aproximación actual.
- Si el volumen de transferencias activas por sucursal deja de ser pequeño: mover el cálculo de real/proyectado a SQL (posiblemente con una expresión `CASE` agregada) en vez de Java.
- Si se pide un rango de fechas libre (no solo "N meses atrás") para ventas o demanda: añadir `from`/`to` explícitos a `sales-summary`/`inventory-rotation`, documentando el nuevo contrato antes de implementarlo.
