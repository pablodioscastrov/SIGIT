# ADR-012 — Pantallas de Compras y Ventas

**Estado:** Accepted

**Relación con ADR-010/ADR-011:** los aplica, no los reabre. Este ADR registra las decisiones que solo aparecieron al construir las pantallas de **Compras** y **Ventas** — los dos primeros flujos con líneas dinámicas y con una operación de escritura de un solo uso protegida por `Idempotency-Key`.

## Contexto

Compras y ventas comparten una forma: cabecera + líneas de producto, con un monto que se calcula a partir de precio/cantidad/descuento. A diferencia de Productos e Inventario (ADR-011), aquí aparecen tres problemas nuevos: formularios con un número variable de líneas, una operación de escritura que el backend exige proteger con `Idempotency-Key`, y — solo en ventas — un precio que no se teclea sino que se resuelve desde una lista de precios.

## Decisión

### 1. Líneas dinámicas siguen sin justificar `react-hook-form`/`zod`

ADR-010 fijó el criterio explícito para adoptarlos: "formularios con líneas dinámicas (venta, compra, transferencia)". Llegado el momento, se evaluó de nuevo y se decidió **no** adoptarlos todavía: cada línea de compra o de venta es un puñado de campos (producto, cantidad, precio u descuento) sin validación cruzada entre ellas más allá de "no repetir producto", que un `Set` en el validador del formulario resuelve en una línea. Un array en `useState` con una función de componente por fila (`PurchaseLineRow`/`SaleLineRow`, necesaria de todos modos porque cada fila consulta sus propias unidades alternativas) sigue siendo más simple que introducir dos dependencias nuevas para dos formularios.

El criterio no se abandona: transferencias trae recepción parcial con faltantes por línea, que sí es validación cruzada real. Se reevalúa ahí, no antes.

### 2. `Idempotency-Key`: una clave por intento, no por sesión ni por click

`POST /purchase-orders`, `POST /purchase-orders/{id}/receipts` y `POST /sales` exigen el encabezado (BR-017). La clave se genera una vez al montar la pantalla o el diálogo de confirmación (`useIdempotencyKey`, `crypto.randomUUID()`) y se **conserva** a través de reintentos del mismo envío — un fallo de red, un doble clic — para que el backend los reconozca como el mismo pedido. Se **renueva** solo cuando la operación se completa o el usuario cierra el formulario, es decir, al iniciar una operación genuinamente nueva.

Esto, sumado a deshabilitar el botón de confirmación mientras `mutation.isPending`, es la defensa de doble envío pedida: dos clics seguidos nunca generan dos peticiones con claves distintas.

### 3. El precio de venta nunca se teclea; el descuento sí

`CreateSaleItemRequest` no lleva `unitPrice` — el backend lo resuelve desde `Price` (BR-030). La pantalla de venta refleja esa asimetría: el precio se muestra de solo lectura junto a cada línea, tomado de `GET /price-lists/{id}/prices`, y el único valor de negocio que el usuario introduce es el descuento, dentro del rango que ya valida el backend (BR-019).

### 4. Selección explícita de lista de precios — y por qué no rompe "no repliques reglas de negocio"

`SaleService.resolvePriceList` (BR-030) prioriza una lista activa de la sucursal sobre la global cuando el request omite `priceListId`. Para poder previsualizar un precio y un total *antes* de confirmar, la pantalla necesita saber qué lista aplica — y la única forma de saberlo con certeza es no dejárselo adivinar al servidor.

La solución: el selector de lista de precios se **preselecciona** con el mismo orden de prioridad (sucursal propia primero, luego global) para que el caso común no requiera ninguna acción extra, pero el usuario puede cambiarla, y sea cual sea la que quede elegida, **siempre se envía explícita** en `priceListId`. Nunca se omite para que el backend "decida". Esto no es una regla de negocio nueva en el cliente — es un valor por defecto de un control de formulario explícito — precisamente porque lo que se envía es siempre lo que está seleccionado, así que no hay forma de que la previsualización diverja de lo que se cobra.

### 5. La previsualización es aritmética de presentación, nunca la fuente de verdad

El total de cada línea y el total de la venta se calculan en el cliente con `number` de JavaScript, para mostrarse antes de confirmar. Se etiqueta explícitamente "estimado" y el comprobante posterior a confirmar (`GET /sales/{id}`) siempre reemplaza esa cifra por el total que calculó el servidor en `BigDecimal` con `HALF_UP`. La previsualización nunca decide nada ni se envía al backend — es la misma distinción que ADR-011 aplicó al estado de reabastecimiento de inventario.

### 6. Recepción de compra: revalidar el detalle, nunca fusionar la respuesta a mano

`PurchaseReceiptResponse` no devuelve la orden completa, solo el resumen de lo recibido. Tras una recepción exitosa se invalida `purchaseOrder(id)` y se relee de `GET /purchase-orders/{id}` — la única fuente de verdad de `pending`/`quantityReceived`/estado. Se descartó deliberadamente "parchear" la fila localmente con la respuesta del recibo: eso duplicaría en el cliente el cálculo de `pending` y de si la orden ya quedó `RECEIVED`, que el backend ya hace.

### 7. Al fallar por stock o por cantidades, se refresca lo que pudo quedar obsoleto

Un `422 STOCK_INSUFICIENTE` en una venta o un `422 CANTIDAD_RECEPCION_EXCEDE_ORDENADO` en una recepción normalmente significan que otra operación cambió el dato entre que la pantalla lo cargó y que el usuario confirmó. Ante ese error se invalidan las cachés relevantes (`inventory`/`inventory-movements` en la venta; el propio `purchaseOrder(id)` en la recepción) para que la siguiente consulta —o el siguiente intento— parta de datos frescos, sin dejar que la pantalla siga mostrando una fotografía vieja. El formulario en sí **no se limpia**: lo que el usuario ya tecleó se conserva para que solo tenga que corregir el número que falló.

### 8. Crear una orden o una venta es una ruta protegida por rol, no solo un botón oculto

A diferencia de Productos/Inventario —donde la misma pantalla mezcla lectura abierta y escritura restringida—, `/compras/nueva` y `/ventas/nueva` son rutas dedicadas exclusivamente a escribir. Dejar esa ruta accesible por URL a un rol sin permiso de escritura significaría mostrarle un formulario aparentemente operativo que solo fallaría con `403` al final. Se decidió envolverlas en `RequireRole roles={["OPERATOR", "ADMIN"]}` — la misma guarda de navegación que ya protegía `/usuarios` para `ADMIN` —, consistente con el principio de ADR-010 de que ocultar un botón nunca es la única protección, pero tampoco una excusa para omitir la guarda de navegación cuando la ruta entera es de escritura.

## Alternativas consideradas

- **Enviar la venta sin `priceListId` y dejar que el backend resuelva:** impediría cualquier previsualización de precio o total antes de confirmar, incumpliendo el requisito de "resumen y total" antes de la confirmación.
- **Fusionar `PurchaseReceiptResponse` en el estado local de la orden:** rechazada por el punto 6 — duplicaría cálculo de servidor en el cliente.
- **`react-hook-form` + `zod` ya en esta fase:** rechazada por el punto 1; el criterio de ADR-010 se reevalúa, no se aplica por inercia.

## Consecuencias positivas

- El patrón de "línea dinámica con su propio `useQuery`" es ahora replicable tal cual en transferencias.
- `useIdempotencyKey` es un hook de una responsabilidad que cualquier operación crítica futura (transferencias) puede reutilizar sin repetir la lógica de generación/renovación.
- Ambas pantallas demuestran que "no calcules disponibilidad en el cliente" y "previsualiza para UX" no son objetivos en tensión: la previsualización vive enteramente separada de la validación real.

## Consecuencias negativas / trade-offs

- **La previsualización de venta puede diferir en el último dígito** del total real por redondeo (`number` vs `BigDecimal` `HALF_UP`) — aceptable porque nunca se persiste ni se envía, y el comprobante posterior siempre muestra la cifra autoritativa.
- **Selección de lista de precios sin gestión propia:** esta fase no incluye una pantalla para crear listas de precios ni fijar precios (`ADMIN`-only, fuera del alcance pedido); si no existe ninguna lista activa aplicable a la sucursal, la venta se bloquea con un mensaje claro, igual que lo haría el backend.
- **Sin idempotencia real para la recepción de compra a nivel de exhibición de errores por línea:** el backend no incluye qué línea causó un `CANTIDAD_INVALIDA`/`CANTIDAD_RECEPCION_EXCEDE_ORDENADO` cuando hay varias, así que el error se muestra general y no adosado a una fila — limitación del contrato, no de la pantalla.

## Criterios para reconsiderarla

- Al llegar a transferencias: si la recepción parcial con tratamiento de faltantes exige validación cruzada real entre líneas, adoptar `react-hook-form` + `zod` ahí.
- Si el backend empieza a incluir el índice de línea en `details[]` de los errores de compra/venta, mostrar el error adosado a la fila en vez de general.
- Si aparece una pantalla de gestión de listas de precios, enlazarla desde el selector de "Nueva venta" en vez de solo indicar su ausencia.
