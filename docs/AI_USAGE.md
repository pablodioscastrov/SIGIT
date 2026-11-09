# Uso de Inteligencia Artificial

## 1. Objetivo

Este documento registra de forma transparente cómo se utilizaron herramientas de inteligencia artificial durante el desarrollo de este proyecto — no como una nota de cumplimiento genérica, sino con ejemplos concretos, verificables contra `docs/STATUS.md` y el historial de este repositorio.

El objetivo es demostrar:

- dirección humana en cada fase (alcance explícito, criterios de aceptación, aprobación antes de avanzar);
- criterio técnico aplicado a lo que la IA propuso (aceptado, ajustado o rechazado);
- verificación real, no solo "la prueba automática pasó" — varios bugs reales de este proyecto solo aparecieron al verificar en vivo contra PostgreSQL/Docker/navegador real, nunca en la suite automática;
- qué requirió ajuste manual;
- dónde la IA no fue útil o propuso algo que se descartó.

La IA funcionó como herramienta de asistencia dirigida, nunca como fuente automática de verdad ni como ejecutora de instrucciones abiertas tipo "haz todo el sistema". Cada fase tuvo alcance explícito, y `CLAUDE.md` (instrucciones del proyecto, seguidas durante todo el desarrollo) impone reglas concretas que hicieron cumplir ese límite: trabajar solo en la fase pedida, detenerse cuando el objetivo está completo, no cambiar el stack/arquitectura/contratos sin exponer el problema y pedir aprobación, no añadir dependencias sin justificación, no refactorizar código no relacionado con la tarea.

## 2. Herramientas

### ChatGPT

Usado en la fase inicial, antes de escribir código: interpretar el enunciado de la prueba técnica, analizar alternativas tecnológicas, seleccionar el stack, esbozar la arquitectura y preparar la documentación de partida (`docs/PROJECT_BRIEF.md`, `docs/REQUIREMENTS_TRACEABILITY.md` en su primera versión).

### Claude Code (Claude, principal herramienta de desarrollo)

Herramienta principal desde el diseño detallado hasta el estado actual: análisis de requisitos, arquitectura, modelo de dominio, reglas de negocio, implementación de backend y frontend módulo por módulo, pruebas (unitarias, integración, concurrencia real, E2E), auditoría de seguridad, y esta misma documentación final.

Capacidades concretas usadas, no solo generación de texto:

- Lectura y edición directa de archivos del repositorio (código, migraciones, documentación).
- Ejecución de comandos reales — `mvn test`, `npm test`, `docker compose build/up`, `curl` contra la API real, `git` — para verificar en vivo, no solo generar código que "debería funcionar".
- Un agente de exploración (subagente de solo lectura) para auditorías de superficie amplia — por ejemplo, recorrer los 17 `Create*/Update*Request` del backend en busca de mass assignment, o construir la matriz completa de `@PreAuthorize` por controlador — que habría sido impracticable de pedir en una sola conversación lineal sin agotar contexto.
- Interacción con un navegador real (Playwright) para las pruebas E2E y para reproducir en vivo hallazgos de seguridad (JWT manipulado, acceso cruzado de sucursal, inyección de precio).
- Entornos Docker desechables y aislados para verificar "¿esto funciona desde cero?" sin arriesgar los datos del entorno de desarrollo real (ver sección 4, verificación del README).

## 3. Metodología

Nunca se usaron instrucciones abiertas del tipo "haz todo el sistema". Cada fase siguió:

```text
Contexto (qué ya existe, qué documento lo rige)
   ↓
Objetivo pequeño y explícito (una fase, un módulo, un tipo de prueba)
   ↓
Análisis (leer el código/documento real antes de proponer nada)
   ↓
Implementación
   ↓
Pruebas (automatizadas + verificación en vivo cuando aplica)
   ↓
Revisión / hallazgos
   ↓
Corrección
   ↓
Documentación (`docs/STATUS.md` se actualiza en cada fase, no al final)
```

`docs/STATUS.md` es la fuente de verdad de esta metodología aplicada: cada entrada de su sección "Completado" es una fase real, con lo que se hizo, lo que se verificó y — cuando aplica — el bug real encontrado y cómo se corrigió.

## 4. Etapas del desarrollo asistido por IA

1. **Interpretación de requisitos y selección de stack** (ChatGPT, luego formalizado con Claude): `docs/PROJECT_BRIEF.md`, `docs/DECISIONS.md`, matriz de trazabilidad.
2. **Diseño previo a la implementación**: arquitectura (`docs/ARCHITECTURE.md`), modelo de dominio y ER (`docs/DOMAIN_MODEL.md`), reglas de negocio (`docs/BUSINESS_RULES.md`), flujos críticos en pseudocódigo, contrato de API (`docs/API_DESIGN.md`, `docs/openapi.yaml`) — con ADRs dedicados para cada decisión no trivial.
3. **Backend módulo por módulo**, en el orden de dependencias del propio dominio (`auth` → `branches`/`users` → `products` → `inventory` → `suppliers`/`purchases` → `sales` → `transfers` → `logistics`/`reports` → SSE → `dashboard` → alertas de stock), cada uno con su propia ronda de pruebas antes de avanzar al siguiente.
4. **Frontend**: arquitectura base y luego una pantalla/módulo a la vez, siempre contra la API real (nunca mocks) para la verificación final de cada fase.
5. **Rondas de ajustes puntuales explícitos** sobre módulos ya entregados (ampliación de permisos por rol, correcciones de UX/CSS, campos editables que no lo eran) — trece rondas documentadas en `docs/STATUS.md`, cada una acotada por instrucción explícita, nunca iniciativa propia de la IA.
6. **Reportes exportables en Excel** (BR-056), backend y luego frontend en fases separadas.
7. **Estrategia de pruebas formalizada** (`docs/TEST_STRATEGY.md`) — clasificación, matriz riesgo × prueba, identificación de las brechas reales frente a la suite ya existente (no reinventar lo que ya estaba probado), y cierre de esas brechas: variantes de concurrencia contra PostgreSQL real (antes solo H2) y una suite E2E nueva con Playwright (antes inexistente).
8. **Pase dirigido de confiabilidad/concurrencia**: 8 escenarios mínimos con hilos reales, no HTTP secuencial — encontró un interbloqueo real de base de datos (sección 5).
9. **Auditoría de seguridad**: revisión de código más pruebas negativas en vivo contra el stack real, en dos pasos explícitos — primero solo el informe ("no modifiques código hasta que yo apruebe los hallazgos"), después la corrección de todo lo aprobado.
10. **Esta documentación final** — README y este mismo archivo, verificados contra el código real antes de publicarse (sección 6).

## 5. Ejemplos representativos de prompts y resultados obtenidos

Las siguientes instrucciones son literales de esta misma sesión (no parafraseadas), elegidas por ser representativas del patrón de todo el desarrollo: rol + alcance explícito + restricción explícita.

> Actúa como líder de QA y define/ajusta la estrategia de pruebas del proyecto completo. [...] Implementa únicamente las pruebas faltantes aprobadas, sin refactor de producto no relacionado.

**Resultado:** `docs/TEST_STRATEGY.md` nuevo, con la clasificación pedida y la matriz riesgo × prueba por módulo. La propia estrategia identificó que la suite ya existente (304 pruebas de backend) era muy sólida y que las brechas reales eran solo dos: pruebas de concurrencia solo contra H2 (nunca contra PostgreSQL real) y ausencia total de E2E. Antes de tocar código se preguntó explícitamente al usuario si aprobaba cerrar esas dos brechas (una de ellas, agregar Playwright, es una dependencia nueva — no se agrega sin ese paso, por regla de `CLAUDE.md`).

> Actúa como especialista en concurrencia y confiabilidad. Diseña y ejecuta pruebas específicas sobre operaciones críticas. [...] Si encuentras un fallo, no lo ocultes con un retry indiscriminado. Explica la causa raíz y propone la corrección mínima.

**Resultado:** 8 escenarios con hilos reales (`ExecutorService`/`CountDownLatch`), 5 de ellos nuevos. Uno de esos escenarios (dos operaciones concurrentes sobre los mismos dos productos en orden opuesto) expuso un **interbloqueo real de base de datos** en `SaleService`/`PurchaseReceiptService`/`TransferService` — no un fallo de la prueba. Causa raíz: las cuatro operaciones recorrían las líneas de una venta/recepción/transferencia multi-producto en el orden en que llegaban en el payload, sin ordenarlas; dos transacciones concurrentes sobre los mismos dos productos en orden opuesto podían retener cada una el lock de fila que la otra necesitaba. Corrección mínima aplicada: ordenar las líneas por `productId` antes de tocar `Inventory`, en los cuatro métodos — prevención estándar de interbloqueo por orden global consistente, sin agregar reintentos nuevos ni cambiar ninguna regla de negocio.

> Actúa como revisor de seguridad de aplicaciones. Audita el sistema sin introducir cambios todavía. [...] No modifiques código hasta que yo apruebe los hallazgos.

**Resultado:** informe con severidad/evidencia/impacto/corrección mínima por hallazgo, incluyendo pruebas negativas ejecutadas en vivo (no solo leídas en el código): acceso cruzado de sucursal, transición de estado no autorizada, JWT manipulado, inyección de precio en una venta, IDOR de lectura y escritura — los 9 casos probados se comportaron correctamente. Encontró y **demostró en vivo** (no solo en teoría) que una recepción de compra con un costo unitario absurdo corrompía permanentemente el costo promedio ponderado del inventario, sin ningún límite. Ningún cambio se aplicó hasta la siguiente instrucción explícita.

> Corrige todo.

**Resultado:** las 7 correcciones del informe (5 medias, 2 bajas), cada una con su prueba de regresión donde aplicaba, verificadas no solo con `mvn test` sino reconstruyendo y reiniciando los contenedores reales de Docker y reproduciendo en vivo cada corrección (ver sección 6 para el nivel de verificación exigido). Tres hallazgos bajos del propio informe **no** se tocaron por ir en contra de una decisión ya vigente y documentada (sin revocación de JWT, alcance de `GET /inventory-movements`, autorización de `originBranchId` en una transferencia) — se dejaron señalados, no "corregidos" a la fuerza.

## 6. Qué requirió ajuste manual / dirección explícita

- **Ninguna corrección de seguridad o concurrencia se aplicó sin que el usuario la aprobara primero** — la auditoría de seguridad se pidió explícitamente en dos pasos (informe, luego "corrige todo"); la decisión de agregar PostgreSQL real a las pruebas de concurrencia y de agregar Playwright se sometió a aprobación explícita antes de tocar código, por tratarse de infraestructura/dependencias nuevas.
- **Umbral de tolerancia de precio en la recepción de compra** (rechazar un costo que se aleje más de 3× del pactado): es una propuesta razonable de la IA para evitar la corrupción del costo promedio ya demostrada en vivo, pero el valor exacto es una decisión de negocio que no le corresponde a la IA fijar por sí sola — se documentó explícitamente como provisional, pendiente de confirmación (ver README, "Limitaciones y deuda conocida").
- **Credenciales de siembra sembradas en migraciones desde el inicio del proyecto se editaron a mano durante fases de verificación manual** (STATUS.md lo documenta: la contraseña de `gerente.centro@inventario.local` dejó de coincidir con la sembrada por la migración) — la suite de pruebas E2E nueva no podía asumir que esas credenciales seguían siendo válidas; se corrigió creando usuarios de prueba propios por cada flujo E2E en vez de depender de datos de siembra mutables.
- **Actualización mayor de `vite`/`vitest`** (5→8, 2→4, por una vulnerabilidad del toolchain de desarrollo): la primera verificación local (`npm install` + `vite build`) pasó, pero un `npm ci` estricto dentro de la imagen Docker real falló por un conflicto de dependencia entre pares (`@vitejs/plugin-react` no soportaba aún Vite 8) — solo se detectó reconstruyendo la imagen real, no con la verificación local inicial. Se corrigió actualizando también esa dependencia y reverificando con `npm ci` limpio antes de dar la corrección por cerrada.
- **`docs/STATUS.md` documenta una interferencia externa recurrente**: más de una vez, archivos ya editados y verificados por la IA (`docs/STATUS.md`, `docs/BUSINESS_RULES.md`, `frontend/src/styles.css`, un lote completo de pruebas) revirtieron a un estado anterior sin que la IA ejecutara esa reversión — coincide con una herramienta externa que hace commits automáticos con mensajes genéricos. Cada vez que se detectó, el trabajo se rehizo y se reverificó; se deja registrado aquí porque es exactamente el tipo de fricción real que este documento debe reflejar, no ocultar.

## 7. Dónde la IA no fue útil o propuso algo que se rechazó/ajustó

- El propio proceso de esta sesión tuvo un desvío menor: para esperar a que terminara una reconstrucción de la imagen Docker en segundo plano, se usó por error una herramienta de "despertar" pensada para el modo de bucle dinámico (`/loop`), no para esperar una tarea puntual — generó un ciclo innecesario que se detuvo en cuanto se detectó, sin impacto en el resultado final, pero es un ejemplo real de una elección de herramienta equivocada que un desarrollador humano tuvo que corregir sobre la marcha.
- Varias ambigüedades del enunciado original de la prueba técnica (p. ej. quién aprueba formalmente una transferencia, o quién autoriza el tratamiento de un faltante) **no se resolvieron por decisión unilateral de la IA**: quedaron marcadas explícitamente como `[Supuesto, pendiente de confirmación]` en `docs/PROJECT_BRIEF.md`/`docs/USE_CASES.md`, con la interpretación más razonable aplicada mientras tanto — reconocer el límite de la propia autoridad para decidir una regla de negocio, en vez de inventar una respuesta con apariencia de certeza.
- La sugerencia por defecto de `npm audit fix --force` (saltar directamente a las versiones más nuevas de `vite`/`vitest`) no se aceptó a ciegas: se ejecutó, se verificó con la suite completa y con un `npm ci` limpio antes de darla por buena — el primer intento reveló el conflicto de dependencias ya descrito en la sección 6, así que "lo que propuso `npm audit fix`" no fue, por sí solo, la corrección final.

## 8. Estimación razonable de asistencia

- **Código (backend, frontend, pruebas):** la gran mayoría del volumen de líneas fue redactado por la IA, pero dentro de límites de alcance fijados por instrucción humana en cada fase (nunca "construye el sistema completo") y con verificación humana del resultado en cada punto de corte — revisión de qué se implementó, ejecución de las pruebas, y en las fases de mayor riesgo (seguridad, concurrencia), verificación en vivo contra contenedores reales antes de aceptar el trabajo como terminado.
- **Documentación:** de redacción similar — mayormente generada por la IA, pero contrastada explícitamente contra el estado real del código antes de publicarse (esta misma actualización de README/AI_USAGE.md se verificó levantando el proyecto completo desde un entorno aislado y limpio, con datos de siembra reales, antes de describir el flujo como funcional — ver README, sección "Levantar el proyecto").
- **Decisiones de arquitectura, alcance y negocio:** dirigidas por instrucción humana explícita en el origen (`docs/PROJECT_BRIEF.md`, `docs/DECISIONS.md`, `CLAUDE.md`) — la IA aplicó esas decisiones y señaló cuándo una nueva situación (una dependencia nueva, un cambio de comportamiento de seguridad) requería salirse de ese marco y pedir aprobación, en vez de decidir por su cuenta.

En síntesis: la IA fue la principal herramienta de producción de código y documentación de este proyecto, pero ninguna fase avanzó sin un objetivo humano explícito, y ningún resultado se aceptó sin verificación — varias de las correcciones más importantes de este proyecto (el interbloqueo real, la corrupción del costo promedio, el conflicto de dependencias tras la actualización de Vite) solo se descubrieron porque esa verificación fue real (base de datos real, contenedores reales, navegador real), no solo la ejecución de una suite de pruebas ya en verde.
