# ADR-0005: Treat architecture as executable constraints

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Architectural rules that exist only in documentation or in senior developers' memory degrade over time.

This risk becomes larger when coding agents contribute code rapidly.

An agent may generate code that:

- compiles
- passes functional tests
- appears stylistically correct

while still violating an important architectural rule.

Examples include:

- domain code importing a provider SDK
- one application module accessing another module's internals
- cyclic dependencies between modules
- infrastructure concerns leaking into aggregates
- adapters being referenced directly by use cases
- API DTOs being reused as persistence models

Code review alone is not a scalable enforcement mechanism.

Bookcast therefore adopts an "architecture as lint" principle.

If an architectural rule can be checked automatically, it should eventually become executable.

## Decision

Use multiple complementary mechanisms to enforce architecture.

### Spring Modulith

Use Spring Modulith to verify application module boundaries.

At minimum, CI should verify:

- no module cycles
- no access to another module's internals
- declared module dependencies are respected

The application module model should reflect DDD/business capabilities.

### jMolecules

Use jMolecules where it adds useful DDD semantics.

Candidate concepts include:

- Aggregate Root
- Entity
- Value Object
- Repository
- Domain Event

Use jMolecules architecture support where appropriate for Hexagonal, Onion or Layered Architecture validation.

### ArchUnit

Use ArchUnit for architectural constraints that are specific to Bookcast or cannot be expressed sufficiently through Spring Modulith or jMolecules.

Example rules may include:

```text
domain must not depend on Spring infrastructure
domain must not depend on AI vendor SDKs
application must not depend directly on outbound adapters
provider adapters must reside in infrastructure/adapter packages
REST controllers must not access repositories directly
```

Rules should describe meaningful invariants rather than formatting preferences.

## Rule evolution

Follow the principle:

```text
Bug / Architecture Violation
        ↓
Understand the invariant
        ↓
Codify the rule
        ↓
Enforce it automatically
```

A recurring architectural mistake should ideally happen:

- once as a discovery
- possibly twice while the rule is understood
- not repeatedly forever

The architecture test suite should grow with the project's institutional knowledge.

## Agent context

Executable architecture does not replace documentation.

Use both:

```text
AGENTS.md
    explains intent

ADRs
    explain decisions and trade-offs

Spring Modulith / jMolecules / ArchUnit
    enforce what must not drift
```

Coding agents should be able to understand the architecture from repository-local context.

The repository must remain usable without requiring access to a specific historical chat conversation or a specific AI vendor.

## CI requirements

Architecture verification must run as part of normal CI.

Architectural failures are build failures.

Do not create a separate optional architecture report that developers can ignore.

Architecture tests should be fast enough to run frequently.

## Initial rules

The first version should enforce only high-value constraints.

Candidate initial checks:

1. Spring Modulith module verification passes.
2. Application module graph contains no cycles.
3. Domain packages do not depend on AI vendor libraries.
4. Domain packages do not depend on outbound adapters.
5. Application packages use ports rather than vendor adapters.
6. Modules do not access another module's internal implementation.
7. AI provider SDK usage is restricted to provider adapter packages.

Additional constraints should be added when actual design knowledge emerges.

Avoid building a large theoretical rule set before the first vertical slice exists.

## Consequences

### Positive

- Architectural drift becomes visible immediately.
- Coding agents receive deterministic guardrails.
- Senior review time is not repeatedly spent on the same architectural mistakes.
- Institutional knowledge accumulates in executable form.
- Refactoring is safer because intended boundaries are explicit.
- The project itself demonstrates architecture-as-code principles.

### Negative

- Architecture tests require maintenance.
- Badly designed rules can obstruct legitimate refactoring.
- Excessively strict rules can encourage developers to work around the tooling.
- Some architectural quality still requires human judgment and cannot be linted.

## Alternatives considered

### Documentation only

Rejected because documentation cannot prevent violations.

### Code review only

Rejected because repeated manual enforcement does not scale.

### ArchUnit only

Rejected because Spring Modulith and jMolecules already understand useful structural and DDD concepts that should not be reimplemented manually.

### Generate a complete formal architecture model before implementation

Rejected for the initial version.

Architecture enforcement should grow alongside demonstrated requirements rather than becoming a speculative meta-project.