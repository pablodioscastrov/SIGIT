-- UC-14: al desactivar un usuario se exige un motivo, que se muestra
-- mientras siga desactivado. Nula para usuarios activos (se limpia al
-- reactivar, ver UserService.activate) y para cualquier usuario que nunca
-- fue desactivado.
ALTER TABLE users ADD COLUMN deactivation_reason VARCHAR(500);
