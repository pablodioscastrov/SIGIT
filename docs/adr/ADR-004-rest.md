# ADR-004 — REST como estilo de comunicación frontend-backend

**Estado:** Accepted

## Contexto

El frontend debe comunicarse con el backend exclusivamente mediante una API bien definida, REST o GraphQL a elección (RT-002). Los recursos del dominio (productos, movimientos de inventario, órdenes de compra, ventas, transferencias) tienen operaciones bien delimitadas de creación, consulta, actualización y transición de estado. El frontend es una SPA administrativa con pantallas fijas por módulo (`docs/PROJECT_BRIEF.md`, sección 3), no un cliente que necesite componer consultas anidadas y variables sobre el mismo endpoint.

## Decisión

La API se implementa como REST.

## Alternativas consideradas

- **GraphQL:** rechazada para este alcance. GraphQL aporta valor cuando distintos clientes necesitan consultas flexibles y anidadas sobre el mismo grafo de datos; aquí hay un único cliente (la SPA) con pantallas predecibles por módulo. Adoptar GraphQL añadiría una capa de resolución y un esquema adicional sin resolver un problema real presente en este alcance.
- **gRPC:** rechazada porque está orientado a comunicación servicio-a-servicio de alto rendimiento; el consumidor real es un navegador vía SPA, y gRPC-Web exigiría un proxy adicional que no aporta valor frente a REST para este caso.

## Consecuencias positivas

- Contratos por recurso simples y documentables con OpenAPI, alineados con el mapeo natural del dominio a recursos.
- Combina directamente con códigos de estado HTTP para el manejo de errores (`docs/ARCHITECTURE.md`, sección 8) y con SSE sobre el mismo protocolo HTTP (`docs/ARCHITECTURE.md`, sección 9), sin introducir un segundo protocolo.
- Herramientas de prueba y depuración ampliamente conocidas (curl, Postman, pruebas de integración HTTP estándar).

## Consecuencias negativas / trade-offs

- Riesgo puntual de sobre-fetching o under-fetching en pantallas que combinan datos de varios recursos (p. ej. el dashboard); se mitiga con endpoints de agregación específicos en lugar de forzar múltiples llamadas al cliente.
- El versionado de contrato es manual, sin la introspección de esquema que ofrece GraphQL.

## Criterios para reconsiderarla

Si el frontend evolucionara hacia clientes con necesidades de consulta muy variables entre sí (p. ej. una app móvil con requerimientos de datos sustancialmente distintos al panel web) y se demostrara sobre-fetching significativo y medible que REST no pueda mitigar razonablemente con endpoints de agregación.
