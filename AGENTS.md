# Bookcast Agent Instructions

Bookcast is an open-source learning platform that converts long-form books and documents into faithful, minimally summarized conversational audio and learning material.

## Read before making changes

Use the repository as the source of truth.

Read:

- `docs/PRODUCT_VISION.md` for product intent, users, goals and non-goals.
- `docs/ARCHITECTURE.md` for the current system structure and module map.
- `docs/ENGINEERING_PRINCIPLES.md` for engineering heuristics.
- `docs/adr/` for accepted architectural decisions relevant to the change.

Do not rely on previous ChatGPT, Claude, Codex or other agent conversations as durable project context.re architecture constraints

- Backend: Java + Spring Boot.
- Frontend: Next.js.
- Frontend/backend contract: OpenAPI.
- Backend architecture: DDD-oriented modular monolith.
- Spring Modulith defines and verifies application module boundaries.
- ArchUnit and jMolecules provide additional executable architecture constraints.
- Prefer machine-verifiable architecture over conventions that exist only in documentation.

### AI integrations

- Core domain and application code must be vendor-independent.
- Do not import OpenAI, Anthropic, Google, ElevenLabs or other vendor SDKs into domain or application code.
- External AI services are outbound adapters behind Bookcast-owned ports.
- Text generation, speech synthesis, embeddings, transcription and evaluation are separate capabilities and may use different providers.
- Provider-specific request and response models stay inside their adapters.

## Product invariants

- Bookcast is not primarily a summarization product.
- Preserve source structure, examples, arguments, qualifications and trade-offs where practical.
- Long-form generation must be decomposable into smaller source-grounded units.
- Podcast output supports distinct speakers.
- Chapters must be independently generatable; whole-book context is optional.
- Generated dialogue is source-grounded but normally discusses the topic rather than narrating the book.
- The podcast is expert-led; do not optimize for equal speaker participation.
- Mobile listening is a primary use case.
- Playback must eventually support seeking, resume, playback speed and chapter navigation.
- Finnish is the default output language and a primary product experience.
- Source language and output language are separate concepts.
- A primary use case is transforming English long-form material into natural Finnish conversational learning content.
- Do not implement generation as literal translation followed by text-to-speech.
- Prefer natural spoken professional Finnish; avoid unnecessary Finglish and preserve English technical terms only where natural in Finnish usage.
- Supporting Finnish by default must not hard-code the architecture to Finnish-only use.

Refer to `docs/PRODUCT_VISION.md` for the complete product intent and non-goals.

## Development workflow

Before coding:

1. Identify the affected application module and use case.
2. Read relevant ADRs.
3. Inspect existing module boundaries and architecture tests.
4. Inspect existing tests for the affected behavior.
5. Prefer the smallest coherent vertical change.

While coding:

- Respect existing module APIs and dependency direction.
- Prefer ports and adapters for external capabilities.
- Do not bypass architectural boundaries for convenience.
- Do not introduce speculative infrastructure without a demonstrated requirement.
- Use fake adapters where they make domain/application tests deterministic.
- Keep changes scoped to the requested task.

After coding:

1. Run relevant unit and integration tests.
2. Run Spring Modulith / architecture verification.
3. Run relevant OpenAPI contract checks when the API changed.
4. Report any architectural rule that could not be expressed automatically.

## Architecture evolution

When a recurring architectural violation reveals a reusable invariant, follow:

`Violation -> Understand -> Codify -> Enforce`

Prefer an executable rule when practical.

Do not add speculative architecture rules merely to increase rule coverage.

## ADR policy

Architecture decisions require human judgment.

You may:

- identify a missing architectural decision,
- analyze alternatives and trade-offs,
- propose an ADR draft,
- challenge an existing ADR when new evidence warrants reconsideration.

You must not:

- silently change an accepted architectural decision,
- create an ADR and mark it `Accepted` without explicit human approval,
- reinterpret an ADR solely to make an implementation easier.

When implementation conflicts with an accepted ADR, stop and surface the conflict.

## Definition of source of truth

When sources disagree, prefer in this order:

1. executable constraints and tests for facts about what the system currently permits,
2. accepted ADRs for intended architectural decisions,
3. `docs/ARCHITECTURE.md` for the documented current system,
4. `docs/ENGINEERING_PRINCIPLES.md` for heuristics,
5. `docs/PRODUCT_VISION.md` for product intent,
6. historical discussion or agent conversation.

A disagreement between executable structure and documented intent is a finding to report, not something to silently resolve.


