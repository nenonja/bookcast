# Bookcast Current State

Last reviewed: 2026-10-18.

This document describes the current application implementation and development focus. It is not an inventory of the owner's local database, paid jobs or media files, and does not imply that a particular local instance has applied every migration.

Use [PRODUCT_VISION.md](PRODUCT_VISION.md) for product intent, [ARCHITECTURE.md](ARCHITECTURE.md) and accepted [ADRs](adr/) for architectural direction, and the [README](../README.md) for setup, configuration and verification commands. Update this document when implemented behavior or current scope changes.

## Implemented product flow

Bookcast has a usable local vertical slice:

```text
Create/open Book
  → add numbered Chapter with pasted source text
  → store immutable SourceRevision and ordered SourceBlocks
  → explicitly request Finnish HOST/EXPERT dialogue generation
  → render checkpointed audio groups
  → assemble Chapter M4A
  → play in browser or download for listening on another device
```

### Library and chapter organization

- Books have stable IDs and titles. The library lists Books; each Book page lists its Chapters in numerical order and supports adding a Chapter.
- New Chapters require an existing Book and a positive integer chapter number. Numbers are unique within a Book, enforced by application validation and PostgreSQL constraints. Chapters in different Books can use the same number.
- Chapter placement can be changed from the Chapter page. Reassignment changes only Book membership and chapter number; it does not create a source revision, regenerate content or change Chapter IDs and existing generation references.
- Source normalization creates ordered, revision-scoped block IDs and records exclusion reasons for a small set of acquisition/UI controls. Source text and exclusion decisions are inspectable on the Chapter page.
- Source revisions and their blocks are immutable. The API supports importing another revision without altering the previous source.
- Existing `/chapters/{id}` URLs remain valid. Pre-existing unnumbered Chapters appear in a temporary organization list and after numbered Chapters in their current Book. Membership and numbering are assigned explicitly; nothing is inferred from titles.

### Dialogue and audio generation

- Jobs expose `queued`, `running`, `succeeded` and `failed` states, a current stage, and accepted dialogue/audio counts. The frontend polls active jobs.
- The supported real-provider baseline is GPT-5.4 with low reasoning for dialogue, followed by `gpt-realtime-2.1-mini` audio with HOST `marin`, EXPERT `ash` and API speed `0.95`.
- Realtime voices and speed are configurable via `bookcast.speech` (defaults above; speed 0.25–1.5). New OpenAI jobs pin model, both voices and speed in private immutable `speech-settings.json` files before provider work. Incompatible resumes fail explicitly; legacy jobs can adopt only verified historical defaults. Attempt diagnostics include the selected voice and speed. Existing artifacts are preserved.
- Dialogue uses the v4-style Finnish guidance: source-grounded topic discussion, expert-led delivery, natural professional Finnish and preservation of substantive source material. The current execution settings use approximately 800 source words per unit and the previous segment's final four turns. These are implementation settings, not architectural invariants.
- ScriptSegments reference source block IDs and contain structured HOST/EXPERT DialogueTurns. New dialogue is structurally validated before checkpointing. A matching leading speaker label is removed from newly generated turn text; an opposite-speaker prefix is rejected. Existing saved dialogue is not rewritten.
- Audio rendering uses isolated contexts and bounded contiguous same-speaker groups. Attempts preserve raw PCM, transcripts, safe metadata and structural diagnostics. Acceptance checks streaming-event correlation and deterministic waveform plausibility. Transcript lexical differences remain visible and do not trigger automatic rerendering.
- Accepted dialogue segments and audio groups are checkpoints. Resume validates and reuses them, starts at the first missing unit, and can repeat assembly without regenerating accepted audio.
- Transient provider failures have bounded automatic retries: at most three attempts per missing dialogue segment or audio group per run/resume, using Resilience4j's high-level retry execution and default randomized exponential backoff. A valid Retry-After is never shortened: values above 60 seconds stop automatic retries and require explicit manual recovery. Request/configuration/auth/quota errors and local validation failures do not receive automatic retries. Every attempt retains available usage; missing usage/cost remains explicitly unknown.
- Generate requests have an idempotency key. Startup recovery marks interrupted running work failed; it does not automatically resume that paid work. The owner can explicitly retry/resume from the UI.
- Completed chapters offer a separate **Generoi uusi podcast** action using the existing creation API, a fresh request key and the same current source revision. It starts with empty checkpoints and current provider settings. Earlier completed players/downloads remain under **Aiemmat valmiit podcastit**; their jobs, attempts, costs and media are preserved. Uncertain creation responses retain their pending key for idempotent resubmission.
- Local assembly adds 200/350 ms pauses, performs one AAC-LC M4A encode, checks complete decoding and records a final hash. Encoding has a separate timeout from provider calls.

### Listening and cost visibility

- The browser player supports play/pause, seeking, ±15 seconds, playback speed and position/duration display. Resume position is saved per generation in that browser's localStorage.
- Completed audio has a separate attachment-download endpoint with a useful UTF-8 filename normalized by Apache Commons IO’s Windows filename rules. Newly assembled M4As carry the chapter title, book album, Bookcast artist/album artist and an optional representable chapter track number. Previously completed files are preserved without automatic retagging. Playback retains byte-range support. Download streams the existing artifact without model calls or re-encoding.
- Generation details display dialogue/audio model names and separate estimated dialogue, audio and total costs. Failed attempts contribute when usage is available; incomplete accounting is indicated rather than treated as measured zero spend.
- The UI discloses that voices are AI-generated. Downloading M4A and copying it to a phone is the current local-development listening workflow.

## Implementation and verification

- Java 21 / Spring Boot backend with two Spring Modulith modules: `library` owns source material and organization; `generation` owns jobs, dialogue and audio artifacts. Generation depends on the library public API, never the reverse.
- A limited Spring Data JDBC pilot handles ordinary generation-job lookups through a read-only repository; critical commands and JSONB/checkpoints remain JdbcTemplate SQL. Domain/application persistence ports and Flyway migrations are unchanged. See [the assessment](SPRING_DATA_PERSISTENCE_PILOT.md).
- PostgreSQL and Flyway persist library data, jobs and checkpoints. V1 introduces source storage and immutability triggers; V2 adds generation storage; V3 adds chapter numbers without regrouping, renumbering or replacing existing data.
- Private candidates, raw attempts and assembled media live on the local filesystem. The database and configured data directory must be preserved together.
- Next.js / TypeScript consumes the checked-in OpenAPI contract through generated types; Maven generates Java API interfaces and DTOs from the same contract.
- Existing automated checks cover domain behavior, retry limits, checkpoint recovery, provider protocol fixtures, PostgreSQL persistence, populated-database migration, API behavior, Book organization and player/download controls. Spring Modulith and ArchUnit check module boundaries and dependency direction.
- Normal tests use fake adapters and offline provider fixtures, with PostgreSQL Testcontainers and local ffmpeg where needed. They must not make paid calls. Fake mode is also available for a complete local smoke test with deterministic dialogue and test-tone audio.

## Known limitations

- This is a trusted single-user local application with one backend worker instance. There is no authentication, authorization, public sharing, cloud/object storage or multi-instance recovery support.
- The UI accepts pasted text, currently limited to 200,000 characters. File upload, PDF/EPUB import, automatic chapter detection and rich figure/table extraction are not implemented.
- Book organization does not supply earlier Chapters or their dialogue to AI. Generation works from the selected Chapter/SourceRevision. There is no Book glossary or terminology memory; continuity context is local to neighboring dialogue segments.
- Book deletion/merging, bulk chapter organization and title editing are not implemented in the UI. Empty Books remain after Chapters move away. Existing unnumbered Chapters require explicit owner organization.
- Structural coverage declarations, waveform checks and provider transcripts are diagnostics, not proof of semantic fidelity or complete audible speech. The application does not run the experimental expensive semantic verifier or automatically repair generated content. Finnish pronunciation, terminology and source fidelity still need owner listening/review.
- The Realtime renderer retains its own waveform gates, including rejection of long leading/trailing silence and internal stalls. GPT-Live's experimental tail-trimming, soft-wrap and orphan-salvage mechanics are not part of this application path.
- Cost reporting is estimated and may be incomplete. There is no application-level paid-call budget gate or sophisticated pricing interface.
- Playback position is browser-local, with no cross-device synchronization, transcript seeking, bookmarks, PWA/offline playback or dedicated mobile app.
- Source language is recorded separately, but the implemented generation flow requests Finnish output; there is no output-language selector yet.

## Current development focus

1. Stabilize and dogfood the first product slice: Book-centric chapter entry/organization, independent Chapter generation, bounded recovery, browser listening and M4A download.
2. Preserve existing source identities, paid attempts, accepted checkpoints and completed media while evolving the implementation. Schema verification must account for populated databases.
3. Keep provider failures diagnosable through safe structured metadata and deterministic offline tests. Never resume, regenerate or invoke paid work as part of routine development without explicit authorization.

Cross-chapter AI context and continuity are deferred to Phase 2 and require a separate instruction. Book IDs and chapter ordering provide a foundation only; no context manifests, earlier-chapter prompts or memory have been implemented.

GPT-Live remains experimental evidence for a possible higher-quality presentation path. It is not the v1 renderer or current development focus. Do not resume Live experiments or promote their mechanics into the application without a separately authorized use case. Learning features such as quizzes/tutoring and production hosting are also deferred.
