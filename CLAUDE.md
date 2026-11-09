# Project Instructions

Before working on any task:

- Read `docs/PROJECT_BRIEF.md`.
- Read `docs/DECISIONS.md`.
- Read `docs/STATUS.md`.
- Consult `docs/ARCHITECTURE.md` and `docs/BUSINESS_RULES.md` when relevant to the task.

## Rules

- Respect all approved technical decisions.
- Work only on the phase or task explicitly requested.
- Stop when the requested objective is complete.
- Do not change the approved stack, architecture, API contracts or database model without explaining the need and requesting approval first.
- Do not add dependencies or infrastructure without a concrete technical justification.
- Business logic belongs in the backend.
- Prioritize consistency, transactions, traceability, authorization and data integrity.
- Do not hardcode secrets or credentials.
- Do not perform destructive Git or database operations without explicit approval.
- Run relevant tests when code is modified.
- Do not refactor unrelated code while implementing a requested task.

## Approved Stack

- Frontend: React + TypeScript
- Backend: Java 21 + Spring Boot
- Database: PostgreSQL
- API: REST
- Architecture: Modular Monolith
- Persistence: Spring Data JPA / Hibernate
- Security: Spring Security + JWT + RBAC
- Infrastructure: Docker Compose
- Near-real-time: SSE preferred when appropriate

## Guiding Principle

Every significant technical decision must be able to answer:

> Why was it done this way?
