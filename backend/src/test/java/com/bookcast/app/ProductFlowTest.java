package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bookcast.app.generation.adapter.FakeCapabilities;
import com.bookcast.app.generation.application.*;
import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.Library;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  @Autowired TextGeneration text;
  @Autowired ObjectMapper json;

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

  private UUID requestGeneration(UUID chapter, UUID revision, UUID request) throws Exception {
    var response = mvc.perform(post("/api/v1/chapters/" + chapter + "/generations")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("requestId", request, "sourceRevisionId", revision))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.sourceRevisionId").value(revision.toString()))
        .andReturn().getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
  }

  @Test
  void freshGenerationAfterCompletionPreservesOriginalAndKeepsIndependentHistory() throws Exception {
    var chapter = library.importChapter(library.createBook("Comparison fixture").id(), 6,
        "Fresh generation fixture", "A substantive source argument.\n\nAn important qualification.", "en");
    UUID originalRequest = UUID.randomUUID();
    UUID original = requestGeneration(chapter.id(), chapter.sourceRevisionId(), originalRequest);
    assertTrue(service.workOne());
    var originalJob = service.get(original);
    assertEquals(Status.succeeded, originalJob.status());
    var originalSegments = service.segments(original);
    var originalAudio = service.audio(original);
    var originalAttempts = service.attempts(original);
    Path originalFile = Path.of(service.audioPath(original));
    byte[] originalBytes = Files.readAllBytes(originalFile);
    var originalModified = Files.getLastModifiedTime(originalFile);

    UUID freshRequest = UUID.randomUUID();
    UUID fresh = requestGeneration(chapter.id(), chapter.sourceRevisionId(), freshRequest);
    assertNotEquals(original, fresh);
    assertEquals(Status.queued, service.get(fresh).status());
    assertEquals(text.profile().model(), service.get(fresh).generationModel());
    assertEquals(speech.profile().model(), service.get(fresh).audioModel());
    assertEquals(0, service.get(fresh).totalSegments());
    assertEquals(0, service.get(fresh).totalAudio());
    assertTrue(service.segments(fresh).isEmpty());
    assertTrue(service.audio(fresh).isEmpty());
    assertTrue(service.attempts(fresh).isEmpty());
    assertEquals(fresh, requestGeneration(chapter.id(), chapter.sourceRevisionId(), freshRequest));
    assertEquals(fresh, requestGeneration(chapter.id(), chapter.sourceRevisionId(), UUID.randomUUID()));
    assertEquals(original, requestGeneration(chapter.id(), chapter.sourceRevisionId(), originalRequest));
    mvc.perform(post("/api/v1/generations/" + original + "/retry"))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/v1/generations/" + original + "/audio/download"))
        .andExpect(status().isOk()).andExpect(content().bytes(originalBytes));

    assertTrue(service.workOne());
    assertFalse(service.workOne());
    var freshJob = service.get(fresh);
    assertEquals(Status.succeeded, freshJob.status());
    assertNotEquals(originalJob.audioPath(), freshJob.audioPath());
    assertTrue(freshJob.audioPath().startsWith(fresh + "/"));
    assertTrue(Files.isRegularFile(Path.of(service.audioPath(fresh))));
    assertFalse(service.segments(fresh).isEmpty());
    assertEquals(originalSegments.size(), service.segments(fresh).size());
    assertEquals(originalAudio.size(), service.audio(fresh).size());
    assertTrue(service.audio(fresh).stream().allMatch(receipt -> receipt.path().startsWith(fresh + "/")));
    assertEquals(originalAttempts.size(), service.attempts(fresh).size());
    var originalAttemptIds = originalAttempts.stream().map(Attempt::id).toList();
    assertTrue(service.attempts(fresh).stream().noneMatch(attempt -> originalAttemptIds.contains(attempt.id())));
    assertEquals(originalJob, service.get(original));
    assertEquals(originalSegments, service.segments(original));
    assertEquals(originalAudio, service.audio(original));
    assertEquals(new HashSet<>(originalAttempts), new HashSet<>(service.attempts(original)));
    assertArrayEquals(originalBytes, Files.readAllBytes(originalFile));
    assertEquals(originalModified, Files.getLastModifiedTime(originalFile));
    assertEquals(chapter, library.revision(chapter.id(), chapter.sourceRevisionId()));
    assertEquals(1, db.queryForObject("select count(*) from source_revisions where chapter_id=?",
        Integer.class, chapter.id()));
    assertEquals(Set.of(original, fresh), new HashSet<>(service.list(chapter.id()).stream().map(Job::id).toList()));
    mvc.perform(get("/api/v1/generations/" + original + "/audio"))
        .andExpect(status().isOk()).andExpect(content().bytes(originalBytes));
    mvc.perform(get("/api/v1/generations/" + original + "/audio/download"))
        .andExpect(status().isOk()).andExpect(content().bytes(originalBytes));
  }

  @Test
  void usableFlowImmutabilityIdempotencyAndCheckpointRetry() throws Exception {
    var chapter =
        library.importChapter(
            library.createBook("Test book").id(),
            1,
            "Test chapter",
            "First important argument.\n\nSecond qualification.",
            "en");
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
        .andExpect(jsonPath("$.totalEstimatedCost").value(0))
        .andExpect(jsonPath("$.costComplete").value(false));
    mvc.perform(get("/api/v1/generations/" + job.id() + "/audio").header("Range", "bytes=0-99"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Type", "audio/mp4"));
    byte[] finalBytes = Files.readAllBytes(Path.of(service.audioPath(job.id())));
    int callsBeforeDownload = speech.calls;
    var attemptsBeforeDownload = service.attempts(job.id());
    var downloaded =
        mvc.perform(get("/api/v1/generations/" + job.id() + "/audio/download"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "audio/mp4"))
            .andExpect(content().bytes(finalBytes))
            .andReturn();
    var disposition =
        org.springframework.http.ContentDisposition.parse(
            downloaded.getResponse().getHeader("Content-Disposition"));
    assertEquals("attachment", disposition.getType());
    assertTrue(disposition.getFilename().endsWith("Test chapter.m4a"));
    assertEquals(callsBeforeDownload, speech.calls);
    assertEquals(attemptsBeforeDownload, service.attempts(job.id()));
    var organizedBook = library.createBook("Organized book");
    library.moveChapter(original.id(), organizedBook.id(), 5);
    assertEquals(original.id(), service.get(job.id()).chapterId());
    assertEquals(original.sourceRevisionId(), service.get(job.id()).sourceRevisionId());
    mvc.perform(get("/api/v1/generations/" + job.id() + "/audio/download"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(finalBytes));
    mvc.perform(get("/api/v1/generations/" + job.id() + "/audio").header("Range", "bytes=0-99"))
        .andExpect(status().isPartialContent());
    assertEquals(callsBeforeDownload, speech.calls);
    mvc.perform(
            post("/api/v1/chapters")
                .contentType("application/json")
                .content("{\"title\":\"Empty\",\"text\":\"\"}"))
        .andExpect(status().isBadRequest());
    var interrupted = service.start(original.id(), original.sourceRevisionId(), UUID.randomUUID());
    store.segment(interrupted.id(), service.segments(job.id()).getFirst());
    store.audio(interrupted.id(), service.audio(job.id()).getFirst());
    assertEquals(interrupted.id(), store.claim().orElseThrow().id());
    store.attempt(
        interrupted.id(),
        new Attempt(
            UUID.randomUUID(),
            "audio",
            "group-1",
            "fake",
            "fake-speech",
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
    assertNotNull(service.attempts(interrupted.id()).getFirst().endedAt());
    assertTrue(service.attempts(interrupted.id()).getFirst().usageUnknown());
    assertEquals(acceptedHash, service.audio(interrupted.id()).getFirst().hash());
    int beforeResume = speech.calls;
    service.retry(interrupted.id());
    service.workOne();
    assertEquals(Status.succeeded, service.get(interrupted.id()).status());
    assertEquals(beforeResume + 1, speech.calls);
    assertEquals(acceptedHash, service.audio(interrupted.id()).getFirst().hash());
  }

  @Test
  void downloadReadinessMissingArtifactsAndSafeFinnishFilename() throws Exception {
    var chapter =
        library.importChapter(
            library.createBook("Älykäs kirja").id(),
            1,
            "Luku: käyttöoikeudet/ääni\r\n",
            "A useful source paragraph.",
            "en");
    var job = service.start(chapter.id(), chapter.sourceRevisionId(), UUID.randomUUID());
    String url = "/api/v1/generations/" + job.id() + "/audio/download";
    mvc.perform(get(url)).andExpect(status().isConflict());
    mvc.perform(get("/api/v1/generations/" + UUID.randomUUID() + "/audio/download"))
        .andExpect(status().isNotFound());
    Path file = data.resolve("download-fixture-" + job.id() + ".m4a");
    byte[] bytes = {1, 2, 3, 4};
    Files.write(file, bytes);
    store.state(job.id(), "succeeded", "complete", null, false, data.relativize(file).toString());
    int calls = speech.calls;
    var result =
        mvc.perform(get(url))
            .andExpect(status().isOk())
            .andExpect(content().bytes(bytes))
            .andReturn();
    String header = result.getResponse().getHeader("Content-Disposition");
    assertTrue(header.contains("filename*=UTF-8''"));
    assertEquals(
        "Älykäs kirja - Luku_ käyttöoikeudet_ääni.m4a",
        org.springframework.http.ContentDisposition.parse(header).getFilename());
    assertEquals(calls, speech.calls);
    assertTrue(service.attempts(job.id()).isEmpty());
    Files.delete(file);
    mvc.perform(get(url))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_FAILURE"))
        .andExpect(jsonPath("$.message").value("The operation failed."));
    assertEquals(calls, speech.calls);
    assertTrue(service.attempts(job.id()).isEmpty());
  }
}
