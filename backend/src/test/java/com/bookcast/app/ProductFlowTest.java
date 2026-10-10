package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bookcast.app.generation.adapter.FakeCapabilities;
import com.bookcast.app.generation.application.*;
import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.Library;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {"bookcast.ai-mode=fake", "bookcast.worker-enabled=false"})
class ProductFlowTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  static final Path data;

  static {
    try {
      data = Files.createTempDirectory("bookcast-tests-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("bookcast.data-dir", data::toString);
  }

  @Autowired Library library;
  @Autowired GenerationService service;
  @Autowired GenerationStore store;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired FlakySpeech speech;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    FlakySpeech controlledSpeech() {
      return new FlakySpeech();
    }
  }

  static class FlakySpeech implements SpeechRendering {
    int calls;
    boolean fail;
    final SpeechRendering delegate = new FakeCapabilities().fakeSpeech();

    public Profile profile() {
      return delegate.profile();
    }

    public AudioResult render(Group g, UUID job, UUID attempt) {
      calls++;
      if (fail && g.order() == 1) {
        fail = false;
        return new AudioResult(new byte[0], "", Metadata.error("TEST_PROVIDER_FAILURE", true));
      }
      return delegate.render(g, job, attempt);
    }
  }

  @Test
  void usableFlowImmutabilityIdempotencyAndCheckpointRetry() throws Exception {
    var chapter =
        library.importChapter(
            null, null, "Test chapter", "First important argument.\n\nSecond qualification.", "en");
    var original = chapter;
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update source_revisions set content_hash='changed' where id=?",
                chapter.sourceRevisionId()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update("delete from source_blocks where revision_id=?", chapter.sourceRevisionId()));
    var newer = library.importRevision(chapter.id(), "Revised source.", "en");
    assertNotEquals(original.sourceRevisionId(), newer.sourceRevisionId());
    assertEquals(original, library.revision(original.id(), original.sourceRevisionId()));
    UUID request = UUID.randomUUID();
    var job = service.start(original.id(), original.sourceRevisionId(), request);
    assertEquals(Status.queued, job.status());
    assertEquals(job.id(), service.start(original.id(), original.sourceRevisionId(), request).id());
    assertEquals(
        job.id(),
        service.start(original.id(), original.sourceRevisionId(), UUID.randomUUID()).id());
    speech.fail = true;
    service.workOne();
    assertEquals(Status.failed, service.get(job.id()).status());
    assertEquals(1, service.audio(job.id()).size());
    String acceptedHash = service.audio(job.id()).getFirst().hash();
    int before = speech.calls;
    service.retry(job.id());
    service.workOne();
    assertEquals(Status.succeeded, service.get(job.id()).status());
    assertEquals(before + 1, speech.calls);
    assertEquals(acceptedHash, service.audio(job.id()).getFirst().hash());
    assertEquals(1, service.segments(job.id()).size());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update script_segments set payload='{}'::jsonb where generation_id=?", job.id()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from audio_groups where generation_id=?", job.id()));
    assertThrows(
        IllegalStateException.class,
        () -> service.start(newer.id(), newer.sourceRevisionId(), request));

    assertEquals(
        3, service.attempts(job.id()).stream().filter(a -> a.capability().equals("audio")).count());
    assertThrows(IllegalStateException.class, () -> service.retry(job.id()));
    mvc.perform(get("/api/v1/generations/" + job.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("succeeded"))
        .andExpect(jsonPath("$.sourceRevisionId").value(original.sourceRevisionId().toString()))
        .andExpect(jsonPath("$.audioUrl").exists())
        .andExpect(jsonPath("$.totalEstimatedCost").value(0));
    mvc.perform(get("/api/v1/generations/" + job.id() + "/audio").header("Range", "bytes=0-99"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Type", "audio/mp4"));
    mvc.perform(
            post("/api/v1/chapters")
                .contentType("application/json")
                .content("{\"title\":\"Empty\",\"text\":\"\"}"))
        .andExpect(status().isBadRequest());
    var interrupted = service.start(newer.id(), newer.sourceRevisionId(), UUID.randomUUID());
    assertEquals(interrupted.id(), store.claim().orElseThrow().id());
    store.attempt(
        interrupted.id(),
        new Attempt(
            UUID.randomUUID(),
            "dialogue",
            "segment-0",
            "fake",
            "fake-dialogue",
            "attempting",
            0,
            Map.of(),
            null,
            false,
            null));
    service.interrupted();
    assertEquals(Status.failed, service.get(interrupted.id()).status());
    assertEquals("PROCESS_INTERRUPTED", service.get(interrupted.id()).errorCode());
    assertTrue(service.get(interrupted.id()).retryable());
    assertEquals("interrupted", service.attempts(interrupted.id()).getFirst().outcome());
    assertTrue(service.attempts(interrupted.id()).getFirst().retryable());
  }
}
