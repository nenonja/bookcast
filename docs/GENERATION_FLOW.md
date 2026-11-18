# Restartable, checkpointed generation pipeline

Verified against implementation and existing tests on 2026-11-15. This describes the OpenAI-backed, single-worker deployment. Fake capabilities use the same application orchestration without paid calls. See [DOMAIN_MODEL.md](DOMAIN_MODEL.md) for Java records/ports and [DATA_MODEL.md](DATA_MODEL.md) for relational persistence.

## Processing boundaries and familiar patterns

The pipeline has two distinct generation stages followed by assembly: source blocks → `DialogueSegment`/`DialogueTurn` values; dialogue turns → planned `SpeechChunk` values → accepted `ValidatedAudioChunk` checkpoints; accepted PCM → final M4A.

`AudioResult` remains the unvalidated capability result and can contain failed or partial output. `ValidatedAudioChunk` represents technical acceptance and becomes a reusable checkpoint on insertion; it does not guarantee transcript accuracy or complete audible word coverage. `ProviderAttempt` records the separate call history/accounting. The final M4A is the assembled podcast, not an individual validated chunk.

Polling exposes `completedSpeechChunks` (accepted persisted chunks) and `totalSpeechChunks` (planned chunks). The accepted checkpoints live in SQL `validated_audio_chunks`, ordered by `chunk_order`; V4 renames the old table/column without changing checkpoint JSONB, attempt history or artifact paths. The total remains in `GenerationJob.totalAudio` / SQL `generations.total_audio`. Provider failures and temporary candidates contribute no accepted count. See [migration and backup steps](DATA_MODEL.md#applying-v4-to-existing-local-data).

| Conceptual pattern | Bookcast's existing implementation |
| --- | --- |
| Pipes and Filters / staged processing | Source filtering/partitioning, dialogue generation/validation, speech planning/rendering/validation, then assembly. These are ordinary service calls with persisted results, not a stream framework. |
| Partitioned or chunk-oriented processing | Dialogue units group source blocks; the independent speech partition is bounded, single-speaker chunks with turn spans. The two partitions serve different provider-request boundaries. |
| Workflow orchestration and explicit state machine | `GenerationService` coordinates the stages, while the worker claims persisted jobs. Status transitions are explicit application operations. |
| Checkpointing, restartability and idempotent acceptance of completed work | Accepted prefixes are reused, and primary keys plus immutability triggers prevent replacing accepted work. Duplicate checkpoint INSERTs fail; they are not no-op upserts. Request idempotency is a separate creation rule. |

These are descriptions of the current design, not new infrastructure. Bookcast uses neither Spring Batch nor Temporal nor a message broker. **External provider execution is not exactly once:** a crash after a response but before its accepted checkpoint may require another paid call. A unique checkpoint key protects acceptance, not the execution of the remote call.

## Persisted job lifecycle

Only these four names are `Podcast.Status` values. The self-transitions below update a running job's stage/progress or retry one missing unit; they are not new queue entries or additional job states.

```mermaid
stateDiagram-v2
    [*] --> queued: create new job
    queued --> running: atomic claim()
    running --> running: accept checkpoint and advance stage
    running --> running: transient unit failure with retry allowed
    running --> succeeded: assembly returns verified M4A path
    running --> failed: permanent, exhausted or deferred failure
    running --> failed: unexpected execution exception
    running --> failed: startup interrupted()
    failed --> queued: explicit retry after compatibility checks

    note right of running
        Stages include dialogue, audio and assembly
        Automatic attempts remain within running
    end note
    note right of succeeded
        Historical job with no manual retry transition
        Fresh generation creates a different job ID
    end note
```

| Transition / operation | Actual behavior and preconditions |
| --- | --- |
| Creation | `start` validates the selected Chapter/revision, inserts a queued job, then checks model/speech compatibility. A repeated request returns its original job; another request for an active revision returns that active job. The diagram's initial edge applies only to a new insert. |
| Claim | `claim()` uses one `UPDATE ... FOR UPDATE SKIP LOCKED ... RETURNING` statement, setting `running`/`dialogue`. No transaction spans provider work. |
| Running stages | Dialogue attempts use `dialogue`; audio uses `audio` or `audio_retry:<chunk number>:<attempt number>`; assembly uses `assembly`. Retry-stage chunk numbers are one-based. Total counters are planned counts; completed counts come from separate accepted-checkpoint reads. |
| Automatic retry | At most three calls per missing dialogue segment/speech chunk per run, including the initial call. Resilience4j supplies randomized exponential backoff. Only explicitly classified transient results qualify; exceptions do not. Valid Retry-After is a minimum; hints above 60 seconds stop automatic retry and leave the job failed for manual recovery. |
| Failure | Controlled dialogue/audio failures retain their respective stage and transient assessment. `workOne` catches unexpected exceptions and writes `failed`/`failed` with a safe code and `retryable=false`. A failed attempt can be followed by a successful attempt while the job stays running. |
| Completion | Assembly must return after encoding, full decode verification and checksum creation before the service writes `succeeded`/`complete` plus `audioPath`. |
| Manual retry | Requires **failed status**, compatible configured model names and speech settings, and no conflicting active job for the revision. The `retryable` flag is advisory for this action. The same ID is requeued, its error cleared, and checkpoints/attempts retained. Completed jobs are rejected. |
| Startup | `GenerationWorker.ready()` calls interruption recovery before enabling polling. Running jobs become `failed`/`interrupted` with `PROCESS_INTERRUPTED`; still-attempting records become `interrupted`. No failed job is requeued. Jobs already queued remain claimable. |

ProviderAttempt `outcome` strings (`attempting`, `succeeded`, `failed`, `interrupted`) are separate from GenerationJob status; `retryClassification` and `nextRetryDelayMs` are attempt metadata. Stage strings and counters are not a second durable state machine. Accepted checkpoints survive all these transitions and cannot be updated/deleted through the adapter or under the Flyway triggers.

This is the service/worker transition graph, not a database-enforced state machine. `JdbcGenerationStore.state()` can write any schema-valid status; the conditional manual-retry SQL and the partial active-revision index enforce narrower rules. A completed generation stays historical through the supported service API. Starting another version uses a fresh creation request, new job identity and empty checkpoint history.

Review: [GenerationService](../backend/src/main/java/com/bookcast/app/generation/application/GenerationService.java), [GenerationWorker](../backend/src/main/java/com/bookcast/app/generation/adapter/GenerationWorker.java), [JdbcGenerationStore](../backend/src/main/java/com/bookcast/app/generation/adapter/JdbcGenerationStore.java), [AudioRetryPolicy](../backend/src/main/java/com/bookcast/app/generation/application/AudioRetryPolicy.java), [DialogueRetryPolicy](../backend/src/main/java/com/bookcast/app/generation/application/DialogueRetryPolicy.java). [AudioRetryTest](../backend/src/test/java/com/bookcast/app/AudioRetryTest.java) and [DialogueRetryTest](../backend/src/test/java/com/bookcast/app/DialogueRetryTest.java) cover limits, deferral and interruptions; [GenerationConcurrencyTest](../backend/src/test/java/com/bookcast/app/generation/adapter/GenerationConcurrencyTest.java) verifies atomic claims/idempotency; [GenerationPersistenceTest](../backend/src/test/java/com/bookcast/app/generation/adapter/GenerationPersistenceTest.java) verifies retry/recovery preservation.

## Happy path: new request to M4A

The frontend already has a Chapter snapshot and submits its `sourceRevisionId` with a request UUID. This trace assumes a fresh request with no active job for that revision and successful provider calls. `Store` is the application port implemented by `JdbcGenerationStore`; its checkpoint/attempt messages are PostgreSQL writes, while `Files` is local storage. Capability calls go through the Bookcast interfaces even though the concrete adapters are named below.

```mermaid
sequenceDiagram
    actor UI as Frontend
    participant HTTP as GenerationController
    participant Service as GenerationService
    participant Store as GenerationStore
    participant Library as Library
    participant Worker as GenerationWorker
    participant Text as OpenAiText
    participant Speech as OpenAiSpeech
    participant Audio as LocalAudioArtifacts
    participant Files as Filesystem

    UI->>HTTP: POST chapter generations with revision and request IDs
    HTTP->>Service: start(chapter, revision, request)
    Service->>Library: revision(chapter, revision)
    Library-->>Service: ChapterSource
    Service->>Store: create(request, chapter, revision, models)
    Store-->>Service: queued GenerationJob
    Service->>Speech: prepareGeneration(job.id)
    Speech->>Files: Pin speech-settings.json
    Service-->>HTTP: GenerationJob
    HTTP-->>UI: 202 with GenerationDto

    Worker->>Service: workOne()
    Service->>Store: claim()
    Store-->>Service: running GenerationJob
    Service->>Service: prepareGeneration(job) checks model names
    Service->>Speech: prepareGeneration(job.id)
    Speech->>Files: Verify speech-settings.json
    Service->>Library: revision(job.chapterId, job.sourceRevisionId)
    Library-->>Service: ChapterSource with ordered blocks
    Service->>Service: units(source) skips excluded blocks
    Service->>Store: totals(...) then segments(job.id)
    Store-->>Service: Accepted prefix, empty for this new job
    loop Each missing dialogue unit
        Service->>Store: state running/dialogue and attempt attempting
        Service->>Text: generate(TextRequest)
        Note over Service,Text: Source blocks plus previous segment's last four turns and boundary flags
        Text->>Files: CREATE_NEW dialogue-candidate.json
        Text-->>Service: TextResult with candidate and Metadata
        Service->>Service: DialogueText.normalize and DialogueSegment.validate
        Service->>Store: attempt succeeded with usage and cost
        Service->>Store: segment(job.id, segment) INSERT
    end
    Service->>Service: SpeechChunkPlanner.plan(all accepted segments)
    Service->>Store: totals(...) then audio(job.id)
    Store-->>Service: Accepted audio prefix, empty for this new job
    loop Each missing speech chunk
        Service->>Store: state running/audio and attempt attempting
        Service->>Speech: render(group, job.id, attempt.id)
        Speech->>Files: Spool stream.pcm and private diagnostics
        Note over Speech: Verify session.updated and response submission, validate bounded stream structure
        Speech-->>Service: AudioResult with PCM, transcript and Metadata
        Service->>Audio: preserve(job.id, attempt.id, pcm, transcript)
        Audio->>Files: CREATE_NEW raw.pcm and transcript.txt
        Audio-->>Service: PCM reference
        Service->>Audio: validate(group, reference, transcript)
        Audio-->>Service: ValidatedAudioChunk
        Service->>Store: attempt succeeded with usage and cost
        Service->>Store: audio(job.id, receipt) INSERT validated_audio_chunks
    end
    Service->>Store: state running/assembly
    Service->>Audio: assemble(job.id, receipts, AudioMetadata)
    Audio->>Files: Verify hashes and concatenate ordered PCM with pauses
    Note over Audio,Files: ffmpeg encodes M4A with tags, verifies decoding, writes checksum
    Audio-->>Service: Final relative M4A path
    Service->>Store: state succeeded/complete with audioPath
    Service-->>Worker: true
```

This is one possible ordering: the creation transaction commits **before** `prepareGeneration`, so the worker can claim a queued job before the HTTP response returns. A creation-time preparation exception does not roll back the inserted queue row. Preparation is checked at start, worker execution and speech rendering. `GenerationDto` construction also reads accepted scripts, audio and attempts separately; these read calls and repeated frontend polling are omitted above, and the DTO is not an atomic progress snapshot.

Source units retain whole nonexcluded blocks under the current word-budget setting (default 800; an oversized block can stand alone). The service starts with saved dialogue, validates its source IDs/order, appends new segments, and plans audio from the **combined** list. The OpenAI text adapter loads the bundled prompt at construction, uses a strict response schema and preserves a candidate file before application validation. That file is not an accepted script checkpoint.

Speech rendering opens an isolated Realtime session for each planned speech chunk. Configuration acknowledgement and successful response submission are required before accepting completion; bounded event/audio/transcript processing and structural validation can still fail. `LocalAudioArtifacts` then preserves returned bytes/text, checks waveform plausibility and produces a `ValidatedAudioChunk`. Provider success, an attempt marked succeeded, and insertion of an accepted checkpoint are separate steps.

Assembly requires chunk orders starting at zero without gaps, rechecks PCM hashes, inserts 200 ms pauses for the same speaker or 350 ms for a speaker change, and creates fresh scratch PCM/M4A names. Source PCM is copied without trimming. The result is mono 24 kHz AAC-LC M4A with display metadata and a SHA-256 sidecar. Only after success is the job's final path stored. Subsequent controller polling exposes playback/download URLs; `getAudio`/`downloadAudio` serve existing files without generation or re-encoding.

There is no transaction covering HTTP provider calls, WebSocket sessions, backoff, filesystem writes or assembly. `JdbcGenerationStore.create` has a short JDBC transaction; its restricted Spring Data reads share the JDBC transaction manager. Other job commands, attempt upserts and checkpoint inserts remain explicit SQL. Library import transactions are a separate concern.

Review: [OpenAiText](../backend/src/main/java/com/bookcast/app/generation/adapter/openai/OpenAiText.java), [OpenAiSpeech](../backend/src/main/java/com/bookcast/app/generation/adapter/openai/OpenAiSpeech.java), [LocalAudioArtifacts](../backend/src/main/java/com/bookcast/app/generation/adapter/LocalAudioArtifacts.java), [SpeechChunkPlanner](../backend/src/main/java/com/bookcast/app/generation/domain/SpeechChunkPlanner.java), [GenerationController](../backend/src/main/java/com/bookcast/app/generation/adapter/GenerationController.java). [ProviderProtocolTest](../backend/src/test/java/com/bookcast/app/generation/adapter/openai/ProviderProtocolTest.java) covers request context/protocol fixtures; [OpenAiSpeechLifecycleTest](../backend/src/test/java/com/bookcast/app/generation/adapter/openai/OpenAiSpeechLifecycleTest.java) covers configuration, completion and resource failures; [ProductFlowTest](../backend/src/test/java/com/bookcast/app/ProductFlowTest.java) covers complete fake-provider execution, preserved originals and file serving.

## Failure, restart and explicit resume

This representative trace starts in the audio stage: all dialogue and some speech chunks are accepted. One missing chunk's first attempt returns a transient error, which is recorded before automatic backoff; the next call is interrupted by process termination. It has no accepted receipt. Partial files can exist without a finished attempt or checkpoint. This example assumes model names match on manual retry; a model mismatch rejects the request before calling speech preparation. The compatible branch shows a successful resume; further failures follow the lifecycle rules above.

```mermaid
sequenceDiagram
    actor UI as Frontend
    participant HTTP as GenerationController
    participant Worker as GenerationWorker
    participant Service as GenerationService
    participant Store as GenerationStore
    participant Library as Library
    participant Speech as OpenAiSpeech
    participant Audio as LocalAudioArtifacts
    participant Files as Filesystem

    Note over Service,Store: Existing accepted dialogue and earlier audio checkpoints
    Service->>Store: attempt A as attempting for missing chunk
    Service->>Speech: render(missing chunk, job.id, A)
    Speech-->>Service: Partial AudioResult with transient error and available usage
    Service->>Audio: preserve(partial PCM and transcript)
    Audio->>Files: Retain attempt A raw files
    Service->>Store: attempt A failed with usage, cost and next delay
    Note over Service: AudioRetryPolicy waits, then retries the same missing unit
    Service->>Store: attempt B as attempting with fresh ID
    Service->>Speech: render(same missing chunk, job.id, B)
    Speech->>Files: ProviderAttempt B stream and journals
    Note over Worker,Files: Process stops before B finishes or a receipt is inserted

    Note over Worker: ApplicationReadyEvent invokes ready() on restart
    Worker->>Service: interrupted()
    Service->>Store: interrupted()
    Note over Store: Running job becomes failed/interrupted, B becomes interrupted
    Note over Store,Files: Accepted checkpoints, A history and existing files are retained
    Note over Worker: Polling enabled after recovery, failed job stays unqueued

    UI->>HTTP: POST generation retry for the same job ID
    HTTP->>Service: retry(job.id)
    Service->>Store: get(job.id)
    Store-->>Service: failed GenerationJob
    Service->>Service: require failed status and matching model profiles
    Service->>Speech: prepareGeneration(job.id)
    Speech->>Files: Verify pinned settings or legacy evidence
    alt Speech settings incompatible or unverifiable
        Speech-->>Service: Configuration exception
        Service-->>HTTP: Reject without requeue
        HTTP-->>UI: HTTP 409
    else Compatibility checks pass
        Service->>Store: retry(job.id) then get(job.id)
        Service-->>HTTP: queued GenerationJob
        HTTP-->>UI: 202 with same job ID
        Worker->>Service: workOne()
        Service->>Store: claim()
        Store-->>Service: running GenerationJob
        Service->>Speech: prepareGeneration(job.id) after model checks
        Service->>Library: revision(chapterId, sourceRevisionId)
        Library-->>Service: Same source revision
        Service->>Store: segments(job.id)
        Store-->>Service: All accepted dialogue
        Service->>Service: Validate segments and derive same audio plan
        Service->>Store: audio(job.id)
        Store-->>Service: Earlier accepted receipts
        Service->>Audio: verify(each saved receipt)
        Audio->>Files: Check accepted PCM hashes
        Note over Service: No text calls and no speech calls for accepted chunks
        loop Only missing speech chunks
            Service->>Store: New attempting record with fresh ID
            Service->>Speech: render(missing chunk, job.id, new attempt ID)
            Speech-->>Service: AudioResult candidate
            Service->>Audio: preserve(...) then validate(...)
            Service->>Store: Finalize attempt then INSERT accepted receipt
        end
        Service->>Store: state running/assembly
        Service->>Audio: assemble(all receipts in order)
        Audio->>Files: Fresh assembly output from preserved and new PCM
        Service->>Store: state succeeded/complete with final path
    end
```

Recovery is triggered by backend startup, not a retry request. Its two SQL updates are not wrapped in one transaction. It preserves existing usage/cost fields, marks still-attempting calls as uncertain/interrupted, and leaves accepted rows/files untouched. `ready` becomes true only after both updates return. The deployment assumes one backend; this is not a multi-instance lease/recovery mechanism.

Manual retry checks the failed GenerationJob and current text/audio model names, then asks speech rendering to verify its immutable per-job settings snapshot. OpenAI checks model, both voices and speed; legacy jobs require historical attempt evidence. A failure leaves the job failed and makes no provider call. This is not a general configuration snapshot: dialogue prompt/version and source-unit word budget are not pinned per job. The worker checks compatibility and reconstructed plans again after claiming.

Resume uses accepted **prefixes**, not provider attempt counts. Persisted dialogue is validated against newly reconstructed source units; completed segments are not regenerated. The audio plan includes old and new dialogue. Saved receipts must match planned chunks and PCM hashes; processing continues after the accepted prefix. SQL keys ensure uniqueness but not contiguous ordering; the sequential writer produces prefixes, and assembly explicitly rejects receipt-order gaps. If every speech chunk is already accepted, no provider call is needed and assembly can run again.

Existing DialogueSegment/ValidatedAudioChunk rows, attempt IDs and accounting, accepted PCM/transcripts, failed candidates, stream journals and prior assembly files are preserved. New calls use fresh attempt directories. Assembly uses new scratch/output filenames, so a failed assembly can be retried without rewriting accepted audio. Files written before a crash may be partial; they do not become checkpoints merely by existing.

Additional provider cost is possible only for missing units, including a call interrupted after the provider received it. Automatic retries and a later manual retry can each incur further calls; the per-unit three-attempt budget starts anew on each run. ProviderAttempt finalization and checkpoint insertion are separate writes: a crash after a successful call/attempt record but before checkpoint insertion can cause that missing unit to be called again. Saved candidates are not automatically promoted or reused as accepted work. Usage may be unknown even when the provider charged for interrupted work.

Review: [AudioRetryTest](../backend/src/test/java/com/bookcast/app/AudioRetryTest.java) checks interrupted backoff, retained failed usage, skipped completed chunks and configuration rejection; [DialogueRetryTest](../backend/src/test/java/com/bookcast/app/DialogueRetryTest.java) checks preserved dialogue, bounded continuity and skipped text calls; [ProductFlowTest](../backend/src/test/java/com/bookcast/app/ProductFlowTest.java) exercises startup/manual recovery with an unchanged accepted PCM hash. [OpenAiSpeechSettingsTest](../backend/src/test/java/com/bookcast/app/generation/adapter/openai/OpenAiSpeechSettingsTest.java) covers legacy/pinned compatibility; [GenerationPersistenceTest](../backend/src/test/java/com/bookcast/app/generation/adapter/GenerationPersistenceTest.java) checks recovery/accounting, advisory manual retryability and checkpoint triggers.

## Worked example: planning is not acceptance

This is a small synthetic fixture, not production content or output from a paid call. It illustrates structure, not semantic-fidelity evaluation. Let B0, B1 and B2 be three ordered source-block UUIDs containing brief statements about ordered processing, preserving completed parts and possible connection interruption. A candidate `DialogueSegment` has `order=0`, `coveredSourceBlockIds=[B0,B1,B2]` and four turns; validation and checkpoint insertion make it accepted.

| Turn index | Role | Text | Java text length |
| --- | --- | --- | --- |
| 0 | HOST | Mistä aloitetaan? | 17 |
| 1 | EXPERT | Lähde jaetaan osiin. | 20 |
| 2 | EXPERT | Valmiit osat säilyvät. | 22 |
| 3 | HOST | Mitä jos yhteys katkeaa? | 24 |

`SpeechChunkPlanner.plan` produces the following global order. Offsets are within each original turn, with an exclusive end; they are not offsets into combined chunk text.

| Chunk order | Segment | Speaker | Text construction | `slices` contents |
| --- | --- | --- | --- | --- |
| 0 | 0 | HOST | Turn 0 | `TurnSpan(0,0,17)` |
| 1 | 0 | EXPERT | Turn 1 + `\n\n` + Turn 2 | `TurnSpan(1,0,20)`, `TurnSpan(2,0,22)` |
| 2 | 0 | HOST | Turn 3 | `TurnSpan(3,0,24)` |

Consecutive EXPERT turns share chunk 1 because they belong to the same segment, have the same speaker and their combined text fits the unchanged 1,400-byte UTF-8 budget (47 bytes here). The blank-line separator is inserted by the planner; both spans still refer to their original turn text. The text hash is SHA-256 of that exact combined UTF-8 string. Same-speaker turns across a segment boundary are not merged.

Suppose chunk 0 is already accepted. Rendering chunk 1 starts ProviderAttempt A (`capability=audio`, legacy `unitKey=group-1`, `attemptNumber=1`). A returns a classified transient timeout: its outcome becomes `failed`, available usage/cost and partial PCM/transcript remain, and **no ValidatedAudioChunk is inserted**. If the bounded policy permits another call, ProviderAttempt B gets a new ID with the same unit key and `attemptNumber=2`.

If B completes successfully, returned PCM/transcript are preserved under `<job>/<B>/raw.pcm` and `transcript.txt`, then checked. Only after structural and waveform gates pass can `ValidatedAudioChunk.group` refer to chunk 1 with its PCM path/hash, transcript and waveform diagnostics. B's attempt is finalized and the validated checkpoint is inserted into `validated_audio_chunks` under `(generation_id, chunk_order=1)`. The JSONB `group` property and attempt `unitKey=group-1` remain unchanged. A planned chunk alone is not evidence that any audio exists; a successful attempt record alone is not an accepted checkpoint either.

After chunk 2 also has a receipt, assembly consumes receipts in order 0, 1, 2. This HOST → EXPERT → HOST example adds two 350 ms pauses. With six seconds of PCM per chunk the concatenated PCM is 18.7 seconds before AAC encoding. Accepted PCM is verified and copied; final M4A gets a new filename. If execution stops after accepting chunk 1, resume preserves receipts 0 and 1 and renders only chunk 2. An interrupted B without a receipt can instead require another paid call.

These boundaries keep requests bounded, select one configured voice per speaker, preserve results independently and let recovery skip completed work. Frozen JSON/hashes/spans are checked by [GenerationJsonCompatibilityTest](../backend/src/test/java/com/bookcast/app/generation/adapter/GenerationJsonCompatibilityTest.java); [ProductFlowTest](../backend/src/test/java/com/bookcast/app/ProductFlowTest.java) seeds old-shaped JSONB/PCM, resumes only missing chunks and compares assembled PCM byte-for-byte in global order. [GenerationPersistenceTest](../backend/src/test/java/com/bookcast/app/generation/adapter/GenerationPersistenceTest.java) checks raw legacy JSONB reads without rewriting payloads.

## Verification scope

Diagrams were checked against the linked Java sources, [V1](../backend/src/main/resources/db/migration/V1__library.sql), [V2](../backend/src/main/resources/db/migration/V2__generation.sql), [V3](../backend/src/main/resources/db/migration/V3__book_chapter_numbers.sql), and the existing tests named above. No jobs or providers were run for this documentation. No local Mermaid renderer was available; visual rendering was not verified. The diagrams use ordinary class, state and sequence syntax without HTML labels or custom rendering configuration.
