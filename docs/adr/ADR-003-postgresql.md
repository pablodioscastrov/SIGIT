# ADR-003 — PostgreSQL como motor de base de datos

**Estado:** Accepted

## Contexto

El dominio es altamente relacional: sucursal, producto, movimiento de inventario, orden de compra, venta y transferencia se relacionan mediante claves foráneas explícitas y requieren integridad referencial estricta. El riesgo de dominio más crítico identificado (`docs/PROJECT_BRIEF.md`, sección 9) es la concurrencia sobre el mismo stock, que exige control transaccional y de bloqueo fiable (RNF-006). El documento fuente permite base de datos relacional o NoSQL, a elección justificada (RT-004).

## Decisión

Se usa PostgreSQL como único motor de base de datos, con Spring Data JPA/Hibernate como capa de acceso.

## Alternativas consideradas

- **MySQL/MariaDB:** alternativa relacional viable; no elegida porque PostgreSQL ofrece soporte más maduro de constraints avanzados y tipos de datos flexibles (JSON, arrays) útiles si se necesitara flexibilidad puntual en atributos de producto sin abandonar el modelo relacional, además de un control de concurrencia (MVCC) bien documentado para el caso de uso de bloqueo de stock.
- **MongoDB / NoSQL documental:** rechazada porque el dominio no es de documentos semi-estructurados sino de entidades con relaciones e integridad transaccional fuertes; modelarlo en Mongo obligaría a reconstruir a mano integridad referencial y control de concurrencia que PostgreSQL da nativamente, contradiciendo directamente el requisito de consistencia (RNF-006).
- **SQLite:** rechazada por no soportar bien concurrencia de escritura multi-conexión, inadecuada incluso para el alcance de una demo con varias sucursales y usuarios operando simultáneamente.

## Consecuencias positivas

- Constraints (`FOREIGN KEY`, `CHECK`, `UNIQUE`, `NOT NULL`) actúan como última línea de defensa de integridad, complementando la validación de negocio en el backend (`docs/ARCHITECTURE.md`, sección 6).
- Bloqueo a nivel de fila (`SELECT ... FOR UPDATE`) y control optimista mediante columna de versión están disponibles de forma nativa para resolver la concurrencia sobre el mismo stock (`docs/ARCHITECTURE.md`, sección 7).
- Ecosistema maduro y bien soportado por Spring Data JPA/Hibernate.

## Consecuencias negativas / trade-offs

- El escalado horizontal de escritura no es trivial; no se requiere para el alcance de esta prueba.
- Una única instancia sin réplica es un punto único de fallo — aceptado explícitamente para el alcance de prueba/demo, no válido para un entorno de producción real.
- El esquema requiere una herramienta de migración controlada (Flyway o Liquibase, decisión pendiente en `docs/STATUS.md`) para evolucionar sin downtime.

## Criterios para reconsiderarla

Si apareciera una necesidad real de escribir volúmenes masivos con baja latencia que un solo nodo relacional no pudiera sostener; si el modelo de datos se volviera predominantemente no relacional (poco probable dado el dominio actual); o si se requiriera operación multi-región con baja latencia de escritura en cada región.
