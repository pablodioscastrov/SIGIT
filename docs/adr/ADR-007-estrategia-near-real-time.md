# ADR-007 — SSE como estrategia near-real-time

**Estado:** Accepted

## Contexto

El inventario debe compartirse entre sucursales en tiempo real o near-real-time (RF-002, RNF-001), y las alertas de stock mínimo deben notificarse a los usuarios autorizados (RF-010, RF-036). En todos los casos la comunicación es unidireccional: el servidor informa que algo cambió; el cliente nunca necesita enviar datos continuos por ese mismo canal — toda escritura del usuario ya ocurre por REST (`docs/USE_CASES.md`).

## Decisión

Se usa Server-Sent Events (SSE) como mecanismo de notificación near-real-time. El evento SSE es una señal ligera ("cambió el inventario de la sucursal X", "hay una nueva alerta"), no el payload completo de negocio: al recibirla, el cliente vuelve a consultar la API REST para obtener el dato autoritativo. WebSocket queda descartado mientras no exista una necesidad bidireccional real.

## Alternativas consideradas

- **WebSocket:** rechazada por ahora. Es bidireccional, pero ninguna funcionalidad actual requiere que el cliente envíe datos por el mismo canal persistente. Adoptarlo hoy añadiría complejidad de protocolo (framing, heartbeats manuales, gestión de reconexión propia) sin resolver una necesidad presente.
- **Polling periódico desde el cliente:** rechazada porque, para lograr una latencia "near-real-time" genuina, obligaría a un intervalo corto que multiplica peticiones a la API incluso cuando no hay cambios — desperdicio de recursos que SSE evita al notificar solo ante un evento real.
- **Mensajería con broker externo (Kafka/RabbitMQ):** rechazada explícitamente. El volumen de eventos de esta prueba (cambios de stock y alertas de un número acotado de sucursales) no justifica introducir infraestructura de mensajería distribuida, y contradice el principio del proyecto de no incorporar Kafka/RabbitMQ sin una necesidad concreta demostrada.

## Consecuencias positivas

- Protocolo nativo sobre HTTP, sin infraestructura adicional que agregar a Docker Compose.
- Reconexión automática del lado del cliente mediante `EventSource`, sin código propio de resiliencia de conexión.
- El evento como señal (no como payload completo) evita una segunda fuente de verdad: REST + PostgreSQL siguen siendo el único origen autoritativo de datos.

## Consecuencias negativas / trade-offs

- SSE es unidireccional: si en el futuro aparece un caso de uso genuinamente bidireccional, habrá que migrar ese caso puntual a WebSocket.
- Cada conexión SSE abierta consume un hilo/recurso en el proceso Spring Boot; aceptable para el volumen de usuarios de esta prueba, no validado a mayor escala.

## Criterios para reconsiderarla

Si aparece un caso de uso real que requiera que el cliente también envíe datos de forma continua por el mismo canal persistente (p. ej. edición colaborativa en tiempo real de una misma solicitud de transferencia por varios usuarios); o si el número de conexiones SSE concurrentes creciera hasta un punto donde el modelo de un hilo/recurso por conexión se demuestre, con medición real, como un cuello de botella.
