-- BR-009: el motivo/detalle del tratamiento de un faltante (especialmente
-- una reclamacion, cuyo contenido es la unica traza del reclamo) se recibia
-- en la API pero nunca se persistia -- ni quien la registro ni la sucursal
-- contraria podian volver a verla despues de creada. `notes` es opcional
-- (igual que en la solicitud), consistente con el resto del dominio: nunca
-- se edita ni se borra una vez registrado (BR-021).
ALTER TABLE transfer_item ADD COLUMN treatment_notes VARCHAR(1000);
