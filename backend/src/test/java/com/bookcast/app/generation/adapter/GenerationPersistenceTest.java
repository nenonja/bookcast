package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.application.GenerationStore;
import com.bookcast.app.generation.domain.AudioGroups;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.Library;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@Transactional
@SpringBootTest(properties = {"bookcast.ai-mode=fake", "bookcast.worker-enabled=false"})
class GenerationPersistenceTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired GenerationStore store;
  @Autowired GenerationReadRepository reads;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DataSource dataSource;
  @Autowired ApplicationContext context;
  @Autowired Library library;
  @Autowired ObjectMapper json;

  Job create() {
    return store.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "text", "audio");
  }

  Segment segment() {
    return new Segment(0, List.of(UUID.randomUUID()),
        List.of(new Turn(Speaker.HOST, "Miksi?"), new Turn(Speaker.EXPERT, "Siksi.")));
  }

  AudioReceipt receipt(Segment segment) {
    Group group = AudioGroups.plan(List.of(segment)).getFirst();
    return new AudioReceipt(group, "fixture/raw.pcm", "accepted-hash", group.text(), false, 1, 0, 0, 60);
  }

  void rejects(Runnable operation) {
    var nested = new TransactionTemplate(transactions);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    assertThrows(DataAccessException.class, () -> nested.executeWithoutResult(tx -> operation.run()));
  }

  @Test
  void jdbcRowsAndRepositoryReadsAgreeOnUuidsEnumsNullsAndTimestamps() {
    UUID chapter = UUID.randomUUID();
    OffsetDateTime created = OffsetDateTime.parse("2026-10-01T12:34:56.123456+03:00");
    for (Status status : Status.values()) {
      UUID id = UUID.randomUUID(), request = UUID.randomUUID(), revision = UUID.randomUUID();
      String error = status == Status.failed ? "PROVIDER_TIMEOUT" : null;
      String path = status == Status.succeeded ? "fixture/final.m4a" : null;
      boolean retryable = status == Status.failed;
      db.update("""
          insert into generations(id,request_id,chapter_id,revision_id,status,stage,total_segments,
              total_audio,generation_model,audio_model,error_code,retryable,audio_path,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, id, request, chapter, revision, status.name(), "fixture", 3, 5,
          "text-override", "audio-override", error, retryable, path, created);
      var expected = new Job(id, chapter, revision, status, "fixture", 3, 5,
          "text-override", "audio-override", error, retryable, path);
      assertEquals(expected, store.get(id));
      var row = reads.findByRequestId(request).orElseThrow();
      assertEquals(request, row.requestId());
      assertEquals(created.toInstant(), row.createdAt().toInstant());
      assertEquals(ZoneOffset.UTC, row.createdAt().getOffset());
      assertEquals(expected, row.toJob());
    }
    assertEquals(4, store.list(chapter).size());
    assertThrows(NoSuchElementException.class, () -> store.get(UUID.randomUUID()));
    assertTrue(store.list(UUID.randomUUID()).isEmpty());
  }

  @Test
  void chapterListingKeepsNewestFirstAndHasAStableIdTieBreaker() {
    UUID chapter = UUID.randomUUID();
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var time = OffsetDateTime.parse("2026-10-01T12:00:00Z");
    Job older = create();
    db.update("update generations set chapter_id=?,created_at=? where id=?", chapter, time.minusDays(1), older.id());
    for (UUID id : List.of(low, high))
      db.update("""
          insert into generations(id,request_id,chapter_id,revision_id,status,stage,
              generation_model,audio_model,created_at) values (?,?,?,?,'succeeded','complete','text','audio',?)
          """, id, UUID.randomUUID(), chapter, UUID.randomUUID(), time);
    for (int i = 0; i < 3; i++)
      assertEquals(List.of(high, low, older.id()), store.list(chapter).stream().map(Job::id).toList());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void oneJdbcTransactionManagerSharesUncommittedWritesAndRollsBackBothPaths() {
    assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
    var manager = assertInstanceOf(DataSourceTransactionManager.class, transactions);
    assertSame(dataSource, manager.getDataSource());
    assertFalse(reads instanceof CrudRepository);
    assertEquals(Set.of("findById", "findByRequestId", "findByRevisionIdAndStatusIn",
        "findByChapterIdOrderByCreatedAtDescIdDesc"),
        Arrays.stream(GenerationReadRepository.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));
    UUID request = UUID.randomUUID();
    UUID[] id = new UUID[1];
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      assertNotNull(TransactionSynchronizationManager.getResource(dataSource));
      var job = store.create(request, UUID.randomUUID(), UUID.randomUUID(), "text", "audio");
      id[0] = job.id();
      assertEquals(job, reads.findById(job.id()).orElseThrow().toJob());
      store.state(job.id(), "failed", "audio", "PROVIDER_TIMEOUT", true, null);
      assertEquals("PROVIDER_TIMEOUT", reads.findById(job.id()).orElseThrow().errorCode());
      tx.setRollbackOnly();
    });
    assertTrue(reads.findByRequestId(request).isEmpty());
    assertEquals(0, db.queryForObject("select count(*) from generations where id=?", Integer.class, id[0]));
  }

  @Test
  void idempotentCreationRetainsOriginalIdentityAndModels() {
    UUID request = UUID.randomUUID(), chapter = UUID.randomUUID(), revision = UUID.randomUUID();
    var original = store.create(request, chapter, revision, "original-text", "original-audio");
    assertEquals(original, store.create(request, chapter, revision, "new-text", "new-audio"));
    assertEquals(original, store.create(UUID.randomUUID(), chapter, revision, "new-text", "new-audio"));
    assertEquals(1, db.queryForObject("select count(*) from generations where revision_id=?", Integer.class, revision));
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(IllegalStateException.class,
        () -> store.create(request, UUID.randomUUID(), revision, "text", "audio")).getMessage());
  }

  @Test
  void manualRetryKeepsCheckpointsAttemptsAndAdvisoryRetryability() {
    var job = create();
    var segment = segment();
    var receipt = receipt(segment);
    var attempt = new Attempt(UUID.randomUUID(), "audio", "group-0", "fixture", "audio",
        "failed", 10, Map.of("inputTokens", 10L), .005, false, "PROVIDER_QUOTA");
    store.segment(job.id(), segment);
    store.audio(job.id(), receipt);
    store.attempt(job.id(), attempt);
    store.totals(job.id(), 3, 5);
    store.state(job.id(), "failed", "audio", "PROVIDER_QUOTA", false, "fixture/prior.m4a");
    store.retry(job.id());
    var queued = store.get(job.id());
    assertEquals(Status.queued, queued.status());
    assertEquals("queued", queued.stage());
    assertNull(queued.errorCode());
    assertFalse(queued.retryable());
    assertEquals("fixture/prior.m4a", queued.audioPath());
    assertEquals(3, queued.totalSegments());
    assertEquals(5, queued.totalAudio());
    assertEquals(List.of(segment), store.segments(job.id()));
    assertEquals(List.of(receipt), store.audio(job.id()));
    assertEquals(List.of(attempt), store.attempts(job.id()));
    assertEquals("GENERATION_NOT_FAILED", assertThrows(IllegalStateException.class,
        () -> store.retry(job.id())).getMessage());
  }

  @Test
  void activeRevisionConstraintAlsoProtectsManualRetry() {
    var failed = create();
    store.state(failed.id(), "failed", "audio", "PROVIDER_TIMEOUT", true, null);
    var active = store.create(UUID.randomUUID(), failed.chapterId(), failed.sourceRevisionId(), "text", "audio");
    rejects(() -> store.retry(failed.id()));
    assertEquals(Status.failed, store.get(failed.id()).status());
    assertEquals(Status.queued, store.get(active.id()).status());
  }

  @Test
  void jsonbUpsertsRetainPartialUsageUnknownCostAndActualZero() throws Exception {
    var job = create();
    UUID id = UUID.randomUUID();
    var pending = new Attempt(id, "audio", "group-0", "fixture", "audio", "attempting",
        0, Map.of("inputTokens", 10L), null, false, null);
    store.attempt(job.id(), pending);
    assertEquals(List.of(pending), store.attempts(job.id()));
    var complete = new Attempt(id, "audio", "group-0", "fixture", "audio", "failed",
        17, Map.of("inputTokens", 10L, "outputTokens", 0L), 0.0, false, "PROVIDER_QUOTA");
    store.attempt(job.id(), complete);
    assertEquals(List.of(complete), store.attempts(job.id()));
    assertEquals(1, db.queryForObject("select count(*) from generation_attempts where generation_id=?", Integer.class, job.id()));
    assertEquals("jsonb", db.queryForObject("select pg_typeof(payload)::text from generation_attempts where id=?", String.class, id));
    var raw = json.readTree(db.queryForObject("select payload::text from generation_attempts where id=?", String.class, id));
    assertEquals(0.0, raw.path("estimatedCost").doubleValue());
    assertEquals(0, raw.path("usage").path("outputTokens").longValue());
    var unknown = new Attempt(UUID.randomUUID(), "audio", "group-1", "fixture", "audio", "failed",
        3, Map.of("inputTokens", 5L), null, true, "PROVIDER_TIMEOUT");
    store.attempt(job.id(), unknown);
    var savedUnknown = store.attempts(job.id()).stream()
        .filter(a -> a.id().equals(unknown.id())).findFirst().orElseThrow();
    assertEquals(unknown, savedUnknown);
    assertNull(savedUnknown.estimatedCost());
    assertFalse(savedUnknown.usage().containsKey("outputTokens"));
  }

  @Test
  void startupRecoveryPreservesPaidEvidenceAndAcceptedWorkWithoutQueuing() {
    var job = create();
    assertEquals(job.id(), store.claim().orElseThrow().id());
    var segment = segment();
    var receipt = receipt(segment);
    store.segment(job.id(), segment);
    store.audio(job.id(), receipt);
    var pending = new Attempt(UUID.randomUUID(), "audio", "group-1", "fixture", "audio", "attempting",
        1, Map.of("inputTokens", 11L), .005, false, null);
    store.attempt(job.id(), pending);
    assertEquals(Status.running, reads.findById(job.id()).orElseThrow().status());
    store.interrupted();
    var recovered = store.get(job.id());
    assertEquals(Status.failed, recovered.status());
    assertEquals("PROCESS_INTERRUPTED", recovered.errorCode());
    assertTrue(recovered.retryable());
    assertTrue(store.claim().isEmpty());
    var attempt = store.attempts(job.id()).getFirst();
    assertEquals("interrupted", attempt.outcome());
    assertEquals(pending.usage(), attempt.usage());
    assertEquals(pending.estimatedCost(), attempt.estimatedCost());
    assertTrue(attempt.usageUnknown());
    assertNotNull(attempt.endedAt());
    assertEquals(List.of(segment), store.segments(job.id()));
    assertEquals(List.of(receipt), store.audio(job.id()));
  }

  @Test
  void acceptedCheckpointAndSourceTriggersRemainInForce() {
    var job = create();
    var segment = segment();
    var receipt = receipt(segment);
    store.segment(job.id(), segment);
    store.audio(job.id(), receipt);
    rejects(() -> store.segment(job.id(), segment));
    rejects(() -> store.audio(job.id(), receipt));
    for (String table : List.of("script_segments", "audio_groups")) {
      rejects(() -> db.update("update " + table + " set payload='{}'::jsonb where generation_id=?", job.id()));
      rejects(() -> db.update("delete from " + table + " where generation_id=?", job.id()));
    }
    var source = library.importChapter(library.createBook("Fixture book").id(), 1,
        "Fixture chapter", "Source text.", "en");
    rejects(() -> db.update("update source_revisions set content_hash='changed' where id=?", source.sourceRevisionId()));
    rejects(() -> db.update("delete from source_revisions where id=?", source.sourceRevisionId()));
    rejects(() -> db.update("update source_blocks set content='changed' where revision_id=?", source.sourceRevisionId()));
    rejects(() -> db.update("delete from source_blocks where revision_id=?", source.sourceRevisionId()));
    assertEquals(source, library.revision(source.id(), source.sourceRevisionId()));
    assertEquals(List.of(segment), store.segments(job.id()));
    assertEquals(List.of(receipt), store.audio(job.id()));
  }
}
