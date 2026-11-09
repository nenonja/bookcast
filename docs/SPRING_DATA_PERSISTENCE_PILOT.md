# Spring Data persistence assessment and pilot

Reviewed 2026-11-09. This records a limited implementation pilot for human review, not a new accepted ADR or a repository-wide migration plan.

## Recommendation

Use Spring Data JDBC selectively for ordinary single-table lookups. Keep JdbcTemplate for commands whose SQL expresses an invariant, PostgreSQL JSONB behavior, and the existing library snapshot queries. Do not add JPA for the current use cases.

The useful pilot is the generation job's four scalar lookups. The library has simple Book lookups, but its other reads combine Book/Chapter metadata, the latest immutable revision and ordered blocks. Introducing ORM entities just for Book retrieval would leave the difficult queries in place while adding another persistence model and runtime.

## Current operation inventory

| Area / operation | Classification | Treatment |
| --- | --- | --- |
| Job by ID, request ID, chapter ID, active revision/status | Ordinary single-table reads | Derived repository methods in this pilot |
| Generation insert with `ON CONFLICT DO NOTHING`, followed by conflict resolution | Atomic/idempotent creation | Keep insert SQL and existing transaction; repository lookups participate in that transaction |
| Queue claim: `UPDATE ... RETURNING` with `FOR UPDATE SKIP LOCKED` | Atomic concurrency-sensitive command | Keep exactly the existing SQL; never replace with find-then-save |
| State/totals updates and conditional manual retry | Explicit commands | Keep SQL; retry requires failed status and remains protected by the active-revision index |
| Startup recovery | Bulk job update plus JSONB payload update | Keep both statements; retain usage/cost and mark uncertain calls interrupted without queuing work |
| Accepted segment/audio reads | Ordered JSONB snapshot reads | Keep SQL casts/order and existing Jackson decoding |
| Accepted segment/audio inserts | Insert-only immutable checkpoints | Keep inserts, composite keys, foreign keys and database triggers |
| Attempt insert/finalization | PostgreSQL JSONB upsert | Keep full-payload replacement by attempt ID; separate IDs continue to represent separate calls |
| Book creation and lookup | Simple CRUD/read candidates | A future small repository could cover these; not enough benefit alone to justify JPA now |
| Book listing | Explicit expression-based ordering | Preserve `lower(title), id`; plain title ordering is not an equivalent replacement |
| Chapter-number availability | Existence lookup with optional excluded ID | Could become two derived exists queries, but the uniqueness constraint and conflict translation must remain |
| Chapter insert/move | Commands constrained by ownership, numbering and uniqueness | Keep SQL and application transactions in this pilot |
| Latest/specific source revision and block retrieval | Joined snapshot read plus ordered children | Keep explicit SQL; the latest-revision tie-breaker and optional revision filter matter |
| Chapter listing | Optional Book filter, nullable chapter order, then snapshot reads | Keep SQL; current loading performs one ID query plus two queries per Chapter |
| Source revision/block append | Immutable inserts in the application transaction | Keep insert behavior and immutability triggers; never model this as replacing a mutable source graph |

Startup recovery currently uses two separate updates without a transaction declared on that store method. The pilot preserves this existing boundary; it does not claim to make recovery atomic. Queue ordering also remains unchanged, including its previously unspecified ordering of equal creation timestamps.

State/totals updates are structurally simple, but replacing partial-column commands with aggregate save would write a broader snapshot. Moving them into modifying repository queries would merely relocate the existing SQL. Neither change is needed for this read pilot.

## Alternatives

| Concern | JdbcTemplate | Spring Data JDBC | Spring Data JPA |
| --- | --- | --- | --- |
| Ordinary reads | Explicit SQL and row mapping | Derived methods and record mapping | Derived methods over JPA entities |
| PostgreSQL commands | Direct fit | Continue using JdbcTemplate beside repositories | Still need native SQL/JDBC for these commands |
| Object state | Explicit snapshots | Eager snapshots; no dirty tracking or entity cache | Persistence context and entity identity/lifecycle |
| Immutable children | Current inserts/triggers | Safe here because no children or save/delete methods are mapped | Requires deliberate cascade/write restrictions |
| Transactions | Existing JDBC manager | Same JDBC manager/DataSource, verified by tests | JpaTransactionManager can share a DataSource with JDBC, but requires JPA/provider configuration |
| Added cost | Existing code | Starter, repository factory and two adapter types | Provider, entity mappings, EntityManager configuration and SQL/entity-state coordination |

Spring Data JDBC's [documented state model](https://docs.spring.io/spring-data/relational/reference/3.5/jdbc/why.html) suits these snapshot reads. Its aggregate save behavior can [delete and reinsert referenced children](https://docs.spring.io/spring-data/relational/reference/3.5/jdbc/entity-persistence.html), so the pilot deliberately exposes no save/delete operations and maps no checkpoint/source children.

JPA is not inherently incompatible with JdbcTemplate: [JpaTransactionManager supports shared-DataSource JDBC access](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html). However, introducing persistence-context state would require deliberate flush/refresh boundaries when raw SQL changes loaded entities. That cost is not justified by the current scalar read opportunity. A JPA pilot was not implemented or benchmarked.

## Implemented pilot

- Replaced the JDBC starter with Boot's Data JDBC starter, which retains JdbcTemplate transitively. Boot 3.5.16 manages Spring Data JDBC 3.5.13; no JPA/Hibernate dependency was added.
- Added adapter-owned `GenerationRow`, with explicit conversion to the existing `Podcast.Job`. UUIDs, status, nullable error/audio fields and creation timestamps use framework/driver mapping. No custom converters were needed.
- Added restricted `GenerationReadRepository extends Repository`, with only `findById`, `findByRequestId`, `findByRevisionIdAndStatusIn`, and `findByChapterIdOrderByCreatedAtDescIdDesc`.
- Kept application persistence ports unchanged. Spring Data annotations/interfaces remain inside the generation adapter.
- Kept SQL commands intact. Claim-returned rows use Spring Framework's existing `DataClassRowMapper` and the same `GenerationRow.toJob()` conversion as repository reads.
- Used read-only repository transactions with normal REQUIRED propagation. They join the store's existing read/write creation transaction; direct reads end their transaction before returning immutable row values. No custom transaction-manager bean was introduced.
- Retained Flyway as schema authority. No migration, constraint, index, trigger, DataSource configuration or schema-generation setting changed.

One intentional ordering refinement: chapter job lists still sort by creation time descending, with descending UUID as a deterministic tie-breaker. The original list query did not specify timestamp-tie order. Queue claiming does not use this new tie-breaker and its SQL is unchanged.

## Complexity result and limits

The store has **13 explicit SQL strings instead of 17**, and no handwritten ResultSet field mapper. The remaining 13 SQL strings are unchanged. Database round-trip counts are not reduced.

Across JdbcGenerationStore and the two new production types, Java source grows from **259 to 310 lines**, including documentation: **51 additional lines**. The gain is readable, typed ordinary lookups and shared explicit domain conversion. The cost is an additional framework and persistence row definition. This is a local readability improvement, not a reduction in total source size or runtime concepts.

Keep this a small pilot. There is no benefit in converting every existing operation merely to make the store uniform. If the project's priority is the smallest dependency footprint, retaining JdbcTemplate is still reasonable; framework record row mapping is available without Spring Data. The library's existing per-Chapter loading pattern remains a separate potential performance review, not something this pilot fixes.

## Validation

- Before the pilot: four real PostgreSQL concurrency cases passed against the original store (same-request idempotency, active-revision deduplication, locked-row skipping and distinct concurrent claims).
- After the pilot: those same cases pass unchanged.
- Nine new PostgreSQL persistence cases cover all status values, UUID and timestamp mapping, nullable fields, deterministic listing, one shared JDBC transaction manager, read-your-own-writes and rollback, idempotent models/identity, manual retry/index protection, JSONB upserts, partial/zero/unknown accounting, recovery and immutable checkpoint/source triggers.
- Constraint-failure assertions use JDBC savepoints so one rejected statement cannot make later trigger checks pass merely because the transaction is already aborted.
- The full `mvn -o -f backend/pom.xml test` run passed **301 tests, zero failures/errors/skips**, including Spring Modulith/ArchUnit, populated V2-to-V3 migration, library, generation, provider fixtures and artifact tests.
- ArchUnit now also rejects Spring Data, JPA and Hibernate dependencies outside adapter packages, protecting domain/application models and public module snapshots.
- The strengthened architecture checks passed again independently. Javadoc validation passed for the changed production types, and `git diff --check` passed.
- Preservation checks confirmed all pre-existing working-tree changes remain intact and all 6,945 saved artifact paths retain their original sizes and modification times.

All test jobs live in dedicated Testcontainers databases with fake providers and the worker disabled. No existing generation was resumed, and no paid provider call or historical artifact modification is part of this pilot.
