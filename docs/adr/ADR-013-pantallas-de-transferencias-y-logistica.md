# ADR-013 — Pantallas de Transferencias y Logística

**Estado:** Accepted

**Relación con ADR-010/011/012:** los aplica sobre el flujo más largo del sistema — una máquina de estados de siete valores con acciones distintas por transición — y sobre el primer consumo real del canal SSE que ADR-007/ADR-009 ya habían aprobado e implementado en el backend, pero que ninguna pantalla había usado todavía.

## Contexto

Transferencias no es un CRUD con un par de mutaciones como compras o ventas: es `REQUESTED → APPROVED → IN_TRANSIT → RECEIVED_COMPLETE | RECEIVED_PARTIAL → CLOSED`, con `REQUESTED → REJECTED` como única salida temprana, y una acción distinta —con un rol y una sucursal distintos— en cada arista. La pantalla tiene que ser honesta sobre eso: mostrar solo la acción que corresponde al estado actual, y solo a quien de verdad puede ejecutarla.

## Decisión

### 1. No hay tabla de historial; el historial se deriva de las columnas de hito

`TransferResponse` no trae una lista de eventos — trae `requestedAt`/`approvedAt`/`dispatchedAt`/`receivedAt` y sus responsables como columnas de la propia transferencia, porque el modelo aprobado decidió no crear una tabla de historial aparte (`docs/STATUS.md`, fase de transferencias del back. `Timeline` (`pages/transfers/Timeline.tsx`) simplemente ordena esas columnas. No se inventa un estado intermedio que el backend no exponga, ni se mantiene una segunda estructura en el cliente que pudiera desincronizarse de la fuente real.

### 2. Visibilidad de acciones: estado + rol + sucursal, nunca solo uno

Cada botón de acción exige los tres:

| Acción | Estado requerido | Rol | Sucursal |
|---|---|---|---|
| Aprobar / Rechazar | `REQUESTED` | `MANAGER`+`ADMIN` | origen |
| Despachar | `APPROVED` | `OPERATOR`+`ADMIN` | origen |
| Recibir | `IN_TRANSIT` | `OPERATOR`+`ADMIN` | destino |
| Tratar faltante | línea con `quantityMissing > 0` sin tratamiento | `MANAGER`+`ADMIN` | origen o destino |

Esto es exactamente la tabla de autorización de `docs/API_DESIGN.md` sección 6, reproducida para no ofrecer un botón que el backend rechazaría — nunca para autorizar. Un `MANAGER` de una sucursal ajena, o un `OPERATOR` en el estado equivocado, simplemente no ve el botón; si de todos modos llama al endpoint, el backend responde `403`/`409` y la pantalla ya sabe mostrarlo (punto 4).

### 3. La cantidad aprobada/despachada tiene un tope estructural, no una regla de negocio local

El campo "cantidad a aprobar" limita visualmente a `quantityRequested`, y "cantidad a despachar" a `quantityApproved`. Esto **no** es una validación de disponibilidad — es el mismo límite que ya define la línea que el propio usuario (o su sucursal) generó antes. La disponibilidad de stock real (BR-005 al aprobar, BR-013 al despachar) la valida el backend al confirmar, y sus rechazos (`STOCK_INSUFICIENTE_PARA_TRANSFERENCIA`, `STOCK_INSUFICIENTE`) se muestran tal cual, nunca se anticipan calculándolos en el cliente.

### 4. Conflicto de estado: un 409 específico, no un error genérico

`TRANSICION_INVALIDA`, `RECEPCION_YA_REGISTRADA` y `FALTANTE_YA_TRATADO` significan lo mismo desde la interfaz: alguien más cambió el estado mientras la pantalla estaba abierta. `isStateConflict` (`pages/transfers/conflicts.ts`) los distingue de cualquier otro error de negocio, y cuando ocurre uno:

1. se cierra el diálogo de la acción — sus datos ya no describen la transferencia real;
2. se muestra un aviso explícito ("alguien más ya cambió el estado…") en vez de un mensaje de error genérico;
3. se invalida y relee la transferencia, para que la pantalla refleje el estado verdadero de inmediato.

La alternativa —dejar el diálogo abierto con el error dentro, como en el resto de formularios de negocio— se descartó a propósito aquí: reintentar la misma acción sobre una transferencia que ya avanzó no tiene sentido, así que insistir en el mismo diálogo sería engañoso.

### 5. Primer consumo del canal SSE: la pantalla se suscribe, nunca decide

`useTransferRealtime` abre `GET /events?access_token=…&branchId=…` (el token va en query string porque `EventSource` no admite encabezados propios — excepción ya aceptada solo para esta ruta, ADR-009) y, al recibir `transfer.status-changed` o `transfer.discrepancy-opened`, invalida la caché de transferencias. El payload nunca se usa como dato: es una señal que dispara una relectura de REST, exactamente el contrato que ADR-007/ADR-009 fijaron. Al reconectar (`onopen`) se invalida también, para reconciliar lo que pudo perderse durante la desconexión — el canal no reenvía eventos pasados por diseño.

Se monta solo en las pantallas de transferencias (listado y detalle), que son las únicas que lo necesitan en esta fase — mismo criterio de alcance que ADR-009 dejó explícito.

### 6. Logística se construye junto a transferencias, no aparte

El título de la fase menciona "transferencias y logística", y el backend ya tiene ambas capacidades completas (`routes`, `reports/logistics-compliance`). Se añadieron dos pantallas mínimas: catálogo de rutas (lectura abierta, clasificar/reclasificar `MANAGER`+`ADMIN`) y el reporte de cumplimiento, que muestra tal cual las cifras que el backend ya calcula (BR-038: nada se recalcula ni se reinterpreta en el cliente). No se construyó una gestión de rutas más elaborada que la que el contrato ofrece (`POST`/`PATCH`, sin borrado).

## Alternativas consideradas

- **Precalcular en el cliente si una aprobación/despacho alcanzará el stock:** rechazada — es exactamente la clase de regla de negocio que ADR-010 prohíbe replicar; el backend la valida al confirmar y su rechazo se muestra tal cual.
- **Dejar el diálogo de acción abierto ante un conflicto 409, igual que con cualquier otro error:** rechazada (punto 4) — el problema no es un dato mal escrito que corregir, es que la premisa del diálogo ya no es cierta.
- **Suscribir el canal SSE a nivel global (layout) en vez de por pantalla:** rechazada por ahora — ninguna otra pantalla lo necesita todavía, y una suscripción global sin consumidor sería infraestructura sin uso, contra el mismo criterio de ADR-009.

## Consecuencias positivas

- El patrón de "estado + rol + sucursal decide qué botón se ofrece" queda como plantilla reutilizable para cualquier flujo futuro con más de dos transiciones.
- El manejo de conflicto 409 es un módulo pequeño y reutilizable (`isStateConflict`) que cualquier otra acción de estado (si aparece) puede adoptar sin duplicar la lógica.
- El punto de enganche SSE quedó demostrado end-to-end: un cambio hecho "por detrás" (verificado con `curl` mientras la pantalla estaba abierta) se reflejó solo, sin recargar.

## Consecuencias negativas / trade-offs

- **Responsables de cada hito se muestran como `Usuario #<id>`**, sin resolver el nombre: no existe un índice de usuarios abierto a todos los roles (`GET /users` es `ADMIN`-only), y resolverlo solo para `ADMIN` habría creado una asimetría visual entre roles sin aportar mucho. Queda como mejora futura si se relaja esa restricción.
- **Reporte de cumplimiento sin exportación ni gráficos**: se limita a las tablas que el contrato ya entrega, en línea con "claridad sobre acabado visual" de ADR-010.
- **Una conexión SSE por pestaña de transferencias abierta**, ya señalado como límite de escala en ADR-007/ADR-009 y no revisado aquí.

## Hallazgo durante la verificación en vivo

React Router no remonta el elemento de una ruta cuando solo cambia el parámetro: navegar de `/transferencias/2` a `/transferencias/9` mediante un enlace **interno** (el que aparece dentro del propio diálogo de tratamiento de faltante, apuntando a la transferencia de reposición) reutilizaba la misma instancia de `TransferDetailPage`, y el diálogo seguía montado con los datos de la transferencia anterior superpuestos sobre la página ya actualizada. Ninguna prueba con `mockFetch` lo detectaba porque el patrón habitual de las pruebas es un `renderApp` por caso, no una navegación cliente entre dos vistas del mismo tipo — apareció solo al navegar de verdad en el navegador. Corregido separando la función exportada (que solo lee `:id`) de una vista interna montada con `key={id}`, forzando un `useState` fresco en cada cambio de transferencia.

## Criterios para reconsiderarla

- Si aparece un endpoint de usuarios abierto a todos los roles: resolver nombres en el historial en vez de `Usuario #<id>`.
- Si el reporte de cumplimiento necesita compararse visualmente entre rutas con frecuencia: considerar un gráfico, sin perder la fuente tabular actual.
- Si otra pantalla necesita el canal SSE: evaluar entonces si conviene una suscripción a nivel de layout en vez de una por pantalla.
