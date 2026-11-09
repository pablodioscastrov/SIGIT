# ADR-011 — Pantallas de Producto e Inventario

**Estado:** Accepted

**Relación con ADR-010:** lo aplica, no lo modifica. ADR-010 fijó la arquitectura base (rutas, sesión, cliente API, caché, estados reutilizables); este ADR registra las decisiones que solo aparecieron al construir las **primeras pantallas de negocio** sobre ella.

## Contexto

Productos e Inventario son las dos primeras pantallas con escritura real. Traen preguntas que el esqueleto no tenía que responder: qué acciones ofrecer a cada rol, cómo mostrar un error de negocio del backend, cuándo pedir confirmación, y qué hacer cuando la respuesta de la API no trae todo lo que la pantalla necesita mostrar.

## Decisión

### 1. La interfaz ofrece; el backend autoriza

`permissions.can` es un **espejo** de la tabla de autorización de `docs/API_DESIGN.md` (sección 6), no una regla propia:

| Acción | Quién | Dónde se decide de verdad |
|---|---|---|
| Leer productos e inventario | cualquier rol autenticado | backend |
| Crear/editar/(des)activar producto y sus unidades | `OPERATOR`, `ADMIN` | `@PreAuthorize` |
| Crear unidad de medida global | solo `ADMIN` | `@PreAuthorize` |
| Ajuste manual de inventario | `OPERATOR`, `ADMIN` | `@PreAuthorize` + `requireBranchAccess` |

Ocultar el botón evita ofrecer algo que devolvería `403`; no protege nada. Si alguien llama a la API igualmente, el `403` se muestra como falta de permisos **sin cerrar la sesión**.

**Caso que conviene subrayar:** la lectura de inventario es abierta a **cualquier** sucursal (RF-002/RF-003), así que el selector de sucursal está disponible para todos los roles. Lo que se acota es la escritura: el botón "Ajustar" solo aparece sobre la sucursal propia, salvo `ADMIN`. Leer y escribir tienen alcances distintos y la pantalla los trata como tales.

### 2. Los errores del backend se muestran, no se traducen ni se esconden

Tres niveles, en este orden:

1. `details[]` del sobre uniforme → error **junto al campo** correspondiente.
2. Códigos de negocio que apuntan inequívocamente a un campo (`SKU_YA_EXISTE` → `sku`, `STOCK_INSUFICIENTE` → `quantity`) → también junto al campo.
3. Lo demás → mensaje del servidor **tal cual**, con su `code` y su `requestId` visibles para poder correlacionar con los logs.

No se reescribe el texto del servidor con uno "más amable": el mensaje del backend es el que describe lo que realmente pasó. Solo hay traducción para los códigos genéricos que un usuario no puede interpretar (`ERROR_DE_RED`, `ERROR_INTERNO`).

**Corolario descubierto probando en vivo:** un error mostrado tiene que *dejar* de mostrarse cuando el usuario corrige los datos. Volver del resumen de confirmación al formulario descarta el error del intento anterior; si no, un resumen ya corregido aparece acompañado del fallo viejo y parece haber fallado otra vez.

### 3. Confirmación explícita, con el efecto escrito

Dos acciones la piden: **desactivar un producto** y **registrar un ajuste manual**. En ambos casos el diálogo dice qué va a ocurrir en concreto ("se sumará 10 unidades de X en la sucursal Y; el movimiento queda a tu nombre y no puede editarse ni borrarse"; "no se elimina: su historial y su inventario se conservan") en vez de un "¿estás seguro?" que no informa de nada.

El motivo (`notes`) del ajuste es obligatorio también en el cliente —el backend ya lo exige con `NOTES_REQUERIDO`— porque es lo que hace auditable el movimiento.

### 4. La validación del cliente es solo de forma

Obligatorio, tipo, cantidad mayor que cero. **Nada de semántica de negocio**: si el retiro deja el stock en negativo lo decide el backend (`422 STOCK_INSUFICIENTE`) y la pantalla se limita a mostrarlo. Duplicar la regla aquí crearía dos verdades que se desincronizan en la primera modificación. Se mantiene la estrategia sin librería de formularios de ADR-010: estos formularios siguen siendo planos, sin líneas dinámicas.

### 5. El estado de reabastecimiento es presentación, no una regla nueva

El indicador "Reabastecer" reproduce **exactamente** el mismo umbral que el backend ya aplica en el filtro `lowStock` (`quantityOnHand <= minimumStock`, BR-010). Se calcula en el cliente solo porque `InventoryResponse` no trae una bandera por fila; el **filtrado** lo sigue haciendo el servidor. Si el umbral cambiara en el backend, este cálculo debe cambiar con él — son la misma regla, no dos.

`minimum_stock` se muestra pero **no se puede editar**: no existe endpoint de escritura, y no se inventa uno desde el frontend.

### 6. Revalidar contra la API, nunca parchear la caché a mano

Después de cada mutación se invalida el prefijo del recurso (`products`, `inventory`, `inventory-movements`) y TanStack Query vuelve a consultar. No se escribe el resultado optimista en la caché: la API es la fuente de verdad, y un ajuste puede tener efectos que la respuesta no describe. Es además el mismo enganche que usará el canal SSE (ADR-009): la señal invalidará estas claves.

### 7. Los filtros del historial viven en la URL

`/inventario/movimientos?branchId=…&productId=…` hace que "ver los movimientos de esta fila" sea un enlace, y que el estado sea compartible y recargable. Los filtros del listado de inventario, que no se enlazan desde ningún sitio, se quedan en estado local.

## Alternativas consideradas

- **Añadir `sku`/`name` a `InventoryResponse` e `InventoryMovementResponse`:** es la solución correcta al problema del punto siguiente, pero cambia el contrato REST y eso requiere aprobación previa. No se hizo unilateralmente.
- **Una petición por producto para resolver su nombre:** N+1 peticiones por página. Descartada.
- **Confirmación mediante `window.confirm`:** descartada por accesibilidad y porque no permite mostrar el resumen del efecto ni el error del servidor dentro del mismo flujo.
- **Editar `minimum_stock` desde la pantalla:** imposible sin endpoint, y crearlo excede el alcance de esta fase.

## Consecuencias positivas

- Añadir una pantalla de negocio es ahora mecánico y consistente: endpoint tipado, `useQuery`, `AsyncBoundary`, diálogo con `toFormErrors`, invalidación por prefijo.
- Un error de negocio nunca se pierde: se ve el mensaje, el código y la referencia para buscarlo en los logs del servidor.
- Las diferencias de alcance entre lectura y escritura quedan explícitas en la interfaz, no implícitas.

## Consecuencias negativas / trade-offs

- **Resolución de productos en el cliente:** las pantallas de inventario cargan `GET /products?size=200` para traducir `productId` a SKU y nombre. Funciona hoy y evita el N+1, pero **se degrada cuando el catálogo supere ese tamaño**: los productos fuera de esa página se mostrarán como `Producto <id>`. Es una limitación conocida, no un descuido; la corrección es el cambio de contrato del primer punto de "Alternativas".
- **Doble envío del ajuste solo mitigado en el cliente:** `POST /inventory/adjustments` no acepta `Idempotency-Key`. El botón se deshabilita mientras la petición está en curso y hay un paso de confirmación, pero la garantía real exige el cambio en el backend (limitación ya registrada en `docs/STATUS.md`).
- **Búsqueda con `debounce` de 300 ms**, no instantánea, para no lanzar una petición por pulsación.
- **Acabado visual mínimo**, en línea con ADR-010.

## Criterios para reconsiderarla

- Catálogo grande → pedir la ampliación de los DTOs de inventario y eliminar el índice del cliente.
- `Idempotency-Key` en ajustes → enviarlo desde `createAdjustment`, donde ya está previsto el hueco.
- Endpoint de escritura de `minimum_stock` → habilitar su edición en la pantalla.
- Formularios con líneas dinámicas (compra, venta, transferencia) → sigue vigente el criterio de ADR-010 para adoptar `react-hook-form` + `zod`.
