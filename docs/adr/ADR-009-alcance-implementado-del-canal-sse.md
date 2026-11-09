# ADR-009 — Alcance implementado del canal near-real-time

**Estado:** Accepted

**Relación con ADR-007:** no lo reemplaza ni lo contradice. ADR-007 decidió *qué* tecnología usar (SSE, no WebSocket ni broker) y sigue vigente sin cambios. Este ADR registra las decisiones que solo aparecieron al **implementarlo**: qué eventos existen realmente, quién los recibe, cómo se garantiza que no se emitan antes de tiempo y qué quedó deliberadamente fuera.

## Contexto

Antes de escribir el canal se verificó qué necesita realmente actualización sin recarga:

- **Pantallas:** el frontend todavía no tiene pantallas de negocio (`frontend/src/` solo contiene el esqueleto y el cliente HTTP). No se puede, por tanto, señalar una pantalla concreta. Lo que sí existe son los requisitos que la exigen: RF-002/RNF-001 (una sucursal ve el inventario de las demás en near-real-time) y RF-029 (estado de las transferencias en curso). El canal se construye para ellos, no para una pantalla imaginada.
- **Eventos previstos** en `docs/API_DESIGN.md` (7.11): `inventory.updated`, `stock-alert.triggered`, `stock-alert.resolved`, `transfer.status-changed`, `transfer.discrepancy-opened`.

## Decisión

### 1. Solo se emiten los eventos que tienen un productor real

Se implementan tres: `inventory.updated`, `transfer.status-changed` y `transfer.discrepancy-opened`.

Los dos de `stock-alert.*` **no se implementan**: la entidad `StockAlert` (RF-010, RF-036) sigue sin construirse, así que no hay nada que los dispare. Declararlos en el canal sin productor daría una falsa sensación de cobertura — un cliente podría suscribirse y esperar indefinidamente una alerta que el sistema nunca genera. Se añadirán junto con la funcionalidad de alertas.

### 2. La visibilidad del canal es un espejo exacto de la de la API REST

Cada evento declara las sucursales implicadas y si está restringido a ellas:

- `inventory.updated` — **no restringido**. La lectura de inventario es abierta a cualquier sucursal (RF-003, `docs/API_DESIGN.md` §6), así que la señal "cambió el stock de la sucursal X" no revela nada que el usuario no pudiera consultar ya.
- `transfer.*` — **restringido** a origen y destino, igual que la lectura de transferencias (§6). `ADMIN` conserva alcance global.

Además, el cliente puede acotar con `?branchId=`, y un no-`ADMIN` que pida una sucursal ajena recibe **403 al suscribirse**, en vez de una suscripción aceptada que luego filtra en silencio.

El principio: *el canal no concede visibilidad que la API no conceda ya*. Si mañana cambia la regla en REST, debe cambiar aquí — son la misma regla, no dos.

### 3. La emisión ocurre después del commit, por construcción y no por disciplina

Los servicios publican con `DomainEventPublisher`; el envío real lo hace `EventBroadcaster` anotado con `@TransactionalEventListener(AFTER_COMMIT)`. Si la transacción revierte, el listener no llega a ejecutarse y la señal no se emite: no hay forma de avisar de un cambio que no ocurrió, aunque el programador se distraiga.

Corolario aceptado: un evento publicado fuera de una transacción se descarta en silencio. Todos los productores son métodos `@Transactional`; hacerlo "funcionar" sin transacción debilitaría la garantía anterior.

### 4. El evento es una señal, nunca el dato

El payload lleva tipo, sucursales implicadas e id del recurso — nada más. El cliente reconsulta REST para obtener el valor autoritativo. **No hay búfer de reproducción**: si el cliente se desconecta y pierde eventos, al reconectar no se le reenvían; se reconcilia consultando la API. Un búfer sería una segunda fuente de verdad, exactamente lo que ADR-007 quiere evitar.

### 5. Latido periódico

Un `@Scheduled` envía cada 30 s un comentario SSE (que `EventSource` descarta) a cada conexión. Resuelve dos problemas que no son evidentes hasta implementar: un servidor SSE **solo descubre que el cliente se fue cuando intenta escribirle** —sin latido, una conexión muerta ocuparía memoria hasta agotar el timeout de 30 minutos—, y muchos proxies cierran conexiones HTTP inactivas en pocos minutos, mientras que un canal de eventos puede estar legítimamente en silencio mucho más.

Es el único trabajo programado del sistema (`@EnableScheduling`). No añade dependencias.

### 6. El fallo del canal nunca afecta al negocio

Toda excepción de envío se captura en el emisor; la suscripción rota se descarta y el cliente reconecta por su cuenta. Sumado a que el envío ocurre tras el commit, una caída del canal no puede revertir ni bloquear una venta, una recepción ni un despacho.

## Alternativas consideradas

- **Emitir también `stock-alert.*` con un productor provisional:** rechazada. Inventar un disparador de alertas fuera del diseño de RF-010/RF-036 sería adelantar una decisión de negocio no tomada, y el resultado quedaría desalineado con la funcionalidad real cuando se implemente.
- **Incluir el dato completo en el evento** (el stock resultante, la transferencia entera): rechazada por ADR-007 — crearía una segunda fuente de verdad que puede discrepar de la base de datos, y obligaría a versionar el payload del canal como si fuera una API paralela.
- **Búfer de reproducción con `Last-Event-ID`:** rechazada por ahora. Añade estado en memoria y una política de retención, para resolver algo que la reconsulta REST ya resuelve de forma más simple y siempre correcta.
- **Restringir también los eventos de inventario a la sucursal propia:** rechazada porque contradiría RF-002/RF-003 — el usuario ya puede consultar ese stock por REST, y restringir la señal degradaría la visibilidad entre sucursales que el requisito pide.

## Consecuencias positivas

- Sin infraestructura nueva: ni broker, ni almacenamiento de eventos, ni dependencias añadidas.
- La regla de visibilidad vive en un único punto (`Subscription.shouldReceive`) y es directamente comparable con la tabla de autorización de `docs/API_DESIGN.md` §6.
- REST sigue siendo utilizable por completo sin el canal: es una mejora de experiencia, no un requisito de funcionamiento.

## Consecuencias negativas / trade-offs

- **Una sola instancia.** El registro de suscriptores vive en memoria del proceso: un evento originado en una instancia no alcanza a los clientes conectados a otra. Hoy Docker Compose levanta una sola instancia de backend. Escalar horizontalmente exige un bus compartido — y ese es el momento de reconsiderar ADR-007, no antes.
- **Un recurso por conexión abierta**, ya señalado en ADR-007 y no validado a gran escala.
- **Token por query string** en `GET /events`, porque `EventSource` no permite encabezados (`docs/API_DESIGN.md` §2). La excepción se limita a esa ruta: en el resto de la API el token en query string se ignora. Sigue pendiente la mejora ya prevista de emitir un token de vida corta exclusivo para esta conexión.
- **Eventos perdidos durante una desconexión no se recuperan por el canal**; se recuperan consultando REST. Es una consecuencia deliberada del punto 4, no una carencia.

## Criterios para reconsiderarla

- Cuando se implementen las alertas de stock mínimo: añadir `stock-alert.triggered`/`resolved` con su productor real.
- Si el backend pasa a más de una instancia: hace falta un bus compartido para el reparto.
- Si aparece una pantalla que necesite el dato completo con latencia menor a la de una reconsulta REST — habría que medirlo antes de cambiar el diseño de "señal, no dato".
