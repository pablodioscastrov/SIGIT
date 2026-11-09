-- Tercera y ultima de las FK de origen opcionales y mutuamente excluyentes
-- previstas en docs/DOMAIN_MODEL.md, seccion 2.8 (purchase_order_item_id en
-- V15, sale_item_id en V20). Con esta, todo InventoryMovement generado por
-- un documento comercial queda trazable hasta su linea de origen.
--
-- Una misma transfer_item genera DOS movimientos: el RETIRO en la sucursal
-- origen al despachar (TRANSFERENCIA_SALIDA) y el INGRESO en la sucursal
-- destino al recibir (TRANSFERENCIA_ENTRADA) - por eso no lleva restriccion
-- de unicidad, a diferencia de idempotency_key.
ALTER TABLE inventory_movement
    ADD COLUMN transfer_item_id BIGINT REFERENCES transfer_item(id) ON DELETE RESTRICT;
