# Diseño de la API REST v1

**Sistema de Inventario Multi-Sucursal**

**Base de este documento:** `docs/USE_CASES.md`, `docs/BUSINESS_RULES.md`, `docs/DOMAIN_MODEL.md`, `docs/CRITICAL_FLOWS.md`, `docs/ARCHITECTURE.md`, `docs/adr/ADR-004-rest.md`, `docs/adr/ADR-007-estrategia-near-real-time.md`.

**Fecha:** 2026-08-26.

**Alcance:** contrato de la API REST v1. No se implementan controladores, DTOs Java ni componentes React. El campo "convención" aquí definido es vinculante para la fase de implementación.

**Principio rector de este diseño:** la API expone **casos de uso del dominio**, no las tablas de `docs/DOMAIN_MODEL.md`. Ningún endpoint refleja una entidad JPA uno a uno; cada respuesta es un DTO ensamblado por la capa de aplicación, con su propia forma y nombres, independiente del esquema físico.

---

## 1. Convenciones generales

| Aspecto | Convención |
|---|---|
| Base URL | `/api/v1` — toda ruta de este documento es relativa a esta base. |
| Formato | JSON (`Content-Type: application/json; charset=utf-8`) en request y response. No se soporta XML ni form-encoded. |
| Nombres de campo | `camelCase` en JSON (p. ej. `quantityOnHand`), independientemente de que la base de datos use `snake_case` (`docs/DOMAIN_MODEL.md`) — la traducción es responsabilidad de la capa de DTO, nunca se expone el nombre de columna tal cual. |
| Identificadores | Claves primarias `BIGINT` (`docs/DOMAIN_MODEL.md`, sección 1) serializadas **como string** en JSON (`"id": "10045"`), no como número — evita pérdida de precisión en clientes JavaScript/TypeScript para valores grandes. |
| Timestamps | ISO-8601 en UTC, sufijo `Z` (`"createdAt": "2026-08-26T15:04:00Z"`). Ningún endpoint acepta ni devuelve timestamps en otro formato o zona horaria; la conversión a hora local es responsabilidad del frontend. |
| Cantidades y precios | Números JSON (no strings), hasta 4 decimales para costos/precios unitarios y cantidades fraccionables (kilogramos, litros). La política de redondeo final está **pendiente** (`docs/BUSINESS_RULES.md`, BR-004) — se documentará aquí cuando se resuelva. |
| Paginación | Basada en página (`page`, base 0; `size`, por defecto 20, máximo 100). Toda colección responde con el sobre: `{"content": [...], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7}`. No se pagina por cursor en v1 — el volumen esperado (`docs/PROJECT_BRIEF.md`, RNF-004) no lo justifica. |
| Filtros | Query params específicos por recurso (documentados por endpoint). Convención de rango: `xxxFrom`/`xxxTo` (p. ej. `dateFrom`, `dateTo`). Convención booleana: `true`/`false` en minúscula. |
| Ordenamiento | `sort=campo,direccion` (p. ej. `sort=createdAt,desc`); repetible para orden multicampo (`sort=branchId,asc&sort=createdAt,desc`). Si se omite, cada endpoint documenta su orden por defecto. |
| Versionado | El número de versión va en la ruta (`/api/v1`), no en un header. Un cambio incompatible se publicaría como `/api/v2`, no se muta v1 en producción. |

---

## 2. Autenticación y encabezados

| Header | Uso |
|---|---|
| `Authorization: Bearer <jwt>` | Obligatorio en toda ruta salvo `POST /auth/login`. El JWT codifica `userId`, `role`, `branchId` (nulo si `ADMIN`) — `docs/adr/ADR-005-jwt-rbac.md`. |
| `Idempotency-Key: <uuid>` | Obligatorio en las operaciones de **creación repetible** (categoría 2 de `docs/CRITICAL_FLOWS.md`, sección 1.1): `POST /sales`, `POST /sales/{id}/returns`, `POST /purchase-orders`, `POST /purchase-orders/{id}/receipts`, `POST /inventory/adjustments`, `POST /transfers`, `POST /price-lists/{id}/prices`. El cliente genera el UUID una sola vez por acción de usuario y lo reenvía igual en cualquier reintento automático. Ausente el header en estos endpoints → `400`. No aplica a las transiciones de un solo uso (`approve`, `dispatch`, `receive`, etc.), que se protegen por el propio estado del recurso. |
| `Content-Type: application/json` | Obligatorio en toda solicitud con body. |
| `X-Request-Id` | Opcional en la solicitud; si el cliente no lo envía, el servidor genera uno y lo devuelve en la respuesta (éxito o error) para correlación con logs (`docs/ARCHITECTURE.md`, sección 8). |

**Excepción documentada — canal SSE (`GET /events`):** el estándar `EventSource` del navegador no permite fijar encabezados personalizados, por lo que el JWT se pasa como parámetro de consulta (`?access_token=<jwt>`) únicamente en esta ruta. Esto implica que el token puede quedar en logs de acceso HTTP intermedios — se acepta como limitación conocida para el alcance de esta prueba (`docs/adr/ADR-007-estrategia-near-real-time.md`); una mejora futura sería emitir un token de vida muy corta exclusivo para esta conexión, **decisión pospuesta**, no resuelta aquí.

---

## 3. Formato uniforme de errores

Todo error (4xx o 5xx) responde con el mismo sobre:

```json
{
  "error": {
    "code": "STOCK_INSUFICIENTE",
    "message": "No hay stock suficiente del producto 101 en la sucursal 4. Disponible: 3, solicitado: 5.",
    "status": 422,
    "requestId": "b3c1e4b2-...",
    "details": [
      { "field": "items[0].quantity", "issue": "excede el stock disponible" }
    ]
  }
}
```

- `code`: slug estable en mayúsculas, el mismo definido en `docs/BUSINESS_RULES.md` por cada regla (ver tabla de la sección 6). Un cliente puede tomar decisiones de UI por `code`, nunca debería parsear `message`.
- `message`: texto legible para mostrar o loguear, no garantizado estable entre versiones.
- `details`: opcional, solo presente en errores de validación con múltiples campos afectados (400/422 estructurales).

**Nunca** se devuelve una lista de errores como array en el nivel superior, ni un `200` con un cuerpo que indique error dentro (`{"success": false}`) — todo error usa el código HTTP correspondiente.

**Implementado** en `backend/src/main/java/com/inventario/multisucursal/common/web/` (`ApiErrorResponse`, `ApiErrorBody`, `GlobalExceptionHandler`) — ver `docs/ARCHITECTURE.md`, sección 8, para el detalle de qué excepción usar en cada módulo nuevo.

---

## 4. Códigos HTTP

Reutiliza exactamente la convención ya fijada en `docs/BUSINESS_RULES.md`:

| Código | Cuándo | Ejemplos de `code` |
|---|---|---|
| 200 / 201 | Éxito (200 lectura/acción sobre recurso existente; 201 creación con `Location` del nuevo recurso). | — |
| 400 | Payload estructuralmente inválido, o falta `Idempotency-Key` donde es obligatorio. | `VALIDATION_ERROR`, `IDEMPOTENCY_KEY_REQUERIDO` |
| 401 | JWT ausente, inválido o expirado. | `NO_AUTENTICADO` |
| 403 | Rol o alcance de sucursal no autoriza la acción (BR-018). | `ROL_NO_AUTORIZADO`, `SUCURSAL_NO_AUTORIZADA` |
| 404 | El recurso referenciado no existe. | `RECURSO_NO_ENCONTRADO` |
| 409 | Estado del recurso impide la operación: transición inválida, ya aplicada, conflicto de versión. | `TRANSICION_INVALIDA`, `ORDEN_YA_RECIBIDA`, `FALTANTE_YA_TRATADO`, `CONFLICTO_CONCURRENCIA` |
| 422 | Payload válido en forma, pero viola una regla de negocio semántica. | `STOCK_INSUFICIENTE`, `CANTIDAD_INVALIDA`, `DESCUENTO_FUERA_DE_RANGO`, `RECEPCION_EXCEDE_ENVIADO`, `CANTIDAD_DESPACHO_EXCEDE_APROBADO` |
| 500 | Fallo interno no atribuible al cliente. | `ERROR_INTERNO` |

---

## 5. Idempotencia

Ver `docs/CRITICAL_FLOWS.md`, sección 1.1, para el fundamento. Resumen aplicado a la API:

| Endpoint | Categoría | Mecanismo |
|---|---|---|
| `POST /sales` | 2 — creación repetible | `Idempotency-Key` obligatorio |
| `POST /purchase-orders` | 2 | `Idempotency-Key` obligatorio |
| `POST /purchase-orders/{id}/receipts` | 2 | `Idempotency-Key` obligatorio |
| `POST /sales/{id}/returns` | 2 | `Idempotency-Key` obligatorio — clave derivada por línea, `<Idempotency-Key>:<saleItemId>` (BR-052, igual patrón que la recepción de compra) |
| `POST /inventory/adjustments` | 2 | `Idempotency-Key` obligatorio |
| `POST /transfers` | 2 | `Idempotency-Key` obligatorio |
| `POST /price-lists/{id}/prices` | 2 | `Idempotency-Key` obligatorio |
| `POST /transfers/{id}/approve` \| `/reject` \| `/dispatch` \| `/receive` | 1 — transición de un solo uso | Guarda de estado (`WHERE status = <esperado>`); un reintento recibe 409, no requiere `Idempotency-Key` |
| `POST /transfers/{id}/items/{itemId}/discrepancy-treatment` | 1 | Guarda `WHERE discrepancy_treatment IS NULL` |
| `POST /sales/{id}/void`, `POST /purchase-orders/{id}/cancel` | 1 | Guarda de estado |

---

## 6. Autorización por endpoint

Convención de la tabla: **rol** con acceso; "propia sucursal" significa que el `branchId` del usuario debe coincidir con el del recurso (excepto `ADMIN`, alcance global siempre). Basada en `docs/USE_CASES.md` (matriz Actor×Acción) y `docs/BUSINESS_RULES.md` BR-018; las asignaciones no cubiertas literalmente por esa matriz están marcadas **[Supuesto]**.

| Recurso | Lectura | Escritura |
|---|---|---|
| `auth/login` | público | — |
| `auth/me` | cualquier rol autenticado | — |
| `users`, `roles` | `ADMIN` | `ADMIN` |
| `branches` | cualquier rol autenticado | `ADMIN` |
| `products`, `units-of-measure` | cualquier rol autenticado | `OPERATOR` + `ADMIN` **[Supuesto: gestión de catálogo no asignada explícitamente en el documento fuente; se asigna a quien ejecuta operaciones de inventario]** — excepción: crear o editar una unidad de medida (`POST`/`PATCH /units-of-measure`) es `ADMIN` únicamente (BR-050), más restrictivo que el resto de la fila |
| `inventory`, `inventory-movements` | cualquier rol autenticado, cualquier sucursal (RF-003) | — (solo vía `adjustments` u otros flujos) |
| `inventory/adjustments` | — | `OPERATOR` (propia sucursal) + `ADMIN` |
| `stock-alerts` | cualquier rol autenticado | — (generación automática) |
| `suppliers` | cualquier rol autenticado | `MANAGER`+`ADMIN` para crear/editar/activar/desactivar; `DELETE` exclusivo de `ADMIN` — BR-058 reduce el alcance de BR-049 (`OPERATOR` pasa a solo lectura), sin restricción por sucursal en ningún caso |
| `purchase-orders`, `.../receipts` | `MANAGER`/`OPERATOR` (propia sucursal), `ADMIN` (cualquiera) | `OPERATOR`/`MANAGER` (propia sucursal) + `ADMIN` — `MANAGER` ampliado a las mismas capacidades que `ADMIN`/`OPERATOR` (crear, cancelar, recibir) por decisión explícita registrada en BR-047 |
| `sales` | `MANAGER`/`OPERATOR` (propia sucursal), `ADMIN` | `OPERATOR` + `MANAGER` (propia sucursal) + `ADMIN` — ampliado por BR-053, incluye crear y devolver (`/returns`) |
| `sales/{id}/void` | — | `MANAGER` (propia sucursal) + `ADMIN` **[Supuesto: anulación requiere un rol de supervisión, no el mismo Operador que la creó — condicionado a que `docs/DOMAIN_MODEL.md` decisión 9 apruebe el estado `VOIDED`]** |
| `price-lists`, `prices` | cualquier rol autenticado | `ADMIN` **[Supuesto: fijación de precios es administrativa]** |
| `transfers` (lectura) | cualquier rol de las sucursales origen/destino, `ADMIN` cualquiera | — |
| `transfers` (crear solicitud) | — | `OPERATOR`/`MANAGER`/`ADMIN` (RF-022) |
| `transfers/{id}/approve`, `/reject` | — | `MANAGER` (sucursal origen) + `ADMIN` — supuesto pendiente ya señalado en `docs/USE_CASES.md`/`docs/DOMAIN_MODEL.md` |
| `transfers/{id}/dispatch` | — | `OPERATOR`/`MANAGER` (sucursal origen) + `ADMIN` — `MANAGER` ampliado por decisión BR-047 |
| `transfers/{id}/receive` | — | `OPERATOR`/`MANAGER` (sucursal destino) + `ADMIN` — `MANAGER` ampliado por decisión BR-047 |
| `transfers/{id}/items/{itemId}/discrepancy-treatment` | — | `MANAGER` + `ADMIN` — mismo supuesto pendiente |
| `routes` | cualquier rol autenticado | `MANAGER` + `ADMIN` |
| `dashboard/*` | propia sucursal, todos los roles | — |
| `dashboard/branch-comparison` | `MANAGER` + `ADMIN` únicamente (RF-035) | — |
| `reports/logistics-compliance` (interactivo y `/export`) | propia sucursal para `OPERATOR`; cualquiera para `MANAGER`/`ADMIN` | — |
| `reports/inventory-movements/export`, `reports/sales/export`, `reports/transfers/export` | propia sucursal para `OPERATOR`/`MANAGER` (mismo alcance que el listado interactivo de cada módulo); cualquiera para `ADMIN` | — |
| `events` (SSE) | cualquier rol autenticado, eventos filtrados a sus sucursales visibles | — |

---

## 7. Recursos y endpoints

Convención de nomenclatura: sustantivos en plural para colecciones; acciones de negocio complejas como sub-recurso verbal explícito (`/transfers/{id}/dispatch`) en vez de forzar un `PATCH` ambiguo sobre el estado — preferencia explícitamente solicitada y coherente con BR-020 (la transición es un evento de negocio, no una simple edición de campo).

### 7.1 Auth

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/auth/login` | Autentica con `email`/`password`, devuelve JWT. |
| `GET` | `/auth/me` | Perfil del usuario autenticado: rol, sucursal, permisos efectivos. |

### 7.2 Users / Roles

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/users` | Lista paginada. Filtros: `branchId`, `role`, `active`. |
| `POST` | `/users` | Crea usuario. |
| `GET` | `/users/{id}` | Detalle. |
| `PATCH` | `/users/{id}` | Actualiza nombre/correo/rol/sucursal (BR-058 sumó el correo; la contraseña sigue fuera de este endpoint). |
| `POST` | `/users/{id}/activate` \| `/deactivate` | Baja/alta lógica (BR-021). |
| `GET` | `/roles` | Catálogo fijo de 3 roles, para poblar formularios. |

### 7.3 Branches

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/branches` | Lista. Filtro: `active`. |
| `POST` | `/branches` | Crea sucursal. |
| `GET` | `/branches/{id}` | Detalle. |
| `PATCH` | `/branches/{id}` | Actualiza datos. |
| `POST` | `/branches/{id}/activate` \| `/deactivate` | Baja/alta lógica. |

### 7.4 Products / Units of Measure

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/products` | Lista. Filtros: `search` (sku/nombre), `active`. |
| `POST` | `/products` | Crea producto (incluye `baseUnitOfMeasureId`, `minimumStock` y `unitPrice` — BR-048, BR-051). |
| `GET` | `/products/{id}` | Detalle. |
| `PATCH` | `/products/{id}` | Actualiza nombre/descripción/precio de venta/stock mínimo (`unitPrice` obligatorio — BR-057, cierra el precio vigente anterior y fija uno nuevo; `minimumStock` obligatorio — BR-059, solo cambia el valor de siembra para sucursales sin `Inventory` de este producto). |
| `POST` | `/products/{id}/activate` \| `/deactivate` | Baja/alta lógica. |
| `GET` | `/products/{id}/units` | Unidades alternativas y su factor de conversión (RF-011). |
| `POST` | `/products/{id}/units` | Agrega una unidad alternativa. |
| `PATCH` | `/products/{id}/units/{unitOfMeasureId}` | Ajusta el factor de conversión. |
| `GET` | `/units-of-measure` | Catálogo global de unidades. |
| `POST` | `/units-of-measure` | Crea una unidad de medida (`ADMIN`). |
| `PATCH` | `/units-of-measure/{id}` | Edita el nombre (`ADMIN`; BR-050). `code` es la clave de negocio y no se acepta en este cuerpo. |

### 7.5 Inventory / Movements / Alerts

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/inventory` | Stock por producto/sucursal. Filtros: `branchId` (recomendado), `productId`, `search`, `lowStock=true` (solo por debajo del mínimo). RF-002, RF-003, RF-006. |
| `GET` | `/inventory/{id}` | Detalle de una fila de stock (cantidad, costo promedio, mínimo). |
| `GET` | `/inventory-movements` | Ledger completo (RF-009). Filtros: `branchId`, `productId`, `reason`, `dateFrom`, `dateTo`. Orden por defecto: `occurredAt,desc`. |
| `POST` | `/inventory/adjustments` | Ajuste manual (flujo G, BR-023). `Idempotency-Key` obligatorio. |
| `GET` | `/stock-alerts` | Alertas de stock mínimo (RF-010, RF-036). Filtros: `branchId`, `status` (`ACTIVE`/`RESOLVED`). |

### 7.6 Suppliers

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/suppliers` | Lista. Filtro: `active`, `search`. |
| `POST` | `/suppliers` | Crea proveedor (`MANAGER`+`ADMIN`, BR-058). |
| `GET` | `/suppliers/{id}` | Detalle. |
| `PATCH` | `/suppliers/{id}` | Actualiza datos (`MANAGER`+`ADMIN`, BR-058). |
| `POST` | `/suppliers/{id}/activate` \| `/deactivate` | Baja/alta lógica (`MANAGER`+`ADMIN`, BR-058). |
| `DELETE` | `/suppliers/{id}` | Eliminación real, exclusiva de `ADMIN` (BR-058); rechaza con 409 si tiene órdenes de compra asociadas. |

### 7.7 Purchases

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/purchase-orders` | Lista. Filtros: `branchId`, `supplierId`, `status`. |
| `POST` | `/purchase-orders` | Crea orden con líneas (RF-012, RF-013). `Idempotency-Key` obligatorio. |
| `GET` | `/purchase-orders/{id}` | Detalle con líneas y su `quantityReceived`. |
| `POST` | `/purchase-orders/{id}/cancel` | `CREATED → CANCELLED` (solo si nada se ha recibido). |
| `POST` | `/purchase-orders/{id}/receipts` | Confirma recepción total o parcial (flujo B, RF-014, RF-016). `Idempotency-Key` obligatorio. |

### 7.8 Sales / Price Lists

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/sales` | Lista. Filtros: `branchId`, `dateFrom`, `dateTo`, `status`. |
| `POST` | `/sales` | Registra y confirma una venta atómicamente (flujo A). `Idempotency-Key` obligatorio. |
| `GET` | `/sales/{id}` | Detalle con líneas y comprobante (RF-021), incluido `quantityReturned`/`pending` por línea (BR-052). |
| `POST` | `/sales/{id}/void` | Anula una venta confirmada — **condicionado a la aprobación pendiente del estado `VOIDED`** (`docs/DOMAIN_MODEL.md`, decisión 9); no disponible hasta esa aprobación. |
| `POST` | `/sales/{id}/returns` | Devolución total o parcial, una o varias líneas (BR-052): repone inventario al costo promedio vigente, no recalcula `Sale.total` ni cambia `Sale.status`. `Idempotency-Key` obligatorio. |
| `GET` | `/price-lists` | Lista. Filtro: `branchId`, `active`. |
| `POST` | `/price-lists` | Crea lista de precios. |
| `GET` | `/price-lists/{id}/prices` | Precios vigentes; `includeHistory=true` incluye versiones cerradas (BR-004/decisión 3.4). |
| `POST` | `/price-lists/{id}/prices` | Fija un nuevo precio vigente para un producto (cierra la versión anterior). `Idempotency-Key` obligatorio. |

### 7.9 Transfers

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/transfers` | Lista. Filtros: `branchId` + `role=origin\|destination`, `status`. |
| `POST` | `/transfers` | Solicitud (flujo C1, RF-022). `Idempotency-Key` obligatorio. |
| `GET` | `/transfers/{id}` | Detalle con líneas (cantidades solicitada/aprobada/despachada/recibida/faltante y tratamiento). |
| `POST` | `/transfers/{id}/approve` | Aprueba, con posible ajuste de cantidad por línea (flujo C2, RF-023, BR-005). |
| `POST` | `/transfers/{id}/reject` | Rechaza la solicitud. |
| `POST` | `/transfers/{id}/dispatch` | Despacha **todas** las líneas de la transferencia en un solo evento de envío (flujo D, RF-024, BR-013) — una transferencia tiene un único tramo de envío (`docs/DOMAIN_MODEL.md`, 2.17), por eso el despacho no es parcial por línea. |
| `POST` | `/transfers/{id}/receive` | Confirma recepción de una o más líneas (flujo E/F1). Acepta un subconjunto de líneas por llamada — a diferencia del despacho, la recepción física puede contarse línea por línea en momentos distintos (ver `docs/CRITICAL_FLOWS.md`, escenario 3.5); el estado de la transferencia se recalcula tras cada llamada y solo pasa a `RECEIVED_COMPLETE`/`RECEIVED_PARTIAL` cuando **todas** las líneas quedan atendidas. |
| `POST` | `/transfers/{id}/items/{itemId}/discrepancy-treatment` | Define el tratamiento del faltante de una línea (flujo F2, RF-026, BR-009); cierra la transferencia automáticamente si era la última línea pendiente. |
| `GET` | `/routes` | Catálogo de rutas clasificadas (RF-028). |
| `POST` | `/routes` | Crea/clasifica una ruta origen-destino. |
| `PATCH` | `/routes/{id}` | Actualiza clasificación. |

### 7.10 Logistics / Dashboard / Reports

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/reports/logistics-compliance` | Cumplimiento logístico (RF-027, RF-030). Filtros: `branchId`, `routeId`, `dateFrom`, `dateTo`. |
| `GET` | `/reports/inventory-movements/export` | Reporte exportable en Excel de movimientos de inventario (BR-056). Filtros: `branchId`, `productId`, `reason`, `dateFrom`/`dateTo` **obligatorios**. Devuelve `.xlsx` (`Content-Disposition: attachment`), tope de 5000 filas. |
| `GET` | `/reports/sales/export` | Reporte exportable en Excel de ventas (BR-056). Filtros: `branchId`, `status`, `dateFrom`/`dateTo` obligatorios. |
| `GET` | `/reports/transfers/export` | Reporte exportable en Excel de transferencias (BR-056). Filtros: `branchId`, `status`, `dateFrom`/`dateTo` obligatorios. |
| `GET` | `/reports/logistics-compliance/export` | Mismo reporte que el endpoint interactivo, exportado a Excel (BR-056), pero con `dateFrom`/`dateTo` obligatorios (el interactivo los acepta abiertos). Filtros: `branchId`, `routeId`. |
| `GET` | `/dashboard/sales-summary` | Ventas del mes vs. anteriores (RF-031). Filtros: `branchId`, `months` (por defecto 3, `docs/PROJECT_BRIEF.md` supuesto 6). |
| `GET` | `/dashboard/inventory-rotation` | Rotación y productos alta/baja demanda (RF-032). Filtro: `branchId`. |
| `GET` | `/dashboard/active-transfers` | Transferencias activas e impacto (RF-033). Filtro: `branchId`. |
| `GET` | `/dashboard/replenishment` | Productos próximos a agotarse (RF-034). Filtro: `branchId`. |
| `GET` | `/dashboard/branch-comparison` | Comparativa entre sucursales (RF-035); solo `MANAGER`/`ADMIN`. |

### 7.11 Eventos near-real-time (SSE)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/events` | Flujo `text/event-stream`. Filtro: `branchId` (repetible). Tipos de evento: `inventory.updated`, `stock-alert.triggered`, `stock-alert.resolved`, `transfer.status-changed`, `transfer.discrepancy-opened`. Ver `docs/adr/ADR-007-estrategia-near-real-time.md` — el evento es una señal, el cliente vuelve a consultar la API REST para el dato completo. |

---

## 8. DTOs de referencia (forma, no exhaustiva)

Los campos siguientes son la forma de respuesta pública — **no** corresponden 1:1 a las columnas de `docs/DOMAIN_MODEL.md** (p. ej. un `InventoryMovement` nunca expone sus tres FKs documentales sueltas; se agrupan en un objeto `source`).

**`ProductDTO`**: `id`, `sku`, `name`, `description`, `baseUnitOfMeasureId`, `active`, `minimumStock` (BR-048), `salePrice` (BR-051 — precio vigente resuelto de la lista de precios global por defecto, `null` si no tiene ninguno).

**`InventoryDTO`**: `id`, `productId`, `branchId`, `quantityOnHand`, `averageUnitCost`, `minimumStock`, `updatedAt`.

**`InventoryMovementDTO`**: `id`, `productId`, `branchId`, `direction`, `reason`, `quantity`, `unitOfMeasureId`, `responsibleUserId`, `occurredAt`, `notes`, `source` (objeto: `{ "type": "PURCHASE_ORDER" | "SALE" | "TRANSFER" | null, "id": "..." }`, nunca las tres FKs sueltas).

**`SaleDTO`**: `id`, `saleNumber`, `branchId`, `soldByUserId`, `soldByUserName` (BR-054, resuelto por el backend — `GET /users` es ADMIN-only), `status`, `saleDate`, `items[]` (`id` — BR-052, necesario para devolver la línea —, `productId`, `quantity`, `unitOfMeasureId`, `unitPrice`, `discountPercentage`, `lineTotal`, `quantityReturned`, `pending`), `subtotal`, `discountTotal`, `total`.

**`PurchaseOrderDTO`**: `id`, `orderNumber`, `supplierId`, `branchId`, `status`, `orderDate`, `paymentTerm`, `items[]` (`id`, `productId`, `quantityOrdered`, `quantityReceived`, `unitPrice`, `discountPercentage`).

**`TransferDTO`**: `id`, `transferNumber`, `status`, `originBranchId`, `destinationBranchId`, `routeId`, `urgency`, `carrierName`, `estimatedArrivalDate`, `dispatchedAt`, `receivedAt`, `items[]` (`id`, `productId`, `quantityRequested`, `quantityApproved`, `quantityShipped`, `quantityReceived`, `quantityMissing`, `discrepancyTreatment`, `followUpTransferId`).

**`ErrorDTO`**: ver sección 3.

---

## 9. Ejemplos de request/response de los flujos críticos

### 9.1 Registrar venta (flujo A)

`POST /api/v1/sales`

```
Headers:
  Authorization: Bearer eyJhbGciOi...
  Idempotency-Key: 6f0a2e2a-7e34-4c1a-9f0d-8f6a2c1b0e11
```

```json
{
  "branchId": "4",
  "priceListId": "2",
  "items": [
    { "productId": "101", "quantity": 5, "unitOfMeasureId": "1", "discountPercentage": 0 },
    { "productId": "205", "quantity": 2, "unitOfMeasureId": "1", "discountPercentage": 10 }
  ]
}
```

**201 Created**

```json
{
  "id": "9012",
  "saleNumber": "V-2026-000912",
  "branchId": "4",
  "soldByUserId": "77",
  "status": "CONFIRMED",
  "saleDate": "2026-08-26T15:04:00Z",
  "items": [
    { "productId": "101", "quantity": 5, "unitOfMeasureId": "1", "unitPrice": 12.50, "discountPercentage": 0, "lineTotal": 62.50 },
    { "productId": "205", "quantity": 2, "unitOfMeasureId": "1", "unitPrice": 40.00, "discountPercentage": 10, "lineTotal": 72.00 }
  ],
  "subtotal": 142.50,
  "discountTotal": 8.00,
  "total": 134.50,
  "createdAt": "2026-08-26T15:04:00Z"
}
```

**422 (stock insuficiente en la primera línea):**

```json
{
  "error": {
    "code": "STOCK_INSUFICIENTE",
    "message": "No hay stock suficiente del producto 101 en la sucursal 4. Disponible: 3, solicitado: 5.",
    "status": 422,
    "requestId": "b3c1e4b2-9a11-4f3a-8e21-1f7c9a0d55aa",
    "details": [{ "field": "items[0].quantity", "issue": "excede el stock disponible" }]
  }
}
```

### 9.2 Recepción de compra (flujo B)

`POST /api/v1/purchase-orders/300/receipts`

```
Headers:
  Idempotency-Key: 2b6e1a10-...
```

```json
{
  "items": [
    { "purchaseOrderItemId": "551", "quantityReceived": 50, "unitPrice": 16.00 }
  ]
}
```

**200 OK**

```json
{
  "purchaseOrderId": "300",
  "status": "PARTIALLY_RECEIVED",
  "items": [
    { "purchaseOrderItemId": "551", "quantityOrdered": 100, "quantityReceived": 50, "pending": 50 }
  ],
  "inventoryUpdates": [
    { "productId": "101", "branchId": "4", "quantityOnHand": 150, "averageUnitCost": 12.00 }
  ]
}
```

### 9.3 Solicitud de transferencia (flujo C1)

`POST /api/v1/transfers`

```json
{
  "originBranchId": "2",
  "destinationBranchId": "4",
  "urgency": true,
  "items": [{ "productId": "101", "quantityRequested": 30 }]
}
```

**201 Created**

```json
{
  "id": "780",
  "transferNumber": "T-2026-000780",
  "status": "REQUESTED",
  "originBranchId": "2",
  "destinationBranchId": "4",
  "urgency": true,
  "requestedByUserId": "77",
  "requestedAt": "2026-08-26T09:00:00Z",
  "items": [{ "id": "1500", "productId": "101", "quantityRequested": 30 }]
}
```

### 9.4 Aprobación (flujo C2)

`POST /api/v1/transfers/780/approve`

```json
{ "items": [{ "transferItemId": "1500", "quantityApproved": 20 }] }
```

**200 OK**

```json
{
  "id": "780",
  "status": "APPROVED",
  "items": [{ "id": "1500", "quantityRequested": 30, "quantityApproved": 20 }]
}
```

**409 (doble aprobación, ver `docs/CRITICAL_FLOWS.md` escenario 3.3):**

```json
{
  "error": { "code": "TRANSICION_INVALIDA", "message": "La transferencia ya no está en estado REQUESTED.", "status": 409, "requestId": "..." }
}
```

### 9.5 Despacho (flujo D)

`POST /api/v1/transfers/780/dispatch`

```json
{
  "carrierName": "Transportes XYZ",
  "estimatedArrivalDate": "2026-08-28",
  "items": [{ "transferItemId": "1500", "quantityShipped": 20 }]
}
```

**200 OK**

```json
{
  "id": "780",
  "status": "IN_TRANSIT",
  "carrierName": "Transportes XYZ",
  "estimatedArrivalDate": "2026-08-28",
  "dispatchedAt": "2026-08-26T11:30:00Z",
  "items": [{ "id": "1500", "quantityApproved": 20, "quantityShipped": 20 }]
}
```

**422 (el stock se consumió por una venta concurrente entre aprobar y despachar — escenario 3.2):**

```json
{
  "error": { "code": "STOCK_INSUFICIENTE", "message": "El stock disponible (14) es menor a la cantidad aprobada (20).", "status": 422, "requestId": "..." }
}
```

### 9.6 Recepción completa (flujo E)

`POST /api/v1/transfers/780/receive`

```json
{ "items": [{ "transferItemId": "1500", "quantityReceived": 20 }] }
```

**200 OK**

```json
{
  "id": "780",
  "status": "RECEIVED_COMPLETE",
  "receivedAt": "2026-08-28T14:10:00Z",
  "items": [{ "id": "1500", "quantityShipped": 20, "quantityReceived": 20, "quantityMissing": null }]
}
```

### 9.7 Recepción parcial y tratamiento del faltante (flujo F)

`POST /api/v1/transfers/780/receive`

```json
{ "items": [{ "transferItemId": "1500", "quantityReceived": 15 }] }
```

**200 OK**

```json
{
  "id": "780",
  "status": "RECEIVED_PARTIAL",
  "items": [{ "id": "1500", "quantityShipped": 20, "quantityReceived": 15, "quantityMissing": 5, "discrepancyTreatment": null }]
}
```

`POST /api/v1/transfers/780/items/1500/discrepancy-treatment`

```json
{ "treatment": "REENVIO", "notes": "Reenviar el faltante en el próximo despacho disponible" }
```

**200 OK**

```json
{
  "transferItemId": "1500",
  "discrepancyTreatment": "REENVIO",
  "followUpTransferId": "781",
  "transferStatus": "CLOSED"
}
```

---

## 10. Alcance de la especificación OpenAPI adjunta

`docs/openapi.yaml` (misma carpeta) contiene una especificación OpenAPI 3.0 **inicial y representativa**, no exhaustiva: cubre autenticación, el esquema de error uniforme, y los recursos/acciones de mayor riesgo de negocio (inventario, ajustes, compras + recepción, ventas, transferencias + todas sus acciones de transición, alertas y un endpoint de dashboard de muestra). Los recursos de gestión puramente CRUD que siguen el mismo patrón ya mostrado (`users`, `suppliers`, `price-lists`, `routes`) no se repiten uno por uno en el YAML para mantenerlo manejable — se documentan aquí en la sección 7 y se completan en la especificación cuando arranque la implementación de cada módulo.

---

**Documentos relacionados:** `docs/USE_CASES.md`, `docs/BUSINESS_RULES.md`, `docs/DOMAIN_MODEL.md`, `docs/CRITICAL_FLOWS.md`, `docs/ARCHITECTURE.md`.
