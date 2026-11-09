# Especificación de Requisitos del Sistema

**Sistema de Inventario Multi-Sucursal**

**Fuente de origen:** `private/Prueba Tecnica Inventario.pdf` (OptiPlant Consultores).
**Base de esta especificación:** `docs/REQUIREMENTS_TRACEABILITY.md` (matriz de trazabilidad aprobada). Cada requisito aquí referenciado con su ID (RF-xxx, RNF-xxx, RT-xxx, ENT-xxx) tiene su detalle completo — criterio de aceptación, evidencia esperada, dependencias — en esa matriz. Este documento es la especificación consolidada y legible; la matriz es la fuente de detalle verificable.
**Fecha:** 2026-08-26.

**Convención de etiquetas usada en todo el documento:**

- **[Origen]** — requisito explícito del documento fuente de la prueba técnica.
- **[Decisión]** — decisión técnica adoptada por el proyecto, no exigida por el documento fuente.
- **[Supuesto]** — interpretación o criterio propio adoptado ante una ambigüedad del documento fuente, pendiente de confirmación si así se indica.

---

## 1. Objetivo y alcance

**[Origen]** Diseñar y desarrollar una aplicación para la gestión de inventario de múltiples sucursales dentro de una misma organización. Cada sucursal opera sus transacciones locales de forma independiente, mientras mantiene visibilidad compartida del inventario general y coherencia de datos con el resto de la red.

El alcance obligatorio cubre seis módulos funcionales — inventario (CRUD), compras, ventas, transferencias entre sucursales (incluida recepción parcial), logística/tiempos de envío, y dashboard/análisis — más al menos una funcionalidad adicional de valor real. El detalle completo de cada módulo está en la sección 3.

**[Origen]** Principio rector transversal: toda decisión de diseño debe poder responder "¿por qué se hizo así?" (CEI-002). La evaluación no se limita al funcionamiento: incluye calidad de diseño, solidez de arquitectura, claridad documental y uso crítico de IA (CEI-001).

## 2. Fuera de alcance explícito

Para evitar expansión accidental durante el desarrollo:

- **[Decisión]** Microservicios, Kubernetes, Kafka, RabbitMQ, Redis, CQRS, Event Sourcing, infraestructura cloud compleja o machine learning avanzado. No se introducirán salvo que surja una necesidad concreta demostrada y se documente el cambio con aprobación explícita.
- **[Supuesto]** Implementación funcional real del actor "Sistema externo" (RF-040) — el documento fuente lo marca como opcional; se garantiza que la API REST no lo impide a futuro, pero no se construye una integración ERP/POS en esta entrega.
- **[Supuesto]** "Predicción de demanda" y las demás ideas de funcionalidad adicional no elegidas (gestión de proveedores, control de caducidad, auditoría como módulo separado, reportes exportables) quedan fuera del alcance obligatorio — ver sección 11 "Oportunidades".
- **[Decisión]** No se implementará ninguna funcionalidad adicional a la elegida (alertas inteligentes de stock, RF-036) mientras los módulos obligatorios no estén completos.
- **[Origen]** No se acepta lógica de negocio en el frontend (RT-002); cualquier validación o cálculo de negocio vive exclusivamente en el backend.
- **[Decisión]** No se cambia el stack, la arquitectura, los contratos de API ni el modelo de datos aprobados sin exponer el problema, alternativas, impacto y solicitar aprobación (regla ya vigente en `CLAUDE.md`).

## 3. Requisitos funcionales por módulo

### 3.0 Actores y roles [Origen]

| Rol | Responsabilidades | ID matriz |
|---|---|---|
| Administrador general | Gestiona usuarios, sucursales y configuración; visibilidad total del sistema. | RF-037 |
| Gerente de sucursal | Supervisa su sucursal, participa en la gestión de transferencias, consulta reportes. | RF-038 |
| Operador de inventario | Ejecuta ingresos, retiros, compras, ventas y solicita transferencias. | RF-039 |
| Sistema externo (opcional) | Integración vía API con ERP/POS externos. Fuera de alcance de implementación — ver sección 2. | RF-040 |

Todo acceso a datos de otra sucursal, y toda acción de escritura, debe evaluarse contra el rol del usuario autenticado (ver RNF-003, sección 4).

### 3.1 Sucursales (branches)

- **[Origen]** Cada sucursal opera sus transacciones locales sin depender de la disponibilidad de otras sucursales. (RF-001)
- **[Origen]** El inventario de cada sucursal es visible para las demás sucursales de la red en tiempo real o near-real-time. (RF-002, ver RNF-001)
- **[Origen]** Cualquier sucursal puede consultar el catálogo e inventario de cualquier otra sucursal de la red. (RF-003)

### 3.2 Productos (products)

- **[Origen]** CRUD completo de productos: creación, consulta, actualización y eliminación/baja. (RF-005)
- **[Origen]** Visualización del catálogo de productos de la sucursal propia, con stock actual. (RF-006)
- **[Origen]** Un producto admite múltiples unidades de medida, con conversión consistente al usarse en movimientos, compras y ventas. (RF-011)

### 3.3 Inventario (inventory)

- **[Origen]** Registro de ingresos de producto: compras, devoluciones, ajustes. (RF-007)
- **[Origen]** Registro de retiros de producto: ventas, mermas, ajustes; no se permite retiro que exceda el stock disponible salvo ajuste explícito autorizado. (RF-008)
- **[Origen]** Trazabilidad completa: todo ingreso o retiro registra como mínimo fecha, responsable, motivo y cantidad, en un historial auditable e inmutable. (RF-009, BR-001)
- **[Origen]** Control de stock mínimo por producto y generación de alerta al alcanzarlo o caer por debajo. (RF-010, BR-010)

### 3.4 Compras (purchases)

- **[Origen]** Creación y gestión de órdenes de compra a proveedores. (RF-012)
- **[Origen]** Registro de condiciones de compra: precio unitario, descuentos, plazo de pago. (RF-013)
- **[Origen]** Al confirmar la recepción de una orden de compra, el inventario de la sucursal receptora se actualiza automáticamente. (RF-014, BR-003)
- **[Origen]** Histórico de compras consultable por proveedor y por producto. (RF-015)
- **[Origen]** Cálculo de costo promedio ponderado del inventario tras cada recepción con precio distinto al costo actual. (RF-016, BR-004)

### 3.5 Ventas (sales)

- **[Origen]** Registro de ventas por producto, cantidad y precio. (RF-017)
- **[Origen]** Toda venta se asocia a sucursal, fecha y usuario responsable. (RF-018)
- **[Origen]** Validación de stock disponible antes de confirmar la venta; se rechaza si excede lo disponible. (RF-019, BR-002)
- **[Origen]** Aplicación de descuentos y uso de listas de precios configurables (no valores fijos hardcodeados). (RF-020)
- **[Supuesto]** Alcance de listas de precios: una o más listas globales o por sucursal aplicables a la venta, sin segmentación por cliente — ver sección 7, supuesto 5.
- **[Origen]** Generación de comprobante/registro de venta consultable posteriormente por su identificador. (RF-021)

### 3.6 Transferencias entre sucursales (transfers)

Flujo completo, en orden: solicitud → preparación de envío → despacho → recepción (completa o parcial).

- **[Origen]** Solicitud de transferencia (producto, cantidad, sucursal origen) iniciada por la sucursal destino o un administrador. (RF-022)
- **[Origen]** Preparación de envío: la sucursal origen revisa disponibilidad y confirma o ajusta la cantidad a enviar. (RF-023)
- **[Origen]** Registro de despacho con fecha estimada de llegada y transportista. (RF-024)
- **[Origen]** Recepción completa: el inventario de la sucursal destino se actualiza automáticamente. (RF-025, BR-006)
- **[Origen]** Recepción parcial: se registra el faltante, se genera una alerta y se define un tratamiento — reenvío, ajuste o reclamación. (RF-026, BR-007, BR-008, BR-009)
- **[Supuesto]** Rol que autoriza/decide el tratamiento del faltante: Gerente de sucursal, dado su rol de supervisión — pendiente de confirmación explícita (ver sección 7, supuesto 4).
- **[Supuesto]** Punto de aprobación formal de la solicitud por parte del Gerente: se asume que la confirmación de la sucursal origen en la etapa de preparación (RF-023) cumple ese control; si se requiere un paso de aprobación separado, debe confirmarse (ver sección 7, supuesto 3).

### 3.7 Logística (logistics)

- **[Origen]** Registro y consulta de tiempos estimados vs. reales de entrega. (RF-027)
- **[Origen]** Clasificación de rutas por prioridad, costo o tiempo. (RF-028)
- **[Origen]** Visualización del estado de cada transferencia en curso: en preparación, en tránsito, recibido, con faltantes. (RF-029)
- **[Origen]** Reportes de cumplimiento logístico filtrables por sucursal y por ruta. (RF-030)

### 3.8 Dashboard (dashboard)

- **[Origen]** Volumen de ventas del mes en curso vs. meses anteriores. (RF-031)
- **[Supuesto]** Ventana de comparación: mes actual vs. 3 meses anteriores, siguiendo la historia de usuario sugerida por el documento fuente (sección 6.3 del PDF) — ver sección 7, supuesto 6.
- **[Origen]** Rotación de inventario y productos de alta/baja demanda. (RF-032)
- **[Origen]** Estado de transferencias activas y su impacto en el inventario. (RF-033)
- **[Origen]** Indicadores de reabastecimiento: productos próximos a agotarse. (RF-034)
- **[Origen]** Comparativa de rendimiento entre sucursales, visible solo para perfiles administrativos (no para Operador). (RF-035)

### 3.9 Funcionalidad adicional

- **[Origen]** Debe existir al menos una funcionalidad adicional de valor real, más allá de los módulos obligatorios. (RF-036)
- **[Decisión]** Funcionalidad elegida: sistema de alertas inteligentes de stock — notificación a usuarios autorizados cuando un producto alcanza o cae por debajo de su stock mínimo configurado. Justificación: reutiliza directamente RF-010, tiene alcance acotado y valor operativo inmediato. Detalle de diseño pendiente de fase de implementación.

## 4. Requisitos no funcionales

El documento fuente exige documentar rendimiento, seguridad, escalabilidad y usabilidad (RNF-002, RNF-003, RNF-004, RNF-005) sin fijar valores concretos. Donde no hay una cifra de origen, se define un criterio propio y se marca **[Supuesto]**.

### 4.1 Seguridad — RNF-003 **[Origen + Decisión]**

- Autenticación mediante JWT; autorización por rol (ADMIN, MANAGER, OPERATOR) sobre cada endpoint sensible.
- Contraseñas nunca en texto plano; JWT sin datos sensibles innecesarios (ya establecido en `DECISIONS.md` TD-007/TD-008).
- **Criterio medible:** 100% de los endpoints de escritura y de consulta cross-sucursal exigen JWT válido; existe al menos una prueba de autorización negativa (403/401) por cada rol y cada módulo con restricción de acceso.

### 4.2 Consistencia — RNF-006 **[Origen]**

- El inventario no debe llegar a un estado contradictorio entre sucursales tras operaciones concurrentes (venta, recepción de compra, transferencia).
- **Criterio medible:** una prueba de concurrencia que ejecuta dos ventas simultáneas sobre el mismo stock límite no permite que el stock resultante sea negativo ni que ambas ventas se confirmen si el stock solo alcanza para una.
- Las operaciones críticas (venta, recepción de compra, recepción de transferencia) se ejecutan dentro de una transacción de base de datos.

### 4.3 Rendimiento razonable para la prueba — RNF-002 **[Supuesto]**

- El documento no fija metas de rendimiento; se asume un contexto de prueba técnica/demo, no de producción a escala.
- **Criterio medible (supuesto propio):** los endpoints de consulta (listados, dashboard) responden en menos de 2 segundos con datos de volumen de prueba (decenas de sucursales, cientos de productos, miles de movimientos). No se establece un SLA de producción.

### 4.4 Escalabilidad conceptual — RNF-004 **[Supuesto]**

- El documento pide documentar escalabilidad sin cifra de referencia; se asume un volumen de prueba, no de producción (ver sección 7, supuesto 2).
- **Criterio medible:** el modelo de datos admite agregar una nueva sucursal sin cambios de esquema; los módulos del monolito modular están desacoplados lo suficiente como para que uno pueda extraerse a servicio independiente si en el futuro aparece una necesidad concreta de escala (sin implementarlo ahora — ver sección 2).

### 4.5 Auditabilidad — RF-009, BR-001 **[Origen]**

- Todo movimiento de inventario, compra, venta y paso del flujo de transferencia queda registrado con fecha, responsable, motivo/tipo y cantidad.
- **Criterio medible:** el historial de movimientos de cualquier producto/sucursal es reconstruible completamente a partir de los registros persistidos, sin depender de logs de aplicación.
- Los registros de movimientos no son editables ni eliminables retroactivamente (solo reversión mediante un nuevo movimiento compensatorio).

### 4.6 Usabilidad — RNF-005 **[Origen]**

- El dashboard y las pantallas principales deben presentar la información con claridad visual y relevancia (valorado explícitamente en el documento fuente, sección 3.6).
- **Criterio medible (supuesto propio):** un usuario del rol correspondiente puede completar los flujos principales (registrar venta, registrar ingreso, solicitar transferencia) sin necesitar explicación adicional fuera de la propia interfaz.

### 4.7 Mantenibilidad **[Decisión]**

No exigida explícitamente por el documento fuente como atributo con ese nombre, pero se deriva del criterio de evaluación "solidez de la arquitectura" (CEI-001).

- Backend organizado en módulos cohesivos (auth, users, branches, products, inventory, purchases, sales, transfers, logistics, dashboard, reports) con separación de capas API/aplicación/persistencia dentro de cada uno.
- **Criterio medible:** ningún módulo accede directamente a las tablas de otro módulo sin pasar por su capa de servicio/API interna.

### 4.8 Observabilidad mínima **[Decisión]**

No exigida por el documento fuente; se incluye por buena práctica de ingeniería, dado el énfasis del documento en trazabilidad y solidez arquitectónica.

- Logging estructurado de operaciones de negocio críticas (venta, compra, transferencia) y de errores no controlados.
- Endpoint de verificación de salud (health check) por servicio.
- **Criterio medible:** ante un fallo en una operación crítica, el log permite identificar sucursal, usuario, operación y motivo del fallo sin necesidad de depuración adicional.

## 5. Restricciones técnicas obligatorias

**[Origen]** Sin excepción, según el documento fuente:

- **RT-001** — Separación en al menos 3 capas: frontend, backend, base de datos, cada una con responsabilidades claras.
- **RT-002** — El frontend se comunica con el backend exclusivamente mediante una API bien definida (REST o GraphQL); no se acepta lógica de negocio en el cliente.
- **RT-003** — Todo el sistema debe levantarse con un solo comando (`docker compose up`), sin configuración manual adicional en el entorno local.
- **RT-004** — El stack tecnológico es libre siempre que se cumplan RT-001 a RT-003; se valorará la justificación de las decisiones tomadas.
- **RT-005** — Deben documentarse con justificación: lenguaje de backend, motor de BD y modelo de datos, estrategia de autenticación/autorización, mecanismo de sincronización de inventario entre sucursales, y patrones de diseño usados.

## 6. Decisiones técnicas ya aprobadas

**[Decisión]** — ninguna de las siguientes es exigida por el documento fuente; son elecciones del proyecto, justificadas en detalle en `docs/DECISIONS.md` (TD-001 a TD-008). Se resumen aquí para contexto:

| Decisión | Resumen | Ref. |
|---|---|---|
| Frontend | React + TypeScript | TD-001 |
| Backend | Java 21 + Spring Boot | TD-002 |
| Base de datos | PostgreSQL | TD-003 |
| API | REST | TD-004 |
| Arquitectura | Monolito modular | TD-005 |
| Persistencia | Spring Data JPA / Hibernate | TD-006 |
| Seguridad | Spring Security + JWT | TD-007 |
| Autorización | RBAC (ADMIN, MANAGER, OPERATOR) | TD-008 |
| Infraestructura | Docker + Docker Compose | — |
| Sincronización near-real-time | SSE preferido; WebSocket solo si aparece necesidad bidireccional real | — |

La decisión de mecanismo de sincronización de inventario (SSE) satisface parcialmente RT-005; falta formalizar su justificación específica frente al punto exacto que exige el documento fuente ("mecanismo de sincronización de inventario entre sucursales") — pendiente en `docs/DECISIONS.md` o un ADR dedicado.

## 7. Supuestos

Cada supuesto queda documentado aquí para trazabilidad; los marcados como "pendiente de confirmación" no deben tratarse como decisión firme hasta validarse con el usuario.

1. **Umbral de near-real-time (RNF-001):** no hay SLA duro comprometido por el documento fuente; se opera con la latencia natural de un push por SSE (típicamente sub-segundo a pocos segundos), sin garantía numérica contractual.
2. **Volumen de referencia (RNF-002, RNF-004):** se asume alcance de prueba técnica — pocas sucursales (decenas), catálogo de cientos de productos, miles de movimientos — no volumen de producción real.
3. **Aprobación de transferencia — pendiente de confirmación:** se asume que la confirmación de la sucursal origen en la preparación de envío (RF-023) es el punto de control operativo; no se modela un paso de aprobación formal separado del Gerente salvo que se confirme lo contrario.
4. **Autorización del tratamiento de faltantes — pendiente de confirmación:** se asume que corresponde al Gerente de sucursal, por su rol de supervisión.
5. **Alcance de listas de precios:** una o más listas (globales o por sucursal) aplicables a la venta; sin segmentación por cliente.
6. **Ventana del comparativo de ventas del dashboard:** mes actual vs. 3 meses anteriores, tomando la historia de usuario sugerida en el documento fuente como referencia de alcance.
7. **Actor "Sistema externo":** sin implementación funcional en esta entrega; solo se preserva la posibilidad futura al no romper el contrato REST.
8. **Condición sobre predicción de demanda:** la restricción "solo se evalúa si todos los requisitos obligatorios están completos" no proviene del documento fuente — es una decisión propia ya registrada; se mantiene como decisión del proyecto salvo indicación contraria.
9. **Historias de usuario:** se documentarán igualmente pese a ser recomendadas (no obligatorias) por su valor de claridad de alcance.
10. **Consolidación del documento de requerimientos no funcionales/restricciones/supuestos (ENT-005):** se resuelve en este mismo documento, en lugar de crear un archivo adicional separado.

## 8. Dependencias

**Entre requisitos/módulos** (detalle completo por ítem en la matriz de trazabilidad):

- Ventas (3.5) depende de Inventario (3.3) para validar stock (RF-019) y de Productos (3.2) para precios/unidades.
- Compras (3.4) depende de Inventario (3.3) para actualizar stock al confirmar recepción (RF-014).
- Transferencias (3.6) depende de Inventario (3.3) en ambas sucursales (origen y destino) y de Logística (3.7) para tiempos y estado.
- Dashboard (3.8) depende de datos de Ventas, Inventario y Transferencias; no genera datos propios.
- Alertas inteligentes (3.9) depende de Inventario (RF-010, stock mínimo).
- Todos los módulos dependen de Auth/Usuarios (3.0) para autorización por rol.

**Externas:**

- **[Origen]** Ninguna integración externa es obligatoria para esta entrega (el único actor externo, RF-040, es opcional y está fuera de alcance — sección 2).
- **[Decisión]** El proyecto no depende de servicios cloud gestionados; todo corre vía Docker Compose en el entorno local del evaluador.

## 9. Riesgos del dominio

**[Origen, derivado de la sección 10 del documento fuente y reforzado por el principio de coherencia de datos de la sección 2]**

- Ventas concurrentes sobre el mismo stock (condición de carrera) — mitigado por RNF-006 (transacciones + prueba de concurrencia).
- Stock negativo por retiro, venta o recepción parcial mal validada.
- Inconsistencia entre el registro de movimientos y el stock agregado del producto.
- Recepción duplicada de una misma compra (doble actualización de inventario).
- Despacho duplicado o recepción duplicada de una misma transferencia.
- Recepción parcial mal calculada (faltante incorrecto, tratamiento no aplicado).
- Pérdida de trazabilidad si un movimiento se registra sin responsable, motivo o cantidad completos.
- Permisos incorrectos entre sucursales (un usuario accede o modifica datos de una sucursal a la que no pertenece, fuera de las consultas explícitamente permitidas).
- Cálculo incorrecto del costo promedio ponderado ante secuencias de compras con precios variables.
- Sobrearquitectura o expansión de alcance no solicitada (mitigado por la sección 2 de este documento y por las reglas de `CLAUDE.md`).
- Ambigüedades de negocio no resueltas (sección 7) implementadas con un supuesto incorrecto sin registrar la desviación.

## 10. Glosario

- **Sucursal:** unidad operativa de la organización con inventario, transacciones y usuarios propios, que opera de forma autónoma pero visible para el resto de la red.
- **Inventario:** conjunto de existencias de productos de una sucursal en un momento dado, junto con su historial de movimientos.
- **Movimiento (de inventario):** registro auditable de un cambio de cantidad de un producto en una sucursal (ingreso o retiro), con fecha, responsable, motivo y cantidad.
- **Ingreso:** movimiento que aumenta el stock (por compra, devolución, ajuste o recepción de transferencia).
- **Retiro:** movimiento que disminuye el stock (por venta, merma, ajuste o despacho de transferencia).
- **Compra / orden de compra:** solicitud formal a un proveedor para adquirir productos, con precio, descuentos y condiciones de pago, que al confirmarse su recepción genera un ingreso de inventario.
- **Venta:** transacción de salida comercial de producto, asociada a sucursal, fecha y responsable, que requiere validación previa de stock.
- **Transferencia:** traslado de producto entre dos sucursales de la red, con flujo de solicitud, preparación, despacho y recepción.
- **Solicitud de transferencia:** paso inicial del flujo de transferencia donde se indica producto, cantidad y sucursal origen.
- **Despacho:** acto de envío físico de la mercancía por parte de la sucursal origen, con fecha estimada de llegada y transportista registrados.
- **Recepción completa:** confirmación de que la sucursal destino recibió la totalidad de la cantidad despachada; actualiza el inventario automáticamente.
- **Recepción parcial:** confirmación de que la sucursal destino recibió menos cantidad de la despachada; genera un faltante, una alerta y requiere definir un tratamiento (reenvío, ajuste o reclamación).
- **Faltante:** diferencia entre la cantidad despachada y la cantidad efectivamente recibida en una transferencia.
- **Stock disponible:** cantidad de un producto en una sucursal que puede comprometerse en una venta, retiro o despacho sin quedar en negativo.
- **Stock mínimo:** umbral configurable por producto y sucursal por debajo del cual (o al alcanzarlo) se genera una alerta de reabastecimiento.
- **Costo promedio ponderado:** método de valuación de inventario que recalcula el costo unitario de un producto tras cada recepción de compra, ponderando cantidades y precios de la existencia previa y la nueva entrada.
- **Unidad de medida:** forma en que se cuantifica un producto (p. ej. unidad, caja, kilogramo); un producto puede tener más de una, con conversión entre ellas.
- **Lista de precios:** conjunto de precios de venta aplicable a uno o más productos, usado como referencia al registrar una venta, sujeto a descuentos.
- **Near-real-time:** propagación de un cambio de estado (p. ej. stock) entre sucursales con una latencia perceptiblemente baja, sin ser necesariamente instantánea ni garantizada por un SLA duro.
- **Rol (actor):** perfil de usuario (Administrador general, Gerente de sucursal, Operador de inventario) que determina qué acciones y datos puede ver o modificar.

## 11. Oportunidades (mejoras opcionales — fuera de lo obligatorio)

Esta sección recoge únicamente ideas que ya aparecen en el documento fuente como "no limitantes" (sección 4 del PDF) o como recomendaciones explícitas, para no perderlas de vista sin comprometerlas como alcance. **Ninguna de estas se implementa salvo decisión posterior explícita.**

- **[Origen — no elegida]** Predicción de demanda (regresión lineal o promedio móvil sobre historial de ventas).
- **[Origen — no elegida]** Gestión de proveedores con condiciones comerciales y evaluación de tiempos de entrega.
- **[Origen — no elegida]** Control de caducidad para productos perecederos.
- **[Origen — no elegida]** Auditoría y trazabilidad como módulo dedicado más allá del registro base de movimientos (RF-009 ya cubre el mínimo exigido).
- **[Origen — no elegida]** Módulo de reportes exportables (PDF/Excel) para movimientos, ventas o transferencias por rango de fechas.
- **[Origen — recomendado, no obligatorio]** Historias de usuario adicionales más allá de las tres ejemplo del documento fuente, si se desea enriquecer `USE_CASES.md`.

---

**Documentos relacionados:** `docs/REQUIREMENTS_TRACEABILITY.md` (detalle verificable por requisito), `docs/DECISIONS.md` (justificación de decisiones técnicas), `docs/BUSINESS_RULES.md` (reglas de negocio de origen), `docs/ARCHITECTURE.md` (baseline técnico), `docs/STATUS.md` (estado y fases del proyecto).
