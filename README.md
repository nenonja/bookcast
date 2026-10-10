# Bookcast

Paste one chapter, generate a source-grounded Finnish HOST/EXPERT podcast, and listen in the browser. Chapters work independently; importing a whole book is unnecessary.

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

## Contract and persistence

`api/openapi.json` is authoritative. Maven generates Java endpoint interfaces and DTOs. Regenerate the browser types after contract changes:

```sh
npm --prefix frontend run generate:api
```

Flyway migrations:

- V1: books, chapters, immutable source revisions and ordered source blocks.
- V2: generation jobs, immutable script/audio checkpoints and safe attempts.

The `library` and `generation` modules are checked by Spring Modulith and ArchUnit. Generation uses the library public API; provider transports exist only in outbound adapters. Source-block references establish provenance, not semantic fidelity.

Generation is queued and polled. Accepted segments/groups persist; a failed job can be retried explicitly from the first missing unit. A restart marks in-flight work failed rather than automatically spending again. The v1 worker supports one backend process. Provider failures require explicit user retry; completed checkpoints are reused. Usage and estimated costs remain explicitly incomplete when the provider does not report usage.

Completed chapter audio can be downloaded as M4A for copying to a phone or another device. This is the intended local-development workflow before Bookcast is hosted remotely.

The player supports play/pause, native seeking, ±15 seconds, speed and resume per generation in localStorage. Audio endpoints support byte ranges. The browser discloses AI-generated voices.

## Intentional limits

No auth, PDF parsing, whole-book prerequisite, glossary, semantic model judge, automatic dialogue retries, GPT-Live, automatic text repair, TTS comparison, tutoring, quizzes, bookmarks, mobile app or offline playback. UI import is pasted text; text-file upload can follow later. Private source/artifact backup and production hosting are outside this first slice.

See [architecture](docs/ARCHITECTURE.md), [product vision](docs/PRODUCT_VISION.md) and [ADRs](docs/adr/) for project context. Earlier spike outputs remain experimental evidence and are not production dependencies.
