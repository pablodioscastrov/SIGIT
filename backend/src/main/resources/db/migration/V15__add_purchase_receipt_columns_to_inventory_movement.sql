-- Resuelve la decision pendiente #6 de docs/BUSINESS_RULES.md ("Ajustes
-- pendientes al modelo de dominio"): InventoryMovement.idempotency_key,
-- necesaria porque una recepcion de compra es una operacion de creacion
-- repetible (categoria 2, docs/CRITICAL_FLOWS.md seccion 1.1) - el estado de
-- la orden (PARTIALLY_RECEIVED sigue siendo valido para recibir de nuevo) no
-- distingue por si solo un reintento de la siguiente recepcion legitima.
--
-- purchase_order_item_id es la primera de las tres FK de origen opcionales y
-- mutuamente excluyentes previstas en docs/DOMAIN_MODEL.md, seccion 2.8
-- (las otras, sale_item_id/transfer_item_id, se agregaran cuando se
-- implementen sales/transfers - condicion de parada de esa fase).
ALTER TABLE inventory_movement
    ADD COLUMN idempotency_key VARCHAR(150),
    ADD COLUMN purchase_order_item_id BIGINT REFERENCES purchase_order_item(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_inventory_movement_idempotency_key ON inventory_movement (idempotency_key) WHERE idempotency_key IS NOT NULL;
