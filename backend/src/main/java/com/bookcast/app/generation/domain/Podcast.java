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

  @org.jmolecules.ddd.annotation.ValueObject
  public record Turn(Speaker speakerRole, String text) {
    public Turn {
      if (speakerRole == null || text == null || text.isBlank())
        throw new IllegalArgumentException("DIALOGUE_CONTENT");
    }
  }

  public record Segment(int order, List<UUID> coveredSourceBlockIds, List<Turn> dialogueTurns) {
    public Segment {
      coveredSourceBlockIds = List.copyOf(coveredSourceBlockIds);
      dialogueTurns = List.copyOf(dialogueTurns);
      if (dialogueTurns.isEmpty()) throw new IllegalArgumentException("EMPTY_SCRIPT");
    }

    public void validate(int expected, List<UUID> ids) {
      if (order != expected || !coveredSourceBlockIds.equals(ids))
        throw new IllegalArgumentException("SOURCE_COVERAGE");
      if (dialogueTurns.stream().map(Turn::speakerRole).distinct().count() != 2)
        throw new IllegalArgumentException("BOTH_SPEAKERS_REQUIRED");
    }
  }

  public record Slice(int turn, int start, int end) {}

  public record Group(
      int order, int segment, Speaker speaker, String text, String textHash, List<Slice> slices) {
    public Group {
      slices = List.copyOf(slices);
    }
  }

  public record AudioReceipt(
      Group group,
      String path,
      String hash,
      String transcript,
      boolean lexicalDeviation,
      double duration,
      double longestInternalSilence,
      double trailingSilence,
      double wordsPerMinute) {}

  public record Attempt(
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
    public Attempt {
      usage = Map.copyOf(usage);
    }
  }

  public record Job(
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

  public static void requireRetryable(Job job) {
    if (job.status() != Status.failed) throw new IllegalStateException("GENERATION_NOT_FAILED");
  }
}
