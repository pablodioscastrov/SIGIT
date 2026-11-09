# ADR-008 — Trazabilidad mediante InventoryMovement append-only

**Estado:** Accepted

## Contexto

Todo ingreso o retiro de inventario debe quedar registrado con fecha, responsable, motivo y cantidad, formando un historial auditable (RF-009, BR-001). Varios módulos distintos generan movimientos de inventario por razones diferentes — compras (RF-014), ventas (RF-008), transferencias (RF-025, RF-026) y ajustes manuales — sin que `inventory` deba conocer los detalles internos de cada uno de esos módulos (ver dependencias permitidas en `docs/ARCHITECTURE.md`, sección 4).

## Decisión

El inventario se modela con dos piezas complementarias: una entidad de **stock agregado** (cantidad actual por producto y sucursal) y una entidad **`InventoryMovement`** de solo-inserción (append-only) que registra cada cambio individual con motivo, cantidad, responsable y fecha. El stock agregado se actualiza siempre a partir de un movimiento; nunca se edita de forma directa e independiente.

## Alternativas consideradas

- **Guardar solo el stock agregado, sin historial de movimientos:** rechazada porque no cumple RF-009/BR-001, que exigen trazabilidad de cada cambio individual, no solo el valor final.
- **Calcular el stock siempre on-the-fly como suma de todo el historial, sin columna de stock agregado:** rechazada para este alcance porque recalcular sumando todo el histórico en cada consulta degradaría el rendimiento de lectura a medida que el historial crece, sin aportar una garantía de auditabilidad adicional frente a mantener un agregado sincronizado con movimientos append-only.
- **Permitir editar o eliminar un movimiento ya registrado para "corregir" errores:** rechazada porque rompe la auditabilidad exigida; una corrección debe registrarse como un nuevo movimiento compensatorio, nunca alterando el histórico existente.

## Consecuencias positivas

- La trazabilidad exigida por RF-009 queda garantizada por el propio modelo de datos, no depende de que cada desarrollador recuerde registrar el movimiento por separado.
- Un mismo mecanismo de movimiento sirve a compras, ventas, transferencias y ajustes, evitando que `inventory` dependa de esos módulos (la dependencia va en sentido contrario, según `docs/ARCHITECTURE.md`).
- El stock agregado permite lecturas rápidas (catálogo, dashboard) sin recalcular el histórico completo en cada consulta.

## Consecuencias negativas / trade-offs

- Mantener el stock agregado sincronizado con los movimientos append-only exige que toda escritura de stock pase por el mismo camino transaccional; esa disciplina debe reforzarse en la capa de servicio de `inventory` (nunca un `UPDATE` directo al agregado sin su movimiento correspondiente), no queda garantizada solo por el diseño del modelo de datos.
- El historial de movimientos crece indefinidamente, sin política de purga; aceptable para el alcance de esta prueba, pero requeriría una estrategia de archivado en un sistema de producción de larga vida.

## Criterios para reconsiderarla

Si el volumen de movimientos históricos creciera hasta un punto donde mantener el agregado sincronizado o consultar el historial se demuestre, con medición real, como una carga de rendimiento significativa, se evaluaría una estrategia de agregación periódica (snapshots) en lugar de sincronizar en cada escritura. No se anticipa esta necesidad para el alcance de esta prueba.
