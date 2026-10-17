# Bookcast Architecture

## Status

This document describes the current architecture of Bookcast.

The first product slice now imports pasted chapter text, persists immutable source revisions, generates source-linked Finnish dialogue, renders Realtime Mini audio and exposes polling plus browser playback. GPT-Live remains experimental and is not the v1 renderer.

It is a living system map.

It describes what the system currently is or is expected to become in the next implementation steps.

Architectural rationale belongs primarily in Architecture Decision Records under `docs/adr/`.

Engineering heuristics belong in `docs/ENGINEERING_PRINCIPLES.md`.

Product intent belongs in `docs/PRODUCT_VISION.md`.

When implementation and documentation disagree, surface the disagreement rather than silently assuming either one is correct.

---

# 1. System overview

Bookcast begins as a single-user web application backed by a modular Spring application.

```text
┌───────────────────────────────┐
│        Next.js web app        │
│                               │
│ source / generation / script  │
│ player / later: study         │
└──────────────┬────────────────┘
               │
               │ OpenAPI HTTP API
               │
┌──────────────▼────────────────┐
│         Spring Boot           │
│                               │
│     Modular Monolith          │
│                               │
│ library                       │
│ generation                    │
│ later: learning / listening   │
└─────────┬─────────┬───────────┘
          │         │
          │         │ outbound ports
          │         │
     ┌────▼───┐ ┌──▼───────────────┐
     │Postgres│ │ External adapters │
     └────────┘ │ AI / storage etc. │
                └───────────────────┘
```

The system is initially deployed as one application.

Microservices are explicitly out of scope.

The first implementation has one user and no authentication.

The code should not introduce assumptions that make future user ownership prohibitively difficult to add.

---

# 2. Repository layout

Current intended layout:

```text
bookcast/
├── AGENTS.md
├── README.md
│
├── docs/
│   ├── PRODUCT_VISION.md
│   ├── ARCHITECTURE.md
│   ├── ENGINEERING_PRINCIPLES.md
│   └── adr/
│       ├── 0001-spring-boot-backend.md
│       ├── 0002-openapi-boundary.md
│       ├── 0003-ddd-modular-monolith.md
│       ├── 0004-provider-independent-ai.md
│       ├── 0005-executable-architecture.md
│       └── 0006-source-model-and-imports.md
│
├── backend/
├── frontend/
└── infra/
```

The first implementation adds `api/` for the OpenAPI source of truth and `compose.yaml` for local PostgreSQL. Do not create tooling directories before they are needed.

The repository itself should remain the durable source of context for both human and AI contributors.

---

# 3. Backend architectural style

The backend uses Java and Spring Boot.

The architectural direction is:

> DDD-oriented modular monolith with explicit module boundaries and ports/adapters around external capabilities.

Only modules with real behavior exist.

Therefore the first implementation should contain:

```text
library
generation
```

Future candidate capabilities such as `learning` and `listening` should not exist as empty modules.

## Two architectural levels

Bookcast distinguishes:

```text
Business capability boundaries
        ↓
Spring Modulith application modules

Internal dependency direction
        ↓
domain / application / adapters
```

Spring Modulith protects relationships between capabilities.

Ports and adapters protect application logic from external systems.

These solve different problems.

---

# 4. Library module

`library` owns imported source material.

It does not own podcast generation.

## Core concepts

Initial vocabulary:

```text
Book
Chapter
SourceRevision
SourceBlock
```

### Book

Represents a work the user wants to learn from.

A Book is not an uploaded file.

It may eventually receive source material through multiple formats.

### Chapter

Represents an ordered learning unit within a Book.

Initially chapters may be created manually.

One pasted or uploaded text payload may correspond directly to one Chapter.

Automatic chapter detection is not required.

### SourceRevision

Represents an immutable imported version of a Chapter's source material.

Changing imported source creates a new revision.

Existing generated artifacts continue to refer to the revision from which they were produced.

### SourceBlock

Represents a stable, ordered block of normalized imported source.

Initially blocks are text-oriented and typically correspond to natural paragraph-level units.

Each block has a stable identifier within its source revision.

Generation references these identifiers rather than raw offsets or PDF page positions.

## Library API

`generation` must not read library persistence internals.

The library module exposes a narrow public API that returns immutable source data required for generation.

Conceptually:

```text
ChapterSource
├── chapter identity
├── source revision identity
├── title
└── ordered source blocks
```

The public `library.Library` API returns immutable `ChapterSource` snapshots; ordered blocks include their stable UUIDs and inspectable exclusion reasons.

---

# 5. Source import architecture

Source acquisition and normalized source content are separate concerns.

Conceptually:

```text
Paste text ─┐
TXT file ───┤
PDF ────────┤
EPUB ───────┤
HTML ───────┘
       ↓
Importer / adapter
       ↓
Normalized SourceRevision
       ↓
SourceBlock[]
```

Initial supported input:

```text
pasted plain text
.txt file
```

Initial workflow may treat one import as one chapter.

This deliberately avoids automatic chapter detection.

## Source normalization and acquisition noise

The Finnish dialogue spike demonstrated that imported text may include acquisition, UI, or navigation material that should not automatically become podcast content. Source normalization owns the distinction between meaningful imported learning content and obvious acquisition debris before generation.

Keep classification decisions inspectable and auditable, including which blocks are excluded and why. Preserve source provenance rather than silently changing source behind generated artifacts.

The spike establishes this concern, not a finalized classification algorithm or type hierarchy. Do not introduce a large rich SourceBlock taxonomy for it now.

## Future importers

Possible future adapters include:

```text
PdfSourceImporter
EpubSourceImporter
HtmlSourceImporter
```

These are not domain concepts.

Adding one should not require changing podcast-generation domain logic.

## Non-text content

The initial normalized source model supports text.

Future models may include:

```text
TextBlock
CodeBlock
FigureBlock
TableBlock
EquationBlock
```

Do not invent missing figures, tables, diagrams, or other source material that was not imported.

---

# 6. Generation module

`generation` owns transformation of source material into podcast-style script and later audio artifacts.

Its initial responsibility is deliberately narrow:

> produce a source-linked conversational script and playable audio for a Chapter source revision.

Learning artifacts such as quizzes are deferred.

Speech rendering is now part of the first usable slice, after structurally validated dialogue.

## Chapter independence

Generation must work from the current Chapter/SourceRevision plus generally available product requirements. It must not require all Chapters of the Book, whole-book preprocessing, or a precomputed Book glossary.

Future Book-level context may optionally improve consistency between Chapters, for example through terminology choices or accumulated context. It is enrichment, not a generation prerequisite. Do not design or implement Book-level terminology persistence yet.

## Generation vocabulary

Three units are distinct.

### Source block

Owned by `library`.

Stable normalized source content.

### Script segment

A retryable generation artifact.

A ScriptSegment:

- covers an explicit source range
- contains multiple dialogue turns
- has an order within the overall script
- has execution/generation metadata
- can fail independently
- may eventually be regenerated independently

### Dialogue turn

One utterance from one speaker.

Conceptually:

```text
DialogueTurn
├── speakerRole
└── text
```

Speaker roles are domain/presentation semantics such as:

```text
HOST
EXPERT
```

Vendor voice identifiers are not dialogue-domain concepts.

## Continuity

Independent generation and coherent conversation create tension.

Therefore a ScriptSegment may require contextual information beyond only the source blocks it covers.

The precise continuity strategy must be learned through real-model experimentation.

Possible context includes:

- previous segment summary
- previous dialogue turns
- current chapter state
- topics already introduced
- concepts still expected later

Do not finalize this contract before the real-model generation spike.

---

# 7. Generation port

The first generation port must be Bookcast-shaped rather than vendor-shaped.

It must not expose concepts such as:

```text
OpenAI message arrays
Anthropic content blocks
vendor tool-call types
vendor sampling configuration
Spring AI ChatModel
```

A conceptual interaction is:

```text
Generation request
├── source range
├── conversational context
├── speaker requirements
├── output language
└── generation intent

        ↓

Text generation capability

        ↓

Validated ScriptSegment
├── source coverage
└── ordered DialogueTurn[]
```

The exact contract will be defined after a small real-provider spike.

Prompt construction and vendor translation remain behind the adapter boundary.

---

# 8. Language

Source language and output language are independent concepts.

The initial product default is:

```text
source language: detected or provided by the source
output language: Finnish (fi)
```

A primary initial use case is:

```text
English long-form source
        ↓
Bookcast generation
        ↓
natural Finnish conversational script
        ↓
Finnish speech
```

Finnish is a product default, not a restriction of the domain model.

Generation must therefore be capable of representing at least:

```text
sourceLanguage
outputLanguage
```

The exact language representation should use a standard language identifier where practical rather than application-specific enums containing only currently supported languages.

## Translation versus adaptation

Generation is not modeled as:

```text
source
  ↓
translation
  ↓
text-to-speech
```

Instead, translation and conversational adaptation are part of one source-grounded generation task:

```text
source meaning
      +
source structure
      +
conversation context
      +
output language
      +
podcast style
        ↓
source-grounded conversational script
```

This distinction is important.

The output should be natural spoken Finnish rather than Finnish text that mechanically preserves English sentence structure.

At the same time, adaptation must preserve source fidelity.

## Technical terminology

Output-language instructions may include terminology policy.

For the initial Finnish experience:

- use natural spoken professional Finnish and established Finnish expressions for ordinary technical concepts,
- preserve established English technical terminology where appropriate,
- do not mechanically translate established software and engineering terms,
- explain terminology conversationally where doing so improves learning.

Terminology handling belongs to Bookcast generation requirements rather than to a specific model provider. Follow the natural Finnish terminology policy in `docs/PRODUCT_VISION.md`; English source language does not justify unnecessary code-switching. This policy does not require a precomputed glossary.

## Speech language

Speech rendering receives explicit Finnish delivery guidance in the current adapter; additional languages/locales can follow behind the same capability boundary.

The chosen speech provider and voice must be evaluated for natural Finnish pronunciation and conversational listening quality.

A provider being technically capable of generating Finnish speech is not sufficient by itself; naturalness is part of the product-quality evaluation.---


# 9. Real-model architecture spike

Before finalizing the production text-generation port, perform a small non-production experiment with:

- one representative long chapter
- one real model
- two-speaker Finnish output

The spike should answer:

```text
How large should source units be?

How much previous conversation is required for continuity?

Can the model return reliable structured output?

How should source-block references be returned?

How does script length compare to source length?

What information must the application provide to generation?

What deterministic validation can reject malformed output?
```

The spike is allowed to use direct provider/Spring AI APIs.

Its code may be discarded.

Do not let spike implementation become accidental production architecture.

## Established findings and experimental settings

See `spikes/finnish-dialogue/SPIKE_RESULTS.md` for evidence and limitations. The v2 run showed local continuity across two neighboring transitions, not chapter-scale continuity. Later saved v3/v4 and GPT-5.4 generation experiments were executed. Their evidence and limitations are recorded in the corresponding spike reports; no spike setting becomes an architectural invariant. The GPT-Live conclusion is in `spikes/finnish-dialogue/LIVE_PRESENTATION_CONCLUSION.md`.

The following remain experimental and must not become architectural constants:

- approximately 800 source words per generation unit,
- previous four dialogue turns as continuity context,
- exact HOST/EXPERT proportions,
- exact source-noise classification heuristics,
- whether Book-level terminology memory or a glossary is useful.

The expert-led product format and Finnish terminology requirements are defined in `docs/PRODUCT_VISION.md`; observed speaker proportions are diagnostics, not quality targets.

---

# 10. Generation lifecycle

The external API should model generation as a resource with state even before execution becomes operationally complex.

Conceptually:

```text
POST generation
      ↓
Generation created
      ↓
QUEUED
      ↓
RUNNING
      ↓
SUCCEEDED
   or FAILED
```

The client may poll generation state initially.

Execution may remain in-process.

Distributed workflow systems and queues are not initially required.

## Failure semantics

Initial Bookcast-owned failure categories should remain small.

Likely categories include:

```text
TRANSIENT_FAILURE
PERMANENT_FAILURE
INVALID_OUTPUT
REFUSED
```

The concrete model should be validated during implementation.

The application, not the provider SDK, owns the externally visible failure vocabulary.

## Generation attempt observability

Preserve safe execution metadata for every generation attempt, including failed attempts where possible:

- attempt identity,
- status/outcome,
- elapsed time,
- provider/model metadata,
- available token usage,
- safe provider/error classification,
- retryability assessment.

Provider-specific exceptions and response structures remain inside infrastructure adapters. Retryability is an assessment. The worker automatically retries only explicitly classified transient provider failures, with at most three attempts per dialogue segment or audio group per explicit run/resume. Dialogue covers transient timeout/network/429/5xx failures and uses the existing bounded 2/8-second jittered timing with Retry-After. Each attempt, including failed/unknown usage, is persisted; local/schema/auth/configuration failures stop immediately. Completed segments are never regenerated. Logging and privacy constraints in section 19 apply to all outcomes.

## Retry

Retry should operate at the smallest useful persisted generation unit.

The initial version may use explicit user-triggered retry rather than sophisticated automatic recovery.

Avoid unbounded automatic retries because provider calls cost money.

---

# 11. Generated script validation

Probabilistic generation must pass deterministic structural validation before becoming a valid ScriptSegment.

Initial checks may include:

```text
known speaker roles only
at least one dialogue turn
no empty dialogue turns
stable turn ordering
valid source-block references
references belong to the requested source revision
```

Generated `coveredSourceBlockIds` are declarations of structural coverage, not proof of semantic fidelity. A structurally valid segment can still omit content or introduce unsupported explanations.

These checks establish structural correctness.

They do not establish source fidelity.

Semantic fidelity requires separate evaluation.

---

# 12. AI provider independence

Core application code depends on Bookcast-owned capabilities.

Provider libraries remain in outbound adapters.

Conceptually:

```text
generation application
        ↓
Bookcast generation port
        ↓
Spring AI / provider adapter
        ↓
model provider
```

Spring AI may reduce provider-specific integration work.

It is infrastructure, not the Bookcast domain contract.

Do not create hypothetical ports for capabilities that have no current use case.

Therefore initial architecture does not require:

```text
EmbeddingPort
TranscriptionPort
SpeechSynthesisPort
```

until those capabilities are introduced.

Add abstractions when the capability becomes real.

---

# 13. Persistence

Persistent state is required early because source revisions and generated scripts must survive restarts.

PostgreSQL is the expected initial relational store.

The exact persistence mapping approach should remain practical.

Do not create separate domain, persistence, and API models automatically for every immutable structure.

Separate models where they protect a meaningful boundary.

Flyway may be used for schema evolution.

Testcontainers may be used for integration testing.

These are implementation mechanisms rather than domain concepts.

---

# 14. OpenAPI boundary

Frontend and backend communicate through HTTP using OpenAPI.

Conceptually:

```text
Spring Boot
     ↓
OpenAPI contract
     ↓
Generated TypeScript client
     ↓
Next.js
```

The checked-in `api/openapi.json` is the contract source of truth. Maven generates Spring endpoint interfaces and API DTOs; `openapi-typescript` generates browser types consumed by `openapi-fetch`. Both boundaries are generated from the same specification. API DTOs are mapped at inbound adapters, not reused as persistence models.

The API must not expose:

- persistence implementation types
- provider SDK types
- Spring AI types
- internal module implementation details

The generated TypeScript client should be reproducible.

---

# 15. Frontend

The frontend uses Next.js and TypeScript.

Initial surfaces:

```text
Books
Book
Create chapter
Paste/upload source text
Source preview
Generation status
Generated script
```

The first frontend includes browser playback with seeking, ±15 seconds, speed and local resume position. It does not require:

```text
PWA installation
offline mode
authentication
quiz UI
flashcards
```

Those belong to later slices.

---

# 16. First production vertical slice

Implemented flow:

```text
Next.js pasted-text import
  → library: Book / Chapter / immutable SourceRevision / ordered SourceBlocks
  → generation: queued / running / succeeded / failed
  → GPT-5.4 low: validated ScriptSegments / HOST and EXPERT DialogueTurns
  → Realtime Mini: isolated audio groups, marin / ash, speed 0.95
  → preserved PCM / transcript / attempt metadata + waveform validation
  → one AAC-LC M4A
  → browser player / localStorage resume position
```

There are only two application modules. `generation` reads revision snapshots through the public `library` API; library never reads generation internals. PostgreSQL stores revisions, dialogue, accepted audio receipts, jobs and safe attempt metadata. Flyway installs the schema and database immutability triggers. Local filesystem storage holds private provider candidates, raw attempts and assembled audio; it must persist alongside the database. No object storage or distributed jobs are introduced.

The in-process worker runs sequentially in one backend instance. Atomic PostgreSQL claiming and a unique active-generation index prevent duplicate work. `requestId` makes Generate requests idempotent; reuse with a different source fails. Completed script segments and audio groups are checkpoints. An application restart marks running jobs/attempts interrupted and requires an explicit user retry; it never silently repeats an uncertain paid call. Retry preserves accepted artifacts and their hashes. Deploying multiple backend instances is not supported by this initial recovery policy.

The dialogue adapter uses the established v4-style Finnish guidance, roughly 800-word units and the previous four turns. These are initial execution settings, not domain constants or proof of chapter-scale fidelity. Chapter and source revision identities are fixed for a generation. Source block IDs declare coverage, not semantic correctness. No expensive semantic verifier runs in this slice.

The audio adapter uses isolated Realtime contexts and bounded contiguous same-speaker groups. Turn slices retain exact text and ordering, including long turns split for transport. Transcript lexical differences are retained diagnostically, never automatically rerendered. Deterministic PCM validation rejects missing activity, long leading/trailing silence, internal stalls and implausible speech rates. Raw attempts remain unchanged. Realtime acceptance correlates response/item identifiers and output/content indices to exactly one completed assistant audio message, rather than requiring a one-item response array. Only additional empty completed assistant message envelopes are explicitly harmless; text-only extras, actionable items and ambiguous correlation are rejected. Safe structural sidecars retain item summaries, correlation identifiers and event counts without provider payload text. Assembly inserts only 200/350 ms pauses and performs one AAC-LC encode plus a complete decode check. Live-specific soft wrapping, orphan salvage and completion heuristics remain in the spike.

Safe attempt metadata includes capability/unit identity, provider/model, outcome, elapsed time, available usage, estimated USD cost, retryability and bounded error classification. Missing usage is shown as incomplete cost information, not zero measured spend. API credentials, source contents, prompts and raw response bodies are never logged. Source and transcript payloads are available only through the user-facing API and private artifact storage.

`BOOKCAST_AI_MODE=fake` provides deterministic no-network dialogue and test-tone audio for local development and tests. Normal tests use this mode, PostgreSQL Testcontainers and real migrations/encoding. Real provider adapters additionally have offline protocol fixtures. Selecting real adapters alone does not make paid calls; only an explicit Generate/retry action queues provider work. Within that authorized run, transient dialogue and audio provider failures use bounded jittered backoff (2/8 seconds by default, bounded Retry-After). Every attempt is retained; progress counts accepted checkpoints only. A restart never resumes spending automatically, including when interrupted during backoff. Local waveform validation and local parsing/configuration failures are not automatically retried. Realtime provider output-structure anomalies after validated session configuration are transient and use the same bounded audio retry policy; unknown provider schemas stop for investigation.

---

# 17. Architectural verification

Keep the initial executable rule set small.

## Spring Modulith

Use verification for:

```text
no module cycles
no access to another module's internals

generation → library public API   allowed
library → generation              forbidden
```

## Dependency direction

Use either jMolecules architecture rules or a small ArchUnit rule where appropriate.

Avoid enforcing the same invariant through multiple tools.

Initial rule:

```text
domain/application → adapters    forbidden
```

## Provider isolation

Add provider-SDK containment when the first real provider dependency is introduced.

Do not create a rule for a dependency that does not yet exist.

## Type system

Prefer typed identifiers and explicit state/result types where they reduce ambiguity.

The Java compiler is also an architecture tool.

---

# 18. Testing

## Deterministic tests

Use ordinary tests for:

- source normalization
- source-block ordering
- source identity
- application orchestration
- generation states
- retry behavior
- script structural validation
- module boundaries

## Fake adapters

Use deterministic fakes for external capabilities.

Fakes should be configurable enough to reproduce:

```text
success
invalid output
failure on a specific segment
retry success
```

## Real-provider tests

External-provider tests belong in a slower integration/evaluation category.

Do not make paid model calls part of every normal local test run.

## Generative evaluations

A later evaluation suite should measure things such as:

- source coverage
- unsupported additions
- preservation of qualifications
- output/source length ratio
- conversational continuity
- Finnish-language quality

Human-labelled examples should be preferred for important fidelity judgments.

Model-as-judge evaluation is useful evidence, not absolute truth.

---

# 19. Logging and privacy

Imported source material may be copyrighted or otherwise private.

Application logs must not contain full source text, full provider prompts, API credentials, or sensitive raw provider response bodies by default.

Log identifiers and metadata rather than source payloads.

Explicit diagnostic modes may expose more information locally, but must be opt-in.

---

# 20. Development-agent workflow

The repository is designed to work with multiple coding agents.

The primary development agent for this project is currently **OpenAI Codex**.

Claude Code may be used selectively as a second independent reviewer or for particularly demanding tasks.

This is a cost and workflow decision, not an architectural dependency.

No project knowledge should exist only in either agent's conversation history.

Durable context belongs in:

```text
AGENTS.md
docs/
ADRs
tests
OpenAPI
module metadata
architecture rules
source code
```

A future contributor should be able to work on Bookcast without access to the original ChatGPT, Codex, or Claude conversations.

Independent agent reviews are useful precisely because the reviewers do not share hidden conversational context.

---

# 21. Deliberately unresolved topics

The following do not need to be decided yet:

```text
PDF ingestion implementation
OCR
EPUB ingestion
automatic chapter detection
object-storage provider
distributed workflow engine
additional speech providers
alternative voice selection
learning module
listening module
provider routing
provider fallback
automatic semantic coverage judge
authentication implementation
multi-user isolation
PWA versus native/private-feed listening
```

Keep them unresolved until a real use case requires a decision.

---

# 22. Architecture decision boundary

Agents and contributors may identify architectural issues and propose alternatives.

Significant changes should normally trigger explicit human review, including:

- new top-level application module
- changing source identity/provenance semantics
- changing the generation unit model
- changing retry or regeneration semantics
- moving responsibility between modules
- new external infrastructure category
- replacement of the API style
- introduction of microservices
- introduction of another backend runtime
- bypassing provider ports
- changing persistence ownership

Agents may propose ADRs.

Only the human owner accepts architectural decisions.