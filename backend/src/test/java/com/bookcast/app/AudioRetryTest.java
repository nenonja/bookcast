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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioRetryTest {
  static class Fixture {
    final GenerationStore store = mock(GenerationStore.class);
    final Library library = mock(Library.class);
    final TextGeneration text = mock(TextGeneration.class);
    final SpeechRendering speech = mock(SpeechRendering.class);
    final AudioArtifacts artifacts = mock(AudioArtifacts.class);
    boolean interruptBeforeRetry;
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
                if (interruptBeforeRetry && a.nextRetryDelayMs() != null)
                  Thread.currentThread().interrupt();
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
      when(artifacts.assemble(any(), any(), any())).thenReturn("final");
      service =
          new GenerationService(
              library,
              store,
              text,
              speech,
              artifacts,
              800,
              new AudioRetryPolicy(attempt -> 0L));
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
  void startPinsSpeechSettingsBeforeWorkerOrProviderWork() {
    var f = new Fixture();
    UUID request = UUID.randomUUID();
    when(f.store.create(request, f.job.chapterId(), f.job.sourceRevisionId(), "text", "speech"))
        .thenReturn(f.job);
    assertEquals(f.job, f.service.start(f.job.chapterId(), f.job.sourceRevisionId(), request));
    var order = inOrder(f.store, f.speech);
    order.verify(f.store).create(request, f.job.chapterId(), f.job.sourceRevisionId(), "text", "speech");
    order.verify(f.speech).prepareGeneration(f.job.id());
    f.calls(0);
    assertTrue(f.saved().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPEECH_CONFIGURATION_CHANGED", "SPEECH_CONFIGURATION_MISMATCH", "SPEECH_CONFIGURATION_UNVERIFIED"})
  void incompatibleResumeStopsBeforeProviderWorkAndPreservesAcceptedCheckpoints(String error) {
    var f = new Fixture();
    var accepted = List.copyOf(f.receipts);
    doThrow(new IllegalStateException(error)).when(f.speech).prepareGeneration(f.job.id());
    f.service.workOne();
    assertEquals(Status.failed, f.job.status());
    assertEquals(error, f.job.errorCode());
    assertFalse(f.job.retryable());
    assertEquals(accepted, f.receipts);
    assertTrue(f.saved().isEmpty());
    f.calls(0);
    verifyNoInteractions(f.artifacts);
    verify(f.store, never()).segment(any(), any());
    verify(f.store, never()).audio(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPEECH_CONFIGURATION_CHANGED", "SPEECH_CONFIGURATION_MISMATCH", "SPEECH_CONFIGURATION_UNVERIFIED"})
  void explicitResumeRejectsChangedSettingsBeforeRequeueAndAllowsRestoredSettings(String code) {
    var f = new Fixture();
    f.results(timeout(), timeout(), timeout());
    f.service.workOne();
    var failed = f.job;
    var attempts = f.saved();
    var receipts = List.copyOf(f.receipts);
    doThrow(new IllegalStateException(code))
        .when(f.speech).prepareGeneration(f.job.id());
    assertEquals(code, assertThrows(IllegalStateException.class,
        () -> f.service.retry(f.job.id())).getMessage());
    verify(f.store, never()).retry(any());
    assertEquals(failed, f.job);
    assertEquals(attempts, f.saved());
    assertEquals(receipts, f.receipts);
    doNothing().when(f.speech).prepareGeneration(f.job.id());
    f.service.retry(f.job.id());
    verify(f.store).retry(f.job.id());
    f.results(success());
    f.service.workOne();
    assertEquals(Status.succeeded, f.job.status());
    verify(f.speech, never()).render(eq(f.groups.getFirst()), any(), any());
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
  }

  @Test
  void nonTransientConfigurationFailureDoesNotRetry() throws Exception {
    var f = new Fixture();
    f.results(
        new Metadata(Map.of(), null, "PROVIDER_HTTP_400", false, 400, "invalid_request", null));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
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
            12L),
        success());
    f.service.workOne();
    f.calls(2);
    assertEquals(12L, f.saved().getFirst().nextRetryDelayMs());
    assertEquals(429, f.saved().getFirst().providerStatus());
    assertEquals(.015, f.saved().stream().mapToDouble(Attempt::estimatedCost).sum(), 1e-9);
  }

  @Test
  void retryAfterAboveWaitLimitDefersWithoutDiscardingUsageOrCheckpoints() throws Exception {
    var f = new Fixture();
    f.results(
        new Metadata(
            Map.of("inputTokens", 5L),
            .005,
            "PROVIDER_HTTP_429",
            true,
            429,
            "rate_limit_exceeded",
            120000L));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
    assertEquals("PROVIDER_HTTP_429", f.job.errorCode());
    assertTrue(f.job.retryable());
    assertEquals(1, f.receipts.size());
    assertEquals(.005, f.saved().getFirst().estimatedCost());
    assertEquals(Map.of("inputTokens", 5L), f.saved().getFirst().usage());
    assertNull(f.saved().getFirst().nextRetryDelayMs());
    f.service.retry(f.job.id());
    f.results(success());
    f.service.workOne();
    f.calls(2);
    assertEquals(Status.succeeded, f.job.status());
    verify(f.speech, never()).render(eq(f.groups.getFirst()), any(), any());
  }

  @Test
  void eachMissingAudioGroupHasItsOwnThreeAttemptLimit() {
    var f = new Fixture();
    f.receipts.clear();
    f.results(timeout(), timeout(), success(), timeout(), timeout(), success());
    f.service.workOne();
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(2, f.receipts.size());
    assertEquals(6, f.saved().size());
    assertEquals(
        List.of(1, 2, 3, 1, 2, 3),
        f.saved().stream().map(Attempt::attemptNumber).toList());
    for (Group group : f.groups)
      verify(f.speech, times(3)).render(eq(group), eq(f.job.id()), any());
  }

  @Test
  void completedGroupsNeverCallProvider() {
    var f = new Fixture();
    f.receipts.add(Fixture.receipt(f.groups.get(1)));
    f.service.workOne();
    verify(f.speech, never()).render(any(), any(), any());
    verify(f.text, never()).generate(any());
    assertEquals(Status.succeeded, f.job.status());
    verify(f.artifacts).assemble(eq(f.job.id()), eq(f.receipts),
        eq(new AudioMetadata("Book", "Chapter", null)));
  }

  @Test
  void interruptedBackoffRequiresExplicitResumeAndKeepsEarlierCheckpoints() throws Exception {
    var f = new Fixture();
    f.results(timeout());
    f.interruptBeforeRetry = true;
    f.service.workOne();
    Thread.interrupted();
    assertEquals(Status.failed, f.job.status());
    assertEquals("PROCESS_INTERRUPTED", f.job.errorCode());
    assertEquals(1, f.receipts.size());
    f.calls(1);
    f.interruptBeforeRetry = false;
    f.service.retry(f.job.id());
    f.results(success());
    f.service.workOne();
    f.calls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(2, f.receipts.size());
    verify(f.speech, never()).render(eq(f.groups.getFirst()), any(), any());
  }

  @Test
  void retryableStructuralAnomalyThenSuccess() throws Exception {
    var f = new Fixture();
    f.results(Metadata.error("UNEXPECTED_AUDIO_STRUCTURE", true), success());
    f.service.workOne();
    f.calls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(List.of("failed", "succeeded"), f.saved().stream().map(Attempt::outcome).toList());
    verify(f.store, times(1)).audio(any(), any());
  }

  @Test
  void threeStructuralAnomaliesStopAtLimit() throws Exception {
    var f = new Fixture();
    var anomaly = Metadata.error("UNEXPECTED_AUDIO_STRUCTURE", true);
    f.results(anomaly, anomaly, anomaly);
    f.service.workOne();
    f.calls(3);
    assertEquals(Status.failed, f.job.status());
    assertEquals("UNEXPECTED_AUDIO_STRUCTURE", f.job.errorCode());
    assertEquals(1, f.receipts.size());
  }

  @Test
  void unsupportedProviderSchemaIsNotAutomaticallyRetried() throws Exception {
    var f = new Fixture();
    f.results(Metadata.error("PROVIDER_SCHEMA_UNSUPPORTED", false));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
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
  }

  @Test
  void failedAttemptRecordingStopsBeforeAnotherPaidAttempt() {
    var f = new Fixture();
    f.results(timeout(), success());
    doThrow(new IllegalStateException("ATTEMPT_WRITE_FAILURE"))
        .when(f.store).attempt(any(), argThat(a -> a.outcome().equals("failed")));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
    assertEquals("ATTEMPT_WRITE_FAILURE", f.job.errorCode());
    assertEquals(1, f.receipts.size());
  }

  @Test
  void resourceLimitFailureIsNotRetriedOrAcceptedAsAnAudioCheckpoint() {
    var f = new Fixture();
    f.results(new Metadata(Map.of("outputTokens", 10L), .01, "PROVIDER_RESOURCE_LIMIT", false));
    f.service.workOne();
    f.calls(1);
    assertEquals(Status.failed, f.job.status());
    assertEquals("PROVIDER_RESOURCE_LIMIT", f.job.errorCode());
    assertFalse(f.job.retryable());
    assertEquals(1, f.receipts.size(), "the earlier checkpoint is preserved");
    verify(f.store, never()).audio(any(), any());
    verify(f.artifacts, never()).validate(any(), any(), any());
    assertEquals(.01, f.saved().getFirst().estimatedCost());
    assertEquals(Map.of("outputTokens", 10L), f.saved().getFirst().usage());
    assertNull(f.saved().getFirst().nextRetryDelayMs());
  }

  @Test
  void retryClassificationIsExplicit() {
    for (int status : List.of(400, 401, 403, 404, 422, 501))
      assertFalse(
          AudioRetryPolicy.transientFailure(
              new Metadata(Map.of(), null, "PROVIDER_HTTP_" + status, true, status, null, null)));
    assertFalse(AudioRetryPolicy.transientFailure(Metadata.error("PROVIDER_QUOTA", false)));
    assertTrue(
        AudioRetryPolicy.transientFailure(Metadata.error("UNEXPECTED_AUDIO_STRUCTURE", true)));
  }
}
