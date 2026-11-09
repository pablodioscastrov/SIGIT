-- Catalogo cerrado de roles RBAC (TD-008, docs/DECISIONS.md).
-- Solo existe para integridad referencial (FK desde users.role_code) - la
-- aplicacion no lee estas filas en tiempo de ejecucion, usa el enum RoleCode.
CREATE TABLE role (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

INSERT INTO role (code, name) VALUES
    ('ADMIN', 'Administrador general'),
    ('MANAGER', 'Gerente de sucursal'),
    ('OPERATOR', 'Operador de inventario');
