# ADR-006 — Docker Compose para orquestación local

**Estado:** Accepted

## Contexto

El documento fuente exige que el sistema completo se levante con un solo comando, sin dependencias de configuración manual en el entorno local (RT-003), y que frontend, backend y base de datos corran como servicios separados (RT-001). El entorno objetivo es el de un evaluador ejecutando el proyecto en su propia máquina, no una plataforma cloud gestionada.

## Decisión

Se usa Docker Compose para levantar los tres contenedores del sistema (frontend, backend, PostgreSQL) mediante `docker compose up`.

## Alternativas consideradas

- **Kubernetes:** rechazada explícitamente. Está pensado para múltiples réplicas, auto-escalado y despliegues distribuidos; para tres contenedores en un entorno de evaluación local es infraestructura sobredimensionada que contradice el principio de no introducir complejidad sin necesidad concreta.
- **Instalación manual sin contenedores (scripts):** rechazada porque no cumple el requisito explícito de "sin dependencias de configuración manual" y dificulta la reproducibilidad entre distintas máquinas de evaluación.
- **Ejecución nativa en el host (Java y Node instalados directamente):** rechazada porque exigiría que el evaluador tenga instaladas exactamente las mismas versiones de Java 21, Node y PostgreSQL que el proyecto usa, rompiendo la reproducibilidad exigida por RT-003.

## Consecuencias positivas

- Reproducibilidad exacta entre máquinas distintas ("funciona igual en la mía").
- Cumple literalmente el requisito de un solo comando (RT-003).
- Aísla las versiones de las dependencias (Java, Node, PostgreSQL) del entorno del evaluador, evitando conflictos de versión.

## Consecuencias negativas / trade-offs

- Docker Compose no gestiona alta disponibilidad, replicación ni actualizaciones sin downtime; irrelevante para el alcance de esta prueba, relevante solo si el sistema fuera a producción.
- El primer arranque incluye el tiempo de construcción de imágenes, añadiendo latencia inicial que no se repite en arranques posteriores.

## Criterios para reconsiderarla

Si el sistema debiera desplegarse en un entorno de producción real con múltiples instancias, alta disponibilidad o despliegues sin downtime. En ese momento, Kubernetes u otra plataforma de orquestación tendría una justificación concreta y medible, no especulativa como hoy.
