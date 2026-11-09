# ADR-005 — JWT + RBAC para autenticación y autorización

**Estado:** Accepted

## Contexto

El sistema define tres roles con permisos diferenciados de lectura/escritura/aprobación por sucursal y por módulo (Administrador general, Gerente de sucursal, Operador de inventario — ver `docs/USE_CASES.md`, matriz Actor×Acción). El backend corre como un único proceso desplegado en contenedores Docker independientes (RT-003), sin un requisito explícito de integración con un proveedor de identidad externo.

## Decisión

Autenticación mediante JWT emitido por el propio backend; autorización mediante RBAC con tres roles (ADMIN, MANAGER, OPERATOR), aplicados de forma declarativa sobre los endpoints de cada módulo.

## Alternativas consideradas

- **Sesiones de servidor con estado (cookie + almacén de sesión):** rechazada porque obligaría a compartir estado de sesión si el backend llegara a escalar horizontalmente o requeriría sesión pegajosa; JWT es stateless y encaja mejor con el despliegue en contenedores independientes descrito en `docs/ARCHITECTURE.md`.
- **OAuth2/OpenID Connect con proveedor de identidad externo:** rechazada para este alcance porque añadiría un componente de infraestructura adicional (servidor de autorización) no justificado para tres roles internos de una sola organización. Sería la elección correcta si en el futuro se requiriera SSO corporativo o autenticación del actor "Sistema externo" (RF-040).
- **ABAC (control de acceso basado en atributos):** rechazada porque todos los permisos observados en la matriz Actor×Acción se explican completamente con tres roles fijos más el alcance por sucursal del usuario; un modelo de atributos añadiría flexibilidad que ningún requisito actual solicita.

## Consecuencias positivas

- Autorización declarativa a nivel de método (`@PreAuthorize` o equivalente) reduce código repetido de control de acceso en los 11 módulos del backend.
- JWT stateless encaja de forma natural con el despliegue en contenedores independientes.
- Tres roles cubren exactamente la matriz Actor×Acción documentada, sin sobredimensionar el modelo de permisos.

## Consecuencias negativas / trade-offs

- La revocación inmediata de un token no es nativa: un usuario desactivado sigue siendo válido hasta que su JWT expire. Se mitiga con tiempos de expiración cortos; una lista de revocación explícita queda como decisión pospuesta (`docs/ARCHITECTURE.md`).
- RBAC puro no captura por sí solo matices como "el Gerente ve la comparativa entre sucursales pero el Operador no, y además cada uno solo escribe en su propia sucursal" — eso requiere una comprobación adicional de alcance por sucursal en el propio servicio, no solo la verificación de rol.

## Criterios para reconsiderarla

Si aparecen combinaciones de permisos que ya no se explican con tres roles fijos (necesidad real de ABAC); si se requiere integración SSO corporativa o de terceros (justificaría OAuth2/OIDC); o si se establece como requisito explícito la revocación inmediata de sesión (justificaría sesiones con estado o una lista de revocación de tokens).
