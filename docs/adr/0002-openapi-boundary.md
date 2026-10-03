# ADR-0002: Use OpenAPI as the frontend/backend contract

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

The frontend will be implemented with Next.js and TypeScript.

The backend will be implemented with Spring Boot and Java.

The application requires type-safe communication between the frontend and backend.

tRPC was considered because it provides an excellent developer experience for full-stack TypeScript applications. However, its strongest feature is the direct sharing of TypeScript types between the server and client.

Because Bookcast uses a Java backend, that advantage does not apply.

The project may also support additional clients in the future, such as:

- native mobile applications
- command-line tools
- third-party integrations
- alternative web clients

The API contract should therefore not be tied to one implementation language.

## Decision

Use **OpenAPI** as the public contract between the Spring Boot backend and clients.

The Spring backend will expose a versioned HTTP API described by an OpenAPI specification.

The Next.js frontend will use a generated TypeScript client derived from that specification.

Application code in the frontend should depend on the generated API client through appropriate application-level abstractions rather than duplicating request and response models manually.

The OpenAPI specification is part of the application's architecture and must remain under version control or be generated deterministically from version-controlled backend definitions.

## Consequences

### Positive

- Language-neutral API contract.
- Type-safe generated TypeScript client.
- Backend and frontend remain independently replaceable.
- Future Kotlin, Swift or other clients can reuse the same contract.
- API documentation can be generated automatically.
- Breaking API changes become visible and reviewable.
- The contract can be tested in CI.

### Negative

- More ceremony than tRPC.
- Code generation introduces an additional build step.
- Care is required to prevent generated DTOs from leaking deeply into frontend domain logic.
- OpenAPI schemas can become difficult to manage if backend endpoint design is careless.

## Rules

- Do not manually duplicate backend DTO interfaces in the frontend.
- Generate client types from OpenAPI.
- Treat breaking OpenAPI changes as architectural changes.
- Do not expose persistence entities directly through the API.
- Domain models and API models are separate concepts even when they initially look similar.
- External provider models must never become part of the Bookcast public API.

## Alternatives considered

### tRPC

Rejected because it is optimized for TypeScript-to-TypeScript communication.

It would make sense if both backend and frontend were implemented in TypeScript.

Adding a Next.js tRPC BFF in front of Spring Boot was also considered but rejected for the initial architecture because it would introduce an unnecessary additional API layer.

### Hand-written REST clients

Rejected because they duplicate contracts and reduce type safety.

### GraphQL

Not selected because the initial product does not require client-defined graph traversal or complex cross-domain queries.

OpenAPI provides a simpler and more explicit contract for the expected use cases.

---

