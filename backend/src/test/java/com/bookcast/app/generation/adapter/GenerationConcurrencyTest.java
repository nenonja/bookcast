package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.application.GenerationStore;
import com.bookcast.app.generation.domain.Podcast.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(properties = {"bookcast.ai-mode=fake", "bookcast.worker-enabled=false"})
class GenerationConcurrencyTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired GenerationStore store;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;

  @AfterEach
  void stopFixtureJobsBeingClaimedByLaterTests() {
    // This container belongs only to this class; retain rows without leaving queue entries behind.
    db.update("update generations set status='failed' where status in ('queued','running')");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void competingCreatesReuseRequestOrActiveRevision(boolean sameRequest) throws Exception {
    UUID chapter = UUID.randomUUID(), revision = UUID.randomUUID();
    UUID request1 = UUID.randomUUID(), request2 = sameRequest ? request1 : UUID.randomUUID();
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Future<Job>> results = new ArrayList<>();
      for (UUID request : List.of(request1, request2)) results.add(executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return store.create(request, chapter, revision, "text", "audio");
      }));
      Job first = results.get(0).get(10, TimeUnit.SECONDS);
      Job second = results.get(1).get(10, TimeUnit.SECONDS);
      assertEquals(first, second);
      assertEquals(1, db.queryForObject(
          "select count(*) from generations where revision_id=?", Integer.class, revision));
      assertEquals(1, store.list(chapter).size());
    }
  }

  @Test
  void claimSkipsLockedOldestRowAndClaimsItAfterRelease() throws Exception {
    Job oldest = store.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "text", "audio");
    Job newer = store.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "text", "audio");
    db.update("update generations set created_at='2000-01-01T00:00:00Z' where id=?", oldest.id());
    db.update("update generations set created_at='2000-01-02T00:00:00Z' where id=?", newer.id());
    try (var executor = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactions).executeWithoutResult(tx -> {
        assertEquals(oldest.id(), db.queryForObject(
            "select id from generations where id=? for update", UUID.class, oldest.id()));
        Future<Optional<Job>> claim = executor.submit(store::claim);
        try {
          var claimed = claim.get(5, TimeUnit.SECONDS).orElseThrow();
          assertEquals(newer.id(), claimed.id());
          assertEquals(Status.running, claimed.status());
        } catch (Exception e) {
          throw new AssertionError("an unlocked queued job must be claimable without waiting for the oldest row", e);
        }
      });
    }
    assertEquals(oldest.id(), store.claim().orElseThrow().id());
    assertTrue(store.claim().isEmpty());
  }

  @Test
  void simultaneousClaimsReturnDistinctJobs() throws Exception {
    Set<UUID> queued = new HashSet<>();
    for (int i = 0; i < 4; i++) queued.add(store.create(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "text", "audio").id());
    var start = new CyclicBarrier(4);
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Future<Optional<Job>>> claims = new ArrayList<>();
      for (int i = 0; i < 4; i++) claims.add(executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return store.claim();
      }));
      Set<UUID> claimed = new HashSet<>();
      for (var claim : claims) assertTrue(claimed.add(claim.get(10, TimeUnit.SECONDS).orElseThrow().id()));
      assertEquals(queued, claimed);
      assertTrue(store.claim().isEmpty());
    }
  }
}
