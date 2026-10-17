package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.*;
import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.ChapterSource;
import java.util.*;
import org.junit.jupiter.api.Test;

class DialogueRetryTest {
  static class Fixture extends AudioRetryTest.Fixture {
    final List<Segment> segments = new ArrayList<>();
    final Segment pending;

    Fixture(boolean completedFirst) {
      var original = store.segments(job.id()).getFirst();
      if (completedFirst) {
        segments.add(original);
        var source = library.revision(job.chapterId(), job.sourceRevisionId());
        UUID block = UUID.randomUUID();
        var blocks = new ArrayList<>(source.blocks());
        blocks.add(new ChapterSource.Block(block, 1, "word ".repeat(800), null));
        when(library.revision(any(), any()))
            .thenReturn(
                new ChapterSource(
                    source.id(),
                    source.bookId(),
                    source.bookTitle(),
                    source.title(),
                    source.sourceRevisionId(),
                    source.sourceLanguage(),
                    source.sourceHash(),
                    source.createdAt(),
                    blocks));
        pending = new Segment(1, List.of(block), original.dialogueTurns());
      } else {
        pending = original;
        receipts.clear();
      }
      when(store.segments(any())).thenAnswer(i -> List.copyOf(segments));
      doAnswer(
              i -> {
                segments.add(i.getArgument(1));
                return null;
              })
          .when(store)
          .segment(any(), any());
      when(speech.render(any(), any(), any()))
          .thenReturn(new AudioResult(new byte[] {1, 2}, "speech", AudioRetryTest.success()));
    }

    void textResults(Metadata... metadata) {
      var queue = new ArrayDeque<>(List.of(metadata));
      doAnswer(
              i -> {
                var m = queue.removeFirst();
                return new TextResult(m.errorCode() == null ? pending : null, m);
              })
          .when(text)
          .generate(any());
    }

    List<Attempt> dialogueAttempts() {
      return saved().stream().filter(a -> a.capability().equals("dialogue")).toList();
    }

    void textCalls(int expected) {
      verify(text, times(expected))
          .generate(
              argThat(
                  r ->
                      r.order() == pending.order()
                          && r.blocks().stream()
                              .map(ChapterSource.Block::id)
                              .toList()
                              .equals(pending.coveredSourceBlockIds())));
    }
  }

  @Test
  void timeoutThenSuccessPersistsAttemptsAndOneCheckpoint() throws Exception {
    var f = new Fixture(false);
    f.textResults(AudioRetryTest.timeout(), AudioRetryTest.success());
    f.service.workOne();
    f.textCalls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(
        List.of("failed", "succeeded"),
        f.dialogueAttempts().stream().map(Attempt::outcome).toList());
    assertEquals(List.of(1, 2), f.dialogueAttempts().stream().map(Attempt::attemptNumber).toList());
    verify(f.store, times(1)).segment(any(), any());
    verify(f.timing).pause(2000);
    var failed = f.dialogueAttempts().getFirst();
    assertTrue(failed.usageUnknown());
    assertNull(failed.estimatedCost());
    assertNotNull(failed.startedAt());
    assertNotNull(failed.endedAt());
    assertEquals("TRANSIENT", failed.retryClassification());
    assertEquals(2000L, failed.nextRetryDelayMs());
    assertFalse(f.dialogueAttempts().getLast().usageUnknown());
    assertEquals(.01, f.dialogueAttempts().getLast().estimatedCost());
  }

  @Test
  void twoTimeoutsThenSuccessUsesThreeAttempts() throws Exception {
    var f = new Fixture(false);
    f.textResults(AudioRetryTest.timeout(), AudioRetryTest.timeout(), AudioRetryTest.success());
    f.service.workOne();
    f.textCalls(3);
    assertEquals(Status.succeeded, f.job.status());
    verify(f.timing).pause(2000);
    verify(f.timing).pause(8000);
    assertEquals(1, f.segments.size());
  }

  @Test
  void thirdTransientFailureStopsWithoutFourthCall() throws Exception {
    var f = new Fixture(false);
    f.textResults(AudioRetryTest.timeout(), AudioRetryTest.timeout(), AudioRetryTest.timeout());
    f.service.workOne();
    f.textCalls(3);
    assertEquals(Status.failed, f.job.status());
    assertEquals(3, f.dialogueAttempts().size());
    assertNull(f.dialogueAttempts().getLast().nextRetryDelayMs());
    assertTrue(f.segments.isEmpty());
    verify(f.speech, never()).render(any(), any(), any());
    verify(f.timing, times(2)).pause(anyLong());
  }

  @Test
  void nonTransientErrorsFailImmediately() throws Exception {
    for (var m :
        List.of(
            Metadata.error("API_KEY_MISSING", false),
            new Metadata(Map.of(), null, "PROVIDER_HTTP_400", false, 400, "invalid_request", null),
            new Metadata(Map.of(), null, "PROVIDER_HTTP_401", false, 401, "invalid_api_key", null),
            new Metadata(Map.of(), null, "PROVIDER_QUOTA", false, 429, "insufficient_quota", null),
            Metadata.error("PROVIDER_OR_SCHEMA_FAILURE", false))) {
      var f = new Fixture(false);
      f.textResults(m);
      f.service.workOne();
      f.textCalls(1);
      assertEquals(Status.failed, f.job.status());
      verify(f.timing, never()).pause(anyLong());
    }
  }

  @Test
  void rateLimitRespectsRetryAfterAndRetainsFailedUsage() throws Exception {
    var f = new Fixture(false);
    f.textResults(
        new Metadata(
            Map.of("inputTokens", 50L),
            .005,
            "PROVIDER_HTTP_429",
            true,
            429,
            "rate_limit_exceeded",
            12000L),
        AudioRetryTest.success());
    f.service.workOne();
    f.textCalls(2);
    verify(f.timing).pause(12000);
    var failed = f.dialogueAttempts().getFirst();
    assertEquals(429, failed.providerStatus());
    assertEquals("rate_limit_exceeded", failed.providerCode());
    assertEquals(.005, failed.estimatedCost());
    assertFalse(failed.usageUnknown());
    assertEquals(
        .015, f.dialogueAttempts().stream().mapToDouble(a -> a.estimatedCost()).sum(), .000001);
  }

  @Test
  void existingSegmentIsNeverRegeneratedWhileMissingOneRetries() {
    var f = new Fixture(true);
    f.textResults(AudioRetryTest.timeout(), AudioRetryTest.success());
    f.service.workOne();
    f.textCalls(2);
    verify(f.text, never()).generate(argThat(r -> r.order() == 0));
    verify(f.speech, never()).render(eq(f.groups.getFirst()), any(), any());
    assertEquals(2, f.segments.size());
    assertEquals(Status.succeeded, f.job.status());
  }

  @Test
  void allCompletedDialogueMakesZeroTextCalls() {
    var f = new AudioRetryTest.Fixture();
    f.results(AudioRetryTest.success());
    f.service.workOne();
    verify(f.text, never()).generate(any());
  }

  @Test
  void interruptedBackoffNeedsManualResumeAndKeepsCheckpoints() throws Exception {
    var f = new Fixture(true);
    f.textResults(AudioRetryTest.timeout());
    doThrow(new InterruptedException()).when(f.timing).pause(anyLong());
    try {
      f.service.workOne();
    } finally {
      Thread.interrupted();
    }
    f.textCalls(1);
    assertEquals(Status.failed, f.job.status());
    assertEquals(1, f.segments.size());
    f.service.interrupted();
    verify(f.store).interrupted();
    f.textCalls(1);
    reset(f.timing);
    f.service.retry(f.job.id());
    f.textResults(AudioRetryTest.success());
    f.service.workOne();
    f.textCalls(2);
    assertEquals(Status.succeeded, f.job.status());
    assertEquals(List.of(1, 1), f.dialogueAttempts().stream().map(Attempt::attemptNumber).toList());
    verify(f.text, never()).generate(argThat(r -> r.order() == 0));
  }

  @Test
  void malformedDialogueAndUnexpectedAdapterExceptionAreNotRetried() throws Exception {
    var f = new Fixture(false);
    when(f.text.generate(any())).thenThrow(new IllegalArgumentException("private detail"));
    f.service.workOne();
    f.textCalls(1);
    assertEquals("DIALOGUE_ADAPTER_FAILURE", f.job.errorCode());
    verify(f.timing, never()).pause(anyLong());
    var g = new Fixture(false);
    when(g.text.generate(any()))
        .thenReturn(
            new TextResult(
                new Segment(99, g.pending.coveredSourceBlockIds(), g.pending.dialogueTurns()),
                AudioRetryTest.success()));
    g.service.workOne();
    g.textCalls(1);
    assertEquals("INVALID_DIALOGUE_OUTPUT", g.job.errorCode());
    verify(g.timing, never()).pause(anyLong());
  }

  @Test
  void classificationExcludesAudioAnomaliesAndIncludesFiveHundreds() {
    assertFalse(
        DialogueRetryPolicy.transientFailure(Metadata.error("UNEXPECTED_AUDIO_STRUCTURE", true)));
    for (int status : List.of(429, 500, 501, 502, 503, 504, 599))
      assertTrue(
          DialogueRetryPolicy.transientFailure(
              new Metadata(Map.of(), null, "PROVIDER_HTTP_" + status, true, status, null, null)));
    for (int status : List.of(400, 401, 403, 404, 422))
      assertFalse(
          DialogueRetryPolicy.transientFailure(
              new Metadata(Map.of(), null, "PROVIDER_HTTP_" + status, true, status, null, null)));
  }
}
