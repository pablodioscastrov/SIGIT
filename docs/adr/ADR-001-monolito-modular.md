# ADR-001 — Monolito modular

**Estado:** Accepted

## Contexto

El dominio (sucursales, productos, inventario, compras, ventas, transferencias, logística) tiene operaciones fuertemente cohesivas desde el punto de vista transaccional: una venta debe validar y descontar stock de forma atómica (RF-019), una recepción de compra debe actualizar inventario y costo promedio ponderado de forma atómica (RF-014, RF-016), y una transferencia toca el inventario de dos sucursales dentro de la misma base de datos (RF-025, RF-026). El documento fuente permite microservicios pero exige justificar cualquier elección de stack/arquitectura (RT-004), y explícitamente advierte contra introducir complejidad distribuida sin necesidad concreta. El alcance es una prueba técnica con equipo y tiempo acotados, no un sistema en producción a gran escala.

## Decisión

El backend se implementa como un monolito modular: un único proceso Spring Boot desplegable, organizado internamente en módulos cohesivos por dominio (auth, users, branches, products, inventory, purchases, sales, transfers, logistics, dashboard, reports), con dependencias unidireccionales explícitas entre ellos (ver `docs/ARCHITECTURE.md`, sección 4) y una única base de datos PostgreSQL compartida lógicamente particionada por módulo.

## Alternativas consideradas

- **Microservicios por módulo:** rechazada para este alcance. Operaciones que hoy son una única transacción SQL (venta, recepción de compra) pasarían a requerir sagas o consistencia eventual entre servicios, resolviendo con complejidad distribuida un problema que hoy no la tiene. La sobrecarga operativa de N contenedores, descubrimiento de servicios y despliegue independiente no se justifica con el volumen y el equipo de esta prueba.
- **Monolito sin modularización interna:** rechazada porque dificultaría la mantenibilidad y la trazabilidad de dependencias entre dominios, contradiciendo el criterio de evaluación explícito de "solidez de la arquitectura" del documento fuente.

## Consecuencias positivas

- Las operaciones críticas de negocio son transacciones ACID simples, sin necesidad de coordinar consistencia entre servicios.
- Despliegue único y simple, coherente con el requisito de levantar todo con `docker compose up` (RT-003).
- Los límites de módulo ya definidos dejan la puerta abierta a una extracción futura sin rediseñar la lógica de negocio, si una necesidad concreta lo justifica.

## Consecuencias negativas / trade-offs

- Riesgo de acoplamiento accidental entre módulos si no se disciplina el acceso a datos; se mitiga con las reglas de dependencia explícitas de `docs/ARCHITECTURE.md` y revisión de código, no por una barrera técnica automática.
- El escalado es del proceso completo, no por módulo: no se puede escalar solo `sales` sin escalar todo el backend.
- Un fallo severo no aislado (p. ej. una fuga de memoria en `dashboard`) corre en el mismo proceso que las operaciones críticas (`sales`, `inventory`).

## Criterios para reconsiderarla

Las señales concretas que justificarían extraer un módulo a servicio independiente están documentadas en `docs/ARCHITECTURE.md` (sección "Señales concretas para extraer un módulo a servicio independiente"): necesidad de escalado/tecnología radicalmente distinta por módulo, cadencia de despliegue independiente, volumen de datos que exija otro motor de almacenamiento, reutilización real desde otro sistema, crecimiento del equipo que supere el costo de coordinación en un solo repositorio, o necesidad de aislar el radio de fallo. Ninguna de estas señales está presente hoy.
