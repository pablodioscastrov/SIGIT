-- UC-15 (docs/USE_CASES.md): administrar sucursales incluye "ubicación" como
-- dato editable, no contemplado en V2__create_branch_table.sql. Se agrega en
-- una migración nueva (no se edita V2) porque V2 ya pudo haberse aplicado en
-- entornos existentes - las migraciones ya aplicadas son inmutables.
ALTER TABLE branch ADD COLUMN location VARCHAR(255);
