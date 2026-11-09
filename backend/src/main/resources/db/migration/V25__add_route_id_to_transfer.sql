-- Resuelve el ajuste pendiente #7 de docs/BUSINESS_RULES.md: la columna que
-- docs/DOMAIN_MODEL.md 2.18 preve en Transfer y que la fase de transfers
-- dejo deliberadamente para esta, por depender de la tabla route.
--
-- Se asigna SOLA, resolviendo el par (origen, destino) al crear la
-- transferencia: nunca llega en el payload. Es una conveniencia de
-- visualizacion, no la fuente de verdad de "a que ruta pertenece" - esa
-- sigue siendo el par de sucursales, que Transfer ya persiste y que no puede
-- quedar desincronizado. El reporte de cumplimiento agrupa por ese par, de
-- modo que una transferencia creada ANTES de clasificar su ruta igual cuenta
-- en el reporte aunque su route_id haya quedado nulo (ver BR-036).
ALTER TABLE transfer
    ADD COLUMN route_id BIGINT REFERENCES route(id) ON DELETE RESTRICT;
