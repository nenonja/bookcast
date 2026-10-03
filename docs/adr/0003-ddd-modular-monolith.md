# ADR-0003: Use a DDD-oriented modular monolith

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Bookcast contains several distinct business capabilities:

- maintaining a user's library of source material
- generating podcast-style learning content
- listening and tracking playback
- creating study material
- managing long-running generation workflows

A purely technical package structure such as:

```text
controllers/
services/
repositories/
entities/
```

would group code by implementation mechanism rather than by business capability.

This makes domain boundaries harder to understand and creates a risk that the codebase gradually becomes a tightly coupled monolith.

Microservices are also not justified at the current scale. They would add deployment, network, consistency and operational complexity before independent scaling is needed.

The project should follow Domain-Driven Design principles while keeping deployment simple.

## Decision

Implement the backend as a **DDD-oriented modular monolith**.

Use **Spring Modulith** to model and verify application modules.

Top-level application modules should represent business capabilities rather than technical infrastructure.

Initial candidate modules are:

```text
library
generation
learning
listening
```

The exact domain boundaries may evolve as understanding improves.

Each module owns its domain model and exposes an explicit public API.

Implementation details remain internal to the module.

Cross-module access must occur only through declared module APIs.

Modules must not access another module's internal packages.

## Internal module architecture

Where useful, application modules should follow a hexagonal structure:

```text
generation/
├── domain/
├── application/
├── port/
└── adapter/
    ├── in/
    └── out/
```

The domain layer contains business concepts.

The application layer coordinates use cases.

Ports describe capabilities required from outside the domain.

Adapters connect ports to technical systems.

Examples:

```text
GeneratePodcastUseCase
        ↓
TextGenerationPort
        ↓
OpenAiTextGenerationAdapter
```

or:

```text
PodcastEpisode
        ↓
AudioStoragePort
        ↓
R2AudioStorageAdapter
```

Provider-specific concepts belong in adapters, not in the domain.

## Domain modeling principles

Use explicit DDD concepts where they improve clarity:

- Aggregate Root
- Entity
- Value Object
- Repository
- Domain Event
- Bounded Context / Application Module

jMolecules may be used to express these concepts explicitly and support architectural verification.

Avoid creating abstractions or aggregates merely to satisfy a DDD pattern.

Domain boundaries should follow actual invariants and business ownership.

## Consequences

### Positive

- Business capabilities are visible from the package structure.
- Strong module boundaries without microservice operational complexity.
- Easier navigation for both humans and coding agents.
- Module dependencies can be machine verified.
- Individual modules may later be extracted if a concrete scaling or ownership need emerges.
- DDD concepts become explicit and reviewable.

### Negative

- Determining good domain boundaries requires ongoing judgment.
- Some features will span multiple modules.
- Too much DDD ceremony can slow early development if applied mechanically.
- Developers must resist bypassing module APIs for convenience.

## Rules

- No microservices without a demonstrated reason.
- No module may directly access another module's internals.
- Modules communicate through explicit APIs or domain/application events where appropriate.
- Technical vendors are not domain modules.
- `openai`, `anthropic`, `elevenlabs`, `postgres`, `r2`, etc. must never become top-level business modules.
- Package structure should communicate domain structure.
- Module boundaries must be verified automatically in CI.

## Alternatives considered

### Traditional layered monolith

Rejected because technical layers alone do not provide sufficiently strong domain boundaries.

### Microservices

Rejected because the operational cost is unjustified for the initial product.

### Pure hexagonal architecture without application modules

Rejected because ports and adapters solve dependency direction inside capabilities but do not by themselves define higher-level business module boundaries.

