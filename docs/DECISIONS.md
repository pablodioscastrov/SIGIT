
:contentReference[oaicite:1]{index=1}

---

## `DECISIONS.md`

```md
# Decisiones Técnicas

## TD-001 — React + TypeScript

**Estado:** Accepted

Se utilizará React + TypeScript para el frontend.

### Justificación

La aplicación necesita:

- formularios;
- tablas;
- filtros;
- dashboards;
- múltiples módulos;
- integración fuerte con una API.

TypeScript ayudará a mantener contratos explícitos y reducir errores de integración.

---

## TD-002 — Java 21 + Spring Boot

**Estado:** Accepted

Se utilizará Java 21 + Spring Boot para el backend.

### Justificación

El dominio requiere:

- transacciones;
- seguridad;
- reglas de negocio;
- validaciones;
- persistencia;
- concurrencia.

Spring Boot proporciona un ecosistema maduro para estos requisitos.

---

## TD-003 — PostgreSQL

**Estado:** Accepted

Se utilizará PostgreSQL.

### Justificación

El dominio es altamente relacional y requiere:

- ACID;
- foreign keys;
- constraints;
- índices;
- locking;
- concurrencia;
- integridad.

---

## TD-004 — REST

**Estado:** Accepted

La comunicación frontend-backend utilizará REST.

### Justificación

Los recursos y casos de uso del dominio pueden modelarse con contratos REST claros.

GraphQL añadiría complejidad que actualmente no aporta suficiente valor.

---

## TD-005 — Monolito modular

**Estado:** Accepted

El backend será un monolito modular.

### Justificación

Inventario, compras, ventas y transferencias tienen fuertes relaciones transaccionales.

Los microservicios introducirían complejidad distribuida innecesaria para el alcance actual.

---

## TD-006 — Spring Data JPA / Hibernate

**Estado:** Accepted

Se utilizará Spring Data JPA / Hibernate para persistencia.

PostgreSQL seguirá utilizando:

- constraints;
- índices;
- capacidades relacionales;
- SQL cuando sea necesario.

---

## TD-007 — Spring Security + JWT

**Estado:** Accepted

Se utilizarán:

- Spring Security;
- JWT.

Las contraseñas nunca se almacenarán en texto plano.

Los JWT no deberán contener información sensible innecesaria.

---

## TD-008 — RBAC

**Estado:** Accepted

Roles iniciales:

```text
ADMIN
MANAGER
OPERATOR
