# ADR-0001: Use Spring Boot for the backend

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Bookcast is an open-source application for converting long-form books and documents into faithful, minimally summarized conversational audio and related learning material.

The backend is responsible for:

- document ingestion and chapter extraction
- long-running podcast generation workflows
- domain state and persistence
- AI model orchestration
- speech synthesis orchestration
- playback metadata
- learning material generation
- integration with external AI, storage and infrastructure providers

The project is also intended to serve as a portfolio project demonstrating backend engineering skills.

The primary backend technology candidates were:

- Java with Spring Boot
- Node.js with TypeScript
- Python

Python has the broadest AI and machine-learning ecosystem, while Node.js offers excellent integration with Next.js and full-stack TypeScript tooling. However, neither is required for the core Bookcast use cases. Most AI and speech services are accessed through HTTP APIs, and document processing, persistence and background processing are well supported on the JVM.

## Decision

Use **Java and Spring Boot** as the primary backend platform.

Use a modern supported Java LTS release.

The backend will initially be implemented as a single deployable application rather than as microservices.

Python may be introduced later as a specialized worker if a concrete need emerges, such as:

- local ML inference
- advanced OCR
- document layout analysis
- audio signal processing
- specialized NLP pipelines

Python will not be introduced merely because the application uses AI.

Node.js will primarily be used on the frontend through Next.js.

## Consequences

### Positive

- Demonstrates Java and Spring expertise as part of the open-source portfolio.
- Strong support for domain modeling, testing, dependency injection and persistence.
- Spring Modulith can be used to define and verify application module boundaries.
- ArchUnit and jMolecules can be used to enforce architectural and DDD rules.
- Spring AI provides useful abstractions for multiple AI providers.
- Java's strong static type system supports explicit domain contracts.
- The backend remains independent from the frontend technology.

### Negative

- Some new AI libraries and experiments may appear first in Python or TypeScript.
- Certain document-processing or ML tasks may require more integration work than in Python.
- JVM startup and memory requirements are higher than those of some lightweight Node.js services.
- Contributors familiar primarily with JavaScript may face a higher entry barrier.

## Alternatives considered

### Node.js / TypeScript

Advantages:

- excellent developer experience with Next.js
- possible use of tRPC
- large AI SDK ecosystem
- shared language between frontend and backend

Rejected because the project benefits from a language-neutral API boundary and because Spring Boot provides stronger portfolio value for the intended backend focus.

### Python

Advantages:

- strongest ecosystem for ML, NLP and document experimentation
- extensive AI tooling
- excellent prototyping experience

Rejected as the primary backend because the application itself does not require Python-specific capabilities.

Python remains an allowed future implementation technology for specialized workers.

---

