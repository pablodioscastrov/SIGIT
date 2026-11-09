# ADR-002 — Java 21 + Spring Boot para el backend

**Estado:** Accepted

## Contexto

El backend debe centralizar toda la lógica de negocio (RT-002): validaciones de stock, cálculo de costo promedio ponderado (RF-016), flujo de estados de transferencia (RF-022 a RF-026), y autorización por rol sobre 11 módulos (RNF-003). Estas operaciones exigen transacciones atómicas fiables (ver `docs/ARCHITECTURE.md`, sección 7) y un modelo de seguridad declarativo por rol. El documento fuente deja el lenguaje/framework de backend libre, siempre que se justifique (RT-004, RT-005).

## Decisión

Se implementa el backend en Java 21 con Spring Boot, usando Spring Data JPA/Hibernate para persistencia y Spring Security para autenticación/autorización.

## Alternativas consideradas

- **Node.js/NestJS:** técnicamente capaz de resolver el mismo problema (decoradores similares a las anotaciones de Spring), pero para este alcance no aporta una ventaja concreta sobre Spring en materia de transacciones declarativas y RBAC integrado; habría que ensamblar manualmente piezas equivalentes (transacciones, guards de autorización) que Spring Boot ya integra de forma madura.
- **Python (Django/FastAPI):** Django resuelve CRUD rápido pero su modelo de transacciones y autorización granular por rol es menos directo para este dominio; FastAPI exigiría ensamblar a mano varias piezas (ORM transaccional, RBAC) que aquí se necesitan desde el primer módulo.
- **.NET/ASP.NET Core:** alternativa técnicamente equivalente en capacidades transaccionales y de seguridad; no elegida por continuidad con el ecosistema JVM ya fijado en la decisión de persistencia (Spring Data JPA/Hibernate), no por una ventaja técnica absoluta sobre Spring.

## Consecuencias positivas

- `@Transactional` declarativo resuelve directamente la atomicidad de venta, recepción de compra y pasos de transferencia sin código de control manual de transacciones.
- Spring Security con JWT y `@PreAuthorize` resuelve RBAC (RNF-003) sin construir un mecanismo de autorización a medida.
- Bean Validation reduce la validación estructural repetida en los 11 módulos del dominio.
- Spring Data JPA acelera el CRUD repetitivo (RF-005, gestión de catálogo/usuarios/sucursales), que el propio documento fuente sugiere acelerar con asistencia de IA.

## Consecuencias negativas / trade-offs

- Mayor verbosidad y ceremonia inicial (entidades, DTOs, mapeos) comparado con frameworks más ligeros.
- Tiempo de arranque del proceso más lento que alternativas más livianas; irrelevante para el alcance de esta prueba (no hay necesidad de cold-start tipo serverless).
- Todo el equipo que mantenga el proyecto necesita conocimiento del ecosistema JVM.

## Criterios para reconsiderarla

Si el dominio se simplificara a operaciones sin necesidad transaccional real (poco probable dado el negocio actual); si apareciera una restricción de despliegue que exigiera cold-start ultrarrápido tipo funciones serverless; o si el equipo real que fuera a mantener el proyecto no tuviera experiencia en JVM y el costo de aprendizaje superara el beneficio transaccional obtenido.
