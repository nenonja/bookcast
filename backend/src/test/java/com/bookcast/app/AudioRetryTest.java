package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.*;
import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class AudioRetryTest {
  static class Fixture {
    final GenerationStore store = mock(GenerationStore.class);
    final Library library = mock(Library.class);
    final TextGeneration text = mock(TextGeneration.class);
    final SpeechRendering speech = mock(SpeechRendering.class);
    final AudioArtifacts artifacts = mock(AudioArtifacts.class);
    final AudioRetryPolicy.Timing timing = mock(AudioRetryPolicy.Timing.class);
    final List<AudioReceipt> receipts = new ArrayList<>();
    final Map<UUID, Attempt> attempts = new LinkedHashMap<>();
    final List<String> stages = new ArrayList<>();
    Job job;
    final GenerationService service;
    final List<Group> groups;

    Fixture() {
      UUID chapter = UUID.randomUUID(), revision = UUID.randomUUID(), block = UUID.randomUUID();
      job =
          new Job(
              UUID.randomUUID(),
              chapter,
              revision,
              Status.running,
              "audio",
              1,
              2,
              "text",
              "speech",
              null,
              false,
              null);
      var source =
          new ChapterSource(
              chapter,
              UUID.randomUUID(),
              "Book",
              "Chapter",
              revision,
              "en",
              "hash",
              OffsetDateTime.now(),
              List.of(new ChapterSource.Block(block, 0, "Source.", null)));
      var segment =
          new Segment(
              0,
              List.of(block),
              List.of(new Turn(Speaker.HOST, "Miksi?"), new Turn(Speaker.EXPERT, "Siksi.")));
      groups = AudioGroups.plan(List.of(segment));
      receipts.add(receipt(groups.getFirst()));
      when(library.revision(chapter, revision)).thenReturn(source);
      when(text.profile()).thenReturn(new Profile("fake", "text"));
      when(speech.profile()).thenReturn(new Profile("fake", "speech"));
      when(store.get(any())).thenAnswer(i -> job);
      when(store.claim()).thenAnswer(i -> Optional.of(job));
      when(store.segments(any())).thenReturn(List.of(segment));
      when(store.audio(any())).thenAnswer(i -> List.copyOf(receipts));
      when(store.attempts(any())).thenAnswer(i -> List.copyOf(attempts.values()));
      doAnswer(
              i -> {
                var a = (Attempt) i.getArgument(1);
                attempts.put(a.id(), a);
                return null;
              })
          .when(store)
          .attempt(any(), any());
      doAnswer(
              i -> {
                receipts.add(i.getArgument(1));
                return null;
              })
          .when(store)
          .audio(any(), any());
      doAnswer(
              i -> {
                stages.add(i.getArgument(2));
                job =
                    new Job(
                        job.id(),
                        job.chapterId(),
                        job.sourceRevisionId(),
                        Status.valueOf(i.getArgument(1)),
                        i.getArgument(2),
                        1,
                        2,
                        "text",
                        "speech",
                        i.getArgument(3),
                        i.getArgument(4),
                        i.getArgument(5));
                return null;
              })
          .when(store)
          .state(any(), any(), any(), any(), anyBoolean(), any());
      when(artifacts.preserve(any(), any(), any(), any())).thenReturn("raw");
      when(artifacts.validate(any(), any(), any())).thenAnswer(i -> receipt(i.getArgument(0)));
      when(artifacts.assemble(any(), any())).thenReturn("final");
      service =
          new GenerationService(
              library,
              store,
              text,
              speech,
              artifacts,
              800,
              new AudioRetryPolicy(timing, 2000, 8000, 60000, .2));
    }

    static AudioReceipt receipt(Group group) {
      return new AudioReceipt(group, "raw", "hash", group.text(), false, 1, 0, 0, 60);
    }

    void results(Metadata... metadata) {
      var queue = new ArrayDeque<>(Arrays.asList(metadata));
      doAnswer(i -> new AudioResult(new byte[] {1, 2}, "Siksi.", queue.removeFirst()))
          .when(speech)
          .render(any(), any(), any());
    }

    List<Attempt> saved() {
      return List.copyOf(attempts.values());
    }

    void calls(int count) {
      verify(speech, times(count)).render(eq(groups.get(1)), eq(job.id()), any());
      verify(text, never()).generate(any());
    }
  }

  static Metadata timeout() {
    return Metadata.error("PROVIDER_TIMEOUT", true);
  }

  static Metadata success() {
    return new Metadata(Map.of("outputTokens", 10L), .01, null, false);
  }

  @Test
  void timeoutThenSuccessPreservesBothAttemptsAndOneCheckpoint() throws Exception {
    var f = new Fixture();
    f.results(timeout(), success());
    f.service.workOne();
    f.calls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(List.of("failed", "succeeded"), f.saved().stream().map(Attempt::outcome).toList());
    assertEquals(List.of(1, 2), f.saved().stream().map(Attempt::attemptNumber).toList());
    verify(f.store, times(1)).audio(any(), any());
    verify(f.timing).pause(2000);
    assertTrue(f.stages.contains("audio_retry:2:2"));
    assertTrue(f.saved().stream().allMatch(a -> a.startedAt() != null && a.endedAt() != null));
    assertTrue(f.saved().getFirst().usageUnknown());
    assertNull(f.saved().getFirst().estimatedCost());
    assertEquals(.01, f.saved().getLast().estimatedCost());
  }

  @Test
  void twoTimeoutsThenSuccess() throws Exception {
    var f = new Fixture();
    f.results(timeout(), timeout(), success());
    f.service.workOne();
    f.calls(3);
    assertEquals(Status.succeeded, f.job.status());
    verify(f.timing).pause(2000);
    verify(f.timing).pause(8000);
  }

  @Test
  void threeFailuresExhaustLimit() throws Exception {
    var f = new Fixture();
    f.results(timeout(), timeout(), timeout());
    f.service.workOne();
    f.calls(3);
    assertEquals(Status.failed, f.job.status());
    assertEquals(1, f.receipts.size());
    assertNull(f.saved().getLast().nextRetryDelayMs());
    verify(f.timing, times(2)).pause(anyLong());
  }

  @Test
  void nonTransientConfigurationFailureDoesNotRetry() throws Exception {
    var f = new Fixture();
    f.results(
        new Metadata(Map.of(), null, "PROVIDER_HTTP_400", false, 400, "invalid_request", null));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
    verify(f.timing, never()).pause(anyLong());
  }

  @Test
  void retryAfterAndFailedUsageArePreserved() throws Exception {
    var f = new Fixture();
    f.results(
        new Metadata(
            Map.of("inputTokens", 5L),
            .005,
            "PROVIDER_HTTP_429",
            true,
            429,
            "rate_limit_exceeded",
            12000L),
        success());
    f.service.workOne();
    f.calls(2);
    verify(f.timing).pause(12000);
    assertEquals(12000L, f.saved().getFirst().nextRetryDelayMs());
    assertEquals(429, f.saved().getFirst().providerStatus());
    assertEquals(.015, f.saved().stream().mapToDouble(Attempt::estimatedCost).sum(), 1e-9);
  }

  @Test
  void completedGroupsNeverCallProvider() {
    var f = new Fixture();
    f.receipts.add(Fixture.receipt(f.groups.get(1)));
    f.service.workOne();
    verify(f.speech, never()).render(any(), any(), any());
    verify(f.text, never()).generate(any());
    assertEquals(Status.succeeded, f.job.status());
  }

  @Test
  void interruptedBackoffRequiresExplicitResumeAndKeepsEarlierCheckpoints() throws Exception {
    var f = new Fixture();
    f.results(timeout());
    doThrow(new InterruptedException()).when(f.timing).pause(anyLong());
    f.service.workOne();
    Thread.interrupted();
    assertEquals(Status.failed, f.job.status());
    assertEquals("PROCESS_INTERRUPTED", f.job.errorCode());
    assertEquals(1, f.receipts.size());
    f.calls(1);
    reset(f.timing);
    f.service.retry(f.job.id());
    f.results(success());
    f.service.workOne();
    f.calls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(2, f.receipts.size());
    verify(f.speech, never()).render(eq(f.groups.getFirst()), any(), any());
  }

  @Test
  void failedArtifactPersistenceStillRecordsProviderUsageWithoutRetry() throws Exception {
    var f = new Fixture();
    f.results(success());
    when(f.artifacts.preserve(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("ARTIFACT_WRITE_FAILURE"));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
    assertEquals("AUDIO_ARTIFACT_WRITE_FAILURE", f.saved().getFirst().errorCode());
    assertEquals(.01, f.saved().getFirst().estimatedCost());
    assertNotNull(f.saved().getFirst().endedAt());
    verify(f.timing, never()).pause(anyLong());
  }

  @Test
  void jitterAndRetryAfterAreBoundedAndClassificationIsExplicit() {
    var timing = mock(AudioRetryPolicy.Timing.class);
    when(timing.jitter()).thenReturn(1.0);
    var policy = new AudioRetryPolicy(timing, 2000, 8000, 60000, .2);
    assertEquals(2400, policy.delay(1, timeout()));
    assertEquals(9600, policy.delay(2, timeout()));
    assertEquals(
        60000,
        policy.delay(
            1, new Metadata(Map.of(), null, "PROVIDER_HTTP_429", true, 429, null, 999999L)));
    for (int status : List.of(400, 401, 403, 404, 422, 501))
      assertFalse(
          AudioRetryPolicy.transientFailure(
              new Metadata(Map.of(), null, "PROVIDER_HTTP_" + status, true, status, null, null)));
    assertFalse(AudioRetryPolicy.transientFailure(Metadata.error("PROVIDER_QUOTA", false)));
    assertFalse(
        AudioRetryPolicy.transientFailure(Metadata.error("UNEXPECTED_AUDIO_STRUCTURE", true)));
  }
}
