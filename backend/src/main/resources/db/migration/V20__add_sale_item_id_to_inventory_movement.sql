-- Segunda de las tres FK de origen opcionales y mutuamente excluyentes
-- previstas en docs/DOMAIN_MODEL.md, seccion 2.8 (la primera,
-- purchase_order_item_id, se agrego en V15; la tercera, transfer_item_id,
-- se agregara cuando se implemente transfers).
ALTER TABLE inventory_movement
    ADD COLUMN sale_item_id BIGINT REFERENCES sale_item(id) ON DELETE RESTRICT;
