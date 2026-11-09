# Bookcast

Create or open a book, paste a numbered chapter, generate a source-grounded Finnish HOST/EXPERT podcast, and listen in the browser. Chapters work independently; importing a whole book is unnecessary.

## Run locally

Requirements: Java 21+, Maven 3.9+, Node 22+, Docker and `ffmpeg` on PATH. One backend instance, PostgreSQL and its local audio directory form the initial deployment. No authentication: keep this development deployment on a trusted local network.

From the repository root:

```sh
docker compose up -d postgres
```

Backend (second terminal):

```sh
cd backend
export OPENAI_API_KEY='your-key'
mvn spring-boot:run
```

Frontend (third terminal):

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:3000. Importing text makes no paid call. **Generate Podcast and retry invoke paid APIs** when the backend uses its default `openai` mode. Dialogue uses GPT-5.4/low; audio uses gpt-realtime-2.1-mini with marin/ash at 0.95. Cost estimates appear with progress; unavailable usage is explicitly flagged.

For a no-network local smoke test, start the backend with:

```sh
cd backend
BOOKCAST_AI_MODE=fake mvn spring-boot:run
```

Fake mode produces short deterministic text and a test tone, not spoken content. It verifies the complete import, persistence, generation and player flow without charges.

Environment options:

- `DATABASE_URL` (JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`; defaults match compose (PostgreSQL on localhost:5442). Set `BOOKCAST_DB_PORT` for compose and a matching `DATABASE_URL` when changing the port.
- `BOOKCAST_DATA_DIR`; default `./data` relative to the backend working directory. Preserve this directory with the database; raw attempts are private and ignored by Git.
- `BOOKCAST_BACKEND_URL` for Next.js; default http://localhost:8080.
- `BOOKCAST_AI_MODE`: `openai` (default) or `fake`.
- `BOOKCAST_ENCODING_TIMEOUT_SECONDS`: timeout for each local ffmpeg encoding/decode step; default 1800 seconds for chapter-length audio. An assembly retry reuses completed dialogue and audio checkpoints.
- `BOOKCAST_DIALOGUE_MODEL` / `BOOKCAST_AUDIO_MODEL`: configuration-backed model names; the defaults above are the supported v1 baseline. Estimates for model overrides are unavailable until their pricing is added.
- `BOOKCAST_HOST_VOICE` / `BOOKCAST_EXPERT_VOICE` / `BOOKCAST_AUDIO_SPEED`: Realtime speech defaults `marin` / `ash` / `0.95` (`bookcast.speech.host-voice`, `expert-voice`, `speed`). Voices must be nonblank; speed must be finite and within 0.25–1.5. Voice availability is checked by the provider. These settings affect new generations; resuming with different settings is rejected before provider work.

## Verify

```sh
mvn -f backend/pom.xml test
npm --prefix frontend ci
npm --prefix frontend run check:api
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

Backend tests require Docker and ffmpeg. They use PostgreSQL Testcontainers, fake adapters and offline provider fixtures; **normal tests make no paid calls**, even if an API key exists in the environment. Architecture verification runs with the test suite.

## Books and existing chapters

Open the library, create or select a Book, and add chapters with explicit positive numbers. Numbers are unique within a Book; chapters are listed numerically. Each chapter keeps its existing `/chapters/{id}` URL, generation controls, player and download.

Older imports already have Book associations but no reliable chapter numbers. Migration V3 preserves every existing Book and Chapter ID and association and leaves their numbers unset—no guessing from titles and no automatic regrouping. They appear under **Järjestettävät vanhat luvut** and at the end of their current Book. Open a chapter and use **Kirja ja luvun numero** to select the correct Book and number (create the Book first if needed). This changes organization only: SourceRevision/SourceBlock IDs, generation jobs, paid checkpoints and audio files are untouched. Duplicate target numbers are rejected without moving the chapter. Previously created empty Books are retained; deletion/merging and bulk reordering are out of scope.

The book API is `/api/v1/books` and `/api/v1/books/{bookId}/chapters`. Organization uses `PUT /api/v1/chapters/{chapterId}/placement`. The existing top-level chapter import URL remains, now requiring `bookId` and `chapterNumber`; revision imports remain independent of organization. No earlier-chapter source or dialogue is sent to AI; cross-chapter context is deferred.

## Contract and persistence

`api/openapi.json` is authoritative. Maven generates Java endpoint interfaces and DTOs. Regenerate the browser types after contract changes:

```sh
npm --prefix frontend run generate:api
```

Flyway migrations:

- V1: books, chapters, immutable source revisions and ordered source blocks.
- V2: generation jobs, immutable script/audio checkpoints and safe attempts.
- V3: nullable legacy chapter numbers, positive-number check and unique `(book_id, chapter_number)` constraint. New imports and moves require a positive integer in the application/API; only legacy chapters may remain unnumbered.

The `library` and `generation` modules are checked by Spring Modulith and ArchUnit. Generation uses the library public API; provider transports exist only in outbound adapters. Source-block references establish provenance, not semantic fidelity.

Generation is queued and polled. Accepted segments/groups persist; a failed job can be retried explicitly from the first missing unit. A restart marks in-flight work failed rather than automatically spending again. The v1 worker supports one backend process. Each missing dialogue segment and audio group allows at most three provider attempts per explicit run/resume, only for classified transient failures. Resilience4j's high-level retry execution uses its default randomized exponential backoff. A valid `Retry-After` is a minimum wait; if it exceeds 60 seconds, automatic retries stop and the job requires explicit manual recovery. Dialogue retries cover only transient timeout/network/429/5xx failures. Request/configuration, quota/auth, malformed dialogue and waveform failures are not automatically retried. Dialogue and audio share this retry policy. Realtime output-structure anomalies are retryable after session configuration validation; unknown provider schemas and local parsing failures stop without automatic retry. Each Realtime attempt stores a safe `response-structure.json` summary and append-only `structure-events.jsonl` journal in its private attempt directory, containing identifiers, indices, item/content types and event counts, never raw event bodies or payload text. No transaction spans backoff. Provider connection/completion limits remain 20/25 and 180 seconds, separate from ffmpeg. Every dialogue and audio attempt records timing, retry classification/delay and available usage; unknown usage/cost remains unknown. Estimated cost includes failed attempts when usage is available; interrupted calls can have unknown charges.

Completed chapter audio can be downloaded as M4A for copying to a phone or another device. Suggested download filenames use Apache Commons IO’s Windows character/length rules, with Finnish letters retained and UTF-8 header encoding supplied by Spring. Unconvertible or rejected names use `Chapter.m4a`; storage filenames and embedded audio tags are unchanged by this naming step. Newly assembled files embed the chapter title, book title as album, and Bookcast as artist/album artist for media players; a known chapter number is included when it fits the M4A track field. Tags are written before the final checksum. Existing completed files and previous downloads are not automatically retagged or re-encoded. This is the intended local-development workflow before Bookcast is hosted remotely.

Each new OpenAI generation pins model, both voices and speech speed in an immutable private `speech-settings.json` beside its attempts. Start/resume checks this snapshot before provider work, including when every audio group is already accepted. A snapshot mismatch fails with `SPEECH_CONFIGURATION_CHANGED`; a verified legacy mismatch uses `SPEECH_CONFIGURATION_MISMATCH`. Missing/corrupt evidence fails with `SPEECH_CONFIGURATION_UNVERIFIED`. Manual retry displays the safe explanation for these HTTP 409 errors, with a generic fallback for other failures. Legacy generations without a snapshot can adopt only the historical default voices/speed, after all existing audio attempt configurations verify the selected voice, speed and model. Historical attempts are never rewritten or regenerated. Preserve these snapshots with the rest of `BOOKCAST_DATA_DIR`; unverifiable jobs require investigation rather than automatic recovery.

The player supports play/pause, native seeking, ±15 seconds, speed and resume per generation in localStorage. Audio endpoints support byte ranges. The browser discloses AI-generated voices.

## Intentional limits

No auth, PDF parsing, whole-book prerequisite, glossary, semantic model judge, GPT-Live, automatic text repair, TTS comparison, tutoring, quizzes, bookmarks, mobile app or offline playback. UI import is pasted text; text-file upload can follow later. Private source/artifact backup and production hosting are outside this first slice.

See [architecture](docs/ARCHITECTURE.md), [product vision](docs/PRODUCT_VISION.md) and [ADRs](docs/adr/) for project context. Earlier spike outputs remain experimental evidence and are not production dependencies.
