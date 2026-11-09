# ADR-010 — Arquitectura base del frontend

**Estado:** Accepted

## Contexto

El stack (React + TypeScript) estaba congelado desde `docs/DECISIONS.md`, pero el frontend era solo un esqueleto: un `App.tsx` de bienvenida y un cliente HTTP sin uso. Antes de construir pantallas de negocio hay que fijar las decisiones transversales —sesión, rutas, obtención de datos, errores, formularios— porque cambiarlas después obliga a reescribir cada pantalla.

Este ADR registra esas decisiones y, sobre todo, **qué dependencias se agregaron y cuáles no**, dado que el proyecto exige justificar cada una.

## Decisión

### 1. Dependencias agregadas: dos

- **`react-router-dom`** — el enrutado de una SPA no es algo que convenga escribir a mano: manejar historial, rutas anidadas, redirecciones con estado y parámetros es un problema resuelto. Sin alternativa razonable.
- **`@tanstack/react-query`** — capa de obtención y caché de datos. La justificación no es genérica ("es lo que se usa"), sino específica de este sistema:
  1. **Encaja con el canal SSE ya aprobado.** ADR-009 decidió que el evento es una *señal* y que el cliente reconsulta REST. Eso es exactamente `invalidateQueries(clave)`. Sin una caché con claves, cada pantalla tendría que inventar su propio mecanismo de "recargar cuando llegue la señal".
  2. **Los estados de carga/error se piden reutilizables.** Query los expone de forma uniforme, en vez de que cada pantalla mantenga sus propios `useState` para lo mismo.
  3. **Reintentos con criterio.** Reintentar un 403 o un 422 no tiene sentido y reintentar una mutación puede duplicar efectos de negocio; la política queda definida una vez, en un solo lugar.

  Se adopta ahora y no "cuando haga falta" porque es precisamente la fase de arquitectura: retrofitear la caché con quince pantallas escritas cuesta mucho más.

### 2. Dependencia eliminada: `axios`

El cliente se reescribió sobre `fetch` nativo. Lo que se necesitaba —JSON, encabezados, cancelación, normalización de errores— lo cubre la plataforma, y el manejo de errores queda como una función legible en vez de repartido en configuración de interceptores. **Una dependencia menos, no una más.**

### 3. Dependencias NO agregadas: `react-hook-form` y `zod`

Hoy existe **un** formulario (login, dos campos). Añadir dos librerías para eso sería infraestructura sin uso. La estrategia actual: componente `Field` reutilizable + restricciones nativas de HTML, con validación limitada a la **forma** del dato.

Criterio explícito para reconsiderarlo: cuando lleguen los formularios con **líneas dinámicas** (venta, compra, transferencia), que traen arrays de ítems, validación cruzada entre campos y estado por campo. Ahí la dependencia se paga sola; hoy no.

### 4. Las reglas de negocio no se replican en el cliente

La validación de cliente cubre obligatoriedad y formato, nunca semántica de negocio (si alcanza el stock, si una transición de estado es válida, si un descuento es admisible). Esas reglas viven en el backend, que ya responde `422` con un código estable, y duplicarlas produciría dos verdades que se desincronizan en la primera modificación.

### 5. Autorización: el backend decide, la interfaz acompaña

Tres capas, con roles distintos y explícitos:

| Capa | Qué hace | Qué **no** es |
|---|---|---|
| Menú por rol (`permissions.ts`) | No ofrece lo que el rol no puede usar | Seguridad |
| Guardas de ruta (`ProtectedRoute`, `RequireRole`) | Evita aterrizar donde solo habría errores | Seguridad |
| Backend (`@PreAuthorize` + sucursal) | **Autoriza** | — |

Ocultar un botón nunca es la única protección: forzar la URL lleva a "Sin permiso", y si el backend responde `403` la interfaz lo muestra como falta de permisos **sin cerrar la sesión** (a diferencia del `401`, que sí la termina porque el token dejó de servir). Hay pruebas para ambos casos.

### 6. Sesión en `sessionStorage`

El backend emite el JWT en el cuerpo del login (ADR-005, stateless), así que el navegador debe guardarlo. Se eligió `sessionStorage` sobre `localStorage`: se borra al cerrar la pestaña, acotando la ventana de exposición, y sobrevive a un refresco para no reautenticar constantemente.

**Limitación asumida:** cualquier almacenamiento accesible por JavaScript es legible ante un XSS. La defensa sólida sería una cookie `HttpOnly`, lo que exige que el backend deje de devolver el token en el cuerpo — es decir, revisar ADR-005. Queda registrado, no resuelto en silencio.

### 7. Tipos del contrato escritos a mano, no generados

`docs/openapi.yaml` es explícitamente parcial (su propia sección 10: "inicial y representativa, no exhaustiva"). Un cliente generado quedaría incompleto justo en los recursos ausentes, con apariencia de completo. Cuando la especificación cubra todo el contrato, generarlos pasa a ser mejor opción que mantenerlos.

### 8. La URL de la API nunca se escribe en el código

Llega por `VITE_API_BASE_URL` y `config/env.ts` falla ruidosamente si falta: un bundle sin API configurada es inservible, y es mejor saberlo al arrancar que en la primera petición. Incluso las pruebas la reciben por configuración.

## Hallazgo durante la verificación: faltaba CORS en el backend

Al probar en un navegador real contra el backend real, **el login falló**: no existía configuración CORS, así que el navegador bloqueaba toda petición del frontend (origen `:3000`) hacia la API (origen `:8080`).

Ninguna prueba podía detectarlo: las del backend usan `TestRestTemplate` (sin navegador, sin CORS) y las del frontend simulan `fetch`. Solo aparece cuando un navegador de verdad habla con el servidor de verdad.

Se corrigió en `SecurityConfig` con orígenes permitidos por configuración (`app.cors.allowed-origins`, nunca `*`, nunca hardcodeados), declarando `Idempotency-Key` entre los encabezados aceptados —sin eso el *preflight* rechazaría las operaciones de creación repetible— y exponiendo `X-Request-Id` para poder correlacionar con los logs.

## Consecuencias positivas

- Añadir una pantalla es ahora mecánico: función de endpoint tipada + `useQuery` + `AsyncBoundary`, sin reinventar carga, vacío ni error.
- El punto de enganche del SSE está listo (`queryKeys` + `invalidateQueries`) sin haber acoplado nada todavía.
- Un `401` cierra sesión y un `403` no, de forma consistente en toda la aplicación, porque la decisión vive en el cliente HTTP y no en cada pantalla.

## Consecuencias negativas / trade-offs

- **Los tipos del contrato se mantienen a mano**: si el backend cambia un DTO y nadie actualiza `types/api.ts`, TypeScript no avisa. Mitigación real pendiente: completar el OpenAPI y generar.
- **Token legible por JavaScript** (punto 6).
- **Aviso de seguridad conocido y no resuelto:** `esbuild <= 0.24.2` (moderado, vía `vite`/`vitest`) afecta solo al **servidor de desarrollo**, no al bundle que sirve nginx en producción. Corregirlo exige subir a Vite 8, un salto mayor del toolchain que excede esta fase; queda como tarea de mantenimiento. El aviso de `react-router` sí se resolvió tomando la versión parcheada (7.x) al introducir la dependencia.
- **Sin librería de UI ni animaciones**: el acabado visual es deliberadamente mínimo, priorizando claridad y mantenibilidad.

## Criterios para reconsiderarla

- Formularios con líneas dinámicas → adoptar `react-hook-form` + `zod`.
- OpenAPI completo → generar los tipos en vez de mantenerlos.
- Necesidad de sesión resistente a XSS → cookie `HttpOnly`, revisando ADR-005.
- Crecimiento del bundle o de la complejidad de rutas → evaluar carga diferida por ruta (`React.lazy`), hoy innecesaria.
