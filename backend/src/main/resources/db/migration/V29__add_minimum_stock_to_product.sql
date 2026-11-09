-- Ajuste aprobado sobre la condicion de parada original de "product" ("no
-- implementes stock dentro de Product"): minimum_stock aqui no es una
-- cantidad de stock -- eso sigue siendo de Inventory, por sucursal -- es el
-- valor por defecto que recibe el minimo de una sucursal la primera vez que
-- registra movimiento de este producto (BR-010). Alimenta el estado de
-- reabastecimiento y las alertas de stock minimo sin depender de un ajuste
-- manual posterior por cada sucursal.
--
-- Default 0 para productos ya existentes: mismo comportamiento que tenian
-- hasta ahora (Inventory.minimum_stock tambien nace en 0).
ALTER TABLE product ADD COLUMN minimum_stock NUMERIC(19,6) NOT NULL DEFAULT 0;
ALTER TABLE product ADD CONSTRAINT chk_product_minimum_stock_non_negative CHECK (minimum_stock >= 0);
