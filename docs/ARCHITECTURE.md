# Bookcast Architecture

## Status

This document describes the intended current architecture of Bookcast.

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
│ later: player / study         │
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

Do not create tooling directories before they are needed.

The repository itself should remain the durable source of context for both human and AI contributors.

---

# 3. Backend architectural style

The backend uses Java and Spring Boot.

The architectural direction is:

> DDD-oriented modular monolith with explicit module boundaries and ports/adapters around external capabilities.

Initially only modules with real behavior should exist.

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

The exact Java types are implementation details to be established during the first slice.

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

> produce a source-linked conversational script for a Chapter source revision.

Learning artifacts such as quizzes are deferred.

Speech generation is deferred until the script workflow is proven.

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

- use natural Finnish prose,
- preserve established English technical terminology where appropriate,
- do not mechanically translate established software and engineering terms,
- explain terminology conversationally where doing so improves learning.

Terminology handling belongs to Bookcast generation requirements rather than to a specific model provider.

## Speech language

Later speech synthesis must receive explicit language/locale information where supported.

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

The exact source-of-truth direction must be decided explicitly before the API pipeline is implemented.

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

The first frontend does not require:

```text
PWA installation
audio player
offline mode
authentication
quiz UI
flashcards
```

Those belong to later slices.

---

# 16. First production vertical slice

After the real-model spike, the first production slice is:

```text
Browser
   │
   │ Create book/chapter
   │ Paste text or upload .txt
   ▼
Next.js
   │
   │ OpenAPI
   ▼
Spring Boot
   │
   ├── library
   │     │
   │     ├── Book
   │     ├── Chapter
   │     ├── SourceRevision
   │     └── SourceBlock[]
   │
   └── generation
          │
          ├── read immutable ChapterSource
          │
          ├── create Generation
          │
          ├── create multiple ScriptSegments
          │
          └── FakeGenerationAdapter
                     │
                     ▼
               DialogueTurn[]
                     │
                     ▼
                 OpenAPI
                     │
                     ▼
                Next.js UI
```

The fake generation adapter should:

- return deterministic structured output
- generate multiple segments
- support deliberate failure on a selected segment

This lets the application test:

- orchestration
- progress
- failure
- retry
- module boundaries
- source provenance

without depending on external model behavior.

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

Application logs must not contain full source text or full provider prompts by default.

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
speech provider
voice selection
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