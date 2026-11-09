# Matriz de Trazabilidad de Requisitos

**Fuente de origen:** `private/Prueba Tecnica Inventario.pdf` — "Prueba Técnica – Sistema de Inventario Multi-Sucursal", OptiPlant Consultores.

**Fecha de elaboración:** 2026-08-26

**Metodología:** cada fila se extrae de una afirmación textual del documento fuente y se referencia por su sección de origen. Las decisiones técnicas propias del proyecto (stack, arquitectura interna, mecanismo SSE, etc.) **no** se registran aquí como requisitos — viven en `DECISIONS.md`. Cuando un requisito del documento se satisface mediante una decisión nuestra, se indica en "Evidencia esperada", sin mezclar ambas categorías.

**Convenciones de estado:** todos los ítems inician en `Pendiente`. Los valores posibles a futuro son `Pendiente`, `En progreso`, `Implementado`, `Verificado`.

**Prefijos de ID:**

| Prefijo | Categoría |
|---|---|
| RF | A. Requisito funcional |
| RNF | B. Requisito no funcional |
| RT | C. Regla técnica obligatoria |
| ENT | D. Entregable |
| REC | E. Recomendación / opcional |
| CEI | F. Criterio de evaluación implícito |

---

## Índice rápido

| ID | Título | Prioridad | Módulo responsable | Estado |
|---|---|---|---|---|
| RF-001 | Autonomía operativa por sucursal | Obligatoria | branches | Pendiente |
| RF-002 | Visibilidad compartida de inventario en tiempo real/near-real-time | Obligatoria | inventory | Pendiente |
| RF-003 | Consulta de inventario de otras sucursales | Obligatoria | inventory | Pendiente |
| RF-004 | Solicitud y recepción de transferencias entre nodos | Obligatoria | transfers | Pendiente |
| RF-005 | CRUD completo de productos | Obligatoria | products | Pendiente |
| RF-006 | Visualizar catálogo de la sucursal propia | Obligatoria | products | Pendiente |
| RF-007 | Registrar ingreso de productos | Obligatoria | inventory | Pendiente |
| RF-008 | Registrar retiro de productos | Obligatoria | inventory | Pendiente |
| RF-009 | Trazabilidad completa de movimientos | Obligatoria | inventory | Pendiente |
| RF-010 | Control de stock mínimo y alertas de reabastecimiento | Obligatoria | inventory | Pendiente |
| RF-011 | Múltiples unidades de medida por producto | Obligatoria | products | Pendiente |
| RF-012 | Órdenes de compra a proveedores | Obligatoria | purchases | Pendiente |
| RF-013 | Condiciones de compra (precio, descuento, plazo de pago) | Obligatoria | purchases | Pendiente |
| RF-014 | Actualización automática de inventario al recibir compra | Obligatoria | purchases | Pendiente |
| RF-015 | Histórico de compras por proveedor y producto | Obligatoria | purchases | Pendiente |
| RF-016 | Costo promedio ponderado | Obligatoria | purchases | Pendiente |
| RF-017 | Registrar venta (producto, cantidad, precio) | Obligatoria | sales | Pendiente |
| RF-018 | Asociar venta a sucursal, fecha, responsable | Obligatoria | sales | Pendiente |
| RF-019 | Validar stock antes de confirmar venta | Obligatoria | sales | Pendiente |
| RF-020 | Descuentos y listas de precios | Obligatoria | sales | Pendiente |
| RF-021 | Comprobante/registro de venta consultable | Obligatoria | sales | Pendiente |
| RF-022 | Solicitud de transferencia | Obligatoria | transfers | Pendiente |
| RF-023 | Preparación de envío (disponibilidad, ajuste de cantidad) | Obligatoria | transfers | Pendiente |
| RF-024 | Registro de envío (fecha estimada, transportista) | Obligatoria | transfers / logistics | Pendiente |
| RF-025 | Recepción completa | Obligatoria | transfers | Pendiente |
| RF-026 | Recepción parcial y tratamiento de faltantes | Obligatoria | transfers | Pendiente |
| RF-027 | Tiempos estimados vs. reales de entrega | Obligatoria | logistics | Pendiente |
| RF-028 | Clasificación de rutas | Obligatoria | logistics | Pendiente |
| RF-029 | Estado de transferencia en curso | Obligatoria | logistics / transfers | Pendiente |
| RF-030 | Reportes de cumplimiento logístico | Obligatoria | logistics / reports | Pendiente |
| RF-031 | Dashboard: ventas del mes vs. anteriores | Obligatoria | dashboard | Pendiente |
| RF-032 | Dashboard: rotación e inventario alta/baja demanda | Obligatoria | dashboard | Pendiente |
| RF-033 | Dashboard: transferencias activas e impacto | Obligatoria | dashboard | Pendiente |
| RF-034 | Dashboard: indicadores de reabastecimiento | Obligatoria | dashboard | Pendiente |
| RF-035 | Dashboard: comparativa entre sucursales | Obligatoria | dashboard | Pendiente |
| RF-036 | Funcionalidad adicional de valor real | Obligatoria | (según elección) | Pendiente |
| RF-037 | Rol Administrador general | Obligatoria | auth / users | Pendiente |
| RF-038 | Rol Gerente de sucursal | Obligatoria | auth / users | Pendiente |
| RF-039 | Rol Operador de inventario | Obligatoria | auth / users | Pendiente |
| RF-040 | Actor opcional Sistema externo | Opcional | auth | Pendiente |
| RNF-001 | Sincronización near-real-time entre sucursales | Obligatoria | inventory | Pendiente |
| RNF-002 | Rendimiento documentado | Obligatoria | (transversal) | Pendiente |
| RNF-003 | Seguridad: autenticación y autorización | Obligatoria | auth | Pendiente |
| RNF-004 | Escalabilidad documentada | Obligatoria | (transversal) | Pendiente |
| RNF-005 | Usabilidad | Obligatoria | dashboard / (transversal) | Pendiente |
| RNF-006 | Consistencia de datos entre sucursales | Obligatoria | inventory | Pendiente |
| RT-001 | Separación en 3 capas | Obligatoria | (transversal) | Pendiente |
| RT-002 | Comunicación exclusiva por API | Obligatoria | (transversal) | Pendiente |
| RT-003 | Contenedorización total con Docker Compose | Obligatoria | (transversal) | Pendiente |
| RT-004 | Stack tecnológico libre y justificado | Obligatoria | (transversal) | Pendiente |
| RT-005 | Justificación documentada de decisiones técnicas clave | Obligatoria | (transversal) | Pendiente |
| ENT-001 | Repositorio GitHub público | Obligatoria | — | Pendiente |
| ENT-002 | Código fuente limpio y organizado | Obligatoria | — | Pendiente |
| ENT-003 | docker-compose.yml funcional | Obligatoria | — | Pendiente |
| ENT-004 | README completo | Obligatoria | — | Pendiente |
| ENT-005 | Documento de requerimientos (RF/RNF/restricciones/supuestos) | Obligatoria | — | Pendiente |
| ENT-006 | Documentación de casos de uso y actores | Obligatoria | — | Pendiente |
| ENT-007 | Diagrama de casos de uso | Obligatoria | — | Pendiente |
| ENT-008 | Diagrama de actividades/flujo | Obligatoria | — | Pendiente |
| ENT-009 | Diagrama de arquitectura | Obligatoria | — | Pendiente |
| ENT-010 | Diagrama entidad-relación | Obligatoria | — | Pendiente |
| ENT-011 | Sección de uso de IA con evidencia | Obligatoria | — | Pendiente |
| REC-001 | Historias de usuario | Recomendada | — | Pendiente |
| REC-002 | Ideas adicionales no elegidas | Opcional | — | Pendiente |
| REC-003 | Actor "Sistema externo" (integración ERP/POS) | Opcional | auth | Pendiente |
| REC-004 | Herramientas sugeridas de diagramado | Opcional | — | Pendiente |
| REC-005 | Orden de trabajo sugerido | Recomendada | — | Pendiente |
| CEI-001 | Evaluación integral más allá del funcionamiento | Implícito | — | Pendiente |
| CEI-002 | Principio "¿por qué se hizo así?" transversal | Implícito | — | Pendiente |
| CEI-003 | Justificación exigida por libertad de stack | Implícito | — | Pendiente |
| CEI-004 | Claridad visual y relevancia del dashboard | Implícito | dashboard | Pendiente |
| CEI-005 | Evaluación crítica del uso de IA, no solo su presencia | Implícito | — | Pendiente |
| CEI-006 | Historial de commits representativo del proceso | Implícito | — | Pendiente |

---

## A. Requisitos funcionales

#### RF-001 — Autonomía operativa por sucursal
- **Descripción:** cada sucursal opera de forma completamente independiente en sus transacciones locales.
- **Sección origen:** 2.1 "Características por Sucursal".
- **Prioridad:** obligatoria.
- **Módulo responsable:** branches (transversal a inventory/sales/purchases).
- **Criterio de aceptación:** una operación local (venta, compra, ingreso, retiro) en la sucursal A se completa sin requerir aprobación ni disponibilidad de otra sucursal.
- **Evidencia esperada:** prueba funcional/integración que ejecuta una transacción local con las demás sucursales simuladas como no disponibles.
- **Dependencias:** RF-005 a RF-021 (módulos que se ejecutan localmente).
- **Estado:** Pendiente.

#### RF-002 — Visibilidad compartida de inventario en tiempo real/near-real-time
- **Descripción:** cada sucursal comparte información de inventario con las demás en tiempo real o near-real-time.
- **Sección origen:** 2.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** un cambio de stock en la sucursal A es visible para un usuario autorizado consultando desde la sucursal B dentro de una ventana de tiempo definida (ver RNF-001, pendiente de valor concreto).
- **Evidencia esperada:** demo o prueba automatizada midiendo el retraso entre el movimiento y su visibilidad remota.
- **Dependencias:** RNF-001 (define el umbral aceptable).
- **Estado:** Pendiente.

#### RF-003 — Consulta de inventario de otras sucursales
- **Descripción:** cualquier sucursal puede consultar el inventario de cualquier otra sucursal de la red.
- **Sección origen:** 2.1; 3.1 ("Consultar el inventario de cualquier otra sucursal de la red").
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** endpoint/consulta que retorna stock de una sucursal distinta a la del usuario autenticado, respetando permisos por rol.
- **Evidencia esperada:** prueba de API + captura de UI mostrando inventario cruzado.
- **Dependencias:** RF-037 a RF-039 (permisos por rol).
- **Estado:** Pendiente.

#### RF-004 — Solicitud y recepción de transferencias entre nodos
- **Descripción:** cada sucursal puede solicitar y recibir transferencias de producto entre nodos de la red.
- **Sección origen:** 2.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers.
- **Criterio de aceptación:** ver detalle en RF-022 a RF-026 (flujo completo).
- **Evidencia esperada:** ver RF-022 a RF-026.
- **Dependencias:** RF-022, RF-023, RF-024, RF-025, RF-026.
- **Estado:** Pendiente.

#### RF-005 — CRUD completo de productos
- **Descripción:** operaciones básicas de creación, consulta, actualización y eliminación de registros de producto.
- **Sección origen:** 3.1 "Gestión de Inventario (CRUD Completo)".
- **Prioridad:** obligatoria.
- **Módulo responsable:** products.
- **Criterio de aceptación:** existen endpoints/pantallas para crear, listar, editar y eliminar (o dar de baja) un producto, con validación de datos obligatorios.
- **Evidencia esperada:** pruebas de API para las 4 operaciones + pantalla CRUD funcional.
- **Dependencias:** RF-011 (unidades de medida es un atributo del producto).
- **Estado:** Pendiente.

#### RF-006 — Visualizar catálogo de la sucursal propia
- **Descripción:** visualizar el catálogo de productos disponibles en la sucursal propia.
- **Sección origen:** 3.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** products / inventory.
- **Criterio de aceptación:** listado filtrado por la sucursal del usuario autenticado, con stock actual visible.
- **Evidencia esperada:** captura de pantalla + prueba de API con filtro por sucursal.
- **Dependencias:** RF-005.
- **Estado:** Pendiente.

#### RF-007 — Registrar ingreso de productos
- **Descripción:** registrar el ingreso de productos (compras, devoluciones, ajustes de inventario).
- **Sección origen:** 3.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** un ingreso incrementa el stock de la sucursal correspondiente y queda registrado como movimiento auditable (ver RF-009).
- **Evidencia esperada:** prueba de integración: ingreso → stock actualizado → movimiento persistido.
- **Dependencias:** RF-009, RF-014 (compras), RF-025 (transferencias).
- **Estado:** Pendiente.

#### RF-008 — Registrar retiro de productos
- **Descripción:** registrar el retiro de productos (ventas, mermas, ajustes de inventario).
- **Sección origen:** 3.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** un retiro decrementa el stock de la sucursal correspondiente sin permitir stock negativo (salvo ajuste explícito autorizado) y queda registrado como movimiento auditable.
- **Evidencia esperada:** prueba de integración: retiro → stock actualizado → movimiento persistido; prueba de rechazo si no hay stock suficiente.
- **Dependencias:** RF-009, RF-019 (ventas).
- **Estado:** Pendiente.

#### RF-009 — Trazabilidad completa de movimientos
- **Descripción:** cada ingreso o retiro debe quedar registrado con fecha, responsable, motivo y cantidad, garantizando un historial auditable.
- **Sección origen:** 3.1, recuadro "Importante".
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** todo movimiento persiste como mínimo: fecha/hora, usuario responsable, motivo, cantidad y producto/sucursal afectados; el historial es consultable y no editable retroactivamente.
- **Evidencia esperada:** consulta de historial de movimientos por producto/sucursal con los 4 campos visibles.
- **Dependencias:** RF-007, RF-008.
- **Estado:** Pendiente.

#### RF-010 — Control de stock mínimo y alertas de reabastecimiento
- **Descripción:** controlar stock mínimo y generar alertas de reabastecimiento.
- **Sección origen:** 3.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory.
- **Criterio de aceptación:** cada producto tiene un stock mínimo configurable; al llegar o caer por debajo de ese umbral se genera una alerta visible para usuarios autorizados.
- **Evidencia esperada:** prueba que fuerza el stock por debajo del mínimo y verifica la alerta generada.
- **Dependencias:** RF-036 (la funcionalidad adicional de alertas inteligentes, si se elige, extiende este requisito base).
- **Estado:** Pendiente.

#### RF-011 — Múltiples unidades de medida por producto
- **Descripción:** gestionar múltiples unidades de medida por producto.
- **Sección origen:** 3.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** products.
- **Criterio de aceptación:** un producto puede definirse/consultarse en más de una unidad de medida (p. ej. caja/unidad) con conversión consistente en movimientos, compras y ventas.
- **Evidencia esperada:** caso de prueba con conversión de unidades en un movimiento de inventario.
- **Dependencias:** RF-005, RF-007, RF-008.
- **Estado:** Pendiente.

#### RF-012 — Órdenes de compra a proveedores
- **Descripción:** crear y gestionar órdenes de compra a proveedores.
- **Sección origen:** 3.2 "Módulo de Compras".
- **Prioridad:** obligatoria.
- **Módulo responsable:** purchases.
- **Criterio de aceptación:** se puede crear una orden de compra asociada a un proveedor y productos, con estados de ciclo de vida (p. ej. creada, confirmada, recibida).
- **Evidencia esperada:** prueba de API de creación y consulta de orden de compra.
- **Dependencias:** RF-013, RF-014.
- **Estado:** Pendiente.

#### RF-013 — Condiciones de compra
- **Descripción:** registrar condiciones de la compra: precio unitario, descuentos, plazo de pago.
- **Sección origen:** 3.2.
- **Prioridad:** obligatoria.
- **Módulo responsable:** purchases.
- **Criterio de aceptación:** una orden de compra almacena precio unitario, descuento aplicado y plazo/condición de pago por línea o por orden.
- **Evidencia esperada:** registro de orden de compra con estos tres campos poblados y consultables.
- **Dependencias:** RF-012.
- **Estado:** Pendiente.

#### RF-014 — Actualización automática de inventario al recibir compra
- **Descripción:** actualizar automáticamente el inventario al confirmar la recepción de mercancía.
- **Sección origen:** 3.2; coincide con BR-003 en `BUSINESS_RULES.md`.
- **Prioridad:** obligatoria.
- **Módulo responsable:** purchases / inventory.
- **Criterio de aceptación:** al confirmar la recepción de una orden de compra, el stock de la sucursal receptora se incrementa automáticamente y se genera el movimiento de ingreso correspondiente (RF-007).
- **Evidencia esperada:** prueba de integración: confirmar recepción → stock actualizado → movimiento de ingreso creado.
- **Dependencias:** RF-007, RF-012.
- **Estado:** Pendiente.

#### RF-015 — Histórico de compras por proveedor y producto
- **Descripción:** llevar histórico de compras por proveedor y por producto.
- **Sección origen:** 3.2.
- **Prioridad:** obligatoria.
- **Módulo responsable:** purchases.
- **Criterio de aceptación:** es posible consultar todas las compras realizadas filtrando por proveedor o por producto.
- **Evidencia esperada:** consulta/reporte con ambos filtros funcionando.
- **Dependencias:** RF-012.
- **Estado:** Pendiente.

#### RF-016 — Costo promedio ponderado
- **Descripción:** calcular el costo promedio ponderado del inventario.
- **Sección origen:** 3.2; coincide con BR-004 en `BUSINESS_RULES.md`.
- **Prioridad:** obligatoria.
- **Módulo responsable:** purchases / inventory.
- **Criterio de aceptación:** tras cada recepción de compra con precio distinto al costo actual, el costo promedio ponderado del producto en esa sucursal se recalcula correctamente (validable con un caso numérico de referencia).
- **Evidencia esperada:** prueba unitaria con caso numérico documentado (entradas, precios, resultado esperado).
- **Dependencias:** RF-014.
- **Estado:** Pendiente.

#### RF-017 — Registrar venta
- **Descripción:** registrar transacciones de venta por producto, cantidad y precio.
- **Sección origen:** 3.3 "Módulo de Ventas".
- **Prioridad:** obligatoria.
- **Módulo responsable:** sales.
- **Criterio de aceptación:** se puede crear una venta con una o más líneas de producto, cantidad y precio, y consultarla posteriormente.
- **Evidencia esperada:** prueba de API de creación y consulta de venta.
- **Dependencias:** RF-019, RF-021.
- **Estado:** Pendiente.

#### RF-018 — Asociar venta a sucursal, fecha y responsable
- **Descripción:** asociar cada venta a una sucursal, fecha y responsable.
- **Sección origen:** 3.3.
- **Prioridad:** obligatoria.
- **Módulo responsable:** sales.
- **Criterio de aceptación:** toda venta persistida incluye sucursal, fecha/hora y usuario responsable, visibles en su consulta.
- **Evidencia esperada:** registro de venta con los tres campos poblados.
- **Dependencias:** RF-017.
- **Estado:** Pendiente.

#### RF-019 — Validar stock antes de confirmar venta
- **Descripción:** validar disponibilidad de stock antes de confirmar la venta.
- **Sección origen:** 3.3; coincide con BR-002 en `BUSINESS_RULES.md`.
- **Prioridad:** obligatoria.
- **Módulo responsable:** sales / inventory.
- **Criterio de aceptación:** una venta que exceda el stock disponible de la sucursal es rechazada antes de confirmarse, sin afectar el inventario.
- **Evidencia esperada:** prueba que intenta vender más stock del disponible y verifica el rechazo.
- **Dependencias:** RF-008, RF-017.
- **Estado:** Pendiente.

#### RF-020 — Descuentos y listas de precios
- **Descripción:** aplicar descuentos y gestionar diferentes listas de precios.
- **Sección origen:** 3.3.
- **Prioridad:** obligatoria.
- **Módulo responsable:** sales.
- **Criterio de aceptación:** una venta puede aplicar un descuento (por línea o total) y tomar el precio desde una lista de precios configurable, no un valor fijo hardcodeado.
- **Evidencia esperada:** prueba con dos listas de precios distintas aplicadas a la misma venta con resultados diferentes.
- **Dependencias:** RF-017.
- **Estado:** Pendiente.

#### RF-021 — Comprobante/registro de venta consultable
- **Descripción:** generar comprobantes o registros de venta para consulta posterior.
- **Sección origen:** 3.3.
- **Prioridad:** obligatoria.
- **Módulo responsable:** sales.
- **Criterio de aceptación:** cada venta confirmada genera un comprobante/registro recuperable por su identificador en cualquier momento posterior.
- **Evidencia esperada:** consulta de una venta histórica mostrando sus datos completos.
- **Dependencias:** RF-017.
- **Estado:** Pendiente.

#### RF-022 — Solicitud de transferencia
- **Descripción:** la sucursal destino (o un administrador) genera una solicitud formal indicando producto, cantidad y origen.
- **Sección origen:** 3.4, paso 1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers.
- **Criterio de aceptación:** se puede crear una solicitud de transferencia con producto, cantidad y sucursal origen, iniciada por un usuario con rol destino o administrador.
- **Evidencia esperada:** prueba de API de creación de solicitud + validación de rol autorizado.
- **Dependencias:** RF-037, RF-038, RF-039 (roles).
- **Estado:** Pendiente.

#### RF-023 — Preparación de envío
- **Descripción:** la sucursal origen revisa disponibilidad y confirma o ajusta la cantidad a enviar.
- **Sección origen:** 3.4, paso 2.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers.
- **Criterio de aceptación:** la sucursal origen puede ver la solicitud, consultar su disponibilidad y confirmar la cantidad original o una cantidad ajustada antes del despacho.
- **Evidencia esperada:** prueba de flujo: solicitud → ajuste de cantidad → confirmación.
- **Dependencias:** RF-022, RF-003 (consulta de disponibilidad).
- **Estado:** Pendiente.

#### RF-024 — Registro de envío
- **Descripción:** se registra el despacho con fecha estimada de llegada y transportista.
- **Sección origen:** 3.4, paso 3.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers / logistics.
- **Criterio de aceptación:** al despachar, la transferencia registra fecha estimada de llegada y datos del transportista, y cambia a estado "en tránsito".
- **Evidencia esperada:** prueba de API de despacho con ambos campos persistidos.
- **Dependencias:** RF-023, RF-027 (tiempos estimados).
- **Estado:** Pendiente.

#### RF-025 — Recepción completa
- **Descripción:** el inventario de la sucursal destino se actualiza automáticamente al confirmar recepción completa.
- **Sección origen:** 3.4, paso 4; coincide con BR-006 en `BUSINESS_RULES.md`.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers.
- **Criterio de aceptación:** al confirmar recepción completa, el stock de la sucursal destino aumenta en la cantidad despachada y se genera el movimiento de ingreso (RF-007).
- **Evidencia esperada:** prueba de integración: confirmación → stock destino actualizado → movimiento creado.
- **Dependencias:** RF-024, RF-007.
- **Estado:** Pendiente.

#### RF-026 — Recepción parcial y tratamiento de faltantes
- **Descripción:** se registra la diferencia (faltantes), se genera una alerta y se define el tratamiento (reenvío, ajuste o reclamación).
- **Sección origen:** 3.4, paso 5; coincide con BR-007, BR-008, BR-009 en `BUSINESS_RULES.md`.
- **Prioridad:** obligatoria.
- **Módulo responsable:** transfers.
- **Criterio de aceptación:** al confirmar una recepción con cantidad menor a la despachada, el sistema registra el faltante, genera una alerta, y permite asociar uno de los tres tratamientos definidos.
- **Evidencia esperada:** prueba de integración cubriendo recepción parcial → faltante registrado → alerta generada → tratamiento asignado.
- **Dependencias:** RF-024, RF-010 (alertas).
- **Estado:** Pendiente.

#### RF-027 — Tiempos estimados vs. reales de entrega
- **Descripción:** registrar y consultar tiempos estimados vs. tiempos reales de entrega.
- **Sección origen:** 3.5 "Módulo de Tiempos de Envío y Logística".
- **Prioridad:** obligatoria.
- **Módulo responsable:** logistics.
- **Criterio de aceptación:** cada transferencia despachada permite comparar la fecha estimada de llegada (RF-024) contra la fecha real de recepción (RF-025/RF-026).
- **Evidencia esperada:** reporte/consulta mostrando ambos tiempos para una transferencia cerrada.
- **Dependencias:** RF-024, RF-025, RF-026.
- **Estado:** Pendiente.

#### RF-028 — Clasificación de rutas
- **Descripción:** clasificar rutas por prioridad, costo o tiempo.
- **Sección origen:** 3.5.
- **Prioridad:** obligatoria.
- **Módulo responsable:** logistics.
- **Criterio de aceptación:** una ruta origen-destino puede clasificarse/etiquetarse según al menos uno de los tres criterios (prioridad, costo, tiempo).
- **Evidencia esperada:** consulta de rutas mostrando su clasificación.
- **Dependencias:** RF-024.
- **Estado:** Pendiente.

#### RF-029 — Estado de transferencia en curso
- **Descripción:** visualizar el estado de cada transferencia en curso (en preparación, en tránsito, recibido, con faltantes).
- **Sección origen:** 3.5.
- **Prioridad:** obligatoria.
- **Módulo responsable:** logistics / transfers.
- **Criterio de aceptación:** cada transferencia expone en todo momento uno de, al menos, los cuatro estados listados, consistente con el paso del flujo en que se encuentra.
- **Evidencia esperada:** máquina de estados verificada por prueba (transición válida/ inválida) — nota: la máquina de estados definitiva está pendiente de diseño según `STATUS.md`.
- **Dependencias:** RF-022 a RF-026.
- **Estado:** Pendiente.

#### RF-030 — Reportes de cumplimiento logístico
- **Descripción:** generar reportes de cumplimiento logístico por sucursal y por ruta.
- **Sección origen:** 3.5.
- **Prioridad:** obligatoria.
- **Módulo responsable:** logistics / reports.
- **Criterio de aceptación:** existe un reporte que agrega cumplimiento (p. ej. % de entregas a tiempo) filtrable por sucursal y por ruta.
- **Evidencia esperada:** reporte generado con datos de prueba y ambos filtros operativos.
- **Dependencias:** RF-027, RF-028.
- **Estado:** Pendiente.

#### RF-031 — Dashboard: ventas del mes vs. anteriores
- **Descripción:** volumen de ventas del mes en curso vs. meses anteriores.
- **Sección origen:** 3.6 "Análisis y Visualización (Dashboard)".
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** el dashboard muestra el volumen de ventas del mes actual comparado con al menos un mes anterior (el documento no fija cuántos meses; ver ambigüedad al final).
- **Evidencia esperada:** captura del dashboard con datos de prueba en al menos dos meses distintos.
- **Dependencias:** RF-017.
- **Estado:** Pendiente.

#### RF-032 — Dashboard: rotación e inventario alta/baja demanda
- **Descripción:** comportamiento del inventario: rotación, productos de alta y baja demanda.
- **Sección origen:** 3.6.
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** el dashboard identifica y muestra productos de alta y baja rotación/demanda con base en movimientos históricos.
- **Evidencia esperada:** captura del dashboard con productos de alta/baja demanda diferenciados.
- **Dependencias:** RF-008, RF-017.
- **Estado:** Pendiente.

#### RF-033 — Dashboard: transferencias activas e impacto
- **Descripción:** estado de las transferencias activas y su impacto en el inventario.
- **Sección origen:** 3.6.
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** el dashboard lista transferencias activas y refleja su efecto proyectado/real sobre el stock afectado.
- **Evidencia esperada:** captura del dashboard con una transferencia activa visible.
- **Dependencias:** RF-029.
- **Estado:** Pendiente.

#### RF-034 — Dashboard: indicadores de reabastecimiento
- **Descripción:** indicadores de reabastecimiento: productos próximos a agotarse.
- **Sección origen:** 3.6.
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** el dashboard destaca productos cuyo stock se acerca al mínimo configurado (RF-010), antes de llegar a él.
- **Evidencia esperada:** captura del dashboard con un producto próximo a su umbral mínimo.
- **Dependencias:** RF-010.
- **Estado:** Pendiente.

#### RF-035 — Dashboard: comparativa entre sucursales
- **Descripción:** comparación de rendimiento entre sucursales (visible para perfiles autorizados).
- **Sección origen:** 3.6.
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** la comparativa entre sucursales solo es visible para roles administrativos (Administrador general, y posiblemente Gerente según alcance de su sucursal); no visible para Operador.
- **Evidencia esperada:** prueba de autorización: Operador no accede a la vista comparativa; Administrador sí.
- **Dependencias:** RF-037, RF-038, RF-039 (permisos por rol).
- **Estado:** Pendiente.

#### RF-036 — Funcionalidad adicional de valor real
- **Descripción:** además de los módulos obligatorios, se debe proponer e implementar al menos una funcionalidad adicional que aporte valor real. El documento ofrece 6 ideas orientadoras no limitantes (alertas inteligentes, predicción de demanda, gestión de proveedores, control de caducidad, auditoría y trazabilidad, reportes exportables); la elección concreta es libre.
- **Sección origen:** 4 "Funcionalidad Adicional Propuesta".
- **Prioridad:** obligatoria (la existencia de al menos una); la elección específica es una decisión nuestra.
- **Módulo responsable:** depende de la funcionalidad elegida. Nota: `PROJECT_BRIEF.md` ya registra como decisión propia priorizar "Sistema de alertas inteligentes de stock" — esa priorización es nuestra, no una exigencia del documento.
- **Criterio de aceptación:** existe al menos una funcionalidad adicional funcionando de extremo a extremo, con justificación documentada de por qué se eligió sobre las demás opciones.
- **Evidencia esperada:** demo funcional + sección en README/ADR justificando la elección.
- **Dependencias:** RF-010 (si se elige alertas inteligentes, extiende este requisito).
- **Estado:** Pendiente.

#### RF-037 — Rol Administrador general
- **Descripción:** gestiona configuración, usuarios, sucursales y tiene visibilidad total del sistema.
- **Sección origen:** 6.2 "Casos de Uso", tabla de actores.
- **Prioridad:** obligatoria.
- **Módulo responsable:** auth / users.
- **Criterio de aceptación:** un usuario con este rol puede gestionar usuarios y sucursales, y consultar datos de cualquier sucursal sin restricción.
- **Evidencia esperada:** prueba de autorización cubriendo las acciones exclusivas de este rol.
- **Dependencias:** RNF-003.
- **Estado:** Pendiente.

#### RF-038 — Rol Gerente de sucursal
- **Descripción:** supervisa operaciones de su sucursal, aprueba transferencias y consulta reportes.
- **Sección origen:** 6.2, tabla de actores.
- **Prioridad:** obligatoria.
- **Módulo responsable:** auth / users.
- **Criterio de aceptación:** un usuario con este rol puede aprobar/gestionar transferencias de su sucursal y acceder a reportes, sin acceso a gestión global de usuarios/sucursales.
- **Evidencia esperada:** prueba de autorización cubriendo permisos y restricciones de este rol.
- **Dependencias:** RF-022 a RF-026, RNF-003.
- **Estado:** Pendiente.

#### RF-039 — Rol Operador de inventario
- **Descripción:** realiza ingresos, retiros, solicita transferencias y registra ventas/compras.
- **Sección origen:** 6.2, tabla de actores.
- **Prioridad:** obligatoria.
- **Módulo responsable:** auth / users.
- **Criterio de aceptación:** un usuario con este rol puede ejecutar RF-007, RF-008, RF-017, RF-012 y RF-022, sin permisos administrativos de configuración global.
- **Evidencia esperada:** prueba de autorización cubriendo permisos y restricciones de este rol.
- **Dependencias:** RF-007, RF-008, RF-012, RF-017, RF-022, RNF-003.
- **Estado:** Pendiente.

#### RF-040 — Actor opcional: Sistema externo
- **Descripción:** puede integrarse con ERPs o sistemas de punto de venta existentes vía API.
- **Sección origen:** 6.2, tabla de actores (marcado explícitamente "opcional").
- **Prioridad:** opcional.
- **Módulo responsable:** auth (autenticación de integraciones) / expone la API REST ya definida para otros módulos.
- **Criterio de aceptación:** no aplica obligatoriamente; si se implementa, debe reutilizar la API REST existente con un mecanismo de autenticación de servicio a servicio.
- **Evidencia esperada:** si se implementa, prueba de integración con credenciales de servicio.
- **Dependencias:** RT-002.
- **Estado:** Pendiente.

---

## B. Requisitos no funcionales

> Nota general: el documento pide expresamente **documentar** rendimiento, seguridad, escalabilidad y usabilidad (sección 6.1), pero no fija valores objetivo concretos para ninguno. Los ítems RNF-002 y RNF-004 se registran como obligación de definir y documentar el atributo, no como una meta numérica ya dada — ver preguntas de negocio al final.

#### RNF-001 — Sincronización near-real-time entre sucursales
- **Descripción:** la información de inventario debe compartirse entre sucursales en tiempo real o near-real-time.
- **Sección origen:** 2.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory (transversal con infraestructura de sincronización).
- **Criterio de aceptación:** pendiente de definir un umbral de latencia máxima aceptable (el documento no lo especifica).
- **Evidencia esperada:** medición de latencia entre el movimiento origen y su visibilidad en otra sucursal, documentada en `ARCHITECTURE.md` o ADR correspondiente.
- **Dependencias:** RF-002.
- **Estado:** Pendiente.

#### RNF-002 — Rendimiento documentado
- **Descripción:** el documento de requerimientos debe cubrir requerimientos no funcionales de rendimiento.
- **Sección origen:** 6.1.
- **Prioridad:** obligatoria (la documentación del atributo, no un valor específico).
- **Módulo responsable:** transversal.
- **Criterio de aceptación:** existe una sección que define expectativas de rendimiento (p. ej. tiempo de respuesta esperado de la API) aunque sea con supuestos propios documentados.
- **Evidencia esperada:** sección correspondiente en el documento de requerimientos (ENT-005).
- **Dependencias:** ENT-005.
- **Estado:** Pendiente.

#### RNF-003 — Seguridad: autenticación y autorización
- **Descripción:** el sistema debe contemplar seguridad; adicionalmente, la sección 8.2 exige documentar la "estrategia de autenticación y autorización implementada" como decisión técnica.
- **Sección origen:** 6.1; 8.2.
- **Prioridad:** obligatoria.
- **Módulo responsable:** auth.
- **Criterio de aceptación:** existe un mecanismo de autenticación y un modelo de autorización por rol aplicado consistentemente a los endpoints sensibles (ver RF-037 a RF-039).
- **Evidencia esperada:** pruebas de autorización negativas (acceso denegado) y positivas por rol; justificación en README/ADR.
- **Dependencias:** RF-037, RF-038, RF-039.
- **Estado:** Pendiente.

#### RNF-004 — Escalabilidad documentada
- **Descripción:** el documento de requerimientos debe cubrir requerimientos no funcionales de escalabilidad.
- **Sección origen:** 6.1.
- **Prioridad:** obligatoria (la documentación del atributo, no un valor específico).
- **Módulo responsable:** transversal.
- **Criterio de aceptación:** existe una sección que declara supuestos de escala (p. ej. número de sucursales, volumen de movimientos esperado) aunque sean estimaciones propias documentadas.
- **Evidencia esperada:** sección correspondiente en el documento de requerimientos (ENT-005).
- **Dependencias:** ENT-005.
- **Estado:** Pendiente.

#### RNF-005 — Usabilidad
- **Descripción:** el documento de requerimientos debe cubrir usabilidad; reforzado por 3.6, que valora "la claridad visual y la relevancia de la información presentada" en el dashboard.
- **Sección origen:** 6.1; 3.6.
- **Prioridad:** obligatoria.
- **Módulo responsable:** dashboard / transversal (UI).
- **Criterio de aceptación:** el dashboard y las pantallas principales presentan información sin requerir explicación adicional para un usuario del rol correspondiente.
- **Evidencia esperada:** revisión de UI/UX documentada o feedback de usabilidad informal registrado.
- **Dependencias:** RF-031 a RF-035.
- **Estado:** Pendiente.

#### RNF-006 — Consistencia de datos entre sucursales
- **Descripción:** cada sucursal opera con autonomía mientras mantiene "coherencia de datos con el resto de la red"; la solidez de la arquitectura es un criterio de evaluación explícito.
- **Sección origen:** 1; 2.
- **Prioridad:** obligatoria.
- **Módulo responsable:** inventory (transversal).
- **Criterio de aceptación:** no existen estados de inventario contradictorios entre sucursales tras una transferencia u operación concurrente (p. ej. doble descuento de stock).
- **Evidencia esperada:** prueba de concurrencia sobre el mismo stock (ver riesgo ya identificado en `PROJECT_BRIEF.md` sección 10).
- **Dependencias:** RF-002, RF-025, RF-026.
- **Estado:** Pendiente.

---

## C. Reglas técnicas obligatorias

#### RT-001 — Separación en 3 capas
- **Descripción:** la solución debe tener al menos tres capas separadas: frontend, backend y base de datos, cada una con responsabilidades claramente definidas.
- **Sección origen:** 5 "Reglas Técnicas Obligatorias"; 8.1 "Separación de Responsabilidades".
- **Prioridad:** obligatoria, sin excepción.
- **Módulo responsable:** transversal (arquitectura).
- **Criterio de aceptación:** el repositorio y el despliegue reflejan tres componentes independientes y desplegables por separado.
- **Evidencia esperada:** estructura de carpetas + `docker-compose.yml` con 3 servicios distintos (frontend, backend, BD).
- **Dependencias:** RT-003.
- **Estado:** Pendiente.

#### RT-002 — Comunicación exclusiva por API
- **Descripción:** el frontend debe comunicarse con el backend exclusivamente a través de una API bien definida (REST o GraphQL). No se acepta lógica de negocio en el cliente.
- **Sección origen:** 5; 8.1.
- **Prioridad:** obligatoria, sin excepción.
- **Módulo responsable:** transversal.
- **Criterio de aceptación:** no existe acceso directo del frontend a la base de datos ni reglas de negocio (cálculos de costo, validación de stock, etc.) implementadas en el cliente.
- **Evidencia esperada:** revisión de código del frontend confirmando ausencia de lógica de negocio; contrato de API documentado (`API_DESIGN.md`, pendiente de crear).
- **Dependencias:** ninguna.
- **Estado:** Pendiente.

#### RT-003 — Contenedorización total con Docker Compose
- **Descripción:** el proyecto completo debe poder ejecutarse con un solo comando (`docker compose up`), sin dependencias de configuración manual en el entorno local.
- **Sección origen:** 5; 8.1; 10 (entregable "Docker Compose").
- **Prioridad:** obligatoria, sin excepción.
- **Módulo responsable:** transversal (infraestructura).
- **Criterio de aceptación:** `docker compose up` desde el repositorio limpio (sin pasos manuales previos salvo variables de entorno documentadas) deja el sistema operativo.
- **Evidencia esperada:** verificación en un entorno limpio + instrucciones en README (ENT-004).
- **Dependencias:** RT-001.
- **Estado:** Pendiente.

#### RT-004 — Stack tecnológico libre y justificado
- **Descripción:** el stack es completamente libre siempre que se cumplan RT-001, RT-002 y RT-003; se valorará la justificación de las decisiones tecnológicas.
- **Sección origen:** 5.
- **Prioridad:** obligatoria (la justificación, no un stack específico).
- **Módulo responsable:** transversal.
- **Criterio de aceptación:** cada elección de stack (frontend, backend, BD) tiene una entrada correspondiente en `DECISIONS.md` con justificación explícita.
- **Evidencia esperada:** `DECISIONS.md` (ya existe con TD-001 a TD-008).
- **Dependencias:** ninguna.
- **Estado:** Pendiente (verificar completitud/consistencia del archivo, que actualmente presenta contenido truncado — ver nota previa).

#### RT-005 — Justificación documentada de decisiones técnicas clave
- **Descripción:** deben documentarse con justificación, como mínimo: elección del lenguaje de backend, selección del motor de base de datos y modelo de datos, estrategia de autenticación/autorización, mecanismo de sincronización de inventario entre sucursales, y cualquier patrón de diseño utilizado.
- **Sección origen:** 8.2 "Decisiones Técnicas a Documentar".
- **Prioridad:** obligatoria.
- **Módulo responsable:** transversal.
- **Criterio de aceptación:** existe, para cada uno de los 5 puntos listados, una justificación explícita y localizable (ADR, `DECISIONS.md` o `ARCHITECTURE.md`).
- **Evidencia esperada:** los 5 puntos cubiertos; en particular, el "mecanismo de sincronización de inventario entre sucursales" no tiene todavía una entrada dedicada (la elección de SSE en `DECISIONS.md`/`PROJECT_BRIEF.md` cubre el transporte, pero no está formalmente vinculada a este punto exigido).
- **Dependencias:** RT-004, RNF-001, RNF-003, RNF-006.
- **Estado:** Pendiente.

---

## D. Entregables

#### ENT-001 — Repositorio GitHub público
- **Descripción:** repositorio público con el código fuente completo, historial de commits representativo del proceso y estructura de carpetas clara.
- **Sección origen:** 10 "Entregables Esperados".
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** repositorio accesible públicamente, con commits incrementales que reflejen las fases de trabajo (no un único commit masivo).
- **Evidencia esperada:** URL del repositorio + `git log` legible.
- **Dependencias:** CEI-006.
- **Estado:** Pendiente.

#### ENT-002 — Código fuente limpio y organizado
- **Descripción:** código de frontend, backend y scripts de base de datos, organizado, comentado y libre de archivos innecesarios (.env, node_modules, etc.).
- **Sección origen:** 10.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** `.gitignore` excluye artefactos de build y secretos; no hay archivos de dependencias versionados.
- **Evidencia esperada:** revisión del árbol de archivos del repositorio.
- **Dependencias:** ninguna.
- **Estado:** Pendiente (ya existe `.gitignore` según historial de commits; verificar cobertura al finalizar).

#### ENT-003 — docker-compose.yml funcional
- **Descripción:** archivo `docker-compose.yml` que levante toda la solución con un solo comando, con instrucciones de configuración inicial.
- **Sección origen:** 10; 5.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** igual a RT-003.
- **Evidencia esperada:** archivo en la raíz del repo + sección de instalación en README.
- **Dependencias:** RT-003.
- **Estado:** Pendiente.

#### ENT-004 — README completo
- **Descripción:** README con descripción del proyecto, instrucciones de instalación, arquitectura, módulos implementados y decisiones de diseño.
- **Sección origen:** 10.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** el README cubre los 5 puntos listados sin remitir a documentos ausentes.
- **Evidencia esperada:** `README.md` en la raíz del repositorio.
- **Dependencias:** ENT-005 a ENT-011 (se referencian desde el README).
- **Estado:** Pendiente.

#### ENT-005 — Documento de requerimientos (RF/RNF/restricciones/supuestos)
- **Descripción:** documento o sección que contenga requerimientos funcionales, no funcionales, restricciones técnicas y de negocio, y supuestos/dependencias del sistema.
- **Sección origen:** 6.1 "Levantamiento de Requerimientos".
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** documento único o conjunto de documentos que cubre explícitamente los 4 componentes listados.
- **Evidencia esperada:** este mismo documento (`REQUIREMENTS_TRACEABILITY.md`) cubre RF y parte de RNF; restricciones y supuestos formales quedan pendientes de consolidar (posible documento adicional o sección ampliada).
- **Dependencias:** ninguna.
- **Estado:** Pendiente (parcialmente cubierto por este documento).

#### ENT-006 — Documentación de casos de uso y actores
- **Descripción:** definición de los actores del sistema y sus interacciones principales.
- **Sección origen:** 6.2 "Casos de Uso".
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** documento `USE_CASES.md` (previsto en `PROJECT_BRIEF.md` sección 11) con los 4 actores y sus interacciones por módulo.
- **Evidencia esperada:** `docs/USE_CASES.md` (pendiente de crear).
- **Dependencias:** RF-037 a RF-040.
- **Estado:** Pendiente.

#### ENT-007 — Diagrama de casos de uso
- **Descripción:** actores y sus relaciones con los módulos del sistema.
- **Sección origen:** 7.1 "Diagramas Obligatorios".
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** diagrama que muestra los 4 actores conectados a los casos de uso/módulos que les aplican.
- **Evidencia esperada:** archivo de imagen o Mermaid/PlantUML en el repositorio.
- **Dependencias:** ENT-006.
- **Estado:** Pendiente.

#### ENT-008 — Diagrama de actividades/flujo
- **Descripción:** al menos el flujo de transferencia entre sucursales y el flujo de venta.
- **Sección origen:** 7.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** existen como mínimo dos diagramas de flujo: transferencias (RF-022 a RF-026) y ventas (RF-017 a RF-021).
- **Evidencia esperada:** archivos de imagen o Mermaid/PlantUML en el repositorio.
- **Dependencias:** RF-017 a RF-026.
- **Estado:** Pendiente.

#### ENT-009 — Diagrama de arquitectura
- **Descripción:** vista técnica del sistema con capas, servicios y base de datos.
- **Sección origen:** 7.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** el diagrama refleja las 3 capas (RT-001) y los módulos internos del backend.
- **Evidencia esperada:** archivo de imagen o Mermaid/PlantUML; puede ampliar el diagrama textual ya esbozado en `ARCHITECTURE.md`.
- **Dependencias:** RT-001.
- **Estado:** Pendiente.

#### ENT-010 — Diagrama entidad-relación
- **Descripción:** modelo de datos completo con relaciones.
- **Sección origen:** 7.1.
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** el diagrama cubre todas las entidades implicadas en los módulos obligatorios (productos, inventario, movimientos, compras, ventas, transferencias, sucursales, usuarios, proveedores, etc.).
- **Evidencia esperada:** `docs/DOMAIN_MODEL.md` con diagrama E-R (pendiente de crear, ya previsto en `PROJECT_BRIEF.md`).
- **Dependencias:** ENT-005.
- **Estado:** Pendiente.

#### ENT-011 — Sección de uso de IA con evidencia
- **Descripción:** descripción de herramientas de IA usadas y en qué etapa, ejemplos concretos de prompts y resultados (capturas o fragmentos), evaluación crítica (qué aportó, qué se ajustó manualmente, dónde no fue útil), y estimación del porcentaje de código/documentación generado con IA.
- **Sección origen:** 9.2 "Evidencia Esperada".
- **Prioridad:** obligatoria.
- **Módulo responsable:** —
- **Criterio de aceptación:** `AI_USAGE.md` contiene los 4 elementos exigidos con ejemplos concretos, no solo declaraciones genéricas.
- **Evidencia esperada:** `docs/AI_USAGE.md` actualizado (actualmente incompleto/truncado — ver nota en la sección de preguntas).
- **Dependencias:** ninguna.
- **Estado:** Pendiente.

---

## E. Recomendaciones / opcionales

#### REC-001 — Historias de usuario
- **Descripción:** documentar funcionalidades clave en formato de historia de usuario; el documento da 3 ejemplos (ingreso con costo promedio, dashboard comparativo, transferencia con urgencia).
- **Sección origen:** 6.3 "Historias de Usuario (Recomendadas)".
- **Prioridad:** recomendada (explícitamente no obligatoria).
- **Módulo responsable:** —
- **Criterio de aceptación:** si se adopta, cada historia sigue el formato "Como \<rol\>, quiero \<acción\>, para \<beneficio\>".
- **Evidencia esperada:** sección en `USE_CASES.md` o documento dedicado.
- **Dependencias:** ENT-006.
- **Estado:** Pendiente.

#### REC-002 — Ideas de funcionalidad adicional no elegidas
- **Descripción:** predicción de demanda, gestión de proveedores, control de caducidad, auditoría y trazabilidad, módulo de reportes exportables — ofrecidas como ideas orientadoras además de (o en lugar de) la elegida en RF-036.
- **Sección origen:** 4.
- **Prioridad:** opcional.
- **Módulo responsable:** depende de cuál se explore.
- **Criterio de aceptación:** no aplica salvo que se decida implementar alguna adicionalmente.
- **Evidencia esperada:** N/A hasta decisión.
- **Dependencias:** RF-036.
- **Estado:** Pendiente.

#### REC-003 — Actor "Sistema externo"
- **Descripción:** integración opcional con ERP o sistemas de punto de venta vía API.
- **Sección origen:** 6.2, tabla de actores.
- **Prioridad:** opcional.
- **Módulo responsable:** auth.
- **Criterio de aceptación:** ver RF-040.
- **Evidencia esperada:** ver RF-040.
- **Dependencias:** RF-040.
- **Estado:** Pendiente.

#### REC-004 — Herramientas sugeridas de diagramado
- **Descripción:** se sugiere draw.io, Lucidchart, PlantUML o Mermaid para los diagramas.
- **Sección origen:** 7.1.
- **Prioridad:** opcional (sugerencia de herramienta, el diagrama en sí es obligatorio).
- **Módulo responsable:** —
- **Criterio de aceptación:** N/A — libre elección de herramienta.
- **Evidencia esperada:** N/A.
- **Dependencias:** ENT-007 a ENT-010.
- **Estado:** Pendiente.

#### REC-005 — Orden de trabajo sugerido
- **Descripción:** arquitectura/stack → modelo de datos y diagramas → entorno Docker → backend por dependencia → frontend → funcionalidad adicional → documentación final.
- **Sección origen:** 12 "Tiempo y Consideraciones Finales".
- **Prioridad:** recomendada.
- **Módulo responsable:** —
- **Criterio de aceptación:** N/A — es una sugerencia de secuenciación, no un requisito verificable.
- **Evidencia esperada:** reflejado en `STATUS.md` (fases del proyecto).
- **Dependencias:** ninguna.
- **Estado:** Pendiente (ya orienta el orden de fases en `STATUS.md`).

---

## F. Criterios de evaluación implícitos

#### CEI-001 — Evaluación integral más allá del funcionamiento
- **Descripción:** "el proyecto no se evalúa únicamente por su funcionamiento, sino por la calidad del diseño, la solidez de la arquitectura, la claridad de la documentación y la incorporación inteligente de IA".
- **Sección origen:** 1 "Objetivo del Proyecto".
- **Prioridad:** implícito / transversal.
- **Módulo responsable:** —
- **Criterio de aceptación:** no verificable de forma unitaria; se satisface por la suma de RT, ENT y CEI restantes.
- **Evidencia esperada:** conjunto de entregables + documentación.
- **Dependencias:** todas las categorías.
- **Estado:** Pendiente.

#### CEI-002 — Principio "¿por qué se hizo así?" transversal
- **Descripción:** toda decisión de diseño debe poder responder esa pregunta; reiterado como cierre del documento.
- **Sección origen:** 1; 12.
- **Prioridad:** implícito / transversal.
- **Módulo responsable:** —
- **Criterio de aceptación:** cada entrada de `DECISIONS.md` y cada ADR incluye una justificación explícita, no solo la decisión.
- **Evidencia esperada:** `DECISIONS.md`, `docs/adr/`.
- **Dependencias:** RT-004, RT-005.
- **Estado:** Pendiente.

#### CEI-003 — Justificación exigida por libertad de stack
- **Descripción:** "se valorará la justificación de las decisiones tecnológicas" al ejercer la libertad de stack.
- **Sección origen:** 5.
- **Prioridad:** implícito.
- **Módulo responsable:** —
- **Criterio de aceptación:** igual a RT-004.
- **Evidencia esperada:** `DECISIONS.md`.
- **Dependencias:** RT-004.
- **Estado:** Pendiente.

#### CEI-004 — Claridad visual y relevancia del dashboard
- **Descripción:** "se valora la claridad visual y la relevancia de la información presentada" en el dashboard.
- **Sección origen:** 3.6.
- **Prioridad:** implícito.
- **Módulo responsable:** dashboard.
- **Criterio de aceptación:** igual a RNF-005.
- **Evidencia esperada:** revisión de UI del dashboard.
- **Dependencias:** RNF-005.
- **Estado:** Pendiente.

#### CEI-005 — Evaluación crítica del uso de IA, no solo su presencia
- **Descripción:** "el uso de IA no es una señal de debilidad técnica, sino de madurez profesional; se evalúa la capacidad del candidato para dirigir, validar y mejorar el output".
- **Sección origen:** 9.2.
- **Prioridad:** implícito.
- **Módulo responsable:** —
- **Criterio de aceptación:** `AI_USAGE.md` documenta correcciones/ajustes manuales sobre el output de IA, no solo prompts exitosos.
- **Evidencia esperada:** ver ENT-011.
- **Dependencias:** ENT-011.
- **Estado:** Pendiente.

#### CEI-006 — Historial de commits representativo del proceso
- **Descripción:** el repositorio debe tener "historial de commits representativo del proceso" de desarrollo.
- **Sección origen:** 10.
- **Prioridad:** implícito (parte de ENT-001).
- **Módulo responsable:** —
- **Criterio de aceptación:** commits incrementales por fase/módulo, no un volcado único de código.
- **Evidencia esperada:** `git log`.
- **Dependencias:** ENT-001.
- **Estado:** Pendiente.

---

## Preguntas de negocio (ambigüedades a resolver)

1. **Umbral de "near-real-time" (RNF-001, RF-002):** el documento no define una latencia máxima aceptable para la sincronización de inventario entre sucursales. ¿Existe un SLA esperado (segundos, minutos) o queda a discreción técnica?
2. **Metas de rendimiento y escalabilidad (RNF-002, RNF-004):** el documento exige documentar estos atributos pero no da cifras de referencia (usuarios concurrentes, volumen de sucursales/productos/movimientos esperado). ¿Se deben asumir valores propios y declararlos como supuesto, o existe un contexto de negocio real (p. ej. número real de sucursales de OptiPlant) que deba usarse?
3. **Aprobación de solicitud de transferencia:** la tabla de actores dice que el Gerente de sucursal "aprueba transferencias", pero el flujo de la sección 3.4 no incluye un paso explícito de aprobación separado de "preparación de envío" (paso 2). ¿La aprobación del gerente es un paso adicional antes de la preparación, o se asume implícita en la confirmación de la sucursal origen?
4. **Autorización del tratamiento de faltantes (RF-026):** ¿qué rol decide entre reenvío, ajuste o reclamación ante una recepción parcial? El documento no lo asigna a ningún actor específico.
5. **Alcance de "listas de precios" (RF-020):** no se especifica si son por sucursal, por cliente, por canal o globales. ¿Cuál es el alcance esperado para esta prueba?
6. **Ventana temporal del comparativo de ventas (RF-031):** el documento pide "mes en curso vs. meses anteriores" sin especificar cuántos meses atrás mostrar. `PROJECT_BRIEF.md`/historias de usuario (REC-001) sugieren 3 meses — ¿se adopta ese número como definición del alcance?
7. **Alcance del actor "Sistema externo" (RF-040):** está marcado como opcional en la tabla de actores. ¿Se implementará algo concreto para este actor o queda completamente fuera del alcance de esta entrega?
8. **Confirmación de la funcionalidad adicional (RF-036):** `PROJECT_BRIEF.md` ya prioriza "alertas inteligentes de stock" como decisión propia, y añade la condición "la predicción de demanda podrá evaluarse únicamente si todos los requisitos obligatorios están completos" — esa condición **no aparece en el PDF fuente**, es una interpretación/decisión adicional registrada en nuestra documentación interna. ¿Se mantiene esa condición como regla propia del proyecto, o se ajusta?
9. **Historias de usuario (REC-001):** son explícitamente recomendadas, no obligatorias. ¿Se incluirán de todas formas como parte de la documentación entregable?
10. **Documento de requerimientos (ENT-005):** el documento pide un artefacto que cubra RF, RNF, restricciones técnicas/de negocio y supuestos/dependencias. Esta matriz cubre RF y parcialmente RNF — ¿restricciones y supuestos se consolidan en este mismo archivo, en `PROJECT_BRIEF.md`, o en un documento nuevo (`REQUIREMENTS.md`)?
