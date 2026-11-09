-- Siembra minima de conveniencia (analoga a V4__seed_initial_users.sql):
-- crear un producto exige una unidad base ya existente. No es un catalogo
-- cerrado como `role` - ADMIN puede agregar mas via POST /units-of-measure.
INSERT INTO unit_of_measure (code, name) VALUES
    ('UN', 'Unidad'),
    ('CJ', 'Caja'),
    ('KG', 'Kilogramo');
