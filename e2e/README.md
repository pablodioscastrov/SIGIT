# E2E — flujos mínimos (docs/TEST_STRATEGY.md §5)

Playwright, contra el stack real levantado con `docker compose up` — no hay `webServer` propio ni mocks: navegador real → API real → PostgreSQL real, exactamente lo que exige RT-003.

## Requisitos

- El stack debe estar arriba: `docker compose up -d` desde la raíz del repo.
- Node.js 18+.

## Ejecutar

```bash
cd e2e
npm install
npx playwright install chromium   # una sola vez
npm test
```

## Flujos cubiertos

- **A** — login → registrar compra → verificar inventario (`tests/A-purchase-to-inventory.spec.ts`).
- **B** — login → venta → verificar decremento y movimiento (`tests/B-sale-decrements-inventory.spec.ts`).
- **C** — solicitar transferencia → despachar → recibir → verificar ambos inventarios (`tests/C-transfer-full-cycle.spec.ts`).
- **D** — recepción parcial → faltante visible (`tests/D-transfer-partial-shortage.spec.ts`).

Cada prueba siembra sus propios datos por API (`tests/support/api.ts`) usando la cuenta ADMIN sembrada (`admin@inventario.local`) — incluye crear un MANAGER/OPERATOR propios por prueba en vez de depender de `gerente.centro@inventario.local`/`operador.centro@inventario.local`: esas cuentas de siembra son "solo para desarrollo" y ya se editaron a mano en este entorno durante fases anteriores del proyecto, así que su contraseña no es un supuesto seguro para una prueba automatizada. Los SKU/códigos de sucursal llevan un sufijo único por ejecución (`RUN_ID`) para poder re-ejecutar la suite contra el mismo volumen persistente de Postgres sin colisionar con datos de corridas anteriores — las cantidades que se verifican en cada aserción siguen siendo siempre fijas y deterministas.
