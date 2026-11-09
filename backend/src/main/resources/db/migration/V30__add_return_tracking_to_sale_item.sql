-- Devolución de ventas (BR-052): cantidad devuelta acumulada por línea, más
-- un contador de versión propio para el bloqueo optimista sobre esa
-- cantidad (mismo patrón ya usado en purchase_order_item.version).
ALTER TABLE sale_item ADD COLUMN quantity_returned NUMERIC(19,6) NOT NULL DEFAULT 0;
ALTER TABLE sale_item ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE sale_item ADD CONSTRAINT chk_sale_item_quantity_returned
    CHECK (quantity_returned >= 0 AND quantity_returned <= quantity);
