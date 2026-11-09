-- Datos de siembra SOLO para desarrollo/pruebas manuales locales - un usuario
-- por rol, contraseña "ChangeMe123!" para los tres (hash BCrypt precomputado,
-- costo 10). No usar estas credenciales en ningun entorno real.
--
-- branch (2) primero, porque MANAGER/OPERATOR requieren branch_id (CHECK en
-- users, V3__create_users_table.sql).
INSERT INTO branch (code, name) VALUES
    ('SUC-001', 'Sucursal Centro');

INSERT INTO users (name, email, password_hash, role_code, branch_id) VALUES
    ('Admin General', 'admin@inventario.local',
     '$2a$10$UPzhuJmCXxmMoJye3PnHC.AOj44lRVZmeoENsVI4TrR8V5cqmO9O6',
     'ADMIN', NULL),
    ('Gerente Sucursal Centro', 'gerente.centro@inventario.local',
     '$2a$10$UPzhuJmCXxmMoJye3PnHC.AOj44lRVZmeoENsVI4TrR8V5cqmO9O6',
     'MANAGER', (SELECT id FROM branch WHERE code = 'SUC-001')),
    ('Operador Sucursal Centro', 'operador.centro@inventario.local',
     '$2a$10$UPzhuJmCXxmMoJye3PnHC.AOj44lRVZmeoENsVI4TrR8V5cqmO9O6',
     'OPERATOR', (SELECT id FROM branch WHERE code = 'SUC-001'));
