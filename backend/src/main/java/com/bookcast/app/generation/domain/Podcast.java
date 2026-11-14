package com.bookcast.app.generation.domain;

import java.util.*;

public final class Podcast {
  private Podcast() {}

  public enum Speaker {
    HOST,
    EXPERT
  }

  public enum Status {
    queued,
    running,
    succeeded,
    failed
  }

  /** One speaker's contribution; a candidate turn must contain nonblank spoken text. */
  @org.jmolecules.ddd.annotation.ValueObject
  public record DialogueTurn(Speaker speakerRole, String text) {
    public DialogueTurn {
      if (speakerRole == null || text == null || text.isBlank())
        throw new IllegalArgumentException("DIALOGUE_CONTENT");
    }
  }

  /**
   * Dialogue for an ordered source unit. Providers return this same value type as a candidate;
   * validation and insert-only persistence establish acceptance. Source IDs remain in source order.
   */
  public record DialogueSegment(
      int order, List<UUID> coveredSourceBlockIds, List<DialogueTurn> dialogueTurns) {
    public DialogueSegment {
      coveredSourceBlockIds = List.copyOf(coveredSourceBlockIds);
      dialogueTurns = List.copyOf(dialogueTurns);
      if (dialogueTurns.isEmpty()) throw new IllegalArgumentException("EMPTY_SCRIPT");
    }

    public void validate(int expected, List<UUID> ids) {
      if (order != expected || !coveredSourceBlockIds.equals(ids))
        throw new IllegalArgumentException("SOURCE_COVERAGE");
      if (dialogueTurns.stream().map(DialogueTurn::speakerRole).distinct().count() != 2)
        throw new IllegalArgumentException("BOTH_SPEAKERS_REQUIRED");
    }
  }

  /**
   * Dialogue-segment-local turn index and UTF-16 text offsets, with an exclusive end. The legacy JSON
   * property {@code turn} remains unchanged; offsets refer to the turn, not the combined chunk.
   */
  public record TurnSpan(int turn, int start, int end) {}

  /**
   * Planned speech for one speaker in one dialogue segment, before audio acceptance. The global
   * order, segment ordinal and contributing spans preserve the original dialogue. Legacy JSON
   * names {@code segment} and {@code slices} are retained for persisted checkpoints and diagnostics.
   */
  public record SpeechChunk(
      int order, int segment, Speaker speaker, String text, String textHash, List<TurnSpan> slices) {
    public SpeechChunk {
      slices = List.copyOf(slices);
    }
  }

  /** Accepted audio evidence. The persisted {@code group} property contains its planned chunk. */
  public record AudioReceipt(
      SpeechChunk group,
      String path,
      String hash,
      String transcript,
      boolean lexicalDeviation,
      double duration,
      double longestInternalSilence,
      double trailingSilence,
      double wordsPerMinute) {}

  /**
   * One provider call, not a whole pipeline run. Its ID survives pending/final outcome updates;
   * another call gets another ID. Available usage and nullable estimated cost survive failures.
   */
  public record ProviderAttempt(
      UUID id,
      String capability,
      String unitKey,
      String provider,
      String model,
      String outcome,
      long elapsedMs,
      Map<String, Long> usage,
      Double estimatedCost,
      boolean retryable,
      String errorCode,
      Integer attemptNumber,
      java.time.OffsetDateTime startedAt,
      java.time.OffsetDateTime endedAt,
      Boolean usageUnknown,
      String retryClassification,
      Integer providerStatus,
      String providerCode,
      Long nextRetryDelayMs) {
    public ProviderAttempt(
        UUID id,
        String capability,
        String unitKey,
        String provider,
        String model,
        String outcome,
        long elapsedMs,
        Map<String, Long> usage,
        Double estimatedCost,
        boolean retryable,
        String errorCode) {
      this(
          id,
          capability,
          unitKey,
          provider,
          model,
          outcome,
          elapsedMs,
          usage,
          estimatedCost,
          retryable,
          errorCode,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ProviderAttempt {
      usage = Map.copyOf(usage);
    }
  }

  /**
   * Snapshot of the overall pipeline's persisted lifecycle. The service coordinates execution;
   * accepted dialogue, audio and attempts are separate store lookups, not child collections here.
   */
  public record GenerationJob(
      UUID id,
      UUID chapterId,
      UUID sourceRevisionId,
      Status status,
      String stage,
      int totalSegments,
      int totalAudio,
      String generationModel,
      String audioModel,
      String errorCode,
      boolean retryable,
      String audioPath) {}

  public static void requireRetryable(GenerationJob job) {
    if (job.status() != Status.failed) throw new IllegalStateException("GENERATION_NOT_FAILED");
  }
}
